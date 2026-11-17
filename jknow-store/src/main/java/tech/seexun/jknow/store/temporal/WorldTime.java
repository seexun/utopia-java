package tech.seexun.jknow.store.temporal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tech.seexun.jknow.common.AppException;

/**
 * 世界轴的时间语义（逐字对齐旧仓库 graph.rs:340-545 的纯函数族）：
 * 精度梯子（0024）、按精度截断、桶的尽头（0031）、关系的时间语义枚举，
 * 以及「一段起止同值的状态」这条写路径共用的拒绝。
 *
 * <p>为什么放在 temporal 包：时态引擎的每一处闭合点（{@code end_before}、
 * {@code close_superseded}、人工改区间）都要截到精度——存的值与精度必须说同一句话，
 * 数据库的 CHECK 与这里同源。任务 3 的落库门（insert_fact_on）同样吃这一组函数。
 */
public final class WorldTime {

    private WorldTime() {
    }

    /** graph.rs:346 {@code ENDED_UNKNOWN}：valid_to_precision 表示「结束了，但不知道哪天」。 */
    public static final String ENDED_UNKNOWN = "unknown";

    /** graph.rs:349-352 世界轴的精度梯子（0024）：从年到秒，不再往下。 */
    public static final String[] WORLD_PRECISIONS = {"year", "month", "day", "hour", "minute", "second"};

    /**
     * graph.rs:355-385 {@code truncate_to} 逐字语义：把值截到它的精度——年精度是
     * 1 月 1 日 0 点，秒精度是整秒。**存的值与精度说同一句话**（0024 第 2 条）；
     * 没有精度（锚点、派生的界）原样返回。
     */
    public static OffsetDateTime truncateTo(OffsetDateTime t, String precision) {
        if (t == null || precision == null) {
            return t;
        }
        LocalDateTime n = t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDate d = n.toLocalDate();
        switch (precision) {
            case "year":
                return OffsetDateTime.of(d.getYear(), 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            case "month":
                return OffsetDateTime.of(d.getYear(), d.getMonthValue(), 1, 0, 0, 0, 0, ZoneOffset.UTC);
            case "day":
                return OffsetDateTime.of(d, java.time.LocalTime.MIN, ZoneOffset.UTC);
            case "hour":
                return OffsetDateTime.of(d, n.toLocalTime().withMinute(0).withSecond(0).withNano(0),
                        ZoneOffset.UTC);
            case "minute":
                return OffsetDateTime.of(d, n.toLocalTime().withSecond(0).withNano(0), ZoneOffset.UTC);
            case "second":
                return OffsetDateTime.of(d, n.toLocalTime().withNano(0), ZoneOffset.UTC);
            default:
                return t;
        }
    }

    /**
     * graph.rs:388-409 {@code bucket_end}：一个桶的尽头——值加一个精度单位。
     * 事件在它命名的那个桶里成立（0031）；跨月的月桶、跨年的年桶照加。
     */
    public static OffsetDateTime bucketEnd(OffsetDateTime t, String precision) {
        if (t == null || precision == null) {
            return t;
        }
        OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
        switch (precision) {
            case "year":
                return utc.plusYears(1);
            case "month":
                return utc.plusMonths(1);
            case "day":
                return utc.plusDays(1);
            case "hour":
                return utc.plusHours(1);
            case "minute":
                return utc.plusMinutes(1);
            case "second":
                return utc.plusSeconds(1);
            default:
                return t;
        }
    }

    /** graph.rs:412-436 {@code Temporal}：关系的时间语义（relation_types.temporal，0031）。 */
    public enum Temporal {
        STATE, EVENT, ETERNAL;

        /** graph.rs:427-433 parse：认不出的值当状态（库里 CHECK 只放这三个进来）。 */
        public static Temporal parse(String s) {
            return switch (s == null ? "" : s) {
                case "event" -> EVENT;
                case "eternal" -> ETERNAL;
                default -> STATE;
            };
        }
    }

    /**
     * graph.rs:540-545 {@code empty_state_span}：一段起止同值的状态（#966）——写入、
     * 改区间、关上都拒它，说的是同一句话。世界轴按 from &lt;= T &lt; to 读状态，
     * 起止同值的一段任何时刻都不成立。
     */
    public static AppException emptyStateSpan() {
        return AppException.invalid("empty_state_span",
                "A state that ends where it starts holds at no moment: give an end after the start, "
                        + "or leave it open.");
    }
}
