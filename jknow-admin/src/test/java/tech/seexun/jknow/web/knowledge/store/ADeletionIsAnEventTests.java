package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.DocumentsWriteService;

/**
 * 移植自 tests/store/a_deletion_is_an_event.rs（375 行，#268）——删除文档是认知轴上的
 * 一个事件，不是减法：文档打墓碑、分块打标、<b>什么内容都不清</b>，只作废「每条出处
 * 都已删除」的事实；撤销原路回来；同内容重传复活墓碑；判据看文档不看分块。
 *
 * <p>等价点：delete/restore/purge/page/get 走 M2 的 DocumentsWriteService/DocumentsService
 * （documents.rs 逐字）；Rust documents::create 的同内容复活走 createWithVersion 的
 * reviveOnTombstone 预检（documents.rs:79-89 同一形状）。
 */
class ADeletionIsAnEventTests extends HeartStoreTestBase {

    @Autowired
    DocumentsWriteService writes;
    @Autowired
    DocumentsService documents;

    record Fx(UUID org, UUID user, UUID kb, UUID src, UUID etype, UUID rel) {
    }

    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID src = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID rel = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'deletion-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'deletion-test')",
                ws, org);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'd')
                """, user, org, user + "@deletion.test");
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'deletion-test')",
                kb, ws);
        jdbc.update("INSERT INTO sources (id, kb_id, kind, name) VALUES (?, ?, 'folder', 'f')",
                src, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'knows', 'knows')",
                rel, kb);
        return new Fx(org, user, kb, src, etype, rel);
    }

    private UUID document(Fx f, String name, String sha) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)
                 VALUES (?, ?, ?, ?, ?, 'ready')
                """, id, f.kb(), f.src(), name, sha);
        return id;
    }

    private UUID chunk(Fx f, UUID doc, int version) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO chunks (id, kb_id, document_id, seq, text, doc_version, superseded_at)
                 VALUES (?, ?, ?, 0, 'the text stays', ?, CASE WHEN ? = 1 THEN NULL ELSE now() END)
                """, id, f.kb(), doc, version, version);
        return id;
    }

    private UUID entity(Fx f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), f.etype(), name);
        return id;
    }

    /** 一条事实，出处是给定的几个分块。 */
    private UUID fact(Fx f, UUID s, UUID o, List<UUID> evidence) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                 VALUES (?, ?, ?, ?, ?, 0.9)
                """, id, f.kb(), s, f.rel(), o);
        for (UUID c : evidence) {
            jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, quote) VALUES (?, ?, 'q')",
                    id, c);
        }
        return id;
    }

    private boolean live(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT invalidated_at IS NULL FROM facts WHERE id = ?", Boolean.class, id));
    }

    private record ChunkLive(boolean live, String text) {
    }

    private ChunkLive chunkLive(UUID id) {
        return jdbc.queryForObject(
                "SELECT superseded_at IS NULL, text FROM chunks WHERE id = ?",
                (rs, i) -> new ChunkLive(rs.getBoolean(1), rs.getString(2)), id);
    }

    private boolean listed(Fx f, UUID id) {
        return documents.page(f.kb(), null, null, null, false, 200, 0).docs()
                .stream().anyMatch(d -> d.id().equals(id));
    }

    @Test
    void a_deletion_is_an_event() {
        Fx f = seed();
        try {
            UUID a = document(f, "a.md", "sha-a");
            UUID b = document(f, "b.md", "sha-b");
            UUID a1 = chunk(f, a, 1);
            UUID b1 = chunk(f, b, 1);
            UUID x = entity(f, "X");
            UUID y = entity(f, "Y");
            UUID z = entity(f, "Z");
            UUID w = entity(f, "W");
            // 只有甲作证；甲乙都作证；甲作证但删之前就作废了
            UUID onlyA = fact(f, x, y, List.of(a1));
            UUID both = fact(f, x, z, List.of(a1, b1));
            UUID alreadyGone = fact(f, x, w, List.of(a1));
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", alreadyGone);

            // 1. 删甲
            var report = writes.delete(f.kb(), a, f.user());
            assertEquals(1, report.invalidatedFacts(), "only the fact with no other source");
            assertEquals(1, report.supersededChunks());
            assertFalse(live(onlyA));
            assertTrue(live(both), "b still vouches for it");
            assertFalse(listed(f, a), "gone from the library");
            assertTrue(listed(f, b));
            var a1Live = chunkLive(a1);
            assertFalse(a1Live.live());
            assertEquals("the text stays", a1Live.text(), "nothing is cleared");
            assertTrue(documents.get(a).deletedAt() != null, "a tombstone, not a hole");
            // Rust 断言按 uuid[] 比较；Java 侧按 uuid[]::text 的形状比（"{u1,u2}"）
            assertEquals("{" + onlyA + "}", jdbc.queryForObject(
                    "SELECT invalidated_facts::text FROM document_deletions WHERE document_id = ?",
                    String.class, a));
            assertEquals("{" + a1 + "}", jdbc.queryForObject(
                    "SELECT superseded_chunks::text FROM document_deletions WHERE document_id = ?",
                    String.class, a));
            assertTrue(jdbc.queryForObject(
                    "SELECT reverted_at FROM document_deletions WHERE document_id = ?",
                    java.time.OffsetDateTime.class, a) == null);

            // 2. 删两次
            assertThrows(AppException.class, () -> writes.delete(f.kb(), a, f.user()));

            // 3. 撤销原路回来；删之前就作废的不被误救
            var doc = writes.restore(f.kb(), a);
            assertTrue(doc.deletedAt() == null);
            assertTrue(live(onlyA));
            assertTrue(live(both));
            assertFalse(live(alreadyGone), "retired before the deletion, so not on the list");
            assertTrue(chunkLive(a1).live());
            assertTrue(listed(f, a));
            assertTrue(jdbc.queryForObject(
                    "SELECT reverted_at FROM document_deletions WHERE document_id = ?",
                    java.time.OffsetDateTime.class, a) != null);
            assertThrows(AppException.class, () -> writes.restore(f.kb(), a));

            // 4. 删掉再传同一份内容：同一个 id 回来
            writes.delete(f.kb(), a, f.user());
            assertFalse(live(onlyA));
            var back = writes.createWithVersion(f.kb(), "a-again.md", "text/markdown", 14,
                    "sha-a", f.src(), null, null);
            assertEquals(a, back.id(), "the same document, revived");
            assertTrue(back.deletedAt() == null);
            assertTrue(live(onlyA));
            assertTrue(chunkLive(a1).live());

            // 5. 判据看文档：乙的证据停在旧版分块上，删甲时乙还活着，事实不作废
            UUID b0 = chunk(f, b, 0);
            UUID staleButSourced = fact(f, y, z, List.of(a1, b0));
            var report2 = writes.delete(f.kb(), a, f.user());
            assertEquals(1, report2.invalidatedFacts(), "only_a again; the stale one keeps its source");
            assertTrue(live(staleButSourced),
                    "an old version of b is still b, not a missing source");
        } finally {
            // 组织删除不级联到库：库自己删，别在共用的测试库里留墓碑
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    private void sharedSourceRestore(boolean purgeLast) {
        Fx f = seed();
        try {
            UUID a = document(f, "a.md", "restore-a");
            UUID b = document(f, "b.md", "restore-b");
            UUID a1 = chunk(f, a, 1);
            UUID b1 = chunk(f, b, 1);
            UUID x = entity(f, "X");
            UUID y = entity(f, "Y");
            UUID shared = fact(f, x, y, List.of(a1, b1));
            UUID z = entity(f, "Z");
            UUID onlyB = fact(f, x, z, List.of(b1));
            UUID alreadyGone = fact(f, y, x, List.of(a1, b1));
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", alreadyGone);
            assertEquals(0, writes.delete(f.kb(), a, null).invalidatedFacts());
            assertEquals(2, writes.delete(f.kb(), b, null).invalidatedFacts());
            assertFalse(live(shared));
            if (purgeLast) {
                writes.purge(f.kb(), b);
            }
            writes.restore(f.kb(), a);
            boolean restoredShared = live(shared);
            boolean restoredOld = live(alreadyGone);
            assertFalse(live(onlyB), "b's exclusive fact stays retired");
            assertTrue(listed(f, a));
            assertFalse(listed(f, b));
            assertTrue(chunkLive(a1).live());
            assertFalse(restoredOld, "a fact retired before either deletion stays retired");
            assertTrue(restoredShared,
                    "restoring the first source must recover the shared fact retired by deleting the second source");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    @Test
    void restoring_either_source_recovers_a_shared_fact() {
        sharedSourceRestore(false);
    }

    @Test
    void restoring_a_source_recovers_a_fact_after_the_last_source_was_purged() {
        sharedSourceRestore(true);
    }
}
