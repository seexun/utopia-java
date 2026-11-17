package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 一轮认下谁（chat_resolved_tests.rs 2 例）+ 同一轮只嵌一次（chat_embed_once_tests.rs
 * 的整条对话档），断言原值移植。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatResolvedAndEmbedTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    private List<List<String>> resolvedIds() {
        List<List<String>> out = new ArrayList<>();
        jdbc.query("SELECT m.resolved::text FROM conversation_messages m "
                        + "JOIN conversations c ON c.id=m.conversation_id "
                        + "WHERE c.kb_id=? AND m.role='assistant' ORDER BY m.created_at",
                rs -> {
                    List<String> ids = new ArrayList<>();
                    frames().parse(rs.getString(1)).forEach(e -> ids.add(e.path("id").asText()));
                    out.add(ids);
                }, kb);
        return out;
    }

    private void entity(UUID id, UUID type, String name) {
        jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name) VALUES(?,?,?,?)",
                id, kb, type, name);
    }

    private UUID entityType(String key, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO entity_types(id,kb_id,key,label) VALUES(?,?,?,?)", id, kb,
                key, label);
        return id;
    }

    /// 一轮记的是读过的那个实体，不是同名候选全体；下一轮被告知的是它
    @Test
    void aTurnRemembersTheEntityItReadNotEveryCandidate() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        fixture(List.of(
                ScriptedReply.tool("find_entities", "{\"name\":\"Zhang Wei\"}"),
                ScriptedReply.tool("entity_facts", "{\"entity_id\":\"" + second + "\"}"),
                ScriptedReply.text("The Zhang Wei who joined in 2021."),
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"follow-up\"}"),
                ScriptedReply.text("Follow-up.")));
        try {
            UUID person = entityType("person", "Person");
            entity(first, person, "Zhang Wei");
            entity(second, person, "Zhang Wei");
            String sse = ask("Which Zhang Wei joined in 2021?");
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(List.of(List.of(second.toString())), resolvedIds(),
                    "the search's candidates are not identified; the one read by id is");

            String follow = askIn(onlyConversation(), "When did he join?");
            assertTrue(follow.contains("event: done"), follow);
            JsonNode request = requests().get(3);
            String told = "";
            for (JsonNode m : request.path("messages")) {
                String content = m.path("content").asText(null);
                if (content != null && content.startsWith("Entities already identified")) {
                    told = content;
                    break;
                }
            }
            assertTrue(told.contains(second.toString()), told);
            assertTrue(!told.contains(first.toString()),
                    "the unchosen namesake must not be handed to the next turn: " + told);
        } finally {
            cleanup();
        }
    }

    /// 同名歧义没选定时什么也不认
    @Test
    void aSearchRemembersOnlyAClearBestMatch() throws Exception {
        fixture(List.of(
                ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}"),
                ScriptedReply.text("Acme is an organization."),
                ScriptedReply.tool("find_entities", "{\"name\":\"Zhang Wei\"}"),
                ScriptedReply.text("Which Zhang Wei do you mean?")));
        try {
            UUID org = entityType("organization", "Organization");
            UUID person = entityType("person", "Person");
            UUID acme = UUID.randomUUID();
            entity(acme, org, "Acme");
            entity(UUID.randomUUID(), org, "Acme Labs");
            entity(UUID.randomUUID(), person, "Zhang Wei");
            entity(UUID.randomUUID(), person, "Zhang Wei");
            String sse = ask("What is Acme?");
            assertTrue(sse.contains("event: done"), sse);
            sse = ask("Tell me about Zhang Wei.");
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(List.of(List.of(acme.toString()), List.of()), resolvedIds());
        } finally {
            cleanup();
        }
    }

    /// 整条对话：先检索分块、再按名字查实体——问题只嵌一次（#971）
    @Test
    void aChatTurnEmbedsItsQuestionOnce() throws Exception {
        fixture(List.of(
                ScriptedReply.tool("search_chunks", "{\"query\":\"Where is the Acme lab?\"}"),
                ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}"),
                ScriptedReply.tool("entity_facts", "{\"entity_id\":\"Acme\"}"),
                ScriptedReply.text("The Acme lab is in Harbor.")));
        try {
            // 画像近的 Acme：问题向量 (1,0,0) 应选中它
            UUID org = entityType("organization", "Organization");
            UUID place = entityType("place", "Place");
            UUID located = UUID.randomUUID();
            jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,temporal) "
                    + "VALUES(?,?,'located_in','located in','state')", located, kb);
            UUID near = UUID.randomUUID();
            UUID far = UUID.randomUUID();
            UUID harbor = UUID.randomUUID();
            jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name,"
                    + "profile_embedding) VALUES(?,?,?,'Acme','[1,0,0]')", near, kb, org);
            jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name,"
                    + "profile_embedding) VALUES(?,?,?,'Acme','[0,1,0]')", far, kb, org);
            jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name) "
                    + "VALUES(?,?,?,'Harbor')", harbor, kb, place);
            jdbc.update("INSERT INTO facts(id,kb_id,subject_id,predicate_id,object_id,confidence) "
                    + "VALUES(?,?,?,?,?,0.9)", UUID.randomUUID(), kb, near, located, harbor);
            jdbc.update("UPDATE llm_settings SET embed_base_url=?, embed_model='fake-embedding' "
                    + "WHERE workspace_id=(SELECT workspace_id FROM knowledge_bases WHERE id=?)",
                    fake.endpoint(), kb);
            String question = "Where is the Acme lab?";
            String sse = ask(question);
            assertTrue(sse.contains("event: done"), sse);
            int steps = 0;
            for (String block : sse.split("\n\n")) {
                if (block.startsWith("event: step")) {
                    steps++;
                }
            }
            assertEquals(3, steps, sse);
            assertEquals(List.of(List.of(question)), fake.embedRequests(),
                    "检索与两次按名字查实体共用这一个向量");
        } finally {
            cleanup();
        }
    }
}
