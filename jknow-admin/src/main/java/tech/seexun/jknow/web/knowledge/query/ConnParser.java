package tech.seexun.jknow.web.knowledge.query;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 连接串解析（query_engine/conn.rs 312 行全量对等）。一个输入框，scheme 决定引擎；
 * 这里把 HTTP 族的 URL 拆成各引擎要的字段。
 *
 * <p>写法沿用 {@code postgres://user:pass@host/db} 的形状：凭据在 userinfo 里，HTTP 族的
 * 令牌放 password 位（{@code databricks://:TOKEN@…}），路径是「目录 / 库 / schema」，
 * 引擎特有的开关走 query。{@code ssl=false} 让 HTTP 族走明文——给本地代理与测试用。
 * Databricks 与 Snowflake 线上只认 https；Trino 与 ClickHouse 默认明文，按端口与
 * {@code ssl} 升级（各自的规则见 {@link TrinoConn} / {@link ClickHouseConn}——一个带密码
 * 升级、一个不升级，方向相反）。
 *
 * <p>解码不用 {@link java.net.URLDecoder}（它把 + 当空格）；URI 取 raw 部分后按
 * percent_decode_str 语义手解，与 Rust 对齐。
 */
public final class ConnParser {

    private ConnParser() {
    }

    /** percent_decode_str 的 Java 对等：只解 %XX，+ 原样。 */
    static String decode(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            flushBytes(out, bytes);
            out.append(c);
            i++;
        }
        flushBytes(out, bytes);
        return out.toString();
    }

    private static void flushBytes(StringBuilder out, java.io.ByteArrayOutputStream bytes) {
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    /** URL 查询段里的键值对（raw 解码形）。 */
    private record Pair(String key, String value) {
    }

    private static List<Pair> queryPairs(URI u) {
        List<Pair> out = new ArrayList<>();
        String raw = u.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.add(new Pair(decode(part), ""));
            } else {
                out.add(new Pair(decode(part.substring(0, eq)), decode(part.substring(eq + 1))));
            }
        }
        return out;
    }

    /** conn.rs:16-21 query：第一个该键的、非空值的（键序与 url crate 的 query_pairs 一致）。 */
    private static String query(URI u, String key) {
        for (Pair p : queryPairs(u)) {
            if (p.key().equals(key) && !p.value().isEmpty()) {
                return p.value();
            }
        }
        return null;
    }

    /** conn.rs:23-28 ssl_off：ssl=false|0|off|no 强制明文（给本地代理与测试）。 */
    private static boolean sslOff(URI u) {
        String v = query(u, "ssl");
        return "false".equals(v) || "0".equals(v) || "off".equals(v) || "no".equals(v);
    }

    /** conn.rs:30-34 segments：路径段（去空段、解码）。 */
    private static List<String> segments(URI u) {
        List<String> out = new ArrayList<>();
        String path = u.getRawPath();
        if (path == null) {
            return out;
        }
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.add(decode(part));
            }
        }
        return out;
    }

    /** userinfo 拆解：返回 [user(raw), password(raw 或 null)]。 */
    private static String[] userInfo(URI u) {
        String raw = u.getRawUserInfo();
        if (raw == null) {
            return new String[]{"", null};
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return new String[]{raw, null};
        }
        return new String[]{raw.substring(0, colon), raw.substring(colon + 1)};
    }

    /**
     * conn.rs:38-43 token_of：令牌 password 位优先；没有 password 时 username 位也算
     * （{@code databricks://TOKEN@host} 少打一个冒号是最常见的手滑）；最后看 ?token=。
     */
    private static String tokenOf(URI u) {
        String[] info = userInfo(u);
        String password = info[1];
        if (password != null && !decode(password).isEmpty()) {
            return decode(password);
        }
        if (!decode(info[0]).isEmpty()) {
            return decode(info[0]);
        }
        return query(u, "token");
    }

    /**
     * conn.rs:46-58 base_of：host 必填；端口缺省；默认端口不写进 URL（日志里干净）。
     * URI 不认 {@code postgres://} 之外的特例——这里按各 scheme 自己拼。
     */
    private static String baseOf(URI u, boolean https, int defaultPort) {
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException(u.getScheme() + "://: a host is required");
        }
        int port = u.getPort() > 0 ? u.getPort() : defaultPort;
        String scheme = https ? "https" : "http";
        String explicit = (https && port == 443) || (!https && port == 80) ? "" : ":" + port;
        return scheme + "://" + host + explicit;
    }

    private static URI parseUri(String conn) {
        try {
            return URI.create(conn.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("relative URL without a base", e);
        }
    }

    // ------------------------------------------------------------------

    /**
     * {@code trino://user[:password]@host[:port]/[catalog[/schema]][?ssl=true|false]}
     * （conn.rs:60-95）。明文 http 是 Trino 的默认（8080）；带密码、ssl=true、或端口
     * 443/8443 时走 https——Trino 自己也拒绝在明文上收密码。presto:// 是同一协议的旧名。
     */
    public record TrinoConn(String base, String user, String password, String catalog,
                            String schema) {

        /** conn.rs:74-94 parse。 */
        public static TrinoConn parse(String conn) {
            URI u = parseUri(conn);
            String[] info = userInfo(u);
            String user = decode(info[0]);
            if (user.isEmpty()) {
                throw new IllegalArgumentException(
                        "trino://: a user is required (it becomes X-Trino-User),"
                                + " e.g. trino://alice@host:8080/hive/default");
            }
            String password = info[1] == null || decode(info[1]).isEmpty() ? null
                    : decode(info[1]);
            boolean https = !sslOff(u) && (password != null
                    || "true".equals(query(u, "ssl"))
                    || u.getPort() == 443 || u.getPort() == 8443);
            String base = baseOf(u, https, https ? 443 : 8080);
            List<String> segs = segments(u);
            return new TrinoConn(base, user, password,
                    segs.isEmpty() ? null : segs.get(0),
                    segs.size() > 1 ? segs.get(1) : null);
        }
    }

    /**
     * {@code clickhouse://user[:password]@host[:port]/[database][?ssl=true|false]}
     * （conn.rs:97-126）。走 HTTP 接口：明文 8123，https 8443。ssl=true（clickhouse-go
     * 的 secure=true 也认）或端口 443/8443 时走 https，ssl=false 强制明文。
     * <b>跟 Trino 不同，带密码不升级 https</b>：ClickHouse 在明文口上照收密码，自建实例
     * 多半就是这样。不写用户就是服务器的 default 用户；库名是之后每条查询的默认库，
     * 也是取 schema 的范围。
     */
    public record ClickHouseConn(String base, String user, String password, String database) {

        /** conn.rs:112-125 parse。 */
        public static ClickHouseConn parse(String conn) {
            URI u = parseUri(conn);
            boolean https = !sslOff(u) && ("true".equals(query(u, "ssl"))
                    || "true".equals(query(u, "secure"))
                    || u.getPort() == 443 || u.getPort() == 8443);
            String base = baseOf(u, https, https ? 8443 : 8123);
            String[] info = userInfo(u);
            return new ClickHouseConn(base,
                    decode(info[0]).isEmpty() ? null : decode(info[0]),
                    info[1] == null || decode(info[1]).isEmpty() ? null : decode(info[1]),
                    segments(u).isEmpty() ? null : segments(u).get(0));
        }
    }

    /**
     * {@code databricks://:TOKEN@workspace-host/sql/1.0/warehouses/WAREHOUSE_ID
     * [?catalog=main&schema=default]}（conn.rs:128-163）。路径就是 JDBC 里的 httpPath，
     * 从控制台复制过来不用改；?warehouse=ID 也认。
     */
    public record DatabricksConn(String base, String token, String warehouseId, String catalog,
                                 String schema) {

        /** conn.rs:141-162 parse。 */
        public static DatabricksConn parse(String conn) {
            URI u = parseUri(conn);
            boolean off = sslOff(u);
            String base = baseOf(u, !off, off ? 80 : 443);
            String token = tokenOf(u);
            if (token == null) {
                throw new IllegalArgumentException("databricks://: a personal access token is"
                        + " required, e.g. databricks://:TOKEN@host/sql/1.0/warehouses/ID");
            }
            List<String> segs = segments(u);
            String fromPath = null;
            for (int i = 0; i < segs.size() - 1; i++) {
                if ("warehouses".equals(segs.get(i))) {
                    fromPath = segs.get(i + 1);
                    break;
                }
            }
            String warehouseId = query(u, "warehouse") != null ? query(u, "warehouse") : fromPath;
            if (warehouseId == null) {
                throw new IllegalArgumentException("databricks://: a SQL warehouse is required"
                        + " — the /sql/1.0/warehouses/ID path or ?warehouse=ID");
            }
            return new DatabricksConn(base, token, warehouseId,
                    query(u, "catalog"), query(u, "schema"));
        }
    }

    /**
     * {@code snowflake://:TOKEN@account.snowflakecomputing.com/[DATABASE[/SCHEMA]]
     * [?warehouse=WH&role=R&token_type=pat|oauth]}（conn.rs:165-210）。
     * SQL API 不收密码，只收令牌：默认当作 programmatic access token，
     * token_type=oauth 换成 OAuth 令牌。密钥对 JWT 要本地签名，这一版不做。
     */
    public record SnowflakeConn(String base, String token, String tokenType, String database,
                                String schema, String warehouse, String role) {

        /** X-Snowflake-Authorization-Token-Type 的值（conn.rs:188-198）。 */
        public static SnowflakeConn parse(String conn) {
            URI u = parseUri(conn);
            boolean off = sslOff(u);
            String base = baseOf(u, !off, off ? 80 : 443);
            String token = tokenOf(u);
            if (token == null) {
                throw new IllegalArgumentException("snowflake://: a programmatic access token"
                        + " or OAuth token is required, e.g."
                        + " snowflake://:TOKEN@account.snowflakecomputing.com/DB/SCHEMA?warehouse=WH");
            }
            String rawTokenType = query(u, "token_type");
            String tokenType;
            if (rawTokenType == null) {
                tokenType = "PROGRAMMATIC_ACCESS_TOKEN";
            } else {
                String lowered = rawTokenType.toLowerCase(Locale.ROOT);
                switch (lowered) {
                    case "pat", "programmatic_access_token" ->
                            tokenType = "PROGRAMMATIC_ACCESS_TOKEN";
                    case "oauth" -> tokenType = "OAUTH";
                    default -> throw new IllegalArgumentException(
                            "snowflake://: unknown token_type '" + lowered + "' (pat or oauth)");
                }
            }
            List<String> segs = segments(u);
            return new SnowflakeConn(base, token, tokenType,
                    segs.isEmpty() ? null : segs.get(0),
                    segs.size() > 1 ? segs.get(1) : null,
                    query(u, "warehouse"), query(u, "role"));
        }
    }

    /** 拼回 URL 查询参数（引擎测试构造连接串用）。 */
    static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
