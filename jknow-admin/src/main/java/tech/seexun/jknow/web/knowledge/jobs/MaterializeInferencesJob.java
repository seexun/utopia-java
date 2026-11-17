package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ReasoningSolver.DeriveReport;

/**
 * 定时重推任务（main.rs:417-448 materialize_inferences 的 match 臂）。
 *
 * <p><b>先记时间再推</b>——推导抛错时也不该让这个库在下一分钟被重新扫起来，那会变成
 * 一个每分钟失败一次的循环。materialize 本来就在拿这一轮算出来的对上库里现有的，
 * 「对比」不是新机制：inserted/invalidated 有变化时才写审计——没有变化时不写，
 * 免得台账被每小时一条「什么都没变」淹掉。actor 是 nil（Rust Uuid::nil——系统任务）。
 */
@Component
public class MaterializeInferencesJob {

    private static final Logger log = LoggerFactory.getLogger(MaterializeInferencesJob.class);

    private final ReasoningService reasoning;
    private final AuditService audit;

    public MaterializeInferencesJob(ReasoningService reasoning, AuditService audit) {
        this.reasoning = reasoning;
        this.audit = audit;
    }

    public void handle(tech.seexun.jknow.store.entity.Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        UUID kbId = UUID.fromString(kbNode.asText());
        // 先记时间再推（main.rs:425）：失败也不留一个每分钟重扫的循环
        reasoning.markInferenceRan(kbId);
        DeriveReport report = reasoning.materialize(kbId);
        if (report.inserted() > 0 || report.invalidated() > 0) {
            String detail = "{\"scheduled\":true,\"inserted\":" + report.inserted()
                    + ",\"invalidated\":" + report.invalidated()
                    + ",\"rules\":" + report.rules() + "}";
            // actor=nil：系统任务（main.rs:437 Uuid::nil()）；审计失败不带垮任务
            audit.record(null, kbId, UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    "inference.materialized", "knowledge_base", kbId, detail);
            log.info("推理物化完成 kb={} inserted={} invalidated={}", kbId, report.inserted(),
                    report.invalidated());
        }
    }
}
