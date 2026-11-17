package tech.seexun.jknow.store.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * temporal.rs:98-218 {@code Row}：时间线上的一行，连同它的一组纯函数
 * （{@link #key()}/{@link #recomputable()}/{@link #end()}/{@link #currentEnd()}/
 * {@link #endBefore()}/{@link #startUnanchored()}——grade C 语义）。
 *
 * <p>字段可变：合并闸门（{@code merge_would_overlap}）把两边的行当成一条时间线时
 * 原地改持有者；单测的 settle 也原地换终点。
 */
public final class TRow {

    public final UUID id;
    public UUID subjectId;
    public UUID objectId;
    public JsonNode objectValue;
    public OffsetDateTime validFrom;
    public String validFromPrecision;
    public OffsetDateTime validTo;
    public String validToPrecision;
    public OffsetDateTime attestedTo;
    public boolean endDerived;
    /** 事实自己的见证（f.attested_from，与世界轴下界同源，0064）。 */
    public OffsetDateTime datedAt;
    /**
     * 起点是怎么来的：A 写明、B 按文档锚点算出、C 有时间词却锚不到；null = 没经过
     * 时间解析（人写的、规则算的），按 A 算。
     */
    public String validFromGrade;
    /** 这一行的出处里有看图描述出来的文字（0040 决定 4）。 */
    public boolean described;

    public TRow(UUID id, UUID subjectId, UUID objectId, JsonNode objectValue,
                OffsetDateTime validFrom, String validFromPrecision,
                OffsetDateTime validTo, String validToPrecision,
                OffsetDateTime attestedTo, boolean endDerived, OffsetDateTime datedAt,
                String validFromGrade, boolean described) {
        this.id = id;
        this.subjectId = subjectId;
        this.objectId = objectId;
        this.objectValue = objectValue;
        this.validFrom = validFrom;
        this.validFromPrecision = validFromPrecision;
        this.validTo = validTo;
        this.validToPrecision = validToPrecision;
        this.attestedTo = attestedTo;
        this.endDerived = endDerived;
        this.datedAt = datedAt;
        this.validFromGrade = validFromGrade;
        this.described = described;
    }

    /**
     * temporal.rs:153-158 {@code start_unanchored}：起点锚不到吗——有时间词、代码没能
     * 把它放到世界轴上（等级 C）。**没有等级的行不算**：它的日期是直接给的（人写的、
     * 规则算的、这一列之前的每一行），不是从别处猜的。锚不到的行仍进得了时间线
     * （它按见证日期排），只是不许它改写历史。
     */
    public boolean startUnanchored() {
        return "C".equals(validFromGrade);
    }

    /**
     * temporal.rs:160-169 {@code key}：排序用的时刻——起点。没有起点时用事实的见证，
     * 但只对还开着、或者由引擎关上的行成立：它们的证据说那天还成立。原文说已经
     * 结束的行，证据的日期只说明「那天之前结束了」，拿它排序会让一个早就结束的值
     * 去关上当下的值（#679 第三轮评审）。
     */
    public OffsetDateTime key() {
        if (validFrom != null) {
            return validFrom;
        }
        if (isOpen() || endDerived) {
            return datedAt;
        }
        return null;
    }

    /** temporal.rs:170-172 {@code is_open}：valid_to 与精度都空 = 仍在持续。 */
    public boolean isOpen() {
        return validTo == null && validToPrecision == null;
    }

    /** temporal.rs:173-176 {@code recomputable}：终点由引擎按时间线定——开着的，或引擎关上的。 */
    public boolean recomputable() {
        return isOpen() || endDerived;
    }

    /**
     * temporal.rs:177-184 {@code end}：终点的时刻——日期；「结束了不知哪天」的锚点
     * （读出侧同样读到它为止）；开着的没有。
     */
    public OffsetDateTime end() {
        if (validTo != null) {
            return validTo;
        }
        if (WorldTime.ENDED_UNKNOWN.equals(validToPrecision)) {
            return attestedTo;
        }
        return null;
    }

    /** temporal.rs:185-192 {@code current_end}：眼下写着的终点。 */
    public TEnd currentEnd() {
        if (validTo != null) {
            return TEnd.at(validTo, validToPrecision == null ? "day" : validToPrecision);
        }
        if (WorldTime.ENDED_UNKNOWN.equals(validToPrecision)) {
            return TEnd.unknown(attestedTo);
        }
        return TEnd.open();
    }

    /**
     * temporal.rs:193-206 {@code end_before}：前一段止于这一行开始时，终点写成什么：
     * 有起点就是那一刻（截到它的精度——月精度的接任就是那个月的 1 日）；
     * 没有起点是「结束了，不知哪天」，锚在这一行的见证上。
     */
    public TEnd endBefore() {
        if (validFrom != null) {
            String precision = validFromPrecision == null ? "day" : validFromPrecision;
            return TEnd.at(WorldTime.truncateTo(validFrom, precision), precision);
        }
        return TEnd.unknown(datedAt);
    }

    /** temporal.rs:207-210 {@code holds_at}：在 at 这一刻还成立。 */
    public boolean holdsAt(OffsetDateTime at) {
        OffsetDateTime e = end();
        return isOpen() || (e != null && e.isAfter(at));
    }

    /** temporal.rs:211-217 {@code holder}：这一行在 side 那一侧的持有者。 */
    public UUID holder(Side side) {
        return side == Side.SUBJECT_SIDE ? subjectId : objectId;
    }

    @Override
    public String toString() {
        return "TRow{" + id + ", value=" + (objectValue == null ? null : objectValue.path("value").asText())
                + ", from=" + validFrom + ", to=" + validTo + ", prec=" + validToPrecision
                + ", endDerived=" + endDerived + ", datedAt=" + datedAt + "}";
    }
}
