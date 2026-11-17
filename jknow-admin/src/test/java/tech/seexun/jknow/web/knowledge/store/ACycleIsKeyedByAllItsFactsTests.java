package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * 移植自 tests/store/a_cycle_is_keyed_by_all_its_facts.rs（236 行，1 例）——环按整条路径
 * 定键（#641），打在真库上。
 *
 * <p>环的 left 是最小那条事实、right 是转到它起头之后的最后一条。两个环共用这两条、
 * 中间走的不同时，从前在 (kb_id, kind, left_fact, right_fact) 上撞成一行：后算出来的
 * 覆盖先算出来的 path，另一个环在 Review 里消失，而留下哪一个取决于遍历顺序。钉四样：
 * <ol>
 *   <li><b>两个环两行</b>，重跑不插不清、行的 id 不变</li>
 *   <li><b>裁决各管各的</b>：认可其中一个，另一个放宽公理后重开——被认可的那一行 path
 *       仍是它自己的环，没有被另一个环的路径盖掉</li>
 *   <li><b>撤掉只在一个环上的边</b>，只影响那一个</li>
 *   <li><b>别的种类照旧按首尾两条</b>：反对称那一对重跑仍是同一行</li>
 * </ol>
 */
class ACycleIsKeyedByAllItsFactsTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID etype, UUID user, UUID partOf, UUID reportsTo) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID reportsTo = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'cycle-key-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'cycle-key-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'cycle-key-test')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Cycle Key Reviewer')
                """, user, org, "cycle-key-" + user + "@test.local");
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types
                     (id, kb_id, key, label, temporal, is_transitive, is_asymmetric)
                 VALUES (?, ?, 'part_of', 'part_of', 'state', TRUE, FALSE),
                        (?, ?, 'reports_to', 'reports_to', 'state', FALSE, TRUE)
                """, partOf, kb, reportsTo, kb);
        return new Fixture(org, kb, etype, user, partOf, reportsTo);
    }

    private UUID entity(Fixture f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), f.etype(), name);
        return id;
    }

    /** 一条从此刻起一直成立的事实：大家同时成立，这里只问形状。 */
    private UUID fact(Fixture f, UUID s, UUID p, UUID o) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, f.kb(), s, p, o);
        return id;
    }

    record CycleRow(UUID id, List<UUID> path, String status, String resolution) {
    }

    /** 环行，按 path 排。 */
    private List<CycleRow> cycleRows(Fixture f) {
        List<CycleRow> rows = jdbc.query(
                "SELECT id, path, status, resolution FROM axiom_violations WHERE kb_id = ?"
                        + " AND kind = 'cycle' ORDER BY path",
                (rs, i) -> new CycleRow(rs.getObject(1, UUID.class),
                        List.of((UUID[]) rs.getArray(2).getArray()), rs.getString(3),
                        rs.getString(4)), f.kb());
        return rows;
    }

    @Test
    void twoCyclesSharingTheirEndsAreTwoRows() {
        Fixture f = seed();
        try {
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            UUID c = entity(f, "C");
            UUID d = entity(f, "D");
            UUID x = entity(f, "X");
            // 按 id 先后插：A→B 最小，X→A 收尾最后一条。两个环的首尾都是这两条
            UUID ab = fact(f, a, f.partOf(), b);
            UUID bc = fact(f, b, f.partOf(), c);
            UUID bd = fact(f, b, f.partOf(), d);
            UUID cx = fact(f, c, f.partOf(), x);
            UUID dx = fact(f, d, f.partOf(), x);
            UUID xa = fact(f, x, f.partOf(), a);
            List<UUID> viaC = sorted(ab, bc, cx, xa);
            List<UUID> viaD = sorted(ab, bd, dx, xa);

            // ---- 一、两个环两行；重跑稳定
            var first = reasoning.run(f.kb());
            assertEquals(2, first.found());
            assertEquals(2, first.inserted(), "两个环各插一行，不是一行被另一行覆盖");
            List<CycleRow> rows = cycleRows(f);
            assertEquals(List.of(viaC, viaD), rows.stream().map(CycleRow::path).toList());
            for (int i = 0; i < 3; i++) {
                var again = reasoning.run(f.kb());
                assertEquals(0, again.inserted());
                assertEquals(0, again.cleared());
                assertEquals(rows.stream().map(CycleRow::id).toList(),
                        cycleRows(f).stream().map(CycleRow::id).toList(), "行不换 id");
            }
            UUID idC = rows.get(0).id();
            UUID idD = rows.get(1).id();

            // ---- 二、裁决各管各的
            reasoning.decide(f.kb(), idC, "accepted", f.user());
            reasoning.decide(f.kb(), idD, "axiom_relaxed", f.user());
            var r = reasoning.run(f.kb());
            assertEquals(1, r.reopened(), "放宽公理的那个环还在，重开它——只重开它");
            List<CycleRow> after = cycleRows(f);
            assertEquals(2, after.size());
            assertEquals(idC, after.get(0).id());
            assertEquals(viaC, after.get(0).path());
            assertEquals("resolved", after.get(0).status());
            assertEquals("accepted", after.get(0).resolution());
            assertEquals(idD, after.get(1).id());
            assertEquals("open", after.get(1).status());

            // ---- 三、撤掉只在经 D 那个环上的 B→D
            reasoning.retractFromViolation(f.kb(), idD, bd, f.user());
            var r2 = reasoning.run(f.kb());
            assertEquals(1, r2.found());
            assertEquals(0, r2.reopened());
            assertEquals(0, r2.inserted(), "只剩经 C 的环，而它认可过");
            List<CycleRow> finalRows = cycleRows(f);
            assertEquals("accepted", finalRows.get(0).resolution());
            assertEquals("fact_retracted", finalRows.get(1).resolution());

            // ---- 四、别的种类照旧按首尾两条
            UUID boss = fact(f, c, f.reportsTo(), d);
            UUID back = fact(f, d, f.reportsTo(), c);
            reasoning.run(f.kb());
            var again = reasoning.run(f.kb());
            assertEquals(0, again.inserted());
            var pair = jdbc.query(
                    "SELECT left_fact, right_fact FROM axiom_violations WHERE kb_id = ?"
                            + " AND kind = 'asymmetry'",
                    (rs, i) -> new UUID[]{rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class)}, f.kb());
            assertEquals(1, pair.size());
            UUID lo = boss.compareTo(back) < 0 ? boss : back;
            UUID hi = boss.compareTo(back) < 0 ? back : boss;
            assertEquals(lo, pair.get(0)[0]);
            assertEquals(hi, pair.get(0)[1]);
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    private static List<UUID> sorted(UUID... ids) {
        List<UUID> out = new ArrayList<>(List.of(ids));
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
