package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ImplicationRulesService;
import tech.seexun.jknow.store.ImplicationRulesService.Proposal;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper.PendingReading;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper.Rule;

/**
 * 移植自 crates/utopia-store/src/implication_rules_tests.rs（317 行，4 例）——蕴含规则
 * （0044 决定 3 第五片）：提案落库、人批与后续任务同事务、待读的字、缓存、物化算隐含行、
 * 驳回退掉。M4 任务 5 追加 read_phrases 真 LLM 路径段（脚本端点下整链复跑到证据链）。
 */
class ImplicationRulesTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ImplicationRulesService implicationRules;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    tech.seexun.jknow.store.SettingsService settings;
    @Autowired
    tech.seexun.jknow.web.knowledge.jobs.ReadPhrasesJob readPhrases;

    record Fx(UUID org, UUID kb, UUID film, UUID place, UUID countryOfOrigin, UUID locatedIn,
              UUID country, UUID loudTour, UUID piedmont, UUID statement) {
    }

    /** 一个库：类 film / place，属性 country_of_origin / located_in / country。 */
    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID film = Uuids.nowV7();
        UUID place = Uuids.nowV7();
        UUID coo = Uuids.nowV7();
        UUID li = Uuids.nowV7();
        UUID country = Uuids.nowV7();
        UUID loud = Uuids.nowV7();
        UUID piedmont = Uuids.nowV7();
        UUID stmt = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'implication-test')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'implication-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'implication-test')",
                kb, ws);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES
                    (?, ?, 'film', 'Film', '#000', 'circle'), (?, ?, 'place', 'Place', '#000', 'circle')
                """, film, kb, place, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal) VALUES
                    (?, ?, 'country_of_origin', 'country of origin', 'relation', 'state'),
                    (?, ?, 'located_in', 'located in', 'relation', 'state'),
                    (?, ?, 'country', 'country', 'relation', 'state')
                """, coo, kb, li, kb, country, kb);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id,specific_type) VALUES
                    (?, ?, 'Loud Tour', ?, 'British film'),
                    (?, ?, 'Piedmont', ?, 'region')
                """, loud, kb, film, piedmont, kb, place);
        jdbc.update("""
                INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase) VALUES
                    (?, ?, ?, ?, 'open', 'located in')
                """, stmt, kb, loud, piedmont);
        return new Fx(org, kb, film, place, coo, li, country, loud, piedmont, stmt);
    }

    private void cleanup(Fx f) {
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb().toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /**
     * 双打环境的确定性闸门：共享栈上活着的 Rust worker 会真处理 materialize_typed
     * （轮到它先物化，测试自己的 materialize 就只剩空转）。判定入队时拿会话级
     * typed_materialize 锁挡住它（Rust 试锁不得 → Deferred 10s 挂回），随后释放，
     * 测试自己的直调 materialize 在这 10 秒窗里跑完。
     */
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

    /** 推到一小时后：worker 的 claim 只认 run_at 到期；cleanup 收走。 */
    private void parkJob(Long jobId) {
        if (jobId == null) {
            return;
        }
        jdbc.update("UPDATE jobs SET run_at = now() + interval '1 hour' WHERE id = ?", jobId);
    }

    /**
     * 把共享栈 Rust worker 的处理彻底静默：等到任务回到 queued 且 run_at 已被推到
     * 一小时之后（Rust 的退避写回会把 park 覆盖成 +30s——看到就再 park 一次），
     * 之后 worker 的 claim（只认 run_at 到期）再也拿不到它，断言/直调独占。
     */
    private void quiesceJob(long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var row = jdbc.queryForMap(
                    "SELECT status, run_at > now() + interval '50 minutes' AS parked FROM jobs WHERE id = ?",
                    jobId);
            if ("queued".equals(row.get("status")) && Boolean.TRUE.equals(row.get("parked"))) {
                return;
            }
            if ("queued".equals(row.get("status"))) {
                parkJob(jobId);
            }
            Thread.sleep(25);
        }
        assertEquals("queued(parked)", jdbc.queryForObject(
                "SELECT status FROM jobs WHERE id = ?", String.class, jobId));
    }

    /** 等 Rust worker 把 Deferred/退避的任务挂回 queued（共享栈双打的等待语义）。 */
    private void awaitQueued(Long jobId) throws Exception {
        if (jobId == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
            if ("queued".equals(status)) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals("queued", status);
    }

    private Proposal phraseRule(Fx f, String reading, UUID property) {
        return new Proposal("phrase", "located in", f.film(), f.place(), false, property, reading,
                "proposed", JsonNodeFactory.instance.nullNode(), "b1", 1, List.of());
    }

    private List<Object[]> impliedRows(UUID kb) {
        return jdbc.query("""
                SELECT subject_id, predicate_id, object_id FROM facts
                  WHERE kb_id = ? AND layer = 'typed' AND implied AND invalidated_at IS NULL
                  ORDER BY recorded_at
                """, (rs, i) -> new Object[]{rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)}, kb);
    }

    @Test
    void aProposalLandsOnceAndAPersonDecidesItWithItsJob() throws Exception {
        Fx f = seed();
        try {
            Proposal p = phraseRule(f, "country_of_place", f.country());
            UUID id = implicationRules.propose(f.kb(), p);
            assertNotNull(id, "first proposal");
            // 同一条再提：代理的行被覆盖（返回 id），不是第二行
            assertEquals(id, implicationRules.propose(f.kb(), p));
            assertEquals(1, implicationRules.list(f.kb(), "proposed").size());
            // 人批：状态 approved，同事务排了 read_phrases（要读数）
            Long job;
            try (java.sql.Connection gate = workerGate(f.kb())) {
                job = implicationRules.decideWithDelivery(f.kb(), id, true,
                        JsonNodeFactory.instance.objectNode());
                assertNotNull(job);
                quiesceJob(job);
            }
            var kindStatus = jdbc.queryForMap("SELECT kind, status FROM jobs WHERE id = ?", job);
            assertEquals(ImplicationRulesService.READ_KIND, kindStatus.get("kind"));
            assertEquals("queued", kindStatus.get("status"));
            Rule r = implicationRules.get(f.kb(), id);
            assertEquals("approved", r.status());
            assertEquals("person", r.decidedBy());
            // 人判过的，代理再提也盖不掉
            assertNull(implicationRules.propose(f.kb(), p));
            // 不要读数的规则，批了直接排物化
            UUID plain = implicationRules.propose(f.kb(), phraseRule(f, null, f.countryOfOrigin()));
            assertNotNull(plain);
            Long job2;
            try (java.sql.Connection gate = workerGate(f.kb())) {
                job2 = implicationRules.decideWithDelivery(f.kb(), plain, true,
                        JsonNodeFactory.instance.objectNode());
                assertNotNull(job2);
                quiesceJob(job2);
            }
            String kind2 = jdbc.queryForObject("SELECT kind FROM jobs WHERE id = ?", String.class,
                    job2);
            assertEquals("materialize_typed", kind2);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void anApprovedRuleWaitsForItsReadingThenImpliesAFactWithEvidence() throws Exception {
        Fx f = seed();
        try {
            UUID id = implicationRules.propose(f.kb(),
                    phraseRule(f, "country_of_place", f.country()));
            assertNotNull(id);
            Long readingJob;
            try (java.sql.Connection gate = workerGate(f.kb())) {
                readingJob = implicationRules.decideWithDelivery(f.kb(), id, true,
                        JsonNodeFactory.instance.objectNode());
                quiesceJob(readingJob);
            }
            // 待读：陈述的宾语「piedmont」按 country_of_place
            List<PendingReading> pending = implicationRules.pendingReadings(f.kb());
            assertEquals(1, pending.size());
            assertEquals("country_of_place", pending.get(0).reading());
            assertEquals("piedmont", pending.get(0).phrase());
            // 缓存没填：物化不算隐含行
            Outcome o = materialize.materialize(f.kb());
            assertEquals(0L, o.implied());
            assertTrue(impliedRows(f.kb()).isEmpty());
            // 读数读出「United States」：库里没有就建，名字事实照记
            UUID us = implicationRules.resolveOrCreateNamed(f.kb(), "United States");
            assertEquals(us, implicationRules.resolveOrCreateNamed(f.kb(), "united states"),
                    "found by name the second time");
            implicationRules.recordReading(f.kb(), "country_of_place", "Piedmont", us, null);
            assertTrue(implicationRules.pendingReadings(f.kb()).isEmpty(), "cached now");
            o = materialize.materialize(f.kb());
            assertEquals(1L, o.implied());
            List<Object[]> rows = impliedRows(f.kb());
            assertEquals(1, rows.size());
            assertEquals(f.loudTour(), rows.get(0)[0]);
            assertEquals(f.country(), rows.get(0)[1]);
            assertEquals(us, rows.get(0)[2]);
            // 证据从触发它的陈述抄来；来源记着规则与陈述（按库过滤）
            var src = jdbc.queryForMap("""
                    SELECT i.rule_id, i.statement_id FROM implied_fact_sources i
                       JOIN facts t ON t.id = i.fact_id WHERE t.kb_id = ?
                    """, f.kb());
            assertEquals(id, src.get("rule_id"));
            assertEquals(f.statement(), src.get("statement_id"));
            // 再跑一遍什么都不动
            o = materialize.materialize(f.kb());
            assertEquals(0L, o.implied());
            assertEquals(0L, o.retired());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void rejectingTheRuleOrLosingTheStatementRetiresTheImpliedRow() throws Exception {
        Fx f = seed();
        try {
            // 不要读数的规则：located in 的陈述还蕴含 country_of_origin = 陈述的宾语（就当测试）
            UUID id = implicationRules.propose(f.kb(),
                    phraseRule(f, null, f.countryOfOrigin()));
            assertNotNull(id);
            try (java.sql.Connection gate = workerGate(f.kb())) {
                quiesceJob(implicationRules.decideWithDelivery(f.kb(), id, true,
                        JsonNodeFactory.instance.objectNode()));
            }
            assertEquals(1L, materialize.materialize(f.kb()).implied());
            assertEquals(1, impliedRows(f.kb()).size());
            // 驳回：来源删、行作废
            try (java.sql.Connection gate = workerGate(f.kb())) {
                quiesceJob(implicationRules.decideWithDelivery(f.kb(), id, false,
                        JsonNodeFactory.instance.objectNode()));
            }
            assertEquals(1L, materialize.materialize(f.kb()).retired());
            assertTrue(impliedRows(f.kb()).isEmpty());
            // 再批回来，行回来；陈述作废，行再退
            try (java.sql.Connection gate = workerGate(f.kb())) {
                Long j = implicationRules.decideWithDelivery(f.kb(), id, true,
                        JsonNodeFactory.instance.objectNode());
                quiesceJob(j);
            }
            assertEquals(1L, materialize.materialize(f.kb()).implied());
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", f.statement());
            assertEquals(1L, materialize.materialize(f.kb()).retired());
            assertTrue(impliedRows(f.kb()).isEmpty());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void aKindWordRuleImpliesAFactForEveryThingSoCalled() throws Exception {
        Fx f = seed();
        try {
            var votes = JsonNodeFactory.instance.objectNode();
            UUID id = implicationRules.propose(f.kb(), new Proposal("kind_word", "British  Film",
                    null, null, false, f.countryOfOrigin(), "country_of_nationality", "proposed",
                    votes, "k1", 1, List.of()));
            assertNotNull(id);
            try (java.sql.Connection gate = workerGate(f.kb())) {
                Long j = implicationRules.decideWithDelivery(f.kb(), id, true, votes);
                quiesceJob(j);
            }
            List<PendingReading> pending = implicationRules.pendingReadings(f.kb());
            assertEquals(1, pending.size());
            assertEquals("country_of_nationality", pending.get(0).reading());
            assertEquals("british film", pending.get(0).phrase());
            UUID uk = implicationRules.resolveOrCreateNamed(f.kb(), "United Kingdom");
            implicationRules.recordReading(f.kb(), "country_of_nationality", "british film", uk,
                    null);
            assertEquals(1L, materialize.materialize(f.kb()).implied());
            List<Object[]> rows = impliedRows(f.kb());
            assertEquals(1, rows.size());
            assertEquals(f.loudTour(), rows.get(0)[0]);
            assertEquals(f.countryOfOrigin(), rows.get(0)[1]);
            assertEquals(uk, rows.get(0)[2]);
            // 读不出来也缓存：不再待读，也不算行
            implicationRules.recordReading(f.kb(), "country_of_nationality", "iberian", null,
                    null);
            assertTrue(implicationRules.pendingReadings(f.kb()).isEmpty());
        } finally {
            cleanup(f);
        }
    }

    /**
     * M4 任务 5 补的 read_phrases 真 LLM 路径段：上面四例的读数是手工
     * resolveOrCreateNamed/recordReading 填的——这里在脚本化端点下把同一条链整段复跑：
     * 提案幂等（再提同 id）→ 人批（read_phrases 任务挂稳删走，等价 Rust 直调）→
     * {@code read_phrases} 问模型真身（名字答案建实体落缓存）→ 物化出带证据链
     * （implied_fact_sources 记着规则与陈述）的隐含行。
     */
    @Test
    void theChainRunsOverTheModelFromProposalToEvidence() throws Exception {
        Fx f = seed();
        tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer model =
                tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer.start(
                        List.of("{\"r\":[[0,\"United States\"]]}"));
        try {
            UUID id;
            try (java.sql.Connection gate = workerGate(f.kb())) {
                UUID ws = jdbc.queryForObject(
                        "SELECT workspace_id FROM knowledge_bases WHERE id = ?", UUID.class,
                        f.kb());
                settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
                // 提案幂等：同一条再提返回同一个 id（代理的行被覆盖，不是第二行）
                Proposal p = phraseRule(f, "country_of_place", f.country());
                id = implicationRules.propose(f.kb(), p);
                assertNotNull(id);
                assertEquals(id, implicationRules.propose(f.kb(), p));
                Long job = implicationRules.decideWithDelivery(f.kb(), id, true,
                        JsonNodeFactory.instance.objectNode());
                assertNotNull(job);
                quiesceJob(job);
                jdbc.update("DELETE FROM jobs WHERE id = ?", job);
                // 待读的字就是陈述的宾语「Piedmont」；read_phrases 真身把它问出去、建出实体
                List<PendingReading> pending = implicationRules.pendingReadings(f.kb());
                assertEquals(1, pending.size());
                assertEquals("piedmont", pending.get(0).phrase());
                readPhrases.readPhrases(f.kb());
                // read_phrases 收尾排的物化任务在锁还握着时收走（放了锁 Rust worker 认领
                // 已删的库只会写失败噪音；物化在锁外直调——materialize 等的正是这把锁）
                jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb().toString());
            }
            // 物化：一条隐含行，证据链从触发它的陈述抄来（规则与陈述都记着）
            Outcome o = materialize.materialize(f.kb());
            assertEquals(1L, o.implied());
            List<Object[]> rows = impliedRows(f.kb());
            assertEquals(1, rows.size());
            assertEquals(f.loudTour(), rows.get(0)[0]);
            assertEquals(f.country(), rows.get(0)[1]);
            var src = jdbc.queryForMap("""
                    SELECT i.rule_id, i.statement_id FROM implied_fact_sources i
                       JOIN facts t ON t.id = i.fact_id WHERE t.kb_id = ?
                    """, f.kb());
            assertEquals(id, src.get("rule_id"));
            assertEquals(f.statement(), src.get("statement_id"));
        } finally {
            model.stop();
            cleanup(f);
        }
    }
}
