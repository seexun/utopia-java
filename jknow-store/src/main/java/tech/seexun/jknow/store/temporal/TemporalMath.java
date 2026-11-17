package tech.seexun.jknow.store.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * temporal.rs 的纯函数核（不碰库）：{@code same_value}/{@code desired_ends}/
 * {@code diff}/{@code plan_timeline}/{@code still_overlaps}/{@code plan_closures}/
 * {@code literal_name}/{@code described_sql}。
 *
 * <p>为什么单拎一个类：这一组函数是时态引擎的判定本体——一次算完就是最终的样子
 * （改写终点不改各行的时刻、值、起点来历），Rust 内联测试（temporal.rs:1682）逐条
 * 钉住它们；Java 侧同置一处，单测与引擎吃同一份实现。
 */
public final class TemporalMath {

    private TemporalMath() {
    }

    /**
     * temporal.rs:38-49 DESCRIBED 谓词原文：这一行的出处里有看图描述出来的文字吗
     * （直接证据或 typed_fact_sources→statement→chunk 两条路，0040 决定 4）。
     */
    public static final String DESCRIBED = """
            (EXISTS (SELECT 1 FROM fact_evidence fe
                                    JOIN chunks ch ON ch.id = fe.chunk_id
                                   WHERE fe.fact_id = f.id AND ch.origin = 'described')
                          OR EXISTS (SELECT 1 FROM typed_fact_sources ts
                                       JOIN fact_evidence fe ON fe.fact_id = ts.statement_id
                                       JOIN chunks ch ON ch.id = fe.chunk_id
                                      WHERE ts.fact_id = f.id AND ch.origin = 'described'))""";

    /**
     * temporal.rs:46-49 {@code described_sql}：DESCRIBED 换成另一个别名——读的时候找
     * 关上一行的后任（#970），与引擎排除同样的行。
     */
    public static String describedSql(String alias) {
        return DESCRIBED.replace("= f.id", "= " + alias + ".id");
    }

    /**
     * temporal.rs:220-226 {@code same_value}：两行说的是不是同一个值（同一侧：
     * 主语侧比宾语，宾语侧比主语）。
     */
    public static boolean sameValue(Side side, TRow a, TRow b) {
        return switch (side) {
            case SUBJECT_SIDE -> Objects.equals(a.objectId, b.objectId)
                    && jsonEq(a.objectValue, b.objectValue);
            case OBJECT_SIDE -> Objects.equals(a.subjectId, b.subjectId);
        };
    }

    /** serde_json::Value 的 == 是结构相等；JsonNode.equals 同为结构相等。 */
    private static boolean jsonEq(JsonNode a, JsonNode b) {
        return a == null ? b == null : b != null && a.equals(b);
    }

    /** temporal.rs:474-482 {@code Plan}：重算一条时间线要改的地方（单测看的形状）。 */
    public record Plan(List<Map.Entry<UUID, TEnd>> ends, List<EndPair> held) {

