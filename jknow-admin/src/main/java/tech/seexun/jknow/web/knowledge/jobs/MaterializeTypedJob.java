package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.jobs.DeferredFailure;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.store.ErrataService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.entity.Job;

/**
 * 人定了一条短语签名，随判定同事务排下的重算任务（main.rs:491-537 {@code materialize_typed}
 * 的 match 臂，0051）。<b>试锁不等</b>：拿不到就挂 {@code Deferred} 十秒后再来——占着
 * 连接排队的是别人的池子；拿到了就是一次完整的按绑定重算，读的是<b>当前</b>绑定而不是
 * 判定时的载荷（回放旧载荷会盖掉后来的人）。
 *
 * <p>有新行才值得勘误看一眼；没新行的重算不排。队列卡片按绑定的状态显示，重算完了
 * 这条判定才算「落地」（Rust 侧 {@code emit_review} 与 {@code emit_graph}——Java 的
 * review/graph SSE 通道均未建，挂账 M4/M5，审查 M-8 补记 graph）。
 */
@Component
public class MaterializeTypedJob {

    private static final Logger log = LoggerFactory.getLogger(MaterializeTypedJob.class);

    private final TypedMaterializeService materialize;
    private final JobsService jobs;
    private final AuditService audit;

    public MaterializeTypedJob(TypedMaterializeService materialize, JobsService jobs,
                               AuditService audit) {
        this.materialize = materialize;
        this.jobs = jobs;
        this.audit = audit;
    }

    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        UUID kbId = UUID.fromString(kbNode.asText());
        Outcome typed = materialize.tryMaterialize(kbId);
        if (typed == null) {
            // main.rs:533-536 "typed projection busy" 挂 Deferred 10s
            throw new DeferredFailure("typed projection busy", Duration.ofSeconds(10));
        }
        if (typed.added() > 0 || typed.merged() > 0 || typed.retired() > 0) {
            String detail = "{\"job_id\":" + job.id() + ",\"added\":" + typed.added()
                    + ",\"merged\":" + typed.merged() + ",\"retired\":" + typed.retired() + "}";
            audit.record(null, kbId, UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    "alignment.materialized", "knowledge_base", kbId, detail);
        }
        // 有新行才值得勘误看一眼；没新行的重算不排（errata_review 的执行器属 M4——
        // job 注册保持 NOOP 桩，先入队不丢：M4 落地时队列里的等着被处理）
        if (typed.added() > 0) {
            jobs.enqueueUnlessQueued(ErrataService.JOB_KIND,
                    new com.fasterxml.jackson.databind.node.ObjectNode(
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance)
                            .put("kb_id", kbId.toString()));
        }
        log.debug("materialize_typed kb={} added={} merged={} retired={} implied={}", kbId,
                typed.added(), typed.merged(), typed.retired(), typed.implied());
    }
}
