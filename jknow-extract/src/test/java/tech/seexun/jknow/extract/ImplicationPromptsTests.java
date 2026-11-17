package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.ImplicationPrompts.Implies;
import tech.seexun.jknow.extract.ImplicationPrompts.ReadingItem;
import tech.seexun.jknow.extract.ImplicationPrompts.RuleItem;
import tech.seexun.jknow.extract.PhraseAlign.PropertyCandidate;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * extract/implication.rs 内联 mod tests 四例逐字移植（断言不改写）：提规则的答案要
 * 指名候选与已知读数 / 坏答案计数不采信 / 读数落成名字或四位年份 / 提示词带着读数清单
 * 与形状。
 */
class ImplicationPromptsTests {

    private static final String[][] READINGS = {
            {"country_of_nationality", "…"}, {"year_of_phrase", "…"}};

    private static PropertyCandidate cand(String key) {
        return new PropertyCandidate(key, key, "", "relation", "state", List.of(), List.of(),
                List.of());
    }

    private static RuleItem item(long id, String trigger, String bound) {
        return new RuleItem(id, trigger, "british film", "film", null, false, bound, List.of(),
                List.of(cand("country_of_origin"), cand("genre")));
    }

    /** implication.rs:339-356 a_rule_answer_names_a_candidate_and_a_known_reading。 */
    @Test
    void aRuleAnswerNamesACandidateAndAKnownReading() {
        List<RuleItem> items = List.of(item(0, "kind_word", null), item(1, "phrase", "genre"));
        ImplicationPrompts.RuleReply r = ImplicationPrompts.parseRuleResponse(
                "```json\n{\"i\":[[0,\"country_of_origin\",\"country_of_nationality\"],[1,null,null]]}\n```",
                items, READINGS);
        assertEquals(0, r.malformed());
        assertEquals(new Implies("country_of_origin", "country_of_nationality"),
                r.choices().get(0).implies());
        assertNull(r.choices().get(1).implies());
    }

    /** implication.rs:358-375 bad_rule_answers_are_counted_not_believed。 */
    @Test
    void badRuleAnswersAreCountedNotBelieved() {
        List<RuleItem> items = List.of(item(0, "kind_word", null), item(1, "phrase", "genre"));
        // 未知属性；类别词没有读数；已绑到的属性；未知读数；重复 id
        ImplicationPrompts.RuleReply r = ImplicationPrompts.parseRuleResponse(
                "{\"i\":[[0,\"director\",null],[0,\"country_of_origin\",null],[1,\"genre\",null],"
                        + "[1,\"country_of_origin\",\"made_up\"],[1,null,null],[1,null,null]]}",
                items, READINGS);
        assertEquals(1, r.choices().size());
        assertEquals(1, r.choices().get(0).id());
        assertEquals(5, r.malformed());
    }

    /** implication.rs:377-406 readings_land_as_names_or_four_digit_years。 */
    @Test
    void readingsLandAsNamesOrFourDigitYears() {
        List<ReadingItem> items = List.of(
                new ReadingItem(0, "country_of_nationality", "british"),
                new ReadingItem(1, "year_of_phrase", "the summer of 1952"),
                new ReadingItem(2, "year_of_phrase", "last year"),
                new ReadingItem(3, "country_of_nationality", "iberian"));
        ImplicationPrompts.ReadingReply r = ImplicationPrompts.parseReadingResponse(
                "{\"r\":[[0,\"United Kingdom\"],[1,1952],[2,\"recently\"],[3,null]]}", items);
        assertEquals(1, r.malformed(), "a non-year for year_of_phrase is malformed");
        assertEquals("United Kingdom", r.answers().get(0).name());
        assertEquals("1952", r.answers().get(1).value());
        assertNull(r.answers().get(2).name());
        assertNull(r.answers().get(2).value());
    }

    /** implication.rs:408-432 the_prompts_carry_the_readings_and_the_shape。 */
    @Test
    void thePromptsCarryTheReadingsAndTheShape() {
        List<RuleItem> items = List.of(item(0, "phrase", "genre"));
        List<ChatMessage> m = ImplicationPrompts.buildRuleMessages(items, READINGS);
        assertTrue(m.get(0).content().contains("- country_of_nationality"));
        assertTrue(m.get(1).content()
                .contains("phrase \"british film\" · subject class: film · object: ?"),
                m.get(1).content());
        assertTrue(m.get(1).content().contains("already bound to: genre"));
        List<ChatMessage> r = ImplicationPrompts.buildReadingMessages(
                List.of(new ReadingItem(7, "year_of_phrase", "in 1952")), READINGS);
        assertTrue(r.get(1).content().contains("Item 7: year_of_phrase · \"in 1952\""),
                r.get(1).content());
    }
}
