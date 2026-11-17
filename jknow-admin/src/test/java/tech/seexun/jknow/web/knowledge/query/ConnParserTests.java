package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.query.ConnParser.ClickHouseConn;
import tech.seexun.jknow.web.knowledge.query.ConnParser.DatabricksConn;
import tech.seexun.jknow.web.knowledge.query.ConnParser.SnowflakeConn;
import tech.seexun.jknow.web.knowledge.query.ConnParser.TrinoConn;

/**
 * 移植自 query_engine/conn.rs 内联 4 例（断言逐字不改写）：四类 URL 的形状与 ssl
 * 升级规则——Trino 带密码/ssl/443/8443 升 https、ClickHouse 带密码<b>不</b>升级。
 */
class ConnParserTests {

    @Test
    void clickhouseIsPlainHttpOn8123UnlessAskedForTls() {
        ClickHouseConn c = ClickHouseConn.parse("clickhouse://analyst:p%40ss@ch.internal/sales");
        assertEquals("http://ch.internal:8123", c.base());
        assertEquals("analyst", c.user());
        // 带密码不升级：这一点跟 Trino 相反，见 ClickHouseConn 上的注释
        assertEquals("p@ss", c.password());
        assertEquals("sales", c.database());

        c = ClickHouseConn.parse("clickhouse://u:p@abc.clickhouse.cloud:8443/db");
        assertEquals("https://abc.clickhouse.cloud:8443", c.base());
        c = ClickHouseConn.parse("clickhouse://u@ch.internal?ssl=true");
        assertEquals("https://ch.internal:8443", c.base());
        assertNull(c.database());
        c = ClickHouseConn.parse("clickhouse://u@ch.internal:9443?secure=true");
        assertEquals("https://ch.internal:9443", c.base());
        c = ClickHouseConn.parse("clickhouse://u@127.0.0.1:8443?ssl=false");
        assertEquals("http://127.0.0.1:8443", c.base());

        // 没有用户就交给服务器的 default 用户
        c = ClickHouseConn.parse("clickhouse://ch.internal:8123");
        assertNull(c.user());
        assertNull(c.password());

        assertTrue(assertThrows(Exception.class,
                () -> ClickHouseConn.parse("clickhouse:///sales")).getMessage()
                        .contains("a host is required"));
    }

    @Test
    void trinoDefaultsToPlainHttpAndUpgradesWhenItMust() {
        TrinoConn c = TrinoConn.parse("trino://alice@lake.internal:8080/iceberg/sales");
        assertEquals("http://lake.internal:8080", c.base());
        assertEquals("alice", c.user());
        assertEquals("iceberg", c.catalog());
        assertEquals("sales", c.schema());

        c = TrinoConn.parse("trino://alice:s%40cret@lake.internal/hive");
        assertEquals("https://lake.internal", c.base());
        assertEquals("s@cret", c.password());

        c = TrinoConn.parse("trino://alice@lake.internal:8443/hive");
        assertEquals("https://lake.internal:8443", c.base());

        c = TrinoConn.parse("presto://bob@127.0.0.1:9000?ssl=false");
        assertEquals("http://127.0.0.1:9000", c.base());
        assertNull(c.catalog());

        assertTrue(assertThrows(Exception.class,
                () -> TrinoConn.parse("trino://lake.internal/hive")).getMessage()
                        .contains("a user is required"));
    }

    @Test
    void databricksReadsTheHttpPathFromTheConsole() {
        DatabricksConn c = DatabricksConn.parse(
                "databricks://:dapi123@dbc-abc.cloud.databricks.com/sql/1.0/warehouses/9f2a"
                        + "?catalog=main&schema=sales");
        assertEquals("https://dbc-abc.cloud.databricks.com", c.base());
        assertEquals("dapi123", c.token());
        assertEquals("9f2a", c.warehouseId());
        assertEquals("main", c.catalog());
        assertEquals("sales", c.schema());

        c = DatabricksConn.parse("databricks://dapi123@host?warehouse=w1");
        assertEquals("dapi123", c.token());
        assertEquals("w1", c.warehouseId());

        assertTrue(assertThrows(Exception.class,
                () -> DatabricksConn.parse("databricks://host/sql/1.0/warehouses/w1"))
                        .getMessage().contains("a personal access token"));
        assertTrue(assertThrows(Exception.class,
                () -> DatabricksConn.parse("databricks://:t@host")).getMessage()
                        .contains("a SQL warehouse"));
    }

    @Test
    void snowflakeTakesATokenAndTheSessionKnobs() {
        SnowflakeConn c = SnowflakeConn.parse(
                "snowflake://:tok@xy12345.eu-central-1.snowflakecomputing.com"
                        + "/ANALYTICS/PUBLIC?warehouse=WH&role=ANALYST");
        assertEquals("https://xy12345.eu-central-1.snowflakecomputing.com", c.base());
        assertEquals("tok", c.token());
        assertEquals("PROGRAMMATIC_ACCESS_TOKEN", c.tokenType());
        assertEquals("ANALYTICS", c.database());
        assertEquals("PUBLIC", c.schema());
        assertEquals("WH", c.warehouse());
        assertEquals("ANALYST", c.role());

        c = SnowflakeConn.parse(
                "snowflake://:tok@acct.snowflakecomputing.com?token_type=oauth");
        assertEquals("OAUTH", c.tokenType());
        assertTrue(assertThrows(Exception.class,
                () -> SnowflakeConn.parse(
                        "snowflake://:tok@acct.snowflakecomputing.com?token_type=jwt"))
                        .getMessage().contains("unknown token_type"));
        assertTrue(assertThrows(Exception.class,
                () -> SnowflakeConn.parse("snowflake://acct.snowflakecomputing.com/DB"))
                        .getMessage().contains("token"));
    }
}
