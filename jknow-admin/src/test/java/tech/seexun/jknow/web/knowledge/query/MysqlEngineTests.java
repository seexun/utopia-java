package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.query.MysqlEngine.Cell;

/**
 * 移植自 query_engine/mysql.rs 内联 7 例（断言逐字不改写）。4 例对真服务器跑：
 * Rust 侧 {@code JKNOW_TEST_MYSQL_URL} 环境门控（文档给的起法就是 mariadb:11.4，
 * 建表语句见 #316）——Java 侧按任务口径换 Testcontainers 同镜像起一台（MariaDB 同时
 * 走 max_statement_time 的超时写法，正是双写法要测的那半边）；另 3 例纯函数直译。
 *
 * <p>容器用裸 {@link GenericContainer}：MariaDBContainer 的就绪探测要 org.mariadb.jdbc
 * 驱动，而引擎走 Connector/J（协议同族）——就绪探日志行（ready for connections），
 * 连接串自己拼。
 *
 * <p>每个测试自己的库，名字带随机后缀：并行跑不撞，也不碰服务器上已有的库。测完
 * 逐个 DROP DATABASE。
 */
@Testcontainers(disabledWithoutDocker = true)
class MysqlEngineTests {

    private static final String ROOT_PASSWORD = "utopia-mysql-test";

    @Container
    static final GenericContainer<?> MARIADB = new GenericContainer<>("mariadb:11.4")
            .withEnv("MARIADB_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("MARIADB_ROOT_HOST", "%")
            .withExposedPorts(3306)
            // mariadb 镜像的日志会打两遍 "ready for connections"（初始化阶段 unix socket 一遍、
            // 网络端口就绪后再一遍）——等到第二遍才是 3306 真可连
            .waitingFor(Wait.forLogMessage(".*ready for connections.*", 2));

    private static String url;
    private static Connection pool;

    @BeforeAll
    static void setUp() throws Exception {
        if (!MARIADB.isRunning()) {
            return;
        }
        String host = MARIADB.getHost();
        int port = MARIADB.getMappedPort(3306);
        String user = "root";
        String password = ROOT_PASSWORD;
        // 连接串照原样用：带不带库名都行（Rust 同款；FETCH schema 的库过滤靠 information_schema）
        url = "mysql://" + user + ":" + password + "@" + host + ":" + port;
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        pool = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port, props);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (pool != null) {
            pool.close();
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Statement st = pool.createStatement()) {
            for (String part : sql.split(";\\s*\\n")) {
                if (!part.trim().isEmpty()) {
                    st.execute(part);
                }
            }
        }
    }

    private static List<SchemaColumn> columns(String connUrl, String schema) throws Exception {
        List<SchemaColumn> cols = new MysqlEngine(connUrl).fetchSchema();
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

    /** #316 的建表语句（真服务器档）：每一列各占一档——无符号/DECIMAL/时间/布尔。 */
    private static void seedSales() throws SQLException {
        exec("CREATE DATABASE IF NOT EXISTS sales");
        exec("CREATE TABLE sales.orders (\n"
                + "  id INT PRIMARY KEY AUTO_INCREMENT,\n"
                + "  region VARCHAR(20),\n"
                + "  amount DECIMAL(12,2) COMMENT 'Order total in CNY',\n"
                + "  qty BIGINT UNSIGNED,\n"
                + "  flag TINYINT(1),\n"
                + "  placed_on DATE\n"
                + ")");
        exec("INSERT INTO sales.orders (region, amount, qty, flag, placed_on) VALUES\n"
                + "  ('east', 1234.56, 18446744073709551615, 1, '2023-06-01'),\n"
                + "  ('west', 42.00, 7, 0, '2023-06-02'),\n"
                + "  (NULL, NULL, NULL, NULL, NULL)");
    }

    /** 对着真服务器跑的那一档：information_schema 列名、超时双写法、取值往返。 */
    @Test
    void aLiveServerAnswersWithTypedValues() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MARIADB.isRunning());
        seedSales();
        MysqlEngine engine = new MysqlEngine(url);
        engine.test();

