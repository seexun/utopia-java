package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Databricks SQL Statement Execution API（/api/2.0/sql/statements，databricks.rs 850 行
 * 对等）。一个 SQL warehouse 后面是 Unity Catalog 的整个湖仓（Delta 为主），令牌是
 * personal access token。结果要 INLINE + JSON_ARRAY：值全是字符串，按 manifest 里的
 * 列类型（{@link QueryEngines#coerce}）还原成数与布尔。
 */
public final class DatabricksEngine implements QueryEngine {

    private final ConnParser.DatabricksConn conn;

    public DatabricksEngine(ConnParser.DatabricksConn conn) {
        this.conn = conn;
    }

    /** manifest 列（databricks.rs:51-60）。 */
    private record ColumnInfo(String name, String typeText) {
    }

    /** databricks.rs:77-112 schema_queries：information_schema.columns 的候选写法。没有 catalog 时只剩会话默认那份。 */
    private List<Map.Entry<String, String>> schemaQueries() {
        String schemaFilter = conn.schema() == null ? ""
                : " AND table_schema = " + QueryEngines.sqlLiteral(conn.schema());
        String select = "SELECT table_schema, table_name, column_name, data_type, comment";
        String tail = "table_schema <> 'information_schema'" + schemaFilter
                + "             ORDER BY table_schema, table_name, ordinal_position";
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (conn.catalog() != null) {
            out.add(Map.entry("catalog information_schema",
                    select + " FROM `" + conn.catalog().replace("`", "``")
                            + "`.information_schema.columns WHERE " + tail));
            out.add(Map.entry("system information_schema",
                    select + " FROM system.information_schema.columns"
                            + "                         WHERE table_catalog = "
                            + QueryEngines.sqlLiteral(conn.catalog()) + " AND " + tail));
        } else {
            out.add(Map.entry("session information_schema",
                    select + " FROM information_schema.columns WHERE " + tail));
        }
        return out;
    }

    /**
     * 提交一条语句，轮询到 SUCCEEDED，把结果拼成（列名, 行值）（databricks.rs:126-283 run）。
     * 所有列值都按 manifest 的 type_text 还原成数或布尔。
     *
     * <p>rowLimit：null 表示不限行（schema 读用）。INLINE 的 JSON_ARRAY 结果可能分成多块；
     * 首块之后通过 next_chunk_internal_link 继续 GET，循环到 result 没有给出下一页为止。
     * schema 读完后用 manifest.total_row_count 做一次行数校验——少了就当结果不全。
     *
     * <p>chat 查询传 rowLimit=ROW_CAP+1（#703）：外包 LIMIT 是 SQL 层的安全阀，row_limit
     * 是服务端的另一道；schema 读不传——information_schema.columns 每行是一列，宽 catalog
     * 上的 schema 文档会被悄悄截到 201 行。
     */
    private RunResult run(String sql, Integer rowLimit) throws Exception {
        HttpClient client = QueryEngines.http();
        ObjectNode body = HttpSupport.JSON.createObjectNode();
        body.put("warehouse_id", conn.warehouseId());
        body.put("statement", sql);
        body.put("wait_timeout", "30s");
        body.put("on_wait_timeout", "CONTINUE");
        body.put("disposition", "INLINE");
        body.put("format", "JSON_ARRAY");
        if (rowLimit != null) {
            body.put("row_limit", rowLimit);
        }
        if (conn.catalog() != null) {
            body.put("catalog", conn.catalog());
        }
        if (conn.schema() != null) {
            body.put("schema", conn.schema());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + conn.token());
        headers.put("Accept-Encoding", "gzip");
        HttpSupport.Response response = HttpSupport.send(client, new HttpSupport.Call(
                conn.base() + "/api/2.0/sql/statements", "POST", headers,
                HttpSupport.JSON.writeValueAsBytes(body), null));
        if (response.status() / 100 != 2) {
            throw HttpSupport.httpStatusError(response);
        }
        JsonNode resp = response.json();
        long started = System.nanoTime();
        while (true) {
            String state = resp.path("status").path("state").asText("");
            if ("SUCCEEDED".equals(state)) {
                break;
            }
            if ("PENDING".equals(state) || "RUNNING".equals(state)) {
                String id = resp.path("statement_id").asText(null);
                if (id == null || id.isEmpty()) {
                    throw new IllegalStateException("Databricks returned no statement_id");
                }
                if (System.nanoTime() - started > QueryEngines.HTTP_POLL_BUDGET.toNanos()) {
                    throw new IllegalStateException("Databricks statement did not finish within "
                            + QueryEngines.HTTP_POLL_BUDGET.toSeconds() + "s");
                }
                Thread.sleep(1000);
                HttpSupport.Response poll = HttpSupport.send(client, new HttpSupport.Call(
                        conn.base() + "/api/2.0/sql/statements/" + id, "GET", headers,
                        new byte[0], null));
                if (poll.status() / 100 != 2) {
                    throw HttpSupport.httpStatusError(poll);
                }
                resp = poll.json();
                continue;
            }
            JsonNode error = resp.path("status").path("error");
            String code = error.path("error_code").asText(null);
            String message = error.path("message").asText(null);
            if (message == null || message.isEmpty()) {
                message = "statement ended in state " + state;
            }
            throw new IllegalStateException(
                    (code == null || code.isEmpty() ? "" : code + ": ") + message);
        }
        List<ColumnInfo> columns = new ArrayList<>();
        for (JsonNode c : resp.path("manifest").path("schema").path("columns")) {
            JsonNode t = c.get("type_text");
            columns.add(new ColumnInfo(c.path("name").asText(""),
                    t == null || t.isNull() ? null : t.asText()));
        }
        List<List<JsonNode>> rows = new ArrayList<>();
        for (JsonNode row : resp.path("result").path("data_array")) {
            rows.add(coercedRow(row, columns));
        }
        JsonNode manifest = resp.path("manifest");
        if (manifest.path("truncated").asBoolean(false)) {
            // manifest.truncated=true 是服务端真把行砍了：直接失败，而不是带着 < ROW_CAP
            // 行假装「查询成功」
            throw new IllegalStateException("Databricks returned a truncated result;"
                    + " row_limit must be raised or the query rewritten");
        }
        JsonNode nextLink = resp.path("result").path("next_chunk_internal_link");
        URI base = URI.create(conn.base());
        while (nextLink != null && !nextLink.isNull() && !nextLink.asText().isEmpty()) {
            // internal_link 是相对于 workspace 的路径，不是可直接发的 URL。解析后还要
            // 留在同一 workspace，才可带着它的 bearer token 请求
            URI url = base.resolve(nextLink.asText());
            if (!sameOrigin(base, url)) {
                throw new IllegalStateException(
                        "Databricks chunk link points outside the workspace");
            }
            HttpSupport.Response chunk = HttpSupport.send(client, new HttpSupport.Call(
                    url.toString(), "GET", headers, new byte[0], null));
            if (chunk.status() / 100 != 2) {
                throw HttpSupport.httpStatusError(chunk);
            }
            JsonNode chunkNode = chunk.json();
            JsonNode chunkRows = chunkNode.get("data_array");
            if (chunkRows == null || chunkRows.isNull()) {
                throw new IllegalStateException("Databricks result chunk returned no data_array");
            }
            for (JsonNode row : chunkRows) {
                rows.add(coercedRow(row, columns));
            }
            nextLink = chunkNode.path("next_chunk_internal_link");
        }
        // schema 读按 docs 不该见到 truncated=true；total_row_count 每次都给，校验要放在
        // 所有 chunk 都收齐之后：第一次响应给的 total 是整个结果集的总数，不是第一个
        // chunk 的总数
        if (rowLimit == null && manifest.has("total_row_count")
                && !manifest.path("total_row_count").isNull()) {
            long expected = manifest.path("total_row_count").asLong();
            if (rows.size() != expected) {
                throw new IllegalStateException("Databricks schema read returned " + rows.size()
                        + " rows but manifest reports " + expected);
            }
        }
        List<String> names = new ArrayList<>(columns.size());
        columns.forEach(c -> names.add(c.name()));
        return new RunResult(names, rows);
    }

    private static List<JsonNode> coercedRow(JsonNode row, List<ColumnInfo> columns) {
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            String ty = i < columns.size() && columns.get(i).typeText() != null
                    ? columns.get(i).typeText() : "";
            out.add(QueryEngines.coerce(ty, row.get(i)));
        }
        return out;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && a.getPort() == b.getPort();
    }

    private record RunResult(List<String> columns, List<List<JsonNode>> rows) {
    }

    /**
     * 引擎说的是「这个 catalog/表不存在（或看不见）」。认的是 Databricks 的错误类名
     * （databricks.rs:286-297）——它们是协议的一部分（[TABLE_OR_VIEW_NOT_FOUND] …），
     * 不是措辞。
     */
    private static boolean isNotFound(Throwable e) {
        String text = String.valueOf(e.getMessage());
        return text.contains("TABLE_OR_VIEW_NOT_FOUND")
                || text.contains("NO_SUCH_CATALOG_EXCEPTION")
                || text.contains("CATALOG_NOT_FOUND")
                || text.contains("SCHEMA_NOT_FOUND");
    }

    @Override
    public void test() throws Exception {
        run("SELECT 1", QueryEngines.ROW_CAP + 1);
    }

    @Override
    public List<SchemaColumn> fetchSchema() throws Exception {
        // 两条候选，先准后全（databricks.rs:305-342）。真实集群上 `main`.information_schema.
        // columns 回过 TABLE_OR_VIEW_NOT_FOUND（#241），所以第一条**说找不到**时退到 system
        // 那份。只有「找不到」才退：令牌不对、仓库停着、超时，换一条语句也还是这样，再跑
        // 一遍只是让点按钮的人多等半分钟。退过去读到 0 行也算失败——两份 information_schema
        // 都只列令牌看得见的对象，system 那份还不含 hive_metastore，看不见的 catalog 在
        // 那里是「成功地没有行」；当成功的话，结构文档会被一份空的覆盖掉。失败时每条试过
        // 的写法连同引擎的原话都带上
        List<Map.Entry<String, String>> queries = schemaQueries();
        List<String> tried = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            String label = queries.get(i).getKey();
            String sql = queries.get(i).getValue();
            try {
                RunResult out = run(sql, null);
                if (out.rows().isEmpty() && i > 0) {
                    tried.add(label + ": no columns for this catalog — the token cannot see it,"
                            + " or it is not managed by Unity Catalog");
                    break;
                }
                List<SchemaColumn> result = new ArrayList<>(out.rows().size());
                for (List<JsonNode> row : out.rows()) {
                    result.add(TrinoEngine.schemaRow(row));
                }
                return result;
            } catch (Exception e) {
                boolean missing = isNotFound(e);
                tried.add(label + ": " + e.getMessage());
                if (!missing) {
                    break;
                }
            }
        }
        throw new IllegalStateException(
                "no readable information_schema (" + String.join("; ", tried) + ")");
    }

    @Override
    public QueryResult execute(String sql) throws Exception {
        RunResult out = run(QueryEngines.wrapLimit(sql), QueryEngines.ROW_CAP + 1);
        var truncated = QueryEngines.truncateRows(out.rows());
        return new QueryResult(
                QueryEngines.rowsToJsonLines(out.columns(), truncated.rows(), HttpSupport.JSON),
                truncated.truncated());
    }
}
