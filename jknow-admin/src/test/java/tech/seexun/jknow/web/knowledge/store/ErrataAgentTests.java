package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.jobs.ErrataReviewJob;
import tech.seexun.jknow.store.ErrataService;
import tech.seexun.jknow.store.SettingsService;

/**
 * 勘误 agent 走脚本化的模型端点（crates/utopia-server/src/errata_tests.rs 3 例逐字移植，
 * 断言不改写）：结构报了的先送去看，撤落地、keep 记账、引文不是原话的加被拒；账上记着
 * 请求数与端点报的 token；看完的文档不再问。直调 {@link ErrataReviewJob#review}（不经
 * 队列——共享栈 Rust worker 会消费同名 kind，AdjudicationAgentTests 同口径）。
 *
 * <p>fixture 与 Rust Fx::new 同一套：organization/place/person；based_in、ceo；文档
 * 「Acme is based in London. Jane Roe runs Acme.」；两条类型化行：Acme —based_in→
 * London（对）、Acme —based_in→ Paris（Paris 不在文档里，旗 name_absent）。usage 帧配
 * 123/45（Rust 桩的数字）——errata_runs 的 token 账按它断言。
 */
class ErrataAgentTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ErrataService errata;
    @Autowired
    ErrataReviewJob review;
    @Autowired
    SettingsService settings;

    private static final class Fx {
        final UUID org;
        final UUID kb;
        final UUID doc;
        final UUID fine;
        final UUID absent;
        final ScriptedModelServer model;

        Fx(UUID org, UUID kb, UUID doc, UUID fine, UUID absent, ScriptedModelServer model) {
            this.org = org;
            this.kb = kb;
            this.doc = doc;
            this.fine = fine;
            this.absent = absent;
            this.model = model;
        }
    }

    /** Rust Fx::new：一个库、一份文档、两条类型化行（Paris 行旗 name_absent 在前）。 */
    private Fx seed(List<String> replies) throws java.io.IOException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID place = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID basedIn = Uuids.nowV7();
        UUID ceo = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID london = Uuids.nowV7();
        UUID paris = Uuids.nowV7();
        UUID jane = Uuids.nowV7();
        UUID s1 = Uuids.nowV7();
        UUID t1 = Uuids.nowV7();
        UUID s2 = Uuids.nowV7();
        UUID t2 = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'errata-server')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'errata-server')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'errata-server')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, 'acme.txt', 'x')",
                doc, kb);
        jdbc.update("""
                INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES
                    (?, ?, ?, 0, 'Acme is based in London. Jane Roe runs Acme.')
                """, chunk, kb, doc);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES
                    (?, ?, 'organization', 'Organization', '#000', 'circle'),
                    (?, ?, 'place', 'Place', '#000', 'circle'),
                    (?, ?, 'person', 'Person', '#000', 'circle')
                """, organization, kb, place, kb, person, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,functional,description) VALUES
                    (?, ?, 'based_in', 'based in', 'relation', 'state', false, 'where an organization is based'),
                    (?, ?, 'ceo', 'chief executive', 'relation', 'state', true, 'who runs it')
                """, basedIn, kb, ceo, kb);
        jdbc.update("""
                INSERT INTO relation_type_domains(relation_type_id,entity_type_id) VALUES
                    (?, ?), (?, ?)
                """, basedIn, organization, ceo, organization);
        jdbc.update("""
                INSERT INTO relation_type_ranges(relation_type_id,entity_type_id) VALUES
                    (?, ?), (?, ?)
                """, basedIn, place, ceo, person);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id) VALUES
                    (?, ?, 'Acme', ?), (?, ?, 'London', ?),
                    (?, ?, 'Paris', ?), (?, ?, 'Jane Roe', ?)
                """, acme, kb, organization, london, kb, place, paris, kb, place, jane, kb,
                person);
        jdbc.update("""
                INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase) VALUES
                    (?, ?, ?, ?, 'open', 'based in'),
                    (?, ?, ?, ?, 'open', 'based in')
                """, s1, kb, acme, london, s2, kb, acme, paris);
        jdbc.update("""
                INSERT INTO facts(id,kb_id,subject_id,predicate_id,object_id,layer,from_statement_id) VALUES
                    (?, ?, ?, ?, ?, 'typed', ?),
                    (?, ?, ?, ?, ?, 'typed', ?)
                """, t1, kb, acme, basedIn, london, s1, t2, kb, acme, basedIn, paris, s2);
        jdbc.update("""
                INSERT INTO typed_fact_sources(fact_id,statement_id) VALUES (?, ?), (?, ?)
                """, t1, s1, t2, s2);
        jdbc.update("""
                INSERT INTO fact_evidence(fact_id,chunk_id,quote,document_id,doc_version) VALUES
                    (?, ?, 'based in London', ?, 1), (?, ?, 'based in London', ?, 1),
                    (?, ?, 'based in', ?, 1), (?, ?, 'based in', ?, 1)
                """, s1, chunk, doc, t1, chunk, doc, s2, chunk, doc, t2, chunk, doc);
        ScriptedModelServer model = ScriptedModelServer.start(replies).usage(123, 45);
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        return new Fx(org, kb, doc, t1, t2, model);
    }

    private void cleanup(Fx f) {
        if (f.model != null) {
            f.model.stop();
        }
        // 组织级联：jobs（按 payload）/kbs/documents/facts/errata_* 全随 org 走
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    /** Rust Fx::prompt_of：第 n 次请求的 user 消息（messages[1].content）。 */
    private static String promptOf(ScriptedModelServer model, int n) {
        return model.requests().get(n).at("/messages/1/content").asText("");
    }

    /** Rust Fx::live：这行还在不在（invalidated_at IS NULL）。 */
    private boolean live(UUID fact) {
        Boolean alive = jdbc.queryForObject(
                "SELECT invalidated_at IS NULL FROM facts WHERE id = ?", Boolean.class, fact);
        return Boolean.TRUE.equals(alive);
    }

    /** errata_tests.rs:167-241 the_agent_reviews_flagged_facts_first_and_each_verdict_is_a_recorded_action。 */
    @Test
    void theAgentReviewsFlaggedFactsFirstAndEachVerdictIsARecordedAction() throws Exception {
        // 0 是报了的 Paris 行（在前），1 是对的 London 行；加一条 ceo（原话在），再加一条原话不在的
        String script = "{\"a\":["
                + "[0,\"retract\",\"Paris is not in the document\",\"Acme is based in London\"],"
                + "[1,\"keep\"],"
                + "[null,\"add\",{\"subject\":\"Acme\",\"property\":\"ceo\",\"object\":\"Jane Roe\"},\"stated\",\"Jane Roe runs Acme\"],"
                + "[null,\"add\",{\"subject\":\"Acme\",\"property\":\"ceo\",\"object\":\"Jane Roe\"},\"made up\",\"Jane Roe owns Acme\"]"
                + "]}";
        // 第二票：Paris 那条结构报了，agent 要撤，再问一遍；答 not_stated 才撤
        String second = "{\"c\":[[0,\"not_stated\"]]}";
        Fx f = seed(List.of(script, second));
        try {
            review.review(f.kb);
            String prompt = promptOf(f.model, 0);
            String confirm = promptOf(f.model, 1);
            assertTrue(confirm.contains("0: Acme —based_in→ Paris")
                    && !confirm.contains("London (place)"), confirm);
            assertTrue(prompt.contains("DOCUMENT:\nAcme is based in London. Jane Roe runs Acme."),
                    prompt);
            assertTrue(prompt.contains(
                    "0: Acme (organization) —based_in→ Paris (place) FLAG name_absent"), prompt);
            assertTrue(prompt.contains("1: Acme (organization) —based_in→ London (place)"),
                    prompt);
            assertTrue(prompt.contains(
                    "- ceo (chief executive): who runs it [thing] subject: organization object: person"),
                    prompt);
            assertFalse(live(f.absent), "the retraction landed");
            assertTrue(live(f.fine));
            List<Object[]> actions = jdbc.query("""
                    SELECT action, status, detail FROM errata_actions WHERE kb_id = ?
                      ORDER BY created_at, id
                    """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2),
                    rs.getString(3)}, f.kb);
            assertEquals(4, actions.size());
            assertRow(actions.get(0), "retract", "applied", null);
            assertRow(actions.get(1), "keep", "applied", null);
            assertRow(actions.get(2), "add", "applied", null);
            assertRow(actions.get(3), "add", "refused",
                    "the quote is not in the document");
            Long ceo = jdbc.queryForObject("""
                    SELECT count(*) FROM facts f JOIN relation_types r ON r.id = f.predicate_id
                      WHERE f.kb_id = ? AND r.key = 'ceo' AND f.invalidated_at IS NULL
                    """, Long.class, f.kb);
            assertEquals(1L, ceo, "one Jane Roe row: the refused add wrote nothing");
            // 账：一次请求，端点报的用量
            var runRow = jdbc.queryForMap("""
                    SELECT flagged, sampled, requests, prompt_tokens, completion_tokens,
                           finished_at IS NOT NULL AS finished
                      FROM errata_runs WHERE kb_id = ?
                    """, f.kb);
            assertEquals(1, ((Number) runRow.get("flagged")).intValue());
            assertEquals(1, ((Number) runRow.get("sampled")).intValue());
            assertEquals(2, ((Number) runRow.get("requests")).intValue());
            assertEquals(246, ((Number) runRow.get("prompt_tokens")).intValue());
            assertEquals(90, ((Number) runRow.get("completion_tokens")).intValue());
            assertTrue(Boolean.TRUE.equals(runRow.get("finished")),
                    "two requests: the verdicts and the second vote");
            // 看完了：再跑不问模型（脚本空了，问了就失败），也不排下一次
            review.review(f.kb);
            assertEquals(2, f.model.requests().size());
            Long queued = jdbc.queryForObject(
                    "SELECT count(*) FROM jobs WHERE kind = ? AND payload->>'kb_id' = ?",
                    Long.class, ErrataService.JOB_KIND, f.kb.toString());
            assertEquals(0L, queued);
        } finally {
            cleanup(f);
        }
    }

    private static void assertRow(Object[] row, String action, String status, String detail) {
        assertEquals(action, row[0]);
        assertEquals(status, row[1]);
        assertEquals(detail, row[2]);
    }

    /** errata_tests.rs:243-277 an_unreadable_reply_leaves_the_facts_unreviewed_and_the_job_retries。 */
    @Test
    void anUnreadableReplyLeavesTheFactsUnreviewedAndTheJobRetries() throws Exception {
        Fx f = seed(List.of("not the protocol"));
        try {
            IllegalStateException err = assertThrows(IllegalStateException.class,
                    () -> review.review(f.kb));
            assertTrue(err.getMessage().contains("failed errata review"), err.getMessage());
            Long actions = jdbc.queryForObject(
                    "SELECT count(*) FROM errata_actions WHERE kb_id = ?", Long.class, f.kb);
            assertEquals(0L, actions);
            assertTrue(live(f.absent) && live(f.fine));
            // 账还是记的：问了一次，没看成
            var runRow = jdbc.queryForMap(
                    "SELECT requests, finished_at IS NOT NULL AS finished FROM errata_runs WHERE kb_id = ?",
                    f.kb);
            assertEquals(1, ((Number) runRow.get("requests")).intValue());
            assertTrue(Boolean.TRUE.equals(runRow.get("finished")));
            assertEquals(List.of(f.doc), errata.documentsDue(f.kb, 10));
        } finally {
            cleanup(f);
        }
    }

    /** errata_tests.rs:279-307 a_retraction_the_second_vote_calls_stated_becomes_a_keep。 */
    @Test
    void aRetractionTheSecondVoteCallsStatedBecomesAKeep() throws Exception {
        String script = "{\"a\":["
                + "[0,\"retract\",\"Paris is not in the document\",\"Acme is based in London\"],"
                + "[1,\"keep\"]]}";
        String second = "{\"c\":[[0,\"stated\"]]}";
        Fx f = seed(List.of(script, second));
        try {
            review.review(f.kb);
            assertTrue(live(f.absent), "one vote does not retract");
            List<Object[]> actions = jdbc.query("""
                    SELECT action, status, reason FROM errata_actions WHERE kb_id = ?
                      ORDER BY created_at, id
                    """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2),
                    rs.getString(3)}, f.kb);
            assertEquals("keep", actions.get(0)[0]);
            assertEquals("applied", actions.get(0)[1]);
            assertTrue(((String) actions.get(0)[2]).startsWith("second vote: stated"),
                    String.valueOf(actions.get(0)[2]));
            assertEquals(2, actions.size());
        } finally {
            cleanup(f);
        }
    }
}
