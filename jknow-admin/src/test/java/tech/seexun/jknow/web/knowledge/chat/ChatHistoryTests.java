package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;

/**
 * 一次请求里模型看到什么（chat_history_tests.rs 全 2 例，断言原值移植）：问题只出现一次
 * （历史回放 + prompt 各一份，按落库 id 剔重）；已认下的实体作为 system 紧挨当前问题。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatHistoryTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private static int userTurns(JsonNode request, String text) {
        int count = 0;
        for (JsonNode m : request.path("messages")) {
            if ("user".equals(m.path("role").asText()) && text.equals(m.path("content").asText())) {
                count++;
            }
        }
        return count;
    }

    private static String lastUser(JsonNode request) {
        JsonNode messages = request.path("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if ("user".equals(m.path("role").asText())) {
                return m.path("content").asText();
            }
        }
        return "";
    }

    @Test
    void theQuestionReachesTheModelOnce() throws Exception {
        ScriptedReply gate =
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"greeting\"}");
        fixture(List.of(gate, ScriptedReply.text("First answer."), gate,
                ScriptedReply.text("Second answer.")));
        try {
            String sse = ask("first question");
            assertTrue(sse.contains("event: done"), sse);
            UUID id = onlyConversation();
            String second = askIn(id, "second question");
            assertTrue(second.contains("event: done"), second);

            List<JsonNode> requests = requests();
            assertEquals(4, requests.size(), "a tool turn and an answer per question");
            for (int i = 0; i < requests.size(); i++) {
                String current = i < 2 ? "first question" : "second question";
                assertEquals(1, userTurns(requests.get(i), current),
                        "request " + i + " must carry its question once");
                assertEquals(current, lastUser(requests.get(i)), "request " + i);
                // 更早的问题只从历史里回放，且只出现一次
                assertEquals(1, userTurns(requests.get(i), "first question"), "request " + i);
            }
        } finally {
            cleanup();
        }
    }

    @Test
    void earlierEntitiesSitRightBeforeTheQuestion() throws Exception {
        fixture(List.of(
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"follow-up\"}"),
                ScriptedReply.text("Answered.")));
        try {
            // 上一轮认了实体：下一轮把它的 id 作为 system 消息、紧挨当前问题之前发
            UUID id = conversations.create(kb, user.id(), "Acme");
            conversations.appendMessage(id, "user", "Who founded Acme?",
                    TurnRecord.empty(frames().mapper));
            conversations.appendMessage(id, "assistant", "Acme was founded by Ada.",
                    new TurnRecord(false, frames().mapper.createArrayNode(),
                            frames().mapper.createArrayNode(),
                            frames().mapper.createArrayNode().add(frames().mapper.createObjectNode()
                                    .put("id", UUID.randomUUID().toString())
                                    .put("name", "Acme").put("type", "Organization")),
                            frames().mapper.createArrayNode()));
            String sse = askIn(id, "When was it founded?");
            assertTrue(sse.contains("event: done"), sse);

            JsonNode first = requests().get(0);
            JsonNode messages = first.path("messages");
            int block = -1;
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).path("role").asText())
                        && messages.get(i).path("content").asText("")
                                .startsWith("Entities already identified")) {
                    block = i;
                    break;
                }
            }
            assertTrue(block >= 0, "the entities resolved earlier are sent:\n" + first);
            assertEquals("user", messages.get(block + 1).path("role").asText());
            assertEquals("When was it founded?", messages.get(block + 1).path("content").asText());
            assertEquals(1, userTurns(first, "When was it founded?"));
            assertEquals(1, userTurns(first, "Who founded Acme?"));
        } finally {
            cleanup();
        }
    }
}
