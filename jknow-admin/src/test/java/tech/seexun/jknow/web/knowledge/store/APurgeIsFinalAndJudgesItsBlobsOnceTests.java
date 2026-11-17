package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.DocumentsWriteService;

/**
 * #516 purge 一句话判完所有指纹，答案与逐个问一模一样（a_purge_judges_its_blobs_once.rs
 * 6 例原值移植）+ #268 下半「真删是终点」的记录面（a_purge_is_final.rs 中
 * DocumentsWriteTests.purgeOnlyReclaims… 未覆盖的半边：墓碑留档/事实保持作废/删除账本
 * 留行/已删除视图剔除 purged 墓碑）——M5 任务 6 台账收口。
 *
 * <p>原文按内容寻址、跨库共用，一份指纹只有在<b>没有任何地方</b>再引用时才交给调用方去删
 * 文件。判错的两个方向不对称：多留是漏磁盘，多交是把别人还在用的原文删掉。
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class APurgeIsFinalAndJudgesItsBlobsOnceTests extends StoreTestBase {

    @Autowired
    DocumentsWriteService writes;
    @Autowired
    DocumentsService documents;

    private UUID org;
    private UUID ws;
    private UUID kb;
    private String tag = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'purge-blobs-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'purge-blobs-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                + " VALUES (?, ?, 'purge-blobs-test')", kb, ws);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    private String sha(String name) {
        return "sha-" + name + "-" + tag;
    }

    private UUID document(UUID base, String name, String sha) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status)"
                + " VALUES (?, ?, ?, ?, 'ready')", id, base, name, sha);
        return id;
    }

    private void version(UUID doc, int version, String sha) {
        jdbc.update("INSERT INTO document_versions (id, document_id, version, sha256)"
                + " VALUES (?, ?, ?, ?)", Uuids.nowV7(), doc, version, sha);
    }

    private List<String> purge(UUID base, UUID doc) {
        writes.delete(base, doc, null);
        return writes.purge(base, doc).blobs();
    }

    /// 从前的判法，逐个指纹问一次；第 6 条拿它当真值
    private List<String> judgedOneByOne(List<String> shas) {
        List<String> out = new ArrayList<>();
        for (String s : shas) {
            Boolean referenced = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM documents WHERE sha256 = ? AND purged_at IS NULL)
                     OR EXISTS (SELECT 1 FROM document_versions WHERE sha256 = ?)
                    """, Boolean.class, s, s);
            if (!referenced) {
                out.add(s);
            }
        }
        return out;
    }

    private UUID otherBase() {
        UUID other = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                + " VALUES (?, ?, 'purge-blobs-test')", other, ws);
        return other;
    }

    @Test
    void aBlobAnotherDocumentStillHoldsIsNeverCollected() {
        // 同库里两篇活文档不能同 sha（documents_kb_sha_idx），所以同库的另一个持有者
        // 只能是别人的历史版本
        String shared = sha("shared-v1");
        UUID a = document(kb, "a.md", shared);
        UUID b = document(kb, "b.md", sha("b-now"));
        version(b, 1, shared);
        assertTrue(purge(kb, a).isEmpty(), "b still holds the blob in a version");
    }

    @Test
    void aBlobSharedAcrossKnowledgeBasesIsNeverCollected() {
        String shared = sha("shared-kb");
        UUID otherKb = otherBase();
        UUID a = document(kb, "a.md", shared);
        document(otherKb, "e.md", shared);
        assertTrue(purge(kb, a).isEmpty(),
                "the holder in another base counts; the check carries no kb_id on purpose");
    }

    @Test
    void aBlobOnlyAnOldVersionHeldIsCollected() {
        String current = sha("cur");
        String old = sha("old");
        UUID a = document(kb, "a.md", current);
        version(a, 1, old);
        List<String> blobs = new ArrayList<>(purge(kb, a));
        blobs.sort(Comparator.naturalOrder());
        List<String> expected = new ArrayList<>(List.of(current, old));
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, blobs,
                "the old version's blob is judged after the version row is gone");
    }

    @Test
    void aPurgedDocumentNoLongerHoldsItsBlob() {
        // 两篇活文档同 sha 只能在两个库里（documents_kb_sha_idx）
        String shared = sha("shared-purged");
        UUID otherKb = otherBase();
        UUID x = document(kb, "x.md", shared);
        UUID y = document(otherKb, "y.md", shared);
        assertTrue(purge(kb, x).isEmpty(), "y still holds it");
        assertEquals(List.of(shared), purge(otherKb, y),
                "x's row is still there but purged, so it is not a holder");
    }

    @Test
    void everyShaIsJudgedExactlyOnce() {
        String repeated = sha("repeated");
        UUID a = document(kb, "a.md", repeated);
        version(a, 1, repeated);
        version(a, 2, repeated);
        assertEquals(List.of(repeated), purge(kb, a),
                "one blob, one line, however many versions carried it");
    }

    @Test
    void theBatchedAnswerEqualsTheOneByOneAnswer() {
        UUID otherKb = otherBase();
        // 甲拿着五个指纹：现行一个、历史四个；其中两个别处还有人拿，一个已被 purge 的文档拿过
        String own = sha("own");
        String sharedDoc = sha("shared-doc");
        String sharedFar = sha("shared-far");
        String heldByPurged = sha("held-by-purged");
        String oldOnly = sha("old-only");
        UUID a = document(kb, "a.md", own);
        version(a, 1, sharedDoc);
        version(a, 2, sharedFar);
        version(a, 3, heldByPurged);
        version(a, 4, oldOnly);
        document(kb, "b.md", sharedDoc);
        document(otherKb, "far.md", sharedFar);
        UUID p = document(kb, "p.md", heldByPurged);
        assertTrue(purge(kb, p).isEmpty(), "a still holds it in a version");

        List<String> shas = jdbc.queryForList("""
                SELECT DISTINCT sha256 FROM document_versions WHERE document_id = ?
                 UNION SELECT sha256 FROM documents WHERE id = ?
                """, String.class, a, a);
        List<String> batched = new ArrayList<>(purge(kb, a));
        // 提交之后的库状态与事务内判断时相同：版本行已删、文档已标 purged
        List<String> oneByOne = new ArrayList<>(judgedOneByOne(shas));
        batched.sort(Comparator.naturalOrder());
        oneByOne.sort(Comparator.naturalOrder());
        assertEquals(oneByOne, batched);
        List<String> expected = new ArrayList<>(List.of(own, heldByPurged, oldOnly));
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, batched);
    }

    // ---- a_purge_is_final.rs 的记录面（写路径走 DocumentsWriteService 真身） ----

    @Test
    void purgingKeepsTheRecordAndRetiresTheFacts() {
        UUID etype = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label)"
                + " VALUES (?, ?, 'org', 'Org')", etype, kb);
        UUID x = Uuids.nowV7();
        UUID y = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name)"
                + " VALUES (?, ?, ?, 'X')", x, kb, etype);
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name)"
                + " VALUES (?, ?, ?, 'Y')", y, kb, etype);
        String shaA = sha("final-a");
        String shaOld = sha("final-old");
        UUID a = document(kb, "a.md", shaA);
        version(a, 1, shaOld);
        document(kb, "c.md", shaOld);
        // 乙：删了但不清，留在「已删除」视图里
        UUID b = document(kb, "b.md", sha("final-b"));
        UUID chunk = Uuids.nowV7();
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text)"
                + " VALUES (?, ?, ?, 0, 'X works with Y.')", chunk, kb, a);
        UUID knownAs = jdbc.queryForList(
                "SELECT id FROM relation_types WHERE kb_id = ? AND key = 'known_as'",
                UUID.class, kb).stream().findFirst().orElse(null);
        if (knownAs == null) {
            knownAs = Uuids.nowV7();
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind)"
                    + " VALUES (?, ?, 'known_as', 'known as', 'attribute')", knownAs, kb);
        }
        UUID rel = Uuids.nowV7();
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind)"
                + " VALUES (?, ?, 'partner', 'partner', 'relation')", rel, kb);
        UUID fact = Uuids.nowV7();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,"
                + " recorded_at) VALUES (?, ?, ?, ?, ?, now())", fact, kb, x, rel, y);
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id) VALUES (?, ?)", fact, chunk);

        // 1. 没删不能清
        assertThrows(AppException.class, () -> writes.purge(kb, a));

        writes.delete(kb, a, null);
        writes.delete(kb, b, null);

        // 2 + 3. 清内容留记录；只交出独占的原文（sha-old 还是 c 的内容，不交）
        DocumentsWriteService.PurgeReport report = writes.purge(kb, a);
        assertEquals(1, report.chunks());
        assertEquals(List.of(shaA), report.blobs());
        assertEquals(0, count("SELECT count(*) FROM chunks WHERE document_id = ?", a));
        assertEquals(0, count("SELECT count(*) FROM fact_evidence WHERE fact_id = ?", fact),
                "the quote goes with the chunk");
        assertEquals(0, count("SELECT count(*) FROM document_versions WHERE document_id = ?", a));
        var tombstone = jdbc.queryForMap("""
                SELECT deleted_at IS NOT NULL AS deleted, purged_at IS NOT NULL AS purged,
                       external_key
                  FROM documents WHERE id = ?
                """, a);
        assertEquals(true, tombstone.get("deleted"));
        assertEquals(true, tombstone.get("purged"));
        assertNull(tombstone.get("external_key"));
        assertTrue(jdbc.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM facts WHERE id = ?", Boolean.class, fact),
                "the fact stays retired");
        assertEquals(1, count("SELECT count(*) FROM document_deletions WHERE document_id = ?", a),
                "the deletion stays on record");

        // 4. 回不来
        assertThrows(AppException.class, () -> writes.restore(kb, a));
        assertThrows(AppException.class, () -> writes.purge(kb, a));

        // 5. 身份让出：同内容同键再进来是一篇新文档；「已删除」视图只有乙
        UUID again = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO documents (id, kb_id, filename, sha256, status, external_key)
                 VALUES (?, ?, 'a.md', ?, 'ready', 'ext-purge-final')
                """, again, kb, shaA);
        var againRow = jdbc.queryForMap(
                "SELECT deleted_at FROM documents WHERE id = ?", again);
        assertNull(againRow.get("deleted_at"), "a new document, not a revival");
        DocumentsService.DocumentPage deleted = documents.page(kb, null, null, null, true, 50, 0);
        assertEquals(List.of(b), deleted.docs().stream().map(d -> d.id()).toList(),
                "purged tombstones are not listed");
        assertEquals(1, deleted.deleted());
        DocumentsService.DocumentPage live = documents.page(kb, null, null, null, false, 50, 0);
        List<UUID> liveIds = live.docs().stream().map(d -> d.id()).toList();
        assertTrue(liveIds.contains(again));
        assertTrue(!liveIds.contains(a) && !liveIds.contains(b));
    }

    private long count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }
}
