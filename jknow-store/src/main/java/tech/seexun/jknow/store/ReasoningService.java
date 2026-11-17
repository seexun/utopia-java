package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.reason.AxiomChecks;
import tech.seexun.jknow.reason.Axioms;
import tech.seexun.jknow.reason.Checked;
import tech.seexun.jknow.reason.Derive.TimedEdge;
import tech.seexun.jknow.reason.Edge;
import tech.seexun.jknow.reason.Kind;
import tech.seexun.jknow.reason.Ontology;
import tech.seexun.jknow.reason.Violation;
import tech.seexun.jknow.store.DerivedPoolProvider;
import tech.seexun.jknow.store.DerivedPoolProvider.Pool;
import tech.seexun.jknow.store.DerivedPoolProvider.RuleDisagreement;
import tech.seexun.jknow.store.mapper.ReasoningMapper;
import tech.seexun.jknow.store.mapper.ReasoningMapper.AcceptedRow;
import tech.seexun.jknow.store.mapper.ReasoningMapper.AxiomRow;
import tech.seexun.jknow.store.mapper.ReasoningMapper.Pair;
import tech.seexun.jknow.store.mapper.ReasoningMapper.StaleRow;
import tech.seexun.jknow.store.mapper.ReasoningMapper.TimedEdgeRow;
import tech.seexun.jknow.store.temporal.WorldTime;

/**
 * 一致性检查的取数与落库（reasoning.rs:1-1090 全族对等）。判断本身在
 * {@link AxiomChecks}（utopia-reason）——那一层不碰数据库。
 *
 * <p>三步：把活事实取成边、把 relation_types 的公理列取成 {@link Axioms}、把
 * {@code checkAll} 吐出来的违规写进 {@code axiom_violations}。
 *
 * <p><b>重跑是幂等的，而且以「重算」为准</b>。每次跑完，这个库里没被重新算出来的
 * open 行会被删掉——它们是派生状态，事实撤了、公理放宽了，那条违规就不该还挂在
 * Review 页上。已经有人表态的（resolved）一行不动：那是人的决定，不是算出来的东西。
 * ON CONFLICT 什么都不做——已经在库里的那一行，无论 open 还是 resolved，都按原样留着。
 *
 * <p><b>第六类（派生撞断言）与 rules_disagree</b>：断言边之外的部分经
 * {@link DerivedPoolProvider} 注入（任务 5 默认空池；任务 6 落 resolve() 后接真身——
 * 与 materialize 单一求解源，队列报的正是图拦下的）。
 */
@Service
public class ReasoningService {

    private final ReasoningMapper mapper;
    private final GraphWriteService graph;
    private final TransactionTemplate tx;
    /**
     * 派生池<b>懒拿</b>：真身（ReasoningDerivedPool）的构造链上有本类，构造期解析会成环
     * （BeanCurrentlyInCreation）——首次 detect() 时才取。没有实现 bean 时落空池。
     */
    private final org.springframework.beans.factory.ObjectProvider<DerivedPoolProvider> derivedPool;
    // volatile（审查 M-7）：并发首查最坏重复解析一次单例，无功能影响，但可见性要保证
    private volatile DerivedPoolProvider derivedPoolResolved;
    /** 求解器懒拿：ReasoningSolver 的构造参数里有本类，直接注入会成环。 */
    private final org.springframework.beans.factory.ObjectProvider<ReasoningSolver> solverProvider;
    /** 读侧的证据行经 GraphService 取（steps_for 逐条；lazy——直接构造时无读侧）。 */
    private final org.springframework.beans.factory.ObjectProvider<GraphService> graphRead;

    public ReasoningService(ReasoningMapper mapper, GraphWriteService graph,
                            PlatformTransactionManager txManager) {
        this(mapper, graph, txManager, null, null, null);
    }

