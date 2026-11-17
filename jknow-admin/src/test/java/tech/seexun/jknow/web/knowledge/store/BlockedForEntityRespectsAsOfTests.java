package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/blocked_for_entity_respects_as_of.rs（230 行，1 例）——没落地的派生
 * 那一档的记录轴（0019 / #549 / #307）：三月被推翻的违规在回放的面板上不该留着幽灵边。
 * violation_open_at 的双支谓词（detected_at &lt;= T 且 (open 或 decided_at &gt; T)）。
 * 断言逐条对译。
 */
class BlockedForEntityRespectsAsOfTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    @Test
    void blockedForEntityRespectsAsOf() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID personType = Uuids.nowV7();
        UUID mira = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID worksAt = Uuids.nowV7();
        UUID ceoOf = Uuids.nowV7();
        UUID worksAtFact = Uuids.nowV7();
        UUID ceoFactA = Uuids.nowV7();
        UUID ceoFactB = Uuids.nowV7();
        UUID stillOpenViolation = Uuids.nowV7();
        UUID resolvedViolation = Uuids.nowV7();
        try {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'as-of-blocked-test')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'as-of-blocked-test')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'as-of-blocked-test')",
                    kb, ws);
            jdbc.update("""
                    CALL jknow_test_user(?, ?, ?, 'Blocked Tester')
                    """, user, org, "blocked-" + user + "@test.local");
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                    personType, kb);
            for (Object[] row : new Object[][]{{mira, "Mira"}, {acme, "Acme"}}) {
                jdbc.update("""
                        INSERT INTO entities (id, kb_id, canonical_name, created_at)
                         VALUES (?, ?, ?, ?)
                        """, row[0], kb, row[1], t("2026-01-01T00:00:00Z"));
            }
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_at', 'works at')",
                    worksAt, kb);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'ceo_of', 'ceo of')",
                    ceoOf, kb);
            // Mira 上的断言事实：被两条违规拿来当 left_fact
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, worksAtFact, kb, mira, worksAt, acme);
            // 派生事实：被两条违规拿来当 right_fact（得是事实才能填 FK）
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, ceoFactA, kb, mira, ceoOf, acme);
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, ceoFactB, kb, mira, ceoOf, acme);
            // 「现在还开着」：3 月检测至今未裁
            jdbc.update("""
                    INSERT INTO axiom_violations (id, kb_id, kind, left_fact, right_fact, status,
                                                  detail, detected_at)
                     VALUES (?, ?, 'derived_contradiction', ?, ?, 'open',
                             jsonb_build_object('subject_id', ?::text, 'object_id', ?::text,
                                                'predicate', 'works at'),
                             ?)
                    """, stillOpenViolation, kb, worksAtFact, ceoFactA, mira, acme,
                    t("2026-03-15T00:00:00Z"));
            // 「三月检测、四月被人裁掉」：当前 status=resolved，decided_at=4 月
            jdbc.update("""
                    INSERT INTO axiom_violations (id, kb_id, kind, left_fact, right_fact, status,
                                                  resolution, decided_at,
                                                  detail, detected_at)
                     VALUES (?, ?, 'derived_contradiction', ?, ?, 'resolved',
                             'fact_retracted', ?,
                             jsonb_build_object('subject_id', ?::text, 'object_id', ?::text,
                                                'predicate', 'works at'),
                             ?)
                    """, resolvedViolation, kb, worksAtFact, ceoFactB,
                    t("2026-04-15T00:00:00Z"), mira, acme, t("2026-03-20T00:00:00Z"));

            java.util.function.Function<OffsetDateTime, List<UUID>> ids = when -> {
                List<UUID> out = new ArrayList<>();
                for (var row : reasoning.blockedForEntity(kb, mira, when)) {
                    out.add((UUID) row.get("violation_id"));
                }
                out.sort(Comparator.naturalOrder());
                return out;
            };

            // 当下：resolved 那条已裁，open 那条留着 → 只有 still_open
            assertEquals(List.of(stillOpenViolation), ids.apply(null), "当下：open 一条");

            // 2 月：两条都还没检测到 → 都没有
            assertTrue(ids.apply(t("2026-02-01T00:00:00Z")).isEmpty(), "2 月：检测之前都不到");

            // 3 月 25 日：resolved 在 decided_at 之前仍开着；still_open 在 → 两条（排序比）
            List<UUID> lateMarch = new ArrayList<>(List.of(stillOpenViolation, resolvedViolation));
            lateMarch.sort(Comparator.naturalOrder());
            assertEquals(lateMarch, ids.apply(t("2026-03-25T00:00:00Z")),
                    "3 月 25 日：resolved 还在、still_open 还在");

            // 5 月：resolved 在 decided_at 之后消失、still_open 还在 → 一条
            assertEquals(List.of(stillOpenViolation), ids.apply(t("2026-05-01T00:00:00Z")),
                    "5 月：resolved 已裁、still_open 还在");
        } finally {
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", kb);
            jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", user);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
