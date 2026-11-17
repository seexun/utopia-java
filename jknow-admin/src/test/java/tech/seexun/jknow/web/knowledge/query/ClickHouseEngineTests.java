package tech.seexun.jknow.web.knowledge.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.query.ClickHouseEngine.Refused;

/**
 * 移植自 query_engine/clickhouse.rs 测试 22 例（wiremock 回放 + 纯函数 + 真服务器
 * 环境门控，断言逐字不改写；Java 侧 ScriptedServer/裸 socket 同角色）：值按列类型
 * 还原、反斜杠字面量、schema 过滤保住反斜杠、包装类型剥离、只有「拒的 setting」才算
 * 降档、URL 上的 readonly+超时+列序、结果中途的 exception、错误在行后/错误块/长错误、
 * 被拒超时降档重试、无 KILL 且限额守不住 → 拒、短限额顶班、readonly 只对本身只读的
 * 账号丢、到点杀查询、KILL 无匹配不谎报取消、放弃等待也杀、回复被掐断杀、掐断带
 * X-ClickHouse-Exception-Tag 命中不杀（M5 任务 6 补例）、连接被拒不杀、网关错杀而
 * ClickHouse 错不杀；真服务器两例沿 Rust 环境门控（缺 URL 静默过）。
 */
class ClickHouseEngineTests {

    private static final String REFUSED_TIMEOUT =
            "Cannot modify 'max_execution_time' setting in readonly mode. (READONLY)";
    private static final String REFUSED_READONLY =
            "Cannot modify 'readonly' setting in readonly mode. (READONLY)";
    private static final String SLOW = "SELECT sum(number) AS s FROM numbers(1000000000000)";

    private static ClickHouseEngine engine(ScriptedServer server) {
        return new ClickHouseEngine(
                ConnParser.ClickHouseConn.parse(
                        "clickhouse://u:p@" + server.hostPort() + "/sales?ssl=false"));
    }

    private static ScriptedServer.Reply reply(String[][] meta, String dataLiteral) {
        StringBuilder metaJson = new StringBuilder("[");
        for (int i = 0; i < meta.length; i++) {
            if (i > 0) {
                metaJson.append(',');
            }
            metaJson.append("{ \"name\": \"").append(meta[i][0]).append("\", \"type\": \"")
                    .append(meta[i][1]).append("\" }");
        }
        metaJson.append(']');
        return ScriptedServer.Reply.json(200, ScriptedServer.j(
                "{ \"meta\": " + metaJson + ", \"data\": " + dataLiteral + ", \"rows\": 1 }"));
    }

    /** wiremock refusal(...)：500 + X-ClickHouse-Exception-Code 头 + "Code: N. DB::Exception: ..." 体。 */
    private static ScriptedServer.Reply refusal(long code, String message) {
        return ScriptedServer.Reply.text(500,
                Map.of("X-ClickHouse-Exception-Code", String.valueOf(code)),
                "Code: " + code + ". DB::Exception: " + message);
    }

    private static ScriptedServer.Reply tabSeparated(String body) {
        return ScriptedServer.Reply.text(body + "\n");
    }

    // ------------------------------------------------------------------
    // 纯函数（values / literal / unwrap / refused_setting）
    // ------------------------------------------------------------------

