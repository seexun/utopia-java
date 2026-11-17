package tech.seexun.jknow.web.knowledge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.jobs.DeferredFailure;
import tech.seexun.jknow.common.jobs.TerminalFailure;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.entity.Job;

/**
 * job 框架核心语义（任务 1 验收清单）：claim 竞争（两 worker SKIP LOCKED 不重不漏）/
 * 退避算术（30/120/270 + 耗尽 + Terminal 不烧预算）/ Deferred 不烧预算与窗口 /
 * mark_done 清错 / 事务内 pg_notify（commit 才投递）/ enqueue_with_max_attempts_tx
 * 同事务回滚 / requeue KB_SCOPE 三键 + hydrate_rss_entry 排除。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobsFrameworkTests extends M2TestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    JobsService jobs;

    @Autowired
    PlatformTransactionManager txManager;

    // ---- 退避算术（jobs.rs:583-610 三个纯函数测试逐条） ----

    @Test
    void anOrdinaryFailureBacksOffAndThenGivesUp() {
        // 30s、120s、270s（270 需要 4 次预算：jobs.rs:583-588 Rust 侧测试用 max=3
        // 只断到 120 后直接 None——本任务验收点名 30-120-270，故补一行 max=4 的第三档）
        assertEquals(30L, JobsService.retryDelay(1, 3, false));
        assertEquals(120L, JobsService.retryDelay(2, 3, false));
        assertEquals(270L, JobsService.retryDelay(3, 4, false));
        // 预算 3：第三次认领（attempts=3）就到此为止——Rust 逐字（jobs.rs:587）
        assertNull(JobsService.retryDelay(3, 3, false));
        assertNull(JobsService.retryDelay(4, 3, false));
        // 标成没救的第一次就到此为止（#195：三次退避七分钟，余额不会自己长回来）
        assertNull(JobsService.retryDelay(1, 3, true));
    }

    @Test
    void deferredRetryIsIndependentOfAttemptsAndFloorsAtOneSecond() {
        // 与失败次数无关：同一等待条件两次挂回得到同一偏移（#526）
        assertEquals(JobsService.deferredRetrySecs(Duration.ofSeconds(30)),
                JobsService.deferredRetrySecs(Duration.ofSeconds(30)));
        // 截断到秒、向上取整：29.5s → 30s
        assertEquals(30L, JobsService.deferredRetrySecs(Duration.ofMillis(29_500)));
        // 0/亚秒给 1s 下界——比轮询间隔还短的等待不值得换算
        assertEquals(1L, JobsService.deferredRetrySecs(Duration.ZERO));
        assertEquals(1L, JobsService.deferredRetrySecs(Duration.ofNanos(500)));
    }

    // ---- claim 竞争：两个 worker 并发认领，不重不漏 ----

    @Test
    void twoWorkersClaimDisjointSetsUnderSkipLocked() throws Exception {
        int total = 12;
        for (int i = 0; i < total; i++) {
            jobs.enqueue("race-probe", JSON.createObjectNode());
        }
        Set<Long> all = jdbc.queryForList("SELECT id FROM jobs WHERE kind = 'race-probe'",
                Long.class).stream().collect(Collectors.toSet());
        assertEquals(total, all.size());

        Set<Long> claimedA = ConcurrentHashMap.newKeySet();
        Set<Long> claimedB = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> claimUntilDrained(start, claimedA));
            Future<?> fb = pool.submit(() -> claimUntilDrained(start, claimedB));
            start.countDown();
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 不重：两条认领流不相交（SKIP LOCKED 的存在意义）
        assertTrue(claimedA.stream().noneMatch(claimedB::contains),
                "double claim: A=" + claimedA + " B=" + claimedB);
        // 不漏：全部被认走（running）
        Set<Long> merged = new HashSet<>(claimedA);
        merged.addAll(claimedB);
        assertEquals(all, merged, "some jobs never claimed");
        assertEquals("running", status(merged.iterator().next()));
        assertEquals(1, attempts(merged.iterator().next()));
    }

    private void claimUntilDrained(CountDownLatch start, Set<Long> claimed) {
        try {
            start.await();
            Job job;
            while ((job = jobs.claimOne()) != null) {
                claimed.add(job.id());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 失败写回三分支 ----

    @Test
    void markFailedBacksOffThenFailsAfterBudget() {
        long id = insertQueued("backoff-probe", 3);
        // 第 1 次：30s
        jobs.markFailed(claimById(id), new IllegalStateException("boom-1"));
        assertJob(id, "queued", 1, "boom-1", 30L);
        // 第 2 次：120s
        jobs.markFailed(claimById(id), new IllegalStateException("boom-2"));
        assertJob(id, "queued", 2, "boom-2", 120L);
        // 第 3 次：预算耗尽 → failed（claims=3 时 retryDelay 给 null，不再有 270s 排队）
        jobs.markFailed(claimById(id), new IllegalStateException("boom-3"));
        assertJob(id, "failed", 3, "boom-3", null);
    }

    @Test
    void aTerminalFailureDoesNotSpendTheBudget() {
        long id = insertQueued("terminal-probe", 3);
        jobs.markFailed(claimById(id), new TerminalFailure("balance gone: third retry", null));
        // 一次就到此为止：attempts 停在 1、status=failed，错误链完整可读
        assertJob(id, "failed", 1, "balance gone: third retry: will not recover by retrying", null);
    }

    @Test
    void aDeferredFailureDoesNotBurnAttempts() {
        long id = insertQueued("deferred-probe", 3);
        // 同一等待条件三轮挂回：attempts 每轮退回 0，永不 failed（#526）
        for (int round = 1; round <= 3; round++) {
            Job j = claimById(id);
            assertEquals(1, j.attempts(), "claim 先 +1");
            jobs.markFailed(j, new DeferredFailure("waiting on ontology index",
                    Duration.ofSeconds(30)));
            assertEquals("queued", status(id));
            assertEquals(0, attempts(id), "第 " + round + " 轮挂回后 attempts 应回 0");
            assertNotNull(payloadText(id, "deferred_since"), "挂回时记下第一次等待起点");
        }
        assertEquals("queued", status(id));
        // run_at ≈ now+30s：与本轮失败次数无关的同一偏移
        assertJob(id, "queued", 0, "waiting on ontology index", 30L);
    }

    @Test
    void aDeferredWindowThatExpiredFallsBackToOrdinaryBackoff() {
        long id = insertQueued("deferred-expired-probe", 3);
        Job j = claimById(id);
        jdbc.update("UPDATE jobs SET payload = payload || jsonb_build_object('deferred_since', "
                + "(now() - interval '2 hours')::text) WHERE id = " + id);
        jobs.markFailed(j, new DeferredFailure(Duration.ofSeconds(30)));
        // 窗口（3600s）已过：Deferred 不再接手，按普通失败退避、烧预算
        assertJob(id, "queued", 1, null, 30L);
        assertEquals("deferred; retry in PT30S", lastError(id));
    }

    @Test
    void markDoneClearsTheLastErrorFromARetriedFailure() {
        long id = insertQueued("done-probe", 3);
        jobs.markFailed(claimById(id), new IllegalStateException("transient"));
        jobs.markDone(claimById(id).id());
        assertNull(lastError(id));
        assertEquals("done", status(id));
    }

    // ---- 入队语义：去重族 + 事务内 notify + 同事务回滚 ----

    @Test
    void enqueueFamiliesDeduplicateAsRusted() {
        ObjectNode payload = JSON.createObjectNode().put("kb_id", UUID.randomUUID().toString());
        assertNotNull(jobs.enqueueUnlessPending("dedup-probe", payload, Duration.ofSeconds(5)));
        assertNull(jobs.enqueueUnlessPending("dedup-probe", payload, Duration.ofSeconds(5)),
                "已排着（queued/running 且去抖窗口内）不再排");
        assertNull(jobs.enqueueUnlessQueued("dedup-probe", payload), "已排着不再排");
        // 清掉排队行后：挡的只是 queued，不挡历史（done/failed）
        jdbc.update("DELETE FROM jobs WHERE kind = 'dedup-probe'");
        assertNotNull(jobs.enqueueUnlessQueued("dedup-probe", payload));
        jdbc.update("DELETE FROM jobs WHERE kind = 'dedup-probe'");
        assertNotNull(jobs.enqueueUnlessQueuedAfter("dedup-probe", payload, Duration.ofSeconds(3)));
        Long delta = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (run_at - now()))::bigint FROM jobs WHERE kind = 'dedup-probe'",
                Long.class);
        assertTrue(delta != null && Math.abs(delta - 3) <= 2, "after=3s 去抖窗口，实差 " + delta);
    }

    @Test
    void notifyIsDeliveredOnlyAfterCommit() throws Exception {
        // 独立连接 LISTEN：入队事务提交后才收到唤醒（jobs.rs:189-198 的注释语义）
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.createStatement().execute("LISTEN utopia_jobs");
            jobs.enqueue("notify-probe", JSON.createObjectNode());
            PGConnection pg = conn.unwrap(PGConnection.class);
            PGNotification[] notes = pg.getNotifications(3_000);
            assertNotNull(notes, "commit 后应至少一条通知");
            assertEquals("utopia_jobs", notes[0].getName());
        }
    }

    @Test
    void enqueueWithMaxAttemptsTxRollsBackWithTheEnclosingTransaction() {
        // 同事务：入队成功但外层回滚 → 任务必须消失（jobs.rs:164-166 的存在理由：
        // 不能留下业务行找不到的 hydration 任务）
        TransactionTemplate tx = new TransactionTemplate(txManager);
        long before = countJobs("tx-probe");
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO audit_events (id, action, target_kind, detail) "
                    + "VALUES (?, 'tx-probe', 'test', '{}')", UUID.randomUUID());
            jobs.enqueueWithMaxAttemptsTx("tx-probe", JSON.createObjectNode(), 3);
            assertEquals(before + 1, countJobs("tx-probe"), "事务内可见");
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(before, countJobs("tx-probe"), "外层回滚带走同事务入队");
        // 预算校验（jobs.rs:153-156）
        assertThrows(AppException.class,
                () -> jobs.enqueueWithMaxAttemptsTx("tx-probe", JSON.createObjectNode(), 33));
        jdbc.update("DELETE FROM jobs WHERE kind = 'tx-probe'");
    }

    // ---- requeue / KB_SCOPE / 状态面 ----

    @Test
    void requeueFailedHonorsKbScopeAndSkipsRssHydration() {
        UUID kb = insertKb(insertWorkspace());
        UUID doc = insertDocument(kb);
        UUID src = insertSource(kb);
        UUID otherKb = insertKb(insertWorkspace());

        long inKbDoc = insertFailedJob("fixable", JSON.createObjectNode()
                .put("document_id", doc.toString()));
        long inKbSrc = insertFailedJob("fixable", JSON.createObjectNode()
                .put("source_id", src.toString()));
        long inKbDirect = insertFailedJob("fixable", JSON.createObjectNode()
                .put("kb_id", kb.toString()));
        long hydrate = insertFailedJob("hydrate_rss_entry", JSON.createObjectNode()
                .put("kb_id", kb.toString()));
        long outOfKb = insertFailedJob("fixable", JSON.createObjectNode()
                .put("kb_id", otherKb.toString()));

        // 库内重排：三键各自解到库（kb_id 直达 / document_id / source_id 子查询），
        // hydrate_rss_entry 排除、库外排除
        assertEquals(3, jobs.requeueFailed(new JobsService.RequeueScope(kb, null, null)));
        assertEquals("queued", status(inKbDoc));
        assertEquals("queued", status(inKbSrc));
        assertEquals("queued", status(inKbDirect));
        assertEquals("failed", status(hydrate), "RSS hydration 的重试归来源准入策略（任务 5）");
        assertEquals("failed", status(outOfKb));
        assertEquals(0, attempts(inKbDoc), "重排即余额回满");
        assertNull(jobs.statusInKb(inKbDirect, otherKb), "库外视 null（路由 404 口径）");
        assertEquals("queued", jobs.statusInKb(inKbDirect, kb).status());
        assertEquals("fixable", jobs.statusInKb(inKbDoc, kb).kind());
        assertTrue(jobs.statusInKb(inKbDoc, kb).lastError().contains("spent"));
        // kind 收窄：只排这一种
        jdbc.update("UPDATE jobs SET status = 'failed' WHERE id = " + inKbSrc);
        assertEquals(1, jobs.requeueFailed(new JobsService.RequeueScope(kb, "fixable", null)));
        jdbc.update("UPDATE jobs SET status = 'failed' WHERE id = " + inKbSrc);
        // 其他 kind 收窄不命中
        assertEquals(0, jobs.requeueFailed(new JobsService.RequeueScope(kb, "no-such-kind", null)));
        assertEquals("failed", status(inKbSrc));
        // failed_count 圈库：此刻库内 failed 只剩 hydrate 与 inKbSrc
        assertEquals(2, jobs.failedCount(kb));
        // 全局（不限库）：非 hydrate 的 failed 全排（含其他 fixture 的历史行——按同 SQL 计数）
        Integer expectGlobal = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status = 'failed' AND kind <> 'hydrate_rss_entry'",
                Integer.class);
        assertEquals(expectGlobal, jobs.requeueFailed(new JobsService.RequeueScope(null, null, null)));
    }

    // ---- helpers ----

    private Job claimById(long id) {
        forceDue(id);
        Job job = jobs.claimOne();
        // 同库有其他到期行时可能先认走它们：标记完成继续认，直到认到目标行
        int guard = 0;
        while (job == null || job.id() != id) {
            if (job != null) {
                jobs.markDone(job.id());
            }
            job = jobs.claimOne();
            if (++guard > 100) {
                throw new IllegalStateException("claim 不到目标行 " + id);
            }
        }
        return job;
    }

    private void forceDue(long id) {
        jdbc.update("UPDATE jobs SET run_at = now() WHERE id = " + id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = " + id, String.class);
    }

    private int attempts(long id) {
        return jdbc.queryForObject("SELECT attempts FROM jobs WHERE id = " + id, Integer.class);
    }

    private String lastError(long id) {
        return jdbc.queryForObject("SELECT last_error FROM jobs WHERE id = " + id, String.class);
    }

    private String payloadText(long id, String key) {
        return jdbc.queryForObject(
                "SELECT payload->>'" + key + "' FROM jobs WHERE id = " + id, String.class);
    }

    private void assertJob(long id, String status, int attempts, String errorContains,
                           Long backoffSecsOrNull) {
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts, last_error, "
                + "EXTRACT(EPOCH FROM (run_at - now()))::bigint AS delta FROM jobs WHERE id = " + id);
        assertEquals(status, row.get("status"));
        assertEquals(attempts, ((Number) row.get("attempts")).intValue());
        if (errorContains != null) {
            assertTrue(String.valueOf(row.get("last_error")).contains(errorContains),
                    "last_error 应含 " + errorContains + "，实为 " + row.get("last_error"));
        }
        if (backoffSecsOrNull != null) {
            long delta = ((Number) row.get("delta")).longValue();
            assertTrue(Math.abs(delta - backoffSecsOrNull) <= 3,
                    "run_at 应约 now()+" + backoffSecsOrNull + "s，实差 " + delta + "s");
        }
    }

    private long countJobs(String kind) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE kind = ?", Long.class, kind);
        return n == null ? 0 : n;
    }

    private long insertQueued(String kind, int maxAttempts) {
        return jdbc.queryForObject(
                "INSERT INTO jobs (kind, payload, max_attempts) VALUES (?, '{}', ?) RETURNING id",
                Long.class, kind, maxAttempts);
    }

    private long insertFailedJob(String kind, ObjectNode payload) {
        return jdbc.queryForObject(
                "INSERT INTO jobs (kind, payload, status, attempts, max_attempts, last_error) "
                        + "VALUES (?, ?::jsonb, 'failed', 3, 3, 'spent') RETURNING id",
                Long.class, kind, payload.toString());
    }

    private UUID insertWorkspace() {
        UUID ws = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'm2-jobs-ws')",
                ws, firstOrg());
        return ws;
    }

    private UUID insertKb(UUID ws) {
        UUID kb = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'm2-jobs-kb')",
                kb, ws);
        return kb;
    }

    private UUID insertDocument(UUID kb) {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, mime, size_bytes, sha256, status) "
                + "VALUES (?, ?, 'm2.txt', 'text/plain', 1, ?, 'ready')", doc, kb,
                UUID.randomUUID().toString());
        return doc;
    }

    private UUID insertSource(UUID kb) {
        UUID src = UUID.randomUUID();
        jdbc.update("INSERT INTO sources (id, kb_id, kind, name, config) "
                + "VALUES (?, ?, 'folder', 'm2-src', '{}')", src, kb);
        return src;
    }

    private UUID firstOrg() {
        List<UUID> orgs = jdbc.queryForList(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        if (orgs.isEmpty()) {
            UUID org = UUID.randomUUID();
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'm2-jobs-org')", org);
            return org;
        }
        return orgs.get(0);
    }
}
