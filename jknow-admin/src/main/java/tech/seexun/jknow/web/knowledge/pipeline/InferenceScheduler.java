package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 定时推理调度器（对齐旧仓库 main.rs:252-279：每 60s 扫一次到期库入队
 * materialize_inferences）。
 *
 * <p><b>必须定时，不能只靠手点</b>：事实是持续变的——每篇文档抽取都在加边——而派生只在
 * 跑的那一刻算。不定时的话，下一篇文档进来之后图上的派生就是缺的，而这种缺失界面上
 * 看不出来（不是错，是新链没推）。tick 只决定「谁到期了」，真正的推导在任务里跑——
 * 一个大库全量重推可能要几秒，卡在调度循环里会拖住别的库。
 *
 * <p><b>启用开关与 JobWorker 同一信号</b>（jknow.jobs.worker-enabled，双打期默认 false）：
 * 共享库双打形态下 Rust 参照实例的调度器也在扫同一张表——两边都入队会双倍推导。
 */
@Component
public class InferenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(InferenceScheduler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReasoningService reasoning;
    private final JobsService jobs;
    private final boolean enabled;

    public InferenceScheduler(ReasoningService reasoning, JobsService jobs,
                              @Value("${jknow.jobs.worker-enabled:false}") boolean enabled) {
        this.reasoning = reasoning;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    /** main.rs:258-279：60s tick（fixedDelay——上一轮没跑完不叠 tick；Skip 错过的周期）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            for (UUID kbId : reasoning.dueForInference()) {
                try {
                    jobs.enqueue("materialize_inferences",
                            JSON.createObjectNode().put("kb_id", kbId.toString()));
                } catch (Exception e) {
                    log.warn("推理任务入队失败 kb_id={} error={}", kbId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("扫描到期推理失败: {}", e.getMessage());
        }
    }
}
