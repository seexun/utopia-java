package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.PhraseAlign.Direction;
import tech.seexun.jknow.extract.PhraseAlign.Marks;
import tech.seexun.jknow.extract.PhraseAlign.PhraseChoice;
import tech.seexun.jknow.extract.PhraseAlign.PhraseItem;
import tech.seexun.jknow.extract.PhraseAlign.PhraseReply;
import tech.seexun.jknow.extract.PhraseAlign.PropertyCandidate;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 移植自 crates/utopia-extract/src/phrase_align.rs 内联 mod tests（14 例）——短语绑定到
 * 属性的三元组（键/方向/#966 的第四格）宽容解析与提示词。断言逐条不改写。
 */
class PhraseAlignTests {

    private static List<String> strings(String... values) {
        return List.of(values);
    }

    private static List<PropertyCandidate> candidates() {
        return List.of(
                new PropertyCandidate("headquartered_in", "headquartered in",
                        "The organization's principal office is at the place.", "relation",
                        "state", List.of("organization"), List.of("place"), List.of()),
                new PropertyCandidate("subsidiary_of", "subsidiary of",
                        "The organization is owned or controlled by the other organization.",
                        "relation", "state", List.of("organization"), List.of("organization"),
                        List.of()),
                new PropertyCandidate("revenue", "revenue",
                        "Total income from sales for a period, as an amount of money.",
                        "attribute", "state", List.of("organization"), List.of(), List.of()));
    }

    @Test
    void thePromptListsTheSignatureItsSentencesAndTheCandidates() {
        List<PhraseItem> items = List.of(new PhraseItem(3, "is based in", "organization",
                null, false, 4,
                strings("Harbor Bakery —is based in→ Port Ellen"),
                strings("Harbor Bakery is based in Port Ellen."), candidates(), List.of()));
        List<ChatMessage> msgs = PhraseAlign.buildPhraseMessages(items);
        String user = msgs.get(1).content();
        assertTrue(user.contains(
                "Item 3: phrase \"is based in\" · subject class: organization · object: ? · 4 statements"),
                user);
        assertTrue(user.contains(
                "· Harbor Bakery —is based in→ Port Ellen\n    \"Harbor Bakery is based in Port Ellen.\""),
                user);
        assertTrue(user.contains(
                "- headquartered_in · headquartered in · relation · state · domain: organization"
                        + " · range: place · The organization's"),
                user);
        assertTrue(user.startsWith("Properties:\n- "), "the glossary comes first, once: " + user);
        assertTrue(user.contains("Candidates: headquartered_in"), "items list keys only: " + user);
        assertEquals(1, count(user, "- headquartered_in ·"), "each property is described once: "
                + user);
        assertTrue(user.contains(
                "- revenue · revenue · attribute · state · domain: organization · Total income"),
                user);
        assertTrue(msgs.get(0).content().contains("every statement of this signature"));
    }

    @Test
    void aKeyTheShortlistHidStillCountsWhenItFitsStructurally() {
        List<PhraseItem> items = List.of(new PhraseItem(0, "is based in", "organization",
                null, false, 1, List.of(), List.of(), candidates(), List.of("located_in")));
        PhraseReply r = PhraseAlign.parsePhraseResponse("{\"b\":[[0,\"located_in\",\"forward\"]]}",
                items);
        assertEquals(0, r.malformed());
        assertEquals("located_in", r.choices().get(0).property().key());
        r = PhraseAlign.parsePhraseResponse("{\"b\":[[0,\"made_up\",\"forward\"]]}", items);
        assertEquals(1, r.malformed(), "a key that fits nowhere is still malformed");
    }

    @Test
    void aLabelAnswerMapsToItsKeyWhenUnique() {
        List<PhraseItem> items = List.of(new PhraseItem(0, "is based in", "organization",
                null, false, 1, List.of(), List.of(), candidates(), List.of()));
        PhraseReply r = PhraseAlign.parsePhraseResponse(
                "{\"b\":[[0,\"Headquartered In\",\"forward\"]]}", items);
        assertEquals(0, r.malformed(), "a label, folded, names the key");
        assertEquals("headquartered_in", r.choices().get(0).property().key());
        assertEquals("dateofbirth", PhraseAlign.fold("Date of Birth"));
    }

