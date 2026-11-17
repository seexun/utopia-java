package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.SettingsService;

/**
 * 移植自 crates/utopia-server/src/time_resolution_pushed_tests.rs（8 例）——推送的陈述把
 * when/ended 写成完整日期或带区时刻时 resolve_time 自己读（#1089）：不问模型，没有对话
 * 模型也照常收工。脚本化 SSE 模型端点 + 真库。断言逐条不改写。
 */
class TimeResolutionPushedTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExtractionJob extraction;
    @Autowired
    ResolveTimeJob resolveTime;
    @Autowired
    SettingsService settings;

    /** 一个 statements 来源、一篇推送来的文档（块就是载荷）、随需一个脚本化对话端点。 */
    private static final class Rig {
        final UUID org;
        final UUID kb;
        final UUID doc;
        final ScriptedModelServer model;

        Rig(UUID org, UUID kb, UUID doc, ScriptedModelServer model) {
            this.org = org;
            this.kb = kb;
            this.doc = doc;
            this.model = model;
        }
    }

    private Rig rig(String payloadJson, List<String> replies) throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID source = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        // 存的文档是 push_statements 自己重排出来的那份：身份、日期（有的话），
        // 再是契约的三个数组，键序固定
        String text = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(payloadJson));
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'pushed-time')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'pushed-time')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'pushed-time')",
                kb, ws);
        jdbc.update("INSERT INTO sources(id,kb_id,kind,name) VALUES (?, ?, 'statements', 'robot-1')",
                source, kb);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256,source_id)"
                + " VALUES (?, ?, 'obs-1.json', ?, ?)", doc, kb, "x" + doc, source);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, ?)",
                chunk, kb, doc, text);
        ScriptedModelServer model = replies != null ? ScriptedModelServer.start(replies) : null;
        if (model != null) {
            settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        }
        // 抽取：推送路径按契约解析、不问模型（0054）；这一步把陈述与时间词写进库
        extraction.extractDocument(doc, ExtractionJob.Proposer.none());
        return new Rig(org, kb, doc, model);
    }

    private void cleanup(Rig rig) {
        if (rig.model != null) {
            rig.model.stop();
        }
        jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ? OR payload->>'kb_id' = ?",
                rig.doc.toString(), rig.kb.toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", rig.org);
    }

    /** 陈述的开放行：(宾语名, valid_from, from 精度, valid_to, to 精度, 起点等级)。 */
    private List<List<Object>> factRows(UUID kb) {
        return jdbc.query(
                "SELECT o.canonical_name, f.valid_from, f.valid_from_precision, f.valid_to,"
                        + " f.valid_to_precision, f.valid_from_grade FROM facts f"
                        + " JOIN entities o ON o.id = f.object_id WHERE f.kb_id = ?"
                        + " AND f.layer = 'open' AND f.invalidated_at IS NULL"
                        + " ORDER BY o.canonical_name",
                (rs, i) -> (List<Object>) (List) Arrays.asList(rs.getString(1),
                        rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getString(5), rs.getString(6)),
                kb);
    }

    /** 时间提及行：(字, 槽, 等级, 解释形状)。 */
    private List<List<Object>> mentionRows(UUID kb) {
        return jdbc.query(
                "SELECT text, role, grade, shape FROM time_mentions WHERE kb_id = ?"
                        + " ORDER BY role, text",
                (rs, i) -> (List<Object>) (List) Arrays.asList(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)),
                kb);
    }

    private static String payload(String when, String ended) {
        String endedJson = ended == null ? "null" : ("\"" + ended + "\"");
        return "{\"external_id\": \"obs-1\", \"doc_time\": \"2026-10-01T00:00:00Z\","
                + " \"e\": [[\"cup-7\", \"cup\", true], [\"kitchen table\", \"table\", true]],"
                + " \"s\": [[null, \"cup-7\", \"is on\", \"kitchen table\", null, null, \"" + when
                + "\", " + endedJson + "]], \"n\": []}";
    }

    private static String payloadTwo(String whenA, String whenB) {
        return "{\"external_id\": \"obs-1\", \"doc_time\": \"2026-10-01T00:00:00Z\","
                + " \"e\": [[\"cup-7\", \"cup\", true], [\"kitchen table\", \"table\", true],"
                + " [\"counter\", \"table\", true]],"
                + " \"s\": [[null, \"cup-7\", \"is on\", \"kitchen table\", null, null, \"" + whenA
                + "\", null],"
                + " [null, \"cup-7\", \"is on\", \"counter\", null, null, \"" + whenB
                + "\", null]], \"n\": []}";
    }

    private static OffsetDateTime day(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    private static OffsetDateTime at(int y, int mo, int d, int h, int mi, int s) {
        return OffsetDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.UTC);
    }

    /** 没有对话模型：载荷写明的日期由代码读成 A 级、字写到的精度，任务照常完成。 */
    @Test
    void aPushedDateIsResolvedInCodeWithoutAModel() throws Exception {
        Rig rig = rig(payload("2026-09-23", null), null);
        try {
            resolveTime.resolveDocument(rig.doc);
            assertEquals(rows(new Object[]{"kitchen table", day(2026, 9, 23), "day",
                    day(2026, 9, 23), "day", "A"}), factRows(rig.kb),
                    "a bare date is a single date at day precision, grade A");
            assertEquals(rows(new Object[]{"2026-09-23", "when", "A", "point"}),
                    mentionRows(rig.kb), "the literal string stays as the mention");
        } finally {
            cleanup(rig);
        }
    }

    /** 带 Z 与带偏移的时刻：精度到秒，值是换算成 UTC 的同一刻——偏移不是贴标签。 */
    @Test
    void aPushedTimestampResolvesAtSecondPrecisionAndItsZoneCounts() throws Exception {
        Rig rig = rig(payload("2026-09-23T08:14:03Z", null), null);
        try {
            resolveTime.resolveDocument(rig.doc);
            assertEquals(rows(new Object[]{"kitchen table", at(2026, 9, 23, 8, 14, 3),
                    "second", at(2026, 9, 23, 8, 14, 3), "second", "A"}), factRows(rig.kb));
        } finally {
            cleanup(rig);
        }
        Rig offset = rig(payload("2026-09-23T08:14:03+08:00", null), null);
        try {
            resolveTime.resolveDocument(offset.doc);
            // +08:00 的 08:14:03 是 UTC 的 00:14:03
            assertEquals(rows(new Object[]{"kitchen table", at(2026, 9, 23, 0, 14, 3),
                    "second", at(2026, 9, 23, 0, 14, 3), "second", "A"}), factRows(offset.kb));
        } finally {
            cleanup(offset);
        }
    }

    /** 字写到哪一级，精度就到哪一级：T08 是小时、T08:14 是分钟——不替它补秒（0024）。 */
    @Test
    void thePrecisionIsTheOneThePayloadWrites() throws Exception {
        for (Object[] row : new Object[][]{
                {"2026-09-23", new int[]{0, 0, 0}, "day"},
                {"2026-09-23T08Z", new int[]{8, 0, 0}, "hour"},
                {"2026-09-23T08:14Z", new int[]{8, 14, 0}, "minute"},
                {"2026-09-23T08:14:03Z", new int[]{8, 14, 3}, "second"}}) {
            String when = (String) row[0];
            int[] expected = (int[]) row[1];
            String precision = (String) row[2];
            Rig rig = rig(payload(when, null), null);
            try {
                resolveTime.resolveDocument(rig.doc);
                assertEquals(rows(new Object[]{"kitchen table",
                        at(2026, 9, 23, expected[0], expected[1], expected[2]), precision,
                        at(2026, 9, 23, expected[0], expected[1], expected[2]), precision, "A"}),
                        factRows(rig.kb), when + " resolves at " + precision);
            } finally {
                cleanup(rig);
            }
        }
    }

    /** when 加 ended 是一个区间：起是 when、止是 ended，各带自己的精度。 */
    @Test
    void whenPlusEndedIsAnInterval() throws Exception {
        Rig rig = rig(payload("2026-09-20", "2026-09-23T08:14:03Z"), null);
        try {
            resolveTime.resolveDocument(rig.doc);
            assertEquals(rows(new Object[]{"kitchen table", day(2026, 9, 20), "day",
                    at(2026, 9, 23, 8, 14, 3), "second", "A"}), factRows(rig.kb));
        } finally {
            cleanup(rig);
        }
    }

    /** 光杆的年与年月不在这一刀里：没有模型时留着 C；自然语言的时间词同样留着 C。 */
    @Test
    void aBareYearOrYearMonthOrWordsAreLeftForAModel() throws Exception {
        for (String when : new String[]{"2026", "2026-09", "last Tuesday"}) {
            Rig rig = rig(payload(when, null), null);
            try {
                resolveTime.resolveDocument(rig.doc);
                assertEquals(rows(new Object[]{when, "when", "C", null}), mentionRows(rig.kb),
                        when + " is not code-resolved");
                assertEquals(rows(new Object[]{"kitchen table", null, null, null, null, "C"}),
                        factRows(rig.kb),
                        when + ": the statement keeps its time words, unresolved, and the job"
                                + " ends");
            } finally {
                cleanup(rig);
            }
        }
    }

    /** 一份载荷两种字：读得动的解成 A，读不动的留 C——两种结局可以并存。 */
    @Test
    void aMixedPushResolvesWhatCodeCanAndLeavesTheRest() throws Exception {
        Rig rig = rig(payloadTwo("2026-09-23", "last Tuesday"), null);
        try {
            resolveTime.resolveDocument(rig.doc);
            assertEquals(rows(new Object[]{"2026-09-23", "when", "A", "point"},
                    new Object[]{"last Tuesday", "when", "C", null}), mentionRows(rig.kb));
        } finally {
            cleanup(rig);
        }
    }

    /** 文档里的字长得像 RFC 3339 也不走推送那条路：判据是来源种类，不是字的形状。 */
    @Test
    void aDocumentMentionThatLooksMachineReadableStaysOnTheModelPath() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        String text = "The cup was on the kitchen table at 2026-09-23T08:14:03Z.";
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'document-path')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'document-path')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'document-path')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, 'note.md', ?)",
                doc, kb, "x" + doc);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, ?)",
                chunk, kb, doc, text);
        // 契约里日期部件用紧凑键（y/m/d/h/min/s）
        String absolute = "{\"kind\": \"absolute\","
                + " \"from\": {\"y\": 2026, \"m\": 9, \"d\": 23, \"h\": 8, \"min\": 14, \"s\": 3}}";
        ScriptedModelServer model = ScriptedModelServer.start(List.of(
                "{\"e\": [[\"the cup\", \"cup\", 1], [\"the kitchen table\", \"table\", 1]],"
                        + " \"s\": [[\"The cup was on the kitchen table at 2026-09-23T08:14:03Z.\","
                        + " \"the cup\", \"was on\", \"the kitchen table\", null, null,"
                        + " \"2026-09-23T08:14:03Z\", null]], \"n\": [], \"t\": []}",
                // 解释：模型照答——走的还是模型这条路，代码没代读
                "{ \"m\": [[0, \"point\", " + absolute + ", \"second\"]] }"));
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        try {
            extraction.extractDocument(doc, ExtractionJob.Proposer.none());
            resolveTime.resolveDocument(doc);
            // 一次抽取 + 一次解释：长得像日期的字照样问过模型；代码没有替它读
            assertEquals(2, model.requests().size(),
                    "the mention went to the model: source identity, not string shape");
            var row = jdbc.queryForMap(
                    "SELECT f.valid_from, f.valid_from_grade FROM facts f JOIN entities o"
                            + " ON o.id = f.object_id WHERE f.kb_id = ? AND f.layer = 'open'"
                            + " AND o.canonical_name = 'the kitchen table'",
                    kb);
            assertEquals(at(2026, 9, 23, 8, 14, 3),
                    row.get("valid_from") instanceof OffsetDateTime t ? t
                            : ((java.sql.Timestamp) row.get("valid_from")).toInstant()
                                    .atOffset(ZoneOffset.UTC));
            assertEquals("A", row.get("valid_from_grade"));
        } finally {
            model.stop();
            jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ?"
                    + " OR payload->>'kb_id' = ?", doc.toString(), kb.toString());
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /**
     * 配了模型也一样：代码读得动的提及不送模型；重跑一次 resolve_time 答案不变——
     * 代码读的没有模型可盖。
     */
    @Test
    void aCodeResolvedMentionIsNeverAskedAndSurvivesReruns() throws Exception {
        // 两个时间词：代码能读的时间戳是第 0 条 distinct；「上周」是第 1 条，只有它去问
        // 模型。模型把它锚在第 0 条上——代码读出的解释与模型给的解释在同一张锚点表里
        String lastWeek = "{ \"m\": [[1, \"point\", {\"kind\": \"anchored\","
                + " \"anchor\": {\"kind\": \"mention\", \"id\": 0},"
                + " \"offset\": {\"count\": 1, \"unit\": \"week\", \"direction\": \"before\"}},"
                + " \"week\"]] }";
        // 重跑还会再替「上周」问一次：给它同一个答案
        Rig rig = rig(payloadTwo("2026-09-23T08:14:03Z", "上周"),
                List.of(lastWeek, lastWeek));
        try {
            resolveTime.resolveDocument(rig.doc);
            // 抽取不问模型（推送路），这里只有一次解释调用：只问「上周」。
            // 提示词的「提及」一栏里没有那条时间戳——它会作为载荷正文出现在「上周」的
            // 语境里，那是文档自己的字；关键是它没有被当成一条提及去问
            List<JsonNode> requests = rig.model.requests();
            assertEquals(1, requests.size(), "only the interpretation call");
            String asked = requests.get(0).toString();
            assertTrue(asked.contains("上周"), "asked: " + asked);
            assertEquals(1, count(asked, "- id "), "asked: " + asked);

            List<List<Object>> first = factRows(rig.kb);
            assertEquals(rows(
                    // 「上周」锚在 9-23T08:14:03Z 上前一周：2026-09-16，日精度，B 级
                    new Object[]{"counter", day(2026, 9, 16), "day", day(2026, 9, 16), "day",
                            "B"},
                    new Object[]{"kitchen table", at(2026, 9, 23, 8, 14, 3), "second",
                            at(2026, 9, 23, 8, 14, 3), "second", "A"}),
                    first, "code-resolved, precision as written");
            // 重跑：同样的答案；脚本端点有第二份回复等着「上周」
            resolveTime.resolveDocument(rig.doc);
            List<JsonNode> again = rig.model.requests();
            assertEquals(2, again.size(), "the rerun asked the model for 上周 again");
            assertEquals(1, count(again.get(1).toString(), "- id "),
                    "the rerun also asked only about 上周");
            assertEquals(first, factRows(rig.kb), "a rerun gives the same answer");
        } finally {
            cleanup(rig);
        }
    }

    /** 期望行的组装帮手：每行包成 List（数组的 equals 是身份比较，不能直接比）。 */
    @SafeVarargs
    private static List<List<Object>> rows(Object[]... rowList) {
        return Arrays.stream(rowList)
                .<List<Object>>map(r -> (List<Object>) (List) Arrays.asList(r)).toList();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }
}
