package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationPair;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationSide;
import tech.seexun.jknow.extract.ExtractLib.Quantity;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 移植自 crates/utopia-extract/src/lib.rs 内联 mod tests（17 例）+ mod a_number_is_one_number
 * （1 例）——取块补括号、开头预算、量的读法（含整数十进制对拍）、契约时间与写出来的
 * 日期、裁决消息与修补解析。断言逐条不改写。
 */
class ExtractLibTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** #690：思考过程里的大括号不能把 JSON 的起止带偏。 */
    @Test
    void jsonBlockIgnooresAThinkBlockAndAFenceBeforeTheJson() {
        String raw = "先想想 {\"a\": 1，再回答。\n</think>{\"entities\":[{\"name\":\"张三\"}]}";
        assertEquals("{\"entities\":[{\"name\":\"张三\"}]}",
                JsonFuns.jsonBlock(raw));
        String fenced = "好的，结果如下：\n```json\n{\"entities\":[]}\n```";
        assertEquals("{\"entities\":[]}", JsonFuns.jsonBlock(fenced));
        assertThrows(Exception.class, () -> JsonFuns.jsonBlock("no json here"));
    }

    /** 括号出现在字符串里不算结构；断在字符串中间的那一截不可用。 */
    @Test
    void bracketsInsideStringsAreNotStructure() {
        assertEquals("{\"s\": [{\"a\": \"a[b{c\"}]}",
                JsonFuns.closeBrackets("{\"s\": [{\"a\": \"a[b{c\"}"));
        assertNull(JsonFuns.closeBrackets("{\"s\": \"cut in the mid"));
        assertNull(JsonFuns.closeBrackets("]"));
    }

    @Test
    void aLongOpeningIsCutOnACharacterBoundary() {
        String longText = "租".repeat(OpenPrompts.OPENING_BUDGET_CHARS + 10);
        String block = OpenPrompts.openingBlock(longText);
        assertEquals(OpenPrompts.OPENING_BUDGET_CHARS, count(block, '租'));
        assertTrue(block.contains(" …"));
        assertEquals("", OpenPrompts.openingBlock("   "));
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    @Test
    void writtenMagnitudesPreserveFractionalValues() {
        for (Object[] row : new Object[][]{
                {"1.00000025 million", 1000000.25d},
                {"100.000025万", 1000000.25d},
                {"-1.00000025 million", -1000000.25d},
                {"+100.000025万", 1000000.25d},
                {"9.2亿", 920000000.0d},
                {"0.00000000025 thousand", 0.00000025d},
                {"0 million", 0.0d},
                {"1,000.00025 thousand", 1000000.25d}}) {
            String input = (String) row[0];
            double expected = (Double) row[1];
            assertEquals(new Quantity(expected, null), ExtractLib.parseQuantity(input), input);
            assertEquals(new Quantity(expected, null), ExtractLib.parseLeadingQuantity(input),
                    input);
            JsonNode v = ExtractLib.normalizeAttrValue("number", JSON.getNodeFactory()
                    .textNode(input));
            assertEquals(expected, v.asDouble(), 0.0, input);
        }
        assertEquals(new Quantity(1000000.25, "$"), ExtractLib.parseQuantity("$1.00000025 million"));
        assertEquals(new Quantity(1000000.25, "people"),
                ExtractLib.parseLeadingQuantity("1.00000025 million people worldwide"));
        for (String rejected : new String[]{
                "3M", "5k", "1e3 million", "1.2.3 million", "--1 million", "1 million people"}) {
            assertNull(ExtractLib.parseQuantity(rejected), rejected);
        }
        assertNull(ExtractLib.parseQuantity("9".repeat(400) + " trillion"));
        assertEquals(new Quantity(1000000.25, null),
                ExtractLib.parseQuantity("0".repeat(20_000) + "1.00000025 million"));
    }

    /** 对拍：精确的整数移位后再按普通十进制读——浮点乘法会吃掉有意义的余数。 */
    @Test
    void writtenMagnitudesMatchIntegerDecimalOracles() {
        Map<String, Integer> magnitudes = Map.of(
                "thousand", 3, "万", 4, "million", 6, "千万", 7, "亿", 8, "billion", 9,
                "trillion", 12);
        List<Long> coefficients = List.of(0L, 1L, 25L, 100000025L, 9200000000L,
                9007199254740991L);
        for (var entry : magnitudes.entrySet()) {
            String word = entry.getKey();
            int exponent = entry.getValue();
            for (long coefficient : coefficients) {
                for (int places = 0; places <= 15; places++) {
                    BigInteger divisor = BigInteger.TEN.pow(places);
                    BigInteger expanded = BigInteger.valueOf(coefficient)
                            .multiply(BigInteger.TEN.pow(exponent));
                    String coefficientDecimal = decimal(BigInteger.valueOf(coefficient),
                            divisor, places);
                    String expandedDecimal = decimal(expanded, divisor, places);
                    for (String sign : new String[]{"", "-"}) {
                        String input = sign + coefficientDecimal + " " + word;
                        double expected = Double.parseDouble(
                                sign + (expandedDecimal.startsWith("-") ? "" : "")
                                        + expandedDecimal);
                        assertEquals(new Quantity(expected, null),
                                ExtractLib.parseQuantity(input), input);
                    }
                }
            }
        }
    }

    private static String decimal(BigInteger n, BigInteger divisor, int places) {
        if (places == 0) {
            return n.toString();
        }
        return n.divide(divisor) + "."
                + String.format("%" + places + "s", n.mod(divisor).toString()).replace(' ', '0');
    }

    @Test
    void aQuantityIsTheWholeStringOrNothing() {
        assertEquals(new Quantity(5e9, "$"), ExtractLib.parseQuantity("$5 billion"));
        assertEquals(new Quantity(1.5e6, "€"), ExtractLib.parseQuantity("€1.5 million"));
        assertEquals(new Quantity(52.0, "%"), ExtractLib.parseQuantity("52%"));
        assertEquals(new Quantity(3.5e6, null), ExtractLib.parseQuantity("3.5 million"));
        assertEquals(new Quantity(35000.0, null), ExtractLib.parseQuantity("35,000"));
        assertEquals(new Quantity(42.0, null), ExtractLib.parseQuantity("  42 "));
        // 币种：符号、ISO 码、中英文单词，统一成符号；量级：英文全写与中文千万亿
        assertEquals(new Quantity(3e7, "€"), ExtractLib.parseQuantity("EUR 30 million"));
        assertEquals(new Quantity(3e7, "€"), ExtractLib.parseQuantity("30 million euros"));
        assertEquals(new Quantity(5e9, "$"), ExtractLib.parseQuantity("USD 5 billion"));
        assertEquals(new Quantity(2e8, "$"), ExtractLib.parseQuantity("2亿美元"));
        assertEquals(new Quantity(1.5e9, "¥"), ExtractLib.parseQuantity("15亿元人民币"));
        assertEquals(new Quantity(3e7, "¥"), ExtractLib.parseQuantity("3000万元"));
        assertEquals(new Quantity(1.5e8, null), ExtractLib.parseQuantity("1.5亿"));
        // 乘过量级的数收成整数：9.2 亿不是 919999999.9999999
        assertEquals(new Quantity(920000000.0, "¥"), ExtractLib.parseQuantity("9.2亿元"));
        assertEquals(new Quantity(2500000000.0, "$"), ExtractLib.parseQuantity("$2.5 billion"));

        // 尾巴上还有实词：含义不再只是那个数，宁可当实体也不当量
        assertNull(ExtractLib.parseQuantity("900 million weekly active users"));
        assertNull(ExtractLib.parseQuantity("2025 Atlantic hurricane season"));
        assertNull(ExtractLib.parseQuantity("$10 billion investment"));
        assertNull(ExtractLib.parseQuantity("8GW data center"));
        // 单字母后缀不认：3M 是一家公司，读成三百万就把一个真实体吃掉了
        assertNull(ExtractLib.parseQuantity("3M"));
        assertNull(ExtractLib.parseQuantity("5k"));
        // 两个记号撞一起，不是量
        assertNull(ExtractLib.parseQuantity("$5%"));
        assertNull(ExtractLib.parseQuantity(""));
        assertNull(ExtractLib.parseQuantity("杭州"));
    }

    @Test
    void aDeclaredNumberReadsPastTheUnit() {
        assertEquals(new Quantity(1250.0, "people"),
                ExtractLib.parseLeadingQuantity("1,250 people"));
        assertEquals(new Quantity(42.0, "%"),
                ExtractLib.parseLeadingQuantity("42% from customers in Europe"));
        assertEquals(new Quantity(3400.0, "people"),
                ExtractLib.parseLeadingQuantity("3,400 people worldwide"));
        assertEquals(new Quantity(9e8, "weekly"),
                ExtractLib.parseLeadingQuantity("900 million weekly active users"));
        // 整体就是量的仍走严的那套：单位是 $，不是 billion
        assertEquals(new Quantity(5e9, "$"), ExtractLib.parseLeadingQuantity("$5 billion"));
        // 币种在尾巴上也认；认不出的词才落到「单位是第一个词」
        assertEquals(new Quantity(3e7, "€"),
                ExtractLib.parseLeadingQuantity("30 million euros in cash"));
        assertEquals(new Quantity(1.5e9, "¥"),
                ExtractLib.parseLeadingQuantity("15亿元人民币的投资"));
        assertEquals(new Quantity(3e7, "francs"),
                ExtractLib.parseLeadingQuantity("30 million francs"));
        assertNull(ExtractLib.parseLeadingQuantity("about ten"));
        assertNull(ExtractLib.parseLeadingQuantity(""));

        // 严的那套一点没松：它要判「是不是一个东西」，判错会吃掉真实体
        assertNull(ExtractLib.parseQuantity("1,250 people"));
        assertNull(ExtractLib.parseQuantity("2025 Atlantic hurricane season"));
    }

    @Test
    void aNumberAttributeTakesAWrittenQuantity() {
        assertEquals(5e9, ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("$5 billion")).asDouble(), 0.0);
        assertEquals(52.0, ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("52%")).asDouble(), 0.0);
        assertEquals(35000.0, ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("35,000")).asDouble(), 0.0);
        assertNull(ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("about ten")));
    }

    @Test
    void parseTimePrecisions() {
        assertEquals("year", ExtractLib.parseTime("2024").precision());
        assertEquals("month", ExtractLib.parseTime("2024-07").precision());
        assertEquals("day", ExtractLib.parseTime("2024-07-15").precision());
        // 带时区的钟点：到分、到时、到秒，值截到那一位，偏移换回 UTC
        var t = ExtractLib.parseTime("2026-06-01T14:32Z");
        assertEquals("2026-06-01T14:32:00Z", t.time().toInstant().toString());
        assertEquals("minute", t.precision());
        assertEquals("hour", ExtractLib.parseTime("2026-06-01T14Z").precision());
        t = ExtractLib.parseTime("2026-06-01T14:32:07.382+08:00");
        assertEquals("2026-06-01T06:32:07Z", t.time().toInstant().toString());
        assertEquals("second", t.precision());
        // 没时区的钟点不是时刻：只记那一天
        t = ExtractLib.parseTime("2026-06-01T14:32");
        assertEquals("2026-06-01T00:00:00Z", t.time().toInstant().toString());
        assertEquals("day", t.precision());
        assertNull(ExtractLib.parseTime("null"));
        assertNull(ExtractLib.parseTime(""));
        assertNull(ExtractLib.parseTime("下个月"));
        // 契约格式之外的写法不归它：工具参数里的「August 2024」不猜
        assertNull(ExtractLib.parseTime("June 23, 2020"));
        // 读模型回复的那一个收写出来的日期（#688），精度随写了几位
        var r = ExtractLib.readTime("June 23, 2020");
        assertEquals("2020-06-23T00:00:00Z", r.time().toInstant().toString());
        assertEquals("day", r.precision());
        assertEquals("month", ExtractLib.readTime("March 2020").precision());
        assertEquals("month", ExtractLib.readTime("2024-07").precision());
        assertNull(ExtractLib.readTime("03/04/2020"));
    }

    /** 合同与公告里的日期写法（#688）：说得清是哪天的都收成规则 3 的样子，说不清的不猜。 */
    @Test
    void aWrittenDateIsReadOnlyWhenItsFormSaysWhichDay() {
        for (String written : new String[]{
                "March 17, 2020", "March 17 2020", "march 17, 2020", "MARCH 17, 2020",
                "Mar 17, 2020", "Mar. 17, 2020", "17 March 2020", "17 Mar. 2020",
                "17 March, 2020", "  March 17, 2020 ", "2020/03/17", "2020.3.17",
                "2020年3月17日"}) {
            assertEquals("2020-03-17", ExtractLib.normalizeAttrValue("date",
                    JSON.getNodeFactory().textNode(written)).asText(), written);
        }
        // 只写到月的是月，不补日
        for (String written : new String[]{"March 2020", "Mar. 2020", "2020年3月"}) {
            assertEquals("2020-03", ExtractLib.normalizeAttrValue("date",
                    JSON.getNodeFactory().textNode(written)).asText(), written);
        }
        // 已经照规则 3 写的原样留着
        assertEquals("2020-03-17", ExtractLib.normalizeAttrValue("date",
                JSON.getNodeFactory().textNode("2020-03-17")).asText());
        assertEquals("2020", ExtractLib.normalizeAttrValue("date",
                JSON.getNodeFactory().textNode("2020")).asText());
        // 日月都是数字、年不在前：说不清是几月几号
        for (String ambiguous : new String[]{"03/04/2020", "3.4.2020", "04-03-2020", "3/4/20"}) {
            assertNull(ExtractLib.normalizeAttrValue("date",
                    JSON.getNodeFactory().textNode(ambiguous)), ambiguous);
        }
        // 不是日期，或不是一个真实的日子
        for (String notADate : new String[]{
                "45 days after the Trigger Date", "Q3 2020", "Sometime 2020",
                "February 30, 2020", "March 17, 20", "March 123, 2020", "2020/13/01",
                "2020/03", "next March"}) {
            assertNull(ExtractLib.normalizeAttrValue("date",
                    JSON.getNodeFactory().textNode(notADate)), notADate);
        }
        // 区间端点读的是同一个解析
        var t = ExtractLib.readTime("17 Mar 2020");
        assertEquals("2020-03-17", t.time().toLocalDate().toString());
        assertEquals("day", t.precision());
    }

    @Test
    void aSimilarityProposedPairSaysSoInThePrompt() {
        List<AdjudicationPair> pairs = List.of(
                new AdjudicationPair(side("张伟"), side("财务部总监张伟"), List.of(),
                        ExtractLib.proposedBecause("0.78")),
                new AdjudicationPair(side("张伟"), side("张伟"), List.of(),
                        ExtractLib.proposedBecause(null)));
        String user = ExtractLib.buildAdjudicationMessages(pairs).get(1).content();
        String first = user.substring(0, user.indexOf("Pair 1:"));
        String second = user.substring(user.indexOf("Pair 1:"));
        assertTrue(first.contains("Why paired:") && first.contains("cosine 0.78"), first);
        assertFalse(second.contains("Why paired:"), "同名对不该带这一行：" + second);
    }

    private static AdjudicationSide side(String name) {
        return new AdjudicationSide(name, "person", List.of());
    }

    @Test
    void aProposalNoteNeedsACosine() {
        assertTrue(ExtractLib.proposedBecause("0.62") != null);
        assertNull(ExtractLib.proposedBecause(null));
    }

    @Test
    void parseAdjudicationReply() {
        String raw = "```json\n{\"verdicts\":[{\"i\":0,\"verdict\":\"same\",\"confidence\":0.92},{\"i\":1,\"verdict\":\"unsure\"}]}\n```";
        var v = ExtractLib.parseAdjudication(raw);
        assertEquals(2, v.size());
        assertEquals("same", v.get(0).verdict());
        assertNull(v.get(1).confidence());
    }

    /** 形状好的回复不算修补过，裁决与 parse_adjudication 一字不差。 */
    @Test
    void aWellFormedReplyIsNotRepaired() {
        String raw = "{\"verdicts\":[{\"i\":0,\"verdict\":\"same\",\"confidence\":0.92,\"why\":\"one line\"}]}";
        var r = ExtractLib.parseAdjudicationRepairing(raw);
        assertFalse(r.repaired());
        assertEquals(1, r.verdicts().size());
        assertEquals("one line", r.verdicts().get(0).why());
        assertEquals(0.92f, r.verdicts().get(0).confidence(), 0.0f);
        var plain = ExtractLib.parseAdjudication(raw);
        assertEquals(1, plain.size());
        assertEquals(plain.get(0).why(), r.verdicts().get(0).why());
    }

    /** 模型在引号里的理由中间直接换行：修补后整批照读，换行留在理由里，标记修补过。 */
    @Test
    void aRawNewlineInsideAReasonStringIsRepaired() {
        String raw = "```json\n{\"verdicts\":[\n"
                + "  {\"i\":0,\"verdict\":\"different\",\"confidence\":0.9,\n"
                + "   \"reason\":\"Rule 2: a version is not the series.\nRecord A is Claude 3, Record B is the family.\",\n"
                + "   \"why\":\"a version\nis not the series\"},\n"
                + "  {\"i\":1,\"verdict\":\"same\",\"confidence\":0.8,\"why\":\"tab\there\"}\n"
                + "]}\n```";
        assertThrows(Exception.class, () -> ExtractLib.parseAdjudicationRepairing(
                "{\"verdicts\":[{\"i\":0,\"verdict\":\"same\",\"why\":\"a\""));
        var r = ExtractLib.parseAdjudicationRepairing(raw);
        assertTrue(r.repaired());
        assertEquals(2, r.verdicts().size());
        assertEquals("different", r.verdicts().get(0).verdict());
        assertEquals("a version\nis not the series", r.verdicts().get(0).why());
        assertEquals("tab\there", r.verdicts().get(1).why());
        // 直接走 parse_adjudication 也一样读得出来
        assertEquals(2, ExtractLib.parseAdjudication(raw).size());
    }

    /** 转义只碰字符串里的：引号外的换行是合法空白；\" 不结束字符串；已转义的 \\n 不动。 */
    @Test
    void onlyControlCharactersInsideStringsAreEscaped() {
        String text = "{\n  \"a\": \"x\ny\",\n  \"b\": \"q\\\"\nz\",\n  \"c\": \"already\\nescaped\"\n}";
        String fixed = ExtractLib.escapeControlInStrings(text);
        assertEquals("{\n  \"a\": \"x\\ny\",\n  \"b\": \"q\\\"\\nz\",\n  \"c\": \"already\\nescaped\"\n}",
                fixed);
        JsonNode v = json(fixed);
        assertEquals("x\ny", v.get("a").asText());
        assertEquals("q\"\nz", v.get("b").asText());
        assertEquals("already\nescaped", v.get("c").asText());
        String clean = "{\"a\": \"fine\"}";
        assertEquals(clean, ExtractLib.escapeControlInStrings(clean));
    }

    @Test
    void normalizeAttrValues() {
        assertEquals(35000.0, ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("35,000")).asDouble(), 0.0);
        // JSON 里的整数也落成同一种数：42 与 "42" 解出来是同一个值
        assertEquals(42.0, ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().numberNode(42)).asDouble(), 0.0);
        assertNull(ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("about ten")));
        assertEquals("2024-07", ExtractLib.normalizeAttrValue("date",
                JSON.getNodeFactory().textNode("2024-07")).asText());
        assertNull(ExtractLib.normalizeAttrValue("date",
                JSON.getNodeFactory().textNode("下个月")));
        assertTrue(ExtractLib.normalizeAttrValue("bool",
                JSON.getNodeFactory().textNode("yes")).asBoolean());
        assertEquals("CTO", ExtractLib.normalizeAttrValue("text",
                JSON.getNodeFactory().textNode(" CTO ")).asText());
        assertNull(ExtractLib.normalizeAttrValue("text", JSON.createArrayNode().add(1)));
    }

    /** 模型写 65 还是 "65%"，落下来都是同一个数——不然同一条边上会记成冲突。 */
    @Test
    void aNumberIsOneNumberHoweverItIsWritten() {
        JsonNode a = ExtractLib.normalizeAttrValue("number", JSON.getNodeFactory().numberNode(65));
        JsonNode b = ExtractLib.normalizeAttrValue("number",
                JSON.getNodeFactory().textNode("65%"));
        JsonNode c = ExtractLib.normalizeAttrValue("number", JSON.getNodeFactory().textNode("65"));
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(65.0, a.asDouble(), 0.0);
    }
}
