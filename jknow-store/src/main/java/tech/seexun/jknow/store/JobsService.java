package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.jobs.DeferredFailure;
import tech.seexun.jknow.common.jobs.JobFailures;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.JobStatus;
import tech.seexun.jknow.store.mapper.JobsMapper;

/**
 * 任务队列服务（逐字对齐旧仓库 crates/utopia-store/src/jobs.rs 的编排层）：
 * 入队四族（除非 pending / 除非 queued / 除非 queued+晚跑 / 显式预算）——非 tx 族
 * 各开独立事务（Rust 侧 {@code pool.begin}，{@link Propagation#REQUIRES_NEW} 同义），
 * _tx 族加入调用方事务（{@link Propagation#REQUIRED}：任务与业务写一起提交、一起回滚）。
 *
 * <p>失败写回（mark_failed，jobs.rs:419-489）三分支：<b>Terminal 优先</b>（一次就 failed）→
 * <b>Deferred</b>（挂回 queued、attempts 退回、不烧预算、3600s 窗口）→ 普通退避
 * 30s×attempts²（预算耗尽落 failed）。退避算术抽成纯函数（{@link #retryDelay}/
 * {@link #deferredRetrySecs}）——决定在这里，写库只是把决定落下去。
 */
@Service
public class JobsService {

    private static final Logger log = LoggerFactory.getLogger(JobsService.class);

    /** worker 唤醒通道（jobs.rs:15 JOB_CHANNEL）：入队事务内 pg_notify。 */
    public static final String JOB_CHANNEL = "utopia_jobs";

    /** 空闲轮询兜底（jobs.rs:202 IDLE_POLL）：run_at 在未来的重试、丢掉的通知由它接住。 */
    public static final Duration IDLE_POLL = Duration.ofSeconds(2);

    /**
     * Deferred 最长连续等待（jobs.rs:402 DEFER_WINDOW_SECS）：一小时够一个大本体在远端
     * 嵌入模型上补齐；过了还没好，多半是等待的那件事自己在失败，该按失败处理、被人看见。
     */
    public static final long DEFER_WINDOW_SECS = 60 * 60;

    private final JobsMapper jobs;

    public JobsService(JobsMapper jobs) {
        this.jobs = jobs;
    }

    // ---- 入队四族 ----

    /** 默认预算 3 的入队（jobs.rs:142-145 enqueue）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long enqueue(String kind, JsonNode payload) {
        return enqueueWithMaxAttempts(kind, payload, 3);
    }

    /**
     * 同种任务、同样载荷已经排着或正在跑就不再排（jobs.rs:39-64 enqueue_unless_pending）。
     * after 是去抖：一批编辑里第一条排下几秒后才跑的任务，其余每条都撞上它而不再排。
     *
     * @return 任务 id；null = 已有一条在排队
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long enqueueUnlessPending(String kind, JsonNode payload, Duration after) {
        Long id = jobs.insertUnlessPending(kind, payload, after.toMillis() / 1000.0);
        if (id != null) {
            jobs.notifyWorker(JOB_CHANNEL);
        }
        return id;
    }

    /** jobs.rs:66-75 enqueue_unless_queued（独立事务壳）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long enqueueUnlessQueued(String kind, JsonNode payload) {
        Long id = jobs.insertUnlessQueuedTx(kind, payload);
        if (id != null) {
            jobs.notifyWorker(JOB_CHANNEL);
        }
        return id;
    }

    /**
     * jobs.rs:80-99 enqueue_unless_queued_tx：排在调用方的事务里——任务与事务里别的写
     * 一起提交、一起回滚（人定一个类别词时，判定和它的对齐任务要么都在、要么都不在）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long enqueueUnlessQueuedTx(String kind, JsonNode payload) {
        Long id = jobs.insertUnlessQueuedTx(kind, payload);
        if (id != null) {
            jobs.notifyWorker(JOB_CHANNEL);
        }
        return id;
    }

    /**
     * jobs.rs:103-126 enqueue_unless_queued_after：挡的只是排着的，不挡在跑的——
     * 调用方正是那个在跑的任务、想给自己之后再排一个的时候用这个。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long enqueueUnlessQueuedAfter(String kind, JsonNode payload, Duration after) {
        Long id = jobs.insertUnlessQueuedAfter(kind, payload, after.toMillis() / 1000.0);
        if (id != null) {
            jobs.notifyWorker(JOB_CHANNEL);
        }
        return id;
    }

    /** 这个库有没有这一种任务排着或跑着（jobs.rs:130-140 pending_for_kb）。 */
    public boolean pendingForKb(String kind, UUID kbId) {
        Boolean pending = jobs.selectPendingForKb(kind, kbId.toString());
        return Boolean.TRUE.equals(pending);
    }

