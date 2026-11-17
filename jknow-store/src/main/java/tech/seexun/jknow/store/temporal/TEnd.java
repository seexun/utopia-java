package tech.seexun.jknow.store.temporal;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * temporal.rs:119-150 {@code End}：一行的终点。三态——仍在持续 / 在这一刻结束（带精度）/
 * 结束了不知哪天（锚在说出结束的那份文档上，读出侧读到锚点为止）。
 */
public final class TEnd {

    public enum Kind { OPEN, AT, UNKNOWN }

    public final Kind kind;
    /** AT：结束那一刻；UNKNOWN：锚点（null = 连锚点都没有）。 */
    public final OffsetDateTime at;
    /** AT：那一刻的精度。 */
    public final String precision;
    /** UNKNOWN：锚在哪份文档的日期上。 */
    public final OffsetDateTime anchor;

    private TEnd(Kind kind, OffsetDateTime at, String precision, OffsetDateTime anchor) {
        this.kind = kind;
        this.at = at;
        this.precision = precision;
        this.anchor = anchor;
    }

    /** 仍在持续。 */
    public static TEnd open() {
        return new TEnd(Kind.OPEN, null, null, null);
    }

    /** temporal.rs:125 在这一刻结束，带精度。 */
    public static TEnd at(OffsetDateTime at, String precision) {
        return new TEnd(Kind.AT, at, precision, null);
    }

    /** temporal.rs:127 结束了，不知哪天；读出侧读到锚点为止。 */
    public static TEnd unknown(OffsetDateTime anchor) {
        return new TEnd(Kind.UNKNOWN, null, null, anchor);
    }

    /** temporal.rs:131-137 {@code instant}：读出来止于哪一刻；开着的没有。 */
    public OffsetDateTime instant() {
        return switch (kind) {
            case OPEN -> null;
            case AT -> at;
            case UNKNOWN -> anchor;
        };
    }

    /**
     * temporal.rs:140-149 {@code earlier}：两个终点里早的那个；同一刻时写着日期的
     * 胜过锚点（日期是原文说的，锚点是推断的下界）。
     */
    public TEnd earlier(TEnd other) {
        OffsetDateTime a = instant();
        OffsetDateTime b = other.instant();
        if (a == null) {
            return other;
        }
        if (b == null) {
            return this;
        }
        if (b.isBefore(a)) {
            return other;
        }
        if (a.isEqual(b) && other.kind == Kind.AT) {
            return other;
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TEnd t)) {
            return false;
        }
        if (kind != t.kind || !Objects.equals(precision, t.precision)) {
            return false;
        }
        // 时刻按瞬间比（Rust 全是 DateTime&lt;Utc&gt;；JDBC 读出的 Offset 偏移不参与）
        return instEquals(at, t.at) && instEquals(anchor, t.anchor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, at == null ? null : at.toInstant(), precision,
                anchor == null ? null : anchor.toInstant());
    }

    private static boolean instEquals(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.toInstant().equals(b.toInstant());
    }

    @Override
    public String toString() {
        return switch (kind) {
            case OPEN -> "Open";
            case AT -> "At(" + at + ", " + precision + ")";
            case UNKNOWN -> "Unknown(" + anchor + ")";
        };
    }
}
