package tech.seexun.jknow.web.knowledge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.Binding;

/**
 * 移植自 crates/utopia-server/src/type_alignment_tests.rs（非 HTTP 的 12 例）——有争议的
 * 类别词绑定不再把旧类投影到实体上；两票不一致撤投影、人判不被盖、请求途中的编辑让判定
 * 过期（#795）、父边指纹、有上限的 reask、嵌入端点坏着退整表。脚本化 SSE 模型端点 +
 * 真库。断言逐条不改写。
 *
 * <p>review_locks mod 的 5 例走 {@code POST /review/alignment/kind-words/{word}} HTTP 面
 * （含 lock_timeout 会话语义）——按 M3/M4 边界（14 队列写端点属 M4）随端点落地，台账记行。
 */
class TypeAlignmentTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlignTypesJob alignTypes;
    @Autowired
    SettingsService settings;
    @Autowired
    TypeBindingsService typeBindings;
    @Autowired
    PhraseBindingsService phraseBindings;

    private static final class Fx {
        final UUID org;
        final UUID ws;
        final UUID kb;
        final UUID klass;
        final UUID entity;
        final ScriptedModelServer model;

        Fx(UUID org, UUID ws, UUID kb, UUID klass, UUID entity, ScriptedModelServer model) {
            this.org = org;
            this.ws = ws;
            this.kb = kb;
            this.klass = klass;
            this.entity = entity;
            this.model = model;
        }
    }

    /** 一个类（organization，旧定义）+ 一个 specific_type='company' 的实体。 */
    private Fx seed(List<String> replies) throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID klass = Uuids.nowV7();
        UUID entity = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'alignment-audit')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'alignment-audit')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name)"
                + " VALUES (?, ?, 'alignment-audit')", kb, ws);
        jdbc.update("INSERT INTO entity_types(id,kb_id,key,label,description)"
                + " VALUES (?, ?, 'organization', 'Organization', 'OLD definition')", klass, kb);
        jdbc.update("INSERT INTO entities(id,kb_id,canonical_name,specific_type)"
                + " VALUES (?, ?, 'Acme', 'company')", entity, kb);
        ScriptedModelServer model = ScriptedModelServer.start(replies);
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        return new Fx(org, ws, kb, klass, entity, model);
    }

    private void seedBound(Fx f) {
        typeBindings.decide(f.kb, "company", List.of(), f.klass, "bound",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "agent");
        typeBindings.applyToEntities(f.kb, "company", f.klass);
        jdbc.update("UPDATE type_bindings SET decided_at = '2000-01-01' WHERE kb_id = ?", f.kb);
    }

    private void cleanup(Fx f) {
        f.model.stop();
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    private Binding binding(Fx f) {
        List<Binding> all = typeBindings.bindings(f.kb);
        return all.get(0);
    }

    private UUID projectedType(Fx f, UUID entity) {
        return jdbc.queryForObject("SELECT type_id FROM entities WHERE id = ?", UUID.class,
                entity);
    }

    private static tech.seexun.jknow.store.entity.Job job(UUID kb, int reask) {
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("kb_id", kb.toString());
        if (reask != 0) {
            payload.put("reask", reask);
        }
        return new tech.seexun.jknow.store.entity.Job(0, "align_types", payload, 1, 3);
    }

    private static String vote(String klass) {
        return "{\"b\":[[0," + (klass == null ? "null" : "\"" + klass + "\"") + "]]}";
    }

    /** 本体向量还在补时类别词对齐不动手：给自己排一份半分钟后的。 */
    @Test
    void kindWordAlignmentWaitsWhileTheOntologyIndexIsRefreshing() throws Exception {
        Fx f = seed(List.of(vote("organization"), vote("organization")));
        try {
            jdbc.update("INSERT INTO jobs (kind, payload) VALUES ('embed_ontology', ?::jsonb)",
                    "{\"kb_id\": \"" + f.kb + "\"}");
            alignTypes.handle(job(f.kb, 0));
            assertEquals(0, f.model.requests().size(),
                    "nothing is asked while the ontology index is still being refreshed");
            var deferred = jdbc.queryForMap("SELECT status, run_at > now() AS later FROM jobs"
                    + " WHERE kind = 'align_types' AND payload->>'kb_id' = ?", f.kb.toString());
            assertEquals("queued", deferred.get("status"));
            assertEquals(Boolean.TRUE, deferred.get("later"),
                    "one kind-word round is queued for later");
            jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
            alignTypes.handle(job(f.kb, 0));
            assertEquals(2, f.model.requests().size(),
                    "the refresh gone, the two votes are asked");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void disagreementRetractsPreviousAlignedType() throws Exception {
        Fx f = seed(List.of(vote("organization"), vote(null)));
        try {
            seedBound(f);
            UUID human = Uuids.nowV7();
            jdbc.update("INSERT INTO entities(id,kb_id,canonical_name,specific_type,type_id,"
                    + " type_source) VALUES (?, ?, 'Human choice', 'company', ?, 'human')",
                    human, f.kb, f.klass);
            // Downstream phrase signatures derive their subject type from the entity projection.
            jdbc.update("INSERT INTO facts(id,kb_id,subject_id,object_value,layer,phrase)"
                    + " VALUES (?, ?, ?, '{\"value\":\"UK\"}'::jsonb, 'open', 'based in')",
                    Uuids.nowV7(), f.kb, f.entity);
            alignTypes.alignTypesLocked(f.kb, 0);
            Binding b = binding(f);
            UUID projected = projectedType(f, f.entity);
            UUID downstream = phraseBindings.signatures(f.kb).isEmpty() ? null
                    : phraseBindings.signatures(f.kb).get(0).subjectTypeId();
            UUID humanType = projectedType(f, human);
            assertEquals(f.klass, humanType,
                    "explicit human entity classification is preserved");
            int count = f.model.requests().size();
            assertEquals(2, count);
            assertEquals("undecided", b.status());
            assertNull(b.typeId());
            assertNull(projected,
                    "an undecided binding must not leave an aligned class on its entities");
            assertNull(downstream, "phrase alignment must not consume the revoked class");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void humanDecisionDuringDisagreementSurvives() throws Exception {
        Fx f = seed(List.of(vote("organization"), vote(null)));
        try {
            f.model.holdFirstRequest();
            Thread worker = new Thread(() -> {
                try {
                    alignTypes.alignTypesLocked(f.kb, 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();
            f.model.awaitRequestArrived(1);
            typeBindings.decide(f.kb, "company", List.of(), f.klass, "bound",
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                    "person");
            typeBindings.applyToEntities(f.kb, "company", f.klass);
            f.model.releaseFirstRequest();
            worker.join(30_000);
            Binding b = binding(f);
            UUID projected = projectedType(f, f.entity);
            Integer requeued = jdbc.queryForObject(
                    "SELECT count(*) FROM jobs WHERE kind = 'align_types'"
                            + " AND payload->>'kb_id' = ?", Integer.class, f.kb.toString());
            assertEquals("person", b.decidedBy());
            assertEquals(f.klass, b.typeId());
            assertEquals(f.klass, projected);
            assertEquals(0, requeued,
                    "a person's decision carries no basis and is never asked about again");
        } finally {
            f.model.releaseFirstRequest();
            cleanup(f);
        }
    }

    @Test
    void agreedVotesKeepTheirExistingBehavior() throws Exception {
        for (String key : new String[]{"organization", null}) {
            Fx f = seed(List.of(vote(key), vote(key)));
            try {
                seedBound(f);
                alignTypes.alignTypesLocked(f.kb, 0);
                Binding b = binding(f);
                UUID projected = projectedType(f, f.entity);
                UUID expected = key != null ? f.klass : null;
                assertEquals(expected, b.typeId());
                assertEquals(expected, projected);
                assertEquals(key != null ? "bound" : "none", b.status());
            } finally {
                cleanup(f);
            }
        }
    }

    /**
     * 代理已经写到实体那一刻人来判 none：代理的绑定事务先锁了行，人排队；迟到的代理不
     * 覆盖人的 none。用 PG 行锁把代理精确停在实体写处，不靠钟。
     */
    @Test
    void humanNoneWinsWhenAgentIsAlreadyWritingItsProjection() throws Exception {
        Fx f = seed(List.of());
        try {
            java.sql.Connection gate = java.sql.DriverManager.getConnection(DB_URL, DB_USER,
                    DB_PASS);
            gate.setAutoCommit(false);
            int blocker;
            try (var rs = gate.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                blocker = rs.getInt(1);
            }
            gate.createStatement().execute("SELECT id FROM entities WHERE id = '"
                    + f.entity + "' FOR UPDATE");
            Thread agent = new Thread(() -> {
                typeBindings.decideAndApply(f.kb, "company", List.of(), f.klass, "bound",
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        "agent");
            });
            agent.start();
            assertTrue(waitBlocked(blocker), "agent is waiting on the gate");
            Thread person = new Thread(() -> {
                typeBindings.decideAndApply(f.kb, "company", List.of(), null, "none",
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                        "person");
            });
            person.start();
            assertTrue(waitBlockedBy(blocker), "person queues behind the agent's binding lock");
            gate.commit();
            gate.close();
            agent.join(30_000);
            person.join(30_000);
            Binding b = binding(f);
            UUID projected = projectedType(f, f.entity);
            assertEquals("person", b.decidedBy());
            assertEquals("none", b.status());
            assertNull(projected, "the older agent must not undo the person's none decision");
        } finally {
            cleanup(f);
        }
    }

    /** blocker 的锁挡住了别的会话（pg_blocking_pids 里出现 blocker）。 */
    private boolean waitBlocked(int blocker) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                            + " WHERE ? = ANY(pg_blocking_pids(pid)))", Boolean.class, blocker);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** 有会话被「被 blocker 挡住的那条」挡住（人排在代理后面）。 */
    private boolean waitBlockedBy(int blocker) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity p WHERE EXISTS"
                            + " (SELECT 1 FROM unnest(pg_blocking_pids(p.pid)) b(pid)"
                            + " WHERE ? = ANY(pg_blocking_pids(b.pid))))", Boolean.class,
                    blocker);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** bench 里「12 篇文档 type_id 全空」的回复形状：两票都得读出来，词才绑得上。 */
    @Test
    void anIdKeyedReplyStillBindsTheKindWord() throws Exception {
        Fx f = seed(List.of("{\"0\": \"organization\"}",
                "{\"b\": {\"0\": [\"organization\"]}}"));
        try {
            alignTypes.alignTypesLocked(f.kb, 0);
            Binding b = binding(f);
            UUID projected = projectedType(f, f.entity);
            int count = f.model.requests().size();
            assertEquals(2, count);
            assertEquals("bound", b.status());
            assertEquals(f.klass, b.typeId());
            assertEquals(f.klass, projected);
        } finally {
            cleanup(f);
        }
    }

    /** 读不出的回复这一轮不下结论，任务自己再排一份（带 reask）；最后一轮排短语对齐。 */
    @Test
    void anUnreadableReplyIsAskedAgainABoundedNumberOfTimes() throws Exception {
        Fx f = seed(List.of("{\"answer\": \"organization\"}", "{\"answer\": \"organization\"}",
                "{\"answer\": \"organization\"}", "{\"answer\": \"organization\"}"));
        try {
            alignTypes.alignTypesLocked(f.kb, 0);
            int bindings = typeBindings.bindings(f.kb).size();
            List<Object[]> reasks = jdbc.query(
                    "SELECT status, (payload->>'reask')::bigint FROM jobs WHERE kind ="
                            + " 'align_types' AND payload->>'kb_id' = ? ORDER BY id",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, Long.class)},
                    f.kb.toString());
            // 还要再问一轮：短语对齐先不排，两端的类还没定
            Long phrasesQueuedEarly = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE"
                    + " kind = 'align_phrases' AND payload->>'kb_id' = ?", Long.class,
                    f.kb.toString());
            jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
            // 已经是最后一次自己排的：不再排
            alignTypes.alignTypesLocked(f.kb, AlignTypesJob.MAX_REASK);
            Long after = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE kind ="
                    + " 'align_types' AND payload->>'kb_id' = ?", Long.class, f.kb.toString());
            // 这是最后一轮：短语对齐现在排
            Long phrasesQueuedLate = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE"
                    + " kind = 'align_phrases' AND payload->>'kb_id' = ?", Long.class,
                    f.kb.toString());
            int count = f.model.requests().size();
            assertEquals(4, count, "两轮各问两票");
            assertEquals(0, bindings, "读不出的回复不写任何判定");
            assertEquals(1, reasks.size());
            assertEquals("queued", reasks.get(0)[0]);
            assertEquals(1L, reasks.get(0)[1], "第一轮之后自己排一份 reask=1");
            assertEquals(0L, after, "排够次数就不再排");
            assertEquals(0L, phrasesQueuedEarly, "还要再问时短语对齐不排");
            assertEquals(1L, phrasesQueuedLate, "最后一轮排短语对齐");
        } finally {
            cleanup(f);
        }
    }

    /** #795：模型还在答的时候类的定义改了——两票读的都是旧定义，下一轮拿新定义再问。 */
    @Test
    void anEditDuringTheModelRequestIsAskedAgain() throws Exception {
        Fx f = seed(List.of(vote(null), vote(null), vote("organization"), vote("organization")));
        try {
            f.model.holdFirstRequest();
            Thread worker = new Thread(() -> {
                try {
                    alignTypes.alignTypesLocked(f.kb, 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            worker.start();
            f.model.awaitRequestArrived(1);
            jdbc.update("UPDATE entity_types SET description = 'NEW definition',"
                    + " updated_at = clock_timestamp() WHERE id = ?", f.klass);
            f.model.releaseFirstRequest();
            worker.join(30_000);
            List<JsonNode> first = f.model.requests();
            assertEquals(2, first.size(), "two votes");
            assertTrue(first.stream().allMatch(r -> r.toString().contains("OLD definition")),
                    "both votes were built before the edit");
            jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
            alignTypes.alignTypesLocked(f.kb, 0);
            List<JsonNode> second = f.model.requests();
            assertEquals(4, second.size(),
                    "the edit made during the request was taken as seen");
            assertTrue(second.subList(2, 4).stream()
                    .allMatch(r -> r.toString().contains("NEW definition")),
                    "the next run asks with the new definition");
            Binding b = binding(f);
            assertEquals("bound", b.status());
            assertEquals(f.klass, b.typeId());
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(4, f.model.requests().size(), "unchanged inputs ask nothing");
        } finally {
            f.model.releaseFirstRequest();
            cleanup(f);
        }
    }

    /** 父边的增删不碰 updated_at，时间戳看不见它；指纹里有祖先闭包，看得见。 */
    @Test
    void aParentEdgeMakesAnAgentBindingStale() throws Exception {
        Fx f = seed(List.of(vote("organization"), vote("organization"), vote("organization"),
                vote("organization")));
        try {
            UUID legal = Uuids.nowV7();
            jdbc.update("INSERT INTO entity_types(id,kb_id,key,label,description)"
                    + " VALUES (?, ?, 'legal_entity', 'Legal entity',"
                    + " 'A body the law treats as a person')", legal, f.kb);
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(2, f.model.requests().size());
            jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
            List<List<Object>> before = jdbc.query(
                    "SELECT id, updated_at FROM entity_types WHERE kb_id = ? ORDER BY id",
                    (rs, i) -> (List<Object>) (List) java.util.Arrays.asList(
                            rs.getObject(1, UUID.class),
                            rs.getObject(2, java.time.OffsetDateTime.class)), f.kb);
            jdbc.update("INSERT INTO entity_type_parents(child_id,parent_id,is_primary)"
                    + " VALUES (?, ?, true)", f.klass, legal);
            List<List<Object>> after = jdbc.query(
                    "SELECT id, updated_at FROM entity_types WHERE kb_id = ? ORDER BY id",
                    (rs, i) -> (List<Object>) (List) java.util.Arrays.asList(
                            rs.getObject(1, UUID.class),
                            rs.getObject(2, java.time.OffsetDateTime.class)), f.kb);
            assertEquals(before, after, "a parent edge leaves updated_at alone");
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(4, f.model.requests().size(),
                    "the ancestor closure is part of the basis, so the word is asked again");
        } finally {
            cleanup(f);
        }
    }

    /** 这一列出现之前代理判的行没有指纹：各重判一次，之后不再问。 */
    @Test
    void rowsWithoutABasisAreDecidedAgainOnce() throws Exception {
        Fx f = seed(List.of(vote("organization"), vote("organization")));
        try {
            seedBound(f);
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(2, f.model.requests().size());
            Binding b = binding(f);
            assertTrue(b.basis() != null && "agent".equals(b.decidedBy()));
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(2, f.model.requests().size(),
                    "a recorded basis that still matches asks nothing");
        } finally {
            cleanup(f);
        }
    }

    /** 过期的词这一轮问了却没问出结论：走有上限的延时再问，不能「还过期」就立刻再排。 */
    @Test
    void aStaleWordWhoseBatchFailsTakesTheBoundedReask() throws Exception {
        Fx f = seed(List.of("{\"answer\": \"organization\"}", "{\"answer\": \"organization\"}"));
        try {
            seedBound(f);
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(2, f.model.requests().size());
            List<Long> jobsList = jdbc.queryForList("SELECT (payload->>'reask')::bigint FROM"
                            + " jobs WHERE kind = 'align_types' AND payload->>'kb_id' = ?"
                            + " ORDER BY id", Long.class, f.kb.toString());
            assertEquals(List.of(1L), jobsList,
                    "only the delayed re-ask is queued, got " + jobsList);
            Binding b = binding(f);
            assertNull(b.basis(), "nothing was decided this round");
        } finally {
            cleanup(f);
        }
    }

    /** 嵌入端点一直坏着：检索退回整表，小库照样绑得上（#894）；候选不变指纹不变。 */
    @Test
    void aRetrievalErrorFallsBackToTheWholeClassList() throws Exception {
        Fx f = seed(List.of(vote("organization"), vote("organization")));
        try {
            String chat = jdbc.queryForObject("SELECT s.chat_base_url FROM llm_settings s"
                    + " JOIN knowledge_bases k ON k.workspace_id = s.workspace_id"
                    + " WHERE k.id = ?", String.class, f.kb);
            // upsert 整行覆盖（只有密钥传 None 才保留旧值），对话端点得原样再给一次
            settings.upsert(f.ws, chat, null, "scripted", chat, null, "scripted-embedding", 4);
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(2, f.model.requests().size(), "the fallback still asks");
            Binding b = binding(f);
            assertEquals("bound", b.status());
            assertEquals(f.klass, b.typeId());
            List<Long> jobsList = jdbc.queryForList("SELECT (payload->>'reask')::bigint FROM"
                    + " jobs WHERE kind = 'align_types' AND payload->>'kb_id' = ?", Long.class,
                    f.kb.toString());
            assertTrue(jobsList.isEmpty(), "a settled word queues nothing");
            alignTypes.alignTypesLocked(f.kb, 0);
            assertEquals(2, f.model.requests().size(),
                    "while retrieval keeps failing the candidates, and so the basis, stay the"
                            + " same");
        } finally {
            cleanup(f);
        }
    }
}
