package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;

/**
 * 移植自 crates/utopia-server/src/time_attestation_tests.rs（3 例）——分节见证在物化之后
 * 不靠 LLM 地对齐真实时间线：段落日期收拢类型化行、每条证据记下它那一节的日期与名字、
 * 已作废的分块不再被重新见证。断言逐条不改写。
 */
class TimeAttestationTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ResolveTimeJob resolveTime;
    @Autowired
    DocumentsService documents;
    @Autowired
    DocumentsWriteMapper writes;
    @Autowired
    GraphWriteService graph;
    @Autowired
    TypedMaterializeService materialize;

    private static final class Fixture {
        final UUID org;
        final UUID kb;
        final UUID doc;
        final UUID subject;
        final UUID workspace;

        Fixture(UUID org, UUID workspace, UUID kb, UUID doc, UUID subject) {
            this.org = org;
            this.workspace = workspace;
            this.kb = kb;
            this.doc = doc;
            this.subject = subject;
        }
    }

    private Fixture fixture() {
        UUID org = Uuids.nowV7();
        UUID workspace = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID property = Uuids.nowV7();
        UUID binding = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'section-attestation')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'section-attestation')",
                workspace, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'section-attestation')",
                kb, workspace);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256,doc_time,doc_time_source)"
                + " VALUES (?, ?, 'chapters.md', ?, '2030-01-01', 'source')", doc, kb, doc);
        jdbc.update("INSERT INTO entities(id,kb_id,canonical_name) VALUES (?, ?, 'Widget')",
                subject, kb);
        jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind,datatype,temporal,"
                + " functional) VALUES (?, ?, 'location', 'Location', 'attribute', 'text',"
                + " 'state', TRUE)", property, kb);
        jdbc.update("INSERT INTO phrase_bindings(id,kb_id,phrase,object_is_value,relation_type_id,"
                + " direction,status) VALUES (?, ?, 'is on', TRUE, ?, 'forward', 'bound')",
                binding, kb, property);
        writes.recordVersion(Uuids.nowV7(), doc, String.valueOf(doc), 100);
        return new Fixture(org, workspace, kb, doc, subject);
    }

    private Object[] chapter(Fixture f, int seq, String heading, String value,
                             OffsetDateTime attested) {
        UUID chunk = Uuids.nowV7();
        String quote = "Widget is on " + value + ".";
        String prefix = "# " + heading + "\n";
        String text = prefix + quote;
        jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text,doc_version)"
                        + " VALUES (?, ?, ?, ?, ?,"
                        + " (SELECT COALESCE(max(version),1) FROM document_versions WHERE document_id = ?))",
                chunk, f.kb, f.doc, seq, text, f.doc);
        JsonNode object = JsonNodeFactory.instance.objectNode().put("value", value);
        GraphWriteService.FactWrite statement = graph.insertOpenStatement(f.kb, f.subject,
                "is on", null, object, attested, 1.0f);
        graph.addEvidenceLocated(statement.id(), chunk, quote, "is on",
                new int[]{prefix.codePointCount(0, prefix.length()),
                        text.codePointCount(0, text.length())});
        return new Object[]{statement.id(), chunk};
    }

    private static OffsetDateTime day(int year) {
        return OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    /** time_context 里的分节日期（DocumentDating 的 JSON 形态）。 */
    private static JsonNode context(String... entries) throws Exception {
        StringBuilder sb = new StringBuilder("{\"entries\": [");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entries[i]);
        }
        sb.append("]}");
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(sb.toString());
    }

    private static String entry(String scope, String name, String words, int year) {
        return "{\"kind\":\"now\",\"name\":\"" + name + "\",\"words\":\"" + words + "\","
                + "\"from\":{\"year\":" + year + ",\"month\":1,\"day\":1},"
                + "\"scope\":[\"" + scope + "\"]}";
    }

    private void attest(Fixture f, JsonNode contextJson) {
        DocumentRow doc = documents.get(f.doc);
        resolveTime.attestStatements(doc, ResolveTimeJob.fromContextJson(contextJson));
    }

    @Test
    void sectionDatesReconcileValuesMaterializedBeforeAttestation() throws Exception {
        Fixture f = fixture();
        try {
            chapter(f, 0, "Earlier", "desk", null);
            chapter(f, 1, "Later", "shelf", null);
            TypedMaterializeService.Outcome materialized = materialize.materialize(f.kb);
            assertEquals(2, materialized.added());
            Long dated = jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE kb_id = ? AND layer = 'typed'"
                            + " AND invalidated_at IS NULL AND attested_from IS NOT NULL",
                    Long.class, f.kb);
            assertEquals(0L, dated, "materialization preserves the statements' NULL dates");
            attest(f, context(
                    entry("Earlier", "Report date", "2015-01-01", 2015),
                    entry("Later", "Report date", "2026-01-01", 2026)));
            List<Object[]> rows = jdbc.query(
                    "SELECT object_value->>'value', valid_from, valid_to_precision, attested_to"
                            + " FROM facts WHERE kb_id = ? AND layer = 'typed'"
                            + " AND invalidated_at IS NULL ORDER BY object_value->>'value'",
                    (rs, i) -> new Object[]{rs.getString(1),
                            rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class)}, f.kb);
            assertEquals(2, rows.size());
            assertEquals("desk", rows.get(0)[0]);
            assertNull(rows.get(0)[1]);
            assertEquals("unknown", rows.get(0)[2]);
            assertEquals(day(2026), rows.get(0)[3]);
            assertEquals("shelf", rows.get(1)[0]);
            assertNull(rows.get(1)[1]);
            assertNull(rows.get(1)[2]);
            assertNull(rows.get(1)[3]);
            for (Object[] at : new Object[][]{
                    {day(2014), new String[]{}},
                    {day(2020), new String[]{"desk"}},
                    {day(2026), new String[]{"shelf"}}}) {
                List<String> values = jdbc.queryForList(
                        "SELECT f.object_value->>'value' FROM facts f WHERE f.kb_id = ?"
                                + " AND f.layer = 'typed' AND f.invalidated_at IS NULL AND ("
                                + holdAtPredicate() + ") ORDER BY 1",
                        String.class, f.kb, at[0], at[0], at[0]);
                assertEquals(List.of((String[]) at[1]), values);
            }
            Long revisions = jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE kb_id = ? AND layer = 'typed'"
                            + " AND supersedes IS NOT NULL", Long.class, f.kb);
            assertEquals(1L, revisions, "the predecessor closes through the ledger");
            attest(f, context(
                    entry("Earlier", "Report date", "2015-01-01", 2015),
                    entry("Later", "Report date", "2026-01-01", 2026)));
            Long afterRetry = jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE kb_id = ? AND layer = 'typed'"
                            + " AND supersedes IS NOT NULL", Long.class, f.kb);
            assertEquals(revisions, afterRetry);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
        }
    }

    /**
     * 每条证据记下它那一节的日期和名字，没说日期的那一节记成空；文档再解析时一条日期都
     * 没说，上一遍记下的清掉。
     */
    @Test
    void eachPieceOfEvidenceRecordsTheDateItsSectionSpeaksFrom() throws Exception {
        Fixture f = fixture();
        try {
            UUID earlier = (UUID) chapter(f, 0, "Earlier", "desk", null)[0];
            UUID later = (UUID) chapter(f, 1, "Later", "shelf", null)[0];
            UUID appendix = (UUID) chapter(f, 2, "Appendix", "floor", null)[0];
            attest(f, context(
                    entry("Earlier", "Report date", "2015-01-01", 2015),
                    entry("Later", "", "2026-01-01", 2026)));
            assertEquals(List.of(day(2015), "Report date 2015-01-01"), witness(earlier));
            assertEquals(List.of(day(2026), "2026-01-01"), witness(later));
            assertEquals(java.util.Arrays.asList(null, null), witness(appendix),
                    "no date governs this section");

            attest(f, context());
            for (UUID statement : new UUID[]{earlier, later, appendix}) {
                assertEquals(java.util.Arrays.asList(null, null), witness(statement),
                        "the document now states no date");
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
        }
    }

    private List<Object> witness(UUID statement) {
        return jdbc.queryForObject(
                "SELECT attested_at, attested_by FROM fact_evidence WHERE fact_id = ?",
                (rs, i) -> (List<Object>) (List) java.util.Arrays.asList(
                        rs.getObject(1, OffsetDateTime.class), rs.getString(2)),
                statement);
    }

    @Test
    void currentContextDoesNotReattestSupersededChunks() throws Exception {
        Fixture f = fixture();
        try {
            Object[] old = chapter(f, 0, "Report", "desk", day(2026));
            jdbc.update("UPDATE chunks SET superseded_at = now() WHERE id = ?", old[1]);
            writes.recordVersion(Uuids.nowV7(), f.doc, String.valueOf(f.doc) + "-v2", 100);
            UUID current = (UUID) chapter(f, 1, "Report", "shelf", null)[0];
            attest(f, context(entry("Report", "Report date", "2025-01-01", 2025)));
            OffsetDateTime oldAt = jdbc.queryForObject(
                    "SELECT attested_from FROM facts WHERE id = ?", OffsetDateTime.class, old[0]);
            OffsetDateTime currentAt = jdbc.queryForObject(
                    "SELECT attested_from FROM facts WHERE id = ?", OffsetDateTime.class, current);
            assertEquals(day(2026), oldAt);
            assertEquals(day(2025), currentAt);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
        }
    }

    /** world_axis facts_hold_at(f, $2) 的展开（与引擎同一套表达式，见 HeartStoreTestBase）。 */
    private static String holdAtPredicate() {
        String temporal = "(SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)";
        String from = "CASE WHEN " + temporal + " = 'eternal' THEN NULL"
                + " ELSE COALESCE(f.valid_from, f.attested_from) END";
        String to = "CASE WHEN " + temporal + " = 'eternal' THEN NULL"
                + " WHEN " + temporal + " = 'event' THEN"
                + " CASE WHEN f.valid_from IS NULL THEN f.attested_from"
                + " ELSE COALESCE(f.valid_to, f.valid_from)"
                + " + ('1 ' || COALESCE(NULLIF(f.valid_to_precision, 'unknown'),"
                + " f.valid_from_precision))::interval END"
                + " WHEN f.valid_to IS NOT NULL THEN f.valid_to"
                + " WHEN f.valid_to_precision = 'unknown' THEN f.attested_to END";
        return "(?::timestamptz IS NULL"
                + " OR (NOT (" + temporal + " IS NOT DISTINCT FROM 'event' AND f.valid_from IS NULL"
                + " AND f.attested_from IS NULL)"
                + " AND ((" + from + " IS NULL OR " + from + " <= ?::timestamptz)"
                + " AND (" + to + " IS NULL OR " + to + " > ?::timestamptz))))";
    }
}
