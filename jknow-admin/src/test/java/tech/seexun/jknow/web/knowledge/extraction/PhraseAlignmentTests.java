package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.AlignmentItem;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 移植自 crates/utopia-server/src/phrase_alignment_tests.rs（19 例）——一条短语判定的
 * 生命周期（0053，#807，#966/#975）：候选经继承命中、父边增删让判定过期、无候选与超限
 * 各自落库、端点类换了旧行不再循环、请求途中的编辑留下可见的过期、人的判定不被覆盖、
 * marks 补问。脚本化 HTTP 端点（chat + embeddings）+ 真库。断言逐条不改写。
 *
 * <p>与 Rust 侧的差别只在「等」的实现：axum 桩的 Notify 换成 ScriptedModelServer 的
 * holdFirstRequest/releaseFirstRequest（第一个请求进门后挂起，等测试放行）。
 */
class PhraseAlignmentTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlignPhrasesJob alignPhrases;
    @Autowired
    SettingsService settings;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    ReviewService reviews;
    // 第 19 例（M3 任务 10 去封）依赖的两块真身：本体编辑器写入口（reindexThenAlign 钩子）
    // 与 embed_ontology job 本体
    @Autowired
    tech.seexun.jknow.web.knowledge.api.OntologyController ontologyApi;
    @Autowired
    tech.seexun.jknow.web.knowledge.jobs.EmbedOntologyJob embedOntology;

    private static final class Fx {
        final UUID org;
        final UUID ws;
        final UUID kb;
        final UUID legalEntity;
        final UUID organization;
        final UUID place;
        final UUID acme;
        final UUID basedIn;
        final ScriptedModelServer model;

        Fx(UUID org, UUID ws, UUID kb, UUID legalEntity, UUID organization, UUID place,
           UUID acme, UUID basedIn, ScriptedModelServer model) {
            this.org = org;
            this.ws = ws;
            this.kb = kb;
            this.legalEntity = legalEntity;
            this.organization = organization;
            this.place = place;
            this.acme = acme;
            this.basedIn = basedIn;
            this.model = model;
        }
    }

    /** 一个库：legal_entity ⊃ organization，place；based_in 声明在 legal_entity → place。 */
    private Fx seed() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID legalEntity = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID place = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID london = Uuids.nowV7();
        UUID basedIn = Uuids.nowV7();
        UUID statement = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'phrase-lifecycle')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'phrase-lifecycle')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'phrase-lifecycle')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES"
                        + " (?, ?, 'legal_entity', 'Legal entity', '#000', 'circle'),"
                        + " (?, ?, 'organization', 'Organization', '#000', 'circle'),"
                        + " (?, ?, 'place', 'Place', '#000', 'circle')",
                legalEntity, kb, organization, kb, place, kb);
        jdbc.update("INSERT INTO entity_type_parents(child_id,parent_id,is_primary)"
                + " VALUES (?, ?, true)", organization, legalEntity);
        jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,description)"
                + " VALUES (?, ?, 'based_in', 'based in', 'relation', 'state',"
                + " 'where an entity is based')", basedIn, kb);
        jdbc.update("INSERT INTO relation_type_domains(relation_type_id,entity_type_id)"
                + " VALUES (?, ?)", basedIn, legalEntity);
        jdbc.update("INSERT INTO relation_type_ranges(relation_type_id,entity_type_id)"
                + " VALUES (?, ?)", basedIn, place);
        jdbc.update("INSERT INTO entities(id,kb_id,canonical_name,type_id) VALUES"
                + " (?, ?, 'Acme', ?), (?, ?, 'London', ?)", acme, kb, organization, london, kb,
                place);
        jdbc.update("INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase)"
                + " VALUES (?, ?, ?, ?, 'open', 'based in')", statement, kb, acme, london);
        ScriptedModelServer model = ScriptedModelServer.start(new ArrayList<>());
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        return new Fx(org, ws, kb, legalEntity, organization, place, acme, basedIn, model);
    }

    private void script(Fx f, List<String> replies) {
        try {
            java.lang.reflect.Field field = ScriptedModelServer.class.getDeclaredField("script");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<String> script = (java.util.List<String>) field.get(f.model);
            synchronized (script) {
                script.clear();
                script.addAll(replies);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 给工作区配上嵌入模型（同一个脚本化端点）。 */
    private void enableEmbeddings(Fx f) {
        jdbc.update("UPDATE llm_settings SET embed_base_url = chat_base_url,"
                + " embed_model = 'scripted-embed' WHERE workspace_id = ?", f.ws);
    }

    private List<String> queued(Fx f) {
        return jdbc.queryForList("SELECT kind FROM jobs WHERE status = 'queued'"
                + " AND payload->>'kb_id' = ? ORDER BY run_at, id", String.class, f.kb.toString());
    }

    private Binding binding(Fx f) {
        List<Binding> all = phraseBindings.bindings(f.kb);
        assertFalse(all.isEmpty(), "no binding");
        return all.get(0);
    }

    private String reason(Fx f) {
        return jdbc.queryForObject(
                "SELECT votes->>'reason' FROM phrase_bindings WHERE kb_id = ?"
                        + " ORDER BY decided_at DESC LIMIT 1", String.class, f.kb);
    }

    private long typed(Fx f) {
        return materialize.count(f.kb);
    }

    /**
     * 这一轮结束后有没有再排一次对齐（收敛判据）。共享栈上活着的 Rust worker 会真认领
     * 立即到期的 align_phrases（status 离开 queued）——数 job 行的存在，不数状态
     * （Rust 侧私库无 worker，原断言只数 queued；语义等价点记 javadoc）。
     */
    private boolean requeued(Fx f) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE kind = 'align_phrases'"
                + " AND payload->>'kb_id' = ?", Long.class, f.kb.toString());
        return n > 0;
    }

    private void clearJobs(Fx f) {
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
    }

    private void cleanup(Fx f) {
        f.model.stop();
        clearJobs(f);
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    private void run(Fx f) throws Exception {
        alignPhrases.alignPhrasesLocked(f.kb, 0);
    }

    private String promptOf(Fx f, int n) {
        return f.model.requests().get(n).path("messages").path(1).path("content").asText("");
    }

    // ---- 脚本帮手（与 Rust 同名函数）----

    private static String vote(String key, String dir) {
        return marked(key, dir, key != null ? "none" : null);
    }

    private static String marked(String key, String dir, String marks) {
        return "{\"b\":[[0, " + jsonStr(key) + ", " + jsonStr(dir) + ", " + jsonStr(marks)
                + "]]}";
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : ("\"" + s + "\"");
    }

    private static String nothingImplied() {
        return "{\"i\":[[0,null,null]]}";
    }

    private static List<String> bound() {
        return List.of(vote("based_in", "forward"), vote("based_in", "forward"),
                nothingImplied());
    }

    private static List<String> none() {
        return List.of(vote(null, null));
    }

    // ---- 19 例 ----

    /** 类别词对齐还排着时短语对齐不动手：它给自己排一份半分钟后的。 */
    @Test
    void phraseAlignmentWaitsWhileAKindWordRoundIsPending() throws Exception {
        Fx f = seed();
        try {
            script(f, bound());
            jdbc.update("INSERT INTO jobs (kind, payload) VALUES ('align_types', ?::jsonb)",
                    "{\"kb_id\": \"" + f.kb + "\"}");
            alignPhrases.handle(job(f.kb, 0));
            assertEquals(0, f.model.requests().size(),
                    "nothing is asked while kind words are still being decided");
            var deferred = jdbc.queryForMap("SELECT status, run_at > now() AS later FROM jobs"
                    + " WHERE kind = 'align_phrases' AND payload->>'kb_id' = ?", f.kb.toString());
            assertEquals("queued", deferred.get("status"));
            assertEquals(Boolean.TRUE, deferred.get("later"),
                    "one phrase round is queued for later");
            alignPhrases.handle(job(f.kb, 0));
            assertEquals(0, f.model.requests().size(),
                    "still waiting: the kind-word job is still queued");
            clearJobs(f);
            run(f);
            assertEquals(2, f.model.requests().size(),
                    "the kind-word round gone, the two votes are asked");
        } finally {
            cleanup(f);
        }
    }

    private static tech.seexun.jknow.store.entity.Job job(UUID kb, int reask) {
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("kb_id", kb.toString());
        if (reask != 0) {
            payload.put("reask", reask);
        }
        return new tech.seexun.jknow.store.entity.Job(0, "align_phrases", payload, 1, 3);
    }

    @Test
    void aPropertyDeclaredOnAnAncestorIsOfferedWithItsBasisAndBound() throws Exception {
        Fx f = seed();
        try {
            script(f, bound());
            run(f);
            assertEquals(2, f.model.requests().size(),
                    "two votes; bound to the only property, so no rule question");
            String prompt = promptOf(f, 0);
            assertTrue(prompt.contains("based_in"), prompt);
            assertTrue(prompt.contains(
                    "fits by inheritance: organization is a subclass of legal_entity"),
                    "the model is told why the candidate fits: " + prompt);
            Binding b = binding(f);
            assertEquals("bound", b.status());
            assertEquals("agent", b.decidedBy());
            assertTrue(b.basis() != null, "an agent decision records its basis");
            assertEquals(1L, typed(f), "the projection follows");
            assertFalse(requeued(f), "unchanged inputs leave no queued work");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void removingTheParentEdgeRetiresTheProjectionWithoutAModelCall() throws Exception {
        Fx f = seed();
        try {
            script(f, bound());
            run(f);
            assertEquals(1L, typed(f));
            jdbc.update("DELETE FROM entity_type_parents WHERE child_id = ?", f.organization);
            clearJobs(f);
            script(f, List.of());
            f.model.requests().clear();
            run(f);
            assertEquals(2, f.model.requests().size(), "no candidate, nothing to ask");
            assertEquals("none", binding(f).status());
            assertEquals("no_candidates", reason(f));
            assertEquals(0L, typed(f), "the unsupported projection is retired");
            assertFalse(requeued(f));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void addingAParentEdgeReopensAStructuralNone() throws Exception {
        Fx f = seed();
        try {
            jdbc.update("DELETE FROM entity_type_parents WHERE child_id = ?", f.organization);
            run(f);
            assertEquals(0, f.model.requests().size());
            assertEquals("none", binding(f).status());
            assertFalse(requeued(f));
            jdbc.update("INSERT INTO entity_type_parents(child_id,parent_id,is_primary)"
                    + " VALUES (?, ?, true)", f.organization, f.legalEntity);
            script(f, bound());
            run(f);
            assertEquals(2, f.model.requests().size(),
                    "the edge changed the basis, so it is asked again");
            assertEquals("bound", binding(f).status());
            assertEquals(1L, typed(f));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void overflowIsRecordedForAPersonAndRecoversWhenCandidatesShrink() throws Exception {
        Fx f = seed();
        try {
            // 61 条不声明域/值域的关系：哪一端都接受，加上 based_in 共 62 > 60
            for (int i = 0; i < 61; i++) {
                jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind,temporal)"
                        + " VALUES (?, ?, ?, ?, 'relation', 'state')", Uuids.nowV7(), f.kb,
                        "filler_" + i, "filler_" + i);
            }
            run(f);
            assertEquals(0, f.model.requests().size(), "too many to ask");
            assertEquals("undecided", binding(f).status());
            assertEquals("too_many_candidates", reason(f));
            assertFalse(requeued(f), "overflow must not queue a run it cannot execute");
            jdbc.update("DELETE FROM relation_types WHERE kb_id = ? AND key LIKE 'filler_%'",
                    f.kb);
            script(f, bound());
            run(f);
            assertEquals(2, f.model.requests().size());
            assertEquals("bound", binding(f).status());
        } finally {
            cleanup(f);
        }
    }

    /** 属性还都没有向量时，短名单不嵌签名（#1097）；属性有了向量，同一条签名照常嵌。 */
    @Test
    void signaturesAreNotEmbeddedWhileNoPropertyHasAVector() throws Exception {
        Fx f = seed();
        try {
            enableEmbeddings(f);
            // 12 条不声明域/值域的关系，加上 based_in 共 13 > SHORTLIST
            for (int i = 0; i < 12; i++) {
                jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind,temporal)"
                        + " VALUES (?, ?, ?, ?, 'relation', 'state')", Uuids.nowV7(), f.kb,
                        "filler_" + i, "filler_" + i);
            }
            script(f, bound());
            run(f);
            assertEquals(0, f.model.embedCalls(),
                    "no property has a vector: nothing to embed for");
            assertEquals("bound", binding(f).status());
            assertTrue(promptOf(f, 0).contains("filler_11"),
                    "without a shortlist the model sees every fitting candidate");

            jdbc.update("UPDATE relation_types SET embedding = '[1,0,0,0]' WHERE kb_id = ?",
                    f.kb);
            script(f, bound());
            run(f);
            assertEquals(1, f.model.embedCalls(),
                    "with property vectors the signature is embedded");
        } finally {
            cleanup(f);
        }
    }

    /** 签名的向量存下来（#1097）：库没变，第二轮一次嵌入也不叫；换了嵌入模型重嵌一次。 */
    @Test
    void aSecondRunOverAnUnchangedBaseMakesNoEmbeddingCall() throws Exception {
        Fx f = seed();
        try {
            enableEmbeddings(f);
            for (int i = 0; i < 12; i++) {
                jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind,temporal)"
                        + " VALUES (?, ?, ?, ?, 'relation', 'state')", Uuids.nowV7(), f.kb,
                        "filler_" + i, "filler_" + i);
            }
            jdbc.update("UPDATE relation_types SET embedding = '[1,0,0,0]' WHERE kb_id = ?",
                    f.kb);
            script(f, bound());
            run(f);
            assertEquals(1, f.model.embedCalls(), "the first run embeds the signature");
            assertEquals("bound", binding(f).status());
            int asked = f.model.requests().size();

            run(f);
            assertEquals(1, f.model.embedCalls(),
                    "a second run over an unchanged base makes no embedding call");
            assertEquals(asked, f.model.requests().size(), "and asks the model nothing");
            assertEquals(List.of("scripted-embed"), jdbc.queryForList(
                    "SELECT model FROM signature_vectors WHERE kb_id = ? ORDER BY model",
                    String.class, f.kb));

            jdbc.update("UPDATE llm_settings SET embed_model = 'scripted-embed-2'"
                    + " WHERE workspace_id = ?", f.ws);
            run(f);
            assertEquals(2, f.model.embedCalls(), "a new embedding model embeds again");
            assertEquals(List.of("scripted-embed-2"), jdbc.queryForList(
                    "SELECT model FROM signature_vectors WHERE kb_id = ? ORDER BY model",
                    String.class, f.kb), "the old model's vectors are dropped");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void anEndpointClassChangeMovesTheSignatureAndTheOldRowStopsLooping() throws Exception {
        Fx f = seed();
        try {
            // Acme 还没有类：签名 (based in, ?, place)，声明了域的属性不接受空的一端
            jdbc.update("UPDATE entities SET type_id = NULL WHERE id = ?", f.acme);
            run(f);
            assertEquals(0, f.model.requests().size());
            Binding old = binding(f);
            assertEquals("none", old.status());
            assertNull(old.subjectTypeId());
            // 类别词绑上了：签名换成 (based in, organization, place)，旧行成了孤儿
            jdbc.update("UPDATE entities SET type_id = ? WHERE id = ?", f.organization, f.acme);
            script(f, bound());
            run(f);
            assertEquals(2, f.model.requests().size());
            List<Binding> all = phraseBindings.bindings(f.kb);
            assertEquals(2, all.size(), "the orphan stays as a cached decision");
            assertTrue(all.stream().anyMatch(b -> f.organization.equals(b.subjectTypeId())
                    && "bound".equals(b.status())));
            assertFalse(requeued(f), "an orphan must not queue work it cannot execute");
            run(f);
            assertEquals(2, f.model.requests().size(),
                    "a second run asks nothing and queues nothing");
            assertFalse(requeued(f));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void anEditDuringTheModelRequestLeavesTheDecisionStale() throws Exception {
        Fx f = seed();
        try {
            f.model.holdFirstRequest();
            script(f, none());
            Thread worker = new Thread(() -> {
                try {
                    alignPhrases.alignPhrasesLocked(f.kb, 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();
            f.model.awaitRequestArrived(1);
            // 模型还在答，定义改了：两票读的都是旧定义
            jdbc.update("UPDATE relation_types SET description = 'NEW definition',"
                    + " updated_at = clock_timestamp() WHERE id = ?", f.basedIn);
            f.model.releaseFirstRequest();
            worker.join(30_000);
            assertEquals("none", binding(f).status());
            assertTrue(requeued(f),
                    "the run noticed its own basis is already stale and queued another");
            clearJobs(f);
            script(f, bound());
            run(f);
            assertEquals(3, f.model.requests().size(),
                    "one vote that says none, then two votes (a none is not asked for rules):"
                            + " the basis differs, not the clock");
            assertEquals("bound", binding(f).status());
        } finally {
            f.model.releaseFirstRequest();
            cleanup(f);
        }
    }

    @Test
    void aPersonDecisionMadeDuringTheRequestIsNotOverwritten() throws Exception {
        Fx f = seed();
        try {
            f.model.holdFirstRequest();
            script(f, none());
            Thread worker = new Thread(() -> {
                try {
                    alignPhrases.alignPhrasesLocked(f.kb, 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();
            f.model.awaitRequestArrived(1);
            PhraseSignature sig = phraseBindings.signatures(f.kb).get(0);
            phraseBindings.decide(f.kb, sig, new PhraseBindingsService.Decision(f.basedIn,
                    "forward", "bound",
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                    "person", null, null, false));
            f.model.releaseFirstRequest();
            worker.join(30_000);
            Binding b = binding(f);
            assertEquals("bound", b.status());
            assertEquals("person", b.decidedBy());
            assertFalse(requeued(f), "a person's decision is never re-evaluated");
        } finally {
            f.model.releaseFirstRequest();
            cleanup(f);
        }
    }

    /** 第一票说没有，结论就是没有：第二票不投，也不问规则。 */
    @Test
    void aFirstVoteThatSaysNoneIsNotFollowedByASecond() throws Exception {
        Fx f = seed();
        try {
            script(f, List.of(vote(null, null), vote("based_in", "forward")));
            run(f);
            assertEquals(1, f.model.requests().size(), "one vote");
            assertEquals("none", binding(f).status());
            assertEquals("first_vote_none", reason(f));
            assertEquals(0L, typed(f));
        } finally {
            cleanup(f);
        }
    }

    /** 两票都得读出来，签名才绑得上。 */
    @Test
    void anIdKeyedReplyStillBindsThePhrase() throws Exception {
        Fx f = seed();
        try {
            script(f, List.of(
                    "{\"0\": [\"based_in\", \"forward\", \"none\"]}",
                    "{\"b\": {\"0\": {\"key\": \"based_in\", \"direction\": \"forward\","
                            + " \"marks\": \"none\"}}}",
                    nothingImplied()));
            run(f);
            assertEquals(2, f.model.requests().size(), "two votes, both read");
            Binding b = binding(f);
            assertEquals("bound", b.status());
            assertEquals("agent", b.decidedBy());
            assertEquals(f.basedIn, b.relationTypeId());
            assertEquals("forward", b.direction());
            assertEquals(1L, typed(f), "the projection follows");
            assertFalse(requeued(f), "nothing left to ask");
        } finally {
            cleanup(f);
        }
    }

    /** 读不出的回复这一轮不下结论、不写任何行，任务自己再排一份（带 reask）。 */
    @Test
    void anUnreadableReplyIsAskedAgainABoundedNumberOfTimes() throws Exception {
        Fx f = seed();
        try {
            script(f, List.of(
                    "{\"answer\": \"based_in\", \"direction\": \"forward\"}",
                    "{\"answer\": \"based_in\", \"direction\": \"forward\"}",
                    "{\"answer\": \"based_in\", \"direction\": \"forward\"}",
                    "{\"answer\": \"based_in\", \"direction\": \"forward\"}"));
            run(f);
            assertEquals(1, f.model.requests().size(),
                    "the first vote is unreadable, the second is not cast");
            assertTrue(phraseBindings.bindings(f.kb).isEmpty(),
                    "an unreadable reply writes no decision");
            List<Object[]> reasks = jdbc.query(
                    "SELECT status, (payload->>'reask')::bigint FROM jobs WHERE kind ="
                            + " 'align_phrases' AND payload->>'kb_id' = ? ORDER BY id",
                    (rs, i) -> new Object[]{rs.getString(1),
                            rs.getObject(2, Long.class)}, f.kb.toString());
            assertEquals(1, reasks.size());
            assertEquals("queued", reasks.get(0)[0]);
            assertEquals(1L, reasks.get(0)[1],
                    "after the first round it queues itself once with reask=1");
            clearJobs(f);
            // 已经是最后一次自己排的：不再排
            script(f, List.of(
                    "{\"answer\": \"based_in\", \"direction\": \"forward\"}",
                    "{\"answer\": \"based_in\", \"direction\": \"forward\"}"));
            alignPhrases.alignPhrasesLocked(f.kb, AlignPhrasesJob.MAX_REASK);
            assertEquals(2, f.model.requests().size(), "the last allowed round still asks");
            Long after = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE kind ="
                    + " 'align_phrases' AND payload->>'kb_id' = ?", Long.class, f.kb.toString());
            assertEquals(0L, after, "past MAX_REASK it stops queueing itself");
            assertTrue(phraseBindings.bindings(f.kb).isEmpty());
        } finally {
            cleanup(f);
        }
    }

    /** 状态属性下，两票还要说只带一个日期的陈述那个日期标什么（#966）。 */
    @Test
    void aStateBindingCarriesWhatASingleDateMarks() throws Exception {
        Fx f = seed();
        try {
            script(f, List.of(marked("based_in", "forward", "start"),
                    marked("based_in", "forward", "start")));
            run(f);
            assertEquals(2, f.model.requests().size());
            String prompt = promptOf(f, 0);
            assertTrue(prompt.contains("- based_in · based in · relation · state ·"),
                    "the model is told the property is a state: " + prompt);
            Binding b = binding(f);
            assertEquals("bound", b.status());
            assertEquals("start", b.marks());
            JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(jdbc.queryForObject(
                            "SELECT votes::text FROM phrase_bindings WHERE kb_id = ?",
                            String.class, f.kb));
            assertEquals("start", parsed.path("first").path("marks").asText());
            assertEquals("start", parsed.path("second").path("marks").asText());
            assertFalse(requeued(f));
        } finally {
            cleanup(f);
        }
    }

    /** 这条签名在对齐队列里：绑到 based_in、照原文方向，只等人说那个日期标什么。 */
    private boolean waitsForTheDate(Fx f) {
        List<?> items = reviews.items(f.kb, "alignment", null, 10, 0);
        boolean listed = items.size() == 1 && items.get(0) instanceof AlignmentItem.Phrase p
                && "based_in".equals(p.boundTo()) && "forward".equals(p.direction());
        long waiting = reviews.summary(f.kb).waiting().alignment().count();
        long badge = reviews.counts(f.kb).alignment();
        return listed && waiting == 1 && badge == 1;
    }

    /**
     * 两票选了同一个属性、同一个方向，却一票说开始、一票说结束，或有一票没说：属性两票
     * 说定了照绑；那个日期没说定，marks 空着交给人。不再问，也不给自己再排一轮。
     */
    @Test
    void twoVotesThatDoNotAgreeOnTheDateBindAndLeaveItToAPerson() throws Exception {
        String threeCells = "{\"b\":[[0, \"based_in\", \"forward\"]]}";
        for (Object[] row : new Object[][]{
                {"end", marked("based_in", "forward", "end")},
                {"three cells", threeCells}}) {
            String label = (String) row[0];
            String first = (String) row[1];
            Fx f = seed();
            try {
                script(f, List.of(first, marked("based_in", "forward", "start")));
                run(f);
                Binding b = binding(f);
                assertEquals("bound", b.status(), label);
                assertEquals(f.basedIn, b.relationTypeId(), label);
                assertNull(b.marks(), label);
                assertTrue(b.marksAskedAt() != null, label + ": asked");
                JsonNode votes = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(jdbc.queryForObject(
                                "SELECT votes::text FROM phrase_bindings WHERE kb_id = ?",
                                String.class, f.kb));
                assertEquals("start", votes.path("second").path("marks").asText(),
                        label + ": both readings are kept for the person");
                assertEquals(1L, typed(f), label + ": the statement is typed");
                assertTrue(waitsForTheDate(f), label);
                assertFalse(requeued(f), label + ": no re-ask");
                run(f);
                assertEquals(2, f.model.requests().size(), label + ": asked once");
            } finally {
                cleanup(f);
            }
        }
    }

    /** 把绑定改回这一问出现之前的样子：没有 marks，也没问过。 */
    private void asBeforeTheQuestion(Fx f) {
        jdbc.update("UPDATE phrase_bindings SET marks = NULL, marks_asked_at = NULL"
                + " WHERE kb_id = ?", f.kb);
    }

    /** 绑定里补问不该动的部分。 */
    private String decision(Fx f) {
        return jdbc.queryForObject("SELECT jsonb_build_array(relation_type_id, direction,"
                + " status, decided_by, decided_at, basis, votes)::text FROM phrase_bindings"
                + " WHERE kb_id = ?", String.class, f.kb);
    }

    /** 这一问出现之前判下的绑定没有 marks：指纹没变也问一次；问过就不再问。 */
    @Test
    void aBindingDecidedBeforeTheQuestionIsAskedOnceAndAPersonsWaitsInTheQueue()
            throws Exception {
        Fx f = seed();
        try {
            script(f, bound());
            run(f);
            assertEquals("none", binding(f).marks());
            asBeforeTheQuestion(f);
            String decided = decision(f);
            script(f, List.of(marked("based_in", "forward", "start"),
                    marked("based_in", "forward", "start")));
            run(f);
            assertEquals(4, f.model.requests().size(),
                    "the fingerprint matches, yet the binding is asked for the value it lacks");
            assertEquals(decided, decision(f), "only the value is written");
            Binding b = binding(f);
            assertEquals("start", b.marks());
            assertTrue(b.marksAskedAt() != null);
            run(f);
            assertEquals(4, f.model.requests().size(), "asked once: it has its value now");
            assertFalse(requeued(f));

            PhraseSignature sig = phraseBindings.signatures(f.kb).get(0);
            phraseBindings.decide(f.kb, sig, new PhraseBindingsService.Decision(f.basedIn,
                    "forward", "bound",
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            "{\"person\": {\"property\": \"based_in\", \"direction\":"
                                    + " \"forward\"}}"),
                    "person", null, null, false));
            run(f);
            assertEquals(4, f.model.requests().size(), "a person's binding is not asked");
            assertTrue(waitsForTheDate(f));
        } finally {
            cleanup(f);
        }
    }

    /** 模型从来不给第四格（#975 评审）：这一列之前绑上的签名只问一次。 */
    @Test
    void aModelThatNeverGivesTheValueIsAskedOnce() throws Exception {
        Fx f = seed();
        try {
            script(f, bound());
            run(f);
            assertEquals(1L, typed(f));
            asBeforeTheQuestion(f);
            String decided = decision(f);
            String threeCells = "{\"b\":[[0, \"based_in\", \"forward\"]]}";
            script(f, List.of(threeCells, threeCells));
            run(f);
            assertEquals(4, f.model.requests().size(), "one ask: two votes");
            assertEquals(decided, decision(f), "the binding is unchanged");
            assertEquals(1L, typed(f), "its typed row is still live");
            run(f);
            run(f);
            assertEquals(4, f.model.requests().size(), "not asked again");
            Binding b = binding(f);
            assertNull(b.marks());
            assertTrue(b.marksAskedAt() != null);
            assertTrue(waitsForTheDate(f));
            assertFalse(requeued(f), "no re-ask is queued");
        } finally {
            cleanup(f);
        }
    }

    /** 补问只能写那个日期标什么，重开不了绑定（#975 评审）。 */
    @Test
    void askingWhatADateMarksNeverReopensTheBinding() throws Exception {
        Fx f = seed();
        try {
            script(f, bound());
            run(f);
            String[][][] answers = {
                    {{"no property"}, {vote(null, null)}, {vote(null, null)}, {"null"}},
                    {{"the other direction"},
                            {marked("based_in", "reverse", "start")},
                            {marked("based_in", "reverse", "start")}, {"null"}},
                    {{"two readings"}, {marked("based_in", "forward", "start")},
                            {marked("based_in", "forward", "end")}, {"null"}},
                    {{"one reading"}, {marked("based_in", "forward", "end")},
                            {marked("based_in", "forward", "end")}, {"end"}}};
            for (String[][] answer : answers) {
                String label = answer[0][0];
                String first = answer[1][0];
                String second = answer[2][0];
                String value = answer[3][0].equals("null") ? null : answer[3][0];
                asBeforeTheQuestion(f);
                String decided = decision(f);
                script(f, List.of(first, second));
                run(f);
                assertEquals(decided, decision(f), label + ": the binding is unchanged");
                assertEquals(1L, typed(f), label + ": its typed row is still live");
                Binding b = binding(f);
                assertEquals(value, b.marks(), label);
                assertTrue(b.marksAskedAt() != null, label);
                assertEquals(value == null, waitsForTheDate(f), label);
                assertFalse(requeued(f), label);
            }
        } finally {
            cleanup(f);
        }
    }

    // ---- 第 19 例（M3 任务 10 去封：依赖 embed_ontology job 本体与本体编辑器写入口） ----

    /**
     * 编辑器建的属性先有向量，短语对齐才开跑：处理函数把 embed_ontology 排在 align_phrases
     * 前面，对齐看见补齐还排着就等；补齐跑完向量在表里，对齐才问模型，而且新属性在候选里。
     * 改了描述再走一遍：存下的原文对不上，同一个任务把它重嵌。
     *
     * <p>共享栈纪律：活着的 Rust worker（1516）会真认领 embed_ontology/align_phrases——
     * 每次编辑器写入后立刻把这两个 kind park 到 +1h（embed 先、align 后，保住
     * queued() 的 run_at 序断言），窗口只有毫秒级（Rust 私库无 worker，原断言无此干扰）。
     */
    @Test
    void aPropertyCreatedInTheEditorHasItsVectorBeforePhrasesAreAligned() throws Exception {
        Fx f = seed();
        try {
            // 直调面登录态（P2 任务 4 起 OntologyController 挂 @PreAuthorize——hasPermi 要
            // LoginUser）；本例两处直调（createRelationType/updateRelationType）全要过注解
            loginAsAdminDirectCall();
            enableEmbeddings(f);
            tech.seexun.jknow.store.entity.UserRow editor = editor(f);
            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode req = json.readTree("""
                    {"key": "headquartered_in", "label": "headquartered in", "kind": "relation",
                     "description": "where the main office of an entity is",
                     "domains": ["%s"], "ranges": ["%s"]}
                    """.formatted(f.legalEntity, f.place));
            var created = ontologyApi.createRelationType(editor, f.kb, req);
            UUID id = UUID.fromString(created.get("id").asText());

            parkAlignJobs(f);
            assertEquals(List.of("embed_ontology", "align_phrases"), queued(f),
                    "the index refresh is queued ahead of the alignment");
            assertEquals(java.util.Arrays.asList(false, null), vectorOf(f, id),
                    "the handler itself does not embed");

            // 对齐先到：补齐还排着，不问模型，自己排到后面
            script(f, bound());
            alignPhrases.handle(job(f.kb, 0));
            assertEquals(0, f.model.requests().size(),
                    "nothing is asked while the index refresh is still queued");
            assertEquals(List.of("embed_ontology", "align_phrases"), queued(f),
                    "the alignment is queued again behind the refresh");

            // worker 到补齐这一份：跑它的主体，标成完成
            assertEquals(8, embedOntology.refresh(f.kb),
                    "the three classes twice over (full text and label) plus based_in"
                            + " and the new property");
            jdbc.update("UPDATE jobs SET status='done' WHERE kind='embed_ontology'"
                    + " AND payload->>'kb_id' = ?", f.kb.toString());
            var vector = vectorOf(f, id);
            assertEquals(Boolean.TRUE, vector.get(0),
                    "the property is in the vector table before align_phrases runs");
            assertEquals("headquarteredIn\nwhere the main office of an entity is", vector.get(1),
                    "label (camel-cased by the store) and description are what was embedded");

            // 现在对齐开跑，而且新属性在候选里
            alignPhrases.handle(job(f.kb, 0));
            assertFalse(f.model.requests().isEmpty(),
                    "the refresh done, the model is asked");
            String prompt = promptOf(f, 0);
            assertTrue(prompt.contains("headquartered_in"),
                    "the new property is offered: " + prompt);

            // 改描述：存下的原文对不上，补齐任务再排、只重嵌这一条
            clearJobs(f);
            com.fasterxml.jackson.databind.JsonNode update = json.readTree("""
                    {"label": "headquartered in", "kind": "relation",
                     "description": "where the head office of an organization sits"}
                    """);
            var updated = ontologyApi.updateRelationType(editor, f.kb, id, update);
            assertTrue(updated.get("ok").asBoolean());
            parkAlignJobs(f);
            assertEquals(List.of("embed_ontology", "align_phrases"), queued(f),
                    "a changed description queues the refresh ahead of the alignment again");
            assertEquals(1, embedOntology.refresh(f.kb),
                    "only the row whose text changed is re-embedded");
            assertEquals("headquarteredIn\nwhere the head office of an organization sits",
                    vectorOf(f, id).get(1));
        } finally {
            cleanup(f);
            logoutDirectCall();
        }
    }

    /** 这个库的一个编辑：走 API 处理函数要有人（Rust f.editor 同位）。 */
    private tech.seexun.jknow.store.entity.UserRow editor(Fx f) {
        UUID id = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Editor')", id, f.org,
                "editor-" + id + "@example.test");
        jdbc.update("INSERT INTO kb_members(kb_id,user_id,role) VALUES (?, ?, 'editor')",
                f.kb, id);
        return new tech.seexun.jknow.store.entity.UserRow(id, f.org,
                "editor-" + id + "@example.test", "x", "Editor", false, null, null);
    }

    /** 一条关系/属性有没有向量，以及嵌的是哪段字（Rust vector_of 同位）。 */
    private java.util.List<Object> vectorOf(Fx f, UUID id) {
        return jdbc.query(
                "SELECT embedding IS NOT NULL, embedded_text FROM relation_types WHERE id = ?",
                (rs, i) -> {
                    java.util.List<Object> row = new java.util.ArrayList<>(2);
                    row.add(rs.getBoolean(1));
                    row.add(rs.getString(2));
                    return row;
                }, id).get(0);
    }

    /**
     * 共享栈双打纪律：编辑器写入刚排下的 embed_ontology/align_phrases 立即 park 到 +1h
     * （embed 先 park，保住 run_at 序）；窗口只有「写入提交→park 提交」的毫秒级——
     * Rust worker 的空闲轮询 2s、embed 的 run_at 本来就在 +2s，撞不进窗口。
     */
    private void parkAlignJobs(Fx f) {
        jdbc.update("UPDATE jobs SET run_at = now() + interval '1 hour'"
                + " WHERE kind = 'embed_ontology' AND status = 'queued'"
                + " AND payload->>'kb_id' = ?", f.kb.toString());
        jdbc.update("UPDATE jobs SET run_at = now() + interval '1 hour'"
                + " WHERE kind = 'align_phrases' AND status = 'queued'"
                + " AND payload->>'kb_id' = ?", f.kb.toString());
    }
}
