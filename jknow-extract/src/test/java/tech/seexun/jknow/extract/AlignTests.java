package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.Align.ClassCandidate;
import tech.seexun.jknow.extract.Align.KindWordChoice;
import tech.seexun.jknow.extract.Align.KindWordItem;
import tech.seexun.jknow.extract.Align.KindWordReply;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 移植自 crates/utopia-extract/src/align.rs 内联 mod tests（17 例）——种类词绑定到类的
 * 回复解析（四种形状宽容读）与提示词。断言逐条不改写。
 */
class AlignTests {

    private static List<String> strings(String... values) {
        return List.of(values);
    }

    /** 三项：公司（应绑组织）、参与者（角色词，应 null）、桥（候选里有结构）。 */
    private static final ClassCandidate ORGANIZATION = new ClassCandidate("organization",
            "Organization",
            "An organized group of people with a shared purpose, such as a company, a council "
                    + "or a club.");
    private static final ClassCandidate PERSON = new ClassCandidate("person", "Person",
            "A human being.");
    private static final ClassCandidate STRUCTURE = new ClassCandidate("structure", "建筑物",
            "人建造的固定物：桥、楼、水坝。");

    private List<KindWordItem> items() {
        return List.of(
                new KindWordItem(12, "company", strings("Company", "company", "公司"),
                        strings("Brightway Builders", "Harbor Estates"),
                        strings("is based in", "was founded by"),
                        List.of(ORGANIZATION, PERSON)),
                new KindWordItem(13, "participant", strings("participants"), List.of(),
                        List.of(), List.of(PERSON, ORGANIZATION)),
                new KindWordItem(14, "bridge", strings("bridge", "Bridge"),
                        strings("Harbor Bridge"), strings("spans", "was completed"),
                        List.of(STRUCTURE)));
    }

    private static final String FULL =
            "{\"b\": [[12, \"organization\"], [13, null], [14, \"structure\"]]}";

    private static KindWordChoice choice(long id, String key) {
        return new KindWordChoice(id, key);
    }

