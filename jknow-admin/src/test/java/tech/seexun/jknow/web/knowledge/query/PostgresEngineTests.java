package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * 移植自 query_engine/postgres.rs 内联 3 例（对真 PG 跑，断言逐字不改写；Rust 侧
 * utopia_store::test_db::url() 的共享库口径——Java 侧同一台 15432（P1 任务 7 起 jknow dev 库），不可达则跳过）：
 * 单列键标/组合键不标、外键指向它自己的目标（同名约束不串表/跨 schema）、只有
 * SELECT 权限的连接照样读得到键（BI 连接大多就是这种角色）。
 *
 * <p>每个测试自己的 schema，名字带随机后缀：并行跑不撞，也不碰库里已有的表。测完
 * DROP SCHEMA ... CASCADE。
 */
class PostgresEngineTests {

    private static String url;
    private static Connection pool;

    @BeforeAll
    static void setUp() throws Exception {
        String jdbc = System.getenv().getOrDefault("JKNOW_TEST_JDBC_URL",
                "jdbc:postgresql://localhost:15432/jknow");
        if (!reachable(jdbc)) {
            Assumptions.assumeTrue(false, "共享库（PG 15432）不可达，跳过 postgres 引擎测试");
        }
        String user = System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        pool = DriverManager.getConnection(jdbc, props);
        // engine 吃 postgres:// 形态的连接串（与界面同一输入）
        String hostPort = jdbc.replace("jdbc:postgresql://", "");
        url = "postgres://" + user + ":" + password + "@" + hostPort;
    }

    private static boolean reachable(String jdbc) {
        try {
            String hostPort = jdbc.replace("jdbc:postgresql://", "").split("/", 2)[0];
            String[] parts = hostPort.split(":");
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(parts[0],
                        Integer.parseInt(parts[1])), 1000);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (pool != null) {
            pool.close();
        }
    }

