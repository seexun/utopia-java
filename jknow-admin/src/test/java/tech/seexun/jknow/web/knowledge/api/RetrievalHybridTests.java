package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.search.EsChunkIndex;
import tech.seexun.jknow.search.EsChunkIndex.IndexEntry;
import tech.seexun.jknow.search.SearchService;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.store.StoreTestBase;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.entity.LlmSettingsRow;

/**
 * 移植自 crates/utopia-server/src/retrieval_tests.rs（4 例）——#515：混合检索的两路各走
 * 各的，丢一路是降级不是失败。夹具：一个库、一篇文档、两个分块（A 的正文能被 BM25 命中、
 * B 不能；两块各有一个 4 维向量），嵌入端点用脚本化 HTTP 服务假扮。四件事：没配嵌入模型
 * 也能答 / 嵌入失败退化 BM25 / 两路都在按 BM25 在前融合 / 历史过滤先于最终截断。
 *
 * <p>调用面与 Rust 同位（retrieval_tests 直调 hybrid 函数）：直调 SearchService.search 的
 * 嵌入端重载；查询嵌入端按 SearchController.queryEmbedder 同一构造法在测试侧搭建
 * （settings→EmbedClient→失败返 null 降级）——控制器壳是参数解析，不属本断言面。
 * wiremock 换成 JDK HttpServer 的脚本端点（先例 ScriptedModelServer）。BM25 路走真 ES
 * （9200）+IK——ES 不可达整类跳过（双门与 SearchApiTests 同法）。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（15432）或本机 ES（9200）不可达，跳过检索融合测试")
class RetrievalHybridTests extends StoreTestBase {

    /** 双门：共享库（15432）+ 本机 ES（9200）——BM25 路走真 ES+IK（SearchApiTests 同法）。 */
    public static boolean envUp() {
        if (!sharedStackUp()) {
            return false;
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 9200), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 脚本化嵌入端点：vector = 回固定向量；null = 永远 500（模型挂了）。 */
    private static final class ScriptedEmbeddings {
        private final HttpServer server;
        volatile float[] vector;
        volatile boolean down;

        private ScriptedEmbeddings(HttpServer server) {
            this.server = server;
        }

        static ScriptedEmbeddings start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
            ScriptedEmbeddings stub = new ScriptedEmbeddings(server);
            server.createContext("/embeddings", exchange -> {
                if (stub.down) {
                    byte[] err = "model is down".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, err.length);
                    try (OutputStream o = exchange.getResponseBody()) {
                        o.write(err);
                    }
                    return;
                }
                StringBuilder json = new StringBuilder("{\"data\":[{\"embedding\":[");
                float[] v = stub.vector;
                for (int i = 0; i < v.length; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append(v[i]);
                }
                json.append("]}]}");
                byte[] out = json.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream o = exchange.getResponseBody()) {
                    o.write(out);
                }
            });
            server.start();
            return stub;
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    private record Fixture(UUID org, UUID ws, UUID kb, UUID doc, UUID a, UUID b) {
    }

    @Autowired
    SearchService search;
    @Autowired
    ChunkIndexer indexer;
    @Autowired
    EsChunkIndex index;
    @Autowired
    SettingsService settings;
    @Autowired
    LlmSupport llm;

    private Fixture fixture() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'retrieval-test')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'retrieval-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'retrieval-test')",
                kb, ws);
        jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, 'orchard.md', ?)",
                doc, kb, "0".repeat(64));
        jdbc.update("""
                INSERT INTO chunks(id,kb_id,document_id,seq,text,embedding) VALUES
                 (?, ?, ?, 0, 'apple orchard harvest report', '[1,0,0,0]'::vector),
                 (?, ?, ?, 1, 'bridge concrete inspection', '[0,1,0,0]'::vector)
                """, a, kb, doc, b, kb, doc);
        indexer.reindexDocument(kb, doc, List.of(
                new IndexEntry(a, doc, "apple orchard harvest report"),
                new IndexEntry(b, doc, "bridge concrete inspection")));
        return new Fixture(org, ws, kb, doc, a, b);
    }

    /** SearchController.queryEmbedder 的同位构造（控制器壳不属断言面，见类注）。 */
    private SearchService.QueryEmbedder queryEmbedder(UUID workspaceId) {
        LlmSettingsRow row = settings.get(workspaceId);
        EmbedClient client = LlmSupport.embedClient(row);
        if (client == null) {
            return null;
        }
        return query -> {
            try {
                List<float[]> vectors = client.embed(List.of(query));
                return vectors.isEmpty() ? null : vectors.get(0);
            } catch (Exception e) {
                // retrieval.rs:86-92「查询 embedding 失败，降级为纯 BM25」
                return null;
            }
        };
    }

    private List<UUID> search(Fixture f, String query) throws Exception {
        List<ChunkView> chunks = search.search(f.kb(), query, 8, null, queryEmbedder(f.ws()));
        return chunks.stream().map(ChunkView::id).toList();
    }

    private void cleanup(Fixture f) throws Exception {
        index.delete(f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    @Test
    void aSearchWithoutAnEmbeddingModelStillAnswers() throws Exception {
        Fixture f = fixture();
        try {
            List<UUID> ids = search(f, "apple harvest");
            assertEquals(List.of(f.a()), ids,
                    "BM25 alone finds the orchard chunk and nothing else");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void aFailedQueryEmbeddingDegradesToBm25() throws Exception {
        Fixture f = fixture();
        ScriptedEmbeddings server = ScriptedEmbeddings.start();
        try {
            server.down = true;
            settings.upsert(f.ws(), null, null, null, server.uri(), null, "fake-embed", null);
            List<UUID> ids = search(f, "apple harvest");
            assertEquals(List.of(f.a()), ids,
                    "a dead embedding endpoint costs the vector channel, not the answer");
        } finally {
            cleanup(f);
            server.stop();
        }
    }

    @Test
    void bothChannelsFuseWithBm25First() throws Exception {
        Fixture f = fixture();
        ScriptedEmbeddings server = ScriptedEmbeddings.start();
        try {
            // 回的向量就是 A 的向量：向量那一路按距离排出 [A, B]，BM25 那一路只有 [A]
            server.vector = new float[] {1f, 0f, 0f, 0f};
            settings.upsert(f.ws(), null, null, null, server.uri(), null, "fake-embed", null);
            List<UUID> ids = search(f, "apple harvest");
            assertEquals(List.of(f.a(), f.b()), ids,
                    "the chunk both channels found ranks first; the vector-only chunk still comes back");
        } finally {
            cleanup(f);
            server.stop();
        }
    }

    @Test
    void historicalSearchKeepsLiveCandidatesBeforeLimitingResults() throws Exception {
        Fixture f = fixture();
        try {
            jdbc.update("UPDATE documents SET created_at='2026-01-01' WHERE id=?", f.doc());
            jdbc.update("UPDATE chunks SET text='apple harvest', created_at='2026-05-01'"
                    + " WHERE id=?", f.a());
            jdbc.update("UPDATE chunks SET text='apple orchard bridge inspection',"
                    + " created_at='2026-01-01' WHERE id=?", f.b());
            List<IndexEntry> indexed = new ArrayList<>(List.of(
                    new IndexEntry(f.a(), f.doc(), "apple harvest"),
                    new IndexEntry(f.b(), f.doc(), "apple orchard bridge inspection")));
            for (int seq = 2; seq < 7; seq++) {
                UUID id = Uuids.nowV7();
                jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text,created_at)"
                        + " VALUES (?, ?, ?, ?, 'apple harvest', '2026-05-01')",
                        id, f.kb(), f.doc(), seq);
                indexed.add(new IndexEntry(id, f.doc(), "apple harvest"));
            }
            indexer.reindexDocument(f.kb(), f.doc(), indexed);

            // All seven hits are already in today's index. This is not the separate
            // limitation that BM25 cannot recall historical versions absent from it.
            List<String> raw = indexer.searchUnderReadLock(f.kb(), "apple harvest", 24);
            assertEquals(7, raw.size());
            assertEquals(f.b().toString(), raw.get(raw.size() - 1),
                    "the diluted chunk ranks last in the engine's own order");
            List<ChunkView> current = search.search(f.kb(), "apple harvest", 6, null,
                    queryEmbedder(f.ws()));
            assertEquals(raw.stream().limit(6).toList(),
                    current.stream().map(c -> c.id().toString()).toList(),
                    "the current search still honors its limit and ranking");

            // The first six hits do not exist in March. They must not consume the
            // final limit and discard the one recalled chunk that does exist then.
            java.time.OffsetDateTime at = java.time.OffsetDateTime.parse("2026-03-01T00:00:00Z");
            List<UUID> six = search.search(f.kb(), "apple harvest", 6, at, queryEmbedder(f.ws()))
                    .stream().map(ChunkView::id).toList();
            List<UUID> seven = search.search(f.kb(), "apple harvest", 7, at,
                    queryEmbedder(f.ws())).stream().map(ChunkView::id).toList();
            List<UUID> expected = List.of(f.b());
            assertEquals(expected, seven, "the eligible chunk was recalled");
            assertEquals(expected, six, "ineligible hits must not consume the final limit");
        } finally {
            cleanup(f);
        }
    }
}