    @Test
    void valuesComeBackByColumnType() {
        String i128Min = "-170141183460469231731687303715884105728";
        String decimal38 = "12345678901234567890.0123456789";
        // (类型, 原始串, 期望 JSON 字面量)。两侧都走 j().toString()——值与串形都在比
        for (String[] trio : new String[][]{
                {"UInt64", "18446744073709551615", "18446744073709551615"},
                {"Int64", "-5", "-5"},
                // Wider than 64 bits stays an exact string, not an approximation
                {"Int128", i128Min, "\"" + i128Min + "\""},
                {"Nullable(Int32)", "ᴺᵁᴸᴸ", "null"},
                {"Nullable(Int32)", "NULL", "null"},
                {"LowCardinality(Nullable(String))", "ᴺᵁᴸᴸ", "null"},
                // Text NULL in a string column is ambiguous, so it stays; non-Nullable means no NULL
                {"Nullable(String)", "NULL", "\"NULL\""},
                {"String", "ᴺᵁᴸᴸ", "\"ᴺᵁᴸᴸ\""},
                // An Enum label is text too: a label `NULL` stays, the real marker is null
                {"Nullable(Enum8('NULL' = 1, 'z' = 2))", "NULL", "\"NULL\""},
                {"Nullable(Enum8('NULL' = 1, 'z' = 2))", "ᴺᵁᴸᴸ", "null"},
                // Leading zeros are part of the value
                {"FixedString(5)", "02134", "\"02134\""},
                {"Decimal(12, 2)", "1234.56", "1234.56"},
                {"Decimal(38, 10)", decimal38, "\"" + decimal38 + "\""},
                {"Nullable(Bool)", "true", "true"},
                {"Float64", "1.5", "1.5"},
                {"Float64", "nan", "\"nan\""},
                {"Date", "2023-06-01", "\"2023-06-01\""},
                {"IntervalDay", "3", "\"3\""},
                {"Array(UInt8)", "[1,2]", "\"[1,2]\""},
        }) {
            String ty = trio[0];
            JsonNode got = ClickHouseEngine.cell(ty,
                    ScriptedServer.j("\"" + trio[1] + "\""));
            assertEquals(ScriptedServer.j(trio[2]).toString(), got.toString(), ty + " "
                    + trio[1]);
        }
    }

    /** ClickHouse reads a backslash as an escape: 'sales\north' would name sales&lt;newline&gt;orth. */
    @Test
    void aLiteralEscapesBackslashesAndQuotes() {
        assertEquals("'sales\\\\north\\'s'", ClickHouseEngine.literal("sales\\north's"));
    }

    @Test
    void wrappersComeOffInAnyOrder() {
        assertEquals(Map.entry("String", true), ClickHouseEngine.unwrapType(
                "LowCardinality(Nullable(String))"));
        assertEquals(Map.entry("Decimal(12, 2)", true), ClickHouseEngine.unwrapType(
                "Nullable(Decimal(12, 2))"));
        assertEquals(Map.entry("String", false), ClickHouseEngine.unwrapType(
                "LowCardinality(String)"));
        assertEquals(Map.entry("Array(Nullable(UInt8))", false), ClickHouseEngine.unwrapType(
                "Array(Nullable(UInt8))"));
    }

    @Test
    void onlyARefusedSettingCountsAsOne() {
        String constraint =
                "Setting max_execution_time should not be changed."
                        + " (SETTING_CONSTRAINT_VIOLATION)";
        assertEquals(Refused.TIMEOUT, ClickHouseEngine.refusedSetting(164L, REFUSED_TIMEOUT));
        assertEquals(Refused.READ_ONLY, ClickHouseEngine.refusedSetting(164L, REFUSED_READONLY));
        assertEquals(Refused.TIMEOUT, ClickHouseEngine.refusedSetting(452L, constraint));
        // A write that read-only blocks: also 164, but no setting was refused
        assertEquals(null, ClickHouseEngine.refusedSetting(164L,
                "rw: Cannot execute query in readonly mode. (READONLY)"));
        assertEquals(null, ClickHouseEngine.refusedSetting(62L, "Syntax error:"
                + " 'readonly' setting"));
    }

    // ------------------------------------------------------------------
    // 协议回放（URL settings / 错误形态 / 降档谈判 / KILL 语义）
    // ------------------------------------------------------------------

