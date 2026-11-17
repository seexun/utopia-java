package tech.seexun.jknow.web.knowledge.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.extract.PhraseAlign;
import tech.seexun.jknow.extract.PhraseAlign.Marks;
import tech.seexun.jknow.extract.PhraseAlign.PhraseChoice;
import tech.seexun.jknow.extract.PhraseAlign.PropertyCandidate;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.jobs.ProposeOntologyJob;
import tech.seexun.jknow.store.ImplicationRulesService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * {@code align_phrases} 任务（phrase_alignment.rs 核心循环逐语义）：关系短语按签名绑到
 * 属性。签名 = 短语 × 主语的类 × 宾语的类（宾语是字面值时记「值」）；<b>两票一致才绑</b>
 * （第二票候选倒序，防「选第一个」冒充一致），不一致记 undecided 留给审核（#725），没有
 * 属性对得上记 none——陈述留在开放图谱，什么都不丢。
 *
 * <p><b>边界沿革（任务 5 审查 M-3 勘误后的现状）</b>：候选按声明的定义域/值域筛（经
 * 继承命中，#807），结构候选多于 {@value #CANDIDATE_LIMIT} 不判（记 undecided 交给
 * 人）；相关度短名单（signature 向量，#1097）已随向量通道落地（{@link #shortlist}，
 * M3 任务 10）——缺省（无嵌入模型）时模型看全部结构候选，Rust 同一条退化路；收尾的
 * 「提规则」段（0044 决定 3 第五片，implication propose_rules）已随 M4 任务 5 落
 * {@link RuleProposer}，本收尾（M4 任务 8）把它接线进对齐循环：本轮判<b>绑上</b>的
 * 签名与带修饰词的类别词各攒一条问询，答案落提案（要人批）或代理驳回。
 */
@Component
public class AlignPhrasesJob {

    private static final Logger log = LoggerFactory.getLogger(AlignPhrasesJob.class);

    /** phrase_alignment.rs:36 BATCH：一次问多少条签名（12）。 */
    static final int BATCH = 12;

    /** phrase_alignment.rs:38 CANDIDATE_LIMIT：筛过定义域/值域之后候选最多这么多，再多就不判（60）。 */
    static final int CANDIDATE_LIMIT = 60;

    /** phrase_alignment.rs:473 MAX_REASK（与类别词对齐同一个数）：没判完的自己再排上限。 */
    static final int MAX_REASK = 3;

    private final JobsService jobs;
    private final javax.sql.DataSource dataSource;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final OntologyService ontology;
    private final tech.seexun.jknow.store.mapper.SignatureVectorsMapper signatureVectors;
    private final PhraseBindingsService phraseBindings;
    private final TypedMaterializeService materialize;
    private final LlmSupport llm;
    private final AppEventBus events;
    private final TypeBindingsService typeBindings;
    private final ImplicationRulesService implicationRules;
    private final RuleProposer ruleProposer;

    public AlignPhrasesJob(JobsService jobs, javax.sql.DataSource dataSource, KbMapper kbs,
                           SettingsService settingsService, OntologyService ontology,
                           tech.seexun.jknow.store.mapper.SignatureVectorsMapper signatureVectors,
                           PhraseBindingsService phraseBindings,
                           TypedMaterializeService materialize, LlmSupport llm,
                           AppEventBus events, TypeBindingsService typeBindings,
                           ImplicationRulesService implicationRules, RuleProposer ruleProposer) {
        this.jobs = jobs;
        this.dataSource = dataSource;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.ontology = ontology;
        this.signatureVectors = signatureVectors;
        this.phraseBindings = phraseBindings;
        this.materialize = materialize;
        this.llm = llm;
        this.events = events;
        this.typeBindings = typeBindings;
        this.implicationRules = implicationRules;
        this.ruleProposer = ruleProposer;
    }

