package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 移植自 tests/store/an_export_carries_the_whole_ledger.rs（1 例）——导出读的是<b>整本
 * 账</b>（0020 / #308），打在真库上。
 *
 * <p>界面那些读路径全都过滤 invalidated_at——它们答的是「现在是什么样」。导出如果照抄
 * 那套过滤，导出去的就是一张干净自信的图，而三月被撤回的那一半一个字都不剩。审计要的
 * 恰恰是那一半。所以这里断言的都是「<b>没有</b>被过滤掉」：撤回的事实在、删掉的文档在、
 * 本体没接住的原话在、派生的前提在。合并掉的实体是唯一该消失的东西——它的事实早已搬到
 * 留下的那个身上，再导一遍就是把同一条事实说两遍。
 */
class AnExportCarriesTheWholeLedgerTests extends StoreTestBase {

    private static UUID org;
    private static UUID kb;
    private static UUID live;
    private static UUID retracted;
    private static UUID bare;
    private static UUID kept;
    private static UUID swallowed;
    private static UUID deletedDoc;
    private static UUID derived;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    void seed() throws SQLException {
        org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        kept = Uuids.nowV7();
        swallowed = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID group = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        deletedDoc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        live = Uuids.nowV7();
        retracted = Uuids.nowV7();
        bare = Uuids.nowV7();
        UUID rule = Uuids.nowV7();
        derived = Uuids.nowV7();
        UUID tag = Uuids.nowV7();

        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'export-test')", org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'export-test')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'export-test')",
                    kb, ws);
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, iri) VALUES (?, ?, ?, ?, ?)",
                    person, kb, "person", "Person", "https://schema.org/Person");
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, iri) VALUES (?, ?, ?, ?, ?)",
                    company, kb, "company", "Company", null);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, iri, functional) "
                    + "VALUES (?, ?, 'works_for', 'works for', 'https://schema.org/worksFor', TRUE)",
                    worksFor, kb);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, is_transitive) "
                    + "VALUES (?, ?, 'part_of', 'part of', TRUE)", partOf, kb);
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    kept, kb, person, "Lin Zhao");
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    swallowed, kb, person, "L. Zhao");
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    acme, kb, company, "Acme");
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    group, kb, company, "Acme Group");
            // 合并掉的那个：事实早已搬走，导出里不该再出现
            jdbc.update("UPDATE entities SET merged_into = ? WHERE id = ?", kept, swallowed);

            jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status, external_key, "
                            + "deleted_at) VALUES (?, ?, ?, ?, 'ready', ?, "
                            + "CASE WHEN ? THEN now() ELSE NULL END)",
                    doc, kb, "handbook.md", "sha-" + tag + "-handbook.md",
                    "file:///handbook.md", false);
            jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status, external_key, "
                            + "deleted_at) VALUES (?, ?, ?, ?, 'ready', ?, "
                            + "CASE WHEN ? THEN now() ELSE NULL END)",
                    deletedDoc, kb, "old.md", "sha-" + tag + "-old.md", "file:///old.md", true);
            jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) "
                    + "VALUES (?, ?, ?, 0, 'Lin Zhao works for Acme.')", chunk, kb, doc);

            jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, "
                    + "confidence, invalidated_at) VALUES (?, ?, ?, ?, ?, 0.9, NULL)",
                    live, kb, kept, worksFor, acme);
            jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, "
                    + "confidence, invalidated_at) VALUES (?, ?, ?, ?, ?, 0.9, now())",
                    retracted, kb, kept, worksFor, group);
            // 本体没接住的那条（0010）：谓词为空，原话在证据里
            jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, "
                    + "confidence, invalidated_at) VALUES (?, ?, ?, NULL, ?, 0.9, NULL)",
                    bare, kb, kept, group);
            jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id, "
                            + "proposed_predicate) VALUES (?, ?, 'Lin Zhao works for Acme.', ?, NULL), "
                            + "(?, ?, 'Lin Zhao advises Acme Group.', ?, 'advises')",
                    live, chunk, doc, bare, chunk, doc);

            jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) "
                    + "VALUES (?, ?, ?, 'transitive')", rule, kb, partOf);
            jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                    + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", derived, kb, acme, partOf, group, rule);
            jdbc.update("INSERT INTO fact_derivations (derived_fact_id, premise_fact_id, seq) "
                    + "VALUES (?, ?, 0)", derived, live);
        }
    }

    @AfterAll
    void cleanup() {
        if (!sharedStackUp()) {
            return;
        }
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        int gone = jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        assertEquals(1, gone, "一次性 org 没删掉");
    }

    /** 每个取数口各开一个只读事务（Rust pool.begin() 对等）。 */
    private Connection openTx() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    @Test
    void an_export_reads_the_whole_ledger_not_the_current_view() throws SQLException {
        // 1. 事实：撤回的那条**在**。界面把它藏起来是对的，导出把它藏起来就是骗人
        List<ExportStore.ExportFact> facts;
        try (Connection tx = openTx()) {
            facts = ExportStore.factsPage(tx, kb, null);
            tx.rollback();
        }
        assertTrue(facts.stream().anyMatch(f -> f.id().equals(live)));
        assertTrue(facts.stream().anyMatch(f -> f.id().equals(retracted)),
                "撤回的事实必须导出——它带着 invalidated_at，读的人自己判断");
        ExportStore.ExportFact retractedRow = facts.stream().filter(f -> f.id().equals(retracted)).findFirst()
                .orElseThrow();
        assertNotNull(retractedRow.invalidatedAt());

        // 2. 证据跟着事实走：出处文档与原句都在同一行上
        ExportStore.ExportFact liveRow = facts.stream().filter(f -> f.id().equals(live)).findFirst()
                .orElseThrow();
        assertEquals(1, liveRow.documents().size());
        assertEquals(List.of("Lin Zhao works for Acme."), liveRow.quotes());

        // 3. 本体没接住的那条：谓词为空，但模型的原话拿得到——导出里它不会
        //    被铸成一个假谓词，也不会消失
        ExportStore.ExportFact bareRow = facts.stream().filter(f -> f.id().equals(bare)).findFirst()
                .orElseThrow();
        assertTrue(bareRow.predicateId() == null);
        assertEquals("advises", bareRow.surfacePredicate());

        // 4. 实体：合并掉的那个是唯一该消失的东西
        List<ExportStore.ExportEntity> entities;
        try (Connection tx = openTx()) {
            entities = ExportStore.entitiesPage(tx, kb, null);
            tx.rollback();
        }
        assertTrue(entities.stream().anyMatch(e -> e.id().equals(kept)));
        assertTrue(entities.stream().noneMatch(e -> e.id().equals(swallowed)),
                "合并掉的实体再导一遍，等于把同一条事实说两遍");

        // 5. 文档：删掉的留着墓碑（#268）。抹掉出处等于抹掉证据链
        List<ExportStore.ExportDocument> docs;
        try (Connection tx = openTx()) {
            docs = ExportStore.documentsPage(tx, kb, null);
            tx.rollback();
        }
        ExportStore.ExportDocument deleted = docs.stream().filter(d -> d.id().equals(deletedDoc)).findFirst()
                .orElseThrow();
        assertNotNull(deleted.deletedAt());

        // 6. 派生：带着规则和前提，审计顺着它走得到断言
        List<ExportStore.ExportDerived> derivedRows;
        try (Connection tx = openTx()) {
            derivedRows = ExportStore.derivedPage(tx, kb, null);
            tx.rollback();
        }
        ExportStore.ExportDerived d = derivedRows.stream().filter(x -> x.id().equals(derived)).findFirst()
                .orElseThrow();
        assertEquals("transitive", d.rule());
        assertEquals(List.of(live), d.premises());

        // 7. 词汇表：导入来的类留着原 IRI，公理位照抄
        List<ExportStore.ExportClass> classes;
        List<ExportStore.ExportRelation> relations;
        try (Connection tx = openTx()) {
            classes = ExportStore.classes(tx, kb);
            relations = ExportStore.relations(tx, kb);
            tx.rollback();
        }
        ExportStore.ExportClass personClass = classes.stream().filter(x -> x.key().equals("person")).findFirst()
                .orElseThrow();
        assertEquals("https://schema.org/Person", personClass.iri());
        assertTrue(relations.stream().anyMatch(r -> r.key().equals("works_for") && r.functional()));
        assertTrue(relations.stream().anyMatch(r -> r.key().equals("part_of") && r.isTransitive()));
    }
}
