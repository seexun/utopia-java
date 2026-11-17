package tech.seexun.jknow.store.temporal;

import java.time.OffsetDateTime;
import tech.seexun.jknow.store.temporal.WorldTime.Temporal;

/**
 * 一次观察带来的时间（逐字对齐旧仓库 graph.rs:316-345/437-537 的 {@code Validity}）：
 * 起止各带精度、这次观察出自哪天的文档（attested_at）、起点是怎么来的（from_grade）。
 *
 * <p>与 {@link WorldTime} 一起构成写入路径的时间词汇：{@link #truncated()} 截到精度、
 * {@link #under(Temporal)} 按谓词的时间语义归一（0031）——事件两端同一刻、恒常抹日期、
 * 状态拒绝起止同值（#966）。这两个变换是 {@code insert_fact_on} 落库门与
 * {@code correct_interval} 共用的同一道关。
 */
public final class Validity {

    public final OffsetDateTime from;
    public final String fromPrecision;
    public final OffsetDateTime to;
    public final String toPrecision;
    /** 这次观察出自哪一天的文档；NULL 明确表示没有（0064 决定 5：不填此刻）。 */
    public final OffsetDateTime attestedAt;
    /**
     * 起点是怎么来的：A 写明、B 按文档锚点算出、C 有时间词却锚不到；null = 没经过
     * 时间解析（人写的、规则算的），按 A 算。时态引擎读它决定这一行能不能关上前任。
     */
    public final String fromGrade;

    public Validity(OffsetDateTime from, String fromPrecision, OffsetDateTime to, String toPrecision,
                    OffsetDateTime attestedAt, String fromGrade) {
        this.from = from;
        this.fromPrecision = fromPrecision;
        this.to = to;
        this.toPrecision = toPrecision;
        this.attestedAt = attestedAt;
        this.fromGrade = fromGrade;
    }

    /** graph.rs:439-448 {@code Validity::default()}：全空。 */
    public static Validity empty() {
        return new Validity(null, null, null, null, null, null);
    }

    /** graph.rs:450-461 {@code starting}：起始端已知、结束端未知或不适用。 */
    public static Validity starting(OffsetDateTime from, String fromPrecision) {
        return new Validity(from, fromPrecision, null, null, null, null);
    }

    /** graph.rs:463-467 {@code attested}：这次观察出自哪一天的文档。 */
    public Validity attested(OffsetDateTime at) {
        return new Validity(from, fromPrecision, to, toPrecision, at, fromGrade);
    }

    /** graph.rs:470-474 {@code truncated}：两端截到各自的精度（0024）——写入路径进库前都走一遍。 */
    public Validity truncated() {
        return new Validity(WorldTime.truncateTo(from, fromPrecision), fromPrecision,
                WorldTime.truncateTo(to, toPrecision), toPrecision, attestedAt, fromGrade);
    }

    /**
     * graph.rs:484-514 {@code under}：按谓词的时间语义归一（0031）。
     * 事件：那一刻写在两端（给了起点用起点，只给终点用终点，一段取起点）；
     * 恒常：日期全抹；状态：原样——**两端相等的不写**（#966）。
     * 调用方先截断再归一：截到精度之后才相等的两端（同一天的两个钟点）同样拒绝。
     */
    public Validity under(Temporal temporal) {
        switch (temporal) {
            case STATE: {
                // 时刻相等（比 Instant）：from/to 的 Offset 表示不参与（Rust 全是 DateTime&lt;Utc&gt;）
                if (from != null && to != null && from.toInstant().equals(to.toInstant())) {
                    throw WorldTime.emptyStateSpan();
                }
                return this;
            }
            case ETERNAL:
                return new Validity(null, null, null, null, attestedAt, fromGrade);
            case EVENT: {
                // graph.rs:497-505：取到那一刻才带它的精度；两头都没给（如「结束了不知
                // 哪天」的裸事件——结束对一刻没有意义，0031）精度一并落下，不单留
                // to_precision——否则 valid_from 为空而精度非空，
                // CHECK facts_from_precision_matches_date 拒收
                OffsetDateTime t;
                String p;
                if (from != null) {
                    t = from;
                    p = fromPrecision;
                } else if (to != null) {
                    t = to;
                    p = toPrecision;
                } else {
                    t = null;
                    p = null;
                }
                return new Validity(t, p, t, p, attestedAt, fromGrade);
            }
            default:
                return this;
        }
    }

    /** graph.rs:517-521 {@code ended_when_unknown}：原文说它结束了，但没说哪天。 */
    public Validity endedWhenUnknown() {
        return new Validity(from, fromPrecision, null, WorldTime.ENDED_UNKNOWN, attestedAt, fromGrade);
    }

    /** graph.rs:531-536 {@code has_ended}：这条断言是否已不再成立——两种结束都算。 */
    public boolean hasEnded() {
        return to != null || WorldTime.ENDED_UNKNOWN.equals(toPrecision);
    }
}
