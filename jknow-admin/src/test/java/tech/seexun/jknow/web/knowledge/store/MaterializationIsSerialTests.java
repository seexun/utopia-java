package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 移植自 tests/store/materialization_is_serial.rs（149 行）——两个物化器不许从同一条陈述
 * 算出两条类型化行。
 *
 * <p>等价点（tokio→JUnit）：Rust 用 max_connections(2)/(1) 的专用池约束连接预算并观察
 * pg_blocking_pids；Java 侧 TypedMaterializeService 一次重算就是一条 Spring 事务（同
 * Rust 的单 tx），两路并发经线程池触发，门/观察用一条独立 JDBC 连接（DriverManager）——
 * 不占 worker 的连接预算，与 Rust 的 control 池同一角色。「单连接也够」的子断言由结构
 * 保证：重算 tx 内不走 REQUIRES_NEW（闭合分支走 InTx 变体），对账在提交之后才各自开
 * 事务；此处按原断言在共享池上重放收尾三断言。
 */
class MaterializationIsSerialTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    PhraseBindingsService phraseBindings;

    private long blockedDescendants(Connection control, int blocker) throws Exception {
        try (var ps = control.prepareStatement("""
                WITH RECURSIVE blocked(pid) AS (
                    SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))
                    UNION SELECT p.pid FROM pg_stat_activity p JOIN blocked b ON b.pid=ANY(pg_blocking_pids(p.pid))
                ) SELECT count(*) FROM blocked
                """)) {
            ps.setInt(1, blocker);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void waitFor(Connection control, int blocker, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (blockedDescendants(control, blocker) < count) {
            assertTrue(System.currentTimeMillis() < deadline, "blocked descendants never reached " + count);
            Thread.sleep(20);
        }
    }

    @Test
    void overlappingMaterializationsCreateOneTypedFact() throws Exception {
        try (Connection control = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            control.setAutoCommit(true);
            UUID org = Uuids.nowV7();
            UUID ws = Uuids.nowV7();
            UUID kb = Uuids.nowV7();
            UUID subject = Uuids.nowV7();
            UUID object = Uuids.nowV7();
            UUID property = Uuids.nowV7();
            UUID statement = Uuids.nowV7();
            jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'materialize-race')", org);
            jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'materialize-race')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'materialize-race')",
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
            PhraseSignature signature = phraseBindings.signatures(kb).get(0);
            assertTrue(phraseBindings.decide(kb, signature, new PhraseBindingsService.Decision(
                    property, "forward", "bound",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    "agent", null, null, false)));
            String name = "materialize_gate_" + kb.toString().replace("-", "");
            try (var st = control.createStatement()) {
                st.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN\n"
                        + "        PERFORM pg_advisory_xact_lock(hashtext('materialize-test'),hashtext(TG_ARGV[0])); RETURN NEW;\n"
                        + "        END $$");
                st.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON facts FOR EACH ROW\n"
                        + "        WHEN (NEW.kb_id='" + kb + "'::uuid AND NEW.layer='typed') EXECUTE FUNCTION "
                        + name + "('" + kb + "')");
            }
            try {
                control.setAutoCommit(false);
                int blocker;
                try (var ps = control.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtext('materialize-test'),hashtext(?))");
                     var pid = control.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                    pid.next();
                    blocker = pid.getInt(1);
                    ps.setString(1, kb.toString());
                    ps.executeQuery();
                }
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    AtomicReference<Outcome> a = new AtomicReference<>();
                    Future<?> fa = pool.submit(() -> a.set(materialize.materialize(kb)));
                    waitFor(control, blocker, 1);
                    AtomicReference<Outcome> b = new AtomicReference<>();
                    Future<?> fb = pool.submit(() -> b.set(materialize.materialize(kb)));
                    // 旧代码：两个插入触发器都等在闸门上。修好的代码：第二个物化器等在
                    // 第一个上，第一个等在闸门上
                    waitFor(control, blocker, 2);
                    control.commit();
                    fa.get(20, TimeUnit.SECONDS);
                    fb.get(20, TimeUnit.SECONDS);
                    long live = materialize.count(kb);
                    long sources = jdbc.queryForObject(
                            "SELECT count(*) FROM typed_fact_sources WHERE statement_id = ?",
                            Long.class, statement);
                    assertEquals(1L, live, "overlapping runs produced " + live + " live typed facts; expected one");
                    assertEquals(1L, sources, "the statement has " + sources + " typed projections; expected one");
                    // 后来的陈述关上已有的行——嵌套的时态改写与投影同一事务收尾
                    jdbc.update("""
                            INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase,valid_to,valid_to_precision)
                             VALUES (?,?,?,?, 'open','based in',?,'day')
                            """, Uuids.nowV7(), kb, subject, object,
                            OffsetDateTime.parse("2020-01-01T00:00:00Z"));
                    Outcome closed = materialize.materialize(kb);
                    assertEquals(1L, closed.added(), "the closing statement should create one corrected fact");
                    assertEquals(1L, materialize.count(kb));
                    Boolean ended = jdbc.queryForObject(
                            "SELECT valid_to=?::timestamptz FROM facts WHERE kb_id=? AND layer='typed' AND invalidated_at IS NULL",
                            Boolean.class, "2020-01-01T00:00:00Z", kb);
                    assertTrue(ended, "the temporal rewrite must commit with the projection");
                    assertEquals(Outcome.EMPTY, materialize.materialize(kb));
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                control.setAutoCommit(true);
                try (var st = control.createStatement()) {
                    st.execute("DROP TRIGGER " + name + " ON facts");
                    st.execute("DROP FUNCTION " + name + "()");
                }
                jdbc.update("DELETE FROM organizations WHERE id = ?", org);
            }
        }
    }
}