    /** 入队并显式指定有限重试预算（jobs.rs:142-162）：调用方不能把外部系统任务变成无限重试。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long enqueueWithMaxAttempts(String kind, JsonNode payload, int maxAttempts) {
        validateMaxAttempts(maxAttempts);
        long id = jobs.insertWithMaxAttemptsTx(kind, payload, maxAttempts);
        jobs.notifyWorker(JOB_CHANNEL);
        return id;
    }

    /**
     * 与业务写共用事务（jobs.rs:166-187）：不能先写 job、再写业务行——进程退出窗口会
     * 留下从业务行找不到的任务。加入调用方事务（REQUIRED：无外层时自开自提交）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public long enqueueWithMaxAttemptsTx(String kind, JsonNode payload, int maxAttempts) {
        validateMaxAttempts(maxAttempts);
        long id = jobs.insertWithMaxAttemptsTx(kind, payload, maxAttempts);
        jobs.notifyWorker(JOB_CHANNEL);
        return id;
    }

    /**
     * 事务内唤醒 worker（jobs.rs:497-501 notify_worker_tx）：不经 enqueue 族自行落
     * jobs 行的路径（documents.rs:1542/1809 抽取排队 RETURNING id 直插）用它补上
     * pg_notify——通知随事务提交才投递。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void notifyWorkerTx() {
        jobs.notifyWorker(JOB_CHANNEL);
    }

    /** jobs.rs:147-153/170-175 的 1..=32 校验（Validation 错误，文案逐字）。 */
    private static void validateMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 32) {
            throw AppException.validation("max_attempts must be between 1 and 32");
        }
    }

    // ---- 认领与写回 ----

    /** 认领一个到期任务（jobs.rs:344-360 claim_one）；无到期任务返回 null。 */
    public Job claimOne() {
        return jobs.claimOne();
    }

    /** 成功收尾（jobs.rs:362-374 mark_done）：last_error 清空。 */
    public void markDone(long id) {
        jobs.markDone(id);
    }

    /**
     * 失败写回（jobs.rs:419-489 mark_failed 三分支）。err 的 last_error 文本 =
     * 错误链逐层 {@code ": "} 连接（Rust {@code format!("{err:#}")} 同构）。
     */
    public void markFailed(Job job, Throwable err) {
        String text = JobFailures.chainText(err);
        // Terminal 优先：处理器最后改主意说「这次不算了」就该走 failed 路径，
        // 不该被 Deferred 覆盖（jobs.rs:422-433）
        if (JobFailures.isTerminal(err)) {
            jobs.markFailedTerminal(job.id(), text);
            return;
        }
        // Deferred（#526）：挂回 queued、attempts 退回、不烧预算；等待有期限——
        // 窗口已过时 UPDATE 不命中（0 行），落到普通退避（jobs.rs:444-466）
        DeferredFailure deferred = JobFailures.deferredOf(err);
        if (deferred != null) {
            double secs = deferredRetrySecs(deferred.retryIn());
            int rows = jobs.markFailedDeferred(job.id(), text, secs, (double) DEFER_WINDOW_SECS);
            if (rows > 0) {
                return;
            }
        }
        Long backoff = retryDelay(job.attempts(), job.maxAttempts(), false);
        if (backoff == null) {
            jobs.markFailedExhausted(job.id(), text);
            return;
        }
        jobs.markFailedBackoff(job.id(), text, (double) backoff);
    }

    /**
     * 下一次重试等多久；null = 到此为止（jobs.rs:387-392 retry_delay 纯函数）。
     * 到此为止的两种理由：次数用完了，或者处理器说了这次不会因为重试而变好。
     */
    public static Long retryDelay(int attempts, int maxAttempts, boolean terminal) {
        if (terminal || attempts >= maxAttempts) {
            return null;
        }
        return 30L * attempts * attempts;
    }

    /**
     * Deferred 的下一次等待秒数（jobs.rs:404-414 deferred_retry_secs）：<b>与失败次数无关</b>
     * （同一等待条件两次挂回得到同一 run_at 偏移，没有 60s、120s 的递增）；
     * 截断到秒、向上取整（少等一秒比早跑一秒好）；0/负/NaN 给 1s 下界。
     */
    public static long deferredRetrySecs(Duration retryIn) {
        double secs = Math.ceil(retryIn.toNanos() / 1_000_000_000.0);
        if (!Double.isFinite(secs) || secs < 1.0) {
            return 1;
        }
        return (long) secs;
    }

    // ---- 失败面（路由 + 回收） ----

    /** 重排范围（jobs.rs:258-263 RequeueScope）：三个条件都可空，空 = 不限。 */
    public record RequeueScope(UUID kbId, String kind, OffsetDateTime failedSince) {
    }

    /**
     * 范围内 failed 放回队列（jobs.rs:280-303 requeue_failed）：attempts 归零、立即到期。
     * hydrate_rss_entry 除外——其来源准入策略拥有重试。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int requeueFailed(RequeueScope scope) {
        int count = jobs.requeueFailed(scope.kind(), scope.failedSince(), scope.kbId());
        if (count > 0) {
            jobs.notifyWorker(JOB_CHANNEL);
        }
        return count;
    }

    /** 范围内 failed 条数（jobs.rs:306-313 failed_count）——设置页那一行「N 个失败任务」。 */
    public long failedCount(UUID kbId) {
        return jobs.failedCount(kbId);
    }

    /** 按 id 读一个任务，且只在它属于这个库时（jobs.rs:330-341 status_in_kb）。 */
    public JobStatus statusInKb(long id, UUID kbId) {
        return jobs.statusInKb(id, kbId);
    }

    /** 孤儿回收（jobs.rs:503-506）：启动时仍 running 的一律重排队（处理器均幂等）。 */
    public int recoverOrphans() {
        int recovered = jobs.recoverOrphans();
        if (recovered > 0) {
            log.warn("回收孤儿任务（上次进程退出时正在运行）count={}", recovered);
        }
        return recovered;
    }
}
