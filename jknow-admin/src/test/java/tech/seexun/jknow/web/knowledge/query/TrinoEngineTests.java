package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.query.ConnParser.TrinoConn;

/**
 * 移植自 query_engine/trino.rs 测试 3 例（断言逐字不改写）：wiremock 回放两例证协议
 * 分页与列序（Java 侧 ScriptedServer 同角色）；真集群一例沿 Rust 环境门控
 * （JKNOW_TEST_TRINO_URL，无则静默过——Rust 同档）。
 */
class TrinoEngineTests {

    private static TrinoConn conn(ScriptedServer server, String path) {
        return TrinoConn.parse("trino://alice@" + server.hostPort() + path + "?ssl=false");
    }

    @Test
    void followsNextUriAndKeepsColumnOrder() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/v1/statement"),
                            ScriptedServer.header("X-Trino-User", "alice"),
                            ScriptedServer.header("X-Trino-Catalog", "hive"),
                            ScriptedServer.bodyContains("LIMIT 201")),
                    r -> {
                        ObjectNode page = HttpSupport.JSON.createObjectNode();
                        page.put("id", "q1");
                        page.put("nextUri", server.uri() + "/v1/statement/q1/1");
                        page.putObject("stats").put("state", "QUEUED");
                        return ScriptedServer.Reply.json(page);
                    });
            server.on(
                    ScriptedServer.all(ScriptedServer.method("GET"),
                            ScriptedServer.path("/v1/statement/q1/1")),
                    r -> {
                        ObjectNode page = HttpSupport.JSON.createObjectNode();
                        page.put("id", "q1");
                        com.fasterxml.jackson.databind.node.ArrayNode columns =
                                page.putArray("columns");
                        columns.addObject().put("name", "region").put("type", "varchar");
                        columns.addObject().put("name", "total").put("type", "double");
                        com.fasterxml.jackson.databind.node.ArrayNode data = page.putArray("data");
                        data.addArray().add("east").add(12.5);
                        data.addArray().add("west").add(3);
                        page.putObject("stats").put("state", "FINISHED");
                        return ScriptedServer.Reply.json(page);
                    });

            QueryResult out = new TrinoEngine(conn(server, "/hive/default"))
                    .execute("SELECT region, total FROM orders");
            assertEquals(List.of("{\"region\":\"east\",\"total\":12.5}",
                    "{\"region\":\"west\",\"total\":3}"), out.rows());
            assertFalse(out.truncated());
        }
    }

    @Test
    void aTrinoErrorPageBecomesAnError() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.path("/v1/statement")),
                    r -> {
                        ObjectNode page = HttpSupport.JSON.createObjectNode();
                        ObjectNode error = page.putObject("error");
                        error.put("message",
                                "line 1:8: Table 'hive.default.nope' does not exist");
                        error.put("errorName", "TABLE_NOT_FOUND");
                        page.putObject("stats").put("state", "FAILED");
                        return ScriptedServer.Reply.json(page);
                    });
            Exception err = assertThrows(Exception.class,
                    () -> new TrinoEngine(conn(server, "/hive"))
                            .execute("SELECT * FROM nope"));
            assertTrue(String.valueOf(err.getMessage()).contains("TABLE_NOT_FOUND"),
                    String.valueOf(err.getMessage()));
        }
    }

    /**
     * 对着真 Trino 跑的那一档（trino.rs live_tests）。没有 JKNOW_TEST_TRINO_URL 就跳过
     * ——脚本回放证得了协议分页与列序，证不了「真集群的 information_schema 长这样、REST
     * 一页页取回来的值解得对」。这两样只有真服务器有答案。起一个来跑：
     * {@code docker run -d -p 8080:8080 trinodb/trino}，然后
     * {@code JKNOW_TEST_TRINO_URL=trino://probe@127.0.0.1:8080/tpch/tiny}。
     */
    @Test
    void aLiveClusterReadsSchemaAndAnswers() throws Exception {
        String url = System.getenv("JKNOW_TEST_TRINO_URL");
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        TrinoEngine engine = new TrinoEngine(TrinoConn.parse(url));
        engine.test();

        // fetch_schema 打的是 "<catalog>".information_schema.columns——列名与写法只有真
        // 集群能证。schema 段限定了范围（tpch 有许多 sfN，只取 tiny）
        List<SchemaColumn> schema = engine.fetchSchema();
        SchemaColumn name = schema.stream()
                .filter(c -> c.table().equals("region") && c.column().equals("name"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tpch.tiny.region.name in"
                        + " information_schema"));
        assertEquals("tiny", name.schema(), "schema 段应被限定");
        assertTrue(name.dataType().startsWith("varchar"),
                "data_type 要给出真形态，拿到的是 " + name.dataType());

        // 取值往返：QUEUED → nextUri 一页页取，直到拿到 data。tpch.tiny.region 是
        // TPC-H 标准表，五行、内容固定，正好当判据
        QueryResult r = engine.execute(
                "SELECT regionkey, name FROM tpch.tiny.region ORDER BY regionkey");
        assertEquals(5, r.rows().size(), "region 有五行");
        assertEquals(0, HttpSupport.JSON.readTree(r.rows().get(0)).path("regionkey").asInt());
        assertEquals("AFRICA", HttpSupport.JSON.readTree(r.rows().get(0)).path("name").asText());
        assertEquals("MIDDLE EAST",
                HttpSupport.JSON.readTree(r.rows().get(4)).path("name").asText());

        // 写路径仍被闸挡住（第 1 层），与 mysql 那档对称
        assertTrue(assertThrows(Exception.class,
                () -> QueryEngines.guardSqlFor("trino", "DROP TABLE tpch.tiny.region"))
                        .getMessage().startsWith("Read-only:"));
    }
}
