package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.extract.OntologyAgentPrompts;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Glossary;
import tech.seexun.jknow.extract.OntologyAgentPrompts.GlossaryProperty;
import tech.seexun.jknow.extract.OntologyAgentPrompts.OpenKindWord;
import tech.seexun.jknow.extract.OntologyAgentPrompts.OpenSignature;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Proposal;
import tech.seexun.jknow.extract.OntologyAgentPrompts.ProposalReply;
import tech.seexun.jknow.extract.OntologyAgentPrompts.QuestionItem;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.extraction.AlignPhrasesJob;
import tech.seexun.jknow.web.knowledge.llm.AlertingSupport;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.CompetencyQuestionsService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.AgentProposal;
import tech.seexun.jknow.store.OntologyService.AgentReview;
import tech.seexun.jknow.store.OntologyService.StoredProposal;
import tech.seexun.jknow.store.OntologyService.TypeCandidate;
import tech.seexun.jknow.store.OntologyRegressionsService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.PhraseBindingsService.Decision;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.OntologyMapper.IdKeyIri;
import tech.seexun.jknow.store.OntologyRegressionsService.NewCase;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.QuestionsMapper.QuestionRow;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 本体代理（ontology_agent.rs:59-720 逐语义，0061 决定 2、3）：对齐判成「没有」「没定」
 * 的短语形状与类别词，对着现有本体和能力问题交给模型，让它提该建的类与属性。提案进
 * {@code ontology_proposals}，<b>人采纳才成为本体</b>（0012）；采纳建出元素后排一次
 * 对齐，那些形状按 0053 的指纹自己重判。
 *
 * <p>任务入口（main.rs:634-643）：{@code propose_with}，payload 的 glossary=full 是
 * 测量旋钮（bench/agent.mjs）——每批都送全部属性的定义，产品路径不开。
 */
@Component
public class ProposeOntologyJob {

    private static final Logger log = LoggerFactory.getLogger(ProposeOntologyJob.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一次调用交给模型的形状数（0044 决定 3 的成本教训：词表每批只带一次）。 */
    static final int SIGNATURES_PER_CALL = 12;
    /** 每批跟着的类别词至多这么多。 */
    static final int KIND_WORDS_PER_CALL = 6;
    /** 一轮至多问这么多形状，按陈述数从多到少；其余等下一轮。 */
    static final int SIGNATURES_PER_ROUND = 120;
    /** 一条形状结构上对得上的属性多过这个数，就按向量只留最近的这么多带定义。 */
    static final int GLOSSARY_PER_SHAPE = 10;
    /** 每条形状按意思最近的这么多条属性，结构对不上也带定义。 */
    static final int GLOSSARY_NEAREST_ANYWAY = 5;
    /** 对齐收尾时没绑上的形状够这么多，才叫代理来看（入队阈值 phrase_alignment.rs:1189）。 */
    public static final int TRIGGER_UNBOUND = 20;

    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final OntologyService ontology;
    private final PhraseBindingsService phraseBindings;
    private final TypeBindingsService typeBindings;
    private final CompetencyQuestionsService questions;
    private final OntologyRegressionsService regressions;
    private final EmbedOntologyJob index;
    private final ProposeQuestionsJob questionAgent;
    private final LlmSupport llm;
    private final JobsService jobs;
    private final AuditService audit;
    private final AppEventBus events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ProposeOntologyJob(KbMapper kbs, SettingsService settingsService,
                              OntologyService ontology, PhraseBindingsService phraseBindings,
                              TypeBindingsService typeBindings,
                              CompetencyQuestionsService questions,
                              OntologyRegressionsService regressions, EmbedOntologyJob index,
                              ProposeQuestionsJob questionAgent, LlmSupport llm,
                              JobsService jobs, AuditService audit, AppEventBus events,
                              DataSource dataSource,
                              org.springframework.transaction.PlatformTransactionManager txManager) {
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.ontology = ontology;
        this.phraseBindings = phraseBindings;
        this.typeBindings = typeBindings;
        this.questions = questions;
        this.regressions = regressions;
        this.index = index;
        this.questionAgent = questionAgent;
        this.llm = llm;
        this.jobs = jobs;
        this.audit = audit;
        this.events = events;
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(txManager);
    }

    /** main.rs:634-643：载荷取 kb_id；glossary=full 是测量旋钮。 */
    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        boolean full = job.payload().hasNonNull("glossary")
                && "full".equals(job.payload().get("glossary").asText());
        proposeWith(UUID.fromString(kbNode.asText()), full);
    }

