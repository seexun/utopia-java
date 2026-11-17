package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Glossary;
import tech.seexun.jknow.extract.OntologyAgentPrompts.GlossaryProperty;
import tech.seexun.jknow.extract.QuestionPrompts;
import tech.seexun.jknow.extract.QuestionPrompts.ProposedQuestion;
import tech.seexun.jknow.extract.QuestionPrompts.TopEntity;
import tech.seexun.jknow.extract.QuestionPrompts.TopKindWord;
import tech.seexun.jknow.extract.QuestionPrompts.TopSignature;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.CompetencyQuestionsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.QuestionsMapper.DegreeRow;
import tech.seexun.jknow.store.mapper.QuestionsMapper.QuestionRow;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 给库提问题的代理（ontology_agent.rs:1171-1338 逐语义，0061 决定 1 的「没有问题的库
 * 不被卡住」）：从开放图谱说得最多的东西——陈述最多的形状、最多的类别词、连接最多的
 * 实体——出发，让模型写出这个库该答得上来的问题。提出来的是 proposed，人接受、改或
 * 拒；代理不替人决定库是干什么的。界面上点、或本体代理发现库里一条问题都没有时走
 * 这里（ProposeOntologyJob 的开场步）。
 */
@Component
public class ProposeQuestionsJob {

    private static final Logger log = LoggerFactory.getLogger(ProposeQuestionsJob.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 给没问题的库一次提这么多问题（QUESTIONS_PER_ROUND）。 */
    public static final int QUESTIONS_PER_ROUND = 10;
    /** 提问题时看多少条说得最多的形状 / 类别词 / 实体。 */
    static final int TOP_SIGNATURES = 40;
    static final int TOP_KIND_WORDS = 20;
    static final int TOP_ENTITIES = 20;

    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final OntologyService ontology;
    private final PhraseBindingsService phraseBindings;
    private final TypeBindingsService typeBindings;
    private final CompetencyQuestionsService questions;
    private final tech.seexun.jknow.store.mapper.QuestionsMapper questionMapper;
    private final LlmSupport llm;
    private final AppEventBus events;

    public ProposeQuestionsJob(KbMapper kbs, SettingsService settingsService,
                               OntologyService ontology, PhraseBindingsService phraseBindings,
                               TypeBindingsService typeBindings,
                               CompetencyQuestionsService questions,
                               tech.seexun.jknow.store.mapper.QuestionsMapper questionMapper,
                               LlmSupport llm, AppEventBus events) {
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.ontology = ontology;
        this.phraseBindings = phraseBindings;
        this.typeBindings = typeBindings;
        this.questions = questions;
        this.questionMapper = questionMapper;
        this.llm = llm;
        this.events = events;
    }

    /** main.rs:645-652：载荷取 kb_id，提完即成功（写入数只进日志）。 */
    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        proposeQuestions(UUID.fromString(kbNode.asText()));
    }

