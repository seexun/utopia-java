package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snowflake SQL API v2（/api/v2/statements，snowflake.rs 494 行对等）。同步提交
 * （async=false）拿不完的语句回 202，沿 statementHandle 轮询。值全是字符串，按
 * rowType（{@link QueryEngines#coerce}）还原数与布尔。
 *
 * <p>只收令牌，不收密码：programmatic access token 或 OAuth（连接串 token_type 决定，
 * {@code X-Snowflake-Authorization-Token-Type} 头同名传递）。密钥对 JWT 要本地签名，
 * 这一版不做——见 {@link ConnParser.SnowflakeConn}。
 *
 * <p>选型记录（M5 任务 3 PoC，2026-11-13）：Snowflake JDBC（snowflake-jdbc 3.24+）确实
 * 支持 PAT（authenticator=PROGRAMMATIC_ACCESS_TOKEN + token 进 password 位）——但四家
 * HTTP 族的 47 例协议测试（wiremock 回放：202 轮询/partition 分块/降档重试/KILL 语义）
 * 断言的是我们自己的 HTTP 行为，JDBC 把协议封进驱动后这些断言无处落——照 Rust 手写
 * REST/HTTP 客户端（PoC 结论与选择记 docs/design/m3-test-ledger.md 任务 3 行）。
 */
public final class SnowflakeEngine implements QueryEngine {

    private final ConnParser.SnowflakeConn conn;

    public SnowflakeEngine(ConnParser.SnowflakeConn conn) {
        this.conn = conn;
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + conn.token());
        h.put("X-Snowflake-Authorization-Token-Type", conn.tokenType());
        h.put("Accept", "application/json");
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    private record RowType(String name, String type) {
    }

    private record PartitionInfo(long rowCount) {
    }

    private record StatementResponse(JsonNode meta, List<List<JsonNode>> data, String message,
                                     String code, String handle) {
    }

    private static StatementResponse parseResponse(JsonNode node) {
        List<List<JsonNode>> data = new ArrayList<>();
        if (node.get("data") != null && node.get("data").isArray()) {
            for (JsonNode row : node.get("data")) {
                List<JsonNode> values = new ArrayList<>();
                if (row.isArray()) {
                    row.forEach(values::add);
                }
                data.add(values);
            }
        }
        String handle = node.path("statementHandle").asText(null);
        return new StatementResponse(node.get("resultSetMetaData"), data,
                node.path("message").asText(null), node.path("code").asText(null), handle);
    }

    /**
     * 提交并收齐（snowflake.rs:68-192 run）：202=还在跑沿 handle 轮询；其余非 2xx 的
     * body 里带 message；partitionInfo 没有链接——首个分区在响应里，后续按
     * statementHandle + partition 序号取，沿用首块的 rowType 还原类型。numRows 是整个
     * 结果集的总数，分区 rowCount 之和也应该是它——任一对不上都说明服务端偷偷改
     * protocol 或中途丢包，残缺数据不要灌进结构文档。
     */
    private RunResult run(String sql) throws Exception {
        HttpClient client = QueryEngines.http();
        ObjectNode body = HttpSupport.JSON.createObjectNode();
        body.put("statement", sql);
        body.put("timeout", QueryEngines.STATEMENT_TIMEOUT_SECS);
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("MULTI_STATEMENT_COUNT", "1");
        for (String[] kv : new String[][]{
                {"database", conn.database()}, {"schema", conn.schema()},
                {"warehouse", conn.warehouse()}, {"role", conn.role()}}) {
            if (kv[1] != null) {
                body.put(kv[0], kv[1]);
            }
        }
        Map<String, String> headers = baseHeaders();
        HttpSupport.Response http = HttpSupport.send(client, new HttpSupport.Call(
                conn.base() + "/api/v2/statements?async=false", "POST", headers,
                HttpSupport.JSON.writeValueAsBytes(body), null));
        long started = System.nanoTime();
        // 202 = 还在跑；其余非 2xx 的 body 里带 message
        while (http.status() == 202) {
            StatementResponse partial = parseResponse(http.json());
            String handle = partial.handle();
            if (handle == null || handle.isEmpty()) {
                throw new IllegalStateException("Snowflake returned 202 without a statementHandle");
            }
            if (System.nanoTime() - started > QueryEngines.HTTP_POLL_BUDGET.toNanos()) {
                throw new IllegalStateException("Snowflake statement did not finish within "
                        + QueryEngines.HTTP_POLL_BUDGET.toSeconds() + "s");
            }
            Thread.sleep(1000);
            http = HttpSupport.send(client, new HttpSupport.Call(
                    conn.base() + "/api/v2/statements/" + handle, "GET", headers,
                    new byte[0], null));
        }
        if (http.status() / 100 != 2) {
            String text = http.text();
            String message;
            try {
                StatementResponse parsed = parseResponse(HttpSupport.JSON.readTree(text));
                message = parsed.message() != null ? parsed.message() : text;
            } catch (Exception e) {
                message = text;
            }
            throw new IllegalStateException("Snowflake " + http.status() + ": " + message);
        }
        StatementResponse resp = parseResponse(http.json());
        if (resp.code() != null && resp.message() != null && resp.meta() == null) {
            // 2xx 里也可能带业务错误码；090001 是 "statement executed successfully"
            if (!"090001".equals(resp.code())) {
                throw new IllegalStateException(
                        "Snowflake " + resp.code() + ": " + resp.message());
            }
        }
        List<RowType> types = new ArrayList<>();
        JsonNode meta = resp.meta();
        if (meta != null && meta.get("rowType") != null) {
            for (JsonNode t : meta.get("rowType")) {
                types.add(new RowType(t.path("name").asText(""), t.path("type").asText("")));
            }
        }
        List<PartitionInfo> partitions = new ArrayList<>();
        if (meta != null && meta.get("partitionInfo") != null) {
            for (JsonNode p : meta.get("partitionInfo")) {
                partitions.add(new PartitionInfo(p.path("rowCount").asLong(0)));
            }
        }
        Long expectedTotal = null;
        if (meta != null && meta.has("numRows") && !meta.path("numRows").isNull()) {
            expectedTotal = meta.path("numRows").asLong();
        }
        List<List<JsonNode>> rows = new ArrayList<>();
        for (List<JsonNode> row : resp.data()) {
            rows.add(coercedRow(row, types));
        }
        long seenPartitionRows = rows.size();
        if (partitions.size() > 1) {
            String handle = resp.handle();
            if (handle == null || handle.isEmpty()) {
                throw new IllegalStateException(
                        "Snowflake returned multiple partitions without a statementHandle");
            }
            for (int partition = 1; partition < partitions.size(); partition++) {
                HttpSupport.Response chunk = HttpSupport.send(client, new HttpSupport.Call(
                        conn.base() + "/api/v2/statements/" + handle + "?partition=" + partition,
                        "GET", headers, new byte[0], null));
                if (chunk.status() / 100 != 2) {
                    throw HttpSupport.httpStatusError(chunk);
                }
                StatementResponse chunkResp = parseResponse(chunk.json());
                seenPartitionRows = Math.addExact(seenPartitionRows, chunkResp.data().size());
                for (List<JsonNode> row : chunkResp.data()) {
                    rows.add(coercedRow(row, types));
                }
            }
        }
        if (expectedTotal != null && seenPartitionRows != expectedTotal) {
            throw new IllegalStateException("Snowflake schema read returned " + seenPartitionRows
                    + " rows across partitions but numRows reports " + expectedTotal);
        }
        long partitionTotal = 0;
        for (PartitionInfo p : partitions) {
            partitionTotal += p.rowCount();
        }
        if (!partitions.isEmpty() && partitionTotal != seenPartitionRows) {
            throw new IllegalStateException("Snowflake partition row counts sum to "
                    + partitionTotal + " but received " + seenPartitionRows + " rows");
        }
        List<String> names = new ArrayList<>(types.size());
        types.forEach(t -> names.add(t.name()));
        return new RunResult(names, rows);
    }

    private static List<JsonNode> coercedRow(List<JsonNode> row, List<RowType> types) {
        List<JsonNode> out = new ArrayList<>(row.size());
        for (int i = 0; i < row.size(); i++) {
            out.add(QueryEngines.coerce(i < types.size() ? types.get(i).type() : "", row.get(i)));
        }
        return out;
    }

    private record RunResult(List<String> columns, List<List<JsonNode>> rows) {
    }

    @Override
    public void test() throws Exception {
        run("SELECT 1");
    }

    @Override
    public List<SchemaColumn> fetchSchema() throws Exception {
        String database = conn.database();
        if (database == null) {
            throw new IllegalStateException("snowflake://: put the database in the connection"
                    + " string (snowflake://:TOKEN@account/DATABASE) so the schema can be read");
        }
        String schemaFilter = conn.schema() == null ? ""
                : " AND table_schema = " + QueryEngines.sqlLiteral(conn.schema());
        String sql = "SELECT table_schema, table_name, column_name, data_type, comment"
                + "             FROM \"" + database.replace("\"", "\"\"")
                + "\".information_schema.columns"
                + "             WHERE table_schema <> 'INFORMATION_SCHEMA'" + schemaFilter
                + "             ORDER BY table_schema, table_name, ordinal_position";
        RunResult out = run(sql);
        List<SchemaColumn> result = new ArrayList<>(out.rows().size());
        for (List<JsonNode> row : out.rows()) {
            result.add(TrinoEngine.schemaRow(row));
        }
        return result;
    }

    @Override
    public QueryResult execute(String sql) throws Exception {
        RunResult out = run(QueryEngines.wrapLimit(sql));
        var truncated = QueryEngines.truncateRows(out.rows());
        return new QueryResult(
                QueryEngines.rowsToJsonLines(out.columns(), truncated.rows(), HttpSupport.JSON),
                truncated.truncated());
    }
}