        // information_schema 的列名与 PG 不同（column_type/column_comment），写错了不会
        // 报错，只会让 schema 文档少一半
        List<SchemaColumn> schema = engine.fetchSchema();
        SchemaColumn amount = schema.stream()
                .filter(c -> c.table().equals("orders") && c.column().equals("amount"))
                .findFirst().orElseThrow(() -> new AssertionError("orders.amount"));
        assertTrue(amount.dataType().startsWith("decimal"),
                "column_type 要给出带精度的形态，拿到的是 " + amount.dataType());
        assertEquals("Order total in CNY", amount.comment(), "注释要跟着列走");
        assertTrue(schema.stream().noneMatch(c -> "".equals(c.comment())),
                "空注释是空串不是 NULL，要过滤掉");

        // 取值往返：每一种掉档都会在这里现形——数变成字符串，或者整列 null
        QueryResult r = engine.execute(
                "SELECT id, region, amount, qty, flag, placed_on FROM sales.orders ORDER BY id");
        assertEquals(3, r.rows().size());
        JsonNode first = HttpSupport.JSON.readTree(r.rows().get(0));
        assertEquals(1, first.path("id").asInt());
        assertEquals("east", first.path("region").asText());
        // DECIMAL：没有 BigDecimal 这一格就是 null
        assertEquals(HttpSupport.JSON.readTree("1234.56"), first.path("amount"));
        // BIGINT UNSIGNED：i64 装不下，且驱动拒绝用 i64 读它
        assertEquals(HttpSupport.JSON.readTree("18446744073709551615"), first.path("qty"));
        // TINYINT(1)：Connector/J 默认 tinyInt1isBit=true，getObject 本就给 Boolean
        assertEquals(HttpSupport.JSON.readTree("true"), first.path("flag"));
        assertEquals("2023-06-01", first.path("placed_on").asText());

        // 全 NULL 的那一行：每一格都该是 JSON null，而不是某个类型的零值
        JsonNode third = HttpSupport.JSON.readTree(r.rows().get(2));
        for (String k : new String[]{"region", "amount", "qty", "flag", "placed_on"}) {
            assertTrue(third.path(k).isNull(), k + " 该是 null");
        }

