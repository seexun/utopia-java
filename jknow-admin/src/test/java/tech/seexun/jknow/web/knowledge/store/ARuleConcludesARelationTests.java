package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
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
 * 移植自 tests/a_rule_concludes_a_relation.rs（547 行，3 例）——A joined rule has to see both
 * ends of the edge in the database（0047）：X 仍按主类筛、Y 是声明的连接够得着的任何实体；
 * 持久行带实体宾语与三段式证明；被拦下的关系候选留在候选里——审核队列报的正是图拦下的。
 * 断言逐条对译。
 */
class ARuleConcludesARelationTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;
    @Autowired
    BusinessRulesService rules;

    record Fixture(UUID org, UUID kb, UUID well, UUID pressure, UUID depth, UUID supplies,
                   UUID upstreamOf, UUID x, UUID y) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID well = Uuids.nowV7();
        UUID field = Uuids.nowV7();
        UUID pressure = Uuids.nowV7();
        UUID depth = Uuids.nowV7();
        UUID supplies = Uuids.nowV7();
        UUID upstreamOf = Uuids.nowV7();
        UUID x = Uuids.nowV7();
        UUID y = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'join-rule-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'join-rule-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'join-rule-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{well, "well", "Well"}, {field, "field", "Field"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        for (Object[] row : new Object[][]{{pressure, "pressure", "number"},
                {depth, "depth", "number"}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype)
                     VALUES (?, ?, ?, ?, 'attribute', ?)
                    """, row[0], kb, row[1], row[1], row[2]);
        }
        for (Object[] row : new Object[][]{{supplies, "supplies"}, {upstreamOf, "upstream_of"}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind)
                     VALUES (?, ?, ?, ?, 'relation')
                    """, row[0], kb, row[1], row[1]);
        }
        for (Object[] row : new Object[][]{{x, well, "W-1"}, {y, field, "F-2"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        return new Fixture(org, kb, well, pressure, depth, supplies, upstreamOf, x, y);
    }

    private UUID attr(Fixture f, UUID subject, UUID predicate, double value, String from) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value,
                                    valid_from, valid_from_precision, confidence)
                 VALUES (?, ?, ?, ?, ?::jsonb, ?::timestamptz, 'day', 0.9)
                """, id, f.kb(), subject, predicate, "{\"value\": " + value + "}", from);
        return id;
    }

    private UUID edge(Fixture f, UUID predicate, String from, String to) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    valid_from, valid_from_precision,
                                    valid_to, valid_to_precision, confidence)
                 VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'day', ?::timestamptz, 'day', 0.9)
                """, id, f.kb(), f.x(), predicate, f.y(), from, to);
        return id;
    }

    private UUID edgeTo(Fixture f, UUID predicate, UUID object, String from, String to) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    valid_from, valid_from_precision,
                                    valid_to, valid_to_precision, confidence)
                 VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'day', ?::timestamptz, 'day', 0.9)
                """, id, f.kb(), f.x(), predicate, object, from, to);
        return id;
    }

    private static List<ConditionInput> conditions(Fixture f) {
        return List.of(
                new ConditionInput(0, f.pressure(), "gt",
                        JsonNodeFactory.instance.numberNode(80.0), "x"),
                new ConditionInput(0, f.depth(), "lt",
                        JsonNodeFactory.instance.numberNode(500.0), "y"));
    }

    private static List<ConditionInput> pressureCondition(Fixture f, String side) {
        return List.of(new ConditionInput(0, f.pressure(), "gt",
                JsonNodeFactory.instance.numberNode(80.0), side));
    }

    /** 只改结论、不带条件的更新会清掉连接而把 y 侧条件留下（#1109）：整条更新被拒。 */
    @Test
    void droppingTheJoinIsRefusedWhileTheRuleKeepsYConditions() {
        Fixture f = seed();
        try {
            UUID rule = rules.create(f.kb(), "high pressure on the joined entity", "", f.well(),
                    "relation", null, f.upstreamOf(), null, null, f.supplies(),
                    pressureCondition(f, "y"));
            var attribute = new BusinessRulesService.ConclusionInput("attribute", null, f.depth(),
                    JsonNodeFactory.instance.numberNode(1.0), null, null);
            java.util.function.Supplier<Object[]> saved = () -> jdbc.queryForObject("""
                    SELECT r.conclusion, r.join_predicate_id, c.subject_side
                       FROM attribute_rules r JOIN attribute_rule_conditions c ON c.rule_id = r.id
                      WHERE r.id = ?
                    """, (rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, UUID.class),
                    rs.getString(3)}, rule);

            boolean refused = false;
            try {
                rules.update(f.kb(), rule, null, null, null, null, attribute);
            } catch (AppException e) {
                refused = "condition_side_without_join".equals(e.code().orElse(null));
            }
            assertTrue(refused, "the Y condition the rule keeps still needs its join");
            Object[] after = saved.get();
            assertEquals("relation", after[0]);
            assertEquals(f.supplies(), after[1]);
            assertEquals("y", after[2]);

            // 同一次更新里把条件换到 X 侧，去掉连接就是合法的
            rules.update(f.kb(), rule, null, null, null, pressureCondition(f, "x"), attribute);
            Object[] onX = saved.get();
            assertEquals("attribute", onX[0]);
            assertEquals(null, onX[1]);
            assertEquals("x", onX[2]);

            // 修复之前存下的非法规则仍然能改名、停用：只有动了条件或结论才校验
            jdbc.update("UPDATE attribute_rule_conditions SET subject_side = 'y' WHERE rule_id = ?",
                    rule);
            rules.update(f.kb(), rule, "disabled", null, false, null, null);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    @Test
    void aJoinedRuleReadsTheOtherSideOfADeclaredEdge() {
        Fixture f = seed();
        try {
            // An older assertion of the same edge must only win where it holds.
            edge(f, f.upstreamOf(), "2024-01-01T00:00:00Z", "2024-01-15T00:00:00Z");
            UUID pressure = attr(f, f.x(), f.pressure(), 120.0, "2024-01-01T00:00:00Z");
            UUID depth = attr(f, f.y(), f.depth(), 300.0, "2024-01-15T00:00:00Z");
            UUID join = edge(f, f.supplies(), "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

            rules.create(f.kb(), "upstream high pressure", "", f.well(), "relation", null,
                    f.upstreamOf(), null, null, f.supplies(), conditions(f));

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.attributeRules());
            assertEquals(1, report.ruleHits(),
                    "the Y reading is outside the rule subject type but inside the declared join");

            record Row(UUID derived, UUID subject, UUID predicate, UUID object,
                       OffsetDateTime from) {
            }
            List<Row> rows = jdbc.query("""
                    SELECT id, subject_id, predicate_id, object_id, valid_from
                           FROM derived_facts
                          WHERE kb_id = ? AND invalidated_at IS NULL
                    """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class), rs.getObject(5, OffsetDateTime.class)), f.kb());
            assertEquals(1, rows.size());
            Row row = rows.get(0);
            assertEquals(f.x(), row.subject());
            assertEquals(f.upstreamOf(), row.predicate());
            assertEquals(f.y(), row.object());
            assertEquals(OffsetDateTime.parse("2024-01-15T00:00Z"),
                    row.from().withOffsetSameInstant(java.time.ZoneOffset.UTC),
                    "validity starts at the latest of the three premises");

            List<Object[]> premises = jdbc.query("""
                    SELECT premise_fact_id, premise_derived_id
                       FROM fact_derivations
                      WHERE derived_fact_id = ?
                      ORDER BY seq
                    """, (rs, i) -> new Object[]{rs.getObject(1), rs.getObject(2)}, row.derived());
            List<UUID> asserted = premises.stream().map(p -> (UUID) p[0])
                    .filter(java.util.Objects::nonNull).toList();
            assertEquals(List.of(pressure, depth, join), asserted,
                    "both sides and the edge are the complete proof");
            assertTrue(premises.stream().allMatch(p -> p[1] == null));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 一条规则推出的关系边撞上断言时不落地，而审核队列要看得见它（0017，0047 决定 3）。 */
    @Test
    void aRelationTheGraphRefusesStillReachesTheReviewQueue() {
        Fixture f = seed();
        try {
            // upstream_of 是 functional：X 已经断言了另一个上游 Z，规则推出的 X → Y
            // 与它区间相交，asserted > derived，这条派生不落地
            jdbc.update("UPDATE relation_types SET functional = true WHERE id = ?", f.upstreamOf());
            UUID z = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, type_id, canonical_name)
                     VALUES (?, ?, (SELECT type_id FROM entities WHERE id = ?), 'F-3')
                    """, z, f.kb(), f.y());
            UUID otherUpstream = edgeTo(f, f.upstreamOf(), z, "2024-01-01T00:00:00Z",
                    "2024-12-31T00:00:00Z");
            attr(f, f.x(), f.pressure(), 120.0, "2024-01-01T00:00:00Z");
            attr(f, f.y(), f.depth(), 300.0, "2024-01-15T00:00:00Z");
            UUID join = edge(f, f.supplies(), "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");
            UUID rule = rules.create(f.kb(), "upstream high pressure", "", f.well(), "relation",
                    null, f.upstreamOf(), null, null, f.supplies(), conditions(f));

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.blocked(), "the relation lost to the asserted upstream");
            assertEquals(0, report.inserted());
            assertEquals(0, report.ruleHits(), "a refused conclusion is not a conclusion that"
                    + " stands");
            Long derivedRows = jdbc.queryForObject(
                    "SELECT count(*) FROM derived_facts WHERE kb_id = ? AND invalidated_at IS NULL",
                    Long.class, f.kb());
            assertEquals(0L, derivedRows, "nothing lands");

            var check = reasoning.run(f.kb());
            assertEquals(1, check.contradictions());
            record Row(UUID left, UUID right, String detail) {
            }
            List<Row> rows = jdbc.query("""
                    SELECT left_fact, right_fact, detail FROM axiom_violations
                      WHERE kb_id = ? AND kind = 'derived_contradiction' AND status = 'open'
                    """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getString(3)), f.kb());
            assertEquals(1, rows.size());
            Row row = rows.get(0);
            assertEquals(otherUpstream, row.left(), "against the asserted upstream");
            assertEquals(join, row.right(), "keyed by the last asserted premise: the join edge");
            var detail = parse(row.detail());
            assertEquals("business_rule", detail.path("rule").asText());
            assertEquals("functional", detail.path("axiom").asText());
            assertEquals(rule.toString(), detail.path("attribute_rule_id").asText());
            assertEquals(f.y().toString(), detail.path("object_id").asText());

            // 同一次求解，第二遍不多不少：队列跟图对得上
            var again = reasoning.run(f.kb());
            assertEquals(0, again.inserted());
            assertEquals(0, again.cleared());
        } finally {
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode parse(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
