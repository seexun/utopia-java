package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话循环的策略守卫（agent.rs 的纯函数族逐语义移植）。循环本身是自研 runner
 * （0042「chat loop is a runner with hooks」），这里的判定被钩子与收尾关口共用。
 */
public final class ChatGuards {

    private ChatGuards() {
    }

    /** 端点无视了 required、首轮只有文字时退回去的那句话。没有 DONE 出口。 */
    public static final String MUST_CALL =
            "(system) Every turn starts with a tool call. Call the tool that gathers the evidence "
                    + "for this question, or call no_evidence_needed if the question is not about "
                    + "the knowledge base at all. Do not describe a plan.";

    /**
     * 模型回了一个空回复（没有正文也没调工具）时重问的那句（#631）。重问一次，第二次还空
     * 才报错——只给一次，一个始终不说话的端点不会让循环空转。措辞两头都留门：工具跑过的
     * 就着查到的答；一步没查的，缺证据就去调工具。
     */
    public static final String EMPTY_REPLY_RETRY =
            "(system) Your previous reply was empty. Reply to the user now: answer from the "
                    + "evidence gathered above, or call a tool if you still need evidence.";

    /**
     * 模型把工具调用写进了正文时退回去的那句（#937）。端点偶尔不走协议里的 tool_calls，
     * 而是把调用的标记原样写成正文（DeepSeek 的 <｜tool▁call▁begin｜>… 或 DSML）。什么
     * 都没执行，那段也不是答案。退回时保留那一回合，模型看得见自己想调什么；只给一次，
     * 再写一次就以 CONTROL_TEXT 报错。
     */
    public static final String MARKUP_RETRY =
            "(system) Your previous reply wrote a tool call out as text, so nothing ran. Make "
                    + "the call as a tool call, or answer the user in plain text.";

    /** Bounded buffering 只加在无工具的收尾调用上；字节上限独立于端点的 token 记账。 */
    public static final int MAX_FINAL_ANSWER_BYTES = 1024 * 1024;

    public static final String FINAL_TOOL_CALL =
            "Model attempted a tool call after the tool budget was exhausted";

    public static final String CONTROL_TEXT =
            "Model returned tool-control text instead of a final answer";

    /** 行首打开一次写成正文的工具调用的标记（DSML 与 DeepSeek 原生标记，#937）。 */
    static final String[] CONTROL_MARKERS = {
            "<DSMLcalls>",
            "<DSMLtool_calls>",
            "<DSMLinvokename=",
            "<tool▁calls▁begin>",
            "<tool▁call▁begin>",
    };

