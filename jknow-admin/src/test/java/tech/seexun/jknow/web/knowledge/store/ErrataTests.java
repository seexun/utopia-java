package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ErrataService;
import tech.seexun.jknow.store.ErrataService.ActionInput;
import tech.seexun.jknow.store.ErrataService.Add;
import tech.seexun.jknow.store.ErrataService.Keep;
import tech.seexun.jknow.store.ErrataService.Recorded;
import tech.seexun.jknow.store.ErrataService.Retract;
import tech.seexun.jknow.store.ErrataService.Revise;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.ErrataHeldItem;
import tech.seexun.jknow.store.mapper.ErrataMapper.Candidate;

/**
 * 移植自 crates/utopia-store/src/errata_tests.rs（574 行，5 例）——勘误（0044 决定 7）：
 * 结构先报、撤销经得住物化（物化与蕴含的 due 查询跳过被勘误撤过的（陈述, 属性））、
 * 闸门留给人、改与加带着原话。
 */
class ErrataTests extends HeartStoreTestBase {

    private static final String TEXT =
            "Acme is based in London. Jane Roe runs Acme. Acme was founded in 1999. Zeta Corp is based in London.";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ErrataService errata;
    @Autowired
    TypedMaterializeService materialize;

    record Fx(UUID org, UUID kb, UUID doc, UUID chunk, UUID organization, UUID place, UUID person,
              UUID basedIn, UUID ceo, UUID founded, UUID acme, UUID london, UUID paris, UUID jane,
              UUID nobody, UUID user) {
    }