    /**
     * 一个库同时只跑一份（同两种对齐——并行会把端点打出 502）：
     * {@code pg_try_advisory_lock(hashtext('propose_ontology'), hashtext(kb))} 同库去重。
     * 锁住在一条专用 JDBC 连接上——连接池的别的连接看不见会话级咨询锁。
     */
    public void proposeWith(UUID kbId, boolean fullGlossary) {
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw new IllegalStateException("no such knowledge base: " + kbId);
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        if (client == null) {
            throw new IllegalStateException("Chat model not configured; cannot propose ontology");
        }
        try (Connection guard = jdbc.getDataSource().getConnection()) {
            boolean locked;
            try (var st = guard.createStatement();
                 var rs = st.executeQuery("SELECT pg_try_advisory_lock("
                         + "hashtext('propose_ontology'), hashtext('" + kbId + "'))")) {
                rs.next();
                locked = rs.getBoolean(1);
            }
            if (!locked) {
                log.info("kb={} 本体代理已有一份在跑，这次跳过", kbId);
                return;
            }
            try {
                proposeLocked(kbId, settings, client, fullGlossary);
            } finally {
                try (var st = guard.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(hashtext('propose_ontology'), "
                            + "hashtext('" + kbId + "'))");
                } catch (Exception e) {
                    // 连接关闭时 PG 会收回会话锁——这里失败只记一笔
                    log.warn("kb={} propose_ontology 锁没解开: {}", kbId, e.getMessage());
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("advisory lock connection failed", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一条合并后的提案：同一个键在几批里都被提了，形状、类别词、问题并起来。 */
    private static final class Merged {
        Proposal proposal;
        /** null=新建；非 null=「本体里已经有」的 map_kind（relation|attribute|class）。 */
        String mapKind;
        List<ObjectNode> shapes = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        List<String> words = new ArrayList<>();
        List<String> quotes = new ArrayList<>();
        List<UUID> serves = new ArrayList<>();
    }

    private void proposeLocked(UUID kbId, LlmSettingsRow settings, ChatClient client,
                               boolean fullGlossary) throws Exception {
        List<EntityTypeView> classes = ontology.entityTypes(kbId);
        List<RelationTypeView> props = ontology.relationTypes(kbId);
        Map<UUID, String> classKey = new HashMap<>();
        Set<String> classKeys = new HashSet<>();
        for (EntityTypeView c : classes) {
            classKey.put(c.id(), c.key());
            classKeys.add(c.key());
        }
        Set<String> propKeys = new HashSet<>();
        Map<String, String> propKind = new HashMap<>();
        for (RelationTypeView p : props) {
            propKeys.add(p.key());
            propKind.put(p.key(), p.kind());
        }
        // 词表的指纹：类键 + 属性键。代理看过没提、或答「已有」的形状，词表不变就不再送
        String basis = basisOf(classKeys, propKeys);

        // 已经提过的（不论表态与否）不再提；看过没提的记的是当时的陈述数（basis=count:n）
        // ——陈述数翻倍了才再送；答「已有」的记词表指纹：词表变了再送
        Set<String> proposedShapes = new HashSet<>();
        Set<String> proposedWords = new HashSet<>();
        Map<String, Long> declinedShapes = new HashMap<>();
        Map<String, Long> declinedWords = new HashMap<>();
        for (AgentReview r : ontology.agentReviews(kbId)) {
            String word = r.shape().path("kind_word").isTextual()
                    ? r.shape().get("kind_word").asText() : null;
            if ("declined".equals(r.outcome())) {
                long seenCount = 1;
                if (r.basis() != null && r.basis().startsWith("count:")) {
                    try {
                        seenCount = Long.parseLong(r.basis().substring("count:".length()));
                    } catch (NumberFormatException e) {
                        seenCount = 1;
                    }
                }
                if ("phrase".equals(r.kind())) {
                    declinedShapes.put(shapeKey(r.shape()), seenCount);
                } else if (word != null) {
                    declinedWords.put(word, seenCount);
                }
                continue;
            }
            // proposed 一律不再送；其余去向（existing 等）记的是词表指纹——词表没变
            // 才压下（Rust：outcome != "proposed" && basis != basis 才 continue）
            if (!"proposed".equals(r.outcome()) && !basis.equals(r.basis())) {
                continue;
            }
            if ("phrase".equals(r.kind())) {
                proposedShapes.add(shapeKey(r.shape()));
            } else if (word != null) {
                proposedWords.add(word);
            }
        }
        for (JsonNode s : ontology.signaturesAlreadyProposed(kbId)) {
            for (JsonNode p : s.path("phrases")) {
                proposedShapes.add(shapeKey(p));
            }
            for (JsonNode w : s.path("kind_words")) {
                if (w.isTextual()) {
                    proposedWords.add(w.asText());
                }
            }
        }

        // 没绑上的形状：对齐判成「没有」或「没定」的。还没判的等对齐，不在这里猜
        List<PhraseSignature> sigs = phraseBindings.signatures(kbId);
        Map<List<Object>, Binding> bindings = new HashMap<>();
        for (Binding b : phraseBindings.bindings(kbId)) {
            bindings.put(b.key(), b);
        }
        List<PhraseSignature> open = new ArrayList<>();
        for (PhraseSignature s : sigs) {
            Binding b = bindings.get(s.key());
            String status = b == null ? null : b.status();
            if (!"none".equals(status) && !"undecided".equals(status)) {
                continue;
            }
            String key = shapeKey(shapeOf(s));
            if (proposedShapes.contains(key)) {
                continue;
            }
            Long seen = declinedShapes.get(key);
            if (seen != null && s.count() < seen * 2) {
                continue;
            }
            open.add(s);
        }
        open.sort(java.util.Comparator.comparingLong(PhraseSignature::count).reversed()
                .thenComparing(PhraseSignature::phrase));
        if (open.size() > SIGNATURES_PER_ROUND) {
            open = open.subList(0, SIGNATURES_PER_ROUND);
        }

        // 没归类的类别词同理
        List<KindWordSignature> words = typeBindings.signatures(kbId);
        Map<String, tech.seexun.jknow.store.mapper.TypeBindingsMapper.Binding> wordBindings =
                new HashMap<>();
        for (var b : typeBindings.bindings(kbId)) {
            wordBindings.put(b.kindWord(), b);
        }
        List<KindWordSignature> openWords = new ArrayList<>();
        for (KindWordSignature w : words) {
            var b = wordBindings.get(w.kindWord());
            String status = b == null ? null : b.status();
            if (!"none".equals(status) && !"undecided".equals(status)) {
                continue;
            }
            if (proposedWords.contains(w.kindWord())) {
                continue;
            }
            Long seen = declinedWords.get(w.kindWord());
            if (seen != null && w.count() < seen * 2) {
                continue;
            }
            openWords.add(w);
        }
        openWords.sort(java.util.Comparator.comparingLong(KindWordSignature::count).reversed()
                .thenComparing(KindWordSignature::kindWord));
        int calls = Math.max(ceilDiv(open.size(), SIGNATURES_PER_CALL),
                ceilDiv(openWords.size(), KIND_WORDS_PER_CALL));
        if (calls == 0) {
            log.info("kb={} 本体代理：没有新的形状要看", kbId);
            return;
        }
        if (openWords.size() > calls * KIND_WORDS_PER_CALL) {
            openWords = openWords.subList(0, calls * KIND_WORDS_PER_CALL);
        }

        // 一条问题都没有的库（决定 1）：先让代理提问题，人接受了下一轮才算数；这一轮
        // 照旧按说法提
        if (questions.list(kbId).isEmpty()) {
            try {
                questionAgent.proposeQuestionsWith(kbId, settings, client);
            } catch (Exception e) {
                log.warn("kb={} 本体代理：提问题失败，这一轮不带问题: {}", kbId, e.toString());
            }
        }
        List<QuestionRow> accepted = questions.accepted(kbId);
        List<QuestionItem> qItems = new ArrayList<>();
        for (int i = 0; i < accepted.size(); i++) {
            qItems.add(new QuestionItem(i, accepted.get(i).question()));
        }
        Set<Long> qIds = new HashSet<>();
        for (int i = 0; i < accepted.size(); i++) {
            qIds.add((long) i);
        }
        java.util.function.Function<List<UUID>, List<String>> keysOf = ids -> {
            List<String> out = new ArrayList<>();
            for (UUID id : ids) {
                String k = classKey.get(id);
                if (k != null) {
                    out.add(k);
                }
            }
            return out;
        };
        // 词表按批裁（第一次真跑：98 条属性带定义每次调用 4k token，占一次调用的一半多）
        Map<UUID, List<UUID>> closure = AlignPhrasesJob.closuresOf(classes);
        List<List<UUID>> nearest;
        {
            List<String> queries = new ArrayList<>();
            for (PhraseSignature s : open) {
                String ex = s.examples().isEmpty() ? null : s.examples().get(0);
                String quote = s.quotes().isEmpty() ? null : s.quotes().get(0);
                if (ex != null && quote != null) {
                    queries.add(s.phrase() + " · " + ex + " · " + quote);
                } else if (ex != null) {
                    queries.add(s.phrase() + " · " + ex);
                } else {
                    queries.add(s.phrase());
                }
            }
            try {
                List<List<TypeCandidate>> found = index.nearestForEach(kbId, queries,
                        GLOSSARY_PER_SHAPE, EmbedOntologyJob.Target.PREDICATE);
                nearest = new ArrayList<>(found.size());
                for (List<TypeCandidate> cands : found) {
                    List<UUID> ids = new ArrayList<>();
                    for (TypeCandidate c : cands) {
                        ids.add(c.id());
                    }
                    nearest.add(ids);
                }
            } catch (Exception e) {
                log.warn("kb={} 本体代理：形状向量没算出来，词表按结构裁: {}", kbId, e.toString());
                nearest = new ArrayList<>(open.size());
                for (int i = 0; i < open.size(); i++) {
                    nearest.add(List.of());
                }
            }
        }

        List<OpenSignature> sigItems = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            PhraseSignature s = open.get(i);
            sigItems.add(new OpenSignature(i, s.phrase(), s.subjectTypeKey(),
                    s.objectTypeKey(), s.objectIsValue(), s.count(), s.examples(),
                    s.quotes()));
        }
        List<OpenKindWord> wordItems = new ArrayList<>();
        for (int i = 0; i < openWords.size(); i++) {
            KindWordSignature w = openWords.get(i);
            wordItems.add(new OpenKindWord(i, w.kindWord(), w.count(), w.examples(),
                    w.phrases()));
        }
        log.info("kb={} shapes={} kind_words={} questions={} calls={} 本体代理开始", kbId,
                open.size(), openWords.size(), accepted.size(), calls);

        Map<String, Merged> merged = new LinkedHashMap<>();
        // 这一轮看过的每条形状的去向（调用失败的批次不记，下一轮再送）
        List<AgentReview> reviews = new ArrayList<>();
        int failed = 0;
        int malformed = 0;
        int skippedExisting = 0;
        for (int b = 0; b < calls; b++) {
            List<OpenSignature> sigBatch = slice(sigItems, b * SIGNATURES_PER_CALL,
                    SIGNATURES_PER_CALL);
            List<OpenKindWord> wordBatch = slice(wordItems, b * KIND_WORDS_PER_CALL,
                    KIND_WORDS_PER_CALL);
            Set<Long> sigIds = new HashSet<>();
            sigBatch.forEach(s -> sigIds.add(s.id()));
            Set<Long> wordIds = new HashSet<>();
            wordBatch.forEach(w -> wordIds.add(w.id()));
            Glossary glossary = glossaryFor(props, classes, keysOf, open, nearest, fullGlossary,
                    b * SIGNATURES_PER_CALL, (b + 1) * SIGNATURES_PER_CALL, closure);
            List<ChatClient.ChatMessage> messages = OntologyAgentPrompts.buildProposalMessages(
                    sigBatch, wordBatch, glossary, qItems);
            ChatClient.Reply reply;
            try {
                reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0);
            } catch (Exception e) {
                if (AlertingSupport.isTerminal(e)) {
                    throw e;
                }
                failed++;
                log.warn("kb={} batch={} 本体代理：这一批调用失败: {}", kbId, b, e.toString());
                continue;
            }
            ProposalReply parsed;
            try {
                parsed = OntologyAgentPrompts.parseProposalResponse(reply.text(), sigIds,
                        wordIds, qIds);
            } catch (Exception e) {
                failed++;
                log.warn("kb={} batch={} 本体代理：回复读不出: {}", kbId, b, e.toString());
                continue;
            }
            malformed += parsed.malformed();
            for (String why : parsed.reasons()) {
                log.warn("kb={} batch={} 本体代理：坏项: {}", kbId, b, why);
            }
            Map<Long, String[]> sigOutcome = new HashMap<>();
            Map<Long, String[]> wordOutcome = new HashMap<>();
            // 「本体里已经有」：存成 map_to 提案，人点「用已有的」就是一次人的绑定判定
            for (var e : parsed.existing()) {
                String mapKind = propKind.get(e.key());
                if (mapKind == null && classKeys.contains(e.key())) {
                    mapKind = "class";
                }
                if (mapKind == null) {
                    malformed++;
                    log.warn("kb={} batch={} 本体代理：坏项 existing: no such key in the "
                            + "ontology: {}", kbId, b, e.key());
                    continue;
                }
                String slot = "map_to:" + e.key();
                final String entryMapKind = mapKind;
                Merged entry = merged.computeIfAbsent(slot, k -> {
                    Merged m = new Merged();
                    m.proposal = new Proposal("existing", e.key(), e.key(), "", false, null,
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
                    m.mapKind = entryMapKind;
                    return m;
                });
                if ("class".equals(entry.mapKind)) {
                    // 类接的是类别词；形状指给类是答错了格，不记
                    for (long id : e.kindWords()) {
                        if (id >= 0 && id < openWords.size()) {
                            pushWord(entry, openWords.get((int) id));
                            wordOutcome.put(id, new String[] {"existing", e.key()});
                        }
                    }
                } else {
                    for (long id : e.signatures()) {
                        if (id >= 0 && id < open.size()) {
                            pushShape(entry, open.get((int) id), e.direction());
                            sigOutcome.put(id, new String[] {"existing", e.key()});
                        }
                    }
                }
            }
            for (Proposal p : parsed.proposals()) {
                // 模型被告知已有的不要提；还是提了就当它没说（对齐那边会把形状挂上去）
                boolean taken = "class".equals(p.kind())
                        ? classKeys.contains(p.key()) : propKeys.contains(p.key());
                if (taken) {
                    skippedExisting++;
                    continue;
                }
                String slot = p.kind() + ":" + p.key();
                Merged entry = merged.computeIfAbsent(slot, k -> {
                    Merged m = new Merged();
                    m.proposal = new Proposal(p.kind(), p.key(), p.label(), p.definition(),
                            p.value(), p.datatype(), p.domains(), p.ranges(), p.parents(),
                            List.of(), List.of(), List.of());
                    return m;
                });
                String target = sectionOf(p) + ":" + p.key();
                for (long id : p.signatures()) {
                    if (id < 0 || id >= open.size()) {
                        continue;
                    }
                    pushShape(entry, open.get((int) id), null);
                    sigOutcome.put(id, new String[] {"proposed", target});
                }
                for (long id : p.kindWords()) {
                    if (id < 0 || id >= openWords.size()) {
                        continue;
                    }
                    pushWord(entry, openWords.get((int) id));
                    wordOutcome.put(id, new String[] {"proposed", target});
                }
                for (long id : p.questions()) {
                    if (id < 0 || id >= accepted.size()) {
                        continue;
                    }
                    UUID qid = accepted.get((int) id).id();
                    if (!entry.serves.contains(qid)) {
                        entry.serves.add(qid);
                    }
                }
            }
            for (OpenSignature si : sigBatch) {
                String[] outcome = sigOutcome.get(si.id());
                String out = outcome == null ? "declined" : outcome[0];
                String target = outcome == null ? null : outcome[1];
                reviews.add(new AgentReview("phrase", shapeOf(open.get((int) si.id())),
                        out, target,
                        "declined".equals(out)
                                ? "count:" + open.get((int) si.id()).count() : basis));
            }
            for (OpenKindWord wi : wordBatch) {
                String[] outcome = wordOutcome.get(wi.id());
                String out = outcome == null ? "declined" : outcome[0];
                String target = outcome == null ? null : outcome[1];
                reviews.add(new AgentReview("kind_word",
                        JSON.createObjectNode().put("kind_word", wi.kindWord()), out, target,
                        "declined".equals(out)
                                ? "count:" + openWords.get((int) wi.id()).count() : basis));
            }
        }

        List<AgentProposal> items = new ArrayList<>();
        for (Merged m : merged.values()) {
            if (m.shapes.isEmpty() && m.words.isEmpty()) {
                continue;
            }
            Proposal p = m.proposal;
            if (m.mapKind != null) {
                String label = null;
                if ("class".equals(m.mapKind)) {
                    for (EntityTypeView c : classes) {
                        if (c.key().equals(p.key())) {
                            label = c.label();
                        }
                    }
                } else {
                    for (RelationTypeView r : props) {
                        if (r.key().equals(p.key())) {
                            label = r.label();
                        }
                    }
                }
                ObjectNode payload = JSON.createObjectNode();
                payload.put("key", p.key());
                payload.put("label", label != null ? label : p.key());
                payload.put("kind", m.mapKind);
                payload.set("forms", arrayOf(m.phrases));
                payload.set("kind_words", arrayOf(m.words));
                payload.set("examples", arrayOf(m.quotes));
                payload.put("proposed_by", "agent");
                items.add(new AgentProposal("map_to", p.key(), payload, List.of(),
                        signaturesJson(m.shapes, m.words)));
                continue;
            }
            String section = sectionOf(p);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("key", p.key());
            payload.put("label", p.label());
            payload.put("description", p.definition());
            payload.set("forms", arrayOf(m.phrases));
            payload.set("kind_words", arrayOf(m.words));
            payload.set("examples", arrayOf(m.quotes));
            payload.put("proposed_by", "agent");
            switch (section) {
                case "entity_types" -> payload.set("parents", arrayOf(p.parents()));
                case "attribute_types" -> {
                    payload.put("datatype", datatypeOrText(p.datatype()));
                    payload.set("domains", arrayOf(p.domains()));
                }
                default -> {
                    payload.put("temporal", "state");
                    payload.set("domains", arrayOf(p.domains()));
                    payload.set("ranges", arrayOf(p.ranges()));
                }
            }
            items.add(new AgentProposal(section, p.key(), payload, m.serves,
                    signaturesJson(m.shapes, m.words)));
        }
        // 同一个目标上一轮已经答过一批形状：并起来，不是盖掉（第一次真跑第三轮盖掉了
        // 第二轮的）。每条新元素带上本体里离它最近的两个已有元素（按向量，不问模型）
        for (boolean classSide : new boolean[] {false, true}) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                AgentProposal it = items.get(i);
                if (!"map_to".equals(it.section())
                        && ("entity_types".equals(it.section())) == classSide) {
                    idx.add(i);
                }
            }
            List<String> queries = new ArrayList<>();
            for (int i : idx) {
                queries.add(items.get(i).payload().path("label").asText("") + " · "
                        + items.get(i).payload().path("description").asText(""));
            }
            EmbedOntologyJob.Target target = classSide ? EmbedOntologyJob.Target.CLASS
                    : EmbedOntologyJob.Target.PREDICATE;
            try {
                List<List<TypeCandidate>> found = index.nearestForEach(kbId, queries, 2, target);
                for (int n = 0; n < idx.size(); n++) {
                    ObjectNode payload = (ObjectNode) items.get(idx.get(n)).payload();
                    ArrayNode closest = payload.putArray("closest");
                    for (TypeCandidate c : found.get(n)) {
                        ObjectNode cn = closest.addObject();
                        cn.put("key", c.key());
                        cn.put("label", c.label());
                        cn.put("distance", c.distance());
                    }
                }
            } catch (Exception e) {
                log.warn("kb={} 本体代理：最近的已有元素没算出来，提案不带这一格: {}", kbId,
                        e.toString());
            }
        }
        for (int i = 0; i < items.size(); i++) {
            AgentProposal it = items.get(i);
            if ("map_to".equals(it.section())) {
                StoredProposal prev = ontology.openProposal(kbId, "map_to", it.key());
                if (prev != null) {
                    items.set(i, mergeMapTo(it, prev));
                }
            }
        }
        ontology.saveAgentProposals(kbId, items);
        ontology.recordAgentReviews(kbId, reviews);
        log.info("kb={} proposals={} failed={} malformed={} skipped_existing={} calls={} "
                + "glossary={} 本体代理结束", kbId, items.size(), failed, malformed,
                skippedExisting, calls, fullGlossary ? "full" : "trimmed");
        if (!items.isEmpty()) {
            events.emitPending(kbId);
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static <T> List<T> slice(List<T> list, int from, int len) {
        if (from >= list.size()) {
            return List.of();
        }
        return list.subList(from, Math.min(from + len, list.size()));
    }

    /** 词表指纹（basis）：sha256(排序类键 join 空格 + "##" + 排序属性键 join 空格)。 */
    static String basisOf(Set<String> classKeys, Set<String> propKeys) {
        String joined = String.join(" ", new TreeSet<>(classKeys)) + "##"
                + String.join(" ", new TreeSet<>(propKeys));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte x : digest) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 形状写进提案里的样子（shape_of）。 */
    ObjectNode shapeOf(PhraseSignature s) {
        ObjectNode n = JSON.createObjectNode();
        n.put("phrase", s.phrase());
        if (s.subjectTypeKey() != null) {
            n.put("subject", s.subjectTypeKey());
        } else {
            n.putNull("subject");
        }
        if (s.objectTypeKey() != null) {
            n.put("object", s.objectTypeKey());
        } else {
            n.putNull("object");
        }
        n.put("value", s.objectIsValue());
        // Proposals show the first example; keep its identity from the same read.
        // 键恒输出（ontology_agent.rs:48-57 的 shape_of 无例句也写空数组——审查 M-3）
        ArrayNode ids = n.putArray("example_statement_ids");
        if (!s.exampleStatementIds().isEmpty()) {
            ids.add(s.exampleStatementIds().get(0).toString());
        }
        return n;
    }

    /** 形状的身份写成一个键：短语 + 两端类键 + 宾语是不是字面值（方向不算）。 */
    static String shapeKey(JsonNode v) {
        return "{phrase=" + v.path("phrase") + ",subject=" + v.path("subject")
                + ",object=" + v.path("object") + ",value=" + v.path("value") + "}";
    }

    static boolean sameShape(JsonNode a, JsonNode b) {
        return a.path("phrase").equals(b.path("phrase"))
                && a.path("subject").equals(b.path("subject"))
                && a.path("object").equals(b.path("object"))
                && a.path("value").equals(b.path("value"));
    }

    private void pushShape(Merged entry, PhraseSignature s, String direction) {
        ObjectNode shape = shapeOf(s);
        if (direction != null) {
            shape.put("direction", direction);
        }
        for (JsonNode x : entry.shapes) {
            if (sameShape(x, shape)) {
                return;
            }
        }
        entry.shapes.add(shape);
        if (!entry.phrases.contains(s.phrase())) {
            entry.phrases.add(s.phrase());
        }
        // 只看第一条引文（Rust quotes.iter().take(1)）：已在例句里时也不翻第二条
        if (!s.quotes().isEmpty()) {
            String q = s.quotes().get(0);
            if (entry.quotes.size() < 3 && !entry.quotes.contains(q)) {
                entry.quotes.add(q);
            }
        }
    }

    private void pushWord(Merged entry, KindWordSignature w) {
        if (entry.words.contains(w.kindWord())) {
            return;
        }
        entry.words.add(w.kindWord());
        if (!w.examples().isEmpty()) {
            String e = w.examples().get(0);
            if (entry.quotes.size() < 3 && !entry.quotes.contains(e)) {
                entry.quotes.add(e);
            }
        }
    }

    static String sectionOf(Proposal p) {
        if ("class".equals(p.kind())) {
            return "entity_types";
        }
        return p.value() ? "attribute_types" : "relation_types";
    }

    static String datatypeOrText(String d) {
        return "text".equals(d) || "number".equals(d) || "date".equals(d) || "bool".equals(d)
                ? d : "text";
    }

    private ArrayNode arrayOf(List<String> values) {
        ArrayNode n = JSON.createArrayNode();
        values.forEach(n::add);
        return n;
    }

    private ObjectNode signaturesJson(List<ObjectNode> shapes, List<String> words) {
        ObjectNode n = JSON.createObjectNode();
        ArrayNode s = n.putArray("phrases");
        shapes.forEach(s::add);
        n.set("kind_words", arrayOf(words));
        return n;
    }

    /**
     * 词表按批裁（glossary_for）：每条形状只给结构对得上的属性定义；对得上的太多就按
     * 向量留最近的几条；另按意思最近带几条不管结构对不对的——一端没类型、或宾语是
     * 字面值的形状，结构上对不上 positionHeld/workLocation，模型看不到定义就把它们又
     * 提了一遍（受控对比里重复提案 1% → 6%，十条里七条是这样来的）。其余属性只报键
     * 和标签，让模型知道它们在。
     */
    private Glossary glossaryFor(List<RelationTypeView> props, List<EntityTypeView> classes,
                                 java.util.function.Function<List<UUID>, List<String>> keysOf,
                                 List<PhraseSignature> open, List<List<UUID>> nearest,
                                 boolean fullGlossary, int from, int to,
                                 Map<UUID, List<UUID>> closure) {
        Set<UUID> full = new HashSet<>();
        if (fullGlossary) {
            for (RelationTypeView p : props) {
                full.add(p.id());
            }
        }
        for (int i = from; i < to && i < open.size(); i++) {
            PhraseSignature s = open.get(i);
            List<UUID> near = i < nearest.size() ? nearest.get(i) : List.of();
            List<RelationTypeView> fitting = new ArrayList<>();
            for (RelationTypeView p : props) {
                if (AlignPhrasesJob.structurallyFits(p, s, closure)) {
                    fitting.add(p);
                }
            }
            if (near != null) {
                for (int k = 0; k < Math.min(GLOSSARY_NEAREST_ANYWAY, near.size()); k++) {
                    full.add(near.get(k));
                }
            }
            if (fitting.size() > GLOSSARY_PER_SHAPE && near != null && !near.isEmpty()) {
                for (RelationTypeView p : fitting) {
                    if (near.contains(p.id())) {
                        full.add(p.id());
                    }
                }
                continue;
            }
            for (RelationTypeView p : fitting) {
                full.add(p.id());
            }
        }
        List<String[]> classEntries = new ArrayList<>();
        for (EntityTypeView c : classes) {
            classEntries.add(new String[] {c.key(), c.label(),
                    c.description() == null ? "" : c.description()});
        }
        List<GlossaryProperty> propertyEntries = new ArrayList<>();
        List<String[]> others = new ArrayList<>();
        for (RelationTypeView p : props) {
            if (full.contains(p.id())) {
                propertyEntries.add(new GlossaryProperty(p.key(), p.label(), p.kind(),
                        keysOf.apply(p.domains()), keysOf.apply(p.ranges()),
                        p.description() == null ? "" : p.description()));
            } else {
                others.add(new String[] {p.key(), p.label()});
            }
        }
        return new Glossary(classEntries, propertyEntries, others);
    }

    /**
     * 把上一轮开着的 map_to 行并进这一轮的：形状、说法、类别词、例句取并集，先前的在
     * 前。返回并好的新提案（AgentProposal 是 record——重建成新值）。
     */
    private AgentProposal mergeMapTo(AgentProposal it, StoredProposal prev) {
        List<ObjectNode> shapes = new ArrayList<>();
        for (JsonNode s : prev.signatures().path("phrases")) {
            shapes.add((ObjectNode) s);
        }
        for (JsonNode s : it.signatures().path("phrases")) {
            boolean seen = shapes.stream().anyMatch(x -> sameShape(x, s));
            if (!seen) {
                shapes.add((ObjectNode) s);
            }
        }
        java.util.function.BiFunction<String, Boolean, List<String>> union =
                (field, fromPayload) -> {
                    List<String> out = new ArrayList<>();
                    JsonNode a = fromPayload ? prev.payload().path(field)
                            : prev.signatures().path(field);
                    JsonNode b = fromPayload ? it.payload().path(field)
                            : it.signatures().path(field);
                    for (JsonNode x : a) {
                        if (x.isTextual()) {
                            out.add(x.asText());
                        }
                    }
                    for (JsonNode x : b) {
                        if (x.isTextual() && !out.contains(x.asText())) {
                            out.add(x.asText());
                        }
                    }
                    return out;
                };
        List<String> words = union.apply("kind_words", false);
        List<String> forms = union.apply("forms", true);
        List<String> examples = union.apply("examples", true);
        if (examples.size() > 3) {
            examples = examples.subList(0, 3);
        }
        ObjectNode payload = it.payload().deepCopy();
        payload.set("forms", arrayOf(forms));
        payload.set("kind_words", arrayOf(words));
        payload.set("examples", arrayOf(examples));
        ObjectNode signatures = JSON.createObjectNode();
        ArrayNode s = signatures.putArray("phrases");
        shapes.forEach(s::add);
        signatures.set("kind_words", arrayOf(words));
        return new AgentProposal(it.section(), it.key(), payload, it.serves(), signatures);
    }

    // ====================================================================
    // 采纳（ontology_agent.rs:855-1169）：建出元素、标记提案、排对齐。
    // ====================================================================

    /** 采纳时人改过的几格（AdoptEdits）。null = 照提案。 */
    public record AdoptEdits(String label, String description, String datatype,
                             List<String> domains, List<String> ranges, List<String> parents) {
    }

    /**
     * 采纳一条代理提案（0061 决定 3）：建出元素，标记提案，排对齐让形状重判。返回新元素
     * 的 id。
     */
    public UUID adopt(UUID kbId, String section, String key, AdoptEdits edits, UUID actor) {
        StoredProposal p = ontology.openProposal(kbId, section, key);
        if (p == null) {
            throw AppException.NOT_FOUND;
        }
        boolean edited = edits != null && (edits.label() != null || edits.description() != null
                || edits.datatype() != null || edits.domains() != null
                || edits.ranges() != null || edits.parents() != null);
        List<EntityTypeView> classes = ontology.entityTypes(kbId);
        Map<String, UUID> byKey = new HashMap<>();
        for (EntityTypeView c : classes) {
            byKey.put(c.key(), c.id());
        }
        java.util.function.Function<List<String>, List<UUID>> resolve = keys -> {
            List<UUID> out = new ArrayList<>();
            for (String k : keys) {
                UUID id = byKey.get(k);
                if (id == null) {
                    throw AppException.invalid("unknown_class",
                            "class `" + k + "` does not exist");
                }
                out.add(id);
            }
            return out;
        };
        String label = edits != null && edits.label() != null && !edits.label().trim().isEmpty()
                ? edits.label().trim()
                : orDefault(p.payload().path("label").isTextual()
                        ? p.payload().path("label").asText() : null, key);
        String description = edits != null && edits.description() != null
                ? edits.description()
                : (p.payload().path("description").isTextual()
                        ? p.payload().path("description").asText() : "");
        description = description == null ? "" : description.trim();
        UUID id;
        switch (section) {
            // 「用已有的」：形状各写一条人的绑定判定（同审核页），类别词各归到那个类
            case "map_to" -> id = adoptMapTo(kbId, p, classes);
            case "entity_types" -> {
                List<String> parentKeys = edits != null && edits.parents() != null
                        ? edits.parents() : stringsOf(p.payload().get("parents"));
                id = ontology.createEntityType(kbId, key, label,
                        tech.seexun.jknow.store.Palette.colorForKey(key), "circle",
                        resolve.apply(parentKeys), description);
                // 多了一个类：类别词里「没有」「没定」的要重判，短语对齐跟在它后面
                try {
                    jobs.enqueueUnlessPending("align_types",
                            JSON.createObjectNode().put("kb_id", kbId.toString()),
                            java.time.Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.debug("kb={} align_types 排队失败（对齐自愈）: {}", kbId, e.getMessage());
                }
            }
            case "relation_types", "attribute_types" -> {
                String kind = "attribute_types".equals(section) ? "attribute" : "relation";
                List<String> domainKeys = edits != null && edits.domains() != null
                        ? edits.domains() : stringsOf(p.payload().get("domains"));
                List<String> rangeKeys = edits != null && edits.ranges() != null
                        ? edits.ranges() : stringsOf(p.payload().get("ranges"));
                List<UUID> domains = resolve.apply(domainKeys);
                List<UUID> ranges = "relation".equals(kind)
                        ? resolve.apply(rangeKeys) : List.of();
                String datatype = null;
                if ("attribute".equals(kind)) {
                    String raw = edits != null && edits.datatype() != null ? edits.datatype()
                            : (p.payload().path("datatype").isTextual()
                                    ? p.payload().path("datatype").asText() : null);
                    datatype = datatypeOrText(raw);
                }
                id = ontology.createRelationType(kbId, key, label, "state",
                        tech.seexun.jknow.store.OntologyService.RelationAxioms.empty(),
                        description, kind, domains, ranges, datatype, null);
                // 多了一个属性：判成 none/undecided 的形状也许对得上了（0053 指纹变了）
                try {
                    jobs.enqueueUnlessPending("align_phrases",
                            JSON.createObjectNode().put("kb_id", kbId.toString()),
                            java.time.Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.debug("kb={} align_phrases 排队失败（对齐自愈）: {}", kbId, e.getMessage());
                }
            }
            default -> throw AppException.invalid("bad_section",
                    "section 只能是 map_to、entity_types、relation_types 或 attribute_types");
        }
        // 新元素要有向量，对齐的短名单才看得见它。补的只是缺的那几条，通常一次调用
        if (!"map_to".equals(section)) {
            try {
                index.refresh(kbId);
            } catch (Exception e) {
                log.warn("kb={} 采纳后本体向量没补上，对齐的短名单看不见新元素: {}", kbId,
                        e.getMessage());
            }
        }
        finishAdoption(kbId, p, id, actor);
        if (edited) {
            ontology.markProposalEdited(kbId, section, key);
        }
        ObjectNode detail = JSON.createObjectNode();
        detail.put("key", key);
        detail.put("label", label);
        detail.put("proposed_by", p.proposedBy());
        detail.set("serves", arrayOf(p.serves().stream().map(UUID::toString).toList()));
        audit.recordOpt(null, kbId, actor, "ontology_proposal.adopted",
                section.endsWith("s") ? section.substring(0, section.length() - 1) : section,
                id, detail.toString());
        events.emitGraph(kbId);
        return id;
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static List<String> stringsOf(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v != null && v.isArray()) {
            for (JsonNode x : v) {
                if (x.isTextual()) {
                    out.add(x.asText());
                }
            }
        }
        return out;
    }

    /**
     * finish_adoption（ontology_agent.rs:1023-1093）：行锁下标记提案；属性提案带着的
     * 例句陈述各记一条观测回归（撤掉的陈述不代换——never substitute another statement）。
     */
    private void finishAdoption(UUID kbId, StoredProposal proposal, UUID propertyId, UUID actor) {
        boolean property = "relation_types".equals(proposal.section())
                || "attribute_types".equals(proposal.section())
                || ("map_to".equals(proposal.section())
                        && !"class".equals(proposal.payload().path("kind").asText("relation")));
        List<Map.Entry<UUID, String>> examples = new ArrayList<>();
        if (property) {
            for (JsonNode shape : proposal.signatures().path("phrases")) {
                List<UUID> ids = new ArrayList<>();
                if (shape.has("example_statement_ids")) {
                    for (JsonNode n : shape.get("example_statement_ids")) {
                        try {
                            ids.add(UUID.fromString(n.asText()));
                        } catch (IllegalArgumentException e) {
                            throw AppException.invalid("invalid_examples",
                                    "Invalid proposal statement identities.");
                        }
                    }
                }
                String direction = "reverse".equals(shape.path("direction").asText(null))
                        ? "reverse" : "forward";
                for (UUID statement : ids) {
                    examples.add(Map.entry(statement, direction));
                }
            }
        }
        tx.executeWithoutResult(status -> {
            List<UUID> open = jdbc.queryForList(
                    "SELECT id FROM ontology_proposals WHERE kb_id=? AND section=? AND key=? "
                            + "AND status='open' FOR UPDATE", UUID.class, kbId,
                    proposal.section(), proposal.key());
            if (open.isEmpty()) {
                throw AppException.NOT_FOUND;
            }
            for (Map.Entry<UUID, String> example : examples) {
                // Removed examples cannot be checked; never substitute another statement.
                List<UUID> live = jdbc.queryForList(
                        "SELECT id FROM facts WHERE kb_id=? AND id=? AND layer='open' "
                                + "AND invalidated_at IS NULL FOR SHARE", UUID.class, kbId,
                        example.getKey());
                if (live.isEmpty()) {
                    continue;
                }
                regressions.addOn(kbId, new NewCase(example.getKey(), propertyId,
                        example.getValue(), actor, "adoption"));
            }
            jdbc.update("UPDATE ontology_proposals SET status='adopted',decided_by=?,"
                    + "decided_at=now() WHERE id=?", actor, open.get(0));
        });
    }

    /**
     * map_to 的采纳（adopt_map_to）：目标是属性，提案里的每条形状写成人的绑定（属性 +
     * 方向），投影跟着排（同审核页）；目标是类，每个类别词归到它。返回目标的 id。
     */
    private UUID adoptMapTo(UUID kbId, StoredProposal p, List<EntityTypeView> classes) {
        String kind = p.payload().path("kind").asText("relation");
        if ("class".equals(kind)) {
            UUID klass = null;
            for (EntityTypeView c : classes) {
                if (c.key().equals(p.key())) {
                    klass = c.id();
                }
            }
            if (klass == null) {
                throw AppException.invalid("unknown_class", "no class with that key");
            }
            for (String w : stringsOf(p.signatures().get("kind_words"))) {
                ObjectNode votes = JSON.createObjectNode();
                votes.put("person", p.key());
                votes.put("via", "ontology_agent");
                typeBindings.decideAndApplyHuman(kbId, w, klass, votes);
            }
            return klass;
        }
        UUID property = null;
        for (RelationTypeView r : ontology.relationTypes(kbId)) {
            if (r.key().equals(p.key())) {
                property = r.id();
            }
        }
        if (property == null) {
            throw AppException.invalid("unknown_property", "no property with that key");
        }
        List<PhraseSignature> sigs = phraseBindings.signatures(kbId);
        for (JsonNode shape : p.signatures().path("phrases")) {
            // 此刻已经没有陈述的形状跳过。方向是这条形状自己的
            PhraseSignature sig = null;
            for (PhraseSignature s : sigs) {
                if (sameShape(shapeOf(s), shape)) {
                    sig = s;
                    break;
                }
            }
            if (sig == null) {
                continue;
            }
            String direction = "reverse".equals(shape.path("direction").asText(null))
                    ? "reverse" : "forward";
            ObjectNode votes = JSON.createObjectNode();
            ObjectNode person = votes.putObject("person");
            person.put("property", p.key());
            person.put("direction", direction);
            person.put("via", "ontology_agent");
            phraseBindings.decideWithDelivery(kbId, sig,
                    new Decision(property, direction, "bound", votes, "person", null, null,
                            false));
        }
        events.emitReview(kbId);
        return property;
    }
}
