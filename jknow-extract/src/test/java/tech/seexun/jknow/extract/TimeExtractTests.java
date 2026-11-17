package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.TimeExtract.AnchorDocument;
import tech.seexun.jknow.extract.TimeExtract.AnchorMention;
import tech.seexun.jknow.extract.TimeExtract.AnchorPeriod;
import tech.seexun.jknow.extract.TimeExtract.DateParts;
import tech.seexun.jknow.extract.TimeExtract.Direction;
import tech.seexun.jknow.extract.TimeExtract.Granularity;
import tech.seexun.jknow.extract.TimeExtract.Interpretation;
import tech.seexun.jknow.extract.TimeExtract.InterpretationReply;
import tech.seexun.jknow.extract.TimeExtract.MentionInput;
import tech.seexun.jknow.extract.TimeExtract.NamedPeriod;
import tech.seexun.jknow.extract.TimeExtract.Offset;
import tech.seexun.jknow.extract.TimeExtract.RefAbsolute;
import tech.seexun.jknow.extract.TimeExtract.RefAnchored;
import tech.seexun.jknow.extract.TimeExtract.RefNone;
import tech.seexun.jknow.extract.TimeExtract.RefPeriod;
import tech.seexun.jknow.extract.TimeExtract.Shape;
import tech.seexun.jknow.extract.TimeExtract.TimeContext;
import tech.seexun.jknow.extract.TimeExtract.TimeEntry;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 移植自 crates/utopia-extract/src/time.rs 内联 mod tests（12 例）+ heading_code_regressions
 * （3 例）——标题路径、now_in_force、DateParts 的季度、解读回复的解析容错与提示词。
 * 断言逐条不改写。
 */
class TimeExtractTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void theHeadingsInForceAreReadFromTheChunkItself() {
        String text = "# 周报汇编\n\n## 第35周周报\n\n提报日期：2026年8月28日\n\n### 风险\n无。"
                + "\n\n## 第36周周报\n\n上周开售。\n";
        java.util.function.ToIntFunction<String> at =
                needle -> text.indexOf(needle);
        assertEquals(List.of("周报汇编", "第35周周报"),
                TimeExtract.headingsAt(text, at.applyAsInt("提报日期")));
        assertEquals(List.of("周报汇编", "第35周周报", "风险"),
                TimeExtract.headingsAt(text, at.applyAsInt("无。")));
        // 同级的新标题顶掉旧的，连同它下面更深的
        assertEquals(List.of("周报汇编", "第36周周报"),
                TimeExtract.headingsAt(text, at.applyAsInt("上周开售")));
        // `#` 后面没有空白的不是标题（#588、#标签）
        assertEquals(List.of(), TimeExtract.headingsAt("#588 是一个编号\n正文", 12));
    }

    @Test
    void theNowInForceIsTheOneOfTheInnermostSectionThatHoldsThePosition() {
        java.util.function.Function<String[], TimeEntry> entry = row -> new TimeEntry(
                row[2], "提报日期", row[0],
                new DateParts(2026, null, 9, 4, null, null, null), null,
                List.of(row[1].split(",")).stream().map(String::strip).toList(),
                null, null);
        List<TimeEntry> entries = List.of(
                entry.apply(new String[]{"2026年3月28日", "汇编", "now"}),
                entry.apply(new String[]{"2026年8月28日", "汇编,第35周", "now"}),
                entry.apply(new String[]{"2026年9月4日", "汇编,第36周", "now"}),
                entry.apply(new String[]{"2026年8月31日", "汇编,第36周", "date"}));
        assertEquals("2026年9月4日",
                TimeExtract.nowInForce(entries, List.of("汇编", "第36周")).words());
        assertEquals("2026年8月28日",
                TimeExtract.nowInForce(entries, List.of("汇编", "第35周", "风险")).words());
        // 别的节里的不管这里；退到整篇的那个
        assertEquals("2026年3月28日",
                TimeExtract.nowInForce(entries, List.of("汇编", "附录")).words());
        assertNull(TimeExtract.nowInForce(entries.subList(1, entries.size()),
                List.of("别的文档")));
    }

    @Test
    void aQuarterOfAStatedYearIsTranscribedNotComputed() {
        DateParts q = TimeExtract.parts(json("{\"y\": 2025, \"q\": 3}"));
        assertEquals(2025, q.year());
        assertEquals(3, q.quarter());
        assertNull(q.month());
        assertEquals("month", q.granularity());
        assertEquals("2025-Q3", q.display());
        // 季度和月不同时写；第五季度不存在
        assertNull(TimeExtract.parts(json("{\"y\": 2025, \"q\": 3, \"m\": 7}")));
        assertNull(TimeExtract.parts(json("{\"y\": 2025, \"q\": 5}")));
        // 老数据里没有这一格：读回来是 None
        DateParts old = new DateParts(2024, null, 3, null, null, null, null);
        assertNull(old.quarter());
    }

    @Test
    void aContextEntryIsReadWithItsPartsAndABadOneIsRefused() {
        JsonNode ok = json(
                "[\"now\", \"提报日期\", \"2026年9月4日\", {\"y\": 2026, \"m\": 9, \"d\": 4}, null]");
        TimeEntry e = TimeExtract.timeEntry(ok);
        assertEquals("now", e.kind());
        assertEquals("提报日期", e.name());
        assertEquals("2026年9月4日", e.words());
        assertEquals("2026-09-04", e.from().display());
        JsonNode period = json("[\"period\", \"报告期\", \"2025年1月1日至2025年12月31日\","
                + " {\"y\": 2025, \"m\": 1, \"d\": 1}, {\"y\": 2025, \"m\": 12, \"d\": 31}]");
        assertEquals("2025-12-31", TimeExtract.timeEntry(period).to().display());
        for (String bad : new String[]{
                "[\"today\", \"x\", \"2026\", {\"y\": 2026}, null]",
                "[\"now\", \"x\", \"\", {\"y\": 2026}, null]",
                "[\"now\", \"x\", \"13月\", {\"y\": 2026, \"m\": 13}, null]",
                "[\"now\", \"x\", \"2026\"]"}) {
            assertNull(TimeExtract.timeEntry(json(bad)), bad);
        }
    }

    /** 审查 M-7：浮点部件是坏条目（serde_json as_i64 对 f64 是 None，不截断）。 */
    @Test
    void aFractionalPartIsRejectedNotTruncated() {
        assertNull(TimeExtract.parts(json("{\"y\": 2026, \"m\": 3.0}")));
        assertNull(TimeExtract.parts(json("{\"y\": 2026.5}")));
        assertEquals(3, TimeExtract.parts(json("{\"y\": 2026, \"m\": 3}")).month());
    }

    private static DateParts ymd(int y, int m, int d) {
        return new DateParts(y, null, m, d, null, null, null);
    }

    private static DateParts year(int y) {
        return new DateParts(y, null, null, null, null, null, null);
    }

    @Test
    void aClockTimeParsesAndPrintsOnTheLadder() {
        DateParts date = TimeExtract.parts(
                json("{\"y\": 2011, \"m\": 3, \"d\": 4, \"h\": 14, \"min\": 30, \"s\": 5}"));
        assertEquals("second", date.granularity());
        assertEquals("2011-03-04 14:30:05", date.display());
        assertEquals("2024", year(2024).display());
        assertEquals("year", year(2024).granularity());
        assertEquals("day", ymd(2019, 9, 30).granularity());
    }

    /** 四种引用、七种形状都在这一份回复里。 */
    private static final String INTERPRETED = """
            {"m": [
                [1, "point", {"kind": "absolute", "from": {"y": 2011, "m": 3, "d": 4}}, "day"],
                [2, "since", {"kind": "anchored", "anchor": {"kind": "document"}, "offset": {"count": 2, "unit": "year", "direction": "before"}}, "year"],
                [3, "interval", {"kind": "period", "name": "fiscal 2019"}, "day"],
                [4, "until", {"kind": "none"}, "day"],
                [5, "as_of", {"kind": "anchored", "anchor": {"kind": "document"}}, "day"],
                [6, "duration", {"kind": "none"}, "year"],
                [7, "ended_unknown", null, "day"],
                [8, "point", {"kind": "anchored", "anchor": {"kind": "mention", "id": 1}, "offset": {"count": 3, "unit": "month", "direction": "after"}}, "month"],
                [9, "interval", {"kind": "absolute", "from": {"y": 2024, "m": 3}, "to": {"y": 2024, "m": 5}}, "month"],
                [10, "interval", {"kind": "period", "name": "Three months ended June 30, 2019", "to": {"y": 2019, "m": 6, "d": 30}}, "day"],
                [11, "point", {"kind": "anchored", "anchor": {"kind": "period", "name": "fiscal 2019"}, "offset": {"count": 0, "unit": "quarter", "direction": "after"}}, "month"]
            ]}""";

    @Test
    void anInterpretationReplyParsesEveryReferenceKindAndShape() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 11)
                .boxed().toList();
        InterpretationReply r = TimeExtract.parseInterpretationResponse(INTERPRETED, ids);
        assertEquals(0, r.skipped());
        assertEquals(11, r.interpretations().size());
        List<Interpretation> items = r.interpretations();

        assertEquals(new Interpretation(1, Shape.POINT,
                new RefAbsolute(ymd(2011, 3, 4), null), Granularity.DAY), items.get(0));
        assertEquals(new RefAnchored(new AnchorDocument(),
                new Offset(2, TimeExtract.Unit.YEAR, Direction.BEFORE)),
                items.get(1).reference());
        assertEquals(Shape.SINCE, items.get(1).shape());
        assertEquals(Granularity.YEAR, items.get(1).granularity());
        assertEquals(new RefPeriod("fiscal 2019", null, null), items.get(2).reference());
        assertEquals(Shape.INTERVAL, items.get(2).shape());
        assertEquals(new RefNone(), items.get(3).reference());
        assertEquals(Shape.UNTIL, items.get(3).shape());
        assertEquals(new RefAnchored(new AnchorDocument(), null), items.get(4).reference());
        assertEquals(Shape.AS_OF, items.get(4).shape());
        assertEquals(Shape.DURATION, items.get(5).shape());
        assertEquals(Shape.ENDED_UNKNOWN, items.get(6).shape());
        assertEquals(new RefNone(), items.get(6).reference(), "JSON null reads as none");
        assertEquals(new RefAnchored(new AnchorMention(1),
                new Offset(3, TimeExtract.Unit.MONTH, Direction.AFTER)),
                items.get(7).reference());
        assertEquals(new RefAbsolute(new DateParts(2024, null, 3, null, null, null, null),
                new DateParts(2024, null, 5, null, null, null, null)),
                items.get(8).reference());
        assertEquals(new RefPeriod("Three months ended June 30, 2019", null, ymd(2019, 6, 30)),
                items.get(9).reference());
        assertEquals(new RefAnchored(new AnchorPeriod("fiscal 2019"),
                new Offset(0, TimeExtract.Unit.QUARTER, Direction.AFTER)),
                items.get(10).reference());
    }

    /** 坏形状、13 月、不在清单里的编号、重复的编号、坏单位各计一次；好的照收。 */
    @Test
    void malformedInterpretationItemsAreCountedNotFatal() {
        String raw = """
                {"m": [
                    [1, "point", {"kind": "absolute", "from": {"y": 2011, "m": 3, "d": 4}}, "day"],
                    [2, "sometime", {"kind": "none"}, "day"],
                    [3, "point", {"kind": "absolute", "from": {"y": 2011, "m": 13}}, "month"],
                    [99, "point", {"kind": "none"}, "day"],
                    [1, "since", {"kind": "none"}, "day"],
                    [4, "point", {"kind": "anchored", "anchor": {"kind": "document"}, "offset": {"count": 2, "unit": "fortnight", "direction": "after"}}, "day"],
                    [5, "point", {"kind": "elsewhere"}, "day"],
                    [6, "point", {"kind": "none"}, "fortnight"],
                    [7, "AS OF", {"kind": "anchored", "anchor": "document"}, "Day"]
                ]}""";
        InterpretationReply r = TimeExtract.parseInterpretationResponse(raw,
                List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertEquals(2, r.interpretations().size());
        assertEquals(1, r.interpretations().get(0).id());
        assertEquals(7, r.interpretations().get(1).id(),
                "case and spacing of the words are forgiven");
        assertEquals(Shape.AS_OF, r.interpretations().get(1).shape());
        assertEquals(Granularity.DAY, r.interpretations().get(1).granularity());
        assertEquals(new RefAnchored(new AnchorDocument(), null),
                r.interpretations().get(1).reference());
        assertEquals(7, r.skipped());
    }

    /** 截在第三条中间：前两条留下，没有报错。 */
    @Test
    void aCutOffInterpretationReplyKeepsTheCompleteItems() {
        String marker = "[3, \"interval\", {\"kind\": \"period\", \"name\": \"fis";
        int cut = INTERPRETED.indexOf(marker) + marker.length();
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList();
        InterpretationReply r = TimeExtract.parseInterpretationResponse(
                INTERPRETED.substring(0, cut), ids);
        assertEquals(0, r.skipped());
        assertEquals(2, r.interpretations().size());
        assertEquals(2, r.interpretations().get(1).id());
    }

    @Test
    void aReplyWithoutJsonIsAnError() {
        assertThrows(Exception.class,
                () -> TimeExtract.parseInterpretationResponse("no", List.of(1L)));
    }

    /** 语料里的字样：提示词里一个都不许有（例子是镇议会、桥、面包房）。 */
    private static final String[] CORPUS_MARKS = {
            "NVIDIA", "Food and Drug", "FDA", "January 25, 2027", "January 26, 2026",
            "July 26, 2026", "fiscal 2027", "上年末", "统计公报", "10-Q", "10-K"};

    @Test
    void theInterpretationPromptCarriesTheContextAndEveryMention() {
        DateParts date = ymd(2019, 11, 12);
        List<NamedPeriod> periods = List.of(
                new NamedPeriod("fiscal 2019", ymd(2018, 10, 1), ymd(2019, 9, 30)),
                new NamedPeriod("the fourth quarter", ymd(2019, 7, 1), ymd(2019, 9, 30)));
        TimeContext context = new TimeContext(date, "November 12, 2019", periods, new int[]{9, 30});
        List<MentionInput> mentions = List.of(
                new MentionInput(7, "two years later",
                        "The bridge opened in 2019; two years later the eastern span was widened."),
                new MentionInput(8, "this quarter", "Sales this quarter rose at the bakery."));
        List<ChatMessage> msgs = TimeExtract.buildInterpretationMessages(context, mentions);
        assertEquals(2, msgs.size());
        String system = msgs.get(0).content();
        assertTrue(system.contains("Never compute"));
        for (String shape : new String[]{
                "\"point\"", "\"since\"", "\"until\"", "\"interval\"", "\"as_of\"",
                "\"duration\"", "\"ended_unknown\""}) {
            assertTrue(system.contains(shape), "shape " + shape + " is not explained");
        }
        for (String mark : CORPUS_MARKS) {
            assertTrue(!system.contains(mark), "prompt carries " + mark);
        }
        String user = msgs.get(1).content();
        assertTrue(user.contains("2019-11-12"));
        assertTrue(user.contains("\"November 12, 2019\""));
        assertTrue(user.contains("\"fiscal 2019\" from 2018-10-01 to 2019-09-30"));
        assertTrue(user.contains("\"the fourth quarter\""));
        assertTrue(user.contains("fiscal year end: month 9, day 30"));
        assertTrue(user.contains("id 7, words \"two years later\""));
        assertTrue(user.contains(mentions.get(0).sentence()));
        assertTrue(user.contains("id 8, words \"this quarter\""));
        assertTrue(user.contains(mentions.get(1).sentence()));
    }

    @Test
    void anUndatedDocumentSaysSoInTheContext() {
        List<ChatMessage> msgs = TimeExtract.buildInterpretationMessages(
                new TimeContext(null, null, List.of(), null), List.of());
        String user = msgs.get(1).content();
        assertTrue(user.contains("date: not stated"));
        assertTrue(user.contains("periods: none named"));
        assertTrue(user.contains("fiscal year end: not stated"));
    }

    /**
     * 存成 JSONB 再读回来，一字不差——Java 侧以「解释参照的 JSON 形状」对等：写回
     * time_mentions.reference 的形状由 server 侧 referenceJson 生成（ResolveTimeJob 测试
     * 断言）；纯模块这一侧钉住的是「同一份回复解析两次结果一致」与形状枚举的线上写法。
     */
    @Test
    void interpretationsRoundTripThroughSerde() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList();
        InterpretationReply first = TimeExtract.parseInterpretationResponse(INTERPRETED, ids);
        InterpretationReply again = TimeExtract.parseInterpretationResponse(INTERPRETED, ids);
        assertEquals(first.interpretations(), again.interpretations());
        // 形状与粒度的线上写法（serde snake_case）
        assertEquals("since", first.interpretations().get(1).shape().asString());
        assertEquals("year", first.interpretations().get(1).granularity().asString());
        assertEquals("ended_unknown", first.interpretations().get(6).shape().asString());
        assertEquals("as_of", first.interpretations().get(4).shape().asString());
    }

    // ---- heading_code_regressions（time.rs:812-841）----

    @Test
    void codeExamplesDoNotReplaceDocumentTimeScopes() {
        for (String code : new String[]{
                "```markdown\n## Sample\n```", "~~~\n## Sample\n~~~", "    ## Sample"}) {
            String text = "# Report\n" + code + "\nActual date here";
            assertEquals(List.of("Report"), TimeExtract.headingsAt(text, text.length()), code);
        }
    }

    @Test
    void closingFenceRequiresTheSameMarkerAndNoNonspaceTail() {
        String text = "# Report\n````\n```\n## Still code\n```` trailing\n## Also code\n````\n## Real\n";
        assertEquals(List.of("Report", "Real"),
                TimeExtract.headingsAt(text, text.length()));
    }

    @Test
    void unicodeOffsetsAndNestedRealHeadingsRemainCorrect() {
        String text = "# 周报\n## 数据\n日期";
        assertEquals(List.of("周报", "数据"), TimeExtract.headingsAt(text, text.length()));
    }
}
