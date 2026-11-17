package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalMath;
import tech.seexun.jknow.store.temporal.TemporalMath.EndPair;
import tech.seexun.jknow.store.temporal.TemporalMath.OpenValue;
import tech.seexun.jknow.store.temporal.TRow;
import tech.seexun.jknow.store.temporal.TEnd;
import tech.seexun.jknow.store.temporal.Timeline;

/**
 * temporal.rs:1682-1963 内联 mod tests 全量（desired_ends/plan 系列/plan_closures/
 * literal_name/described_sql 纯函数测试）——断言逐条对译，不改写。
 * 等价点（tokio→JUnit）：Rust 内联测试无库依赖，这里同样纯内存，无需 Spring 上下文。
 */
class TemporalMathTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- 造数助手（temporal.rs:1686-1753 逐字对译） ----

    /** temporal.rs:1686-1694 v()：plan_closures 的输入值。 */
    private static OpenValue v(int[] from, String grade) {
        return new OpenValue(UUID.randomUUID(), "", from == null ? null : day(from), grade, 0.9f);
    }

    private static OffsetDateTime day(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    private static OffsetDateTime day(int[] ymd) {
        return day(ymd[0], ymd[1], ymd[2]);
    }

    private static OffsetDateTime day(String iso) {
        return OffsetDateTime.parse(iso + "T00:00:00Z");
    }

    /**
     * temporal.rs:1701-1717 row()：时间线上的一行——值、起点、没起点时自带日期的证据。
     */
    private static TRow row(String value, OffsetDateTime from, OffsetDateTime dated) {
        JsonNode object = JSON.createObjectNode().put("value", value);
        return new TRow(UUID.randomUUID(), UUID.randomUUID(), null, object, from,
                from != null ? "day" : null, null, null, null, false, dated, null, false);
    }

    /**
     * temporal.rs:1720-1736 settle()：把计划落到行上（改写成新 id 的事与此无关，
     * 原地换终点），直到计划为空。
     */
    private static void settle(List<TRow> rows) {
        TemporalMath.Plan plan = TemporalMath.planTimeline(Side.SUBJECT_SIDE, rows);
        for (Map.Entry<UUID, TEnd> e : plan.ends()) {
            TRow r = rows.stream().filter(x -> x.id.equals(e.getKey())).findFirst().orElseThrow();
            TEnd end = e.getValue();
            r.endDerived = !end.equals(TEnd.open());
            switch (end.kind) {
                case OPEN -> {
                    r.validTo = null;
                    r.validToPrecision = null;
                    r.attestedTo = null;
                }
                case AT -> {
                    r.validTo = end.at;
                    r.validToPrecision = end.precision;
                    r.attestedTo = null;
                }
                case UNKNOWN -> {
                    r.validTo = null;
                    r.validToPrecision = "unknown";
                    r.attestedTo = end.anchor;
                }
            }
        }
        assertEquals(TemporalMath.Plan.empty().ends(),
                TemporalMath.planTimeline(Side.SUBJECT_SIDE, rows).ends(),
                "一次算完就是最终的样子");
    }

    /** temporal.rs:1738-1753 shape()：按值排序的 (值, 终点)。 */
    private static List<Map.Entry<String, TEnd>> shape(List<TRow> rows) {
        List<Map.Entry<String, TEnd>> out = new ArrayList<>();
        for (TRow r : rows) {
            out.add(Map.entry(r.objectValue.path("value").asText(), r.currentEnd()));
        }
        out.sort(java.util.Map.Entry.comparingByKey());
        return out;
    }

    private static List<Map.Entry<String, TEnd>> shape(String v1, TEnd e1, String v2, TEnd e2,
                                                       String v3, TEnd e3, String v4, TEnd e4,
                                                       String v5, TEnd e5) {
        return List.of(Map.entry(v1, e1), Map.entry(v2, e2), Map.entry(v3, e3), Map.entry(v4, e4),
                Map.entry(v5, e5));
    }

    /** 同一批行，不论以什么顺序排进来，算出来的时间线一样（temporal.rs:1756-1784）。 */
    @Test
    void a_timeline_does_not_depend_on_the_order_its_rows_arrive() {
        List<TRow> base = new ArrayList<>(List.of(
                row("v1", day(2020, 2, 18), null),
                row("v2", day(2020, 3, 17), null),
                row("v3", null, day(2020, 4, 14)),
                row("v4", day(2020, 5, 26), null),
                row("v5", null, day(2020, 6, 8))));
        List<TRow> first = new ArrayList<>(base);
        settle(first);
        assertEquals(
                shape("v1", TEnd.at(day(2020, 3, 17), "day"),
                        "v2", TEnd.unknown(day(2020, 4, 14)),
                        "v3", TEnd.at(day(2020, 5, 26), "day"),
                        "v4", TEnd.unknown(day(2020, 6, 8)),
                        "v5", TEnd.open()),
                shape(first));
        for (int rotation = 1; rotation < base.size(); rotation++) {
            List<TRow> rows = new ArrayList<>(base);
            java.util.Collections.rotate(rows, -rotation);
            java.util.Collections.reverse(rows);
            settle(rows);
            assertEquals(shape(first), shape(rows));
        }
    }

    /** 后面那一段挪了、走了，引擎画的终点跟着变；写明的终点不动（temporal.rs:1787-1820）。 */
    @Test
    void a_derived_end_follows_what_comes_next_and_a_stated_one_stays() {
        List<TRow> rows = new ArrayList<>(List.of(
                row("A", day(2016, 5, 16), null),
                row("B", null, day(2020, 8, 13))));
        settle(rows);
        assertEquals(TEnd.unknown(day(2020, 8, 13)), rows.get(0).currentEnd());
        // 后来的文件说 B 从 7 月 31 日起：A 止于那一天
        rows.get(1).validFrom = day(2020, 7, 31);
        rows.get(1).validFromPrecision = "day";
        settle(rows);
        assertEquals(TEnd.at(day(2020, 7, 31), "day"), rows.get(0).currentEnd());
        // B 被撤回：A 重新开着
        rows.remove(1);
        settle(rows);
        assertEquals(TEnd.open(), rows.get(0).currentEnd());
        assertFalse(rows.get(0).endDerived);

        // 原文写明 C 到 2019 年底，后面的 D 从 2019 年 6 月起：两份原文说法相左，不替人挑
        List<TRow> stated = new ArrayList<>(List.of(
                row("C", day(2018, 1, 1), null),
                row("D", day(2019, 6, 1), null)));
        stated.get(0).validTo = day(2019, 12, 31);
        stated.get(0).validToPrecision = "day";
        assertEquals(TemporalMath.Plan.empty(),
                TemporalMath.planTimeline(Side.SUBJECT_SIDE, stated));
    }

    /** 合并前的闸门：两个值能排成先后接替就不算叠在一起；排不开的才算（temporal.rs:1823-1852）。 */
    @Test
    void two_values_overlap_only_when_the_engine_cannot_order_them() {
        List<TRow> succession = List.of(
                row("HPBB1", day(2016, 5, 16), null),
                row("BBHQ1", null, day(2020, 8, 13)));
        assertFalse(TemporalMath.stillOverlaps(Side.SUBJECT_SIDE, succession, true));

        List<TRow> sameDay = List.of(
                row("P", day(2020, 1, 1), null),
                row("Q", day(2020, 1, 1), null));
        assertTrue(TemporalMath.stillOverlaps(Side.SUBJECT_SIDE, sameDay, true));

        List<TRow> noTime = List.of(row("K", null, null), row("N", day(2020, 1, 1), null));
        assertTrue(TemporalMath.stillOverlaps(Side.SUBJECT_SIDE, noTime, true));

        // 两份原文写明的区间相交：引擎不替人挑
        List<TRow> stated = new ArrayList<>(List.of(
                row("C", day(2018, 1, 1), null),
                row("D", day(2019, 6, 1), null)));
        stated.get(0).validTo = day(2019, 12, 31);
        stated.get(0).validToPrecision = "day";
        assertTrue(TemporalMath.stillOverlaps(Side.SUBJECT_SIDE, stated, true));

        // 事件与恒常不经引擎排：两个说不出时间的值照旧算撞
        List<TRow> eternal = List.of(row("X", null, null), row("Y", null, null));
        assertTrue(TemporalMath.stillOverlaps(Side.SUBJECT_SIDE, eternal, false));
    }

    /**
     * 起点锚不到的后任不许关上前任，也不开矛盾（0045 第 3 刀：等级 C 两样都不做）；
     * 前任止于它之后第一个锚得住的后任（temporal.rs:1855-1873）。
     */
    @Test
    void an_unanchored_successor_closes_nothing_and_opens_no_conflict() {
        List<TRow> rows = new ArrayList<>(List.of(
                row("A", day(2020, 1, 1), null),
                row("B", day(2020, 2, 1), null),
                row("C", day(2020, 3, 1), null)));
        rows.get(1).validFromGrade = "C";
        TemporalMath.Plan plan = TemporalMath.planTimeline(Side.SUBJECT_SIDE, rows);
        assertEquals(
                List.of(Map.entry(rows.get(0).id, TEnd.at(day(2020, 3, 1), "day")),
                        Map.entry(rows.get(1).id, TEnd.at(day(2020, 3, 1), "day"))),
                plan.ends());
        assertTrue(plan.held().isEmpty(), "锚不到的一对没有什么可判的");
    }

    /**
     * 读的时候找关上一行的后任，用的是引擎同一条「看图描述出来的」判据，只换了别名
     * （#970）（temporal.rs:1876-1881）。
     */
    @Test
    void the_described_check_takes_another_alias() {
        String n = TemporalMath.describedSql("n");
        assertFalse(n.contains("= f.id"), n);
        assertEquals(2, n.split("= n\\.id", -1).length - 1, n);
    }

    /**
     * 看图描述出来的后任不许单独关上前任，这一对交给人（0040 决定 4）；
     * 前任止于它之后第一个不是描述出来的后任（temporal.rs:1884-1902）。
     */
    @Test
    void a_described_successor_is_held_and_the_next_stated_one_closes() {
        List<TRow> rows = new ArrayList<>(List.of(
                row("A", day(2020, 1, 1), null),
                row("B", day(2020, 2, 1), null),
                row("C", day(2020, 3, 1), null)));
        rows.get(1).described = true;
        TemporalMath.Plan plan = TemporalMath.planTimeline(Side.SUBJECT_SIDE, rows);
        assertEquals(
                List.of(Map.entry(rows.get(0).id, TEnd.at(day(2020, 3, 1), "day")),
                        Map.entry(rows.get(1).id, TEnd.at(day(2020, 3, 1), "day"))),
                plan.ends());
        assertEquals(List.of(new EndPair(rows.get(0).id, rows.get(1).id)), plan.held());
    }

    /** 三条链关两次（temporal.rs:1904-1912）。 */
    @Test
    void a_chain_of_three_closes_twice() {
        List<OpenValue> values = List.of(
                v(new int[]{2023, 2, 1}, null),
                v(new int[]{2024, 7, 5}, null),
                v(new int[]{2025, 9, 1}, null));
        assertArrayEquals(new int[]{2, 0}, TemporalMath.planClosures(values));
    }

    /** 引擎不会关的进人审（temporal.rs:1914-1948）。 */
    @Test
    void what_the_engine_would_not_close_goes_to_review() {
        // 同一天开始
        assertArrayEquals(new int[]{0, 1}, TemporalMath.planClosures(List.of(
                v(new int[]{2024, 1, 1}, null), v(new int[]{2024, 1, 1}, null))));
        // 两条都没起点
        assertArrayEquals(new int[]{0, 1},
                TemporalMath.planClosures(List.of(v(null, null), v(null, null))));
        // 起点更早的那条锚不到，不许它改写历史
        assertArrayEquals(new int[]{0, 1}, TemporalMath.planClosures(List.of(
                v(new int[]{2023, 1, 1}, "C"), v(new int[]{2024, 1, 1}, null))));
        // 后任没起点：它止于前任的起点（落库时的"旧事实无起点也适用"）
        assertArrayEquals(new int[]{1, 0}, TemporalMath.planClosures(List.of(
                v(new int[]{2023, 1, 1}, null), v(null, null))));
        // 一条不成对
        assertArrayEquals(new int[]{0, 0},
                TemporalMath.planClosures(List.of(v(new int[]{2023, 1, 1}, null))));
        // 三条都没起点：每一对都说不清，三对冲突，与引擎一致
        assertArrayEquals(new int[]{0, 3}, TemporalMath.planClosures(List.of(
                v(null, null), v(null, null), v(null, null))));
        // 有起点的一条闭合两条没起点的：它们都止于它的起点
        assertArrayEquals(new int[]{2, 0}, TemporalMath.planClosures(List.of(
                v(new int[]{2023, 1, 1}, null), v(null, null), v(null, null))));
    }

    /** 字面值给人看的样子（temporal.rs:1950-1962）。 */
    @Test
    void a_literal_reads_as_a_value_with_its_unit() throws Exception {
        assertEquals("28000 CNY", TemporalMath.literalName(
                JSON.readTree("{\"value\": 28000, \"unit\": \"CNY\"}")));
        assertEquals("Staff Engineer", TemporalMath.literalName(
                JSON.readTree("{\"value\": \"Staff Engineer\"}")));
        assertEquals("32000", TemporalMath.literalName(JSON.readTree("32000")));
        assertEquals("plain", TemporalMath.literalName(JSON.readTree("\"plain\"")));
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    /** Timeline.lockKey 与 UUID 字节序比较的形状（构造保险，非 Rust 测试对译）。 */
    @Test
    void lockKeyTakesTheRustShape() {
        UUID kb = UUID.nameUUIDFromBytes("kb".getBytes());
        Timeline both = new Timeline(UUID.randomUUID(), UUID.randomUUID(), Side.SUBJECT_SIDE, true);
        assertEquals("timeline:" + kb + ":" + both.predicateId() + ":both", both.lockKey(kb));
        Timeline one = new Timeline(both.holder(), both.predicateId(), Side.OBJECT_SIDE, false);
        assertEquals("timeline:" + kb + ":" + one.holder() + ":" + one.predicateId() + ":ObjectSide",
                one.lockKey(kb));
        // 字节序比较与 Java UUID.compareTo 在高位符号位上分道（保险断言）
        UUID a = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertTrue(Timeline.UUID_BYTES.compare(a, b) > 0);
    }
}
