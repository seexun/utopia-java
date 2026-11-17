package tech.seexun.jknow.web.knowledge.jobs;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.mapper.DeploymentSettingsMapper;
import tech.seexun.jknow.store.secrets.SealingService;

/**
 * 任务 worker（逐字对齐旧仓库 crates/utopia-store/src/jobs.rs:495-575 run_worker 的
 * 调度循环 + main.rs:171-209 的装配序）：运行中任务数低于目标并发就继续认领，
 * 空闲时等 LISTEN utopia_jobs 通知、2s 轮询兜底（#517）；每任务独立线程执行，
 * 失败按 30s×attempts² 退避；panic（{@link Throwable}）隔离收尸。
 *
 * <p><b>启动序</b>（main.rs:123-137/171-182）：凭据补封 → 孤儿回收（启动时仍 running
 * 的必是上次进程退出遗留）→ 装载 worker_concurrency（deployment_settings 默认 64 钳
 * [1,256]）→ 起 LISTEN 线程 + 调度线程。并发数经 {@link AtomicInteger} 热读——
 * LISTEN 线程每轮（≤2s）从系统设置重读，改并发即时生效，无需重启。
 *
 * <p><b>启用开关</b>：M2 双打期 Java 与 Rust 参照实例<b>写同一库互斥</b>——worker
 * 默认关闭（jknow.jobs.worker-enabled，默认 false；部署/集成测试显式开）。
 * 开关同一承担「本进程持有写路径所有权」信号：凭据补封也只在启用时执行
 * （Rust 单进程部署无此歧义，双打期需要；补封幂等，Rust 实例自会补它自己的）。
 *
 * <p><b>LISTEN 取舍</b>：pgjdbc 专用连接 + 阻塞 getNotifications(2s)（对等 sqlx PgListener
 * 一直握着一条池连接的做法，池上限从此少一条）；监听建不起来不致命——退回 2s 轮询，
 * 行为与没有通知时一样（jobs.rs:213-216）。
 */
