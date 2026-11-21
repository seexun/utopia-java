package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.entity.GraphNodeRow;

/**
 * graph 读路径对真实数据的只读断言（任务 3 步骤 3/4；共享库直连，StoreTestBase 口径）：
 * 以「镜像 SQL 直接算期望值」比对 mapper 逐字搬迁的结果——总数与口径一致、排序一致
 * （degree DESC）、边端点落在节点集内、精确同名优先。示例库（Rust 管道写入的 30+ 实体）
 * 是断言主体；空库面（剧本自建库恒空）只断言形状零。
 */
class GraphStoreTests extends StoreTestBase {

    /** Rust 管道写入真实数据的示例库（实体数最多的库；数据漂移则按同规则现取）。 */
    private static UUID demoKb;
    private static long mirrorTotalNodes;
    private static long mirrorTotalEdges;

    @Autowired
    GraphService graph;

    @BeforeAll
    void pickDemoKb() {
        // 实体最多的库 = 示例库（星野科技，seed_demo 经 Rust 抽取管道写入）；无则整类跳过
        var kbWithEntities = jdbc.queryForList(
                "SELECT k.id FROM knowledge_bases k JOIN entities e ON e.kb_id = k.id "
                        + "GROUP BY k.id ORDER BY count(*) DESC LIMIT 1", UUID.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(!kbWithEntities.isEmpty(), "共享库无带实体的示例库");
        demoKb = kbWithEntities.get(0);
        // 镜像口径（graph.rs:1316-1334 同 SQL，防手写期望值漂移）
        mirrorTotalNodes = jdbc.queryForObject(
                "SELECT count(*) FROM entities e WHERE e.kb_id = ? AND NOT EXISTS "
                        + "(SELECT 1 FROM entity_merges m WHERE m.source_id = e.id AND m.reverted_at IS NULL)",
                Long.class, demoKb);
        mirrorTotalEdges = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM facts f WHERE f.kb_id = ? AND f.invalidated_at IS NULL "
                        + "AND f.object_id IS NOT NULL) + (SELECT count(*) FROM derived_facts d "
                        + "WHERE d.kb_id = ? AND d.invalidated_at IS NULL AND d.object_id IS NOT NULL)",
                Long.class, demoKb, demoKb);
    }

    @Test
    void overviewTotalsMatchMirrorSqlAndNodesAreTopDegree() {
        GraphService.Overview o = graph.overview(demoKb, 50, null, null);
        assertEquals(mirrorTotalNodes, o.totalNodes(), "total_nodes 与镜像口径一致");
        assertEquals(mirrorTotalEdges, o.totalEdges(), "total_edges=facts+derived 两子查询和");
        assertEquals(Math.min(50, mirrorTotalNodes), o.nodes().size(), "top-N 截断");
        for (int i = 1; i < o.nodes().size(); i++) {
            assertTrue(o.nodes().get(i - 1).degree() >= o.nodes().get(i).degree(),
                    "节点按度数降序");
        }
        // 边端点必须都落在取回的节点集内（edges_among 的 = ANY(ids) 语义）
        Set<UUID> ids = o.nodes().stream().map(GraphNodeRow::id).collect(Collectors.toSet());
        o.edges().forEach(e -> assertTrue(ids.contains(e.source()) && ids.contains(e.target()),
                "边两端都在节点集内: " + e.id()));
        assertTrue(o.totalEdges() > 0, "示例库有活的事实边");
    }

    @Test
    void overviewNodeShapeIsLeftJoinWithDefaults() {
        GraphService.Overview o = graph.overview(demoKb, 1000, null, null);
        GraphNodeRow any = o.nodes().get(0);
        assertNotNull(any.id());
        assertNotNull(any.name());
        assertNotNull(any.color());
        assertNotNull(any.shape());
        // LEFT JOIN 语义：没判出类型的实体 color/shape 走 SQL 缺省值，type_key 可空
        o.nodes().forEach(n -> assertTrue(n.degree() >= 0));
    }

    @Test
    void searchRecallsByCanonicalNameAndAlias() {
        // 规范名召回：AGV 前缀实体成族
        GraphService.Search agv = graph.searchEntities(demoKb, "AGV", 10, 0, null);
        Long mirror = jdbc.queryForObject(
                "SELECT count(*) FROM entities e WHERE e.kb_id = ? AND NOT EXISTS "
                        + "(SELECT 1 FROM entity_merges m WHERE m.source_id = e.id AND m.reverted_at IS NULL) "
                        + "AND (e.canonical_name ILIKE '%AGV%' OR EXISTS (SELECT 1 FROM facts nf "
                        + "JOIN relation_types nr ON nr.id = nf.predicate_id WHERE nf.subject_id = e.id "
                        + "AND nf.kb_id = e.kb_id AND nr.builtin AND nr.key = 'known_as' "
                        + "AND nf.invalidated_at IS NULL AND nf.object_value->>'value' ILIKE '%AGV%'))",
                Long.class, demoKb);
        assertEquals(mirror, agv.total(), "total 与召回谓词镜像一致");
        agv.entities().forEach(n ->
                assertTrue(n.name().toLowerCase().contains("agv"), "命中名含 AGV: " + n.name()));
        // 精确同名优先（graph.rs:1572-1574）：查全名，完全同名者必须排第一
        String full = jdbc.queryForObject(
                "SELECT canonical_name FROM entities WHERE kb_id = ? ORDER BY created_at LIMIT 1",
                String.class, demoKb);
        GraphService.Search exact = graph.searchEntities(demoKb, full, 5, 0, null);
        assertEquals(full, exact.entities().get(0).name(), "精确同名排最前");
    }

