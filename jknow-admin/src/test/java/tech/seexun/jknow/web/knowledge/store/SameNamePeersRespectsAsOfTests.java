package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/same_name_peers_respects_as_of.rs（341 行，2 例）——同名列的记录轴
 * （#336）：三月并掉的实体在 as_of 早于三月时重新出现（entity_visible_at）；度数也倒回
 * 当时谁持有事实（owner_at）。第二例走真 merge_entities。断言逐条对译。
 */
class SameNamePeersRespectsAsOfTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    GraphService graph;
    @Autowired
    ResolutionService resolution;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    @Test
    void sameNamePeersRespectsAsOf() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID personType = Uuids.nowV7();
        UUID zhangWeiA = Uuids.nowV7();
        UUID zhangWeiB = Uuids.nowV7();
        UUID zhangWeiC = Uuids.nowV7();
        UUID projectEntity = Uuids.nowV7();
        UUID otherEntity = Uuids.nowV7();
        UUID worksAt = Uuids.nowV7();
        UUID f_a_old = Uuids.nowV7();
        UUID f_b = Uuids.nowV7();
        UUID f_c_new = Uuids.nowV7();
        UUID mergeId = Uuids.nowV7();
        try {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'as-of-peers-test')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'as-of-peers-test')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'as-of-peers-test')",
                    kb, ws);
            jdbc.update("""
                    CALL jknow_test_user(?, ?, ?, 'Zhang Wei')
                    """, user, org, "peers-" + user + "@test.local");
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                    personType, kb);
            for (UUID id : List.of(zhangWeiA, zhangWeiB, zhangWeiC)) {
                jdbc.update("""
                        INSERT INTO entities (id, kb_id, type_id, canonical_name, created_at)
                         VALUES (?, ?, ?, 'Zhang Wei', ?)
                        """, id, kb, personType, t("2026-01-01T00:00:00Z"));
            }
            for (Object[] row : new Object[][]{{projectEntity, "Project Phoenix"},
                    {otherEntity, "Other Co"}}) {
                jdbc.update("""
                        INSERT INTO entities (id, kb_id, canonical_name, created_at)
                         VALUES (?, ?, ?, ?)
                        """, row[0], kb, row[1], t("2026-01-01T00:00:00Z"));
            }
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_at', 'works at')",
                    worksAt, kb);
            for (Object[] row : new Object[][]{{f_a_old, zhangWeiA, projectEntity, "2026-01-15T00:00:00Z"},
                    {f_b, zhangWeiB, otherEntity, "2026-01-20T00:00:00Z"},
                    {f_c_new, zhangWeiC, otherEntity, "2026-05-10T00:00:00Z"}}) {
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                           confidence, recorded_at)
                         VALUES (?, ?, ?, ?, ?, 0.9, ?)
                        """, row[0], kb, row[1], worksAt, row[2], t((String) row[3]));
            }
            // 张伟 a 上 1 月记下那条事实在 3 月作废（之后被合并覆盖之前先收掉）
            jdbc.update("UPDATE facts SET invalidated_at = ? WHERE id = ? AND invalidated_at IS NULL",
                    t("2026-03-10T00:00:00Z"), f_a_old);
            // 3 月合并 a → b（直接写 merged_into：测同名列的可见性，不整条合并路径走一遍）
            jdbc.update("""
                    INSERT INTO entity_merges (id, kb_id, source_id, target_id, merged_by, reason, created_at)
                     VALUES (?, ?, ?, ?, ?, 'duplicate person', ?)
                    """, mergeId, kb, zhangWeiA, zhangWeiB, user, t("2026-03-15T00:00:00Z"));
            jdbc.update("UPDATE entities SET merged_into = ? WHERE id = ?", zhangWeiB, zhangWeiA);

            java.util.function.Function<OffsetDateTime, List<String>> names = when -> {
                List<String> out = new ArrayList<>();
                for (var n : graph.sameNamePeers(kb, zhangWeiB, when)) {
                    out.add(n.name());
                }
                out.sort(Comparator.naturalOrder());
                return out;
            };

            // 当下：a 已被合并，只剩 c
            assertEquals(List.of("Zhang Wei"), names.apply(null), "当下只剩独立的 Zhang Wei");
            // 2 月：a 还在活跃，c 已创建 → a、c 都与 b 同名
            assertEquals(List.of("Zhang Wei", "Zhang Wei"), names.apply(t("2026-02-01T00:00:00Z")),
                    "2 月：合并之前，a 与 b 同名");
            // 4 月：a 已合并，c 已创建 → 只有 c
            assertEquals(List.of("Zhang Wei"), names.apply(t("2026-04-01T00:00:00Z")),
                    "4 月：a 已合并，只剩 c");
            // 6 月：合并之后，c 已是唯一独立同名实体
            assertEquals(List.of("Zhang Wei"), names.apply(t("2026-06-01T00:00:00Z")),
                    "6 月：a 已合并，c 已创建");
        } finally {
            jdbc.update("DELETE FROM entity_merges WHERE kb_id = ?", kb);
            jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", user);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /** 走真的合并路径：回放到合并之前，被并的那个在同名列里的度数按当时谁持有事实数。 */
    @Test
    void aMergedPeerKeepsItsDegreeBeforeTheMerge() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID worksAt = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID other = Uuids.nowV7();
        try {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'as-of-peers-merge')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'as-of-peers-merge')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'as-of-peers-merge')",
                    kb, ws);
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                    person, kb);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_at', 'works at')",
                    worksAt, kb);
            for (Object[] row : new Object[][]{{a, "Zhang Wei", person},
                    {b, "Zhang Wei", person},
                    {acme, "Acme", null},
                    {other, "Other Co", null}}) {
                jdbc.update("""
                        INSERT INTO entities (id, kb_id, type_id, canonical_name, created_at)
                         VALUES (?, ?, ?::uuid, ?, ?)
                        """, row[0], kb, row[2], row[1], t("2026-01-01T00:00:00Z"));
            }
            for (UUID subject : List.of(a, b)) {
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, recorded_at)
                         VALUES (?, ?, ?, ?, ?, 0.9, ?)
                        """, Uuids.nowV7(), kb, subject, worksAt,
                        subject.equals(a) ? acme : other, t("2026-01-10T00:00:00Z"));
            }
            resolution.mergeEntities(kb, a, b, null, "test");
            jdbc.update("UPDATE entity_merges SET created_at = ? WHERE source_id = ?",
                    t("2026-04-01T00:00:00Z"), a);

            var feb = t("2026-02-01T00:00:00Z");
            var peers = graph.sameNamePeers(kb, b, feb);
            var merged = peers.stream().filter(n -> n.id().equals(a)).findFirst()
                    .orElseThrow();
            var panel = graph.entityDetail(kb, a, null, feb).facts();
            assertEquals(1, panel.size(), "面板上二月的 a 有一条事实");
            assertEquals(1, merged.degree(), "同名列里的度数与面板一致");
            assertNotNull(merged);
        } finally {
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