    public void handle(Job job) {
        UUID kbId = UUID.fromString(job.payload().get("kb_id").asText());
        int reask = job.payload().path("reask").asInt(0);
        // 类别词对齐还排着或跑着：两端的类还没定，现在判的签名指纹马上就变，判了也是重判
        if (jobs.pendingForKb("align_types", kbId)) {
            retry(kbId, reask, Duration.ofSeconds(30), "类别词对齐");
            return;
        }
        if (jobs.pendingForKb("embed_ontology", kbId)) {
            retry(kbId, reask, Duration.ofSeconds(30), "本体向量补齐");
            return;
        }
        // 一个库同时只跑一份（并行跑会把端点打出 502）；正在跑的那份收尾时会自己看一眼
        // 有没有新东西，这里不排——排回去会和跑着的那份互相踢成死循环
        try (Connection conn = dataSource.getConnection()) {
            if (!tryLock(conn, kbId)) {
                log.info("kb={} 短语对齐已有一份在跑，这次跳过", kbId);
                return;
            }
            try {
                alignPhrasesLocked(kbId, reask);
            } finally {
                unlock(conn, kbId);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("align_phrases 咨询锁连接失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void retry(UUID kbId, int reask, Duration after, String what) {
        log.info("kb={} 短语对齐：{}还没收尾，半分钟后再看", kbId, what);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("kb_id", kbId.toString());
        if (reask != 0) {
            payload.put("reask", reask);
        }
        jobs.enqueueUnlessQueuedAfter("align_phrases", payload, after);
    }

    /** phrase_alignment.rs:523-618 的锁内主体（候选短名单与提规则按上文边界退化）。 */
    void alignPhrasesLocked(UUID kbId, int reask) throws InterruptedException {
        KbRow kb = kbs.selectById(kbId);
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        if (settings == null || !settings.chatReady()) {
            throw new IllegalStateException("Chat model not configured; cannot align phrases");
        }
        ChatClient client = LlmSupport.chatClient(settings);

        List<RelationTypeView> props = ontology.relationTypes(kbId);
        List<EntityTypeView> classes = ontology.entityTypes(kbId);
        Map<UUID, String> classKey = new HashMap<>();
        for (EntityTypeView c : classes) {
            classKey.put(c.id(), c.key());
        }
        Map<String, RelationTypeView> byKey = new HashMap<>();
        for (RelationTypeView p : props) {
            byKey.put(p.key(), p);
        }
        Map<UUID, List<UUID>> closure = closures(classes);
        Map<UUID, java.time.OffsetDateTime> versions = new HashMap<>();
        for (Map.Entry<UUID, java.time.OffsetDateTime> e : phraseBindings.propertyVersions(kbId)
                .entrySet()) {
            versions.put(e.getKey(), e.getValue());
        }
        List<PhraseSignature> sigs = phraseBindings.signatures(kbId);
        Map<java.util.List<Object>, Binding> existing = new HashMap<>();
        for (Binding b : phraseBindings.bindings(kbId)) {
            existing.put(b.key(), b);
        }
        // 每条活着的签名此刻的候选与指纹。候选按继承命中，多了再按相关度开短名单；指纹
        // 是判定看到的全部输入（0053），短名单也算在内——名单变了就再问
        Map<java.util.List<Object>, Considered> full = consider(sigs, props, closure, versions, null);
        Map<java.util.List<Object>, List<UUID>> shortlists = shortlist(settings, kbId, sigs, full);
        Map<java.util.List<Object>, Considered> considered = consider(sigs, props, closure,
                versions, shortlists);
        // 过期 = 存下的指纹和此刻的不一样；人的判定不重判。绑到状态属性、从没问过一刻标
        // 哪一端的代理判定也问（#966）
        List<PhraseSignature> todo = new ArrayList<>();
        for (PhraseSignature s : sigs) {
            Binding b = existing.get(s.key());
            Considered c = considered.get(s.key());
            if (b == null) {
                todo.add(s);
            } else if (!"person".equals(b.decidedBy())
                    && (!c.basis().equals(b.basis()) || awaitsMarks(b, props))) {
                todo.add(s);
            }
        }
        // 其中指纹没变、只为补问 marks 进来的：这一问只能写 marks，绑定原样（0053 修订）
        Map<java.util.List<Object>, Binding> marksOnly = new HashMap<>();
        for (PhraseSignature s : todo) {
            Binding b = existing.get(s.key());
            if (b != null && considered.get(s.key()).basis().equals(b.basis())
                    && awaitsMarks(b, props)) {
                marksOnly.put(s.key(), b);
            }
        }
        log.info("kb={} 短语对齐开始：signatures={} to_decide={} properties={}", kbId, sigs.size(),
                todo.size(), props.size());

        // 没有属性可绑：每条都是「没有」；属性出现后指纹变了，它们会再交回来
        if (props.isEmpty()) {
            for (PhraseSignature s : todo) {
                phraseBindings.decide(kbId, s, new PhraseBindingsService.Decision(null, null,
                        "none", JsonNodeFactory.instance.objectNode().put("reason",
                                "no_properties"),
                        "agent", considered.get(s.key()).basis(), null, false));
            }
            return;
        }

        int bound = 0;
        int none = 0;
        int undecided = 0;
        int skipped = 0;
        int failed = 0;
        int unanswered = 0;
        int marked = 0;
        int unsettled = 0;
        for (int from = 0; from < todo.size(); from += BATCH) {
            List<PhraseSignature> batch = todo.subList(from, Math.min(from + BATCH, todo.size()));
            // 候选超过上限的不问模型：记成 undecided 交给人，指纹照记——属性少下去指纹就变
            List<List<RelationTypeView>> cands = new ArrayList<>();
            for (PhraseSignature s : batch) {
                List<RelationTypeView> fitting = considered.get(s.key()).fitting();
                cands.add(fitting.size() > CANDIDATE_LIMIT ? List.of() : new ArrayList<>(fitting));
            }
            // 一票 = (key, direction, marks)
            List<Vote[]> votes = new ArrayList<>();
            List<boolean[]> answered = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                votes.add(new Vote[2]);
                answered.add(new boolean[2]);
            }
            // 读得出回复的遍数：补问 marks 时，两遍的回复都回来了才算问过
            int[] replied = {0};
            for (int pass = 0; pass < 2; pass++) {
                List<PhraseAlign.PhraseItem> items = new ArrayList<>();
                List<Integer> itemIdx = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    if (cands.get(i).isEmpty()) {
                        continue;
                    }
                    boolean secondDue = secondVoteIsDue(votes.get(i)[0], answered.get(i)[0],
                            marksOnly.containsKey(batch.get(i).key()));
                    if (pass == 1 && !secondDue) {
                        continue;
                    }
                    PhraseSignature s = batch.get(i);
                    List<RelationTypeView> list = new ArrayList<>(cands.get(i));
                    if (pass == 1) {
                        java.util.Collections.reverse(list);
                    }
                    List<PropertyCandidate> candidates = new ArrayList<>();
                    for (RelationTypeView p : list) {
                        candidates.add(new PropertyCandidate(p.key(), p.label(), p.description(),
                                p.kind(), p.temporal(), keysOf(p.domains(), classKey),
                                keysOf(p.ranges(), classKey),
                                viaOf(p, s, closure, classKey)));
                    }
                    // 结构对得上却没进短名单的键：模型若从批里的属性表选了它，也算票
                    List<String> alsoAllowed = new ArrayList<>();
                    for (RelationTypeView p : full.get(s.key()).fitting()) {
                        boolean inBatch = false;
                        for (RelationTypeView c : list) {
                            if (c.id().equals(p.id())) {
                                inBatch = true;
                                break;
                            }
                        }
                        if (!inBatch) {
                            alsoAllowed.add(p.key());
                        }
                    }
                    items.add(new PhraseAlign.PhraseItem(i, s.phrase(), s.subjectTypeKey(),
                            s.objectTypeKey(), s.objectIsValue(), s.count(), s.examples(),
                            s.quotes(), candidates, alsoAllowed));
                    itemIdx.add(i);
                }
                if (items.isEmpty()) {
                    continue;
                }
                tech.seexun.jknow.llm.ChatClient.Reply reply;
                try {
                    reply = llm.chatRetryingRateLimits(settings, client,
                            PhraseAlign.buildPhraseMessages(items), 0.0d);
                } catch (Exception e) {
                    log.warn("kb={} 短语对齐调用失败，这一批留到下次: {}", kbId, e.getMessage());
                    failed++;
                    continue;
                }
                PhraseAlign.PhraseReply parsed;
                try {
                    parsed = PhraseAlign.parsePhraseResponse(reply.text(), items);
                } catch (Exception e) {
                    log.warn("kb={} 短语对齐回复解析失败，这一批留到下次: {}", kbId, e.getMessage());
                    failed++;
                    continue;
                }
                skipped += parsed.malformed();
                if (parsed.choices().isEmpty()) {
                    log.warn("kb={} 短语对齐回复读不出一条（{} 字），这一批留到下次", kbId,
                            reply.text().length());
                    failed++;
                    continue;
                }
                replied[0]++;
                for (PhraseChoice c : parsed.choices()) {
                    if (c.id() >= 0 && c.id() < votes.size()) {
                        votes.get((int) c.id())[pass] = Vote.of(c);
                        answered.get((int) c.id())[pass] = true;
                    }
                }
            }
            for (int i = 0; i < batch.size(); i++) {
                PhraseSignature s = batch.get(i);
                String basis = considered.get(s.key()).basis();
                Binding asked = marksOnly.get(s.key());
                if (asked != null) {
                    // 补问 marks（0053 修订）：两票都认这条绑定的属性与方向、又说了同一个
                    // 值，写下它；回复没回来不算问过，下次再问；问不了的（候选超限）交给人
                    if (!cands.get(i).isEmpty() && replied[0] < 2) {
                        unanswered++;
                        continue;
                    }
                    String value = valueOf(votes.get(i)[0], asked, byKey);
                    String valueB = valueOf(votes.get(i)[1], asked, byKey);
                    if (value != null && !value.equals(valueB)) {
                        value = null;
                    }
                    if (phraseBindings.recordMarks(kbId, asked, value)) {
                        if (value != null) {
                            marked++;
                        } else {
                            unsettled++;
                        }
                    }
                    continue;
                }
                if (cands.get(i).isEmpty()) {
                    int fitting = considered.get(s.key()).fitting().size();
                    String status;
                    ObjectNode votesJson = JsonNodeFactory.instance.objectNode();
                    if (fitting == 0) {
                        status = "none";
                        votesJson.put("reason", "no_candidates");
                    } else {
                        status = "undecided";
                        votesJson.putNull("first");
                        votesJson.putNull("second");
                        votesJson.put("reason", "too_many_candidates");
                        votesJson.put("candidates", fitting);
                    }
                    if (phraseBindings.decide(kbId, s, new PhraseBindingsService.Decision(null,
                            null, status, votesJson, "agent", basis, null, false))) {
                        if ("none".equals(status)) {
                            none++;
                        } else {
                            undecided++;
                        }
                    }
                    skipped++;
                    continue;
                }
                Vote a = votes.get(i)[0];
                Vote b = votes.get(i)[1];
                // 第一票说没有的不投第二票（first_vote_none）：结论就是没有
                boolean askedTwice = secondVoteIsDue(a, answered.get(i)[0], false);
                if (!answered.get(i)[0] || (askedTwice && !answered.get(i)[1])) {
                    // 有一票没答到：不下结论，下次再问
                    unanswered++;
                    continue;
                }
                ObjectNode record = JsonNodeFactory.instance.objectNode();
                if (askedTwice) {
                    record.set("first", a != null ? a.json() : NullNode);
                    record.set("second", b != null ? b.json() : NullNode);
                } else {
                    record.putNull("first");
                    record.put("reason", "first_vote_none");
                }
                boolean agree = Vote.agree(a, b);
                if (!agree) {
                    phraseBindings.decide(kbId, s, new PhraseBindingsService.Decision(null, null,
                            "undecided", record, "agent", basis, null, false));
                    undecided++;
                    continue;
                }
                // 属性是状态时，两票还说一刻标哪一端（#966）：说了同一个值才写下它
                boolean isState = a != null && a.key() != null
                        && "state".equals(byKey.get(a.key()) != null
                                ? byKey.get(a.key()).temporal() : null);
                String marks = null;
                if (isState && a.marks() != null && a.marks().equals(b.marks())) {
                    marks = a.marks();
                }
                if (a != null && a.key() != null) {
                    RelationTypeView p = byKey.get(a.key());
                    if (phraseBindings.decide(kbId, s, new PhraseBindingsService.Decision(p.id(),
                            a.direction(), "bound", record, "agent", basis, marks, isState))) {
                        bound++;
                        if (isState && marks == null) {
                            unsettled++;
                        }
                    }
                } else {
                    if (phraseBindings.decide(kbId, s, new PhraseBindingsService.Decision(null,
                            null, "none", record, "agent", basis, null, false))) {
                        none++;
                    }
                }
            }
        }
        log.info("kb={} 短语对齐完成：bound={} none={} undecided={} skipped={} failed={} "
                + "unanswered={} marked={} unsettled={}", kbId, bound, none, undecided, skipped,
                failed, unanswered, marked, unsettled);
        if (unanswered > 0) {
            log.warn("kb={} 短语对齐有 {} 条签名模型没答到，这些签名这轮没有结论", kbId,
                    unanswered);
        }
        // 提规则（0044 决定 3 第五片，phrase_alignment.rs:1006-1130）：本轮刚判过的签名，
        // 和带类别词的东西，问模型「这种形状还蕴含什么」。只问本轮判过的：指纹没变的形状
        // 上一轮已经问过，答案（提案或代理驳回）还在 implication_rules 里；指纹变了它就
        // 在 todo 里，自然再问
        proposeAfterAlignment(kbId, settings, client, props, considered, todo, marksOnly,
                classKey, byKey, versions);
        // 绑定定了，视图跟着算：绑上的签名下的陈述成类型化行，绑定变了的行作废（0067）
        TypedMaterializeService.Outcome typed = materialize.materialize(kbId);
        log.info("kb={} 类型化事实按绑定算完：added={} merged={} retired={}", kbId, typed.added(),
                typed.merged(), typed.retired());
        if (typed.added() > 0 || typed.merged() > 0 || typed.retired() > 0) {
            events.emitGraph(kbId);
        }
        // 这一轮跑着的时候世界没停：**重新加载**再比（快照就是判定写下的那份指纹，跟它比
        // 永远相等）。只看活着的签名：旧签名的行没有陈述可判，它永远「过期」却永远不可执行
        boolean changed;
        {
            List<RelationTypeView> propsNow = ontology.relationTypes(kbId);
            List<EntityTypeView> classesNow = ontology.entityTypes(kbId);
            Map<UUID, List<UUID>> closureNow = closures(classesNow);
            Map<UUID, java.time.OffsetDateTime> versionsNow = phraseBindings.propertyVersions(kbId);
            // 短名单沿用开跑时算的那份：向量没变，名单就没变；变了的签名本轮之后自然再问
            Map<java.util.List<Object>, Considered> nowConsidered = consider(
                    phraseBindings.signatures(kbId), propsNow, closureNow, versionsNow,
                    shortlists);
            Map<java.util.List<Object>, Binding> now = new HashMap<>();
            for (Binding b : phraseBindings.bindings(kbId)) {
                now.put(b.key(), b);
            }
            changed = false;
            for (PhraseSignature s : phraseBindings.signatures(kbId)) {
                Binding b = now.get(s.key());
                if (b == null) {
                    changed = !attempted(todo, s);
                } else if (!"person".equals(b.decidedBy())
                        && !nowConsidered.get(s.key()).basis().equals(b.basis())) {
                    changed = true;
                }
                if (changed) {
                    break;
                }
            }
        }
        boolean unfinished = failed > 0 || unanswered > 0;
        if (changed) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("kb_id", kbId.toString());
            jobs.enqueueUnlessQueued("align_phrases", payload);
        } else if (unfinished && reask < MAX_REASK) {
            Duration delay = Duration.ofSeconds(20L * (reask + 1));
            log.info("kb={} 短语对齐没判完（failed={} unanswered={}），{} 秒后再问", kbId, failed,
                    unanswered, delay.toSeconds());
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("kb_id", kbId.toString());
            payload.put("reask", reask + 1);
            jobs.enqueueUnlessPending("align_phrases", payload, delay);
        } else if (unfinished) {
            log.warn("kb={} 短语对齐问了几轮仍没判完，等下一篇文档或本体改动再问", kbId);
        }
        // 对齐收尾：没绑上的形状够多，叫本体代理来看（0061 决定 2；phrase_alignment.rs
        // :1186-1196）。代理自己跳过提过的，所以这里只数不筛；一分钟的去抖让连着几篇
        // 文档只叫一次（M5 任务 5 随 propose_ontology 真身接上）
        if (!changed && !unfinished) {
            long unbound = phraseBindings.bindings(kbId).stream()
                    .filter(b -> "none".equals(b.status()) || "undecided".equals(b.status()))
                    .count();
            if (unbound >= ProposeOntologyJob.TRIGGER_UNBOUND) {
                ObjectNode payload = JsonNodeFactory.instance.objectNode();
                payload.put("kb_id", kbId.toString());
                jobs.enqueueUnlessPending("propose_ontology", payload, Duration.ofMinutes(1));
            }
        }
    }

