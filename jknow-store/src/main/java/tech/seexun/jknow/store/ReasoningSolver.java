package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.reason.AxiomChecks;
import tech.seexun.jknow.reason.Axioms;
import tech.seexun.jknow.reason.Derive;
import tech.seexun.jknow.reason.Derive.Derivation;
import tech.seexun.jknow.reason.Derive.Derived;
import tech.seexun.jknow.reason.Derive.Rule;
import tech.seexun.jknow.reason.Derive.Span;
import tech.seexun.jknow.reason.Derive.TimedEdge;
import tech.seexun.jknow.reason.Edge;
import tech.seexun.jknow.reason.Expressions;
import tech.seexun.jknow.reason.Expressions.Expr;
import tech.seexun.jknow.reason.Rules;
import tech.seexun.jknow.reason.Rules.AttrFact;
import tech.seexun.jknow.reason.Rules.BusinessRule;
import tech.seexun.jknow.reason.Rules.Condition;
import tech.seexun.jknow.reason.Rules.Conclusion;
import tech.seexun.jknow.reason.Rules.Op;
import tech.seexun.jknow.reason.Rules.Operand;
import tech.seexun.jknow.reason.Rules.RuleEdge;
import tech.seexun.jknow.reason.Rules.Side;
import tech.seexun.jknow.store.ReasoningService.ReadSpan;
import tech.seexun.jknow.store.mapper.ReasoningMapper;
import tech.seexun.jknow.store.mapper.ReasoningMapper.AttrFactRow;
import tech.seexun.jknow.store.mapper.ReasoningMapper.TimedEdgeRow;
import tech.seexun.jknow.store.temporal.WorldTime;
import tech.seexun.jknow.store.temporal.WorldTime.Temporal;

/**
 * 一次求解（reasoning.rs:2089-2407 {@code resolve}）与落库对账（:2409-2694
 * {@code materialize}）——run（审核队列）与 materialize（落库）<b>共用同一次求解</b>：
 * 候选里有规则推出的关系边，被拦下的也在——队列报的正是图拦下的（0017/0047 决定 3）。
 *
 * <p>输入只有断言（0013 分表买到的性质）：输入侧一行 {@code derived_facts} 都不读，
 * 反馈全在内存池子里，一次物化仍然是 (facts, rules, axioms) 的纯函数。
 *
 * <p>第二趟（业务规则，0021/0030/0047）的结论<b>并进同一个 {@code wanted}</b>——
 * 陈旧行的对账扫的是整张 {@code derived_facts}，两趟各做各的 diff 会把对方落的行
 * 每一轮都判成陈旧作废掉。
 */
@Service
public class ReasoningSolver {

    /** 0017 §1：单谓词进审核队列的上限——超出的只计数，不为零说明根子在规则。 */
    public static final int MAX_CLASHES_PER_PREDICATE = 50;

    private final ReasoningMapper mapper;
    private final ReasoningService reasoning;
    private final TransactionTemplate tx;

    public ReasoningSolver(ReasoningMapper mapper, ReasoningService reasoning,
                           PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.reasoning = reasoning;
        this.tx = new TransactionTemplate(txManager);
    }

    // ===================== 对外形状 =====================

    /** 一条派生从它的前提上得到的精度与置信度（0022/0024，reasoning.rs:1220-1225）。 */
    public record PremiseMeta(String fromPrecision, String toPrecision, float confidence,
                              boolean fromAnchored, boolean toAnchored) {
    }

    /**
     * 一次物化的报告（reasoning.rs:1093-1136）。每个字段都有「得报出来」的理由——
     * unruled 尤其：曾经是一句 {@code continue}，把 {@code ceo_of ⊑ works_at} 推出来的
     * 那条 works_at 静默丢掉，下游靠它推出的 employs 反倒进了库。
     */
    public record DeriveReport(int rules, int edges, int derived, int inserted, int invalidated,
                               int capped, int unruled, int blocked, int attributeRules,
                               int ruleHits, int ruleCapped, int ruleRounds,
                               boolean ruleRoundsCapped, int reproved, int redefined) {
    }

    /** 派生事实的身份：主 + 谓 + 宾 + 值 + 区间。<b>区间进键</b>——区间变了就是另一条。 */
    record DerivedKey(UUID subject, UUID predicate, UUID objectId, String valueKey, Long from,
                      Long to) {
    }

    /** 这一轮要落库的一条派生（公理的与规则的在这里合流——合流是必须的）。 */
    record Wanted(UUID subject, UUID predicate, UUID objectId, JsonNode objectValue, Long from,
                  Long to, List<UUID> premises, UUID ruleId, UUID attributeRuleId,
                  UUID attributeRuleVersionId) {
    }

    /** 一条业务规则推出的关系边在不动点里的身份（0047 决定 3）；refused 可变。 */
    static final class RelationCandidate {
        final RuleEdge edge;
        final DerivedKey key;
        final List<UUID> premises;
        final UUID ruleId;
        boolean refused;

        RelationCandidate(RuleEdge edge, DerivedKey key, List<UUID> premises, UUID ruleId) {
            this.edge = edge;
            this.key = key;
            this.premises = premises;
            this.ruleId = ruleId;
        }
    }

    /** 一遍检查的产出：池子上的公理派生 + 规则推出的关系候选，和它们撞了什么。 */
    public record CheckedPool(Derivation candidates, int axiomCount,
                              Map<Integer, UUID> candidateRule, Derive.Contradictions clashes,
                              Set<Integer> blocked) {
    }

    /** 一条业务规则连同它的条件，已经解析成求值器的形状（reasoning.rs:1533-1546）。 */
    record LoadedRule(BusinessRule rule, List<UUID> subjectTypes, List<String> subjectClasses,
                      UUID concludePredicate, UUID version) {
    }

    /** 编译出来的一批规则 + 接链要用的两样东西（0030）。 */
    record LoadedRules(List<LoadedRule> rules, UUID isA, Map<String, UUID> classIds) {
    }

