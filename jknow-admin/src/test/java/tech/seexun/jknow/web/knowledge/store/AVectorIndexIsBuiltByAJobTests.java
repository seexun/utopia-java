package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.VectorIndexService.Target;

/**
 * 移植自顶层 tests/a_vector_index_is_built_by_a_job.rs（3 例）——索引由任务建（0035）：
 * 写入一侧只负责「排一条」，且排一次就够；删索引与建索引同锁（分开的并发索引操作在
 * 同一张表上互相死锁）；两个维度同时建 + 旁边有摄取事务时靠会话级咨询锁排队（不加锁
 * 一轮约一半概率 40P01）。
 *
 * <p>Rust 侧这三例留在 tests/ 顶层独立二进制（碰 chunks 全表状态，不进合并测试进程）；
 * Java 侧 surefire 类内串行、类间不并行，同一档隔离。<b>共享栈纪律</b>：活着的 Rust
 * worker（1516）会认领立即到期的 build_vector_index——request 与「park 到 +1h」放同一
 * 事务（M2 claimAndPark 纪律），提交前 worker 看不见、提交后已不可认领，窗口不存在。
 */
class AVectorIndexIsBuiltByAJobTests extends HeartStoreTestBase {

    /** 这个测试独占的维度。 */
    private static final int DIMS = 9;

    @Autowired
    VectorIndexService vectorIndexes;
    @Autowired
    PlatformTransactionManager txManager;

