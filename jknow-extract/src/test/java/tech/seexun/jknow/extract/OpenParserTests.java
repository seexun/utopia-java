package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.OpenParser.OpenEntity;
import tech.seexun.jknow.extract.OpenParser.OpenExtraction;
import tech.seexun.jknow.extract.OpenParser.OpenName;
import tech.seexun.jknow.extract.OpenParser.OpenStatement;
import tech.seexun.jknow.extract.OpenPrompts.KnownEntity;

/**
 * 移植自 crates/utopia-extract/src/open.rs 内联 mod tests（:638-1167，24 例）——解析容错族
 * 与提示词。断言逐条不改写；bad 条目「宁收勿弃」的生产兜底语义全在这里钉住。
 */
class OpenParserTests {

    private static final String AWARD =
            "The city council awarded the paving contract to Brightway Builders for $2 million "
                    + "on March 4, 2011.";
    private static final String LEASE =
            "Nebula (formerly Starlight Labs) leased the northern wing from Harbor Estates "
                    + "from 2010 until the end of 2019.";

    /** 一份完整的紧凑回复：本块列出的与已知清单里的名字、两种限定词、起止时间、别名。 */
    private static final String FULL = """
            {"e": [["city council", "council", 0], ["paving contract", "contract", 0],
                   ["Brightway Builders", "builders", 1], ["northern wing", "wing", 0]],
             "s": [["The city council awarded the paving contract to Brightway Builders for $2 million on March 4, 2011.",
                    "city council", "awarded", "paving contract", null, {"to": "Brightway Builders", "amount": "$2 million"}, "March 4, 2011", null],
                   ["Nebula (formerly Starlight Labs) leased the northern wing from Harbor Estates from 2010 until the end of 2019.",
                    "Nebula Technologies Inc.", "leased", "northern wing", null, {"from": "Harbor Estates", "term": "ten years"}, "from 2010", "until the end of 2019"],
                   ["The city council awarded the paving contract to Brightway Builders for $2 million on March 4, 2011.",
                    "paving contract", "worth", null, "$2 million", null, null, null]],
             "n": [["Nebula Technologies Inc.", "Starlight Labs", "Nebula (formerly Starlight Labs) leased the northern wing from Harbor Estates from 2010 until the end of 2019."]]}""";

    /**
     * 审查 I-1（open.rs:416-419）：只有 (key, ": [") 成立才换节。"t":"oops" 这类键后不是
     * 数组的，节照旧——切早了，紧随其后的八格陈述会按 t 节的格数去解（timeEntry 拒收），
     * 白丢一条陈述。
     */
    @Test
    void aSectionKeyNotFollowedByAnArrayDoesNotSwitchTheSection() {
        String raw = "{\"e\": [[\"Acme\", \"organization\", 1]],"
                + " \"t\": \"oops\","
                + " [\"Acme is based in London.\", \"Acme\", \"based in\", null, null, null, null, null]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated);
        assertEquals(0, x.skipped);
        assertEquals(1, x.entities.size());
        assertEquals("Acme", x.entities.get(0).name());
        // 陈述仍按 e 节的 statement_shaped 读进 s（前一节的身份还在管）
        assertEquals(1, x.statements.size());
        assertEquals("Acme is based in London.", x.statements.get(0).quote());
        assertEquals("based in", x.statements.get(0).phrase());
    }

    /**
     * 审查 I-2：serde_json::from_str 拒绝尾随 token——双 @} 结尾是「写坏了尾巴」，
     * 走 salvage 逐条读并标 truncated；Jackson 默认忽略尾随内容会当完整回复。
     */
    @Test
    void aTrailingTokenAfterTheObjectMarksTheReplyTruncated() {
        String raw = "{\"e\": [[\"Acme\", \"organization\", 1]], \"s\": [], \"n\": [],"
                + " \"t\": []}}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated, "double closing brace is not a whole reply");
        assertEquals(0, x.skipped);
        assertEquals(1, x.entities.size());
        assertEquals(0, x.statements.size());
    }

