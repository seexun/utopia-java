package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_signature_holds_on_every_path.rs（245 行，1 例）——关系的签名
 * （domain / range）在抽取之外的写路径上也得算数（#190 / #196）。
 *
 * <p>抽取写入时按 #138 掰正方向或留空谓词，但<b>合并</b>会换掉主语的类型，守卫只装在
 * 抽取上就被它绕过去。这里守两件事：
 * <ol>
 *   <li>合并之后，被换了主语的事实若违反签名 → axiom_violations 里多一条 signature</li>
 *   <li>一致性检查（R0）也能量出签名违规，事实撤了它就被清掉</li>
 * </ol>
 */
class ASignatureHoldsOnEveryPathTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    @Autowired
    ResolutionService resolution;

    record Fixture(UUID org, UUID kb, UUID company, UUID person, UUID employee, UUID acme,
                   UUID alice, UUID bob) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID employee = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID alice = Uuids.nowV7();
        UUID bob = Uuids.nowV7();
        UUID src = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'signature-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'signature-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'signature-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{company, "company", "Company"},
                {person, "person", "Person"}}) {
            jdbc.update(
                    "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        jdbc.update(
                "INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'employee', 'employee')",
                employee, kb);
        jdbc.update(
                "INSERT INTO relation_type_domains (relation_type_id, entity_type_id) VALUES (?, ?)",
                employee, company);
        jdbc.update(
                "INSERT INTO relation_type_ranges (relation_type_id, entity_type_id) VALUES (?, ?)",
                employee, person);
        for (Object[] row : new Object[][]{{acme, company, "Acme"}, {alice, person, "Alice"},
                {bob, person, "Bob"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        jdbc.update("INSERT INTO sources (id, kb_id, name) VALUES (?, ?, 'signature-test')",
                src, kb);
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)
                 VALUES (?, ?, ?, 'note.md', 'signature', 'ready')
                """, doc, kb, src);
        jdbc.update("""
                INSERT INTO chunks (id, kb_id, document_id, seq, text)
                 VALUES (?, ?, ?, 0, 'Alice is an employee of Acme. Bob is an employee of Alice.')
                """, chunk, kb, doc);
        return new Fixture(org, kb, company, person, employee, acme, alice, bob);
    }

    private List<UUID> openSignatureBreaks(UUID kb) {
        return jdbc.queryForList(
                "SELECT left_fact FROM axiom_violations WHERE kb_id = ? AND kind = 'signature'"
                        + " AND status = 'open'",
                UUID.class, kb);
    }

    @Test
    void aMergeThatBreaksTheSignatureIsRecorded() {
        Fixture f = seed();
        try {
            // Acme employee Alice：主语 company、宾语 person，合签名
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id)
                     VALUES (?, ?, ?, ?, ?)
                    """, Uuids.nowV7(), f.kb(), f.acme(), f.employee(), f.alice());
            assertTrue(openSignatureBreaks(f.kb()).isEmpty());

            // 1. 合并：把 Acme 并进 Bob（person），Acme employee Alice 的主语变成 person
            resolution.mergeEntities(f.kb(), f.acme(), f.bob(), null, "test");
            var edges = jdbc.queryForList("""
                    SELECT subject_id FROM facts
                      WHERE kb_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                      ORDER BY recorded_at
                    """, UUID.class, f.kb(), f.employee());
            assertEquals(List.of(f.bob()), edges, "the merge moved the subject onto Bob");
            UUID movedFact = jdbc.queryForObject(
                    "SELECT id FROM facts WHERE kb_id = ? AND predicate_id = ?"
                            + " AND invalidated_at IS NULL",
                    UUID.class, f.kb(), f.employee());
            assertEquals(List.of(movedFact), openSignatureBreaks(f.kb()),
                    "a merge that breaks the signature must show up as an open violation");

            // 2. 一致性检查量得出它，撤了事实就清掉
            var report = reasoning.run(f.kb());
            assertEquals(1, report.found());
            assertEquals(0, report.inserted(),
                    "already recorded by the merge; the check must not duplicate it");
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", movedFact);
            var report2 = reasoning.run(f.kb());
            assertEquals(0, report2.found());
            assertEquals(1, report2.cleared(), "a retracted fact takes its violation with it");
            assertTrue(openSignatureBreaks(f.kb()).isEmpty());

            // 未分类的实体不算违反：类型是 NULL 的主语没有东西可比
            UUID untyped = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'Nobody')",
                    untyped, f.kb());
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, Uuids.nowV7(), f.kb(), untyped, f.employee(), f.alice());
            assertTrue(reasoning.signatureBreaks(f.kb(), null).isEmpty(),
                    "an untyped subject is unknown, not wrong");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
