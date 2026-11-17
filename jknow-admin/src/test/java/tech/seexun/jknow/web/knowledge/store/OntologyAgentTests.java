package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.jobs.ProposeOntologyJob;
import tech.seexun.jknow.web.knowledge.jobs.ProposeOntologyJob.AdoptEdits;
import tech.seexun.jknow.store.CompetencyQuestionsService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.StoredProposal;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.PhraseBindingsService.Decision;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.QuestionsMapper;
import tech.seexun.jknow.store.mapper.QuestionsMapper.QuestionRow;

/**
 * 本体代理的一轮（ontology_agent_tests.rs 4 例逐字移植，断言不改写）：没绑上的形状和
 * 接受了的问题交给脚本化的模型，提案落进 ontology_proposals 并记下服务的问题与形状；
 * 采纳建出属性并排对齐；提过的形状下一轮不再送；declined 的陈述数翻倍再送；没问题的
 * 库先提问题。直调 {@link ProposeOntologyJob#proposeWith}（不经队列——共享栈 Rust
 * worker 会消费同名 kind，ErrataAgentTests 同口径）。
 */
class OntologyAgentTests extends StoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProposeOntologyJob agent;
    @Autowired
    tech.seexun.jknow.web.knowledge.jobs.ProposeQuestionsJob questionAgent;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    CompetencyQuestionsService questions;
    @Autowired
    QuestionsMapper questionMapper;
    @Autowired
    tech.seexun.jknow.store.mapper.OntologyMapper ontologyMapper;
    @Autowired
    OntologyService ontology;
    @Autowired
    SettingsService settings;
    @Autowired
    tech.seexun.jknow.store.OntologyRegressionsService regressions;
    @Autowired
    JobsService jobs;

    private static final class Fx {
        final UUID org;
        final UUID kb;
        final UUID organization;
        final UUID place;
        final UUID locatedIn;
        final UUID user;
        final ScriptedModelServer model;

        Fx(UUID org, UUID kb, UUID organization, UUID place, UUID locatedIn, UUID user,
           ScriptedModelServer model) {
            this.org = org;
            this.kb = kb;
            this.organization = organization;
            this.place = place;
            this.locatedIn = locatedIn;
            this.user = user;
            this.model = model;
        }
    }

    /**
     * Rust Fx::new：一个库（organization、place 两个类，located_in 一条属性）；
     * Acme —founded in→ London、Acme —located in→ London 两条开放陈述（形状按短语排：
     * s0 = founded in，s1 = located in）。
     */
    private Fx seed(List<String> replies) throws java.io.IOException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID place = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID london = Uuids.nowV7();
        UUID statement = Uuids.nowV7();
        UUID st2 = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID locatedIn = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'ontology-agent')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?,?,'ontology-agent')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?,?,'ontology-agent')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, '?@agent.test', 'Adopter')
                """, user, org);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape,description) VALUES
                    (?,?,'organization','Organization','#000','circle','a company or institution'),
                    (?,?,'place','Place','#000','circle','a geographic location')
                """, organization, kb, place, kb);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id) VALUES
                    (?,?,'Acme',?), (?,?,'London',?)
                """, acme, kb, organization, london, kb, place);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,description)
                VALUES (?,?,'located_in','located in','relation','state','where an organization sits')
                """, locatedIn, kb);
        jdbc.update("""
                INSERT INTO relation_type_domains(relation_type_id,entity_type_id)
                VALUES (?,?)
                """, locatedIn, organization);
        jdbc.update("""
                INSERT INTO relation_type_ranges(relation_type_id,entity_type_id)
                VALUES (?,?)
                """, locatedIn, place);
        jdbc.update("""
                INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase) VALUES
                    (?, ?, ?, ?, 'open', 'founded in'), (?, ?, ?, ?, 'open', 'located in')
                """, statement, kb, acme, london, st2, kb, acme, london);
        ScriptedModelServer model = ScriptedModelServer.start(replies);
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        return new Fx(org, kb, organization, place, locatedIn, user, model);
    }

    private void cleanup(Fx f) {
        if (f.model != null) {
            f.model.stop();
        }
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
        // decided_by 指向 users，不级联：先清提案再删组织
        jdbc.update("DELETE FROM ontology_agent_reviews WHERE kb_id = ?", f.kb);
        jdbc.update("DELETE FROM ontology_proposals WHERE kb_id = ?", f.kb);
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    /** 对齐判过这两条形状：没有属性可绑（decide_none）。 */
    private void decideNone(Fx f) {
        var sigs = phraseBindings.signatures(f.kb);
        assertEquals(2, sigs.size(), "two open statements, two signatures");
        for (var sig : sigs) {
            phraseBindings.decide(f.kb, sig, new Decision(null, null, "none",
                    json("{\"reason\": \"no_candidates\"}"), "agent", "test", null, false));
        }
    }

    /** 库里有一条人写的问题（本体代理就不先去提问题）。 */
    private UUID seedQuestion(Fx f) {
        return questions.create(f.kb, new CompetencyQuestionsService.NewQuestion(
                "Where was each organization founded?", null, null, "person", "accepted", null));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rust Fx::user_text：全部 user 消息拼起来。 */
    private static String userText(ScriptedModelServer model, int n) {
        StringBuilder out = new StringBuilder();
        for (JsonNode m : model.requests().get(n).path("messages")) {
            if ("user".equals(m.path("role").asText())) {
                out.append(m.path("content").asText("")).append('\n');
            }
        }
        return out.toString();
    }

    /** an_unbound_shape_becomes_a_proposal_that_serves_a_question_and_adoption_builds_the_property。 */
    @Test
    void anUnboundShapeBecomesAProposalThatServesAQuestionAndAdoptionBuildsTheProperty()
            throws Exception {
        Fx f = seed(List.of("{\"p\": [{"
                + "\"kind\": \"property\", \"key\": \"founded_in\", \"label\": \"founded in\","
                + "\"definition\": \"the place where an organization was founded\","
                + "\"value\": false, \"datatype\": null,"
                + "\"domains\": [\"organization\"], \"ranges\": [\"place\"],"
                + "\"s\": [0], \"k\": [], \"q\": [0]"
                + "}], \"existing\": []}"));
        try {
            decideNone(f);
            UUID qid = seedQuestion(f);

            agent.proposeWith(f.kb, false);

            // 模型看到了形状、词表和问题
            assertEquals(1, f.model.requests().size(), "one batch, one call");
            String text = userText(f.model, 0);
            assertTrue(text.contains("founded in"), "the open shape is offered: " + text);
            assertTrue(text.contains("organization"), "the glossary is offered: " + text);
            assertTrue(text.contains("located_in"), "existing properties are in the glossary: "
                    + text);
            assertTrue(text.contains("Where was each organization founded?"),
                    "the accepted question is offered: " + text);

            // 提案落库，带着谁提的、服务的问题、会绑的形状
            List<StoredProposal> open = ontology.openProposals(f.kb);
            assertEquals(1, open.size(), () -> open.toString());
            StoredProposal p = open.get(0);
            assertEquals("relation_types", p.section());
            assertEquals("founded_in", p.key());
            assertEquals("agent", p.proposedBy());
            assertEquals(List.of(qid), p.serves());
            assertEquals("founded in", p.signatures().path("phrases").get(0).path("phrase")
                    .asText(), "the shape it binds is recorded: " + p.signatures());
            assertEquals("organization", p.signatures().path("phrases").get(0).path("subject")
                    .asText());
            assertEquals("founded in", p.payload().path("forms").get(0).asText());
            assertEquals("organization", p.payload().path("domains").get(0).asText());
            assertEquals("place", p.payload().path("ranges").get(0).asText());
            UUID exampleId = UUID.fromString(p.signatures().path("phrases").get(0)
                    .path("example_statement_ids").get(0).asText());
            String phrase = jdbc.queryForObject(
                    "SELECT phrase FROM facts WHERE id = ? AND kb_id = ?", String.class,
                    exampleId, f.kb);
            assertEquals("founded in", phrase,
                    "the proposed example has a stable source identity");
            assertEquals("the place where an organization was founded",
                    p.payload().path("description").asText());

            // 提过的形状、看过没提的形状（located in）下一轮都不再送：没有模型调用
            List<String> outcomes = jdbc.queryForList(
                    "SELECT shape->>'phrase' || '=' || outcome FROM ontology_agent_reviews "
                            + "WHERE kb_id = ? ORDER BY 1", String.class, f.kb);
            assertEquals(List.of("founded in=proposed", "located in=declined"), outcomes,
                    "every offered shape is recorded with its outcome");
            agent.proposeWith(f.kb, false);
            assertEquals(1, f.model.requests().size(),
                    "already-proposed and declined shapes are not offered again");

            // 采纳：属性建出来，签名域值域照提案，提案标记 adopted，短语对齐排上
            UUID id = agent.adopt(f.kb, "relation_types", "founded_in",
                    new AdoptEdits("Founded in", null, null, null, null, null), f.user);
            var props = ontology.relationTypes(f.kb);
            var built = props.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertEquals("founded_in", built.key());
            // 属性标签照库里的规矩写成小驼峰：人的改动进去了，只是换了写法
            assertEquals("foundedIn", built.label(), "the person's edit wins");
            assertEquals("relation", built.kind());
            assertEquals(List.of(f.organization), built.domains());
            assertEquals(List.of(f.place), built.ranges());
            assertEquals("the place where an organization was founded", built.description());
            assertTrue(ontology.openProposals(f.kb).isEmpty(), "the proposal is no longer open");
            assertEquals("adopted", jdbc.queryForObject(
                    "SELECT status FROM ontology_proposals WHERE kb_id = ? "
                            + "AND section = 'relation_types' AND key = 'founded_in'",
                    String.class, f.kb));
            var cases = regressions.list(f.kb);
            assertEquals(1, cases.size());
            assertEquals(exampleId, cases.get(0).statementId());
            assertEquals(id, cases.get(0).expectedPropertyId());
            assertEquals("forward", cases.get(0).expectedDirection());
            assertEquals(f.user, cases.get(0).createdBy());
            assertEquals("adoption", cases.get(0).origin());

            assertTrue(jobs.pendingForKb("align_phrases", f.kb),
                    "adoption re-decides the shapes through alignment");

            // 采纳过的不能再采纳
            assertThrows(AppException.class,
                    () -> agent.adopt(f.kb, "relation_types", "founded_in",
                            new AdoptEdits(null, null, null, null, null, null), f.user));

            // Older proposals lack source IDs: adoption succeeds without inventing cases.
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode legacyShape =
                    (com.fasterxml.jackson.databind.node.ObjectNode) p.signatures()
                            .path("phrases").get(0).deepCopy();
            legacyShape.remove("example_statement_ids");
            ontology.saveAgentProposals(f.kb, List.of(new OntologyService.AgentProposal(
                    "map_to", "located_in",
                    mapper.createObjectNode().put("kind", "relation"), List.of(),
                    mapper.createObjectNode().set("phrases",
                            mapper.createArrayNode().add(legacyShape)))));
            agent.adopt(f.kb, "map_to", "located_in",
                    new AdoptEdits(null, null, null, null, null, null), f.user);
            assertNull(ontology.openProposal(f.kb, "map_to", "located_in"));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM ontology_regression_cases WHERE kb_id = ?", Long.class,
                    f.kb), "a proposal without example IDs adds no regression case");
        } finally {
            cleanup(f);
        }
    }

    /** a_proposal_for_an_existing_key_or_binding_nothing_is_dropped。 */
    @Test
    void aProposalForAnExistingKeyOrBindingNothingIsDropped() throws Exception {
        Fx f = seed(List.of("{\"p\": ["
                // 已有的类：不提
                + "{\"kind\": \"class\", \"key\": \"place\", \"label\": \"Place\", \"definition\": \"x\", \"parents\": [], \"s\": [], \"k\": [], \"q\": []},"
                // 什么都不绑：坏项
                + "{\"kind\": \"property\", \"key\": \"orphan\", \"label\": \"orphan\", \"definition\": \"x\", \"value\": false, \"domains\": [], \"ranges\": [], \"s\": [], \"k\": [], \"q\": []},"
                // 字面值属性：进 attribute_types，datatype 缺省 text
                + "{\"kind\": \"property\", \"key\": \"founding_note\", \"label\": \"founding note\", \"definition\": \"x\", \"value\": true, \"datatype\": \"weird\", \"domains\": [\"organization\"], \"ranges\": [], \"s\": [0], \"k\": [], \"q\": []}"
                + "], \"existing\": []}"));
        try {
            decideNone(f);
            seedQuestion(f);
            agent.proposeWith(f.kb, false);
            List<StoredProposal> open = ontology.openProposals(f.kb);
            assertEquals(1, open.size(), () -> open.toString());
            assertEquals("attribute_types", open.get(0).section());
            assertEquals("founding_note", open.get(0).key());
            assertEquals("text", open.get(0).payload().path("datatype").asText());
            assertTrue(open.get(0).serves().isEmpty());
        } finally {
            cleanup(f);
        }
    }

    /**
     * a_declined_shape_returns_when_its_statements_double_and_an_existing_answer_becomes_a_binding。
     */
    @Test
    void aDeclinedShapeReturnsWhenItsStatementsDoubleAndAnExistingAnswerBecomesABinding()
            throws Exception {
        Fx f = seed(List.of(
                // 第一轮：founded in 没提；located in 本体里已经有（located_in，正向）
                "{\"p\": [], \"existing\": [{\"key\": \"located_in\", \"dir\": \"forward\", \"s\": [1], \"k\": []}]}",
                // 陈述翻倍之后的那轮：只有 founded in 送来（这一批里它是 s0），答成 located_in 反着读
                "{\"p\": [], \"existing\": [{\"key\": \"located_in\", \"dir\": \"reverse\", \"s\": [0], \"k\": []}]}"));
        try {
            decideNone(f);
            seedQuestion(f);

            agent.proposeWith(f.kb, false);
            assertEquals(1, f.model.requests().size());
            List<StoredProposal> open = ontology.openProposals(f.kb);
            assertEquals(1, open.size(), "the existing answer is a map_to proposal");
            StoredProposal m = open.get(0);
            assertEquals("map_to", m.section());
            assertEquals("located_in", m.key());
            assertEquals("agent", m.proposedBy());
            assertEquals("relation", m.payload().path("kind").asText());
            assertEquals("located in", m.payload().path("label").asText(),
                    "the target's label travels with it");
            assertEquals("located in", m.payload().path("forms").get(0).asText());
            assertEquals("located in", m.signatures().path("phrases").get(0).path("phrase")
                    .asText());
            assertEquals("forward", m.signatures().path("phrases").get(0).path("direction")
                    .asText());

            // 第二轮：一条提过（map_to）、一条看过没提，词表没变——不问
            agent.proposeWith(f.kb, false);
            assertEquals(1, f.model.requests().size(), "nothing new to look at");

            // 词表变了（多了一个类）：看过没提的不再问——对齐自己会拿新元素去重判
            ontology.createEntityType(f.kb, "city", "City", "#000", "circle",
                    List.of(f.place), "a city");
            agent.proposeWith(f.kb, false);
            assertEquals(1, f.model.requests().size(),
                    "a changed glossary alone does not re-offer declined shapes");

            // founded in 的陈述翻倍了（1 → 2）：再送一次；map_to 已经提过的 located in 不送
            jdbc.update("""
                    INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase)
                    SELECT ?, kb_id, subject_id, object_id, 'open', 'founded in' FROM facts
                     WHERE kb_id = ? AND phrase = 'founded in' LIMIT 1
                    """, Uuids.nowV7(), f.kb);
            agent.proposeWith(f.kb, false);
            assertEquals(2, f.model.requests().size(),
                    "a declined shape with twice the statements is offered again");
            String text = userText(f.model, 1);
            assertTrue(text.contains("founded in"), "the declined shape is offered again: "
                    + text);
            assertFalse(text.contains("s1"),
                    "the shape already proposed as map_to is not offered: " + text);

            // 这一轮的答案并进同一条 map_to：两条形状，各自的方向
            open = ontology.openProposals(f.kb);
            assertEquals(1, open.size(), "still one map_to row");
            JsonNode shapes = open.get(0).signatures().path("phrases");
            assertEquals(2, shapes.size(), "shapes from both rounds are kept");
            assertEquals("located in", shapes.get(0).path("phrase").asText());
            assertEquals("forward", shapes.get(0).path("direction").asText());
            assertEquals("founded in", shapes.get(1).path("phrase").asText());
            assertEquals("reverse", shapes.get(1).path("direction").asText());
            assertEquals(json("[\"located in\",\"founded in\"]"),
                    open.get(0).payload().path("forms"));

            // 采纳 map_to：两条形状都成了人的绑定，绑到 located_in，各按自己的方向
            UUID id = agent.adopt(f.kb, "map_to", "located_in",
                    new AdoptEdits(null, null, null, null, null, null), f.user);
            assertEquals(f.locatedIn, id);
            List<Binding> bindings = phraseBindings.bindings(f.kb);
            for (String[] pd : new String[][] {{"located in", "forward"},
                    {"founded in", "reverse"}}) {
                Binding b = bindings.stream().filter(x -> x.phrase().equals(pd[0])).findFirst()
                        .orElseThrow(() -> new IllegalStateException("the binding exists"));
                assertEquals("bound", b.status(), pd[0]);
                assertEquals("person", b.decidedBy(), pd[0]);
                assertEquals(f.locatedIn, b.relationTypeId(), pd[0]);
                assertEquals(pd[1], b.direction(), pd[0]);
            }
            assertTrue(ontology.openProposals(f.kb).isEmpty(), "the map_to proposal is adopted");
            // 再来一轮：两条都绑上了——不问
            agent.proposeWith(f.kb, false);
            assertEquals(2, f.model.requests().size());
        } finally {
            cleanup(f);
        }
    }

    /** a_base_with_no_questions_gets_them_proposed_and_the_report_counts_them。 */
    @Test
    void aBaseWithNoQuestionsGetsThemProposedAndTheReportCountsThem() throws Exception {
        Fx f = seed(List.of(
                // 本体代理发现库里一条问题都没有：先提问题（这一轮的第一次调用）
                "{\"q\": ["
                        + "{\"question\": \"Where is each organization located?\", \"classes\": [\"organization\", \"place\"], \"properties\": [\"located_in\", \"made_up\"]},"
                        + "{\"question\": \"Why?\", \"classes\": [], \"properties\": []}"
                        + "]}",
                // 然后照旧按说法提本体
                "{\"p\": [], \"existing\": []}",
                // 界面上再叫一次提问题：同一句不重复，新的进来
                "{\"q\": ["
                        + "{\"question\": \"where is each ORGANIZATION located?\", \"classes\": [], \"properties\": []},"
                        + "{\"question\": \"Which organizations were founded in each place?\", \"classes\": [\"organization\"], \"properties\": []}"
                        + "]}"));
        try {
            decideNone(f);

            agent.proposeWith(f.kb, false);
            assertEquals(2, f.model.requests().size(),
                    "questions first, then the ontology round");
            String text = userText(f.model, 0);
            assertTrue(text.contains("Most frequent statement shapes"), text);
            assertTrue(text.contains("\"founded in\""), "the shapes are offered: " + text);
            assertTrue(text.contains("Acme (organization)"),
                    "the connected entities are offered: " + text);
            List<QuestionRow> qs = questions.list(f.kb);
            assertEquals(1, qs.size(), "the short one is dropped: " + qs);
            assertEquals("agent", qs.get(0).origin());
            assertEquals("proposed", qs.get(0).status());
            assertEquals(json("{\"classes\": [\"organization\", \"place\"], "
                    + "\"properties\": [\"located_in\"]}"), qs.get(0).needs(),
                    "keys the ontology lacks are dropped from needs");
            // 提出来的还没接受：本体那一轮没有问题可用
            assertTrue(userText(f.model, 1).contains("(none written yet"));

            // 界面上点一次提问题：同一句不重复（大小写不算差别），新的进来
            int written = questionAgent.proposeQuestions(f.kb);
            assertEquals(1, written, "the duplicate is skipped, the new one written");
            assertEquals(3, f.model.requests().size());
            String third = userText(f.model, 2);
            assertTrue(third.contains("do not repeat them"), third);
            assertTrue(third.contains("Where is each organization located?"), third);

            // 接受一条、问过一条：报告数得出来
            List<QuestionRow> after = questions.list(f.kb);
            // 新的在前（list 按创建倒序）：采纳两条，问过第一条
            questions.setStatus(f.kb, after.get(after.size() - 1).id(), "accepted");
            questions.setStatus(f.kb, after.get(0).id(), "accepted");
            assertTrue(questions.recordResult(f.kb, after.get(after.size() - 1).id(),
                    json("{\"answered\": true, \"judged_by\": \"shape\"}")));
            var r = questionMapper.selectQuestionReport(f.kb);
            assertEquals(2, r.accepted());
            assertEquals(0, r.proposed());
            assertEquals(1, r.checked());
            assertEquals(1, r.answered());
            var p = ontologyMapper.selectAgentProposalReport(f.kb);
            assertEquals(0, p.open());
            assertEquals(0, p.adopted());
            assertEquals(0, p.adoptedEdited());
            assertEquals(0, p.rejected());
        } finally {
            cleanup(f);
        }
    }
}
