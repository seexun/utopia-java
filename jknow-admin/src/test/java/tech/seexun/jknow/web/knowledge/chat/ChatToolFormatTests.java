package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.ChatGraphQueries;
import tech.seexun.jknow.store.GraphService.GraphChangeRow;

/**
 * 工具排版与时间参数的纯函数族（tools.rs 内联 15 例，断言原值移植）。
 */
class ChatToolFormatTests {

    private final ChatFrames frames = new ChatFrames();

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    // --- parse_when --------------------------------------------------------------

    /// at 与 as_of 共用一个解析：日期按当天零点，RFC3339 原样；别的一律 None，不猜
    @Test
    void aMomentIsADateOrAnRfc3339StampAndNothingElse() {
        assertEquals(t("2024-08-01T00:00:00Z"), ToolExecutor.parseWhen("2024-08-01"));
        assertEquals(t("2026-09-05T02:43:24.197Z"), ToolExecutor.parseWhen(
                " 2026-09-05T02:43:24.197Z "));
        assertNull(ToolExecutor.parseWhen("August 2024"));
        assertNull(ToolExecutor.parseWhen(""));
    }

    // --- changes_window（经 changes 的内部窗口组装验证） ----------------------------

    /// 说「到 3 月 31 日为止」的人要的是含 31 日：SQL 那头是 < end，end 落在 4 月 1 日
    /// 零点——差这一天，问 31 日会安静地丢掉 31 日。但展示串里绝不能出现 04-01
    @Test
    void untilNamesADayTheWindowMustContain() {
        ToolExecutor.Window w = ToolExecutor.changesWindow(d("2026-03-01"), d("2026-03-31"),
                t("2026-06-01T00:00:00Z"));
        assertEquals(t("2026-03-01T00:00:00Z"), w.start());
        assertEquals(t("2026-04-01T00:00:00Z"), w.end());
        assertEquals("2026-03-01 → 2026-03-31", w.label());
    }

    /// 没给 until 时，展示串写 now——写成 end 的格式化等于把服务器时钟当用户问的边界
    @Test
    void anOpenWindowSaysNowRatherThanTheClock() {
        ToolExecutor.Window w = ToolExecutor.changesWindow(d("2026-03-01"), null,
                t("2026-06-01T13:45:00Z"));
        assertEquals(t("2026-06-01T13:45:00Z"), w.end());
        assertEquals("2026-03-01 → now", w.label());
    }

    /// 没有 since 就没有窗口（changes 拒缺 since 的调用）
    @Test
    void withoutSinceThereIsNoWindow() {
        assertNull(ToolExecutor.parseWhen(null));
    }

    // --- change_line --------------------------------------------------------------

