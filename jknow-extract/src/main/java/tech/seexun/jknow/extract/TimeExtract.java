package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 时间提法的解读（time.rs 逐语义，0045 决定 2、3、8）：**模型读，代码算**。
 *
 * <p>只返回结构化的字段，模型从不写一个算出来的日期：每条提法给形状、引用、粒度；绝对值
 * 按原文的字抄成数字部件，相对的说锚点与偏移，期间报名字。日历算术、财年到区间、按粒度
 * 截断、形状蕴含的区间，全在服务端算（{@code ResolveTimeJob}）。
 *
 * <p>代码里<b>没有时间词</b>（决定 8）：不认月份名、不认「去年」，只解 JSON、验部件范围、
 * 拼提示词。解析逐项宽容：坏的条目计数，不毁整批；截断的回复退到最后一个完整条目补括号，
 * 与 open 同一套修补。
 */
public final class TimeExtract {

    private TimeExtract() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** time.rs:34-47 DateParts：原文说到的日期部件；只有原文写了的才是非 null。 */
    public record DateParts(int year, Integer quarter, Integer month, Integer day, Integer hour,
                            Integer minute, Integer second) {

        /** time.rs:50-66 granularity：部件够到的那一级（有日就是日、只有年就是年）。 */
        public String granularity() {
            if (second != null) {
                return "second";
            }
            if (minute != null) {
                return "minute";
            }
            if (hour != null) {
                return "hour";
            }
            if (day != null) {
                return "day";
            }
            if (month != null || quarter != null) {
                return "month";
            }
            return "year";
        }

        /** time.rs:69-93 Display：只排版、不计算——{@code 2011}、{@code 2011-03}、{@code 2011-Q3}。 */
        public String display() {
            StringBuilder sb = new StringBuilder().append(year);
            if (quarter != null) {
                sb.append("-Q").append(quarter);
            }
            if (month != null) {
                sb.append(String.format("-%02d", month));
            }
            if (day != null) {
                sb.append(String.format("-%02d", day));
            }
            if (hour != null) {
                sb.append(String.format(" %02d", hour));
            }
            if (minute != null) {
                sb.append(String.format(":%02d", minute));
            }
            if (second != null) {
                sb.append(String.format(":%02d", second));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return display();
        }
    }

    /** time.rs:95-101 NamedPeriod：文档命名的期间及其边界。 */
    public record NamedPeriod(String name, DateParts from, DateParts to) {
    }

    /**
     * time.rs:108-127 TimeEntry：文档说到的一个日期，连它的名字和它管到哪（0064 决定 1）。
     * 一篇文档不止一个日期，哪个都不比别的大：提报日期、截止、生效日期各管各的事。
     */
    public record TimeEntry(String kind, String name, String words, DateParts from, DateParts to,
                            List<String> scope, String chunk, Integer charStart) {
    }

    /** time.rs:225-244 DocumentDating：文档自己的时间语境（决定 3）。 */
    public static final class DocumentDating {
        public List<TimeEntry> entries = new ArrayList<>();
        public DateParts date;
        public String dateWords;
        public List<NamedPeriod> periods = new ArrayList<>();
        /** (month, day) on which the document's fiscal year ends, when stated。 */
        public int[] fiscalYearEnd;
    }

    /** time.rs:247-257 Shape：提法对它所定的陈述做了什么（snake_case 字符串）。 */
    public enum Shape {
        POINT("point"), SINCE("since"), UNTIL("until"), INTERVAL("interval"), AS_OF("as_of"),
        DURATION("duration"), ENDED_UNKNOWN("ended_unknown");

        private final String wire;

        Shape(String wire) {
            this.wire = wire;
        }

        public String asString() {
            return wire;
        }

        /**
         * 解析侧的枚举词（time.rs:572-579 word：大小写、空格与连字符都宽容）。对 wire
         * 匹配而不是 {@code valueOf}：常量名是大写、线上的词是 snake_case，valueIf 会
         * 把每一条合法回复都炸成坏条目。
         */
        public static Shape parse(String s) {
            String w = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (Shape v : values()) {
                if (v.wire.equals(w)) {
                    return v;
                }
            }
            throw new IllegalArgumentException("unknown shape: " + s);
        }
    }

    /** time.rs:259-272 Granularity：原文的字够到阶梯的哪一级（0024）。周落在日这一档。 */
    public enum Granularity {
        YEAR("year"), MONTH("month"), DAY("day", "week"), HOUR("hour"), MINUTE("minute"),
        SECOND("second");

        private final String wire;
        private final String alias;

        Granularity(String wire) {
            this(wire, null);
        }

        Granularity(String wire, String alias) {
            this.wire = wire;
            this.alias = alias;
        }

        public String asString() {
            return wire;
        }

        public static Granularity parse(String s) {
            String w = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (Granularity g : values()) {
                if (g.wire.equals(w) || (g.alias != null && g.alias.equals(w))) {
                    return g;
                }
            }
            throw new IllegalArgumentException("unknown granularity: " + s);
        }
    }

    /** time.rs:274-285 Unit。 */
    public enum Unit {
        YEAR("year"), QUARTER("quarter"), MONTH("month"), WEEK("week"), DAY("day"), HOUR("hour"),
        MINUTE("minute"), SECOND("second");

        private final String wire;

        Unit(String wire) {
            this.wire = wire;
        }

        public String asString() {
            return wire;
        }

        public static Unit parse(String s) {
            String w = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (Unit v : values()) {
                if (v.wire.equals(w)) {
                    return v;
                }
            }
            throw new IllegalArgumentException("unknown unit: " + s);
        }
    }

    /** time.rs:287-292 Direction。 */
    public enum Direction {
        BEFORE("before"), AFTER("after");

        private final String wire;

        Direction(String wire) {
            this.wire = wire;
        }

        public String asString() {
            return wire;
        }

        public static Direction parse(String s) {
            String w = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (Direction v : values()) {
                if (v.wire.equals(w)) {
                    return v;
                }
            }
            throw new IllegalArgumentException("unknown direction: " + s);
        }
    }

    /** time.rs:294-300 Offset：原文写的偏移（"two years later" = 2 year after）。 */
    public record Offset(long count, Unit unit, Direction direction) {
    }

    /** time.rs:302-308 Anchor（tag=kind）。 */
    public sealed interface Anchor permits AnchorMention, AnchorDocument, AnchorPeriod {
    }

    /** {@code {"kind":"mention","id":N}}。 */
    public record AnchorMention(long id) implements Anchor {
    }

    /** {@code {"kind":"document"}}。 */
    public record AnchorDocument() implements Anchor {
    }

    /** {@code {"kind":"period","name":…}}。 */
    public record AnchorPeriod(String name) implements Anchor {
    }

    /** time.rs:310-331 Reference（tag=kind；JSON null 当 none）。 */
    public sealed interface Reference permits RefAbsolute, RefAnchored, RefPeriod, RefNone {
    }

    /** 照写的绝对值（to 只给写全了两端的区间）。 */
    public record RefAbsolute(DateParts from, DateParts to) implements Reference {
    }

    /** 相对一个锚点（无偏移 = 锚点本身）。 */
    public record RefAnchored(Anchor anchor, Offset offset) implements Reference {
    }

    /** 一个命名的期间（边界由这里写明或从语境按名字找）。 */
    public record RefPeriod(String name, DateParts from, DateParts to) implements Reference {
    }

    /** 字没给可锚的东西（时长、没日期的结束、含糊）。 */
    public record RefNone() implements Reference {
    }

    /** time.rs:333-340 Interpretation：一条提法的解读，存成 JSONB 由服务端算区间。 */
    public record Interpretation(long id, Shape shape, Reference reference, Granularity granularity) {
    }

    /** time.rs:342-348 MentionInput：送去解读的一条提法（模型靠句子挑锚点）。 */
    public record MentionInput(long id, String text, String sentence) {
    }

    /** time.rs:350-357 TimeContext：进提示词的时间语境（DocumentDating 的借用视图）。 */
    public record TimeContext(DateParts date, String dateWords, List<NamedPeriod> periods,
                              int[] fiscalYearEnd) {
    }

    /**
     * time.rs:134-181 headings_at：块的正文里，这个字符位置之前还在管着的标题，由外到内。
     * 判据是结构的：行首连着的 {@code #} 是标题的级，同级或更深的旧标题被新标题顶掉。
     * 分块器让每一块以它所在的标题开头，所以一块自己的正文就说得出它在哪一节——不靠
     * 块与块的先后（#588 并发抽照常成立）。
     */
    public static List<String> headingsAt(String text, int charPos) {
        List<int[]> stack = new ArrayList<>(); // (level)
        List<String> titles = new ArrayList<>();
        int seen = 0;
        Character fenceKind = null;
        int fenceWidth = 0;
        for (String line : splitInclusive(text)) {
            if (seen > charPos) {
                break;
            }
            int indentation = 0;
            while (indentation < line.length() && line.charAt(indentation) == ' ') {
                indentation++;
            }
            String trimmed = line.strip();
            char marker = trimmed.isEmpty() ? ' ' : trimmed.charAt(0);
            int markers = countRun(trimmed, marker);
            if (fenceKind != null) {
                if (indentation <= 3 && marker == fenceKind && markers >= fenceWidth
                        && trimmed.substring(markers).strip().isEmpty()) {
                    fenceKind = null;
                }
                seen += line.codePointCount(0, line.length());
                continue;
            }
            if (indentation <= 3 && (marker == '`' || marker == '~') && markers >= 3
                    && (marker != '`' || !trimmed.substring(markers).contains("`"))) {
                fenceKind = marker;
                fenceWidth = markers;
                seen += line.codePointCount(0, line.length());
                continue;
            }
            if (indentation >= 4 || line.startsWith("\t")) {
                seen += line.codePointCount(0, line.length());
                continue;
            }
            int level = countRun(trimmed, '#');
            if (level >= 1 && level <= 6) {
                String rest = trimmed.substring(level);
                String title = rest.strip();
                if (!title.isEmpty() && !rest.isEmpty()
                        && Character.isWhitespace(rest.charAt(0))) {
                    while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= level) {
                        stack.remove(stack.size() - 1);
                        titles.remove(titles.size() - 1);
                    }
                    stack.add(new int[]{level});
                    titles.add(title);
                }
            }
            seen += line.codePointCount(0, line.length());
        }
        return titles;
    }

