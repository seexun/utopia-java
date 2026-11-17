package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_corrected_time_keeps_its_statements.rs（205 行）——人改一条物化
 * 出来的行的区间（#899 的第三个改写者）：改完它仍是那条陈述算出来的行；画面上一条陈述
 * 只画一条边；下一轮物化没有要补的（人改的起点不被算回去）。
 */
class ACorrectedTimeKeepsItsStatementsTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    TemporalService temporal;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    @Test
    void aCorrectedTimeKeepsItsStatements() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'corrected-time-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'corrected-time-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'corrected-time-test')",
                kb, ws);
        try {
            UUID person = Uuids.nowV7();
            UUID project = Uuids.nowV7();
            UUID leads = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label, color, shape)
                     VALUES (?, ?, 'person', 'person', '#7fd0ff', 'circle')
                    """, person, kb);
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label, color, shape)
                     VALUES (?, ?, 'project', 'project', '#7fd0ff', 'circle')
                    """, project, kb);
            // 一个项目同时只有一个 lead：接任关上前任
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, inverse_functional)
                     VALUES (?, ?, 'leads', 'leads', 'state', TRUE)
                    """, leads, kb);
            UUID zhang = Uuids.nowV7();
            UUID li = Uuids.nowV7();
            UUID aurora = Uuids.nowV7();
            for (Object[] row : new Object[][]{{zhang, person, "Zhang San"}, {li, person, "Li Si"},
                    {aurora, project, "Aurora"}}) {
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                        row[0], kb, row[1], row[2]);
            }
            // due 按 s.id 排——v7 同毫秒不保序，排序后 charter 先于 handover
            List<UUID> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ids.add(Uuids.nowV7());
            }
            ids.sort(null);
            UUID charter = ids.get(0);
            UUID handover = ids.get(1);
            for (Object[] row : new Object[][]{
                    new Object[]{charter, zhang, OffsetDateTime.parse("2023-01-10T00:00:00Z")},
                    new Object[]{handover, li, OffsetDateTime.parse("2024-07-05T00:00:00Z")}}) {
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                            valid_from, valid_from_precision, valid_from_grade)
                         VALUES (?, ?, ?, ?, 'open', 'leads', 0.9, ?, 'day', 'A')
                        """, row[0], kb, row[1], aurora, row[2]);
            }
            jdbc.update("""
                    INSERT INTO phrase_bindings
                         (id, kb_id, phrase, subject_type_id, object_type_id, object_is_value,
                          relation_type_id, direction, status)
                     VALUES (?, ?, 'leads', ?, ?, false, ?, 'forward', 'bound')
                    """, Uuids.nowV7(), kb, person, project, leads);

            // 两条陈述两行；物化后的对账把张三那一段关在李四接手那天
            Outcome first = materialize.materialize(kb);
            assertEquals(2L, first.added());
            assertEquals(1L, first.corrected(), first.toString());
            List<Object[]> rows = jdbc.query("""
                    SELECT id, valid_to FROM facts
                      WHERE kb_id = ? AND layer = 'typed' AND subject_id = ? AND invalidated_at IS NULL
                    """, (rs, i) -> new Object[]{rs.getObject(1, UUID.class),
                    rs.getObject(2, OffsetDateTime.class)}, kb, zhang);
            assertEquals(1, rows.size());
            UUID closed = (UUID) rows.get(0)[0];
            assertEquals(t("2024-07-05T00:00:00Z"), rows.get(0)[1], "交接日关上前任");

            // 人改起点，终点照旧；改完按搬移重新对账
            UUID corrected = temporal.correctInterval(closed, new Validity(
                    t("2023-02-01T00:00:00Z"), "day", t("2024-07-05T00:00:00Z"), "day", null, null));
            assertNotNull(corrected, "the row was live");
            temporal.reconcileMovedFacts(kb, List.of(corrected));

            // 修正行仍是那条陈述算出来的
            assertEquals(charter, jdbc.queryForObject(
                    "SELECT from_statement_id FROM facts WHERE id = ?", UUID.class, corrected));
            assertEquals(List.of(charter), jdbc.queryForList(
                    "SELECT statement_id FROM typed_fact_sources WHERE fact_id = ?", UUID.class,
                    corrected));

            // 画面上一条陈述只画一条边：改过的那一段。1 月 20 日还没人在管，2024 年八月只有李四
            assertEquals(List.of(), leadsOf(kb, aurora, "2023-01-20T00:00:00Z"));
            assertEquals(List.of("Zhang San"), leadsOf(kb, aurora, "2023-03-01T00:00:00Z"));
            assertEquals(List.of("Li Si"), leadsOf(kb, aurora, "2024-08-01T00:00:00Z"));
            // 下一轮物化没有要补的：人改的起点不被算回去
            assertEquals(Outcome.EMPTY, materialize.materialize(kb));
            List<Object[]> after = jdbc.query("""
                    SELECT valid_from, valid_to FROM facts
                      WHERE kb_id = ? AND layer = 'typed' AND subject_id = ? AND invalidated_at IS NULL
                    """, (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class),
                    rs.getObject(2, OffsetDateTime.class)}, kb, zhang);
            assertEquals(1, after.size());
            assertEquals(t("2023-02-01T00:00:00Z"), after.get(0)[0]);
            assertEquals(t("2024-07-05T00:00:00Z"), after.get(0)[1], "张三只有人改过的那一段");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    @Autowired
    tech.seexun.jknow.store.GraphService graphs;

    /** Aurora 的 leads 邻居（世界轴 as-of 读），排序后断言。 */
    private List<String> leadsOf(UUID kb, UUID aurora, String at) {
        var n = graphs.neighborhood(kb, aurora, 1, t(at), null);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (var e : n.edges()) {
            if (aurora.equals(e.target())) {
                n.nodes().stream().filter(x -> x.id().equals(e.source())).findFirst()
                        .ifPresent(x -> names.add(x.name()));
            }
        }
        java.util.Collections.sort(names);
        return names;
    }
}
