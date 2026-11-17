package tech.seexun.jknow.web.knowledge.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.extract.ImplicationPrompts;
import tech.seexun.jknow.extract.ImplicationPrompts.Implies;
import tech.seexun.jknow.extract.ImplicationPrompts.RuleItem;
import tech.seexun.jknow.extract.ImplicationPrompts.RuleReply;
import tech.seexun.jknow.extract.PhraseAlign.PropertyCandidate;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.ImplicationRulesService;
import tech.seexun.jknow.store.ImplicationRulesService.Proposal;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 向模型提蕴含规则（server/implication.rs:34-232 {@code propose_rules} 逐语义，
 * 0044 决定 3 第五片）：对齐器判过的签名（或类别词）除了它绑到的属性还蕴含什么。
 * 答案落成提案（要人批）或代理的驳回（什么也不蕴含，记下免得每轮都问）。
 *
 * <p><b>Java 化取舍</b>：Rust 批与批 {@code buffer_unordered(4)} 并行——Java 沿
 * {@code AlignPhrasesJob} 同一处取舍（phrase_alignment.rs:1020 的 buffer_unordered 同
 * 样落成顺序批）：批数少、每批一次调用，顺序执行结果等价，模型闸门（acquireChat）仍在
 * 每次调用内生效。
 */
@Component
public class RuleProposer {

    private static final Logger log = LoggerFactory.getLogger(RuleProposer.class);

    /** implication.rs:22 BATCH：一批问多少条形状（12）。read_phrases 的批是它的两倍。 */
    static final int BATCH = 12;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImplicationRulesService implicationRules;
    private final LlmSupport llm;

    public RuleProposer(ImplicationRulesService implicationRules, LlmSupport llm) {
        this.implicationRules = implicationRules;
        this.llm = llm;
    }

    /** 一次提规则的输入（implication.rs:27-33 RuleAsk）：签名或类别词（其一），带候选。 */
    public record RuleAsk(PhraseSignature phrase, KindWordSignature kindWord, String boundTo,
                          List<RelationTypeView> candidates, String basis) {
    }

    /** (提案数, 失败批次)——Rust 元组。 */
    public record Result(int proposed, int failed) {
    }

