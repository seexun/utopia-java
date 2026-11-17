package tech.seexun.jknow.web.knowledge.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.impl.LiteralLabel;
import tech.seexun.jknow.extract.ExtractLib;
import tech.seexun.jknow.extract.ExtractLib.ParsedTime;

/**
 * RDF 字面量铸造规则（rdf.rs:244-971 字面量五件套逐字移植——recon §1.4 的「最易差不多
 * 漂移」面，29 个内联测试的断言与期望值逐字锚定）。
 *
 * <p>五件套：
 * <ol>
 *   <li>{@link #literalValue}——{"value"} 标量优先 → summary/class 散文（xsd:string 不套
 *       声明类型）→ 认不出 → <b>不写 rdf:object</b>；relative 一律 plain string；</li>
 *   <li>{@link #decimalText}——BigDecimal toPlainString 去指数（1e-7 → 0.0000001）；</li>
 *   <li>{@link #dateLiteral}——属性日期保留原文时钟与时区（±HH 归一 ±HH:MM、扩展年去
 *       {@code +}、小时/分精度补 :00:00）；</li>
 *   <li>{@link #worldTime}——世界时间按当时量到的精度落 gYear/gYearMonth/date/dateTime
 *       （亚日精度另写 utopia:validFromPrecision）；</li>
 *   <li>{@link #dt}——记录轴时刻的 xsd:dateTime（chrono AutoSi：0/3/6/9 位小数取最短档）。</li>
 * </ol>
 *
 * <p>{@link #doubleText} 复刻 ryu 1.0.23 的 format64（serde_json 的 f64 打印）：JDK 的
 * Double.toString 规定「小数点后至少一位」（4.9E-324 而非 5E-324），与 ryu 的最短位
 * 数字不同——两边的 xsd:decimal 词法形会差一位。这里按「最短可往返十进制 + ryu 的
 * 定点/科学式分档」逐条复刻，使 decimal_text 的输入文本与 Rust 逐字一致。
 */
public final class RdfLiterals {

    private RdfLiterals() {
    }

    // =====================================================================
    // 记录轴时刻 / 世界时间（rdf.rs:244-265）
    // =====================================================================

    /** chrono %Y：0..=9999 补零到 4 位；&gt;9999 带 +；&lt;0 带 -（绝对值补零到 4 位）。 */
    static String yearFmt(int year) {
        if (year < 0) {
            return "-" + String.format("%04d", -year);
        }
        if (year > 9999) {
            return "+" + year;
        }
        return String.format("%04d", year);
    }

    static String dateFmt(int year, int month, int day) {
        return yearFmt(year) + "-" + String.format("%02d", month) + "-" + String.format("%02d", day);
    }

    /**
     * 记录轴时刻 → xsd:dateTime（rdf.rs:244-249 dt：chrono to_rfc3339_opts(AutoSi, true)）。
     * 小数位取能保值的最短档（0/3/6/9 位）；时刻一律 UTC（读库时已折 UTC，与 sqlx 的
     * DateTime&lt;Utc&gt; 同口径），时区打 Z。
     */
    public static String dtText(OffsetDateTime at) {
        StringBuilder sb = new StringBuilder();
        sb.append(dateFmt(at.getYear(), at.getMonthValue(), at.getDayOfMonth())).append('T')
                .append(String.format("%02d", at.getHour())).append(':')
                .append(String.format("%02d", at.getMinute())).append(':')
                .append(String.format("%02d", at.getSecond()));
        int nano = at.getNano();
        if (nano != 0) {
            if (nano % 1_000_000 == 0) {
                sb.append('.').append(String.format("%03d", nano / 1_000_000));
            } else if (nano % 1_000 == 0) {
                sb.append('.').append(String.format("%06d", nano / 1_000));
            } else {
                sb.append('.').append(String.format("%09d", nano));
            }
        }
        sb.append('Z');
        return sb.toString();
    }

    /** dt 的节点形态。 */
    public static Node dt(OffsetDateTime at) {
        return typed(dtText(at), XSDDatatype.XSDdateTime);
    }

