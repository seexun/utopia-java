package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ErrataService;
import tech.seexun.jknow.store.ErrataService.ActionInput;
import tech.seexun.jknow.store.ErrataService.Recorded;
import tech.seexun.jknow.store.ErrataService.Revise;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.entity.GraphEdge;
import tech.seexun.jknow.store.mapper.ErrataMapper.Candidate;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_moment_marks_its_state.rs（1277 行，10 例）——只带一个日期的陈述
 * 绑在状态属性上（#966）。「Lin Zhao joined Meridian Systems on 2023-06-01」的日期照 0031
 * 写事件的办法写在两端；绑到 works_for（状态）之后照抄就是一段任何时刻都不成立的状态。
 * 绑定现在说那个日期标的是什么（phrase_bindings.marks）：start 从那天起、end 到那天为止、
 * none/未说不算类型化行。照抄写下的空段由物化作废重算，人写的、人改成一刻的不碰。
 */
class AMomentMarksItsStateTests extends HeartStoreTestBase {

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    /** 2023-06-01，日精度。 */
    private static OffsetDateTime day() {
        return t("2023-06-01T00:00:00Z");
    }

    /** 两端都是那一天：时间解析写一刻的样子。 */
    private static Validity moment() {
        return new Validity(day(), "day", day(), "day", null, null);
    }

    /** 只知道终点。 */
    private static Validity ending(OffsetDateTime at) {
        return new Validity(null, null, at, "day", null, null);
    }

