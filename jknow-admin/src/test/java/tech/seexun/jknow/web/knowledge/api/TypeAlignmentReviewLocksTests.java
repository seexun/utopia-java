package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper;

/**
 * type-alignment 审核锁面重建（P1 任务 8；源 TypeAlignmentReviewLocksTests 核心语义——
 * MockMvc+LoginUser 重建）：binding_lock/entity_lock 竞争下 409 alignment_busy 且
 * 半途写入全回滚、门释放后重试成功、迟到的 agent 判定盖不了人的决定、
 * 401/422/404/403 各错位不串味、agent 的会话等待策略（无 2s 超时）。
 * 行锁门 = 手开事务持 FOR UPDATE（Gate）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypeAlignmentReviewLocksTests extends ApiTestBase {

    @Autowired
    TypeBindingsService typeBindings;

    @Autowired
    TypeBindingsMapper typeBindingsMapper;

    @Autowired
    DataSource dataSource;

    private UUID org;
    private UUID kb;
    private UUID clazz;
    private UUID entity;
    private UUID user;

    /** Rust Fx::new + seed_bound 的装置：org/ws/kb/'organization' 类/Acme(company) + 已绑定的词。 */
    private void fixture(boolean seedBound) throws Exception {
        org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        kb = UUID.randomUUID();
        clazz = UUID.randomUUID();
        entity = UUID.randomUUID();
        user = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'alignment-audit')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'alignment-audit')",
                ws, org);
        // materialize_inferences 关掉：避开共享栈调度器对新库的 materialize 排队（快照对账）
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, materialize_inferences) "
                        + "VALUES (?, ?, 'alignment-audit', FALSE)",
                kb, ws);
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Lock Test')", user, org,
                user + "@example.test");
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role) VALUES (?, ?, 'editor')", kb, user);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, description) "
                        + "VALUES (?, ?, 'organization', 'Organization', 'OLD definition')", clazz, kb);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, specific_type) "
                        + "VALUES (?, ?, 'Acme', 'company')", entity, kb);
        if (seedBound) {
            typeBindings.decide(kb, "company", List.of(), clazz, "bound", mapper.createObjectNode(),
                    "agent");
            typeBindings.applyToEntities(kb, "company", clazz);
            jdbc.update("UPDATE type_bindings SET decided_at = '2000-01-01' WHERE kb_id = ?", kb);
        }
    }

    @BeforeEach
    void reset() {
        org = null;
    }

    @AfterEach
    void teardown() throws SQLException {
        if (org == null) {
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM jobs WHERE payload->>'kb_id' = '" + kb + "'");
        }
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        org = null;
    }

    private Resp request(UUID kbId, UUID as, String classOrNull) throws Exception {
        String body = "{\"class\":" + (classOrNull == null ? "null" : "\"" + classOrNull + "\"") + "}";
        return post("/api/v1/kbs/" + kbId + "/review/alignment/kind-words/company", as, body);
    }

    /** Rust snapshot()：绑定行 + 实体集 + 本库 job 数 + kind_word_decided 台账数。 */
    private JsonNode snapshot() throws Exception {
        String binding = jdbc.query(
                "SELECT coalesce(to_jsonb(b)::text, 'null') FROM type_bindings b WHERE kb_id = ?",
                (rs, n) -> rs.getString(1), kb).stream().findFirst().orElse("null");
        String entities = jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY id)::text, 'null') "
                        + "FROM entities e WHERE kb_id = ?", String.class, kb);
        long jobs = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE payload->>'kb_id' = ?", Long.class, kb.toString());
        long audit = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE kb_id = ? "
                        + "AND action = 'alignment.kind_word_decided'", Long.class, kb);
        return mapper.readTree("{\"binding\":" + binding + ",\"entities\":" + entities
                + ",\"jobs\":" + jobs + ",\"audit\":" + audit + "}");
    }

    /** 行锁门：一条手开的事务持 FOR UPDATE，直到 rollback。 */
    private static final class Gate implements AutoCloseable {
        final Connection conn;

        Gate(DataSource ds, String sql) throws SQLException {
            conn = ds.getConnection();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery(sql)) {
                    assertTrue(rs.next(), "gate row exists");
                }
            }
        }

        void rollback() {
            try {
                conn.rollback();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            rollback();
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 连接已死
            }
        }
    }

    /** 三条共用主线的参数化（binding_lock / entity_lock × rebind / unapply）。 */
    private void contention(boolean bindingLock, boolean unapply) throws Exception {
        fixture(true);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'other', 'Other')",
                other, kb);
        UUID human = UUID.randomUUID();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, specific_type, type_id, type_source) "
                        + "VALUES (?, ?, 'Human choice', 'company', ?, 'human')", human, kb, clazz);
        JsonNode before = snapshot();
        String gateSql = bindingLock
                ? "SELECT id FROM type_bindings WHERE kb_id = '" + kb + "' FOR UPDATE"
                : "SELECT id FROM entities WHERE id = '" + entity + "' FOR UPDATE";
        try (Gate gate = new Gate(dataSource, gateSql)) {
            String classOrNull = unapply ? null : "other";
            Resp resp = request(kb, user, classOrNull);
            assertEquals(409, resp.status(), "body=" + resp.raw());
            assertEquals("alignment_busy", resp.body().get("code").asText());
            assertEquals(before, snapshot(), "timeout left a partial write");
        }
        // 门还了之后重试成功
        Resp ok = request(kb, user, unapply ? null : "other");
        assertEquals(200, ok.status(), "body=" + ok.raw());
        JsonNode after = snapshot();
        assertEquals("person", after.get("binding").get("decided_by").asText());
        assertEquals(unapply ? "none" : "bound", after.get("binding").get("status").asText());
        assertEquals(1, after.get("jobs").asLong());
        assertEquals(1, after.get("audit").asLong());
        UUID projected = jdbc.queryForObject("SELECT type_id FROM entities WHERE id = ?",
                UUID.class, entity);
        assertEquals(unapply ? null : other, projected);
        UUID preserved = jdbc.queryForObject("SELECT type_id FROM entities WHERE id = ?",
                UUID.class, human);
        assertEquals(clazz, preserved, "human decision preserved");
        // 迟到的 agent 判定盖不了人刚提交的决定
        assertFalse(typeBindings.decideAndApply(kb, "company", List.of(), clazz, "bound",
                mapper.createObjectNode(), "agent"), "older agent overwrote human");
        assertEquals(after, snapshot(), "older agent overwrote human");
    }

    @Test
    void bindingLockReturnsConflictThenRetries() throws Exception {
        contention(true, false);
    }

    @Test
    void projectionLockRollsBackTheBinding() throws Exception {
        contention(false, false);
    }

    @Test
    void unapplyLockRollsBackTheBinding() throws Exception {
        contention(false, true);
    }

    @Test
    void unrelatedErrorsAndPermissionsKeepTheirMeaning() throws Exception {
        fixture(true);
        JsonNode before = snapshot();
        assertEquals(401, request(kb, null, null).code(), "无凭据：若依链拒（体 code 401）");
        assertEquals(422, request(kb, user, "missing").status());
        assertEquals(404, request(UUID.randomUUID(), user, null).status());
        UUID otherKb = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility) "
                        + "SELECT ?, workspace_id, 'Other base', 'restricted' FROM knowledge_bases WHERE id = ?",
                otherKb, kb);
        assertEquals(404, request(otherKb, user, null).status());
        jdbc.update("UPDATE kb_members SET role = 'viewer' WHERE kb_id = ?", kb);
        assertEquals(403, request(kb, user, null).status());
        // 不存在的类：FK 23503 从 store 直通（不是 409/500 包装的别的形状）
        DataAccessException e = assertThrows(DataAccessException.class,
                () -> typeBindings.decideAndApplyHuman(kb, "company", UUID.randomUUID(),
                        mapper.createObjectNode()));
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        assertEquals("23503", ((SQLException) t).getSQLState());
        assertEquals(before, snapshot());
    }

    /** agent 的会话等待策略与人不同：没有 2s 的 lock_timeout，锁着就一直等。 */
    @Test
    void agentKeepsItsSessionWaitPolicy() throws Exception {
        fixture(false);
        try (Gate gate = new Gate(dataSource,
                "SELECT id FROM entities WHERE id = '" + entity + "' FOR UPDATE")) {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> agent = pool.submit(() -> typeBindings.decideAndApply(kb,
                        "company", List.of(), clazz, "bound", mapper.createObjectNode(), "agent"));
                // 2.3s 还没完 = 没吃到人的 2s 超时（仍在等行锁）
                Thread.sleep(2300);
                assertFalse(agent.isDone(), "agent received the human timeout");
                gate.rollback();
                assertTrue(agent.get(10, TimeUnit.SECONDS), "agent result");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
