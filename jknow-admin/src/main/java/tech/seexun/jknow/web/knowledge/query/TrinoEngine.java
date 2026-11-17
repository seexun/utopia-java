package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trino（旧名 Presto，trino.rs 338 行对等）：REST 协议 POST /v1/statement，然后沿
 * nextUri 一页页取。一个引擎顶起整个湖仓——Iceberg/Delta/Hive/Hudi 都是它的 catalog，
 * 换格式不换协议。Starburst 同协议。
 *
 * <p>没有会话可设只读：超时靠 {@code X-Trino-Session: query_max_execution_time}，
 * 只读靠 {@link QueryEngines#guardSqlFor}——这是 HTTP 族比线协议少的那一层（如实声明）。
 */
public final class TrinoEngine implements QueryEngine {

    private final ConnParser.TrinoConn conn;

    public TrinoEngine(ConnParser.TrinoConn conn) {
        this.conn = conn;
    }

    /** trino.rs:48-73 headers：用户/来源/超时会话/目录与 schema，带密码时 Basic 认证。 */
    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Trino-User", conn.user());
        h.put("X-Trino-Source", "utopia");
        h.put("X-Trino-Session",
                "query_max_execution_time=" + QueryEngines.STATEMENT_TIMEOUT_SECS + "s");
        if (conn.catalog() != null) {
            h.put("X-Trino-Catalog", conn.catalog());
        }
        if (conn.schema() != null) {
            h.put("X-Trino-Schema", conn.schema());
        }
        if (conn.password() != null) {
            String token = Base64.getEncoder()
                    .encodeToString((conn.user() + ":" + conn.password())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            h.put("Authorization", "Basic " + token);
        }
        return h;
    }

    /** 一页（trino.rs:22-41 Page）。 */
    private record Page(String nextUri, List<String> columns, List<List<JsonNode>> data,
                        String errorMessage, String errorName) {
    }

    private static Page parsePage(JsonNode node) {
        JsonNode error = node.get("error");
        List<String> columns = new ArrayList<>();
        if (node.get("columns") != null && node.get("columns").isArray()) {
            for (JsonNode c : node.get("columns")) {
                columns.add(c.path("name").asText(null));
            }
        }
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
        return new Page(node.path("nextUri").asText(null), columns, data,
                error == null ? null : error.path("message").asText(null),
                error == null ? null : error.path("errorName").asText(null));
    }

    /**
     * 提交并沿 nextUri 收完（trino.rs:76-124 run）：列在第一个带 columns 的页上，
     * 数据分页累积；轮询预算 30s（HTTP_POLL_BUDGET）。
     */
    private RunResult run(String sql) throws Exception {
        HttpClient client = QueryEngines.http();
        Map<String, String> headers = headers();
        Map<String, String> postHeaders = new LinkedHashMap<>(headers);
        // 语句体是裸 SQL 文本（reqwest .body(String) 不设 Content-Type，同形）
        postHeaders.put("Content-Type", "text/plain");
        postHeaders.put("Accept-Encoding", "gzip");
        HttpSupport.Response first = HttpSupport.send(client, new HttpSupport.Call(
                conn.base() + "/v1/statement", "POST", postHeaders,
                sql.getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        if (first.status() / 100 != 2) {
            throw HttpSupport.httpStatusError(first);
        }
        Page page = parsePage(first.json());
        long started = System.nanoTime();
        List<String> columns = null;
        List<List<JsonNode>> rows = new ArrayList<>();
        while (true) {
            if (page.errorMessage() != null) {
                String name = page.errorName() == null ? "" : page.errorName() + ": ";
                throw new IllegalStateException(name + page.errorMessage());
            }
            if (columns == null && !page.columns().isEmpty()) {
                columns = page.columns();
            }
            rows.addAll(page.data());
            String next = page.nextUri();
            if (next == null) {
                break;
            }
            if (System.nanoTime() - started > QueryEngines.HTTP_POLL_BUDGET.toNanos()) {
                throw new IllegalStateException("Trino query did not finish within "
                        + QueryEngines.HTTP_POLL_BUDGET.toSeconds() + "s");
            }
            Map<String, String> getHeaders = new LinkedHashMap<>(headers);
            getHeaders.put("Accept-Encoding", "gzip");
            HttpSupport.Response response = HttpSupport.send(client,
                    new HttpSupport.Call(next, "GET", getHeaders, new byte[0], null));
            if (response.status() / 100 != 2) {
                throw HttpSupport.httpStatusError(response);
            }
            page = parsePage(response.json());
        }
        return new RunResult(columns == null ? List.of() : columns, rows);
    }

    private record RunResult(List<String> columns, List<List<JsonNode>> rows) {
    }

    @Override
    public void test() throws Exception {
        run("SELECT 1");
    }

    @Override
    public List<SchemaColumn> fetchSchema() throws Exception {
        // fetch_schema 打的是 "<catalog>".information_schema.columns——没有 catalog 就
        // 没得读（trino.rs:133-152）
        String catalog = conn.catalog();
        if (catalog == null) {
            throw new IllegalStateException("trino://: put the catalog in the connection string"
                    + " (trino://user@host/CATALOG) so the schema can be read");
        }
        String schemaFilter = conn.schema() == null ? ""
                : " AND table_schema = " + QueryEngines.sqlLiteral(conn.schema());
        String sql = "SELECT table_schema, table_name, column_name, data_type, comment"
                + "             FROM \"" + catalog.replace("\"", "\"\"")
                + "\".information_schema.columns"
                + "             WHERE table_schema <> 'information_schema'" + schemaFilter
                + "             ORDER BY table_schema, table_name, ordinal_position";
        RunResult out = run(sql);
        List<SchemaColumn> result = new ArrayList<>(out.rows().size());
        for (List<JsonNode> row : out.rows()) {
            result.add(schemaRow(row));
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

    /**
     * information_schema 的一行 → SchemaColumn（trino.rs:165-196 schema_row；值可能是
     * null，comment 常是）。
     *
     * <p>键在这里一律「不知道」（#502）。四个引擎各不相同，别照 SQL 标准去读：
     * <ul>
     *   <li>Trino/Presto：每个 catalog 的 information_schema 都由内建连接器提供，只有
     *       columns、tables、views、schemata 与权限、角色几张表，<b>没有</b>
     *       table_constraints/key_column_usage——查它们是 TABLE_NOT_FOUND（#682）；</li>
     *   <li>Snowflake：没有 key_column_usage，列级的键要 SHOW PRIMARY KEYS /
     *       SHOW IMPORTED KEYS；</li>
     *   <li>Databricks：Unity Catalog 在 information_schema.table_constraints /
     *       key_column_usage / referential_constraints 里登记信息性的主外键，读得到；</li>
     *   <li>ClickHouse：system.columns 有 is_in_primary_key，但主键是排序键的前缀，
     *       不保证唯一——MergeTree 照收主键相同的两行，标成键会让探索提示词拿一个
     *       会重复的列当 ID。</li>
     * </ul>
     */
    static SchemaColumn schemaRow(List<JsonNode> row) {
        String text = row.size() > 0 && row.get(0).isTextual() ? row.get(0).asText() : "";
        String table = row.size() > 1 && row.get(1).isTextual() ? row.get(1).asText() : "";
        String column = row.size() > 2 && row.get(2).isTextual() ? row.get(2).asText() : "";
        String dataType = row.size() > 3 && row.get(3).isTextual() ? row.get(3).asText() : "";
        JsonNode commentNode = row.size() > 4 ? row.get(4) : null;
        String comment = commentNode != null && commentNode.isTextual()
                && !commentNode.asText().isEmpty() ? commentNode.asText() : null;
        return new SchemaColumn(text, table, column, dataType, comment, false, null);
    }
}
