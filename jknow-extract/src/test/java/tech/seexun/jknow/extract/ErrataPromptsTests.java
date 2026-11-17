package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.ErrataPrompts.ErrataFact;
import tech.seexun.jknow.extract.ErrataPrompts.ErrataProperty;
import tech.seexun.jknow.extract.ErrataPrompts.ErrataResponse;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * extract/errata.rs 内联 mod tests 六例逐字移植（断言不改写）：提示词带着文档/旗/
 * 属性表；一票一条、坏行计数不采信；撤没引文算坏；没答完就加 → 加全拒；第二票只认
 * 显式 not_stated；没有数组是错误不是零票。
 */
class ErrataPromptsTests {

    private static List<ErrataFact> facts() {
        return List.of(
                new ErrataFact(0, "Acme", "organization", "based_in", "Paris", "place",
                        "name_absent", "Acme is based in London"),
                new ErrataFact(1, "Acme", "organization", "based_in", "London", "place", null,
                        null));
    }

    /** errata.rs:306-323 the_prompt_carries_the_document_the_flags_and_the_properties。 */
    @Test
    void thePromptCarriesTheDocumentTheFlagsAndTheProperties() {
        List<ErrataProperty> props = List.of(new ErrataProperty("based_in", "based in",
                "where an organization is based", "relation", List.of("organization"),
                List.of("place"), null));
        List<ChatMessage> m = ErrataPrompts.buildErrataMessages("Acme is based in London.",
                props, facts());
        assertEquals(2, m.size());
        assertTrue(m.get(0).content().contains("JSON only"));
        String u = m.get(1).content();
        assertTrue(u.contains("DOCUMENT:\nAcme is based in London."), u);
        assertTrue(u.contains(
                "- based_in (based in): where an organization is based [thing] subject: organization object: place"),
                u);
        assertTrue(u.contains(
                "0: Acme (organization) —based_in→ Paris (place) FLAG name_absent · read from: \"Acme is based in London\""),
                u);
        assertTrue(u.contains("1: Acme (organization) —based_in→ London (place)\n")
                || u.endsWith("1: Acme (organization) —based_in→ London (place)"), u);
    }

    /** errata.rs:325-341 every_fact_gets_one_verdict_and_bad_rows_are_counted_not_believed。 */
    @Test
    void everyFactGetsOneVerdictAndBadRowsAreCountedNotBelieved() {
        String raw = "Sure: {\"a\":[[0,\"retract\",\"not in the document\",\"Acme is based in London\"],"
                + "[1,\"keep\"],[0,\"keep\"],[7,\"keep\"],"
                + "[1,\"revise\",{\"property\":null,\"object\":null},\"x\",\"q\"],[\"nope\"]]}";
        ErrataResponse r = ErrataPrompts.parseErrataResponse(raw, facts());
        assertEquals(2, r.verdicts.size());
        assertInstanceOf(ErrataPrompts.Retract.class, r.verdicts.get(0).verdict());
        assertEquals("Acme is based in London", r.verdicts.get(0).quote());
        assertInstanceOf(ErrataPrompts.Keep.class, r.verdicts.get(1).verdict());
        // 重复的 0、不认识的 7、改了等于没改的、不是数组的：四条坏行
        assertEquals(4, r.malformed);
    }

    /** errata.rs:343-349 a_retraction_without_a_quote_is_malformed。 */
    @Test
    void aRetractionWithoutAQuoteIsMalformed() {
        ErrataResponse r = ErrataPrompts.parseErrataResponse(
                "{\"a\":[[0,\"retract\",\"no quote\"],[1,\"keep\"]]}", facts());
        assertEquals(1, r.verdicts.size());
        assertEquals(1, r.malformed);
    }

    /** errata.rs:351-371 additions_count_only_after_every_given_fact_is_answered。 */
    @Test
    void additionsCountOnlyAfterEveryGivenFactIsAnswered() {
        String add = "[null,\"add\",{\"subject\":\"Acme\",\"property\":\"ceo\",\"object\":\"Jane Roe\"},"
                + "\"stated\",\"Jane Roe runs Acme\"]";
        ErrataResponse partial = ErrataPrompts.parseErrataResponse(
                "{\"a\":[[0,\"keep\"]," + add + "]}", facts());
        assertTrue(partial.additions.isEmpty());
        assertEquals(1, partial.additionsRefused);
        ErrataResponse full = ErrataPrompts.parseErrataResponse(
                "{\"a\":[[0,\"keep\"],[1,\"keep\"]," + add
                        + ",[null,\"add\",{\"subject\":\"Acme\"},\"half\",\"q\"]]}", facts());
        assertEquals(1, full.additions.size());
        assertEquals("Jane Roe", full.additions.get(0).object());
        assertEquals("Jane Roe runs Acme", full.additions.get(0).quote());
        assertEquals(1, full.malformed);
    }

    /** errata.rs:373-390 the_second_vote_only_counts_an_explicit_not_stated。 */
    @Test
    void theSecondVoteOnlyCountsAnExplicitNotStated() {
        List<ChatMessage> m = ErrataPrompts.buildConfirmMessages("Acme is based in London.",
                facts().subList(0, 1));
        assertTrue(m.get(1).content().contains("0: Acme —based_in→ Paris"), m.get(1).content());
        List<Long> ids = List.of(0L, 1L);
        Set<Long> r = ErrataPrompts.parseConfirmResponse(
                "{\"c\":[[0,\"not_stated\"],[1,\"stated\"],[7,\"not_stated\"],[2]]}", ids);
        assertTrue(r.contains(0L) && !r.contains(1L) && !r.contains(7L));
        // 没答到的不算票
        assertTrue(ErrataPrompts.parseConfirmResponse("{\"c\":[]}", ids).isEmpty());
        assertThrows(Exception.class,
                () -> ErrataPrompts.parseConfirmResponse("nope", ids));
    }

    /** errata.rs:392-396 no_array_is_an_error_not_an_empty_answer。 */
    @Test
    void noArrayIsAnErrorNotAnEmptyAnswer() {
        assertThrows(Exception.class, () -> ErrataPrompts.parseErrataResponse("{}", facts()));
        assertThrows(Exception.class,
                () -> ErrataPrompts.parseErrataResponse("not json", facts()));
    }
}
