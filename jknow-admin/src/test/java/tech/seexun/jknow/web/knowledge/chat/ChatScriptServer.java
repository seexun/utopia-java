package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 脚本化的 chat 模型端点（chat_empty_reply_tests.rs 的 Scripted/wiremock 桩同位）：
 * JDK HttpServer 起 POST /chat/completions 与 /embeddings。带工具的<b>流式</b>请求
 * （对话循环的形状）按脚本回 SSE；脚本里的字符串就是流里的 delta.content。每次请求的
 * 正文都记下，好查「重问那一次问了什么」「历史裁到多少」。
 *
 * <p>/embeddings 同时起：记录每次请求的输入，每条回同一个方向（rank_by_question 的
 * 假嵌入服务档）。
 */
public final class ChatScriptServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<ScriptedReply> script = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<List<String>> embedRequests = new CopyOnWriteArrayList<>();
    /** 嵌入回的固定向量（默认 (1,0,0)：画像 [1,0,0] 的同名实体是「近」的那个）。 */
    private volatile List<Float> embedVector = List.of(1f, 0f, 0f);
    /** 停止语义的档：每个 SSE 响应先写帧、发 gateOpened、等 releaseGate，再写 [DONE]。 */
    final java.util.concurrent.CountDownLatch gateOpened =
            new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch releaseGate =
            new java.util.concurrent.CountDownLatch(1);
    private volatile boolean holdBeforeDone;
    /** 总时限测试的档：第 delayFromRequest 次请求起，响应头先迟到 delayMillis 再写——
     * 量到响应头为止的请求超时才切得断它（holdBeforeDone 是头已到、体停住，切不断）。 */
    private volatile int delayFromRequest;
    private volatile long delayMillis;
    private final HttpServer server;

    private ChatScriptServer(HttpServer server) {
        this.server = server;
    }

    public static ChatScriptServer start(List<ScriptedReply> replies) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        ChatScriptServer stub = new ChatScriptServer(server);
        stub.script.addAll(replies);
        server.createContext("/embeddings", exchange -> {
            List<String> texts = new ArrayList<>();
            try {
                for (JsonNode t : JSON.readTree(exchange.getRequestBody().readAllBytes())
                        .path("input")) {
                    texts.add(t.asText());
                }
            } catch (IOException e) {
                // 解不开按空批
            }
            stub.embedRequests.add(texts);
            ArrayNode data = JSON.createArrayNode();
            for (int i = 0; i < texts.size(); i++) {
                ArrayNode v = JSON.createArrayNode();
                stub.embedVector.forEach(v::add);
                data.addObject().put("index", i).set("embedding", v);
            }
            byte[] out = JSON.createObjectNode().set("data", data).toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(out);
            }
        });
        server.createContext("/chat/completions", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonNode body;
            try {
                body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            } catch (IOException e) {
                body = JSON.createObjectNode();
            }
            int n = stub.requests.size() + 1;
            stub.requests.add(body);
            if (stub.delayFromRequest > 0 && n >= stub.delayFromRequest && stub.delayMillis > 0) {
                try {
                    Thread.sleep(stub.delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // requestAware 的档（legacyOnly）对每个请求按形状作答，脚本位不耗尽
            ScriptedReply reply;
            if (n <= stub.script.size()) {
                reply = stub.script.get(n - 1);
            } else if (!stub.script.isEmpty() && stub.script.get(stub.script.size() - 1)
                    .requestAware()) {
                reply = stub.script.get(stub.script.size() - 1);
            } else {
                reply = ScriptedReply.empty();
            }
            byte[] payload;
            int status;
            String contentType;
            if (reply instanceof ScriptedReply.Http http) {
                status = http.status();
                contentType = http.body() != null && http.body().trim().startsWith("{")
                        ? "application/json" : "text/plain";
                payload = (http.body() == null ? "Evidence gathering complete" : http.body())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                return;
            }
            status = 200;
            contentType = "text/event-stream";
            String sse;
            try {
                sse = reply.requestAware() ? reply.sse(JSON, n, body) : reply.sse(JSON, n);
            } catch (ScriptedReply.HttpWithBody http) {
                status = http.status;
                contentType = "application/json";
                payload = http.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                return;
            }
            payload = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            // 停止语义的档：帧写出、flush、通知到位，然后等放行——Stop 打在流中途
            if (stub.holdBeforeDone && sse.endsWith("data: [DONE]\n\n")
                    && sse.startsWith("data:")) {
                byte[] head = sse.substring(0, sse.length() - "data: [DONE]\n\n".length())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, head.length
                        + "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(head);
                    out.flush();
                    stub.gateOpened.countDown();
                    try {
                        stub.releaseGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return stub;
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<JsonNode> requests() {
        return new ArrayList<>(requests);
    }

    /** 每次嵌入请求一行，行里是那次嵌的文字。 */
    public List<List<String>> embedRequests() {
        return new ArrayList<>(embedRequests);
    }

    public ChatScriptServer embedVector(List<Float> vector) {
        this.embedVector = vector;
        return this;
    }

    /** 开启「写帧后停住等放行」档（Stop 打在模型流中途的测试形状）。 */
    public ChatScriptServer holdBeforeDone() {
        this.holdBeforeDone = true;
        return this;
    }

    /** 从第 {@code requestNumber} 次请求起（含），响应头迟到 {@code millis}（1 起数）。 */
    public ChatScriptServer delayResponsesFrom(int requestNumber, long millis) {
        this.delayFromRequest = requestNumber;
        this.delayMillis = millis;
        return this;
    }

    public void awaitGate() throws InterruptedException {
        if (!gateOpened.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("model gate never opened");
        }
    }

    public void release() {
        releaseGate.countDown();
    }

    public void stop() {
        server.stop(0);
    }
}