    @Test
    void theSchemaFilterKeepsABackslashInTheDatabaseName() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub filter = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.bodyContains("database = 'sales\\\\north'")),
                    r -> reply(new String[][]{{"database", "String"}}, "[]"));
            ClickHouseEngine engine = new ClickHouseEngine(ConnParser.ClickHouseConn.parse(
                    "clickhouse://u@" + server.hostPort() + "/sales%5Cnorth"));
            engine.fetchSchema();
            assertEquals(1, filter.hits());
        }
    }

    @Test
    void sendsReadOnlyAndATimeoutAndKeepsColumnOrder() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub request = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParam("readonly", "1"),
                            ScriptedServer.queryParam("max_execution_time", "10"),
                            ScriptedServer.queryParam("database", "sales"),
                            ScriptedServer.bodyContains("LIMIT 201"),
                            ScriptedServer.bodyContains("FORMAT JSONCompactStrings")),
                    r -> reply(
                            new String[][]{{"region", "LowCardinality(String)"},
                                    {"total", "UInt64"}, {"note", "Nullable(String)"}},
                            "[[\"east\", \"18446744073709551615\", \"ᴺᵁᴸᴸ\"]]"));
            QueryResult out = engine(server)
                    .execute("SELECT region, total, note FROM orders");
            assertEquals(List.of("{\"region\":\"east\",\"total\":18446744073709551615,"
                    + "\"note\":null}"), out.rows());
            assertFalse(out.truncated());
            assertEquals(1, request.hits());
        }
    }

    /** An error after the result started: valid JSON, status 200, half the data. */
    @Test
    void anExceptionInsideTheResultIsAnError() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(ScriptedServer.method("POST"),
                    r -> ScriptedServer.Reply.json(200, ScriptedServer.j("{\n"
                            + "            \"meta\": [{ \"name\": \"number\", \"type\":"
                            + " \"UInt64\" }],\n"
                            + "            \"data\": [[\"0\"]],\n"
                            + "            \"exception\": \"Code: 396. DB::Exception:"
                            + " Limit for result exceeded. (TOO_MANY_ROWS_OR_BYTES)\"\n"
                            + "        }")));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> engine(server).execute(SLOW)).getMessage());
            assertTrue(err.contains("TOO_MANY_ROWS_OR_BYTES"), err);
        }
    }

    /**
     * The same error with a gzip reply: status 500, and the rows come before the message.
     * The message quotes "Code: 395." once more, as ClickHouse does with a literal.
     */
    @Test
    void anErrorAfterRowsKeepsOnlyTheMessage() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            String rows = "{\"meta\":[{\"name\":\"s\",\"type\":\"String\"}],"
                    + "\"data\":[[\"" + "x".repeat(100_000) + "\"]";
            String message = "Code: 395. DB::Exception: boom: while executing"
                    + " 'throwIf(..., 'Code: 395. boom'_String)'."
                    + " (FUNCTION_THROW_IF_VALUE_IS_NON_ZERO)";
            server.on(ScriptedServer.method("POST"),
                    r -> ScriptedServer.Reply.text(500,
                            Map.of("X-ClickHouse-Exception-Code", "395"),
                            rows + message + "\n"));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> engine(server).execute(SLOW)).getMessage());
            assertEquals(message, err);
        }
    }

    /** When the headers have already gone out, the same error comes as status 200 with an __exception__ block. */
    @Test
    void anErrorBlockAfterRowsKeepsOnlyTheMessage() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            String message = "Code: 395. DB::Exception: boom."
                    + " (FUNCTION_THROW_IF_VALUE_IS_NON_ZERO)";
            String body = "{\n\t\"meta\": [{\"name\": \"s\", \"type\": \"String\"}],"
                    + "\n\t\"data\":\n\t[\n\t\t[\"0\"]\r\n"
                    + "             __exception__\r\nabcdef\r\n" + message + "\n"
                    + (message.length() + 1) + " abcdef\r\n__exception__\r\n";
            server.on(ScriptedServer.method("POST"),
                    r -> ScriptedServer.Reply.text(200, body));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> engine(server).execute(SLOW)).getMessage());
            assertEquals(message, err);
        }
    }

    /** A long ordinary error keeps its start, which names the cause. */
    @Test
    void aLongErrorStaysWhole() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            String message = "Code: 47. DB::Exception: Unknown expression identifier"
                    + " `missing_col`. In scope " + ("SELECT a AS b, ".repeat(200))
                    + ". (UNKNOWN_IDENTIFIER)";
            server.on(ScriptedServer.method("POST"),
                    r -> ScriptedServer.Reply.text(500,
                            Map.of("X-ClickHouse-Exception-Code", "47"), message));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> engine(server).execute(SLOW)).getMessage());
            assertEquals(message, err);
        }
    }

    /**
     * A readonly=1 profile: max_execution_time is refused and dropped, readonly=1 stays.
     * The server no longer holds the time, so the engine also tries a KILL.
     */
    @Test
    void aRefusedTimeoutIsDroppedAndReadOnlyStays() throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            ScriptedServer.Stub first = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParam("max_execution_time", "10")),
                    r -> refusal(164, REFUSED_TIMEOUT));
            ScriptedServer.Stub retry = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParamMissing("max_execution_time"),
                            ScriptedServer.queryParam("readonly", "1")),
                    r -> reply(new String[][]{{"1", "UInt8"}}, "[[\"1\"]]"));
            ScriptedServer.Stub kill = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.bodyContains("KILL QUERY")),
                    r -> ScriptedServer.Reply.text(200, ""));
            engine(server).test();
            assertEquals(1, first.hits());
            assertEquals(1, retry.hits());
            assertEquals(1, kill.hits());
        }
    }

    /**
     * A readonly=1 account that cannot kill its queries, queried with execute() alone
     * (no test()). profileLimit is its profile's max_execution_time (null: the probe fails).
     * The retry without the timeout must go out retries times.
     */
    private QueryResult queryWithoutKill(String profileLimit, int retries) throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParam("max_execution_time", "10")),
                    r -> refusal(164, REFUSED_TIMEOUT));
            String noGrant = "Not enough privileges. To execute this query, it's necessary"
                    + " to have the grant SELECT ON system.processes. (ACCESS_DENIED)";
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.bodyContains("KILL QUERY")),
                    r -> refusal(497, noGrant));
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.bodyContains("getSetting('max_execution_time')")),
                    r -> profileLimit == null ? refusal(497, "(ACCESS_DENIED)")
                            : tabSeparated(profileLimit));
            ScriptedServer.Stub retry = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParamMissing("max_execution_time"),
                            ScriptedServer.bodyContains("SELECT 7 AS x")),
                    r -> reply(new String[][]{{"x", "UInt8"}}, "[[\"7\"]]"));
            QueryResult out = engine(server).execute("SELECT 7 AS x");
            assertEquals(retries, retry.hits());
            return out;
        }
    }

    /** No KILL, and the profile limit cannot hold 10s (none, too long, unknown): the query is refused with the grant message, and the retry without the timeout never goes out. */
    @Test
    void aQueryThatNothingCanStopInTimeIsRefused() throws Exception {
        for (String limit : new String[]{"0", "3600", null}) {
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> queryWithoutKill(limit, 0)).getMessage());
            assertTrue(err.contains("Grant it SELECT ON system.processes"),
                    String.valueOf(limit) + ": " + err);
            assertTrue(err.contains("ACCESS_DENIED"), String.valueOf(limit) + ": " + err);
        }
    }

    /** No KILL, but the profile limit is 10s or less: the server holds it, so the query runs. */
    @Test
    void aShortProfileLimitStandsInForKill() throws Exception {
        for (String limit : new String[]{"5", "10"}) {
            QueryResult out = queryWithoutKill(limit, 1);
            assertEquals(List.of("{\"x\":7}"), out.rows(), limit);
        }
    }

    /**
     * readonly=1 is refused (a readonly=2 profile, or a statement's own
     * "SETTINGS readonly = 0"), and the account's own readonly is own.
     * Only a read-only account drops readonly and retries.
     */
    private QueryResult readonlyRefused(String own, int retries) throws Exception {
        try (ScriptedServer server = ScriptedServer.start()) {
            server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.bodyContains("getSetting('readonly')"
                                    + " FORMAT TabSeparated")),
                    r -> tabSeparated(own));
            ScriptedServer.Stub first = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParam("readonly", "1")),
                    r -> refusal(164, REFUSED_READONLY));
            // A read-only account resends the same statement without readonly, after a KILL
            // check (statements can lift its timeout); a writable one must not
            ScriptedServer.Stub kill = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.bodyContains("KILL QUERY")),
                    r -> ScriptedServer.Reply.text(200, ""));
            ScriptedServer.Stub retry = server.on(
                    ScriptedServer.all(ScriptedServer.method("POST"),
                            ScriptedServer.queryParamMissing("readonly"),
                            ScriptedServer.queryParam("max_execution_time", "10"),
                            ScriptedServer.bodyContains("SELECT 7 AS x"),
                            ScriptedServer.bodyContains("LIMIT 201")),
                    r -> reply(new String[][]{{"x", "UInt8"}}, "[[\"7\"]]"));
            QueryResult out = engine(server).execute("SELECT 7 AS x");
            assertEquals(1, first.hits());
            assertEquals(retries, kill.hits());
            assertEquals(retries, retry.hits());
            return out;
        }
    }

    @Test
    void readOnlyIsDroppedOnlyForAnAccountThatIsReadOnlyItself() throws Exception {
        QueryResult out = readonlyRefused("2", 1);
        assertEquals(List.of("{\"x\":7}"), out.rows());
        String err = String.valueOf(assertThrows(Exception.class,
                () -> readonlyRefused("0", 0)).getMessage());
        assertTrue(err.contains("Cannot modify 'readonly'"), err);
    }

    /** A slow query that answers after 5s, plus a KILL endpoint (no timeout, exactly one hit). matched: the KILL reply has the row ClickHouse gives for a matched query, or is empty. */
    private ScriptedServer slowServer(boolean matched) throws Exception {
        ScriptedServer server = ScriptedServer.start();
        server.on(
                ScriptedServer.all(ScriptedServer.method("POST"),
                        ScriptedServer.bodyContains("KILL QUERY WHERE query_id = 'utopia-"),
                        ScriptedServer.queryParamMissing("max_execution_time")),
                r -> {
                    String id = r.bodyText().split("'", -1)[1];
                    return ScriptedServer.Reply.text(200,
                            matched ? "waiting\t" + id + "\tu\tSELECT ...\n" : "");
                });
        server.on(
                ScriptedServer.all(ScriptedServer.method("POST"),
                        ScriptedServer.queryParamContains("query_id", "utopia-"),
                        ScriptedServer.bodyContains("numbers")),
                r -> reply(new String[][]{{"s", "UInt64"}}, "[[\"1\"]]").delayed(5000));
        return server;
    }

    /** The KILL names the slow query's query_id. The guard sends it from the error path, so wait for it. */
    private static void assertKilledTheSlowQuery(ScriptedServer server) throws Exception {
        List<ScriptedServer.Recorded> requests = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            requests = server.requests();
            if (requests.size() >= 2) {
                break;
            }
            Thread.sleep(5);
        }
        assertTrue(requests.size() >= 2, "no KILL was sent");
        String slow = requests.stream()
                .filter(r -> r.bodyText().contains("numbers"))
                .map(r -> r.queryParam("query_id"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the slow query carries a query_id"));
        String kill = requests.stream()
                .map(ScriptedServer.Recorded::bodyText)
                .filter(b -> b.contains("KILL QUERY"))
                .findFirst().orElseThrow(() -> new AssertionError("a KILL was sent"));
        assertTrue(kill.contains("'" + slow + "'"), "KILL names " + slow);
    }

    /** No reply by the deadline: a timeout error, and the query is killed. */
    @Test
    void aQueryPastTheDeadlineIsKilled() throws Exception {
        try (ScriptedServer server = slowServer(true)) {
            ClickHouseEngine ch = engine(server);
            ch.deadline(Duration.ofMillis(300));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> ch.execute(SLOW)).getMessage());
            assertTrue(err.contains("did not finish") && err.contains("was cancelled"), err);
            assertKilledTheSlowQuery(server);
        }
    }

    /** The KILL matched nothing (say, it reached another replica): not cancelled. */
    @Test
    void aKillThatMatchesNothingIsNotReportedAsCancelled() throws Exception {
        try (ScriptedServer server = slowServer(false)) {
            ClickHouseEngine ch = engine(server);
            ch.deadline(Duration.ofMillis(300));
            String err = String.valueOf(assertThrows(Exception.class,
                    () -> ch.execute(SLOW)).getMessage());
            assertTrue(err.contains("could not be cancelled")
                    && err.contains("may still be running"), err);
        }
    }

    /** The caller stops waiting (Stop in chat): the thread is interrupted, and the guard still kills it. */
    @Test
    void aDroppedQueryIsKilledToo() throws Exception {
        try (ScriptedServer server = slowServer(true)) {
            ClickHouseEngine ch = engine(server);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = pool.submit(() -> {
                    try {
                        ch.execute(SLOW);
                    } catch (Exception e) {
                        // 中断后的清理路径；不吞断言
                    }
                });
                Thread.sleep(300);
                boolean cancelled = future.cancel(true);
                assertTrue(cancelled, "the query should still be running");
                assertKilledTheSlowQuery(server);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * The server takes the statement, then the connection drops while we read the reply
     * (content-length promised more than it sent). The query may still run, so the guard
     * kills it.
     */
    @Test
    void aReplyCutShortKillsTheQuery() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 0,
                java.net.InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> seen = new LinkedBlockingQueue<>();
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket sock = listener.accept()) {
                        String request = readRequest(sock);
                        byte[] reply = request.contains("KILL QUERY")
                                ? "HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n"
                                        .getBytes(StandardCharsets.UTF_8)
                                : "HTTP/1.1 200 OK\r\ncontent-length: 100\r\n\r\n{\"meta\""
                                        .getBytes(StandardCharsets.UTF_8);
                        OutputStream out = sock.getOutputStream();
                        out.write(reply);
                        out.flush();
                        seen.add(request);
                        // 掐断：留给 content-length 不够的半截体（sock 关闭）
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            int port = listener.getLocalPort();
            ClickHouseEngine engine = new ClickHouseEngine(
                    ConnParser.ClickHouseConn.parse("clickhouse://u@127.0.0.1:" + port
                            + "/sales"));
            AtomicReference<Exception> err = new AtomicReference<>();
            try {
                engine.execute(SLOW);
            } catch (Exception e) {
                err.set(e);
            }
            assertTrue(!String.valueOf(err.get()).contains("did not finish"),
                    String.valueOf(err.get()));
            String statement = seen.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(statement, "the statement");
            String id = java.util.Arrays.stream(statement.split("[?& ]"))
                    .filter(p -> p.startsWith("query_id="))
                    .findFirst().orElseThrow()
                    .substring("query_id=".length());
            String kill = seen.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(kill, "a KILL was sent");
            assertTrue(kill.contains("KILL QUERY") && kill.contains("'" + id + "'"), kill);
            listener.close();
        }
    }

    /**
     * The same cut, but the reply carries {@code X-ClickHouse-Exception-Tag} and the bytes
     * read so far end with an {@code __exception__} block of the same tag (25.11+ cuts the
     * reply on purpose after an error block, so that it cannot pass for a whole result).
     * That block <b>is</b> the server's answer: the statement has ended — no KILL, and the
     * message survives (M5 任务 3 审查 I-1 的补例，clickhouse.rs:269-283）。
     */
    @Test
    void aCutAfterTheExceptionBlockIsTheAnswerNotATransportError() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 0,
                java.net.InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> seen = new LinkedBlockingQueue<>();
            String message = "Code: 395. DB::Exception: boom."
                    + " (FUNCTION_THROW_IF_VALUE_IS_NON_ZERO)";
            String body = "{\n\t\"meta\": [{\"name\": \"s\", \"type\": \"String\"}],"
                    + "\n\t\"data\":\n\t[\n\t\t[\"0\"]\r\n"
                    + "__exception__\r\nabcdef\r\n" + message + "\n"
                    + (message.length() + 1) + " abcdef\r\n__exception__\r\n";
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket sock = listener.accept()) {
                        String request = readRequest(sock);
                        // content-length 承诺更多：__exception__ 块发完即掐断
                        byte[] reply = ("HTTP/1.1 200 OK\r\n"
                                + "X-ClickHouse-Exception-Tag: abcdef\r\n"
                                + "content-length: " + (body.length() + 64) + "\r\n\r\n"
                                + body).getBytes(StandardCharsets.UTF_8);
                        OutputStream out = sock.getOutputStream();
                        out.write(reply);
                        out.flush();
                        seen.add(request);
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            int port = listener.getLocalPort();
            ClickHouseEngine engine = new ClickHouseEngine(
                    ConnParser.ClickHouseConn.parse("clickhouse://u@127.0.0.1:" + port
                            + "/sales"));
            AtomicReference<Exception> err = new AtomicReference<>();
            try {
                engine.execute(SLOW);
            } catch (Exception e) {
                err.set(e);
            }
            assertEquals(message, err.get() == null ? null : err.get().getMessage(),
                    String.valueOf(err.get()));
            String statement = seen.poll(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(statement, "the statement");
            String kill = seen.poll(2, TimeUnit.SECONDS);
            assertTrue(kill == null || !kill.contains("KILL QUERY"),
                    "the answer came back, no KILL: " + kill);
            listener.close();
        }
    }

    /**
     * 一次 HTTP 请求（头 + 体）从裸 socket 读回。<b>不关流</b>——关 socket 的输入流会把
     * 整个 socket 关掉，回复就写不出去了（HTTP/1.1 钉死后请求体随头一起到，这里能读满
     * content-length）。
     */
    private static String readRequest(Socket sock) throws Exception {
        java.io.InputStream in = sock.getInputStream();
        StringBuilder head = new StringBuilder();
        java.util.Deque<Integer> window = new java.util.ArrayDeque<>();
        while (true) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.append((char) b);
            window.addLast(b);
            if (window.size() > 4) {
                window.removeFirst();
            }
            if (window.size() == 4 && new ArrayList<>(window).equals(
                    List.of((int) '\r', (int) '\n', (int) '\r', (int) '\n'))) {
                break;
            }
        }
        String headers = head.toString();
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = in.readNBytes(contentLength);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    /** A refused connection never reached the server, so there is nothing to kill. */
    @Test
    void aRefusedConnectionSendsNoKill() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 0,
                java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        ClickHouseEngine engine = new ClickHouseEngine(
                ConnParser.ClickHouseConn.parse("clickhouse://u@127.0.0.1:" + port + "/sales"));
        AtomicReference<Exception> err = new AtomicReference<>();
        try {
            engine.execute(SLOW);
        } catch (Exception e) {
            err.set(e);
        }
        String text = String.valueOf(err.get()) + " " + (err.get() == null ? ""
                : String.valueOf(err.get().getCause()));
        assertTrue(text.toLowerCase().contains("connect"), text);
        // 连不上已 disarm：此刻重开端口也不该有 KILL 敲进来
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),
                port), 1);
        listener.setSoTimeout(500);
        assertThrows(java.net.SocketTimeoutException.class, listener::accept,
                "a KILL was sent");
        listener.close();
    }

    /**
     * A gateway in front of ClickHouse gives up and answers a full 502 without ClickHouse's
     * header: the query may still run, so the guard kills it. A ClickHouse error reply
     * (with the header) means the statement has ended, so nothing is killed.
     */
    @Test
    void aGatewayErrorKillsTheQueryAndAClickhouseErrorDoesNot() throws Exception {
        for (Object[] pair : new Object[][]{
                {ScriptedServer.Reply.text(502, "Bad Gateway"), 1},
                {refusal(62, "Syntax error. (SYNTAX_ERROR)"), 0},
        }) {
            try (ScriptedServer server = ScriptedServer.start()) {
                ScriptedServer.Stub kill = server.on(
                        ScriptedServer.all(ScriptedServer.method("POST"),
                                ScriptedServer.bodyContains("KILL QUERY")),
                        r -> ScriptedServer.Reply.text(200, ""));
                ScriptedServer.Stub slow = server.on(
                        ScriptedServer.all(ScriptedServer.method("POST"),
                                ScriptedServer.bodyContains("numbers")),
                        r -> (ScriptedServer.Reply) pair[0]);
                assertThrows(Exception.class, () -> engine(server).execute(SLOW));
                assertEquals((int) pair[1], kill.hits(), "KILL count");
                if ((int) pair[1] == 1) {
                    assertKilledTheSlowQuery(server);
                } else {
                    // Give a wrongly fired KILL time to arrive before counting
                    Thread.sleep(300);
                    assertEquals(0, kill.hits());
                }
                assertEquals(1, slow.hits());
            }
        }
    }

    // ------------------------------------------------------------------
    // 真服务器档（环境门控，缺 URL 静默过——Rust 同档）
    // ------------------------------------------------------------------

    /**
     * 对着真 ClickHouse 跑（26.9.12.8 实测口径；clickhouse.rs live_tests 头注给了起容器
     * 与四类账号的 SQL）。没有 JKNOW_TEST_CLICKHOUSE_URL 就跳过。
     */
    @Test
    void aLiveServerAnswersReadOnlyAndWithinTheTimeout() throws Exception {
        String url = System.getenv("JKNOW_TEST_CLICKHOUSE_URL");
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        ClickHouseEngine engine = new ClickHouseEngine(
                ConnParser.ClickHouseConn.parse(url));
        engine.test();

        List<SchemaColumn> schema = engine.fetchSchema();
        java.util.function.Function<String, SchemaColumn> col = name -> schema.stream()
                .filter(c -> c.table().equals("orders") && c.column().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("orders." + name));
        assertEquals("LowCardinality(String)", col.apply("region").dataType());
        assertEquals("Nullable(String)", col.apply("note").dataType());
        assertEquals("CNY", col.apply("amount").comment());
        assertEquals(null, col.apply("note").comment(), "an empty comment is filtered out");
        assertFalse(col.apply("id").isPrimaryKey(), "a sort key is not a unique key");

        // The UInt64 maximum would become 18446744073709552000 through f64
        QueryResult r = engine.execute(
                "SELECT id, region, note, amount, big FROM orders ORDER BY id");
        assertEquals(List.of("{\"id\":1,\"region\":\"east\",\"note\":null,\"amount\":1234.56,"
                + "\"big\":18446744073709551615}"), r.rows());

        // The server refuses the write: the normal account through our readonly=1, the
        // others through their own settings. This bypasses the gate to prove layer 3
        java.net.http.HttpClient client = QueryEngines.http();
        String err = String.valueOf(assertThrows(Exception.class,
                () -> engine.negotiated(client,
                        "CREATE TABLE orders_probe (x UInt8) ENGINE = Memory"))
                        .getMessage());
        assertTrue(err.contains("readonly mode"), err);

        // Timeout: the server stops the query for the normal and readonly=2 accounts, our
        // KILL for the rest. Both must return near the deadline and leave no query behind
        long started = System.nanoTime();
        Exception slow = assertThrows(Exception.class,
                () -> engine.execute("SELECT sum(sipHash64(number)) FROM"
                        + " numbers(1000000000000)"));
        long tookMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(String.valueOf(slow.getMessage()).contains("TIMEOUT_EXCEEDED")
                        || String.valueOf(slow.getMessage()).contains("did not finish"),
                String.valueOf(slow.getMessage()));
        assertTrue(tookMs >= 9000 && tookMs < 15_000,
                "took " + tookMs + "ms: " + slow.getMessage());
        boolean drained = false;
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < waitUntil) {
            QueryResult left = engine.executeWith(client,
                    "SELECT count() FROM system.processes WHERE"
                            + " query LIKE '%numbers(1000000000000)%'"
                            + " AND query NOT LIKE '%system.processes%'");
            if ("0".equals(HttpSupport.JSON.readTree(left.rows().get(0)).elements().next()
                    .asText())) {
                drained = true;
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(drained, "the slow query is still on the server: " + slow.getMessage());
    }

    /**nokill 账号（无 system.processes 权）被拒的那档；缺 JKNOW_TEST_CLICKHOUSE_NO_KILL_URL 跳过。 */
    @Test
    void anAccountThatCannotKillItsQueriesFailsTheTest() throws Exception {
        String url = System.getenv("JKNOW_TEST_CLICKHOUSE_NO_KILL_URL");
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        ClickHouseEngine engine = new ClickHouseEngine(
                ConnParser.ClickHouseConn.parse(url));
        String err = String.valueOf(assertThrows(Exception.class, engine::test).getMessage());
        assertTrue(err.contains("SELECT ON system.processes"), err);
        String err2 = String.valueOf(assertThrows(Exception.class,
                () -> engine.execute("SELECT 1")).getMessage());
        assertTrue(err2.contains("SELECT ON system.processes"), err2);
    }
}
