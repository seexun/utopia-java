package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.reason.AxiomChecks;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_rule_reads_what_a_rule_concluded.rs（613 行，5 例）——链（0030）：
 * 一条规则的结论是下一条规则的前提。前提链两列二选一（premise_fact_id /
 * premise_derived_id）；推出来的类带着区间当条件；叶子变了整条链退场；自反馈收敛；
 * 结论没变理由变了证明换。断言逐条对译。
 */
class ARuleReadsWhatARuleConcludedTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID well, UUID gasBearing, UUID producer, UUID thc,
                   UUID completed, UUID isA, UUID w1) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID well = Uuids.nowV7();
        UUID gasBearing = Uuids.nowV7();
        UUID producer = Uuids.nowV7();
        UUID thc = Uuids.nowV7();
        UUID completed = Uuids.nowV7();
        UUID isA = Uuids.nowV7();
        UUID w1 = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'chain-rule-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'chain-rule-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'chain-rule-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{well, "well", "Well"},
                {gasBearing, "gas_well", "GasBearingWell"},
                {producer, "producer", "Producer"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        for (Object[] row : new Object[][]{{thc, "total_hydrocarbon", "number", Boolean.FALSE},
                {completed, "completion", "text", Boolean.FALSE},
                {isA, "is_a", "text", Boolean.TRUE}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, builtin)
                     VALUES (?, ?, ?, ?, 'attribute', ?, ?)
                    """, row[0], kb, row[1], row[1], row[2], row[3]);
        }
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'W-1')",
                w1, kb, well);
        return new Fixture(org, kb, well, gasBearing, producer, thc, completed, isA, w1);
    }

    /** 一条属性事实，区间两端都给死——链上的区间是交出来的，端点含糊就看不出交没交。 */
    private UUID attr(Fixture f, UUID predicate, String value, String from, String to) {
        UUID id = Uuids.nowV7();
        // to 为空时精度一并空（Rust to.map(|_| "day")；CHECK：无终点不许带精度）
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value,
                                    valid_from, valid_from_precision,
                                    valid_to, valid_to_precision, confidence)
                 VALUES (?, ?, ?, ?, ?::jsonb, ?::timestamptz, 'day', ?::timestamptz,
                         CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'day' END, 0.9)
                """, id, f.kb(), f.w1(), predicate, "{\"value\": " + value + "}", from, to, to);
        return id;
    }

    /** 一条归类规则：主类上的一个属性条件 → 结论类。 */
    private UUID rule(Fixture f, String name, UUID subject, UUID predicate, String op,
                      String operand, UUID concludes) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, conclude_type_id)
                 VALUES (?, ?, ?, ?, 'typing', ?)
                """, id, f.kb(), name, subject, concludes);
        jdbc.update("""
                INSERT INTO attribute_rule_conditions (id, rule_id, seq, predicate_id, op, operand)
                 VALUES (?, ?, 0, ?, ?, ?::jsonb)
                """, Uuids.nowV7(), id, predicate, op, operand);
        return id;
    }

    record Derived(UUID id, String class_, OffsetDateTime validTo, OffsetDateTime invalidatedAt) {
    }

    private List<Derived> derived(Fixture f) {
        return jdbc.query("""
                SELECT id, object_value ->> 'class' AS class,
                        valid_to, invalidated_at
                   FROM derived_facts WHERE kb_id = ? ORDER BY derived_at, id
                """, (rs, i) -> new Derived(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class)),
                f.kb());
    }

    @Test
    void aConclusionIsAPremiseForTheNextRule() {
        Fixture f = seed();
        try {
            UUID thcFact = attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z", null);
            UUID done = attr(f, f.completed(), "\"yes\"", "2023-06-01T00:00:00Z", null);
            rule(f, "gas-bearing", f.well(), f.thc(), "gt", "8.0", f.gasBearing());
            rule(f, "producer", f.gasBearing(), f.completed(), "present", null, f.producer());

            var report = reasoning.materialize(f.kb());
            assertEquals(2, report.attributeRules());
            assertEquals(2, report.ruleHits(), "两条结论：含气井，以及在产井");
            assertTrue(report.ruleRounds() >= 2,
                    "链上一环一轮，一轮跑不出第二条：" + report.ruleRounds());
            assertFalse(report.ruleRoundsCapped(), "两环没到上限");

            List<Derived> rows = derived(f);
            assertEquals(2, rows.size());
            Derived gas = rows.stream().filter(d -> "gas_well".equals(d.class_())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("含气井那一行"));
            Derived prod = rows.stream().filter(d -> "producer".equals(d.class_())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("在产井那一行"));

            // 含气井：两条前提都是断言（第 0 组只凭那条全烃读数）
            List<Object[]> leaf = jdbc.query("""
                    SELECT premise_fact_id, premise_derived_id
                      FROM fact_derivations WHERE derived_fact_id = ? ORDER BY seq
                    """, (rs, i) -> new Object[]{rs.getObject(1), rs.getObject(2)}, gas.id());
            assertEquals(1, leaf.size());
            assertEquals(thcFact, leaf.get(0)[0]);
            assertEquals(null, leaf.get(0)[1]);

            // 在产井：一条断言 + 一条派生。这一条就是链
            List<Object[]> chained = jdbc.query("""
                    SELECT premise_fact_id, premise_derived_id
                      FROM fact_derivations WHERE derived_fact_id = ? ORDER BY seq
                    """, (rs, i) -> new Object[]{rs.getObject(1), rs.getObject(2)}, prod.id());
            assertEquals(2, chained.size(), "完井那条读数，加上「它是含气井」那条结论");
            boolean hasAsserted = false;
            boolean hasDerived = false;
            for (Object[] p : chained) {
                hasAsserted |= java.util.Objects.equals(done, p[0]) && p[1] == null;
                hasDerived |= p[0] == null && java.util.Objects.equals(gas.id(), p[1]);
            }
            assertTrue(hasAsserted, "断言前提落在 premise_fact_id 上");
            assertTrue(hasDerived, "派生前提落在 premise_derived_id 上，指的是含气井那一行");

            // 证明顺着链往下走一层：在产井 → 含气井 → 全烃那句话
            var proof = reasoning.proof(f.kb(), prod.id());
            assertTrue(proof != null, "在产井有证明");
            var step = proof.steps().stream().filter(s -> s.derived)
                    .findFirst().orElseThrow();
            assertEquals(1, step.premises.size(), "那一步自己也有前提，再往下一层");
            assertFalse(step.premises.get(0).derived, "再下一层是断言，链到此为止");

            // 重跑不该多出行，也不该把自己判成陈旧——链上那一行认的是上一轮留下的
            // 那个行 id，认错了这里会看见「作废一条、插入一条」
            var again = reasoning.materialize(f.kb());
            assertEquals(0, again.inserted(), "同一条链重跑不再插");
            assertEquals(0, again.invalidated(), "也不该把自己判成陈旧");
            assertEquals(2, derived(f).size());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 推出来的类带着区间：它是当条件进的，不是当筛子。 */
    @Test
    void aConcludedClassOnlyHoldsWhereItWasConcluded() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z");
            attr(f, f.completed(), "\"yes\"", "2022-01-01T00:00:00Z", null);
            rule(f, "gas-bearing", f.well(), f.thc(), "gt", "8.0", f.gasBearing());
            rule(f, "producer", f.gasBearing(), f.completed(), "present", null, f.producer());

            reasoning.materialize(f.kb());
            List<Derived> rows = derived(f);
            assertEquals(1, rows.size(), "只有含气井那一行；完井那条读数落在它成立的区间之外");
            assertEquals("gas_well", rows.get(0).class_());
            assertEquals(OffsetDateTime.parse("2021-01-01T00:00Z"),
                    rows.get(0).validTo().withOffsetSameInstant(java.time.ZoneOffset.UTC),
                    "结论的区间就是那条读数的区间");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 叶子变了，整条链退场——而且没有一行代码是为这件事写的。 */
    @Test
    void aChainRetiresWithTheReadingItStoodOn() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z", null);
            attr(f, f.completed(), "\"yes\"", "2023-06-01T00:00:00Z", null);
            UUID first = rule(f, "gas-bearing", f.well(), f.thc(), "gt", "8.0", f.gasBearing());
            rule(f, "producer", f.gasBearing(), f.completed(), "present", null, f.producer());
            reasoning.materialize(f.kb());
            assertEquals(2, derived(f).size());

            // 抬到 20：那条 12.3 的读数不再满足第一条规则
            jdbc.update(
                    "UPDATE attribute_rule_conditions SET operand = '20.0'::jsonb WHERE rule_id = ?",
                    first);
            var report = reasoning.materialize(f.kb());
            assertEquals(2, report.invalidated(), "含气井与站在它上面的在产井一起退场");
            assertEquals(0, report.inserted());

            List<Derived> rows = derived(f);
            assertEquals(2, rows.size(), "作废不是删除：记录轴上留着「曾据此推出」");
            assertTrue(rows.stream().allMatch(d -> d.invalidatedAt() != null), "两行都作废");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 一条规则读自己推出来的类，不动点照样收敛——上限是兜底，不是靠它停的。 */
    @Test
    void aRuleThatFeedsItselfSettles() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z", null);
            rule(f, "gas-bearing", f.well(), f.thc(), "gt", "8.0", f.gasBearing());
            // 主类是含气井，结论也是含气井：它读的正是自己推出来的那条
            rule(f, "still gas-bearing", f.gasBearing(), f.thc(), "gt", "8.0", f.gasBearing());

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.ruleHits(), "两条规则推出的是同一条结论，只算一条");
            assertTrue(report.ruleRounds() < AxiomChecks.MAX_DEPTH,
                    "收敛靠的是「这一轮没有新键」，不是撞上上限：" + report.ruleRounds());
            assertFalse(report.ruleRoundsCapped());
            assertEquals(1, derived(f).size(), "库里就一行");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 结论没变，理由变了：那一行留着，证明跟着换（0030 reproved）。 */
    @Test
    void aConclusionThatStandsOnANewReadingGetsANewProof() {
        Fixture f = seed();
        try {
            UUID first = attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z", null);
            rule(f, "gas-bearing", f.well(), f.thc(), "gt", "8.0", f.gasBearing());
            reasoning.materialize(f.kb());
            List<Derived> rows = derived(f);
            assertEquals(1, rows.size());
            UUID row = rows.get(0).id();

            // 撤掉那条读数，换一条区间一样、值也过阈值的：结论一字不差，凭的却是另一句话
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", first);
            UUID second = attr(f, f.thc(), "30.0", "2023-06-01T00:00:00Z", null);

            var report = reasoning.materialize(f.kb());
            assertEquals(0, report.inserted(), "结论没变，不该多一行");
            assertEquals(0, report.invalidated(), "也不该把它判成陈旧");
            assertEquals(1, report.reproved(), "证明换了一条");

            List<Object[]> now = jdbc.query("""
                    SELECT premise_fact_id, premise_derived_id
                      FROM fact_derivations WHERE derived_fact_id = ? ORDER BY seq
                    """, (rs, i) -> new Object[]{rs.getObject(1), rs.getObject(2)}, row);
            assertEquals(1, now.size());
            assertEquals(second, now.get(0)[0]);
            assertEquals(null, now.get(0)[1], "挂的是现在成立的那条读数");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
