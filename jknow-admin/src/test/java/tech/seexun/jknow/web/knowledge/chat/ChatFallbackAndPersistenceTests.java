package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 降级与落库（chat_fallback_tests.rs 2 例 + chat_persistence_tests.rs 2 例，断言原值
 * 移植）：只有端点拒绝带工具的请求才降级一次性 RAG；降级答案的存库失败必须 error 收口
 * （断连后也一样）。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatFallbackAndPersistenceTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    /** legacy RAG 降级要真检索（ES 9200）——ES 不可达的环境跳过（P1 任务 8 重建环境适配）。 */
    static boolean esUp() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 9200), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /// 端点拒工具（400/422）→ 一次性 RAG：检索一次、sources 帧、流式作答、落库
    @Test
    void fallbackAnswerStreamedAndStored() throws Exception {
        Assumptions.assumeTrue(esUp(), "ES（9200）不可达：legacy RAG 降级面跳过");
        fixture(List.of(ScriptedReply.legacyOnly(400, "Fallback answer.")));
        try {
            String sse = ask("orchard");
            assertTrue(sse.contains("event: done") && sse.contains("Fallback answer."), sse);
            assertFalse(sse.contains("event: error"), sse);
            List<JsonNode> plain = new ArrayList<>();
            for (JsonNode r : requests()) {
                if (!r.has("tools")) {
                    plain.add(r);
                }
            }
            assertEquals(1, plain.size());
            assertEquals("Fallback answer.", storedAnswer());
            // 兼容协商：带工具的请求恰好两次（required 被拒后去掉重发一次）
            assertEquals(2, requests().size() - plain.size());
            assertTrue(sse.contains("data: []"), "空检索也发 sources 帧:\n" + sse);
        } finally {
            cleanup();
        }
    }

    /// 其余 provider 失败（401/402/429/500）不进降级：error 收口、不请求 RAG
    @Test
    void otherProviderFailuresDoNotEnterFallback() throws Exception {
        for (int status : new int[]{401, 402, 429, 500}) {
            fixture(List.of(ScriptedReply.legacyOnly(status, "unused")));
            try {
                String sse = ask("orchard");
                assertTrue(sse.contains("event: error") && !sse.contains("event: done"), sse);
                for (JsonNode r : requests()) {
                    assertTrue(r.has("tools"), "retrieval failure must not request a RAG answer");
                }
                assertEquals(null, storedAnswer());
            } finally {
                cleanup();
            }
        }
    }

    /// 降级答案存库失败：绝不报 done
    @Test
    void failedFallbackSaveNeverReportsDone() throws Exception {
        Assumptions.assumeTrue(esUp(), "ES（9200）不可达：legacy RAG 降级面跳过");
        fixture(List.of(ScriptedReply.legacyOnly(422, "Generated answer.")));
        try {
            String name = "reject_chat_" + kb.toString().replace("-", "");
            jdbc.update("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql "
                    + "AS $$ BEGIN IF NEW.role='assistant' AND EXISTS(SELECT 1 FROM "
                    + "conversations WHERE id=NEW.conversation_id AND kb_id='" + kb
                    + "') THEN RAISE EXCEPTION 'private persistence diagnostic'; END IF; "
                    + "RETURN NEW; END $$");
            jdbc.update("CREATE TRIGGER " + name + " BEFORE INSERT ON conversation_messages "
                    + "FOR EACH ROW EXECUTE FUNCTION " + name + "()");
            String sse = ask("hello");
            jdbc.update("DROP TRIGGER " + name + " ON conversation_messages");
            jdbc.update("DROP FUNCTION " + name + "()");
            assertTrue(sse.contains("event: error") && !sse.contains("event: done"), sse);
            assertTrue(sse.contains("Could not confirm that the answer was saved."), sse);
            assertFalse(sse.contains("private persistence diagnostic"), sse);
            assertEquals(null, storedAnswer());
        } finally {
            cleanup();
        }
    }

    /// 降级答案正常存库：done 时库里一定读得到
    @Test
    void committedFallbackAnswerIsReadableAtDone() throws Exception {
        Assumptions.assumeTrue(esUp(), "ES（9200）不可达：legacy RAG 降级面跳过");
        fixture(List.of(ScriptedReply.legacyOnly(422, "Generated answer.")));
        try {
            String sse = ask("hello");
            assertTrue(sse.contains("event: done") && !sse.contains("event: error"), sse);
            assertEquals("Generated answer.", storedAnswer());
        } finally {
            cleanup();
        }
    }
}
