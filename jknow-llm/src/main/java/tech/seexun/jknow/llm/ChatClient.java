package tech.seexun.jknow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * OpenAI 兼容对话客户端（语义照搬旧仓库 utopia-llm/src/lib.rs 的 {@code LlmClient}）：
 * {@code POST {base_url}/chat/completions}，Bearer 密钥，<b>走流式 SSE 但整段返回</b>
 * （lib.rs:634-748 {@code chat_at_streaming}）。
 *
 * <p><b>为什么长提示词的几条路都走流式</b>：读超时量的是「多久没有新字节」，而非流式调用的
 * 第一个字节要等模型把整段生成完——模型思考的时间于是全部算作沉默，开着推理时一块密集
 * 正文实测首字节 227 秒、偶尔越过 300 秒被判死；流式下思考过程就是字节，超时只杀真正
 * 卡住的请求。返回值仍是整段。
 *
 * <p><b>错误分类做成类型而不是文本匹配</b>（lib.rs:114-240 同理由）：调用链上任何一层包一层
 * 都会改文本，而 {@link #transient(Exception)}/{@link #outOfCredit(Exception)} 要据此决定
 * 「等一会儿再来」还是「整篇终止」。本侧以 {@link LlmError} 一个异常类型 + kind 字段承载
 * Rust 侧的 RateLimited / Unavailable / OutOfCredit / Interrupted / Unreachable 五类，
 * 判定函数与 Rust 的同名自由函数一一对应。
 *
 * <p>与既有 {@link EmbedClient} 同风格：JDK HttpClient 零新依赖。SSE 缓冲按字节攒帧
 * （UTF-8 可能断在字符中间），LF/CRLF/裸 CR 都算行尾。
 *
 * <p><b>未移植（审查 I-5，台账记行）</b>：lib.rs:655-683 的补全上限自适应——400 且端点
 * 自述更低的补全上限（#891/#892）时记住降下来的值、按它立刻重试一次；本侧
 * {@code max_tokens} 恒为 {@link #MAX_COMPLETION_TOKENS}，撞上限走 REJECTED 不降级。
 * 归属后续里程碑（共享库写入侧无此状态，属客户端协商）。
 */
public final class ChatClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** lib.rs:447 CONNECT_TIMEOUT：建连 10 秒。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * lib.rs:464 READ_TIMEOUT：300 秒没有新字节算这个请求死了。量的是<b>沉默</b>不是总时长
     * ——流式下 token 持续到达，永远碰不到它。没有它的代价实测过：7459 块的灌入死在
     * 第 55 块上、一条错误都不报。JDK HttpClient 没有读超时原语，这里以请求级
     * timeout 近似（流式下首帧到尾帧的间隔远小于它），并保留 Interrupted 判定兜「流断在
     * 半路」的那一类。
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    /** lib.rs:485 MAX_COMPLETION_TOKENS：上限归我们，不归端点的默认 4096（#760）。 */
    static final int MAX_COMPLETION_TOKENS = 65_536;

    private static final int MAX_ERROR_DETAIL_CHARS = 500;

    /** lib.rs:10-15 ChatMessage：role + content 两条就够（这条路上没有工具回合）。 */
    public record ChatMessage(String role, String content) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }
    }

    /**
     * lib.rs:377-398 Reply：整段答案 + 端点给的收尾原因。{@code finish_reason=length}
     * 是端点说「我是被截断的」——解析器只看得见 JSON 少了尾巴，看不见少的原因。
     */
    public record Reply(String text, String finishReason, Usage usage) {

        /** lib.rs:395-397：端点说这段是被 token 上限截断的。 */
        public boolean hitTokenCeiling() {
            return "length".equals(finishReason);
        }
    }

    /**
     * lib.rs:15-27 ToolCall：一次函数调用（id 供结果回合指回）。{@code extraContent}
     * 是端点挂在调用上的私货，原样带回：Gemini 3 在每个 function call 上给一个
     * {@code extra_content.google.thought_signature}，同一回合把结果送回去时<b>必须</b>
     * 原样附上，否则 400「Function call is missing a thought_signature」；上一回合的
     * 可以不带。内容不解读，不是对象就当没有（lib.rs:88 extra_content_of 同判）。
     */
    public record ToolCall(String id, String name, String arguments, JsonNode extraContent) {
    }

    /**
     * lib.rs:33-58 AssistantTurn：工具对话的一个 assistant 回合——文本与工具调用至少其
     * 一。{@link #toMessage()} 还原为 OpenAI 协议的 assistant 消息（回灌对话历史用）。
     */
    public static final class AssistantTurn {

        public final String finishReason;
        public final String content;
        public final List<ToolCall> toolCalls;

        public AssistantTurn(String finishReason, String content, List<ToolCall> toolCalls) {
            this.finishReason = finishReason;
            this.content = content;
            this.toolCalls = toolCalls;
        }

        /** lib.rs:40-53 to_message：assistant 回合还原为协议消息（带 tool_calls 原样回灌，extra_content 跟行）。 */
        public ObjectNode toMessage() {
            ObjectNode msg = JSON.createObjectNode();
            msg.put("role", "assistant");
            if (content != null) {
                msg.put("content", content);
            } else {
                msg.putNull("content");
            }
            if (!toolCalls.isEmpty()) {
                ArrayNode calls = msg.putArray("tool_calls");
                for (ToolCall c : toolCalls) {
                    ObjectNode call = calls.addObject();
                    call.put("id", c.id());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", c.name());
                    fn.put("arguments", c.arguments());
                    if (c.extraContent() != null) {
                        call.set("extra_content", c.extraContent());
                    }
                }
            }
            return msg;
        }
    }

    /** lib.rs:83-85 tool_result_message：工具结果消息（role=tool）。 */
    public static ObjectNode toolResultMessage(String toolCallId, String content) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("content", content);
        return msg;
    }

    /** lib.rs:388-391 Usage：端点自己报的用量，不报不编。 */
    public record Usage(long promptTokens, long completionTokens) {
    }

    /**
     * 分类异常（Rust 侧五个 thiserror 类型合一，kind 即类型名）。
     * {@code retryAfter} 只在限流/不可用上有（多数厂商不发 Retry-After，有则更准）。
     */
    public static final class LlmError extends IOException {

        public enum Kind {
            /** lib.rs:114 Unreachable：连不上（DNS/连接/TLS/读超时），或 2xx 却解不成 JSON。 */
            UNREACHABLE,
            /** lib.rs:128 RateLimited：429——它会自己好，等一会儿再来。 */
            RATE_LIMITED,
            /** lib.rs:185 Unavailable：408/502/503/504——网关抽风，也会自己好。 */
            UNAVAILABLE,
            /** lib.rs:209 OutOfCredit：欠费/配额尽——不会自己好，整篇终止（#1095）。 */
            OUT_OF_CREDIT,
            /** lib.rs:171 Interrupted：流断在半路，既没有 [DONE] 也没有 finish_reason。 */
            INTERRUPTED,
            /**
             * lib.rs:248 ContextTooLong：提示超过了模型的上下文窗口——既不是「不支持工具」
             * 也不是补全上限（#964）。对话域据此裁历史重发，而不是降级。
             */
            CONTEXT_TOO_LONG,
            /** lib.rs:228 Rejected：其余非 2xx——端点回答了「不」。 */
            REJECTED
        }

        private final Kind kind;
        private final int status;
        /** Retry-After 的整数秒（lib.rs:276-285：HTTP-date 形态不解析，按没有处理）。 */
        private final Duration retryAfter;
        /**
         * 仅 CONTEXT_TOO_LONG：端点报出的窗口（token），没报或不可信就是 -1
         * （lib.rs stated_context_window——只认紧跟着「窗口」的那个数）。
         */
        private final int contextWindow;

        public LlmError(Kind kind, int status, Duration retryAfter, String message) {
            this(kind, status, retryAfter, message, -1);
        }

        public LlmError(Kind kind, int status, Duration retryAfter, String message,
                        int contextWindow) {
            super(message);
            this.kind = kind;
            this.status = status;
            this.retryAfter = retryAfter;
            this.contextWindow = contextWindow;
        }

        /** 端点在拒绝里报出的上下文窗口（token）；-1 = 没说或不可信。 */
        public int contextWindow() {
            return contextWindow;
        }

        public Kind kind() {
            return kind;
        }

        public int status() {
            return status;
        }

        public Duration retryAfter() {
            return retryAfter;
        }

        /** Rust downcast_ref 语义：异常链上任何一层是 LlmError 且 kind 匹配就算。 */
        static LlmError of(Throwable t, Kind kind) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof LlmError e && e.kind == kind) {
                    return e;
                }
            }
            return null;
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    /** OpenAI 兼容的 reasoning_effort（lib.rs:516-526）；空则不带字段。 */
    private final String reasoningEffort;
    /**
     * 端点报过的上下文窗口（token），起初 Integer.MAX_VALUE。同一个客户端此后都按它算
     * 历史预算，只降不升（lib.rs history_char_budget / remember_context_window，#964）。
     * 对话客户端按工作区缓存（见 server 侧 ChatClients），下一轮也记得。
     */
    private final AtomicInteger contextWindow = new AtomicInteger(Integer.MAX_VALUE);

    public ChatClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, null);
    }

    public ChatClient(String baseUrl, String apiKey, String model, String reasoningEffort) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                ? null : reasoningEffort;
    }

    /**
     * lib.rs:150-165 transient：值得再试一次的那几类失败，连它们各自的「等多久」。
     * 判据是「它会自己好」：限流、端点不可用、流半路断了、请求根本没送到（连接被拒/
     * DNS 失败/建连超时——审查 I-3）。<b>读超时不在里面</b>：一个总是超时的调用不会
     * 因为多等而变好——Rust 以 {@code is_timeout()} 把它滤掉，这里以非连接档的
     * {@link java.net.http.HttpTimeoutException} 对等（请求级 timeout 量的是「多久没有
     * 新字节」，建连超时是 {@link java.net.http.HttpConnectTimeoutException} 子类、算
     * 「没送到」可重试）。
     */
    public static LlmError transientHit(Throwable t) {
        LlmError hit = LlmError.of(t, LlmError.Kind.RATE_LIMITED);
        if (hit != null) {
            return hit;
        }
        hit = LlmError.of(t, LlmError.Kind.UNAVAILABLE);
        if (hit != null) {
            return hit;
        }
        hit = LlmError.of(t, LlmError.Kind.INTERRUPTED);
        if (hit != null) {
            return hit;
        }
        LlmError sending = LlmError.of(t, LlmError.Kind.UNREACHABLE);
        return sending != null && !causedByReadTimeout(t) ? sending : null;
    }

    /** 错误链里有没有「读超时」形态（非连接档的 HttpTimeoutException——is_timeout 过滤）。 */
    private static boolean causedByReadTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.net.http.HttpTimeoutException
                    && !(c instanceof java.net.http.HttpConnectTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    /**
     * lib.rs:215-217 out_of_credit：账号付不起这次请求。跟限流分开，因为它不会自己好
     * ——重试只是把同一个错误说三遍（#1095：一次跑测里 14 篇文档因为它整篇失败）。
     */
    public static LlmError outOfCredit(Throwable t) {
        return LlmError.of(t, LlmError.Kind.OUT_OF_CREDIT);
    }

    /**
     * lib.rs rate_limited（alerts.rs alert_for 的中间档）：端点在限流。告警分类要把它与
     * 欠费、不可达分开——该做的事不同（降并发或升配额，而不是充值或查网络）。
     */
    public static LlmError rateLimited(Throwable t) {
        return LlmError.of(t, LlmError.Kind.RATE_LIMITED);
    }

    /** lib.rs:119-121 is_unreachable：连不上（含读超时——u.0.is_timeout() 在 Rust 侧对 Unreachable 不过滤）。 */
    public static boolean isUnreachable(Throwable t) {
        return LlmError.of(t, LlmError.Kind.UNREACHABLE) != null;
    }

    /**
     * 一次问答，走流式但整段返回（lib.rs:634 {@code chat_at_streaming}）。温度由调用方定：
     * 开放抽取这类「照抄原文」的活要 0（端点缺省 1.0 时同一块两次回复密度差三倍）。
     *
     * <p>思考过程不进返回值：只收 {@code delta.content}；{@code <think>} 混在 content 里的
     * 由 {@link #stripReasoning} 切掉（lib.rs:501-506：切掉<b>最后一个</b> {@code </think>}
     * 之前的一切，防着正文里引用它）。
     */
    public Reply chatAtStreaming(List<ChatMessage> messages, Double temperature)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode row = msgs.addObject();
            row.put("role", m.role());
            row.put("content", m.content());
        }
        body.put("stream", true);
        // 用量随最后一帧回来（lib.rs:643-645）：换成流式不能把这个数弄丢
        ObjectNode streamOptions = body.putObject("stream_options");
        streamOptions.put("include_usage", true);
        body.put("max_tokens", MAX_COMPLETION_TOKENS);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<java.io.InputStream> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException e) {
            // 请求没送到（连接被拒/DNS 失败/建连超时/中断）——lib.rs:164 的 sending 档。
            // cause 挂上：transientHit 的 is_timeout 过滤要沿链认 HttpTimeoutException
            LlmError err = new LlmError(LlmError.Kind.UNREACHABLE, 0, null,
                    "LLM endpoint gave no usable answer: " + e);
            err.initCause(e);
            throw err;
        }
        int status = resp.statusCode();
        if (status / 100 != 2) {
            throw failure(status, resp.headers().firstValue("retry-after").orElse(null),
                    readAll(resp.body()));
        }
        // SSE 帧以空行分隔；最后一个不完整的帧没有尾随空行，读完流后也要交给解析
        SseBuffer buf = new SseBuffer();
        StringBuilder answer = new StringBuilder();
        boolean[] sawFrame = {false};
        boolean[] ended = {false};
        String[] finishReason = {null};
        Usage[] usage = {null};
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            int c;
            StringBuilder line = new StringBuilder();
            boolean afterCr = false;
            while ((c = reader.read()) != -1) {
                char ch = (char) c;
                if (afterCr && ch == '\n') {
                    afterCr = false;
                    continue;
                }
                afterCr = ch == '\r';
                char norm = afterCr ? '\n' : ch;
                line.append(norm);
                if (norm == '\n') {
                    buf.pushLine(line.toString());
                    line.setLength(0);
                }
            }
            if (line.length() > 0) {
                buf.pushLine(line.toString());
            }
        } catch (IOException e) {
            LlmError err = new LlmError(LlmError.Kind.UNREACHABLE, status, null,
                    "LLM endpoint gave no usable answer: " + e);
            err.initCause(e);
            throw err;
        }
        buf.flushTail();
        for (String frame : buf.takeFrames()) {
            takeFrame(frame, answer, sawFrame, ended, finishReason, usage);
        }
        if (!sawFrame[0]) {
            throw new LlmError(LlmError.Kind.UNREACHABLE, status, null,
                    "LLM stream carried no frames");
        }
        // 端点开口了又半路没了（lib.rs:736-742）：拼到一半的回复长得像成功，不做成
        // 错误就会被当成模型给的全部答案
        if (!ended[0]) {
            throw new LlmError(LlmError.Kind.INTERRUPTED, status, null,
                    "LLM stream ended in the middle of the answer ("
                            + answer.codePointCount(0, answer.length()) + " chars in)");
        }
        return new Reply(stripReasoning(answer.toString()), finishReason[0], usage[0]);
    }

    /**
     * lib.rs:798-804/841-892 {@code chat_tools}（非流式）：工具对话——messages 为 OpenAI
     * 协议原始 JSON（支持 assistant.tool_calls 与 role=tool 回合），tools 为 function 定义
     * 数组。工具回合的回答多半是一次函数调用的几十个 token，非流式的首字节等待不成问题
     * （Rust 同款非流式）；<b>不带 max_tokens</b>（Rust tools_body 同——补全上限归端点默认，
     * 这条路没有「整段长答案被截」的形态）。解析 {@code choices[0].message}：content 只在
     * 非空字符串时给，tool_calls 缺字段的那一条整条丢弃（Rust filter_map 同判）。
     */
    public AssistantTurn chatTools(List<JsonNode> messages, JsonNode tools)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        for (JsonNode m : messages) {
            msgs.add(m);
        }
        body.put("stream", false);
        body.set("tools", tools);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException e) {
            LlmError err = new LlmError(LlmError.Kind.UNREACHABLE, 0, null,
                    "LLM endpoint gave no usable answer: " + e);
            err.initCause(e);
            throw err;
        }
        int status = resp.statusCode();
        if (status / 100 != 2) {
            throw failure(status, resp.headers().firstValue("retry-after").orElse(null),
                    resp.body());
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body());
        } catch (IOException e) {
            throw new LlmError(LlmError.Kind.UNREACHABLE, status, null,
                    "LLM endpoint gave no usable answer: " + e);
        }
        JsonNode msg = root.path("choices").path(0).path("message");
        if (msg.isMissingNode() || msg.isNull()) {
            throw new LlmError(LlmError.Kind.UNREACHABLE, status, null,
                    "Unexpected LLM response shape: " + root);
        }
        String content = msg.path("content").asText(null);
        if (content != null && content.isEmpty()) {
            content = null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            String id = c.path("id").asText(null);
            String name = c.path("function").path("name").asText(null);
            if (id == null || name == null) {
                continue;
            }
            JsonNode extra = c.path("extra_content");
            calls.add(new ToolCall(id, name,
                    c.path("function").path("arguments").asText("{}"),
                    extra.isObject() ? extra : null));
        }
        return new AssistantTurn(
                root.path("choices").path(0).path("finish_reason").asText(null),
                content, calls);
    }

    // ---- 对话域的流式档（M5 任务 4：chat loop 的增量通道） -----------------------

    /**
     * 历史的字符预算（lib.rs:558-567 history_char_budget）。不是分词器估算：端点报过
     * 窗口就取窗口的<b>一半</b>当字数——中文差不多一字一 token，按 token 数原样当字数会在
     * 小窗口上超出（#973 评审）——另一半留给提示、工具和回答；再夹在 2000 与 32000 之间。
     * 估得不准的那一次由一轮一次的恢复兜着。
     */
    public int historyCharBudget() {
        long window = contextWindow.get() & 0xFFFFFFFFL;
        long half = (window / 2);
        return (int) Math.max(MIN_HISTORY_CHARS, Math.min(DEFAULT_HISTORY_CHARS, half));
    }

    /** 端点在拒绝里说了自己的窗口就记住它，只降不升（lib.rs:569-576）。 */
    public void rememberContextWindow(Throwable err) {
        LlmError hit = LlmError.of(err, LlmError.Kind.CONTEXT_TOO_LONG);
        if (hit != null && hit.contextWindow > 0) {
            contextWindow.getAndAccumulate(hit.contextWindow, Math::min);
        }
    }

    /** 错误链里是不是「提示超过了上下文窗口」（lib.rs:268 context_too_long）。 */
    public static LlmError contextTooLong(Throwable t) {
        return LlmError.of(t, LlmError.Kind.CONTEXT_TOO_LONG);
    }

    /** 历史预算的缺省与下限（lib.rs:320-327 同款）。 */
    static final int DEFAULT_HISTORY_CHARS = 32_000;
    static final int MIN_HISTORY_CHARS = 2_000;
    /** 比这小的「窗口」不信（lib.rs MIN_CONTEXT_WINDOW_TOKENS）：没有模型的窗口那么小。 */
    static final int MIN_CONTEXT_WINDOW_TOKENS = 1024;

    /**
     * 带工具的<b>流式</b>对话（lib.rs:907 chat_tools_stream_with）：正文增量即时交给
     * {@code onDelta}（对话循环逐行外发/扣行就在这个回调里），工具调用按 OpenAI 协议的
     * index 分片归并（id/name 首帧到达、arguments 逐帧续传），流末交回完整回合。
     *
     * <p>不带 max_tokens（与 {@link #chatTools} 同——工具回合的补全上限归端点默认）。
     * 端点开口了又半路没了（没有 [DONE] 也没有 finish_reason）按 Interrupted 抛——拼到
     * 一半的回合不能当完整回合交给循环。
     */
    public AssistantTurn chatToolsStream(List<JsonNode> messages, JsonNode tools,
                                         JsonNode toolChoice, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        return chatToolsStream(messages, tools, toolChoice, onDelta, REQUEST_TIMEOUT);
    }

    /**
     * 带每次请求时限的档：收尾作答的 120s 总时限盖住两次尝试（chat_finalization.rs 的
     * tokio::time::timeout 对位）——每一试的请求超时取「deadline 减现在」，第一次耗掉的
     * 时间第二次不补。时限量法仍是 {@link #REQUEST_TIMEOUT} 的口径（到响应头为止的
     * 近似，读体沉默由缺省档兜底）。
     */
    public AssistantTurn chatToolsStream(List<JsonNode> messages, JsonNode tools,
                                         JsonNode toolChoice, Consumer<String> onDelta,
                                         Duration requestTimeout)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        for (JsonNode m : messages) {
            msgs.add(m);
        }
        body.put("stream", true);
        // tools 为 null 就不带工具字段——不是空数组：有的端点见到 "tools": [] 会 400
        if (tools != null && !tools.isNull()) {
            body.set("tools", tools);
            if (toolChoice != null && !toolChoice.isNull()) {
                body.set("tool_choice", toolChoice);
            }
        }
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        HttpResponse<java.io.InputStream> resp = post(body, requestTimeout);
        java.io.InputStream in = resp.body();
        StringBuilder content = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        String[] finishReason = {null};
        boolean[] done = {false};
        boolean[] sawFrame = {false};
        forEachSseFrame(in, frame -> {
            for (String line : frame.split("\n", -1)) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.equals("[DONE]")) {
                    done[0] = true;
                    return;
                }
                JsonNode v;
                try {
                    v = JSON.readTree(data);
                } catch (IOException e) {
                    continue;
                }
                sawFrame[0] = true;
                JsonNode reason = v.path("choices").path(0).path("finish_reason");
                if (reason.isTextual()) {
                    finishReason[0] = reason.asText();
                    done[0] = true;
                }
                JsonNode delta = v.path("choices").path(0).path("delta");
                JsonNode text = delta.path("content");
                if (text.isTextual() && !text.asText().isEmpty()) {
                    content.append(text.asText());
                    if (onDelta != null) {
                        onDelta.accept(text.asText());
                    }
                }
                for (JsonNode tc : delta.path("tool_calls")) {
                    int idx = tc.path("index").asInt(0);
                    while (calls.size() <= idx) {
                        calls.add(null);
                    }
                    ToolCall slot = calls.get(idx);
                    if (slot == null) {
                        slot = new ToolCall("", "", "", null);
                        calls.set(idx, slot);
                    }
                    // extra_content 端点挂在调用上的私货原样带回（Gemini 3 的 thought_signature）
                    if (tc.path("extra_content").isObject()) {
                        slot = new ToolCall(slot.id(), slot.name(), slot.arguments(),
                                tc.path("extra_content"));
                        calls.set(idx, slot);
                    }
                    JsonNode id = tc.path("id");
                    if (id.isTextual()) {
                        slot = new ToolCall(slot.id() + id.asText(), slot.name(),
                                slot.arguments(), slot.extraContent());
                        calls.set(idx, slot);
                    }
                    JsonNode fn = tc.path("function").path("name");
                    if (fn.isTextual()) {
                        slot = new ToolCall(slot.id(), slot.name() + fn.asText(),
                                slot.arguments(), slot.extraContent());
                        calls.set(idx, slot);
                    }
                    JsonNode args = tc.path("function").path("arguments");
                    if (args.isTextual()) {
                        slot = new ToolCall(slot.id(), slot.name(),
                                slot.arguments() + args.asText(), slot.extraContent());
                        calls.set(idx, slot);
                    }
                }
            }
        });
        // 只有 [DONE] 也算一整回合（空回复档）：Rust chat_tools_stream_with 没有
        // 「至少一帧 JSON」的要求——空回合是策略层（EMPTY_REPLY_RETRY）要认的形态
        if (!done[0]) {
            // HTTP 正常结束也可能只送到半个模型回合，不能把没收完的工具调用当完整回合
            throw new LlmError(LlmError.Kind.INTERRUPTED, resp.statusCode(), null,
                    "LLM stream ended in the middle of the answer ("
                            + content.codePointCount(0, content.length()) + " chars in)");
        }
        calls.removeIf(c -> c == null || c.name().isEmpty());
        String text = content.length() == 0 ? null : content.toString();
        return new AssistantTurn(finishReason[0], text, calls);
    }

    /**
     * 无工具的流式对话（lib.rs:1013 chat_stream_raw）：原始 JSON 消息（可携带工具回合
     * 上下文——legacy RAG 兜底），增量文本即时交给 {@code onDelta}。流断在半路按
     * Interrupted 抛。
     */
    public void chatStreamRaw(List<JsonNode> messages, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        for (JsonNode m : messages) {
            msgs.add(m);
        }
        body.put("stream", true);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        HttpResponse<java.io.InputStream> resp = post(body);
        boolean[] ended = {false};
        long[] got = {0};
        forEachSseFrame(resp.body(), frame -> {
            for (String line : frame.split("\n", -1)) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.equals("[DONE]")) {
                    ended[0] = true;
                    return;
                }
                try {
                    JsonNode v = JSON.readTree(data);
                    if (v.path("choices").path(0).path("finish_reason").isTextual()) {
                        ended[0] = true;
                    }
                    JsonNode delta = v.path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        got[0] += delta.asText().codePointCount(0, delta.asText().length());
                        if (onDelta != null) {
                            onDelta.accept(delta.asText());
                        }
                    }
                } catch (IOException notJson) {
                    // 与 Rust 同：解不开的帧跳过
                }
            }
        });
        if (!ended[0]) {
            throw new LlmError(LlmError.Kind.INTERRUPTED, resp.statusCode(), null,
                    "LLM stream ended in the middle of the answer (" + got[0] + " chars in)");
        }
    }

    /**
     * 精确的流式请求体字节数（lib.rs:834 tool_free_request_bytes）：收尾的无工具请求
     * 在任何网络 I/O 之前用它对 1MiB 的上下文上限把关。
     */
    public int toolFreeRequestBytes(List<JsonNode> messages) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        for (JsonNode m : messages) {
            msgs.add(m);
        }
        body.put("stream", true);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        return body.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** 共用的 POST + 状态分类（失败在 failure() 里成型，含 CONTEXT_TOO_LONG 档）。 */
    private HttpResponse<java.io.InputStream> post(ObjectNode body)
            throws IOException, InterruptedException {
        return post(body, REQUEST_TIMEOUT);
    }

    private HttpResponse<java.io.InputStream> post(ObjectNode body, Duration requestTimeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<java.io.InputStream> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException e) {
            LlmError err = new LlmError(LlmError.Kind.UNREACHABLE, 0, null,
                    "LLM endpoint gave no usable answer: " + e);
            err.initCause(e);
            throw err;
        }
        if (resp.statusCode() / 100 != 2) {
            throw failure(resp.statusCode(),
                    resp.headers().firstValue("retry-after").orElse(null),
                    readAll(resp.body()));
        }
        return resp;
    }

    /**
     * 逐帧消费 SSE 字节流（LF/CRLF/裸 CR 都算行尾；UTF-8 交给 BufferedReader 解码）。
     * 帧以空行分隔；末尾不跟空行的最后一帧也算——丢掉它就是丢掉答案的尾巴。
     */
    private static void forEachSseFrame(java.io.InputStream in,
                                        java.util.function.Consumer<String> frameConsumer)
            throws IOException {
        StringBuilder line = new StringBuilder();
        StringBuilder frame = new StringBuilder();
        boolean afterCr = false;
        int c;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            while ((c = reader.read()) != -1) {
                char ch = (char) c;
                if (afterCr && ch == '\n') {
                    afterCr = false;
                    continue;
                }
                afterCr = ch == '\r';
                char norm = afterCr ? '\n' : ch;
                if (norm == '\n') {
                    if (line.length() == 0) {
                        if (frame.length() > 0) {
                            frameConsumer.accept(frame.toString());
                            frame.setLength(0);
                        }
                    } else {
                        if (frame.length() > 0) {
                            frame.append('\n');
                        }
                        frame.append(line);
                        line.setLength(0);
                    }
                } else {
                    line.append(norm);
                }
            }
        }
        if (line.length() > 0 || frame.length() > 0) {
            if (line.length() > 0) {
                if (frame.length() > 0) {
                    frame.append('\n');
                }
                frame.append(line);
            }
            if (frame.length() > 0) {
                frameConsumer.accept(frame.toString());
            }
        }
    }

    /**
     * lib.rs:751-795 take_frame 的逐行等价：取 {@code data:} 增量、认 {@code [DONE]}、
     * 记 finish_reason（stop 正常收尾 / length 撞上输出上限，都算端点把话说完了）与用量。
     */
    private void takeFrame(String frame, StringBuilder answer, boolean[] sawFrame,
                           boolean[] ended, String[] finishReason, Usage[] usage) {
        for (String line : frame.split("\n", -1)) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).trim();
            if (data.equals("[DONE]")) {
                ended[0] = true;
                continue;
            }
            JsonNode v;
            try {
                v = JSON.readTree(data);
            } catch (IOException e) {
                continue;
            }
            sawFrame[0] = true;
            JsonNode delta = v.path("choices").path(0).path("delta").path("content");
            if (delta.isTextual()) {
                answer.append(delta.asText());
            }
            JsonNode reason = v.path("choices").path(0).path("finish_reason");
            if (reason.isTextual()) {
                ended[0] = true;
                finishReason[0] = reason.asText();
            }
            JsonNode u = v.path("usage");
            if (!u.isMissingNode() && !u.isNull()) {
                usage[0] = new Usage(u.path("prompt_tokens").asLong(0),
                        u.path("completion_tokens").asLong(0));
            }
        }
    }

    /**
     * lib.rs:291-350 failure：非 2xx 在这里成型，分四类。
     * <b>欠费要先判，而且不能只看状态码</b>：402 是标准答案，OpenAI 的余额耗尽走 429、
     * 靠 body 里的 {@code insufficient_quota} 区分。只按状态码分类会把没钱的账号当限流
     * 无限退避。500 不在不可用之列——它可以是端点自己的 bug，重试只是把同一个崩溃
     * 再触发一遍。
     */
    private static LlmError failure(int status, String retryAfterHeader, String raw) {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(raw);
        } catch (IOException e) {
            parsed = JSON.createObjectNode();
        }
        String detail = errDetail(parsed, raw);
        Duration retryAfter = parseRetryAfter(retryAfterHeader);
        if (status == 402 || saysOutOfCredit(parsed)) {
            return new LlmError(LlmError.Kind.OUT_OF_CREDIT, status, null,
                    "LLM account cannot pay for this request (" + status + "): " + detail);
        }
        if (status == 429) {
            return new LlmError(LlmError.Kind.RATE_LIMITED, status, retryAfter,
                    "LLM endpoint is rate limiting (" + status + "): " + detail);
        }
        if (status == 408 || status == 502 || status == 503 || status == 504) {
            return new LlmError(LlmError.Kind.UNAVAILABLE, status, retryAfter,
                    "LLM endpoint is unavailable (" + status + "): " + detail);
        }
        // 提示超过上下文窗口（lib.rs:335 failure 的 ContextTooLong 档）：既不是「不支持
        // 工具」也不是补全上限——对话域据此裁历史重发（#964），不降级
        if ((status == 400 || status == 413 || status == 422)
                && saysContextTooLong(parsed, detail)) {
            return new LlmError(LlmError.Kind.CONTEXT_TOO_LONG, status, null,
                    "LLM request failed (" + status + "): the prompt exceeds the model's "
                            + "context window: " + detail,
                    statedContextWindow(detail));
        }
        return new LlmError(LlmError.Kind.REJECTED, status, null,
                "LLM request failed (" + status + "): " + detail);
    }

    /**
     * lib.rs:1237 says_context_too_long：error.code/type 或顶层 code 说
     * {@code context_length_exceeded}，或 detail 里出现各家的「超长」措辞。账目说 messages
     * 装得下、超的是我们要的 completion 时不算（那是补全上限要按窗口剩的来）。
     */
    private static boolean saysContextTooLong(JsonNode body, String detail) {
        boolean says = false;
        for (String path : new String[]{"/error/code", "/error/type", "/code"}) {
            if ("context_length_exceeded".equals(atPointer(body, path))) {
                says = true;
            }
        }
        String lower = detail.toLowerCase();
        for (String wording : new String[]{"maximum context length", "context window",
                "too many tokens", "prompt is too long", "exceeds the available context"}) {
            if (lower.contains(wording)) {
                says = true;
            }
        }
        return says && completionOverflows(lower) == -1;
    }

    private static String atPointer(JsonNode body, String pointer) {
        JsonNode v = body.at(pointer);
        return v.isTextual() ? v.asText() : null;
    }

    /**
     * 超的是 completion 时窗口给回答留的余地（lib.rs completion_overflows）；messages 本身
     * 装不下（余地小于 1024，一段像样的回答都放不进）才是对话太长——返回 -1 表示不是这类。
     */
    private static int completionOverflows(String lowerDetail) {
        int window = statedContextWindow(lowerDetail);
        if (window <= 0) {
            return -1;
        }
        Long messages = numberBefore(lowerDetail, " in the messages");
        Long completion = numberBefore(lowerDetail, " in the completion");
        if (messages == null || completion == null) {
            return -1;
        }
        long room = window - messages;
        return (room >= MIN_CONTEXT_WINDOW_TOKENS && room < completion) ? (int) room : -1;
    }

    /**
     * lib.rs stated_context_window：只认紧跟着「窗口」的那个数（marker 后可跟 is/of/:/=），
     * 数字后还得跟 tokens；小于 {@link #MIN_CONTEXT_WINDOW_TOKENS} 的不信——那多半是别的数。
     */
    static int statedContextWindow(String detail) {
        String lower = detail.toLowerCase();
        for (String marker : new String[]{"maximum context length", "context window"}) {
            int at = lower.indexOf(marker);
            if (at < 0) {
                continue;
            }
            String rest = lower.substring(at + marker.length());
            rest = rest.replaceFirst("^[\\s:=]+", "");
            if (rest.startsWith("is ")) {
                rest = rest.substring(3);
            } else if (rest.startsWith("of ")) {
                rest = rest.substring(3);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([0-9][0-9,\\s]*)\\s*tokens?\\b").matcher(rest);
            if (m.find()) {
                try {
                    long n = Long.parseLong(m.group(1).replaceAll("[,\\s]", ""));
                    return n >= MIN_CONTEXT_WINDOW_TOKENS ? (int) n : -1;
                } catch (NumberFormatException ignore) {
                    // 数字过大：按没说处理
                }
            }
        }
        return -1;
    }

    /** marker 之前最近的数字（overflow_ledger 的 number_before）。 */
    private static Long numberBefore(String lowerDetail, String marker) {
        int at = lowerDetail.indexOf(marker);
        if (at < 0) {
            return null;
        }
        String before = lowerDetail.substring(0, at).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9][0-9,]*)\\s*(?:\\([^)]*\\)\\s*)?$").matcher(before);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1).replaceAll(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** lib.rs:1203-1214 err_detail：依次试 error.message / 顶层 message / error 字符串，最后引用原文。 */
    private static String errDetail(JsonNode body, String raw) {
        String s = body.path("error").path("message").asText(null);
        if (s == null) {
            s = body.path("message").asText(null);
        }
        if (s == null && body.path("error").isTextual()) {
            s = body.path("error").asText();
        }
        if (s != null) {
            return s;
        }
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.isEmpty()) {
            return trimmed.codePoints().limit(MAX_ERROR_DETAIL_CHARS)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint,
                            StringBuilder::append)
                    .toString();
        }
        return "unknown error";
    }

    /** lib.rs:1224-1229 says_out_of_credit：只认 code/type 里的 insufficient_quota，不匹配自由文本。 */
    private static boolean saysOutOfCredit(JsonNode body) {
        for (String k : new String[]{"code", "type"}) {
            if ("insufficient_quota".equals(body.path("error").path(k).asText(null))) {
                return true;
            }
        }
        return false;
    }

    /** lib.rs:276-285 retry_after_of：整数秒形态；HTTP-date 不解析（调用方自会退避）。 */
    private static Duration parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** lib.rs:501-506 strip_reasoning：推理模型的思考过程以内联 &lt;think&gt; 写进 content——切掉最后一个 {@code </think>} 之前的一切。 */
    static String stripReasoning(String reply) {
        int pos = reply.lastIndexOf("</think>");
        return pos < 0 ? reply : reply.substring(pos + "</think>".length());
    }

    /**
     * SSE 帧缓冲：按行攒、空行切帧（lib.rs:400-427 SseBuffer 的行式等价——
     * CR 跨 TCP 片记忆、裸 CR 折算 LF，UTF-8 交给 BufferedReader 解码）。
     */
    private static final class SseBuffer {
        private final StringBuilder pending = new StringBuilder();
        private final java.util.List<String> frames = new java.util.ArrayList<>();

        void pushLine(String line) {
            if (line.isEmpty()) {
                // 空行 = 帧边界
                flushFrame();
                return;
            }
            if (pending.length() > 0) {
                pending.append('\n');
            }
            pending.append(line);
        }

        private void flushFrame() {
            if (pending.length() > 0) {
                frames.add(pending.toString());
                pending.setLength(0);
            }
        }

        /** 流读完：最后一个不跟空行的帧也算（lib.rs:712-723——丢掉它就是丢掉答案的尾巴）。 */
        void flushTail() {
            flushFrame();
        }

        java.util.List<String> takeFrames() {
            return frames;
        }
    }
}