    private static List<String> splitInclusive(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int idx = text.indexOf('\n');
        while (idx >= 0) {
            out.add(text.substring(start, idx + 1));
            start = idx + 1;
            idx = text.indexOf('\n', start);
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static int countRun(String s, char c) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == c) {
            n++;
        }
        return n;
    }

    /**
     * time.rs:185-196 now_in_force：这个位置上管事的那个 {@code now}——所在的标题路径以
     * 它的范围开头的里面，范围最深的那个；同一层取先说的。没有就是 null（等着，决定 4）。
     */
    public static TimeEntry nowInForce(List<TimeEntry> entries, List<String> path) {
        TimeEntry best = null;
        for (TimeEntry e : entries) {
            if (!"now".equals(e.kind())) {
                continue;
            }
            if (e.scope().size() <= path.size()
                    && e.scope().equals(path.subList(0, e.scope().size()))
                    && (best == null || e.scope().size() > best.scope().size())) {
                best = e;
            }
        }
        return best;
    }

    /**
     * time.rs:200-223 time_entry：抽取回复里的一条 {@code t}：[kind, name, words, from, to]。
     * 部件是照字抄的数字，出界的、缺字的都是坏条目（返回 null）。
     */
    public static TimeEntry timeEntry(JsonNode item) {
        if (item == null || !item.isArray()) {
            return null;
        }
        String kind = optTextAt(item, 0);
        if (kind == null) {
            return null;
        }
        kind = kind.toLowerCase(Locale.ROOT);
        if (!kind.equals("now") && !kind.equals("date") && !kind.equals("period")) {
            return null;
        }
        String words = optTextAt(item, 2);
        if (words == null) {
            return null;
        }
        if (item.size() < 4) {
            return null;
        }
        DateParts from = parts(item.get(3));
        if (from == null) {
            return null;
        }
        DateParts to = null;
        JsonNode toV = item.size() > 4 ? item.get(4) : null;
        if (toV != null && !toV.isNull()) {
            to = parts(toV);
            if (to == null) {
                return null;
            }
        }
        String name = optTextAt(item, 1);
        return new TimeEntry(kind, name == null ? "" : name, words, from, to, List.of(), null,
                null);
    }

