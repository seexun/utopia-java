package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TypedMaterializeService;

/**
 * 证据所在的文档删了、删除撤销了，事实的见证照还在的证据重算
 * （a_deleted_document_no_longer_attests.rs 原值移植，0064 决定 3、5；0022 修订
 * 2026-10-03 留下的口子——M5 任务 6 台账收口）：
 * <ul>
 *   <li>每条证据记着它那段原文说话的那一刻；删掉最早那篇，陈述的见证挪到还在的证据里
 *     最早的那一节的日期上，连同名字——不是那篇文档的日期；</li>
 *   <li>还在的证据一条日期都没说，见证就是空：删掉的文档的日期不再给它作证；</li>
 *   <li>撤销删除，见证原样回来；</li>
 *   <li>没记章节日期的证据（这一列之前写下的）按文档自己的日期读，和从前一样；</li>
 *   <li>跟着陈述走的类型化行一起挪，随文档作废的来源陈述不再给它作证。</li>
 * </ul>
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ADeletedDocumentNoLongerAttestsTests extends StoreTestBase {

    @Autowired
    GraphWriteService writes;
    @Autowired
    DocumentsWriteService documents;
    @Autowired
    TypedMaterializeService materialize;

    private UUID org;
    private UUID kb;
    private UUID watch;
    private UUID market;
    private UUID region;
    private UUID soldIn;

    /** 一条证据所在那一节的日期和它的名字；那一节没说日期就是 null。 */
    private record Witness(OffsetDateTime at, String by) {
        static final Witness NONE = null;
    }

    private static OffsetDateTime day(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        watch = Uuids.nowV7();
        market = Uuids.nowV7();
        region = Uuids.nowV7();
        soldIn = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'deleted-doc-attests')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name)"
                + " VALUES (?, ?, 'deleted-doc-attests')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                + " VALUES (?, ?, 'deleted-doc-attests')", kb, ws);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, '码表')",
                watch, kb);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, '全球')",
                market, kb);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, '欧洲')",
                region, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, kind, temporal, description)
                 VALUES (?, ?, 'sold_in', 'sold in', 'relation', 'state', '')
                """, soldIn, kb);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    private Object[] anchor(UUID fact) {
        return jdbc.queryForObject(
                "SELECT attested_from, attested_by FROM facts WHERE id = ?",
                (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class), rs.getString(2)},
                fact);
    }

    /** 一篇文档和它的一块正文。own 是文档自己的日期（正文说的），没有就是没说。 */
    private UUID document(OffsetDateTime own) {
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO documents (id, kb_id, filename, sha256, doc_time, doc_time_source)
                 VALUES (?, ?, ?, ?, ?, ?)
                """, doc, kb, "doc-" + doc + ".md", "doc-" + doc, own,
                own != null ? "content" : "none");
        jdbc.update("""
                INSERT INTO chunks (id, kb_id, document_id, seq, text)
                 VALUES (?, ?, ?, 0, ?)
                """, chunk, kb, doc, "# 年报\n码表所属市场为全球。");
        return chunk;
    }

    /** 一条开放陈述，证据在这几块里；非 null 的那几条证据记着章节日期。 */
    private UUID statement(UUID subject, UUID object, String phrase, List<Object[]> evidence) {
        var wrote = writes.insertOpenStatement(kb, subject, phrase, object, null, null, 0.9f);
        for (Object[] row : evidence) {
            UUID chunk = (UUID) row[0];
            Witness witness = (Witness) row[1];
            writes.addEvidence(wrote.id(), chunk, "码表所属市场为全球", null);
            writes.witnessEvidence(wrote.id(), chunk,
                    witness == null ? null : witness.at(), witness == null ? null : witness.by());
            if (witness != null) {
                writes.attestStatement(wrote.id(), witness.at(), witness.by());
            }
        }
        return wrote.id();
    }

    /** 从这些陈述物化出来的一行类型化事实：记着来源，抄了它们的证据。 */
    private UUID typed(UUID subject, UUID object, UUID predicate, List<UUID> sources) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, object_id, predicate_id, layer,
                                   from_statement_id)
                 VALUES (?, ?, ?, ?, ?, 'typed', ?)
                """, id, kb, subject, object, predicate, sources.get(0));
        for (UUID source : sources) {
            jdbc.update("INSERT INTO typed_fact_sources (fact_id, statement_id)"
                    + " VALUES (?, ?)", id, source);
            jdbc.update("""
                    INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id, doc_version,
                                               proposed_predicate, quote_start, quote_end)
                     SELECT ?, chunk_id, quote, document_id, doc_version, proposed_predicate,
                            quote_start, quote_end
                       FROM fact_evidence WHERE fact_id = ?
                     ON CONFLICT DO NOTHING
                    """, id, source);
        }
        materialize.syncTypedAttestation(kb, null);
        return id;
    }

    @Test
    void attestationFollowsTheEvidenceThatRemains() {
        OffsetDateTime chapter2015 = day(2015, 6, 1);
        OffsetDateTime chapter2020 = day(2020, 6, 1);
        String by2015 = "2015 年年报";
        String by2020 = "2020 年年报";
        OffsetDateTime lateOwnDate = day(2026, 3, 1);
        // 早的那篇自己的日期是 2026 年 1 月，章节说的是 2015；晚的那篇 2026 年 3 月，
        // 章节说 2020；第三篇什么日期都没说
        UUID earlyChunk = document(day(2026, 1, 10));
        UUID early = jdbc.queryForObject(
                "SELECT document_id FROM chunks WHERE id = ?", UUID.class, earlyChunk);
        UUID lateChunk = document(lateOwnDate);
        UUID bareChunk = document(null);
        Witness from2015 = new Witness(chapter2015, by2015);
        Witness from2020 = new Witness(chapter2020, by2020);

        // 两篇都说过：最早的章节作证
        UUID both = statement(watch, market, "所属市场为",
                List.<Object[]>of(new Object[]{earlyChunk, from2015}, new Object[]{lateChunk, from2020}));
        // 另一处出处一个日期都没说
        UUID besideUndated = statement(watch, market, "销往",
                List.<Object[]>of(new Object[]{earlyChunk, from2015}, new Object[]{bareChunk, null}));
        // 只在早的那篇里：随它一起作废
        UUID onlyEarly = statement(watch, region, "所属市场为",
                List.<Object[]>of(new Object[]{earlyChunk, from2015}));
        // 这一列之前写下的证据：没记章节日期，见证是当时按文档日期给的
        UUID legacy = statement(watch, region, "销往",
                List.<Object[]>of(new Object[]{earlyChunk, null}, new Object[]{lateChunk, null}));
        jdbc.update("UPDATE facts SET attested_from = ? WHERE id = ?",
                day(2026, 1, 10), legacy);
        // 类型化的行：一行跟着两篇都说过的那条；一行的两个来源里有一个只在早的那篇里
        UUID followsBoth = typed(watch, market, soldIn, List.of(both));
        UUID lateOnly = statement(watch, region, "在售于",
                List.<Object[]>of(new Object[]{lateChunk, from2020}));
        UUID mixedSources = typed(watch, region, soldIn, List.of(onlyEarly, lateOnly));

        Object[] earliest = {chapter2015, by2015};
        for (UUID fact : List.of(both, besideUndated, followsBoth, mixedSources)) {
            assertEquals(earliest[0], anchor(fact)[0], "before the deletion");
            assertEquals(earliest[1], anchor(fact)[1], "before the deletion");
        }

        documents.delete(kb, early, null);

        Object[] remaining = {chapter2020, by2020};
        assertEquals(remaining[0], anchor(both)[0],
                "the chapter date of the evidence that remains, not that document's own date");
        assertEquals(remaining[1], anchor(both)[1], "with its name");
        assertEquals(null, anchor(besideUndated)[0],
                "what remains states no date: the deleted document's date no longer attests");
        assertEquals(null, anchor(besideUndated)[1]);
        assertEquals(lateOwnDate, anchor(legacy)[0],
                "evidence with no recorded chapter date reads the document's own date, as before");
        assertEquals(null, anchor(legacy)[1]);
        assertEquals(remaining[0], anchor(followsBoth)[0], "a typed row follows its statement");
        assertEquals(remaining[1], anchor(followsBoth)[1]);
        assertNotNull(jdbc.queryForObject(
                "SELECT invalidated_at FROM facts WHERE id = ?", OffsetDateTime.class, onlyEarly),
                "a statement whose only source is deleted is invalidated");
        assertEquals(remaining[0], anchor(mixedSources)[0],
                "a source statement invalidated with the document no longer attests the typed row");
        assertEquals(remaining[1], anchor(mixedSources)[1]);

        documents.restore(kb, early);
        for (UUID fact : List.of(both, besideUndated, followsBoth, mixedSources)) {
            assertEquals(earliest[0], anchor(fact)[0], "after the restore");
            assertEquals(earliest[1], anchor(fact)[1], "after the restore");
        }
        assertEquals(day(2026, 1, 10), anchor(legacy)[0],
                "legacy evidence reads the earliest own date again");
    }
}
