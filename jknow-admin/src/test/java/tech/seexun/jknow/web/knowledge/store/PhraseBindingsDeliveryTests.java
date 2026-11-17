package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 移植自 crates/utopia-store/src/phrase_bindings_delivery_tests.rs（232 行，4 例）+
 * phrase_bindings.rs 内联 mod tests（normalize 1 例）——人的判定与它的重算任务是一次提交
 * （0051）。四件事：判定和 job 一起落库、载荷指着这个库；排队失败时判定也没了；别人握着
 * 物化锁时 {@code try_materialize} 立刻让开而不是等；两次判定各自的 job 不管先后处理，
 * 类型化图都收敛到最后一次判定。
 */
class PhraseBindingsDeliveryTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    JobsService jobs;

    // ---- phrase_bindings.rs:503-509 内联：一个短语不管怎么写都是一个短语 ----

    @Test
    void aPhraseIsOnePhraseHoweverSpacedOrCased() {
        assertEquals("was designed by", PhraseBindingsService.normalize("  Was   Designed By "));
        assertEquals("revenue", PhraseBindingsService.normalize("Revenue"));
        assertEquals("细化解读", PhraseBindingsService.normalize("细化解读"));
        assertEquals("", PhraseBindingsService.normalize("   "));
    }

    record Fx(UUID org, UUID kb, UUID property, PhraseSignature sig) {
    }

    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID object = Uuids.nowV7();
        UUID property = Uuids.nowV7();
        UUID statement = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'phrase-delivery')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'phrase-delivery')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'phrase-delivery')",
                kb, ws);
        for (Object[] row : new Object[][]{{subject, "Acme"}, {object, "London"}}) {
            jdbc.update("INSERT INTO entities(id,kb_id,canonical_name) VALUES (?, ?, ?)",
                    row[0], kb, row[1]);
        }
        jdbc.update(
                "INSERT INTO relation_types(id,kb_id,key,label,temporal) VALUES (?, ?, 'based_in', 'based in', 'state')",
                property, kb);
        jdbc.update(
                "INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase) VALUES (?, ?, ?, ?, 'open', 'based in')",
                statement, kb, subject, object);
        PhraseSignature sig = phraseBindings.signatures(kb).get(0);
        return new Fx(org, kb, property, sig);
    }

    private void cleanup(Fx f) {
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb().toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /**
     * 双打环境的确定性闸门：共享栈上活着的 Rust worker（docker 网段）会真处理
     * materialize_typed 任务（job_id 会离开 queued、轮到它物化）。测试里拿会话级
     * typed_materialize 锁挡住它——Rust 的 try_materialize 试锁不得 → Deferred 10s
     * 挂回 queued（0051 语义跨实现成立）。用完 rollback 释放。
     */
    private Connection workerGate(UUID kb) throws Exception {
        Connection gate = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        gate.setAutoCommit(false);
        try (var ps = gate.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('typed_materialize'), hashtext(?))")) {
            ps.setString(1, kb.toString());
            ps.executeQuery();
        }
        return gate;
    }

    /** 等 Rust worker 把 Deferred/退避的任务挂回 queued（共享栈双打的等待语义）。 */
    private void awaitQueued(long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
            if ("queued".equals(status)) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals("queued", status);
    }

    /**
     * 把任务推到一小时之后（Rust claim 只认 run_at 到期的）——闸门释放后共享栈的
     * Rust worker 也不会再碰它，测试自己的断言/直调就有了独占窗口；cleanup 收走。
     */
    private void parkJob(long jobId) {
        jdbc.update("UPDATE jobs SET run_at = now() + interval '1 hour' WHERE id = ?", jobId);
    }

    /**
     * 彻底静默：等到任务回到 queued 且 run_at 已在一小时之后（Rust 的退避写回会覆盖
     * park，看到就再 park），之后 worker 再也拿不到它。
     */
    private void quiesceJob(long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var row = jdbc.queryForMap(
                    "SELECT status, run_at > now() + interval '50 minutes' AS parked FROM jobs WHERE id = ?",
                    jobId);
            if ("queued".equals(row.get("status")) && Boolean.TRUE.equals(row.get("parked"))) {
                return;
            }
            if ("queued".equals(row.get("status"))) {
                parkJob(jobId);
            }
            Thread.sleep(25);
        }
        assertEquals("queued", jdbc.queryForObject(
                "SELECT status FROM jobs WHERE id = ?", String.class, jobId));
    }

    private PhraseBindingsService.Decision bound(UUID property) {
        return new PhraseBindingsService.Decision(property, "forward", "bound",
                JsonNodeFactory.instance.nullNode(), "person", null, null, false);
    }

    private PhraseBindingsService.Decision none() {
        return new PhraseBindingsService.Decision(null, null, "none",
                JsonNodeFactory.instance.nullNode(), "person", null, null, false);
    }

    private List<Object[]> jobsFor(UUID kb) {
        return jdbc.query("SELECT id, kind, status FROM jobs WHERE payload->>'kb_id' = ? ORDER BY id",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)},
                kb.toString());
    }

    @Test
    void aDecisionAndItsJobCommitTogether() throws Exception {
        Fx f = seed();
        try {
            Long id;
            try (Connection gate = workerGate(f.kb())) {
                id = phraseBindings.decideWithDelivery(f.kb(), f.sig(), bound(f.property()));
                assertNotNull(id, "a person's decision is always written");
                quiesceJob(id);
                List<Object[]> jobs = jobsFor(f.kb());
                assertEquals(1, jobs.size());
                assertEquals(id, jobs.get(0)[0]);
                assertEquals(PhraseBindingsService.MATERIALIZE_KIND, jobs.get(0)[1]);
                assertEquals("queued", jobs.get(0)[2]);
                var bindings = phraseBindings.bindings(f.kb());
                assertEquals(1, bindings.size());
                assertEquals("bound", bindings.get(0).status());
                // 载荷只带库：job 读当前绑定，不回放这次判定的属性
                JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        jdbc.queryForObject("SELECT payload::text FROM jobs WHERE id = ?",
                                String.class, id));
                assertEquals(f.kb().toString(), payload.get("kb_id").asText());
                // Rust 的 #526 Deferred 路径会往 payload 里补 deferred_since——那是共享栈
                // worker 试锁不得的痕迹，不是这次入队带的；剥掉后断言「载荷只带库」
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("deferred_since");
                assertEquals(1, payload.size());
            }
        } finally {
            cleanup(f);
        }
    }

    @Test
    void anEnqueueFailureTakesTheDecisionWithIt() {
        Fx f = seed();
        try {
            // 预算 0 被 enqueue 拒掉：一次发生在判定写入之后的真实失败
            assertThrows(Exception.class, () -> phraseBindings.decideWithDeliveryBudget(f.kb(),
                    f.sig(), bound(f.property()), 0));
            assertTrue(phraseBindings.bindings(f.kb()).isEmpty());
            assertTrue(jobsFor(f.kb()).isEmpty());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void aBusyProjectionIsDeclinedNotWaitedFor() throws Exception {
        Fx f = seed();
        try {
            // 闸门先于判定：共享栈的 Rust worker 拿不到锁，它那份 job 只能 Deferred 挂回
            // （run_at=+10s，cleanup 先收走）；这条测试自己的两条 try 才是锁的竞争者
            long enqueued;
            try (Connection gate = workerGate(f.kb())) {
                enqueued = phraseBindings.decideWithDelivery(f.kb(), f.sig(), bound(f.property()));
                // 另一条连接抱着锁：try 版本立刻回 null，不占第二条连接排队
                long started = System.nanoTime();
                assertNull(materialize.tryMaterialize(f.kb()));
                assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
                quiesceJob(enqueued);
                gate.rollback();
            }
            Outcome outcome = materialize.tryMaterialize(f.kb());
            assertNotNull(outcome, "lock released");
            assertEquals(1L, outcome.added());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void jobsProcessedInAnyOrderConvergeOnTheLastDecision() throws Exception {
        Fx f = seed();
        try {
            Long first;
            Long second;
            try (Connection gate = workerGate(f.kb())) {
                first = phraseBindings.decideWithDelivery(f.kb(), f.sig(), bound(f.property()));
                quiesceJob(first);
                second = phraseBindings.decideWithDelivery(f.kb(), f.sig(), none());
                quiesceJob(second);
            }
            assertTrue(first < second);
            // 两个 job 都排着；先处理后来的，再处理先来的——每次都读当前绑定。
            // 双打环境：把两份 job 推到一小时后（共享栈 Rust worker 不得代跑），
            // 下面自己的 tryMaterialize 才是这里唯一的物化者
            assertEquals(2, jobsFor(f.kb()).size());
            materialize.tryMaterialize(f.kb());
            materialize.tryMaterialize(f.kb());
            assertEquals(0L, materialize.count(f.kb()), "the last decision was none");
            // 反过来：先绑、再解绑、先处理老的
            try (Connection gate = workerGate(f.kb())) {
                long again = phraseBindings.decideWithDelivery(f.kb(), f.sig(), bound(f.property()));
                quiesceJob(again);
            }
            materialize.tryMaterialize(f.kb());
            assertEquals(1L, materialize.count(f.kb()));
        } finally {
            cleanup(f);
        }
    }
}
