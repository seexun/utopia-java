package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;

/**
 * 假模型的一次回复（chat_empty_reply_tests.rs::Reply 的逐档移植）。脚本里的项按请求
 * 序消耗，读完结尾一律回 Empty（与 Rust {@code unwrap_or(Reply::Empty)} 同判）。
 */
public interface ScriptedReply {

    /** 各档的 SSE 载荷构造（requestNumber 是第几次请求——Rust 用它编 call id）。 */
    String sse(ObjectMapper json, int requestNumber) throws com.fasterxml.jackson.core.JsonProcessingException;

    /** 带请求正本的档（legacy_only 这类要看有没有 tools 才回什么的形状）。 */
    default String sse(ObjectMapper json, int requestNumber, JsonNode request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return sse(json, requestNumber);
    }

    /** 是否先看请求再定档（ChatScriptServer 分派用）。 */
    default boolean requestAware() {
        return false;
    }

    /**
     * legacy_only（chat_persistence_tests.rs::LegacyOnly）：带 tools 的请求回 status
     * （tools unsupported），不带 tools 的回一段流式答案——降级路径的模型形状。
     */
    static ScriptedReply legacyOnly(int status, String answer) {
        return new ScriptedReply() {
            @Override
            public boolean requestAware() {
                return true;
            }

            @Override
            public String sse(ObjectMapper json, int requestNumber, JsonNode request)
                    throws com.fasterxml.jackson.core.JsonProcessingException {
                if (request.has("tools")) {
                    throw new HttpWithBody(status,
                            "{\"error\":{\"message\":\"tools unsupported\"}}");
                }
                ObjectNode delta = json.createObjectNode().put("content", answer);
                ObjectNode choice = json.createObjectNode();
                choice.set("delta", delta);
                choice.put("finish_reason", "stop");
                ObjectNode frame = json.createObjectNode();
                frame.putArray("choices").add(choice);
                return "data: " + frame + "\n\ndata: [DONE]\n\n";
            }

            @Override
            public String sse(ObjectMapper json, int requestNumber) {
                throw new IllegalStateException("request aware");
            }
        };
    }

    /** HTTP 档的请求感知形态（legacyOnly 内部用）。 */
    class HttpWithBody extends RuntimeException {

        final int status;
        final String body;

        HttpWithBody(int status, String body) {
            super(null, null, false, false);
            this.status = status;
            this.body = body;
        }
    }

    /** 没有正文也不调工具（空回复——#631 的重问路径）。 */
    static ScriptedReply empty() {
        return (json, n) -> "data: [DONE]\n\n";
    }

    /** 一段纯文本。 */
    static ScriptedReply text(String text) {
        return (json, n) -> {
            ObjectNode delta = json.createObjectNode().put("content", text);
            return sse(json, choicesFrame(json, delta, null));
        };
    }

    /** 一段文本分多个增量（分片标记扣行路径用）。 */
    static ScriptedReply splitText(List<String> parts) {
        return (json, n) -> {
            StringBuilder sse = new StringBuilder();
            for (String part : parts) {
                ObjectNode delta = json.createObjectNode().put("content", part);
                sse.append("data: ").append(choicesFrame(json, delta, null)).append("\n\n");
            }
            return sse + "data: [DONE]\n\n";
        };
    }

    /** 叙述 + 一个真实工具调用（NarratedTool）。 */
    static ScriptedReply narratedTool() {
        return toolCall("I will check the evidence.", "find_entities", "{\"name\":\"Acme\"}");
    }

    /** 文本 + 同一回合里真的调一个工具（TextWithTool）。 */
    static ScriptedReply textWithTool(String text) {
        return toolCall(text, "find_entities", "{\"name\":\"Acme\"}");
    }

