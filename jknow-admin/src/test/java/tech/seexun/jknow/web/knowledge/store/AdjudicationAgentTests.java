package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.jobs.AdjudicateEntitiesJob;
import tech.seexun.jknow.store.SettingsService;

/**
 * 自动裁决 agent 全链（M4 任务 3 交付测试——Rust 侧无同款 server 集成测试，按
 * m4-plan「脚本化端点的裁决全链测试」补）：脚本化 SSE 模型端点 + 真库自建自拆，
 * 直调 {@link AdjudicateEntitiesJob#adjudicate}（不经队列——共享栈 Rust worker 会
 * 消费同名 kind；断言窗口内不让它插手，与 ExtractionOpenKnownFlowTests 同口径）。
 *
 * <p>覆盖七条主链 + 两条钉子：无模型全升 human（escalate_no_model）/缓存命中第二轮
 * 不问模型（脚本端点计数断言）/执行闸门拦（矛盾 → escalate_impact 不合并）/same 高置信
 * 自动合并（review merged + audit auto_merged + entity_merges reason）/different 高置信
 * kept_apart/unsure 低置信 escalate_unsure/同批连锁 Conflict（escalate_entity_changed）；
 * 钉子：相似提的 same 第二眼不可用 → SECOND_LOOK_UNAVAILABLE 不落地不进缓存（#889）、
 * PER_KB 串行等而不跳。
 */
class AdjudicationAgentTests extends HeartStoreTestBase {

    @Autowired
    AdjudicateEntitiesJob adjudicate;

    @Autowired
    SettingsService settings;

    /** 模型答复：idx 从 0 起，与攒批里 to_ask 的顺序（created_at 先后）一致。 */
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

    private static final class Fx {
        final UUID org;
        final UUID ws;
        final UUID kb;
        final UUID person;
        final ScriptedModelServer model;

        Fx(UUID org, UUID ws, UUID kb, UUID person, ScriptedModelServer model) {
            this.org = org;
            this.ws = ws;
            this.kb = kb;
            this.person = person;
            this.model = model;
        }
    }

    /** 无模型的库（llm_settings 不建行——传 null 即 bare）。 */
    private Fx seedBare() throws java.io.IOException {
        return seed(null);
    }

