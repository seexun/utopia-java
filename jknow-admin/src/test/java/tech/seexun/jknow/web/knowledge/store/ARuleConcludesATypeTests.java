package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_rule_concludes_a_type.rs（974 行，9 例）——业务规则打在真库上
 * （0021 / #277）。求值本身的用例在 utopia-reason RulesTests（纯逻辑），这里钉的是那一层
 * 看不见的四样：命中落进 derived_facts 的字面值通道并挂 attribute_rule_id；前提链进
 * fact_derivations；重跑不产生第二行（同一性索引把 NULL 宾语认成同一条）；阈值抬高后
 * 旧结论作废而不是删除。断言逐条对译，不改写。
 */
class ARuleConcludesATypeTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;
    @Autowired
    tech.seexun.jknow.store.BusinessRulesService rules;

    record Fixture(UUID org, UUID kb, UUID well, UUID gasBearing, UUID thc, UUID category,
                   UUID isA, UUID w1) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID well = Uuids.nowV7();
        UUID gasBearing = Uuids.nowV7();
        UUID thc = Uuids.nowV7();
        UUID category = Uuids.nowV7();
        UUID isA = Uuids.nowV7();
        UUID w1 = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'rule-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'rule-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'rule-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{well, "well", "Well"},
                {gasBearing, "gas_well", "GasBearingWell"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        // 两个属性谓词 + 内建 is_a
        for (Object[] row : new Object[][]{{thc, "total_hydrocarbon", "全烃", "number"},
                {category, "interpretation_category", "解释结论", "text"},
                {isA, "is_a", "is a", "text"}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, builtin)
                     VALUES (?, ?, ?, ?, 'attribute', ?, ?)
                    """, row[0], kb, row[1], row[2], row[3], "is_a".equals(row[1]));
        }
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'W-1')",
                w1, kb, well);
        return new Fixture(org, kb, well, gasBearing, thc, category, isA, w1);
    }

    private record DerivedRow(UUID id, JsonNode objectValue, UUID objectId,
                              UUID attributeRuleId, OffsetDateTime validFrom,
                              OffsetDateTime invalidatedAt) {
    }

    private List<DerivedRow> derived(Fixture f) {
        return jdbc.query("""
                SELECT id, object_value, object_id, attribute_rule_id, valid_from, invalidated_at
                   FROM derived_facts WHERE kb_id = ? ORDER BY derived_at
                """, (rs, i) -> new DerivedRow(rs.getObject(1, UUID.class),
                readJson(rs.getString(2)), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, OffsetDateTime.class),
                rs.getObject(6, OffsetDateTime.class)), f.kb());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static JsonNode readJson(String raw) {
        try {
            return raw == null ? null : JSON.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 一条属性事实。 */
    private UUID attr(Fixture f, UUID predicate, String value, String from) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value,
                                    valid_from, valid_from_precision, confidence)
                 VALUES (?, ?, ?, ?, ?::jsonb, ?::timestamptz, 'day', 0.9)
                """, id, f.kb(), f.w1(), predicate,
                "{\"value\": " + jsonLiteral(value) + "}", from);
        return id;
    }

    private static String jsonLiteral(String raw) {
        try {
            return JSON.readTree(raw).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 一条规则：全烃 > 阈值 且 解释结论 ∈ {气测异常, 气测异常后效} → GasBearingWell。 */
    private UUID rule(Fixture f, double threshold) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, conclude_type_id)
                 VALUES (?, ?, 'gas-bearing', ?, 'typing', ?)
                """, id, f.kb(), f.well(), f.gasBearing());
        jdbc.update("""
                INSERT INTO attribute_rule_conditions (id, rule_id, seq, predicate_id, op, operand)
                 VALUES (?, ?, 0, ?, 'gt', ?::jsonb), (?, ?, 1, ?, 'in', ?::jsonb)
                """, Uuids.nowV7(), id, f.thc(), String.valueOf(threshold),
                Uuids.nowV7(), id, f.category(), "[\"气测异常\",\"气测异常后效\"]");
        return id;
    }

    @Test
    void aRuleTypesAWellAndSaysWhichReadingsDidIt() {
        Fixture f = seed();
        try {
            UUID a = attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            UUID b = attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            UUID r = rule(f, 8.0);

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.attributeRules(), "规则要被读进来");
            assertEquals(1, report.ruleHits(), "两条读数满足合取");

            List<DerivedRow> rows = derived(f);
            assertEquals(1, rows.size(), "一条派生归类");
            DerivedRow d = rows.get(0);
            assertNull(d.objectId(), "归类走字面值通道，没有实体宾语");
            assertEquals("gas_well", d.objectValue().path("class").asText(),
                    "没有 IRI 时退回 key，记的是类而不是标签");
            assertEquals(r, d.attributeRuleId(), "结论指回它的规则");
            assertEquals(OffsetDateTime.parse("2023-06-01T00:00Z"),
                    d.validFrom().withOffsetSameInstant(java.time.ZoneOffset.UTC),
                    "区间取两条前提的交集");

            // 前提链：凭哪两条读数
            List<UUID> got = jdbc.queryForList("""
                    SELECT premise_fact_id FROM fact_derivations WHERE derived_fact_id = ?
                     ORDER BY seq
                    """, UUID.class, d.id());
            assertEquals(2, got.size(), "两条前提都要记下来");
            assertTrue(got.contains(a) && got.contains(b));

            // 重跑不该多一行。字面值结论的 object_id 是 NULL，而 Postgres 默认把 NULL
            // 当互不相同——同一性索引写错的话这里每跑一轮多一条
            var again = reasoning.materialize(f.kb());
            assertEquals(0, again.inserted(), "同一条结论重跑不再插");
            assertEquals(0, again.invalidated(), "也不该把自己判成陈旧");
            assertEquals(1, derived(f).size(), "库里还是一行");
        } finally {
            cleanup(f);
        }
    }

    /** 两组「或」（0029 / #476）：第二组独自成立时，落下的结论只挂它自己那条读数。 */
    @Test
    void eitherGroupConcludesAndNamesOnlyItsOwnReading() {
        Fixture f = seed();
        try {
            UUID a = attr(f, f.thc(), "25.0", "2023-06-01T00:00:00Z");
            UUID id = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, conclude_type_id)
                     VALUES (?, ?, 'gas-bearing (two ways)', ?, 'typing', ?)
                    """, id, f.kb(), f.well(), f.gasBearing());
            jdbc.update("""
                    INSERT INTO attribute_rule_conditions
                        (id, rule_id, group_seq, seq, predicate_id, op, operand)
                     VALUES (?, ?, 0, 0, ?, 'gt', '8'::jsonb),
                            (?, ?, 0, 1, ?, 'in', '["气测异常"]'::jsonb),
                            (?, ?, 1, 0, ?, 'gt', '20'::jsonb)
                    """, Uuids.nowV7(), id, f.thc(), Uuids.nowV7(), id, f.category(),
                    Uuids.nowV7(), id, f.thc());

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.ruleHits(), "第二组独自成立，一次命中");

            List<DerivedRow> rows = derived(f);
            assertEquals(1, rows.size());
            List<UUID> got = jdbc.queryForList(
                    "SELECT premise_fact_id FROM fact_derivations WHERE derived_fact_id = ?",
                    UUID.class, rows.get(0).id());
            assertEquals(List.of(a), got, "前提只有这一组用到的那条读数");
        } finally {
            cleanup(f);
        }
    }

    /** 阈值抬到读数之上再跑：结论作废而不是删除——记录轴上留着「我们曾据此推出」。 */
    @Test
    void raisingTheThresholdInvalidatesWithoutDeleting() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            UUID r = rule(f, 8.0);
            reasoning.materialize(f.kb());
            assertEquals(1, derived(f).size());

            // 阈值抬到 20：同一份数据不再满足
            jdbc.update(
                    "UPDATE attribute_rule_conditions SET operand = '20'::jsonb"
                            + " WHERE rule_id = ? AND seq = 0", r);
            var report = reasoning.materialize(f.kb());
            assertEquals(0, report.ruleHits(), "抬高之后不再命中");
            assertEquals(1, report.invalidated());

            List<DerivedRow> rows = derived(f);
            assertEquals(1, rows.size(), "行还在——作废不是删除");
            assertNotNull(rows.get(0).invalidatedAt(), "标了作废时刻");
        } finally {
            cleanup(f);
        }
    }

    /** 关掉规则等同于前提消失：结论下一轮失效，而规则行还在。 */
    @Test
    void disablingARuleRetiresWhatItConcluded() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            UUID r = rule(f, 8.0);
            reasoning.materialize(f.kb());

            jdbc.update("UPDATE attribute_rules SET enabled = FALSE WHERE id = ?", r);
            var report = reasoning.materialize(f.kb());
            assertEquals(0, report.attributeRules(), "关掉的规则不参与求值");
            assertEquals(1, report.invalidated(), "它推出来的东西跟着退场");
            assertNotNull(derived(f).get(0).invalidatedAt());
        } finally {
            cleanup(f);
        }
    }

    /** 撤掉一条前提事实，结论跟着失效——与公理那一趟同一条路。 */
    @Test
    void retractingAReadingRetiresTheConclusion() {
        Fixture f = seed();
        try {
            UUID a = attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            rule(f, 8.0);
            reasoning.materialize(f.kb());
            assertNull(derived(f).get(0).invalidatedAt());

            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", a);
            var report = reasoning.materialize(f.kb());
            assertEquals(0, report.ruleHits(), "少了全烃这条前提就不成立");
            assertEquals(1, report.invalidated());
        } finally {
            cleanup(f);
        }
    }

    /** 派生的结论不写 entities.type_id（0021 决策 2）：断言类型是人的，规则的结论是叠加层。 */
    @Test
    void theAssertedTypeIsLeftAlone() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            rule(f, 8.0);
            reasoning.materialize(f.kb());

            UUID t = jdbc.queryForObject("SELECT type_id FROM entities WHERE id = ?", UUID.class,
                    f.w1());
            assertEquals(f.well(), t, "实体的断言类型仍然是 Well");
            List<DerivedRow> rows = derived(f);
            assertEquals(1, rows.size(), "GasBearingWell 只作为派生存在");
            assertNull(rows.get(0).objectId());
            // 结论落在内建 is_a 上
            UUID p = jdbc.queryForObject("SELECT predicate_id FROM derived_facts WHERE id = ?",
                    UUID.class, rows.get(0).id());
            assertEquals(f.isA(), p);
        } finally {
            cleanup(f);
        }
    }

    /** 改结论：从「推一个类」改成「推一个属性值」——旧结论作废，半截状态清空。 */
    @Test
    void changingTheConclusionRetiresTheOldOne() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            UUID r = rule(f, 8.0);
            reasoning.materialize(f.kb());
            List<DerivedRow> rows = derived(f);
            assertEquals(1, rows.size());
            assertEquals("{\"class\":\"gas_well\"}", rows.get(0).objectValue().toString());

            // 结论换成属性：含气性 = 含气
            UUID verdict = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype)
                     VALUES (?, ?, 'gas_verdict', '含气性', 'attribute', 'text')
                    """, verdict, f.kb());
            rules.update(f.kb(), r, null, null, null, null,
                    new tech.seexun.jknow.store.BusinessRulesService.ConclusionInput(
                            "attribute", null, verdict,
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                    .textNode("含气"),
                            null, null));

            var kindClass = jdbc.queryForMap(
                    "SELECT conclusion, conclude_type_id AS class FROM attribute_rules WHERE id = ?",
                    r);
            assertEquals("attribute", kindClass.get("conclusion"));
            assertNull(kindClass.get("class"), "换了形状，旧的那一格要清空");

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.ruleHits(), "同一份读数照样命中，只是结论变了");
            assertEquals(1, report.invalidated(), "旧结论退场");

            List<DerivedRow> after = derived(f);
            assertEquals(2, after.size(), "两行：作废的旧结论 + 新结论");
            DerivedRow old = after.stream().filter(d -> d.invalidatedAt() != null).findFirst()
                    .orElseThrow();
            DerivedRow fresh = after.stream().filter(d -> d.invalidatedAt() == null).findFirst()
                    .orElseThrow();
            assertEquals("{\"class\":\"gas_well\"}", old.objectValue().toString());
            assertEquals("{\"value\":\"含气\"}", fresh.objectValue().toString());
        } finally {
            cleanup(f);
        }
    }

    /** 展不完的组合要留在规则上，不是只在跑完那一刻说一句；读数收回来要归零。 */
    @Test
    void aCappedExpansionIsRemembered() {
        Fixture f = seed();
        try {
            List<UUID> thcFacts = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) {
                thcFacts.add(attr(f, f.thc(), String.valueOf(10.0 + i),
                        "2023-06-" + String.format("%02d", i + 1) + "T00:00:00Z"));
                attr(f, f.category(), "\"气测异常\"",
                        "2023-07-" + String.format("%02d", i + 1) + "T00:00:00Z");
            }
            UUID r = rule(f, 8.0);

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.ruleCapped());
            assertEquals(0, report.ruleHits(), "展不开就整个跳过——这正是要说出来的原因");
            assertEquals(1, cappedOf(f, r), "落在规则上，卡片读得到");

            // 读数收回到 1 × 9：能展开了
            for (UUID id : thcFacts.subList(1, thcFacts.size())) {
                jdbc.update("DELETE FROM facts WHERE id = ?", id);
            }
            var again = reasoning.materialize(f.kb());
            assertEquals(0, again.ruleCapped());
            assertTrue(again.ruleHits() > 0);
            assertEquals(0, cappedOf(f, r), "归零，不累积");
        } finally {
            cleanup(f);
        }
    }

    /** 卡片上那个数字点开之后看见的东西：谁被标住了，以及凭哪几条读数。 */
    @Test
    void theMatchesListSaysWhoAndWhy() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"气测异常\"", "2023-06-01T00:00:00Z");
            UUID r = rule(f, 8.0);
            reasoning.materialize(f.kb());

            var matches = rules.matches(f.kb(), r, 20, 0);
            assertEquals(1, matches.total());
            assertEquals("W-1", matches.rows().get(0).path("entity").asText());
            assertEquals("GasBearingWell", matches.rows().get(0).path("concluded").asText(),
                    "读出来是标签，不是库里的 key");
            List<String> premises = new java.util.ArrayList<>();
            matches.rows().get(0).path("premises").forEach(v -> premises.add(v.asText()));
            assertEquals(List.of("全烃 = 12.3", "解释结论 = 气测异常"), premises,
                    "前提按顺序，读得出「凭什么」");

            // 规则关掉之后结论退场，列表也就空了——数字与列表是同一份事实
            rules.update(f.kb(), r, null, null, false, null, null);
            reasoning.materialize(f.kb());
            var empty = rules.matches(f.kb(), r, 20, 0);
            assertEquals(0, empty.total());
            assertTrue(empty.rows().isEmpty());
        } finally {
            cleanup(f);
        }
    }

    /** 「不属于」写进库、读回来、真的判得动（0029 / #476）：从前 parse_operand 漏了它。 */
    @Test
    void aRuleThatSaysNotOneOfIsNotSilentlySkipped() {
        Fixture f = seed();
        try {
            attr(f, f.thc(), "12.3", "2023-06-01T00:00:00Z");
            attr(f, f.category(), "\"水层\"", "2023-06-01T00:00:00Z");

            UUID id = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, conclude_type_id)
                     VALUES (?, ?, 'not-water', ?, 'typing', ?)
                    """, id, f.kb(), f.well(), f.gasBearing());
            jdbc.update("""
                    INSERT INTO attribute_rule_conditions (id, rule_id, seq, predicate_id, op, operand)
                     VALUES (?, ?, 0, ?, 'not_in', '["气测异常"]'::jsonb)
                    """, Uuids.nowV7(), id, f.category());

            var report = reasoning.materialize(f.kb());
            assertEquals(1, report.attributeRules(), "规则要被读进来");
            assertEquals(1, report.ruleHits(),
                    "「水层」不属于 {气测异常}，这条规则应当命中——从前它整条被跳过");
            assertEquals(1, derived(f).size());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void renamingARuleUsesTheCreationNameLimitsBeforeAnyWrite() {
        Fixture f = seed();
        try {
            var conditions = List.of(new tech.seexun.jknow.store.BusinessRulesService.ConditionInput(
                    0, f.thc(), "gt", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .numberNode(5), "x"));
            UUID id = rules.create(f.kb(), "original", "", f.well(), "typing", f.gasBearing(),
                    null, null, null, null, conditions);
            var changed = List.of(new tech.seexun.jknow.store.BusinessRulesService.ConditionInput(
                    9, f.thc(), "lt", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .numberNode(10), "x"));
            var conclusion = new tech.seexun.jknow.store.BusinessRulesService.ConclusionInput(
                    "attribute", null, f.category(),
                    readJson("{\"value\":\"changed\"}"), null, null);
            for (String invalid : new String[]{"", " \t\n ", "a".repeat(81), "界".repeat(81)}) {
                var before = rules.list(f.kb());
                boolean refused = false;
                try {
                    rules.update(f.kb(), id, invalid, "changed", false, changed,
                            conclusion);
                } catch (AppException e) {
                    refused = "bad_rule_name".equals(e.code().orElse(null));
                }
                assertTrue(refused, "invalid name " + (invalid.isEmpty() ? "<empty>" : invalid));
                assertEquals(before, rules.list(f.kb()),
                        "a rejected combined update must leave name, conditions and conclusion"
                                + " unchanged");
            }
            for (String valid : new String[]{"a".repeat(80), "界".repeat(80), "  trimmed  "}) {
                rules.update(f.kb(), id, valid, null, null, null, null);
                var rows = rules.list(f.kb());
                assertEquals(valid.strip(), rows.get(0).path("name").asText());
                // Saving the same name remains valid.
                rules.update(f.kb(), id, valid, null, null, null, null);
            }
            rules.update(f.kb(), id, null, "changed", false, changed, conclusion);
            var rows = rules.list(f.kb());
            assertEquals("trimmed", rows.get(0).path("name").asText());
            assertEquals("changed", rows.get(0).path("description").asText());
            assertFalse(rows.get(0).path("enabled").asBoolean());
            assertEquals(9, rows.get(0).path("conditions").get(0).path("group").asInt());
            assertEquals("lt", rows.get(0).path("conditions").get(0).path("op").asText());
            assertEquals("attribute", rows.get(0).path("conclusion").asText());
            assertEquals("{\"value\":\"changed\"}",
                    rows.get(0).path("conclude_value").toString());
        } finally {
            cleanup(f);
        }
    }

    private static void assertFalse(boolean v) {
        org.junit.jupiter.api.Assertions.assertFalse(v);
    }

    /** 一条规则上次跑的时候，有几个实体没展开完。 */
    private int cappedOf(Fixture f, UUID ruleId) {
        var list = rules.list(f.kb());
        var row = list.stream().filter(r -> r.path("id").asText().equals(ruleId.toString()))
                .findFirst().orElseThrow(() -> new IllegalStateException("规则在列表里"));
        return row.path("capped").asInt();
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }
}
