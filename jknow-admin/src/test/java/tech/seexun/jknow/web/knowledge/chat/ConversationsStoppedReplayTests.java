package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tech.seexun.jknow.web.knowledge.store.StoreTestBase;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.ConversationsService.History;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;

/**
 * 停止标记随原文回放，模型上下文另外说明回答未完成（#934）
 * （store a_stopped_chat_is_replayed_as_incomplete，断言原值移植）。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（15432）不可达，跳过 store 集成测试")
class ConversationsStoppedReplayTests extends StoreTestBase {

    public static boolean envUp() {
        return sharedStackUp();
    }

    @org.springframework.beans.factory.annotation.Autowired
    ConversationsService conversations;

    @Test
    void aStoppedChatIsReplayedAsIncomplete() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'stopped-chat-test')", org);
        try {
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'stopped-chat-test')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'stopped-chat-test')",
                    kb, ws);
            jdbc.update("CALL jknow_test_user(?, ?, ?, 'Stopped Chat')", user, org,
                    user + "@stopped-chat.test");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Object[] row : new Object[][]{
                    {"A complete answer.", false, "A complete answer."},
                    {"已生成的部分", true,
                            "[This response was stopped by the user before completion.]\n\n已生成的部分"},
                    {"", true,
                            "[This response was stopped by the user before completion.]\n\n"}}) {
                String content = (String) row[0];
                boolean stopped = (Boolean) row[1];
                String expectedContext = (String) row[2];
                UUID conversation = conversations.create(kb, user, "question");
                UUID question = conversations.appendMessage(conversation, "user", "question",
                        TurnRecord.empty(mapper));
                JsonNode sources = mapper.readTree("[{\"number\": 1, \"title\": \"Record\"}]");
                JsonNode exchange = mapper.readTree("""
                        [{"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function",
                          "function": {"name": "no_evidence_needed", "arguments": "{}"}}]},
                         {"role": "tool", "tool_call_id": "call_1", "content": "ok"}]""");
                UUID answer = conversations.appendMessage(conversation, "assistant", content,
                        new TurnRecord(stopped, mapper.createArrayNode(), sources,
                                mapper.createArrayNode(), exchange));

                var messages = conversations.messages(conversation);
                assertEquals(2, messages.size());
                assertFalse(messages.get(0).stopped());
                assertEquals(content, messages.get(1).content(), "历史保留原文");
                assertEquals(stopped, messages.get(1).stopped());

                History history = conversations.recentContext(conversation, 20);
                assertEquals(List.of(question, answer), history.turnIds);
                assertEquals("user", history.turns.get(0)[0]);
                assertEquals("question", history.turns.get(0)[1]);
                assertEquals("assistant", history.turns.get(1)[0]);
                assertEquals(expectedContext, history.turns.get(1)[1]);
                List<JsonNode> sourcesList = new java.util.ArrayList<>();
                sources.forEach(sourcesList::add);
                assertEquals(sourcesList, history.lastSources);
                List<JsonNode> exchangeList = new java.util.ArrayList<>();
                exchange.forEach(exchangeList::add);
                assertEquals(exchangeList, history.lastToolExchange);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private static void assertFalse(boolean v) {
        org.junit.jupiter.api.Assertions.assertFalse(v);
    }
}
