package tech.seexun.jknow.web.knowledge.jobs;

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
import tech.seexun.jknow.extract.Align;
import tech.seexun.jknow.extract.Align.KindWordItem;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.TypeBindingsService.Acceptance;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * {@code align_types} 任务（main.rs:570-584 → type_alignment.rs:112-159 + 对齐循环）：类别词
 * 绑到类（0044 对齐的第一片）——两票一致才绑（第二票候选倒序，防「选第一个」冒充一致），
 * 不一致记 undecided 留给审核（#725 队列 2），没有类对得上的记 none 并提成「建议加类」。
 * 绑定连判定时看到的候选类指纹（basis，0053）；过期 = 按现在的类重算的指纹对不上，
 * 本体一改只重判对不上的。
 *
 * <p>顺序与门槛（与 Rust 同序）：本体向量还在补（embed_ontology 排着）→ 排一份半分钟后
 * 的自己先回；一个库同时只跑一份（会话锁）；模型通道未就绪 → 「Chat model not
 * configured」失败。候选类：本体向量索引（M3 任务 10）落地前走 Rust 无嵌入时的同一条
 * 退化路——类不超过 {@value #WHOLE_LIST_LIMIT} 个时整表给，太多不判（瞎判比不判糟）。
 */
@Component
public class AlignTypesJob {

    private static final Logger log = LoggerFactory.getLogger(AlignTypesJob.class);

    /** type_alignment.rs:55 BATCH：一次问多少个类别词（20）。 */
    static final int BATCH = 20;

    /** type_alignment.rs:59 WHOLE_LIST_LIMIT：没有嵌入模型时类不超过这个数就整表给（60）。 */
    static final int WHOLE_LIST_LIMIT = 60;

    /** type_alignment.rs:102 MAX_REASK：一轮里没判完的自己再排几次的上限。 */
    static final int MAX_REASK = 3;

    private final JobsService jobs;
    private final javax.sql.DataSource dataSource;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final OntologyService ontology;
    private final TypeBindingsService typeBindings;
    private final LlmSupport llm;
    private final AppEventBus events;

    public AlignTypesJob(JobsService jobs, javax.sql.DataSource dataSource, KbMapper kbs,
                         SettingsService settingsService, OntologyService ontology,
                         TypeBindingsService typeBindings, LlmSupport llm, AppEventBus events) {
        this.jobs = jobs;
        this.dataSource = dataSource;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.ontology = ontology;
        this.typeBindings = typeBindings;
        this.llm = llm;
        this.events = events;
    }

    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        UUID kbId = UUID.fromString(kbNode.asText());
        int reask = job.payload().path("reask").asInt(0);
        // 本体向量还在补：编辑器建了类先排 embed_ontology 再排这个任务，候选类按向量检索，
        // 没向量的类检索不到。等它收尾再来——排一份半分钟后的，排着的至多一份
        if (jobs.pendingForKb("embed_ontology", kbId)) {
            log.info("kb={} 类别词对齐：本体向量还在补，半分钟后再看", kbId);
            enqueueRetry(kbId, reask, Duration.ofSeconds(30));
            return;
        }
        // 一个库同时只跑一份（session 级咨询锁；锁—体—解锁必须绑同一条连接，审查 I-1：
        // JdbcTemplate 两次调用各借各的池连接，unlock 落在别的连接上会返回 false——锁
        // 泄漏在借出连接上，此后该库每次 align_types 都被静默跳过）
        try (Connection conn = dataSource.getConnection()) {
            if (!tryLock(conn, kbId)) {
                log.info("kb={} 类别词对齐已有一份在跑，这次跳过", kbId);
                return;
            }
            try {
                alignTypesLocked(kbId, reask);
            } finally {
                unlock(conn, kbId);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("align_types 咨询锁连接失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void enqueueRetry(UUID kbId, int reask, Duration after) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("kb_id", kbId.toString());
        if (reask != 0) {
            payload.put("reask", reask);
        }
        jobs.enqueueUnlessQueuedAfter("align_types", payload, after);
    }

    /** type_alignment.rs:158-249 align_types_locked 的主体（候选走无嵌入的退化路）。 */
    void alignTypesLocked(UUID kbId, int reask) throws InterruptedException {
        KbRow kb = kbs.selectById(kbId);
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        if (settings == null || !settings.chatReady()) {
            throw new IllegalStateException("Chat model not configured; cannot align kind words");
        }
        ChatClient client = LlmSupport.chatClient(settings);

        // 开跑时的输入快照（#795）：类、指纹、类别词、已有判定
        List<EntityTypeView> classes = ontology.entityTypes(kbId);
        TypeBindingsService.ClassSnapshot snapshot = typeBindings.classSnapshot(kbId);
        List<KindWordSignature> sigs = typeBindings.signatures(kbId);
        Map<String, Binding> existing = new HashMap<>();
        for (Binding b : typeBindings.bindings(kbId)) {
            existing.put(b.kindWord(), b);
        }
        Map<String, EntityTypeView> byKey = new HashMap<>();
        for (EntityTypeView c : classes) {
            byKey.put(c.key(), c);
        }
        // 每个活着的类别词此刻的候选与指纹。候选：类不超过上限就整表给（本体向量索引
        // 属任务 10，落地后按向量检索——检索失败在 Rust 侧也退回整表，同一条退化路）
        Map<String, List<UUID>> candidatesOf = new HashMap<>();
        Map<String, String> basisOf = new HashMap<>();
        for (KindWordSignature s : sigs) {
            List<UUID> cands = classes.size() <= WHOLE_LIST_LIMIT
                    ? classes.stream().map(EntityTypeView::id).toList() : List.of();
            candidatesOf.put(s.kindWord(), cands);
            basisOf.put(s.kindWord(), snapshot.basis(cands));
        }
        // 过期 = 存下的指纹和此刻的不一样（没有指纹的是这一列出现前判的，各重判一次）。
        // 人的判定不重判
        List<KindWordSignature> todo = new ArrayList<>();
        for (KindWordSignature s : sigs) {
            Binding b = existing.get(s.kindWord());
            if (b == null || (!"person".equals(b.decidedBy())
                    && !basisOf.get(s.kindWord()).equals(b.basis()))) {
                todo.add(s);
            }
        }
        Set<String> attempted = new HashSet<>();
        for (KindWordSignature s : todo) {
            attempted.add(s.kindWord());
        }
        // 本轮写下的，或人已判过、代理不覆盖的
        Set<String> settled = new HashSet<>();
        log.info("kb={} 类别词对齐开始：kind_words={} to_decide={} classes={}", kbId, sigs.size(),
                todo.size(), classes.size());

        // 没有类可绑：每个词都是「没有」，并提成建议；类出现后指纹变了，它们会再交回来
        if (classes.isEmpty()) {
            for (KindWordSignature s : todo) {
                Acceptance accepted = typeBindings.decideAndApplyIfCurrent(kbId, s.kindWord(),
                        s.words(), null, "none",
                        JsonNodeFactory.instance.objectNode().put("reason", "no classes"),
                        basisOf.get(s.kindWord()), candidatesOf.get(s.kindWord()));
                if (accepted == Acceptance.WRITTEN) {
                    typeBindings.propose(kbId, s.kindWord(),
                            s.words().isEmpty() ? s.kindWord() : s.words().get(0));
                }
            }
            return;
        }

        int bound = 0;
        int none = 0;
        int undecided = 0;
        int skipped = 0;
        // 调用或解析失败的批次：这轮跳过，结束时自己再排一次，不等下一篇文档来排
        int failed = 0;
        // 问了、模型也答了、却没答到的词：两票缺一票就不下结论
        int unanswered = 0;
        // 模型答题期间候选类变了、回复没收下的词
        int moved = 0;
        for (int from = 0; from < todo.size(); from += BATCH) {
            List<KindWordSignature> batch = todo.subList(from, Math.min(from + BATCH, todo.size()));
            // 两票：第二票把候选倒过来给，防止「选第一个」这种顺序偏好冒充一致
            List<String[]> votes = new ArrayList<>();
            List<boolean[]> answered = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                votes.add(new String[2]);
                answered.add(new boolean[2]);
            }
            for (int pass = 0; pass < 2; pass++) {
                List<KindWordItem> items = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    List<UUID> cands = candidatesOf.get(batch.get(i).kindWord());
                    if (cands.isEmpty()) {
                        continue;
                    }
                    List<UUID> ordered = new ArrayList<>(cands);
                    if (pass == 1) {
                        java.util.Collections.reverse(ordered);
                    }
                    List<Align.ClassCandidate> candidates = new ArrayList<>();
                    for (UUID id : ordered) {
                        for (EntityTypeView c : classes) {
                            if (c.id().equals(id)) {
                                candidates.add(new Align.ClassCandidate(c.key(), c.label(),
                                        c.description()));
                                break;
                            }
                        }
                    }
                    KindWordSignature s = batch.get(i);
                    items.add(new KindWordItem(i, s.kindWord(), s.words(), s.examples(),
                            s.phrases(), candidates));
                }
                if (items.isEmpty()) {
                    continue;
                }
                tech.seexun.jknow.llm.ChatClient.Reply reply;
                try {
                    reply = llm.chatRetryingRateLimits(settings, client,
                            Align.buildKindWordMessages(items), 0.0d);
                } catch (Exception e) {
                    log.warn("kb={} 类别词对齐调用失败，这一批留到下次: {}", kbId, e.getMessage());
                    failed++;
                    continue;
                }
                Align.KindWordReply parsed;
                try {
                    parsed = Align.parseKindWordResponse(reply.text(), items);
                } catch (Exception e) {
                    log.warn("kb={} 类别词对齐回复解析失败，这一批留到下次: {}", kbId, e.getMessage());
                    failed++;
                    continue;
                }
                skipped += parsed.malformed();
                if (parsed.choices().isEmpty()) {
                    // 解出来了却一对都没读到：回复的形状不是我们认得的——按失败算、留到下次
                    log.warn("kb={} 类别词对齐回复读不出一项（{} 字），这一批留到下次", kbId,
                            reply.text().length());
                    failed++;
                    continue;
                }
                for (Align.KindWordChoice c : parsed.choices()) {
                    if (c.id() >= 0 && c.id() < votes.size()) {
                        votes.get((int) c.id())[pass] = c.key();
                        answered.get((int) c.id())[pass] = true;
                    }
                }
            }
            for (int i = 0; i < batch.size(); i++) {
                KindWordSignature s = batch.get(i);
                if (candidatesOf.get(s.kindWord()).isEmpty()) {
                    skipped++;
                    continue;
                }
                String a = votes.get(i)[0];
                String b = votes.get(i)[1];
                if (!answered.get(i)[0] || !answered.get(i)[1]) {
                    // 有一票没答到：不下结论，下次再问
                    unanswered++;
                    continue;
                }
                ObjectNode record = JsonNodeFactory.instance.objectNode();
                record.put("first", a);
                record.put("second", b);
                UUID typeId = null;
                String status;
                boolean agree = java.util.Objects.equals(a, b);
                if (!agree) {
                    status = "undecided";
                } else if (a != null) {
                    EntityTypeView cls = byKey.get(a);
                    if (cls != null) {
                        typeId = cls.id();
                        status = "bound";
                    } else {
                        status = "none";
                    }
                } else {
                    status = "none";
                }
                Acceptance accepted = typeBindings.decideAndApplyIfCurrent(kbId, s.kindWord(),
                        s.words(), typeId, status, record, basisOf.get(s.kindWord()),
                        candidatesOf.get(s.kindWord()));
                switch (accepted) {
                    case WRITTEN -> {
                        settled.add(s.kindWord());
                        switch (status) {
                            case "bound" -> bound++;
                            case "undecided" -> undecided++;
                            default -> {
                                typeBindings.propose(kbId, s.kindWord(),
                                        s.words().isEmpty() ? s.kindWord() : s.words().get(0));
                                none++;
                            }
                        }
                    }
                    case KEPT_PERSON -> settled.add(s.kindWord());
                    case MOVED -> {
                        log.info("kb={} kind_word={} 类别词对齐：模型答题期间候选类变了，这份回复不收",
                                kbId, s.kindWord());
                        moved++;
                    }
                }
            }
        }
        log.info("kb={} 类别词对齐完成：bound={} none={} undecided={} skipped={} failed={} "
                + "unanswered={} moved={}", kbId, bound, none, undecided, skipped, failed,
                unanswered, moved);
        if (unanswered > 0) {
            log.warn("kb={} 类别词对齐有 {} 个词模型没答到，这些词这轮没有结论", kbId, unanswered);
        }
        if (bound + none + undecided > 0) {
            events.emitGraph(kbId);
        }
        // 收尾的「变了没有」：重新读一遍输入（不是拿开跑时的快照比——快照就是判定写下的
        // 那份指纹，跟它比永远相等），本轮判完又过期的、没试过的新词都算变
        boolean changed = moved > 0;
        if (!changed) {
            TypeBindingsService.ClassSnapshot now = typeBindings.classSnapshot(kbId);
            Map<String, Binding> decided = new HashMap<>();
            for (Binding b : typeBindings.bindings(kbId)) {
                decided.put(b.kindWord(), b);
            }
            for (KindWordSignature s : typeBindings.signatures(kbId)) {
                Binding b = decided.get(s.kindWord());
                if (b == null) {
                    changed = !attempted.contains(s.kindWord());
                } else {
                    changed = !"person".equals(b.decidedBy())
                            && (settled.contains(s.kindWord()) || !attempted.contains(s.kindWord()))
                            && candidatesOf.containsKey(s.kindWord())
                            && !now.basis(candidatesOf.get(s.kindWord())).equals(b.basis());
                }
                if (changed) {
                    break;
                }
            }
        }
        finish(kbId, reask, changed, failed, unanswered);
    }

    /**
     * type_alignment.rs:461-497 finish：有新活就立刻再排一轮；没判完的延时再问（次数有
     * 上限）；不再排自己时才排短语对齐——两端的类还在变时判的签名指纹一变就得重判。
     */
    private void finish(UUID kbId, int reask, boolean changed, int failed, int unanswered) {
        boolean unfinished = failed > 0 || unanswered > 0;
        boolean anotherRound = false;
        if (changed) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("kb_id", kbId.toString());
            jobs.enqueueUnlessQueued("align_types", payload);
            anotherRound = true;
        } else if (unfinished && reask < MAX_REASK) {
            Duration delay = Duration.ofSeconds(20L * (reask + 1));
            log.info("kb={} 类别词对齐没判完（failed={} unanswered={}），{} 秒后再问", kbId,
                    failed, unanswered, delay.toSeconds());
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("kb_id", kbId.toString());
            payload.put("reask", reask + 1);
            jobs.enqueueUnlessPending("align_types", payload, delay);
            anotherRound = true;
        } else if (unfinished) {
            log.warn("kb={} 类别词对齐问了几轮仍没判完，等下一篇文档或本体改动再问", kbId);
        }
        // 两端的类定了，短语的签名才定：短语对齐排在类别词对齐收尾之后
        if (!anotherRound) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("kb_id", kbId.toString());
            jobs.enqueueUnlessQueued("align_phrases", payload);
        }
    }

    private static boolean tryLock(Connection conn, UUID kbId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtext('align_types'), hashtext(?))")) {
            ps.setString(1, kbId.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection conn, UUID kbId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_advisory_unlock(hashtext('align_types'), hashtext(?))")) {
            ps.setString(1, kbId.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }
}