    /** serde_json 的对象按键排序——限定词的顺序不承载意义，比对前排序。 */
    private static List<Map.Entry<String, String>> byKey(
            List<Map.Entry<String, String>> qualifiers) {
        return qualifiers.stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey())).toList();
    }

    @Test
    void aFullReplyParsesIntoTheStructs() {
        OpenExtraction x = OpenParser.parseOpenResponse(FULL);
        assertFalse(x.truncated);
        assertEquals(0, x.skipped);

        assertEquals(4, x.entities.size());
        assertEquals("city council", x.entities.get(0).name());
        assertEquals("council", x.entities.get(0).kind());
        assertFalse(x.entities.get(0).named());
        assertEquals("Brightway Builders", x.entities.get(2).name());
        assertTrue(x.entities.get(2).named());

        assertEquals(3, x.statements.size());
        OpenStatement s = x.statements.get(0);
        assertEquals("city council", s.subject());
        assertEquals("awarded", s.phrase());
        assertEquals("paving contract", s.object());
        assertNull(s.value());
        assertEquals(List.of(Map.entry("amount", "$2 million"),
                Map.entry("to", "Brightway Builders")), byKey(s.qualifiers()));
        assertEquals("March 4, 2011", s.when());
        assertNull(s.ended());
        assertEquals(AWARD, s.quote());

        s = x.statements.get(1);
        assertEquals("Nebula Technologies Inc.", s.subject(), "已知清单里的名字照字面收");
        assertEquals("northern wing", s.object());
        assertEquals(List.of(Map.entry("from", "Harbor Estates"),
                Map.entry("term", "ten years")), byKey(s.qualifiers()));
        assertEquals("from 2010", s.when());
        assertEquals("until the end of 2019", s.ended());
        assertEquals(LEASE, s.quote());

        s = x.statements.get(2);
        assertNull(s.object());
        assertEquals("$2 million", s.value());
        assertTrue(s.qualifiers().isEmpty());
        assertNull(s.when());
        assertNull(s.ended());
        assertEquals(AWARD, s.quote());

        assertEquals(1, x.names.size());
        assertEquals("Nebula Technologies Inc.", x.names.get(0).entity());
        assertEquals("Starlight Labs", x.names.get(0).name());
        assertEquals(LEASE, x.names.get(0).quote());
    }

    /** 截在 n 数组中间：s 之前的全留下，truncated 标出来。 */
    @Test
    void aCutOffReplyKeepsWhatWasComplete() {
        String marker = "\"n\": [[\"Nebula Technologies Inc.\", \"Starl";
        int cut = FULL.indexOf(marker) + marker.length();
        OpenExtraction x = OpenParser.parseOpenResponse(FULL.substring(0, cut));
        assertTrue(x.truncated, "截断要标出来");
        assertEquals(4, x.entities.size());
        assertEquals(3, x.statements.size());
        assertEquals(AWARD, x.statements.get(2).quote());
        assertTrue(x.names.isEmpty());
        assertEquals(0, x.skipped);
    }

    /** 截在一条陈述的限定词之后：那半条配不平，不收成一条只有引文的陈述，也不算坏条目。 */
    @Test
    void aHalfStatementAtTheCutIsNotKept() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1]],\n"
                + "    \"s\": [[\"A is b.\", \"A\", \"is\", null, \"b\", null, null, null], "
                + "[\"A was c.\", \"A\", \"was\", null, \"c\", {\"at\": \"home\"}, \"in 20";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated);
        assertEquals(1, x.statements.size());
        assertEquals(0, x.skipped);
    }

    /** e 没关就写起了 s（回复正常结束，坏在三分之一处）：逐条读，一条不少。 */
    @Test
    void statementsWrittenAfterAnUnclosedListAreAllRead() {
        String raw = "```json\n"
                + "{\"e\": [[\"Harbor Bakery\", \"bakery\", 1], [\"Hillside School\", \"school\", 1], [\"bread\", \"bread\", 0],\n"
                + "[\"Harbor Bakery supplies Hillside School.\", \"Harbor Bakery\", \"supplies\", \"Hillside School\", null, null, null, null],\n"
                + "\"s\": [[\"Harbor Bakery bakes bread [daily].\", \"Harbor Bakery\", \"bakes\", \"bread\", null, {\"how often\": \"daily\"}, null, null],\n"
                + "[\"Harbor Bakery opened in 2005.\", \"Harbor Bakery\", \"opened\", null, \"opened\", null, \"2005\", null]],\n"
                + "\"n\": [[\"Harbor Bakery\", \"the Bakery\", \"The Bakery opened in 2005.\"]],\n"
                + "\"t\": []}\n"
                + "```";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated, "读是读出来了，回复不合法要标出来");
        assertEquals(3, x.entities.size());
        assertEquals(List.of("supplies", "bakes", "opened"),
                x.statements.stream().map(OpenStatement::phrase).toList(),
                "写在 e 里的那条八格条目是陈述");
        assertEquals("Harbor Bakery bakes bread [daily].", x.statements.get(1).quote());
        assertEquals(1, x.names.size());
        assertEquals(0, x.skipped);
    }

    /** s 的结尾少写一个 ]：陈述都在，从前丢的是它后面的 n 与 t——文档的日期没了。 */
    @Test
    void whatFollowsAMiswrittenBracketIsStillRead() {
        String raw = "{\"e\": [[\"码表\", \"产品\", 1]],\n"
                + "\"s\": [[\"码表平均售价为849元。\", \"码表\", \"平均售价\", null, \"849元\", null, \"2025年第四季度\", null]}\n"
                + ",\n\"n\": [],\n"
                + "\"t\": [[\"now\", \"提报日期\", \"2026年8月28日\", {\"y\": 2026, \"m\": 8, \"d\": 28}, null]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated);
        assertEquals(1, x.statements.size());
        assertEquals(1, x.time.size());
        assertEquals(0, x.skipped);
    }

    /** 一条写坏了（对象少了 }）只丢这一条，计数；前后的照收。 */
    @Test
    void oneMiswrittenItemCostsOnlyItself() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1]],\n"
                + "\"s\": [[\"A is b.\", \"A\", \"is\", null, \"b\", null, null, null],\n"
                + "[\"A was c.\", \"A\", \"was\", null, \"c\", {\"at\": \"home\"], \"in 2019\", null],\n"
                + "[\"A has d.\", \"A\", \"has\", null, \"d\", null, null, null]],\n"
                + "\"n\": [], \"t\": []}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated);
        assertEquals(List.of("is", "has"),
                x.statements.stream().map(OpenStatement::phrase).toList());
        assertEquals(1, x.skipped);
    }

    /** 一条少了结尾的 ]，把下一条套了进去：套着别的条目的不是一条，里面那条照收。 */
    @Test
    void anItemThatSwallowedTheNextOneGivesTheNextOneBack() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1]],\n"
                + "\"s\": [[\"A is b.\", \"A\", \"is\", null, \"b\", null, null, null,\n"
                + "[\"A has d.\", \"A\", \"has\", null, \"d\", null, null, null]],\n"
                + "\"n\": [], \"t\": []}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(List.of("has"),
                x.statements.stream().map(OpenStatement::phrase).toList());
        assertEquals(1, x.skipped);
    }

    /** 一格写错了类型（kind 写成数组、限定词写成一对对的数组）只丢那一格。 */
    @Test
    void aSlotOfTheWrongTypeCostsOnlyThatSlotWhenReadItemByItem() {
        String raw = "{\"e\": [[\"Acme\", [\"company\", \"org\"], 1], [\"Brightway\", \"builder\", 1],\n"
                + "\"s\": [[\"Lin joined Acme as CEO.\", \"Lin\", \"joined\", \"Acme\", null, [[\"role\", \"CEO\"]], null, null],\n"
                + "[\"Lin left Acme.\", \"Lin\", \"left\", \"Acme\", null, null, null, null]],\n"
                + "\"n\": [], \"t\": []}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated);
        assertEquals(List.of(Map.entry("Acme", ""), Map.entry("Brightway", "builder")),
                x.entities.stream().map(e -> Map.entry(e.name(), e.kind())).toList(),
                "no entity is read out of a slot");
        assertEquals(List.of("joined", "left"),
                x.statements.stream().map(OpenStatement::phrase).toList());
        assertTrue(x.statements.get(0).qualifiers().isEmpty());
        assertEquals(0, x.skipped);
    }

    /** 同一批条目，一份整体合法，一份只少了关 e 的 ]：两份读出来的一样。 */
    @Test
    void aReplyReadItemByItemReadsWhatTheWholeReplyReads() {
        String e = "[\"Acme\", [\"company\", \"org\"], 1], [\"Lin\", \"person\", [1]], [\"Harbor\", \"place\", 0]";
        String s = "[\"Lin joined Acme.\", \"Lin\", \"joined\", [\"Acme\"], null, [[\"role\", \"CEO\"]], null, null],\n"
                + "[\"Acme sells tools.\", \"Acme\", \"sells\", null, [\"tools\", \"parts\"], null, [\"2020\"], null],\n"
                + "[\"Harbor is old.\", \"Harbor\", \"is\", null, \"old\", null, null]";
        String n = "[\"Acme\", \"Acme Corp\", [\"Acme Corp is based in Harbor.\"]]";
        String whole = "{\"e\": [" + e + "], \"s\": [" + s + "], \"n\": [" + n + "], \"t\": []}";
        String broken = "{\"e\": [" + e + ", \"s\": [" + s + "], \"n\": [" + n + "], \"t\": []}";
        OpenExtraction w = OpenParser.parseOpenResponse(whole);
        OpenExtraction b = OpenParser.parseOpenResponse(broken);
        assertFalse(w.truncated);
        assertTrue(b.truncated);
        assertEquals(w.entities.toString(), b.entities.toString());
        assertEquals(w.statements.toString(), b.statements.toString());
        assertEquals(w.names.toString(), b.names.toString());
        assertEquals(w.skipped, b.skipped);
        assertEquals(3, w.entities.size());
        assertEquals(2, w.statements.size());
        assertEquals(1, w.names.size());
        assertEquals(1, w.skipped, "the seven-slot statement, in both");
    }

    /** 限定词全是 null 的回复里一个 } 都没有：只认 } 的修补会把整块作废。 */
    @Test
    void aCutOffReplyWithoutAnyClosingBraceIsStillRepaired() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1]],\n"
                + "    \"s\": [[\"A is b.\", \"A\", \"is\", null, \"b\", null, null, null], "
                + "[\"A was c.\", \"A\", \"was\", null, \"c\", nu";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertTrue(x.truncated);
        assertEquals(1, x.entities.size());
        assertEquals(1, x.statements.size());
        assertEquals("b", x.statements.get(0).value());
        assertEquals(0, x.skipped);
    }

    /** 连一个完整条目都没有时仍然报失败——容错不是把空结果说成成功。 */
    @Test
    void aReplyWithNothingCompleteStillFails() {
        assertThrows(Exception.class,
                () -> OpenParser.parseOpenResponse("{\"e\": [[\"a\", \"b\""));
    }

    /** 七格的陈述是坏条目：跳过并计数，八格的照收。 */
    @Test
    void aStatementWithSevenSlotsIsSkippedAndCounted() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1]],\n"
                + "    \"s\": [[\"A is b.\", \"A\", \"is\", null, \"b\", null, null],\n"
                + "          [\"A is b.\", \"A\", \"is\", null, \"b\", null, null, null]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(1, x.statements.size());
        assertEquals("A is b.", x.statements.get(0).quote());
        assertEquals(1, x.skipped);
        assertFalse(x.truncated);
    }

    /** 字面值写成 JSON 数字也照它的写法收成文本。 */
    @Test
    void aNumberInTheValueSlotBecomesText() {
        String raw = "{\"e\": [[\"a\", \"k\", 1]],\n"
                + "    \"s\": [[\"q\", \"a\", \"cut water use\", null, 12, null, null, null],\n"
                + "          [\"q\", \"a\", \"ratio\", null, 1.5, null, null, null]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals("12", x.statements.get(0).value());
        assertEquals("1.5", x.statements.get(1).value());
        assertEquals(0, x.skipped);
    }

    /** e 里重名（去首尾空白、内部空白折一、不分大小写）留第一个，其余计入 skipped。 */
    @Test
    void aDuplicateEntityNameKeepsTheFirstAndIsCounted() {
        String raw = "{\"e\": [[\"Harbor Bridge\", \"bridge\", 1], [\" harbor   bridge \", \"span\", 0],\n"
                + "    [\"HARBOR BRIDGE\", \"bridge\", 1], [\"Harbor Bridge Authority\", \"agency\", 1]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(2, x.entities.size());
        assertEquals("Harbor Bridge", x.entities.get(0).name());
        assertEquals("bridge", x.entities.get(0).kind());
        assertTrue(x.entities.get(0).named());
        assertEquals("Harbor Bridge Authority", x.entities.get(1).name());
        assertEquals(2, x.skipped);
    }

    /** 坏条目逐个计数：没名字的实体、不是数组的实体、没主语的陈述、空短语的陈述、
     少一格的陈述、不是数组的陈述、没别名的 n、实体格放了对象的 n、实体名为空的 n。 */
    @Test
    void malformedItemsAreCountedNotFatal() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1], [\"\"], [5, \"x\"], \"not an array\", []],\n"
                + "    \"s\": [[\"A is b.\", \"A\", \"is\", null, \"b\", null, null, null],\n"
                + "          [\"A is b.\", null, \"is\", null, \"b\", null, null, null],\n"
                + "          [\"A is b.\", \"A\", \"  \", null, \"b\", null, null, null],\n"
                + "          [\"A is b.\", \"A\", \"is\", null, \"b\", null, null],\n"
                + "          {\"subject\": \"A\"}],\n"
                + "    \"n\": [[\"A\", \"Ay\", \"A is b.\"], [\"A\"], [{}, \"Ay\", \"q\"], [\"\", \"Ay\", \"q\"]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(1, x.entities.size());
        assertEquals(1, x.statements.size());
        assertEquals(1, x.names.size());
        assertEquals(4 + 4 + 3, x.skipped);
        assertFalse(x.truncated);
    }

    /** 可选格放错了类型只是那一格为 null，整条照收。 */
    @Test
    void aWrongTypeInAnOptionalSlotIsNullNotMalformed() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1]],\n"
                + "    \"s\": [[7, \"A\", \"is\", 3, true, \"not an object\", [2019], {\"y\": 2020}]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(1, x.statements.size());
        OpenStatement s = x.statements.get(0);
        assertNull(s.object());
        assertNull(s.value());
        assertTrue(s.qualifiers().isEmpty());
        assertNull(s.when());
        assertNull(s.ended());
        assertNull(s.quote());
        assertEquals(0, x.skipped);
    }

    /** 限定词：字符串是名字或原文，数字照写法转文本，其余类型与空键悄悄丢。 */
    @Test
    void qualifiersTakeStringsAndNumbersAndDropTheRest() {
        String raw = "{\"e\": [[\"a\", \"k\", 1]],\n"
                + "    \"s\": [[\"q\", \"a\", \"cut water use\", null, \"12%\", "
                + "{\"compared to\": \"2019\", \"at\": \"Harbor Estates\", \"by\": 3, \"ratio\": 1.5, "
                + "\"flag\": true, \"who\": null, \"list\": [\"x\"], \"\": \"x\", \"blank\": \"  \"}, null, null]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(List.of(Map.entry("at", "Harbor Estates"),
                Map.entry("by", "3"), Map.entry("compared to", "2019"),
                Map.entry("ratio", "1.5")), byKey(x.statements.get(0).qualifiers()));
        assertEquals(0, x.skipped);
    }

    /** 每个字符串都去首尾空白：名字、种类、主语、短语、宾语、时间、引文、限定词的键与值。 */
    @Test
    void everyStringIsTrimmed() {
        String raw = "{\"e\": [[\"  A  \", \" thing \", 1]],\n"
                + "    \"s\": [[\" A is B. \", \" A \", \" is \", \" B \", null, {\" at \": \" home \"}, "
                + "\" in 2019 \", \" until 2020 \"]],\n"
                + "    \"n\": [[\" A \", \" Ay \", \" A is B. \"]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals("A", x.entities.get(0).name());
        assertEquals("thing", x.entities.get(0).kind());
        OpenStatement s = x.statements.get(0);
        assertEquals("A", s.subject());
        assertEquals("is", s.phrase());
        assertEquals("B", s.object());
        assertEquals(List.of(Map.entry("at", "home")), s.qualifiers());
        assertEquals("in 2019", s.when());
        assertEquals("until 2020", s.ended());
        assertEquals("A is B.", s.quote());
        assertEquals("A", x.names.get(0).entity());
        assertEquals("Ay", x.names.get(0).name());
        assertEquals("A is B.", x.names.get(0).quote());
    }

    /** 宾语与字面值同时有：照收，两个都给调用方看，这里不裁。 */
    @Test
    void aStatementWithBothObjectAndValueIsPassedThrough() {
        String raw = "{\"e\": [[\"A\", \"thing\", 1], [\"B\", \"thing\", 1]],\n"
                + "    \"s\": [[\"A is b.\", \"A\", \"is\", \"B\", \"b\", null, null, null]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(1, x.statements.size());
        assertEquals("B", x.statements.get(0).object());
        assertEquals("b", x.statements.get(0).value());
        assertEquals(0, x.skipped);
    }

    /** named 认 1/0 与 true/false，缺了当点了名；kind 缺了是空串。 */
    @Test
    void namedTakesNumbersAndBooleansAndDefaultsToTrue() {
        String raw = "{\"e\": [[\"a\", \"k\", 1], [\"b\", \"k\", 0], [\"c\", \"k\", true],\n"
                + "    [\"d\", \"k\", false], [\"e\"]]}";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(List.of(true, false, true, false, true),
                x.entities.stream().map(OpenEntity::named).toList());
        assertEquals("", x.entities.get(4).kind());
        assertEquals(0, x.skipped);
    }

    /** 围栏与前后废话照旧容忍（走的是同一个 json_block）。 */
    @Test
    void aFencedReplyParses() {
        String raw = "Here you go:\n```json\n{\"e\": [[\"a\", \"k\", 1]], \"s\": [], \"n\": []}\n```";
        OpenExtraction x = OpenParser.parseOpenResponse(raw);
        assertEquals(1, x.entities.size());
        assertFalse(x.truncated);
    }

    private static KnownEntity known(String handle, String name, String typeKey) {
        return new KnownEntity(handle, typeKey, name);
    }

    /** 提示词里没有文档日期、没有类型与关系清单、没有编号与句柄；有文件名、开头、
     已知名字与正文，并且按这个顺序。 */
    @Test
    void theTableRulesRideOnlyWithAPassageThatHasATable() {
        String plain = "The council met on March 4, 2011. It awarded the paving contract.";
        String system = OpenPrompts.buildOpenMessages("a.md", List.of(), null, plain)
                .get(0).content();
        assertFalse(system.contains("In a table"));
        assertFalse(system.contains("{TABLE}"));
        assertTrue(system.contains("Write every figure the passage states.\n"));
        for (String table : new String[]{
                "| Item | Q3 2025 |\n|---|---|\n| Revenue | 12 |",
                "<table><tr><td>Revenue</td><td>12</td></tr></table>",
                "Revenue\t12\t14",
                "Revenue      12      14\nCosts        7       8"}) {
            String s = OpenPrompts.buildOpenMessages("a.md", List.of(), null, table)
                    .get(0).content();
            assertTrue(s.contains("Write every figure the passage states. In a table, a cell is"),
                    table);
            assertTrue(s.contains("column heading is not in the passage.\n"), table);
        }
    }

    @Test
    void thePromptCarriesNoOntologyAndNoDocumentDate() {
        List<tech.seexun.jknow.llm.ChatClient.ChatMessage> msgs = OpenPrompts.buildOpenMessages(
                "annual-report.txt",
                List.of(known("k1", "Nebula Technologies Inc.", "organization"),
                        known("k2", "Harbor Estates", "")),
                "Nebula Technologies Inc. Annual Report 2019",
                "Nebula leased the northern wing.");
        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).role());
        assertEquals("user", msgs.get(1).role());
        String system = msgs.get(0).content();
        String user = msgs.get(1).content();
        for (String s : new String[]{system, user}) {
            assertFalse(s.contains("Document date"), s);
            assertFalse(s.contains("Resolve relative time"), s);
            assertFalse(s.contains("Entity types"), s);
            assertFalse(s.contains("Relation types"), s);
            assertFalse(s.contains("Attributes ("), s);
            assertFalse(s.contains("\"q\""), "没有引文表: " + s);
            assertFalse(s.contains("k1"), "句柄不给模型看: " + s);
        }
        assertTrue(system.contains("Never compute, convert or normalise a date"));
        // 0064：文档自己说到的日期由这一块报上来（t），时间词只进 when / ended
        assertTrue(system.contains("\"t\" lists the dates the passage states about the document itself"));
        assertTrue(system.contains("go in when or ended and nowhere else"));
        assertTrue(system.contains("transcribe the numbers"));
        assertTrue(system.contains("do not translate it into any vocabulary of your own"));
        assertTrue(system.contains("written exactly as listed in \"e\""));
        assertTrue(system.contains("copied verbatim"));
        assertTrue(system.contains("write nothing about them"));
        assertTrue(system.contains(
                "\"s\": [[\"The city council awarded the paving contract to Brightway Builders "
                        + "for $2 million on March 4, 2011.\", \"city council\", \"awarded\", "
                        + "\"paving contract\", null, {\"to\": \"Brightway Builders\""));

        int doc = user.indexOf("Document: annual-report.txt");
        int opening = user.indexOf("Opening of this document");
        assertTrue(user.contains("Annual Report 2019"));
        int recorded = user.indexOf("Already recorded from earlier parts");
        assertTrue(user.contains("\n- Nebula Technologies Inc. (organization)\n"));
        assertTrue(user.contains("\n- Harbor Estates\n"), "空类型不留括号");
        assertTrue(user.contains("write its name exactly as it is listed here"));
        int passage = user.indexOf("Passage:\nNebula leased the northern wing.");
        assertTrue(doc < opening && opening < recorded && recorded < passage);
        assertTrue(user.endsWith("Nebula leased the northern wing."));
    }

    /** 第一块：没有开头也没有已知实体，两段都不出现。 */
    @Test
    void theFirstChunkCarriesNeitherOpeningNorKnownBlock() {
        List<tech.seexun.jknow.llm.ChatClient.ChatMessage> msgs =
                OpenPrompts.buildOpenMessages("a.txt", List.of(), null, "text");
        String user = msgs.get(1).content();
        assertEquals("Document: a.txt\n\nPassage:\ntext", user);
        assertFalse(user.contains("Opening of this document"));
        assertFalse(user.contains("Already recorded"));
    }
}
