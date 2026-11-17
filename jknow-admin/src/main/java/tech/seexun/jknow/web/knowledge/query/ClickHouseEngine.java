package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ClickHouse over HTTP（clickhouse.rs 1293 行对等）：POST / 语句在 body 里。没有会话，
 * 只读与超时是每个请求 URL 上的 settings，而<b>账号收哪一份由它自己决定</b>：
 * <ul>
 *   <li><b>被拒的 setting 丢掉重发。</b> 要 readonly=1 与 max_execution_time；哪份被
 *       profile 拒了就丢哪份再发（四类实测账号见 {@link #negotiated}）；</li>
 *   <li><b>服务器守不住时间就由引擎杀查询。</b> 每个请求带 query_id；到 deadline、或
 *       调用方放弃了等待（chat 里的 Stop），就发 KILL QUERY——需要 SELECT ON
 *       system.processes；没有它查询被拒，除非 readonly=1 生效且账号自己的
 *       max_execution_time 不长于 10s。负载均衡后面 KILL 可能落到另一台副本上匹配不到；
 *       这类账号请在服务端配 max_execution_time；</li>
 *   <li><b>值按列类型还原</b>（JSONCompactStrings）——{@link #cell} 说原因。</li>
 * </ul>
 */
public final class ClickHouseEngine implements QueryEngine {

    /** JSONCompactStrings 写 NULL 的记号。服务器的 output_format_pretty_grid_charset 是 ASCII 时写 "NULL"。 */
    static final String NULL_TEXT = "ᴺᵁᴸᴸ";
    static final String NULL_TEXT_ASCII = "NULL";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ClickHouseEngine.class);

    private final ConnParser.ClickHouseConn conn;
    /** Kill the query if no reply comes by then. Two seconds more than the server's max_execution_time: when both apply, the server's error is clearer than ours. */
    private Duration deadline;

    public ClickHouseEngine(ConnParser.ClickHouseConn conn) {
        this.conn = conn;
        this.deadline = Duration.ofSeconds(QueryEngines.STATEMENT_TIMEOUT_SECS + 2);
    }

    /** 测试用：把 deadline 拧短（Rust 测试直接改同名字段）。 */
    void deadline(Duration d) {
        this.deadline = d;
    }

    // ------------------------------------------------------------------
    // 纯件（clickhouse.rs:63-153）
    // ------------------------------------------------------------------

    /** 一个请求带的两道闸。 */
    record Guards(boolean readonly, boolean timeout) {
        static final Guards ALL = new Guards(true, true);
        static final Guards NONE = new Guards(false, false);
    }

    enum Refused {
        TIMEOUT, READ_ONLY
    }

    /**
     * ClickHouse 字符串字面量（clickhouse.rs:88-90 literal）。ClickHouse 把反斜杠也当
     * 转义读，共享的 {@link QueryEngines#sqlLiteral}（只翻倍引号）会把 sales\north 读成
     * sales&lt;换行&gt;orth。
     */
    static String literal(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** 最后 n 个<b>字符</b>（clickhouse.rs:93-98 tail）：长回复把错误留在结尾。 */
    static String tail(String text, int n) {
        if (n <= 0) {
            return "";
        }
        int len = text.length();
        return len <= n ? text : text.substring(len - n);
    }

    /**
     * 错误回复里服务端的话（clickhouse.rs:106-121 server_message）。结果开始之后出的错
     * 在那段数据后面；回复可能引用同一个 "Code: N." 字面量，以它开头的回复是普通错误、
     * 保持完整。
     */
    static String serverMessage(Long code, String text) {
        String t = text.trim();
        String trailer = trailerMessage(t);
        if (trailer != null) {
            return trailer;
        }
        if (code == null) {
            return tail(t, 1000);
        }
        String prefix = "Code: " + code + ".";
        if (t.startsWith(prefix)) {
            return t;
        }
        int idx = t.lastIndexOf("Code: " + code + ". DB::");
        if (idx >= 0) {
            return t.substring(idx);
        }
        return tail(t, 1000);
    }

    /**
     * 头已经发出去时（无压缩、或结果大到攒不住），25.11+ 回 200：行数据之后跟
     * {@code __exception__ <tag> <message> <length> <tag> __exception__} 块（clickhouse.rs:127-142）。
     */
    static String trailerMessage(String text) {
        String tag = trailerTag(text);
        if (tag == null) {
            return null;
        }
        if (!text.endsWith("__exception__")) {
            return null;
        }
        String body = text.substring(0, text.length() - "__exception__".length()).trim();
        int lastNl = body.lastIndexOf('\n');
        if (lastNl < 0) {
            return null;
        }
        body = body.substring(0, lastNl);
        int marker = body.lastIndexOf("__exception__");
        if (marker < 0) {
            return null;
        }
        String block = body.substring(marker + "__exception__".length()).trim();
        if (!block.startsWith(tag)) {
            return null;
        }
        String message = block.substring(tag.length()).trim();
        return message;
    }

    /** __exception__ 块最后一行上的 tag（clickhouse.rs:127-142 trailer_tag）。 */
    static String trailerTag(String text) {
        if (!text.endsWith("__exception__")) {
            return null;
        }
        String rest = text.substring(0, text.length() - "__exception__".length()).trim();
        int lastNl = rest.lastIndexOf('\n');
        if (lastNl < 0) {
            return null;
        }
        String[] words = rest.substring(lastNl + 1).trim().split("\\s+");
        return words.length > 1 ? words[1] : null;
    }

    static String queryId() {
        return "utopia-" + tech.seexun.jknow.common.uuids.Uuids.nowV7().toString()
                .replace("-", "");
    }

    /** 哪个 setting 被拒了（clickhouse.rs:439-454 refused_setting）：164=只读账号、452=约束；写操作也被 164 挡，但它的话里不点名 setting。 */
    static Refused refusedSetting(Long code, String message) {
        if (code == null || (code != 164 && code != 452)) {
            return null;
        }
        if (message.contains("'max_execution_time' setting")
                || message.contains("Setting max_execution_time ")) {
            return Refused.TIMEOUT;
        }
        if (message.contains("'readonly' setting") || message.contains("Setting readonly ")) {
            return Refused.READ_ONLY;
        }
        return null;
    }

    /** 剥掉 LowCardinality(...)/Nullable(...)（可嵌套）；boolean 表示「可以为 NULL」（clickhouse.rs:457-476）。 */
    static java.util.Map.Entry<String, Boolean> unwrapType(String typeName) {
        String ty = typeName.trim();
        boolean nullable = false;
        while (true) {
            if (ty.startsWith("Nullable(") && ty.endsWith(")")) {
                ty = ty.substring("Nullable(".length(), ty.length() - 1);
                nullable = true;
            } else if (ty.startsWith("LowCardinality(") && ty.endsWith(")")) {
                ty = ty.substring("LowCardinality(".length(), ty.length() - 1);
            } else {
                return java.util.Map.entry(ty, nullable);
            }
        }
    }

    /**
     * 一格：JSONCompactStrings 的字符串 → JSON 值（clickhouse.rs:478-530 cell）。
     *
     * <p>不用 JSONCompact：64 位以上整数的引号与否取决于服务器的
     * output_format_json_quote_64bit_integers（只读账号改不了它）。不用共享
     * {@link QueryEngines#coerce}：它的数列表含 Snowflake 的 FIXED，FixedString(5) 里的
     * 邮编 '02134' 会变成 2134。只有这些类型转换：
     * <ul>
     *   <li>整数：装得下 i64/u64 就是数，否则（128/256 位）留<b>精确字符串</b>——比一个
     *       尾数错的数对模型诚实；</li>
     *   <li>Decimal(P,S)：P≤15（f64 装得精确）转数，否则留字符串，同一理由；</li>
     *   <li>浮点转数（nan/inf 留字符串：不是 JSON 数），Bool 转 bool；</li>
     *   <li>NULL 只在 Nullable(...) 列里；ASCII "NULL" 只在非文本列里当 NULL。
     *       Nullable(String) 里恰好存着这段文本是有歧义的：精确整数的代价。</li>
     * </ul>
     */
    static JsonNode cell(String typeName, JsonNode raw) {
        var unwrapped = unwrapType(typeName);
        String base = unwrapped.getKey();
        boolean nullable = unwrapped.getValue();
        if (!(raw instanceof TextNode textNode)) {
            return raw;
        }
        String s = textNode.textValue();
        boolean textType = base.equals("String")
                || base.startsWith("FixedString(") || base.startsWith("Enum8(")
                || base.startsWith("Enum16(");
        if (nullable && (NULL_TEXT.equals(s) || (NULL_TEXT_ASCII.equals(s) && !textType))) {
            return NullNode.getInstance();
        }
        String bits = null;
        if (base.startsWith("UInt") && base.length() > 4
                && isAllDigits(base.substring(4))) {
            bits = base.substring(4);
        } else if (base.startsWith("Int") && base.length() > 3
                && isAllDigits(base.substring(3))) {
            bits = base.substring(3);
        }
        if (bits != null && !bits.isEmpty()) {
            try {
                return new com.fasterxml.jackson.databind.node.LongNode(Long.parseLong(s));
            } catch (NumberFormatException tooWide) {
                try {
                    BigInteger big = new BigInteger(s);
                    if (big.signum() >= 0
                            && big.compareTo(new BigInteger("18446744073709551615")) <= 0) {
                        return new BigIntegerNode(big);
                    }
                } catch (NumberFormatException notANumber) {
                    // 留原串
                }
            }
            return raw;
        }
        Integer precision = null;
        if (base.startsWith("Decimal(")) {
            String rest = base.substring("Decimal(".length());
            int comma = rest.indexOf(',');
            String p = comma >= 0 ? rest.substring(0, comma) : rest.replace(")", "");
            try {
                precision = Integer.parseInt(p.trim());
            } catch (NumberFormatException ignored) {
                // 非标准形态：当文本
            }
        }
        boolean shortDecimal = precision != null && precision <= 15;
        if (shortDecimal || base.equals("Float32") || base.equals("Float64")
                || base.equals("BFloat16")) {
            return QueryEngines.coerce("DOUBLE", raw);
        }
        if (base.equals("Bool")) {
            return QueryEngines.coerce("BOOL", raw);
        }
        return raw;
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 请求与守闸（clickhouse.rs:148-337）
    // ------------------------------------------------------------------

    /** 带 settings 的请求 URL（clickhouse.rs:148-176 request）。 */
    private static String requestUrl(ConnParser.ClickHouseConn conn, Guards guards,
                                     String queryId) {
        StringBuilder url = new StringBuilder(conn.base());
        url.append('?');
        if (queryId != null) {
            url.append("query_id=").append(enc(queryId)).append('&');
        }
        if (conn.database() != null) {
            url.append("database=").append(enc(conn.database())).append('&');
        }
        if (guards.readonly()) {
            url.append("readonly=1&");
        }
        if (guards.timeout()) {
            url.append("max_execution_time=")
                    .append(QueryEngines.STATEMENT_TIMEOUT_SECS).append('&');
        }
        url.setLength(url.length() - 1);
        return url.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 杀我们自己的查询（clickhouse.rs:182-201 kill_query）。返回 KILL 是否匹配到它：
     * 回复里每个匹配的查询一行 TSV（status, query_id, user, query），一条都没匹配是空
     * （比如 KILL 落到了另一台副本上）。错误里留着服务器的理由（通常是没给
     * system.processes 权限）。
     */
    boolean killQuery(HttpClient client, String queryId) throws Exception {
        String sql = "KILL QUERY WHERE query_id = " + literal(queryId)
                + " ASYNC FORMAT TabSeparated";
        HttpSupport.Call call = call(sql, Guards.NONE, null, Duration.ofSeconds(5));
        HttpSupport.Response response = HttpSupport.send(client, call);
        String text = response.text();
        if (response.status() / 100 != 2) {
            throw new IllegalStateException(text.trim());
        }
        for (String row : text.split("\n")) {
            String[] fields = row.split("\t", -1);
            if (fields.length > 1 && fields[1].equals(queryId)) {
                return true;
            }
        }
        return false;
    }

    private HttpSupport.Call call(String body, Guards guards, String queryId, Duration timeout) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (conn.user() != null) {
            String token = java.util.Base64.getEncoder().encodeToString(
                    (conn.user() + ":" + (conn.password() == null ? "" : conn.password()))
                            .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + token);
        }
        headers.put("Accept-Encoding", "gzip");
        return new HttpSupport.Call(requestUrl(conn, guards, queryId), "POST", headers,
                body.getBytes(StandardCharsets.UTF_8), timeout);
    }

    /**
     * 到点或调用方放弃时把查询杀掉（clickhouse.rs:204-240 KillOnDrop 的 Java 形——没有
     * 析构，错误路径显式 fire）。返回 TRUE=匹配杀掉、FALSE=KILL 没匹配到、null=KILL
     * 自己失败（错误已告警）。
     */
    private final class KillGuard {
        private final HttpClient client;
        private final String queryId;
        private boolean armed = true;

        KillGuard(HttpClient client, String queryId) {
            this.client = client;
            this.queryId = queryId;
        }

        void disarm() {
            armed = false;
        }

        Boolean fire() {
            if (!armed) {
                return null;
            }
            armed = false;
            try {
                boolean killed = killQuery(client, queryId);
                if (!killed) {
                    LOG.warn("query_id={} KILL QUERY matched no ClickHouse query", queryId);
                }
                return killed;
            } catch (Exception e) {
                LOG.warn("query_id={} could not kill the ClickHouse query: {}", queryId,
                        e.getMessage());
                return null;
            }
        }
    }

    /** ClickHouse 自己答的错（X-ClickHouse-Exception-Code 头）。 */
    private static final class ServerError extends RuntimeException {
        ServerError(Long code, String message) {
            super(message);
            this.code = code;
        }

        final Long code;
    }

    /**
     * 发出请求并收完回复（clickhouse.rs:250-337 post）。三岔口：
     * <ul>
     *   <li><b>到 deadline</b>（HttpTimeoutException）或<b>调用方放弃</b>
     *       （InterruptedException——chat 里的 Stop）：发 KILL 等结果，三种结局各一句；</li>
     *   <li><b>连接被拒</b>：语句根本没到服务器，没有要杀的东西；</li>
     *   <li><b>其它传输错</b>（读回复时连接断掉）：查询可能还在跑——照杀。</li>
     * </ul>
     * 成功、或带 X-ClickHouse-Exception-Code 头的错（ClickHouse 答的、语句已结束）不杀；
     * 没有 ClickHouse 头的错（网关的 502/504）证明不了什么——照杀。
     */
    private String post(HttpClient client, String body, Guards guards) throws Exception {
        String id = queryId();
        HttpSupport.Call call = call(body, guards, id, deadline);
        KillGuard guard = new KillGuard(client, id);
        HttpSupport.Response response;
        try {
            response = HttpSupport.sendPartial(client, call);
        } catch (HttpTimeoutException | InterruptedException timedOut) {
            long secs = deadline.toSeconds();
            Boolean killed = guard.fire();
            if (Boolean.TRUE.equals(killed)) {
                throw new IllegalStateException(
                        "ClickHouse query did not finish within " + secs + "s and was cancelled");
            }
            if (Boolean.FALSE.equals(killed)) {
                throw new IllegalStateException("ClickHouse query did not finish within " + secs
                        + "s and could not be cancelled (KILL QUERY matched no query;"
                        + " behind a load balancer it may have reached another replica);"
                        + " it may still be running");
            }
            throw new IllegalStateException("ClickHouse query did not finish within " + secs
                    + "s and could not be cancelled (" + timedOut + "); it may still be running");
        } catch (IOException transport) {
            if (isConnectFailure(transport)) {
                // 只有「连不上」能证明语句没到服务器；此后任何错（比如读回复时连接断）它都可能在跑
                guard.disarm();
            } else {
                guard.fire();
            }
            throw new IOException(String.valueOf(transport), transport);
        }
        if (response.readError() != null) {
            // 读回复时断流（clickhouse.rs:269-283 chunk 循环的 Err 分支）：错误块之后
            // 25.11+ 故意掐断回复、不让它冒充完整结果——头里的 X-ClickHouse-Exception-Tag
            // 与已读体尾部的 __exception__ 块 tag 对上，说明这就是服务器的话、语句已结束；
            // 任何其它截断都是传输错，查询可能还在跑
            String tag = response.header("X-ClickHouse-Exception-Tag");
            String got = trailerTag(response.text().trim());
            if (tag == null || got == null || !tag.equals(got)) {
                guard.fire();
                throw new IOException(String.valueOf(response.readError()), response.readError());
            }
        }
        Long code = parseCode(response.header("X-ClickHouse-Exception-Code"));
        if (response.status() / 100 == 2 || code != null) {
            // ClickHouse 答了、语句已结束
            guard.disarm();
        } else {
            guard.fire();
        }
        if (response.status() / 100 != 2) {
            throw new ServerError(code, serverMessage(code, response.text()));
        }
        return response.text();
    }

    private static boolean isConnectFailure(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || "connect".equalsIgnoreCase(String.valueOf(t.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    private static Long parseCode(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 带只读与超时发送，被拒的那份丢掉重发（clickhouse.rs:339-377 negotiated）。各账号
     * 实测（26.9.12.8）：
     * <ul>
     *   <li>普通账号：两份都收、服务器两道都执守。用 readonly=1 不用 2：readonly=2 下
     *       {@code SELECT … SETTINGS max_execution_time = 0} 能把超时关掉；</li>
     *   <li>profile 配 readonly=1：max_execution_time 被 READONLY(164) 拒；readonly=1
     *       是现值，服务器收；</li>
     *   <li>profile 配 readonly=2：反过来（readonly=1 被拒）；语句能改它；</li>
     *   <li>max_execution_time 被 CONST 约束锁死：SETTING_CONSTRAINT_VIOLATION(452)。</li>
     * </ul>
     * 只在账号<b>自己就是只读</b>时才敢丢 readonly：可写账号的语句自己带
     * {@code SETTINGS readonly = 0} 会拿到同一句 "Cannot modify 'readonly'"，丢掉闸门
     * 就等于给它开门。
     */
    Negotiated negotiated(HttpClient client, String body) throws Exception {
        Guards guards = Guards.ALL;
        while (true) {
            if (!guards.equals(Guards.ALL)) {
                canStopInTime(client, guards);
            }
            String text;
            try {
                text = post(client, body, guards);
                return new Negotiated(text, guards);
            } catch (ServerError e) {
                Refused refused = refusedSetting(e.code, e.getMessage());
                if (refused == Refused.TIMEOUT && guards.timeout()) {
                    guards = new Guards(guards.readonly(), false);
                    continue;
                }
                if (refused == Refused.READ_ONLY && guards.readonly()) {
                    // 探测失败或账号本身是 0：保留原始拒绝
                    String own;
                    try {
                        own = accountSetting(client, "readonly");
                    } catch (Exception probeFailed) {
                        own = null;
                    }
                    if (!"1".equals(own) && !"2".equals(own)) {
                        throw e;
                    }
                    guards = new Guards(false, guards.timeout());
                    continue;
                }
                throw e;
            }
        }
    }

    private record Negotiated(String text, Guards guards) {
    }

    /** 账号自己（profile 里）的某个 setting（clickhouse.rs:381-388 account_setting）。FORMAT 固定：profile 可能把 default_format 设成 JSONEachRow，那会回一个 JSON 对象。 */
    private String accountSetting(HttpClient client, String name) throws Exception {
        String sql = "SELECT getSetting(" + literal(name) + ") FORMAT TabSeparated";
        return post(client, sql, Guards.NONE).trim();
    }

    /**
     * 语句要在缺一道闸的情况下出门前跑（clickhouse.rs:391-415 can_stop_in_time）。
     * 服务器只在两道都在时守时间，否则只剩 KILL：先拿一个不会匹配的 id 试它——KILL
     * 失败时，跑在 readonly=1 下的账号可以指望自己的 max_execution_time（包括 CONST
     * 锁的），但不得长于我们的 10s：3600s 的限额守不住 10s。
     */
    private void canStopInTime(HttpClient client, Guards guards) throws Exception {
        String reason;
        try {
            killQuery(client, queryId());
            return;
        } catch (Exception e) {
            reason = String.valueOf(e.getMessage());
        }
        Double limit = null;
        if (guards.readonly()) {
            try {
                limit = Double.parseDouble(accountSetting(client, "max_execution_time"));
            } catch (Exception ignored) {
                // 探不出（无权限/非数字）：当没有
            }
        }
        if (limit != null && limit > 0.0
                && limit <= (double) QueryEngines.STATEMENT_TIMEOUT_SECS) {
            return;
        }
        throw new IllegalStateException("This ClickHouse account cannot cancel its own queries ("
                + reason + "), so nothing would stop a query at the "
                + QueryEngines.STATEMENT_TIMEOUT_SECS + "s timeout. Grant it SELECT ON"
                + " system.processes, or set max_execution_time to at most "
                + QueryEngines.STATEMENT_TIMEOUT_SECS + " in its read-only settings profile.");
    }

    /** run 的返回：解析后的回复树 + 谈下来的闸。 */
    private record RunReply(JsonNode tree, Guards guards) {
    }

    /**
     * 带闸谈判地跑一条语句并解出回复（clickhouse.rs:417-433 run）。JSON 解不开时按
     * 25.11+ 的 {@code __exception__} 尾块取错误；结果开始之后出的错（exception 字段，
     * 23.9-25.10 默认行为）半截数据不算数。
     */
    RunReply run(HttpClient client, String sql) throws Exception {
        Negotiated out = negotiated(client, sql + "\nFORMAT JSONCompactStrings");
        JsonNode reply;
        try {
            reply = HttpSupport.JSON.readTree(out.text());
        } catch (Exception e) {
            String trailer = trailerMessage(out.text().trim());
            throw new IllegalStateException(trailer != null ? trailer
                    : "ClickHouse returned an unreadable result: " + tail(out.text(), 300).trim());
        }
        if (reply.path("exception").isTextual()) {
            throw new IllegalStateException(reply.path("exception").asText().trim());
        }
        return new RunReply(reply, out.guards());
    }

    // ------------------------------------------------------------------
    // QueryEngine
    // ------------------------------------------------------------------

    @Override
    public void test() throws Exception {
        run(QueryEngines.http(), "SELECT 1");
    }

    @Override
    public List<SchemaColumn> fetchSchema() throws Exception {
        // URL 里有库名就只读那一个：default 库多半是空的，不带范围整个服务器都进 schema
        // 文档。type 保持原样（Nullable/LowCardinality 一起留）：前者说「有 NULL」，后者
        // 多半标记维度列——都有用，而且 SQL 跟内层类型一样写（clickhouse.rs:538-562）
        String scope = conn.database() != null
                ? "database = " + literal(conn.database())
                : "database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')";
        HttpClient client = QueryEngines.http();
        RunReply out = run(client, "SELECT database, table, name, type, comment"
                + " FROM system.columns"
                + "                     WHERE " + scope
                + " ORDER BY database, table, position");
        List<SchemaColumn> result = new ArrayList<>();
        for (JsonNode row : out.tree().path("data")) {
            List<JsonNode> values = new ArrayList<>();
            if (row.isArray()) {
                row.forEach(values::add);
            }
            result.add(TrinoEngine.schemaRow(values));
        }
        return result;
    }

    @Override
    public QueryResult execute(String sql) throws Exception {
        return executeWith(QueryEngines.http(), sql);
    }

    /** execute 的可见客户端形（真服务器档测试复用同一条解析路）。 */
    QueryResult executeWith(HttpClient client, String sql) throws Exception {
        RunReply out = run(client, QueryEngines.wrapLimit(sql));
        List<String> columns = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (JsonNode m : out.tree().path("meta")) {
            columns.add(m.path("name").asText(""));
            types.add(m.path("type").asText(""));
        }
        List<List<JsonNode>> rows = new ArrayList<>();
        for (JsonNode row : out.tree().path("data")) {
            List<JsonNode> values = new ArrayList<>();
            if (row.isArray()) {
                for (int i = 0; i < row.size(); i++) {
                    values.add(cell(i < types.size() ? types.get(i) : "", row.get(i)));
                }
            }
            rows.add(values);
        }
        var truncated = QueryEngines.truncateRows(rows);
        return new QueryResult(
                QueryEngines.rowsToJsonLines(columns, truncated.rows(), HttpSupport.JSON),
                truncated.truncated());
    }
}
