package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/the_world_axis_reaches_the_second.rs（248 行，2 例）——世界轴到秒
 * 为止（0024）：分精度的事实截到整分存；值与精度不一致的行被 CHECK 挡住（要真插一次）；
 * 派生行的精度跟着赢下这一端的那条前提走。断言逐条对译。
 */
class TheWorldAxisReachesTheSecondTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    tech.seexun.jknow.store.GraphWriteService writes;
    @Autowired
    TemporalService temporal;
    @Autowired
    ReasoningService reasoning;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    record Fixture(UUID org, UUID kb, UUID partOf, UUID a, UUID b, UUID c) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID ty = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID c = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'second-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'second-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'second-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Org')",
                ty, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, is_transitive)
                 VALUES (?, ?, 'part_of', 'part of', TRUE)
                """, partOf, kb);
        for (Object[] row : new Object[][]{{a, "Team A"}, {b, "Group B"}, {c, "Division C"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, ty, row[1]);
        }
        return new Fixture(org, kb, partOf, a, b, c);
    }

    record Bounds(OffsetDateTime from, String fromPrecision, OffsetDateTime to, String toPrecision) {
    }

    private Bounds bounds(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT valid_from, valid_from_precision, valid_to, valid_to_precision FROM "
                        + table + " WHERE id = ?",
                (rs, i) -> new Bounds(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getString(4)), id);
    }

    @Test
    void aStoredValueIsTruncatedToItsPrecision() {
        Fixture f = seed();
        try {
            // 记忆日志那一行：14:32 打的戳，精度到分。带着毫秒进来，存的是整分
            var wrote = writes.insertFact(f.kb(), f.a(), f.partOf(), f.b(),
                    new Validity(t("2026-06-01T14:32:07.382Z"), "minute", null, null,
                            t("2026-06-01T14:32:07.382Z"), null), 0.9f);
            Bounds factBounds = bounds("facts", wrote.id());
            assertEquals(t("2026-06-01T14:32:00Z"),
                    factBounds.from().withOffsetSameInstant(java.time.ZoneOffset.UTC), "截到整分");
            assertEquals("minute", factBounds.fromPrecision());

            // 人把它闭合在某个小时：闭合点截到整点
            UUID closed = temporal.closeSuperseded(wrote.id(), t("2026-06-01T18:45:00Z"), "hour");
            Bounds closedBounds = bounds("facts", closed);
            assertEquals(t("2026-06-01T18:00:00Z"),
                    closedBounds.to().withOffsetSameInstant(java.time.ZoneOffset.UTC),
                    "小时精度就是整点");
            assertEquals("hour", closedBounds.toPrecision());

            // 绕过写入路径直接塞一行值与精度不一致的：CHECK 挡住。这条要真跑——
            // 三值逻辑里 NULL 会让 CHECK 静默放行，只有插一次才知道它没放
            DataIntegrityViolationException bad = assertThrows(
                    DataIntegrityViolationException.class, () -> jdbc.update("""
                            INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                                valid_from, valid_from_precision)
                             VALUES (?, ?, ?, ?, ?, '2026-06-01T14:00:00Z'::timestamptz, 'day')
                            """, Uuids.nowV7(), f.kb(), f.b(), f.partOf(), f.c()),
                    "day 精度带着 14 点，不该存得进去");
            assertTrue(bad.getMessage().contains("facts_from_precision_matches_date"),
                    bad.getMessage());
            // 精度不在梯子上的也挡
            assertThrows(Exception.class, () -> jdbc.update("""
                            INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                                valid_from, valid_from_precision)
                             VALUES (?, ?, ?, ?, ?, '2026-06-01T14:00:00.500Z'::timestamptz, 'millisecond')
                            """, Uuids.nowV7(), f.kb(), f.b(), f.partOf(), f.c()),
                    "账本到秒为止");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 派生的起点是前提里最晚的那个起点；精度就是那条前提的精度，不是所有前提里最粗的。 */
    @Test
    void aDerivedBoundCarriesThePrecisionOfThePremiseThatSetIt() {
        Fixture f = seed();
        try {
            // a ⊂ b 从 2020 年起（year）；b ⊂ c 从 2023-06-15 起（day）
            writes.insertFact(f.kb(), f.a(), f.partOf(), f.b(),
                    new Validity(t("2020-01-01T00:00:00Z"), "year", null, null,
                            t("2020-01-01T00:00:00Z"), null), 0.9f);
            writes.insertFact(f.kb(), f.b(), f.partOf(), f.c(),
                    new Validity(t("2023-06-15T00:00:00Z"), "day", null, null,
                            t("2023-06-15T00:00:00Z"), null), 0.9f);
            reasoning.materialize(f.kb());

            UUID id = jdbc.queryForObject("""
                    SELECT id FROM derived_facts
                      WHERE kb_id = ? AND subject_id = ? AND object_id = ? AND invalidated_at IS NULL
                    """, UUID.class, f.kb(), f.a(), f.c());
            Bounds span = bounds("derived_facts", id);
            assertEquals(t("2023-06-15T00:00:00Z"),
                    span.from().withOffsetSameInstant(java.time.ZoneOffset.UTC),
                    "起点是最晚的那条前提的起点");
            assertEquals("day", span.fromPrecision(),
                    "精度跟赢下这一端的前提走——从前取最粗会把 year 标在 6 月 15 日上");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