    /**
     * 向模型提规则，把答案落成提案（要人批）或代理的驳回。候选为空的形状不问。
     */
    public Result proposeRules(UUID kbId, LlmSettingsRow settings, ChatClient client,
                               List<RuleAsk> asks, Map<UUID, String> classKey,
                               Map<String, RelationTypeView> byKey) {
        List<RuleAsk> withCandidates = new ArrayList<>();
        for (RuleAsk a : asks) {
            if (!a.candidates().isEmpty()) {
                withCandidates.add(a);
            }
        }
        int proposed = 0;
        int failed = 0;
        for (int from = 0; from < withCandidates.size(); from += BATCH) {
            List<RuleAsk> batch = withCandidates.subList(from,
                    Math.min(from + BATCH, withCandidates.size()));
            List<RuleItem> items = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                items.add(ruleItem(batch.get(i), i, classKey));
            }
            List<tech.seexun.jknow.llm.ChatClient.ChatMessage> messages =
                    ImplicationPrompts.buildRuleMessages(items, ImplicationRulesService.READINGS);
            ChatClient.Reply reply;
            try {
                reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0);
            } catch (Exception e) {
                log.warn("kb={} 提规则调用失败，这一批留到下次: {}", kbId, e.getMessage());
                failed++;
                continue;
            }
            RuleReply parsed;
            try {
                parsed = ImplicationPrompts.parseRuleResponse(reply.text(), items,
                        ImplicationRulesService.READINGS);
            } catch (Exception e) {
                log.warn("kb={} 提规则回复解析失败，这一批留到下次: {}", kbId, e.getMessage());
                failed++;
                continue;
            }
            if (parsed.malformed() > 0) {
                log.info("kb={} malformed={} 提规则的回复里有坏项", kbId, parsed.malformed());
            }
            for (var choice : parsed.choices()) {
                int i = (int) choice.id();
                if (i < 0 || i >= batch.size()) {
                    continue;
                }
                RuleAsk a = batch.get(i);
                Shape shape = shapeOf(a);
                if (shape == null) {
                    continue;
                }
                Implies implies = choice.implies();
                if (implies != null) {
                    RelationTypeView p = byKey.get(implies.property());
                    if (p == null) {
                        continue;
                    }
                    ObjectNode votes = JSON.createObjectNode();
                    ObjectNode agent = votes.putObject("agent");
                    agent.put("property", implies.property());
                    // reading 可空：json! 里 Option<String> 序列化成 null
                    if (implies.reading() != null) {
                        agent.put("reading", implies.reading());
                    } else {
                        agent.putNull("reading");
                    }
                    if (implicationRules.propose(kbId, new Proposal(shape.trigger(),
                            shape.phrase(), shape.subjectTypeId(), shape.objectTypeId(),
                            shape.objectIsValue(), p.id(), implies.reading(), "proposed", votes,
                            a.basis(), shape.count(), shape.examples())) != null) {
                        proposed++;
                    }
                } else {
                    // 「什么也不蕴含」也要落下来，不然每轮都问。落成代理驳回的一行：
                    // 属性列非空不可，这里记的是形状本身，用签名绑到的属性或第一个候选占位
                    RelationTypeView placeholder = a.boundTo() != null
                            ? byKey.get(a.boundTo()) : null;
                    if (placeholder == null) {
                        placeholder = a.candidates().get(0);
                    }
                    if (placeholder == null) {
                        continue;
                    }
                    ObjectNode votes = JSON.createObjectNode();
                    votes.putNull("agent");
                    votes.put("reason", "nothing_implied");
                    implicationRules.propose(kbId, new Proposal(shape.trigger(), shape.phrase(),
                            shape.subjectTypeId(), shape.objectTypeId(), shape.objectIsValue(),
                            placeholder.id(), null, "rejected", votes, a.basis(), shape.count(),
                            shape.examples()));
                }
            }
        }
        return new Result(proposed, failed);
    }

    /** Rust 的 match (a.phrase, a.kind_word)：哪一头给了就是哪种形状。 */
    private record Shape(String trigger, String phrase, UUID subjectTypeId, UUID objectTypeId,
                         boolean objectIsValue, long count, List<String> examples) {
    }

    private static Shape shapeOf(RuleAsk a) {
        if (a.phrase() != null) {
            return new Shape("phrase", a.phrase().phrase(), a.phrase().subjectTypeId(),
                    a.phrase().objectTypeId(), a.phrase().objectIsValue(), a.phrase().count(),
                    a.phrase().examples());
        }
        if (a.kindWord() != null) {
            return new Shape("kind_word", a.kindWord().kindWord(), null, null, false,
                    a.kindWord().count(), a.kindWord().examples());
        }
        return null;
    }

    /** RuleItem 的拼装（implication.rs:70-107）：候选带键进提示词，类映成 key。 */
    private static RuleItem ruleItem(RuleAsk a, int id, Map<UUID, String> classKey) {
        List<PropertyCandidate> candidates = new ArrayList<>(a.candidates().size());
        for (RelationTypeView p : a.candidates()) {
            candidates.add(new PropertyCandidate(p.key(), p.label(), p.description(), p.kind(),
                    p.temporal(), keysOf(p.domains(), classKey), keysOf(p.ranges(), classKey),
                    List.of()));
        }
        if (a.phrase() != null) {
            PhraseSignature s = a.phrase();
            return new RuleItem(id, "phrase", s.phrase(), s.subjectTypeKey(), s.objectTypeKey(),
                    s.objectIsValue(), a.boundTo(), s.examples(), candidates);
        }
        KindWordSignature k = a.kindWord();
        return new RuleItem(id, "kind_word", k.kindWord(), null, null, false, null, k.examples(),
                candidates);
    }

    private static List<String> keysOf(List<UUID> ids, Map<UUID, String> classKey) {
        List<String> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            String key = classKey.get(id);
            if (key != null) {
                out.add(key);
            }
        }
        return out;
    }
}
