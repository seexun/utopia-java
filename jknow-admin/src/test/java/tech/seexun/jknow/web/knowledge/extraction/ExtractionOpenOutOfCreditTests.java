package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.llm.AlertingSupport;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.Job;

/**
 * 移植自 crates/utopia-server/src/extraction_open_out_of_credit_tests.rs（2 例）——空余额
 * 停下整篇、已落块保留、断点续抽从下一块起（#1095）；以及 #1187：记忆日志里一句读不出
 * 时不挡住后面句子的确认卡与时间解析任务。
 *
 * <p>脚本化 SSE 模型端点（null 脚本项 = OpenAI 形态空余额 429）。Rust 侧的 Terminal
 * 标记以 {@link AlertingSupport.TerminalException} 对等；告警面（observe_job_failure →
 * alerts.insertAlert）为任务 8 补齐的最小写路径（挂账见 m2-report）。
 */
class ExtractionOpenOutOfCreditTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExtractionJob extraction;
    @Autowired
    SettingsService settings;
    @Autowired
    AlertingSupport alerting;
    @Autowired
    tech.seexun.jknow.store.MemoryService memoryService;

    private record Fx(UUID org, UUID ws, UUID kb, UUID doc, ScriptedModelServer model,
                     List<UUID> alertsBefore) {
    }

    @Test
    void anEmptyBalanceStopsTheDocumentAndKeepsWhatWasApplied() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'open-out-of-credit')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'open-out-of-credit')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'open-out-of-credit')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, 'acme.txt', 'x')",
                doc, kb);
        for (Object[] row : new Object[][]{
                {0, "Acme is based in London."},
                {1, "Acme runs the harbour facility."},
                {2, "Acme was founded in 1990."}}) {
            jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, ?, ?)",
                    Uuids.nowV7(), kb, doc, row[0], row[1]);
        }
        String first = "{\"e\": [[\"Acme\", \"organization\", 1]],"
                + " \"s\": [[\"Acme is based in London.\", \"Acme\", \"based in\", null,"
                + " \"London\", null, null, null]], \"n\": []}";
        // 第 1 块成功、第 2 块撞空余额；第 3 块根本不该被调用
        ScriptedModelServer model = ScriptedModelServer.start(Arrays.asList(first, null, null));
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        List<UUID> alertsBefore = jdbc.queryForList("SELECT id FROM alerts", UUID.class);
        try {
            Exception err = assertThrows(Exception.class,
                    () -> extraction.extractDocument(doc, ExtractionJob.Proposer.none()),
                    "extraction succeeded with no balance");
            // main.rs 对没救的错误做的事：hopeless → 不再重试 → 这一次就是最后一次，现在就报
            assertTrue(AlertingSupport.hopeless(err), () -> "not hopeless: " + err);
            alerting.observeJobFailure(new Job(0, "extract_document",
                    new com.fasterxml.jackson.databind.node.ObjectNode(
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance),
                    1, 3), new AlertingSupport.TerminalException(err));
            List<String> alerts = jdbc.query(
                    "DELETE FROM alerts WHERE NOT (id = ANY(?)) AND detail->>'job' = 'extract_document'"
                            + " RETURNING kind",
                    (rs, i) -> rs.getString(1),
                    (Object) alertsBefore.toArray(new UUID[0]));
            var row = jdbc.queryForMap(
                    "SELECT (SELECT count(*) FROM facts WHERE kb_id = ? AND layer = 'open') AS facts,"
                            + " (SELECT array_agg(extracted_at IS NOT NULL ORDER BY seq) FROM chunks"
                            + " WHERE document_id = ?) AS extracted, graph_status FROM documents"
                            + " WHERE id = ?",
                    kb, doc, doc);
            Boolean[] extracted = (Boolean[]) ((java.sql.Array) row.get("extracted")).getArray();
            assertEquals(1, model.remainingScript(), "the third chunk is not called");
            assertEquals(List.of(AlertingSupport.LLM_OUT_OF_CREDIT), alerts);
            assertEquals(1L, row.get("facts"), "chunk 0 stays in the graph");
            assertEquals(List.of(true, false, false), Arrays.asList(extracted),
                    "the next run resumes at chunk 1");
            assertEquals("failed", row.get("graph_status"));
        } finally {
            model.stop();
            jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                    doc.toString(), kb.toString());
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /**
     * #1187：记忆日志里有一句模型读不出。尝试仍报 incomplete，但它后面的那句拿到抽出的
     * 句子该有的一切：进待确认的陈述 + 时间解析任务。从前错误抢在这两者之前返回。
     */
    @Test
    void aMemoryThatCannotBeExtractedDoesNotHoldBackTheNext() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'memory-incomplete')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'memory-incomplete')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'memory-incomplete')",
                kb, ws);
        // 记忆日志一句一块（appendEpisode 每次一条）
        var first = memoryService.appendEpisode(kb, "Something no model can read.",
                java.time.OffsetDateTime.now());
        var second = memoryService.appendEpisode(kb, "Acme is based in London.",
                java.time.OffsetDateTime.now());
        UUID doc = first.documentId();
        String good = "{\"e\": [[\"Acme\", \"organization\", 1]],"
                + " \"s\": [[\"Acme is based in London.\", \"Acme\", \"based in\", null,"
                + " \"London\", null, null, null]], \"n\": []}";
        ScriptedModelServer model = ScriptedModelServer.start(List.of(
                "I cannot help with that.", good));
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        try {
            Exception err = assertThrows(Exception.class,
                    () -> extraction.extractDocument(doc, ExtractionJob.Proposer.none()),
                    "an attempt that left a sentence unread was reported as complete");
            String message = String.valueOf(err.getMessage());
            Long pending = jdbc.queryForObject(
                    "SELECT count(*) FROM pending_facts WHERE chunk_id = ?", Long.class,
                    second.chunkId());
            Long resolving = jdbc.queryForObject(
                    "SELECT count(*) FROM jobs WHERE kind = 'resolve_time'"
                            + " AND payload->>'document_id' = ?", Long.class, doc.toString());
            assertTrue(message.contains("1 of 2 chunks"), message);
            assertEquals(1L, pending, "the second sentence awaits a nod");
            assertEquals(1L, resolving, "and its time words are queued for reading");
        } finally {
            model.stop();
            jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                    doc.toString(), kb.toString());
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
