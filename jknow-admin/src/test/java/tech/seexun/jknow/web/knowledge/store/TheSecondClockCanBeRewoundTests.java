package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;

/**
 * 移植自 tests/store/the_second_clock_can_be_rewound.rs（324 行，1 例）——记录轴回退
 * （0019 / #307）打在真库上：为什么非要连库，因为这道防御**整个活在 SQL 字符串里**，
 * 编译器看不见一个漏写的 WHERE（0009 栽的正是这一跤：{@code NULL <> uuid} 不报错、
 * 只是什么都不选）。
 *
 * <p>三个方向都要断言，因为它们会以不同的方式坏掉：撤掉的行在 as_of=现在<b>不出现</b>
 * （谓词写反了会让作废行全体复活）；撤掉的行在作废时刻<b>之前出现</b>（谓词没接上就
 * 永远是"现在"，回放照旧空手）；recorded_at 晚于 T 的行在 T <b>不出现</b>（只写下界会让
 * 三月看见四月的修正）。Rust 行注里的三个 bullet 逐条对应。
 *
 * <p>等价点（tokio 测试 → JUnit）：自建自拆一次性 org/workspace/kb（跑完连 org 一起删，
 * 绝不碰已有的库）；graph_changes 是 Rust 侧 store 函数（api/tools.rs:774 的 MCP 工具
 * 消费，HTTP 无路由）——Java 侧随本移植落 GraphService.graphChanges（SQL 逐字），
 * MCP 暴露属 M5。
 */
class TheSecondClockCanBeRewoundTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    GraphService graph;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    /** seed 的产物：kb/张三/被修正掉的断言/修正行/前提没了的派生。 */
    record Fx(UUID kb, UUID zhang, UUID retracted, UUID correction, UUID derived) {
    }

    /** 一个人接过一个项目，我们三月改了主意，引擎在这中间推过一条边。 */
    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID project = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID zhang = Uuids.nowV7();
        UUID phoenix = Uuids.nowV7();
        UUID program = Uuids.nowV7();
        UUID retracted = Uuids.nowV7();
        UUID correction = Uuids.nowV7();
        UUID derived = Uuids.nowV7();
        UUID rule = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'rewind-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'rewind-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'rewind-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{person, "person", "Person"},
                {project, "project", "Project"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        for (Object[] row : new Object[][]{{leads, "leads", "leads"},
                {partOf, "part_of", "part of"}}) {
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        for (Object[] row : new Object[][]{{zhang, person, "Zhang San"},
                {phoenix, project, "Project Phoenix"},
                {program, project, "Phoenix Program"}}) {
            // **实体的出生时刻也要回填**：事实记在三月，实体却是"刚才"建的，那种账本
            // 现实里不存在——而 #336 之后 T 时刻还没出生的实体不再出现在图上
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, type_id, canonical_name, created_at)
                     VALUES (?, ?, ?, ?, ?)
                    """, row[0], kb, row[1], row[2], t("2026-01-01T00:00:00Z"));
        }
        // 03-01 记下、03-20 被修正掉的那条断言；03-20 记下的修正行
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    recorded_at, invalidated_at, supersedes)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, retracted, kb, zhang, leads, phoenix,
                t("2026-03-01T00:00:00Z"), t("2026-03-20T00:00:00Z"), null);
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    recorded_at, invalidated_at, supersedes)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, correction, kb, zhang, leads, phoenix,
                t("2026-03-20T00:00:00Z"), null, retracted);
        // 派生也有两根轴（derived_at / invalidated_at）：回放的图上留着**当时**推出
        // 的边，而不是今天这套规则的结论
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) VALUES (?, ?, ?, 'transitive')",
                rule, kb, partOf);
        jdbc.update("""
                INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, rule_id,
                                            derived_at, invalidated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, derived, kb, phoenix, partOf, program, rule,
                t("2026-03-05T00:00:00Z"), t("2026-03-22T00:00:00Z"));
        return new Fx(kb, zhang, retracted, correction, derived);
    }

    private void cleanup(UUID kb) {
        jdbc.update("""
                DELETE FROM organizations WHERE id = (
                    SELECT w.org_id FROM workspaces w
                    JOIN knowledge_bases k ON k.workspace_id = w.id WHERE k.id = ?)
                """, kb);
    }

    private static List<UUID> edgeIds(List<tech.seexun.jknow.store.entity.GraphEdge> edges) {
        return edges.stream().map(tech.seexun.jknow.store.entity.GraphEdge::id).sorted().toList();
    }

    @Test
    void theRecordingAxisRewindsOnEveryGraphRead() {
        Fx f = seed();
        try {
            // 1. 现在：修正行在，被它顶掉的断言不在，前提没了的派生也不在
            GraphService.Overview now = graph.overview(f.kb(), 50, null, null);
            assertEquals(List.of(f.correction()), edgeIds(now.edges()), "现在只该有修正行那条边");
            assertEquals(1, now.totalEdges(), "边总数跟着画布数，不是历史全量");
            assertEquals(1, now.nodes().stream().filter(n -> n.id().equals(f.zhang()))
                    .findFirst().orElseThrow().degree());

            // 2. 倒回 03-10：作废前的断言回来了，三月才推出的派生也在，
            //    而 03-20 才记下的修正行**不该出现**——那是当时还没发生的事
            GraphService.Overview march = graph.overview(f.kb(), 50, null, t("2026-03-10T00:00:00Z"));
            assertEquals(List.of(f.retracted(), f.derived()).stream().sorted().toList(),
                    edgeIds(march.edges()), "03-10 该看见当时持有的断言与派生");
            assertEquals(2, march.totalEdges());
            assertEquals(1, march.nodes().stream().filter(n -> n.id().equals(f.zhang()))
                    .findFirst().orElseThrow().degree(), "度数按当时的边数");
            assertTrue(march.edges().stream().filter(e -> e.id().equals(f.derived()))
                    .findFirst().orElseThrow().derived(), "派生边要带 derived 位，界面靠它画出区别");

            // 3. 倒回 03-25：修正行已经记下，派生已经被推翻
            GraphService.Overview after = graph.overview(f.kb(), 50, null, t("2026-03-25T00:00:00Z"));
            assertEquals(List.of(f.correction()), edgeIds(after.edges()));

            // 4. 更早于一切：图是空的，而不是"退化成现在"
            GraphService.Overview before = graph.overview(f.kb(), 50, null, t("2026-02-01T00:00:00Z"));
            assertTrue(before.edges().isEmpty(), "02-01 我们还什么都没记下");
            assertEquals(0, before.totalEdges());

            // 5. 邻域铺开也按当时的边找邻居
            assertEquals(List.of(f.retracted()),
                    edgeIds(graph.neighborhood(f.kb(), f.zhang(), 1, null,
                            t("2026-03-10T00:00:00Z")).edges()));
            assertEquals(List.of(f.correction()),
                    edgeIds(graph.neighborhood(f.kb(), f.zhang(), 1, null, null).edges()));

            // 6. 点开节点，面板说的是**当时**的事实——回放的图上点开一个节点，
            //    侧栏还答今天的事，两个说法就并排摆在同一个屏幕上
            List<GraphService.EntityFact> nowFacts = graph.entityDetail(f.kb(), f.zhang(),
                    null, null).facts();
            assertEquals(1, nowFacts.size());
            assertEquals(f.correction(), nowFacts.get(0).id());
            assertTrue(nowFacts.get(0).corrected(), "修正行认得出自己改写了谁");

            List<GraphService.EntityFact> marchFacts = graph.entityDetail(f.kb(), f.zhang(),
                    null, t("2026-03-10T00:00:00Z")).facts();
            assertEquals(1, marchFacts.size());
            assertEquals(f.retracted(), marchFacts.get(0).id(), "03-10 面板该给出当时那条");

            assertTrue(graph.entityDetail(f.kb(), f.zhang(), null,
                    t("2026-02-01T00:00:00Z")).facts().isEmpty(), "记下之前不该有事实");

            // #351：同一秒内先录入再更正。拿更正时刻退一整秒会跳过旧记录，
            // 拿更正时刻本身则已经是新记录；工具提示的「前一微秒」必须能读回旧事实。
            OffsetDateTime first = t("2026-03-20T02:43:53.382000Z");
            OffsetDateTime changed = t("2026-03-20T02:43:53.382002Z");
            jdbc.update("UPDATE facts SET recorded_at = ?, invalidated_at = ? WHERE id = ?",
                    first, changed, f.retracted());
            jdbc.update("UPDATE facts SET recorded_at = ? WHERE id = ?", changed, f.correction());

            List<GraphService.GraphChangeRow> events = graph.graphChanges(f.kb(),
                    t("2026-03-20T00:00:00Z"), t("2026-03-21T00:00:00Z"), f.zhang(), null, 10);
            assertEquals(2, events.size());
            assertEquals(changed, events.get(0).at());
            assertEquals("corrected", events.get(0).kind());
            assertEquals(first, events.get(1).at());
            assertEquals("asserted", events.get(1).kind());

            for (Object[] moment : new Object[][]{
                    {changed.minusSeconds(1), null},
                    {changed.minusNanos(1000), f.retracted()},
                    {changed, f.correction()}}) {
                OffsetDateTime asOf = (OffsetDateTime) moment[0];
                @SuppressWarnings("unchecked")
                List<UUID> expected = (List<UUID>) (moment[1] == null
                        ? List.of() : List.of((UUID) moment[1]));
                List<UUID> ids = graph.entityDetail(f.kb(), f.zhang(), null, asOf).facts().stream()
                        .map(GraphService.EntityFact::id).toList();
                assertEquals(expected, ids, "as_of=" + asOf);
            }
            assertNull(graph.graphChanges(f.kb(), t("2026-03-20T00:00:00Z"),
                    t("2026-03-21T00:00:00Z"), f.zhang(), null, 10).get(0).quote(),
                    "无证据行的事件 quote 为 null（resultMap 装配完整性）");
        } finally {
            cleanup(f.kb());
        }
    }
}