    private static String optTextAt(JsonNode arr, int i) {
        if (arr.size() <= i) {
            return null;
        }
        return OpenParser.optText(arr.get(i));
    }

    /**
     * 解读提法的系统消息（time.rs:360-426 逐字——形状各一行；引用是字面说的，不是它蕴含
     * 的；从不计算。例子是中性的）。
     */
    public static final String INTERPRETATION_SYSTEM = """
            You interpret the time expressions of a document. Each mention is given with its id, its words exactly as written and the sentence it occurs in; before them, the document's own time context: its date and the words that state it, the periods it names with their bounds, and the day its fiscal year ends. For each mention you write what its words state — a shape, a reference and a granularity — and never a date you computed: the reader computes dates from what you write. Output one JSON object and nothing else, shaped like this:

            {"m": [[id, "shape", reference, "granularity"]]}

            1. shape is what the time does to the statement it dates, one of:
            "point" — the statement happened or holds at that time;
            "since" — it holds from that time on;
            "until" — it holds up to that time;
            "interval" — it holds between two bounds;
            "as_of" — the state observed at that time;
            "duration" — a length of time with no position;
            "ended_unknown" — the words say it ended but not when.
            2. reference is what the words say, not what they imply, in one of four forms:
            {"kind": "absolute", "from": {"y": 2011, "m": 3, "d": 4}} — the words state the value. Parts are transcribed digits: y the year, m the month (a month name becomes its number), d the day, and h, min, s for a clock time; write only the parts the words state. A bare year ("2019") is {"y": 2019} with granularity "year". A quarter of a stated year is written with q, the number of the quarter as the words give it, and no month: "Q3 2025" and "2025年第三季度" are {"y": 2025, "q": 3} with granularity "month". "to" is added only for an interval the words write out with both bounds ("from March to May 2024": from {"y": 2024, "m": 3}, to {"y": 2024, "m": 5}).
            {"kind": "anchored", "anchor": {"kind": "document"}, "offset": {"count": 1, "unit": "year", "direction": "before"}} — the words point at another time. The anchor is the document itself, {"kind": "document"}, for "today", "now", "this quarter", "last year", "the prior year"; the mention the sentence counts from, {"kind": "mention", "id": 3}, for "two years later", "three months earlier", "the following day" when the sentence names that time, and the document when it names none; a period from the context, {"kind": "period", "name": "fiscal 2019"}, for "the end of fiscal 2019". offset is the count, unit and direction the words state: "two years later" is count 2, unit "year", direction "after"; "this quarter" and "this year" are count 0 with that unit; "today" and "now" have no offset. unit is one of year, quarter, month, week, day, hour, minute, second; direction is before or after.
            {"kind": "period", "name": "Q2 fiscal 2019"} — the words name a period. One listed in the context is named exactly as listed. One whose bounds the words state here carries them: a heading "Three months ended June 30, 2019" is {"kind": "period", "name": "Three months ended June 30, 2019", "to": {"y": 2019, "m": 6, "d": 30}}, with "from" only when the words state it. Words that name a period only relatively (the whole year, the end of the year, the start of the month, this quarter) are not a period: they are anchored to the document with count 0 and that unit; the end of it takes shape "as_of" or "until", the start "since", the whole "interval". A count inside something the sentence names (a week of a trial, a month of a programme) anchors to that thing's dated mention when the sentence gives one, otherwise it is {"kind": "none"}: it is not counted from the document.
            {"kind": "none"} — the words give nothing to anchor to: a duration ("ten years"), an ending without a date ("formerly", "no longer"), a vague time ("recently").
            3. granularity is the rung the words reach: "year", "month", "day", "hour", "minute" or "second". "March 2024" is month; "two years later" is year; "today" is day; a quarter or a season is month.
            4. Never compute: do not resolve "last year" to a year, do not add an offset to a date, do not turn a period into dates, do not use today's date. Write one item per mention, with the id from the list, and no item for an id not listed.

            Example. With the document dated March 4, 2011 and the mentions [id 1 "March 4, 2011" in "The council met on March 4, 2011.", id 2 "since 2005" in "Harbor Bakery has supplied the school since 2005.", id 3 "2019" and id 4 "two years later" in "The bridge opened in 2019; two years later the eastern span was widened.", id 5 "last year" in "Last year the bakery opened a second shop."]:
            {"m": [[1, "point", {"kind": "absolute", "from": {"y": 2011, "m": 3, "d": 4}}, "day"],
            [2, "since", {"kind": "absolute", "from": {"y": 2005}}, "year"],
            [3, "point", {"kind": "absolute", "from": {"y": 2019}}, "year"],
            [4, "point", {"kind": "anchored", "anchor": {"kind": "mention", "id": 3}, "offset": {"count": 2, "unit": "year", "direction": "after"}}, "year"],
            [5, "point", {"kind": "anchored", "anchor": {"kind": "document"}, "offset": {"count": 1, "unit": "year", "direction": "before"}}, "year"]]}""";


