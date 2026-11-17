package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/**
 * 脚本化 HTTP 端点（HTTP 族引擎测试的 wiremock 同角色；JDK HttpServer 沿
 * ScriptedModelServer 先例——侵入最小，不加新依赖）。
 *
 * <p>挂载按<b>注册顺序</b>第一个匹配的 stub（wiremock 的 matcher 语义）；没匹配到的
 * 请求回 500 带标记体——脚本漏了一条会比静默 404 更早暴露。每个 stub 记命中数
 * （{@code expect(n)} 的对位，测后断言）；全局请求流水（method/path/rawQuery/headers/
 * body）供 wiremock received_requests 的断言。执行器用线程池：慢响应占着一个连接时，
 * KILL 一类的新请求还得能进来（ClickHouse 的 deadline 测试正靠这一点）。
 */
public final class ScriptedServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** serde_json::json! 宏的对位：JSON 字面量直读（wiremock 测试体的同款形状）。 */
    public static com.fasterxml.jackson.databind.JsonNode j(String literal) {
        try {
            return JSON.readTree(literal);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 一条应答：状态、头、体、写头前的延迟（wiremock ResponseTemplate 的对位）。 */
    public record Reply(int status, Map<String, String> headers, long delayMs, byte[] body) {

        public static Reply json(int status, JsonNode body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), 0,
                    body.toString().getBytes(StandardCharsets.UTF_8));
        }

        public static Reply json(JsonNode body) {
            return json(200, body);
        }

        public static Reply text(int status, String body) {
            return new Reply(status, Map.of(), 0, body.getBytes(StandardCharsets.UTF_8));
        }

        public static Reply text(String body) {
            return text(200, body);
        }

        /** 头 + 文本体（refusal 一类）。 */
        public static Reply text(int status, Map<String, String> headers, String body) {
            Map<String, String> all = new LinkedHashMap<>(headers);
            return new Reply(status, all, 0, body.getBytes(StandardCharsets.UTF_8));
        }

        /** 慢应答（wiremock set_delay）：延迟发生在写响应头之前。 */
        public Reply delayed(long ms) {
            return new Reply(status, headers, ms, body);
        }

        /** gzip 应答（Snowflake 的后续分区线上会压；JDK 服务器不会，脚本里手动压）。 */
        public static Reply gzip(int status, JsonNode body) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                    gz.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Content-Encoding", "gzip");
                return new Reply(status, headers, 0, bytes.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 记录下来的请求（wiremock Request 的对位）。 */
    public record Recorded(String method, String path, String rawQuery,
                           Map<String, List<String>> headers, byte[] body) {

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode bodyJson() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("request body is not JSON", e);
            }
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        /** 解码后的单个查询参数（第一个）。 */
        public String queryParam(String name) {
            if (rawQuery == null) {
                return null;
            }
            for (String part : rawQuery.split("&")) {
                int eq = part.indexOf('=');
                String key = eq < 0 ? part : part.substring(0, eq);
                if (key.equals(name)) {
                    return eq < 0 ? "" : decode(part.substring(eq + 1));
                }
            }
            return null;
        }

        private static String decode(String raw) {
            return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        }
    }

    /** 一条脚本：匹配器 + 应答器 + 命中计数。 */
    public final class Stub {
        private final Function<Recorded, Boolean> matcher;
        private final Function<Recorded, Reply> responder;
        private final AtomicInteger hits = new AtomicInteger();

        Stub(Function<Recorded, Boolean> matcher, Function<Recorded, Reply> responder) {
            this.matcher = matcher;
            this.responder = responder;
        }

        public int hits() {
            return hits.get();
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Stub> stubs = new ArrayList<>();
    private final List<Recorded> requests = new ArrayList<>();

    private ScriptedServer(HttpServer server) {
        this.server = server;
    }

    public static ScriptedServer start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ScriptedServer scripted = new ScriptedServer(server);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Recorded recorded = new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders(), body);
            synchronized (scripted.requests) {
                scripted.requests.add(recorded);
            }
            Reply reply = null;
            synchronized (scripted.stubs) {
                for (Stub stub : scripted.stubs) {
                    if (Boolean.TRUE.equals(stub.matcher.apply(recorded))) {
                        stub.hits.incrementAndGet();
                        reply = stub.responder.apply(recorded);
                        break;
                    }
                }
            }
            if (reply == null) {
                byte[] message = ("no scripted stub matched " + recorded.method() + " "
                        + recorded.path() + "?" + recorded.rawQuery())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(500, message.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(message);
                }
                return;
            }
            if (reply.delayMs() > 0) {
                try {
                    Thread.sleep(reply.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (Map.Entry<String, String> h : reply.headers().entrySet()) {
                exchange.getResponseHeaders().set(h.getKey(), h.getValue());
            }
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply.body());
            }
        });
        server.setExecutor(scripted.executor);
        server.start();
        return scripted;
    }

    /** 挂一条脚本（注册顺序即匹配顺序）。 */
    public Stub on(Function<Recorded, Boolean> matcher,
                   Function<Recorded, Reply> responder) {
        Stub stub = new Stub(matcher, responder);
        synchronized (stubs) {
            stubs.add(stub);
        }
        return stub;
    }

    /** 全局请求流水（wiremock received_requests 的对位）。 */
    public List<Recorded> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    /** 基址（http://127.0.0.1:port）。 */
    public String uri() {
        return "http://" + hostPort();
    }

    /** host:port（拼进连接串——HTTP 族引擎测试都是明文 + ssl=false）。 */
    public String hostPort() {
        return server.getAddress().getAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- 匹配器（wiremock matchers 的对位） ----

    @SafeVarargs
    public static Function<Recorded, Boolean> all(Function<Recorded, Boolean>... matchers) {
        return r -> {
            for (Function<Recorded, Boolean> m : matchers) {
                if (!Boolean.TRUE.equals(m.apply(r))) {
                    return false;
                }
            }
            return true;
        };
    }

    public static Function<Recorded, Boolean> method(String m) {
        return r -> m.equalsIgnoreCase(r.method());
    }

    public static Function<Recorded, Boolean> path(String p) {
        return r -> p.equals(r.path());
    }

    public static Function<Recorded, Boolean> header(String name, String value) {
        return r -> value.equals(r.header(name));
    }

    public static Function<Recorded, Boolean> queryParam(String name, String value) {
        return r -> value.equals(r.queryParam(name));
    }

    public static Function<Recorded, Boolean> queryParamMissing(String name) {
        return r -> r.queryParam(name) == null;
    }

    public static Function<Recorded, Boolean> queryParamContains(String name, String contains) {
        return r -> {
            String v = r.queryParam(name);
            return v != null && v.contains(contains);
        };
    }

    public static Function<Recorded, Boolean> bodyContains(String needle) {
        return r -> r.bodyText().contains(needle);
    }

    /** wiremock body_partial_json：body JSON 的顶层键包含给定的键与相等值。 */
    public static Function<Recorded, Boolean> bodyPartialJson(JsonNode partial) {
        return r -> {
            try {
                JsonNode body = r.bodyJson();
                for (var it = partial.fields(); it.hasNext();) {
                    var e = it.next();
                    if (!body.has(e.getKey()) || !body.get(e.getKey()).equals(e.getValue())) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        };
    }
}
