package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自顶层 tests/a_row_a_rule_implied_follows_its_statements.rs（177 行）——迁移 0101：
 * 这之前规则算出的行，见证跟着它读的陈述走。照这之前的来路造（物化算出隐含行，再把
 * 见证改回那时写下的样子），重跑迁移；再跑一遍一样不动。迁移 SQL 从 classpath 读
 * V0092 重跑（include_str! 对等）。
 */
class ARowARuleImpliedFollowsItsStatementsTests extends HeartStoreTestBase {

    private static final String MIGRATION =
            readMigration("V0092__a_row_a_rule_implies_follows_its_statements.sql");

    private static String readMigration(String name) {
        try (var in = new ClassPathResource("db/migration/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;
    @Autowired
    GraphWriteService writes;

    private Object[] anchor(UUID fact) {
        return jdbc.queryForObject("SELECT attested_from, attested_by FROM facts WHERE id = ?",
                (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class), rs.getString(2)},
                fact);
    }

    /** 主语到宾语之间活着的那一行类型化事实。 */
    private UUID typedRow(UUID kb, UUID subject, UUID object) {
        return jdbc.queryForObject("""
                SELECT id FROM facts
                  WHERE kb_id = ? AND subject_id = ? AND object_id = ?
                    AND layer = 'typed' AND invalidated_at IS NULL
                """, UUID.class, kb, subject, object);
    }

    @Test
    void aRowARuleImpliedBeforeFollowsItsStatements() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        UUID origin = Uuids.nowV7();
        UUID lin = Uuids.nowV7();
        UUID aster = Uuids.nowV7();
        UUID brightway = Uuids.nowV7();
        UUID meridian = Uuids.nowV7();
        UUID film = Uuids.nowV7();
        UUID uk = Uuids.nowV7();
        UUID signedRule = Uuids.nowV7();
        UUID kindRule = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'rule-rows-before-0101')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'rule-rows-before-0101')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'rule-rows-before-0101')",
                kb, ws);
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
                    (?, ?, 'Brightway', ?, NULL),
                    (?, ?, 'Meridian Systems', ?, NULL),
                    (?, ?, 'Loud Tour', NULL, 'British film'),
                    (?, ?, 'United Kingdom', NULL, NULL)
                """, lin, kb, person, aster, kb, company, brightway, kb, company, meridian, kb,
                company, film, kb, uk, kb);
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
            OffsetDateTime processed = OffsetDateTime.parse("2026-09-30T03:00:00Z");
            OffsetDateTime signedDay = OffsetDateTime.parse("2026-09-04T00:00:00Z");
            OffsetDateTime writtenDay = OffsetDateTime.parse("2026-08-01T00:00:00Z");
            UUID signedAster = writes.insertOpenStatement(kb, lin, "signed with", aster, null,
                    null, 0.9f).id();
            UUID later = writes.insertOpenStatement(kb, lin, "signed with", brightway, null, null,
                    0.9f).id();
            UUID byHand = writes.insertFact(kb, lin, worksFor, meridian,
                    new Validity(null, null, null, null, writtenDay, null), 0.9f).id();
            writes.insertOpenStatement(kb, lin, "signed with", meridian, null, null, 0.9f);
            materialize.materialize(kb);
            // 物化之后陈述才作证：只动陈述，那时算出的行不跟
            assertNotNull(writes.attestStatement(later, signedDay, "签约日 2026年9月4日"));
            // 这之前写下的样子：规则算的行都是处理那一刻，没有日期的名字
            jdbc.update("UPDATE facts SET attested_from = ?, attested_by = NULL WHERE kb_id = ? AND implied",
                    processed, kb);
            UUID undated = typedRow(kb, lin, aster);
            UUID datedLater = typedRow(kb, lin, brightway);
            UUID byKind = typedRow(kb, film, uk);
            assertEquals(byHand, typedRow(kb, lin, meridian));
            for (UUID row : new UUID[]{undated, datedLater, byKind}) {
                assertEquals(processed, anchor(row)[0]);
                assertEquals(null, anchor(row)[1]);
            }

            // 跑两遍：第二遍一样不动
            for (int i = 0; i < 2; i++) {
                runMigration(MIGRATION);
                assertEquals(null, anchor(undated)[0], "an undated statement");
                assertEquals(null, anchor(undated)[1]);
                assertEquals(signedDay, anchor(datedLater)[0],
                        "the statement attested after the row was computed");
                assertEquals("签约日 2026年9月4日", anchor(datedLater)[1]);
                assertEquals(null, anchor(byKind)[0], "a kind word");
                assertEquals(null, anchor(byKind)[1]);
                assertEquals(writtenDay, anchor(byHand)[0], "a person's row a rule merged into");
                assertEquals(null, anchor(byHand)[1]);
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /** 迁移重跑：一个事务（与 sqlx::migrate 同一形状）。 */
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