    /** 一段正文 + 两个并行工具调用（ParallelTools）。 */
    static ScriptedReply parallelTools() {
        return (json, n) -> {
            ObjectNode delta = json.createObjectNode().put("content", "核查😀");
            ArrayNode calls = delta.putArray("tool_calls");
            calls.add(call(json, 0, "call_" + n + "_a", "find_entities",
                    "{\"name\":\"Acme\"}"));
            calls.add(call(json, 1, "call_" + n + "_b", "find_entities",
                    "{\"name\":\"Other\"}"));
            return sse(json, choicesFrame(json, delta, null));
        };
    }

    /** 一个工具调用：(名字, 参数 JSON)。 */
    static ScriptedReply tool(String name, String args) {
        return toolCall(null, name, args);
    }

    /** 读一篇文档：get_document by id。 */
    static ScriptedReply document(UUID documentId) {
        return (json, n) -> {
            ObjectNode delta = json.createObjectNode();
            String args = json.createObjectNode().put("document_id", documentId.toString())
                    .toString();
            delta.putArray("tool_calls").add(call(json, 0, "call_" + n, "get_document", args));
            return sse(json, choicesFrame(json, delta, null));
        };
    }

    /** 文本 + finish_reason（length/content_filter 等非修复即败的档）。 */
    static ScriptedReply finished(String text, String reason) {
        return (json, n) -> {
            ObjectNode delta = json.createObjectNode().put("content", text);
            return sse(json, choicesFrame(json, delta, reason));
        };
    }

    /** 257 帧 × 4096 字（> 1MiB 的终稿档）。 */
    static ScriptedReply oversizedText() {
        return (json, n) -> {
            ObjectNode delta = json.createObjectNode().put("content", "x".repeat(4096));
            String frame = "data: " + choicesFrame(json, delta, null) + "\n\n";
            return frame.repeat(257) + "data: [DONE]\n\n";
        };
    }

    /** 一个 HTTP 状态（不带 JSON 错误体）。 */
    static ScriptedReply http(int status) {
        return new Http(status, null);
    }

    /** 一个 HTTP 状态 + 原文错误体（context_length_exceeded 等）。 */
    static ScriptedReply httpError(int status, String body) {
        return new Http(status, body);
    }

    /** 文本 + 工具调用（叙述带换行时文本在工具结果之前逐行外发）。 */
    static ScriptedReply toolCall(String text, String name, String args) {
        return (json, n) -> {
            ObjectNode delta = json.createObjectNode();
            if (text != null) {
                delta.put("content", text);
            }
            delta.putArray("tool_calls").add(call(json, 0, "call_" + n, name, args));
            return sse(json, choicesFrame(json, delta, null));
        };
    }

    private static ObjectNode call(ObjectMapper json, int index, String id, String name,
                                   String args) {
        ObjectNode call = json.createObjectNode();
        call.put("index", index);
        call.put("id", id);
        ObjectNode fn = call.putObject("function");
        fn.put("name", name);
        fn.put("arguments", args);
        return call;
    }

    /** choices[0] 的完整帧：delta + 可选 finish_reason。 */
    private static ObjectNode choicesFrame(ObjectMapper json, ObjectNode delta, String reason) {
        ObjectNode choice = json.createObjectNode();
        choice.set("delta", delta);
        if (reason != null) {
            choice.put("finish_reason", reason);
        }
        ObjectNode frame = json.createObjectNode();
        frame.putArray("choices").add(choice);
        return frame;
    }

    /** 组一帧 data 行（Rust respond 的通用收尾：帧 + [DONE]）。 */
    private static String sse(ObjectMapper json, ObjectNode frame) {
        return "data: " + frame + "\n\ndata: [DONE]\n\n";
    }

    /** 组一帧 data 行（Rust respond 的通用收尾：帧 + [DONE]）。 */
    private static String sse(ObjectMapper json, JsonNode frame) {
        return "data: " + frame + "\n\ndata: [DONE]\n\n";
    }

    /** HTTP 档：不走 SSE。 */
    record Http(int status, String body) implements ScriptedReply {

        @Override
        public String sse(ObjectMapper json, int requestNumber) throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new IllegalStateException("handled by ChatScriptServer directly");
        }
    }
}