        // 写路径仍然被闸挡住（第 1 层），只读会话是第 3 层
        assertTrue(assertThrows(Exception.class,
                () -> QueryEngines.guardSqlFor("mysql", "DELETE FROM sales.orders"))
                        .getMessage().startsWith("Read-only:"));
    }

    private static String newDatabase(int i) throws SQLException {
        String suffix = Uuids.nowV7().toString().replace("-", "");
        String name = "mysql_keys_" + i + "_" + suffix.substring(suffix.length() - 12);
        exec("CREATE DATABASE `" + name + "`");
        return name;
    }

    /** 单列主键标出来；组合主键、组合外键的成员都不标。 */
    @Test
    void aSingleColumnKeyIsMarkedAndACompositeOneIsNotMysql() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MARIADB.isRunning());
        String a = newDatabase(0);
        try {
            exec("CREATE TABLE `" + a + "`.parent (id INT PRIMARY KEY AUTO_INCREMENT,"
                    + " name VARCHAR(100) NOT NULL, note TEXT);\n"
                    + "             CREATE TABLE `" + a + "`.line (\n"
                    + "                 order_id INT NOT NULL,\n"
                    + "                 ordinal INT NOT NULL,\n"
                    + "                 payload TEXT,\n"
                    + "                 PRIMARY KEY (order_id, ordinal)\n"
                    + "             );\n"
                    + "             CREATE TABLE `" + a + "`.line_note (\n"
                    + "                 order_id INT,\n"
                    + "                 ordinal INT,\n"
                    + "                 FOREIGN KEY (order_id, ordinal) REFERENCES `" + a
                    + "`.line (order_id, ordinal)\n"
                    + "             );");

            List<SchemaColumn> cols = columns(url, a);
            SchemaColumn id = col(cols, a, "parent", "id");
            assertTrue(id.isPrimaryKey());
            assertTrue(id.referencesTable() == null);
            assertFalse(col(cols, a, "parent", "name").isPrimaryKey());
            SchemaColumn note = col(cols, a, "parent", "note");
            assertFalse(note.isPrimaryKey());
            assertTrue(note.referencesTable() == null);
            for (String c : new String[]{"order_id", "ordinal"}) {
                assertFalse(col(cols, a, "line", c).isPrimaryKey(),
                        "组合主键的成员 line." + c + " 不标");
                assertTrue(col(cols, a, "line_note", c).referencesTable() == null,
                        "组合外键的成员 line_note." + c + " 不标");
            }
        } finally {
            dropDatabase(a);
        }
    }

    /** 外键带上它指向的表：自引用、跨库；一列同时在两个单列外键里时取排序在前的那个。 */
    @Test
    void aForeignKeyPointsAtItsOwnTargetMysql() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MARIADB.isRunning());
        String a = newDatabase(0);
        String b = newDatabase(1);
        try {
            exec("CREATE TABLE `" + a + "`.p1 (id INT PRIMARY KEY AUTO_INCREMENT, parent INT,"
                    + " FOREIGN KEY (parent) REFERENCES `" + a + "`.p1 (id));\n"
                    + "             CREATE TABLE `" + a + "`.p2 (id INT PRIMARY KEY"
                    + " AUTO_INCREMENT);\n"
                    + "             CREATE TABLE `" + a + "`.c1 (x INT, FOREIGN KEY (x)"
                    + " REFERENCES `" + a + "`.p1 (id));\n"
                    + "             CREATE TABLE `" + a + "`.c2 (y INT, FOREIGN KEY (y)"
                    + " REFERENCES `" + a + "`.p2 (id));\n"
                    + "             CREATE TABLE `" + a + "`.c3 (z INT, FOREIGN KEY (z)"
                    + " REFERENCES `" + a + "`.p1 (id));\n"
                    + "             CREATE TABLE `" + b + "`.orders (item_id INT NOT NULL,"
                    + " FOREIGN KEY (item_id) REFERENCES `" + a + "`.p2 (id));\n"
                    + "             CREATE TABLE `" + a + "`.dual_fk (x INT,\n"
                    + "                 FOREIGN KEY (x) REFERENCES `" + a + "`.p2 (id),\n"
                    + "                 FOREIGN KEY (x) REFERENCES `" + a + "`.p1 (id));");

            List<SchemaColumn> cols = new MysqlEngine(url).fetchSchema();
            String p1 = a + ".p1";
            String p2 = a + ".p2";
            assertEquals(p1, col(cols, a, "p1", "parent").referencesTable(), "自引用");
            assertEquals(p1, col(cols, a, "c1", "x").referencesTable());
            assertEquals(p2, col(cols, a, "c2", "y").referencesTable(), "c2 指 p2 不是 p1");
            assertEquals(p1, col(cols, a, "c3", "z").referencesTable());
            assertEquals(p2, col(cols, b, "orders", "item_id").referencesTable(), "跨库");
            assertEquals(p1, col(cols, a, "dual_fk", "x").referencesTable(),
                    "两条外键取排序在前的");
            SchemaColumn p2Id = col(cols, a, "p2", "id");
            assertTrue(p2Id.isPrimaryKey() && p2Id.referencesTable() == null,
                    "p2.id 是 PK，不是 FK");
        } finally {
            dropDatabase(b);
            dropDatabase(a);
        }
    }

    /** 只有 SELECT 权限的连接照样读得到键：BI 连接大多就是这种用户。建不了用户就跳过。 */
    @Test
    void aReadOnlyLoginStillSeesTheKeysMysql() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(MARIADB.isRunning());
        String a = newDatabase(0);
        try {
            exec("CREATE TABLE `" + a + "`.p (id INT PRIMARY KEY AUTO_INCREMENT);\n"
                    + "             CREATE TABLE `" + a + "`.c (p_id INT, FOREIGN KEY (p_id)"
                    + " REFERENCES `" + a + "`.p (id));");
            String user = "ro_" + Uuids.nowV7().toString().replace("-", "").substring(0, 16);
            String password = Uuids.nowV7().toString().replace("-", "").substring(0, 16);
            try {
                exec("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'");
            } catch (SQLException e) {
                System.err.println("跳过：建不了只读用户（" + e.getMessage() + "）");
                return;
            }
            try {
                exec("GRANT SELECT ON `" + a + "`.* TO '" + user + "'@'%'");
            } catch (SQLException e) {
                // 测试用户没有 GRANT 权（root 之外）：跳过这一档
                System.err.println("跳过：GRANT 失败（" + e.getMessage() + "）");
                exec("DROP USER '" + user + "'@'%'");
                return;
            }

            // 换成只读用户，并去掉库名：它只在测试库上有 SELECT，连到连接串里写的那个库
            // （比如 /sales）会被拒
            String roUrl = url.substring(0, url.indexOf("://") + 3) + user + ":" + password
                    + "@" + url.substring(url.indexOf('@') + 1);
            try {
                List<SchemaColumn> cols = columns(roUrl, a);
                assertTrue(col(cols, a, "p", "id").isPrimaryKey());
                assertEquals(a + ".p", col(cols, a, "c", "p_id").referencesTable());
            } finally {
                exec("DROP USER '" + user + "'@'%'");
            }
        } finally {
            dropDatabase(a);
        }
    }

    // ---- 纯函数三例（分派表判据=驱动报型；Rust 侧 sqlx/Java 侧 Connector/J 同一规矩） ----

    @Test
    void mariadbIsTheSameProtocolUnderAnotherName() {
        // Connector/J 只认 jdbc:mysql://，而界面允许写 mariadb://——不改写的话驱动会以
        // 「未知 scheme」拒掉一个完全正常的连接串
        assertEquals("mysql://u:p@h:3306/db",
                new MysqlEngine("mariadb://u:p@h:3306/db").connString());
        assertEquals("mysql://u:p@h:3306/db",
                new MysqlEngine("mysql://u:p@h:3306/db").connString());
        // 只剥前缀：密码里出现同样的字符不该被动到
        assertEquals("mysql://u:mariadb://x@h/db",
                new MysqlEngine("mysql://u:mariadb://x@h/db").connString());
    }

    /**
     * 这三个断言各对应一种「整列变 null」（mysql.rs:715-743 every_number_shape）。
     * Rust 侧的名字来自 sqlx 的 ColumnType::name；Java 侧同一张表判 Connector/J 的
     * getColumnTypeName（判据=驱动的报型，不是 MySQL 手册）。
     */
    @Test
    void everyNumberShapeLandsInAReadableSlot() {
        for (String t : new String[]{"TINYINT", "SMALLINT", "MEDIUMINT", "INT", "BIGINT",
                "int"}) {
            assertEquals(Cell.INT, MysqlEngine.cellKind(t), t);
        }
        // 驱动的有符号读取装不下无符号大整数，而类型名带着后缀过来。
        // 少了这一档，一个 BIGINT UNSIGNED 列会掉进兜底，兜底读不出整数
        for (String t : new String[]{"TINYINT UNSIGNED", "SMALLINT UNSIGNED",
                "MEDIUMINT UNSIGNED", "INT UNSIGNED", "BIGINT UNSIGNED"}) {
            assertEquals(Cell.UNSIGNED_INT, MysqlEngine.cellKind(t), t);
        }
        // DECIMAL 被 sqlx 同时挡在 f64 与 String 之外（只有 BigDecimal 读得出）；Java 侧
        // getObject 给 BigDecimal——金额列几乎都是这个类型，掉档的代价最大
        assertEquals(Cell.DECIMAL, MysqlEngine.cellKind("DECIMAL"));
        assertEquals(Cell.DECIMAL, MysqlEngine.cellKind("DECIMAL UNSIGNED"));
        assertEquals(Cell.FLOAT, MysqlEngine.cellKind("DOUBLE"));
        assertEquals(Cell.FLOAT, MysqlEngine.cellKind("DOUBLE UNSIGNED"));
        // TINYINT(1) 在 sqlx 里报 BOOLEAN；Connector/J 的 getObject 也直接给 Boolean
        assertEquals(Cell.BOOL, MysqlEngine.cellKind("BOOLEAN"));
        assertEquals(Cell.TEXT, MysqlEngine.cellKind("VARCHAR"));
        assertEquals(Cell.TEXT, MysqlEngine.cellKind("BLOB"));
    }

    @Test
    void eachTimeTypeKeepsItsOwnShape() {
        // 四种时间各读各的。DATE 当成 DATETIME 读会报错退回字符串，拿到的就不是
        // 2023-06-01 而是驱动的原始形态
        assertEquals(Cell.DATE, MysqlEngine.cellKind("DATE"));
        assertEquals(Cell.TIME, MysqlEngine.cellKind("TIME"));
        assertEquals(Cell.DATETIME, MysqlEngine.cellKind("DATETIME"));
        assertEquals(Cell.TIMESTAMP, MysqlEngine.cellKind("TIMESTAMP"));
    }

    private static void dropDatabase(String name) {
        try (Statement st = pool.createStatement()) {
            // 跨库外键会让 DROP DATABASE 顺序敏感：临时关掉 session 级外键检查再删
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("DROP DATABASE IF EXISTS `" + name + "`");
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (SQLException e) {
            System.err.println("drop database " + name + " failed: " + e.getMessage());
        }
    }
}
