package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.JobsService;

/**
 * 治理的定时扫描（对齐旧仓库 main.rs:281-304：每小时扫一遍 due 的库入队 govern）：
 * 开关开着、队列里还有 agent 没看过的对的库，排一轮。
 *
 * <p>抽取结束与人的裁决各自即时排；这一轮兜的是失败重试耗尽之后留下的积压，所以是
 * 小时不是分钟——模型挂了的时候，每分钟撞一次没有意义（Rust 头注逐语义）。
 *
 * <p><b>启用开关与 JobWorker 同一信号</b>（jknow.jobs.worker-enabled，双打期默认
 * false，与 {@link InferenceScheduler} 同源）：共享库双打形态下 Rust 参照实例的调度器
 * 也在扫同一张表——两边都入队会双倍治理。首扫延迟一分钟（Rust interval 首拍即时，
 * 这里沿 InferenceScheduler 的 startup 留缝口径：应用刚起时连接池还没热）。
 */
@Component
public class GovernanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(GovernanceScheduler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GovernanceService governance;
    private final JobsService jobs;
    private final boolean enabled;

    public GovernanceScheduler(GovernanceService governance, JobsService jobs,
                               @Value("${jknow.jobs.worker-enabled:false}") boolean enabled) {
        this.governance = governance;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    /** main.rs:286-304：3600s tick（fixedDelay 不叠拍；失败只 warn 不抛，下一小时再来）。 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            for (UUID kbId : governance.due()) {
                try {
                    jobs.enqueueUnlessQueued("govern",
                            JSON.createObjectNode().put("kb_id", kbId.toString()));
                } catch (Exception e) {
                    log.warn("治理任务入队失败 kb_id={} error={}", kbId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("扫描治理积压失败: {}", e.getMessage());
        }
    }
}
