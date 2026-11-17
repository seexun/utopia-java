package tech.seexun.jknow.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Glossary;
import tech.seexun.jknow.extract.OntologyAgentPrompts.GlossaryProperty;
import tech.seexun.jknow.extract.OntologyAgentPrompts.OpenKindWord;
import tech.seexun.jknow.extract.OntologyAgentPrompts.OpenSignature;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Proposal;
import tech.seexun.jknow.extract.OntologyAgentPrompts.QuestionItem;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/** 本体代理提示词与解析（extract/ontology_agent.rs 内联 mod tests 3 例——断言逐字）。 */
class OntologyAgentPromptsTests {

    private static Set<Long> idsOf(long... xs) {
        Set<Long> out = new HashSet<>();
        for (long x : xs) {
            out.add(x);
        }
        return out;
    }

    @Test
    void a_well_formed_reply_yields_proposals_and_existing() throws Exception {
        String raw = """
                {"p":[{"kind":"property","key":"supplies","label":"supplies","definition":"The subject organisation delivers goods to the object organisation. Not for one-off sales.","value":false,"datatype":null,"domains":["organization"],"ranges":["organization"],"s":[0,2],"k":[],"q":[1]},{"kind":"class","key":"supplier","label":"Supplier","definition":"An organisation that supplies others.","parents":["organization"],"s":[],"k":[0],"q":[]}],"existing":[{"key":"located_in","dir":"reverse","s":[1],"k":[]}]}
                """;
        var r = OntologyAgentPrompts.parseProposalResponse(raw, idsOf(0, 1, 2), idsOf(0),
                idsOf(1));
        assertEquals(0, r.malformed());
        assertEquals(2, r.proposals().size());
        assertEquals("supplies", r.proposals().get(0).key());
        assertEquals(List.of(0L, 2L), r.proposals().get(0).signatures());
        assertEquals(List.of(1L), r.proposals().get(0).questions());
        assertEquals(List.of("organization"), r.proposals().get(0).domains());
        assertEquals("class", r.proposals().get(1).kind());
        assertEquals(List.of("organization"), r.proposals().get(1).parents());
        assertEquals(1, r.existing().size());
        assertEquals("located_in", r.existing().get(0).key());
        assertEquals("reverse", r.existing().get(0).direction());
        assertEquals(List.of(1L), r.existing().get(0).signatures());
        assertEquals(List.of(), r.existing().get(0).kindWords());
    }

    @Test
    void a_proposal_that_binds_nothing_or_points_outside_the_batch_is_malformed()
            throws Exception {
        String raw = """
                {"p":[{"kind":"property","key":"orphan","label":"o","definition":"","s":[],"k":[],"q":[]},{"kind":"property","key":"far","label":"f","definition":"","s":[9],"k":[],"q":[]},{"kind":"property","key":"Bad Key","label":"b","definition":"","s":[0],"k":[],"q":[]},{"kind":"property","key":"ok","label":"ok","definition":"d","value":true,"datatype":"number","s":["s0"],"k":[],"q":["q1"]}]}
                """;
        var r = OntologyAgentPrompts.parseProposalResponse(raw, idsOf(0), idsOf(), idsOf(1));
        assertEquals(3, r.malformed());
        assertEquals(1, r.proposals().size());
        Proposal ok = r.proposals().get(0);
        assertEquals("ok", ok.key());
        assertTrue(ok.value());
        assertEquals("number", ok.datatype());
        assertEquals(List.of(0L), ok.signatures(), "an id written as s0 still counts");
        assertEquals(List.of(1L), ok.questions());
    }

    @Test
    void the_prompt_names_every_input_once() {
        List<String> examples = List.of("W-1 —supplies→ F-2");
        List<String> quotes = List.of("W-1 supplies F-2 with parts.");
        List<OpenSignature> sigs = List.of(new OpenSignature(0, "supplies", "organization",
                "organization", false, 7, examples, quotes));
        List<String> names = List.of("Acme Supply");
        List<String> phrases = List.of("supplies");
        List<OpenKindWord> kws = List.of(new OpenKindWord(0, "supplier", 3, names, phrases));
        Glossary glossary = new Glossary(
                List.<String[]>of(new String[] {"organization", "Organization",
                        "a company or institution"}),
                List.of(new GlossaryProperty("located_in", "located in", "relation",
                        List.of("organization"), List.of("location"), "where a thing is")),
                List.<String[]>of(new String[] {"p999", "somethingElse"}));
        List<QuestionItem> qs = List.of(new QuestionItem(1, "Who supplies whom?"));
        List<ChatMessage> m = OntologyAgentPrompts.buildProposalMessages(sigs, kws, glossary,
                qs);
        assertEquals(2, m.size());
        String user = m.get(1).content();
        assertTrue(user.contains("Other existing properties"), user);
        assertTrue(user.contains("- p999 · somethingElse"), user);
        for (String needle : List.of("s0: phrase \"supplies\"", "k0: kind word \"supplier\"",
                "q1: Who supplies whom?",
                "- located_in · located in · relation · domain: organization · range: location",
                "\"W-1 supplies F-2 with parts.\"")) {
            assertTrue(user.contains(needle), "missing \"" + needle + "\" in\n" + user);
        }
    }
}