    /** resolve() 的全部产出（reasoning.rs:2066-2087）。 */
    public final class Resolved {
        final List<TimedEdge> edges;
        final Map<UUID, Span> spans;
        final Map<UUID, PremiseMeta> meta;
        final Map<UUID, Axioms> axioms;
        /** 公理规则行：(声明所在的谓词, 种类) → 规则 id。键字面量 = uuid + "|" + kind。 */
        final Map<String, UUID> rules;
        /** facts 里的行：边与属性事实（审核队列的键与外键只认这些）。 */
        final Set<UUID> assertedIds;
        CheckedPool checked;
        final Map<DerivedKey, Wanted> wanted;
        /** 一条规则结论的临时 id → 它最后落在哪一行（键是派生键，值是临时 id）。 */
        final Map<DerivedKey, UUID> provisional;
        final LoadedRules loaded;
        final Map<UUID, Integer> cappedByRule;
        int rounds;
        boolean ruleRoundsCapped;

        Resolved(List<TimedEdge> edges, Map<UUID, Span> spans, Map<UUID, PremiseMeta> meta,
                 Map<UUID, Axioms> axioms, Map<String, UUID> rules, Set<UUID> assertedIds,
                 CheckedPool checked, Map<DerivedKey, Wanted> wanted,
                 Map<DerivedKey, UUID> provisional, LoadedRules loaded,
                 Map<UUID, Integer> cappedByRule, int rounds) {
            this.edges = edges;
            this.spans = spans;
            this.meta = meta;
            this.axioms = axioms;
            this.rules = rules;
            this.assertedIds = assertedIds;
            this.checked = checked;
            this.wanted = wanted;
            this.provisional = provisional;
            this.loaded = loaded;
            this.cappedByRule = cappedByRule;
            this.rounds = rounds;
            this.ruleRoundsCapped = rounds == AxiomChecks.MAX_DEPTH;
        }
    }

    // ===================== compile_rules / attribute_rules =====================

