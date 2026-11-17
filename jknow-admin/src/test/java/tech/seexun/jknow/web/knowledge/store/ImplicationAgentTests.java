package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.extraction.AlignPhrasesJob;
import tech.seexun.jknow.web.knowledge.extraction.RuleProposer;
import tech.seexun.jknow.web.knowledge.extraction.RuleProposer.RuleAsk;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.jobs.ReadPhrasesJob;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.ImplicationRulesService;
import tech.seexun.jknow.store.ImplicationRulesService.Proposal;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.AlignmentItem;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper.PendingReading;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 提规则与读数走脚本化的模型端点（crates/utopia-server/src/implication_tests.rs 2 例
 * 逐字移植，断言不改写）：提案落到队列，读数落进缓存并解析成库里的实体，然后排物化
 * ——隐含行在那里算。另补 read_phrases 问模型段的三条路径单测（malformed 年份留待读不
 * 失败整批 / null 落缓存不再问 / 名字答案建出带名字事实的实体）与一例对齐收尾接线
 * （任务 8：AlignPhrasesJob 收尾把带修饰词的类别词攒给 RuleProposer）。
 *
 * <p>共享栈纪律（Rust 自建库没有的一面，ImplicationRulesTests 同款）：批准规则排下的
 * read_phrases 先 quiesceJob 挂稳再删（Rust 测试直接删——挂稳防止删除前的认领窗口被
 * Rust worker 拿去打我们的脚本端点）；read_phrases 收尾排的 materialize_typed 同样挂稳
 * ——它 run_at 即到，不挂会被 Rust worker 认领试锁（typed_materialize 会话锁在我们手里
 * → Deferred）抢走「物化算出隐含行」这一幕。
 */
class ImplicationAgentTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KbMapper kbs;
    @Autowired
    SettingsService settings;
    @Autowired
    OntologyService ontology;
    @Autowired
    TypeBindingsService typeBindings;
    @Autowired
    RuleProposer proposer;
    @Autowired
    AlignPhrasesJob alignPhrases;
    @Autowired
    ReadPhrasesJob readPhrases;
    @Autowired
    ImplicationRulesService implicationRules;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    ReviewService reviews;

    private static final class Fx {
        final UUID org;
        final UUID ws;
        final UUID kb;
        final UUID film;
        final UUID countryOfOrigin;
        final ScriptedModelServer model;

        Fx(UUID org, UUID ws, UUID kb, UUID film, UUID countryOfOrigin,
           ScriptedModelServer model) {
            this.org = org;
            this.ws = ws;
            this.kb = kb;
            this.film = film;
            this.countryOfOrigin = countryOfOrigin;
            this.model = model;
        }
    }

    /** Rust Fx::new：一个库，类 film，属性 country_of_origin，实体 Loud Tour（British film）。 */
    private Fx seed(List<String> replies) throws java.io.IOException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID film = Uuids.nowV7();
        UUID coo = Uuids.nowV7();
        UUID loud = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'implication-server')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'implication-server')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'implication-server')",
                kb, ws);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES
                    (?, ?, 'film', 'Film', '#000', 'circle')
                """, film, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,description) VALUES
                    (?, ?, 'country_of_origin', 'country of origin', 'relation', 'state', 'the country a work comes from')
                """, coo, kb);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id,specific_type) VALUES
                    (?, ?, 'Loud Tour', ?, 'British film')
                """, loud, kb, film);
        ScriptedModelServer model = ScriptedModelServer.start(replies);
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        return new Fx(org, ws, kb, film, coo, model);
    }

    private void cleanup(Fx f) {
        if (f.model != null) {
            f.model.stop();
        }
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    /** implication_tests.rs:118-173 a_kind_word_is_offered_and_the_model_proposes_a_rule。 */
    @Test
    void aKindWordIsOfferedAndTheModelProposesARule() throws Exception {
        Fx f = seed(List.of(
                "{\"i\":[[0,\"country_of_origin\",\"country_of_nationality\"]]}"));
        try {
            KbRow kb = kbs.selectById(f.kb);
            LlmSettingsRow s = settings.get(kb.workspaceId());
            ChatClient client = LlmSupport.chatClient(s);
            List<RelationTypeView> props = ontology.relationTypes(f.kb);
            List<KindWordSignature> words = typeBindings.signatures(f.kb);
            assertEquals(1, words.size());
            Map<UUID, String> classKey = new HashMap<>(Map.of(f.film, "film"));
            Map<String, RelationTypeView> byKey = new HashMap<>();
            for (RelationTypeView p : props) {
                byKey.put(p.key(), p);
            }
            List<RuleAsk> asks = List.of(new RuleAsk(null, words.get(0), null, props, "k1"));
            RuleProposer.Result r = proposer.proposeRules(f.kb, s, client, asks, classKey,
                    byKey);
            assertEquals(1, r.proposed());
            assertEquals(0, r.failed());
            String prompt = f.model.requests().get(0).at("/messages/1/content").asText("");
            assertTrue(prompt.contains("kind word \"british film\""), prompt);
            var rules = implicationRules.list(f.kb, "proposed");
            assertEquals(1, rules.size());
            assertEquals("kind_word", rules.get(0).trigger());
            assertEquals("country_of_nationality", rules.get(0).reading());
            assertEquals(f.countryOfOrigin, rules.get(0).concludePropertyId());
            // 队列里看得见
            List<?> items = reviews.items(f.kb, "alignment", null, 10, 0);
            assertTrue(items.stream().anyMatch(i -> i instanceof AlignmentItem.Rule),
                    String.valueOf(items));
        } finally {
            cleanup(f);
        }
    }

    /** implication_tests.rs:175-207 read_phrases_fills_the_cache_names_a_thing_and_queues_the_materialization。 */
    @Test
    void readPhrasesFillsTheCacheNamesAThingAndQueuesTheMaterialization() throws Exception {
        Fx f = seed(List.of("{\"r\":[[0,\"United Kingdom\"]]}"));
        try {
            try (java.sql.Connection gate = workerGate(f.kb)) {
                var votes = JsonNodeFactory.instance.objectNode();
                UUID id = implicationRules.propose(f.kb, new Proposal("kind_word", "british film",
                        null, null, false, f.countryOfOrigin, "country_of_nationality",
                        "proposed", votes, "k1", 1, List.of()));
                assertNotNull(id);
                Long job = implicationRules.decideWithDelivery(f.kb, id, true, votes);
                assertNotNull(job);
                quiesceJob(job);
                // Rust 测试删掉排下的任务再直调（挂稳后删——认领窗口不存在）
                jdbc.update("DELETE FROM jobs WHERE id = ?", job);
                readPhrases.readPhrases(f.kb);
                var row = jdbc.queryForMap("""
                        SELECT entity_id, value FROM phrase_readings
                          WHERE kb_id = ? AND reading = 'country_of_nationality' AND phrase = 'british film'
                        """, f.kb);
                assertNull(row.get("value"));
                String name = jdbc.queryForObject(
                        "SELECT canonical_name FROM entities WHERE id = ?", String.class,
                        (UUID) row.get("entity_id"));
                assertEquals("United Kingdom", name);
                // 收尾必排物化：挂稳它（Rust worker 认领会抢走这一幕），它仍是 queued 的唯一一条
                Long materializeJob = awaitQueuedJob(f.kb,
                        PhraseBindingsService.MATERIALIZE_KIND);
                assertNotNull(materializeJob);
                quiesceJob(materializeJob);
                List<String> kinds = jdbc.queryForList(
                        "SELECT kind FROM jobs WHERE payload->>'kb_id' = ? AND status = 'queued'",
                        String.class, f.kb.toString());
                assertEquals(List.of(PhraseBindingsService.MATERIALIZE_KIND), kinds);
                // 物化在锁外直调：TypedMaterializeService.materialize 等的正是 gate 手里
                // 那把 typed_materialize 锁，开着 gate 调就自锁死（ImplicationRulesTests
                // 同款——gate 只罩任务排布段）；挂走的任务没人认领，独家算
            }
            TypedMaterializeService.Outcome o = materialize.materialize(f.kb);
            assertEquals(1L, o.implied());
        } finally {
            cleanup(f);
        }
    }

    /**
     * 任务 5 补：年份答非四位（malformed）只算坏项——这一条留待读，任务不失败、收尾照排
     * 物化。
     */
    @Test
    void aMalformedYearStaysPendingWithoutFailingTheBatch() throws Exception {
        Fx f = seed(List.of("{\"r\":[[0,\"recently\"]]}"));
        try (java.sql.Connection gate = workerGate(f.kb)) {
            approveReadingRule(f, "the summer of", "year_of_phrase");
            List<PendingReading> pending = implicationRules.pendingReadings(f.kb);
            assertEquals(1, pending.size());
            readPhrases.readPhrases(f.kb);
            Long cached = jdbc.queryForObject(
                    "SELECT count(*) FROM phrase_readings WHERE kb_id = ?", Long.class, f.kb);
            assertEquals(0L, cached, "a non-year is not an answer");
            // 坏项不毁掉整批：任务成功收尾（没 bail），物化照排（挂稳防 Rust worker 抢跑）
            List<PendingReading> left = implicationRules.pendingReadings(f.kb);
            assertEquals(1, left.size());
            assertEquals("the summer of", left.get(0).phrase());
            Long materializeJob = awaitQueuedJob(f.kb, PhraseBindingsService.MATERIALIZE_KIND);
            assertNotNull(materializeJob, "the job finished and queued the materialization");
            quiesceJob(materializeJob);
        } finally {
            cleanup(f);
        }
    }

    /** 任务 5 补：null（读不出来）也落缓存——别再问。 */
    @Test
    void aNullAnswerIsCachedSoThePhraseIsNeverAskedAgain() throws Exception {
        Fx f = seed(List.of("{\"r\":[[0,null]]}"));
        try (java.sql.Connection gate = workerGate(f.kb)) {
            approveReadingRule(f, "iberian", "country_of_nationality");
            readPhrases.readPhrases(f.kb);
            var row = jdbc.queryForMap("""
                    SELECT entity_id, value FROM phrase_readings
                      WHERE kb_id = ? AND reading = 'country_of_nationality' AND phrase = 'iberian'
                    """, f.kb);
            assertNull(row.get("entity_id"));
            assertNull(row.get("value"));
            assertTrue(implicationRules.pendingReadings(f.kb).isEmpty(), "cached now");
            Long materializeJob = awaitQueuedJob(f.kb, PhraseBindingsService.MATERIALIZE_KIND);
            assertNotNull(materializeJob);
            quiesceJob(materializeJob);
        } finally {
            cleanup(f);
        }
    }

    /** 任务 5 补：名字答案库里没有就建——实体带名字事实（0041，召回的桥）。 */
    @Test
    void aNameAnswerCreatesAThingWithItsNameFact() throws Exception {
        Fx f = seed(List.of("{\"r\":[[0,\"France\"]]}"));
        try (java.sql.Connection gate = workerGate(f.kb)) {
            approveReadingRule(f, "french film", "country_of_nationality");
            readPhrases.readPhrases(f.kb);
            UUID created = jdbc.queryForObject("""
                    SELECT entity_id FROM phrase_readings
                      WHERE kb_id = ? AND reading = 'country_of_nationality' AND phrase = 'french film'
                    """, UUID.class, f.kb);
            assertNotNull(created);
            assertEquals("France", jdbc.queryForObject(
                    "SELECT canonical_name FROM entities WHERE id = ?", String.class, created));
            // 名字是内建属性 known_as 上的一条值事实（0041——召回的桥）
            Long named = jdbc.queryForObject("""
                    SELECT count(*) FROM facts f JOIN relation_types r ON r.id = f.predicate_id
                      WHERE f.kb_id = ? AND f.subject_id = ? AND r.key = 'known_as'
                        AND f.invalidated_at IS NULL
                    """, Long.class, f.kb, created);
            assertTrue(named != null && named >= 1, "the created thing carries its name");
            Long materializeJob = awaitQueuedJob(f.kb, PhraseBindingsService.MATERIALIZE_KIND);
            assertNotNull(materializeJob);
            quiesceJob(materializeJob);
        } finally {
            cleanup(f);
        }
    }

    /**
     * 任务 8 补（对齐收尾的接线面，Rust 无同款端到端——phrase_alignment.rs:1006-1130 的
     * 提规则段此前只在 proposeRules 直调层测过）：AlignPhrasesJob 收尾把<b>带修饰词的
     * 类别词</b>攒成 RuleAsk 问模型——无短语签名时这一问是收尾唯一的模型调用。答案落
     * 提案（proposed）且对齐队列看得见。任务 5 遗留 #1 的回归钉子。
     */
    @Test
    void theAlignmentTailAsksTheKindWordForImpliedRules() throws Exception {
        Fx f = seed(List.of(
                "{\"i\":[[0,\"country_of_origin\",\"country_of_nationality\"]]}"));
        try {
            com.fasterxml.jackson.databind.node.ObjectNode payload =
                    JsonNodeFactory.instance.objectNode();
            payload.put("kb_id", f.kb.toString());
            alignPhrases.handle(new tech.seexun.jknow.store.entity.Job(
                    0, "align_phrases", payload, 1, 3));
            // 空签名库：判定循环零调用，收尾的提规则是唯一一次问模型
            assertEquals(1, f.model.requests().size(), "只问一次提规则");
            String prompt = f.model.requests().get(0).at("/messages/1/content").asText("");
            assertTrue(prompt.contains("kind word \"british film\""), prompt);
            var rules = implicationRules.list(f.kb, "proposed");
            assertEquals(1, rules.size());
            assertEquals("kind_word", rules.get(0).trigger());
            assertEquals("country_of_nationality", rules.get(0).reading());
            assertEquals(f.countryOfOrigin, rules.get(0).concludePropertyId());
            // 提案在对齐队列看得见（proposed>0 → emit_review）
            List<?> items = reviews.items(f.kb, "alignment", null, 10, 0);
            assertTrue(items.stream().anyMatch(i -> i instanceof AlignmentItem.Rule),
                    String.valueOf(items));
            // 直调没有排下任何任务（todo 空、物化直调——共享栈零残留）
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM jobs WHERE payload->>'kb_id' = ?", Long.class,
                    f.kb.toString()));
        } finally {
            cleanup(f);
        }
    }

    // ---- 共享栈纪律（ImplicationRulesTests 同款搬运） ----

    /** 批一条 kind_word 读数规则：read_phrases 任务挂稳后删（直调 readPhrases，等价 Rust 删任务直调）。 */
    private void approveReadingRule(Fx f, String phrase, String reading) throws Exception {
        var votes = JsonNodeFactory.instance.objectNode();
        UUID id = implicationRules.propose(f.kb, new Proposal("kind_word", phrase, null, null,
                false, f.countryOfOrigin, reading, "proposed", votes, "k" + reading, 1,
                List.of()));
        assertNotNull(id);
        Long job = implicationRules.decideWithDelivery(f.kb, id, true, votes);
        assertNotNull(job);
        quiesceJob(job);
        jdbc.update("DELETE FROM jobs WHERE id = ?", job);
    }

    /**
     * 等到该库该 kind 的任务回到 queued（共享栈 Rust worker 可能正拿着它试锁——
     * typed_materialize 在我们手里 → Deferred 挂回 queued +10s；等它回来再挂走）。
     */
    private Long awaitQueuedJob(UUID kb, String kind) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM jobs WHERE payload->>'kb_id' = ? AND kind = ? AND status = 'queued'",
                    Long.class, kb.toString(), kind);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            Thread.sleep(25);
        }
        return null;
    }

    /** typed_materialize 会话锁：挡住共享栈 Rust worker 抢跑物化（试锁不得 → Deferred 10s）。 */
    private java.sql.Connection workerGate(UUID kb) throws Exception {
        java.sql.Connection gate = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        gate.setAutoCommit(false);
        try (var ps = gate.prepareStatement(
                "SELECT pg_advisory_lock(hashtext('typed_materialize'), hashtext(?))")) {
            ps.setString(1, kb.toString());
            ps.executeQuery();
        }
        return gate;
    }

    /** 挂到一小时后：worker 的 claim 只认 run_at 到期；看到 queued 且未挂够就再挂。 */
    private void quiesceJob(Long jobId) throws Exception {
        if (jobId == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var row = jdbc.queryForMap(
                    "SELECT status, run_at > now() + interval '50 minutes' AS parked FROM jobs WHERE id = ?",
                    jobId);
            if ("queued".equals(row.get("status")) && Boolean.TRUE.equals(row.get("parked"))) {
                return;
            }
            if ("queued".equals(row.get("status"))) {
                jdbc.update("UPDATE jobs SET run_at = now() + interval '1 hour' WHERE id = ?",
                        jobId);
            }
            Thread.sleep(25);
        }
        assertEquals("queued(parked)", jdbc.queryForObject(
                "SELECT status FROM jobs WHERE id = ?", String.class, jobId));
    }
}