@Component
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    /** 认领不到时调度循环的睡眠步长（run_worker：运行中≥目标并发睡 200ms 再看）。 */
    private static final long DISPATCH_PACE_MS = 200;
    /** 认领报错后的退避（run_worker：库不可达时 5s 后重试，避免紧循环刷日志）。 */
    private static final long CLAIM_ERROR_BACKOFF_MS = 5_000;
    /** 并发数缺省与钳制边界（main.rs:172-182 + 迁移 0011 列缺省）。 */
    private static final int CONCURRENCY_DEFAULT = 64;
    private static final int CONCURRENCY_MIN = 1;
    private static final int CONCURRENCY_MAX = 256;

    private final JobsService jobs;
    private final JobDispatcher dispatcher;
    private final SealingService sealing;
    private final DeploymentSettingsMapper deploymentSettings;
    private final DataSource dataSource;
    private final boolean enabled;

    /** 运行中任务数（run_worker 的 running AtomicUsize）。 */
    private final AtomicInteger running = new AtomicInteger();
    /** 目标并发（热调入口；LISTEN 线程每轮重读 deployment_settings 更新）。 */
    private final AtomicInteger concurrency = new AtomicInteger(CONCURRENCY_DEFAULT);
    /** 唤醒信号：LISTEN 线程 release，调度线程 tryAcquire(2s)——通知先到就先醒。 */
    private final Semaphore wake = new Semaphore(0);

    private volatile boolean started;
    private volatile boolean stopping;
    private ExecutorService executor;
    private Thread workerThread;
    private Thread listenerThread;
    private Connection listenerConnection;

    public JobWorker(JobsService jobs, JobDispatcher dispatcher, SealingService sealing,
                     DeploymentSettingsMapper deploymentSettings, DataSource dataSource,
                     @Value("${jknow.jobs.worker-enabled:false}") boolean enabled) {
        this.jobs = jobs;
        this.dispatcher = dispatcher;
        this.sealing = sealing;
        this.deploymentSettings = deploymentSettings;
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("jobs worker 未启用（jknow.jobs.worker-enabled=false，双打期 Rust 实例持有队列）");
            return;
        }
        // 凭据补封（main.rs:133-137）：钥匙装好之后的第一次启动把升级前落库的明文凭据补成密文
        int sealed = sealing.backfill();
        if (sealed > 0) {
            log.info("凭据补封完成 rows={}", sealed);
        }
        // 孤儿回收（run_worker:501-518）：进程被杀时 running 任务无人收尸，一律重排队
        jobs.recoverOrphans();
        refreshConcurrency();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "utopia-job-executor");
            t.setDaemon(true);
            return t;
        });
        stopping = false;
        listenerThread = new Thread(this::listenLoop, "utopia-job-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        workerThread = new Thread(this::dispatchLoop, "utopia-job-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        started = true;
        log.info("jobs worker 已启动 concurrency={}", concurrency.get());
    }

    @Override
    public void stop() {
        started = false;
        stopping = true;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        closeListenerConnection();
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    // ---- 调度循环（run_worker jobs.rs:524-574 逐分支） ----

    private void dispatchLoop() {
        while (!stopping) {
            int cap = Math.max(1, concurrency.get());
            if (running.get() >= cap) {
                sleep(DISPATCH_PACE_MS);
                continue;
            }
            try {
                Job job = jobs.claimOne();
                if (job != null) {
                    running.incrementAndGet();
                    executor.execute(() -> runOne(job));
                } else {
                    waitForWork();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.error("任务认领失败，5s 后重试: {}", e.getMessage());
                sleep(CLAIM_ERROR_BACKOFF_MS);
            }
        }
    }

    /** 空闲等待（jobs.rs:237-251 wait_for_work）：通知先到就先醒，否则等满 2s。 */
    private void waitForWork() throws InterruptedException {
        wake.tryAcquire(JobsService.IDLE_POLL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 单任务执行（run_worker:536-563 的 spawn 体）——Throwable 收尸 = Rust panic 隔离。 */
    private void runOne(Job job) {
        try {
            dispatcher.dispatch(job);
            jobs.markDone(job.id());
        } catch (Throwable t) {
            // 处理器 panic 也要收尸：不接住的话任务行永远停在 running、无错误无重试
            log.warn("任务执行失败 job_id={} kind={}: {}", job.id(), job.kind(), t.toString());
            try {
                jobs.markFailed(job, t);
            } catch (Exception writeBack) {
                log.error("任务状态写回失败 job_id={}: {}", job.id(), writeBack.getMessage());
            }
        } finally {
            running.decrementAndGet();
        }
    }

    // ---- LISTEN（listen_for_jobs jobs.rs:217-230：连接一直握着，还回池里状态就没了） ----

    private void listenLoop() {
        while (!stopping) {
            try {
                PGConnection pg = establishListener();
                while (!stopping) {
                    PGNotification[] notes = pg.getNotifications(
                            (int) JobsService.IDLE_POLL.toMillis());
                    if (notes != null && notes.length > 0) {
                        wake.release();
                    }
                    // worker_concurrency 热读：与 Rust 的 AtomicUsize 等价——系统设置里
                    // 改并发即时生效，无需重启（main.rs:171-182 的装载 + 每轮刷新）
                    refreshConcurrency();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // 监听出错不能立刻再认领（紧循环风险）：按轮询节奏睡一觉再重连
                // （jobs.rs:244-248 同一理由）
                log.warn("jobs worker 通知监听失败，继续轮询: {}", e.getMessage());
                closeListenerConnection();
                sleep(JobsService.IDLE_POLL.toMillis());
            }
        }
    }

    private PGConnection establishListener() throws Exception {
        // 从池里取出后一直握着（LISTEN 是连接级状态）；失败退回轮询，行为与无通知一致
        listenerConnection = dataSource.getConnection();
        PGConnection pg = listenerConnection.unwrap(PGConnection.class);
        try (Statement st = listenerConnection.createStatement()) {
            st.execute("LISTEN " + JobsService.JOB_CHANNEL);
        }
        return pg;
    }

    private void closeListenerConnection() {
        Connection conn = this.listenerConnection;
        this.listenerConnection = null;
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                log.debug("关闭 LISTEN 连接失败: {}", e.getMessage());
            }
        }
    }

    // ---- 并发热读（main.rs:172-182） ----

    /** 读 deployment_settings.worker_concurrency，缺省 64 钳 [1,256]；读失败保旧值。 */
    private void refreshConcurrency() {
        Integer value;
        try {
            value = deploymentSettings.selectWorkerConcurrency();
        } catch (Exception e) {
            // 查询失败（DB 降级）保旧值——不让调度循环跟着一起死
            log.debug("worker_concurrency 读取失败，沿用旧值: {}", e.getMessage());
            return;
        }
        int n = value == null ? CONCURRENCY_DEFAULT : value;
        concurrency.set(Math.max(CONCURRENCY_MIN, Math.min(CONCURRENCY_MAX, n)));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