    private static final JsonNode NullNode = com.fasterxml.jackson.databind.node.NullNode
            .getInstance();

    // ---- 提规则收尾（phrase_alignment.rs:1006-1130；RuleProposer=implication.rs 侧） ----

    /**
     * 对齐收尾的提规则段（phrase_alignment.rs:1006-1130）：短语侧只问本轮判
     * <b>绑上</b>的签名（判「无」的形状一轮 1121 条问下来提了不到 1% 的规则，却占了
     * 提规则一半以上的调用——bench README 2026-09-24；拿不定的等人先定），候选排除已
     * 绑的那个；类别词侧问还没问过、带修饰词的词，候选是全部属性（短名单按嵌入近邻裁）。
     * 提完有落库的提案就 emit_review；失败只记不炸任务（下一轮再提）。
     */
    private void proposeAfterAlignment(UUID kbId, LlmSettingsRow settings, ChatClient client,
            List<RelationTypeView> props,
            Map<java.util.List<Object>, Considered> considered, List<PhraseSignature> todo,
            Map<java.util.List<Object>, Binding> marksOnly, Map<UUID, String> classKey,
            Map<String, RelationTypeView> byKey,
            Map<UUID, java.time.OffsetDateTime> versions) {
        // 判完重读一遍绑定（判「绑上」要看刚写下的行）
        Map<java.util.List<Object>, Binding> decidedNow = new HashMap<>();
        for (Binding b : phraseBindings.bindings(kbId)) {
            decidedNow.put(b.key(), b);
        }
        List<tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature> kindWords =
                typeBindings.signatures(kbId);
        Set<String> askedKind = new HashSet<>();
        for (var r : implicationRules.list(kbId, null)) {
            if ("kind_word".equals(r.trigger())) {
                askedKind.add(r.phrase());
            }
        }
        List<RuleProposer.RuleAsk> asks = new ArrayList<>();
        for (PhraseSignature s : todo) {
            // 只补问了 marks 的没有重判：指纹没变，它的规则上一轮问过
            if (marksOnly.containsKey(s.key())) {
                continue;
            }
            Binding b = decidedNow.get(s.key());
            if (b == null || !"bound".equals(b.status())) {
                continue;
            }
            String boundTo = null;
            for (RelationTypeView p : props) {
                if (p.id().equals(b.relationTypeId())) {
                    boundTo = p.key();
                    break;
                }
            }
            List<RelationTypeView> candidates = new ArrayList<>();
            for (RelationTypeView p : considered.get(s.key()).fitting()) {
                if (!p.key().equals(boundTo)) {
                    candidates.add(p);
                }
            }
            asks.add(new RuleProposer.RuleAsk(s, null, boundTo, candidates,
                    considered.get(s.key()).basis()));
        }
        // 类别词：每个词问一次；候选是主语能落在它绑到的类（或没声明）的关系属性
        Map<String, List<UUID>> kindShort = shortlistKindWords(settings, kbId, kindWords,
                askedKind);
        List<Object[]> versionRows = new ArrayList<>(versions.size());
        for (Map.Entry<UUID, java.time.OffsetDateTime> e : versions.entrySet()) {
            versionRows.add(new Object[]{e.getKey(), e.getValue()});
        }
        for (var k : kindWords) {
            if (askedKind.contains(k.kindWord()) || !hasModifier(k.kindWord())) {
                continue;
            }
            List<RelationTypeView> candidates = new ArrayList<>();
            for (RelationTypeView p : props) {
                if ("relation".equals(p.kind()) || "attribute".equals(p.kind())) {
                    candidates.add(p);
                }
            }
            List<UUID> keep = kindShort.get(k.kindWord());
            if (keep != null) {
                candidates.removeIf(p -> !keep.contains(p.id()));
                candidates.sort(java.util.Comparator.comparingInt(p -> keep.indexOf(p.id())));
            }
            asks.add(new RuleProposer.RuleAsk(null, k, null, candidates,
                    PhraseBindingsService.basisOf(List.of(), List.of(), false, versionRows)
                            + ":" + k.kindWord()));
        }
        if (asks.isEmpty()) {
            return;
        }
        try {
            RuleProposer.Result r = ruleProposer.proposeRules(kbId, settings, client, asks,
                    classKey, byKey);
            log.info("kb={} 提规则完成：asked={} proposed={} failed={}", kbId, asks.size(),
                    r.proposed(), r.failed());
            if (r.proposed() > 0) {
                events.emitReview(kbId);
            }
        } catch (Exception e) {
            log.warn("kb={} 提规则失败，下一轮再提: {}", kbId, e.getMessage());
        }
    }

