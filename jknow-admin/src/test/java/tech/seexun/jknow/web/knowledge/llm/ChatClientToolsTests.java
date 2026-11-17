package tech.seexun.jknow.web.knowledge.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.GovernorPrompts;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;

/**
 * ChatClient.chatTools 的工具回合协议面（lib.rs:15-58 对等）——I-1 单测：端点挂在
 * tool_call 上的 {@code extra_content}（Gemini 3 的 thought_signature）必须经
 * AssistantTurn.toMessage 原样带回下一轮请求体，丢了同一回合的工具结果就 400
 * 「Function call is missing a thought_signature」。脚本化端点（工具档）两回合，
 * 回灌证据落在第二个请求体上。
 */
class ChatClientToolsTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 脚本端点的一个工具回合 message（extraContent 为 null 时该项不带 extra_content）。 */
    private static String turn(String id, String name, String args, String extraContent) {
        return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{"
                + "\"id\":\"" + id + "\",\"type\":\"function\""
                + (extraContent == null ? "" : ",\"extra_content\":" + extraContent)
                + ",\"function\":{\"name\":\"" + name + "\",\"arguments\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(args)
                + "}}]}";
    }

    @Test
    void extraContentSurvivesTheRoundTrip() throws Exception {
        ScriptedModelServer model = ScriptedModelServer.start(List.of(), List.of(
                turn("c1", "facts", "{\"side\":\"A\"}",
                        "{\"google\":{\"thought_signature\":\"sig-123\"}}"),
                turn("c2", "decide",
                        "{\"verdict\":\"same\",\"confidence\":0.9,\"why\":\"one CEO\"}",
                        null)));
        try {
            ChatClient client = new ChatClient(model.endpoint(), null, "scripted");
            JsonNode tools = GovernorPrompts.tools();
            ObjectNode user = JSON.createObjectNode();
            user.put("role", "user");
            user.put("content", "look at this pair");
            List<JsonNode> messages = new ArrayList<>();
            messages.add(user);

            ChatClient.AssistantTurn first = client.chatTools(messages, tools);
            messages.add(first.toMessage());
            assertEquals(1, first.toolCalls.size());
            assertEquals("sig-123", first.toolCalls.get(0).extraContent()
                    .path("google").path("thought_signature").asText(),
                    "解析侧收下端点的私货（is_object 才收，其余当没有）");
            messages.add(ChatClient.toolResultMessage("c1", "0 facts of A"));

            ChatClient.AssistantTurn second = client.chatTools(messages, tools);

            // 回灌证据：第二个请求体里 assistant 消息的 tool_calls[0] 原样带着 extra_content
            JsonNode echoed = model.requests().get(1)
                    .path("messages").get(1).path("tool_calls").get(0)
                    .path("extra_content");
            assertTrue(echoed.isObject(), "同一回合送回结果必须原样附上，否则 Gemini 3 直接 400");
            assertEquals("sig-123", echoed.path("google").path("thought_signature").asText());
            // 端点没给的调用不凭空造键（多一个 null 键也是形状变化）
            assertFalse(second.toMessage().path("tool_calls").get(0).has("extra_content"));
        } finally {
            model.stop();
        }
    }
}
