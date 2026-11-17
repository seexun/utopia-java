package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.extract.ErrataPrompts;
import tech.seexun.jknow.extract.ErrataPrompts.ErrataFact;
import tech.seexun.jknow.extract.ErrataPrompts.ErrataProperty;
import tech.seexun.jknow.extract.ErrataPrompts.ErrataResponse;
import tech.seexun.jknow.extract.ErrataPrompts.FactVerdict;
import tech.seexun.jknow.extract.ErrataPrompts.Verdict;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.ErrataService;
import tech.seexun.jknow.store.ErrataService.ActionInput;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.ErrataMapper.Candidate;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 勘误 agent（server/errata.rs 399 行逐语义，0044 决定 7 第六刀）：抽取之后按文档复审
 * 类型化图谱。
 *
 * <p>一份文档一次：结构报了的事实先送去看，其余抽样；一次请求最多
 * {@value #FACTS_PER_REQUEST} 条，一份文档最多 {@value #REQUESTS_PER_DOCUMENT} 次——
 * 这就是「按文档的预算」。模型走 JSON 动作协议（{@link ErrataPrompts}），每一笔在库里
 * 记成动作（keep 也记——<b>没行等于没看过</b>，复审不重复），撤、改、加带着文档的原话；
 * 会牵动图外东西的留给人（0027 的闸门）。
 *
 * <p><b>两票协议</b>：结构报了的、agent 要撤或改的，再问一遍「文档说了没有」；
 * <b>只认显式的 not_stated</b>——第二票没说 not_stated 的撤/改一律改判 keep
 * （reason 前缀 {@code second vote: stated (…)}、quote 清空）。无旗的撤改不问第二票：
 * 结构没报过的事 agent 单方面动，本来就走 unflagged 留给人（errata.record 的规则），
 * 闸门之外还有一道人的防线。
 *
 * <p>度量记在 errata_runs 上：看了几条、问了几次、端点报的 token。模型未配置时任务
 * 失败重试（与 read_phrases 同口径——静默跳过会让结构报过的行永远没人看）。
 */
@Component
public class ErrataReviewJob {

    private static final Logger log = LoggerFactory.getLogger(ErrataReviewJob.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** errata.rs:24 DOCS_PER_JOB：一次任务最多看几份文档；还有剩的再排一次。 */
    static final int DOCS_PER_JOB = 20;

    /** errata.rs:26 FACTS_PER_DOCUMENT：一份文档最多送去看几条：结构报的先占，抽样的补到这个数。 */
    static final int FACTS_PER_DOCUMENT = 40;

    /** errata.rs:28 SAMPLE：抽样最多几条（没报的）。 */
    static final int SAMPLE = 10;

    /** errata.rs:30 FACTS_PER_REQUEST：一次请求最多几条。 */
    static final int FACTS_PER_REQUEST = 20;

    /** errata.rs:32 REQUESTS_PER_DOCUMENT：一份文档最多问几次（撤改的第二票另算，每批至多一次）。 */
    static final int REQUESTS_PER_DOCUMENT = 2;

    /** errata.rs:34 DOC_CHARS：正文最多带多少字（码点）；再长的文档截断，截掉的部分这一轮看不到。 */
    static final int DOC_CHARS = 16_000;

    /** errata.rs:322 GLOSSARY_NEAREST：勘误一次请求带多少条属性（有向量时）。 */
    static final long GLOSSARY_NEAREST = 24;

    /** errata.rs:36-44 DocumentOutcome：一份文档看完的账。 */
    static final class DocumentOutcome {

        int reviewed;
        int applied;
        int held;
        int refused;
        int requests;
    }

    /** 一条词汇表条目：提示词用的形状 + 近邻查询对回的属性行 id（Rust nearest 按行返回，Java 按 id）。 */
    record GlossaryEntry(UUID id, ErrataProperty property) {
    }

    private final ErrataService errata;
    private final JobsService jobs;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final OntologyService ontology;
    private final LlmSupport llm;
    private final AuditService audit;
    private final AppEventBus events;

    public ErrataReviewJob(ErrataService errata, JobsService jobs, KbMapper kbs,
                           SettingsService settingsService, OntologyService ontology,
                           LlmSupport llm, AuditService audit, AppEventBus events) {
        this.errata = errata;
        this.jobs = jobs;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.ontology = ontology;
        this.llm = llm;
        this.audit = audit;
        this.events = events;
    }

    /** dispatch 桩：payload 的 kb_id。 */
    public void handle(Job job) throws Exception {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        review(UUID.fromString(kbNode.asText()));
    }

    /** errata.rs:47-123 {@code review}：有类型化行还没看过的文档，一份一份看；看不完再排一次。 */
    public void review(UUID kbId) throws Exception {
        List<UUID> due = errata.documentsDue(kbId, DOCS_PER_JOB);
        if (due.isEmpty()) {
            log.info("kb={} 没有待勘误的文档", kbId);
            return;
        }
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw AppException.NOT_FOUND;
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        if (client == null) {
            // 与 Rust「Chat model not configured; cannot review errata」同一口径：任务失败
            // 重试，不静默跳过（跳过 = 结构报过的行永远没人看）
            throw new IllegalStateException("Chat model not configured; cannot review errata");
        }
        // 词表：本体属性全量（类 id 映成 key 进 domains/ranges），再按文档裁
        // （shortlistGlossary）——整份本体每次都带是勘误一次请求 4.7k token 里的 4k
        List<GlossaryEntry> glossary = glossary(kbId);
        int failed = 0;
        int applied = 0;
        int held = 0;
        for (UUID documentId : due) {
            try {
                DocumentOutcome o = reviewDocument(settings, client, kbId, documentId, glossary);
                applied += o.applied;
                held += o.held;
            } catch (Exception e) {
                log.warn("kb={} doc={} 这份文档的勘误没跑完，任务重试时再来: {}", kbId, documentId,
                        e.getMessage());
                failed++;
            }
        }
        if (applied > 0 || held > 0) {
            ObjectNode detail = JSON.createObjectNode();
            detail.put("documents", due.size());
            detail.put("applied", applied);
            detail.put("held", held);
            audit.recordOpt(null, kbId, null, "errata.reviewed", "knowledge_base", kbId,
                    detail.toString());
            events.emitReview(kbId);
            events.emitGraph(kbId);
        }
        if (failed > 0) {
            throw new IllegalStateException(
                    failed + " documents failed errata review; the job retries");
        }
        if (!errata.documentsDue(kbId, 1).isEmpty()) {
            jobs.enqueueUnlessQueued(ErrataService.JOB_KIND, kbPayload(kbId));
        }
    }

    /** errata.rs:60-82 词表：relation_type_views 全量 + 类 id → key 映射进 domains/ranges。 */
    private List<GlossaryEntry> glossary(UUID kbId) {
        List<RelationTypeView> props = ontology.relationTypes(kbId);
        List<EntityTypeView> classes = ontology.entityTypes(kbId);
        Map<UUID, String> classKey = new HashMap<>();
        for (EntityTypeView c : classes) {
            classKey.put(c.id(), c.key());
        }
        List<GlossaryEntry> out = new ArrayList<>(props.size());
        for (RelationTypeView p : props) {
            List<String> domains = new ArrayList<>();
            for (UUID d : p.domains()) {
                String key = classKey.get(d);
                if (key != null) {
                    domains.add(key);
                }
            }
            List<String> ranges = new ArrayList<>();
            for (UUID r : p.ranges()) {
                String key = classKey.get(r);
                if (key != null) {
                    ranges.add(key);
                }
            }
            out.add(new GlossaryEntry(p.id(), new ErrataProperty(p.key(), p.label(),
                    p.description(), p.kind(), domains, ranges, p.datatype())));
        }
        return out;
    }

    /** errata.rs:126-319 {@code review_document}：一份文档——挑要看的，按预算问，每一票记一笔。 */
    DocumentOutcome reviewDocument(LlmSettingsRow settings, ChatClient client, UUID kbId,
                                   UUID documentId, List<GlossaryEntry> glossary) {
        List<Candidate> all = errata.candidates(kbId, documentId);
        List<Candidate> chosen = choose(all);
        DocumentOutcome outcome = new DocumentOutcome();
        // 没有可看的行：第一次见这份文档就送一份空清单去（agent 只能加）；看过的不再送
        if (chosen.isEmpty() && errata.runsOf(documentId) > 0) {
            return outcome;
        }
        int flagged = 0;
        for (Candidate c : chosen) {
            if (c.flag() != null) {
                flagged++;
            }
        }
        UUID run = errata.startRun(kbId, documentId, flagged, chosen.size() - flagged);
        String text = errata.documentText(documentId);
        String document = truncate(text, DOC_CHARS);
        List<GlossaryEntry> shortlist = shortlistGlossary(settings, kbId, document, glossary,
                chosen);
        List<ErrataProperty> shortProperties = new ArrayList<>(shortlist.size());
        for (GlossaryEntry g : shortlist) {
            shortProperties.add(g.property());
        }
        long promptTokens = 0;
        long completionTokens = 0;
        boolean sawUsage = false;
        Exception error = null;
        List<List<Candidate>> batches = new ArrayList<>();
        if (chosen.isEmpty()) {
            batches.add(chosen);
        } else {
            for (int from = 0; from < chosen.size() && batches.size() < REQUESTS_PER_DOCUMENT;
                    from += FACTS_PER_REQUEST) {
                batches.add(chosen.subList(from,
                        Math.min(from + FACTS_PER_REQUEST, chosen.size())));
            }
        }
        for (List<Candidate> batch : batches) {
            List<ErrataFact> items = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Candidate c = batch.get(i);
                items.add(new ErrataFact(i, c.subject(), c.subjectClass(), c.property(),
                        c.object(), c.objectClass(), c.flag(), c.quote()));
            }
            List<ChatClient.ChatMessage> messages = ErrataPrompts.buildErrataMessages(document,
                    shortProperties, items);
            outcome.requests++;
            ChatClient.Reply reply;
            try {
                reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0);
            } catch (Exception e) {
                error = e;
                break;
            }
            if (reply.usage() != null) {
                sawUsage = true;
                promptTokens += reply.usage().promptTokens();
                completionTokens += reply.usage().completionTokens();
            }
            ErrataResponse parsed;
            try {
                parsed = ErrataPrompts.parseErrataResponse(reply.text(), items);
            } catch (Exception e) {
                error = new IllegalStateException("errata reply unreadable: " + e.getMessage(), e);
                break;
            }
            if (parsed.malformed > 0 || parsed.additionsRefused > 0) {
                log.info("kb={} doc={} malformed={} additions_refused={} 勘误回复里有坏项",
                        kbId, documentId, parsed.malformed, parsed.additionsRefused);
            }
            // 第二票：结构报了的、agent 要撤或改的，再问一遍文档说了没有；没拿到
            // not_stated 的改成 keep（没报的那些不用问——record 的 unflagged 规则留给人）
            List<FactVerdict> verdicts = new ArrayList<>(parsed.verdicts);
            List<ErrataFact> doubted = new ArrayList<>();
            for (FactVerdict v : verdicts) {
                if (v.verdict() instanceof ErrataPrompts.Keep) {
                    continue;
                }
                int i = (int) v.id();
                if (i >= 0 && i < items.size() && items.get(i).flag() != null) {
                    doubted.add(items.get(i));
                }
            }
            if (!doubted.isEmpty()) {
                List<Long> ids = new ArrayList<>(doubted.size());
                for (ErrataFact f : doubted) {
                    ids.add(f.id());
                }
                List<ChatClient.ChatMessage> confirm =
                        ErrataPrompts.buildConfirmMessages(document, doubted);
                outcome.requests++;
                ChatClient.Reply second;
                try {
                    second = llm.chatRetryingRateLimits(settings, client, confirm, 0.0);
                } catch (Exception e) {
                    error = e;
                    break;
                }
                if (second.usage() != null) {
                    sawUsage = true;
                    promptTokens += second.usage().promptTokens();
                    completionTokens += second.usage().completionTokens();
                }
                Set<Long> notStated;
                try {
                    notStated = ErrataPrompts.parseConfirmResponse(second.text(), ids);
                } catch (Exception e) {
                    error = new IllegalStateException(
                            "errata second vote unreadable: " + e.getMessage(), e);
                    break;
                }
                for (int v = 0; v < verdicts.size(); v++) {
                    FactVerdict old = verdicts.get(v);
                    // 只认显式 not_stated：没进集合的撤/改改判 keep——两票不齐不动图，
                    // reason 前缀留痕、quote 清空（keep 不需要引文）
                    if (!(old.verdict() instanceof ErrataPrompts.Keep)
                            && ids.contains(old.id()) && !notStated.contains(old.id())) {
                        verdicts.set(v, new FactVerdict(old.id(), new ErrataPrompts.Keep(),
                                "second vote: stated (" + old.reason() + ")", null));
                    }
                }
            }
            for (FactVerdict v : verdicts) {
                int i = (int) v.id();
                if (i < 0 || i >= batch.size()) {
                    continue;
                }
                Candidate c = batch.get(i);
                ErrataService.Proposed proposed;
                if (v.verdict() instanceof ErrataPrompts.Keep) {
                    proposed = new ErrataService.Keep();
                } else if (v.verdict() instanceof ErrataPrompts.Retract) {
                    proposed = new ErrataService.Retract();
                } else if (v.verdict() instanceof ErrataPrompts.Revise rv) {
                    proposed = new ErrataService.Revise(rv.property(), rv.object());
                } else {
                    continue;
                }
                ErrataService.Recorded r = errata.record(kbId, new ActionInput(run, documentId,
                        c, proposed, v.reason(), v.quote(), text));
                outcome.reviewed++;
                count(outcome, r.status(), v.verdict());
            }
            for (ErrataPrompts.Addition a : parsed.additions) {
                ErrataService.Recorded r = errata.record(kbId, new ActionInput(run, documentId,
                        null, new ErrataService.Add(a.subject(), a.property(), a.object()),
                        a.reason(), a.quote(), text));
                // 加落地也算「动了图」——verdict 按 retract 传（count 只分 keep 与非 keep）
                count(outcome, r.status(), new ErrataPrompts.Retract());
            }
        }
        // 账还是记的：问了几次、端点报没报用量（没报就空着），哪怕中途失败——
        // 失败的那次请求也算花了
        errata.finishRun(run, outcome.requests, sawUsage ? promptTokens : null,
                sawUsage ? completionTokens : null);
        log.info("kb={} doc={} reviewed={} applied={} held={} refused={} requests={} 勘误看完一份文档",
                kbId, documentId, outcome.reviewed, outcome.applied, outcome.held,
                outcome.refused, outcome.requests);
        if (error != null) {
            throw error instanceof RuntimeException rt ? rt : new IllegalStateException(error);
        }
        return outcome;
    }

    /**
     * errata.rs:326-365 {@code shortlist_glossary}：这份文档看得到的属性——向量最近的
     * {@value #GLOSSARY_NEAREST} 条，加上清单里事实已经用的。没配嵌入模型、属性没向量、
     * 嵌入失败：全部。
     */
    private List<GlossaryEntry> shortlistGlossary(LlmSettingsRow settings, UUID kbId,
            String document, List<GlossaryEntry> all, List<Candidate> chosen) {
        tech.seexun.jknow.llm.EmbedClient embed = LlmSupport.embedClient(settings);
        if (embed == null) {
            return all;
        }
        float[] vector;
        Semaphore permit = llm.acquirePermit(settings.embedBaseUrl(), settings.embedModel());
        try {
            List<float[]> got = embed.embed(List.of(document));
            vector = got.size() == 1 ? got.get(0) : null;
        } catch (Exception e) {
            return all;
        } finally {
            if (permit != null) {
                permit.release();
            }
        }
        if (vector == null) {
            return all;
        }
        // Java 的近邻查询回 id（Rust 回整行）——id 对回词汇表条目
        List<UUID> near = ontology.nearestRelationTypeIds(kbId, vectorText(vector),
                GLOSSARY_NEAREST, null);
        if (near.isEmpty()) {
            return all;
        }
        Set<UUID> keepIds = new HashSet<>(near);
        Set<String> chosenKeys = new HashSet<>();
        for (Candidate c : chosen) {
            chosenKeys.add(c.property());
        }
        List<GlossaryEntry> out = new ArrayList<>();
        for (GlossaryEntry g : all) {
            if (keepIds.contains(g.id()) || chosenKeys.contains(g.property().key())) {
                out.add(g);
            }
        }
        return out;
    }

    /** errata.rs:368-375 {@code count}：keep 落地不算「动了图」；撤改加落地算。 */
    private static void count(DocumentOutcome o, String status, Verdict verdict) {
        if ("applied".equals(status) && !(verdict instanceof ErrataPrompts.Keep)) {
            o.applied++;
        } else if ("held".equals(status)) {
            o.held++;
        } else if ("refused".equals(status)) {
            o.refused++;
        }
    }

    /** errata.rs:378-388 {@code choose}：结构报了的全要（到上限为止），没报的抽前几条补上。 */
    static List<Candidate> choose(List<Candidate> all) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : all) {
            if (c.flag() != null && out.size() < FACTS_PER_DOCUMENT) {
                out.add(c);
            }
        }
        int room = Math.min(FACTS_PER_DOCUMENT - out.size(), SAMPLE);
        for (Candidate c : all) {
            if (room <= 0) {
                break;
            }
            if (c.flag() == null) {
                out.add(c);
                room--;
            }
        }
        return out;
    }

    /** errata.rs:390-395 {@code truncate}：按码点截（Rust char_indices——多字节不切半）。 */
    static String truncate(String s, int maxChars) {
        if (s == null || s.codePointCount(0, s.length()) <= maxChars) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxChars));
    }

    /** float[] → pgvector 文本形态（近邻查询用；AlignPhrasesJob 同款）。 */
    private static String vectorText(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private static JsonNode kbPayload(UUID kbId) {
        ObjectNode payload = JSON.createObjectNode();
        return payload.put("kb_id", kbId.toString());
    }
}
