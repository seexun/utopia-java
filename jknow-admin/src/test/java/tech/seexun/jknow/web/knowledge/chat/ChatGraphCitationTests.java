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
 * 图谱答出来的回答点得开原句（#935）（chat_graph_citation_tests.rs 的主链两例，断言
 * 原值移植）：工具印的 [n] 随 sources 帧发出去、跟着回答落库；同一块一个号。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatGraphCitationTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    /** 一个项目、两个人、两篇文档（晚的先入库），一条 leads 张三、一条 leads 李四。 */
    private void seedGraph() {
        UUID person = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID leads = UUID.randomUUID();
        UUID zhang = UUID.randomUUID();
        UUID li = UUID.randomUUID();
        UUID aurora = UUID.randomUUID();
        UUID dCharter = UUID.randomUUID();
        UUID dHandover = UUID.randomUUID();
        UUID charter = UUID.randomUUID();
        UUID handover = UUID.randomUUID();
        UUID fZhang = UUID.randomUUID();
        UUID fLi = UUID.randomUUID();
        jdbc.update("INSERT INTO entity_types(id,kb_id,key,label) VALUES(?,?,'person','Person'),"
                + "(?,?,'project','Project')", person, kb, project, kb);
        jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,temporal) VALUES(?,?,"
                + "'leads','leads','state')", leads, kb);
        jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name,created_at) "
                + "VALUES(?,?,?,'Zhang San','2026-01-01')", zhang, kb, person);
        jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name,created_at) "
                + "VALUES(?,?,?,'Li Si','2026-01-01')", li, kb, person);
        jdbc.update("INSERT INTO entities(id,kb_id,type_id,canonical_name,created_at) "
                + "VALUES(?,?,?,'Project Aurora','2026-01-01')", aurora, kb, project);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256,created_at,doc_time) "
                + "VALUES(?,?,'charter.md',repeat('1',64),'2026-01-01','2023-01-05'),"
                + "(?,?,'handover.md',repeat('3',64),'2026-01-01','2024-07-05')",
                dCharter, kb, dHandover, kb);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text,created_at) "
                + "VALUES(?,?,?,0,'Zhang San leads Project Aurora from 2023-01-10.','2026-01-01'),"
                + "(?,?,?,0,'Li Si leads Project Aurora from 2024-07-05.','2026-01-01')",
                charter, kb, dCharter, handover, kb, dHandover);
        jdbc.update("INSERT INTO facts(id,kb_id,subject_id,predicate_id,object_id,valid_from,"
                + "valid_from_precision,valid_to,valid_to_precision,recorded_at,confidence) "
                + "VALUES(?,?,?,?,?,'2023-01-10T00:00:00Z','day','2024-07-05T00:00:00Z','day',"
                + "'2026-01-01',0.9),"
                + "(?,?,?,?,?,'2024-07-05T00:00:00Z','day',NULL,NULL,'2026-01-01',0.9)",
                fZhang, kb, zhang, leads, aurora, fLi, kb, li, leads, aurora);
        // 晚的那篇先插也先入库：取哪条证据按文档自己的日期，不按插入先后
        jdbc.update("INSERT INTO fact_evidence(fact_id,chunk_id,document_id,doc_version,quote) "
                + "VALUES(?,?,?,1,'Li Si leads Project Aurora from 2024-07-05.')",
                fLi, handover, dHandover);
        jdbc.update("INSERT INTO fact_evidence(fact_id,chunk_id,document_id,doc_version,quote) "
                + "VALUES(?,?,?,1,'Zhang San leads Project Aurora from 2023-01-10.')",
                fZhang, charter, dCharter);
    }

    private JsonNode storedSources() {
        return frames().parse(jdbc.queryForObject(
                "SELECT m.sources::text FROM conversation_messages m "
                        + "JOIN conversations c ON c.id=m.conversation_id "
                        + "WHERE c.kb_id=? AND m.role='assistant'",
                String.class, kb));
    }

    /// entity_facts 的每条事实行带着它证据的 [n]；sources 帧与落库一致；模型读到行上的号
    @Test
    void graphFactLinesCarryEvidenceMarksIntoSourcesAndStorage() throws Exception {
        fixture(List.of(
                ScriptedReply.tool("entity_facts", "{\"entity_id\":\"Zhang San\"}"),
                ScriptedReply.text("Zhang San led Project Aurora until the handover [1].")));
        try {
            seedGraph();
            String sse = ask("Who led Project Aurora?");
            assertTrue(sse.contains("event: done"), sse);
            List<JsonNode> requests = requests();
            String facts = "";
            for (JsonNode m : requests.get(1).path("messages")) {
                if ("tool".equals(m.path("role").asText())) {
                    facts = m.path("content").asText();
                }
            }
            assertTrue(facts.contains("Project Aurora (2023-01-10 → 2024-07-05)"), facts);
            assertTrue(facts.contains("[1]"), "the fact line carries its evidence mark:\n" + facts);
            // 存下的 sources 与最后一帧 sources 一致；第一块就是 charter（更早的文档）
            JsonNode stored = storedSources();
            assertEquals(1, stored.size());
            assertEquals("charter.md", stored.get(0).path("filename").asText());
            assertEquals(1, stored.get(0).path("n").asInt());
            String lastSourcesFrame = null;
            for (String block : sse.split("\n\n")) {
                if (block.startsWith("event: sources\ndata: ")) {
                    lastSourcesFrame = block.substring("event: sources\ndata: ".length());
                }
            }
            assertEquals(stored, frames().parse(lastSourcesFrame));
            assertEquals("Zhang San led Project Aurora until the handover [1].", storedAnswer());
        } finally {
            cleanup();
        }
    }

    /// 两块各拿各的号；同一块在两轮工具里只有一个号（号按出现序，检索与图谱共用）
    @Test
    void twoChunksGetDistinctNumbersSharedAcrossTools() throws Exception {
        fixture(List.of(
                ScriptedReply.tool("entity_facts", "{\"entity_id\":\"Zhang San\"}"),
                ScriptedReply.tool("entity_facts", "{\"entity_id\":\"Li Si\"}"),
                ScriptedReply.text("Both led it at different times [1][2].")));
        try {
            seedGraph();
            String sse = ask("Who led Project Aurora and when?");
            assertTrue(sse.contains("event: done"), sse);
            JsonNode stored = storedSources();
            assertEquals(2, stored.size());
            assertEquals(1, stored.get(0).path("n").asInt());
            assertEquals(2, stored.get(1).path("n").asInt());
            assertEquals("charter.md", stored.get(0).path("filename").asText());
            assertEquals("handover.md", stored.get(1).path("filename").asText());
            String seen = "";
            for (JsonNode r : requests()) {
                for (JsonNode m : r.path("messages")) {
                    if ("tool".equals(m.path("role").asText())) {
                        seen += m.path("content").asText() + "\n---\n";
                    }
                }
            }
            assertTrue(seen.contains("## leads →"), seen);
            assertTrue(seen.contains("[1]") && seen.contains("[2]"), seen);
        } finally {
            cleanup();
        }
    }
}
