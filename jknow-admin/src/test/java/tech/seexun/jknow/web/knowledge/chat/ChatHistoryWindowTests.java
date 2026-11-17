package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.llm.ChatClient;

/**
 * 一整个用户轮次共用的历史窗口（chat_context.rs 内联 5 例，断言原值移植）：预算先丢
 * 整段往返再丢最旧问答、往返随它的回答走、比历史短的列表原样发、超限对半裁一次。
 */
class ChatHistoryWindowTests {

    private final ChatFrames frames = new ChatFrames();

    private static final String REFUSAL = "{\"error\":{\"code\":\"context_length_exceeded\","
            + "\"message\":\"maximum context length is 4096 tokens; "
            + "request has 12000 tokens\"}}";

    private List<JsonNode> heavyExchange() {
        return List.of(
                frames.parse("""
                        {"role":"assistant","tool_calls":[
                          {"id":"old-1","type":"function","function":{"name":"get_document","arguments":"{}"}},
                          {"id":"old-2","type":"function","function":{"name":"get_document","arguments":"{}"}}]}"""),
                frames.parse("{\"role\":\"tool\",\"tool_call_id\":\"old-1\",\"content\":\""
                        + "x".repeat(24_000) + "\"}"),
                frames.parse("{\"role\":\"tool\",\"tool_call_id\":\"old-2\",\"content\":\""
                        + "y".repeat(24_000) + "\"}"));
    }

    private int chars(String s) {
        return s.codePoints().toArray().length;
    }

    private int size(List<String[]> turns) {
        return turns.stream().mapToInt(t -> chars(t[1])).sum();
    }

    /// 预算先丢整段问答（孤儿答案不带），绝不把最新那段答案截半
    @Test
    void aBudgetDropsWholeExchangesIncludingFailedAndStoppedTurns() {
        List<String[]> turns = List.of(
                new String[]{"assistant", "orphan"},
                new String[]{"user", "failed unanswered question"},
                new String[]{"user", "latest"},
                new String[]{"assistant", "[stopped]界😀"});
        int budget = chars("latest") + chars("[stopped]界😀");
        ChatHistoryContext context = ChatHistoryContext.of(turns, List.of(), budget);
        assertEquals(2, context.snapshot().turns().size());
        assertEquals("latest", context.snapshot().turns().get(0)[1]);
        ChatHistoryContext smaller = ChatHistoryContext.of(turns, List.of(), budget - 1);
        assertTrue(smaller.snapshot().turns().isEmpty(), "never cut the answer to make it fit");
    }

    /// 上一轮的往返先于任何问答被丢；装得下时照发
    @Test
    void thePreviousToolExchangeGoesBeforeAnyQuestionOrAnswer() {
        List<String[]> turns = List.of(
                new String[]{"user", "which documents mention Aurora?"},
                new String[]{"assistant", "Two documents mention Aurora [1][2]."});
        List<JsonNode> exchange = heavyExchange();
        ChatHistoryContext context = ChatHistoryContext.of(turns, exchange, 32_000);
        assertEquals(2, context.snapshot().turns().size(), "the question and answer stay");
        assertTrue(context.snapshot().exchange().isEmpty(), "the exchange goes first");

        List<JsonNode> messages = new ArrayList<>();
        messages.add(frames.parse("{\"role\":\"system\",\"content\":\"preserve system\"}"));
        messages.addAll(ChatGuards.historyMessages(frames, turns, exchange));
        messages.add(frames.parse("{\"role\":\"system\",\"content\":\"known entities\"}"));
        messages.add(frames.parse("{\"role\":\"user\",\"content\":\"make it shorter\"}"));
        messages.add(frames.parse(
                "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"new-call\"}]}"));
        messages.add(frames.parse(
                "{\"role\":\"tool\",\"tool_call_id\":\"new-call\",\"content\":\"preserve evidence\"}"));
        List<JsonNode> expected = new ArrayList<>();
        expected.add(frames.parse("{\"role\":\"system\",\"content\":\"preserve system\"}"));
        expected.addAll(ChatGuards.historyMessages(frames, turns, List.of()));
        expected.add(frames.parse("{\"role\":\"system\",\"content\":\"known entities\"}"));
        expected.add(frames.parse("{\"role\":\"user\",\"content\":\"make it shorter\"}"));
        expected.add(frames.parse(
                "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"new-call\"}]}"));
        expected.add(frames.parse(
                "{\"role\":\"tool\",\"tool_call_id\":\"new-call\",\"content\":\"preserve evidence\"}"));
        assertEquals(expected, context.apply(frames, messages));

        ChatHistoryContext smaller = ChatHistoryContext.of(turns, exchange, 10);
        assertTrue(smaller.snapshot().turns().isEmpty(), "then the oldest exchanges");
        assertTrue(smaller.snapshot().exchange().isEmpty());
    }

