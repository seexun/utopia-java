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
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/derived_facts_are_second_class.rs（313 行，1 例）——R1 打在真库上：
 * 断言优先于派生；前提撤了派生跟着失效（置 invalidated_at 不删）；证明存得下来
 * （fact_derivations 按 seq）；规则身份跨重跑稳定。还有精度 CHECK：交集把某一端算成
 * 无界时那一端的精度必须清掉，精度跟赢下这一端的前提走（0024）。断言逐条对译。
 */
class DerivedFactsAreSecondClassTests extends HeartStoreTestBase {

    /** Rust 侧 ATTESTED 常量（夹具统一证据日期）。 */
    private static final String ATTESTED = "2024-05-01T00:00:00Z";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID partOf, UUID a, UUID b, UUID c) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID c = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'derived-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'derived-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'derived-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, is_transitive)
                 VALUES (?, ?, 'part_of', 'part of', TRUE)
                """, partOf, kb);
        for (Object[] row : new Object[][]{{a, "A"}, {b, "B"}, {c, "C"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, etype, row[1]);
        }
        return new Fixture(org, kb, partOf, a, b, c);
    }

    private UUID assertFact(Fixture f, UUID subject, UUID object, String from, String precision) {
        UUID id = Uuids.nowV7();
        if (from != null) {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                        valid_from, valid_from_precision, attested_from)
                     VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?, ?::timestamptz)
                    """, id, f.kb(), subject, f.partOf(), object, from, precision, ATTESTED);
        } else {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, attested_from)
                     VALUES (?, ?, ?, ?, ?, ?::timestamptz)
                    """, id, f.kb(), subject, f.partOf(), object, ATTESTED);
        }
        return id;
    }

    /** 活着的派生事实：(主, 宾)。 */
    private List<Object[]> liveDerived(Fixture f) {
        return jdbc.query("""
                SELECT subject_id, object_id FROM derived_facts
                  WHERE kb_id = ? AND invalidated_at IS NULL
                  ORDER BY subject_id, object_id
                """, (rs, i) -> new Object[]{rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class)}, f.kb());
    }

    @Test
    void whatTheEngineAddsItCanAlsoTakeBack() {
        Fixture f = seed();
        try {
            // ---- 一、A ⊂ B ⊂ C ⟹ A ⊂ C
            UUID ab = assertFact(f, f.a(), f.b(), null, null);
            UUID bc = assertFact(f, f.b(), f.c(), null, null);
            var r = reasoning.materialize(f.kb());
            assertEquals(1, r.rules(), "一条 transitive 规则");
            assertEquals(2, r.edges());
            assertEquals(1, r.inserted());
            assertEquals(1, liveDerived(f).size());

            // 证明：两条前提，按顺序
            UUID derived = jdbc.queryForObject(
                    "SELECT id FROM derived_facts WHERE kb_id = ?", UUID.class, f.kb());
            List<UUID> premises = jdbc.queryForList("""
                    SELECT premise_fact_id FROM fact_derivations
                     WHERE derived_fact_id = ? ORDER BY seq
                    """, UUID.class, derived);
            assertEquals(List.of(ab, bc), premises, "证明要按推导顺序记直接前提");

            // ---- 二、重跑幂等，规则 id 不变
            UUID ruleBefore = jdbc.queryForObject("SELECT id FROM rules WHERE kb_id = ?",
                    UUID.class, f.kb());
            var again = reasoning.materialize(f.kb());
            assertEquals(0, again.inserted(), "同一条派生第二次跑不该再插一份");
            assertEquals(0, again.invalidated());
            UUID ruleAfter = jdbc.queryForObject("SELECT id FROM rules WHERE kb_id = ?",
                    UUID.class, f.kb());
            assertEquals(ruleBefore, ruleAfter, "重编译要认得出还是那条规则");

            // ---- 三、断言优先：把 A ⊂ C 也断言出来，派生的那条就该让路
            assertFact(f, f.a(), f.c(), null, null);
            var asserted = reasoning.materialize(f.kb());
            assertEquals(0, asserted.derived(), "断言过的三元组不该再派生");
            assertEquals(1, asserted.invalidated(), "此前派生的那条要作废");
            assertTrue(liveDerived(f).isEmpty());
            // 作废不是删——记录轴上留着
            Long ghost = jdbc.queryForObject("""
                    SELECT count(*) FROM derived_facts
                      WHERE kb_id = ? AND invalidated_at IS NOT NULL
                    """, Long.class, f.kb());
            assertEquals(1L, ghost, "派生失效要留痕，与拒绝一条事实同构");

            // ---- 四、前提撤了，派生跟着走
            jdbc.update(
                    "UPDATE facts SET invalidated_at = now() WHERE subject_id = ? AND object_id = ?",
                    f.a(), f.c());
            var back = reasoning.materialize(f.kb());
            assertEquals(1, back.inserted(), "断言撤了，派生该回来");
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", bc);
            var gone = reasoning.materialize(f.kb());
            assertEquals(1, gone.invalidated(), "前提没了，派生必须跟着失效");
            assertTrue(liveDerived(f).isEmpty());

            // ---- 五、精度：交集把结束端算成无界，那一端的精度必须跟着清掉
            jdbc.update("UPDATE facts SET invalidated_at = NULL WHERE id = ?", bc);
            jdbc.update("""
                    UPDATE facts SET valid_from = '2020-01-01T00:00:00Z'::timestamptz,
                                              valid_from_precision = 'year' WHERE id = ?
                    """, ab);
            jdbc.update("""
                    UPDATE facts SET valid_from = '2022-06-01T00:00:00Z'::timestamptz,
                                              valid_from_precision = 'day' WHERE id = ?
                    """, bc);
            var dated = reasoning.materialize(f.kb());
            assertEquals(1, dated.inserted());
            record Span(OffsetDateTime from, String fp, String tp) {
            }
            Span span = jdbc.queryForObject("""
                    SELECT valid_from, valid_from_precision, valid_to_precision FROM derived_facts
                      WHERE kb_id = ? AND invalidated_at IS NULL
                    """, (rs, i) -> new Span(rs.getObject(1, OffsetDateTime.class),
                    rs.getString(2), rs.getString(3)), f.kb());
            assertEquals("2022-06-01",
                    span.from().withOffsetSameInstant(java.time.ZoneOffset.UTC)
                            .toLocalDate().toString(),
                    "交集取两个起点里晚的那个");
            // 精度跟着赢下这一端的那条前提走（0024）：起点是 b ⊂ c 的 6 月 1 日，精度就是
            // 它的 day。从前取所有前提里最粗的——year 标在一个 6 月 1 日的值上，值与标签
            // 互相矛盾，如今数据库的 CHECK 也不会放行
            assertEquals("day", span.fp(), "精度跟赢下这一端的前提走，不是最粗的那个");
            assertEquals(null, span.tp(), "结束端无界，精度必须是空");

            // ---- 六、公理撤了：据它推出的事实作废，而规则行留着
            jdbc.update("UPDATE relation_types SET is_transitive = FALSE WHERE id = ?",
                    f.partOf());
            var blind = reasoning.materialize(f.kb());
            assertEquals(0, blind.rules(), "没有公理就编不出规则");
            assertEquals(1, blind.invalidated(), "据它推出来的事实要作废");
            assertTrue(liveDerived(f).isEmpty());
            Long rulesLeft = jdbc.queryForObject("SELECT count(*) FROM rules WHERE kb_id = ?",
                    Long.class, f.kb());
            assertEquals(1L, rulesLeft,
                    "规则行留着——刚作废的那些派生仍指着它，解释「当时靠哪条规则推的」需要它还在");
            // 公理加回来，派生也要回来（规则 id 还是原来那个）
            jdbc.update("UPDATE relation_types SET is_transitive = TRUE WHERE id = ?",
                    f.partOf());
            var revived = reasoning.materialize(f.kb());
            assertEquals(1, revived.rules());
            assertEquals(1, revived.inserted(), "公理回来，推导也回来");
            UUID ruleNow = jdbc.queryForObject("SELECT id FROM rules WHERE kb_id = ?",
                    UUID.class, f.kb());
            assertEquals(ruleBefore, ruleNow, "撤了又加回来，还是同一条规则");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
