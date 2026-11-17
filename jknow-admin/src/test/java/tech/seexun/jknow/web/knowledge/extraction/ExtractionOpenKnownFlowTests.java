package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.MemoryService;
import tech.seexun.jknow.store.NameVectorsService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.PendingService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TimeMentionsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.ExtractionDropsService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;

/**
 * 移植自 crates/utopia-server/src/extraction_open_known_flow_tests.rs（2 例）——前面的
 * 分块认下的实体，后面的分块认得（#588 cut 1）。第一块列出 Acme，第二块的回复<b>不列</b>
 * 它、只在陈述里提到：两条陈述挂在同一个实体上，且第二块的提示词里有
 * {@code - Acme (organization)}；{@code extract_known_in_prompt} 关掉后提示词里没有那一行，
 * 落库照样认回去。
 *
 * <p>脚本化 SSE 模型端点（{@link ScriptedModelServer}，不打真模型）+ 真库（共享库口径，
 * 自建自拆）。共享栈的 Rust worker 会真消费排进队列的 resolve_time/align_types——收尾
 * 抢先删掉本测试的 job 行（它跑我们的文档无害，但别让它占着断言窗口）。
 */
class ExtractionOpenKnownFlowTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExtractionJob extraction;
    @Autowired
    SettingsService settings;
    @Autowired
    DocumentsService documents;
    @Autowired
    DocumentsWriteMapper writes;
    @Autowired
    KbMapper kbs;
    @Autowired
    MemoryService memory;
    @Autowired
    ResolutionService resolution;
    @Autowired
    NamesService names;
    @Autowired
    GraphWriteService graph;
    @Autowired
    PendingService pending;
    @Autowired
    TimeMentionsService timeMentions;
    @Autowired
    ExtractionDropsService drops;
    @Autowired
    NameVectorsService nameVectors;
    @Autowired
    TypeBindingsService typeBindings;
    @Autowired
    JobsService jobs;
    @Autowired
    LlmSupport llm;
    @Autowired
    AppEventBus events;

    private static final class Fx {
        final UUID org;
        final UUID kb;
        final UUID doc;
        final ScriptedModelServer model;
        final boolean knownInPrompt;

        Fx(UUID org, UUID kb, UUID doc, ScriptedModelServer model, boolean knownInPrompt) {
            this.org = org;
            this.kb = kb;
            this.doc = doc;
            this.model = model;
            this.knownInPrompt = knownInPrompt;
        }
    }

    private Fx seed(List<String> replies, boolean knownInPrompt) throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk0 = Uuids.nowV7();
        UUID chunk1 = Uuids.nowV7();
        // 插进去的只有本地生成的 UUID
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'open-known-flow')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'open-known-flow')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'open-known-flow')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, 'acme.txt', 'x')",
                doc, kb);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, 'Acme is based in London.')",
                chunk0, kb, doc);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 1, 'Acme runs the harbour facility.')",
                chunk1, kb, doc);
        ScriptedModelServer model = ScriptedModelServer.start(replies);
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        return new Fx(org, kb, doc, model, knownInPrompt);
    }

    private void cleanup(Fx f) {
        jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                f.doc.toString(), f.kb.toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
        // 删库后仍在飞的 job 行清尾（worker 认领走的那条不再有意义）
        jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                f.doc.toString(), f.kb.toString());
    }

    /** 两块的回复：第一块列出 Acme，第二块不列、只在陈述里提到它。 */
    private static List<String> replies() {
        return List.of(
                "{\"e\": [[\"Acme\", \"organization\", 1]],"
                        + " \"s\": [[\"Acme is based in London.\", \"Acme\", \"based in\", null,"
                        + " \"London\", null, null, null]],"
                        + " \"n\": []}",
                "{\"e\": [],"
                        + " \"s\": [[\"Acme runs the harbour facility.\", \"Acme\", \"runs\", null,"
                        + " \"harbour\", null, null, null]],"
                        + " \"n\": []}");
    }

    /** 跑完抽取：只有一个 Acme，两条陈述都挂在它上面。交回第二块的提示词。 */
    private String extractAndReadTheSecondPrompt(Fx f) throws Exception {
        ExtractionJob job = f.knownInPrompt ? extraction
                : new ExtractionJob(documents, writes, kbs, settings, memory, resolution, names,
                        graph, pending, timeMentions, drops, nameVectors, typeBindings, jobs,
                        llm, events, false);
        job.extractDocument(f.doc, ExtractionJob.Proposer.none());
        List<UUID> acme = jdbc.queryForList(
                "SELECT id FROM entities WHERE kb_id = ? AND canonical_name = 'Acme'",
                UUID.class, f.kb);
        assertEquals(1, acme.size(), () -> "one Acme across both chunks, got " + acme.size());
        List<UUID> subjects = jdbc.queryForList(
                "SELECT subject_id FROM facts WHERE kb_id = ? AND layer = 'open' ORDER BY recorded_at",
                UUID.class, f.kb);
        assertEquals(List.of(acme.get(0), acme.get(0)), subjects,
                "both statements hang on the one Acme: " + subjects);
        List<JsonNode> requests = f.model.requests();
        assertEquals(2, requests.size(), () -> "one request a chunk, got " + requests.size());
        return requests.get(1).path("messages").path(1).path("content").asText("");
    }

    @Test
    void aThingListedByAnEarlierChunkReachesALaterOneViaKnown() throws Exception {
        Fx f = seed(replies(), true);
        String prompt;
        try {
            prompt = extractAndReadTheSecondPrompt(f);
        } finally {
            f.model.stop();
            cleanup(f);
        }
        assertTrue(prompt.contains("- Acme (organization)"),
                "the second chunk is told what the first one listed: " + prompt);
    }

    /** 旋钮关掉：第二块的提示词里不再列前面认下的实体，落库仍然认回同一个 Acme。 */
    @Test
    void withKnownLeftOutOfThePromptALaterChunkStillLandsOnTheSameEntity() throws Exception {
        Fx f = seed(replies(), false);
        String prompt;
        try {
            prompt = extractAndReadTheSecondPrompt(f);
        } finally {
            f.model.stop();
            cleanup(f);
        }
        assertTrue(!prompt.contains("- Acme (organization)"),
                "nothing listed as known: " + prompt);
    }
}
