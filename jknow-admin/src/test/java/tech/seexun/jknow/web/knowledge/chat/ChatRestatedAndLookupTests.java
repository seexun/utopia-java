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

/**
 * 改写沿用上一条的来源（#943）（chat_restated_tests.rs 三例）+ 精确名命中
 * （chat_lookup_tests.rs 一例），断言原值移植。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatRestatedAndLookupTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private static final ScriptedReply FIRST =
            ScriptedReply.text("The documented target is 95% [1].");
    private static final ScriptedReply RESTATE =
            ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"shorter\"}");

    private void seedDocument(UUID document) {
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES(?,?,'target.md',"
                + "repeat('0',64))", document, kb);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES(?,?,?,0,"
                + "'The planned target is 95%, not a measured result.')", UUID.randomUUID(),
                kb, document);
    }

    private List<JsonNode> storedSources() {
        List<JsonNode> out = new ArrayList<>();
        jdbc.query("SELECT m.sources::text FROM conversation_messages m "
                        + "JOIN conversations c ON c.id=m.conversation_id "
                        + "WHERE c.kb_id=? AND m.role='assistant' ORDER BY m.created_at",
                rs -> { out.add(frames().parse(rs.getString(1))); }, kb);
        return out;
    }

    private JsonNode liveSources(String sse) {
        String data = null;
        for (String frame : sse.split("\n\n")) {
            if (frame.startsWith("event: sources\ndata: ")) {
                data = frame.substring("event: sources\ndata: ".length());
            }
        }
        return frames().parse(data);
    }

    private String twoTurns() throws Exception {
        String first = ask("What is the documented target?");
        assertTrue(first.contains("event: done"), first);
        UUID id = onlyConversation();
        String second = askIn(id, "Say it shorter");
        assertTrue(second.contains("event: done"), second);
        return second;
    }

    /// 改写把上一条回答里被引用过的 [n] 沿用：live 与 reload 一致
    @Test
    void aRestatementKeepsTheSourcesItRepeats() throws Exception {
        UUID document = UUID.randomUUID();
        fixture(List.of(ScriptedReply.document(document), FIRST, RESTATE,
                ScriptedReply.text("Target: 95% [1].")));
        try {
            seedDocument(document);
            String second = twoTurns();
            List<JsonNode> stored = storedSources();
            assertEquals(2, stored.size());
            assertEquals(1, stored.get(0).get(0).path("n").asInt());
            assertEquals(stored.get(0), stored.get(1),
                    "the restated [1] opens the passage the first answer cited");
            assertEquals(stored.get(1), liveSources(second), "live and after reload alike");
        } finally {
            cleanup();
        }
    }

    /// 改写引了上一条没引过的号：一条不给，不去猜
    @Test
    void aRestatementCitingANumberTheLastAnswerDidNotKeepsNone() throws Exception {
        UUID document = UUID.randomUUID();
        fixture(List.of(ScriptedReply.document(document), FIRST, RESTATE,
                ScriptedReply.text("Target: 95% [2].")));
        try {
            seedDocument(document);
            twoTurns();
            List<JsonNode> stored = storedSources();
            assertEquals(2, stored.size());
            assertTrue(stored.get(1).isEmpty(), stored.get(1).toString());
        } finally {
            cleanup();
        }
    }

    /// 不带角标的改写：没有号就没有沿用
    @Test
    void aRestatementWithoutMarksKeepsNoSources() throws Exception {
        UUID document = UUID.randomUUID();
        fixture(List.of(ScriptedReply.document(document), FIRST, RESTATE,
                ScriptedReply.text("The target is ninety-five percent.")));
        try {
            seedDocument(document);
            twoTurns();
            List<JsonNode> stored = storedSources();
            assertEquals(2, stored.size());
            assertTrue(stored.get(1).isEmpty(), stored.get(1).toString());
        } finally {
            cleanup();
        }
    }

    /// 名字要够得着那个恰好叫这个名字的实体——九个更忙的「Apple Store …」挡不住 Apple
    @Test
    void aNameReachesTheEntityCalledExactlyThat() throws Exception {
        fixture(List.of(
                ScriptedReply.tool("find_entities", "{\"name\":\"Apple\"}"),
                ScriptedReply.tool("entity_facts", "{\"entity_id\":\"Apple\"}"),
                ScriptedReply.text("Apple has no recorded facts.")));
        try {
            UUID ty = UUID.randomUUID();
            jdbc.update("INSERT INTO entity_types(id,kb_id,key,label) VALUES(?,?,'organization',"
                    + "'Organization')", ty, kb);
            UUID located = UUID.randomUUID();
            jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind) VALUES(?,?,"
                    + "'located_in','located in','relation')", located, kb);
            UUID city = UUID.randomUUID();
            jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name) "
                    + "VALUES(?,?,?,'Cupertino')", city, kb, ty);
            UUID apple = UUID.randomUUID();
            jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name) "
                    + "VALUES(?,?,?,'Apple')", apple, kb, ty);
            for (int i = 1; i <= 9; i++) {
                UUID store = UUID.randomUUID();
                jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name) "
                        + "VALUES(?,?,?,?)", store, kb, ty, "Apple Store " + i);
                jdbc.update("INSERT INTO facts(id,kb_id,subject_id,predicate_id,object_id,"
                        + "recorded_at) VALUES(?,?,?,?,?,now())", UUID.randomUUID(), kb, store,
                        located, city);
            }
            String sse = ask("Tell me about Apple.");
            assertTrue(sse.contains("event: done"), sse);
            List<JsonNode> requests = requests();
            String found = latestToolResult(requests.get(1));
            assertTrue(found.startsWith("Best match: " + apple + " | Apple | "),
                    "find_entities must list the entity named Apple first:\n" + found);
            String facts = latestToolResult(requests.get(2));
            assertTrue(facts.contains("\"Apple\" = Apple ("), facts);
            assertTrue(facts.contains("Apple: no recorded facts."), facts);
            assertFalse(facts.contains("Cupertino"),
                    "a store's facts must not be read as Apple's:\n" + facts);
        } finally {
            cleanup();
        }
    }

    private static String latestToolResult(JsonNode request) {
        JsonNode messages = request.path("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if ("tool".equals(m.path("role").asText())) {
                return m.path("content").asText();
            }
        }
        return "";
    }
}