    private static Validity starting(OffsetDateTime at, String precision) {
        return new Validity(at, precision, null, null, null, null);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    GraphWriteService writes;
    @Autowired
    GraphService graph;
    @Autowired
    TemporalService temporal;
    @Autowired
    ErrataService errata;

    /** 一行类型化事实：(起, 止, 来源数)。 */
    private record Span(OffsetDateTime from, OffsetDateTime to, long sources) {
    }

    private static Span span(OffsetDateTime from, OffsetDateTime to, long sources) {
        return new Span(from, to, sources);
    }

    private class Base {
        final UUID org;
        final UUID kb;
        final UUID person;
        final UUID company;
        final UUID worksFor;
        final UUID acquired;
        final UUID lin;
        final UUID meridian;
        final UUID aster;

        Base() {
            UUID org = Uuids.nowV7();
            UUID ws = Uuids.nowV7();
            UUID kb = Uuids.nowV7();
            UUID person = Uuids.nowV7();
            UUID company = Uuids.nowV7();
            UUID worksFor = Uuids.nowV7();
            UUID acquired = Uuids.nowV7();
            UUID lin = Uuids.nowV7();
            UUID meridian = Uuids.nowV7();
            UUID aster = Uuids.nowV7();
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'a-moment-marks-its-state')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'a-moment-marks-its-state')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'a-moment-marks-its-state')",
                    kb, ws);
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label, color, shape) VALUES
                        (?, ?, 'person', 'person', '#7fd0ff', 'circle'),
                        (?, ?, 'organization', 'organization', '#7fd0ff', 'circle')
                    """, person, kb, company, kb);
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal) VALUES
                        (?, ?, 'works_for', 'works for', 'state'),
                        (?, ?, 'acquired', 'acquired', 'event')
                    """, worksFor, kb, acquired, kb);
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES
                        (?, ?, ?, 'Lin Zhao'), (?, ?, ?, 'Meridian Systems'), (?, ?, ?, 'Aster Labs')
                    """, lin, kb, person, meridian, kb, company, aster, kb, company);
            this.org = org;
            this.kb = kb;
            this.person = person;
            this.company = company;
            this.worksFor = worksFor;
            this.acquired = acquired;
            this.lin = lin;
            this.meridian = meridian;
            this.aster = aster;
        }

        UUID statement(String phrase, OffsetDateTime from, OffsetDateTime to) {
            return statementIn(phrase, from, to, "day");
        }

        UUID statementIn(String phrase, OffsetDateTime from, OffsetDateTime to, String precision) {
            return statementTo(phrase, meridian, from, to, precision);
        }

        UUID statementTo(String phrase, UUID object, OffsetDateTime from, OffsetDateTime to,
                         String precision) {
            UUID id = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                        valid_from, valid_from_precision, valid_from_grade,
                                        valid_to, valid_to_precision)
                     VALUES (?, ?, ?, ?, 'open', ?, 0.9,
                            ?, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE ? END,
                            CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'A' END,
                            ?, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE ? END)
                    """, id, kb, lin, object, phrase, from, from, precision, from, to, to, precision);
            return id;
        }

        void bind(String phrase, String marks) {
            bindTo(phrase, worksFor, marks);
        }

        void bindTo(String phrase, UUID property, String marks) {
            jdbc.update("""
                    INSERT INTO phrase_bindings
                         (id, kb_id, phrase, subject_type_id, object_type_id, object_is_value,
                          relation_type_id, direction, status, marks)
                     VALUES (?, ?, ?, ?, ?, false, ?, 'forward', 'bound', ?)
                     ON CONFLICT (kb_id, phrase, subject_type_id, object_type_id, object_is_value)
                     DO UPDATE SET marks = EXCLUDED.marks
                    """, Uuids.nowV7(), kb, phrase, person, company, property, marks);
        }

        /** 一行两端都是那一天的类型化 works_for 行：#966 之前照抄写下的样子。 */
        UUID emptyRow(UUID object, UUID statement) {
            UUID id = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, layer,
                                        valid_from, valid_from_precision, valid_to, valid_to_precision,
                                        confidence, from_statement_id)
                     VALUES (?, ?, ?, ?, ?, 'typed', ?, 'day', ?, 'day', 0.9, ?)
                    """, id, kb, lin, worksFor, object, day(), day(), statement);
            return id;
        }

        /** Lin Zhao 与 Meridian 之间活着的类型化行：(起, 止, 来源数)，按起点排。 */
        List<Span> rows() {
            return jdbc.query("""
                    SELECT t.valid_from, t.valid_to,
                            (SELECT count(*) FROM typed_fact_sources s WHERE s.fact_id = t.id) AS sources
                       FROM facts t
                      WHERE t.kb_id = ? AND t.layer = 'typed' AND t.invalidated_at IS NULL
                        AND t.object_id = ?
                      ORDER BY t.valid_from NULLS FIRST, t.valid_to NULLS LAST
                    """, (rs, i) -> new Span(rs.getObject(1, OffsetDateTime.class),
                    rs.getObject(2, OffsetDateTime.class), rs.getLong(3)), kb, meridian);
        }

        /** 世界轴上某一刻，谁 works_for Meridian。 */
        List<String> atMeridian(String at) {
            var n = graph.neighborhood(kb, meridian, 1, t(at), null);
            return n.edges().stream()
                    .filter(e -> meridian.equals(e.target()) && "works_for".equals(e.predicate()))
                    .filter(e -> n.nodes().stream().anyMatch(x -> x.id().equals(e.source())))
                    .map(e -> n.nodes().stream().filter(x -> x.id().equals(e.source())).findFirst()
                            .orElseThrow().name())
                    .toList();
        }

        /** 不看世界轴时，Meridian 身上的边：(id, 起, 止)。 */
        List<Object[]> edges() {
            var n = graph.neighborhood(kb, meridian, 1, null, null);
            return n.edges().stream()
                    .filter(e -> meridian.equals(e.target()))
                    .map(e -> new Object[]{e.id(), e.validFrom(), e.validTo()})
                    .toList();
        }

        Object[] live(UUID fact) {
            return jdbc.queryForObject(
                    "SELECT invalidated_at IS NULL, valid_to FROM facts WHERE id = ?",
                    (rs, i) -> new Object[]{rs.getBoolean(1), rs.getObject(2, OffsetDateTime.class)},
                    fact);
        }

        void remove() {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /** temporal.rs:1325-1342 list_conflicts 的读面（与 ReviewMapper conflicts 队列同一 SQL）。 */
    private record OpenConflict(UUID id, String reason) {
    }

    private List<OpenConflict> openConflicts(UUID kb) {
        return jdbc.query("""
                SELECT c.id, c.reason FROM fact_conflicts c
                  JOIN facts fo ON fo.id = c.old_fact_id
                  JOIN facts fn_ ON fn_.id = c.new_fact_id
                  JOIN entities os ON os.id = fo.subject_id
                  JOIN entities ns ON ns.id = fn_.subject_id
                  JOIN relation_types r ON r.id = fo.predicate_id
                 WHERE c.kb_id = ? AND c.status = 'open'
                 ORDER BY c.created_at DESC, c.id DESC LIMIT 10 OFFSET 0
                """, (rs, i) -> new OpenConflict(rs.getObject(1, UUID.class), rs.getString(2)), kb);
    }

    private static boolean refusedAsEmpty(AppException e) {
        return "empty_state_span".equals(e.code().orElse(null));
    }

    /** #966 的三种顺序：三种都是一段从 2023-06-01 起、至今开着的状态。 */
    @Test
    void aDateThatStartsAStateOpensItWhateverTheOrder() {
        String[][] cases = {{"A", "joined", "works for"}, {"B", "works for", "joined"},
                {"C", "joined"}};
        for (String[] c : cases) {
            String label = c[0];
            String[] phrases = java.util.Arrays.copyOfRange(c, 1, c.length);
            Base base = new Base();
            try {
                for (String phrase : phrases) {
                    boolean point = "joined".equals(phrase);
                    base.statement(phrase, day(), point ? day() : null);
                    base.bind(phrase, point ? "start" : null);
                }
                materialize.materialize(base.kb);
                assertEquals(List.of(span(day(), null, phrases.length)), base.rows(),
                        "case " + label + ": one row from 2023-06-01, every statement its source");
                for (String at : new String[]{"2023-06-01T12:00:00Z", "2023-07-01T00:00:00Z",
                        "2026-01-01T00:00:00Z"}) {
                    assertEquals(List.of("Lin Zhao"), base.atMeridian(at),
                            "case " + label + " at " + at);
                }
                assertEquals(Outcome.EMPTY, materialize.materialize(base.kb),
                        "case " + label + ": a second run changes nothing");
            } finally {
                base.remove();
            }
        }
    }

    /** 标结束的短语：把开着的那段关在那一天。先读到哪一句都一样。 */
    @Test
    void aDateThatEndsAStateClosesItThere() {
        OffsetDateTime left = t("2025-01-31T00:00:00Z");
        for (String[] order : new String[][]{{"works for", "left"}, {"left", "works for"}}) {
            Base base = new Base();
            try {
                for (String phrase : order) {
                    if ("left".equals(phrase)) {
                        base.statement(phrase, left, left);
                    } else {
                        base.statement(phrase, day(), null);
                    }
                }
                base.bind("works for", null);
                base.bind("left", "end");
                materialize.materialize(base.kb);
                assertEquals(List.of(span(day(), left, 2)), base.rows(),
                        java.util.Arrays.toString(order) + ": one span, closed where he left");
                assertEquals(List.of("Lin Zhao"), base.atMeridian("2024-06-01T00:00:00Z"),
                        java.util.Arrays.toString(order));
                assertTrue(base.atMeridian("2025-06-01T00:00:00Z").isEmpty(),
                        java.util.Arrays.toString(order) + ": not after he left");
                assertEquals(Outcome.EMPTY, materialize.materialize(base.kb));
            } finally {
                base.remove();
            }
        }
    }

    /** 标「都不是」的日期，和绑定还没说标什么的日期：不算类型化行。 */
    @Test
    void aDateThatMarksNeitherOrNothingYetComputesNoRow() {
        for (String marks : new String[]{"none", null}) {
            Base base = new Base();
            try {
                UUID seen = base.statement("was employed by", day(), day());
                base.bind("was employed by", marks);
                assertEquals(Outcome.EMPTY, materialize.materialize(base.kb), String.valueOf(marks));
                assertTrue(base.rows().isEmpty(), marks + ": no typed row");
                List<Object[]> edges = base.edges();
                assertEquals(1, edges.size());
                assertEquals(seen, edges.get(0)[0]);
                assertEquals(day(), edges.get(0)[1]);
                assertEquals(day(), edges.get(0)[2]);
            } finally {
                base.remove();
            }
        }
    }

    /** 之前照抄写下的空段：物化作废它（还在表里），陈述按绑定重算；人写的空段不碰。 */
    @Test
    void aRowThatHeldAtNoMomentIsComputedAgain() {
        Base base = new Base();
        try {
            UUID joined = base.statement("joined", day(), day());
            UUID works = base.statement("works for", day(), null);
            base.bind("joined", null);
            base.bind("works for", null);
            UUID computed = base.emptyRow(base.meridian, joined);
            jdbc.update(
                    "INSERT INTO typed_fact_sources (fact_id, statement_id) VALUES (?, ?), (?, ?)",
                    computed, joined, computed, works);
            UUID byHand = base.emptyRow(base.aster, null);
            assertTrue(base.atMeridian("2023-07-01T00:00:00Z").isEmpty(),
                    "the ledger as #966 found it: nobody works for Meridian");

            assertEquals(new Outcome(1, 1, 0, 0, 0, 0), materialize.materialize(base.kb));
            Object[] computedLive = base.live(computed);
            assertEquals(false, computedLive[0]);
            assertEquals(day(), computedLive[1]);
            assertEquals(List.of(span(day(), null, 1)), base.rows());
            assertEquals(List.of("Lin Zhao"), base.atMeridian("2023-07-01T00:00:00Z"));
            List<Object[]> edges = base.edges();
            assertTrue(edges.stream().anyMatch(e -> e[0].equals(joined)),
                    "joined has no typed row yet, so it shows as itself");
            assertTrue(edges.stream().noneMatch(e -> e[0].equals(works)));
            Object[] handLive = base.live(byHand);
            assertEquals(true, handLive[0]);
            assertEquals(day(), handLive[1]);

            base.bind("joined", "start");
            assertEquals(new Outcome(0, 0, 1, 0, 0, 0), materialize.materialize(base.kb));
            assertEquals(List.of(span(day(), null, 2)), base.rows());
            assertEquals(Outcome.EMPTY, materialize.materialize(base.kb));
        } finally {
            base.remove();
        }
    }

    /** 不变量：声明成状态的属性下两端相等的行写不进去——写入、改区间、关在起点都拒。 */
    @Test
    void aStateThatEndsWhereItStartsIsNeverWritten() {
        Base base = new Base();
        try {
            AppException refused = assertThrows(AppException.class, () ->
                    writes.insertFact(base.kb, base.lin, base.worksFor, base.meridian, moment(),
                            0.9f));
            assertTrue(refusedAsEmpty(refused), refused.toString());
            Validity sameDay = new Validity(t("2023-06-01T09:00:00Z"), "day",
                    t("2023-06-01T17:00:00Z"), "day", null, null);
            refused = assertThrows(AppException.class, () ->
                    writes.insertFact(base.kb, base.lin, base.worksFor, base.meridian, sameDay,
                            0.9f));
            assertTrue(refusedAsEmpty(refused), "equal once truncated to the day");
            writes.insertFact(base.kb, base.meridian, base.acquired, base.aster, moment(), 0.9f);
            var bare = writes.insertFact(base.kb, base.lin, null, base.aster, moment(), 0.9f);
            assertEquals(day(), base.live(bare.id())[1], "a row without a property keeps its date");

            var open = writes.insertFact(base.kb, base.lin, base.worksFor, base.meridian,
                    starting(day(), "day"), 0.9f);
            AppException corrected = assertThrows(AppException.class, () ->
                    temporal.correctInterval(open.id(), moment()));
            assertTrue(refusedAsEmpty(corrected), "corrected into an empty span");
            AppException closed = assertThrows(AppException.class, () ->
                    temporal.closeSuperseded(open.id(), day(), "day"));
            assertTrue(refusedAsEmpty(closed), "closed where it starts");
            Object[] openLive = base.live(open.id());
            assertEquals(true, openLive[0]);
            assertNull(openLive[1], "the open row is untouched");
            assertTrue(temporal.closeSuperseded(open.id(), t("2024-01-01T00:00:00Z"), "day") != null,
                    "closing after the start still works");
            var unnamed = writes.insertFact(base.kb, base.lin, null, base.meridian,
                    starting(day(), "day"), 0.9f);
            assertTrue(temporal.correctInterval(unnamed.id(), moment()) != null,
                    "a row without a property can be given a single date");
        } finally {
            base.remove();
        }
    }

    /** 护栏：人写的空段不并进后来的观察、也不被它关上；同日开始结束的关在那一天的尽头。 */
    @Test
    void aRowThatHoldsAtNoMomentNeitherAbsorbsNorCloses() {
        Base base = new Base();
        try {
            UUID empty = base.emptyRow(base.meridian, null);
            var open = writes.insertFact(base.kb, base.lin, base.worksFor, base.meridian,
                    starting(day(), "day"), 0.9f);
            assertTrue(open.created() && !open.id().equals(empty), "a new row, not merged into the empty one");
            OffsetDateTime left = t("2025-01-31T00:00:00Z");
            writes.insertFact(base.kb, base.lin, base.worksFor, base.meridian, ending(left), 0.9f);
            assertEquals(List.of(span(day(), day(), 0), span(day(), left, 0)), base.rows(),
                    "the open row is the one that closes");
            Object[] emptyLive = base.live(empty);
            assertEquals(true, emptyLive[0]);
            assertEquals(day(), emptyLive[1]);
        } finally {
            base.remove();
        }
        for (boolean startFirst : new boolean[]{true, false}) {
            Base b = new Base();
            try {
                Validity since = starting(day(), "day");
                Validity[] writesArr = startFirst
                        ? new Validity[]{since, ending(day())}
                        : new Validity[]{ending(day()), since};
                for (Validity v : writesArr) {
                    writes.insertFact(b.kb, b.lin, b.worksFor, b.meridian, v, 0.9f);
                }
                assertEquals(List.of(span(day(), t("2023-06-02T00:00:00Z"), 0)), b.rows(),
                        "start first: " + startFirst + ": one row, through that day");
            } finally {
                b.remove();
            }
        }
    }

    /** 勘误改一条之前写下的空段：新行接不了一段不成立的时间（验证时拒；升级前留下的批了也不撤旧行）。 */
    @Test
    void aRevisionCannotTakeATimeThatHoldsAtNoMoment() {
        Base base = new Base();
        try {
            String text = "Lin Zhao joined Aster Labs on 2023-06-01.";
            UUID doc = Uuids.nowV7();
            UUID chunk = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, 'offer.txt', 'x')",
                    doc, base.kb);
            jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                    chunk, base.kb, doc, text);
            UUID empty = base.emptyRow(base.meridian, null);
            UUID runId = errata.startRun(base.kb, doc, 1, 0);
            Candidate candidate = new Candidate(empty, null, base.worksFor, base.lin,
                    "Lin Zhao", "person", "works_for", "works for", base.meridian,
                    "Meridian Systems", "organization", "name_absent", null);
            Recorded recorded = errata.record(base.kb, new ActionInput(runId, doc, candidate,
                    new Revise(null, "Aster Labs"), "the document names Aster Labs",
                    "Lin Zhao joined Aster Labs", text));
            assertEquals("refused", recorded.status());
            assertEquals("a state cannot take a time whose start and end are equal", recorded.detail());
            Object[] emptyLive = base.live(empty);
            assertEquals(true, emptyLive[0]);
            assertEquals(day(), emptyLive[1]);

            UUID held = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO errata_actions (id, kb_id, run_id, document_id, fact_id, predicate_id,
                                                 action, reason, quote, proposed, status)
                     VALUES (?, ?, ?, ?, ?, ?, 'revise', 'held before the upgrade',
                             'Lin Zhao joined Aster Labs', ?::jsonb, 'held')
                    """, held, base.kb, runId, doc, empty, base.worksFor,
                    "{\"subject\": \"Lin Zhao\", \"property\": \"works_for\", \"object\": \"Aster Labs\","
                            + " \"subject_id\": \"" + base.lin + "\", \"predicate_id\": \"" + base.worksFor
                            + "\", \"object_id\": \"" + base.aster + "\", \"object_value\": null}");
            AppException refused = assertThrows(AppException.class,
                    () -> errata.decideHeld(base.kb, held, true, Uuids.nowV7()));
            assertTrue(refusedAsEmpty(refused), refused.toString());
            Object[] after = base.live(empty);
            assertEquals(true, after[0]);
            assertEquals(day(), after[1]);
            assertEquals("held", jdbc.queryForObject(
                    "SELECT status FROM errata_actions WHERE id = ?", String.class, held));

            // 一刻的事件改成状态属性：新行接过来的也是一段不成立的时间，同样拒
            var acquisition = writes.insertFact(base.kb, base.meridian, base.acquired, base.aster,
                    moment(), 0.9f);
            Candidate event = new Candidate(acquisition.id(), null, base.acquired, base.meridian,
                    "Meridian Systems", "organization", "acquired", "acquired", base.aster,
                    "Aster Labs", "organization", "name_absent", null);
            recorded = errata.record(base.kb, new ActionInput(runId, doc, event,
                    new Revise("works_for", null), "the document says an employment",
                    "Lin Zhao joined Aster Labs", text));
            assertEquals("refused", recorded.status());
            assertEquals("a state cannot take a time whose start and end are equal", recorded.detail());
            Object[] acqLive = base.live(acquisition.id());
            assertEquals(true, acqLive[0]);
            assertEquals(day(), acqLive[1]);
        } finally {
            base.remove();
        }
    }

    /** 规则没有 marks：只有起点的陈述照旧蕴含；只说了一刻的蕴含状态时读法未知，不算。 */
    @Test
    void aRuleReadsAStartDateButNotASingleDate() {
        Base base = new Base();
        try {
            base.statement("was hired by", day(), null);
            base.statement("signed with", day(), day());
            for (String phrase : new String[]{"was hired by", "signed with"}) {
                jdbc.update("""
                        INSERT INTO implication_rules
                             (id, kb_id, trigger, phrase, subject_type_id, object_type_id, object_is_value,
                              conclude_property_id, status, decided_by)
                         VALUES (?, ?, 'phrase', ?, ?, ?, false, ?, 'approved', 'person')
                        """, Uuids.nowV7(), base.kb, phrase, base.person, base.company,
                        base.worksFor);
            }
            Outcome outcome = materialize.materialize(base.kb);
            assertEquals(1L, outcome.implied(), outcome.toString());
            List<Object[]> rows = jdbc.query("""
                    SELECT valid_from, valid_to, implied FROM facts
                      WHERE kb_id = ? AND layer = 'typed' AND invalidated_at IS NULL
                    """, (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class),
                    rs.getObject(2, OffsetDateTime.class), rs.getBoolean(3)}, base.kb);
            assertEquals(1, rows.size());
            assertEquals(day(), rows.get(0)[0]);
            assertNull(rows.get(0)[1]);
            assertEquals(true, rows.get(0)[2]);
        } finally {
            base.remove();
        }
    }

    /** 人改成起止相等的行（#975 评审）：带着链接，看上去和照抄的一样，物化不作废它。 */
    @Test
    void aRowAPersonSetToASingleDateIsNotRetired() {
        Base base = new Base();
        try {
            UUID works = base.statement("works for", day(), null);
            base.bind("works for", null);
            UUID hired = base.statementTo("was hired by", base.aster, day(), null, "day");
            UUID signed = base.statement("signed with", day(), day());
            java.util.List<UUID> rules = new java.util.ArrayList<>();
            for (String phrase : new String[]{"was hired by", "signed with"}) {
                UUID rule = Uuids.nowV7();
                jdbc.update("""
                        INSERT INTO implication_rules
                             (id, kb_id, trigger, phrase, subject_type_id, object_type_id, object_is_value,
                              conclude_property_id, status, decided_by)
                         VALUES (?, ?, 'phrase', ?, ?, ?, false, ?, 'approved', 'person')
                        """, rule, base.kb, phrase, base.person, base.company, base.worksFor);
                rules.add(rule);
            }
            materialize.materialize(base.kb);
            UUID typed = jdbc.queryForObject(
                    "SELECT fact_id FROM typed_fact_sources WHERE statement_id = ?", UUID.class, works);
            UUID implied = jdbc.queryForObject(
                    "SELECT fact_id FROM implied_fact_sources WHERE statement_id = ?", UUID.class, hired);
            assertFalse(typed.equals(implied));
            // 人把两行都改成那一天：不变量出现之前 correct_interval 就这样写，行带着它的来源
            for (UUID row : new UUID[]{typed, implied}) {
                jdbc.update("UPDATE facts SET valid_to = valid_from, valid_to_precision = valid_from_precision"
                        + " WHERE id = ?", row);
            }
            UUID copied = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, layer,
                                        valid_from, valid_from_precision, valid_to, valid_to_precision,
                                        confidence, implied)
                     VALUES (?, ?, ?, ?, ?, 'typed', ?, 'day', ?, 'day', 0.9, true)
                    """, copied, base.kb, base.lin, base.worksFor, base.meridian, day(), day());
            jdbc.update(
                    "INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id) VALUES (?, ?, ?)",
                    copied, rules.get(1), signed);

            Outcome outcome = materialize.materialize(base.kb);
            assertEquals(1L, outcome.retired());
            assertEquals(0L, outcome.added());
            assertEquals(0L, outcome.implied());
            assertEquals(false, base.live(copied)[0]);
            assertEquals(day(), base.live(copied)[1]);
            assertEquals(true, base.live(typed)[0]);
            assertEquals(day(), base.live(typed)[1]);
            assertEquals(true, base.live(implied)[0]);
            assertEquals(day(), base.live(implied)[1]);
            assertEquals(Outcome.EMPTY, materialize.materialize(base.kb));
        } finally {
            base.remove();
        }
    }

    /** 终点说的是一个时段（#975）：从时段里开始的行关在时段的尽头。先读到哪一句都一样。 */
    @Test
    void aCoarseEndClosesARowThatStartsInsideItsPeriod() {
        OffsetDateTime march = t("2024-03-01T00:00:00Z");
        OffsetDateTime year = t("2024-01-01T00:00:00Z");
        OffsetDateTime nextYear = t("2025-01-01T00:00:00Z");
        for (String[] order : new String[][]{{"works for", "left"}, {"left", "works for"}}) {
            Base base = new Base();
            try {
                for (String phrase : order) {
                    if ("left".equals(phrase)) {
                        base.statementIn(phrase, year, year, "year");
                    } else {
                        base.statement(phrase, march, null);
                    }
                }
                base.bind("works for", null);
                base.bind("left", "end");
                materialize.materialize(base.kb);
                assertEquals(List.of(span(march, nextYear, 2)), base.rows(),
                        java.util.Arrays.toString(order) + ": one span, closed at the end of 2024");
                assertEquals(List.of("Lin Zhao"), base.atMeridian("2024-06-01T00:00:00Z"),
                        java.util.Arrays.toString(order));
                assertTrue(base.atMeridian("2025-06-01T00:00:00Z").isEmpty(),
                        java.util.Arrays.toString(order) + ": not still working there in 2025");
                assertEquals(Outcome.EMPTY, materialize.materialize(base.kb));
            } finally {
                base.remove();
            }
        }
    }

    /** 同一个时段的终点，对时段之外开始的行照旧：各自一行，开着的那段照旧开着。 */
    @Test
    void aCoarseEndLeavesRowsOutsideItsPeriodAsBefore() {
        OffsetDateTime year = t("2024-01-01T00:00:00Z");
        Validity leftIn2024 = new Validity(null, null, year, "year", null, null);
        Object[][] cases = {
                {t("2020-01-01T00:00:00Z"), List.of(span(t("2020-01-01T00:00:00Z"), year, 0))},
                {t("2025-06-01T00:00:00Z"), List.of(span(null, year, 0),
                        span(t("2025-06-01T00:00:00Z"), null, 0))},
        };
        for (Object[] c : cases) {
            OffsetDateTime start = (OffsetDateTime) c[0];
            @SuppressWarnings("unchecked")
            List<Span> expected = (List<Span>) c[1];
            for (boolean startFirst : new boolean[]{true, false}) {
                Base base = new Base();
                try {
                    Validity since = starting(start, "day");
                    Validity[] sequence = startFirst
                            ? new Validity[]{since, leftIn2024}
                            : new Validity[]{leftIn2024, since};
                    for (Validity v : sequence) {
                        writes.insertFact(base.kb, base.lin, base.worksFor, base.meridian, v, 0.9f);
                    }
                    assertEquals(expected, base.rows(),
                            "from " + start + ", start first: " + startFirst);
                } finally {
                    base.remove();
                }
            }
        }
    }

    /** 两个值同一刻开始的冲突：人选「关上旧的」不给日期，就关在新的起点——也是它自己的起点，拒绝。 */
    @Test
    void closingASimultaneousConflictAtItsOwnStartIsRefused() {
        Base base = new Base();
        try {
            jdbc.update("UPDATE relation_types SET functional = true WHERE id = ?", base.worksFor);
            Validity since = starting(day(), "day");
            java.util.List<UUID> facts = new java.util.ArrayList<>();
            for (UUID object : new UUID[]{base.meridian, base.aster}) {
                facts.add(writes.insertFact(base.kb, base.lin, base.worksFor, object, since,
                        0.9f).id());
            }
            var report = temporal.reconcileNewFact(base.kb, facts.get(1), base.lin, base.worksFor,
                    base.aster, Side.SUBJECT_SIDE);
            assertEquals(1, report.conflicts);
            var conflicts = openConflicts(base.kb);
            assertEquals(1, conflicts.size());
            assertEquals("simultaneous", conflicts.get(0).reason());
            AppException refused = assertThrows(AppException.class, () ->
                    temporal.resolveConflict(base.kb, conflicts.get(0).id(), "close", null, "day"));
            assertTrue(refusedAsEmpty(refused), refused.toString());
            Object[] oldLive = base.live(facts.get(0));
            assertEquals(true, oldLive[0]);
            assertNull(oldLive[1], "the old row is untouched");
            assertEquals(1, openConflicts(base.kb).size(),
                    "the conflict stays open");
            temporal.resolveConflict(base.kb, conflicts.get(0).id(), "close",
                    t("2024-01-01T00:00:00Z"), "day");
            assertTrue(openConflicts(base.kb).isEmpty());
        } finally {
            base.remove();
        }
    }

    /** 人写的行不被第 0 步作废：一句话并进人写的行，行不因此变成算出来的；后来属性改成状态也不作废。 */
    @Test
    void aRowAPersonWroteIsKeptWhenASingleDateMergedIntoIt() {
        Base base = new Base();
        try {
            var byHand = writes.insertFact(base.kb, base.lin, base.acquired, base.meridian,
                    moment(), 0.9f);
            base.statement("signed with", day(), day());
            UUID toAster = base.statementTo("signed with", base.aster, day(), day(), "day");
            base.bindTo("signed with", base.acquired, null);
            Outcome outcome = materialize.materialize(base.kb);
            assertEquals(1L, outcome.added());
            assertEquals(1L, outcome.merged(),
                    "one merged into the person's row, one computed: " + outcome);
            UUID computed = jdbc.queryForObject(
                    "SELECT fact_id FROM typed_fact_sources WHERE statement_id = ?", UUID.class,
                    toAster);
            var handRow = jdbc.queryForMap("""
                    SELECT from_statement_id,
                            (SELECT count(*) FROM typed_fact_sources WHERE fact_id = f.id) AS links
                       FROM facts f WHERE id = ?
                    """, byHand.id());
            assertNull(handRow.get("from_statement_id"));
            assertEquals(1L, handRow.get("links"));

            // 本体页上把 acquired 改成状态
            jdbc.update("UPDATE relation_types SET temporal = 'state' WHERE id = ?", base.acquired);
            outcome = materialize.materialize(base.kb);
            assertEquals(1L, outcome.retired(), "only the computed row: " + outcome);
            Object[] handLive = base.live(byHand.id());
            assertEquals(true, handLive[0]);
            assertEquals(day(), handLive[1], "a row a person wrote is kept");
            Object[] computedLive = base.live(computed);
            assertEquals(false, computedLive[0]);
            assertEquals(day(), computedLive[1], "the computed one is retired");
        } finally {
            base.remove();
        }
    }
}
