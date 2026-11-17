package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.extract.ImplicationPrompts;
import tech.seexun.jknow.extract.ImplicationPrompts.ReadingAnswer;
import tech.seexun.jknow.extract.ImplicationPrompts.ReadingItem;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.ImplicationRulesService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper.PendingReading;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * {@code read_phrases} 任务（main.rs:557-560 → implication.rs:234-316，0044 决定 3
 * 第五片）：已批准规则要的、缓存里还没有的读数，问一遍模型，落进缓存；填完排一次物化
 * ——隐含行在那里算。
 *
 * <p><b>单模型一次调用</b>（批 {@value #BATCH}×2 条/请求，温度 0）：不存在「两模型一致
 * 才落」——两票一致的机制在短语绑定（M3）。读不出来的也记（null 落缓存，别再问）；
 * 名字答案 {@code resolve_or_create_named}（库里按名找，没有建实体+名字事实）；年份
 * 答案包 {@code {"value":…}}（四位数字，parse 侧把关）。批失败计数，&gt;0 则 bail 让
 * job 重试；收尾<b>必排</b> {@code materialize_typed}——失败路不排（重试成功后自然排上）。
 */
@Component
public class ReadPhrasesJob {

    private static final Logger log = LoggerFactory.getLogger(ReadPhrasesJob.class);

    /** implication.rs:22 BATCH=12；read_phrases 的批是它的两倍（24 条/请求）。 */
    static final int BATCH = 12;

    private final ImplicationRulesService implicationRules;
    private final JobsService jobs;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final LlmSupport llm;

    public ReadPhrasesJob(ImplicationRulesService implicationRules, JobsService jobs,
                          KbMapper kbs, SettingsService settingsService, LlmSupport llm) {
        this.implicationRules = implicationRules;
        this.jobs = jobs;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.llm = llm;
    }

    public void handle(Job job) throws Exception {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        readPhrases(UUID.fromString(kbNode.asText()));
    }

    /** implication.rs:234-316 {@code read_phrases}。 */
    public void readPhrases(UUID kbId) throws Exception {
        List<PendingReading> pending = implicationRules.pendingReadings(kbId);
        if (pending.isEmpty()) {
            log.info("kb={} 没有待读的字", kbId);
        } else {
            KbRow kb = kbs.selectById(kbId);
            if (kb == null) {
                throw tech.seexun.jknow.common.AppException.NOT_FOUND;
            }
            LlmSettingsRow settings = settingsService.get(kb.workspaceId());
            ChatClient client = LlmSupport.chatClient(settings);
            if (client == null) {
                // 与 Rust「Chat model not configured; cannot read phrases」同一口径：job
                // 失败重试，不静默跳过（跳过会让已批准的规则永远不算隐含行）
                throw new IllegalStateException("Chat model not configured; cannot read phrases");
            }
            int answered = 0;
            int failed = 0;
            for (int from = 0; from < pending.size(); from += BATCH * 2) {
                List<PendingReading> batch = pending.subList(from,
                        Math.min(from + BATCH * 2, pending.size()));
                List<ReadingItem> items = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    items.add(new ReadingItem(i, batch.get(i).reading(), batch.get(i).phrase()));
                }
                List<ChatClient.ChatMessage> messages = ImplicationPrompts.buildReadingMessages(
                        items, ImplicationRulesService.READINGS);
                ChatClient.Reply reply;
                try {
                    reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0);
                } catch (Exception e) {
                    log.warn("kb={} 读数调用失败，这一批留到下次: {}", kbId, e.getMessage());
                    failed++;
                    continue;
                }
                ImplicationPrompts.ReadingReply parsed;
                try {
                    parsed = ImplicationPrompts.parseReadingResponse(reply.text(), items);
                } catch (Exception e) {
                    log.warn("kb={} 读数回复解析失败，这一批留到下次: {}", kbId, e.getMessage());
                    failed++;
                    continue;
                }
                if (parsed.malformed() > 0) {
                    log.info("kb={} malformed={} 读数的回复里有坏项", kbId, parsed.malformed());
                }
                for (ReadingAnswer a : parsed.answers()) {
                    int i = (int) a.id();
                    if (i < 0 || i >= batch.size()) {
                        continue;
                    }
                    PendingReading p = batch.get(i);
                    // 名字答案：库里按名找，没有建一个有名字事实的实体（0041——召回的桥）
                    UUID entity = a.name() != null
                            ? implicationRules.resolveOrCreateNamed(kbId, a.name()) : null;
                    // 年份答案包 {"value":…}——物化按值宾语读
                    com.fasterxml.jackson.databind.JsonNode value = a.value() != null
                            ? new ObjectNode(
                                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)
                                    .put("value", a.value())
                            : null;
                    implicationRules.recordReading(kbId, p.reading(), p.phrase(), entity, value);
                    answered++;
                }
            }
            log.info("kb={} pending={} answered={} failed={} 读数填缓存完成", kbId, pending.size(),
                    answered, failed);
            if (failed > 0) {
                throw new IllegalStateException(
                        failed + " reading batches failed; the job retries");
            }
        }
        // 收尾必排物化：隐含行在那里算（读数失败路不排——重试成功后自然排上）
        jobs.enqueueUnlessQueued(PhraseBindingsService.MATERIALIZE_KIND, kbPayload(kbId));
    }

    private static JsonNode kbPayload(UUID kbId) {
        ObjectNode payload = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        return payload.put("kb_id", kbId.toString());
    }
}