    /** phrase_alignment.rs:203-205 has_modifier：词里有非 ASCII 字母的字符（空格/数字/连字符…）才算带修饰——裸名词不问。 */
    static boolean hasModifier(String kindWord) {
        for (char c : kindWord.trim().toCharArray()) {
            boolean asciiAlpha = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!asciiAlpha) {
                return true;
            }
        }
        return false;
    }

    /**
     * phrase_alignment.rs:417-464 shortlist_kind_words：类别词的候选短名单。词加例名
     * 嵌入后与属性向量比近，留最近的 {@value #SHORTLIST} 条。没配嵌入模型、一条带向量
     * 的属性都没有、这一批没嵌出来、或近邻为空——都不进表，模型看全部候选。与签名的
     * shortlist 不同，这里不存 signature_vectors 缓存（每轮直接嵌）。
     */
    private Map<String, List<UUID>> shortlistKindWords(LlmSettingsRow settings, UUID kbId,
            List<tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature> kindWords,
            Set<String> askedKind) {
        Map<String, List<UUID>> out = new HashMap<>();
        tech.seexun.jknow.llm.EmbedClient embed = LlmSupport.embedClient(settings);
        if (embed == null) {
            return out;
        }
        // 还没问过、带修饰词的才有资格进短名单（问过的本轮直接跳过，嵌了也用不上）
        List<tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature> fresh =
                new ArrayList<>();
        for (var k : kindWords) {
            if (!askedKind.contains(k.kindWord()) && hasModifier(k.kindWord())) {
                fresh.add(k);
            }
        }
        // 一条带向量的属性都没有，嵌了也是空手而回（理由同 shortlist）
        if (fresh.isEmpty() || !ontology.hasRelationTypeVectors(kbId, null)) {
            return out;
        }
        for (int from = 0; from < fresh.size(); from += SHORTLIST_EMBED_BATCH) {
            List<tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature> batch =
                    new ArrayList<>(fresh.subList(from,
                            Math.min(from + SHORTLIST_EMBED_BATCH, fresh.size())));
            List<String> texts = new ArrayList<>(batch.size());
            for (var k : batch) {
                texts.add(k.kindWord() + " · " + String.join(", ", k.examples()));
            }
            List<float[]> vectors;
            try {
                vectors = embed.embed(texts);
            } catch (Exception e) {
                log.warn("kb={} 类别词向量没算出来，这一批看全部候选: {}", kbId, e.getMessage());
                continue;
            }
            if (vectors.size() != batch.size()) {
                log.warn("kb={} 类别词向量数量对不上，这一批看全部候选", kbId);
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                List<UUID> near = ontology.nearestRelationTypeIds(kbId,
                        vectorText(vectors.get(i)), SHORTLIST, null);
                if (!near.isEmpty()) {
                    out.put(batch.get(i).kindWord(), near);
                }
            }
        }
        return out;
    }

    private static boolean attempted(List<PhraseSignature> todo, PhraseSignature s) {
        for (PhraseSignature t : todo) {
            if (t.key().equals(s.key())) {
                return true;
            }
        }
        return false;
    }

    // ---- 候选与指纹（phrase_alignment.rs:60-156） ----

    /** 一条签名此刻的候选（经继承命中）与指纹（0053）。 */
    record Considered(List<RelationTypeView> fitting, String basis) {
    }

    /**
     * phrase_alignment.rs:105-111 structurally_fits 的包外出口（M5 任务 5——本体代理
     * 的词表按批裁要同一套判据；pub(crate) 的 Java 等价）。
     */
    public static boolean structurallyFits(RelationTypeView p, PhraseSignature s,
                                           Map<UUID, List<UUID>> closure) {
        return fits(p, s, closure) != null;
    }

    /** closures 的包外出口（同上——词表裁剪与本文件共用一份祖先闭包）。 */
    public static Map<UUID, List<UUID>> closuresOf(List<EntityTypeView> classes) {
        return closures(classes);
    }

    /** phrase_alignment.rs:62-82 closures：一个类连同它的全部祖先（UNION 语义，同一祖先只进一次）。 */
    static Map<UUID, List<UUID>> closures(List<EntityTypeView> classes) {
        Map<UUID, List<UUID>> parents = new HashMap<>();
        for (EntityTypeView c : classes) {
            parents.put(c.id(), c.parents());
        }
        Map<UUID, List<UUID>> out = new HashMap<>();
        for (EntityTypeView c : classes) {
            List<UUID> seen = new ArrayList<>();
            seen.add(c.id());
            List<UUID> stack = new ArrayList<>(c.parents());
            while (!stack.isEmpty()) {
                UUID p = stack.remove(stack.size() - 1);
                if (seen.contains(p)) {
                    continue;
                }
                seen.add(p);
                List<UUID> pp = parents.get(p);
                if (pp != null) {
                    stack.addAll(pp);
                }
            }
            seen.sort(null);
            out.put(c.id(), seen);
        }
        return out;
    }

    /**
     * phrase_alignment.rs:103-156 consider（短名单退化为空——模型看全部结构候选）：筛
     * 定义域/值域（经继承命中），指纹 = phrase_bindings::basis_of。
     */
    /** phrase_alignment.rs:261 SHORTLIST / SHORTLIST_EMBED_BATCH。 */
    static final int SHORTLIST = 10;
    static final int SHORTLIST_EMBED_BATCH = 32;

    /**
     * phrase_alignment.rs:277-397 shortlist：按相关度给候选多的签名开短名单。签名的文本
     * （短语加一条例句）嵌入后与属性的向量比近（embed_ontology 建的那份），留最近的
     * SHORTLIST 条；标签里的词出现在短语里的属性无论远近都留着。没配嵌入模型、属性还没
     * 向量、或候选本来就不多的签名不进表——那时模型看的还是全部结构候选。签名的向量按
     * (库, 嵌入模型, 文本的哈希) 存在 signature_vectors 里，一轮只嵌没见过的（#1097）。
     */
    private Map<java.util.List<Object>, List<UUID>> shortlist(LlmSettingsRow settings, UUID kbId,
            List<PhraseSignature> sigs, Map<java.util.List<Object>, Considered> full) {
        Map<java.util.List<Object>, List<UUID>> out = new HashMap<>();
        tech.seexun.jknow.llm.EmbedClient embed = LlmSupport.embedClient(settings);
        String model = settings.embedModel();
        if (embed == null || model == null) {
            return out;
        }
        List<PhraseSignature> wide = new ArrayList<>();
        for (PhraseSignature s : sigs) {
            if (full.get(s.key()).fitting().size() > SHORTLIST) {
                wide.add(s);
            }
        }
        List<String> texts = new ArrayList<>();
        for (PhraseSignature s : wide) {
            texts.add(signatureText(s));
        }
        List<String> hashes = new ArrayList<>();
        for (String t : texts) {
            hashes.add(hex(sha256(t)));
        }
        String[] hashArray = hashes.toArray(new String[0]);
        // 这一轮不再出现的文本、别的模型嵌的向量删掉。按全部候选多的签名留，不只按嵌了的
        signatureVectors.prune(kbId, model, hashArray);
        if (wide.isEmpty()) {
            return out;
        }
        // 先问这一类属性有没有向量，没有的签名不嵌：嵌了最近邻也是空，照样看全部（#1097）
        Map<String, Boolean> hasVectors = new HashMap<>();
        for (String kind : new String[]{"relation", "attribute"}) {
            boolean any = false;
            for (PhraseSignature s : wide) {
                if (propertyKind(s).equals(kind)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                hasVectors.put(kind, ontology.hasRelationTypeVectors(kbId, kind));
            }
        }
        List<Integer> usable = new ArrayList<>();
        for (int i = 0; i < wide.size(); i++) {
            if (Boolean.TRUE.equals(hasVectors.get(propertyKind(wide.get(i))))) {
                usable.add(i);
            }
        }
        Map<String, float[]> vectors = new HashMap<>();
        for (var row : signatureVectors.selectByHashes(kbId, model, hashArray)) {
            vectors.put(row.hash(), parseVector(row.vector()));
        }
        java.util.function.Function<Integer, String> hashKey = hashes::get;
        // 没存过的才嵌；两条签名文本相同只嵌一次
        java.util.Set<String> queued = new java.util.HashSet<>();
        List<Integer> missing = new ArrayList<>();
        for (int i : usable) {
            if (!vectors.containsKey(hashKey.apply(i)) && queued.add(hashKey.apply(i))) {
                missing.add(i);
            }
        }
        for (int from = 0; from < missing.size(); from += SHORTLIST_EMBED_BATCH) {
            List<Integer> batch = missing.subList(from, Math.min(from + SHORTLIST_EMBED_BATCH,
                    missing.size()));
            List<String> batchTexts = new ArrayList<>();
            for (int i : batch) {
                batchTexts.add(texts.get(i));
            }
            List<float[]> got;
            try {
                got = embed.embed(batchTexts);
            } catch (Exception e) {
                log.warn("kb={} 签名向量没算出来，这一批不开短名单: {}", kbId, e.getMessage());
                continue;
            }
            if (got.size() != batch.size()) {
                log.warn("kb={} sent={} got={} 签名向量数量对不上，这一批不开短名单", kbId,
                        batch.size(), got.size());
                continue;
            }
            for (int b = 0; b < batch.size(); b++) {
                int i = batch.get(b);
                signatureVectors.insertVector(kbId, model, hashes.get(i),
                        vectorText(got.get(b)));
                vectors.put(hashKey.apply(i), got.get(b));
            }
        }
        for (int i : usable) {
            PhraseSignature s = wide.get(i);
            // 这一批没嵌出来：不开短名单，模型看全部
            float[] vector = vectors.get(hashKey.apply(i));
            if (vector == null) {
                continue;
            }
            List<RelationTypeView> fitting = full.get(s.key()).fitting();
            List<UUID> near = ontology.nearestRelationTypeIds(kbId, vectorText(vector),
                    fitting.size() * 2L, propertyKind(s));
            if (near.isEmpty()) {
                // 属性还没有向量：不开短名单，模型看全部
                continue;
            }
            List<UUID> fittingIds = new ArrayList<>();
            List<UUID> mustKeep = new ArrayList<>();
            for (RelationTypeView p : fitting) {
                fittingIds.add(p.id());
                if (labelInPhrase(p.label(), s.phrase())) {
                    mustKeep.add(p.id());
                }
            }
            out.put(s.key(), pickShortlist(near, fittingIds, mustKeep, SHORTLIST));
        }
        return out;
    }

    /** phrase_alignment.rs:399-404 signature_text：短语加第一条例句和它的引文。 */
    static String signatureText(PhraseSignature s) {
        String example = s.examples().isEmpty() ? null : s.examples().get(0);
        String quote = s.quotes().isEmpty() ? null : s.quotes().get(0);
        if (example != null && quote != null) {
            return s.phrase() + " · " + example + " · " + quote;
        }
        if (example != null) {
            return s.phrase() + " · " + example;
        }
        return s.phrase();
    }

    /** phrase_alignment.rs:407-413 property_kind：宾语是字面值的绑属性，否则绑关系。 */
    static String propertyKind(PhraseSignature s) {
        return s.objectIsValue() ? "attribute" : "relation";
    }

    /** phrase_alignment.rs:466-472 label_in_phrase：标签里有一个像样的词（四个字母以上）出现在短语里。 */
    static boolean labelInPhrase(String label, String phrase) {
        String lower = phrase.toLowerCase(java.util.Locale.ROOT);
        for (String w : label.toLowerCase(java.util.Locale.ROOT)
                .split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && lower.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** phrase_alignment.rs:475-487 pick_shortlist：先是标签对上的（无论远近），再按向量距离补到上限。 */
    static List<UUID> pickShortlist(List<UUID> near, List<UUID> fitting, List<UUID> mustKeep,
                                    int limit) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : mustKeep) {
            if (fitting.contains(id)) {
                out.add(id);
            }
        }
        for (UUID id : near) {
            if (out.size() >= limit) {
                break;
            }
            if (fitting.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** float[] → pgvector 文本形态（写入/近邻查询共用）。 */
    static String vectorText(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** pgvector 文本形态 → float[]（读出侧）。 */
    static float[] parseVector(String text) {
        String inner = text.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        java.util.StringTokenizer tok = new java.util.StringTokenizer(inner, ",");
        float[] out = new float[tok.countTokens()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Float.parseFloat(tok.nextToken().trim());
        }
        return out;
    }

    static Map<java.util.List<Object>, Considered> consider(List<PhraseSignature> sigs,
                                              List<RelationTypeView> props,
                                              Map<UUID, List<UUID>> closure,
                                              Map<UUID, java.time.OffsetDateTime> versions,
                                              Map<java.util.List<Object>, List<UUID>> shortlist) {
        Map<java.util.List<Object>, Considered> out = new HashMap<>();
        for (PhraseSignature s : sigs) {
            List<RelationTypeView> fitting = new ArrayList<>();
            for (RelationTypeView p : props) {
                if (fits(p, s, closure) != null) {
                    fitting.add(p);
                }
            }
            // 结构对得上的太多时只留最近的几条（见 shortlist），按相关度排：第一票先看最像的
            List<UUID> keep = shortlist == null ? null : shortlist.get(s.key());
            if (keep != null) {
                fitting.removeIf(p -> !keep.contains(p.id()));
                fitting.sort(java.util.Comparator.comparingInt(p -> keep.indexOf(p.id())));
            }
            List<Object[]> cands = new ArrayList<>();
            for (RelationTypeView p : fitting) {
                java.time.OffsetDateTime at = versions.get(p.id());
                if (at != null) {
                    cands.add(new Object[]{p.id(), at});
                }
            }
            String basis = PhraseBindingsService.basisOf(closure.getOrDefault(s.subjectTypeId(),
                    List.of()), closure.getOrDefault(s.objectTypeId(), List.of()),
                    s.objectIsValue(), cands);
            out.put(s.key(), new Considered(fitting, basis));
        }
        return out;
    }

    /**
     * phrase_alignment.rs:98-121 within：声明的类里有没有一个是这一端的类或其祖先。没声明
     * 不限；这一端没绑到类时只被没声明的接受。返回命中的 (声明的类, 这一端的类)——null
     * 表示不命中，OUTER 表示直接命中/没声明。
     */
    private static final UUID[] DIRECT = new UUID[0];

    private static UUID[] within(List<UUID> declared, UUID cls, Map<UUID, List<UUID>> closure) {
        if (declared.isEmpty()) {
            return DIRECT;
        }
        if (cls == null) {
            return null;
        }
        if (declared.contains(cls)) {
            return DIRECT;
        }
        List<UUID> up = closure.get(cls);
        if (up == null) {
            return null;
        }
        for (UUID d : declared) {
            if (up.contains(d)) {
                return new UUID[]{d, cls};
            }
        }
        return null;
    }

    /**
     * phrase_alignment.rs:123-156 fits：签名两端的类落在属性声明的域/值域里，经继承也算；
     * 正反两个方向都算（绑定可以是反向的）。
     */
    static List<UUID[]> fits(RelationTypeView p, PhraseSignature s,
                             Map<UUID, List<UUID>> closure) {
        List<UUID[]> via = new ArrayList<>();
        if (s.objectIsValue()) {
            if (!"attribute".equals(p.kind())) {
                return null;
            }
            UUID[] hit = within(p.domains(), s.subjectTypeId(), closure);
            if (hit == null) {
                return null;
            }
            if (hit != DIRECT) {
                via.add(hit);
            }
            return via;
        }
        if (!"relation".equals(p.kind())) {
            return null;
        }
        UUID[] fd = within(p.domains(), s.subjectTypeId(), closure);
        UUID[] fr = within(p.ranges(), s.objectTypeId(), closure);
        UUID[] rd = within(p.domains(), s.objectTypeId(), closure);
        UUID[] rr = within(p.ranges(), s.subjectTypeId(), closure);
        if (fd != null && fr != null) {
            if (fd != DIRECT) {
                via.add(fd);
            }
            if (fr != DIRECT) {
                via.add(fr);
            }
            return via;
        }
        if (rd != null && rr != null) {
            if (rd != DIRECT) {
                via.add(rd);
            }
            if (rr != DIRECT) {
                via.add(rr);
            }
            return via;
        }
        return null;
    }

    /** phrase_alignment.rs:51-59 awaits_marks：绑到状态属性、从没问过 marks 的代理判定。 */
    static boolean awaitsMarks(Binding b, List<RelationTypeView> props) {
        if (!"bound".equals(b.status()) || b.marks() != null || b.marksAskedAt() != null) {
            return false;
        }
        if (b.relationTypeId() == null) {
            return false;
        }
        for (RelationTypeView p : props) {
            if (p.id().equals(b.relationTypeId())) {
                return "state".equals(p.temporal());
            }
        }
        return false;
    }

    /** phrase_alignment.rs:170-181 second_vote_is_due：第一票说没有的第二票怎么答都绑不上，省下。 */
    static boolean secondVoteIsDue(Vote first, boolean firstAnswered, boolean marksOnly) {
        return marksOnly || (firstAnswered && first != null);
    }

    /** 一票：这条签名选了哪个属性、哪个方向，状态属性还有一刻标哪一端（null = 没有）。 */
    record Vote(String key, String direction, String marks) {

        static Vote of(PhraseChoice c) {
            if (c.property() == null) {
                return null;
            }
            return new Vote(c.property().key(), c.property().direction().asString(),
                    c.marks() != null ? c.marks().asString() : null);
        }

        static boolean agree(Vote a, Vote b) {
            if (a == null && b == null) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            return a.key.equals(b.key) && a.direction.equals(b.direction);
        }

        JsonNode json() {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.put("property", key);
            n.put("direction", direction);
            if (marks != null) {
                n.put("marks", marks);
            } else {
                n.putNull("marks");
            }
            return n;
        }
    }

    /** 补问 marks 的一票认的是这条绑定、又说了值：认了就是它说的值。 */
    private static String valueOf(Vote v, Binding asked, Map<String, RelationTypeView> byKey) {
        if (v == null || v.marks == null) {
            return null;
        }
        RelationTypeView p = byKey.get(v.key);
        if (p == null || !p.id().equals(asked.relationTypeId())) {
            return null;
        }
        if (!v.direction.equals(asked.direction())) {
            return null;
        }
        return v.marks;
    }

    private static List<String> keysOf(List<UUID> ids, Map<UUID, String> classKey) {
        List<String> out = new ArrayList<>();
        for (UUID id : ids) {
            String k = classKey.get(id);
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    /** 「X is a subclass of Y」——按继承命中的依据写给模型看（#807）。 */
    private static List<String> viaOf(RelationTypeView p, PhraseSignature s,
                                      Map<UUID, List<UUID>> closure, Map<UUID, String> classKey) {
        List<UUID[]> via = fits(p, s, closure);
        List<String> out = new ArrayList<>();
        if (via == null) {
            return out;
        }
        for (UUID[] pair : via) {
            out.add((classKey.getOrDefault(pair[1], "?")) + " is a subclass of "
                    + classKey.getOrDefault(pair[0], "?"));
        }
        return out;
    }

    private static boolean tryLock(Connection conn, UUID kbId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtext('align_phrases'), hashtext(?))")) {
            ps.setString(1, kbId.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection conn, UUID kbId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_advisory_unlock(hashtext('align_phrases'), hashtext(?))")) {
            ps.setString(1, kbId.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }
}
