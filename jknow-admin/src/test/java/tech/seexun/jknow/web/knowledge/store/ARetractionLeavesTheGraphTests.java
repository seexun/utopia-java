package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_retraction_leaves_the_graph.rs（239 行，#202）——「数据错了」要
 * 真的把事实撤掉，队列也得诚实：撤指定的那一条；不在违规里的事实撤不了；单事实的违规
 * 不用说；承诺没兑现就重开（axiom_relaxed），accepted 有意并存、重跑沉默。
 *
 * <p>任务 5 去封：三个 seam 已换 ReasoningService 真身（run/decide/retractFromViolation），
 * 断言一字不动。
 */
class ARetractionLeavesTheGraphTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    record Fx(UUID org, UUID user, UUID kb, UUID reportsTo, UUID etype) {
    }

    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID reportsTo = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'retract-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'retract-test')", ws,
                org);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'r')
                """, user, org, user + "@retract.test");
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'retract-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        // 反对称且非自反：双向断言是矛盾，自环也是
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, is_asymmetric, is_irreflexive)
                 VALUES (?, ?, 'reports_to', 'reports to', TRUE, TRUE)
                """, reportsTo, kb);
        return new Fx(org, user, kb, reportsTo, etype);
    }

    private UUID entity(Fx f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), f.etype(), name);
        return id;
    }

    private UUID fact(Fx f, UUID s, UUID o) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                 VALUES (?, ?, ?, ?, ?, 0.9)
                """, id, f.kb(), s, f.reportsTo(), o);
        return id;
    }

    /** (id, kind, status, resolution, left, right) 按检出时间。 */
    record Violation(UUID id, String kind, String status, String resolution) {
    }

    private List<Violation> violations(Fx f) {
        return jdbc.query("""
                SELECT id, kind, status, resolution FROM axiom_violations
                  WHERE kb_id = ? ORDER BY detected_at, id
                """, (rs, i) -> new Violation(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4)), f.kb());
    }

    private boolean retracted(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM facts WHERE id = ?", Boolean.class, id));
    }

    // ---- reasoning 引擎（任务 5 真身：run/decide/retract_from_violation） ----

    private void reasoningRun(UUID kb) {
        reasoning.run(kb);
    }

    private void reasoningDecide(UUID kb, UUID id, String resolution, UUID user) {
        reasoning.decide(kb, id, resolution, user);
    }

    private UUID retractFromViolation(UUID kb, UUID id, UUID pick, UUID user) {
        return reasoning.retractFromViolation(kb, id, pick, user);
    }

    @Test
    void a_retraction_leaves_the_graph() {
        Fx f = seed();
        try {
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            UUID ab = fact(f, a, b);
            UUID ba = fact(f, b, a);
            reasoningRun(f.kb());
            var rows = violations(f);
            assertEquals(1, rows.size());
            assertEquals("asymmetry", rows.get(0).kind());
            UUID vid = rows.get(0).id();

            // 2. 不在违规里的撤不了；双事实的不说撤哪条也不行
            UUID stranger = fact(f, a, entity(f, "Z"));
            assertThrows(RuntimeException.class,
                    () -> retractFromViolation(f.kb(), vid, stranger, f.user()));
            assertThrows(RuntimeException.class,
                    () -> retractFromViolation(f.kb(), vid, null, f.user()));

            // 1. 撤 B→A：事实作废、违规 resolved、重跑不再报
            UUID gone = retractFromViolation(f.kb(), vid, ba, f.user());
            assertEquals(ba, gone);
            assertTrue(retracted(ba), "the button does what it says");
            assertTrue(!retracted(ab), "the other fact stays");
            reasoningRun(f.kb());
            var resolvedRows = violations(f);
            assertEquals(1, resolvedRows.size());
            assertEquals("resolved", resolvedRows.get(0).status());
            assertEquals("fact_retracted", resolvedRows.get(0).resolution());

            // 3. 自环只有一条事实，不用说撤哪条
            UUID e = entity(f, "E");
            UUID ee = fact(f, e, e);
            reasoningRun(f.kb());
            UUID loopId = violations(f).stream().filter(v -> "self_loop".equals(v.kind()))
                    .findFirst().orElseThrow().id();
            assertEquals(ee, retractFromViolation(f.kb(), loopId, null, f.user()));
            assertTrue(retracted(ee));

            // 4. 承诺没兑现就重开：axiom_relaxed 后本体没改，重跑回到 open；accepted 沉默
            UUID c = entity(f, "C");
            UUID d = entity(f, "D");
            fact(f, c, d);
            fact(f, d, c);
            reasoningRun(f.kb());
            UUID cdId = violations(f).stream().filter(v -> "open".equals(v.status())).findFirst()
                    .orElseThrow(() -> new AssertionError("the new pair is open")).id();
            reasoningDecide(f.kb(), cdId, "axiom_relaxed", f.user());
            reasoningRun(f.kb());
            Violation row = violations(f).stream().filter(v -> v.id().equals(cdId)).findFirst()
                    .orElseThrow();
            assertEquals("open", row.status());
            assertEquals(null, row.resolution(),
                    "the axiom is still declared, so the promise did not hold");
            reasoningDecide(f.kb(), cdId, "accepted", f.user());
            reasoningRun(f.kb());
            Violation row2 = violations(f).stream().filter(v -> v.id().equals(cdId)).findFirst()
                    .orElseThrow();
            assertEquals("resolved", row2.status(),
                    "accepted means both stand; the queue stays quiet");
        } finally {
            // 先拆库再拆组织：裁决行的 decided_by 指着这个组织的用户，而删组织时
            // 用户与库的级联谁先谁后不保证（a_cycle 系测试同一处理）
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