    /** 兼顾 ASCII/全角竖线与双竖线的写法（control_key 的清洗）。 */
    static String controlKey(String candidate) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < candidate.length() && count < 80; i++) {
            char c = candidate.charAt(i);
            if (Character.isWhitespace(c) || c == '|' || c == '｜') {
                continue;
            }
            out.append(c);
            count++;
        }
        return out.toString();
    }

    static boolean isControl(String candidate) {
        String key = controlKey(candidate);
        for (String marker : CONTROL_MARKERS) {
            if (key.startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 一行还在写、可能长成标记的那条线（may_become_control）。 */
    static boolean mayBecomeControl(String candidate) {
        String key = controlKey(candidate);
        for (String marker : CONTROL_MARKERS) {
            if (marker.startsWith(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一段回复是不是写成正文的工具调用（#845、#937）。解释与围栏示例是散文；明确要原样
     * 示例的问题可以要那段标记。这是发布闸：绝不把这段文本当可执行的工具调用。
     */
    public static boolean toolCallText(String text, String question) {
        String request = question.toLowerCase();
        boolean asksForEncoding = (request.contains("dsml") || request.contains("tool▁call"))
                && List.of("example", "verbatim", "示例", "原样").stream().anyMatch(request::contains)
                && !request.contains("business")
                && !request.contains("业务");
        if (asksForEncoding) {
            return false;
        }
        // 检查拼好的整回合，SSE 分片边界不参与判断
        String trimmed = text.trim();
        if (isControl(trimmed)) {
            return true;
        }
        // 围栏内的示例不算：``` / ~~~ 开闭围栏跟踪，围栏外的行首标记才算
        Character fenceMarker = null;
        int fenceSize = 0;
        for (String rawLine : trimmed.split("\n", -1)) {
            String line = rawLine.stripLeading();
            if (line.isEmpty()) {
                continue;
            }
            char first = line.charAt(0);
            if (first == '`' || first == '~') {
                int len = 0;
                while (len < line.length() && line.charAt(len) == first) {
                    len++;
                }
                if (len >= 3) {
                    if (fenceMarker == null) {
                        fenceMarker = first;
                        fenceSize = len;
                    } else if (fenceMarker == first && len >= fenceSize
                            && line.substring(len).trim().isEmpty()) {
                        fenceMarker = null;
                    }
                    continue;
                }
            }
            if (fenceMarker == null && isControl(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 工具回合的正文边流边发能发到多少（#937）：按行发；可能长成标记的那行扣着，一旦
     * 成了标记，这一回合剩下的都扣着（退回或丢弃）。from 是已发到的字节位（行界），
     * holding 记录是否已进入整回合扣住状态。
     */
    public static int publishable(String text, int from, boolean[] holding) {
        if (holding[0]) {
            return from;
        }
        int at;
        if (from == 0 || text.charAt(from - 1) == '\n') {
            at = from;
        } else {
            int nl = text.indexOf('\n', from);
            if (nl < 0) {
                return text.length();
            }
            at = nl + 1;
        }
        while (at < text.length()) {
            int nl = text.indexOf('\n', at);
            String line = nl < 0 ? text.substring(at) : text.substring(at, nl);
            boolean finished = nl >= 0;
            if (isControl(line)) {
                holding[0] = true;
                return at;
            }
            if (!finished) {
                return mayBecomeControl(line) ? at : text.length();
            }
            at += line.length() + 1;
        }
        return at;
    }

    /**
     * 收尾关口（finalization_error）：预算边界上的候选答案为什么不许发布——结构化调用、
     * 空文本、或写成正文的工具调用。null = 可以发布。
     */
    public static String finalizationError(String text, boolean hasCalls, String question) {
        if (hasCalls) {
            return FINAL_TOOL_CALL;
        }
        if (text.trim().isEmpty()) {
            return "Model returned an empty answer";
        }
        return toolCallText(text, question) ? CONTROL_TEXT : null;
    }

    /**
     * 落库的历史（OpenAI 协议 JSON）变回协议消息（agent.rs history_messages）：最后那一轮
     * 的工具往返插在它的结论之前——顺序就是真实顺序，模型读起来是「我问了、我查了、我
     * 答了」。tool 消息的工具名协议里没有，从前面带 tool_calls 的 assistant 消息按 id 找回。
     */
    public static List<JsonNode> historyMessages(ChatFrames frames, List<String[]> turns,
                                                 List<JsonNode> lastToolExchange) {
        int lastAssistant = -1;
        for (int i = 0; i < turns.size(); i++) {
            if ("assistant".equals(turns.get(i)[0])) {
                lastAssistant = i;
            }
        }
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            if (i == lastAssistant) {
                out.addAll(exchangeMessages(frames, lastToolExchange));
            }
            String[] turn = turns.get(i);
            ObjectNode msg = frames.mapper.createObjectNode();
            msg.put("role", "assistant".equals(turn[0]) ? "assistant" : "user");
            msg.put("content", turn[1]);
            out.add(msg);
        }
        return out;
    }

    static List<JsonNode> exchangeMessages(ChatFrames frames, List<JsonNode> exchange) {
        Map<String, String> names = new HashMap<>();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode m : exchange) {
            String role = m.path("role").asText("");
            if (role.equals("assistant")) {
                ObjectNode msg = frames.mapper.createObjectNode();
                msg.put("role", "assistant");
                String text = m.path("content").asText("");
                if (text.isEmpty()) {
                    msg.putNull("content");
                } else {
                    msg.put("content", text);
                }
                boolean any = !text.isEmpty();
                ArrayNode calls = null;
                for (JsonNode c : m.path("tool_calls")) {
                    if (calls == null) {
                        calls = msg.putArray("tool_calls");
                    }
                    String id = c.path("id").asText("");
                    String name = c.path("function").path("name").asText("");
                    names.put(id, name);
                    ObjectNode call = calls.addObject();
                    call.put("id", id);
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", name);
                    fn.put("arguments", c.path("function").path("arguments").asText("{}"));
                    any = true;
                }
                if (any) {
                    out.add(msg);
                }
            } else if (role.equals("tool")) {
                ObjectNode msg = frames.mapper.createObjectNode();
                msg.put("role", "tool");
                msg.put("tool_call_id", m.path("tool_call_id").asText(""));
                msg.put("content", m.path("content").asText(""));
                out.add(msg);
            }
        }
        return out;
    }

    /**
     * 前几轮已经认下的实体，连 id 一起交回去（上下文文档；wire 层把它作为 system 插在
     * 当前问题紧前面）。少了它，模型只看得见上一轮的最终答案文字，不知道自己拿到过哪些
     * id，于是从名字重搜一遍；同名歧义时两轮可能落到不同的实体上。
     */
    public static String knownEntitiesBlock(ChatFrames frames, List<JsonNode> entities, int limit) {
        if (entities.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        entities.stream().limit(limit).forEach(e -> lines.add(
                e.path("id").asText("?") + " | " + e.path("name").asText("?") + " | "
                        + e.path("type").asText("?")));
        return "Entities already identified earlier in this conversation (id | name | type). "
                + "Call entity_facts with these ids directly; do not look them up by name again:\n"
                + String.join("\n", lines);
    }

    /** 工具结果消息（role=tool），is_error 标记失败观察（收尾的证据单按 status 读它）。 */
    public static ObjectNode toolResultMessage(ChatFrames frames, String callId, String content,
                                               Boolean isError) {
        ObjectNode msg = frames.mapper.createObjectNode();
        msg.put("role", "tool");
        msg.put("tool_call_id", callId);
        msg.put("content", content);
        if (isError != null) {
            msg.put("is_error", isError);
        }
        return msg;
    }
}