    /**
     * 按本体公理重编译规则（reasoning.rs:1459-1506），返回 (谓词, 种类) → 规则 id。
     * <b>幂等</b>：身份取 (kb, 谓词, 种类)，重编译认得出「还是那条规则」——否则每跑一次
     * derived_facts.rule_id 就指向一个新 id，历史全断。<b>公理撤了的规则不删</b>：
     * 已失效的派生行仍指着它，解释「当时靠哪条规则推的」需要它还在。
     */
    private Map<String, UUID> compileRules(UUID kbId, Map<UUID, Axioms> ax) {
        List<Map.Entry<UUID, String>> want = new ArrayList<>();
        for (Map.Entry<UUID, Axioms> e : ax.entrySet()) {
            if (e.getValue().transitive()) {
                want.add(Map.entry(e.getKey(), "transitive"));
            }
            if (e.getValue().symmetric()) {
                want.add(Map.entry(e.getKey(), "symmetric"));
            }
            // 后两种是迁移 0016 补上的规则源。规则挂在「有声明的那一侧」——归一化过的
            // 逆两边都有声明，所以两个方向各得一条规则
            if (e.getValue().inverseOf() != null) {
                want.add(Map.entry(e.getKey(), "inverse"));
            }
            if (e.getValue().subPropertyOf() != null) {
                want.add(Map.entry(e.getKey(), "sub_property"));
            }
        }
        want.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
        Map<String, UUID> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> w : want) {
            mapper.insertRule(Uuids.nowV7(), kbId, w.getKey(), w.getValue());
            UUID id = mapper.selectRuleId(kbId, w.getKey(), w.getValue());
            out.put(ruleKey(w.getKey(), w.getValue()), id);
        }
        return out;
    }

    /** (谓词, 种类) 的键字面量。 */
    static String ruleKey(UUID predicate, String kind) {
        return predicate + "|" + kind;
    }

    /**
     * 取业务规则（reasoning.rs:1578-1747）。条件形状不合法的规则<b>整条跳过而不是报错</b>
     * ——一条写坏的规则不该让整轮物化停摆，而它不产出这件事在报告的条数里看得见。
     */
    private LoadedRules attributeRules(UUID kbId) {
        List<ReasoningMapper.RuleDefRow> rows = mapper.selectAttributeRules(kbId);
        if (rows.isEmpty()) {
            return new LoadedRules(List.of(), null, Map.of());
        }
        // 类名 ↔ 类 id，两个方向各一份：归类结论按名字记（改标签不该让已推出的结论
        // 变成另一条），而主类范围与实体的类都是 id
        Map<String, UUID> classIds = new LinkedHashMap<>();
        Map<UUID, String> className = new HashMap<>();
        for (ReasoningMapper.TypeRow t : mapper.selectEntityTypes(kbId)) {
            String name = t.iri() != null ? t.iri() : t.key();
            if (name == null) {
                continue;
            }
            classIds.put(name, t.id());
            className.put(t.id(), name);
        }
        UUID isA = mapper.selectIsAPredicate(kbId);
        List<ReasoningMapper.RuleConditionRow> conds = mapper.selectRuleConditions(
                rows.stream().map(ReasoningMapper.RuleDefRow::id).toList());
        Map<UUID, List<Condition>> byRule = new LinkedHashMap<>();
        Set<UUID> broken = new HashSet<>();
        for (ReasoningMapper.RuleConditionRow c : conds) {
            Op op = Op.parse(c.op());
            if (op == null) {
                broken.add(c.ruleId());
                continue;
            }
            Operand operand = parseOperand(op, c.operand());
            if (operand == null) {
                broken.add(c.ruleId());
                continue;
            }
            byRule.computeIfAbsent(c.ruleId(), k -> new ArrayList<>()).add(new Condition(
                    c.groupSeq(), Side.parse(c.subjectSide()) == null ? Side.X
                            : Side.parse(c.subjectSide()),
                    c.predicateId(), op, operand));
        }
        List<LoadedRule> out = new ArrayList<>();
        for (ReasoningMapper.RuleDefRow r : rows) {
            if (broken.contains(r.id())) {
                continue;
            }
            List<Condition> conditions = byRule.remove(r.id());
            // 一条没有条件的规则什么都不推（求值器也这么判）
            if (conditions == null) {
                continue;
            }
            Conclusion conclusion;
            UUID predicate;
            switch (r.conclusion()) {
                case "typing" -> {
                    if (r.concludeTypeId() == null || isA == null) {
                        continue;
                    }
                    // 按 IRI 记，没有 IRI 才退回 key：改标签不该让已推出的结论变成另一条
                    String class_ = r.iri() != null ? r.iri() : r.key();
                    if (class_ == null) {
                        continue;
                    }
                    conclusion = new Conclusion.Typing(class_);
                    predicate = isA;
                }
                case "computed" -> {
                    if (r.concludePredicateId() == null || r.concludeExpr() == null) {
                        continue;
                    }
                    Expr expr;
                    try {
                        expr = Expressions.fromJson(r.concludeExpr());
                    } catch (Expressions.ExprErrorException e) {
                        continue;
                    }
                    conclusion = new Conclusion.Computed(r.concludePredicateId(), expr);
                    predicate = r.concludePredicateId();
                }
                case "attribute" -> {
                    if (r.concludePredicateId() == null || r.concludeValue() == null) {
                        continue;
                    }
                    conclusion = new Conclusion.Attribute(r.concludePredicateId(),
                            r.concludeValue());
                    predicate = r.concludePredicateId();
                }
                case "relation" -> {
                    if (r.concludePredicateId() == null || r.joinPredicateId() == null) {
                        continue;
                    }
                    conclusion = new Conclusion.Relation(r.concludePredicateId());
                    predicate = r.concludePredicateId();
                }
                default -> {
                    continue;
                }
            }
            List<UUID> subjectTypes = mapper.selectDescendantTypes(kbId, r.subjectTypeId());
            List<String> subjectClasses = new ArrayList<>();
            for (UUID t : subjectTypes) {
                String name = className.get(t);
                if (name != null) {
                    subjectClasses.add(name);
                }
            }
            out.add(new LoadedRule(new BusinessRule(r.id(), conclusion, r.joinPredicateId(),
                    conditions), subjectTypes, subjectClasses, predicate, r.versionId()));
        }
        return new LoadedRules(out, isA, classIds);
    }

    /**
     * 操作数按 op 解析（reasoning.rs:1750-1791）。形状不对返回 null，调用方整条规则跳过。
     * <b>In 与 NotIn 同一支</b>——漏掉后者的下场是整条规则被当成写坏的跳过（0029/#476）。
     */
    static Operand parseOperand(Op op, JsonNode raw) {
        switch (op) {
            case PRESENT:
                return new Operand.None();
            case BETWEEN: {
                // 元素必须是真 JSON 数（reasoning.rs:1758-1759 的 as_f64()——数字字符串
                // 不收，与写侧校验同口径，审查 M-6）
                if (raw == null || !raw.isArray() || raw.size() < 2
                        || !raw.get(0).isNumber() || !raw.get(1).isNumber()) {
                    return null;
                }
                double lo = raw.get(0).asDouble();
                double hi = raw.get(1).asDouble();
                return new Operand.Range(Math.min(lo, hi), Math.max(lo, hi));
            }
            case IN:
            case NOT_IN: {
                if (raw == null || !raw.isArray() || raw.isEmpty()) {
                    return null;
                }
                List<String> set = new ArrayList<>();
                for (JsonNode v : raw) {
                    if (v.isTextual()) {
                        set.add(v.asText().trim());
                    } else {
                        set.add(v.toString());
                    }
                }
                return new Operand.Set_(set);
            }
            default: {
                if (raw == null) {
                    return null;
                }
                if (raw.isObject()) {
                    try {
                        return new Operand.Calc(Expressions.fromJson(raw));
                    } catch (Expressions.ExprErrorException e) {
                        return null;
                    }
                }
                Double n = Rules.num(raw);
                if (n == null && raw.isTextual()) {
                    try {
                        String s = raw.asText().trim();
                        n = s.isEmpty() ? null : Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        n = null;
                    }
                }
                return n == null ? null : new Operand.Num(n);
            }
        }
    }

    // ===================== 取数 =====================

    /** 一次取数三样：带区间的边、每条事实的区间、精度与置信度（run 与 materialize 共用）。 */
    private record TimedEdges(List<TimedEdge> edges, Map<UUID, Span> spans,
                              Map<UUID, PremiseMeta> meta) {
    }

    private TimedEdges timedEdgesFull(UUID kbId) {
        List<TimedEdgeRow> rows = mapper.selectTimedEdgeRows(kbId);
        Map<UUID, WorldTime.Temporal> temporalOf = new HashMap<>();
        for (ReasoningMapper.TemporalRow r : mapper.selectTemporals(kbId)) {
            temporalOf.put(r.id(), Temporal.parse(r.temporal()));
        }
        List<TimedEdge> edges = new ArrayList<>(rows.size());
        Map<UUID, Span> spans = new LinkedHashMap<>();
        Map<UUID, PremiseMeta> meta = new LinkedHashMap<>();
        for (TimedEdgeRow r : rows) {
            Temporal temporal = temporalOf.getOrDefault(r.predicateId(), Temporal.STATE);
            ReadSpan s = ReasoningService.readSpan(temporal, r.validFrom(), r.validFromPrecision(),
                    r.validTo(), r.validToPrecision(), r.attestedFrom(), r.attestedTo());
            edges.add(new TimedEdge(
                    new Edge(r.id(), r.predicateId(), r.subjectId(), r.objectId()),
                    s.from(), s.to()));
            spans.put(r.id(), new Span(s.from(), s.to()));
            meta.put(r.id(), new PremiseMeta(r.validFromPrecision(), r.validToPrecision(),
                    r.confidence(), s.fromAnchored(), s.toAnchored()));
        }
        return new TimedEdges(edges, spans, meta);
    }

    /** 属性事实（reasoning.rs:1831-1900）：字面值通道，与 timed_edges 对偶。 */
    private record AttributeFacts(List<AttrFact> facts, Map<UUID, Span> spans,
                                  Map<UUID, PremiseMeta> meta, Map<UUID, UUID> typeOf) {
    }

    private AttributeFacts attributeFacts(UUID kbId) {
        List<AttrFactRow> rows = mapper.selectAttributeFacts(kbId);
        List<AttrFact> facts = new ArrayList<>(rows.size());
        Map<UUID, Span> spans = new LinkedHashMap<>();
        Map<UUID, PremiseMeta> meta = new LinkedHashMap<>();
        Map<UUID, UUID> typeOf = new LinkedHashMap<>();
        for (AttrFactRow r : rows) {
            // 与公理那一路同一种读法（0022）：读数没日期就从它的文档起算；属性一律状态
            ReadSpan s = ReasoningService.readSpan(Temporal.STATE, r.validFrom(),
                    r.validFromPrecision(), r.validTo(), r.validToPrecision(), r.attestedFrom(),
                    r.attestedTo());
            // 字面值是 {"value": …}；比较的是里面那个 value。取不到就把整个对象交给
            // 求值器——它对认不出的形状一律判不满足
            JsonNode inner = r.objectValue() != null && r.objectValue().hasNonNull("value")
                    ? r.objectValue().get("value") : r.objectValue();
            facts.add(new AttrFact(r.id(), r.subjectId(), r.predicateId(), inner));
            spans.put(r.id(), new Span(s.from(), s.to()));
            meta.put(r.id(), new PremiseMeta(r.validFromPrecision(), r.validToPrecision(),
                    r.confidence(), s.fromAnchored(), s.toAnchored()));
            typeOf.put(r.subjectId(), r.typeId());
        }
        return new AttributeFacts(facts, spans, meta, typeOf);
    }

    /** 人认可过并存的（派生三元组, 断言）对（reasoning.rs:1350-1377）。 */
    private Set<AcceptedClash> acceptedClashes(UUID kbId) {
        Set<AcceptedClash> out = new HashSet<>();
        for (ReasoningMapper.AcceptedClashRow r : mapper.selectAcceptedClashes(kbId)) {
            try {
                out.add(new AcceptedClash(
                        UUID.fromString(r.detail().path("subject_id").asText()),
                        UUID.fromString(r.detail().path("predicate_id").asText()),
                        UUID.fromString(r.detail().path("object_id").asText()),
                        r.leftFact()));
            } catch (RuntimeException e) {
                // detail 残缺（手改过）：这一对认可管不了新算的派生，跳过
            }
        }
        return out;
    }

    record AcceptedClash(UUID subject, UUID predicate, UUID object, UUID against) {
    }

    // ===================== check / retire / refuse_blocked =====================

    /**
     * 在「断言边 + 站得住的关系边」的池子上跑一遍公理推导，再拿公理量一遍候选
     * （reasoning.rs:1937-1995）。断言那一侧只给断言：关系边与别的派生互撞走派生之间
     * 那一路，against 始终是 facts 里的一行——审核队列的外键指的就是那张表。
     */
    CheckedPool check(List<TimedEdge> edges, List<RelationCandidate> relation,
                      Map<UUID, Axioms> axioms, Map<UUID, Span> spans,
                      Set<AcceptedClash> accepted) {
        List<TimedEdge> timed = new ArrayList<>(edges);
        for (RelationCandidate r : relation) {
            if (r.refused) {
                continue;
            }
            Span span = spans.getOrDefault(r.edge.id(), Span.OPEN);
            timed.add(new TimedEdge(new Edge(r.edge.id(), r.edge.predicate(), r.edge.subject(),
                    r.edge.object()), span.from(), span.to()));
        }
        Derivation candidates = Derive.derive(timed, axioms);
        int axiomCount = candidates.facts.size();
        Map<Integer, UUID> candidateRule = new LinkedHashMap<>();
        for (RelationCandidate r : relation) {
            candidateRule.put(candidates.facts.size(), r.ruleId);
            candidates.facts.add(new Derived(r.edge.predicate(), r.edge.predicate(),
                    r.edge.subject(), r.edge.object(), Rule.BUSINESS, r.premises));
        }
        Derive.Contradictions clashes = Derive.contradictions(candidates, edges, axioms, spans);
        // asserted > derived 是硬性的（0002）：撞上断言的派生不落地。人认可过并存的除外；
        // 派生之间互撞的两边都不落，认可与否只影响报不报（0017）
        Set<Integer> blocked = new HashSet<>();
        for (Derive.Clash c : clashes.withAssertions) {
            Derived d = candidates.facts.get(c.derived());
            if (!accepted.contains(new AcceptedClash(d.subject(), d.predicate(), d.object(),
                    c.against()))) {
                blocked.add(c.derived());
            }
        }
        for (Derive.RuleClash rc : clashes.betweenDerivations) {
            for (int[] pair : rc.pairs()) {
                blocked.add(pair[0]);
                blocked.add(pair[1]);
            }
        }
        return new CheckedPool(candidates, axiomCount, candidateRule, clashes, blocked);
    }

    /**
     * 被拦下的关系边退出去之后，站在它上面的结论一起退场（reasoning.rs:2000-2026）：
     * 读了它的规则结论、再站在那些结论上的结论，直到没有新的为止——一条派生随前提
     * 失效（0002），在不动点里也成立。
     */
    private void retire(Set<UUID> seed, Map<DerivedKey, Wanted> wanted,
                        Map<DerivedKey, UUID> provisional, List<AttrFact> factPool,
                        List<RelationCandidate> relation) {
        Set<UUID> gone = new HashSet<>(seed);
        while (true) {
            List<DerivedKey> dropped = new ArrayList<>();
            for (Map.Entry<DerivedKey, Wanted> e : wanted.entrySet()) {
                if (e.getValue().premises().stream().anyMatch(gone::contains)) {
                    dropped.add(e.getKey());
                }
            }
            if (dropped.isEmpty()) {
                break;
            }
            for (DerivedKey key : dropped) {
                wanted.remove(key);
                UUID prov = provisional.remove(key);
                if (prov != null) {
                    gone.add(prov);
                }
            }
        }
        factPool.removeIf(f -> gone.contains(f.id()));
        // Rust retain **保留** refused 的候选（队列要报它）；只退掉前提没了的
        relation.removeIf(r -> !r.refused && gone.contains(r.edge.id()));
    }

    /**
     * 把这一遍检查拦下的关系候选退出池子（reasoning.rs:2030-2058）。
     * 返回有没有退出的：退了就得再检查一遍，池子变了。
     */
    private boolean refuseBlocked(CheckedPool checked, List<RelationCandidate> relation,
                                  Map<DerivedKey, Wanted> wanted,
                                  Map<DerivedKey, UUID> provisional, List<AttrFact> factPool) {
        Set<UUID> retired = new HashSet<>();
        for (int i : checked.blocked()) {
            int k = i - checked.axiomCount();
            if (k < 0 || k >= relation.size()) {
                continue;
            }
            RelationCandidate r = relation.get(k);
            if (r.refused) {
                continue;
            }
            r.refused = true;
            retired.add(r.edge.id());
            wanted.remove(r.key);
            provisional.remove(r.key);
        }
        if (retired.isEmpty()) {
            return false;
        }
        retire(retired, wanted, provisional, factPool, relation);
        return true;
    }

    // ===================== resolve =====================

    /**
     * 一次求解（reasoning.rs:2089-2407）。断言边 → check（asserted &gt; derived 拦截 +
     * retire/refuse_blocked 连带退场）→ 业务规则不动点（<b>全量重跑非半朴素</b>：
     * 链通常只有一两环，收敛靠「这一轮没产出新键」；输入侧一行 derived_facts 不读）。
     */
    public Resolved resolve(UUID kbId) {
        Map<UUID, Axioms> axioms = reasoning.axioms(kbId);
        Map<String, UUID> rules = compileRules(kbId, axioms);
        TimedEdges timed = timedEdgesFull(kbId);
        List<TimedEdge> edges = timed.edges();
        Map<UUID, Span> spans = timed.spans();
        Map<UUID, PremiseMeta> meta = timed.meta();
        Set<AcceptedClash> accepted = acceptedClashes(kbId);
        LoadedRules loaded = attributeRules(kbId);
        Set<UUID> assertedIds = new HashSet<>();
        for (TimedEdge e : edges) {
            assertedIds.add(e.edge().fact());
        }

        Map<DerivedKey, Wanted> wanted = new LinkedHashMap<>();
        Map<DerivedKey, UUID> provisional = new LinkedHashMap<>();
        List<RelationCandidate> relation = new ArrayList<>();
        Map<UUID, Integer> cappedByRule = new LinkedHashMap<>();
        int rounds = 0;
        CheckedPool checked = check(edges, relation, axioms, spans, accepted);

        if (!loaded.rules().isEmpty()) {
            AttributeFacts attrs = attributeFacts(kbId);
            List<AttrFact> factPool = new ArrayList<>(attrs.facts());
            for (AttrFact f : attrs.facts()) {
                assertedIds.add(f.id());
            }
            // 前提的精度与置信度：落地那一段与不动点这一段共用，先合起来；区间也要——
            // 认出派生的哪一端是被前提的锚点顶上来的，靠的就是它
            meta.putAll(attrs.meta());
            spans.putAll(attrs.spans());
            // 断言边上公理已经推出的键：规则再推出同一条关系时不另立一行
            Set<DerivedKey> axiomKeys = new HashSet<>();
            for (Derived d : checked.candidates().facts) {
                Span span = Derive.validity(d.premises(), spans);
                if (span != null) {
                    axiomKeys.add(new DerivedKey(d.subject(), d.predicate(), d.object(), null,
                            span.from(), span.to()));
                }
            }
            List<RuleEdge> edgePool = edges.stream()
                    .map(e -> new RuleEdge(e.edge().fact(), e.edge().predicate(),
                            e.edge().subject(), e.edge().object()))
                    .toList();
            Map<EdgeKey, List<Span>> assertedEdges = new HashMap<>();
            for (TimedEdge e : edges) {
                assertedEdges.computeIfAbsent(
                        new EdgeKey(e.edge().subject(), e.edge().predicate(), e.edge().object()),
                        k -> new ArrayList<>()).add(new Span(e.from(), e.to()));
            }

            // 每个实体被推出来的类。断言的类在 typeOf 里：断言的类没有区间（筛子），
            // 推出来的类有区间（条件，0030）
            Map<UUID, List<UUID>> derivedTypes = new LinkedHashMap<>();
            boolean dirty = false;
            for (int round = 0; round < AxiomChecks.MAX_DEPTH; round++) {
                rounds++;
                // 规则推出的关系边先过公理与矛盾检查（0047 决定 3）：站得住的这一轮起
                // 当一条普通的边，被拦下的退出池子。退了池子就变了，再查一遍，直到稳住
                boolean refusedNow = false;
                while (dirty) {
                    checked = check(edges, relation, axioms, spans, accepted);
                    dirty = refuseBlocked(checked, relation, wanted, provisional, factPool);
                    refusedNow |= dirty;
                }
                List<RuleEdge> evaluationEdges = new ArrayList<>(edgePool);
                for (RelationCandidate r : relation) {
                    if (!r.refused) {
                        evaluationEdges.add(r.edge);
                    }
                }
                // 一轮之内先算完再入池：同一轮里规则读到的是上一轮结束时的池子
                List<Map.Entry<Integer, Rules.RuleHit>> fresh = new ArrayList<>();
                cappedByRule.clear();
                for (int ri = 0; ri < loaded.rules().size(); ri++) {
                    LoadedRule lr = loaded.rules().get(ri);
                    // 主类是断言的：范围是个筛子，不带区间、不进前提
                    List<AttrFact> byAssertion = new ArrayList<>();
                    // 只靠派生归类够得着的：范围变成一个条件
                    List<AttrFact> byConclusion = new ArrayList<>();
                    for (AttrFact f : factPool) {
                        UUID t = attrs.typeOf().get(f.subject());
                        if (t != null && lr.subjectTypes().contains(t)) {
                            byAssertion.add(f);
                        } else {
                            List<UUID> ts = derivedTypes.get(f.subject());
                            if (ts != null && ts.stream().anyMatch(lr.subjectTypes()::contains)) {
                                byConclusion.add(f);
                            }
                        }
                    }
                    Rules.RuleEval eval = Rules.evaluateWithPool(List.of(lr.rule), byAssertion,
                            factPool, spans, evaluationEdges);
                    int capped = eval.report().capped();
                    for (Rules.RuleHit h : eval.hits()) {
                        fresh.add(Map.entry(ri, h));
                    }
                    // 主类是推出来的那一份：范围写成一条条件（0030）——归类那条派生事实
                    // 因此进了前提，区间跟着它收窄，它作废时这条结论也跟着退场
                    if (!byConclusion.isEmpty() && loaded.isA() != null
                            && !lr.subjectClasses().isEmpty()) {
                        BusinessRule scoped = scopedByConclusion(lr.rule(), loaded.isA(),
                                lr.subjectClasses());
                        Rules.RuleEval eval2 = Rules.evaluateWithPool(List.of(scoped),
                                byConclusion, factPool, spans, evaluationEdges);
                        capped += eval2.report().capped();
                        for (Rules.RuleHit h : eval2.hits()) {
                            fresh.add(Map.entry(ri, h));
                        }
                    }
                    cappedByRule.merge(lr.rule().id(), capped, Integer::sum);
                }

                int before = provisional.size();
                for (Map.Entry<Integer, Rules.RuleHit> item : fresh) {
                    int ri = item.getKey();
                    Rules.RuleHit h = item.getValue();
                    LoadedRule lr = loaded.rules().get(ri);
                    if (lr.rule().conclusion() instanceof Conclusion.Relation) {
                        if (h.object() == null) {
                            continue;
                        }
                        UUID object = h.object();
                        DerivedKey key = new DerivedKey(h.subject(), lr.concludePredicate(),
                                object, null, h.from(), h.to());
                        // 上一轮已经推出过同一条（站得住的、被拦下的都算——被拦下的
                        // 不再试第二次，不然它每轮进一次退一次，不动点就到不了）
                        boolean seen = wanted.containsKey(key) || axiomKeys.contains(key);
                        if (!seen) {
                            for (RelationCandidate r : relation) {
                                if (r.key.equals(key)) {
                                    seen = true;
                                    break;
                                }
                            }
                        }
                        if (seen) {
                            continue;
                        }
                        // 同一条边已经断言在案、区间还交：asserted > derived，不另立一行
                        boolean overlaps = false;
                        for (Span span : assertedEdges.getOrDefault(
                                new EdgeKey(h.subject(), lr.concludePredicate(), object),
                                List.of())) {
                            if (Derive.overlap(new Span(h.from(), h.to()), span) != null) {
                                overlaps = true;
                                break;
                            }
                        }
                        if (overlaps) {
                            continue;
                        }
                        UUID prov = Uuids.nowV7();
                        PremiseMeta pm = premiseMeta(h.premises(), h.from(), h.to(), spans, meta);
                        spans.put(prov, new Span(h.from(), h.to()));
                        meta.put(prov, pm);
                        relation.add(new RelationCandidate(
                                new RuleEdge(prov, lr.concludePredicate(), h.subject(), object),
                                key, h.premises(), lr.rule().id()));
                        provisional.put(key, prov);
                        wanted.put(key, new Wanted(h.subject(), lr.concludePredicate(), object,
                                null, h.from(), h.to(), h.premises(), null, lr.rule().id(),
                                lr.version()));
                        dirty = true;
                        continue;
                    }
                    JsonNode value;
                    JsonNode inner;
                    if (lr.rule().conclusion() instanceof Conclusion.Typing typing) {
                        value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .objectNode().put("class", typing.class_());
                        inner = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .textNode(typing.class_());
                    } else if (lr.rule().conclusion()
                            instanceof Conclusion.Attribute attribute) {
                        value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .objectNode().set("value", attribute.value());
                        inner = attribute.value();
                    } else if (lr.rule().conclusion()
                            instanceof Conclusion.Computed computed) {
                        if (h.value() == null) {
                            continue;
                        }
                        JsonNode v = h.value().toJson();
                        value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .objectNode().set("value", v);
                        inner = v;
                    } else {
                        throw new IllegalStateException("unreachable");
                    }
                    DerivedKey key = new DerivedKey(h.subject(), lr.concludePredicate(), null,
                            valueKey(value), h.from(), h.to());
                    // 上一轮已经推出过同一条：不再进池子。自反馈的环正是在这里停下的
                    if (wanted.containsKey(key)) {
                        continue;
                    }
                    // 临时 id：这一条现在就要当事实用，可它落在哪一行要等对账之后才知道
                    UUID prov = Uuids.nowV7();
                    PremiseMeta pm = premiseMeta(h.premises(), h.from(), h.to(), spans, meta);
                    spans.put(prov, new Span(h.from(), h.to()));
                    meta.put(prov, pm);
                    factPool.add(new AttrFact(prov, h.subject(), lr.concludePredicate(), inner));
                    if (lr.rule().conclusion() instanceof Conclusion.Typing typing) {
                        UUID t = loaded.classIds().get(typing.class_());
                        if (t != null) {
                            derivedTypes.computeIfAbsent(h.subject(), k -> new ArrayList<>())
                                    .add(t);
                        }
                    }
                    provisional.put(key, prov);
                    wanted.put(key, new Wanted(h.subject(), lr.concludePredicate(), null, value,
                            h.from(), h.to(), h.premises(), null, lr.rule().id(), lr.version()));
                }
                // 这一轮什么新东西都没推出来、也没退出去什么：不动点到了
                if (provisional.size() == before && !refusedNow) {
                    break;
                }
            }
            // 最后一轮加的关系边还没过检查：查到稳住为止，只是不再跑规则
            while (dirty) {
                checked = check(edges, relation, axioms, spans, accepted);
                dirty = refuseBlocked(checked, relation, wanted, provisional, factPool);
            }
        }

        return new Resolved(edges, spans, meta, axioms, rules, assertedIds, checked, wanted,
                provisional, loaded, cappedByRule, rounds);
    }

    /** 断言边的键：(主, 谓, 宾)——同一条断言边的几段有效期挂在它下面。 */
    record EdgeKey(UUID subject, UUID predicate, UUID object) {
    }

    /**
     * 主类靠派生归类才够得着的那一份规则（reasoning.rs:1196-1219）：范围从筛子变成一条
     * 条件——`范围 与 ((A 且 B) 或 C)` 展开每组各加一条，析取仍只有一层（0029）。
     */
    static BusinessRule scopedByConclusion(BusinessRule rule, UUID isA, List<String> classes) {
        Set<Integer> groups = new java.util.TreeSet<>();
        for (Condition c : rule.conditions()) {
            groups.add(c.group());
        }
        List<Condition> conditions = new ArrayList<>(rule.conditions());
        for (int group : groups) {
            conditions.add(new Condition(group, Side.X, isA, Op.IN, new Operand.Set_(
                    new ArrayList<>(classes))));
        }
        return new BusinessRule(rule.id(), rule.conclusion(), rule.joinPredicate(), conditions);
    }

    /**
     * 一条派生从它的前提上得到的精度与置信度（reasoning.rs:1147-1189）。
     * 精度跟着<b>赢下这一端的那条前提</b>走；几条前提并列时取最粗；那一端若是某条
     * 前提的锚点顶上来的（0022），没有精度可言。置信度取前提里最低的。
     */
    static PremiseMeta premiseMeta(List<UUID> premises, Long from, Long to,
                                   Map<UUID, Span> spans, Map<UUID, PremiseMeta> meta) {
        String fp = null;
        String tp = null;
        float conf = 1.0f;
        boolean fromAnchored = false;
        boolean toAnchored = false;
        for (UUID p : premises) {
            PremiseMeta m = meta.get(p);
            if (m == null) {
                continue;
            }
            conf = Math.min(conf, m.confidence());
            Span s = spans.get(p);
            if (s == null) {
                continue;
            }
            if (from != null && java.util.Objects.equals(s.from(), from)) {
                if (m.fromAnchored()) {
                    fromAnchored = true;
                } else {
                    fp = coarsest(fp, m.fromPrecision());
                }
            }
            if (to != null && java.util.Objects.equals(s.to(), to)) {
                if (m.toAnchored()) {
                    toAnchored = true;
                } else {
                    // 'unknown' 不是粒度，是「结束了不知哪天」的标记；它顶上来的那端是锚点
                    String pt = WorldTime.ENDED_UNKNOWN.equals(m.toPrecision()) ? null
                            : m.toPrecision();
                    tp = coarsest(tp, pt);
                }
            }
        }
        return new PremiseMeta(fp, tp, conf, fromAnchored, toAnchored);
    }

    /** 精度按「最粗的那个」取（reasoning.rs:1430-1448）：梯子 year 最粗 second 最细。 */
    static String coarsest(String a, String b) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(String precision) {
        for (int i = 0; i < WorldTime.WORLD_PRECISIONS.length; i++) {
            if (WorldTime.WORLD_PRECISIONS[i].equals(precision)) {
                return i;
            }
        }
        return WorldTime.WORLD_PRECISIONS.length;
    }

    /** JSON 值的规范化文本形态，只用来做键（reasoning.rs:1426-1428）。 */
    static String valueKey(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime stamp(long secs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(secs), ZoneOffset.UTC);
    }

    // ===================== materialize =====================

    /**
     * 推一遍，把派生事实落进账本（reasoning.rs:2409-2694）。键 = (主, 谓, 宾, 值, 起, 止)
     * <b>区间进键</b>；不再成立的作废（置 invalidated_at 不删）；新行 14 列 INSERT；
     * reproved（结论没变理由变了）删重写 fact_derivations；redefined（版本切换 0060）行留
     * 版本换；证明<b>等派生全插完再写</b>（链前提可能在本批）。
     */
    public DeriveReport materialize(UUID kbId) {
        Resolved resolved = resolve(kbId);
        CheckedPool checked = resolved.checked;
        Map<DerivedKey, Wanted> wanted = resolved.wanted;

        // 站得住的关系结论已经在 provisional 里；候选里在它们之外的是被拦下的那些
        int liveRelations = 0;
        for (Wanted w : wanted.values()) {
            if (w.attributeRuleId() != null && w.objectId() != null) {
                liveRelations++;
            }
        }
        int unruled = 0;

        // 公理派生并进同一个 wanted——合流是必须的（两趟各做各的 diff 会互判陈旧）
        for (int i = 0; i < checked.axiomCount(); i++) {
            if (checked.blocked().contains(i)) {
                continue;
            }
            Derived d = checked.candidates().facts.get(i);
            Span span = Derive.validity(d.premises(), resolved.spans);
            if (span == null) {
                continue;
            }
            // 按 via 查，不是 predicate：规则行是给「声明了公理的那个谓词」编的
            UUID ruleId = resolved.rules.get(ruleKey(d.via(), d.rule().asString()));
            if (ruleId == null) {
                // 查不到规则是编译与推导不一致，数出来，别再让它静默消失一次
                unruled++;
                continue;
            }
            wanted.putIfAbsent(new DerivedKey(d.subject(), d.predicate(), d.object(), null,
                            span.from(), span.to()),
                    new Wanted(d.subject(), d.predicate(), d.object(), null, span.from(),
                            span.to(), d.premises(), ruleId, null, null));
        }
        int ruleCapped = 0;
        for (LoadedRule lr : resolved.loaded.rules()) {
            // 展不完的组合数按规则写回：这个数字在表里常驻。取最后一轮的数——最全的池子
            int capped = resolved.cappedByRule.getOrDefault(lr.rule().id(), 0);
            mapper.updateRuleCapped(lr.rule().id(), capped);
            ruleCapped += capped;
        }
        final DeriveReport head = new DeriveReport(resolved.rules.size(), resolved.edges.size(),
                checked.candidates().facts.size() + resolved.provisional.size() - liveRelations,
                0, 0, checked.candidates().capped.size(), unruled, checked.blocked().size(),
                resolved.loaded.rules().size(), resolved.provisional.size(), ruleCapped,
                resolved.rounds, resolved.ruleRoundsCapped, 0, 0);

        return tx.execute(status -> {
            List<ReasoningMapper.LiveDerivedRow> live = mapper.selectLiveDerived(kbId);
            List<UUID> stale = new ArrayList<>();
            // 每个还成立的结论最后落在哪一行——不变的结论保留原来那一行，链上指向它的
            // 前提要指向这个 id（0030）
            Map<DerivedKey, UUID> settled = new LinkedHashMap<>();
            List<Map.Entry<UUID, Wanted>> kept = new ArrayList<>();
            for (ReasoningMapper.LiveDerivedRow row : live) {
                DerivedKey key = new DerivedKey(row.subjectId(), row.predicateId(), row.objectId(),
                        valueKey(row.objectValue()),
                        row.validFrom() == null ? null : row.validFrom().toEpochSecond(),
                        row.validTo() == null ? null : row.validTo().toEpochSecond());
                Wanted d = wanted.remove(key);
                if (d != null) {
                    settled.put(key, row.id());
                    kept.add(Map.entry(row.id(), d));
                } else {
                    stale.add(row.id());
                }
            }
            int invalidated = 0;
            int inserted = 0;
            int redefined = 0;
            int reproved = 0;
            if (!stale.isEmpty()) {
                // 前提没了 → 派生跟着失效。置 invalidated_at 而不是删（0002 第 3 节）
                mapper.invalidateDerived(stale);
                invalidated = stale.size();
            }
            // 剩下的都是新行。先把 id 全定下来再插：链上的前提要指向的那一行，
            // 可能是这一批里还没插的另一条（0030）
            List<Map.Entry<UUID, Wanted>> fresh = new ArrayList<>();
            for (Map.Entry<DerivedKey, Wanted> e : wanted.entrySet()) {
                UUID id = Uuids.nowV7();
                settled.put(e.getKey(), id);
                fresh.add(Map.entry(id, e.getValue()));
            }
            // 临时 id → 真正的行 id；断言前提不在 derived_facts 里，原样通过
            Map<UUID, UUID> resolveIds = new LinkedHashMap<>();
            for (Map.Entry<DerivedKey, UUID> e : resolved.provisional.entrySet()) {
                UUID id = settled.get(e.getKey());
                if (id != null) {
                    resolveIds.put(e.getValue(), id);
                }
            }

            for (Map.Entry<UUID, Wanted> e : fresh) {
                Wanted d = e.getValue();
                // 交集把某一端算成无界时，那一端的精度清掉；锚点端也没有精度——
                // 在无知的地方填一个确定的值正是 facts 那条注释说的病
                PremiseMeta m = premiseMeta(d.premises(), d.from(), d.to(), resolved.spans,
                        resolved.meta);
                String fp = m.fromAnchored() ? null : d.from() != null ? m.fromPrecision() : null;
                String tp = m.toAnchored() ? null : d.to() != null ? m.toPrecision() : null;
                mapper.insertDerived(e.getKey(), kbId, d.subject(), d.predicate(), d.objectId(),
                        d.objectValue(),
                        d.from() == null ? null : stamp(d.from()),
                        d.to() == null ? null : stamp(d.to()), fp, tp, m.confidence(),
                        d.ruleId(), d.attributeRuleId(), d.attributeRuleVersionId());
                inserted++;
            }

            // 结论没变、定义变了：行留着，版本换过来——证明才说得出它现在凭什么（0060）
            for (Map.Entry<UUID, Wanted> e : kept) {
                UUID version = e.getValue().attributeRuleVersionId();
                if (version == null) {
                    continue;
                }
                redefined += mapper.updateDerivedVersion(e.getKey(), version);
            }

            // 结论没变、理由变了：同一句话可以有第二条依据。对账键里没有前提，所以那一行
            // 会带着上一轮的证明留下来——一次查完再逐条比，只有真不一样的才重写
            List<UUID> keptIds = kept.stream().map(Map.Entry::getKey).toList();
            Map<UUID, List<ReasoningMapper.StoredPremiseRow>> stored = new LinkedHashMap<>();
            if (!keptIds.isEmpty()) {
                for (ReasoningMapper.StoredPremiseRow row : mapper.selectStoredPremises(keptIds)) {
                    stored.computeIfAbsent(row.derivedFactId(), k -> new ArrayList<>()).add(row);
                }
            }
            List<Map.Entry<UUID, List<ReasoningMapper.StoredPremiseRow>>> want = kept.stream()
                    .map(e -> Map.entry(e.getKey(), e.getValue().premises().stream()
                            .map(p -> new ReasoningMapper.StoredPremiseRow(e.getKey(),
                                    resolveIds.containsKey(p) ? null : p,
                                    resolveIds.get(p)))
                            .toList()))
                    .toList();
            List<Map.Entry<UUID, List<ReasoningMapper.StoredPremiseRow>>> reprovedRows =
                    new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                UUID id = kept.get(i).getKey();
                List<ReasoningMapper.StoredPremiseRow> have = stored.getOrDefault(id, List.of());
                if (!have.equals(want.get(i).getValue())) {
                    reprovedRows.add(want.get(i));
                }
            }
            for (Map.Entry<UUID, List<ReasoningMapper.StoredPremiseRow>> e : reprovedRows) {
                mapper.deleteDerivations(e.getKey());
                int seq = 0;
                for (ReasoningMapper.StoredPremiseRow p : e.getValue()) {
                    mapper.insertDerivation(e.getKey(), p.premiseFactId(), p.premiseDerivedId(),
                            seq++);
                }
            }
            reproved = reprovedRows.size();

            // 证明等派生全插完再写：链上一条前提指的可能是这一批里的另一行，而这一批
            // 没有顺序可言——一趟写完的话，先轮到的那一行会指着还不存在的行
            for (Map.Entry<UUID, Wanted> e : fresh) {
                int seq = 0;
                for (UUID premise : e.getValue().premises()) {
                    UUID derivedPremise = resolveIds.get(premise);
                    mapper.insertDerivation(e.getKey(),
                            derivedPremise == null ? premise : null, derivedPremise, seq++);
                }
            }
            return new DeriveReport(head.rules(), head.edges(), head.derived(), inserted,
                    invalidated, head.capped(), head.unruled(), head.blocked(),
                    head.attributeRules(), head.ruleHits(), head.ruleCapped(), head.ruleRounds(),
                    head.ruleRoundsCapped(), reproved, redefined);
        });
    }

}
