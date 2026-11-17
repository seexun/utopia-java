package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.governance.GovernanceHooks;
import tech.seexun.jknow.web.knowledge.jobs.AdjudicateEntitiesJob;
import tech.seexun.jknow.web.knowledge.jobs.GovernJob;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.SettingsService;

/**
 * 治理任务全链（M4 任务 4 交付测试——Rust 侧 govern 无 server 集成测试，按 m4-plan
 * 「脚本化端点的 govern 全链」补）：脚本化模型端点（攒批走 SSE、带 tools 的第二层走
 * 非流式 JSON）+ 真库自建自拆，直调 {@link GovernJob#govern}（不经队列——共享栈 Rust
 * worker 会消费同名 kind，与 AdjudicationAgentTests 同口径）。
 *
 * <p>覆盖：无模型/开关关的原地等待、Propose 落库（question/trace/calls/precedents
 * JSON）、Apply same 合并（governed|{conf} + 台账 via=governor）、Apply different kept、
 * 执行闸门拦（held for a person）、settled_by_people 快路不问模型（端点计数）、轮数
 * 用完再排、循环预算用尽只写建议、相似提的 same 第二眼没跑成上交（#889）、保险丝
 * 端到端（两撤回→trip→告警一次）、BaseLock 抢不到 60s 再排（等而不跳）。
 */
class GovernAgentTests extends HeartStoreTestBase {

    @Autowired
    GovernJob govern;

    @Autowired
    GovernanceHooks hooks;

    @Autowired
    GovernanceService governance;

    @Autowired
    SettingsService settings;

