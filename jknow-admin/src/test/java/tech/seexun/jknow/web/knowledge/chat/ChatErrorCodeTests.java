package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 对话流的失败带 code（0004）（chat_error_code_tests.rs 全 3 例，断言原值移植）：
 * 服务端不出显示文本——error 帧与请求被拒同一个信封 {error, code}。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatErrorCodeTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private static final ScriptedReply TOOL =
            ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}");
    private static final String DSML = "<｜DSML｜ calls>\n"
            + "<｜DSML｜ invoke name=\"entity_facts\">{}</｜DSML｜ invoke>\n</｜DSML｜ calls>";

    private String[] failure(String sse) {
        for (String frame : sse.split("\n\n")) {
            if (frame.startsWith("event: error\ndata: ")) {
                JsonNode body = frames().parse(frame.substring("event: error\ndata: ".length()));
                return new String[]{body.path("code").asText(""), body.path("error").asText("")};
            }
        }
        throw new AssertionError("no error frame:\n" + sse);
    }

    @Test
    void eachFailureAUserCanMeetCarriesItsCode() throws Exception {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{"empty after a retry",
                List.of(TOOL, ScriptedReply.empty(), ScriptedReply.empty()), "answer_empty"});
        List<ScriptedReply> budget = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            budget.add(ScriptedReply.narratedTool());
        }
        budget.add(ScriptedReply.text(DSML));
        budget.add(ScriptedReply.text(DSML));
        cases.add(new Object[]{"tool-control text at the budget", budget, "answer_tool_text"});
        cases.add(new Object[]{"a tool call written as text twice (#946)",
                List.of(TOOL, ScriptedReply.text(DSML), ScriptedReply.text(DSML)),
                "answer_tool_text"});
        cases.add(new Object[]{"out of credit", List.of(ScriptedReply.http(402)),
                "model_out_of_credit"});
        cases.add(new Object[]{"rate limited", List.of(ScriptedReply.http(429)),
                "model_rate_limited"});
        cases.add(new Object[]{"unavailable", List.of(ScriptedReply.http(503)),
                "model_unavailable"});
        cases.add(new Object[]{"key rejected", List.of(ScriptedReply.http(401)),
                "model_rejected"});
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            List<ScriptedReply> replies = (List<ScriptedReply>) c[1];
            fixture(replies);
            try {
                String sse = ask("What changed at Acme?");
                String[] got = failure(sse);
                assertEquals(c[2], got[0], c[0] + ": " + got[1]);
                assertFalse(got[1].isEmpty(),
                        c[0] + ": the English sentence stays for logs and MCP");
            } finally {
                cleanup();
            }
        }
    }

    @Test
    void anAnswerThatCannotBeSavedSaysSoWithItsCode() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("Accepted answer.")));
        try {
            String name = "reject_assistant_" + kb.toString().replace("-", "");
            jdbc.update("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql "
                    + "AS $$ BEGIN IF NEW.role='assistant' AND EXISTS(SELECT 1 FROM "
                    + "conversations WHERE id=NEW.conversation_id AND kb_id='" + kb
                    + "') THEN RAISE EXCEPTION 'injected persistence failure'; END IF; "
                    + "RETURN NEW; END $$");
            jdbc.update("CREATE TRIGGER " + name + " BEFORE INSERT ON conversation_messages "
                    + "FOR EACH ROW EXECUTE FUNCTION " + name + "()");
            String sse = ask("What happened?");
            jdbc.update("DROP TRIGGER " + name + " ON conversation_messages");
            jdbc.update("DROP FUNCTION " + name + "()");
            assertEquals("answer_not_saved", failure(sse)[0]);
        } finally {
            cleanup();
        }
    }

    /// 收尾理由按原文认：finalization_error 的输出直接对 code——改措辞在这里失败
    @Test
    void theFinalizationReasonsKeepTheirCodes() {
        for (Object[] c : new Object[][]{
                {"", false, "answer_empty"},
                {"Answer.", true, "answer_tool_call"},
                {DSML, false, "answer_tool_text"}}) {
            String reason = ChatGuards.finalizationError((String) c[0], (Boolean) c[1],
                    "What changed?");
            assertTrue(reason != null);
            assertEquals(c[2], ChatFinalizer.unpublishableCode(reason), reason);
            String wrapped = "Model could not produce a final answer: "
                    + "Model could not produce a final answer after one recovery: " + reason;
            assertEquals(c[2], ChatFinalizer.unpublishableCode(wrapped), wrapped);
        }
        assertEquals("answer_too_long",
                ChatFinalizer.unpublishableCode("Model final answer exceeded the size limit"));
        assertEquals(null, ChatFinalizer.unpublishableCode("Final answer timed out"));
    }
}
