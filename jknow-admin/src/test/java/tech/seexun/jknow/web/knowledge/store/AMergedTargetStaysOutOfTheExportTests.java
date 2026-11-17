package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ExportStore;

/**
 * 移植自 tests/store/a_merged_target_stays_out_of_the_export.rs（4 例）。
 *
 * <p>同库不等于在导出集里：entities_page 滤掉 merged_into 非空的行，但 merge 只改写
 * fact 的主语/宾语——属性里的实体值、派生的主语/宾语仍可能指着已合并的行。序列化照铸
 * 它的 IRI 就是一条悬空边。判法：<b>同库但不在导出集</b>也是越界——与越库同一处置，
 * 整份拒导。不重写指向留下的实体（那是另一个语义动作），也不静默省略。
 */
class AMergedTargetStaysOutOfTheExportTests extends StoreTestBase {

    private static UUID org;
    private static UUID kb;
    private static UUID survivor;
    private static UUID merged;
    private static UUID rel;
    private static UUID attr;
    private static UUID rule;
    private static UUID fact;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    void seed() throws SQLException {
        org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        survivor = Uuids.nowV7();
        merged = Uuids.nowV7();
        rel = Uuids.nowV7();
        attr = Uuids.nowV7();
        fact = Uuids.nowV7();
        rule = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'merged-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'merged-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'merged-test')",
                kb, ws);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'survivor')",
                survivor, kb);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, merged_into) "
                + "VALUES (?, ?, 'gone', ?)", merged, kb, survivor);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'knows', 'knows')",
                rel, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind) "
                + "VALUES (?, ?, 'since', 'since', 'attribute')", attr, kb);
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence) "
                + "VALUES (?, ?, ?, ?, ?, 0.9)", fact, kb, survivor, rel, survivor);
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) "
                + "VALUES (?, ?, ?, 'transitive')", rule, kb, rel);
    }

    @AfterAll
    void cleanup() {
        if (!sharedStackUp()) {
            return;
        }
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    private UUID insertDerived(UUID subject, UUID object) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", id, kb, subject, rel, object, rule);
        return id;
    }

    /** 已合并的实体不进导出集——它不在 entities 页里出现。 */
    @Test
    void a_merged_entity_is_not_emitted() throws SQLException {
        try (Connection tx = openTx()) {
            List<ExportStore.ExportEntity> page = ExportStore.entitiesPage(tx, kb, null);
            tx.rollback();
            assertTrue(page.stream().anyMatch(e -> e.id().equals(survivor)));
            assertFalse(page.stream().anyMatch(e -> e.id().equals(merged)),
                    "merged 实体必须不在导出集里");
        }
    }

    /** 指着已合并实体的属性值：同库但缺席——体检与事实页都要拦。 */
    @Test
    void a_qualifier_on_a_merged_entity_fails_closed() throws SQLException {
        jdbc.update("INSERT INTO fact_qualifiers (fact_id, qualifier_type_id, entity_id) "
                + "VALUES (?, ?, ?)", fact, attr, merged);
        try {
            try (Connection tx = openTx()) {
                AppException err = assertThrows(AppException.class,
                        () -> ExportStore.provenanceIntegrity(tx, kb));
                assertTrue(contains(err, "qualifier.entity(merged)"),
                        "要报 qualifier.entity(merged): " + err.detail());
                assertThrows(AppException.class, () -> ExportStore.factsPage(tx, kb, null),
                        "事实页也要拦下同一条边");
                tx.rollback();
            }
        } finally {
            jdbc.update("DELETE FROM fact_qualifiers WHERE fact_id = ?", fact);
        }
    }

    /** 派生的主语/宾语指着已合并的实体：同库但缺席。 */
    @Test
    void a_derived_on_a_merged_entity_fails_closed() throws SQLException {
        UUID dSubj = insertDerived(merged, survivor);
        try (Connection tx = openTx()) {
            AppException err = assertThrows(AppException.class,
                    () -> ExportStore.provenanceIntegrity(tx, kb));
            assertTrue(contains(err, "derived.subject(merged)"),
                    "要报 derived.subject(merged): " + err.detail());
            tx.rollback();
        }
        jdbc.update("DELETE FROM derived_facts WHERE id = ?", dSubj);

        UUID dObj = insertDerived(survivor, merged);
        try (Connection tx = openTx()) {
            AppException err = assertThrows(AppException.class,
                    () -> ExportStore.provenanceIntegrity(tx, kb));
            assertTrue(contains(err, "derived.object(merged)"),
                    "要报 derived.object(merged): " + err.detail());
            tx.rollback();
        }
        jdbc.update("DELETE FROM derived_facts WHERE id = ?", dObj);

        // 干净之后就放行——拒的是缺席的引用，不是库本身
        try (Connection tx = openTx()) {
            ExportStore.provenanceIntegrity(tx, kb);
            tx.rollback();
        }
    }

    /** 事实的主语/宾语指着已合并的实体（merge 没走到的旧写或绕过 store 的写）：
     *  事实页与体检都要拦。 */
    @Test
    void a_fact_on_a_merged_entity_fails_closed() throws SQLException {
        UUID badFact = Uuids.nowV7();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence) "
                + "VALUES (?, ?, ?, ?, ?, 0.9)", badFact, kb, survivor, rel, merged);
        try {
            try (Connection tx = openTx()) {
                AppException err = assertThrows(AppException.class,
                        () -> ExportStore.provenanceIntegrity(tx, kb));
                assertTrue(contains(err, "fact.object(merged)"),
                        "要报 fact.object(merged): " + err.detail());
                assertThrows(AppException.class, () -> ExportStore.factsPage(tx, kb, null),
                        "事实页也要拦下同一条边");
                tx.rollback();
            }
        } finally {
            jdbc.update("DELETE FROM facts WHERE id = ?", badFact);
        }
        try (Connection tx = openTx()) {
            ExportStore.provenanceIntegrity(tx, kb);
            tx.rollback();
        }
    }

    private Connection openTx() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    private static boolean contains(AppException e, String needle) {
        return e.getMessage().contains(needle)
                || e.detail().map(d -> d.contains(needle)).orElse(false);
    }
}
