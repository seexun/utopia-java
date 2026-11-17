package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.ingest.ChunkPiece;
import tech.seexun.jknow.ingest.Provenance;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.DataSourceView;

/**
 * 来源域收尾四件（M3 任务 9 移植）：
 *
 * <ul>
 *   <li><b>a_source_reaches_only_where_it_was_granted</b>（0014 数据源授权）——看不见 /
 *       也挂不上 / 收回是真收回；</li>
 *   <li><b>a_source_kind_is_listed_once</b>（#247 后端侧）——一张表出创建白名单与分派，
 *       每个 kind 经它的串往返；前端对表那一半是 web/src 的事（Java 仓没有前端，
 *       台账记契约等价）；</li>
 *   <li><b>concurrent_chunk_replacement</b>——两次重叠的 replace_chunks 只留一份活文本
 *       （两个单连接写者对着真 DB 锁交错，不是靠 sleep）；</li>
 *   <li><b>a_chunk_says_where_its_words_came_from</b>（0040）——出处原样落库原样认领，
 *       锚点形状由来源定（DB CHECK 拒收）。</li>
 * </ul>
 */
class SourceDomainTailTests extends HeartStoreTestBase {

    @Autowired
    DatasourcesService datasources;

    @Autowired
    DocumentsWriteService documents;

    @Autowired
    SourcesService sources;

    // ---- a_source_reaches_only_where_it_was_granted.rs ----

