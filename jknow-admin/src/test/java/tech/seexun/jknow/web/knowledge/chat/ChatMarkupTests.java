package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 模型把工具调用写成了正文（#937）（chat_markup_tests.rs 全 7 例，断言原值移植）：
 * 标记不外发、只退一次、再写一次以 error 收尾；叙述照常流出；围栏示例放行。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatMarkupTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private static final String CALL = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function"
            + "<｜tool▁sep｜>search_chunks\n```json\n{\"query\":\"Acme revenue\"}\n```"
            + "<｜tool▁call▁end｜><｜tool▁calls▁end｜>";
    private static final String ANSWER = "Acme's revenue rose 5% last quarter.";
    private static final ScriptedReply TOOL =
            ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}");
    private static final String QUESTION = "What was Acme's revenue last quarter?";

    private static String streamed(String sse) {
        StringBuilder out = new StringBuilder();
        for (String frame : sse.split("\n\n")) {
            if (frame.startsWith("event: delta\ndata: ")) {
                out.append(new ChatFrames().parse(
                        frame.substring("event: delta\ndata: ".length())).path("text").asText());
            }
        }
        return out.toString();
    }

    private int sentBack() {
        int count = 0;
        for (JsonNode r : requests()) {
            JsonNode messages = r.path("messages");
            String last = messages.size() == 0 ? ""
                    : messages.get(messages.size() - 1).path("content").asText("");
            if (ChatGuards.MARKUP_RETRY.equals(last)) {
                count++;
            }
        }
        return count;
    }

    private JsonNode storedExchange() {
        return frames().parse(jdbc.queryForObject(
                "SELECT m.tool_exchange::text FROM conversation_messages m "
                        + "JOIN conversations c ON c.id=m.conversation_id "
                        + "WHERE c.kb_id=? AND m.role='assistant'",
                String.class, kb));
    }

    @Test
    void aCallWrittenAsTextAfterAToolIsSentBackOnce() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text(CALL), ScriptedReply.text(ANSWER)));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(ANSWER, streamed(sse), "the markup never reaches the reader");
            assertEquals(ANSWER, storedAnswer());
            List<JsonNode> requests = requests();
            assertEquals(3, requests.size(), "tool, markup, then one retry");
            JsonNode messages = requests.get(2).path("messages");
            assertEquals("assistant",
                    messages.get(messages.size() - 2).path("role").asText());
            assertEquals(CALL, messages.get(messages.size() - 2).path("content").asText());
            assertEquals(1, sentBack());
        } finally {
            cleanup();
        }
    }

    @Test
    void aCallWrittenAsTextInTheFirstTurnIsLeftOutOfTheAnswer() throws Exception {
        fixture(List.of(ScriptedReply.text(CALL), TOOL, ScriptedReply.text(ANSWER)));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: done"), sse);
            assertTrue(sse.contains("event: step"),
                    "the retry made the call for real:\n" + sse);
            assertEquals(ANSWER, streamed(sse));
            assertEquals(ANSWER, storedAnswer());
            assertEquals(3, requests().size());
            JsonNode messages = requests().get(1).path("messages");
            assertEquals(ChatGuards.MARKUP_RETRY,
                    messages.get(messages.size() - 1).path("content").asText());
            assertEquals(1, sentBack());
        } finally {
            cleanup();
        }
    }

    @Test
    void aCallWrittenAsTextTwiceIsAnErrorAndNothingIsStored() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text(CALL), ScriptedReply.text(CALL),
                ScriptedReply.text("Never requested")));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: error"), sse);
            assertTrue(sse.contains(ChatGuards.CONTROL_TEXT), sse);
            assertEquals("", streamed(sse), "neither turn reached the reader");
            assertEquals(null, storedAnswer());
            assertEquals(3, requests().size(), "one retry, not a loop");
            assertEquals(1, sentBack());
        } finally {
            cleanup();
        }
    }

    @Test
    void narrationStreamsWhileASplitMarkerIsHeld() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.splitText(List.of(
                "I will search the filings.\n",
                "<｜tool",
                "▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>search_chunks\n",
                "```json\n{\"query\":\"Acme filings\"}\n```"
                        + "<｜tool▁call▁end｜><｜tool▁calls▁end｜>")),
                ScriptedReply.text(ANSWER)));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: done"), sse);
            String shown = streamed(sse);
            assertTrue(shown.startsWith("I will search the filings.\n"), shown);
            assertTrue(shown.endsWith(ANSWER), shown);
            assertTrue(!shown.contains("<｜tool") && !shown.contains("```json"),
                    "no part of the split marker went out: " + shown);
            assertEquals(shown, storedAnswer());
            assertEquals(1, sentBack());
        } finally {
            cleanup();
        }
    }

    @Test
    void markupBesideARealCallIsDroppedAndNotReplayed() throws Exception {
        String said = "Checking the ledger.\n<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function"
                + "<｜tool▁sep｜>find_entities\n```json\n{\"name\":\"Acme\"}\n```"
                + "<｜tool▁call▁end｜><｜tool▁calls▁end｜>";
        fixture(List.of(ScriptedReply.textWithTool(said), ScriptedReply.text(ANSWER)));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: done"), sse);
            String shown = streamed(sse);
            assertTrue(shown.startsWith("Checking the ledger.\n"), shown);
            assertTrue(shown.endsWith(ANSWER), shown);
            assertTrue(!shown.contains("tool▁"), shown);
            assertEquals(shown, storedAnswer());
            assertEquals(2, requests().size(), "the real call ran; nothing is sent back");
            assertEquals(0, sentBack());
            // 这一步落在叙述之后、答案之前，不在扣掉的标记之后
            String stepFrame = null;
            for (String block : sse.split("\n\n")) {
                if (stepFrame == null && block.startsWith("event: step\ndata: ")) {
                    stepFrame = block.substring("event: step\ndata: ".length());
                }
            }
            JsonNode step = frames().parse(stepFrame);
            assertEquals(shown.length() - ANSWER.length(), step.path("at").asInt());
            JsonNode exchange = storedExchange();
            assertEquals("Checking the ledger.\n",
                    exchange.get(0).path("content").asText());
            assertTrue(!exchange.toString().contains("tool▁"), exchange.toString());
        } finally {
            cleanup();
        }
    }

    @Test
    void textThatOnlyStartsLikeAMarkerGoesOutWholeAndOnce() throws Exception {
        String text = "<b>Acme</b> grew 5% [1].\n"
                + "<DSML> is the name of that markup, not a call.";
        fixture(List.of(TOOL, ScriptedReply.splitText(List.of(
                "<", "b>Acme</b> grew 5% [1].",
                "\n<DS", "ML> is the name of that markup, not a call."))));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(text, streamed(sse));
            assertEquals(text, storedAnswer());
            assertEquals(2, requests().size());
            assertEquals(0, sentBack());
        } finally {
            cleanup();
        }
    }

    @Test
    void aFencedExampleOfTheMarkupIsReleasedWhenTheTurnEnds() throws Exception {
        String example = "It looks like this:\n~~~\n<｜tool▁calls▁begin｜>"
                + "<｜tool▁call▁begin｜>function<｜tool▁sep｜>search_chunks\n~~~\n"
                + "That is the raw form of one call.";
        fixture(List.of(ScriptedReply.tool("no_evidence_needed",
                "{\"reason\":\"about the format\"}"), ScriptedReply.text(example)));
        try {
            String sse = ask("What does DeepSeek's raw tool-call format look like?");
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(example, streamed(sse));
            assertEquals(example, storedAnswer());
            assertEquals(2, requests().size());
            assertEquals(0, sentBack());
        } finally {
            cleanup();
        }
    }
}
