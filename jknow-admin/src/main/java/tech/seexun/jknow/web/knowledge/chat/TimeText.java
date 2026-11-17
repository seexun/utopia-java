package tech.seexun.jknow.web.knowledge.chat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间给模型看的写法（time_text.rs 逐语义）：一条规则，所有工具行都走它。
 * <ul>
 *   <li>世界轴的端点按<b>自己的精度</b>写：year → {@code 2023}，month → {@code 2023-06}，
 *       day → {@code 2023-06-01}。从前一律 yyyy-MM-dd——年精度的事实印成 1 月 1 日，正是
 *       facts.valid_from_precision 那条注释说的病：在无知的地方填一个确定的值。</li>
 *   <li>没有精度的时刻——at、as_of、recorded_at、doc_time，以及锚点顶上来的界——写完整
 *       RFC3339，含小数秒。记录轴上同一秒内可以先录入再更正（#351），截到天或秒都会把
 *       两次认知叠回一起。</li>
 *   <li>「结束了，不知哪天」写成 {@code ended, date unknown}，绝不写 now。</li>
 * </ul>
 */
public final class TimeText {

    private TimeText() {
    }

    /** 一个时刻，完整写出（to_rfc3339_opts(AutoSi, true)——小数秒 3/6/9 位按值自选）。 */
    public static String instant(OffsetDateTime t) {
        int nanos = t.getNano();
        DateTimeFormatter fmt;
        if (nanos == 0) {
            fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");
        } else if (nanos % 1_000_000 == 0) {
            fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'");
        } else if (nanos % 1_000 == 0) {
            fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
        } else {
            fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'");
        }
        return t.withOffsetSameInstant(ZoneOffset.UTC).format(fmt);
    }

    /** 世界轴的一端，按精度写；没有精度就是一个时刻（锚点、派生的界），写完整。 */
    public static String world(OffsetDateTime t, String precision) {
        OffsetDateTime utc = t.withOffsetSameInstant(ZoneOffset.UTC);
        if (precision == null) {
            return instant(t);
        }
        return switch (precision) {
            case "year" -> utc.format(DateTimeFormatter.ofPattern("uuuu"));
            case "month" -> utc.format(DateTimeFormatter.ofPattern("uuuu-MM"));
            case "day" -> utc.format(DateTimeFormatter.ofPattern("uuuu-MM-dd"));
            case "hour" -> utc.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH'Z'"));
            case "minute" -> utc.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm'Z'"));
            case "second" -> utc.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'"));
            default -> instant(t);
        };
    }

    /** 一条事实的两端与日期从哪来（time_text.rs Span）。closed_by = (接任者, 它证据的号)。 */
    public record Span(OffsetDateTime validFrom, String fromPrecision, OffsetDateTime validTo,
                       String toPrecision, OffsetDateTime holdsFrom, OffsetDateTime holdsTo,
                       boolean endDerived, String corrected, String closedBy, String closerMark,
                       String correctionNote) {
    }

    /** 「结束了，不知哪天」的精度记号（graph.rs ENDED_UNKNOWN）。 */
    public static final String ENDED_UNKNOWN = "unknown";

    /** 备注在行上最多这么多字：再长就是一段话，不是一个说明。 */
    private static final int NOTE_CHARS = 120;

    /** 备注折成一行、去掉两端空白，过长截断；方括号换掉防它落成引用号。空的就是没写。 */
    static String noteText(String note) {
        String flat = String.join(" ", note.replace('[', '(').replace(']', ')').trim().split("\\s+"));
        if (flat.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        noteChars:
        for (String word : flat.split("\\s+")) {
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i);
                if (out.codePointCount(0, out.length()) >= NOTE_CHARS) {
                    out.append('…');
                    break noteChars;
                }
                out.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
            out.append(' ');
        }
        return out.toString().trim();
    }

    /**
     * {@code from → to}，给模型看。起点：原文给了按精度写；没给但有锚点写 {@code undated}
     * ——从前写 attested&lt;文档日期&gt;，模型把那个日期当成事情发生的日子念出来。终点：
     * 原文给了按精度写；「结束了不知哪天」照实说；否则 now。两端都无可写返回空串。
     * 人改过的端写 corrected 标记（备注带上，人的话不是原文段落，不带号）；时间线推出来
     * 的终点写 {@code superseded by X [m]}——[m] 打开的是接任那条事实读出来的原句。
     */
    public static String span(Span s) {
        String from;
        if (s.validFrom() != null) {
            from = world(s.validFrom(), s.fromPrecision());
        } else if (s.holdsFrom() != null) {
            from = "undated";
        } else {
            from = null;
        }
        boolean endedUnknown = s.validTo() == null && ENDED_UNKNOWN.equals(s.toPrecision());
        String to;
        if (s.validTo() != null) {
            to = world(s.validTo(), s.toPrecision());
        } else if (endedUnknown) {
            to = "ended, date unknown";
        } else {
            to = null;
        }
        boolean derived = s.endDerived() && to != null;
        String range;
        if (from == null && to == null) {
            range = "";
        } else if (to == null) {
            range = from + " → now";
        } else if (from == null) {
            range = "→ " + to;
        } else {
            range = from + " → " + to;
        }
        java.util.List<String> marks = new java.util.ArrayList<>();
        if (s.corrected() != null) {
            String what = switch (s.corrected()) {
                case "start" -> "start corrected";
                case "end" -> "end corrected";
                default -> "corrected";
            };
            String note = s.correctionNote() == null ? null : noteText(s.correctionNote());
            marks.add(note == null ? what : what + ": " + note);
        }
        if (derived) {
            if (s.closedBy() != null) {
                marks.add("superseded by " + s.closedBy()
                        + (s.closerMark() == null ? "" : s.closerMark()));
            } else {
                marks.add("end derived");
            }
        }
        if (marks.isEmpty()) {
            return range;
        }
        return range.isEmpty() ? String.join(", ", marks)
                : range + ", " + String.join(", ", marks);
    }
}