    private Fx seed(List<String> replies) throws java.io.IOException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'adjudication-agent-test')",
                org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'adjudication-agent-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'adjudication-agent-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        ScriptedModelServer model = null;
        if (replies != null) {
            model = ScriptedModelServer.start(replies);
            settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        }
        return new Fx(org, ws, kb, person, model);
    }

    private UUID entity(Fx f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb, f.person, name);
        return id;
    }

    /** created_at 显式给序（pending_adjudications ORDER BY created_at——同批两对的先后要钉死）。 */
    private UUID review(Fx f, UUID left, UUID right, String reason, int createdSecondsAgo) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, reason, stage, created_at)
                 VALUES (?, ?, ?, ?, 0.78, ?, 'adjudicating', now() - make_interval(secs => ?))
                """, id, f.kb, left, right, reason, createdSecondsAgo);
        return id;
    }

    /** attested_from 显式 NULL（列有 DEFAULT now()——带上时间轴的两行按接替排，不再是矛盾）。 */
    private void fact(Fx f, UUID subject, UUID predicate, UUID object) {
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, attested_from)
                 VALUES (?, ?, ?, ?, ?, NULL)
                """, Uuids.nowV7(), f.kb, subject, predicate, object);
    }

    private void cleanup(Fx f) {
        if (f.model != null) {
            f.model.stop();
        }
        // 组织级联：workspaces/kbs/entities/facts/reviews/verdicts/audit 全随 org 走
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    private String reviewReason(UUID reviewId) {
        return jdbc.queryForObject(
                "SELECT reason FROM resolution_reviews WHERE id = ?", String.class, reviewId);
    }

    /** 无模型：全部转人工，任务本身成功结束（缺席不影响抽取与查询）。 */
    @Test
    void withoutAModelEverythingGoesToPeople() throws Exception {
        Fx f = seedBare();
        try {
            UUID r1 = review(f, entity(f, "Zhang San"), entity(f, "Zhang San"),
                    "ambiguous_name|0.41", 60);
            UUID r2 = review(f, entity(f, "Acme"), entity(f, "Acme Ltd"),
                    "namesake|0.51", 30);
            adjudicate.adjudicate(f.kb);
            for (UUID id : List.of(r1, r2)) {
                assertEquals("escalate_no_model", reviewReason(id));
                assertEquals("human", jdbc.queryForObject(
                        "SELECT stage FROM resolution_reviews WHERE id = ?", String.class, id));
                assertEquals("pending", jdbc.queryForObject(
                        "SELECT status FROM resolution_reviews WHERE id = ?", String.class, id));
            }
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM resolution_verdicts WHERE kb_id = ?", Long.class, f.kb),
                    "没问过模型，也就没有缓存");
        } finally {
            cleanup(f);
        }
    }

    /** 缓存命中：同一对名字再入队（新实体行），第二轮不问模型照旧自动合并。 */
    @Test
    void aVerdictIsAskedOncePerPairKey() throws Exception {
        Fx f = seed(List.of(verdicts("same")));
        try {
            UUID first = review(f, entity(f, "Ivan Petrov"), entity(f, "Ivan Petrov"),
                    "ambiguous_name|0.41", 60);
            adjudicate.adjudicate(f.kb);
            assertEquals("merged", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, first));
            assertEquals(1, f.model.requests().size(), "第一轮问了一次");

            // 新的实体行、同一对名字（重传文档的形态）——键相同，命中缓存
            UUID second = review(f, entity(f, "Ivan Petrov"), entity(f, "Ivan Petrov"),
                    "ambiguous_name|0.41", 30);
            adjudicate.adjudicate(f.kb);
            assertEquals("merged", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, second));
            assertEquals("auto_merged|cached 0.90", reviewReason(second),
                    "via=cached：照旧自动合并，但来路记的是缓存");
            assertEquals(1, f.model.requests().size(), "第二轮没有再问模型");
        } finally {
            cleanup(f);
        }
    }

    /** 执行闸门（0027）：合并会立刻开出违规——矛盾（functional 谓词两边各挂一个）。 */
    @Test
    void aMergeThatWouldBreakAnAxiomIsHeldForAPerson() throws Exception {
        Fx f = seed(List.of(verdicts("same")));
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
            UUID review = review(f, a, b, "ambiguous_name|0.41", 60);
            adjudicate.adjudicate(f.kb);
            assertEquals("escalate_impact|contradiction CEO of", reviewReason(review),
                    "人看到的是留下的原因，不是「裁决器没把握」");
            assertEquals("human", jdbc.queryForObject(
                    "SELECT stage FROM resolution_reviews WHERE id = ?", String.class, review));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND merged_into IS NOT NULL",
                    Long.class, f.kb), "闸门拦住的合并一个也没落地");
        } finally {
            cleanup(f);
        }
    }

    /** same 高置信：自动合并——review 行 merged、entity_merges 记来路、台账记系统行。 */
    @Test
    void aConfidentSameMergesOnItsOwn() throws Exception {
        Fx f = seed(List.of(verdicts("same")));
        try {
            UUID a = entity(f, "OpenAI");
            UUID b = entity(f, "OpenAI");
            UUID review = review(f, a, b, "ambiguous_name|0.41", 60);
            adjudicate.adjudicate(f.kb);
            assertEquals("merged", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, review));
            assertEquals("auto_merged|adjudicated 0.90", reviewReason(review));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE id = ANY(ARRAY[?,?]::uuid[]) "
                            + "AND merged_into IS NOT NULL", Long.class, a, b),
                    "一对里恰好一个被并进另一个");
            assertEquals("auto_merged|adjudicated 0.90", jdbc.queryForObject(
                    "SELECT reason FROM entity_merges WHERE kb_id = ?", String.class, f.kb));
            assertEquals("scripted", jdbc.queryForObject(
                    "SELECT model FROM resolution_verdicts WHERE kb_id = ?", String.class, f.kb),
                    "判决进缓存，模型名跟着走");
            var audit = jdbc.queryForMap(
                    "SELECT actor_id, detail->>'via' AS via FROM audit_events WHERE kb_id = ? "
                            + "AND action = 'review.merge'", f.kb);
            assertNull(audit.get("actor_id"), "actor 为空 = 系统");
            assertEquals("adjudicated", audit.get("via"), "detail 带来路");
        } finally {
            cleanup(f);
        }
    }

    /** different 高置信：自动保持分开。 */
    @Test
    void aConfidentDifferentKeepsThemApart() throws Exception {
        Fx f = seed(List.of(verdicts("different")));
        try {
            UUID review = review(f, entity(f, "Delta Air Lines"), entity(f, "Delta Faucet Company"),
                    "ambiguous_name|0.41", 60);
            adjudicate.adjudicate(f.kb);
            assertEquals("kept", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, review));
            assertEquals("kept_apart|adjudicated 0.90", reviewReason(review));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_events WHERE kb_id = ? AND action = 'review.keep'",
                    Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }

    /** unsure 低置信：转人工，reason 是 code（escalate_unsure|{via} {conf:.2}）。 */
    @Test
    void anUnsureVerdictEscalatesWithItsConfidence() throws Exception {
        Fx f = seed(List.of(
                "{\"verdicts\":[{\"i\":0,\"verdict\":\"unsure\",\"confidence\":0.5,"
                        + "\"why\":\"facts point both ways\"}]}"));
        try {
            UUID review = review(f, entity(f, "Phoenix Labs"), entity(f, "Phoenix Laboratory"),
                    "ambiguous_name|0.41", 60);
            adjudicate.adjudicate(f.kb);
            assertEquals("pending", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, review));
            assertEquals("human", jdbc.queryForObject(
                    "SELECT stage FROM resolution_reviews WHERE id = ?", String.class, review));
            assertEquals("escalate_unsure|adjudicated 0.50", reviewReason(review));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND merged_into IS NOT NULL",
                    Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }

    /**
     * 同批连锁：两对共享一个实体——第一对合并后第二对的一侧已被吞掉，
     * Conflict 不是失败，转人工（escalate_entity_changed）。
     */
    @Test
    void aChainMergeInTheSameBatchHandsTheSecondPairToPeople() throws Exception {
        Fx f = seed(List.of(verdicts("same", "same")));
        try {
            // A 的事实多一度 → A 存活，B 并进 A；第二对 B-C 里的 B 已不是活行
            UUID ceoOf = Uuids.nowV7();
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_at', 'works at')",
                    ceoOf, f.kb);
            UUID a = entity(f, "Global Corp");
            UUID b = entity(f, "Global Corp");
            UUID c = entity(f, "Global Corporation");
            fact(f, a, ceoOf, entity(f, "Somewhere"));
            UUID first = review(f, a, b, "ambiguous_name|0.41", 60);
            UUID second = review(f, b, c, "ambiguous_name|0.41", 30);
            adjudicate.adjudicate(f.kb);
            assertEquals("merged", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, first));
            assertEquals("pending", jdbc.queryForObject(
                    "SELECT status FROM resolution_reviews WHERE id = ?", String.class, second));
            assertEquals("escalate_entity_changed", reviewReason(second),
                    "连锁吞掉的一方：转人工而不是让任务失败");
        } finally {
            cleanup(f);
        }
    }

    /**
     * #889 钉子：名字向量提的对（0041 第 2 刀）批量说 same 再有把握也不能不经第二眼
     * 落地——第二眼没跑成就上交，且那个 same 不进缓存：进缓存的话这一对每次都从缓存
     * 直接合，第二眼永远轮不到。M4 任务 4 起 SecondLookPort 已被 GovernJob 真身接管
     * （任务 3 期的 UNAVAILABLE 缺省退位）：本例实际路径是 GovernJob 的 investigate
     * 打在空工具脚本上 → 端点 500 → REJECTED → secondLook 回 empty（断言不变）。
     */
    @Test
    void aSimilarityProposedSameNeverLandsOrCachesWithoutTheSecondLook() throws Exception {
        Fx f = seed(List.of(verdicts("same")));
        try {
            UUID review = review(f, entity(f, "Zhang Wei"), entity(f, "Zhang Wei the CFO"),
                    "name_vector|0.91", 60);
            adjudicate.adjudicate(f.kb);
            assertEquals(AdjudicateEntitiesJob.SECOND_LOOK_UNAVAILABLE, reviewReason(review));
            assertEquals("human", jdbc.queryForObject(
                    "SELECT stage FROM resolution_reviews WHERE id = ?", String.class, review));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND merged_into IS NOT NULL",
                    Long.class, f.kb), "没合");
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM resolution_verdicts WHERE kb_id = ?",
                    Long.class, f.kb), "也不进缓存");
        } finally {
            cleanup(f);
        }
    }

    /**
     * PER_KB 串行等而不跳（adjudication.rs:137-148 的实测事故）：同一库的第二个任务
     * 等前一个跑完再进——不跳过、不失败；进来时重查待裁项。
     */
    @Test
    void theSameBaseIsAdjudicatedOneRunAtATime() throws Exception {
        Fx f = seed(List.of(verdicts("same")));
        try {
            review(f, entity(f, "Omega Fund"), entity(f, "Omega Fund"),
                    "ambiguous_name|0.41", 60);
            // 测试线程先占住这一库的锁：另一线程的裁决必须等——模型不被问到就不是自己先跑了
            java.util.concurrent.locks.ReentrantLock gate = AdjudicateEntitiesJob.lockFor(f.kb);
            gate.lock();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread second = new Thread(() -> {
                try {
                    adjudicate.adjudicate(f.kb);
                } catch (Throwable t) {
                    failed.set(t);
                } finally {
                    done.countDown();
                }
            });
            second.start();
            Thread.sleep(600);
            assertTrue(second.isAlive(), "第二个任务在等这一库的锁，不跳过也不失败");
            assertEquals(0, f.model.requests().size(), "还没轮到它，模型一次都没被问");
            gate.unlock();
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "放行后要能跑完");
            assertNull(failed.get(), "放行后照常跑完");
            assertEquals(1, f.model.requests().size(), "进来时重查待裁项，问了一次");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM resolution_reviews WHERE kb_id = ? AND status = 'merged'",
                    Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }
}