    @Test
    void aFullReplyParsesWithKeysAndNulls() {
        KindWordReply r = Align.parseKindWordResponse(FULL, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(13, null),
                choice(14, "structure")), r.choices());
    }

    /** 围栏与前后废话照旧容忍（走的是同一个 json_block）。 */
    @Test
    void aFencedReplyParses() {
        String raw = "Here you go:\n```json\n" + FULL + "\n```\nDone.";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(3, r.choices().size());
    }

    /** 键不在那一项的候选里就是坏项：别的项有这个候选也不算。 */
    @Test
    void aKeyNotAmongTheItemsCandidatesIsMalformed() {
        String raw = "{\"b\": [[12, \"place\"], [14, \"organization\"], [13, \"person\"]]}";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(2, r.malformed());
        assertEquals(List.of(choice(13, "person")), r.choices());
    }

    @Test
    void anUnknownIdIsMalformed() {
        String raw = "{\"b\": [[99, \"organization\"], [12, \"organization\"]]}";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(1, r.malformed());
        assertEquals(List.of(choice(12, "organization")), r.choices());
    }

    /** 截在第三对的键中间：前两对留下，第三项缺席（不是 null，也不算坏）。 */
    @Test
    void aTruncatedReplyIsRepairedToTheLastCompletePair() {
        int cut = FULL.indexOf("\"struc");
        KindWordReply r = Align.parseKindWordResponse(FULL.substring(0, cut), items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(13, null)), r.choices());
    }

    /** 截在一对的 id 之后（[14）：退到上一个完整的 ]。 */
    @Test
    void aReplyCutInsideAPairKeepsThePairsBeforeIt() {
        String raw = "{\"b\": [[12, \"organization\"], [13, null], [14";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(2, r.choices().size());
    }

    /** 连一对都没有时仍然报失败——容错不是把空结果说成成功。 */
    @Test
    void aReplyWithNothingCompleteStillFails() {
        assertThrows(Exception.class,
                () -> Align.parseKindWordResponse("{\"b\": [[12, \"org", items()));
        assertThrows(Exception.class, () -> Align.parseKindWordResponse("no json here", items()));
    }

    /**
     * b 是空的：一项都没答，不是坏项。没有 b、键又不是 id 的：那是读不出的答案，算坏项
     * ——从前这种回复算「一项都没答」，一个库十几轮下来什么痕迹都不留。
     */
    @Test
    void aReplyWithoutPairsGivesNoChoices() {
        KindWordReply r = Align.parseKindWordResponse("{\"b\": {}}", items());
        assertTrue(r.choices().isEmpty());
        assertEquals(0, r.malformed());
        r = Align.parseKindWordResponse("{\"b\": []}", items());
        assertTrue(r.choices().isEmpty());
        assertEquals(0, r.malformed());
        r = Align.parseKindWordResponse("{\"x\": 1}", items());
        assertTrue(r.choices().isEmpty());
        assertEquals(1, r.malformed());
    }

    /**
     * DeepSeek-V3.2 实测的写法：整段是按 id 作键的对象，没有 b。这就是 bench 里
     * 「12 篇文档 type_id 全空」的回复——它解出来必须和样例形状一模一样。
     */
    @Test
    void anIdKeyedObjectIsReadAsThePairList() {
        String raw = "{ \"12\": \"organization\", \"13\": null, \"14\": \"structure\" }";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(13, null),
                choice(14, "structure")), r.choices());
        // b 下面是对象而不是数组：一样读
        raw = "{\"b\": {\"12\": \"organization\", \"13\": null}}";
        r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(2, r.choices().size());
    }

    /** 同一个模型的另一种写法：键包在单元素数组里。空数组与两个键不是一个答案。 */
    @Test
    void aKeyWrappedInAOneElementArrayIsUnwrapped() {
        String raw = "{ \"12\": [\"organization\"], \"13\": [null], \"14\": [] }";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(1, r.malformed(), "空数组不是一个答案");
        assertEquals(List.of(choice(12, "organization"), choice(13, null)), r.choices());
        raw = "{\"b\": [[12, [\"organization\", \"person\"]]]}";
        r = Align.parseKindWordResponse(raw, items());
        assertEquals(1, r.malformed(), "两个键不是一个答案");
        assertTrue(r.choices().isEmpty());
        // 值里先抄一遍 id 再给键：{"0": [0, "organization"]}
        raw = "{\"12\": [12, \"organization\"], \"13\": [13, null]}";
        r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(13, null)), r.choices());
        // 键后面跟着候选行（标签与定义）：那是抄了一遍候选，不是第二个答案
        raw = "{\"12\": [\"organization\", \"Organization — An organized group of people\"],"
                + " \"13\": [null, \"no candidate fits\"]}";
        r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(13, null)), r.choices());
    }

    /** 一对写成对象、或者整段不要外层对象只给数组：照读。 */
    @Test
    void objectPairsAndABareArrayParse() {
        String raw = "{\"b\": [{\"id\": 12, \"key\": \"organization\"}, {\"id\": 13, \"key\": null}]}";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(13, null)), r.choices());
        raw = "```json\n[[12, \"organization\"], [14, \"structure\"]]\n```";
        r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(14, "structure")), r.choices());
    }

    /** 同一个 id 答了两次：留第一次，第二次计入坏项。 */
    @Test
    void aDuplicateIdKeepsTheFirst() {
        String raw = "{\"b\": [[12, \"organization\"], [12, null], [12, \"person\"]]}";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(List.of(choice(12, "organization")), r.choices());
        assertEquals(2, r.malformed());
    }

    /**
     * 坏项逐个计数：不是数组、只有一格、键是数字、键是空串、id 是对象；id 写成字符串照
     * 数字读。
     */
    @Test
    void malformedPairsAreCountedNotFatal() {
        String raw = "{\"b\": [\"not a pair\", [12], [13, 7], [14, \"\"],"
                + " [{\"id\": 12}, null], [\"12\", \"organization\"]]}";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(5, r.malformed());
        assertEquals(List.of(choice(12, "organization")), r.choices());
    }

    /** 键的大小写写错了：对回候选自己的键；首尾空白也去掉。 */
    @Test
    void aKeyInTheWrongCaseIsReadBackAsTheCandidatesKey() {
        String raw = "{\"b\": [[12, \"Organization\"], [14, \" structure \"]]}";
        KindWordReply r = Align.parseKindWordResponse(raw, items());
        assertEquals(0, r.malformed());
        assertEquals(List.of(choice(12, "organization"), choice(14, "structure")), r.choices());
    }

    /**
     * 提示词列出每一项的 id、词、拼写、例子、短语、候选键与定义，并带着「每一个这种
     * 东西」的判据；空清单写 (none)。
     */
    @Test
    void thePromptListsEveryItemAndCarriesTheRule() {
        List<ChatMessage> msgs = Align.buildKindWordMessages(items());
        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).role());
        assertEquals("user", msgs.get(1).role());
        String system = msgs.get(0).content();
        String user = msgs.get(1).content();

        assertTrue(system.contains("every thing of this kind is an instance of that class"));
        assertTrue(system.contains("{\"b\": [[12, \"organization\"], [13, null]]}"));
        assertTrue(system.contains("never merely related"));
        assertTrue(system.contains("Never invent a key"));

        assertTrue(user.contains("Item 12: \"company\"\n"));
        assertTrue(user.contains("Spellings: \"Company\", \"company\", \"公司\"\n"));
        assertTrue(user.contains("Examples: \"Brightway Builders\", \"Harbor Estates\"\n"));
        assertTrue(user.contains("Phrases: \"is based in\", \"was founded by\"\n"));
        assertTrue(user.startsWith("Classes:\n- "));
        assertEquals(1, count(user, "- organization: Organization — An organized group of people"),
                "一条定义一批只写一遍");
        assertEquals(1, count(user, "- person: Person — A human being."));
        assertTrue(user.indexOf("- structure: ") < user.indexOf("Item 12"), "定义表在各项之前");

        assertTrue(user.contains("Item 13: \"participant\"\n"));
        assertTrue(user.contains("Spellings: \"participants\"\nExamples: (none)\nPhrases: (none)\n"));

        assertTrue(user.contains("Item 14: \"bridge\"\n"));
        assertTrue(user.contains("Examples: \"Harbor Bridge\"\n"));
        assertTrue(user.contains("Phrases: \"spans\", \"was completed\"\n"));
        assertTrue(user.contains("- structure: 建筑物 — 人建造的固定物：桥、楼、水坝。"),
                "候选的标签与定义照库里的语言给: " + user);

        int a = user.indexOf("Item 12");
        int b = user.indexOf("Item 13");
        int c = user.indexOf("Item 14");
        assertTrue(a < b && b < c);
        assertTrue(!user.endsWith("\n"));
    }

    /** 标签与键相同不重复；定义为空不写破折号；没有候选写 (none)。 */
    @Test
    void aCandidateLineOmitsWhatIsEmpty() {
        List<KindWordItem> items = new ArrayList<>(items().subList(0, 1));
        items.set(0, new KindWordItem(12, "company", strings("Company"),
                strings("Brightway Builders", "Harbor Estates"),
                strings("is based in", "was founded by"),
                List.of(new ClassCandidate("organization", "organization", ""),
                        new ClassCandidate("place", "", "A location."))));
        items.add(new KindWordItem(15, "council", strings("council"), List.of(), List.of(),
                List.of()));
        String user = Align.buildKindWordMessages(items).get(1).content();
        assertTrue(user.startsWith("Classes:\n- organization\n- place — A location.\n\n"));
        assertTrue(user.contains("Candidates: organization, place\n"));
        assertTrue(user.contains("Item 15: \"council\"\n"));
        assertTrue(user.endsWith("Candidates: (none)"));
    }

    /** 提示词的例子是中性的：不出现任何测量语料的名字。 */
    @Test
    void thePromptNamesNoBenchmarkCorpus() {
        List<ChatMessage> msgs = Align.buildKindWordMessages(items());
        for (ChatMessage m : msgs) {
            for (String name : new String[]{
                    "NVIDIA", "Nvidia", "EDGAR", "CUAD", "DocRED", "Holmes", "Wikidata",
                    "ai-timeline", "OpenAI", "Pfizer", "State of the Union"}) {
                assertTrue(!m.content().contains(name),
                        name + " in prompt: " + m.content());
            }
        }
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
