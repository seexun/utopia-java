package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自顶层 tests/a_rewritten_row_gets_its_statements_back.rs（302 行）——迁移 0096：
 * #967 之前改写出来的行找回它由哪些陈述算出，之后物化重算出的重复行作废。
 * 迁移 SQL 从 classpath 读 V0087 重跑（Rust include_str! 对等；Flyway 文件体逐字）。
 */
class ARewrittenRowGetsItsStatementsBackTests extends HeartStoreTestBase {

    private static final String MIGRATION = readMigration("V0087__a_rewritten_row_gets_its_statements_back.sql");

    private static String readMigration(String name) {
        try (var in = new ClassPathResource("db/migration/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    TemporalService temporal;
    @Autowired
    tech.seexun.jknow.store.GraphService graphs;

    /** 一行的链接：from_statement_id、implied、两张来源表里的陈述。 */
    private Object[] links(UUID fact) {
        var from = jdbc.queryForMap(
                "SELECT from_statement_id, implied FROM facts WHERE id = ?", fact);
        List<UUID> typed = jdbc.queryForList(
                "SELECT statement_id FROM typed_fact_sources WHERE fact_id = ? ORDER BY statement_id",
                UUID.class, fact);
        List<UUID> byRule = jdbc.queryForList(
                "SELECT statement_id FROM implied_fact_sources WHERE fact_id = ? ORDER BY statement_id",
                UUID.class, fact);
        return new Object[]{from.get("from_statement_id"), from.get("implied"), typed, byRule};
    }

    private boolean live(UUID fact) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT invalidated_at IS NULL FROM facts WHERE id = ?", Boolean.class, fact));
    }

    /** 修正行的链接抹掉：#967 之前的 correct_interval 写出来就是这样。 */
    private void asBefore967(UUID fact) {
        jdbc.update("UPDATE facts SET from_statement_id = NULL, implied = FALSE WHERE id = ?", fact);
        jdbc.update("DELETE FROM typed_fact_sources WHERE fact_id = ?", fact);
        jdbc.update("DELETE FROM implied_fact_sources WHERE fact_id = ?", fact);
    }

    private UUID correctStart(UUID fact, String from) {
        UUID corrected = temporal.correctInterval(fact, new Validity(t(from), "day", null, null,
                null, null));
        assertNotNull(corrected, "the row was live");
        return corrected;
    }

    /** 活着的某一谓词的类型化行（主语、宾语给定）。 */
    private List<UUID> rows(UUID kb, UUID subject, UUID predicate) {
        return jdbc.queryForList("""
                SELECT id FROM facts
                  WHERE kb_id = ? AND layer = 'typed' AND subject_id = ? AND predicate_id = ?
                    AND invalidated_at IS NULL ORDER BY id
                """, UUID.class, kb, subject, predicate);
    }

    @Test
    void aRewrittenRowGetsItsStatementsBack() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'rewritten-row-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'rewritten-row-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'rewritten-row-test')",
                kb, ws);
        try {
            UUID person = Uuids.nowV7();
            UUID project = Uuids.nowV7();
            UUID leads = Uuids.nowV7();
            UUID worksOn = Uuids.nowV7();
            UUID rule = Uuids.nowV7();
            UUID zhang = Uuids.nowV7();
            UUID li = Uuids.nowV7();
            UUID wang = Uuids.nowV7();
            UUID zhou = Uuids.nowV7();
            UUID aurora = Uuids.nowV7();
            UUID helios = Uuids.nowV7();
            UUID nova = Uuids.nowV7();
            // v7 同毫秒不保序；due 按 s.id 排——charter < kickoff < bare 是本测试的次序前提
            List<UUID> stmtIds = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                stmtIds.add(Uuids.nowV7());
            }
            stmtIds.sort(null);
            UUID charter = stmtIds.get(0);
            UUID kickoff = stmtIds.get(1);
            UUID bare = stmtIds.get(2);
            UUID byHand = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label, color, shape) VALUES
                        (?, ?, 'person', 'person', '#7fd0ff', 'circle'),
                        (?, ?, 'project', 'project', '#7fd0ff', 'circle')
                    """, person, kb, project, kb);
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal) VALUES
                        (?, ?, 'leads', 'leads', 'state'),
                        (?, ?, 'works_on', 'works on', 'state')
                    """, leads, kb, worksOn, kb);
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES
                        (?, ?, ?, 'Zhang San'), (?, ?, ?, 'Li Si'), (?, ?, ?, 'Wang Wu'),
                        (?, ?, ?, 'Zhou Qi'), (?, ?, ?, 'Aurora'), (?, ?, ?, 'Helios'), (?, ?, ?, 'Nova')
                    """, zhang, kb, person, li, kb, person, wang, kb, person, zhou, kb, person,
                    aurora, kb, project, helios, kb, project, nova, kb, project);
            // 三条陈述：张三那条走 #967 之前的路，李四那条走 #967 之后的路，周七那条没有起点，
            // 之后由人点头的事实补上
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                       valid_from, valid_from_precision, valid_from_grade) VALUES
                        (?, ?, ?, ?, 'open', 'leads', 0.9, '2023-01-10T00:00:00Z', 'day', 'A'),
                        (?, ?, ?, ?, 'open', 'leads', 0.9, '2024-08-12T00:00:00Z', 'day', 'A'),
                        (?, ?, ?, ?, 'open', 'leads', 0.9, NULL, NULL, NULL)
                    """, charter, kb, zhang, aurora, kickoff, kb, li, helios, bare, kb, zhou, nova);
            jdbc.update("""
                    INSERT INTO phrase_bindings (id, kb_id, phrase, subject_type_id, object_type_id,
                                                 object_is_value, relation_type_id, direction, status)
                     VALUES (?, ?, 'leads', ?, ?, false, ?, 'forward', 'bound')
                    """, Uuids.nowV7(), kb, person, project, leads);
            jdbc.update("""
                    INSERT INTO implication_rules (id, kb_id, trigger, phrase, subject_type_id, object_type_id,
                                                   object_is_value, conclude_property_id, reading, status,
                                                   decided_by) VALUES
                        (?, ?, 'phrase', 'leads', ?, ?, false, ?, NULL, 'approved', 'person')
                    """, rule, kb, person, project, worksOn);
            // 人手写的一行：链上没有物化出来的行
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, layer, confidence,
                                       valid_from, valid_from_precision) VALUES
                        (?, ?, ?, ?, ?, 'typed', 1.0, '2022-01-01T00:00:00Z', 'day')
                    """, byHand, kb, wang, leads, helios);

            // 物化：三条陈述各一行 leads，每句另有一行规则蕴含的 works_on
            materialize.materialize(kb);
            List<UUID> typed = rows(kb, zhang, leads);
            List<UUID> byRule = rows(kb, zhang, worksOn);
            assertEquals(1, typed.size());
            assertEquals(1, byRule.size());

            // #967 之前人改区间：修正行写出来没有链接
            UUID corrected = correctStart(typed.get(0), "2023-02-01T00:00:00Z");
            UUID correctedRule = correctStart(byRule.get(0), "2023-02-01T00:00:00Z");
            asBefore967(corrected);
            asBefore967(correctedRule);
            // 对照：#967 之后改的一行带着链接；人手写的一行被改过
            List<UUID> afterRows = rows(kb, li, leads);
            UUID after = correctStart(afterRows.get(0), "2024-08-01T00:00:00Z");
            UUID hand = correctStart(byHand, "2022-02-01T00:00:00Z");
            Object[] afterLinks = links(after);
            assertEquals(kickoff, afterLinks[0], "#967 之后改写带着链接");
            // 对照：人点头的事实给裸行补上起点——新行链着那条物化行，却是另一次观察
            UUID refinedId = refinedInsert(kb, zhou, leads, nova, t("2023-05-01T00:00:00Z"));
            UUID supersedes = jdbc.queryForObject("SELECT supersedes FROM facts WHERE id = ?",
                    UUID.class, refinedId);
            assertNotNull(supersedes,
                    "the confirmed fact refines the bare row");

            // 旧代码的后果：再物化一次，照陈述又算出两行，与修正行并排
            Outcome second = materialize.materialize(kb);
            assertEquals(1L, second.added());
            assertEquals(1L, second.implied());
            assertEquals(1L, second.merged(),
                    "the two duplicates, and the bare statement merged into the refined row: " + second);
            Object[] refinedLinks = links(refinedId);
            assertEquals(null, refinedLinks[0]);
            assertEquals(false, refinedLinks[1]);
            assertEquals(List.of(bare), refinedLinks[2]);
            assertEquals(List.of(), refinedLinks[3]);
            UUID duplicate = rows(kb, zhang, leads).stream()
                    .filter(f -> !f.equals(corrected)).findFirst().orElseThrow();
            UUID duplicateRule = rows(kb, zhang, worksOn).stream()
                    .filter(f -> !f.equals(correctedRule)).findFirst().orElseThrow();

            runMigration(MIGRATION);

            // 两条修正行找回陈述与规则来源
            Object[] correctedLinks = links(corrected);
            assertEquals(charter, correctedLinks[0]);
            assertEquals(false, correctedLinks[1]);
            assertEquals(List.of(charter), correctedLinks[2]);
            assertEquals(List.of(), correctedLinks[3]);
            Object[] correctedRuleLinks = links(correctedRule);
            assertEquals(null, correctedRuleLinks[0]);
            assertEquals(true, correctedRuleLinks[1]);
            assertEquals(List.of(), correctedRuleLinks[2]);
            assertEquals(List.of(charter), correctedRuleLinks[3]);
            // 重复行作废，不删
            assertFalse(live(duplicate) && live(duplicateRule));
            // 对照不动
            assertEquals(afterLinks[0], links(after)[0]);
            assertEquals(afterLinks[2], links(after)[2]);
            assertEquals(afterLinks[3], links(after)[3]);
            Object[] handLinks = links(hand);
            assertEquals(null, handLinks[0]);
            assertEquals(false, handLinks[1]);
            assertEquals(List.of(), handLinks[2]);
            assertEquals(List.of(), handLinks[3]);
            Object[] refinedAgain = links(refinedId);
            assertEquals(refinedLinks[0], refinedAgain[0]);
            assertEquals(refinedLinks[2], refinedAgain[2]);
            assertEquals(refinedLinks[3], refinedAgain[3]);
            assertTrue(live(hand) && live(after) && live(refinedId));

            // 再物化什么也不做：陈述由修正行代表，规则的来源也在
            assertEquals(Outcome.EMPTY, materialize.materialize(kb));
            var n = graphs.neighborhood(kb, aurora, 1, t("2023-01-20T00:00:00Z"), null);
            assertTrue(n.edges().stream().noneMatch(e -> aurora.equals(e.target())),
                    "nobody on 2023-01-20");

            // 再跑一遍，一样不动
            runMigration(MIGRATION);
            assertEquals(charter, links(corrected)[0]);
            assertEquals(List.of(charter), links(corrected)[2]);
            assertEquals(List.of(corrected), rows(kb, zhang, leads), "one live leads row");
            assertEquals(List.of(correctedRule), rows(kb, zhang, worksOn));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    @Autowired
    tech.seexun.jknow.store.GraphWriteService writes;

    private UUID refinedInsert(UUID kb, UUID subject, UUID predicate, UUID object,
                               OffsetDateTime from) {
        return writes.insertFact(kb, subject, predicate, object,
                new Validity(from, "day", null, null, null, null), 1.0f).id();
    }

    /** 迁移重跑：每个迁移一个事务（与 sqlx::migrate 同一形状）。 */
    private void runMigration(String sql) throws Exception {
        try (var conn = jdbc.getDataSource().getConnection()) {
            boolean old = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(old);
            }
        }
    }
}
