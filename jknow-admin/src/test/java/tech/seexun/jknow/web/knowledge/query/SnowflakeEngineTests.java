package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.query.ConnParser.SnowflakeConn;

/**
 * 移植自 query_engine/snowflake.rs 测试 6 例（wiremock 回放协议，断言逐字不改写；
 * Java 侧 ScriptedServer 同角色）：同步应答按 rowType 还原、202 轮询 + 分区沿用首块
 * 类型（后续分区 gzip 压缩不带元数据）、错误体上报、分区读到穷尽、多分区必须带
 * statementHandle、失败/残缺分区不出半截行。
 */
class SnowflakeEngineTests {

    private static SnowflakeConn conn(ScriptedServer server) {
        return SnowflakeConn.parse("snowflake://:pat-test@" + server.hostPort()
                + "/ANALYTICS/PUBLIC?warehouse=WH&ssl=false");
    }

    private static ScriptedServer.Reply ok(String body) {
        return ScriptedServer.Reply.json(200, ScriptedServer.j(body));
    }

    // The SQL API compresses later partitions and omits their metadata.
    private static ScriptedServer.Reply compressedPartition(String dataLiteral) {
        return ScriptedServer.Reply.gzip(200, ScriptedServer.j("{ \"data\": " + dataLiteral
                + " }"));
    }