    /**
     * 世界时间按<b>当时量到的精度</b>落字面值（rdf.rs:253-265 world_time）：只知道年份就
     * 写 gYear——一律写成 xsd:date 等于替账本补上它从来没有过的确定性。年/月切片语义
     * 与 Rust 逐字（{@code iso[..4]}）。
     */
    public static Node worldTime(OffsetDateTime at, String precision) {
        String iso = dateFmt(at.getYear(), at.getMonthValue(), at.getDayOfMonth());
        String p = precision == null ? "" : precision;
        switch (p) {
            case "year":
                return typed(iso.substring(0, 4), XSDDatatype.XSDgYear);
            case "month":
                return typed(iso.substring(0, 7), XSDDatatype.XSDgYearMonth);
            // 小时以下（0024）：xsd:dateTime 说不出「到分为止」，精度由 emit_validity 另写一条
            case "hour", "minute", "second":
                return typed(dateFmt(at.getYear(), at.getMonthValue(), at.getDayOfMonth())
                        + "T" + String.format("%02d", at.getHour())
                        + ":" + String.format("%02d", at.getMinute())
                        + ":" + String.format("%02d", at.getSecond()) + "Z",
                        XSDDatatype.XSDdateTime);
            default:
                return typed(iso, XSDDatatype.XSDdate);
        }
    }

    /** 小时以下的精度：XSD 的类型说不出来，另写一条 utopia:*Precision（rdf.rs:269-271）。 */
    public static String subDay(String precision) {
        if (precision == null) {
            return null;
        }
        return switch (precision) {
            case "hour", "minute", "second" -> precision;
            default -> null;
        };
    }

    // =====================================================================
    // 字面量五件套（rdf.rs:273-283 + 796-971）
    // =====================================================================

    public static Node text(String s) {
        return NodeFactory.createLiteralString(s);
    }

    /**
     * 置信度两位小数（rdf.rs:277-279）。Rust 的 {:.2} 是 std flt2dec 的
     * <b>half-to-even</b>——Java Formatter 是 HALF_UP（0.625→"0.63" vs Rust "0.62"），
     * 必须用 BigDecimal HALF_EVEN 对齐（M5 任务 1 审查 I-1；规则取平均可达精确半值）。
     */
    public static Node confidence(float c) {
        return typed(new java.math.BigDecimal(c).setScale(2, java.math.RoundingMode.HALF_EVEN)
                .toPlainString(), XSDDatatype.XSDdecimal);
    }

    /** 布尔字面量（rdf.rs:281-283）。 */
    public static Node flag(boolean b) {
        return typed(b ? "true" : "false", XSDDatatype.XSDboolean);
    }

    /**
     * 词法形 + 数据类型的节点（不经 Jena 的值归一——xsd 的词法形就是合同）。Jena 的
     * NodeFactory.createLiteral* 在构造时就按 datatype 解析词法形，超过纳秒的小数秒
     * （"…T14:32:07.123456789012Z"）会被拒——Rust 侧只保词法、序列化原样落笔，这里用
     * {@link LexicalDatatype}（parse=原样返回）绕过值解析，URI 仍是真 XSD URI。
     */
    public static Node typed(String lexical, XSDDatatype datatype) {
        return NodeFactory.createLiteralDT(lexical, LexicalDatatype.of(datatype.getURI()));
    }

    /**
     * 只认词法的 datatype 装饰器：parse 原样返回、isValid 恒真——字面量的「值」就是
     * 它的词法形，Jena 不再试图把它折成 Java 对象（导出要的是词法保真，不是值计算）。
     */
    static final class LexicalDatatype implements RDFDatatype {

        private static final Map<String, LexicalDatatype> CACHE = new ConcurrentHashMap<>();
        private final String uri;

        private LexicalDatatype(String uri) {
            this.uri = uri;
        }

        static LexicalDatatype of(String uri) {
            return CACHE.computeIfAbsent(uri, LexicalDatatype::new);
        }

        @Override
        public String getURI() {
            return uri;
        }

        @Override
        public String unparse(Object value) {
            return String.valueOf(value);
        }

        @Override
        public Object parse(String lexicalForm) {
            return lexicalForm;
        }

        @Override
        public boolean isValid(String lexicalForm) {
            return true;
        }

        @Override
        public boolean isValidValue(Object value) {
            return true;
        }

        @Override
        public boolean isValidLiteral(LiteralLabel lit) {
            return uri.equals(lit.getDatatypeURI());
        }

        @Override
        public boolean isEqual(LiteralLabel value1, LiteralLabel value2) {
            return value1.getLexicalForm().equals(value2.getLexicalForm())
                    && value1.getDatatypeURI().equals(value2.getDatatypeURI());
        }

        @Override
        public int getHashCode(LiteralLabel lit) {
            return (lit.getLexicalForm() + uri).hashCode();
        }

        @Override
        public Class<?> getJavaClass() {
            return String.class;
        }

        @Override
        public Object cannonicalise(Object value) {
            return value;
        }

