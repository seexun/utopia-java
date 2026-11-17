package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.SettingsService;

/**
 * 移植自 crates/utopia-server/src/time_context_tests.rs（2 例）——相对的时间词从它自己
 * 那一节的日期起算（0064 决定 1、4）。两句「上周」算出来差一周；文档没说日期时不再另问
 * 一次开头，相对的时间词锚不到等着（C 级）。脚本化 SSE 模型端点 + 真库。断言逐条不改写。
 */
class TimeContextTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExtractionJob extraction;
    @Autowired
    ResolveTimeJob resolveTime;
    @Autowired
    SettingsService settings;

    private static final String WEEK_35 =
            "# 周报汇编\n\n## 第35周周报\n\n提报日期：2026年8月28日\n\n上周，码表一代在韩国市场开售。";
    private static final String WEEK_36 =
            "# 周报汇编\n\n## 第36周周报\n\n提报日期：2026年9月4日\n\n上周，码表一代在泰国市场开售。";

    private static OffsetDateTime day(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    @Test
    void lastWeekIsCountedFromTheDateOfItsOwnSection() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk0 = Uuids.nowV7();
        UUID chunk1 = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'time-context')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'time-context')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'time-context')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, '周报汇编.md', 'x')",
                doc, kb);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, ?)",
                chunk0, kb, doc, WEEK_35);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 1, ?)",
                chunk1, kb, doc, WEEK_36);
        String anchored = "{\"kind\": \"anchored\", \"anchor\": {\"kind\": \"document\"},"
                + " \"offset\": {\"count\": 1, \"unit\": \"week\", \"direction\": \"before\"}}";
        ScriptedModelServer model = ScriptedModelServer.start(List.of(
                "{\"e\": [[\"码表一代\", \"产品\", 1], [\"韩国市场\", \"市场\", 1]],"
                        + " \"s\": [[\"上周，码表一代在韩国市场开售。\", \"码表一代\", \"开售于\","
                        + " \"韩国市场\", null, null, \"上周\", null]], \"n\": [],"
                        + " \"t\": [[\"now\", \"提报日期\", \"2026年8月28日\","
                        + " {\"y\": 2026, \"m\": 8, \"d\": 28}, null]]}",
                "{\"e\": [[\"泰国市场\", \"市场\", 1]],"
                        + " \"s\": [[\"上周，码表一代在泰国市场开售。\", \"码表一代\", \"开售于\","
                        + " \"泰国市场\", null, null, \"上周\", null]], \"n\": [],"
                        + " \"t\": [[\"now\", \"提报日期\", \"2026年9月4日\","
                        + " {\"y\": 2026, \"m\": 9, \"d\": 4}, null],"
                        // 字不在这一块里的日期不算
                        + " [\"date\", \"数据截止\", \"2026年8月31日\","
                        + " {\"y\": 2026, \"m\": 8, \"d\": 31}, null]]}",
                // 解析：两条「上周」字和句子都不同，各是一条；粒度照字报 week
                "{ \"m\": [[0, \"point\", " + anchored + ", \"week\"],"
                        + " [1, \"point\", " + anchored + ", \"week\"]] }"));
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        try {
            extraction.extractDocument(doc, ExtractionJob.Proposer.none());
            // 文档上存下了两条起算点，各带它所在的标题路径；字不在块里的那条没进来
            String rawContext = jdbc.queryForObject(
                    "SELECT time_context::text FROM documents WHERE id = ?", String.class, doc);
            JsonNode context = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(rawContext);
            List<Object[]> entries = new java.util.ArrayList<>();
            for (JsonNode e : context.get("entries")) {
                List<String> scope = new java.util.ArrayList<>();
                e.get("scope").forEach(s -> scope.add(s.asText()));
                entries.add(new Object[]{e.get("kind").asText(), e.get("words").asText(), scope});
            }
            assertEquals(2, entries.size());
            assertEquals("now", entries.get(0)[0]);
            assertEquals("2026年8月28日", entries.get(0)[1]);
            assertEquals(List.of("周报汇编", "第35周周报"), entries.get(0)[2]);
            assertEquals("now", entries.get(1)[0]);
            assertEquals("2026年9月4日", entries.get(1)[1]);
            assertEquals(List.of("周报汇编", "第36周周报"), entries.get(1)[2]);
            // 块与块之间什么都没传：第二块的提示词里没有第一块的日期之外的东西可依赖——
            // 它自己的正文里本来就有自己的标题和日期
            String second = model.requests().get(1).path("messages").path(1).path("content")
                    .asText("");
            assertTrue(second.contains("第36周周报") && second.contains("2026年9月4日"));

            resolveTime.resolveDocument(doc);
            assertEquals(3, model.requests().size(),
                    "two extraction calls and one interpretation call: the opening is not asked"
                            + " for a date");
            List<Object[]> dated = jdbc.query(
                    "SELECT o.canonical_name, f.valid_from, f.valid_from_grade FROM facts f"
                            + " JOIN entities o ON o.id = f.object_id WHERE f.kb_id = ?"
                            + " AND f.layer = 'open' ORDER BY o.canonical_name",
                    (rs, i) -> new Object[]{rs.getString(1),
                            rs.getObject(2, OffsetDateTime.class), rs.getString(3)}, kb);
            // 每条陈述由它自己那一节的提报日期作证，连那条日期的名字；不是处理文档的那一刻
            List<Object[]> attested = jdbc.query(
                    "SELECT o.canonical_name, f.attested_from, f.attested_by FROM facts f"
                            + " JOIN entities o ON o.id = f.object_id WHERE f.kb_id = ?"
                            + " AND f.layer = 'open' ORDER BY o.canonical_name",
                    (rs, i) -> new Object[]{rs.getString(1),
                            rs.getObject(2, OffsetDateTime.class), rs.getString(3)}, kb);
            assertEquals(2, attested.size());
            assertEquals("泰国市场", attested.get(0)[0]);
            assertEquals(day(2026, 9, 4), attested.get(0)[1]);
            assertEquals("提报日期 2026年9月4日", attested.get(0)[2]);
            assertEquals("韩国市场", attested.get(1)[0]);
            assertEquals(day(2026, 8, 28), attested.get(1)[1]);
            assertEquals("提报日期 2026年8月28日", attested.get(1)[2]);
            assertEquals(2, dated.size());
            // 第 36 周的「上周」从 9 月 4 日起算
            assertEquals("泰国市场", dated.get(0)[0]);
            assertEquals(day(2026, 8, 28), dated.get(0)[1]);
            assertEquals("B", dated.get(0)[2]);
            // 第 35 周的「上周」从 8 月 28 日起算
            assertEquals("韩国市场", dated.get(1)[0]);
            assertEquals(day(2026, 8, 21), dated.get(1)[1]);
            assertEquals("B", dated.get(1)[2]);
        } finally {
            model.stop();
            jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                    doc.toString(), kb.toString());
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /** 文档没说自己是哪天的：抽取报了「没有」，就是没有——不再另问一次开头。 */
    @Test
    void aDocumentThatStatesNoDateIsNotAskedForOne() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'time-context-undated')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'time-context-undated')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'time-context-undated')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, '背景.md', 'x')",
                doc, kb);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, ?)",
                chunk, kb, doc, "# 项目背景\n\n去年，码表一代进入北美市场。");
        ScriptedModelServer model = ScriptedModelServer.start(List.of(
                "{\"e\": [[\"码表一代\", \"产品\", 1], [\"北美市场\", \"市场\", 1]],"
                        + " \"s\": [[\"去年，码表一代进入北美市场。\", \"码表一代\", \"进入\","
                        + " \"北美市场\", null, null, \"去年\", null]], \"n\": [], \"t\": []}",
                "{ \"m\": [[0, \"point\", {\"kind\": \"anchored\","
                        + " \"anchor\": {\"kind\": \"document\"},"
                        + " \"offset\": {\"count\": 1, \"unit\": \"year\", \"direction\": \"before\"}},"
                        + " \"year\"]] }"));
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        try {
            extraction.extractDocument(doc, ExtractionJob.Proposer.none());
            resolveTime.resolveDocument(doc);
            List<JsonNode> requests = model.requests();
            assertEquals(2, requests.size(),
                    "one extraction call and one interpretation call; the opening is not read for"
                            + " a date");
            for (JsonNode r : requests) {
                String system = r.path("messages").path(0).path("content").asText("");
                assertTrue(!system.startsWith("You read the opening of a document"),
                        "the dating prompt is not sent");
            }
            var row = jdbc.queryForMap(
                    "SELECT valid_from, valid_from_grade, attested_from FROM facts WHERE kb_id = ?"
                            + " AND layer = 'open'", kb);
            assertEquals(null, row.get("valid_from"));
            assertEquals("C", row.get("valid_from_grade"));
            assertEquals(null, row.get("attested_from"),
                    "no anchor: the time word waits, and the statement has no attestation");
        } finally {
            model.stop();
            jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                    doc.toString(), kb.toString());
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
