package tech.seexun.jknow.web.knowledge.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 脚本化的 chat 模型端点（known_flow / out_of_credit 测试与 Rust 侧 axum 桩同一档）：
 * JDK {@link HttpServer} 起 {@code POST /chat/completions}，脚本里的字符串就是流里的
 * {@code delta.content}。与 errata_tests.rs::reply 同一形状（text/event-stream +
 * {@code usage} 收尾）。{@code null} 脚本项 = OpenAI 形态的空余额 429
 * （insufficient_quota，#1095）——按 {@code out_of_credit} 的分类走，不重试。
 *
 * <p>不打真模型：请求体被记下供提示词断言（known 清单在不在第二个块的提示词里）。
 */
public final class ScriptedModelServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 脚本项：String = 一帧 content；null = 空余额 429。 */
    // 脚本允许 null 项（空余额 429）——Concurrent/Deque 系都不接 null，用同步 List
    private final java.util.List<String> script = new ArrayList<>();
    /**
     * 工具回合脚本（M4 任务 4）：带 {@code tools} 字段的 chat 请求走非流式
     * {@code choices[0].message}——每项是 message 对象的 JSON（content 或 tool_calls）。
     * 脚本用尽回 500（REJECTED）让漏排脚本的测试当场红掉，而不是悄悄演成「说了话没调工具」。
     */
    private final java.util.List<String> toolScript = new ArrayList<>();
    /** 第一个请求挂起直到 release（「编辑发生在模型请求途中」那族测试）。 */
    private volatile boolean hold;
    private final Object holdGate = new Object();
    private final java.util.concurrent.atomic.AtomicInteger embeds =
            new java.util.concurrent.atomic.AtomicInteger();
    private final List<JsonNode> requests = new ArrayList<>();
    private final HttpServer server;
    /**
     * usage 帧报的 token 数（可配——errata 测试按 Rust 端点报的 123/45 断言
     * errata_runs 的账；默认 12/34 沿 known_flow 起的形状，改默认会动一批既有断言）。
     */
    private volatile int usagePrompt = 12;
    private volatile int usageCompletion = 34;

    private ScriptedModelServer(HttpServer server) {
        this.server = server;
    }

    public static ScriptedModelServer start(List<String> replies) throws IOException {
        return start(replies, List.of());
    }

    /** 带工具回合脚本的形态（治理第二层的 investigate 循环）。 */
    public static ScriptedModelServer start(List<String> replies, List<String> toolTurns)
            throws IOException {
        // getLoopbackAddress() 不走名字解析——字符串形式在个别环境下会做一次不必要的查名
        HttpServer server = HttpServer.create(
                new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        ScriptedModelServer stub = new ScriptedModelServer(server);
        stub.script.addAll(replies);
        stub.toolScript.addAll(toolTurns);
        server.createContext("/embeddings", exchange -> {
            stub.embeds.incrementAndGet();
            com.fasterxml.jackson.databind.node.ArrayNode input;
            try {
                input = (com.fasterxml.jackson.databind.node.ArrayNode)
                        JSON.readTree(exchange.getRequestBody().readAllBytes()).get("input");
            } catch (IOException | ClassCastException e) {
                input = JSON.createArrayNode();
            }
            com.fasterxml.jackson.databind.node.ArrayNode data = JSON.createArrayNode();
            if (input != null) {
                for (JsonNode t : input) {
                    // 每段文字一个由字节算出的四维向量：只要求确定、条数对得上
                    byte[] bytes = t.asText().getBytes(StandardCharsets.UTF_8);
                    com.fasterxml.jackson.databind.node.ArrayNode v = JSON.createArrayNode();
                    for (int i = 0; i < 4; i++) {
                        float sum = 0f;
                        for (int k = i; k < bytes.length; k += 4) {
                            sum += (bytes[k] & 0xff) / 255.0f;
                        }
                        v.add(sum);
                    }
                    data.addObject().set("embedding", v);
                }
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
            boolean first;
            synchronized (stub.requests) {
                stub.requests.add(body);
                first = stub.requests.size() == 1;
            }
            if (first && stub.hold) {
                synchronized (stub.holdGate) {
                    try {
                        stub.holdGate.wait(20_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            // 工具回合（治理第二层 chatTools）：非流式 JSON——脚本项即 choices[0].message
            if (body.has("tools")) {
                String turn;
                synchronized (stub.toolScript) {
                    turn = stub.toolScript.isEmpty() ? null : stub.toolScript.remove(0);
                }
                byte[] payload;
                int status;
                if (turn != null) {
                    JsonNode message = JSON.readTree(turn);
                    ObjectNode reply = JSON.createObjectNode();
                    reply.put("id", "chatcmpl-scripted-tools");
                    reply.put("model", body.path("model").asText("scripted"));
                    ObjectNode choice = reply.putArray("choices").addObject();
                    choice.set("message", message);
                    choice.put("finish_reason", "stop");
                    payload = reply.toString().getBytes(StandardCharsets.UTF_8);
                    status = 200;
                } else {
                    payload = "{\"error\":{\"message\":\"tool script exhausted\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    status = 500;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                return;
            }
            String text;
            synchronized (stub.script) {
                text = stub.script.isEmpty() ? null : stub.script.remove(0);
            }
            byte[] payload;
            int status;
            if (text != null) {
                // 与 known_flow_tests.rs::reply 同一帧序：content → done(finish_reason=stop)
                // → usage → [DONE]
                String frame = "{\"choices\":[{\"delta\":{\"content\":"
                        + JSON.writeValueAsString(text) + "}}]}";
                String done = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
                String usage = "{\"choices\":[],\"usage\":{\"prompt_tokens\":" + stub.usagePrompt
                        + ",\"completion_tokens\":" + stub.usageCompletion + "}}";
                payload = ("data: " + frame + "\n\ndata: " + done + "\n\ndata: " + usage
                        + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else {
                // out_of_credit_tests.rs::reply 的 None 档：OpenAI 的余额耗尽走 429 +
                // insufficient_quota（LlmError 据此分类为 OUT_OF_CREDIT 而非 RATE_LIMITED）
                String err = "{\"error\": {\"message\": \"You exceeded your current quota.\","
                        + " \"type\": \"insufficient_quota\","
                        + " \"code\": \"credit_balance_exhausted\"}}";
                payload = err.getBytes(StandardCharsets.UTF_8);
                status = 429;
            }
            exchange.getResponseHeaders().set("Content-Type",
                    status == 200 ? "text/event-stream" : "application/json");
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

    /** 配 usage 帧报的 token 数（errata 测试按 Rust 端点 123/45 断言 errata_runs 的账）。 */
    public ScriptedModelServer usage(int promptTokens, int completionTokens) {
        this.usagePrompt = promptTokens;
        this.usageCompletion = completionTokens;
        return this;
    }

    public List<JsonNode> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    /** 挂起第一个请求（已进入处理器的信号：请求已到）。 */
    public void holdFirstRequest() {
        hold = true;
    }

    public void releaseFirstRequest() {
        hold = false;
        synchronized (holdGate) {
            holdGate.notifyAll();
        }
    }

    int embedCalls() {
        return embeds.get();
    }

    /** 等到第一个请求已经进门（hold 生效的确定性等待）。 */
    public void awaitRequestArrived(int atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (requests) {
                if (requests.size() >= atLeast) {
                    return;
                }
            }
            Thread.sleep(20);
        }
    }

    int remainingScript() {
        synchronized (script) {
            return script.size();
        }
    }

    public void stop() {
        server.stop(0);
    }
}