    @Test
    void aSourceReachesOnlyWhereItWasGranted() {
        UUID org = Uuids.nowV7();
        UUID ours = Uuids.nowV7();
        UUID theirs = Uuids.nowV7();
        UUID ourKb = Uuids.nowV7();
        UUID theirKb = Uuids.nowV7();
        UUID source = Uuids.nowV7();
        UUID actor = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'grant-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'ours')", ours, org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'theirs')", theirs,
                org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'our-kb')",
                ourKb, ours);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'their-kb')",
                theirKb, theirs);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Grant Test', TRUE)
                """, actor, org, actor + "@grant.test");
        jdbc.update("""
                INSERT INTO data_sources (id, name, engine, conn_string, created_by)
                 VALUES (?, ?, 'postgres', 'postgres://u:p@db.test:5432/w', ?)
                """, source, "warehouse-" + source, actor);

        try {
            // 一、没授权 = 谁都够不着
            assertTrue(datasources.grantedToWorkspace(ours).isEmpty(), "没授权过，可挂载列表就该是空的");
            assertFalse(datasources.isGranted(ourKb, source), "没授权，挂载端点的守卫必须说不");

            // 二、授权一个工作区，另一个不受影响
            datasources.grant(source, ours, actor);
            List<DataSourceView> granted = datasources.grantedToWorkspace(ours);
            assertEquals(1, granted.size(), "授权过的工作区看得见它");
            assertFalse(granted.get(0).summary().contains("p@"),
                    "列表里只能有 host:port/db 摘要，凭据不出服务端");
            assertTrue(datasources.grantedToWorkspace(theirs).isEmpty(),
                    "授权是逐工作区的：给了一个不等于给了全部署");

            // 三、端点侧的守卫：列表过滤只挡「看得见」
            assertTrue(datasources.isGranted(ourKb, source));
            assertFalse(datasources.isGranted(theirKb, source),
                    "没授权的工作区，就算自己拼一个 uuid 打过来也挂不上");

            // 四、一个源可授权给多个工作区（多对多）；重复授权幂等
            datasources.grant(source, theirs, actor);
            assertEquals(2, datasources.grantsForSource(source).size(),
                    "同一个数仓要能同时服务多个工作区");
            datasources.grant(source, theirs, actor);
            assertEquals(2, datasources.grantsForSource(source).size(), "重复授权是幂等的");

            // 五、收回连同挂载一起收
            datasources.mount(ourKb, source);
            datasources.mount(theirKb, source);
            long unmounted = datasources.revoke(source, theirs);
            assertEquals(1L, unmounted, "撤授权要把那个工作区里已挂上的一起卸掉");
            assertTrue(datasources.mounted(theirKb).isEmpty(),
                    "留着挂载 = 撤销不生效：问数读的是 kb_data_sources");
            assertEquals(1, datasources.mounted(ourKb).size(), "另一个工作区的挂载不受牵连");
        } finally {
            jdbc.update("DELETE FROM data_sources WHERE id = ?", source);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    // ---- a_source_kind_is_listed_once.rs（后端侧；前端对表是 web/src 的事，契约等价） ----

    /**
     * 一张表出创建白名单与分派（#247：手写第二张表的教训）；每个 kind 都在表里、
     * 白名单=表减 memory/upload；不存在的 kind 经真分派拒收（不是白名单拦的）。
     */
    @Test
    void everyKindRoundTripsThroughItsString() {
        List<String> all = tech.seexun.jknow.store.SourcesService.allKinds();
        assertEquals(List.of("folder", "url", "rss", "github_issues", "jira_issues", "s3",
                "azure_blob", "gcs", "webdav", "notion", "api", "statements", "custom",
                "memory", "upload"), all, "全部 15 种——创建白名单/同步分派/前端下拉同一张表");
        assertEquals(all.stream().filter(k -> !"memory".equals(k) && !"upload".equals(k))
                .toList(), sources.creatableKinds(), "人可建的 = 全集减 memory/upload");
        assertFalse(all.contains("watch_folder"), "不存在的 kind 不进表");
        // 真分派：kind 直插一行，走 SyncSourceJob 的穷举 switch——unknown 拒收
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID bad = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'kind-once')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'kind-once')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'kind-once')",
                kb, ws);
        jdbc.update("INSERT INTO sources (id, kb_id, kind, name, config) "
                + "VALUES (?, ?, 'watch_folder', 'x', '{}')", bad, kb);
        try {
            Exception e = assertThrows(IllegalStateException.class, () -> sync(bad));
            assertTrue(e.getMessage().contains("unknown source kind"), e.getMessage());
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    @Autowired
    tech.seexun.jknow.web.knowledge.pipeline.SyncSourceJob syncJob;

    private void sync(UUID sourceId) throws Exception {
        syncJob.sync(sourceId);
    }

    // ---- concurrent_chunk_replacement.rs ----

    @Test
    void overlappingReprocessingKeepsOneLiveCopyOfEachChunk() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'chunk-race')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'chunk-race')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'chunk-race')",
                kb, ws);
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status) "
                + "VALUES (?, ?, 'report.txt', ?::text, 'pending')", doc, kb, doc);
        try {
            // 门闩：拿住 doc 行锁，让两个写者都停在「读块集合」之前
            try (var gate = jdbc.getDataSource().getConnection()) {
                gate.setAutoCommit(false);
                try (var st = gate.prepareStatement(
                        "SELECT id FROM documents WHERE id = ? FOR UPDATE")) {
                    st.setObject(1, doc);
                    st.executeQuery();
                    // 两个写者（真 DB 锁交错，不是 sleep）：先等双双堵在 documents 行锁上
                    var writers = new java.util.ArrayList<java.util.concurrent.Future<
                            List<DocumentsWriteService.ChunkPair>>>();
                    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                    for (int i = 0; i < 2; i++) {
                        writers.add(pool.submit(() ->
                                documents.replaceChunks(kb, doc, pieces())));
                    }
                    long deadline = System.currentTimeMillis() + 10_000;
                    while (System.currentTimeMillis() < deadline) {
                        Integer waiting = jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity "
                                        + "WHERE wait_event_type = 'Lock' "
                                        + "AND query LIKE '%documents%'", Integer.class);
                        if (waiting != null && waiting >= 2) {
                            break;
                        }
                        Thread.sleep(10);
                    }
                    gate.commit();
                    var returned = new java.util.ArrayList<List<DocumentsWriteService.ChunkPair>>();
                    for (var f : writers) {
                        returned.add(f.get(15, java.util.concurrent.TimeUnit.SECONDS));
                    }
                    pool.shutdown();
                    long live = jdbc.queryForObject(
                            "SELECT count(*) FROM chunks WHERE document_id = ? "
                                    + "AND superseded_at IS NULL", Long.class, doc);
                    assertEquals(1L, live, "overlapping processing must not duplicate the "
                            + "document's live text");
                    assertEquals(returned.get(0), returned.get(1),
                            "both runs must return the adopted chunk id for indexing");
                }
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private static List<ChunkPiece> pieces() {
        return List.of(new ChunkPiece(0, "Revenue was 100.", 0, 16, null, Provenance.stated()));
    }

    // ---- a_chunk_says_where_its_words_came_from.rs ----

    @Test
    void aChunkKeepsItsProvenanceAndIsClaimedByIt() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'origin-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'origin-test')", ws,
                org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'origin-test')",
                kb, ws);
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status) "
                + "VALUES (?, ?, 'scan.pdf', ?::text, 'ready')", doc, kb, doc);
        try {
            // 出处原样落库：同一份正文里一块原文、一块扫描页，读回来各是各的
            documents.replaceChunks(kb, doc, List.of(
                    piece(0, "Rent is paid monthly.", Provenance.stated()),
                    piece(1, "The rent is 1,000 per month.", ocr(1))));
            var rows = stored(doc);
            assertEquals("stated", rows.get(0)[2]);
            assertEquals(null, rows.get(0)[4]);
            assertEquals("ocr", rows.get(1)[2]);
            assertEquals("mineru", rows.get(1)[3]);
            assertEquals(1, ((Number) com.fasterxml.jackson.databind.json.JsonMapper
                    .builder().build().readTree((String) rows.get(1)[4]).path("page").asInt(-1)));

            // 再处理一遍：出处没变的认领原行（id 不变）；同一句话换到另一页是新的一块
            documents.replaceChunks(kb, doc, List.of(
                    piece(0, "Rent is paid monthly.", Provenance.stated()),
                    piece(1, "The rent is 1,000 per month.", ocr(2))));
            var again = stored(doc);
            assertEquals(rows.get(0)[0], again.get(0)[0], "the stated chunk is claimed");
            assertTrue(!rows.get(1)[0].equals(again.get(1)[0]),
                    "a different anchor is a different chunk");
            assertEquals(2, com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree((String) again.get(1)[4]).path("page").asInt());

            // 锚点的形状由来源定：转写必须分得出说话人；原文不许带锚点——数据库拒收
            assertThrows(Exception.class, () -> documents.replaceChunks(kb, doc,
                    List.of(piece(0, "We ship in Q3.",
                            new Provenance(Provenance.Origin.Transcribed, "whisper",
                                    json("{\"start_ms\":0,\"end_ms\":900}"))))));
            assertThrows(Exception.class, () -> documents.replaceChunks(kb, doc,
                    List.of(piece(0, "x", new Provenance(Provenance.Origin.Stated, null,
                            json("{\"page\":1}"))))));
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String raw) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
    }

    private static ChunkPiece piece(int seq, String text, Provenance provenance) {
        return new ChunkPiece(seq, text, 0, text.length(), null, provenance);
    }

    private static Provenance ocr(long page) {
        try {
            return new Provenance(Provenance.Origin.Ocr, "mineru",
                    json("{\"page\":" + page + ",\"bbox\":[10,20,300,400]}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Object[]> stored(UUID doc) {
        return jdbc.query("SELECT id, text, origin, origin_model, anchor::text FROM chunks "
                        + "WHERE document_id = ? AND superseded_at IS NULL ORDER BY seq",
                (rs, i) -> new Object[] {rs.getObject(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)}, doc);
    }
}