    /**
     * time.rs:429-476 build_interpretation_messages：常量系统消息 + 语境块（日期/期间/财年
     * 截止日）+ 提法清单（编号、原文的字、句子）。
     */
    public static List<ChatMessage> buildInterpretationMessages(TimeContext context,
                                                                List<MentionInput> mentions) {
        StringBuilder user = new StringBuilder("Document time context:\n");
        if (context.date() != null && context.dateWords() != null) {
            user.append("- date: ").append(context.date().display())
                    .append(", stated by the words \"").append(context.dateWords()).append("\"\n");
        } else if (context.date() != null) {
            user.append("- date: ").append(context.date().display()).append('\n');
        } else {
            user.append("- date: not stated; still anchor to the document where the words point at it\n");
        }
        if (context.periods().isEmpty()) {
            user.append("- periods: none named\n");
        } else {
            List<String> listed = new ArrayList<>();
            for (NamedPeriod p : context.periods()) {
                listed.add("\"" + p.name() + "\" from " + p.from().display() + " to "
                        + p.to().display());
            }
            user.append("- periods: ").append(String.join("; ", listed)).append('\n');
        }
        if (context.fiscalYearEnd() != null) {
            user.append("- fiscal year end: month ").append(context.fiscalYearEnd()[0])
                    .append(", day ").append(context.fiscalYearEnd()[1]).append('\n');
        } else {
            user.append("- fiscal year end: not stated\n");
        }
        user.append("\nMentions:\n");
        for (MentionInput m : mentions) {
            user.append("- id ").append(m.id()).append(", words \"").append(m.text())
                    .append("\", in the sentence: \"").append(m.sentence()).append("\"\n");
        }
        return List.of(ChatMessage.system(INTERPRETATION_SYSTEM), ChatMessage.user(user.toString()));
    }

