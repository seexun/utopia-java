package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.reason.AxiomChecks;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_cycle_search_that_stops_says_so.rs（197 行，1 例）——环没搜完要
 * 说出来，而且没搜到的不算没了（#642），打在真库上。
 *
 * <p>一团稠密的强连通分量里，深度 12 以内的简单环是天文数字，找环撞上
 * MAX_CYCLES_PER_PREDICATE / MAX_CYCLE_STEPS 就停。钉三样：
 * <ol>
 *   <li><b>报告里说出来</b>：cycles_capped 数到这个谓词；落库的环不超过上限；同一份数据
 *       重跑不插不清——停在哪儿只取决于数据</li>
 *   <li><b>没搜完的谓词，上一轮的 open 环不清</b>：先有一个环，后来同一谓词上长出一团
 *       稠密的，搜索在够到那个环之前就停了。那一行留着——搜不到不等于不存在</li>
 *   <li><b>没截断的谓词照常清</b>：另一个谓词上的环，撤掉一条边，那一行照样被清掉</li>
 * </ol>
 */
class ACycleSearchThatStopsSaysSoTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID etype, UUID partOf, UUID locatedIn) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID locatedIn = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'cycle-cap-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'cycle-cap-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'cycle-cap-test')",
                kb, ws);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal, is_transitive)
                 VALUES (?, ?, 'part_of', 'part_of', 'eternal', TRUE),
                        (?, ?, 'located_in', 'located_in', 'eternal', TRUE)
                """, partOf, kb, locatedIn, kb);
        return new Fixture(org, kb, etype, partOf, locatedIn);
    }

    private UUID entity(Fixture f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), f.etype(), name);
        return id;
    }

    private UUID fact(Fixture f, UUID s, UUID p, UUID o) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, f.kb(), s, p, o);
        return id;
    }

    /** open 的环行，按谓词分。 */
    private List<UUID> openCycles(Fixture f, UUID predicate) {
        return jdbc.queryForList("""
                SELECT v.id FROM axiom_violations v JOIN facts lf ON lf.id = v.left_fact
                  WHERE v.kb_id = ? AND v.kind = 'cycle' AND v.status = 'open'
                    AND lf.predicate_id = ?
                  ORDER BY v.id
                """, UUID.class, f.kb(), predicate);
    }

    @Test
    void aCycleSearchThatStopsSaysSoAndKeepsWhatItDidNotReach() {
        Fixture f = seed();
        try {
            // 稠密那一团的节点先建：id 更小，搜索从它们起步，先撞上上限
            List<UUID> clique = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                clique.add(entity(f, "C" + i));
            }
            // ---- 一、先只有一个小环（两个 id 更大的节点）；另一个谓词上也有一个
            UUID x = entity(f, "X");
            UUID y = entity(f, "Y");
            fact(f, x, f.partOf(), y);
            fact(f, y, f.partOf(), x);
            UUID p = entity(f, "P");
            UUID q = entity(f, "Q");
            UUID pq = fact(f, p, f.locatedIn(), q);
            fact(f, q, f.locatedIn(), p);

            var r = reasoning.run(f.kb());
            assertEquals(2, r.found());
            assertEquals(0, r.cyclesCapped());
            List<UUID> small = openCycles(f, f.partOf());
            assertEquals(1, small.size());

            // ---- 二、part_of 上长出一团 12 个节点两两互指的
            for (UUID s : clique) {
                for (UUID o : clique) {
                    if (!s.equals(o)) {
                        fact(f, s, f.partOf(), o);
                    }
                }
            }
            var capped = reasoning.run(f.kb());
            assertEquals(1, capped.cyclesCapped(), "part_of 的环没搜完，要说出来");
            List<UUID> open = openCycles(f, f.partOf());
            assertTrue(open.contains(small.get(0)),
                    "搜索在够到 X↔Y 之前就停了——那一行留着，搜不到不等于不存在");
            assertTrue(open.size() <= AxiomChecks.MAX_CYCLES_PER_PREDICATE + 1,
                    "落库的环不超过上限（加上留着的那一行）：" + open.size());
            assertEquals(0, capped.cleared());

            // 同一份数据重跑：停在同一处，不插不清
            for (int i = 0; i < 2; i++) {
                var again = reasoning.run(f.kb());
                assertEquals(0, again.inserted());
                assertEquals(0, again.cleared());
                assertEquals(1, again.cyclesCapped());
                assertEquals(open, openCycles(f, f.partOf()));
            }

            // ---- 三、没截断的谓词照常清
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", pq);
            var swept = reasoning.run(f.kb());
            assertEquals(1, swept.cleared(), "located_in 没截断，撤掉一条边，那个环的行被清");
            assertTrue(openCycles(f, f.locatedIn()).isEmpty());
            assertEquals(open, openCycles(f, f.partOf()));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
