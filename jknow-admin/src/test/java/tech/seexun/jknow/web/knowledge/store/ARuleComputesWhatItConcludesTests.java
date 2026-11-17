package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.BusinessRulesService;
import tech.seexun.jknow.store.BusinessRulesService.ConditionInput;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_rule_computes_what_it_concludes.rs（324 行，3 例）——算出来的结论
 * 打在真库上（0032 / #488）：存进 conclude_expr 的树编译回来还是同一棵；算式读的那几条
 * 读数一并进前提；写坏的算式在建的时候就被拦下（不是等到物化时「不推东西」）。
 * 断言逐条对译。
 */
class ARuleComputesWhatItConcludesTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;
    @Autowired
    BusinessRulesService rules;

    record Fixture(UUID org, UUID kb, UUID well, UUID revenue, UUID cost, UUID margin, UUID w1) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID well = Uuids.nowV7();
        UUID revenue = Uuids.nowV7();
        UUID cost = Uuids.nowV7();
        UUID margin = Uuids.nowV7();
        UUID w1 = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'calc-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'calc-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'calc-test')",
                kb, ws);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'well', 'Well')",
                well, kb);
        for (Object[] row : new Object[][]{{revenue, "revenue"}, {cost, "cost"}, {margin, "margin"}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype)
                     VALUES (?, ?, ?, ?, 'attribute', 'number')
                    """, row[0], kb, row[1], row[1]);
        }
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'W-1')",
                w1, kb, well);
        return new Fixture(org, kb, well, revenue, cost, margin, w1);
    }

    private UUID attr(Fixture f, UUID predicate, double value) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value,
                                    valid_from, valid_from_precision, confidence)
                 VALUES (?, ?, ?, ?, ?::jsonb, '2023-06-01T00:00:00Z', 'day', 0.9)
                """, id, f.kb(), f.w1(), predicate, "{\"value\": " + value + "}");
        return id;
    }

    /** 只有一个条件：revenue 有记录。算式读的是 revenue 与 cost。 */
    private List<ConditionInput> presentRevenue(Fixture f) {
        return List.of(new ConditionInput(0, f.revenue(), "present", null, "x"));
    }

    private String marginExpr(Fixture f) {
        return "{\"op\":\"sub\",\"l\":{\"attr\":\"" + f.revenue()
                + "\"},\"r\":{\"attr\":\"" + f.cost() + "\"}}";
    }

    @Test
    void aComputedConclusionLandsWithTheReadingsItRead() {
        Fixture f = seed();
        try {
            UUID rev = attr(f, f.revenue(), 300.0);
            UUID cost = attr(f, f.cost(), 120.0);

            UUID rule = rules.create(f.kb(), "margin", "", f.well(), "computed", null, f.margin(),
                    null, readJson(marginExpr(f)), null, presentRevenue(f));

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.attributeRules(), "规则要编译得出来");
            assertEquals(1, report.ruleHits());

            record Row(UUID id, com.fasterxml.jackson.databind.JsonNode value, UUID ruleId) {
            }
            List<Row> rows = jdbc.query("""
                    SELECT id, object_value, attribute_rule_id FROM derived_facts WHERE kb_id = ?
                    """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                    readJson(rs.getString(2)), rs.getObject(3, UUID.class)), f.kb());
            assertEquals(1, rows.size());
            assertEquals(rule, rows.get(0).ruleId(), "结论指回它的规则");
            assertEquals(180.0, rows.get(0).value().path("value").asDouble(), "300 − 120");

            // 算式读的两条都在前提里。条件只提到 revenue，可 cost 也让这个数成立——
            // 漏掉它，这一行就说不出自己凭什么，改了 cost 也不会退场
            List<UUID> got = jdbc.queryForList("""
                    SELECT premise_fact_id FROM fact_derivations WHERE derived_fact_id = ?
                     ORDER BY seq
                    """, UUID.class, rows.get(0).id());
            assertEquals(2, got.size());
            assertTrue(got.contains(rev) && got.contains(cost));

            // 改掉 cost，值跟着变——这正是「算完抄回来当断言」做不到的那一半
            jdbc.update("UPDATE facts SET object_value = '{\"value\": 50.0}'::jsonb WHERE id = ?",
                    cost);
            var again = reasoning.materialize(f.kb());
            assertEquals(1, again.invalidated(), "180 那一行退场");
            assertEquals(1, again.inserted(), "250 那一行落下");
            Double live = jdbc.queryForObject("""
                    SELECT (object_value->>'value')::float8 FROM derived_facts
                      WHERE kb_id = ? AND invalidated_at IS NULL
                    """, Double.class, f.kb());
            assertEquals(250.0, live);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 缺一条读数不落行，而不是落一行零。没记不等于零（0032）。 */
    @Test
    void aMissingReadingLandsNothing() {
        Fixture f = seed();
        try {
            attr(f, f.revenue(), 300.0);
            rules.create(f.kb(), "margin", "", f.well(), "computed", null, f.margin(), null,
                    readJson(marginExpr(f)), null, presentRevenue(f));
            var report = reasoning.materialize(f.kb());
            assertEquals(0, report.ruleHits(), "cost 没记，margin 就不该有");
            Long n = jdbc.queryForObject("SELECT count(*) FROM derived_facts WHERE kb_id = ?",
                    Long.class, f.kb());
            assertEquals(0L, n);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 写坏的算式在建的时候就被拒（#494 那次的教训）。 */
    @Test
    void aBrokenExpressionIsRefusedWhereItIsWritten() {
        Fixture f = seed();
        try {
            String[][] cases = {
                    {"{\"op\": \"pow\", \"l\": {\"const\": 2}, \"r\": {\"const\": 3}}",
                            "四则以外的运算不收"},
                    {"{\"const\": 12}", "一条读数都不读的算式就是个常量，该走 attribute 那一支"},
                    {"{\"attr\": \"not-a-uuid\"}", "认不出的谓词不收"},
                    {"{\"op\": \"sub\", \"l\": {\"attr\": \"" + f.revenue()
                            + "\"}, \"r\": {\"attr\": \"" + Uuids.nowV7() + "\"}}",
                            "不在这个库里的属性不收"},
            };
            for (String[] c : cases) {
                boolean refused = false;
                try {
                    rules.create(f.kb(), "r-" + Uuids.nowV7(), "", f.well(), "computed", null,
                            f.margin(), null, readJson(c[0]), null, presentRevenue(f));
                } catch (AppException e) {
                    refused = true;
                }
                assertTrue(refused, c[1]);
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode readJson(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
