package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 循环守卫的纯函数族（agent.rs 内联 5 例，断言原值移植）：finalization 关口认得叙述后
 * 的控制标记、DeepSeek 原生标记、扣行发布、落库历史插位与实体清单。
 */
class ChatGuardsTests {

    private final ChatFrames frames = new ChatFrames();

    private static final String DSML = "<｜DSML｜ calls>\n<｜DSML｜ invoke name=\"entity_facts\">"
            + "{}</｜DSML｜ invoke>\n</｜DSML｜ calls>";

    @Test
    void finalizationChecksNarratedControlButPreservesProtocolExamples() {
        String control = "Let me examine it.\n\n<｜｜DSML｜｜ calls>\n"
                + "<｜DSML｜ invoke name=\"lookup\">{}";
        assertTrue(ChatGuards.finalizationError(control, false, "What happened?") != null);
        assertNull(ChatGuards.finalizationError(control, false,
                "Return a DSML example verbatim"));
        assertTrue(ChatGuards.finalizationError(control, false,
                "请解释为什么出现 DSML，但请直接回答业务问题") != null);
        for (String explanation : new String[]{
                "The encoding includes <｜DSML｜ calls> as a marker.",
                "Example:\n```xml\n<｜DSML｜ calls>\n```\nThis is the encoding.",
                "Example:\n~~~~xml\n```\n<｜DSML｜ calls>\n~~~~",
                "> <｜DSML｜ calls>\nThis quotes the encoding."}) {
            assertNull(ChatGuards.finalizationError(explanation, false, "Explain the encoding"),
                    explanation);
        }
        String afterExample = "```xml\n<｜DSML｜ calls>\n```\nLet me check.\n<|DSML|calls>";
        assertTrue(ChatGuards.finalizationError(afterExample, false, "What happened?") != null);
    }

    /// DeepSeek 的原生标记也是写成正文的工具调用（#937）：两种竖线、叙述之后的行首都认；
    /// 行内提及、引用、围栏里的示例、明确要原样示例的问题都不算
    @Test
    void deepseekMarkupIsAToolCallWrittenAsText() {
        String call = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>"
                + "search_chunks\n```json\n{\"query\":\"Acme\"}\n```"
                + "<｜tool▁call▁end｜><｜tool▁calls▁end｜>";
        String question = "What changed at Acme?";
        assertTrue(ChatGuards.toolCallText(call, question));
        assertTrue(ChatGuards.toolCallText(call.replace('｜', '|'), question));
        assertTrue(ChatGuards.toolCallText("Let me search.\n\n" + call, question));
        assertTrue(ChatGuards.toolCallText(
                "<｜tool▁call▁begin｜>function<｜tool▁sep｜>search_chunks", question));
        for (String prose : new String[]{
                "The reply contained <｜tool▁calls▁begin｜> in the middle of a sentence.",
                "> <｜tool▁calls▁begin｜>\nThat line quotes the markup.",
                "It looks like this:\n~~~\n<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>\n~~~",
                "Acme's revenue rose 5% [1]."}) {
            assertFalse(ChatGuards.toolCallText(prose, question), prose);
        }
        assertFalse(ChatGuards.toolCallText(call,
                "Show a <｜tool▁calls▁begin｜> example verbatim"));
        assertEquals(ChatGuards.CONTROL_TEXT,
                ChatGuards.finalizationError(call, false, question));
    }

    /// 流式外发按行：可能长成标记的那一行扣着，成了标记就连同这一回合剩下的都扣着
    @Test
    void aLineIsHeldWhileItMayBecomeAToolCall() {
        // (本回合正文, 已发出的字节数, 应当发到哪里为止, 之后是否整回合扣住)
        record Case(String text, int from, String out, boolean held) {
        }
        for (Case c : new Case[]{
                new Case("Let me search.\n<｜to", 0, "Let me search.\n", false),
                new Case("Let me search.\n<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>", 15,
                        "Let me search.\n", true),
                new Case("One.\n\nTwo.\n  ", 0, "One.\n\nTwo.\n", false),
                new Case("<", 0, "", false),
                new Case("<b>Acme</b> grew", 0, "<b>Acme</b> grew", false),
                new Case("Acme grew\n<b>", 9, "Acme grew\n<b>", false),
                new Case("Acme grew <｜tool▁calls▁begin｜> inline", 9,
                        "Acme grew <｜tool▁calls▁begin｜> inline", false),
                new Case("Acme grew\n<|tool▁call▁begin|>", 9, "Acme grew\n", true),
                new Case("<|DSML|calls>\nMore.", 0, "", true)}) {
            boolean[] holding = {false};
            int upto = ChatGuards.publishable(c.text(), c.from, holding);
            assertEquals(c.out, c.text().substring(0, upto), c.text() + " from " + c.from);
            assertEquals(c.held, holding[0], c.text() + " from " + c.from);
        }
        boolean[] holding = {true};
        assertEquals(0, ChatGuards.publishable("Held.\nStill held.", 0, holding));
    }

    /// 上一轮的工具往返插在它的结论之前
    @Test
    void theLastExchangeSitsBeforeItsConclusion() {
        List<String[]> turns = List.of(
                new String[]{"user", "q1"},
                new String[]{"assistant", "a1"},
                new String[]{"user", "q2"});
        List<JsonNode> exchange = List.of(
                frames.parse("""
                        {"role":"assistant","content":null,"tool_calls":[
                          {"id":"c1","type":"function",
                           "function":{"name":"search_chunks","arguments":"{\\"query\\":\\"x\\"}"}}]}"""),
                frames.parse("{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"found\"}"));
        List<JsonNode> msgs = ChatGuards.historyMessages(frames, turns, exchange);
        assertEquals(5, msgs.size());
        assertEquals("user", msgs.get(0).path("role").asText());
        assertEquals("assistant", msgs.get(1).path("role").asText());
        assertEquals("c1", msgs.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("{\"query\":\"x\"}", msgs.get(1).path("tool_calls").get(0).path("function")
                .path("arguments").asText());
        assertEquals("tool", msgs.get(2).path("role").asText());
        assertEquals("c1", msgs.get(2).path("tool_call_id").asText());
        assertEquals("found", msgs.get(2).path("content").asText());
        assertEquals("assistant", msgs.get(3).path("role").asText());
        assertEquals("user", msgs.get(4).path("role").asText());
    }

    @Test
    void theEntityBlockListsIdNameTypeAndStopsAtTheLimit() {
        assertNull(ChatGuards.knownEntitiesBlock(frames, List.of(), 20));
        List<JsonNode> entities = List.of(
                frames.parse("{\"id\":\"e1\",\"name\":\"Acme\",\"type\":\"Organization\"}"),
                frames.parse("{\"id\":\"e2\",\"name\":\"Bob\",\"type\":\"Person\"}"));
        String block = ChatGuards.knownEntitiesBlock(frames, entities, 1);
        assertTrue(block.contains("e1 | Acme | Organization"));
        assertFalse(block.contains("e2"));
        assertTrue(block.startsWith("Entities already identified"));
    }
}