    /**
     * time.rs:480-495 reply_value：先按常规取块解；解不开再从第一个 {@code {} 取到结尾，
     * 退到最后一个完整条目补括号（同 open.rs：紧凑回复的最后一个 {@code }} 不是可靠结尾）。
     */
    static JsonNode replyValue(String raw, String what) {
        String block = null;
        Exception first = null;
        try {
            block = JsonFuns.jsonBlock(raw);
        } catch (Exception e) {
            first = e;
        }
        if (block != null) {
            try {
                return JSON.readTree(block);
            } catch (Exception e) {
                first = e;
            }
        }
        String text = JsonFuns.jsonText(raw);
        int at = text.indexOf('{');
        String fixed = at >= 0 ? OpenParser.repairTruncatedCompact(text.substring(at)) : null;
        if (fixed == null) {
            throw new IllegalArgumentException("Failed to parse " + what + " JSON: " + first);
        }
        try {
            return JSON.readTree(fixed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse " + what + " JSON: " + e);
        }
    }

    /**
     * time.rs:498-503 int：JSON <b>整数</b>，或模型偶尔写成字符串的数字。浮点
     * （{@code {"m": 3.0}}）是坏条目——serde_json 的 as_i64 对存成 f64 的值返回 None，
     * 不是截断（审查 M-7；asLong() 会把 3.0 截成 3 悄悄收下）。
     */
    static Long intOf(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.isIntegralNumber() ? v.asLong() : null;
        }
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * time.rs:507-514 part：可选的整数部件，带范围——缺或 null 是 Some(None)；有但出界或
     * 不是数是 null（坏）。
     */
    static Integer part(JsonNode obj, String key, long min, long max) {
        JsonNode v = obj.get(key);
        if (v == null || v.isNull()) {
            return OUTER; // 缺
        }
        Long n = intOf(v);
        if (n == null || n < min || n > max) {
            return BAD;
        }
        return (int) (long) n;
    }

    /** 内部哨兵：区分「缺」(OUTER) 与「坏」(BAD)。 */
    static final Integer OUTER = Integer.MIN_VALUE;
    static final Integer BAD = Integer.MAX_VALUE;

    /**
     * time.rs:519-553 parts：日期部件——y 必有；细的部件必须带着粗的（有日必有月），
     * 月 1–12、日 1–31、时 0–23、分秒 0–59；季度和月不同时写；出界就是坏条目。
     */
    public static DateParts parts(JsonNode v) {
        if (v == null || !v.isObject()) {
            return null;
        }
        Long year = intOf(v.get("y"));
        if (year == null || year < Integer.MIN_VALUE || year > Integer.MAX_VALUE) {
            return null;
        }
        Integer quarter = part(v, "q", 1, 4);
        Integer month = part(v, "m", 1, 12);
        if (quarter == BAD || month == BAD) {
            return null;
        }
        boolean hasQuarter = quarter != OUTER && quarter != null;
        boolean hasMonth = month != OUTER && month != null;
        if (hasQuarter && hasMonth) {
            return null;
        }
        Integer day = part(v, "d", 1, 31);
        Integer hour = part(v, "h", 0, 23);
        Integer minute = part(v, "min", 0, 59);
        Integer second = part(v, "s", 0, 59);
        if (day == BAD || hour == BAD || minute == BAD || second == BAD) {
            return null;
        }
        boolean[] ladder = {hasMonth, day != OUTER && day != null, hour != OUTER && hour != null,
                minute != OUTER && minute != null, second != OUTER && second != null};
        for (int i = 1; i < ladder.length; i++) {
            if (ladder[i] && !ladder[i - 1]) {
                return null;
            }
        }
        return new DateParts((int) (long) year,
                hasQuarter ? quarter : null,
                hasMonth ? month : null,
                day == OUTER ? null : day,
                hour == OUTER ? null : hour,
                minute == OUTER ? null : minute,
                second == OUTER ? null : second);
    }

    /** time.rs:555-561 opt_parts。 */
    static DateParts optParts(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        DateParts p = parts(v);
        // 三值逻辑：缺失=null 且无错；坏=抛出——调用方以异常表达「坏」不合适，这里以
        // BAD_DATE 哨兵与「缺」区分（Rust Option<Option<…>> 的 Java 形态）
        return p;
    }

    /** optParts 的三值结果：missing=true 表示 JSON 里没有这一格（合法缺失）。 */
    static final class OptParts {
        final boolean missing;
        final DateParts value;

        OptParts(boolean missing, DateParts value) {
            this.missing = missing;
            this.value = value;
        }
    }

    static OptParts optPartsBoxed(JsonNode v) {
        if (v == null || v.isNull()) {
            return new OptParts(true, null);
        }
        DateParts p = parts(v);
        if (p == null) {
            return new OptParts(false, null); // 坏
        }
        return new OptParts(false, p);
    }

    /** time.rs:583-599 anchor。 */
    static Anchor anchor(JsonNode v) {
        if (v == null) {
            return null;
        }
        String kind;
        if (v.isTextual()) {
            kind = v.asText();
        } else {
            JsonNode k = v.get("kind");
            if (k == null || !k.isTextual()) {
                return null;
            }
            kind = k.asText();
        }
        kind = kind.trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "mention" -> {
                Long id = intOf(v.get("id"));
                return id == null ? null : new AnchorMention(id);
            }
            case "document" -> {
                return new AnchorDocument();
            }
            case "period" -> {
                String name = v.get("name") == null ? null : OpenParser.optText(v.get("name"));
                return name == null ? null : new AnchorPeriod(name);
            }
            default -> {
                return null;
            }
        }
    }

