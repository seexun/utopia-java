package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 引擎无关的安全闸与共用件（query_engine/mod.rs 全量对等）。
 *
 * <p><b>四层纵深防御，每层的威胁模型</b>（不信任模型——它可能被提示注入诱导写库）：
 * <ol>
 *   <li><b>解析白名单</b>（{@link #guardSqlFor}，JSqlParser 对等 Rust sqlparser 0.62）：
 *       仅放行<b>单条</b> SELECT/WITH（含 CTE），拒绝 DML/DDL/多语句。按引擎选方言——
 *       JSqlParser 是单一语法，反引号（Databricks/MySQL）、:: 转型（Snowflake）、
 *       ClickHouse 的 FINAL/SETTINGS 一体认得（PoC 5.4，2026-11-13）；Rust 侧
 *       「sqlparser 没有 Trino 方言，Generic 是它的超集」在 Java 侧对应「单一语法
 *       即 Trino 超集」——同一策略；</li>
 *   <li><b>强制外包一层 LIMIT</b>（{@link #wrapLimit}）：cap+1 探测截断——模型要求
 *       「全部行」也拿不走超过 ROW_CAP 行；</li>
 *   <li><b>会话级只读 + 语句超时</b>（各引擎自己的机制——parser 万一漏网也写不进去、
 *       跑不死库）。PG：SET default_transaction_read_only + statement_timeout；
 *       MySQL：SET SESSION TRANSACTION READ ONLY + max_execution_time /
 *       max_statement_time 双写法；ClickHouse：每请求自带 readonly=1 + max_execution_time，
 *       被拒降档重试（见 {@link ClickHouseEngine}）。HTTP 族（Trino/Databricks/Snowflake）
 *       没有会话，只有语句超时——只读靠第 1 层，这是它们比线协议少的那一层（如实声明）；</li>
 *   <li><b>结果统一 JSON Lines 保列序</b>：PG 让库自己转（row_to_json）；其余引擎拿到
 *       列名与值后 {@link #rowsToJsonLines} 手拼——不用会按键排序的序列化器，
 *       列序是查询写下的顺序，模型读表靠它。</li>
 * </ol>
 */
public final class QueryEngines {

    /** 行数上限（外包 LIMIT cap+1，第 201 行只用来判断截断——mod.rs:37-38）。 */
    public static final int ROW_CAP = 200;

    /** mod.rs:39 STATEMENT_TIMEOUT_SECS：语句超时（各引擎写法不同，值同一）。 */
    public static final int STATEMENT_TIMEOUT_SECS = 10;

    /** mod.rs:41 HTTP_REQUEST_TIMEOUT：HTTP 族单次请求的超时。 */
    public static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** mod.rs:42 HTTP_POLL_BUDGET：整条语句从提交到拿完结果的轮询预算。 */
    public static final Duration HTTP_POLL_BUDGET = Duration.ofSeconds(30);

    /** 注册表里 engine 列的取值（迁移 0020 的 CHECK 与这张表一致）。 */
    public static final List<String> ENGINES =
            List.of("postgres", "mysql", "trino", "databricks", "snowflake", "clickhouse");

    private QueryEngines() {
    }

    /**
     * scheme → 引擎名（mod.rs:92-105 engine_from_conn）。界面上每档引擎的表单最后都拼成
     * 一条连接串（dsSpecs），这里是它唯一的分派点。mysql 一条线协议顺带覆盖
     * TiDB/OceanBase/Doris/StarRocks——它们都说 MySQL 协议，连接串照写 mysql:// 即可。
     */
    public static String engineFromConn(String conn) {
        String trimmed = conn.trim();
        int cut = trimmed.indexOf("://");
        if (cut < 0) {
            return null;
        }
        String scheme = trimmed.substring(0, cut).toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "postgres", "postgresql" -> "postgres";
            case "mysql", "mariadb" -> "mysql";
            case "trino", "presto" -> "trino";
            case "databricks" -> "databricks";
            case "snowflake" -> "snowflake";
            case "clickhouse" -> "clickhouse";
            default -> null;
        };
    }

    /** 引擎工厂（mod.rs:108-126 engine_for）。conn 凭据只在服务端流转。 */
    public static QueryEngine engineFor(String engine, String conn) {
        return switch (engine) {
            case "postgres" -> new PostgresEngine(conn);
            case "mysql" -> new MysqlEngine(conn);
            case "trino" -> new TrinoEngine(ConnParser.TrinoConn.parse(conn));
            case "databricks" -> new DatabricksEngine(ConnParser.DatabricksConn.parse(conn));
            case "snowflake" -> new SnowflakeEngine(ConnParser.SnowflakeConn.parse(conn));
            case "clickhouse" -> new ClickHouseEngine(ConnParser.ClickHouseConn.parse(conn));
            default -> throw new IllegalArgumentException("Unsupported engine: " + engine);
        };
    }

    /**
     * 安全闸第 1 层（mod.rs:129-153 guard_sql_for）：解析并校验，返回规整后的语句文本。
     * 多语句靠 {@code parseStatements} 的计数拒——JSqlParser 的单语句 parse 会静默停在
     * 第一条上（PoC 实测），计数才是「Exactly one statement」的真判据。
     */
    public static String guardSqlFor(String engine, String sql) {
        // 与 Rust trim().trim_end_matches(';').trim() 同形：剥掉全部尾分号再判空
        String cleaned = sql.trim();
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty SQL");
        }
        net.sf.jsqlparser.statement.Statements statements;
        try {
            statements = net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(cleaned);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage()).split("\n")[0];
            throw new IllegalArgumentException("SQL parse error: " + message, e);
        }
        if (statements.size() != 1) {
            throw new IllegalArgumentException("Exactly one statement is allowed");
        }
        net.sf.jsqlparser.statement.Statement first = statements.get(0);
        // SELECT/WITH（含 CTE）都解析成 Select；方言差异（反引号/::/FINAL/SETTINGS）
        // 由单一语法一体认得，六引擎同一道闸——与 Rust「Generic 是 Trino 超集」同策略
        if (first instanceof net.sf.jsqlparser.statement.select.Select) {
            return cleaned;
        }
        throw new IllegalArgumentException(
                "Read-only: only SELECT/WITH queries are allowed (got " + statementKind(first) + ")");
    }

    /** mod.rs:155-166 statement_kind：拒绝信息里点名是哪一种写操作（帮人改 SQL）。 */
    private static String statementKind(net.sf.jsqlparser.statement.Statement s) {
        if (s instanceof net.sf.jsqlparser.statement.insert.Insert) {
            return "INSERT";
        }
        if (s instanceof net.sf.jsqlparser.statement.update.Update) {
            return "UPDATE";
        }
        if (s instanceof net.sf.jsqlparser.statement.delete.Delete) {
            return "DELETE";
        }
        if (s instanceof net.sf.jsqlparser.statement.create.table.CreateTable) {
            return "CREATE TABLE";
        }
        if (s instanceof net.sf.jsqlparser.statement.drop.Drop) {
            return "DROP";
        }
        if (s instanceof net.sf.jsqlparser.statement.alter.Alter) {
            return "ALTER TABLE";
        }
        if (s instanceof net.sf.jsqlparser.statement.truncate.Truncate) {
            return "TRUNCATE";
        }
        return "a non-SELECT statement";
    }

    /** 第 2 层（mod.rs:169-171 wrap_limit）：外包一层 LIMIT。HTTP 族的引擎都认这个写法；PG 有自己的 row_to_json 版本。 */
    public static String wrapLimit(String sql) {
        return "SELECT * FROM ( " + sql + " ) AS _q LIMIT " + (ROW_CAP + 1);
    }

    /** 第 201 行只用来判断截断，不交给模型（mod.rs:174-178 truncate_rows）。 */
    public static <T> Truncated<T> truncateRows(List<T> rows) {
        boolean truncated = rows.size() > ROW_CAP;
        return new Truncated<>(rows.subList(0, Math.min(rows.size(), ROW_CAP)), truncated);
    }

    /** truncateRows 的返回形状。 */
    public record Truncated<T>(List<T> rows, boolean truncated) {
    }

    /**
     * HTTP 族共用：「列名 + 行值」拼成 JSON Lines（mod.rs:182-202 rows_to_json_lines）。
     * 手拼而不是 ObjectNode——后者按键排序，而列序是查询写下的顺序，模型读表靠它。
     * 值的序列化走 Jackson 紧凑形（serde_json::to_string 同形：整数不带小数点、
     * 字符串按 JSON 转义、null 字面量）。
     */
    public static List<String> rowsToJsonLines(List<String> columns, List<List<JsonNode>> rows,
                                               com.fasterxml.jackson.databind.ObjectMapper json) {
        List<String> out = new ArrayList<>(rows.size());
        for (List<JsonNode> row : rows) {
            StringBuilder line = new StringBuilder("{");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    line.append(',');
                }
                // serde_json::to_string(col).unwrap_or("\"?\"")：列名序列化不会失败，兜底照抄
                try {
                    line.append(json.writeValueAsString(columns.get(i)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    line.append("\"?\"");
                }
                line.append(':');
                JsonNode value = i < row.size() ? row.get(i) : NullNode.getInstance();
                line.append(value.toString());
            }
            line.append('}');
            out.add(line.toString());
        }
        return out;
    }

    /** Databricks/Snowflake/ClickHouse 数值列档位表（mod.rs:210-215 coerce 的 NUMERIC）。 */
    private static final String[] NUMERIC = {
            "INT", "LONG", "SHORT", "BYTE", "FLOAT", "DOUBLE", "DECIMAL", "NUMBER", "FIXED",
            "REAL", "NUMERIC",
    };

    /**
     * Databricks 的 JSON_ARRAY 与 Snowflake 的 data 把每个值都给成字符串（或 null），
     * ClickHouse 的 JSONCompactStrings 只把浮点与布尔交给这里。按列类型把数与布尔还原，
     * 其余留字符串——模型对 "42" 和 42 的算术不一样（mod.rs:208-236 coerce）。
     * INTERVAL 也含 "INT"：解析不成数就原样留下，不会误伤。
     */
    public static JsonNode coerce(String typeName, JsonNode raw) {
        if (!(raw instanceof TextNode textNode)) {
            return raw;
        }
        String s = textNode.textValue();
        String ty = typeName.toUpperCase(Locale.ROOT);
        boolean numeric = false;
        for (String key : NUMERIC) {
            if (ty.contains(key)) {
                numeric = true;
                break;
            }
        }
        if (numeric) {
            try {
                return new LongNode(Long.parseLong(s));
            } catch (NumberFormatException notIntegral) {
                try {
                    double f = Double.parseDouble(s);
                    if (!Double.isNaN(f) && !Double.isInfinite(f)) {
                        return new DoubleNode(f);
                    }
                } catch (NumberFormatException notANumber) {
                    // 原样留下（serde_json::Number::from_f64 None 的同档：交给 raw）
                }
            }
        }
        if (ty.startsWith("BOOL")) {
            if ("true".equals(s) || "TRUE".equals(s)) {
                return com.fasterxml.jackson.databind.node.BooleanNode.TRUE;
            }
            if ("false".equals(s) || "FALSE".equals(s)) {
                return com.fasterxml.jackson.databind.node.BooleanNode.FALSE;
            }
        }
        return raw;
    }

    /** 单引号字面量的转义（mod.rs:239-241 sql_literal）：schema 名进 information_schema 的 WHERE 子句。 */
    public static String sqlLiteral(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------
    // HTTP 族共用的客户端（mod.rs:243-300 http/http_builder/proxy_for/no_proxy_matches）
    // ------------------------------------------------------------------

    /**
     * HTTP 族共用的客户端（mod.rs:249-259 http()）。**代理策略是显式的**：回环地址与
     * NO_PROXY 里的主机直连，其余按 HTTPS_PROXY/HTTP_PROXY/ALL_PROXY 走——服务进程
     * 该看环境变量，这条规矩与 docker-compose 里的写法一致。不用 JDK 默认
     * ProxySelector（读的是系统属性而非环境变量）也不做系统代理探测：Windows 注册表里
     * 127.* 这种绕过写法认不全，本机的替身服务会被送进代理拿回 502（Rust 侧同一理由）。
     *
     * <p>请求级超时（20s）由各引擎在 {@link java.net.http.HttpRequest.Builder#timeout} 落
     * （JDK HttpClient 无客户端级请求超时）；重定向跟随 NORMAL 对齐 reqwest 缺省
     * （最多 10 跳）。协议钉 HTTP/1.1：reqwest 开了 http2 特性也只在 TLS（ALPN）上议
     * h2，明文 http 走 HTTP/1.1——JDK 缺省在明文上发 h2c 升级请求（升级悬决期间不发
     * 请求体），四家引擎的端点都是 HTTP/1.1，钉死与 reqwest 同形。
     * gzip：reqwest 开了 gzip 特性自动解压——JDK 客户端不会，各引擎显式带
     * Accept-Encoding 并按 Content-Encoding 解（{@link HttpSupport}）。
     */
    public static java.net.http.HttpClient http() {
        return java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .proxy(new UtopiaProxySelector())
                .build();
    }

    /**
     * mod.rs:261-300 proxy_for + no_proxy_matches 的 Java 对等：ProxySelector 按请求
     * URI 的 scheme/host 决策——loopback 或 NO_PROXY 命中 → DIRECT；否则 https 走
     * HTTPS_PROXY/https_proxy/ALL_PROXY/all_proxy，http 走 HTTP_PROXY/http_proxy/ALL_PROXY/
     * all_proxy（先设先用）；都没设 → DIRECT。
     */
    static final class UtopiaProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host == null || isLoopback(host) || noProxyMatches(host)) {
                return List.of(Proxy.NO_PROXY);
            }
            String[] keys = "https".equalsIgnoreCase(uri.getScheme())
                    ? new String[]{"HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"}
                    : new String[]{"HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"};
            for (String key : keys) {
                String value = System.getenv(key);
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                Proxy proxy = parseProxy(value.trim());
                if (proxy != null) {
                    return List.of(proxy);
                }
            }
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
            // 与 reqwest 的 custom proxy 同：失败交给客户端自身的重试/报错路径
        }

        private static boolean isLoopback(String host) {
            if ("localhost".equalsIgnoreCase(host)) {
                return true;
            }
            // 先按 IP 字面量判（Rust 只认字面量——审查 M-1：对任意主机名做 getByName
            // 会引入阻塞 DNS 解析，且域名解析到 loopback 时直连/代理决策与 Rust 相反）
            String h = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1) : host;
            boolean literal = h.indexOf(':') >= 0 || (h.chars().filter(c -> c == '.').count() == 3
                    && h.chars().allMatch(c -> Character.isDigit(c) || c == '.'));
            if (!literal) {
                return false;
            }
            try {
                return InetAddress.getByName(h).isLoopbackAddress();
            } catch (Exception e) {
                return false;
            }
        }

        /** NO_PROXY=localhost,127.0.0.1,.internal,corp.example：整名相等，或以点开头的后缀匹配；"*" 全直连。 */
        private static boolean noProxyMatches(String host) {
            String raw = System.getenv("NO_PROXY");
            if (raw == null) {
                raw = System.getenv("no_proxy");
            }
            if (raw == null || raw.isBlank()) {
                return false;
            }
            for (String part : raw.split(",")) {
                if ("*".equals(part.trim())) {
                    return true;
                }
            }
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (p.isEmpty() || "*".equals(p)) {
                    continue;
                }
                p = p.startsWith(".") ? p.substring(1) : p;
                if (host.equalsIgnoreCase(p)
                        || host.toLowerCase(Locale.ROOT)
                                .endsWith("." + p.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        /** 环境变量值（http://host:port 一类）→ HTTP 代理地址；解不出给 null（直连）。 */
        private static Proxy parseProxy(String value) {
            try {
                URI u = URI.create(value.contains("://") ? value : "http://" + value);
                int port = u.getPort() > 0 ? u.getPort() : 80;
                if (u.getHost() == null) {
                    return null;
                }
                return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(u.getHost(), port));
            } catch (Exception e) {
                return null;
            }
        }
    }
}
