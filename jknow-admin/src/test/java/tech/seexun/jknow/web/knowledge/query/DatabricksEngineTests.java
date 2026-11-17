package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.query.ConnParser.DatabricksConn;

/**
 * 移植自 query_engine/databricks.rs 测试 12 例（wiremock 回放协议，断言逐字不改写；
 * Java 侧 ScriptedServer 同角色）：轮询到 SUCCEEDED/类型还原、失败语句报文、schema
 * 读两候选（catalog 退 system）、「两条死路各带原话」、非缺失不重试、退路 0 行算失败、
 * INLINE 多分块收齐、chat 查询分块后 cap、后块失败不出半截结果、internal link 不出
 * workspace、服务端 truncated 失败、total_row_count 对不上失败。
 */
class DatabricksEngineTests {

    private static DatabricksConn conn(ScriptedServer server) {
        return DatabricksConn.parse("databricks://:dapi-test@" + server.hostPort()
                + "/sql/1.0/warehouses/wh1?catalog=main&ssl=false");
    }

    private static ScriptedServer.Reply ok(String body) {
        return ScriptedServer.Reply.json(200, ScriptedServer.j(body));
    }

    private static ScriptedServer.Reply failed(String code, String message) {
        return ok("{\"status\": { \"state\": \"FAILED\", \"error\": { \"error_code\": \"" + code
                + "\", \"message\": \"" + message + "\" } } }");
    }

