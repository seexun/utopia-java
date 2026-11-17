package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ExportStore;

/**
 * 移植自 tests/store/export_surfaces.rs（3 例）——导出取数面：单事务快照与游标边界。
 *
 * <p>判定在导出取数层：provenance_integrity 与各 page 函数吃调用方的连接——同一条连接里
 * 种行、读页、断言。除「中途提交」那条探针外（它要两条连接），所有种子与坏行都在一个
 * <b>回滚的事务</b>里造：快照库上跑这组测试不会留下任何一行。
 *
 * <p>坏行靠 {@code SET LOCAL session_replication_role='replica'} 造：只关本事务的触发器
 * （0070 装上的那些也一并关），回滚即恢复——要模拟的正是绕过触发器进来的存量坏行。
 */
class ExportSurfacesTests extends StoreTestBase {

    @Autowired
    private DataSource dataSource;

    private Connection openTx() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    record Fixture(UUID org, UUID ws, UUID a, UUID b, UUID docA, UUID attrA) {
    }

    /** 两个库；A 库一份文档一段一实体一事实一条证据一属性，B 库空着备查。
     *  全部在调用方的事务里落——回滚即清场，一个 DELETE 都不用。 */
    private Fixture seedTx(Connection tx) throws SQLException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID docA = Uuids.nowV7();
        UUID docB = Uuids.nowV7();
        UUID chunkA = Uuids.nowV7();
        UUID entA = Uuids.nowV7();
        UUID entB = Uuids.nowV7();
        UUID factA = Uuids.nowV7();
        UUID attrA = Uuids.nowV7();
        update(tx, "INSERT INTO organizations (id, name) VALUES (?, 'export-test')", org);
        update(tx, "INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'export-test')", ws, org);
        for (UUID kb : List.of(a, b)) {
            update(tx, "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'export-test')",
                    kb, ws);
        }
        update(tx, "INSERT INTO documents (id, kb_id, filename, sha256, status, external_key) "
                + "VALUES (?, ?, 'a.md', ?, 'ready', 'file:///a.md')", docA, a, "sha-" + docA);
        update(tx, "INSERT INTO documents (id, kb_id, filename, sha256, status, external_key) "
                + "VALUES (?, ?, 'b.md', ?, 'ready', 'file:///b.md')", docB, b, "sha-" + docB);
        update(tx, "INSERT INTO chunks (id, kb_id, document_id, seq, text, doc_version) "
                + "VALUES (?, ?, ?, 0, 'x', 1)", chunkA, a, docA);
        update(tx, "INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entA, a);
        update(tx, "INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entB, b);
        update(tx, "INSERT INTO facts (id, kb_id, subject_id, object_id, confidence) "
                + "VALUES (?, ?, ?, ?, 0.9)", factA, a, entA, entA);
        update(tx, "INSERT INTO fact_evidence (fact_id, chunk_id, document_id, doc_version) "
                + "VALUES (?, ?, ?, 1)", factA, chunkA, docA);
        update(tx, "INSERT INTO relation_types (id, kb_id, key, label, kind, datatype) "
                + "VALUES (?, ?, 'headcount', 'headcount', 'attribute', 'number')", attrA, a);
        return new Fixture(org, ws, a, b, docA, attrA);
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** 文档过户的极端形态（replica）：文档挪到 B 之后，A 的证据行还指着它——A 的导出宁拒
     *  也不能把别库文档铸进本库 IRI；B 没指着它，照常放行。 */
    @Test
    void a_reassigned_document_breaks_the_edges_that_point_at_it() throws SQLException {
        try (Connection tx = openTx()) {
            Fixture f = seedTx(tx);
            update(tx, "SET LOCAL session_replication_role = 'replica'");
            update(tx, "UPDATE documents SET kb_id = ? WHERE id = ?", f.b(), f.docA());

            // A 的导出整体拒：evidence.document 还指着那份已经不属于本库的文档
            AppException err = assertThrows(AppException.class,
                    () -> ExportStore.provenanceIntegrity(tx, f.a()));
            assertTrue(err.getMessage().contains("evidence.document")
                            || String.valueOf(err.detail()).contains("evidence.document"),
                    "该报 evidence.document: " + err.getMessage() + " / " + err.detail());
            assertThrows(AppException.class, () -> ExportStore.factsPage(tx, f.a(), null));

            // B 收下了文档，但它没有任何指着 A 的行：它的体检照样过
            ExportStore.provenanceIntegrity(tx, f.b());
            tx.rollback();
        }
    }

    /** 导出中途落下的写进不了这一份。tx 起 REPEATABLE READ 快照后，另一条连接提交一条
     *  新谓词+引用它的派生——本事务的词汇表页与派生页都看不见它：没有半个进来的引用，
     *  也没有悬空的 wasGeneratedBy。这条要两条连接，种子必须提交——清场照常走。 */
    @Test
    void a_mid_stream_commit_stays_outside_the_snapshot() throws SQLException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID entA = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'export-race')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'export-race')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'export-race')",
                a, ws);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entA, a);

        // 先埋一条谓词和一条引用它的派生，作为「快照内」基线
        UUID rule0 = Uuids.nowV7();
        UUID pred0 = Uuids.nowV7();
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind) "
                + "VALUES (?, ?, 'p0', 'p0', 'relation')", pred0, a);
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) "
                + "VALUES (?, ?, ?, 'transitive')", rule0, a, pred0);
        jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", Uuids.nowV7(), a, entA, pred0, entA, rule0);

        // 导出事务：只读 REPEATABLE READ，快照从第一条语句起钉死
        Connection tx = openTx();
        try (var st = tx.createStatement()) {
            st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
        }
        ExportStore.provenanceIntegrity(tx, a);
        ExportStore.entitiesPage(tx, a, null);

        // 中途：另一条连接提交一条新谓词+引用它的派生事实
        UUID ruleLate = Uuids.nowV7();
        UUID predLate = Uuids.nowV7();
        UUID derivedLate = Uuids.nowV7();
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind) "
                + "VALUES (?, ?, 'p_late', 'p_late', 'relation')", predLate, a);
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) "
                + "VALUES (?, ?, ?, 'transitive')", ruleLate, a, predLate);
        jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", derivedLate, a, entA, predLate, entA, ruleLate);

        // 快照里：词汇表页、派生页都不该有中途进来的行——一致性是整份的
        List<ExportStore.ExportRelation> relations = ExportStore.relations(tx, a);
        assertTrue(relations.stream().noneMatch(r -> r.id().equals(predLate)),
                "中途提交的谓词不许进这份导出");
        List<ExportStore.ExportDerived> derived = ExportStore.derivedPage(tx, a, null);
        assertTrue(derived.stream().noneMatch(d -> d.id().equals(derivedLate)),
                "中途提交的派生不许进这份导出——也就不会有悬空的 wasGeneratedBy");
        assertEquals(1, derived.size(), "快照内的那条还在");
        tx.rollback();
        tx.close();

        // 新事务是新的快照：两条都该在
        try (Connection tx2 = openTx()) {
            List<ExportStore.ExportRelation> relations2 = ExportStore.relations(tx2, a);
            assertTrue(relations2.stream().anyMatch(r -> r.id().equals(predLate)));
            assertEquals(2, ExportStore.derivedPage(tx2, a, null).size());
            tx2.rollback();
        }

        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", a);
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    /** NIL 是合法 uuid——schema 不拦它当主键。按序它排在最前；首页谓词若是 id &gt; 哨兵，
     *  这一行就永远进不了任何一页。而指向它的引用照样解析过去：节点缺席、边在场，导出里
     *  就悬一条没有本体的引用。所有按 id 翻页的取数口第一页都得把它翻出来。 */
    @Test
    void a_nil_id_row_still_reaches_the_first_page() throws SQLException {
        try (Connection tx = openTx()) {
            Fixture f = seedTx(tx);
            UUID nil = new UUID(0L, 0L);

            // 每张走 id 游标的表各埋一行 NIL 主键；引用一律指回这些 NIL 行自己，
            // 外键不因 NIL 失效——它们跟其他行一样合法
            update(tx, "INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'nil-e')",
                    nil, f.a());
            update(tx, "INSERT INTO documents (id, kb_id, filename, sha256, status, external_key) "
                    + "VALUES (?, ?, 'nil.md', 'nil-sha', 'ready', 'file:///nil.md')", nil, f.a());
            update(tx, "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence) "
                    + "VALUES (?, ?, ?, ?, ?, 0.9)", nil, f.a(), nil, f.attrA(), nil);
            UUID predR = Uuids.nowV7();
            UUID ruleR = Uuids.nowV7();
            update(tx, "INSERT INTO relation_types (id, kb_id, key, label, kind) "
                    + "VALUES (?, ?, 'rel_p', 'rel_p', 'relation')", predR, f.a());
            update(tx, "INSERT INTO rules (id, kb_id, predicate_id, kind) "
                    + "VALUES (?, ?, ?, 'transitive')", ruleR, f.a(), predR);
            update(tx, "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                    + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", nil, f.a(), nil, predR, nil, ruleR);

            assertTrue(ExportStore.entitiesPage(tx, f.a(), null).stream().anyMatch(e -> e.id().equals(nil)),
                    "entities 首页漏掉 NIL 行");
            assertTrue(ExportStore.documentsPage(tx, f.a(), null).stream().anyMatch(d -> d.id().equals(nil)),
                    "documents 首页漏掉 NIL 行");
            assertTrue(ExportStore.factsPage(tx, f.a(), null).stream().anyMatch(x -> x.id().equals(nil)),
                    "facts 首页漏掉 NIL 行");
            assertTrue(ExportStore.derivedPage(tx, f.a(), null).stream().anyMatch(d -> d.id().equals(nil)),
                    "derived 首页漏掉 NIL 行");
            tx.rollback();
        }
    }
}