        @Override
        public Object extendedTypeDefinition() {
            return null;
        }

        @Override
        public RDFDatatype normalizeSubType(Object value, RDFDatatype dt) {
            return dt;
        }

        @Override
        public String toString() {
            return "lexical:" + uri;
        }
    }

    /** 值上带着 "relative": true：原文只相对一件事给出它，没有日历上的日期（rdf.rs:796-798）。 */
    static boolean isRelative(JsonNode v) {
        JsonNode r = v.get("relative");
        return r != null && r.isBoolean() && r.asBoolean();
    }

    /**
     * {"value": …, "unit": …} 或 {"summary": …} 等情况下抽出字面量（rdf.rs:810-823 literal_value）。
     *
     * <p><b>认不出就返回 null（#821）</b>：an absent object is not an empty literal——
     * 缺对象/解析不出对象都不写 rdf:object 三元组，免得 {@code rdf:object=""} 把审计工具
     * 误导成「事实无对象」。相对的值写成普通字符串：{@code "45 days after the Trigger
     * Date"^^xsd:date} 是个不合法的字面量。
     */
    public static Node literalValue(JsonNode v, String datatype) {
        Text t = literalText(v);
        if (t == null) {
            return null;
        }
        if (t.prose() || isRelative(v)) {
            return text(t.value());
        }
        if (datatype == null) {
            return text(t.value());
        }
        return switch (datatype) {
            case "number" -> typed(decimalText(t.value()), XSDDatatype.XSDdecimal);
            case "date" -> {
                Node lit = dateLiteral(t.value());
                yield lit != null ? lit : typed(t.value(), XSDDatatype.XSDdate);
            }
            case "bool" -> typed(t.value(), XSDDatatype.XSDboolean);
            default -> text(t.value());
        };
    }