    @Test
    void pollsUntilSucceededAndRestoresTypes() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.header("authorization", "Bearer dapi-test")),
                    r -> ok("{\"statement_id\": \"s1\", \"status\": { \"state\": \"PENDING\" } }"));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/2.0/sql/statements/s1")),
                    r -> ok("{\n"
                            + "                \"statement_id\": \"s1\",\n"
                            + "                \"status\": { \"state\": \"SUCCEEDED\" },\n"
                            + "                \"manifest\": { \"schema\": { \"columns\": [\n"
                            + "                    { \"name\": \"region\", \"type_text\":"
                            + " \"STRING\", \"position\": 0 },\n"
                            + "                    { \"name\": \"total\", \"type_text\":"
                            + " \"DECIMAL(12,2)\", \"position\": 1 },\n"
                            + "                    { \"name\": \"active\", \"type_text\":"
                            + " \"BOOLEAN\", \"position\": 2 }\n"
                            + "                ] } },\n"
                            + "                \"result\": { \"data_array\":"
                            + " [ [\"east\", \"12.50\", \"true\"], [\"west\", null,"
                            + " \"false\"] ] }\n"
                            + "            }"));

            QueryResult out = new DatabricksEngine(conn(server))
                    .execute("SELECT region, total, active FROM orders");
            assertEquals(List.of("{\"region\":\"east\",\"total\":12.5,\"active\":true}",
                    "{\"region\":\"west\",\"total\":null,\"active\":false}"), out.rows());
        }
    }

    @Test
    void aFailedStatementReportsTheMessage() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements")),
                    r -> ok("{\"statement_id\": \"s2\", \"status\": { \"state\": \"FAILED\","
                            + " \"error\": { \"error_code\": \"BAD_REQUEST\", \"message\":"
                            + " \"TABLE_OR_VIEW_NOT_FOUND: nope\" } } }"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new DatabricksEngine(conn(server)).execute("SELECT * FROM nope"))
                            .getMessage());
            assertTrue(err.contains("TABLE_OR_VIEW_NOT_FOUND"), err);
        }
    }

    /** catalog 级那份读不到（真实集群回过 TABLE_OR_VIEW_NOT_FOUND）时退到 system 那份。 */
    @Test
    void theSchemaReadFallsBackToTheSystemInformationSchema() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub first = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("`main`.information_schema.columns")),
                    r -> failed("BAD_REQUEST",
                            "[TABLE_OR_VIEW_NOT_FOUND] The table or view `main`."
                                    + "`information_schema`.`columns` cannot be found."));
            ScriptedServer.Stub second = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("system.information_schema.columns")),
                    r -> ok("{\"statement_id\": \"s4\", \"status\": { \"state\":"
                            + " \"SUCCEEDED\" },\n"
                            + "                \"manifest\": { \"schema\": { \"columns\": [\n"
                            + "                    { \"name\": \"table_schema\","
                            + " \"type_text\": \"STRING\", \"position\": 0 },\n"
                            + "                    { \"name\": \"table_name\","
                            + " \"type_text\": \"STRING\", \"position\": 1 },\n"
                            + "                    { \"name\": \"column_name\","
                            + " \"type_text\": \"STRING\", \"position\": 2 },\n"
                            + "                    { \"name\": \"data_type\","
                            + " \"type_text\": \"STRING\", \"position\": 3 },\n"
                            + "                    { \"name\": \"comment\","
                            + " \"type_text\": \"STRING\", \"position\": 4 }\n"
                            + "                ] } },\n"
                            + "                \"result\": { \"data_array\": [\n"
                            + "                    [\"default\", \"orders\", \"amount\","
                            + " \"DECIMAL(12,2)\", \"订单金额\"]\n"
                            + "                ] }\n"
                            + "            }"));

            List<SchemaColumn> cols = new DatabricksEngine(conn(server)).fetchSchema();
            assertEquals(1, cols.size());
            assertEquals("default", cols.get(0).schema());
            assertEquals("orders", cols.get(0).table());
            assertEquals("amount", cols.get(0).column());
            assertEquals("DECIMAL(12,2)", cols.get(0).dataType());
            assertEquals("订单金额", cols.get(0).comment());
            assertEquals(1, first.hits());
            assertEquals(1, second.hits());
        }
    }

    /** 两条都不通时，错误带上每一条试过的写法和它自己的原话。 */
    @Test
    void twoDeadEndsSayWhichOnesWereTried() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("`main`.information_schema.columns")),
                    r -> failed("BAD_REQUEST", "[TABLE_OR_VIEW_NOT_FOUND] first words"));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("system.information_schema.columns")),
                    r -> failed("PERMISSION_DENIED", "second words"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new DatabricksEngine(conn(server)).fetchSchema()).getMessage());
            assertTrue(err.contains("catalog information_schema"), err);
            assertTrue(err.contains("first words"), err);
            assertTrue(err.contains("system information_schema"), err);
            assertTrue(err.contains("second words"), err);
        }
    }

    /** 令牌不对、仓库停着这类错误不是「找不到」：换一条语句也一样，只发一次。 */
    @Test
    void anErrorThatIsNotAMissingTableIsNotRetried() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub post = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements")),
                    r -> failed("PERMISSION_DENIED", "token lacks USE CATALOG"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new DatabricksEngine(conn(server)).fetchSchema()).getMessage());
            assertTrue(err.contains("token lacks USE CATALOG"), err);
            assertTrue(!err.contains("system information_schema"), err);
            assertEquals(1, post.hits());
        }
    }

    /**
     * 退到 system 那份读到 0 行：看不见这个 catalog，或它不归 Unity Catalog 管。
     * 这是失败，不是一份空的结构——否则刷新会把原来的结构文档覆盖成空的。
     */
    @Test
    void aFallbackThatReadsNoColumnsIsAFailure() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("`main`.information_schema.columns")),
                    r -> failed("BAD_REQUEST", "[TABLE_OR_VIEW_NOT_FOUND] cannot be found"));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("system.information_schema.columns")),
                    r -> ok("{\"statement_id\": \"s5\", \"status\": { \"state\":"
                            + " \"SUCCEEDED\" },\n"
                            + "                \"manifest\": { \"schema\": { \"columns\": [\n"
                            + "                    { \"name\": \"table_schema\","
                            + " \"type_text\": \"STRING\", \"position\": 0 }\n"
                            + "                ] } },\n"
                            + "                \"result\": { \"data_array\": [] }\n"
                            + "            }"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new DatabricksEngine(conn(server)).fetchSchema()).getMessage());
            assertTrue(err.contains("no columns for this catalog"), err);
            assertTrue(err.contains("cannot be found"), err);
        }
    }

    /**
     * 宽 catalog 上的 schema 读按 docs 走 INLINE 多分块：第一个 chunk 给
     * next_chunk_internal_link，按它 GET 到第二个 chunk，没有下一页就停。之前对所有
     * 语句都传 row_limit=201（#703），宽 catalog 上 information_schema.columns 一行一列，
     * 每张表第一个 200 列之后的列就被丢掉了。
     */
    @Test
    void aSchemaReadFollowsChunksUntilExhausted() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub post = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("`main`.information_schema.columns")),
                    r -> ok("{\"statement_id\": \"s_chunk1\", \"status\": { \"state\":"
                            + " \"SUCCEEDED\" },\n"
                            + "                \"manifest\": {\n"
                            + "                    \"schema\": { \"columns\": [\n"
                            + "                        { \"name\": \"table_schema\","
                            + " \"type_text\": \"STRING\", \"position\": 0 },\n"
                            + "                        { \"name\": \"table_name\","
                            + " \"type_text\": \"STRING\", \"position\": 1 },\n"
                            + "                        { \"name\": \"column_name\","
                            + " \"type_text\": \"STRING\", \"position\": 2 },\n"
                            + "                        { \"name\": \"data_type\","
                            + " \"type_text\": \"STRING\", \"position\": 3 },\n"
                            + "                        { \"name\": \"comment\","
                            + " \"type_text\": \"STRING\", \"position\": 4 }\n"
                            + "                    ] },\n"
                            + "                    \"total_row_count\": 3,\n"
                            + "                    \"total_chunk_count\": 3,\n"
                            + "                    \"truncated\": false\n"
                            + "                },\n"
                            + "                \"result\": {\n"
                            + "                    \"chunk_index\": 0, \"row_offset\": 0,"
                            + " \"row_count\": 1,\n"
                            + "                    \"next_chunk_index\": 1,\n"
                            + "                    \"next_chunk_internal_link\":"
                            + " \"/api/2.0/sql/statements/s_chunk1/result/chunks/1"
                            + "?row_offset=1&cursor=a%2Fb%2Bc\",\n"
                            + "                    \"data_array\": [[\"default\","
                            + " \"wide_orders\", \"id\", \"BIGINT\", \"主键\"]]\n"
                            + "                }\n"
                            + "            }"));
            ScriptedServer.Stub chunk1 = server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/2.0/sql/statements/s_chunk1"
                                    + "/result/chunks/1"),
                            ScriptedServer.queryParam("row_offset", "1"),
                            ScriptedServer.queryParam("cursor", "a/b+c"),
                            ScriptedServer.header("authorization", "Bearer dapi-test")),
                    r -> ok("{\n"
                            + "                    \"chunk_index\": 1, \"row_offset\": 1,"
                            + " \"row_count\": 1,\n"
                            + "                    \"next_chunk_index\": 2,\n"
                            + "                    \"next_chunk_internal_link\":"
                            + " \"/api/2.0/sql/statements/s_chunk1/result/chunks/2"
                            + "?row_offset=2\",\n"
                            + "                    \"data_array\": [[\"default\","
                            + " \"wide_orders\", \"region\", \"STRING\", null]]\n"
                            + "                }"));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/api/2.0/sql/statements/s_chunk1"
                                    + "/result/chunks/2"),
                            ScriptedServer.queryParam("row_offset", "2"),
                            ScriptedServer.header("authorization", "Bearer dapi-test")),
                    r -> ok("{\n"
                            + "                    \"chunk_index\": 2, \"row_offset\": 2,"
                            + " \"row_count\": 1,\n"
                            + "                    \"data_array\": [[\"default\","
                            + " \"wide_orders\", \"amount\", \"DECIMAL(12,2)\", null]]\n"
                            + "                }"));

            List<SchemaColumn> cols = new DatabricksEngine(conn(server)).fetchSchema();
            assertEquals(3, cols.size(), "want 3 columns across chunks, got " + cols);
            assertEquals("id", cols.get(0).column());
            assertEquals("region", cols.get(1).column());
            assertEquals("amount", cols.get(2).column());
            List<ScriptedServer.Recorded> requests = server.requests();
            assertTrue(requests.get(0).bodyJson().get("row_limit") == null
                            || requests.get(0).bodyJson().get("row_limit").isNull(),
                    "schema 读不传 row_limit（#703）");
            assertEquals("row_offset=1&cursor=a%2Fb%2Bc", requests.get(1).rawQuery());
            assertEquals(1, post.hits());
            assertEquals(1, chunk1.hits());
        }
    }

    @Test
    void aChatQueryReadsLaterChunksBeforeApplyingTheRowCap() throws Exception {
        for (int total : new int[]{1, 3, QueryEngines.ROW_CAP + 1}) {
            try (ScriptedServer server = ScriptedServer.start()) {
                ScriptedServer.Stub post = server.on(
                        ScriptedServer.all(ScriptedServer.method("POST"),
                                ScriptedServer.path("/api/2.0/sql/statements"),
                                ScriptedServer.bodyPartialJson(ScriptedServer.j(
                                        "{\"row_limit\": " + (QueryEngines.ROW_CAP + 1) + "}"))),
                        r -> ok("{\"statement_id\": \"s_chat\", \"status\": { \"state\":"
                                + " \"SUCCEEDED\" },\n"
                                + "                        \"manifest\": {\n"
                                + "                            \"schema\": { \"columns\":"
                                + " [{ \"name\": \"n\", \"type_text\": \"BIGINT\" }] },\n"
                                + "                            \"total_row_count\": " + total
                                + ",\n"
                                + "                            \"truncated\": false\n"
                                + "                        },\n"
                                + "                        \"result\": {\n"
                                + "                            \"data_array\": [[\"0\"]],\n"
                                + "                            \"next_chunk_internal_link\":"
                                + " \"/api/2.0/sql/statements/s_chat/result/chunks/1"
                                + "?row_offset=1\"\n"
                                + "                        }\n"
                                + "                    }"));
                com.fasterxml.jackson.databind.node.ArrayNode remaining =
                        HttpSupport.JSON.createArrayNode();
                for (int n = 1; n < total; n++) {
                    remaining.addArray().add(String.valueOf(n));
                }
                ScriptedServer.Stub chunk = server.on(
                        ScriptedServer.all(ScriptedServer.method("GET"),
                                ScriptedServer.path("/api/2.0/sql/statements/s_chat"
                                        + "/result/chunks/1"),
                                ScriptedServer.queryParam("row_offset", "1")),
                        r -> ok("{\n"
                                + "                        \"chunk_index\": 1,"
                                + " \"row_offset\": 1, \"row_count\": " + (total - 1) + ",\n"
                                + "                        \"data_array\": " + remaining
                                + "\n"
                                + "                    }"));
                QueryResult out = new DatabricksEngine(conn(server))
                        .execute("SELECT n FROM numbers");
                java.util.List<String> expected = new java.util.ArrayList<>();
                for (int n = 0; n < Math.min(total, QueryEngines.ROW_CAP); n++) {
                    expected.add("{\"n\":" + n + "}");
                }
                assertEquals(expected, out.rows());
                assertEquals(total > QueryEngines.ROW_CAP, out.truncated());
                assertEquals(1, post.hits());
                assertEquals(1, chunk.hits());
            }
        }
    }

    @Test
    void aLaterChunkFailureDoesNotReturnAPartialQuery() throws Exception {
        for (Object[] pair : new Object[][]{
                {503, "503"},
                {"{}", "data_array"},
                {"{ \"status\": { \"state\": \"SUCCEEDED\" }, \"result\":"
                        + " { \"data_array\": [[\"second\"]] } }", "data_array"},
        }) {
            try (ScriptedServer server = ScriptedServer.start()) {
                server.on(
                        ScriptedServer.all(ScriptedServer.method("POST"),
                                ScriptedServer.path("/api/2.0/sql/statements")),
                        r -> ok("{\"status\": { \"state\": \"SUCCEEDED\" },\n"
                                + "                    \"result\": {\n"
                                + "                        \"data_array\": [[\"first\"]],\n"
                                + "                        \"next_chunk_internal_link\":"
                                + " \"/api/2.0/sql/statements/s_failed/result/chunks/1\"\n"
                                + "                    }\n"
                                + "                }"));
                Object response = pair[0];
                ScriptedServer.Reply reply = response instanceof Integer status
                        ? new ScriptedServer.Reply(status, java.util.Map.of(), 0,
                                new byte[0])
                        : ok((String) response);
                server.on(
                        ScriptedServer.all(ScriptedServer.method("GET"),
                                ScriptedServer.path("/api/2.0/sql/statements/s_failed"
                                        + "/result/chunks/1")),
                        r -> reply);
                String error = String.valueOf(assertThrows(Exception.class,
                        () -> new DatabricksEngine(conn(server)).execute("SELECT n FROM"
                                + " numbers")).getMessage());
                assertTrue(error.contains((String) pair[1]), error);
            }
        }
    }

    @Test
    void anInternalChunkLinkCannotSendTheTokenToAnotherOrigin() throws Exception {
        try (ScriptedServer foreign = ScriptedServer.start()) {
            for (String link : new String[]{
                    foreign.uri() + "/result/chunks/1",
                    "//" + foreign.hostPort() + "/result/chunks/1",
            }) {
                try (ScriptedServer server = ScriptedServer.start()) {
                    server.on(
                            ScriptedServer.all(ScriptedServer.method("POST"),
                                    ScriptedServer.path("/api/2.0/sql/statements")),
                            r -> ok("{\"status\": { \"state\": \"SUCCEEDED\" },\n"
                                    + "                    \"result\": {\n"
                                    + "                        \"data_array\": [[\"first\"]],\n"
                                    + "                        \"next_chunk_internal_link\": \""
                                    + link + "\"\n"
                                    + "                    }\n"
                                    + "                }"));
                    String error = String.valueOf(assertThrows(Exception.class,
                            () -> new DatabricksEngine(conn(server))
                                    .execute("SELECT n FROM numbers")).getMessage());
                    assertTrue(error.contains("outside the workspace"), error);
                }
            }
            assertTrue(foreign.requests().isEmpty());
        }
    }

    /**
     * 跑 chat 查询时（带 row_limit），服务端真的把行砍了：manifest.truncated=true
     * 应当直接失败，而不是带着 &lt; ROW_CAP 行假装「查询成功」。
     */
    @Test
    void aChatQueryBailsLoudlyWhenTheServiceSaysTruncated() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements")),
                    r -> ok("{\"statement_id\": \"s_trunc\",\n"
                            + "                \"status\": { \"state\": \"SUCCEEDED\" },\n"
                            + "                \"manifest\": {\n"
                            + "                    \"schema\": { \"columns\": [\n"
                            + "                        { \"name\": \"n\", \"type_text\":"
                            + " \"BIGINT\", \"position\": 0 }\n"
                            + "                    ] },\n"
                            + "                    \"total_row_count\": 1000,\n"
                            + "                    \"truncated\": true\n"
                            + "                },\n"
                            + "                \"result\": { \"data_array\": [ [\"1\"],"
                            + " [\"2\"] ] }\n"
                            + "            }"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new DatabricksEngine(conn(server)).execute("SELECT n FROM big"))
                            .getMessage());
            assertTrue(err.contains("truncated"), err);
            assertTrue(err.contains("row_limit"), err);
        }
    }

    /**
     * schema 读 manifest 报的 total_row_count 跟实际收到的行数对不上——服务端偷偷改
     * protocol，或中途丢包：fail-fast，免得把残缺的 schema 灌进结构文档。
     */
    @Test
    void aSchemaReadFailsWhenTotalRowCountDoesNotMatch() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/api/2.0/sql/statements"),
                            ScriptedServer.bodyContains("`main`.information_schema.columns")),
                    r -> ok("{\"statement_id\": \"s_count_mismatch\",\n"
                            + "                \"status\": { \"state\": \"SUCCEEDED\" },\n"
                            + "                \"manifest\": {\n"
                            + "                    \"schema\": { \"columns\": [\n"
                            + "                        { \"name\": \"table_schema\","
                            + " \"type_text\": \"STRING\", \"position\": 0 },\n"
                            + "                        { \"name\": \"table_name\","
                            + " \"type_text\": \"STRING\", \"position\": 1 },\n"
                            + "                        { \"name\": \"column_name\","
                            + " \"type_text\": \"STRING\", \"position\": 2 },\n"
                            + "                        { \"name\": \"data_type\","
                            + " \"type_text\": \"STRING\", \"position\": 3 },\n"
                            + "                        { \"name\": \"comment\","
                            + " \"type_text\": \"STRING\", \"position\": 4 }\n"
                            + "                    ] },\n"
                            + "                    \"total_row_count\": 99\n"
                            + "                },\n"
                            + "                \"result\": { \"data_array\": [\n"
                            + "                    [\"default\", \"orders\", \"id\","
                            + " \"BIGINT\", null]\n"
                            + "                ] }\n"
                            + "            }"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> new DatabricksEngine(conn(server)).fetchSchema()).getMessage());
            assertTrue(err.contains("schema read returned 1 rows"), err);
            assertTrue(err.contains("99"), err);
        }
    }
}