    private long queued(int dims) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE kind = ? AND payload = ?::jsonb"
                        + " AND status = 'queued'",
                Long.class, VectorIndexService.JOB_KIND,
                "{\"table\": \"chunks\", \"dims\": " + dims + "}");
        return n == null ? 0 : n;
    }

    /** request + park 同一事务：job 行随 park 一起提交，worker 无窗口可认领。 */
    private Long requestParked(Target target, int dims) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long id = tx.execute(status -> {
            Long inserted = vectorIndexes.request(target, dims);
            jdbc.update("UPDATE jobs SET run_at = now() + interval '1 hour'"
                    + " WHERE kind = ? AND payload->>'dims' = ?",
                    VectorIndexService.JOB_KIND, Integer.toString(dims));
            return inserted;
        });
        return id;
    }

    /** dropping_an_index_waits_for_a_build：删与建同锁——分开的并发索引操作互相死锁。 */
    @Test
    void droppingAnIndexWaitsForABuild() throws Exception {
        try (Connection holder = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            String lock = "SELECT pg_try_advisory_lock(hashtextextended('vector_index:build', 0))";
            while (true) {
                try (PreparedStatement ps = holder.prepareStatement(lock);
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        break;
                    }
                }
                Thread.sleep(10);
            }
            try {
                // An absent index makes DROP CONCURRENTLY quick unless it takes the build lock.
                ExecutorService pool = Executors.newSingleThreadExecutor();
                Future<?> drop;
                try {
                    drop = pool.submit(() -> {
                        try {
                            vectorIndexes.drop(Target.CHUNKS, 17);
                            return null;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    boolean blocked = false;
                    try {
                        drop.get(100, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        blocked = true;
                    }
                    boolean unlocked;
                    try (PreparedStatement ps = holder.prepareStatement(
                            "SELECT pg_advisory_unlock(hashtextextended('vector_index:build', 0))");
                         ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        unlocked = rs.getBoolean(1);
                    }
                    assertTrue(unlocked);
                    assertTrue(blocked, "DROP must wait for an in-progress index build");
                    drop.get(30, TimeUnit.SECONDS); // 锁已放：先头那份补完
                } finally {
                    pool.shutdownNow();
                }
                vectorIndexes.drop(Target.CHUNKS, 17);
            } finally {
                // holder 的锁已在上文显式解开；连接关闭时残留会话锁自动释放，双保险不取
            }
        }
    }

    /** the_first_write_of_a_dimension_queues_one_build：排一次就够/建好不排/超限 Terminal。 */
    @Test
    void theFirstWriteOfADimensionQueuesOneBuild() throws Exception {
        // 上一次跑留下的都清掉，从「什么都没有」开始
        vectorIndexes.drop(Target.CHUNKS, DIMS);
        jdbc.update("DELETE FROM jobs WHERE kind = ? AND payload->>'dims' = ?",
                VectorIndexService.JOB_KIND, Integer.toString(DIMS));
        try {
            Long first = requestParked(Target.CHUNKS, DIMS);
            assertNotNull(first, "第一次写下 9 维：排上一条");
            assertEquals(1L, queued(DIMS));
            Long second = vectorIndexes.request(Target.CHUNKS, DIMS);
            assertNull(second, "队列里已经有一条，不再排");
            assertEquals(1L, queued(DIMS));

            VectorIndexService.Built built = vectorIndexes.build(Target.CHUNKS, DIMS);
            assertTrue(built.created());
            assertEquals(Boolean.TRUE, vectorIndexes.status(Target.CHUNKS, DIMS));
            // 任务跑完那条会被标 done；这里模拟一下，好让下一问只靠「进程里记住了」
            jdbc.update("UPDATE jobs SET status = 'done' WHERE kind = ? AND payload->>'dims' = ?",
                    VectorIndexService.JOB_KIND, Integer.toString(DIMS));
            Long third = vectorIndexes.request(Target.CHUNKS, DIMS);
            assertNull(third, "建好了：什么都不排");
            assertEquals(0L, queued(DIMS));

            // 超过上限的维度
            int tooWide = VectorIndexService.MAX_DIMS + 1;
            assertNull(vectorIndexes.request(Target.CHUNKS, tooWide), "建不了的不排");
            assertEquals(0L, queued(tooWide));
            AppException err = assertThrows(AppException.class,
                    () -> vectorIndexes.build(Target.CHUNKS, tooWide),
                    "HNSW on vector holds up to 2000 dims");
            assertEquals(AppException.Kind.VALIDATION, err.kind(),
                    "是 Validation，分发层据此判 Terminal: " + err.getMessage());
        } finally {
            try {
                vectorIndexes.drop(Target.CHUNKS, DIMS);
            } catch (Exception ignored) {
                // teardown 不放大失败（Rust let _ = drop 同位）
            }
            jdbc.update("DELETE FROM jobs WHERE kind = ? AND payload->>'dims' = ?",
                    VectorIndexService.JOB_KIND, Integer.toString(DIMS));
        }
    }

    /**
     * two_dimensions_built_at_once_take_turns：两个维度同时建，旁边还有事务在碰 chunks
     * （tokio::join! → 线程池）。两条 CREATE INDEX CONCURRENTLY 各自要等表上其他事务
     * 结束、也各自算对方要等的事务，Postgres 报 deadlock detected；build 里的会话级
     * 咨询锁让后到的那条等前一条建完。连做八轮，不加锁时几乎必红。
     */
    @Test
    void twoDimensionsBuiltAtOnceTakeTurns() throws Exception {
        final int a = 11;
        final int b = 13;
        ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "vector-build-race");
            t.setDaemon(true);
            return t;
        });
        Exception outcome = null;
        try {
            for (int round = 0; round < 8 && outcome == null; round++) {
                for (int dims : new int[] {a, b}) {
                    vectorIndexes.drop(Target.CHUNKS, dims);
                }
                // 摄取还在往 chunks 写：CIC 要等这些事务，死锁就靠它凑齐
                Future<?> writer = pool.submit(() -> {
                    try (Connection conn = java.sql.DriverManager.getConnection(
                            DB_URL, DB_USER, DB_PASS)) {
                        for (int i = 0; i < 20; i++) {
                            conn.setAutoCommit(false);
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "SELECT count(*) FROM chunks");
                                 ResultSet rs = ps.executeQuery()) {
                                rs.next();
                            }
                            Thread.sleep(5);
                            conn.commit();
                            conn.setAutoCommit(true);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                Future<VectorIndexService.Built> fa = pool.submit(
                        () -> vectorIndexes.build(Target.CHUNKS, a));
                Future<VectorIndexService.Built> fb = pool.submit(
                        () -> vectorIndexes.build(Target.CHUNKS, b));
                writer.get(60, TimeUnit.SECONDS);
                try {
                    VectorIndexService.Built ra = fa.get(120, TimeUnit.SECONDS);
                    VectorIndexService.Built rb = fb.get(120, TimeUnit.SECONDS);
                    assertTrue(ra.created() && rb.created(),
                            "第 " + round + " 轮两条都建成: " + ra + " " + rb);
                } catch (Exception e) {
                    outcome = e;
                }
            }
        } finally {
            pool.shutdownNow();
            for (int dims : new int[] {a, b}) {
                try {
                    vectorIndexes.drop(Target.CHUNKS, dims);
                } catch (Exception ignored) {
                    // teardown 不放大失败
                }
            }
        }
        assertNull(outcome, "八轮里有一轮炸了");
    }
}