    @Test
    void aValueSignatureSaysValueForItsObject() {
        List<PhraseItem> items = List.of(new PhraseItem(0, "revenue", "organization", null,
                true, 1, strings("Harbor Bakery —revenue→ $2 million"),
                strings("Harbor Bakery's revenue was $2 million."), candidates(), List.of()));
        String user = PhraseAlign.buildPhraseMessages(items).get(1).content();
        assertTrue(user.contains("· object: value ·"), user);
    }

    /** 绑上带方向；null 不绑；键照候选抄回；绑了没方向、键不在候选里、id 不在批里都算坏。 */
    @Test
    void triplesParseAndBadOnesAreCounted() {
        List<PhraseItem> items = new ArrayList<>();
        for (Object[] row : new Object[][] {
                {0L, "owns"}, {1L, "said"}, {2L, "acquired"}, {3L, "employs"}, {4L, "x"}}) {
            items.add(new PhraseItem((Long) row[0], (String) row[1], "organization",
                    "organization", false, 1, List.of(), List.of(), candidates(), List.of()));
        }
        String raw = "{\"b\": [[0, \"Subsidiary_Of\", \"reverse\"], [1, null, null],"
                + " [2, \"acquired\", \"forward\"], [3, \"subsidiary_of\"],"
                + " [\"4\", \"revenue\", \"forward\"], [9, null, null], [0, null, null]]}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(List.of(
                bound(0, "subsidiary_of", Direction.REVERSE),
                unbound(1),
                bound(4, "revenue", Direction.FORWARD)), r.choices());
        // 2：键不在候选里；3：绑了没方向；9：id 不在批里；0 的第二次
        assertEquals(4, r.malformed());
    }

    @Test
    void aTruncatedReplyKeepsTheCompleteTriples() {
        List<PhraseItem> items = List.of(
                new PhraseItem(0, "a", null, null, false, 1, List.of(), List.of(),
                        candidates(), List.of()),
                new PhraseItem(1, "b", null, null, false, 1, List.of(), List.of(),
                        candidates(), List.of()));
        String raw = "{\"b\": [[0, \"revenue\", \"forward\"], [1, \"subsid";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(1, r.choices().size());
        assertEquals(0, r.choices().get(0).id());
    }

    /** 三条签名：owns（应反向绑 subsidiary_of）、said（应 null）、revenue（值）。 */
    private static List<PhraseItem> threeItems() {
        List<PhraseItem> out = new ArrayList<>();
        long[] ids = {0, 1, 2};
        String[] phrases = {"owns", "said", "revenue"};
        boolean[] value = {false, false, true};
        for (int i = 0; i < 3; i++) {
            out.add(new PhraseItem(ids[i], phrases[i], "organization",
                    value[i] ? null : "organization", value[i], 1, List.of(), List.of(),
                    candidates(), List.of()));
        }
        return out;
    }

    private static PhraseChoice bound(long id, String key, Direction direction) {
        return new PhraseChoice(id, new PhraseChoice.Property(key, direction), null);
    }

    private static PhraseChoice unbound(long id) {
        return new PhraseChoice(id, null, null);
    }

    private static PhraseChoice marked(long id, String key, Direction direction, Marks marks) {
        return new PhraseChoice(id, new PhraseChoice.Property(key, direction), marks);
    }

    /**
     * 第四格说一刻标状态的哪一端（#966）：四格数组、对象的 marks 字段、按 id 作键的写法
     * 都读；缺席或 null 是没说；不绑的不带；写了却认不出的算坏项。
     */
    @Test
    void theFourthValueSaysWhichEndAMomentMarks() {
        List<PhraseItem> items = threeItems();
        String raw = "{\"b\": [[0, \"subsidiary_of\", \"reverse\", \"start\"],"
                + " [1, null, null, \"end\"], [2, \"revenue\", \"forward\", null]]}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(
                marked(0, "subsidiary_of", Direction.REVERSE, Marks.START),
                unbound(1),
                bound(2, "revenue", Direction.FORWARD)), r.choices());
        raw = "{\"0\": [\"subsidiary_of\", \"reverse\", \"END\"],"
                + " \"2\": {\"key\": \"revenue\", \"direction\": \"forward\", \"marks\": \"none\"}}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(
                marked(0, "subsidiary_of", Direction.REVERSE, Marks.END),
                marked(2, "revenue", Direction.FORWARD, Marks.NEITHER)), r.choices());
        raw = "{\"b\": [{\"id\": 0, \"p\": \"subsidiary_of\", \"d\": \"reverse\", \"m\": \"start\"},"
                + " [2, \"revenue\", \"forward\", \"later\"]]}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(List.of(marked(0, "subsidiary_of", Direction.REVERSE, Marks.START)),
                r.choices());
        assertEquals(1, r.malformed(), "an unreadable marks is a bad answer");
    }

    /**
     * 事件与恒常不读第四格：写了认不出的值不算坏项，写了认得出的也不带。状态属性下
     * 认不出照样算坏。
     */
    @Test
    void anEventPropertyDoesNotReadTheFourthValue() {
        List<PhraseItem> items = threeItems();
        List<PropertyCandidate> withEvent = new ArrayList<>(items.get(0).candidates);
        withEvent.add(new PropertyCandidate("acquired", "acquired",
                "The organization bought the other organization.", "relation", "event",
                List.of("organization"), List.of("organization"), List.of()));
        items.set(0, new PhraseItem(0, "owns", "organization", "organization", false, 1,
                List.of(), List.of(), withEvent, List.of()));
        String raw = "{\"b\": [[0, \"acquired\", \"forward\", \"later\"],"
                + " [2, \"revenue\", \"forward\", \"later\"]]}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(List.of(bound(0, "acquired", Direction.FORWARD)), r.choices());
        assertEquals(1, r.malformed(), "revenue is a state: its fourth value is read");
        raw = "{\"b\": [[0, \"acquired\", \"forward\", \"start\"]]}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(List.of(bound(0, "acquired", Direction.FORWARD)), r.choices());
        assertEquals(0, r.malformed());
    }

    /** 提示词说出每条属性是不是状态，以及第四格怎么答。 */
    @Test
    void thePromptSaysWhichPropertiesAreStatesAndAsksForTheFourthValue() {
        List<ChatMessage> msgs = PhraseAlign.buildPhraseMessages(threeItems());
        assertTrue(msgs.get(1).content().contains(
                "- subsidiary_of · subsidiary of · relation · state ·"));
        String system = msgs.get(0).content();
        assertTrue(system.contains("\"start\" when the phrase says the state began then"),
                system);
        assertTrue(system.contains("\"end\" when it says the state ended then"), system);
        assertTrue(system.contains("For an event or eternal property"), system);
    }

    /**
     * 类别词那边实测的写法搬到短语上：整段是按 id 作键的对象，没有 b，值是 [key, dir]。
     * 解出来必须和样例形状一模一样。
     */
    @Test
    void anIdKeyedObjectWithPairsIsReadAsTheTripleList() {
        List<PhraseItem> items = threeItems();
        String raw = "{ \"0\": [\"subsidiary_of\", \"reverse\"], \"1\": [null, null],"
                + " \"2\": [\"revenue\", \"forward\"] }";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(
                bound(0, "subsidiary_of", Direction.REVERSE),
                unbound(1),
                bound(2, "revenue", Direction.FORWARD)), r.choices());
        // 不绑的写成裸 null 或只有一格；绑了却只有一格是没方向，算坏
        raw = "{\"0\": [\"subsidiary_of\"], \"1\": null, \"2\": [null]}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(1, r.malformed(), "bound without a direction");
        assertEquals(List.of(unbound(1), unbound(2)), r.choices());
        // 值里先抄一遍 id 再给键与方向
        raw = "{\"0\": [0, \"subsidiary_of\", \"reverse\"], \"1\": [1, null, null]}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(bound(0, "subsidiary_of", Direction.REVERSE), unbound(1)),
                r.choices());
        // 空数组不是一个答案
        raw = "{\"0\": [], \"1\": [null]}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(1, r.malformed());
        assertEquals(List.of(unbound(1)), r.choices());
    }

    /** 按 id 作键、值是对象：{"key": .., "direction": ..}，字段名的几种别名都认。 */
    @Test
    void anIdKeyedObjectWithObjectValuesParses() {
        List<PhraseItem> items = threeItems();
        String raw = "{\"0\": {\"key\": \"subsidiary_of\", \"direction\": \"Reverse\"},"
                + " \"1\": {\"key\": null, \"direction\": null},"
                + " \"2\": {\"property\": \"revenue\", \"dir\": \"FORWARD\"}}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(
                bound(0, "subsidiary_of", Direction.REVERSE),
                unbound(1),
                bound(2, "revenue", Direction.FORWARD)), r.choices());
    }

    /** b 下面是对象而不是数组：一样读；b 在就只看 b，顶层别的键是旁白。 */
    @Test
    void bAsAnObjectParsesAndOtherTopLevelKeysAreIgnored() {
        List<PhraseItem> items = threeItems();
        String raw = "{\"note\": \"done\", \"b\": {\"0\": [\"subsidiary_of\", \"reverse\"],"
                + " \"1\": null}}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(bound(0, "subsidiary_of", Direction.REVERSE), unbound(1)),
                r.choices());
        raw = "{\"b\": {\"0\": {\"key\": \"subsidiary_of\", \"direction\": \"reverse\"}}}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(bound(0, "subsidiary_of", Direction.REVERSE)), r.choices());
    }

    /** 数组里的一条写成对象、或整段不要外层对象只给数组：照读。 */
    @Test
    void objectTriplesAndABareArrayParse() {
        List<PhraseItem> items = threeItems();
        String raw = "{\"b\": [{\"id\": 0, \"key\": \"subsidiary_of\", \"direction\": \"reverse\"},"
                + " {\"id\": \"1\", \"key\": null, \"direction\": null}]}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(bound(0, "subsidiary_of", Direction.REVERSE), unbound(1)),
                r.choices());
        raw = "```json\n[[0, \"subsidiary_of\", \"reverse\"], [2, \"revenue\", \"forward\"]]\n```";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(
                bound(0, "subsidiary_of", Direction.REVERSE),
                bound(2, "revenue", Direction.FORWARD)), r.choices());
        // 样例形状里第二格又包了一层：[id, [key, dir]]
        raw = "{\"b\": [[0, [\"subsidiary_of\", \"reverse\"]], [1, [null]]]}";
        r = PhraseAlign.parsePhraseResponse(raw, items);
        assertEquals(0, r.malformed());
        assertEquals(List.of(bound(0, "subsidiary_of", Direction.REVERSE), unbound(1)),
                r.choices());
    }

    /**
     * b 是空的：一项都没答，不是坏项。没有 b、键又不是 id 的：那是读不出的答案，算坏项
     * ——从前这种回复算「一项都没答」，调用方静静跳过，什么痕迹都不留。
     */
    @Test
    void anEmptyBAnswersNothingButAnUnreadableReplyIsMalformed() {
        List<PhraseItem> items = threeItems();
        for (String raw : new String[]{"{\"b\": []}", "{\"b\": {}}"}) {
            PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
            assertTrue(r.choices().isEmpty(), raw);
            assertEquals(0, r.malformed(), raw);
        }
        String raw = "{\"answer\": \"subsidiary_of\", \"direction\": \"reverse\"}";
        PhraseReply r = PhraseAlign.parsePhraseResponse(raw, items);
        assertTrue(r.choices().isEmpty());
        assertEquals(2, r.malformed());
        // b 是标量：一条都没有
        r = PhraseAlign.parsePhraseResponse("{\"b\": 1}", items);
        assertTrue(r.choices().isEmpty());
        assertEquals(0, r.malformed());
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
