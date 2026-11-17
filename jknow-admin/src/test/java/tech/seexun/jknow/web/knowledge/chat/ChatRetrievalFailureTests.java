package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.seexun.jknow.search.SearchService;
import tech.seexun.jknow.search.SearchService.QueryEmbedder;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.NameVectorsService.Embedder;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.entity.ConceptMapping;

/**
 * 两种检索失败各说各的原因，底层诊断只能留在服务端（chat_retrieval_failure_tests.rs
 * 唯一一例，断言原值移植——M5 任务 4 记「手段等价物待任务 6」，本类即等价物）。
 *
 * <p><b>手段等价（如实记）</b>：Rust 用夹具级连接池的 TEMP TABLE 遮住
 * chunks/concept_mappings，让<b>真实查询</b>因缺列失败——Java 侧共享 Hikari 池不适用
 * 同法（TEMP TABLE 只遮单连接，池轮换不可控）。等价手段：测试构造的 service 子类在
 * 查询面抛同款 {@code SQLException("column … does not exist")}，从
 * {@link ChatController#chatAttached} 起整条链（SSE 帧序/落库/会话释放）全真身——唯一
 * 的缝在 service 查询面，与 Rust 唯一的缝在 TEMP TABLE 同位；生产代码零改动。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatRetrievalFailureTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    @Autowired
    ChatToolSchema schema;
    @Autowired
    ToolExecutor tools;
    @Autowired
    ChatFinalizer finalizer;
    @Autowired
    ChatClients chatClients;
    @Autowired
    ConversationsService conversations;
    @Autowired
    SettingsService settings;
    @Autowired
    DatasourcesService datasources;
    @Autowired
    KbAccess rbac;
    @Autowired
    LlmSupport llm;

    /// 两种失败同一张脸：一句 code、一句人话，别的不出门
    @Test
    void mappingAndDocumentRetrievalFailuresHaveDistinctSafeCodes() throws Exception {
        for (boolean mapping : new boolean[]{true, false}) {
            String code = mapping ? "mapping_search_failed" : "search_failed";
            String message = mapping ? "Could not retrieve relevant mappings."
                    : "Could not search the documents.";
            fixture(List.of(ScriptedReply.legacyOnly(400, "unused")));
            UUID source = null;
            try {
                if (mapping) {
                    source = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO data_sources (id, name, engine, conn_string, created_by)
                             VALUES (?, ?, 'postgres', 'postgres://u:p@db.test:5432/w', ?)
                            """, source, "mapping failure " + kb, user.id());
                    datasources.mount(kb, source);
                }
                String sse = askThrough(failingController(mapping), "What is the revenue?");

                assertEquals(1, countMatches(sse, "event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                String data = java.util.Arrays.stream(sse.split("\n\n"))
                        .filter(f -> f.startsWith("event: error")).findFirst().orElseThrow()
                        .lines().filter(l -> l.startsWith("data: "))
                        .map(l -> l.substring("data: ".length())).findFirst().orElseThrow();
                assertTrue(frames().mapper.readTree(data).equals(frames().mapper.readTree(
                        "{\"code\":\"" + code + "\",\"error\":\"" + message + "\"}")),
                        data);
                assertFalse(sse.contains("does not exist"),
                        "the SQL diagnostic must stay server-side:\n" + sse);

                UUID id = onlyConversation();
                List<String[]> messages = conversations.messages(id).stream()
                        .map(m -> new String[]{m.role(), String.valueOf(m.content())})
                        .toList();
                assertEquals(1, messages.size(), "only the question is stored");
                assertEquals("user", messages.get(0)[0]);
                assertNull(live.attach(id), "the session is released after the error");
                assertEquals(mapping ? 0 : 2, requests().size(),
                        "mapping fails before any model call; the documents case spends the"
                                + " two tool-carrier requests the fallback needs");
            } finally {
                if (source != null) {
                    jdbc.update("DELETE FROM kb_data_sources WHERE kb_id = ? AND"
                            + " data_source_id = ?", kb, source);
                    jdbc.update("DELETE FROM data_sources WHERE id = ?", source);
                }
                cleanup();
            }
        }
    }

    /** 唯一的缝：查询面抛缺列 SQLException（Rust TEMP TABLE 的同位手段）。 */
    private ChatController failingController(boolean mappingFailure) {
        String table = mappingFailure ? "concept_mappings" : "chunks";
        java.sql.SQLException boom = new java.sql.SQLException(
                "column " + table + ".unrelated_column does not exist");
        MappingsService mappings = new MappingsService(null) {
            @Override
            public List<ConceptMapping> relevant(UUID kbId, String rawQuery, int k,
                                                 Embedder embedder, String model) {
                throw new RuntimeException(boom);
            }
        };
        SearchService search = new SearchService(null, null, null) {
            @Override
            public List<ChunkView> search(UUID kbId, String query, int topK,
                                          OffsetDateTime asOf, QueryEmbedder embedder) {
                throw new RuntimeException(boom);
            }
        };
        ChatRunner runner = new ChatRunner(frames(), schema, tools, finalizer, search,
                mappings, settings, llm);
        // ChatGeneration 用自己的 runner 跑生产者——缝要下到这里，controller 的 runner
        // 参数只服务 controller 内的其余路径
        ChatGeneration failing = new ChatGeneration(frames(), conversations, runner);
        return new ChatController(frames(), live, chatClients, failing, runner,
                conversations, settings, datasources, rbac);
    }

    private String askThrough(ChatController controller, String message) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SessionFilter.USER_ATTR, user);
        return collect(controller.chatAttached(kb,
                new ChatController.ChatReq(null, message, null), req));
    }

    private static int countMatches(String hay, String needle) {
        int count = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