        public static Plan empty() {
            return new Plan(List.of(), List.of());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Plan p)) {
                return false;
            }
            return ends.equals(p.ends) && held.equals(p.held);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ends, held);
        }
    }

    /** (行, 看图描述出来、没让它关上的最近那个后任)：交给人。 */
    public record EndPair(UUID row, UUID later) {
    }

    /**
     * temporal.rs:493-534 {@code desired_ends}——核心纯函数：一条时间线上每一行该有的
     * 终点（只含终点由引擎定的行）。
     *
     * <p>能排进时间线的行（有起点或有见证）按时刻排好；终点写明的行不动。其余每一行
     * ——开着的、引擎关上的——止于它之后最近的、值不同的那一行开始时；后面没有这样的
     * 行就开着。后任的起点锚不到（等级 C）时跳过它：它在轴上的位置是见证给的，不是
     * 那句话给的（0045 第 3 刀）。后任是看图描述出来的时也跳过，那一对交给人
     * （0040 决定 4）。无时间的行不能保留旧引擎仅凭文档日期推出来的终点——清成开着。
     *
     * <p>每一行的终点只取决于各行的时刻、值和起点的来历，改写终点不改这三样，所以
     * 一次算完就是最终的样子，不必一轮一轮地来；行怎么排进来的也不影响结果（#679）。
     */
    public static Map.Entry<Map<UUID, TEnd>, List<EndPair>> desiredEnds(Side side, List<TRow> rows) {
        List<TRow> keyed = new ArrayList<>();
        for (TRow r : rows) {
            if (r.key() != null) {
                keyed.add(r);
            }
        }
        // 同一刻开始的几行，写着起点的排前面：后任取它，前任就止于一个日期而不是一个锚点
        keyed.sort(Comparator
                .comparing((TRow r) -> r.key(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.validFrom == null)
                .thenComparing(r -> r.id, Timeline.UUID_BYTES));
        // 无时间的行不能保留旧引擎仅凭文档日期推出来的终点；人工写明的终点仍不重算
        Map<UUID, TEnd> ends = new LinkedHashMap<>();
        for (TRow row : rows) {
            if (row.recomputable() && row.key() == null) {
                ends.put(row.id, TEnd.open());
            }
        }
        List<EndPair> held = new ArrayList<>();
        for (int i = 0; i < keyed.size(); i++) {
            TRow row = keyed.get(i);
            if (!row.recomputable()) {
                continue;
            }
            TEnd end = TEnd.open();
            UUID doubtful = null;
            for (int j = i + 1; j < keyed.size(); j++) {
                TRow later = keyed.get(j);
                if (!(later.key().isAfter(row.key()) && !sameValue(side, row, later))) {
                    continue;
                }
                // 起点锚不到：不关，也不记矛盾——这一对没有什么可判的，待锚的提及归
                // 时间锚点队列（0045：等级 C 既不关时间线也不开矛盾）
                if (later.startUnanchored()) {
                    continue;
                }
                // 看图描述出来的事实能进图、能被引用，却不许单独关掉一段正确的旧值：
                // 柱状图读错一个数是常事，而库里没有一行会说这次关闭靠的是一张图（0040 决定 4）
                if (later.described) {
                    if (doubtful == null) {
                        doubtful = later.id;
                    }
                    continue;
                }
                end = later.endBefore();
                break;
            }
            if (doubtful != null) {
                held.add(new EndPair(row.id, doubtful));
            }
            ends.put(row.id, end);
        }
        return Map.entry(ends, held);
    }

    /** temporal.rs:547-554 {@code diff}：该有的终点里与眼下不同的，按行在时间线上的顺序。 */
    public static List<Map.Entry<UUID, TEnd>> diff(List<TRow> rows, Map<UUID, TEnd> ends) {
        List<Map.Entry<UUID, TEnd>> out = new ArrayList<>();
        for (TRow r : rows) {
            TEnd end = ends.remove(r.id);
            if (end == null) {
                continue;
            }
            if (!r.currentEnd().equals(end)) {
                out.add(Map.entry(r.id, end));
            }
        }
        return out;
    }

    /** temporal.rs:536-544 {@code plan_timeline}：单侧时间线的重算计划。 */
    public static Plan planTimeline(Side side, List<TRow> rows) {
        var r = desiredEnds(side, rows);
        return new Plan(diff(rows, new LinkedHashMap<>(r.getKey())), r.getValue());
    }

    /**
     * temporal.rs:714-751 {@code still_overlaps}：一条时间线按引擎排完之后，不同的值
     * 有没有哪一刻同时成立（纯函数）。{@code placed}：谓词是状态，引擎会排它；
     * 事件与恒常不排，照行上写着的终点比。合并闸门（0027）用它。
     */
    public static boolean stillOverlaps(Side side, List<TRow> rows, boolean placed) {
        Map<UUID, TEnd> ends = placed ? desiredEnds(side, rows).getKey() : new HashMap<>();
        // 每一行排完之后的 [起, 止)：起点是排序用的时刻，说不出时间的行从无穷早起；
        // 终点是引擎推的，或原文写明的
        record Span(OffsetDateTime start, OffsetDateTime end) {
        }
        List<Span> spans = new ArrayList<>();
        for (TRow r : rows) {
            TEnd engine = ends.get(r.id);
            OffsetDateTime end = engine != null ? engine.instant() : r.end();
            spans.add(new Span(r.key(), end));
        }
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                if (sameValue(side, rows.get(i), rows.get(j))) {
                    continue;
                }
                Span si = spans.get(i);
                Span sj = spans.get(j);
                if (startsBeforeEnd(si.start, sj.end) && startsBeforeEnd(sj.start, si.end)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean startsBeforeEnd(OffsetDateTime s, OffsetDateTime e) {
        if (s == null || e == null) {
            return true;
        }
        return s.isBefore(e);
    }

    /** temporal.rs:1435-1471 uniqueness_candidates 的值形状（供 UI 提议，非判决）。 */
    public record OpenValue(UUID factId, String name, OffsetDateTime validFrom,
                            String validFromGrade, float confidence) {
    }

    /**
     * temporal.rs:1611-1656 {@code plan_closures}：一个持有者的开放值按年表排好后，
     * 对账会怎么处置——(闭合数, 进人审数)。把 reconcile_predicate 的过程干跑一遍，
     * 不落库。这是估算——真跑一遍的结果才作数。
     */
    public static int[] planClosures(List<OpenValue> values) {
        boolean[] open = new boolean[values.size()];
        java.util.Arrays.fill(open, true);
        int close = 0;
        int review = 0;
        for (int i = 0; i < values.size(); i++) {
            if (!open[i]) {
                continue;
            }
            OpenValue now = values.get(i);
            for (int j = i + 1; j < values.size(); j++) {
                if (!open[j]) {
                    continue;
                }
                OpenValue old = values.get(j);
                OffsetDateTime of = old.validFrom();
                OffsetDateTime nf = now.validFrom();
                if (nf == null) {
                    review++;
                } else if (of != null && of.isEqual(nf)) {
                    review++;
                } else if (of != null && nf.isBefore(of)) {
                    if ("C".equals(now.validFromGrade())) {
                        review++;
                    } else {
                        close++;
                        open[i] = false;
                        break;
                    }
                } else {
                    if ("C".equals(now.validFromGrade())) {
                        review++;
                    } else {
                        close++;
                        open[j] = false;
                    }
                }
            }
        }
        return new int[]{close, review};
    }

    /**
     * temporal.rs:1658-1679 {@code literal_name}：字面值给人看的样子——
     * {value, unit} → "28000 CNY"，裸标量读成它自己。
     */
    public static String literalName(JsonNode v) {
        if (v == null) {
            return "null";
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isObject()) {
            JsonNode valueNode = v.get("value");
            if (valueNode == null) {
                return v.toString();
            }
            String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
            JsonNode unitNode = v.get("unit");
            String unit = unitNode != null && unitNode.isTextual() && !unitNode.asText().isEmpty()
                    ? unitNode.asText() : null;
            return unit != null ? value + " " + unit : value;
        }
        return v.toString();
    }
}
