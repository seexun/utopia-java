package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 移植自 query_engine/mod.rs 内联 7 例（断言逐字不改写）：安全闸第 1 层（单条
 * SELECT/WITH 放行、写/DDL/多语句拒、六方言同闸）、scheme 分派、JSON Lines 列序、
 * coerce 按列类型还原。
 */
class QueryEngineGuardTests {

    private static String guardSql(String sql) {
        return QueryEngines.guardSqlFor("postgres", sql);
    }

    @Test
    void allowsSelectAndCte() {
        assertEquals("SELECT region, sum(amount) FROM orders GROUP BY 1",
                guardSql("SELECT region, sum(amount) FROM orders GROUP BY 1"));
        assertEquals("WITH t AS (SELECT 1 AS x) SELECT * FROM t",
                guardSql("WITH t AS (SELECT 1 AS x) SELECT * FROM t;"));
    }

    @Test
    void rejectsWritesAndDdl() {
        for (String bad : new String[]{
                "UPDATE orders SET amount = 0",
                "DELETE FROM orders",
                "INSERT INTO orders (region) VALUES ('east')",
                "DROP TABLE orders",
                "TRUNCATE orders",
                "CREATE TABLE t (id int)",
                "ALTER TABLE orders ADD COLUMN x int",
        }) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> guardSql(bad), "should reject: " + bad);
            assertTrue(e.getMessage().startsWith("Read-only:"),
                    bad + " -> " + e.getMessage());
        }
    }

    @Test
    void rejectsMultiStatement() {
        assertEquals("Exactly one statement is allowed",
                assertThrows(IllegalArgumentException.class,
                        () -> guardSql("SELECT 1; DROP TABLE orders")).getMessage());
        assertEquals("Empty SQL",
                assertThrows(IllegalArgumentException.class, () -> guardSql("")).getMessage());
    }

    @Test
    void everyDialectKeepsTheSameGate() {
        for (String engine : new String[]{
                "postgres", "mysql", "trino", "databricks", "snowflake", "clickhouse",
        }) {
            assertEquals("SELECT a FROM t WHERE b > 1",
                    QueryEngines.guardSqlFor(engine, "SELECT a FROM t WHERE b > 1"), engine);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> QueryEngines.guardSqlFor(engine, "DELETE FROM t"),
                    engine).getMessage().startsWith("Read-only:"), engine);
            assertEquals("Exactly one statement is allowed",
                    assertThrows(IllegalArgumentException.class,
                            () -> QueryEngines.guardSqlFor(engine, "SELECT 1; SELECT 2"),
                            engine).getMessage(), engine);
        }
        // 各家的方言细节：反引号、双冒号转型，都要过得去
        assertEquals("SELECT `region` FROM main.sales.orders",
                QueryEngines.guardSqlFor("databricks", "SELECT `region` FROM main.sales.orders"));
        assertEquals("SELECT amount::number FROM db.public.orders",
                QueryEngines.guardSqlFor("snowflake", "SELECT amount::number FROM db.public.orders"));
        assertEquals("SELECT count(*) FROM hive.default.orders",
                QueryEngines.guardSqlFor("trino", "SELECT count(*) FROM hive.default.orders"));
        // MySQL 的反引号与 PG 方言不兼容，走自己的方言才过得去（JSqlParser 单一语法一体认得，
        // 与 Rust「Generic 是 Trino 超集」同一策略的 Java 形）
        assertEquals("SELECT `region` FROM `sales`.`orders`",
                QueryEngines.guardSqlFor("mysql", "SELECT `region` FROM `sales`.`orders`"));
        // ClickHouse 的函数名与 FINAL 修饰都要过得去。语句里的 SETTINGS 闸这一层不管：
        // readonly=1 生效时服务器拒掉它；readonly=2 的账号不拒，那时超时靠 KILL
        // （见 ClickHouseEngine）
        assertEquals(
                "SELECT toStartOfMonth(placed_on) AS m, count() FROM sales.orders FINAL GROUP BY m",
                QueryEngines.guardSqlFor("clickhouse",
                        "SELECT toStartOfMonth(placed_on) AS m, count() FROM sales.orders"
                                + " FINAL GROUP BY m"));
    }

    @Test
    void engineFollowsTheScheme() {
        assertEquals("postgres", QueryEngines.engineFromConn("postgres://u:p@h/db"));
        assertEquals("postgres", QueryEngines.engineFromConn("postgresql://u:p@h/db"));
        assertEquals("trino", QueryEngines.engineFromConn("trino://u@h:8443/hive"));
        assertEquals("trino", QueryEngines.engineFromConn("presto://u@h/hive"));
        assertEquals("databricks",
                QueryEngines.engineFromConn("databricks://:t@h/sql/1.0/warehouses/x"));
        assertEquals("snowflake",
                QueryEngines.engineFromConn("snowflake://:t@a.snowflakecomputing.com/db"));
        assertEquals("mysql", QueryEngines.engineFromConn("mysql://u:p@h:3306/db"));
        // 同一套协议的另一个写法，引擎里会被改写成 mysql:// 再交给驱动
        assertEquals("mysql", QueryEngines.engineFromConn("mariadb://u@h/db"));
        assertEquals("clickhouse", QueryEngines.engineFromConn("clickhouse://u:p@h:8123/sales"));
        assertNull(QueryEngines.engineFromConn("garbage"));
    }

    @Test
    void jsonLinesKeepColumnOrder() {
        List<String> cols = List.of("zeta", "alpha");
        List<List<com.fasterxml.jackson.databind.JsonNode>> rows = List.of(
                List.of(new com.fasterxml.jackson.databind.node.IntNode(1), new TextNode("x")),
                List.of(NullNode.getInstance()));
        assertEquals(
                List.of("{\"zeta\":1,\"alpha\":\"x\"}", "{\"zeta\":null,\"alpha\":null}"),
                QueryEngines.rowsToJsonLines(cols, rows, HttpSupport.JSON));
    }

    @Test
    void stringsComeBackAsNumbersWhenTheColumnSaysSo() {
        assertEquals(new DoubleNode(12.5), QueryEngines.coerce("DOUBLE", new TextNode("12.5")));
        assertEquals(new com.fasterxml.jackson.databind.node.LongNode(42),
                QueryEngines.coerce("fixed", new TextNode("42")));
        assertEquals(com.fasterxml.jackson.databind.node.BooleanNode.TRUE,
                QueryEngines.coerce("BOOLEAN", new TextNode("true")));
        assertEquals(new TextNode("42"), QueryEngines.coerce("STRING", new TextNode("42")));
        assertEquals(new TextNode("1 day"), QueryEngines.coerce("INTERVAL", new TextNode("1 day")));
        assertEquals(NullNode.getInstance(), QueryEngines.coerce("DOUBLE", NullNode.getInstance()));
    }
}
