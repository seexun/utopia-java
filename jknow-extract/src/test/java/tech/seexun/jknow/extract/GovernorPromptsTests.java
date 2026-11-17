package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationPair;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationSide;
import tech.seexun.jknow.extract.GovernorPrompts.EarlierLook;
import tech.seexun.jknow.extract.GovernorPrompts.Step;

/**
 * governor.rs:230-339 内联 mod tests 三例逐字移植（断言不改写）：工具调用的参数校验 /
 * decide 的读法与钳制 / 开场消息带上这一对与上一眼。
 */
class GovernorPromptsTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** governor.rs:235-254 a_lookup_needs_its_argument。 */
    @Test
    void aLookupNeedsItsArgument() throws Exception {
        assertEquals(
                new Step.Lookup("facts", JSON.readTree("{\"side\":\"A\"}")),
                GovernorPrompts.readStep("facts", "{\"side\":\"A\"}"));
        assertInstanceOf(Step.Unknown.class,
                GovernorPrompts.readStep("facts", "{\"side\":\"C\"}"));
        assertInstanceOf(Step.Unknown.class, GovernorPrompts.readStep("ledger", "{}"));
        assertInstanceOf(Step.Unknown.class, GovernorPrompts.readStep("ledger", "not json"));
        assertInstanceOf(Step.Lookup.class,
                GovernorPrompts.readStep("namesakes", "{\"query\":\"Apple\"}"));
    }

    /** governor.rs:256-287 a_decision_is_read_and_clamped。 */
    @Test
    void aDecisionIsReadAndClamped() {
        assertEquals(
                new Step.Decide(true, 1.0f, "one CEO"),
                GovernorPrompts.readStep("decide",
                        "{\"verdict\":\"same\",\"confidence\":1.7,\"why\":\"one CEO\"}"));
        assertInstanceOf(Step.Unknown.class,
                GovernorPrompts.readStep("decide", "{\"verdict\":\"maybe\"}"));
        assertEquals(
                new Step.Defer("Is the 2024 CFO the same person?"),
                GovernorPrompts.readStep("defer",
                        "{\"question\":\" Is the 2024 CFO the same person? \"}"));
        assertInstanceOf(Step.Unknown.class,
                GovernorPrompts.readStep("defer", "{\"question\":\"\"}"));
        assertInstanceOf(Step.Unknown.class, GovernorPrompts.readStep("smash", "{}"));
    }

    /** governor.rs:289-338 the_opening_carries_the_pair_and_the_earlier_look。 */
    @Test
    void theOpeningCarriesThePairAndTheEarlierLook() {
        AdjudicationPair pair = new AdjudicationPair(
                new AdjudicationSide("Zhang Wei", "Person", List.of("works at → Nebula")),
                new AdjudicationSide("Zhang Wei", "Person", List.of()),
                List.of("this same pair was kept apart by a person on 2026-09-01"),
                null);
        List<JsonNode> m = GovernorPrompts.messages(pair,
                new EarlierLook("unsure", 0.5f, "no facts on B"));
        assertEquals(2, m.size());
        String user = m.get(1).get("content").asText();
        assertTrue(user.contains("Record A: \"Zhang Wei\" (Person)"));
        assertTrue(user.contains("(no recorded facts)"));
        assertTrue(user.contains("kept apart by a person"));
        assertTrue(user.contains("The earlier look said: unsure (0.50) — no facts on B."));
        JsonNode tools = GovernorPrompts.tools();
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode t : tools) {
            names.add(t.get("function").get("name").asText());
        }
        assertEquals(List.of("facts", "quotes", "ledger", "namesakes", "consequences",
                "decide", "defer"), names);
    }
}