    @Test
    void aSynchronousAnswerIsTypedByRowType() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub post = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/v2/statements"),
                            ScriptedServer.header("authorization", "Bearer pat-test"),
                            ScriptedServer.header("X-Snowflake-Authorization-Token-Type",
                                    "PROGRAMMATIC_ACCESS_TOKEN")),
                    r -> ok("{\n"
                            + "                \"resultSetMetaData\": { \"numRows\": 1,"
                            + " \"rowType\": [\n"
                            + "                    { \"name\": \"REGION\", \"type\":"
                            + " \"text\" },\n"
                            + "                    { \"name\": \"TOTAL\", \"type\":"
                            + " \"fixed\", \"scale\": 2 }\n"
                            + "                ] },\n"
                            + "                \"data\": [ [\"east\", \"42.10\"] ],\n"
                            + "                \"code\": \"090001\",\n"
                            + "                \"statementHandle\": \"h1\",\n"
                            + "                \"message\": \"Statement executed"
                            + " successfully.\"\n"
                            + "            }"));
            QueryResult out = new SnowflakeEngine(conn(server))
                    .execute("SELECT region, total FROM orders");
            assertEquals(List.of("{\"REGION\":\"east\",\"TOTAL\":42.1}"), out.rows());
            assertEquals(1, post.hits());
        }
    }

    @Test
    void a202IsPolledAndItsPartitionsKeepTheFirstRowsTypes() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub post = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/v2/statements")),
                    r -> ScriptedServer.Reply.json(202, ScriptedServer.j("{\n"
                            + "                \"code\": \"333334\","
                            + " \"statementHandle\": \"h2\", \"message\":"
                            + " \"Asynchronous execution in progress.\"\n"
                            + "            }")));
            ScriptedServer.Stub poll = server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/v2/statements/h2"),
                            ScriptedServer.queryParamMissing("partition")),
                    r -> ok("{\n"
                            + "                \"resultSetMetaData\": {\n"
                            + "                    \"numRows\": 3,\n"
                            + "                    \"rowType\": [ { \"name\": \"N\","
                            + " \"type\": \"fixed\" } ],\n"
                            + "                    \"partitionInfo\": [ { \"rowCount\":"
                            + " 1 }, { \"rowCount\": 2 } ]\n"
                            + "                },\n"
                            + "                \"data\": [ [\"1\"] ], \"code\":"
                            + " \"090001\", \"statementHandle\": \"h2\"\n"
                            + "            }"));
            ScriptedServer.Stub partition1 = server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/v2/statements/h2"),
                            ScriptedServer.queryParam("partition", "1"),
                            ScriptedServer.header("authorization", "Bearer pat-test"),
                            ScriptedServer.header("X-Snowflake-Authorization-Token-Type",
                                    "PROGRAMMATIC_ACCESS_TOKEN")),
                    r -> compressedPartition("[[\"2\"], [null]]"));
            QueryResult out = new SnowflakeEngine(conn(server)).execute("SELECT 1 AS n");
            assertEquals(List.of("{\"N\":1}", "{\"N\":2}", "{\"N\":null}"), out.rows());
            assertFalse(out.truncated());
            assertEquals(1, post.hits());
            assertEquals(1, poll.hits());
            assertEquals(1, partition1.hits());
        }
    }

    @Test
    void anErrorBodyIsSurfaced() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/v2/statements")),
                    r -> ScriptedServer.Reply.json(422, ScriptedServer.j("{\n"
                            + "                \"code\": \"002003\", \"message\":"
                            + " \"SQL compilation error: Object 'NOPE' does not exist\"\n"
                            + "            }")));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new SnowflakeEngine(conn(server)).execute("SELECT * FROM nope"))
                            .getMessage());
            assertTrue(err.contains("does not exist"), err);
        }
    }

    /**
     * partitionInfo only describes row counts and sizes. Partition zero is inline;
     * subsequent partitions use the statement handle and their index.
     */
    @Test
    void aSchemaReadFollowsPartitionsUntilExhausted() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/v2/statements")),
                    r -> ok("{\n"
                            + "                \"resultSetMetaData\": {\n"
                            + "                    \"numRows\": 3,\n"
                            + "                    \"rowType\": [\n"
                            + "                        { \"name\": \"TABLE_SCHEMA\","
                            + " \"type\": \"text\" },\n"
                            + "                        { \"name\": \"TABLE_NAME\","
                            + " \"type\": \"text\" },\n"
                            + "                        { \"name\": \"COLUMN_NAME\","
                            + " \"type\": \"text\" },\n"
                            + "                        { \"name\": \"DATA_TYPE\","
                            + " \"type\": \"text\" },\n"
                            + "                        { \"name\": \"COMMENT\","
                            + " \"type\": \"text\" }\n"
                            + "                    ],\n"
                            + "                    \"partitionInfo\": [\n"
                            + "                        { \"rowCount\": 1,"
                            + " \"uncompressedSize\": 120 },\n"
                            + "                        { \"rowCount\": 1,"
                            + " \"uncompressedSize\": 130, \"compressedSize\": 80 },\n"
                            + "                        { \"rowCount\": 1,"
                            + " \"uncompressedSize\": 140, \"compressedSize\": 90 }\n"
                            + "                    ]\n"
                            + "                },\n"
                            + "                \"data\": [\n"
                            + "                    [\"default\", \"wide_orders\", \"id\","
                            + " \"BIGINT\", \"主键\"]\n"
                            + "                ],\n"
                            + "                \"code\": \"090001\",\n"
                            + "                \"statementHandle\": \"h3\",\n"
                            + "                \"message\": \"Statement executed"
                            + " successfully.\"\n"
                            + "            }"));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/v2/statements/h3"),
                            ScriptedServer.queryParam("partition", "1"),
                            ScriptedServer.header("authorization", "Bearer pat-test")),
                    r -> compressedPartition("[[\n"
                            + "                \"default\",\n"
                            + "                \"wide_orders\",\n"
                            + "                \"region\",\n"
                            + "                \"STRING\",\n"
                            + "                null\n"
                            + "            ]]"));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/v2/statements/h3"),
                            ScriptedServer.queryParam("partition", "2"),
                            ScriptedServer.header("authorization", "Bearer pat-test")),
                    r -> compressedPartition("[[\n"
                            + "                \"default\",\n"
                            + "                \"wide_orders\",\n"
                            + "                \"amount\",\n"
                            + "                \"DECIMAL(12,2)\",\n"
                            + "                null\n"
                            + "            ]]"));

            List<SchemaColumn> cols = new SnowflakeEngine(conn(server)).fetchSchema();
            assertEquals(3, cols.size(),
                    "want 3 columns across partitions, got " + cols);
            assertEquals("id", cols.get(0).column());
            assertEquals("region", cols.get(1).column());
            assertEquals("amount", cols.get(2).column());
        }
    }

    @Test
    void multiplePartitionsRequireAStatementHandle() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/v2/statements")),
                    r -> ok("{\n"
                            + "                \"resultSetMetaData\": {\n"
                            + "                    \"rowType\": [{ \"name\": \"N\","
                            + " \"type\": \"fixed\" }],\n"
                            + "                    \"partitionInfo\": [{ \"rowCount\":"
                            + " 1 }, { \"rowCount\": 1 }]\n"
                            + "                },\n"
                            + "                \"data\": [[\"1\"]]\n"
                            + "            }"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new SnowflakeEngine(conn(server)).execute("SELECT n FROM numbers"))
                            .getMessage());
            assertTrue(err.contains("multiple partitions without a statementHandle"), err);
        }
    }

    @Test
    void failedOrIncompletePartitionsDoNotReturnPartialRows() throws Exception {
        for (Object[] pair : new Object[][]{
                {Boolean.TRUE, "503"},
                {Boolean.FALSE, "numRows reports 2"},
        }) {
            try (ScriptedServer server = ScriptedServer.start()) {
                server.on(
                        ScriptedServer.all(ScriptedServer.method("POST"),
                                ScriptedServer.path("/api/v2/statements")),
                        r -> ok("{\n"
                                + "                    \"resultSetMetaData\": {\n"
                                + "                        \"numRows\": 2,\n"
                                + "                        \"rowType\": [{ \"name\":"
                                + " \"N\", \"type\": \"fixed\" }],\n"
                                + "                        \"partitionInfo\":"
                                + " [{ \"rowCount\": 1 }, { \"rowCount\": 1 }]\n"
                                + "                    },\n"
                                + "                    \"data\": [[\"1\"]],"
                                + " \"statementHandle\": \"h4\"\n"
                                + "                }"));
                server.on(
                        ScriptedServer.all(ScriptedServer.method("GET"),
                                ScriptedServer.path("/api/v2/statements/h4"),
                                ScriptedServer.queryParam("partition", "1")),
                        r -> (Boolean) pair[0]
                                ? new ScriptedServer.Reply(503, java.util.Map.of(), 0,
                                        new byte[0])
                                : compressedPartition("[]"));
                String err = String.valueOf(assertThrows(Exception.class,
                        () -> new SnowflakeEngine(conn(server))
                                .execute("SELECT n FROM numbers")).getMessage());
                assertTrue(err.contains((String) pair[1]), err);
            }
        }
    }
}
