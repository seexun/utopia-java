package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * 移植自 tests/store/search_entities_degree_respects_as_of.rs（327 行，2 例）——检索的
 * 度数与列表都倒回当时（#336 / #549）：作废/记下的事实按 recorded_at 进出度数；回放里
 * 被并掉的实体重新出现在列表与总数里。断言逐条对译。
 */
class SearchEntitiesDegreeRespectsAsOfTests extends HeartStoreTestBase {

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
    void searchEntitiesDegreeRespectsAsOf() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID personType = Uuids.nowV7();
        UUID projectType = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        UUID alpha = Uuids.nowV7();
        UUID beta = Uuids.nowV7();
        UUID projectEntity = Uuids.nowV7();
        try {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'as-of-search-test')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'as-of-search-test')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'as-of-search-test')",
                    kb, ws);
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                    personType, kb);
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'project', 'Project')",
                    projectType, kb);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'leads', 'leads')",
                    leads, kb);
            for (Object[] row : new Object[][]{{alpha, "Alpha Person", personType},
                    {beta, "Beta Person", personType},
                    {projectEntity, "Project", projectType}}) {
                jdbc.update("""
                        INSERT INTO entities (id, kb_id, type_id, canonical_name, created_at)
                         VALUES (?, ?, ?::uuid, ?, ?)
                        """, row[0], kb, row[2], row[1], t("2026-01-01T00:00:00Z"));
            }
            // alpha：1 月记下「leads project_entity」——一直活着
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                       confidence, recorded_at)
                     VALUES (?, ?, ?, ?, ?, 0.9, ?)
                    """, Uuids.nowV7(), kb, alpha, leads, projectEntity,
                    t("2026-01-15T00:00:00Z"));
            // beta：1 月记下 → 3 月作废；5 月又记下第二条
            UUID fBetaOld = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                       confidence, recorded_at)
                     VALUES (?, ?, ?, ?, ?, 0.9, ?)
                    """, fBetaOld, kb, beta, leads, projectEntity, t("2026-01-20T00:00:00Z"));
            jdbc.update("UPDATE facts SET invalidated_at = ? WHERE id = ? AND invalidated_at IS NULL",
                    t("2026-03-10T00:00:00Z"), fBetaOld);
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                       confidence, recorded_at)
                     VALUES (?, ?, ?, ?, ?, 0.9, ?)
                    """, Uuids.nowV7(), kb, beta, leads, projectEntity,
                    t("2026-05-10T00:00:00Z"));

            // Rust 侧 find_degree 助手：按 id 找度数，找不到 -1
            java.util.function.Function<OffsetDateTime, Long> alphaDegree = asOf -> graph
                    .searchEntities(kb, "Person", 50, 0, asOf).entities().stream()
                    .filter(n -> n.id().equals(alpha)).mapToLong(GraphNodeDegree).findFirst()
                    .orElse(-1L);
            java.util.function.Function<OffsetDateTime, Long> betaDegree = asOf -> graph
                    .searchEntities(kb, "Person", 50, 0, asOf).entities().stream()
                    .filter(n -> n.id().equals(beta)).mapToLong(GraphNodeDegree).findFirst()
                    .orElse(-1L);

            // 当下：alpha=1，beta=1（旧的已作废、新的还活着）
            assertEquals(1L, alphaDegree.apply(null), "当下 alpha degree=1");
            assertEquals(1L, betaDegree.apply(null), "当下 beta degree=1（新的那条）");
            // 2 月：alpha 已记下=1；beta 的旧边还在作废之前=1
            assertEquals(1L, alphaDegree.apply(t("2026-02-01T00:00:00Z")), "2 月 alpha degree=1");
            assertEquals(1L, betaDegree.apply(t("2026-02-01T00:00:00Z")),
                    "2 月 beta 旧边在作废之前 degree=1");
            // 4 月：alpha 还是 1；beta 旧边已作废、新边还没记下 → 0
            assertEquals(1L, alphaDegree.apply(t("2026-04-01T00:00:00Z")), "4 月 alpha degree=1");
            assertEquals(0L, betaDegree.apply(t("2026-04-01T00:00:00Z")),
                    "4 月 beta 旧边已作废、新边还没记下 degree=0");
            // 6 月：alpha 还是 1；beta 新边已记下=1
            assertEquals(1L, alphaDegree.apply(t("2026-06-01T00:00:00Z")), "6 月 alpha degree=1");
            assertEquals(1L, betaDegree.apply(t("2026-06-01T00:00:00Z")),
                    "6 月 beta 新边已记下 degree=1");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private static final java.util.function.ToLongFunction<
            tech.seexun.jknow.store.entity.GraphNodeRow> GraphNodeDegree =
            tech.seexun.jknow.store.entity.GraphNodeRow::degree;

    /** 回放时列表与总数也按当时可见的实体算，走真的合并路径。 */
    @Test
    void aSearchInReplayListsTheEntitiesOfThatMoment() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID worksAt = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID later = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID other = Uuids.nowV7();
        try {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'as-of-search-merge')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'as-of-search-merge')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'as-of-search-merge')",
                    kb, ws);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_at', 'works at')",
                    worksAt, kb);
            for (Object[] row : new Object[][]{{a, "Zhang Wei A", "2026-01-01T00:00:00Z"},
                    {b, "Zhang Wei B", "2026-01-01T00:00:00Z"},
                    // 五月才建：三月的搜索里不该有它
                    {later, "Zhang Wei C", "2026-05-01T00:00:00Z"},
                    {acme, "Acme", "2026-01-01T00:00:00Z"},
                    {other, "Other Co", "2026-01-01T00:00:00Z"}}) {
                jdbc.update("""
                        INSERT INTO entities (id, kb_id, canonical_name, created_at)
                         VALUES (?, ?, ?, ?)
                        """, row[0], kb, row[1], t((String) row[2]));
            }
            for (Object[] row : new Object[][]{{a, acme}, {b, other}}) {
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, recorded_at)
                         VALUES (?, ?, ?, ?, ?, 0.9, ?)
                        """, Uuids.nowV7(), kb, row[0], worksAt, row[1],
                        t("2026-01-10T00:00:00Z"));
            }
            resolution.mergeEntities(kb, a, b, null, "test");
            jdbc.update("UPDATE entity_merges SET created_at = ? WHERE source_id = ?",
                    t("2026-04-01T00:00:00Z"), a);

            var march = graph.searchEntities(kb, "Zhang", 10, 0, t("2026-03-01T00:00:00Z"));
            record Seen(UUID id, long degree) {
            }
            List<Seen> seen = new ArrayList<>();
            for (var n : march.entities()) {
                seen.add(new Seen(n.id(), n.degree()));
            }
            seen.sort(Comparator.comparing(Seen::id));
            List<Seen> want = new ArrayList<>(List.of(new Seen(a, 1), new Seen(b, 1)));
            want.sort(Comparator.comparing(Seen::id));
            assertEquals(want, seen, "三月：A 还没并进 B，各一度；C 还没建");
            assertEquals(2, march.total());
        } finally {
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
