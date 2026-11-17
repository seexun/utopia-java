package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Glossary;
import tech.seexun.jknow.extract.OntologyAgentPrompts.GlossaryProperty;
import tech.seexun.jknow.extract.QuestionPrompts.ProposedQuestion;
import tech.seexun.jknow.extract.QuestionPrompts.TopEntity;
import tech.seexun.jknow.extract.QuestionPrompts.TopKindWord;
import tech.seexun.jknow.extract.QuestionPrompts.TopSignature;

/** 提问代理提示词与解析（extract/question_agent.rs 内联 mod tests 2 例——断言逐字）。 */
class QuestionPromptsTests {

    @Test
    void the_prompt_lists_shapes_kind_words_entities_and_existing_questions() {
        Glossary glossary = new Glossary(
                List.<String[]>of(new String[] {"person", "Person", ""}),
                List.of(new GlossaryProperty("p20", "placeOfDeath", "relation",
                        List.of("person"), List.of("location"), "")),
                List.<String[]>of(new String[] {"p999", "somethingElse"}));
        List<TopSignature> sigs = List.of(new TopSignature("died in", "person", "location",
                false, 7, "p20", "Orlov died in Moscow"));
        List<TopKindWord> words = List.of(new TopKindWord("district", 12));
        List<TopEntity> ents = List.of(new TopEntity("Pedro II", "person", 9));
        String user = QuestionPrompts.buildQuestionMessages(sigs, words, ents, glossary,
                List.of("Who died where?"), 10).get(1).content();
        assertTrue(user.contains("\"died in\" → location · 7 statements · bound to p20"));
        assertTrue(user.contains("district · 12 entities"));
        assertTrue(user.contains("Pedro II (person) · 9 statements"));
        assertTrue(user.contains("do not repeat them"));
        assertTrue(user.contains("Who died where?"));
        assertTrue(user.contains("up to 10"));
    }

    @Test
    void keys_the_ontology_lacks_are_dropped_and_short_or_duplicate_questions_are_not_kept()
            throws Exception {
        Set<String> classes = new HashSet<>(List.of("person"));
        Set<String> props = new HashSet<>(List.of("p20"));
        String raw = """
                {"q":[
                  {"question":"Where did each person die?","classes":["person","ghost"],"properties":["p20","died_in"]},
                  {"question":"where did each PERSON die?","classes":[],"properties":[]},
                  {"question":"Why?","classes":[],"properties":[]},
                  "not an object"
                ]}
                """;
        var parsed = QuestionPrompts.parseQuestionResponse(raw, classes, props);
        assertEquals(List.of(new ProposedQuestion("Where did each person die?",
                List.of("person"), List.of("p20"))), parsed.questions());
        assertEquals(2, parsed.malformed());
    }
}