    @Test
    void neighborhoodExpandsFromSeedWithinTwoHops() {
        GraphService.Overview o = graph.overview(demoKb, 1, null, null);
        UUID seed = o.nodes().get(0).id();
        GraphService.Neighborhood n = graph.neighborhood(demoKb, seed, 2, null, null);
        assertTrue(n.nodes().size() >= 1, "种子自身在结果内");
        Set<UUID> ids = n.nodes().stream().map(GraphNodeRow::id).collect(Collectors.toSet());
        assertTrue(ids.contains(seed));
        n.edges().forEach(e -> assertTrue(ids.contains(e.source()) && ids.contains(e.target())));
        assertTrue(n.nodes().size() <= 300, "节点数封顶 300");
    }

    @Test
    void unknownEntityNeighborhoodIsEmptyShape() {
        GraphService.Neighborhood n = graph.neighborhood(demoKb,
                UUID.fromString("00000000-0000-0000-0000-000000000000"), 2, null, null);
        assertEquals(List.of(), n.nodes());
        assertEquals(List.of(), n.edges());
    }

    @Test
    void asOfNowMatchesNowFormOnDataWithoutFutureTimestamps() {
        // 回放形态与「现在」形态在无未来时间戳的库上必须同值（record_axis 退化等价性）
        GraphService.Overview now = graph.overview(demoKb, 50, null, null);
        GraphService.Overview rewind = graph.overview(demoKb, 50, null, OffsetDateTime.now());
        assertEquals(now.totalNodes(), rewind.totalNodes());
        assertEquals(now.totalEdges(), rewind.totalEdges());
        assertEquals(now.nodes().stream().map(GraphNodeRow::id).toList(),
                rewind.nodes().stream().map(GraphNodeRow::id).toList());
    }

    @Test
    void emptyKbOverviewAndSearchAreZero() {
        // 空库面：General（首用户库空起步，#580）
        // emptyKbOverview 前提自足：General 库三行链（org→workspace→kb），清卷空库直跑
        jdbc.update("INSERT INTO organizations (id, name, created_at) VALUES ('0190d3e0-0000-7d5a-8000-0000000000f0', '默认组织', '2026-10-01T00:00:00Z') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO workspaces (id, org_id, name, created_at) VALUES ('0190d3e0-0000-7d5a-8000-0000000000f1', '0190d3e0-0000-7d5a-8000-0000000000f0', '默认工作区', '2026-10-02T00:00:00Z') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility, created_at) VALUES ('0190d3e0-0000-7d5a-8000-0000000000f2', '0190d3e0-0000-7d5a-8000-0000000000f1', 'General', 'restricted', '2026-10-02T00:00:01Z') ON CONFLICT (id) DO NOTHING");
        UUID emptyKb = jdbc.queryForObject(
                "SELECT id FROM knowledge_bases WHERE name = 'General'", UUID.class);
        GraphService.Overview o = graph.overview(emptyKb, 150, null, null);
        assertEquals(0, o.totalNodes());
        assertEquals(0, o.totalEdges());
        assertEquals(List.of(), o.nodes());
        assertEquals(List.of(), o.edges());
        GraphService.Search s = graph.searchEntities(emptyKb, "x", 10, 0, null);
        assertEquals(0, s.total());
        assertEquals(List.of(), s.entities());
    }

    @Test
    void factEvidenceReturnsRustWrittenRows() {
        // 136 条 Rust 写下的证据行（示例库）：取一条有证据的 fact 断言行形状
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT fe.fact_id FROM fact_evidence fe JOIN facts f ON f.id = fe.fact_id "
                        + "WHERE f.kb_id = ? AND fe.document_id IS NOT NULL LIMIT 1", demoKb);
        org.junit.jupiter.api.Assumptions.assumeTrue(!rows.isEmpty(), "库内暂无带文档的证据行");
        UUID factId = (UUID) rows.get(0).get("fact_id");
        var evidence = graph.factEvidence(factId);
        assertTrue(evidence.size() >= 1);
        assertTrue(evidence.get(0).filename() != null && !evidence.get(0).filename().isEmpty());
    }
}
