package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_proof_reaches_the_sentence.rs（253 行，1 例）——R2：一条派生的
 * 证明要能一路读到原句。顺序对；叶子是原句（证据带引句）；撤了的前提照样列出并打标记。
 * 断言逐条对译。
 */
class AProofReachesTheSentenceTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID partOf, UUID a, UUID b, UUID c, UUID doc,
                   UUID chunkAb, UUID chunkBc) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID c = Uuids.nowV7();
        UUID src = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunkAb = Uuids.nowV7();
        UUID chunkBc = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'proof-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'proof-test')", ws, org);
        jdbc.update("""
                INSERT INTO knowledge_bases (id, workspace_id, name, materialize_inferences)
                 VALUES (?, ?, 'proof-test', TRUE)
                """, kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, is_transitive)
                 VALUES (?, ?, 'part_of', 'part of', TRUE)
                """, partOf, kb);
        for (Object[] row : new Object[][]{{a, "FarmBeats"}, {b, "Azure"}, {c, "Microsoft"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, etype, row[1]);
        }
        jdbc.update("INSERT INTO sources (id, kb_id, name) VALUES (?, ?, 'proof-test')", src, kb);
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)
                 VALUES (?, ?, ?, 'press.md', 'proof', 'ready')
                """, doc, kb, src);
        for (Object[] row : new Object[][]{{chunkAb, 0, "FarmBeats is part of Azure."},
                {chunkBc, 1, "Azure is part of Microsoft."}}) {
            jdbc.update(
                    "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, ?, ?)",
                    row[0], kb, doc, row[1], row[2]);
        }
        return new Fixture(org, kb, partOf, a, b, c, doc, chunkAb, chunkBc);
    }

    /** 一条断言，带一句原文当证据。 */
    private UUID asserted(Fixture f, UUID subject, UUID object, UUID chunk, String quote) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                 VALUES (?, ?, ?, ?, ?, 0.9)
                """, id, f.kb(), subject, f.partOf(), object);
        jdbc.update("""
                INSERT INTO fact_evidence (fact_id, chunk_id, quote, proposed_predicate, document_id, doc_version)
                 VALUES (?, ?, ?, 'part of', ?, 1)
                """, id, chunk, quote, f.doc);
        return id;
    }

    @Test
    void aProofReachesTheSentence() {
        Fixture f = seed();
        try {
            UUID ab = asserted(f, f.a(), f.b(), f.chunkAb(), "FarmBeats is part of Azure");
            UUID bc = asserted(f, f.b(), f.c(), f.chunkBc(), "Azure is part of Microsoft");
            reasoning.materialize(f.kb());

            var derived = reasoning.derivedForEntity(f.kb(), f.a(), null, null);
            var ac = derived.stream()
                    .filter(d -> d.subjectId().equals(f.a()) && f.c().equals(d.objectId()))
                    .findAny().orElseThrow(() -> new IllegalStateException(
                            "A part_of C should be derived"));

            // 1. 顺序对，2. 叶子是原句
            var proof = reasoning.proof(f.kb(), ac.id());
            assertTrue(proof != null, "live derivation has a proof");
            assertEquals(ac.id(), proof.derived().id());
            assertEquals(2, proof.steps().size());
            assertEquals(ab, proof.steps().get(0).factId,
                    "the chain starts where the derivation starts");
            assertEquals(bc, proof.steps().get(1).factId);
            assertEquals("FarmBeats", proof.steps().get(0).subject);
            assertEquals("Azure", proof.steps().get(0).object);
            assertEquals("part of", proof.steps().get(0).predicate);
            assertEquals(1, proof.steps().get(0).evidence.size());
            assertEquals("FarmBeats is part of Azure",
                    proof.steps().get(0).evidence.get(0).quote(),
                    "the leaf of a proof is the sentence it was extracted from");
            assertEquals(f.chunkAb(), proof.steps().get(0).evidence.get(0).chunkId());
            assertEquals(f.chunkBc(), proof.steps().get(1).evidence.get(0).chunkId());
            assertTrue(proof.steps().stream().noneMatch(s -> s.retracted));

            // 一个不存在的 id 不是错误，是「没有证明」
            assertNull(reasoning.proof(f.kb(), Uuids.nowV7()));

            // 3. 撤掉一条前提：派生失效，证明还在，且那一步打上标记
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", bc);
            reasoning.materialize(f.kb());
            Boolean gone = jdbc.queryForObject(
                    "SELECT invalidated_at IS NOT NULL FROM derived_facts WHERE id = ?",
                    Boolean.class, ac.id());
            assertTrue(gone, "a derivation falls with its premise");
            var history = reasoning.proof(f.kb(), ac.id());
            assertTrue(history != null, "an invalidated derivation still explains itself");
            assertFalse(history.steps().get(0).retracted);
            assertTrue(history.steps().get(1).retracted,
                    "the retracted premise is marked, not hidden");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