    /** time.rs:601-611 offset：数不为负（方向另说）。 */
    static Offset offset(JsonNode v) {
        if (v == null) {
            return null;
        }
        Long count = intOf(v.get("count"));
        if (count == null || count < 0) {
            return null;
        }
        try {
            return new Offset(count, Unit.parse(v.get("unit").asText()),
                    Direction.parse(v.get("direction").asText()));
        } catch (Exception e) {
            return null;
        }
    }

    static final class OptOffset {
        final boolean missing;
        final Offset value;

        OptOffset(boolean missing, Offset value) {
            this.missing = missing;
            this.value = value;
        }
    }

    static OptOffset optOffset(JsonNode v) {
        if (v == null || v.isNull()) {
            return new OptOffset(true, null);
        }
        Offset o = offset(v);
        return o == null ? new OptOffset(false, null) : new OptOffset(false, o);
    }

    /** time.rs:621-644 reference：按 kind 分四种；JSON null 当 none。 */
    static Reference reference(JsonNode v) {
        if (v == null || v.isNull()) {
            return new RefNone();
        }
        JsonNode kindNode = v.get("kind");
        if (kindNode == null || !kindNode.isTextual()) {
            return null;
        }
        String kind = kindNode.asText().trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "absolute" -> {
                DateParts from = parts(v.get("from"));
                if (from == null) {
                    return null;
                }
                OptParts to = optPartsBoxed(v.get("to"));
                if (!to.missing && to.value == null) {
                    return null;
                }
                return new RefAbsolute(from, to.value);
            }
            case "anchored" -> {
                Anchor a = anchor(v.get("anchor"));
                if (a == null) {
                    return null;
                }
                OptOffset off = optOffset(v.get("offset"));
                if (!off.missing && off.value == null) {
                    return null;
                }
                return new RefAnchored(a, off.value);
            }
            case "period" -> {
                String name = v.get("name") == null ? null : OpenParser.optText(v.get("name"));
                if (name == null) {
                    return null;
                }
                OptParts from = optPartsBoxed(v.get("from"));
                if (!from.missing && from.value == null) {
                    return null;
                }
                OptParts to = optPartsBoxed(v.get("to"));
                if (!to.missing && to.value == null) {
                    return null;
                }
                return new RefPeriod(name, from.value, to.value);
            }
            case "none" -> {
                return new RefNone();
            }
            default -> {
                return null;
            }
        }
    }

    /** time.rs:646-655 interpretation：一条 [id, shape, reference, granularity]。 */
    static Interpretation interpretation(JsonNode v) {
        if (v == null || !v.isArray() || v.size() < 4) {
            return null;
        }
        Long id = intOf(v.get(0));
        if (id == null) {
            return null;
        }
        // Rust 的三个 `?`：shape/reference/granularity 任一解不出，整条就是坏条目
        // （reference 返回 null 不抛——必须显式判，漏判会把坏参照当 none 收进库）
        Shape shape;
        Granularity granularity;
        try {
            shape = Shape.parse(v.get(1).asText());
            granularity = Granularity.parse(v.get(3).asText());
        } catch (Exception e) {
            return null;
        }
        Reference reference = reference(v.get(2));
        if (reference == null) {
            return null;
        }
        return new Interpretation(id, shape, reference, granularity);
    }

    /**
     * time.rs:659-677 parse_interpretation_response：返回解开的解读与坏条目数。编号不在
     * ids 里的、同一编号第二次出现的都算坏；截断的回复修补后少掉的条目不算坏——调用方拿
     * ids 对一下就知道谁没回来。
     */
    public static InterpretationReply parseInterpretationResponse(String raw, List<Long> ids) {
        JsonNode value = replyValue(raw, "time interpretation");
        Set<Long> known = new HashSet<>(ids);
        Set<Long> seen = new HashSet<>();
        List<Interpretation> out = new ArrayList<>();
        int[] skipped = {0};
        JsonNode m = value.get("m");
        if (m != null && m.isArray()) {
            for (JsonNode item : m) {
                Interpretation interp = interpretation(item);
                if (interp != null && known.contains(interp.id()) && seen.add(interp.id())) {
                    out.add(interp);
                } else {
                    skipped[0]++;
                }
            }
        }
        return new InterpretationReply(out, skipped[0]);
    }

    /** 解读回复：解读列表 + 坏条目数。 */
    public record InterpretationReply(List<Interpretation> interpretations, int skipped) {
    }
}