    /**
     * JSON 数可以带指数，xsd:decimal 不可以（rdf.rs:827-845 decimal_text）。用精确十进制
     * 展开，不再过一次 f64，也不舍入；没有指数的写法（包括负零）原样保留。指数只在
     * [-324, 308] 且系数只含数字/±/. 时才展开——不让异常的大指数触发无界分配。
     */
    public static String decimalText(String input) {
        int e = -1;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == 'e' || c == 'E') {
                e = i;
                break;
            }
        }
        if (e < 0) {
            return input;
        }
        String coefficient = input.substring(0, e);
        String exponent = input.substring(e + 1);
        boolean supportedExponent;
        try {
            int exp = Integer.parseInt(exponent);
            supportedExponent = exp >= -324 && exp <= 308;
        } catch (NumberFormatException nfe) {
            supportedExponent = false;
        }
        boolean decimalCoefficient = !coefficient.isEmpty();
        for (int i = 0; i < coefficient.length() && decimalCoefficient; i++) {
            char c = coefficient.charAt(i);
            if (!(Character.isDigit(c) || c == '+' || c == '-' || c == '.')) {
                decimalCoefficient = false;
            }
        }
        if (supportedExponent && decimalCoefficient) {
            try {
                return new BigDecimal(input).toPlainString();
            } catch (NumberFormatException ignored) {
                // 落回原样（Rust parse 失败同路）
            }
        }
        return input;
    }

    /**
     * 属性日期保留的是<b>原文</b>，不是世界时间区间（rdf.rs:852-908 date_literal）。
     * parse_time 只在没有钟点时借它判断年/月/日；时钟和时区从原文规范化：±HH/±HHMM
     * 归一成 XSD 要求的 ±HH:MM；扩展年去掉 chrono 加的 +（XSD 年份不允许正号）；
     * 小时/分精度补 :00:00（类型本身表达不了「只精确到小时/分钟」）。认不出返回 null，
     * 调用方沿用原输出（xsd:date 原文）。
     */
    public static Node dateLiteral(String value) {
        int sep = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 'T' || c == ' ') {
                sep = i;
                break;
            }
        }
        if (sep < 0) {
            ParsedTime t = ExtractLib.parseTime(value);
            if (t == null) {
                return null;
            }
            String lexical;
            XSDDatatype datatype;
            switch (t.precision()) {
                case "year" -> {
                    lexical = yearFmt(t.time().getYear());
                    datatype = XSDDatatype.XSDgYear;
                }
                case "month" -> {
                    lexical = yearFmt(t.time().getYear()) + "-"
                            + String.format("%02d", t.time().getMonthValue());
                    datatype = XSDDatatype.XSDgYearMonth;
                }
                default -> {
                    lexical = dateFmt(t.time().getYear(), t.time().getMonthValue(),
                            t.time().getDayOfMonth());
                    datatype = XSDDatatype.XSDdate;
                }
            }
            return typed(trimStartPlus(lexical), datatype);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value.substring(0, sep));
        } catch (Exception e) {
            return null;
        }
        String calendarDate = trimStartPlus(
                dateFmt(date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
        String clockZone = value.substring(sep + 1);
        String clock;
        String zone;
        if (clockZone.endsWith("Z") || clockZone.endsWith("z")) {
            clock = clockZone.substring(0, clockZone.length() - 1);
            zone = "Z";
        } else {
            int at = -1;
            for (int i = 0; i < clockZone.length(); i++) {
                char c = clockZone.charAt(i);
                if (c == '+' || c == '-') {
                    at = i;
                    break;
                }
            }
            if (at >= 0) {
                clock = clockZone.substring(0, at);
                zone = xsdTimezone(clockZone.substring(at));
                if (zone == null) {
                    return null;
                }
            } else {
                // XSD 允许没有时区的 dateTime；不能替原文猜一个 UTC
                clock = clockZone;
                zone = "";
            }
        }
        // split 保留尾空段（Rust split(':') 不丢；审查 M-1——"14:32:" 的空秒段要走
        // !is_empty() 拒绝落回 xsd:date，Java 默认丢弃会把脏行升成 dateTime）
        String[] parts = clock.split(":", -1);
        String hour;
        String minute;
        String second;
        switch (parts.length) {
            case 1 -> {
                hour = parts[0];
                minute = "00";
                second = "00";
            }
            case 2 -> {
                hour = parts[0];
                minute = parts[1];
                second = "00";
            }
            case 3 -> {
                hour = parts[0];
                minute = parts[1];
                second = parts[2];
            }
            default -> {
                return null;
            }
        }
        String fraction = "";
        int dot = second.indexOf('.');
        if (dot >= 0) {
            String frac = second.substring(dot + 1);
            if (frac.isEmpty()) {
                return null;
            }
            for (int i = 0; i < frac.length(); i++) {
                if (!isAsciiDigit(frac.charAt(i))) {
                    return null;
                }
            }
            fraction = "." + frac;
            second = second.substring(0, dot);
        }
        for (String part : new String[]{hour, minute, second}) {
            if (part.isEmpty()) {
                return null;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!isAsciiDigit(part.charAt(i))) {
                    return null;
                }
            }
        }
        int h;
        int m;
        int s;
        try {
            h = Integer.parseInt(hour);
            m = Integer.parseInt(minute);
            s = Integer.parseInt(second);
            LocalTime.of(h, m, s);
        } catch (Exception e) {
            return null;
        }
        // dateTime 必须有 HH:MM:SS；补零表示该小时/分钟的起点。小数秒不交给 java.time，
        // 避免超过纳秒的原文被截断
        return typed(calendarDate + "T" + String.format("%02d", h) + ":" + String.format("%02d", m)
                + ":" + String.format("%02d", s) + fraction + zone, XSDDatatype.XSDdateTime);
    }

    /** parse_time 也接受 ±HH 和 ±HHMM，XSD 时区则要求 ±HH:MM（rdf.rs:911-934）。 */
    static String xsdTimezone(String zone) {
        if (!zone.chars().allMatch(c -> c < 128)) {
            return null;
        }
        String normalized;
        switch (zone.length()) {
            case 3 -> normalized = zone + ":00";
            case 5 -> normalized = zone.substring(0, 3) + ":" + zone.substring(3);
            case 6 -> {
                if (zone.charAt(3) != ':') {
                    return null;
                }
                normalized = zone;
            }
            default -> {
                return null;
            }
        }
        String hh = normalized.substring(1, 3);
        String mm = normalized.substring(4, 6);
        if (!hh.chars().allMatch(RdfLiterals::isAsciiDigitChar)
                || !mm.chars().allMatch(RdfLiterals::isAsciiDigitChar)) {
            return null;
        }
        int hour = Integer.parseInt(hh);
        int minute = Integer.parseInt(mm);
        if (hour > 14 || minute > 59 || (hour == 14 && minute != 0)) {
            return null;
        }
        return normalized;
    }

    private static boolean isAsciiDigitChar(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String trimStartPlus(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '+') {
            i++;
        }
        return s.substring(i);
    }

    /** literal_text 的产物：可写的字面文本 + 这段文本是不是人写的散文。 */
    record Text(String value, boolean prose) {
    }

    /**
     * 把事实的 object_value 形状抽出可写的字面文本（rdf.rs:950-971 literal_text）。
     *
     * <p>认得的形态（其它都视作缺值）：直接标量；{"value": …}（递归，标量优先）；
     * {"summary"|"class": …}（value 缺席或解析不到时才看，非空才取，标 true=散文）。
     * 老的行为是 {@code v.get("value").unwrap_or(v)}：键缺失时把整个对象当字面值（#831）。
     */
    static Text literalText(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return new Text(Boolean.toString(v.asBoolean()), false);
        }
        if (v.isNumber()) {
            return new Text(numberText(v), false);
        }
        if (v.isTextual()) {
            return new Text(v.asText(), false);
        }
        if (v.isArray()) {
            return null;
        }
        JsonNode value = v.get("value");
        if (value != null) {
            Text inner = literalText(value);
            if (inner != null) {
                return new Text(inner.value(), false);
            }
        }
        for (String key : new String[]{"summary", "class"}) {
            JsonNode s = v.get(key);
            if (s != null && s.isTextual() && !s.asText().isEmpty()) {
                return new Text(s.asText(), true);
            }
        }
        return null;
    }

    /**
     * JSON 数的文本形：整数原样；浮点走 {@link #doubleText}（ryu 复刻）。
     * Rust serde_json 的 Number::to_string = 整数十进制 / ryu f64。
     */
    static String numberText(JsonNode n) {
        if (n.isIntegralNumber()) {
            return n.asText();
        }
        if (n.isFloatingPointNumber()) {
            return doubleText(n.asDouble());
        }
        return n.asText();
    }

    /**
     * ryu 1.0.23 format64 的 Java 复刻（serde_json 对 f64 的打印）。两步：
     * <ol>
     *   <li>最短位数字：1..17 位里找第一个 round-trip 的十进制（对精确二进制值
     *       HALF_EVEN 取整——ryu 同目标：最短、最近、平手取偶）；</li>
     *   <li>ryu 的定点/科学式分档（pretty/mod.rs 逐条）：0≤k 且 kk≤16 → 整数补零带 .0；
     *       0&lt;kk≤16 → 定点小数；-5&lt;kk≤0 → 0.000ddd；单数字 → de±XX；其余 → d.dddde±XX
     *       （指数不带 + 不补零）。</li>
     * </ol>
     * JDK 的 Double.toString 规定小数点后至少一位（4.9E-324 而非 5E-324），与 ryu 不同——
     * 这就是不复用它而逐条复刻的原因。
     */
    static String doubleText(double v) {
        if (v == 0.0) {
            return Double.doubleToRawLongBits(v) < 0 ? "-0.0" : "0.0";
        }
        boolean negative = v < 0;
        // 候选按绝对值搜（round-trip 也按绝对值的位形比对），符号最后补——负零已在上行挡掉
        long targetBits = Double.doubleToRawLongBits(Math.abs(v));
        BigDecimal exact = new BigDecimal(Math.abs(v));
        for (int len = 1; len <= 17; len++) {
            BigDecimal cand = exact.round(new MathContext(len, RoundingMode.HALF_EVEN));
            if (Double.doubleToRawLongBits(cand.doubleValue()) == targetBits) {
                return (negative ? "-" : "") + ryuFormat(cand);
            }
        }
        return Double.toString(v);
    }

    /** ryu 的落笔分档（输入：已 round-trip 的最短数字候选）。 */
    private static String ryuFormat(BigDecimal cand) {
        BigDecimal stripped = cand.stripTrailingZeros();
        String digits = stripped.unscaledValue().abs().toString();
        int length = digits.length();
        int k = -stripped.scale();
        int kk = length + k;
        if (0 <= k && kk <= 16) {
            // 1234e7 -> 12340000000.0
            StringBuilder sb = new StringBuilder(digits);
            for (int i = length; i < kk; i++) {
                sb.append('0');
            }
            return sb.append(".0").toString();
        }
        if (0 < kk && kk <= 16) {
            // 1234e-2 -> 12.34
            return digits.substring(0, kk) + "." + digits.substring(kk);
        }
        if (-5 < kk && kk <= 0) {
            // 1234e-6 -> 0.001234
            StringBuilder sb = new StringBuilder("0.");
            for (int i = 0; i < -kk; i++) {
                sb.append('0');
            }
            return sb.append(digits).toString();
        }
        if (length == 1) {
            // 1e30
            return digits + "e" + (kk - 1);
        }
        // 1234e30 -> 1.234e33
        return digits.charAt(0) + "." + digits.substring(1) + "e" + (kk - 1);
    }
}
