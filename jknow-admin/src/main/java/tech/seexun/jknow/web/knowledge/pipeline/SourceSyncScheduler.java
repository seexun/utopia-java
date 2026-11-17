package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.SourceRow;

/**
 * 定时摄入调度器（对齐旧仓库 main.rs:213-239：每 60s 扫一次到期来源，mark_queued 幂等
 * 标记 → 入队 sync_source）。tick 只决定「谁到期了」，真正的同步在任务里跑——一个大
 * feed 可能要几十秒，卡在调度循环里会拖住别的库。
 *
 * <p><b>启用开关与 JobWorker 同一信号</b>（jknow.jobs.worker-enabled，双打期默认 false）：
 * 共享库双打形态下 Rust 参照实例的调度器也在扫同一张表——两边都入队会双倍同步。开关开启
 * 即本进程持有写路径所有权（worker 认领 + 调度入队 + 凭据补封三件事同源）。
 */
@Component
public class SourceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncScheduler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourcesService sources;
    private final JobsService jobs;
    private final boolean enabled;

    public SourceSyncScheduler(SourcesService sources, JobsService jobs,
                               @Value("${jknow.jobs.worker-enabled:false}") boolean enabled) {
        this.sources = sources;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    /** main.rs:216-239：60s tick（fixedDelay——上一轮没跑完不叠tick）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            for (SourceRow s : sources.dueSources()) {
                // mark_queued 幂等（已在队列/运行中返回 false，避免重复入队）
                if (!sources.markQueued(s.id())) {
                    continue;
                }
                try {
                    jobs.enqueue("sync_source",
                            JSON.createObjectNode().put("source_id", s.id().toString()));
                } catch (Exception e) {
                    log.warn("同步任务入队失败 source_id={} error={}", s.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("扫描到期来源失败: {}", e.getMessage());
        }
    }
}
