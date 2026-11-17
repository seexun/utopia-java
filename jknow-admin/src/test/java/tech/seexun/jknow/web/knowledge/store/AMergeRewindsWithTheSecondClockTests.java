package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_merge_rewinds_with_the_second_clock.rs（257 行，0019 第二刀/#336）
 * ——合并也倒得回去。合并是<b>原地改写</b>（UPDATE facts SET subject_id = target），行上
 * 只剩合并之后的样子，「三月那天这条事实挂在谁身上」只在 entity_merges 的数组里。
 * 三个时刻各自会以不同的方式坏掉：合并之前（被并掉的实体重新长出来）、已撤销合并的
 * 窗口之内（那段时间它们确实是一个）、现在（一切照旧）。
 *
 * <p>等价点：facts_of 用 DetailStubs.entityDetail（entity_detail 的 as_of 核心读——任务 6
 * 落真端点后换真调用）；overview 用 GraphService 真身（as_of 回放形态已有）。
 */
class AMergeRewindsWithTheSecondClockTests extends HeartStoreTestBase {

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    @Autowired
    ResolutionService resolution;
    @Autowired
    GraphService graph;

    record Fixture(UUID org, UUID kb, UUID zhangA, UUID zhangB, UUID zhangC, UUID factA,
                   UUID factB, UUID factC) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID zenith = Uuids.nowV7();
        UUID zhangA = Uuids.nowV7();
        UUID zhangB = Uuids.nowV7();
        UUID zhangC = Uuids.nowV7();
        UUID factA = Uuids.nowV7();
        UUID factB = Uuids.nowV7();
        UUID factC = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'merge-rewind-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'merge-rewind-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'merge-rewind-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'company', 'Company')",
                company, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_for', 'works for')",
                worksFor, kb);
        // 实体的出生时刻也倒着看：一月的图上它们还不存在
        for (Object[] e : new Object[][]{{acme, company, "Acme"}, {zenith, company, "Zenith"},
                {zhangA, person, "Zhang Wei"}, {zhangB, person, "Zhang Wei"},
                {zhangC, person, "Zhang Wei"}}) {
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, type_id, canonical_name, created_at)
                     VALUES (?, ?, ?, ?, ?)
                    """, e[0], kb, e[1], e[2], t("2026-02-01T00:00:00Z"));
        }
        for (Object[] e : new Object[][]{{factA, zhangA, acme, "2026-03-01T00:00:00Z"},
                {factB, zhangB, zenith, "2026-03-02T00:00:00Z"},
                {factC, zhangC, acme, "2026-03-03T00:00:00Z"}}) {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence,
                                        recorded_at)
                     VALUES (?, ?, ?, ?, ?, 0.9, ?)
                    """, e[0], kb, e[1], worksFor, e[2], t((String) e[3]));
        }

        // 四月：B 并进 A，至今仍并着
        resolution.mergeEntities(kb, zhangB, zhangA, null, "test");
        backdate(zhangB, "2026-04-01T00:00:00Z", null);

        // 五月：C 并进 A；六月撤销。撤销把事实搬回去了，所以现在的行上看不出
        // 五月到六月之间它们曾是一个——那段窗口只在 entity_merges 里
        UUID mergeC = resolution.mergeEntities(kb, zhangC, zhangA, null, "test");
        backdate(zhangC, "2026-05-01T00:00:00Z", null);
        resolution.revertMerge(kb, mergeC);
        backdate(zhangC, "2026-05-01T00:00:00Z", "2026-06-01T00:00:00Z");

        return new Fixture(org, kb, zhangA, zhangB, zhangC, factA, factB, factC);
    }

    /** 合并的时刻由 now() 落库，测试要的是确定的日期。 */
    private void backdate(UUID source, String created, String reverted) {
        jdbc.update("UPDATE entity_merges SET created_at = ?, reverted_at = ? WHERE source_id = ?",
                t(created), reverted != null ? t(reverted) : null, source);
    }

    /** entity_detail 的事实 id 集（as_of 回放；entity_detail 真端点属任务 6）。 */
    private List<UUID> factsOf(UUID kb, UUID entity, String asOf) {
        List<UUID> ids = new ArrayList<>();
        for (DetailStubs.DetailFact f : DetailStubs.entityDetail(jdbc, kb, entity, null,
                asOf != null ? t(asOf) : null)) {
            ids.add(f.id());
        }
        Collections.sort(ids);
        return ids;
    }

    private static List<UUID> sorted(List<UUID> v) {
        List<UUID> out = new ArrayList<>(v);
        Collections.sort(out);
        return out;
    }

    @Test
    void a_merged_entity_comes_back_before_the_merge() {
        Fixture f = seed();
        try {
            // 1. 现在：B 并着（不出现），C 已撤销（照常出现）
            var now = graph.overview(f.kb(), 50, null, null);
            List<UUID> ids = now.nodes().stream().map(n -> n.id()).sorted().toList();
            assertFalse(ids.contains(f.zhangB()), "仍并着的实体不该出现在画布上");
            assertTrue(ids.contains(f.zhangC()), "撤销过的合并不该继续吞掉那个实体");
            assertEquals(4L, now.totalNodes(), "节点总数与画布同一口径");
            assertEquals(sorted(List.of(f.factA(), f.factB())), factsOf(f.kb(), f.zhangA(), null));
            assertEquals(List.of(f.factC()), factsOf(f.kb(), f.zhangC(), null));

            // 2. 三月：三个张伟各自站着，各自带着自己的那条事实。
            //    这是这一刀的全部意义——事实行上写的是 A，而三月那天它挂在 B 身上
            var march = graph.overview(f.kb(), 50, null, t("2026-03-15T00:00:00Z"));
            List<UUID> marchIds = march.nodes().stream().map(n -> n.id()).toList();
            assertTrue(marchIds.contains(f.zhangB()) && marchIds.contains(f.zhangC()));
            assertEquals(5L, march.totalNodes());
            assertEquals(List.of(f.factA()), factsOf(f.kb(), f.zhangA(), "2026-03-15T00:00:00Z"),
                    "三月的 A 只有自己那一条");
            assertEquals(List.of(f.factB()), factsOf(f.kb(), f.zhangB(), "2026-03-15T00:00:00Z"),
                    "被并掉的 B 在三月还拿着自己那条事实");

            // 3. 五月中：C 那次合并当时生效着（六月才撤销）。撤销已经把行搬回 C，
            //    所以这一格只能从 entity_merges 的数组里读出来
            var may = graph.overview(f.kb(), 50, null, t("2026-05-15T00:00:00Z"));
            List<UUID> mayIds = may.nodes().stream().map(n -> n.id()).toList();
            assertFalse(mayIds.contains(f.zhangC()), "五月中 C 正并在 A 里");
            assertFalse(mayIds.contains(f.zhangB()));
            assertEquals(3L, may.totalNodes());
            assertEquals(sorted(List.of(f.factA(), f.factB(), f.factC())),
                    factsOf(f.kb(), f.zhangA(), "2026-05-15T00:00:00Z"),
                    "五月中三条事实都在 A 身上");

            // 4. 一月：实体还没被建出来，图是空的，而不是退化成现在
            var january = graph.overview(f.kb(), 50, null, t("2026-01-01T00:00:00Z"));
            assertTrue(january.nodes().isEmpty(), "一月这些实体还不存在");
            assertEquals(0L, january.totalNodes());
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            int gone = jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
            assertEquals(1, gone, "一次性 org 没删掉");
        }
    }
}
