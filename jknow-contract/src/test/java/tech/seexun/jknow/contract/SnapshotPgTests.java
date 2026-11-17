package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 快照 dumper 直连共享库集成测试（M3 任务 1）：造数 INSERT fixture（固定 uuid）→ dump 两次
 * 逐字节一致 → 占位/排序断言 → @AfterAll 物理清理。共享 PG（127.0.0.1:1517，与 Rust 参照实例
 * 同库）不可达时整类跳过（干净 clone 的 mvn verify 仍绿）——沿 StoreTestBase 的 @EnabledIf 门模式
 * （utopia-server 侧），凭据缺省对齐共享栈部署（docker-compose：utopia/utopia）。
 *
 * <p>造数自律（StoreTestBase 同款纪律）：只 INSERT 专属 fixture（固定 uuid 的独立组织/工作空间/
 * kb/类型/实体/事实），不碰既有行；清理删工作空间（kb 级联）+ 组织。
 */
@EnabledIf(value = "pgUp", disabledReason = "共享库（PG 1517）不可达，跳过快照直连测试")
class SnapshotPgTests {

    private static final UUID ORG = UUID.fromString("7ea11000-0000-7000-8000-00000000c0de");
    private static final UUID WS = UUID.fromString("7ea11000-0000-7000-8000-00000000c0df");
    private static final UUID KB = UUID.fromString("7ea11000-0000-7000-8000-00000000c0e0");
    private static final UUID TYPE = UUID.fromString("7ea11000-0000-7000-8000-00000000c0e1");
    private static final UUID REL = UUID.fromString("7ea11000-0000-7000-8000-00000000c0e2");
    private static final UUID E_CARA = UUID.fromString("7ea11000-0000-7000-8000-00000000c0e3");
    private static final UUID E_GAMMA = UUID.fromString("7ea11000-0000-7000-8000-00000000c0e4");
    private static final UUID F_WORKS = UUID.fromString("7ea11000-0000-7000-8000-00000000c0e5");

    static boolean pgUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 1517), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Connection open() throws SQLException {
        String url = env("UTOPIA_JDBC_URL", "jdbc:postgresql://localhost:1517/utopia");
        String user = env("UTOPIA_DB_USERNAME", "utopia");
        String pass = env("UTOPIA_DB_PASSWORD", "utopia");
        return java.sql.DriverManager.getConnection(url, user, pass);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 同库两次 dump 逐字节稳定 + 表集覆盖/表名校验 + 静默等待即刻通过（无 job）。 */
    @Test void 同库两次dump逐字节稳定() throws Exception {
        try (Connection conn = open()) {
            insertFixture(conn);
            Snapshot.awaitQuiet(conn, KB, 3_000, 200); // 该 kb 无 job → 立即通过
            JsonNode first = Snapshot.dump(conn, KB,
                    List.of("facts", "entities", "entity_types", "relation_types"));
            JsonNode again = Snapshot.dump(conn, KB,
                    List.of("facts", "entities", "entity_types", "relation_types"));
            assertEquals(first.toString(), again.toString(), "同库两次 dump 必须逐字节一致");

            // 行按稳定键排序：Cara 在 Gamma 前（canonical_name 字典序）
            JsonNode entities = first.path("tables").path("entities");
            assertEquals("Cara", entities.get(0).path("canonical_name").asText());
            assertEquals("Gamma", entities.get(1).path("canonical_name").asText());

            // 引用一致性：事实的 subject_id 占位符 = Cara 实体 id 的占位符；kb 恒 <kb>
            JsonNode works = first.path("tables").path("facts").get(0);
            assertEquals(entities.get(0).path("id").asText(), works.path("subject_id").asText());
            assertEquals("<kb>", works.path("kb_id").asText());
            assertTrue(works.path("recorded_at").asText().startsWith("<t:"),
                    "时间戳必须占位保序: " + works.path("recorded_at"));
        }
    }

    /** tables 覆盖只能从登记词汇选；jobs（瞬态）永不进快照——给可定位报错。 */
    @Test void 未登记表名报错() throws Exception {
        try (Connection conn = open()) {
            insertFixture(conn);
            IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> Snapshot.dump(conn, KB, List.of("jobs")));
            assertTrue(e.getMessage().contains("jobs 永不进快照"), e.getMessage());
        }
    }

    private static void insertFixture(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM workspaces WHERE id = '" + WS + "'");
            st.executeUpdate("DELETE FROM organizations WHERE id = '" + ORG + "'");
            st.executeUpdate("INSERT INTO organizations (id, name) VALUES ('" + ORG + "', 'snap-test-org')");
            st.executeUpdate("INSERT INTO workspaces (id, org_id, name) VALUES ('" + WS + "', '" + ORG
                    + "', 'snap-test-ws')");
            st.executeUpdate("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES ('" + KB
                    + "', '" + WS + "', 'snap-test-kb')");
            st.executeUpdate("INSERT INTO entity_types (id, kb_id, key, label) VALUES ('" + TYPE
                    + "', '" + KB + "', 'person', 'Person')");
            st.executeUpdate("INSERT INTO relation_types (id, kb_id, key, label) VALUES ('" + REL
                    + "', '" + KB + "', 'works_at', 'works at')");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO entities (id, kb_id, canonical_name, type_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, E_CARA);
            ps.setObject(2, KB);
            ps.setString(3, "Cara");
            ps.setObject(4, TYPE);
            ps.executeUpdate();
            ps.setObject(1, E_GAMMA);
            ps.setObject(2, KB);
            ps.setString(3, "Gamma");
            ps.setObject(4, TYPE);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)"
                        + " VALUES (?, ?, ?, ?, ?, 0.9)")) {
            ps.setObject(1, F_WORKS);
            ps.setObject(2, KB);
            ps.setObject(3, E_CARA);
            ps.setObject(4, REL);
            ps.setObject(5, E_GAMMA);
            ps.executeUpdate();
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!pgUp()) {
            return;
        }
        try (Connection conn = open();
             Statement st = conn.createStatement()) {
            // kb 级联清（facts/entities/types 均 ON DELETE CASCADE 挂 kb）；工作空间/组织独立清
            st.executeUpdate("DELETE FROM workspaces WHERE id = '" + WS + "'");
            st.executeUpdate("DELETE FROM organizations WHERE id = '" + ORG + "'");
        }
    }
}