    /// 年精度的起点写成年；结束了不知哪天绝不写 now
    @Test
    void aChangeLineShowsEachEndAtItsPrecision() {
        ToolExecutor executor = new ToolExecutor(frames, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        GraphChangeRow c = new GraphChangeRow(UUID.randomUUID(),
                t("2026-08-28T10:00:00Z"), "asserted", UUID.randomUUID(), "Acme",
                "founded in", null, null, t("2019-01-01T00:00:00Z"), null, "year", null,
                0.9f, null, null, null);
        String line = executor.changeLine(c);
        assertTrue(line.contains("[valid 2019 → now]"), line);
        // 年精度与 ended-unknown 的整行经由 TimeText.span：
        String span = TimeText.span(new TimeText.Span(
                t("2019-01-01T00:00:00Z"), "year", null, null, null, null, false, null, null,
                null, null));
        assertEquals("2019 → now", span);
        assertEquals("2019 → ended, date unknown", TimeText.span(new TimeText.Span(
                t("2019-01-01T00:00:00Z"), "year", null, "unknown", null, null, false, null,
                null, null, null)));
    }

    /// 一段（年/月/日）的最后一天；parse_when 与 at 同一种写法
    @Test
    void aWindowEndCoversTheWholePeriodNamed() {
        assertEquals(d("2023-12-31"), ToolExecutor.periodLastDay(d("2023-01-01"), "year"));
        assertEquals(d("2024-02-29"), ToolExecutor.periodLastDay(d("2024-02-01"), "month"));
        assertEquals(d("2023-12-31"), ToolExecutor.periodLastDay(d("2023-12-01"), "month"));
        assertEquals(d("2023-06-15"), ToolExecutor.periodLastDay(d("2023-06-15"), "day"));
        assertEquals(t("2023-01-01T00:00:00Z"), ToolExecutor.parseWhen("2023"));
        assertEquals(t("2023-06-01T00:00:00Z"), ToolExecutor.parseWhen("2023-06"));
    }

    private GraphChangeRow change(String kind) {
        return new GraphChangeRow(UUID.randomUUID(), t("2026-08-28T10:00:00Z"), kind,
                UUID.randomUUID(), "Acme", "founded in", null, null, null, null, null, null,
                0.9f, null, null, null);
    }

    /// 同一秒内也能先录入再更正；截到整秒会把这两次认知重新叠在一起
    @Test
    void changeStampsRoundTripWithoutLosingTheRecordClock() {
        ToolExecutor executor = new ToolExecutor(frames, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        for (String stamp : new String[]{"2026-09-05T02:43:53Z", "2026-09-05T02:43:53.382Z",
                "2026-09-05T02:43:53.382001Z", "2026-09-05T02:43:53.382002Z"}) {
            GraphChangeRow c = new GraphChangeRow(UUID.randomUUID(), t(stamp), "corrected",
                    UUID.randomUUID(), "Acme", "founded in", null, null, null, null, null, null,
                    0.9f, null, null, null);
            String line = executor.changeLine(c);
            String printed = line.split("\\s+")[0];
            assertEquals(stamp, printed, line);
            assertEquals(t(stamp), ToolExecutor.parseWhen(printed));
        }
    }

    /// before 减的是账本分辨率的一微秒——小数为零要向秒借位
    @Test
    void beforeIsTheMicrosecondBeforeAtTheLedgersResolution() {
        OffsetDateTime t0 = t("2026-09-05T02:43:53.382Z");
        assertEquals(t("2026-09-05T02:43:53.381999Z"), ToolExecutor.justBefore(t0));
        assertEquals(t("2026-09-05T02:43:52.999999Z"),
                ToolExecutor.justBefore(t("2026-09-05T02:43:53Z")));
        ToolExecutor.Moments m = new ToolExecutor.Moments(null, null, t0);
        assertEquals("2 facts as recorded before 2026-09-05T02:43:53.382Z",
                ToolExecutor.entityFactsDetail(2, m));
    }

    /// 摘要里两根轴并列时各写各的
    @Test
    void factDetailsKeepTheRecordInstantBesideTheWorldDate() {
        OffsetDateTime at = t("2024-08-01T00:00:00Z");
        OffsetDateTime asOf = t("2026-09-05T02:43:53.382001Z");
        assertEquals("2 facts at 2024-08-01T00:00:00Z, "
                + "as recorded by 2026-09-05T02:43:53.382001Z",
                ToolExecutor.entityFactsDetail(2, new ToolExecutor.Moments(at, asOf, null)));
        assertEquals("2 facts as recorded by 2026-09-05T02:43:53.382001Z",
                ToolExecutor.entityFactsDetail(2, new ToolExecutor.Moments(null, asOf, null)));
        assertEquals("2 facts as of 2024-08-01T00:00:00Z",
                ToolExecutor.entityFactsDetail(2, new ToolExecutor.Moments(at, null, null)));
        assertEquals("0 facts",
                ToolExecutor.entityFactsDetail(0, new ToolExecutor.Moments(null, null, null)));
    }

    // --- fact_line / literal_text ---------------------------------------------------

    private ToolExecutor executor() {
        return new ToolExecutor(frames, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private ChatGraphQueries.ToolEntityFact attributeFact(JsonNode value) {
        return new ChatGraphQueries.ToolEntityFact(UUID.randomUUID(), null, "out", "salary",
                "salary", false, "state", null, null, null, value, List.of(),
                t("2023-06-01T00:00:00Z"), "day", t("2024-02-20T00:00:00Z"), "day",
                t("2023-06-01T00:00:00Z"), t("2024-02-20T00:00:00Z"), 0.9f, false, false, null,
                null, null, null, null);
    }

    /// 属性事实的值要到模型眼前（#348）：从前区间和置信度都在，唯独值没到
    @Test
    void anAttributeFactShowsTheModelItsValue() {
        String line = executor().factLine(attributeFact(frames.parse(
                "{\"value\":28000,\"unit\":\"CNY\"}")), "");
        assertTrue(line.startsWith("salary → 28000 CNY"), line);
        assertTrue(line.contains("(2023-06-01 → 2024-02-20)"), line);
        String flag = executor().factLine(attributeFact(frames.parse("{\"value\":true}")), "");
        assertTrue(flag.startsWith("salary → ✓"), flag);
        String mapped = executor().factLine(attributeFact(frames.parse(
                "{\"summary\":\"orders.total\"}")), "");
        assertTrue(mapped.startsWith("salary → orders.total"), mapped);
    }

    /// 本体没接住的关系仍然是 ?
    @Test
    void aMissingObjectStillReadsAsAQuestionMark() {
        String line = executor().factLine(attributeFact(null), "");
        assertTrue(line.startsWith("salary → ?"), line);
    }

    /// 字面值要读成它自己，不连引号印成 JSON
    @Test
    void aLiteralValueReadsAsItselfNotAsJson() {
        GraphChangeRow c = new GraphChangeRow(UUID.randomUUID(),
                t("2026-08-28T10:00:00Z"), "asserted", UUID.randomUUID(), "Acme",
                "founded in", null, frames.parse("\"1993\""), null, null, null, null, 0.9f,
                null, null, null);
        assertTrue(executor().changeLine(c).endsWith("Acme founded in 1993"),
                executor().changeLine(c));
    }

    /// 两根轴必须在同一行里看得出是两样东西
    @Test
    void theRecordTimeAndTheValidRangeDoNotReadAsOneDateRun() {
        GraphChangeRow c = new GraphChangeRow(UUID.randomUUID(),
                t("2026-08-28T10:00:00Z"), "corrected", UUID.randomUUID(), "Acme",
                "headquartered in", "Berlin", null, t("2019-01-01T00:00:00Z"), null, "day",
                null, 0.9f, null, null, null);
        String line = executor().changeLine(c);
        assertTrue(line.startsWith("2026-08-28T10:00:00Z corrected: "), line);
        assertTrue(line.contains("[valid 2019-01-01 → now]"), line);
    }

    /// 没有证据就什么都别说：凭空补一句 from "…" 会让无出处的断言看起来有出处
    @Test
    void aFactWithNoEvidenceClaimsNoDocument() {
        GraphChangeRow c = new GraphChangeRow(UUID.randomUUID(),
                t("2026-08-28T10:00:00Z"), "rejected", UUID.randomUUID(), "Acme",
                "headquartered in", "Berlin", null, null, null, null, null, 0.9f, null,
                null, null);
        assertFalse(executor().changeLine(c).contains("from"), executor().changeLine(c));
    }

    @Test
    void evidenceCarriesTheFilenameAndTheQuote() {
        GraphChangeRow c = new GraphChangeRow(UUID.randomUUID(),
                t("2026-08-28T10:00:00Z"), "corrected", UUID.randomUUID(), "Acme",
                "headquartered in", "Berlin", null, null, null, null, null, 0.9f, null,
                "annual-report.pdf", "moved its head office to Berlin");
        String line = executor().changeLine(c);
        assertTrue(line.contains("from \"annual-report.pdf\""), line);
        assertTrue(line.contains("\"moved its head office to Berlin\""), line);
    }
}
