package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;

/**
 * 重答（#936）：没有回答的最后一问就地再答一次（chat_retry_tests.rs 主干，断言原值
 * 移植）。从前答到一半失败，想再问只能重发，同一问题在历史里存两遍，没回答的那一遍
 * 还会在之后每一问里作为「没回答的一轮」回放给模型。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatRetryTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private static final ScriptedReply TOOL =
            ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}");
    private static final String QUESTION = "What changed at Acme?";

    private record Stored(UUID id, String role, String content) {
    }

    private List<Stored> stored(UUID conversationId) {
        return jdbc.query("SELECT id, role, content FROM conversation_messages "
                        + "WHERE conversation_id = ? ORDER BY created_at, id",
                (rs, i) -> new Stored(rs.getObject("id", UUID.class), rs.getString("role"),
                        rs.getString("content")), conversationId);
    }

    private UUID appendUser(UUID conversationId, String content) {
        return conversations.appendMessage(conversationId, "user", content,
                TurnRecord.empty(frames().mapper));
    }

    @Test
    void aQuestionWhoseAnswerFailedIsAnsweredInPlace() throws Exception {
        fixture(List.of(ScriptedReply.http(401), TOOL, ScriptedReply.text("Retried answer.")));
        try {
            String first = ask(QUESTION);
            assertTrue(first.contains("event: error"), "the first answer fails:\n" + first);
            UUID conversationId = onlyConversation();
            List<Stored> before = stored(conversationId);
            assertEquals(1, before.size(), "only the question is stored");
            UUID questionId = before.get(0).id();
            int askedBefore = requests().size();

            String sse = post(new ChatController.ChatReq(conversationId, QUESTION, questionId));
            assertTrue(sse.contains("event: done"), sse);
            // conversation 那一帧带着这一问存下的 id：界面凭它重答
            assertTrue(sse.contains("\"message_id\":\"" + questionId + "\""), sse);
            List<Stored> after = stored(conversationId);
            assertEquals(2, after.size());
            assertEquals(questionId, after.get(0).id());
            assertEquals("user", after.get(0).role());
            assertEquals(QUESTION, after.get(0).content());
            assertEquals("assistant", after.get(1).role());
            assertEquals("Retried answer.", after.get(1).content());
            // 重答的第一次模型请求里，这个问题只出现一次
            JsonNode request = requests().get(askedBefore);
            int asked = 0;
            for (JsonNode m : request.path("messages")) {
                if ("user".equals(m.path("role").asText())
                        && m.path("content").asText("").contains(QUESTION)) {
                    asked++;
                }
            }
            assertEquals(1, asked);
        } finally {
            cleanup();
        }
    }

    @Test
    void anAnsweredQuestionIsNotAnsweredAgain() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("First answer.")));
        try {
            String sse = ask(QUESTION);
            assertTrue(sse.contains("event: done"), sse);
            UUID conversationId = onlyConversation();
            UUID questionId = stored(conversationId).get(0).id();
            int before = requests().size();
            AppException refused = assertThrowsExactly(AppException.class,
                    () -> post(new ChatController.ChatReq(conversationId, QUESTION, questionId)));
            assertEquals("retry_answered", refused.code().orElse(null));
            assertEquals(before, requests().size(), "the model is not asked again");
            assertEquals(2, stored(conversationId).size(), "nothing more is stored");
        } finally {
            cleanup();
        }
    }

    /// 答案之后又有人接着问：不能原地重答，顺序会乱
    @Test
    void aQuestionFollowedByMoreConversationIsNotAnsweredAgain() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("First answer.")));
        try {
            ask(QUESTION);
            UUID conversationId = onlyConversation();
            UUID questionId = stored(conversationId).get(0).id();
            AppException refused = assertThrowsExactly(AppException.class,
                    () -> post(new ChatController.ChatReq(conversationId, QUESTION, questionId)));
            assertEquals("retry_answered", refused.code().orElse(null));
        } finally {
            cleanup();
        }
    }

    /// 不在这场对话里的 id：404
    @Test
    void aQuestionFromAnotherConversationIsRejected() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("a")));
        try {
            ask(QUESTION);
            UUID conversationId = onlyConversation();
            assertThrowsExactly(AppException.class, () -> post(new ChatController.ChatReq(
                    conversationId, QUESTION, UUID.randomUUID())));
        } finally {
            cleanup();
        }
    }

    /// 助手消息不是问题
    @Test
    void anAnswerCannotBeAnsweredAgain() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("First answer.")));
        try {
            ask(QUESTION);
            UUID conversationId = onlyConversation();
            UUID answerId = stored(conversationId).get(1).id();
            AppException refused = assertThrowsExactly(AppException.class,
                    () -> post(new ChatController.ChatReq(conversationId, QUESTION, answerId)));
            assertEquals("retry_not_question", refused.code().orElse(null));
        } finally {
            cleanup();
        }
    }

    /// 没有 conversation_id 的重试：缺上下文
    @Test
    void aRetryNeedsItsConversation() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("a")));
        try {
            AppException refused = assertThrowsExactly(AppException.class,
                    () -> post(new ChatController.ChatReq(null, QUESTION, UUID.randomUUID())));
            assertEquals("retry_needs_conversation", refused.code().orElse(null));
        } finally {
            cleanup();
        }
    }

    /// 被拒的重试要释放占位（准备失败 = Drop，live.rs 同语义）：retry_answered 是正常
    /// 用户流，拒绝之后同一会话立即可再问（不再 409 answer_running）、attach 回 idle——
    /// 不释放的话该会话被一条拒绝焊死到重启，接上的人还会在 ready 上等成死等
    @Test
    void aRefusedRetryReleasesTheConversationForTheNextQuestion() throws Exception {
        fixture(List.of(TOOL, ScriptedReply.text("First answer."),
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"follow-up\"}"),
                ScriptedReply.text("Second answer.")));
        try {
            String first = ask(QUESTION);
            assertTrue(first.contains("event: done"), first);
            UUID conversationId = onlyConversation();
            UUID questionId = stored(conversationId).get(0).id();
            AppException refused = assertThrowsExactly(AppException.class,
                    () -> post(new ChatController.ChatReq(conversationId, QUESTION, questionId)));
            assertEquals("retry_answered", refused.code().orElse(null));
            // 拒绝即释放：没有在跑的生成，接上回 idle
            assertNull(live.attach(conversationId));
            // 同一会话继续提问不再 409，一问一答照常落库
            String second = askIn(conversationId, "next question");
            assertTrue(second.contains("event: done"), second);
            List<Stored> after = stored(conversationId);
            assertEquals(4, after.size());
            assertEquals("user", after.get(2).role());
            assertEquals("next question", after.get(2).content());
            assertEquals("assistant", after.get(3).role());
            assertEquals("Second answer.", after.get(3).content());
            assertNull(live.attach(conversationId));
        } finally {
            cleanup();
        }
    }

    /// 重试与新问题共用占位（answer_running）：占住会话之后再判资格，409 不写历史
    @Test
    void newQuestionsAndRetriesShareAdmission() throws Exception {
        fixture(List.of(ScriptedReply.tool("no_evidence_needed",
                "{\"reason\":\"greeting\"}"), ScriptedReply.text("First answer.")));
        fake.holdBeforeDone();
        try {
            UUID conversationId = conversations.create(kb, user.id(), "admission");
            UUID questionId = appendUser(conversationId, "first greeting");
            // 第一问挂在模型流中途（帧已发、[DONE] 未发）
            // 无回答的问题代表上一轮失败；重试沿用这个 id（Rust 同款 retry=true 档）
            LiveRegistry.Attached attached = chat.chatAttached(kb,
                    new ChatController.ChatReq(conversationId, "first greeting", questionId),
                    request());
            fake.awaitGate();
            for (UUID retryWith : new UUID[]{null, questionId}) {
                AppException busy = assertThrowsExactly(AppException.class,
                        () -> chat.chatAttached(kb, new ChatController.ChatReq(conversationId,
                                "overlapping question", retryWith), request()));
                assertEquals("answer_running", busy.code().orElse(null));
            }
            assertEquals(1, stored(conversationId).size(),
                    "409 must not write the follow-up question");
            fake.release();
            StringBuilder sse = new StringBuilder();
            while (true) {
                LiveRegistry.Frame frame = attached.queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                if (frame == null || "done".equals(frame.event()) || "error".equals(frame.event())) {
                    break;
                }
            }
            assertEquals(2, stored(conversationId).size());
        } finally {
            fake.release();
            cleanup();
        }
    }

    private org.springframework.mock.web.MockHttpServletRequest request() {
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute(tech.seexun.jknow.web.knowledge.auth.SessionFilter.USER_ATTR, user);
        return req;
    }
}