    /// rig 没把完整历史交回来：按条数替换会把本轮的问题吞掉，所以原样发
    @Test
    void aListShorterThanTheHistoryIsSentUnchanged() {
        List<String[]> turns = List.of(
                new String[]{"user", "old"},
                new String[]{"assistant", "answer"});
        ChatHistoryContext context = ChatHistoryContext.of(turns, List.of(), 3);
        assertTrue(context.snapshot().turns().isEmpty(), "everything is trimmed");
        JsonNode system = frames.parse("{\"role\":\"system\",\"content\":\"preserve system\"}");
        JsonNode current = frames.parse("{\"role\":\"user\",\"content\":\"make it shorter\"}");
        List<JsonNode> shortList = List.of(system, current);
        assertEquals(shortList, context.apply(frames, shortList),
                "one message where two of history were expected: the question must survive");
        List<JsonNode> whole = new ArrayList<>();
        whole.add(system);
        whole.addAll(ChatGuards.historyMessages(frames, turns, List.of()));
        whole.add(current);
        assertEquals(List.of(system, current), context.apply(frames, whole));
    }

    /// 小的往返随它的回答一起发
    @Test
    void aSmallExchangeStaysWithItsAnswer() {
        List<String[]> turns = List.of(
                new String[]{"user", "old"},
                new String[]{"assistant", "answer"},
                new String[]{"user", "failed"});
        List<JsonNode> exchange = List.of(
                frames.parse("""
                        {"role":"assistant","tool_calls":[
                          {"id":"c","type":"function","function":{"name":"find_entities","arguments":"{}"}}]}"""),
                frames.parse("{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"small\"}"));
        ChatHistoryContext context = ChatHistoryContext.of(turns, exchange, 32_000);
        assertEquals(3, context.snapshot().turns().size());
        assertEquals(exchange, context.snapshot().exchange());
        List<JsonNode> messages = ChatGuards.historyMessages(frames, turns, exchange);
        assertEquals(messages, context.apply(frames, messages), "nothing to remove");
    }

    /// 拒绝对半裁一次；裁不动（历史已空）不重发同一个请求
    @Test
    void aRefusalHalvesTheHistoryOnceAndOnlyWhenSomethingCanGo() {
        ChatClient client = new ChatClient("http://unused", null, "m");
        List<String[]> turns = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            turns.add(new String[]{"user", "question " + i});
            turns.add(new String[]{"assistant", "界".repeat(1000)});
        }
        ChatHistoryContext context = ChatHistoryContext.of(turns, heavyExchange(), 32_000);
        assertTrue(context.snapshot().exchange().isEmpty());
        ChatClient.LlmError refusal = (ChatClient.LlmError) new ChatClient.LlmError(
                ChatClient.LlmError.Kind.CONTEXT_TOO_LONG, 400, null,
                "LLM request failed (400 Bad Request): the prompt exceeds the model's context "
                        + "window: " + REFUSAL, 4096);
        assertTrue(context.recover(client, refusal));
        List<String[]> kept = context.snapshot().turns();
        assertEquals(4, kept.size(), "half of four exchanges, the newest kept");
        assertEquals("question 2", kept.get(0)[1]);
        assertFalse(context.recover(client, refusal), "the second refusal of a turn is final");

        ChatHistoryContext empty = ChatHistoryContext.of(List.of(), List.of(), 32_000);
        assertFalse(empty.recover(client, refusal),
                "nothing to drop, so the same request is not sent again");
        ChatClient.LlmError other = new ChatClient.LlmError(
                ChatClient.LlmError.Kind.REJECTED, 500, null, "network down");
        assertFalse(ChatHistoryContext.of(turns, List.of(), 32_000).recover(client, other));
    }
}
