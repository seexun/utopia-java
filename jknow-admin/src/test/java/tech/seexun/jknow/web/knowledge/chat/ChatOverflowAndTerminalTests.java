package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;

/**
 * 长对话（#964）与「恰好一个挣来的终结」（#857）（chat_context_tests.rs 4 例 +
 * chat_terminal_tests.rs 的表驱动 2 例，断言原值移植）。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatOverflowAndTerminalTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private static final String REFUSAL = "{\"error\":{\"code\":\"context_length_exceeded\","
            + "\"message\":\"maximum context length is 4096 tokens; "
            + "request has 12000 tokens\"}}";
    private static final ScriptedReply GATE =
            ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"restating the conversation\"}");

    private void seed(int count, int chars) {
        for (int i = 0; i < count; i++) {
            conversations.appendMessage(conversationForSeeding, "user", "question " + i,
                    TurnRecord.empty(frames().mapper));
            conversations.appendMessage(conversationForSeeding, "assistant",
                    "answer " + i + ": " + "界".repeat(chars),
                    TurnRecord.empty(frames().mapper));
        }
    }

    private UUID conversationForSeeding;

    private void newConversation(String title) {
        conversationForSeeding = conversations.create(kb, user.id(), title);
    }

    private static int historyChars(JsonNode request, String current) {
        int total = 0;
        for (JsonNode m : request.path("messages")) {
            String role = m.path("role").asText();
            String content = m.path("content").asText(null);
            if ("system".equals(role) || current.equals(content)) {
                continue;
            }
            if (content != null) {
                total += content.codePoints().count();
            }
        }
        return total;
    }

    private static void preservesQuestion(JsonNode request, String current) {
        JsonNode messages = request.path("messages");
        int count = 0;
        for (JsonNode m : messages) {
            if ("user".equals(m.path("role").asText())
                    && current.equals(m.path("content").asText())) {
                count++;
            }
        }
        assertEquals(1, count);
        assertEquals("system", messages.get(0).path("role").asText());
        String firstRole = null;
        for (JsonNode m : messages) {
            if (!"system".equals(m.path("role").asText())) {
                firstRole = m.path("role").asText();
                break;
            }
        }
        assertEquals("user", firstRole, "no orphan answer at the history boundary");
    }

    /// 长历史在首个请求之前就裁进 32k；落库的对话一条不少
    @Test
    void longHistoryIsBoundedBeforeTheFirstRequest() throws Exception {
        fixture(List.of(GATE, ScriptedReply.text("Recovered.")));
        try {
            newConversation("context test");
            seed(10, 6000);
            String sse = askIn(conversationForSeeding, "current question");
            assertTrue(sse.contains("event: done"), sse);
            List<JsonNode> requests = requests();
            assertEquals(2, requests.size());
            assertTrue(historyChars(requests.get(0), "current question") <= 32_000);
            assertFalse(requests.get(0).toString().contains("question 0"));
            assertTrue(requests.get(0).toString().contains("question 9"));
            preservesQuestion(requests.get(0), "current question");
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM conversation_messages WHERE conversation_id=?",
                    Long.class, conversationForSeeding);
            assertEquals(22, count);
        } finally {
            cleanup();
        }
    }

    /// 超限对半裁一次；下一轮记得端点报的窗口（预算=窗口一半）
    @Test
    void overflowTrimsOnceAndTheNextTurnRemembersTheWindow() throws Exception {
        fixture(List.of(ScriptedReply.httpError(400, REFUSAL), GATE,
                ScriptedReply.text("Recovered."), GATE, ScriptedReply.text("Next answer.")));
        try {
            newConversation("context test");
            seed(6, 1500);
            String sse = askIn(conversationForSeeding, "current question");
            assertTrue(sse.contains("event: done"), sse);
            List<JsonNode> requests = requests();
            assertEquals(3, requests.size());
            assertTrue(historyChars(requests.get(1), "current question")
                    <= historyChars(requests.get(0), "current question") / 2);
            assertEquals(requests.get(0).path("tools"), requests.get(1).path("tools"));
            assertEquals(requests.get(0).path("tool_choice"), requests.get(1).path("tool_choice"));
            assertEquals(requests.get(0).path("messages").get(0),
                    requests.get(1).path("messages").get(0));
            String second = askIn(conversationForSeeding, "next question");
            assertTrue(second.contains("event: done"), second);
            requests = requests();
            assertEquals(5, requests.size());
            // 端点说窗口是 4096 token：预算是它的一半，2048 字
            assertTrue(historyChars(requests.get(3), "next question") <= 2048);
            String sent = requests.get(3).toString();
            assertTrue(sent.contains("question 5") && !sent.contains("question 4"), sent);
            preservesQuestion(requests.get(3), "next question");
        } finally {
            cleanup();
        }
    }

    /// 第二次拒绝有它自己的 code（context_too_long），不降级、不 done
    @Test
    void aSecondRefusalHasItsOwnCodeAndNeverFallsBack() throws Exception {
        fixture(List.of(ScriptedReply.httpError(400, REFUSAL),
                ScriptedReply.httpError(422, REFUSAL)));
        try {
            newConversation("context test");
            seed(4, 2000);
            String sse = askIn(conversationForSeeding, "current question");
            assertTrue(sse.contains("event: error"), sse);
            assertTrue(sse.contains("context_too_long"), sse);
            assertFalse(sse.contains("event: done"));
            List<JsonNode> requests = requests();
            assertEquals(2, requests.size());
            for (JsonNode r : requests) {
                assertTrue(r.has("tools"));
                preservesQuestion(r, "current question");
            }
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM conversation_messages WHERE conversation_id=? "
                            + "AND role='assistant'",
                    Long.class, conversationForSeeding);
            assertEquals(4, count, "no failed answer is stored as success");
        } finally {
            cleanup();
        }
    }

    /// 问题本身就超长：没有历史可丢，同一个请求不再发第二遍
    @Test
    void evenAnOversizedCurrentQuestionIsNeverCut() throws Exception {
        fixture(List.of(ScriptedReply.httpError(400, REFUSAL)));
        try {
            String question = "新问题😀".repeat(10_000);
            String sse = ask(question);
            assertTrue(sse.contains("context_too_long"), sse);
            List<JsonNode> requests = requests();
            assertEquals(1, requests.size());
            preservesQuestion(requests.get(0), question);
        } finally {
            cleanup();
        }
    }

    /// 一次对话轮次恰好有一个挣来的终结（#857）：表驱动的两行
    @Test
    void everyTurnEndsInExactlyOneEarnedTerminal() throws Exception {
        record Case(String what, List<ScriptedReply> replies, boolean done) {
        }
        ScriptedReply tool = ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}");
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("empty after retry",
                List.of(tool, ScriptedReply.empty(), ScriptedReply.empty()), false));
        cases.add(new Case("budget control text", List.of(
                ScriptedReply.narratedTool(), ScriptedReply.narratedTool(),
                ScriptedReply.narratedTool(), ScriptedReply.narratedTool(),
                ScriptedReply.narratedTool(), ScriptedReply.narratedTool(),
                ScriptedReply.text("<｜DSML｜ calls>"),
                ScriptedReply.text("<｜DSML｜ calls>")), false));
        for (Case c : cases) {
            fixture(c.replies());
            try {
                String sse = ask("What changed at Acme?");
                int done = 0;
                int error = 0;
                String reason = null;
                for (String frame : sse.split("\n\n")) {
                    String kind = "";
                    StringBuilder data = new StringBuilder();
                    for (String line : frame.split("\n")) {
                        if (line.startsWith("event:")) {
                            kind = line.substring("event:".length()).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring("data:".length()).trim());
                        }
                    }
                    if ("done".equals(kind)) {
                        done++;
                    } else if ("error".equals(kind)) {
                        error++;
                        reason = data.toString();
                    }
                }
                assertEquals(1, done + error, c.what() + "：恰好一个终结\n" + sse);
                if (c.done()) {
                    assertEquals(1, done, () -> c.what() + "：答案成立时该是 done");
                } else {
                    assertEquals(0, done, () -> c.what() + "：失败不许报成 done");
                    JsonNode body = reason == null ? null : frames().parse(reason);
                    assertTrue(body != null && !body.path("code").asText("").isEmpty()
                            && !body.path("error").asText("").isEmpty(),
                            c.what() + "：error 帧要带 code 和原句：" + reason);
                }
            } finally {
                cleanup();
            }
        }
    }
}
