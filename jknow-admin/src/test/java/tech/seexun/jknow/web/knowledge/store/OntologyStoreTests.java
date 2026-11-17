package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.entity.EntityInstance;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.RelationTypeView;

/**
 * ontology 读路径对真实数据的只读断言（任务 4；StoreTestBase 口径）：示例库
 * （星野科技，Rust 管道写入 13 类/13 谓词/32 实体）上验证两视图与 instances——
 * 排序（lower(label)/lower(name)+id）、usage 与镜像 SQL 同口径、数组子查询形状。
 */
class OntologyStoreTests extends StoreTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "task4-onto-store-" + RUN + "@test.local";

    private static UUID demoKb;
    private static long mirrorTypes;
    private static UUID orgId;
    private static UUID wsId;
    private static UUID fixtureKb;

    @Autowired
    OntologyService ontology;

    @BeforeAll
    void pickDemoKb() {
        var kbWithEntities = jdbc.queryForList(
                "SELECT k.id FROM knowledge_bases k JOIN entities e ON e.kb_id = k.id "
                        + "GROUP BY k.id ORDER BY count(*) DESC LIMIT 1", UUID.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(!kbWithEntities.isEmpty(), "共享库无带实体的示例库");
        demoKb = kbWithEntities.get(0);
        mirrorTypes = jdbc.queryForObject(
                "SELECT count(*) FROM entity_types WHERE kb_id = ?", Long.class, demoKb);

        // misses fixture（参照实例二进制契约：count DESC, key ASC；dismissed 分列）
        orgId = jdbc.queryForObject("SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Task4 Onto Store', false)", UUID.randomUUID(), orgId, EMAIL);
        wsId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId, "ws-task4-onto-store-" + RUN);
        fixtureKb = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                fixtureKb, wsId, "kb-task4-onto-store-" + RUN);
        jdbc.update("INSERT INTO ontology_misses (kb_id, kind, key, example, count, updated_at, dismissed_at) VALUES "
                + "('" + fixtureKb + "', 'entity_type', 'zz_small', 'old small', 1, now() - interval '50 minutes', NULL), "
                + "('" + fixtureKb + "', 'entity_type', 'aaa', 'tie a', 5, now() - interval '20 minutes', NULL), "
                + "('" + fixtureKb + "', 'entity_type', 'aab', 'tie b', 5, now() - interval '30 minutes', NULL), "
                + "('" + fixtureKb + "', 'relation_type', 'gone', 'dismissed one', 2, now() - interval '10 minutes', "
                + "now() - interval '5 minutes')");
    }

    @AfterAll
    void cleanup() {
        if (!sharedStackUp()) {
            return;
        }
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", fixtureKb);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", EMAIL);
    }

    @Test
    void entityTypeViewsSortByLowerLabelWithUsageMirror() {
        List<EntityTypeView> views = ontology.entityTypes(demoKb);
        assertEquals(mirrorTypes, views.size(), "全部类");
        for (int i = 1; i < views.size(); i++) {
            String prev = views.get(i - 1).label().toLowerCase(Locale.ROOT);
            String cur = views.get(i).label().toLowerCase(Locale.ROOT);
            assertTrue(prev.compareTo(cur) <= 0, "ORDER BY lower(label)");
        }
        // usage 与镜像口径（该类下在世实体数）逐类一致
        for (EntityTypeView v : views) {
            Long mirror = jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND type_id = ? "
                            + "AND merged_into IS NULL", Long.class, demoKb, v.id());
            assertEquals(mirror, v.usage(), "usage 一致: " + v.label());
            assertNotNull(v.parents(), "parents 数组（可为空）");
            assertNotNull(v.disjoint(), "disjoint 数组");
            assertNotNull(v.color());
            assertNotNull(v.shape());
        }
    }

    @Test
    void relationTypeViewsCarryAxiomsAndUsage() {
        List<RelationTypeView> views = ontology.relationTypes(demoKb);
        assertEquals(jdbc.queryForObject(
                "SELECT count(*) FROM relation_types WHERE kb_id = ?", Long.class, demoKb), views.size());
        boolean anyUsage = false;
        for (RelationTypeView v : views) {
            Long mirror = jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE kb_id = ? AND predicate_id = ? "
                            + "AND invalidated_at IS NULL", Long.class, demoKb, v.id());
            assertEquals(mirror, v.usage(), "usage=活事实数: " + v.key());
            anyUsage |= v.usage() > 0;
            assertNotNull(v.temporal(), "temporal 恒有");
            assertNotNull(v.kind(), "kind 恒有");
            assertNotNull(v.domains());
            assertNotNull(v.ranges());
            assertNotNull(v.qualifiers());
        }
        assertTrue(anyUsage, "示例库谓词有使用量");
        for (int i = 1; i < views.size(); i++) {
            assertTrue(views.get(i - 1).label().toLowerCase(Locale.ROOT)
                    .compareTo(views.get(i).label().toLowerCase(Locale.ROOT)) <= 0, "ORDER BY lower(label)");
        }
    }

    @Test
    void instancesPageByNameWithFactCountAndTotal() {
        // 实体最多的类做断言主体（数据漂移则按同规则现取）
        var typed = jdbc.queryForList(
                "SELECT type_id FROM entities WHERE kb_id = ? AND type_id IS NOT NULL "
                        + "AND merged_into IS NULL GROUP BY type_id ORDER BY count(*) DESC LIMIT 1", UUID.class, demoKb);
        org.junit.jupiter.api.Assumptions.assumeTrue(!typed.isEmpty(), "无带类型的实体");
        UUID typeId = typed.get(0);
        OntologyService.Instances page = ontology.entityInstances(demoKb, typeId, 100, 0);
        Long mirror = jdbc.queryForObject(
                "SELECT count(*) FROM entities WHERE kb_id = ? AND type_id = ? AND merged_into IS NULL",
                Long.class, demoKb, typeId);
        assertEquals(mirror, page.total(), "total 同 WHERE");
        List<EntityInstance> rows = page.entities();
        assertEquals(Math.min(100, mirror), rows.size());
        for (int i = 1; i < rows.size(); i++) {
            int cmp = rows.get(i - 1).name().toLowerCase(Locale.ROOT)
                    .compareTo(rows.get(i).name().toLowerCase(Locale.ROOT));
            assertTrue(cmp <= 0, "ORDER BY lower(canonical_name), id");
        }
        EntityInstance first = rows.get(0);
        Long factMirror = jdbc.queryForObject(
                "SELECT count(*) FROM facts f WHERE f.kb_id = ? AND (f.subject_id = ? OR f.object_id = ?) "
                        + "AND f.invalidated_at IS NULL", Long.class, demoKb, first.id(), first.id());
        assertEquals(factMirror, first.factCount(), "fact_count 相关子查询");
        // 翻页：per=1 第 0/1 页衔接且不重叠
        List<EntityInstance> p0 = ontology.entityInstances(demoKb, typeId, 1, 0).entities();
        List<EntityInstance> p1 = ontology.entityInstances(demoKb, typeId, 1, 1).entities();
        assertEquals(1, p0.size());
        if (rows.size() > 1) {
            assertEquals(rows.get(0).id(), p0.get(0).id());
            assertEquals(rows.get(1).id(), p1.get(0).id(), "OFFSET=per*page");
        }
    }

    @Test
    void unknownTypeInstancesAreEmpty() {
        OntologyService.Instances empty = ontology.entityInstances(demoKb,
                UUID.fromString("00000000-0000-0000-0000-000000000000"), 12, 0);
        assertEquals(List.of(), empty.entities());
        assertEquals(0, empty.total());
    }

    // ---- misses / dismissed_misses（参照实例二进制契约，2026-11-11 对 1516 实测） ----

    @Test
    void missesOrderByCountDescThenKeyWithTiesByKey() {
        OntologyService.Misses m = ontology.misses(fixtureKb);
        assertEquals(3, m.misses().size(), "三行活的");
        assertEquals("aaa", m.misses().get(0).key(), "count DESC 首行");
        assertEquals("aab", m.misses().get(1).key(), "同计数 tie 按 key ASC（与 updated_at 无关）");
        assertEquals("zz_small", m.misses().get(2).key());
        assertEquals("entity_type", m.misses().get(0).kind());
        assertEquals("tie a", m.misses().get(0).example());
        assertEquals(5, m.misses().get(0).count());
        assertEquals(1, m.dismissedMisses().size(), "dismissed 分列");
        assertEquals("gone", m.dismissedMisses().get(0).key());
        // 示例库无未匹配（Rust 管道不写该表）→ 双空数组
        assertEquals(List.of(), ontology.misses(demoKb).misses());
        assertEquals(List.of(), ontology.misses(demoKb).dismissedMisses());
    }
}