    /** 任务入口：给库提问题。返回写入数。 */
    public int proposeQuestions(UUID kbId) {
        try {
            KbRow kb = kbs.selectById(kbId);
            if (kb == null) {
                throw new IllegalStateException("no such knowledge base: " + kbId);
            }
            LlmSettingsRow settings = settingsService.get(kb.workspaceId());
            ChatClient client = LlmSupport.chatClient(settings);
            if (client == null) {
                throw new IllegalStateException(
                        "Chat model not configured; cannot propose questions");
            }
            return proposeQuestionsWith(kbId, settings, client);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 本体已配好 settings/client 时直入（ProposeOntologyJob 的开场步复用同一份客户端）。
     */
    public int proposeQuestionsWith(UUID kbId, LlmSettingsRow settings, ChatClient client)
            throws Exception {
        List<EntityTypeView> classes = ontology.entityTypes(kbId);
        List<RelationTypeView> props = ontology.relationTypes(kbId);
        Map<UUID, String> classKey = new HashMap<>();
        Map<UUID, String> propKey = new HashMap<>();
        for (EntityTypeView c : classes) {
            classKey.put(c.id(), c.key());
        }
        for (RelationTypeView p : props) {
            propKey.put(p.id(), p.key());
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
        List<String[]> classEntries = new ArrayList<>();
        for (EntityTypeView c : classes) {
            classEntries.add(new String[] {c.key(), c.label(),
                    c.description() == null ? "" : c.description()});
        }
        List<GlossaryProperty> propertyEntries = new ArrayList<>();
        for (RelationTypeView p : props) {
            propertyEntries.add(new GlossaryProperty(p.key(), p.label(), p.kind(),
                    keysOf.apply(p.domains()), keysOf.apply(p.ranges()),
                    p.description() == null ? "" : p.description()));
        }
        Glossary glossary = new Glossary(classEntries, propertyEntries, List.of());
        // 说得最多的形状，带上对齐绑到的属性
        List<PhraseSignature> sigs = new ArrayList<>(phraseBindings.signatures(kbId));
        sigs.sort(Comparator.comparingLong(PhraseSignature::count).reversed()
                .thenComparing(PhraseSignature::phrase));
        if (sigs.size() > TOP_SIGNATURES) {
            sigs = sigs.subList(0, TOP_SIGNATURES);
        }
        Map<List<Object>, Binding> bindings = new HashMap<>();
        for (Binding b : phraseBindings.bindings(kbId)) {
            bindings.put(b.key(), b);
        }
        List<TopSignature> topSigs = new ArrayList<>();
        for (PhraseSignature s : sigs) {
            Binding b = bindings.get(s.key());
            String property = null;
            if (b != null && "bound".equals(b.status()) && b.relationTypeId() != null) {
                property = propKey.get(b.relationTypeId());
            }
            String example = s.quotes().isEmpty() ? null : s.quotes().get(0);
            topSigs.add(new TopSignature(s.phrase(), s.subjectTypeKey(), s.objectTypeKey(),
                    s.objectIsValue(), s.count(), property, example));
        }
        List<KindWordSignature> words = new ArrayList<>(typeBindings.signatures(kbId));
        words.sort(Comparator.comparingLong(KindWordSignature::count).reversed()
                .thenComparing(KindWordSignature::kindWord));
        if (words.size() > TOP_KIND_WORDS) {
            words = words.subList(0, TOP_KIND_WORDS);
        }
        List<TopKindWord> topWords = words.stream()
                .map(w -> new TopKindWord(w.kindWord(), w.count())).toList();
        List<DegreeRow> degrees = questionMapper.selectTopEntityDegrees(kbId, TOP_ENTITIES);
        List<TopEntity> topEntities = degrees.stream()
                .map(d -> new TopEntity(d.name(), d.klass(), d.degree())).toList();
        List<QuestionRow> have = questions.list(kbId);
        List<String> existing = new ArrayList<>();
        for (QuestionRow q : have) {
            if (!"rejected".equals(q.status())) {
                existing.add(q.question());
            }
        }
        if (topSigs.isEmpty() && topWords.isEmpty() && topEntities.isEmpty()) {
            log.info("kb={} 提问题：库里还没有东西可问", kbId);
            return 0;
        }
        List<ChatClient.ChatMessage> messages = QuestionPrompts.buildQuestionMessages(
                topSigs, topWords, topEntities, glossary, existing, QUESTIONS_PER_ROUND);
        ChatClient.Reply reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0);
        Set<String> classKeys = new HashSet<>();
        for (EntityTypeView c : classes) {
            classKeys.add(c.key());
        }
        Set<String> propKeys = new HashSet<>();
        for (RelationTypeView p : props) {
            propKeys.add(p.key());
        }
        QuestionPrompts.QuestionParse parsed = QuestionPrompts.parseQuestionResponse(
                reply.text(), classKeys, propKeys);
        int written = 0;
        List<ProposedQuestion> candidates = parsed.questions().size() > QUESTIONS_PER_ROUND
                ? parsed.questions().subList(0, QUESTIONS_PER_ROUND) : parsed.questions();
        for (ProposedQuestion q : candidates) {
            if (questions.existsText(kbId, q.question())) {
                continue;
            }
            ObjectNode needs = JSON.createObjectNode();
            needs.set("classes", stringArray(q.classes()));
            needs.set("properties", stringArray(q.properties()));
            questions.create(kbId, new CompetencyQuestionsService.NewQuestion(q.question(),
                    null, needs, "agent", "proposed", null));
            written++;
        }
        log.info("kb={} proposed={} written={} malformed={} 提问题结束", kbId,
                parsed.questions().size(), written, parsed.malformed());
        if (written > 0) {
            events.emitPending(kbId);
        }
        return written;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode stringArray(List<String> xs) {
        com.fasterxml.jackson.databind.node.ArrayNode n = JSON.createArrayNode();
        xs.forEach(n::add);
        return n;
    }
}