    private static String newSchema(String prefix) throws SQLException {
        String suffix = Uuids.nowV7().toString().replace("-", "");
        String schema = prefix + "_" + suffix.substring(suffix.length() - 12);
        try (Statement st = pool.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        return schema;
    }

    private static void exec(String sql) throws SQLException {
        try (Statement st = pool.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<SchemaColumn> columns(String connUrl, String schema)
            throws Exception {
        List<SchemaColumn> cols = new PostgresEngine(connUrl).fetchSchema();
        return cols.stream().filter(c -> c.schema().equals(schema)).toList();
    }

    private static SchemaColumn col(List<SchemaColumn> cols, String schema, String table,
                                    String column) {
        return cols.stream()
                .filter(c -> c.schema().equals(schema) && c.table().equals(table)
                        && c.column().equals(column))
                .findFirst()
                .orElseThrow(() -> new AssertionError(schema + "." + table + "." + column));
    }

    /** 单列主键标出来；组合主键、组合外键的成员都不标。 */
    @Test
    void aSingleColumnKeyIsMarkedAndACompositeOneIsNot() throws Exception {
        String a = newSchema("schema_keys_0");
        try {
            exec("CREATE TABLE " + a + ".parent (id serial PRIMARY KEY, name text NOT NULL,"
                    + " note text);\n"
                    + "             CREATE TABLE " + a + ".line (\n"
                    + "                 order_id int NOT NULL,\n"
                    + "                 ordinal int NOT NULL,\n"
                    + "                 payload text,\n"
                    + "                 PRIMARY KEY (order_id, ordinal)\n"
                    + "             );\n"
                    + "             CREATE TABLE " + a + ".line_note (\n"
                    + "                 order_id int,\n"
                    + "                 ordinal int,\n"
                    + "                 FOREIGN KEY (order_id, ordinal) REFERENCES " + a
                    + ".line (order_id, ordinal)\n"
                    + "             );");

            List<SchemaColumn> cols = columns(url, a);
            SchemaColumn id = col(cols, a, "parent", "id");
            assertTrue(id.isPrimaryKey());
            assertFalse(id.referencesTable() != null);
            SchemaColumn name = col(cols, a, "parent", "name");
            assertFalse(name.isPrimaryKey());
            SchemaColumn note = col(cols, a, "parent", "note");
            assertFalse(note.isPrimaryKey());
            assertTrue(note.referencesTable() == null);
            for (String c : new String[]{"order_id", "ordinal"}) {
                SchemaColumn member = col(cols, a, "line", c);
                assertFalse(member.isPrimaryKey(), "组合主键的成员 line." + c + " 不标");
                SchemaColumn fkMember = col(cols, a, "line_note", c);
                assertTrue(fkMember.referencesTable() == null,
                        "组合外键的成员 line_note." + c + " 不标");
            }
        } finally {
            drop(a);
        }
    }

    /**
     * 外键带上它指向的表：自引用、跨 schema，以及两张表上<b>同名</b>的约束各指各的。
     * 约束名只在一张表内唯一，按名字连接会把目标串到别处。
     */
    @Test
    void aForeignKeyPointsAtItsOwnTarget() throws Exception {
        String a = newSchema("schema_keys_0");
        String b = newSchema("schema_keys_1");
        try {
            exec("CREATE TABLE " + a + ".p1 (id int PRIMARY KEY, parent int REFERENCES " + a
                    + ".p1 (id));\n"
                    + "             CREATE TABLE " + a + ".p2 (id int PRIMARY KEY);\n"
                    + "             CREATE TABLE " + a + ".c1 (x int CONSTRAINT fk_ref"
                    + " REFERENCES " + a + ".p1 (id));\n"
                    + "             CREATE TABLE " + a + ".c2 (y int CONSTRAINT fk_ref"
                    + " REFERENCES " + a + ".p2 (id));\n"
                    + "             -- 与 p2 的主键约束同名的外键\n"
                    + "             CREATE TABLE " + a + ".c3 (z int CONSTRAINT p2_pkey"
                    + " REFERENCES " + a + ".p1 (id));\n"
                    + "             CREATE TABLE " + b + ".orders (item_id int NOT NULL"
                    + " REFERENCES " + a + ".p2 (id));");

            List<SchemaColumn> cols = new PostgresEngine(url).fetchSchema();
            String p1 = a + ".p1";
            String p2 = a + ".p2";
            assertEquals(p1, col(cols, a, "p1", "parent").referencesTable(), "自引用");
            assertEquals(p1, col(cols, a, "c1", "x").referencesTable());
            assertEquals(p2, col(cols, a, "c2", "y").referencesTable(), "同名约束不串表");
            assertEquals(p1, col(cols, a, "c3", "z").referencesTable());
            assertEquals(p2, col(cols, b, "orders", "item_id").referencesTable(), "跨 schema");
            SchemaColumn p2Id = col(cols, a, "p2", "id");
            assertTrue(p2Id.isPrimaryKey() && p2Id.referencesTable() == null,
                    "同名的外键不影响 p2 的主键");
        } finally {
            drop(b);
            drop(a);
        }
    }

    /**
     * 只有 SELECT 权限的连接照样读得到键。information_schema 的约束视图对这种角色是
     * 空的——BI 连接大多就是这种角色。建不了角色（库用户没有 CREATEROLE）就跳过。
     */
    @Test
    void aReadOnlyLoginStillSeesTheKeys() throws Exception {
        String a = newSchema("schema_keys_0");
        try {
            exec("CREATE TABLE " + a + ".p (id int PRIMARY KEY);\n"
                    + "             CREATE TABLE " + a + ".c (p_id int REFERENCES " + a
                    + ".p (id));");
            String role = a + "_ro";
            String password = Uuids.nowV7().toString().replace("-", "");
            try {
                exec("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
                exec("GRANT USAGE ON SCHEMA " + a + " TO " + role + ";\n"
                        + "             GRANT SELECT ON ALL TABLES IN SCHEMA " + a + " TO "
                        + role + ";");
            } catch (SQLException e) {
                System.err.println("跳过：建不了只读角色（" + e.getMessage() + "）");
                return;
            }
            String roUrl = withUserInfo(url, role, password);
            try {
                List<SchemaColumn> cols = columns(roUrl, a);
                assertTrue(col(cols, a, "p", "id").isPrimaryKey());
                assertEquals(a + ".p", col(cols, a, "c", "p_id").referencesTable());
            } finally {
                exec("DROP OWNED BY " + role + "; DROP ROLE " + role);
            }
        } finally {
            drop(a);
        }
    }

    /** url::Url set_username/set_password 的对位：换掉连接串的 userinfo。 */
    private static String withUserInfo(String connUrl, String user, String password) {
        int schemeEnd = connUrl.indexOf("://") + 3;
        int at = connUrl.indexOf('@', schemeEnd);
        return connUrl.substring(0, schemeEnd) + user + ":" + password
                + connUrl.substring(at);
    }

    private static void drop(String schema) {
        try (Statement st = pool.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (SQLException e) {
            // 泄漏 schema 不该翻掉测试的断言结果，但留下痕迹
            System.err.println("drop schema " + schema + " failed: " + e.getMessage());
        }
    }
}
