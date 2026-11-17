package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;
import tech.seexun.jknow.store.temporal.TemporalService;

/**
 * 移植自顶层 tests/a_timeline_uses_its_statements_attestation.rs（526 行，9 例）——
 * 章节见证给函数型状态排序（0064）：文档日期顶替不了它。测试用生产物化与世界轴谓词，
 * 无 LLM。
 */
class ATimelineUsesItsStatementsAttestationTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    GraphWriteService writes;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    TemporalService temporal;

    private static OffsetDateTime day(String date) {
        return OffsetDateTime.parse(date + "T00:00:00Z");
    }

    record Fixture(UUID org, UUID kb, UUID subject, UUID property, UUID document) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID workspace = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID property = Uuids.nowV7();
        UUID document = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'chapter-timeline-test')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'chapter-timeline-test')",
                workspace, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'chapter-timeline-test')",
                kb, workspace);
        jdbc.update("INSERT INTO entities(id,kb_id,canonical_name) VALUES (?, ?, 'Watch')", subject, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,datatype,temporal,functional)
                 VALUES (?, ?, 'market', 'market', 'attribute', 'text', 'state', TRUE)
                """, property, kb);
        jdbc.update("""
                INSERT INTO documents(id,kb_id,filename,sha256,doc_time,doc_time_source)
                 VALUES (?, ?, 'chapters.txt', ?, '2026-09-04', 'content')
                """, document, kb, document.toString());
        jdbc.update("""
                INSERT INTO document_versions(id,document_id,version,sha256,doc_time)
                 VALUES (?, ?, 1, ?, '2026-09-04')
                """, Uuids.nowV7(), document, document.toString());
        return new Fixture(org, kb, subject, property, document);
    }

    private UUID statement(Fixture f, String value, String attested, int version) {
        var object = JsonNodeFactory.instance.objectNode().put("value", value);
        var wrote = writes.insertOpenStatement(f.kb(), f.subject(), "market is", null, object,
                null, 0.9f);
        UUID chunk = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO chunks(id,kb_id,document_id,doc_version,seq,text)
                 VALUES (?, ?, ?, ?, ?, ?)
                """, chunk, f.kb(), f.document(), version, version, value);
        writes.addEvidence(wrote.id(), chunk, value, "market is");
        if (attested != null) {
            writes.attestStatement(wrote.id(), day(attested), "chapter " + attested);
        }
        return wrote.id();
    }

    private void bind(Fixture f) {
        for (PhraseSignature signature : phraseBindings.signatures(f.kb())) {
            phraseBindings.decide(f.kb(), signature, new PhraseBindingsService.Decision(
                    f.property(), "forward", "bound", JsonNodeFactory.instance.objectNode(),
                    "agent", null, null, false));
        }
    }

    record State(UUID id, OffsetDateTime validFrom, OffsetDateTime validTo, String validToPrecision,
                 OffsetDateTime attestedFrom, String attestedBy, OffsetDateTime attestedTo,
                 String validFromGrade, boolean endDerived, UUID supersedes) {
    }

    private State state(Fixture f, String value) {
        return jdbc.queryForObject("""
                SELECT id,valid_from,valid_to,valid_to_precision,attested_from,attested_by,attested_to,valid_from_grade,end_derived,supersedes
                   FROM facts WHERE kb_id=? AND predicate_id=? AND layer='typed'
                    AND invalidated_at IS NULL AND object_value #>> '{value}'=?
                """, (rs, i) -> new State(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class), rs.getString(8), rs.getBoolean(9),
                rs.getObject(10, UUID.class)), f.kb(), f.property(), value);
    }

    /** world_axis.rs facts_hold_at("f", n) 的展开（HeartStoreTestBase.HF_F/HT_F 同源）。 */
    private List<String> valuesAt(Fixture f, String date) {
        String eventless = "(SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)"
                + " IS NOT DISTINCT FROM 'event' AND f.valid_from IS NULL AND f.attested_from IS NULL";
        String sql = "SELECT object_value #>> '{value}' FROM facts f"
                + " WHERE kb_id=? AND predicate_id=? AND layer='typed' AND invalidated_at IS NULL"
                + " AND (NOT (" + eventless + ")"
                + " AND ((" + HF_F + " IS NULL OR " + HF_F + " <= ?::timestamptz)"
                + " AND (" + HT_F + " IS NULL OR " + HT_F + " > ?::timestamptz)))"
                + " ORDER BY object_value #>> '{value}'";
        return jdbc.queryForList(sql, String.class, f.kb(), f.property(), day(date), day(date));
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM organizations WHERE id=?", f.org());
    }

    private void assertChapterTimeline(Fixture f) {
        State old = state(f, "old");
        State now = state(f, "new");
        assertNull(old.validFrom(), "as-of is not a start");
        assertNull(now.validFrom(), "as-of is not a start");
        assertEquals(day("2015-01-01"), old.attestedFrom());
        assertEquals("chapter 2015-01-01", old.attestedBy());
        assertEquals(day("2026-01-01"), now.attestedFrom());
        assertNull(old.validTo(), "the text did not state an end");
        assertEquals("unknown", old.validToPrecision());
        assertEquals(day("2026-01-01"), old.attestedTo());
        assertTrue(old.endDerived());
        assertNotNull(old.supersedes(), "closure must preserve the ledger");
        Boolean historical = jdbc.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM facts WHERE id=?", Boolean.class,
                old.supersedes());
        assertTrue(historical);
        assertFalse(now.endDerived());
        assertNull(now.validToPrecision());
        assertEquals(List.of(), valuesAt(f, "2014-01-01"));
        assertEquals(List.of("old"), valuesAt(f, "2020-01-01"));
        assertEquals(List.of("new"), valuesAt(f, "2026-01-01"));
    }

    @Test
    void chapterDatesBeforeMaterializationCloseThePreviousValue() {
        Fixture f = seed();
        try {
            statement(f, "old", "2015-01-01", 1);
            statement(f, "new", "2026-01-01", 1);
            bind(f);
            Outcome result = materialize.materialize(f.kb());
            assertEquals(1L, result.corrected(), result.toString());
            assertEquals(0, result.conflicts(), "different chapter dates are ordered");
            assertChapterTimeline(f);
            assertEquals(Outcome.EMPTY, materialize.materialize(f.kb()));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void chapterDatesSyncedAfterMaterializationReconcileTheExistingTimeline() {
        Fixture f = seed();
        try {
            UUID old = statement(f, "old", null, 1);
            UUID now = statement(f, "new", null, 1);
            bind(f);
            materialize.materialize(f.kb());
            assertTrue(writes.attestStatement(old, day("2015-01-01"), "chapter 2015-01-01"));
            assertTrue(writes.attestStatement(now, day("2026-01-01"), "chapter 2026-01-01"));
            Outcome result = materialize.materialize(f.kb());
            assertEquals(0L, result.added(), "date synchronization must reconcile existing rows");
            assertEquals(1L, result.corrected(), result.toString());
            assertChapterTimeline(f);
        } finally {
            cleanup(f);
        }
    }

    @Test
    void equalChapterDatesRemainARealConflict() {
        Fixture f = seed();
        try {
            for (String value : new String[]{"old", "new"}) {
                statement(f, value, "2026-01-01", 1);
            }
            bind(f);
            Outcome result = materialize.materialize(f.kb());
            assertEquals(0L, result.corrected());
            assertEquals(1, result.conflicts());
            assertEquals(List.of("new", "old"), valuesAt(f, "2026-01-01"));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void anUndatedStatementDoesNotBorrowADocumentOrVersionDate() {
        Fixture f = seed();
        try {
            statement(f, "old", "2015-01-01", 1);
            statement(f, "new", null, 1);
            bind(f);
            Outcome result = materialize.materialize(f.kb());
            assertEquals(0L, result.corrected(), "NULL explicitly means no attestation");
            assertNull(state(f, "new").attestedFrom());
            assertNull(state(f, "old").validToPrecision());
            assertEquals(List.of("new"), valuesAt(f, "2014-01-01"));
            assertEquals(List.of("new", "old"), valuesAt(f, "2026-01-01"));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void explicitStartsTakePriorityAndUnresolvedStartsCloseNothing() {
        for (String grade : new String[]{"A", "C"}) {
            Fixture f = seed();
            try {
                statement(f, "old", "2015-01-01", 1);
                String attested = "A".equals(grade) ? "2010-01-01" : "2026-01-01";
                UUID now = statement(f, "new", attested, 1);
                writes.setOpenValidity(now, "A".equals(grade) ? day("2020-01-01") : null,
                        "A".equals(grade) ? "day" : null, null, null, grade);
                bind(f);
                Outcome result = materialize.materialize(f.kb());
                State old = state(f, "old");
                if ("A".equals(grade)) {
                    assertEquals(1L, result.corrected());
                    assertEquals(day("2020-01-01"), old.validTo());
                    assertEquals("day", old.validToPrecision());
                    assertNull(old.attestedTo());
                    assertEquals(List.of("old"), valuesAt(f, "2016-01-01"));
                    assertEquals(List.of("new"), valuesAt(f, "2020-01-01"));
                } else {
                    assertEquals(0L, result.corrected());
                    assertEquals(0, result.conflicts(), "grade C waits for resolution");
                    assertNull(old.validToPrecision());
                }
            } finally {
                cleanup(f);
            }
        }
    }

    @Test
    void aNewDocumentVersionCannotRedateEarlierChapterEvidence() {
        Fixture f = seed();
        try {
            statement(f, "old", "2015-01-01", 1);
            jdbc.update("UPDATE documents SET doc_time='2030-01-01' WHERE id=?", f.document());
            jdbc.update("""
                    INSERT INTO document_versions(id,document_id,version,sha256,doc_time)
                     VALUES (?, ?, 2, ?, '2030-01-01')
                    """, Uuids.nowV7(), f.document(), Uuids.nowV7().toString());
            statement(f, "new", "2026-01-01", 2);
            bind(f);
            materialize.materialize(f.kb());
            assertChapterTimeline(f);
            State old = state(f, "old");
            List<Integer> versions = jdbc.queryForList(
                    "SELECT doc_version FROM fact_evidence WHERE fact_id=? ORDER BY doc_version",
                    Integer.class, old.id());
            assertEquals(List.of(1), versions, "closure preserves the old evidence's version");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void closingAnUnresolvedPredecessorPreservesItsGradeAndAttestationName() {
        Fixture f = seed();
        try {
            UUID old = statement(f, "old", "2015-01-01", 1);
            writes.setOpenValidity(old, null, null, null, null, "C");
            statement(f, "new", "2026-01-01", 1);
            bind(f);
            materialize.materialize(f.kb());
            assertChapterTimeline(f);
            assertEquals("C", state(f, "old").validFromGrade());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void controlledReconciliationReopensAnUndatedDerivedEndWithoutOverwritingHistory() {
        Fixture f = seed();
        try {
            statement(f, "old", null, 1);
            bind(f);
            materialize.materialize(f.kb());
            State original = state(f, "old");
            // 模拟只有文档时间的旧引擎边界。修复必须经账本替换这条活行，不抹它的过去
            jdbc.update("""
                    UPDATE facts SET valid_to_precision='unknown',attested_to='2026-09-04',end_derived=TRUE WHERE id=?
                    """, original.id());
            var repaired = temporal.reconcilePredicate(f.kb(), f.property());
            assertEquals(1, repaired.corrected.size());
            State reopened = state(f, "old");
            assertEquals(original.id(), reopened.supersedes());
            assertNull(reopened.attestedFrom());
            assertNull(reopened.validToPrecision());
            assertNull(reopened.attestedTo());
            assertFalse(reopened.endDerived());
            assertEquals(List.of("old"), valuesAt(f, "2030-01-01"));
            var historical = jdbc.queryForMap("""
                    SELECT invalidated_at IS NOT NULL AS inv, valid_to_precision, attested_to FROM facts WHERE id=?
                    """, original.id());
            assertEquals(true, historical.get("inv"));
            assertEquals("unknown", historical.get("valid_to_precision"));
            assertEquals(java.sql.Timestamp.valueOf("2026-09-04 00:00:00").toInstant(),
                    ((java.sql.Timestamp) historical.get("attested_to")).toInstant());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void mergeConflictChecksUseTheSameChapterDatesAsTimelineReconciliation() {
        Fixture f = seed();
        try {
            jdbc.update("UPDATE relation_types SET kind='relation',datatype=NULL WHERE id=?",
                    f.property());
            UUID otherSubject = Uuids.nowV7();
            UUID oldMarket = Uuids.nowV7();
            UUID newMarket = Uuids.nowV7();
            for (Object[] row : new Object[][]{{otherSubject, "Other Watch"},
                    {oldMarket, "Old Market"}, {newMarket, "New Market"}}) {
                jdbc.update("INSERT INTO entities(id,kb_id,canonical_name) VALUES (?, ?, ?)",
                        row[0], f.kb(), row[1]);
            }
            UUID laterStatement = null;
            for (Object[] row : new Object[][]{{f.subject(), oldMarket, "2015-01-01"},
                    {otherSubject, newMarket, "2026-01-01"}}) {
                var wrote = writes.insertOpenStatement(f.kb(), (UUID) row[0], "market is",
                        (UUID) row[1], null, null, 0.9f);
                writes.attestStatement(wrote.id(), day((String) row[2]), "chapter " + row[2]);
                UUID chunk = Uuids.nowV7();
                jdbc.update("""
                        INSERT INTO chunks(id,kb_id,document_id,doc_version,seq,text) VALUES (?, ?, ?, 1, 0, ?)
                        """, chunk, f.kb(), f.document(), row[2]);
                writes.addEvidence(wrote.id(), chunk, (String) row[2], "market is");
                laterStatement = wrote.id();
            }
            bind(f);
            materialize.materialize(f.kb());
            assertTrue(temporal.mergeWouldOverlap(f.kb(), f.subject(), otherSubject).isEmpty(),
                    "different chapter dates can form one timeline after a merge");
            assertTrue(writes.attestStatement(laterStatement, day("2015-01-01"),
                    "chapter 2015-01-01"));
            materialize.materialize(f.kb());
            assertEquals(List.of("market"),
                    temporal.mergeWouldOverlap(f.kb(), f.subject(), otherSubject),
                    "equal chapter dates remain a merge conflict");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void chapterAttestationReconciliationPreservesAStatedEnd() {
        Fixture f = seed();
        try {
            UUID old = statement(f, "old", "2015-01-01", 1);
            writes.setOpenValidity(old, null, null, day("2018-01-01"), "day", "A");
            statement(f, "new", "2026-01-01", 1);
            bind(f);
            materialize.materialize(f.kb());
            temporal.reconcilePredicate(f.kb(), f.property());
            State stated = state(f, "old");
            assertEquals(day("2018-01-01"), stated.validTo());
            assertEquals("day", stated.validToPrecision());
            assertFalse(stated.endDerived(), "only engine-derived ends may be recomputed");
            assertEquals(List.of(), valuesAt(f, "2020-01-01"));
            assertEquals(List.of("new"), valuesAt(f, "2026-01-01"));
        } finally {
            cleanup(f);
        }
    }
}
