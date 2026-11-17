package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/history_shows_the_merge_itself.rs（224 行，#337 的时钟接进
 * entity_history）——合并本身也进这条认识轴。三件事各自会以不同的方式坏掉：
 * <b>方向</b>（吸收了谁与被谁吸收是两件事，回滚按方向做）、<b>撤销的归因</b>（撤的人
 * 常常不是当初合的人——从审计 merge.revert 取，不能借合并行的 merged_by）、<b>总数</b>
 * （分页总数漏掉合并这一支，翻到最后一页会少几条）。
 */
class HistoryShowsTheMergeItselfTests extends HeartStoreTestBase {

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    @Autowired
    ResolutionService resolution;
    @Autowired
    GraphService graph;

    record Fixture(UUID org, UUID kb, UUID keeper, UUID absorbed, UUID returned, UUID reverter,
                   UUID mergeId) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID keeper = Uuids.nowV7();
        UUID absorbed = Uuids.nowV7();
        UUID returned = Uuids.nowV7();
        UUID reverter = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'history-merge-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'history-merge-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'history-merge-test')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Reverter')
                """, reverter, org, "reverter-" + reverter + "@test.local");
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        for (UUID id : new UUID[]{keeper, absorbed, returned}) {
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, type_id, canonical_name, created_at)
                     VALUES (?, ?, ?, 'Zhang Wei', ?)
                    """, id, kb, person, t("2026-02-01T00:00:00Z"));
        }

        // 四月：absorbed 并进 keeper，自动裁决（merged_by 为空）
        resolution.mergeEntities(kb, absorbed, keeper, null, "test");
        backdate(absorbed, "2026-04-01T00:00:00Z", null);

        // 五月：returned 并进 keeper；六月由人撤销
        UUID mergeId = resolution.mergeEntities(kb, returned, keeper, null, "test");
        resolution.revertMerge(kb, mergeId);
        backdate(returned, "2026-05-01T00:00:00Z", "2026-06-01T00:00:00Z");
        // 撤销的人记在审计里（merge.revert），history 从那儿取归因
        jdbc.update("""
                INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, target_id, detail)
                 VALUES (?, ?, ?, 'merge.revert', 'merge', ?, '{}'::jsonb)
                """, Uuids.nowV7(), kb, reverter, mergeId);

        return new Fixture(org, kb, keeper, absorbed, returned, reverter, mergeId);
    }

    /** 合并的时刻由 now() 落库，测试要的是确定的日期。 */
    private void backdate(UUID source, String created, String reverted) {
        jdbc.update("UPDATE entity_merges SET created_at = ?, reverted_at = ? WHERE source_id = ?",
                t(created), reverted != null ? t(reverted) : null, source);
    }

    @Test
    void history_carries_the_merge_and_its_direction() {
        Fixture f = seed();
        try {
            // 收编的那一头：两次并入 + 一次撤销
            var page = graph.entityHistory(f.kb(), f.keeper(), 50, 0);
            List<String> kinds = page.events().stream().map(e -> e.kind()).toList();
            assertEquals(2, kinds.stream().filter("merged_in"::equals).count(),
                    "两次都是别人并进来");
            assertEquals(1, kinds.stream().filter("merge_reverted"::equals).count(),
                    "六月那次撤销要在");
            assertFalse(kinds.contains("merged_away"), "keeper 从没被并走过");
            assertEquals(page.events().size(), page.total(), "总数漏了合并这一支");

            // 对方的名字要在，否则界面上只剩一句「有实体并了进来」
            assertTrue(page.events().stream().filter(e -> "merged_in".equals(e.kind()))
                    .allMatch(e -> "Zhang Wei".equals(e.otherName())),
                    "并入事件要带对方实体的名字");

            // 归因：合并是引擎自动裁的（merged_by 为空），撤销是人做的
            var mergedIn = page.events().stream().filter(e -> "merged_in".equals(e.kind()))
                    .findFirst().orElseThrow();
            assertTrue(mergedIn.actorName() == null,
                    "自动合并没有人，归因该是空（界面显示为引擎）");
            var reverted = page.events().stream()
                    .filter(e -> "merge_reverted".equals(e.kind())).findFirst().orElseThrow();
            assertEquals("Reverter", reverted.actorName(),
                    "撤销的人从审计里取，而不是借合并行的 merged_by");

            // 被并走的那一头：方向相反，而且看得见自己是四月没的
            var away = graph.entityHistory(f.kb(), f.absorbed(), 50, 0);
            List<tech.seexun.jknow.store.entity.EntityHistoryEvent> awayEvents = away.events()
                    .stream().filter(e -> "merged_away".equals(e.kind())).toList();
            assertEquals(1, awayEvents.size(), "它并进了别人，只有这一次");
            assertEquals(t("2026-04-01T00:00:00Z"), awayEvents.get(0).at());
            assertTrue(away.events().stream().noneMatch(e -> "merged_in".equals(e.kind())),
                    "它没吸收过谁");

            // 撤销过的那一头：并出去、又回来，两条都在
            var back = graph.entityHistory(f.kb(), f.returned(), 50, 0);
            List<String> backKinds = back.events().stream().map(e -> e.kind()).toList();
            assertTrue(backKinds.contains("merged_away") && backKinds.contains("merge_reverted"),
                    "并出去与撤销都要在");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", f.reverter());
            int gone = jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
            assertEquals(1, gone, "一次性 org 没删掉");
        }
    }
}