    /** 一个库：organization/place/person；based_in、ceo（只许一个）、founded（日期属性）。 */
    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID place = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID basedIn = Uuids.nowV7();
        UUID ceo = Uuids.nowV7();
        UUID founded = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID london = Uuids.nowV7();
        UUID paris = Uuids.nowV7();
        UUID jane = Uuids.nowV7();
        UUID nobody = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'errata-test')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'errata-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'errata-test')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'reviewer')
                """, user, org, user + "@errata.test");
        jdbc.update("""
                INSERT INTO documents(id,kb_id,filename,sha256,doc_time)
                 VALUES (?, ?, 'acme.txt', 'x', '2020-01-01T00:00:00Z')
                """, doc, kb);
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, ?)",
                chunk, kb, doc, TEXT);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES
                    (?, ?, 'organization', 'Organization', '#000', 'circle'),
                    (?, ?, 'place', 'Place', '#000', 'circle'),
                    (?, ?, 'person', 'Person', '#000', 'circle')
                """, organization, kb, place, kb, person, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,functional,datatype) VALUES
                    (?, ?, 'based_in', 'based in', 'relation', 'state', false, NULL),
                    (?, ?, 'ceo', 'chief executive', 'relation', 'state', true, NULL),
                    (?, ?, 'founded', 'founded', 'attribute', 'eternal', false, 'date')
                """, basedIn, kb, ceo, kb, founded, kb);
        jdbc.update("""
                INSERT INTO relation_type_domains(relation_type_id,entity_type_id) VALUES
                    (?, ?), (?, ?), (?, ?)
                """, basedIn, organization, ceo, organization, founded, organization);
        jdbc.update("""
                INSERT INTO relation_type_ranges(relation_type_id,entity_type_id) VALUES
                    (?, ?), (?, ?)
                """, basedIn, place, ceo, person);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id) VALUES
                    (?, ?, 'Acme', ?), (?, ?, 'London', ?),
                    (?, ?, 'Paris', ?), (?, ?, 'Jane Roe', ?),
                    (?, ?, 'Nobody', ?)
                """, acme, kb, organization, london, kb, place, paris, kb, place, jane, kb, person,
                nobody, kb, person);
        return new Fx(org, kb, doc, chunk, organization, place, person, basedIn, ceo, founded,
                acme, london, paris, jane, nobody, user);
    }

    private void cleanup(Fx f) {
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb().toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /** 一条开放陈述加一条从它算出的类型化行，证据落在这份文档上。返回 (陈述, 类型化行)。 */
    private UUID[] typed(Fx f, UUID subject, String phrase, UUID property, UUID object,
                         String value) {
        UUID s = Uuids.nowV7();
        UUID t = Uuids.nowV7();
        JsonNode valueNode = value == null ? null
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("value", value);
        jdbc.update("""
                INSERT INTO facts(id,kb_id,subject_id,object_id,object_value,layer,phrase)
                 VALUES (?,?,?,?,?::jsonb,'open',?)
                """, s, f.kb(), subject, object, value == null ? null : "{\"value\": \"" + value + "\"}", phrase);
        jdbc.update("""
                INSERT INTO facts(id,kb_id,subject_id,predicate_id,object_id,object_value,layer,from_statement_id)
                 VALUES (?,?,?,?,?,?::jsonb,'typed',?)
                """, t, f.kb(), subject, property, object,
                value == null ? null : "{\"value\": \"" + value + "\"}", s);
        jdbc.update("INSERT INTO typed_fact_sources(fact_id,statement_id) VALUES (?, ?)", t, s);
        for (UUID fact : new UUID[]{s, t}) {
            jdbc.update("""
                    INSERT INTO fact_evidence(fact_id,chunk_id,quote,document_id,doc_version)
                     VALUES (?,?,?, ?,1)
                    """, fact, f.chunk(), phrase, f.doc());
        }
        return new UUID[]{s, t};
    }

    private boolean live(UUID fact) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT invalidated_at IS NULL FROM facts WHERE id = ?", Boolean.class, fact));
    }

    private Object[] statusOf(UUID action) {
        return jdbc.queryForObject("SELECT status, detail FROM errata_actions WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)}, action);
    }

    private ActionInput input(Fx f, UUID run, Candidate c, Object proposed, String quote) {
        return new ActionInput(run, f.doc(), c, (ErrataService.Proposed) proposed, "because",
                quote, TEXT);
    }

    @Test
    void structureFlagsFactsAndTheFlaggedComeFirst() {
        Fx f = seed();
        try {
            UUID[] finePair = typed(f, f.acme(), "based in", f.basedIn(), f.london(), null);
            UUID[] absentPair = typed(f, f.acme(), "based in", f.basedIn(), f.paris(), null);
            UUID[] domainPair = typed(f, f.jane(), "based in", f.basedIn(), f.london(), null);
            UUID[] rangePair = typed(f, f.acme(), "run by", f.ceo(), f.london(), null);
            UUID[] noDatePair = typed(f, f.acme(), "founded", f.founded(), null, "the nineties");
            UUID[] datedPair = typed(f, f.acme(), "founded in", f.founded(), null, "1999");
            UUID[] nobodyPair = typed(f, f.acme(), "run by", f.ceo(), f.nobody(), null);
            List<Candidate> c = errata.candidates(f.kb(), f.doc());
            var flags = new java.util.HashMap<UUID, String>();
            for (Candidate x : c) {
                flags.put(x.factId(), x.flag());
            }
            assertNull(flags.get(finePair[1]));
            assertNull(flags.get(datedPair[1]));
            assertEquals("name_absent", flags.get(absentPair[1]));
            assertEquals("domain", flags.get(domainPair[1]));
            assertEquals("range", flags.get(rangePair[1]));
            assertEquals("no_date", flags.get(noDatePair[1]));
            assertEquals("name_absent", flags.get(nobodyPair[1]));
            // 报了的在前，没报的在后（第一组的 dated/fine 无 flag；第二组 nobodyPair 有 name_absent）
            List<Boolean> order = c.stream().map(x -> x.flag() != null).toList();
            assertEquals(List.of(true, true, true, true, true, false, false), order);
            Candidate first = c.get(0);
            assertEquals("Acme", first.subject());
            assertEquals("based_in", first.property());
            assertEquals("based in", first.quote());
            assertTrue(first.statementId() != null);
            // 这份文档待看；看过一条（keep）之后它还待看，全看完才不
            assertEquals(List.of(f.doc()), errata.documentsDue(f.kb(), 10));
            UUID run = errata.startRun(f.kb(), f.doc(), 5, 2);
            Recorded r = errata.record(f.kb(), input(f, run, first, new Keep(), null));
            assertEquals("applied", r.status());
            assertEquals(6, errata.candidates(f.kb(), f.doc()).size());
            assertEquals(List.of(f.doc()), errata.documentsDue(f.kb(), 10));
            // 抽完了、一条类型化行都没有的文档也该看一次（agent 只能加）；看过一次就不再排
            UUID emptyDoc = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO documents(id,kb_id,filename,sha256,graph_status) VALUES (?, ?, 'empty.txt', 'y', 'done')",
                    emptyDoc, f.kb());
            assertEquals(List.of(f.doc(), emptyDoc), errata.documentsDue(f.kb(), 10));
            UUID emptyRun = errata.startRun(f.kb(), emptyDoc, 0, 0);
            assertEquals(1L, errata.runsOf(emptyDoc));
            errata.finishRun(emptyRun, 1, null, null);
            assertEquals(List.of(f.doc()), errata.documentsDue(f.kb(), 10));
            errata.finishRun(run, 1, 100L, 20L);
            var usage = jdbc.queryForMap(
                    "SELECT requests, prompt_tokens FROM errata_runs WHERE id = ?", run);
            assertEquals(1, ((Number) usage.get("requests")).intValue());
            assertEquals(100, ((Number) usage.get("prompt_tokens")).intValue());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void aRetractionSticksThroughMaterialisation() {
        Fx f = seed();
        try {
            // 一条陈述 Acme —based in→ Paris，签名绑到 based_in；物化算出类型化行
            UUID s = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase)
                     VALUES (?, ?, ?, ?, 'open', 'based in')
                    """, s, f.kb(), f.acme(), f.paris());
            jdbc.update("""
                    INSERT INTO fact_evidence(fact_id,chunk_id,quote,document_id,doc_version)
                     VALUES (?, ?, 'based in', ?, 1)
                    """, s, f.chunk(), f.doc());
            jdbc.update("""
                    INSERT INTO phrase_bindings (id, kb_id, phrase, subject_type_id, object_type_id, object_is_value,
                                                  relation_type_id, direction, status)
                     VALUES (?, ?, 'based in', ?, ?, false, ?, 'forward', 'bound')
                    """, Uuids.nowV7(), f.kb(), f.organization(), f.place(), f.basedIn());
            var o = materialize.materialize(f.kb());
            assertEquals(1L, o.added());
            List<Candidate> c = errata.candidates(f.kb(), f.doc());
            assertEquals(1, c.size());
            assertEquals("name_absent", c.get(0).flag(), "Paris is not in the document");
            assertEquals(s, c.get(0).statementId());
            UUID typedRow = c.get(0).factId();
            UUID run = errata.startRun(f.kb(), f.doc(), 1, 0);
            Recorded r = errata.record(f.kb(), input(f, run, c.get(0), new Retract(),
                    "Acme is based in London"));
            assertEquals("applied", r.status());
            assertNull(r.detail());
            assertFalse(live(typedRow));
            // 陈述还活着、绑定还在：物化再跑，那一对不再算出来（0044 决定 7——撤销要站得住）
            o = materialize.materialize(f.kb());
            assertEquals(0L, o.added());
            assertEquals(0L, o.merged());
            assertEquals(0L, o.retired());
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE kb_id = ? AND layer = 'typed' AND invalidated_at IS NULL",
                    Long.class, f.kb()));
            assertTrue(errata.documentsDue(f.kb(), 10).isEmpty(), "nothing left to look at");
            // 引文不是原话的撤：记下来，不执行
            UUID[] other = typed(f, f.acme(), "based in", f.basedIn(), f.london(), null);
            List<Candidate> again = errata.candidates(f.kb(), f.doc());
            Recorded refused = errata.record(f.kb(), input(f, run, again.get(0), new Retract(),
                    "Acme is based in Paris"));
            assertEquals("refused", refused.status());
            assertEquals("the quote is not in the document", refused.detail());
            assertTrue(live(other[1]));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void aRetractionSomethingRestsOnIsHeldUntilAPersonDecides() {
        Fx f = seed();
        try {
            UUID[] t1 = typed(f, f.acme(), "based in", f.basedIn(), f.paris(), null);
            UUID[] t2 = typed(f, f.acme(), "run by", f.ceo(), f.nobody(), null);
            // 两条各有一条派生靠着
            UUID rule = Uuids.nowV7();
            jdbc.update("INSERT INTO rules(id,kb_id,predicate_id,kind) VALUES (?, ?, ?, 'transitive')",
                    rule, f.kb(), f.basedIn());
            for (Object[] row : new Object[][]{{t1[1], f.london()}, {t2[1], f.paris()}}) {
                UUID d = Uuids.nowV7();
                jdbc.update("""
                        INSERT INTO derived_facts(id,kb_id,subject_id,predicate_id,object_id,rule_id)
                         VALUES (?, ?, ?, ?, ?, ?)
                        """, d, f.kb(), f.acme(), f.basedIn(), row[1], rule);
                jdbc.update(
                        "INSERT INTO fact_derivations(derived_fact_id,premise_fact_id,seq) VALUES (?, ?, 0)",
                        d, row[0]);
            }
            List<Candidate> c = errata.candidates(f.kb(), f.doc());
            UUID run = errata.startRun(f.kb(), f.doc(), 2, 0);
            java.util.List<UUID> heldIds = new java.util.ArrayList<>();
            for (Candidate cand : c) {
                Recorded r = errata.record(f.kb(), input(f, run, cand, new Retract(),
                        "Acme is based in London"));
                assertEquals("held", r.status());
                assertEquals("derived 1", r.detail());
                heldIds.add(r.id());
            }
            assertTrue(live(t1[1]) && live(t2[1]), "held means untouched");
            List<ErrataHeldItem> queue = errata.held(f.kb(), 10, 0);
            assertEquals(2, queue.size());
            assertEquals("acme.txt", queue.get(0).document());
            assertEquals("derived 1", queue.get(0).detail());
            assertEquals("Acme", queue.get(0).proposed().get("subject").asText());
            assertEquals(2L, errata.waiting(f.kb()).count());
            // 人否第一笔：事实还在；批第二笔：撤了
            assertTrue(errata.decideHeld(f.kb(), heldIds.get(0), false, f.user()));
            assertTrue(errata.decideHeld(f.kb(), heldIds.get(1), true, f.user()));
            assertFalse(errata.decideHeld(f.kb(), heldIds.get(1), true, f.user()), "answered once");
            assertEquals("rejected", statusOf(heldIds.get(0))[0]);
            assertEquals("applied", statusOf(heldIds.get(1))[0]);
            assertTrue(live(c.get(0).factId()));
            assertFalse(live(c.get(1).factId()));
            assertEquals(0L, errata.waiting(f.kb()).count());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void anUnflaggedFactIsRetractedOnlyByAPerson() {
        Fx f = seed();
        try {
            UUID[] fine = typed(f, f.acme(), "based in", f.basedIn(), f.london(), null);
            List<Candidate> c = errata.candidates(f.kb(), f.doc());
            assertNull(c.get(0).flag());
            UUID run = errata.startRun(f.kb(), f.doc(), 0, 1);
            Recorded r = errata.record(f.kb(), input(f, run, c.get(0), new Retract(),
                    "Acme is based in London"));
            assertEquals("held", r.status());
            assertEquals("unflagged", r.detail());
            assertTrue(live(fine[1]));
            assertEquals("unflagged", errata.held(f.kb(), 10, 0).get(0).detail());
            assertTrue(errata.decideHeld(f.kb(), r.id(), true, f.user()));
            assertFalse(live(fine[1]));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void aRevisionAndAnAdditionWriteTypedFactsWithTheDocumentsWords() {
        Fx f = seed();
        try {
            UUID[] wrongCeo = typed(f, f.acme(), "run by", f.ceo(), f.london(), null);
            List<Candidate> c = errata.candidates(f.kb(), f.doc());
            assertEquals("range", c.get(0).flag());
            UUID run = errata.startRun(f.kb(), f.doc(), 1, 0);
            // 改宾语：London → Jane Roe。旧行撤了，新行活着、带引文
            Recorded r = errata.record(f.kb(), input(f, run, c.get(0),
                    new Revise(null, "Jane Roe"), "Jane Roe runs Acme"));
            assertEquals("applied", r.status());
            assertNull(r.detail());
            UUID newFact = r.newFactId();
            assertTrue(newFact != null, "a revision writes a row");
            assertFalse(live(wrongCeo[1]));
            var row = jdbc.queryForMap(
                    "SELECT subject_id, predicate_id, object_id FROM facts WHERE id = ?", newFact);
            assertEquals(f.acme(), row.get("subject_id"));
            assertEquals(f.ceo(), row.get("predicate_id"));
            assertEquals(f.jane(), row.get("object_id"));
            var quote = jdbc.queryForMap(
                    "SELECT chunk_id, quote FROM fact_evidence WHERE fact_id = ?", newFact);
            assertEquals(f.chunk(), quote.get("chunk_id"));
            assertEquals("Jane Roe runs Acme", quote.get("quote"));
            // 加一条日期属性：值落下
            r = errata.record(f.kb(), input(f, run, null,
                    new Add("Acme", "founded", "1999"), "founded in 1999"));
            assertEquals("applied", r.status());
            String value = jdbc.queryForObject(
                    "SELECT object_value #>> '{value}' FROM facts WHERE id = ?", String.class,
                    r.newFactId());
            assertEquals("1999", value);
            // 名字不在库里：勘误不造东西
            r = errata.record(f.kb(), input(f, run, null,
                    new Add("Acme", "ceo", "Bob"), "runs Acme"));
            assertEquals("refused", r.status());
            assertEquals("no thing named \"Bob\" and the document does not name it", r.detail());
            // 文档提到的新东西：建一个带名字的实体，事实落下
            r = errata.record(f.kb(), input(f, run, null,
                    new Add("Zeta Corp", "based_in", "London"), "Zeta Corp is based in London"));
            assertEquals("applied", r.status());
            assertNull(r.detail());
            var zeta = jdbc.queryForMap("""
                    SELECT e.canonical_name, f.object_id FROM facts f
                      JOIN entities e ON e.id = f.subject_id WHERE f.id = ?
                    """, r.newFactId());
            assertEquals("Zeta Corp", zeta.get("canonical_name"));
            assertEquals(f.london(), zeta.get("object_id"));
            // 只许一个值的谓词已经有 Jane：再加一个 London 会开出违规，留给人
            r = errata.record(f.kb(), input(f, run, null,
                    new Add("Acme", "ceo", "London"), "based in London"));
            assertEquals("held", r.status());
            assertEquals("contradiction chief executive", r.detail());
            // 属性不存在
            r = errata.record(f.kb(), input(f, run, null,
                    new Add("Acme", "owner", "Jane Roe"), "runs Acme"));
            assertEquals("refused", r.status());
            assertEquals("no property with key \"owner\"", r.detail());
        } finally {
            cleanup(f);
        }
    }
}
