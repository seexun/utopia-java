package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import tech.seexun.jknow.llm.ChatClient;

/**
 * 一整个用户轮次共用的一个历史窗口和一次上下文超限恢复（chat_context.rs 逐语义，#964）。
 * 能丢的只有此前已完成或已失败的整段问答，以及上一轮的工具往返；本轮的问题、系统提示
 * 和本轮已经拿到的工具结果一个字都不动，工具也不重跑。落库的对话不变：裁的是这一次
 * 发给模型的东西。
 *
 * <p>一轮里三个地方——带工具的请求、RAG 兜底、只看证据的最终作答——共用同一个窗口，
 * 谁先撞上超限谁来裁，裁完别处照着发，一轮合计只裁一次。
 */
public final class ChatHistoryContext {

    private static final class Window {

        /** 落库的历史，最旧在前；start 之前的不再发。 */
        final List<String[]> turns;
        /** 上一轮的工具往返：带 tool_calls 的助手消息和配套的 tool 结果，协议 JSON。 */
        final List<JsonNode> exchange;
        /** 最后一条助手消息在 turns 里的下标，往返插在它前面。 */
        final int lastAssistant;
        int start;
        /** 往返还发不发。它单独一位，而且先于任何问答被丢：一条 get_document 的结果就能
         * 有 24,000 字，两条就超过缺省预算——从前它和最后那条回答绑在一起，丢它就得把最旧
         * 到最新的问答一起丢。回答是人读过的对话，往返只是那条回答消化过的证据。 */
        boolean exchangeKept = true;
        /** 这一轮恢复过没有，三个地方合计。 */
        boolean retried = false;

        Window(List<String[]> turns, List<JsonNode> exchange) {
            this.turns = turns;
            this.exchange = exchange;
            int last = -1;
            for (int i = 0; i < turns.size(); i++) {
                if ("assistant".equals(turns.get(i)[0])) {
                    last = i;
                }
            }
            this.lastAssistant = last;
        }

        int size() {
            int total = 0;
            for (int i = start; i < turns.size(); i++) {
                total += turns.get(i)[1].codePoints().count();
            }
            if (keepsExchange()) {
                for (JsonNode m : exchange) {
                    total += m.toString().codePoints().count();
                }
            }
            return total;
        }

        /** 往返随它的回答走：回答被裁掉了，往返也不能单独留下。 */
        boolean keepsExchange() {
            return exchangeKept && lastAssistant >= start;
        }

        /** 把 start 推到下一条用户消息——落单的助手回答不发，哪怕预算装得下。 */
        void skipOrphans() {
            while (start < turns.size() && !"user".equals(turns.get(start)[0])) {
                start++;
            }
        }

        /** 先丢往返，再从最旧的问答起整段整段地丢，直到装进预算；一条消息从不截半。 */
        void trim(int budget) {
            skipOrphans();
            if (size() > budget && keepsExchange()) {
                exchangeKept = false;
            }
            while (start < turns.size() && size() > budget) {
                start++;
                skipOrphans();
            }
        }
    }

    private final Window window;

    private ChatHistoryContext(Window window) {
        this.window = window;
    }

    public static ChatHistoryContext of(List<String[]> turns, List<JsonNode> exchange,
                                        int budget) {
        Window window = new Window(new ArrayList<>(turns), exchange);
        window.trim(budget);
        return new ChatHistoryContext(window);
    }

    /** 现在还发的历史和往返。 */
    public Snapshot snapshot() {
        List<String[]> turns = new ArrayList<>(window.turns.subList(window.start,
                window.turns.size()));
        List<JsonNode> exchange = window.keepsExchange() ? new ArrayList<>(window.exchange)
                : List.of();
        return new Snapshot(turns, exchange);
    }

    public record Snapshot(List<String[]> turns, List<JsonNode> exchange) {
    }

    /** 落库的历史和往返展开成协议消息（history_messages 同一条路，数出的条数才对得上）。 */
    List<JsonNode> protocol(ChatFrames frames) {
        return ChatGuards.historyMessages(frames, window.turns, window.exchange);
    }

    /**
     * 模型的消息列表整轮不变：开头是它当初拿到的完整历史，后面跟本轮的问题和本轮的工具
     * 往返。每次发送前把历史那一段换成裁过的。数的是协议消息而不是对话轮次——一条落库
     * 的回答能展开成好几条工具消息；system 消息原地不动——认下的实体那条 system 就贴在
     * 问题前面。上一轮的往返在最后那条回答之前，丢它是从中间抠掉，不是掐头。
     */
    public List<JsonNode> apply(ChatFrames frames, List<JsonNode> messages) {
        List<JsonNode> full = protocol(frames);
        int sent = 0;
        for (JsonNode m : messages) {
            if (!"system".equals(m.path("role").asText())) {
                sent++;
            }
        }
        // rig 的列表开头应当就是这份完整历史，后面至少还有本轮的问题。少了——给的不是
        // 这一轮的列表——按条数替换会把本轮的问题一起吞掉，还不出声。这时原样发：请求
        // 超长顶多再挨一次 400，也好过一个没有问题的请求（#973 评审的后续）
        if (sent <= full.size()) {
            return new ArrayList<>(messages);
        }
        List<JsonNode> kept = ChatGuards.historyMessages(frames,
                window.turns.subList(window.start, window.turns.size()),
                window.keepsExchange() ? window.exchange : List.of());
        List<JsonNode> out = new ArrayList<>(messages.size());
        int replaced = 0;
        for (JsonNode m : messages) {
            if ("system".equals(m.path("role").asText()) || replaced == full.size()) {
                out.add(m);
                continue;
            }
            replaced++;
            if (replaced == full.size()) {
                out.addAll(kept);
            }
        }
        return out;
    }

    /**
     * 端点说上下文超了：记住它报的窗口，把历史裁到一半再发一次，同一轮只来一次。
     * <b>裁不动就不重发</b>——历史已经空了、问题本身就超长的时候，原样再发只会再挨一次
     * 同样的 400（#973 评审）。
     */
    public boolean recover(ChatClient client, Throwable err) {
        if (ChatClient.contextTooLong(err) == null) {
            return false;
        }
        client.rememberContextWindow(err);
        if (window.retried) {
            return false;
        }
        window.retried = true;
        int beforeStart = window.start;
        boolean beforeKept = window.exchangeKept;
        int budget = Math.min(window.size() / 2, client.historyCharBudget());
        window.trim(budget);
        return window.start != beforeStart || window.exchangeKept != beforeKept;
    }

    @Override
    public String toString() {
        return "ChatHistoryContext{start=" + window.start + ", kept=" + snapshot().turns.size()
                + ", turns=" + snapshot().turns.stream().map(t -> t[0]).collect(Collectors.toList())
                + "}";
    }
}
