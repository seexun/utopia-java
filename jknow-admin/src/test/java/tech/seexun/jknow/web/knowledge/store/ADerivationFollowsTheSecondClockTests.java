package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_derivation_follows_the_second_clock.rs（235 行，1 例）——派生那一档
 * 的记录轴（0019 / #549）：推出之前不该有，推翻之后还在——回放的图上留着**当时**推出的边，
 * 不是今天这套规则的结论。断言逐条对译。
 */
class ADerivationFollowsTheSecondClockTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;
    @Autowired
    GraphService graph;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    private record Fixture(UUID org, UUID kb, UUID atlas, UUID systems, UUID derived) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID atlas = Uuids.nowV7();
        UUID robotics = Uuids.nowV7();
        UUID systems = Uuids.nowV7();
        UUID premiseA = Uuids.nowV7();
        UUID premiseB = Uuids.nowV7();
        UUID rule = Uuids.nowV7();
        UUID derived = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'follow-clock-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'follow-clock-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'follow-clock-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Org')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, is_transitive)
                 VALUES (?, ?, 'part_of', 'part of', TRUE)
                """, partOf, kb);
        for (Object[] row : new Object[][]{{atlas, "Atlas"}, {robotics, "Acme Robotics"},
                {systems, "Acme Systems"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, etype, row[1]);
        }
        for (Object[] row : new Object[][]{{premiseA, atlas, robotics},
                {premiseB, robotics, systems}}) {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, recorded_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                    """, row[0], kb, row[1], partOf, row[2], t("2026-03-10T00:00:00Z"));
        }
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) VALUES (?, ?, ?, 'transitive')",
                rule, kb, partOf);
        jdbc.update("""
                INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, rule_id,
                                            derived_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                """, derived, kb, atlas, partOf, systems, rule, t("2026-03-11T00:00:00Z"));
        int seq = 0;
        for (UUID premise : List.of(premiseA, premiseB)) {
            jdbc.update("""
                    INSERT INTO fact_derivations (derived_fact_id, premise_fact_id, seq)
                     VALUES (?, ?, ?)
                    """, derived, premise, seq++);
        }
        return new Fixture(org, kb, atlas, systems, derived);
    }

    private List<String> derivedOf(UUID entity, String asOf) {
        return reasoning.derivedForEntity(f.kb(), entity, null, asOf == null ? null : t(asOf))
                .stream().map(view -> view.object()).toList();
    }

    private Fixture f;

    @Test
    void aDerivationIsAbsentBeforeItWasDrawnAndKeptUntilItWasWithdrawn() {
        f = seed();
        try {
            // ---- 一、现在：结论在，且是那一条
            assertEquals(List.of("Acme Systems"), derivedOf(f.atlas(), null),
                    "现在：引擎推出的结论要在");

            // ---- 二、三月一日：前提还没记下，结论也不该在——这是 #549 报的那半句。
            // 断言那一半同一时刻也是空的：两半必须说同一个时刻
            var facts = graph.entityDetail(f.kb(), f.atlas(), null, t("2026-03-01T00:00:00Z"))
                    .facts();
            assertTrue(facts.isEmpty(), "三月一日：两条前提都还没记下");
            assertTrue(derivedOf(f.atlas(), "2026-03-01T00:00:00Z").isEmpty(),
                    "推出之前的时刻不该有这条结论：它的前提一条都不在");
            assertTrue(derivedOf(f.systems(), "2026-03-01T00:00:00Z").isEmpty(),
                    "从宾语那一侧看回去也一样");

            // 前提记下了、结论还没推出的一天：断言在，派生不在
            facts = graph.entityDetail(f.kb(), f.atlas(), null, t("2026-03-10T12:00:00Z")).facts();
            assertEquals(1, facts.size(), "三月十日中午：Atlas 自己的那条前提已经记下");
            assertTrue(derivedOf(f.atlas(), "2026-03-10T12:00:00Z").isEmpty(),
                    "推出是次日的事");

            // ---- 三、四月一日推翻。推出与推翻之间的时刻，结论仍要在——0019 承诺的那半句
            jdbc.update("UPDATE derived_facts SET invalidated_at = ? WHERE id = ?", 
                    OffsetDateTime.parse("2026-04-01T00:00:00Z"), f.derived());
            assertEquals(List.of("Acme Systems"), derivedOf(f.atlas(), "2026-03-20T00:00:00Z"),
                    "推翻之前的时刻，当时推出的边要留在回放的图上");
            assertTrue(derivedOf(f.atlas(), null).isEmpty(), "现在：已推翻的结论不再出现");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
