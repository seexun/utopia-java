package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TypedMaterializeService;

/**
 * 移植自 tests/store/a_statement_is_attested_by_what_its_document_says.rs（320 行，0064
 * 决定 3、5）——陈述的见证来自文档自己说的日期，没有就是没有：留空的行在世界轴上任何
 * 时点都成立；attest_statement 写上见证和那条日期的名字，只往早挪；物化出来的类型化行
 * 跟着它的来源陈述走；规则算出来的行也跟着它读的陈述走。
 *
 * <p>任务 7 去封：两个 seam 已换 {@link TypedMaterializeService} 真调用，断言一字不动。
 */
class AStatementIsAttestedByWhatItsDocumentSaysTests extends HeartStoreTestBase {

    private static final String ORG = "statement-attested-by-document-test";
    private static final String RULE_ORG = "rule-row-attested-by-its-statement-test";

    @Autowired
    GraphWriteService writes;
    @Autowired
    JdbcTemplate jdbc;

    private Object[] anchor(UUID fact) {
        return jdbc.queryForObject(
                "SELECT attested_from, attested_by FROM facts WHERE id = ?",
                (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class), rs.getString(2)},
                fact);
    }

    /**
     * 这一行在 T 时刻成立吗，按读路径自己的谓词（world_axis.rs:72-84 facts_hold_at 的
     * 展开——temporal_of/interval_holds_at(facts_holds_from, facts_holds_to, param)，
     * 与 HeartStoreTestBase.HF_F/HT_F 同一套表达式）。
     */
    private boolean holdsAt(UUID fact, OffsetDateTime at) {
        String temporal = "(SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)";
        String from = "CASE WHEN " + temporal + " = 'eternal' THEN NULL"
                + " ELSE COALESCE(f.valid_from, f.attested_from) END";
        String to = "CASE WHEN " + temporal + " = 'eternal' THEN NULL"
                + " WHEN " + temporal + " = 'event' THEN"
                + " CASE WHEN f.valid_from IS NULL THEN f.attested_from"
                + " ELSE COALESCE(f.valid_to, f.valid_from)"
                + " + ('1 ' || COALESCE(NULLIF(f.valid_to_precision, 'unknown'), f.valid_from_precision))::interval END"
                + " WHEN f.valid_to IS NOT NULL THEN f.valid_to"
                + " WHEN f.valid_to_precision = 'unknown' THEN f.attested_to END";
        String sql = "SELECT (?::timestamptz IS NULL"
                + " OR (NOT (" + temporal + " IS NOT DISTINCT FROM 'event' AND f.valid_from IS NULL"
                + " AND f.attested_from IS NULL)"
                + " AND ((" + from + " IS NULL OR " + from + " <= ?::timestamptz)"
                + " AND (" + to + " IS NULL OR " + to + " > ?::timestamptz))))"
                + " FROM facts f WHERE f.id = ?";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, at, at, at, fact));
    }

    @Autowired
    TypedMaterializeService typedMaterialize;

    @Test
    void an_unattested_statement_holds_at_every_moment_and_an_attested_one_from_its_date() {
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID watch = Uuids.nowV7();
        UUID market = Uuids.nowV7();
        UUID launch = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, ?)", org, ORG);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, ?)", kb, ws,
                ORG);
        jdbc.update("INSERT INTO entities(id,kb_id,canonical_name) VALUES (?, ?, '码表'), (?, ?, '全球')",
                watch, kb, market, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,description) VALUES
                    (?, ?, 'launched_in', 'launched in', 'relation', 'event', '')
                """, launch, kb);
        try {
            OffsetDateTime longAgo = OffsetDateTime.parse("2015-01-01T00:00:00Z");
            OffsetDateTime reportDay = OffsetDateTime.parse("2026-09-04T00:00:00Z");
            OffsetDateTime earlierReport = OffsetDateTime.parse("2026-08-28T00:00:00Z");

            // 文档没说自己是哪天的：没有见证，任何时点都成立
            var statement = writes.insertOpenStatement(kb, watch, "所属市场为", market, null,
                    null, 0.9f);
            assertEquals(null, anchor(statement.id())[0]);
            assertEquals(null, anchor(statement.id())[1]);
            assertTrue(holdsAt(statement.id(), longAgo), "no time at all: holds at every moment");
            assertTrue(holdsAt(statement.id(), OffsetDateTime.now()));

            // 它所在那一节的日期作证：从那天起读得到，之前读不到（0022 的下界）
            assertTrue(writes.attestStatement(statement.id(), reportDay, "提报日期 2026年9月4日"));
            assertEquals(reportDay, anchor(statement.id())[0]);
            assertEquals("提报日期 2026年9月4日", anchor(statement.id())[1]);
            assertFalse(holdsAt(statement.id(), longAgo));
            assertTrue(holdsAt(statement.id(), reportDay));
            // 更早的文档也说过：见证往早挪，名字跟着；更晚的不动它
            assertTrue(writes.attestStatement(statement.id(), earlierReport, "提报日期 2026年8月28日"));
            assertFalse(writes.attestStatement(statement.id(), reportDay, "提报日期 2026年9月4日"));
            assertEquals(earlierReport, anchor(statement.id())[0]);
            assertEquals("提报日期 2026年8月28日", anchor(statement.id())[1]);

            // 物化出来的类型化行跟着来源陈述走；来源没有见证的，它也没有
            UUID typed = Uuids.nowV7();
            UUID bareTyped = Uuids.nowV7();
            var bare = writes.insertOpenStatement(kb, watch, "上市于", market, null, null, 0.9f);
            for (UUID[] pair : new UUID[][]{{typed, statement.id()},
                    {bareTyped, bare.id()}}) {
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, object_id, predicate_id, layer, from_statement_id)
                         VALUES (?, ?, ?, ?, ?, 'typed', ?)
                        """, pair[0], kb, watch, market, launch, pair[1]);
                jdbc.update("INSERT INTO typed_fact_sources (fact_id, statement_id) VALUES (?, ?)",
                        pair[0], pair[1]);
            }
            typedMaterialize.syncTypedAttestation(kb, null);
            assertEquals(earlierReport, anchor(typed)[0]);
            assertEquals("提报日期 2026年8月28日", anchor(typed)[1]);
            assertEquals(null, anchor(bareTyped)[0]);
            assertEquals(null, anchor(bareTyped)[1]);
            // 没日期的事件任何时点都不算成立（0022），没有见证也一样
            assertFalse(holdsAt(bareTyped, longAgo));
            assertFalse(holdsAt(bareTyped, OffsetDateTime.now()));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        }
    }

    /** 主语到宾语之间活着的那一行类型化事实。 */
    private UUID typedRow(UUID kb, UUID subject, UUID object) {
        return jdbc.queryForObject("""
                SELECT id FROM facts
                  WHERE kb_id = ? AND subject_id = ? AND object_id = ?
                    AND layer = 'typed' AND invalidated_at IS NULL
                """, UUID.class, kb, subject, object);
    }

    /**
     * 规则算出来的行（0073 的隐含行）跟着它读的陈述走：陈述没说时间，它就没有见证、
     * 任何时点都成立；陈述后来作了证，它跟到那天。类别词规则从实体算的行没有陈述可跟，
     * 也没有见证。人写的行被规则的结论并进来，见证还是人写下它的那一刻。
     */
    @Test
    void a_row_a_rule_implies_follows_the_statement_it_read() {
        jdbc.update("DELETE FROM organizations WHERE name = ?", RULE_ORG);
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            ids.add(Uuids.nowV7());
        }
        UUID org = ids.get(0);
        UUID ws = ids.get(1);
        UUID kb = ids.get(2);
        UUID person = ids.get(3);
        UUID company = ids.get(4);
        UUID worksFor = ids.get(5);
        UUID origin = ids.get(6);
        UUID lin = ids.get(7);
        UUID aster = ids.get(8);
        UUID meridian = ids.get(9);
        UUID film = ids.get(10);
        UUID uk = ids.get(11);
        UUID signedRule = ids.get(12);
        UUID kindRule = ids.get(13);
        // 两条批准了的规则：「signed with」（人 → 机构）蕴含 works_for；类别词「british film」
        // 蕴含 country_of_origin，宾语由读数缓存给出
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, ?)", org, RULE_ORG);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, ?)", ws, org, RULE_ORG);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, ?)", kb, ws,
                RULE_ORG);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES
                    (?, ?, 'person', 'person', '#000', 'circle'),
                    (?, ?, 'organization', 'organization', '#000', 'circle')
                """, person, kb, company, kb);
        jdbc.update("""
                INSERT INTO relation_types(id,kb_id,key,label,kind,temporal) VALUES
                    (?, ?, 'works_for', 'works for', 'relation', 'state'),
                    (?, ?, 'country_of_origin', 'country of origin', 'relation', 'state')
                """, worksFor, kb, origin, kb);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id,specific_type) VALUES
                    (?, ?, 'Lin Zhao', ?, NULL),
                    (?, ?, 'Aster Labs', ?, NULL),
                    (?, ?, 'Meridian Systems', ?, NULL),
                    (?, ?, 'Loud Tour', NULL, 'British film'),
                    (?, ?, 'United Kingdom', NULL, NULL)
                """, lin, kb, person, aster, kb, company, meridian, kb, company, film, kb, uk, kb);
        jdbc.update("""
                INSERT INTO implication_rules(id,kb_id,trigger,phrase,subject_type_id,object_type_id,
                                              conclude_property_id,reading,status,decided_by) VALUES
                    (?, ?, 'phrase', 'signed with', ?, ?, ?, NULL, 'approved', 'person'),
                    (?, ?, 'kind_word', 'british film', NULL, NULL, ?, 'country_of_nationality', 'approved', 'person')
                """, signedRule, kb, person, company, worksFor, kindRule, kb, origin);
        jdbc.update("""
                INSERT INTO phrase_readings(kb_id,reading,phrase,entity_id) VALUES
                    (?, 'country_of_nationality', 'british film', ?)
                """, kb, uk);
        try {
            OffsetDateTime longAgo = OffsetDateTime.parse("2015-01-01T00:00:00Z");
            OffsetDateTime signedDay = OffsetDateTime.parse("2026-09-04T00:00:00Z");
            OffsetDateTime writtenDay = OffsetDateTime.parse("2026-08-01T00:00:00Z");

            // 一条没说时间的陈述：规则从它算出的行没有见证，任何时点都成立——与同一条陈述物化
            // 出来的行一样，不是算出它的那一刻。类别词算出的行也是
            var signed = writes.insertOpenStatement(kb, lin, "signed with", aster, null, null,
                    0.9f);
            typedMaterialize.materialize(kb);
            UUID byRule = typedRow(kb, lin, aster);
            UUID byKind = typedRow(kb, film, uk);
            assertEquals(null, anchor(byRule)[0]);
            assertEquals(null, anchor(byRule)[1]);
            assertEquals(null, anchor(byKind)[0]);
            assertEquals(null, anchor(byKind)[1]);
            for (UUID row : new UUID[]{byRule, byKind}) {
                assertTrue(holdsAt(row, longAgo), "no time at all: holds at every moment");
                assertTrue(holdsAt(row, OffsetDateTime.now()));
            }

            // 陈述后来由它所在那一节的日期作证，时间解析那条路：先给陈述作证，再同步类型化的行。
            // 规则算出的行跟到那天，连同那条日期的名字；类别词那行没有陈述，照旧没有
            assertTrue(writes.attestStatement(signed.id(), signedDay, "签约日 2026年9月4日"));
            typedMaterialize.syncTypedAttestation(kb, null);
            assertEquals(signedDay, anchor(byRule)[0]);
            assertEquals("签约日 2026年9月4日", anchor(byRule)[1]);
            assertFalse(holdsAt(byRule, longAgo));
            assertTrue(holdsAt(byRule, signedDay));
            assertEquals(null, anchor(byKind)[0]);
            assertEquals(null, anchor(byKind)[1]);
            // 再物化一轮，不动它们
            typedMaterialize.materialize(kb);
            assertEquals(signedDay, anchor(byRule)[0]);
            assertEquals("签约日 2026年9月4日", anchor(byRule)[1]);
            assertEquals(null, anchor(byKind)[0]);
            assertEquals(null, anchor(byKind)[1]);

            // 人写的行由人写下它的那一刻作证。规则从一条没说时间的陈述算出同一件事、并进这一行：
            // 它有了规则的来源，见证不变
            var byHand = writes.insertFact(kb, lin, worksFor, meridian,
                    tech.seexun.jknow.store.temporal.Validity.empty().attested(writtenDay), 0.9f);
            writes.insertOpenStatement(kb, lin, "signed with", meridian, null, null, 0.9f);
            typedMaterialize.materialize(kb);
            Long ruleSources = jdbc.queryForObject(
                    "SELECT count(*) FROM implied_fact_sources WHERE fact_id = ?", Long.class,
                    byHand.id());
            assertEquals(1L, ruleSources);
            assertEquals(writtenDay, anchor(byHand.id())[0],
                    "a person's row keeps the moment it was written");
            assertEquals(null, anchor(byHand.id())[1]);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", RULE_ORG);
        }
    }
}