    /**
     * Spring 装配用（唯一带 provider 的构造器）：派生池与求解器都经 {@link ObjectProvider}
     * 懒拿——任务 6 注册 resolve() 真身（ReasoningDerivedPool / ReasoningSolver）后自动
     * 接管，与 detect 共用同一次求解；三个 provider 都不急解，避开构造环。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReasoningService(ReasoningMapper mapper, GraphWriteService graph,
                            PlatformTransactionManager txManager,
                            org.springframework.beans.factory.ObjectProvider<DerivedPoolProvider> derivedPool,
                            org.springframework.beans.factory.ObjectProvider<ReasoningSolver> solverProvider,
                            org.springframework.beans.factory.ObjectProvider<GraphService> graphRead) {
        this.mapper = mapper;
        this.graph = graph;
        this.tx = new TransactionTemplate(txManager);
        this.derivedPool = derivedPool;
        this.derivedPoolResolved = derivedPool == null ? DerivedPoolProvider.EMPTY : null;
        this.solverProvider = solverProvider;
        this.graphRead = graphRead;
    }

    private DerivedPoolProvider pool() {
        if (derivedPoolResolved == null) {
            derivedPoolResolved = derivedPool.getIfAvailable(() -> DerivedPoolProvider.EMPTY);
        }
        return derivedPoolResolved;
    }

    private ReasoningSolver solver() {
        if (solverProvider == null) {
            throw new IllegalStateException("ReasoningSolver 未装配（直接构造的实例没有物化能力）");
        }
        return solverProvider.getObject();
    }

    /** 一次检查的产出，给调用方写审计与告诉用户（reasoning.rs:28-52）。 */
    public record Report(int edges, int predicatesWithAxioms, int found, int inserted, int cleared,
                         int contradictions, int contradictionsCapped, int rulesDisagree,
                         int reopened, int cyclesCapped) {

        static Report empty() {
            return new Report(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** 本体自洽性检查的产出（reasoning.rs:1003-1009）。 */
    public record OntologyReport(int classes, int found, int inserted, int cleared) {
    }

    /**
     * 一处违规在库里的身份（reasoning.rs:593-613）：与两条唯一索引同形状——环按整条
     * {@code path}，其余按 {@code (kind, left_fact, right_fact)}（0054）。
     *
     * <p><b>分档归一化</b>：非环种类的 path 存的是这一轮的证据（前提集/组员），
     * 检出重算时它可以变而行还是那一处违规——拿它分档会把「旧判据下确实检出」
     * 的行误认成「早就陈了」，从 criterion_changed 掉成硬删。所以对账键里非环行的
     * path 一律归成空。
     */
    public record ViolationKey(String kind, UUID left, UUID right, List<UUID> path) {
    }

    static ViolationKey partitionKey(String kind, UUID left, UUID right, List<UUID> path) {
        return new ViolationKey(kind, left, right, "cycle".equals(kind) ? path : List.of());
    }

    /** 一次检测的全部读侧产物（reasoning.rs:220-236）——只读不写，run 与本体事务共用。 */
    private record Detection(List<Violation> violations,
                             Map<UUID, Map<UUID, JsonNode>> details, List<UUID> cyclesCapped,
                             List<AcceptedGroup> accepted, List<RuleDisagreement> ruleClashes,
                             int edges, int predicatesWithAxioms, int contradictionsCapped) {
    }

    /** (种类, 组里的事实)——人认可过并存的互斥组。 */
    record AcceptedGroup(String kind, Set<UUID> facts) {
    }

    // ===================== 取数 =====================

    /**
     * 取这个库的谓词公理（reasoning.rs:63-136）。<b>只取声明了至少一位的</b>：一位都没
     * 声明的谓词进了表也不会被检查（saysNothing 会跳过），白白占内存；而且这个条数
     * 本身有意义——它是「有没有判据」的度量，报告里要用。
     *
     * <p><b>逆是相互的，而库里只存单向</b>。声明了 p⁻¹=q 却没回填 q⁻¹=p 的话，
     * {@code A p B} 推得出 {@code B q A}，{@code B q A} 却推不回 {@code A p B}
     * ——「问工作和问雇佣答案不同」正是 R1 要消灭的东西，只修一半等于没修。
     * 归一化放在这里而不是数据库触发器：绕过触发器的路不止一条（RDF 导入、直接改表），
     * 而载入公理只有这一处，谁也绕不过去。
     */
    public Map<UUID, Axioms> axioms(UUID kbId) {
        Map<UUID, Axioms> map = new LinkedHashMap<>();
        for (AxiomRow r : mapper.selectAxioms(kbId)) {
            map.put(r.id(), new Axioms(r.transitive(), r.symmetric(), r.asymmetric(),
                    r.irreflexive(), r.functional(), r.inverseFunctional(), r.inverseOf(),
                    r.subPropertyOf()));
        }
        List<Map.Entry<UUID, UUID>> pairs = new ArrayList<>();
        for (Map.Entry<UUID, Axioms> e : map.entrySet()) {
            if (e.getValue().inverseOf() != null) {
                pairs.add(Map.entry(e.getValue().inverseOf(), e.getKey()));
            }
        }
        for (Map.Entry<UUID, UUID> p : pairs) {
            // 已经声明了自己的逆就不动它——**人写的优先于推出来的**，
            // 两边指得不一样是本体自己的矛盾，交给 ontology 检查报，不在这里悄悄改
            Axioms target = map.get(p.getKey());
            if (target == null) {
                map.put(p.getKey(), new Axioms(false, false, false, false, false, false,
                        p.getValue(), null));
            } else if (target.inverseOf() == null) {
                map.put(p.getKey(), new Axioms(target.transitive(), target.symmetric(),
                        target.asymmetric(), target.irreflexive(), target.functional(),
                        target.inverseFunctional(), p.getValue(), target.subPropertyOf()));
            }
        }
        return map;
    }

    /**
     * 一条前提<b>读出来的</b>区间（reasoning.rs:1237-1287，0022）：原文没给起点就从锚点起，
     * 说结束了不知哪天就到锚点为止。两端都不知道的行读成空区间，求交时自然掉出去——
     * 它支撑不了任何派生。
     */
    public record ReadSpan(Long from, Long to, boolean fromAnchored, boolean toAnchored) {
    }

    public static ReadSpan readSpan(WorldTime.Temporal temporal, OffsetDateTime from,
                                    String fromPrecision, OffsetDateTime to, String toPrecision,
                                    OffsetDateTime attestedFrom, OffsetDateTime attestedTo) {
        // 恒常每一刻都成立，证据日期不闸它（0031）
        if (temporal == WorldTime.Temporal.ETERNAL) {
            return new ReadSpan(null, null, false, false);
        }
        // 事件在它命名的那个桶里成立；没日期的事件区间为空——overlap 对空交集不推，
        // 所以一条经过「不知何时收购」的链推不出东西，与读出侧一致（0031）。
        // 0031 之前写下的事件行终点是空的，按起点那个桶读
        if (temporal == WorldTime.Temporal.EVENT) {
            if (from == null) {
                // 没日期的事件区间为空。没有见证的（0064 决定 5）也一样：两端都落在
                // 读不到的远处，和任何区间都不相交
                long a = attestedFrom == null ? Long.MAX_VALUE / 4
                        : attestedFrom.toEpochSecond();
                return new ReadSpan(a, a, true, true);
            }
            String precision = toPrecision != null && !WorldTime.ENDED_UNKNOWN.equals(toPrecision)
                    ? toPrecision : fromPrecision;
            OffsetDateTime end = WorldTime.bucketEnd(to != null ? to : from, precision);
            return new ReadSpan(from.toEpochSecond(), end.toEpochSecond(), false, false);
        }
        Long f;
        boolean fromAnchored;
        if (from != null) {
            f = from.toEpochSecond();
            fromAnchored = false;
        } else if (attestedFrom != null) {
            // 没有起点：从见证起；连见证都没有的，下界开放——任何时点都成立（0064 决定 5）
            f = attestedFrom.toEpochSecond();
            fromAnchored = true;
        } else {
            f = null;
            fromAnchored = false;
        }
        Long t;
        boolean toAnchored;
        if (to != null) {
            t = to.toEpochSecond();
            toAnchored = false;
        } else if (WorldTime.ENDED_UNKNOWN.equals(toPrecision) && attestedTo != null) {
            // 结束未知的行按 CHECK 必带 attested_to；万一没有，按开放读——宁可多推一点，
            // 也不凭空造一个终点
            t = attestedTo.toEpochSecond();
            toAnchored = true;
        } else {
            t = null;
            toAnchored = false;
        }
        return new ReadSpan(f, t, fromAnchored, toAnchored);
    }

    /**
     * 活断言边（reasoning.rs:1289-1347）。输入<b>只有断言</b>——派生住在另一张表，
     * 所以这里连过滤都不必写。
     */
    public List<TimedEdge> timedEdges(UUID kbId) {
        List<TimedEdgeRow> rows = mapper.selectTimedEdgeRows(kbId);
        // 谓词的时间语义（0031）：事件按它的桶读，恒常两端开放。一次取全，按谓词查
        Map<UUID, WorldTime.Temporal> temporalOf = new HashMap<>();
        for (ReasoningMapper.TemporalRow r : mapper.selectTemporals(kbId)) {
            temporalOf.put(r.id(), WorldTime.Temporal.parse(r.temporal()));
        }
        List<TimedEdge> edges = new ArrayList<>(rows.size());
        for (TimedEdgeRow r : rows) {
            // 按读出来的区间推（0022）：没起点的前提从最早的证据起，结束了不知哪天的
            // 到说出它的那份文档为止。读成开放的话，一条经过 "former CEO" 的链会推出
            // 一条今天还成立的边
            WorldTime.Temporal temporal = temporalOf.getOrDefault(r.predicateId(),
                    WorldTime.Temporal.STATE);
            ReadSpan s = readSpan(temporal, r.validFrom(), r.validFromPrecision(), r.validTo(),
                    r.validToPrecision(), r.attestedFrom(), r.attestedTo());
            edges.add(new TimedEdge(
                    new Edge(r.id(), r.predicateId(), r.subjectId(), r.objectId()),
                    s.from(), s.to()));
        }
        return edges;
    }

    // ===================== 检测 =====================

    /**
     * 一次检测（reasoning.rs:238-351）＝断言边 + 公理 → {@link AxiomChecks#checkAll} +
     * 签名（SQL）+ 派生撞断言（{@link DerivedPoolProvider}，任务 5 空池）。跑在调用方
     * 的事务里（run 与本体对账事务各带一个）。
     */
    private Detection detect(UUID kbId) {
        List<TimedEdge> timed = timedEdges(kbId);
        Map<UUID, Axioms> axioms = axioms(kbId);
        Checked checked = AxiomChecks.checkAll(timed, axioms);
        List<Violation> violations = new ArrayList<>(checked.violations());
        // 第五类不在纯逻辑引擎里：它要看实体的类型与谓词的 domain / range，那是库里的
        // 东西。算出来后与其它四类走同一条落库与清陈规矩
        for (UUID fact : mapper.selectSignatureBreaks(kbId, null)) {
            violations.add(new Violation(Kind.SIGNATURE, fact, fact, List.of()));
        }
        // 第六类（0017）与 rules_disagree：派生侧——单一求解源（与物化共用），
        // 任务 5 默认空池
        Pool pool = pool().solve(kbId);
        Map<UUID, Map<UUID, JsonNode>> details = new HashMap<>();
        for (DerivedPoolProvider.Contradiction c : pool.contradictions()) {
            Violation v = c.violation();
            if (v.kind() != Kind.DERIVED_CONTRADICTION) {
                continue;
            }
            violations.add(v);
            details.computeIfAbsent(v.left(), k -> new HashMap<>()).put(v.right(), c.detail());
        }
        return new Detection(violations, details, checked.cyclesCapped(),
                acceptedGroups(kbId), pool.ruleClashes(), timed.size(), axioms.size(),
                pool.contradictionsCapped());
    }

    /**
     * 人认可过「这几条可以并存」，这一轮的组又全在那几条里——比如其中一条后来撤了，
     * 剩下的组首尾变了、键也变了——仍然是那句认可管着，不再端上来。
     * 组里有一条认可时没见过的，就不拦：那是新情况（reasoning.rs:356-361）。
     */
    static boolean suppressed(Violation v, List<AcceptedGroup> accepted) {
        return isGrouped(v.kind())
                && accepted.stream().anyMatch(g -> g.kind().equals(v.kind().asString())
                        && v.path().stream().allMatch(g.facts()::contains));
    }

    /** 互斥的三类：一处违规是一组同时成立、彼此冲突的事实（path 是整组）。 */
    static boolean isGrouped(Kind kind) {
        return kind == Kind.ASYMMETRY || kind == Kind.FUNCTIONAL
                || kind == Kind.INVERSE_FUNCTIONAL;
    }

    /** 人认可过并存的互斥组（reasoning.rs:700-722）：按事实集合比，不按键比（#624）。 */
    private List<AcceptedGroup> acceptedGroups(UUID kbId) {
        List<AcceptedGroup> out = new ArrayList<>();
        for (AcceptedRow r : mapper.selectAcceptedGroups(kbId)) {
            Set<UUID> facts = new HashSet<>(r.path());
            // 首尾本来就在 path 里；0053 之前记下的一对 path 是空的，靠这两列
            facts.add(r.leftFact());
            facts.add(r.rightFact());
            out.add(new AcceptedGroup(r.kind(), facts));
        }
        return out;
    }

    // ===================== 落库 =====================

    /**
     * 一轮检测的落库侧（reasoning.rs:366-478）：违规按行键 upsert（证据刷成这一轮的，
     * #619），resolved 的破约行重开；<b>没算出来的 open 行不在这一层处置</b>——run 删掉
     * 它们，本体事务里的对账把它们分档（0062）。返回「这一轮仍成立」的行 id 与计数。
     */
    private SettleResult settleViolations(UUID kbId, Detection det) {
        List<UUID> fresh = new ArrayList<>(det.violations().size());
        int inserted = 0;
        int reopened = 0;
        for (Violation v : det.violations()) {
            if (suppressed(v, det.accepted())) {
                continue;
            }
            JsonNode detail = det.details().getOrDefault(v.left(), Map.of()).get(v.right());
            if (detail == null) {
                detail = JsonNodeFactory.instance.objectNode();
            }
            ReasoningMapper.UpsertRow row = v.kind() == Kind.CYCLE
                    ? mapper.upsertViolationCycle(Uuids.nowV7(), kbId, v.kind().asString(),
                            v.left(), v.right(), v.path(), detail)
                    : mapper.upsertViolationGrouped(Uuids.nowV7(), kbId, v.kind().asString(),
                            v.left(), v.right(), v.path(), detail);
            if (row.fresh()) {
                inserted++;
            }
            // 本来就在而且 resolved 的要再看一眼：fact_retracted / fact_closed /
            // axiom_relaxed / criterion_changed 都是「世界会变」的承诺——事实没了、
            // 区间闭了、判据换了，违规就不该再算出来。又算出来了，承诺就是没兑现，
            // 那行回到 open，人再看一次。accepted 是有意并存，重算多少次都沉默
            // （#202）——只要还是认可时那几条。互斥的组走到这里说明上面没拦住：
            // 同一个键下多了一条，那行也回到 open
            boolean grouped = isGrouped(v.kind());
            boolean broken = switch (row.resolution() == null ? "" : row.resolution()) {
                case "fact_retracted", "fact_closed", "axiom_relaxed", "criterion_changed" ->
                    true;
                case "accepted" -> grouped;
                default -> false;
            };
            if ("resolved".equals(row.status()) && broken) {
                mapper.reopenViolation(row.id());
                reopened++;
            }
            fresh.add(row.id());
        }
        // 环没搜完的谓词，上一轮的 open 环这一轮不清（#642）：没搜到不等于不存在。
        // 撞上上限时报出的是按数据排定的那一批，数据不变就是同一批；数据变了，旧的那几行
        // 也宁可留着等人看，不能因为这一轮搜不到就当它没了
        if (!det.cyclesCapped().isEmpty()) {
            fresh.addAll(mapper.selectKeptCycles(kbId, det.cyclesCapped()));
        }
        return new SettleResult(fresh, inserted, reopened);
    }

    record SettleResult(List<UUID> fresh, int inserted, int reopened) {
    }

    /**
     * 派生之间互撞的按规则对进 {@code ontology_defects}——根子是那两条声明，不是哪条
     * 事实。同一对谓词上可能有几种撞法（functional 与 asymmetric 各撞各的），唯一键只到
     * 谓词对，所以合成一行，几种撞法都写进 detail（reasoning.rs:483-553）。
     */
    private void settleDefects(UUID kbId, Detection det) {
        // 不做空判提前返回（审查 I-1）：Rust（reasoning.rs:483-553）末尾无条件清陈
        // rules_disagree——零撞法的那一轮要拿空集清掉全部陈旧 open 行（"从有撞到无撞"
        // 的那轮也得清）；提前返回会让旧行永远挂着
        // 谓词标签：detail 要写人读的话（reasoning.rs:780-782 names_for 的谓词侧）
        Set<UUID> wantLabels = new HashSet<>();
        for (RuleDisagreement rc : det.ruleClashes()) {
            wantLabels.add(rc.viaA());
            wantLabels.add(rc.viaB());
        }
        Map<UUID, String> labels = new HashMap<>();
        for (ReasoningMapper.LabelRow r : mapper.selectPredicateLabels(List.copyOf(wantLabels))) {
            labels.put(r.id(), r.label());
        }
        // 同一对谓词聚合，保持遇到顺序
        Map<List<UUID>, List<ObjectNode>> byPair = new LinkedHashMap<>();
        Map<List<UUID>, Integer> counts = new LinkedHashMap<>();
        for (RuleDisagreement rc : det.ruleClashes()) {
            List<UUID> key = List.of(rc.viaA(), rc.viaB());
            ObjectNode rule = JsonNodeFactory.instance.objectNode();
            rule.put("rule_a", rc.ruleA());
            rule.put("via_a", labels.getOrDefault(rc.viaA(), "?"));
            rule.put("rule_b", rc.ruleB());
            rule.put("via_b", labels.getOrDefault(rc.viaB(), "?"));
            rule.put("axiom", rc.axiom());
            rule.put("count", rc.pairs());
            rule.set("examples", examplesNode(rc.examples()));
            byPair.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
            counts.merge(key, rc.pairs(), Integer::sum);
        }
        List<UUID> freshDefects = new ArrayList<>(byPair.size());
        for (Map.Entry<List<UUID>, List<ObjectNode>> e : byPair.entrySet()) {
            ObjectNode detail = JsonNodeFactory.instance.objectNode();
            detail.put("count", counts.get(e.getKey()));
            detail.set("rules", JsonNodeFactory.instance.arrayNode()
                    .addAll(e.getValue().stream().map(n -> (JsonNode) n).toList()));
            freshDefects.add(mapper.upsertRulesDisagree(Uuids.nowV7(), kbId, e.getKey().get(0),
                    e.getKey().get(1), detail));
        }
        mapper.deleteStaleRulesDisagree(kbId, freshDefects);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode examplesNode(
            List<String[]> examples) {
        com.fasterxml.jackson.databind.node.ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (String[] pair : examples) {
            com.fasterxml.jackson.databind.node.ArrayNode two = out.addArray();
            two.add(pair[0]);
            two.add(pair[1]);
        }
        return out;
    }

    // ===================== 入口 =====================

    /**
     * 跑一遍检查，把结果落库（reasoning.rs:556-591）。检测与落库进同一条事务：
     * 写侧没有「插新的」与「清陈旧的」之间的窗口。
     */
    public Report run(UUID kbId) {
        return tx.execute(status -> {
            Detection det = detect(kbId);
            SettleResult settled = settleViolations(kbId, det);
            // 这一轮没算出来的 open 行是陈的：事实被撤了，或者公理放宽了。
            // resolved 的不动——那是人的决定，不是派生状态
            int cleared = mapper.deleteStaleOpenViolations(kbId, settled.fresh());
            settleDefects(kbId, det);
            // 审查 M-2：contradictions 数 (left,right) 对（details 的外层键是 left，
            // 一条 left 可以撞多派生）——与 Rust det.details.len() 的扁平键同一口径
            int contradictionPairs = 0;
            for (Map<UUID, JsonNode> inner : det.details().values()) {
                contradictionPairs += inner.size();
            }
            return new Report(det.edges(), det.predicatesWithAxioms(), det.violations().size(),
                    settled.inserted(), cleared, contradictionPairs, det.contradictionsCapped(),
                    det.ruleClashes().size(), settled.reopened(), det.cyclesCapped().size());
        });
    }

    /**
     * 当前本体下<b>会算出来</b>的违规键集（0062，reasoning.rs:619-630）。不改违规状态。
     * 改判据的那条事务在动笔之前调它，拿到的就是旧本体下的基线。
     */
    public Set<ViolationKey> detectionKeys(UUID kbId) {
        Detection det = detect(kbId);
        Set<ViolationKey> out = new HashSet<>();
        for (Violation v : det.violations()) {
            if (!suppressed(v, det.accepted())) {
                out.add(partitionKey(v.kind().asString(), v.left(), v.right(), v.path()));
            }
        }
        return out;
    }

    /**
     * 判据在同一条事务里刚被改过（0062，reasoning.rs:643-686）：按新本体重新检出、落库，
     * 然后给陈旧的 open 行一个诚实的下场——
     * <ul>
     *   <li>旧基线里有的：它的判据在这次改动里被端掉了，以 criterion_changed 收场。
     *       不是人裁的（decided_by 空），是公理/签名变了；下次又被算出来时重开规矩
     *       与 axiom_relaxed 同一支</li>
     *   <li>旧基线里没有的：它在改动前就已经陈了（事实没了之类），照旧删掉，与
     *       run 同一条清陈规矩</li>
     * </ul>
     * 保住的不变式：{@code status='open'} 的一行必然已对账于<b>当前</b>本体。
     * 返回这一轮检出的违规数（与 run 的 report.found 同一口径）。
     */
    public int reconcileOntology(UUID kbId, Set<ViolationKey> wasDetected) {
        Detection det = detect(kbId);
        SettleResult settled = settleViolations(kbId, det);
        List<UUID> disqualified = new ArrayList<>();
        List<UUID> evaporated = new ArrayList<>();
        for (StaleRow stale : mapper.selectOpenViolationsNotIn(kbId, settled.fresh())) {
            if (wasDetected.contains(partitionKey(stale.kind(), stale.leftFact(),
                    stale.rightFact(), stale.path()))) {
                disqualified.add(stale.id());
            } else {
                evaporated.add(stale.id());
            }
        }
        if (!disqualified.isEmpty()) {
            mapper.markCriterionChanged(disqualified);
        }
        if (!evaporated.isEmpty()) {
            mapper.deleteViolations(evaporated);
        }
        settleDefects(kbId, det);
        return det.violations().size();
    }

    // ===================== 解除/裁决 =====================

    /**
     * 一处违规里该撤哪条事实（reasoning.rs:925-940）。单事实的种类（自环、签名、派生撞
     * 断言）只有一条，不用说；双事实与环上的要人指名，而且<b>只能指违规自己列出的那
     * 几条</b>——撤一条不相干的事实不是裁决，是误操作。
     */
    public static UUID pickRetraction(UUID left, UUID right, List<UUID> path,
                                      UUID requested) {
        if (Objects.equals(left, right)) {
            if (requested == null || requested.equals(left)) {
                return left;
            }
            return null;
        }
        if (requested == null) {
            return null;
        }
        return requested.equals(left) || requested.equals(right) || path.contains(requested)
                ? requested : null;
    }

    /**
     * 「数据错了」：<b>真的撤掉那条事实</b>，再把违规标成 resolved（#202，
     * reasoning.rs:947-974）。撤走的是 reject_fact 那条路——invalidated_at，证据不动，
     * 账本留痕。回撤掉的那条 id，调用方据此记审计。
     */
    public UUID retractFromViolation(UUID kbId, UUID violationId, UUID requested, UUID actor) {
        StaleRow row = mapper.selectViolationForRetract(violationId, kbId);
        if (row == null) {
            throw AppException.NOT_FOUND;
        }
        UUID target = pickRetraction(row.leftFact(), row.rightFact(), row.path(), requested);
        if (target == null) {
            throw AppException.invalid("fact_required",
                    "这处违规涉及多条事实，要说撤哪一条，且只能是它列出的那几条");
        }
        graph.rejectFact(kbId, target);
        decide(kbId, violationId, "fact_retracted", actor);
        return target;
    }

    /**
     * 人裁决一处违规（reasoning.rs:976-998）。<b>四个出路，不是两个</b>。时态冲突问
     * 「哪条对」，而这里可能是定义错了——用户导的本体把某个属性声明成反对称，而他
     * 自己的语料里那关系其实双向。axiom_relaxed 记的就是这种：该改的是本体，不是
     * 二十条事实。改状态不删行，与账本同一个规矩：表过态这件事本身要留痕，而且
     * run 靠 status='open' 判断哪些是派生的、可以重算掉——人的决定必须活过重跑。
     */
    public void decide(UUID kbId, UUID violationId, String resolution, UUID actor) {
        if (mapper.decideViolation(kbId, violationId, resolution, actor) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /**
     * reasoning.rs:2742-2764 {@code decide_defect}：人对一处本体缺陷表态。
     * <b>两个出路而不是三个</b>：本体缺陷压根没看数据，所以没有「数据错了」这一条——
     * {@code fixed} 是「我去改了本体」，{@code accepted} 是「看过，不必改」。0 行（不
     * 存在/别的库/已表过态）→ NotFound，由路由转 404。
     */
    public void decideDefect(UUID kbId, UUID defectId, String resolution, UUID actor) {
        if (mapper.decideDefect(kbId, defectId, resolution, actor) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    // ===================== 签名（#190 / #196） =====================

    /**
     * 主语不在谓词声明的 domain 里、或宾语不在 range 里的活事实（#190 / #196，
     * reasoning.rs:149-188）。签名检查在<b>账本层</b>：对齐只给结构对得上的候选（0053），
     * 但合并会换掉主语、本体会事后改 domain，写入时的把关挡不住写入之后的改动。
     * 所以这里对着库量一遍，任何一条路写反了都在 Review 里看得见。
     */
    public List<UUID> signatureBreaks(UUID kbId, List<UUID> only) {
        return mapper.selectSignatureBreaks(kbId, only);
    }

    /**
     * 把签名违规落进 {@code axiom_violations}（kind=signature，left 与 right 同一条事实，
     * reasoning.rs:192-215）。幂等：同一条事实重复报不重复入库。返回新插入的条数。
     */
    public int recordSignatureBreaks(UUID kbId, List<UUID> facts) {
        int inserted = 0;
        for (UUID fact : facts) {
            if (mapper.insertSignatureBreak(Uuids.nowV7(), kbId, fact) != null) {
                inserted++;
            }
        }
        return inserted;
    }

    // ===================== 推理物化与读侧（M3 任务 6） =====================

    /**
     * 推一遍，把派生事实落进账本（reasoning.rs:2409-2694）。求解本体在
     * {@link ReasoningSolver}——与 {@link #detect}（经 {@link DerivedPoolProvider} 真身）
     * 共用同一次求解，单一求解源不变量（recon 附录第 8 条）。
     */
    public ReasoningSolver.DeriveReport materialize(UUID kbId) {
        return solver().materialize(kbId);
    }

    /** 一次求解的公开入口（预览 / 读侧复用；reasoning.rs:2089 resolve）。 */
    public ReasoningSolver.Resolved solve(UUID kbId) {
        return solver().resolve(kbId);
    }

    /** 一条派生的证明（models.rs Proof serde 形状）：结论 + 按推导顺序展开的前提步。 */
    public record Proof(ReasoningMapper.DerivedFactView derived, List<ProofStep> steps) {
    }

    /**
     * 证明的一步（models.rs ProofStep serde 形状）。断言步带证据（叶子是原句）；
     * 派生步的「证据」是它自己那一层前提（premises，最多再往下 MAX_DEPTH 层）。
     */
    public static final class ProofStep {
        public int seq;
        public UUID factId;
        public boolean derived;
        public UUID subjectId;
        public String subject;
        public UUID predicateId;
        public String predicate;
        public UUID objectId;
        public String object;
        public OffsetDateTime validFrom;
        public OffsetDateTime validTo;
        public float confidence;
        public boolean retracted;
        public List<tech.seexun.jknow.store.entity.EvidenceRow> evidence = List.of();
        public List<ProofStep> premises = List.of();

        ProofStep(int seq, UUID factId, boolean derived, UUID subjectId, String subject,
                  UUID predicateId, String predicate, UUID objectId, String object,
                  OffsetDateTime validFrom, OffsetDateTime validTo, float confidence,
                  boolean retracted) {
            this.seq = seq;
            this.factId = factId;
            this.derived = derived;
            this.subjectId = subjectId;
            this.subject = subject;
            this.predicateId = predicateId;
            this.predicate = predicate;
            this.objectId = objectId;
            this.object = object;
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.confidence = confidence;
            this.retracted = retracted;
        }
    }

    /**
     * 一条派生事实的证明（reasoning.rs:2802-2812）：顺着 fact_derivations 一层层问下去
     * 就是完整的证明。撤了的前提照样列出并打上标记——「当时靠的是什么」要读得出来，
     * 那正是记录轴存在的理由。派生已失效或不存在时回 null（不是错误，界面据此收起）。
     */
    public Proof proof(UUID kbId, UUID derivedId) {
        ReasoningMapper.DerivedFactView derived = mapper.selectDerivedOne(kbId, derivedId);
        if (derived == null) {
            return null;
        }
        return new Proof(derived, premiseSteps(derivedId, 0));
    }

    /**
     * 一条派生的直接前提，派生的那几步再往下展开一层（reasoning.rs:2818-2866）。
     * 深度上限与推理是同一条 MAX_DEPTH：链最长这么长，证明也就最深这么深。
     */
    private List<ProofStep> premiseSteps(UUID derivedId, int depth) {
        List<ReasoningMapper.PremiseRow> rows = mapper.selectPremiseRows(derivedId);
        // 断言那几步一次取完（每一步还要各取一次证据），派生那几步逐条问
        List<UUID> asserted = rows.stream().map(ReasoningMapper.PremiseRow::premiseFactId)
                .filter(Objects::nonNull).toList();
        Map<UUID, ProofStep> byFact = new HashMap<>();
        for (ProofStep step : stepsFor(asserted)) {
            byFact.put(step.factId, step);
        }
        List<ProofStep> steps = new ArrayList<>(rows.size());
        for (ReasoningMapper.PremiseRow row : rows) {
            if (row.premiseFactId() != null) {
                ProofStep step = byFact.remove(row.premiseFactId());
                if (step == null) {
                    continue;
                }
                step.seq = row.seq();
                steps.add(step);
            } else if (row.premiseDerivedId() != null) {
                ReasoningMapper.DerivedStepRow r = mapper.selectDerivedStep(row.premiseDerivedId());
                if (r == null) {
                    continue;
                }
                ProofStep step = new ProofStep(row.seq(), r.factId(), true, r.subjectId(),
                        r.subject(), r.predicateId(), r.predicate(), r.objectId(), r.object(),
                        r.validFrom(), r.validTo(), r.confidence(), r.retracted());
                if (depth + 1 < AxiomChecks.MAX_DEPTH) {
                    step.premises = premiseSteps(row.premiseDerivedId(), depth + 1);
                }
                steps.add(step);
            }
        }
        return steps;
    }

    /**
     * 一串前提展开成证明的步（reasoning.rs:2945-3015）：三元组、区间、撤没撤、证据。
     * 落了地的派生（fact_derivations）与没落地的（axiom_violations.path）都从这里走。
     */
    public List<ProofStep> stepsFor(List<UUID> premises) {
        List<ProofStep> steps = new ArrayList<>();
        for (ReasoningMapper.ProofStepRow r : mapper.selectStepsFor(premises)) {
            // 一条链最多 MAX_DEPTH 步，逐条取证据是可数的几次往返
            ProofStep step = new ProofStep((int) r.seq(), r.factId(), false, r.subjectId(),
                    r.subject(), r.predicateId(), r.predicate(), r.objectId(), r.object(),
                    r.validFrom(), r.validTo(), r.confidence(), r.retracted());
            step.evidence = graphRead.getObject().factEvidence(r.factId());
            steps.add(step);
        }
        return steps;
    }

    /** 没落地的派生里，与这个实体有关、当时还开着的那些（reasoning.rs:3026-3063）。 */
    public List<Map<String, Object>> blockedForEntity(UUID kbId, UUID entityId,
                                                      OffsetDateTime asOf) {
        return mapper.selectBlockedForEntity(kbId, entityId, asOf);
    }

    /** 没落地的派生的证明链（reasoning.rs:3066-3083）：前提在那条违规的 path 里。 */
    public List<ProofStep> blockedProof(UUID kbId, UUID violationId) {
        if (mapper.selectViolationExists(violationId, kbId) == null) {
            return null;
        }
        return stepsFor(mapper.selectViolationPath(violationId, kbId));
    }

    /** 一条派生事实，配好展示与证明所需的文本（reasoning.rs:3141-3209，实体面板档）。 */
    public List<ReasoningMapper.DerivedFactView> derivedForEntity(UUID kbId, UUID entityId,
                                                                  OffsetDateTime at,
                                                                  OffsetDateTime asOf) {
        return mapper.selectDerivedForEntity(kbId, entityId, at, asOf);
    }

    /** 到点该重推的库（reasoning.rs:2770-2779；从没推过的算到期）。 */
    public List<UUID> dueForInference() {
        return mapper.selectDueForInference();
    }

    /** 记下这一轮推完的时间（reasoning.rs:2786-2792）——推完就记，哪怕什么都没变。 */
    public void markInferenceRan(UUID kbId) {
        mapper.markInferenceRan(kbId);
    }

    // ===================== R0 的另一半：本体自己 =====================

    /**
     * 量一遍本体自己（reasoning.rs:1015-1090）：谓词的公理组合、subClassOf 的环、
     * 不可满足的类。与 {@link #run} 同一套重跑规矩：open 是派生状态、可以被重算掉，
     * resolved 是人的决定、一行不动。
     */
    public OntologyReport checkOntology(UUID kbId) {
        Map<UUID, Axioms> ax = axioms(kbId);
        List<Ontology.ParentLink> parents = new ArrayList<>();
        for (Pair p : mapper.selectParents(kbId)) {
            parents.add(new Ontology.ParentLink(p.a(), p.b()));
        }
        List<Ontology.DisjointPair> disjoint = new ArrayList<>();
        for (Pair p : mapper.selectDisjoint(kbId)) {
            disjoint.add(new Ontology.DisjointPair(p.a(), p.b()));
        }
        long classes = mapper.countClasses(kbId);
        List<Ontology.OntologyDefect> defects = Ontology.checkOntology(ax, parents, disjoint);
        record Acc(int classes, int found, int inserted, int cleared, List<UUID> fresh) {
        }
        Acc acc = tx.execute(status -> {
            List<UUID> fresh = new ArrayList<>(defects.size());
            int inserted = 0;
            for (Ontology.OntologyDefect d : defects) {
                UUID existing = mapper.selectDefectId(kbId, d.kind().asString(), d.subject(),
                        d.other());
                UUID id;
                if (existing != null) {
                    id = existing;
                } else {
                    id = Uuids.nowV7();
                    mapper.insertDefect(id, kbId, d.kind().asString(), d.subject(), d.other(),
                            d.path());
                    inserted++;
                }
                fresh.add(id);
            }
            int cleared = mapper.deleteStaleDefects(kbId, fresh);
            return new Acc((int) classes, defects.size(), inserted, cleared, fresh);
        });
        return new OntologyReport(acc.classes(), acc.found(), acc.inserted(), acc.cleared());
    }
}