    /** 攒批答复（SSE）：idx 从 0 起，与簇内待问对的顺序一致；置信度一律 0.9。 */
    private static String verdicts(String... entries) {
        StringBuilder json = new StringBuilder("{\"verdicts\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"i\":").append(i).append(",\"verdict\":\"").append(entries[i])
                    .append("\",\"confidence\":0.9,\"why\":\"because\"}");
        }
        return json.append("]}").toString();
    }

    /** 第二层的一个工具回合（非流式 choices[0].message）。 */
    private static String toolCall(String id, String name, String argsJson) {
        return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                + "\",\"arguments\":" + com.fasterxml.jackson.databind.node.TextNode
                        .valueOf(argsJson).toString() + "}}]}";
    }

    private static final class Fx {
        final UUID org;
        final UUID ws;
        final UUID kb;
        final UUID user;
        final UUID personType;
        final ScriptedModelServer model;

        Fx(UUID org, UUID ws, UUID kb, UUID user, UUID personType, ScriptedModelServer model) {
            this.org = org;
            this.ws = ws;
            this.kb = kb;
            this.user = user;
            this.personType = personType;
            this.model = model;
        }
    }

    /** 治理开关开着 + 可选脚本模型（null = 不配模型）。 */
    private Fx seed(List<String> replies, List<String> toolTurns) throws java.io.IOException {
        // 自愈：名字空间内清掉先前异常中断留下的库与其悬空 govern 任务（250 实体会
        // 盖过共享示例库的「实体最多」探针，拖坏 GraphStoreTests 这类按数据取 demoKb
        // 的测试；任务先删——kb 行没了就再也对不上号，JVM 中途被杀后它会一直在共享栈
        // 被 worker 反复重试）
        jdbc.update("""
                DELETE FROM jobs WHERE kind = 'govern' AND payload->>'kb_id' IN
                 (SELECT id::text FROM knowledge_bases WHERE name = 'govern-agent-test')
                """);
        jdbc.update("DELETE FROM knowledge_bases WHERE name = 'govern-agent-test'");
        jdbc.update("DELETE FROM organizations WHERE name = 'govern-agent-test'");
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID personType = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'govern-agent-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'govern-agent-test')",
                ws, org);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Governor')
                """, user, org, "governor-" + user + "@test.local");
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, governance)"
                + " VALUES (?, ?, 'govern-agent-test', TRUE)", kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                personType, kb);
        ScriptedModelServer model = null;
        if (replies != null) {
            model = ScriptedModelServer.start(replies, toolTurns == null ? List.of() : toolTurns);
            settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        }
        return new Fx(org, ws, kb, user, personType, model);
    }

    private UUID entity(Fx f, String name) {
        return entity(f, name, f.personType);
    }

    private UUID entity(Fx f, String name, UUID type) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb, type, name);
        return id;
    }

    /** 等人的对：stage='human'（queue 只挑没在裁的）。 */
    private UUID review(Fx f, UUID left, UUID right, String reason, int createdSecondsAgo) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, reason, stage, created_at)
                 VALUES (?, ?, ?, ?, 0.78, ?, 'human', now() - make_interval(secs => ?))
                """, id, f.kb, left, right, reason, createdSecondsAgo);
        return id;
    }

    /** 人裁过的旧行（decided_before 的先例）：同一对实体，decided_by 非空。 */
    private void priorDecision(Fx f, UUID left, UUID right, String status) {
        jdbc.update("""
                INSERT INTO resolution_reviews
                    (id, kb_id, left_id, right_id, score, stage, status, decided_at, decided_by)
                 VALUES (?, ?, ?, ?, 0.9, 'human', ?, now() - interval '1 hour', ?)
                """, Uuids.nowV7(), f.kb, left, right, status, f.user);
    }

    /** attested_from 显式 NULL（列有 DEFAULT now()——时间轴语义从简，与事实无关的边）。 */
    private void fact(Fx f, UUID subject, UUID predicate, UUID object) {
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, attested_from)
                 VALUES (?, ?, ?, ?, ?, NULL)
                """, Uuids.nowV7(), f.kb, subject, predicate, object);
    }

    private void cleanup(Fx f) {
        // 先删我们排下的 govern 任务：kb 即将删除，别让共享栈的 worker 拿到悬空载荷
        jdbc.update("DELETE FROM jobs WHERE kind = 'govern' AND payload->>'kb_id' = ?",
                f.kb.toString());
        if (f.model != null) {
            f.model.stop();
        }
        // kb 先删（reviews 的 decided_by 挂着用户——kb 级联先走掉，org 再级联用户）
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb);
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    private String reviewReason(UUID reviewId) {
        return jdbc.queryForObject(
                "SELECT reason FROM resolution_reviews WHERE id = ?", String.class, reviewId);
    }

    private String reviewStatus(UUID reviewId) {
        return jdbc.queryForObject(
                "SELECT status FROM resolution_reviews WHERE id = ?", String.class, reviewId);
    }

    /** 这一对上的 agent 决定（最近一条）。 */
    private java.util.Map<String, Object> decision(UUID reviewId) {
        return jdbc.queryForMap(
                "SELECT action, confidence, reason, status, merge_id, question, calls, trace,"
                        + " jsonb_typeof(precedents) AS precedents_kind FROM agent_decisions"
                        + " WHERE target_id = ? ORDER BY created_at DESC LIMIT 1", reviewId);
    }

    // ------------------------------------------------------------------

    /** 无模型：任务成功结束、什么都不动——队列原地等（没有依据的裁决不如不裁）。 */
    @Test
    void withoutAModelTheQueueWaitsWhereItIs() throws Exception {
        Fx f = seed(null, null);
        try {
            UUID r = review(f, entity(f, "Acme"), entity(f, "Acme Ltd"), "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("pending", reviewStatus(r));
            assertEquals("human", jdbc.queryForObject(
                    "SELECT stage FROM resolution_reviews WHERE id = ?", String.class, r));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM agent_decisions WHERE kb_id = ?", Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }

    /** 开关关着：直接成功返回，不问模型、不建任何行。 */
    @Test
    void aBaseWithGovernanceOffIsSkippedImmediately() throws Exception {
        Fx f = seed(List.of(), null);
        try {
            jdbc.update("UPDATE knowledge_bases SET governance = FALSE WHERE id = ?", f.kb);
            UUID r = review(f, entity(f, "Acme"), entity(f, "Acme Ltd"), "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("pending", reviewStatus(r));
            assertEquals(0, f.model.requests().size());
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM agent_decisions WHERE kb_id = ?", Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }

    /**
     * Propose 落库：第一层 unsure → 第二层查一眼事实、defers——转人工留一个具体的问题，
     * agent_decisions 带 question/trace/calls/precedents JSON。
     */
    @Test
    void aDeferLeavesAQuestionForPeople() throws Exception {
        Fx f = seed(
                List.of("{\"verdicts\":[{\"i\":0,\"verdict\":\"unsure\",\"confidence\":0.5,"
                        + "\"why\":\"facts point both ways\"}]}"),
                List.of(
                        toolCall("c1", "facts", "{\"side\":\"A\"}"),
                        toolCall("c2", "defer",
                                "{\"question\":\"Is the Beijing CFO the same person?\"}")));
        try {
            UUID r = review(f, entity(f, "Zhang Wei"), entity(f, "Zhang Wei"),
                    "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("pending", reviewStatus(r));
            assertEquals("proposed", reviewReason(r));
            assertEquals("human", jdbc.queryForObject(
                    "SELECT stage FROM resolution_reviews WHERE id = ?", String.class, r));
            var d = decision(r);
            assertEquals("proposed", d.get("status"));
            assertEquals("unsure", d.get("action"));
            assertEquals("Is the Beijing CFO the same person?", d.get("question"));
            assertEquals(2, ((Number) d.get("calls")).intValue(), "两回合：查一次、defer 一次");
            assertEquals("array", d.get("precedents_kind"), "先例 JSON 原样落行（空也是数组）");
            String trace = d.get("trace").toString();
            assertTrue(trace.contains("facts"), trace);
            assertTrue(trace.contains("0 facts of A"), "note 进 trace：" + trace);
            assertEquals(3, f.model.requests().size(), "攒批一次 + 第二层两回合");
            assertTrue(f.model.requests().get(1).has("tools"), "第二层带工具清单");
            assertFalse(f.model.requests().get(0).has("tools"), "攒批不带工具");
        } finally {
            cleanup(f);
        }
    }

    /** Apply same：过闸自己动手——governed|{conf:.2}、决策行 applied 带 merge_id、台账 via=governor。 */
    @Test
    void aConfidentSameMergesUnderGovernance() throws Exception {
        Fx f = seed(List.of(verdicts("same")), List.of());
        try {
            UUID a = entity(f, "OpenAI");
            UUID b = entity(f, "OpenAI");
            UUID r = review(f, a, b, "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("merged", reviewStatus(r));
            assertEquals("governed|0.90", reviewReason(r));
            var d = decision(r);
            assertEquals("applied", d.get("status"));
            assertEquals("merge", d.get("action"));
            assertTrue(d.get("merge_id") != null, "merge_id 非空——撤回走它");
            assertEquals("governed|0.90", jdbc.queryForObject(
                    "SELECT reason FROM entity_merges WHERE kb_id = ?", String.class, f.kb));
            var audit = jdbc.queryForMap(
                    "SELECT actor_id, detail->>'via' AS via, detail->>'decision' AS decision"
                            + " FROM audit_events WHERE kb_id = ? AND action = 'review.merge'",
                    f.kb);
            assertNull(audit.get("actor_id"), "actor 为空 = 机器");
            assertEquals("governor", audit.get("via"));
            assertEquals(1, f.model.requests().size(), "高置信不进第二层");
        } finally {
            cleanup(f);
        }
    }

    /** Apply different：分开可再合——关行 kept，决策行 applied。 */
    @Test
    void aConfidentDifferentKeepsThemApartUnderGovernance() throws Exception {
        Fx f = seed(List.of(verdicts("different")), List.of());
        try {
            UUID r = review(f, entity(f, "Delta Air Lines"), entity(f, "Delta Faucet Company"),
                    "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("kept", reviewStatus(r));
            assertEquals("governed|0.90", reviewReason(r));
            assertEquals("applied", decision(r).get("status"));
            assertEquals("keep", decision(r).get("action"));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_events WHERE kb_id = ? AND action = 'review.keep'",
                    Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }

    /** 执行闸门（0027）：合并会立刻开出矛盾——把握再高也不动手，理由写明是闸门留下的。 */
    @Test
    void aMergeThatWouldBreakAnAxiomIsHeldForAPerson() throws Exception {
        Fx f = seed(List.of(verdicts("same")), List.of());
        try {
            UUID ceoOf = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, functional)
                     VALUES (?, ?, 'ceo_of', 'CEO of', TRUE)
                    """, ceoOf, f.kb);
            UUID a = entity(f, "Acme");
            UUID b = entity(f, "Acme Corp");
            fact(f, a, ceoOf, entity(f, "Alice"));
            fact(f, b, ceoOf, entity(f, "Bob"));
            UUID r = review(f, a, b, "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("escalate_impact|contradiction CEO of", reviewReason(r));
            assertEquals("pending", reviewStatus(r));
            var d = decision(r);
            assertEquals("proposed", d.get("status"));
            assertTrue(d.get("reason").toString()
                            .startsWith("held for a person: merging would put two \"CEO of\""),
                    () -> d.get("reason").toString());
            assertTrue(d.get("reason").toString().contains("; because"),
                    "模型的一句理由跟在闸门的话后面");
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND merged_into IS NOT NULL",
                    Long.class, f.kb), "闸门拦住的合并一个也没落地");
        } finally {
            cleanup(f);
        }
    }

    /** settled_by_people 快路：人裁过这一对实体——照人的定，不问模型（端点零请求）。 */
    @Test
    void aPairPeopleAlreadyDecidedIsCopiedWithoutAskingTheModel() throws Exception {
        Fx f = seed(List.of(), List.of());
        try {
            UUID a = entity(f, "Nebula Systems");
            UUID b = entity(f, "Nebula Systems");
            priorDecision(f, a, b, "merged");
            UUID r = review(f, a, b, "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("merged", reviewStatus(r), "人裁过合——agent 照办");
            assertEquals("governed|1.00", reviewReason(r));
            var d = decision(r);
            assertEquals("applied", d.get("status"));
            assertEquals("a person decided this same pair before", d.get("reason"));
            assertEquals(0, f.model.requests().size(), "没问过模型");
        } finally {
            cleanup(f);
        }
    }

    /**
     * 轮数用完还有积压：再排一个。250 个共享一个实体的对全部有人的先例（快路，不花
     * 模型）——每轮一个簇 12 对，20 轮走 240，剩 10 个与一条新排的 govern 任务。
     */
    @Test
    void roundsExhaustedRequeueTheRest() throws Exception {
        Fx f = seed(List.of(), List.of());
        try {
            UUID shared = entity(f, "Vertex");
            int total = 20 * 12 + 10;
            for (int i = 0; i < total; i++) {
                UUID other = entity(f, "Partner " + i);
                priorDecision(f, shared, other, "kept");
                review(f, shared, other, "ambiguous_name|0.41", 3600 - i);
            }
            long before = System.currentTimeMillis();
            govern.govern(f.kb);
            assertTrue(System.currentTimeMillis() - before < 300_000, "快路要跑得完");
            assertEquals(10L, jdbc.queryForObject(
                    "SELECT count(*) FROM resolution_reviews WHERE kb_id = ? AND status = 'pending'",
                    Long.class, f.kb), "20 轮 × 12 对走完，剩 10 个");
            assertEquals(0, f.model.requests().size(), "全走人的先例，模型零调用");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM jobs WHERE kind = 'govern' AND payload->>'kb_id' = ?",
                    Long.class, f.kb.toString()), "轮数用完再排一个接着走");
        } finally {
            cleanup(f);
        }
    }

    /** 循环预算用尽：第二层不跑，判不定的对照第一刀写成建议（攒批照问）。 */
    @Test
    void theDailyLoopBudgetSpentProposesInsteadOfLooking() throws Exception {
        Fx f = seed(
                List.of("{\"verdicts\":[{\"i\":0,\"verdict\":\"same\",\"confidence\":0.5,"
                        + "\"why\":\"the names match\"}]}"),
                List.of());
        try {
            // 今天已花满预算：一条 calls=2000 的旧行
            jdbc.update("""
                    INSERT INTO agent_decisions
                        (id, kb_id, run_id, target_kind, target_id, action, confidence, status, calls)
                     VALUES (?, ?, ?, 'review', ?, 'keep', 0, 'accepted', 2000)
                    """, Uuids.nowV7(), f.kb, Uuids.nowV7(), Uuids.nowV7());
            UUID r = review(f, entity(f, "Phoenix Labs"), entity(f, "Phoenix Laboratory"),
                    "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("proposed", reviewReason(r));
            var d = decision(r);
            assertEquals("proposed", d.get("status"));
            assertEquals("merge", d.get("action"), "第一刀说了 same，只是没把握");
            assertEquals(0, ((Number) d.get("calls")).intValue(), "第二层没跑");
            assertEquals(1, f.model.requests().size(), "只问了攒批");
            assertFalse(f.model.requests().get(0).has("tools"));
        } finally {
            cleanup(f);
        }
    }

    /**
     * #889 钉子（治理这条路同判）：名字向量提的对说 same，第二眼没跑成（端点 500）——
     * 过闸也不合，上交 SECOND_LOOK_UNAVAILABLE，看法照记成建议。
     */
    @Test
    void aSimilarityProposedSameWithoutTheSecondLookIsHeldForAPerson() throws Exception {
        Fx f = seed(List.of(verdicts("same")), List.of());
        try {
            UUID r = review(f, entity(f, "Zhang Wei"), entity(f, "Zhang Wei the CFO"),
                    "name_vector|0.91", 60);
            govern.govern(f.kb);
            assertEquals(AdjudicateEntitiesJob.SECOND_LOOK_UNAVAILABLE, reviewReason(r));
            assertEquals("pending", reviewStatus(r));
            var d = decision(r);
            assertEquals("proposed", d.get("status"));
            assertEquals("held for a person: the names are similar, not the same string,"
                    + " and the second look did not run", d.get("reason"));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND merged_into IS NOT NULL",
                    Long.class, f.kb), "没合");
            assertEquals(2, f.model.requests().size(), "攒批一次 + 第二层没跑成的那一次");
            assertTrue(f.model.requests().get(1).has("tools"));
        } finally {
            cleanup(f);
        }
    }

    /**
     * 保险丝端到端：govern 自己合了两对 → 人各撤回一次 → 第二次撤回时 trip（开关关、
     * 告警恰好一条、台账 kb.updated via=fuse）。第一次撤回后 reverts=1 不跳——只有两笔
     * 才关（0025 决定 9）。
     */
    @Test
    void theFuseTripsAfterTwoReverts() throws Exception {
        Fx f = seed(List.of(verdicts("same"), verdicts("same")), List.of());
        try {
            UUID r1 = review(f, entity(f, "Polar Corp"), entity(f, "Polar Corp"),
                    "ambiguous_name|0.41", 120);
            UUID r2 = review(f, entity(f, "Aurora Ltd"), entity(f, "Aurora Ltd"),
                    "ambiguous_name|0.41", 60);
            govern.govern(f.kb);
            assertEquals("merged", reviewStatus(r1));
            assertEquals("merged", reviewStatus(r2));
            UUID merge1 = (UUID) decision(r1).get("merge_id");
            UUID merge2 = (UUID) decision(r2).get("merge_id");
            assertTrue(merge1 != null && merge2 != null);

            hooks.afterRevert(f.kb, merge1, f.user);
            assertTrue(jdbc.queryForObject(
                    "SELECT governance FROM knowledge_bases WHERE id = ?", Boolean.class, f.kb),
                    "一笔撤回不跳闸");
            hooks.afterRevert(f.kb, merge2, f.user);
            assertFalse(jdbc.queryForObject(
                    "SELECT governance FROM knowledge_bases WHERE id = ?", Boolean.class, f.kb),
                    "两笔撤回关开关");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM alerts WHERE kb_id = ? AND kind = 'governance.tripped'",
                    Long.class, f.kb), "告警恰好一条");
            assertEquals("fuse", jdbc.queryForObject(
                    "SELECT detail->>'via' FROM audit_events WHERE kb_id = ?"
                            + " AND action = 'kb.updated'", String.class, f.kb));
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT count(*) FROM agent_decisions WHERE kb_id = ? AND status = 'reverted'",
                    Long.class, f.kb), "agent 的两笔 applied 都记成 reverted");
        } finally {
            cleanup(f);
        }
    }

    /** BaseLock 抢不到：等而不跳——隔一分钟再排一个，本任务成功返回、队列不动。 */
    @Test
    void waitingForTheBaseLockRequeuesInAMinute() throws Exception {
        Fx f = seed(List.of(), List.of());
        try {
            UUID r = review(f, entity(f, "Acme"), entity(f, "Acme Ltd"), "ambiguous_name|0.41", 60);
            GovernanceService.BaseLock held = governance.tryLockBase(f.kb);
            assertTrue(held != null, "测试先占住这个库的治理锁");
            try {
                govern.govern(f.kb);
                OffsetDateTime runAt = jdbc.queryForObject(
                        "SELECT run_at FROM jobs WHERE kind = 'govern' AND payload->>'kb_id' = ?",
                        OffsetDateTime.class, f.kb.toString());
                assertTrue(runAt != null, "抢不到锁就再排一个");
                OffsetDateTime now = OffsetDateTime.now();
                assertTrue(runAt.isAfter(now.plusSeconds(50))
                        && runAt.isBefore(now.plusSeconds(80)),
                        "排在约一分钟之后：" + runAt);
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT count(*) FROM jobs WHERE kind = 'govern' AND payload->>'kb_id' = ?",
                        Long.class, f.kb.toString()));
                assertEquals("pending", reviewStatus(r), "队列原地等，不升人工也不裁");
                assertEquals(0, f.model.requests().size());
            } finally {
                held.release();
            }
        } finally {
            cleanup(f);
        }
    }
}
