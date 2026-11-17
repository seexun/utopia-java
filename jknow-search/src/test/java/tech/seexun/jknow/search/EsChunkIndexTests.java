package tech.seexun.jknow.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * EsChunkIndex 对真实 ES（本机 9200 单节点 + analysis-ik）的读写面：mapping 的
 * ik_max_word、bulk/count/search/delete 往返、纯标点查询零词元回空、缺失索引回空。
 * ES 不可达整类跳过（干净 clone 的 mvn verify 仍绿——环境门与 StoreTestBase 同法）。
 */
@EnabledIf(value = "esUp",
        disabledReason = "本机 ES（9200）不可达，跳过 ES 集成测试")
class EsChunkIndexTests {

    private static final RestClient REST = RestClient.builder(
            HttpHost.create("http://localhost:9200")).build();
    private static final EsChunkIndex INDEX = new EsChunkIndex(new ElasticsearchClient(
            new RestClientTransport(REST, new JacksonJsonpMapper(new ObjectMapper()))));

    public static boolean esUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 9200), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void closeClient() throws Exception {
        REST.close();
    }

    private UUID kb() throws Exception {
        UUID kbId = UUID.randomUUID();
        INDEX.delete(kbId); // 幂等兜底（uuid 碰撞概率为零，防重跑残留）
        return kbId;
    }

    @Test
    void mappingCarriesIkMaxWord() throws Exception {
        UUID kbId = kb();
        INDEX.create(kbId);
        // 以同一 RestClient 另建客户端查 mapping（EsChunkIndex 不暴露内部 client）
        ElasticsearchClient client = new ElasticsearchClient(
                new RestClientTransport(REST, new JacksonJsonpMapper(new ObjectMapper())));
        String got = client.indices().getMapping(m -> m.index(EsChunkIndex.indexName(kbId)))
                .result().get(EsChunkIndex.indexName(kbId)).mappings().properties()
                .get("text").text().analyzer();
        assertEquals("ik_max_word", got, "text 字段分词器=ik_max_word（对等 jieba 语义的 ES 侧选择）");
        INDEX.delete(kbId);
    }

    @Test
    void bulkCountSearchRoundTrip() throws Exception {
        UUID kbId = kb();
        UUID docId = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        INDEX.create(kbId);
        INDEX.bulk(kbId, List.of(
                new EsChunkIndex.IndexEntry(c1, docId, "Eve works at Acme Corp."),
                new EsChunkIndex.IndexEntry(c2, docId, "沈星野出任首席执行官。")));
        assertEquals(2, INDEX.count(kbId), "bulk 后计数=2（refresh 已强制）");
        List<String> hit = INDEX.search(kbId, "Acme", 24);
        assertEquals(List.of(c1.toString()), hit, "英文词命中单块");
        List<String> zh = INDEX.search(kbId, "首席执行官", 24);
        assertEquals(List.of(c2.toString()), zh, "IK 切词中文词命中");
        INDEX.delete(kbId);
        assertEquals(0, INDEX.count(kbId), "删后计数=0");
    }

    @Test
    void purePunctuationQueryMatchesNothing() throws Exception {
        UUID kbId = kb();
        INDEX.create(kbId);
        INDEX.bulk(kbId, List.of(new EsChunkIndex.IndexEntry(UUID.randomUUID(),
                UUID.randomUUID(), "some text")));
        assertTrue(INDEX.search(kbId, "!!!  。。。 ", 24).isEmpty(),
                "零词元=零命中（对等 lib.rs:145-147 term_queries 空→回空）");
        INDEX.delete(kbId);
    }

    @Test
    void missingIndexSearchIsEmptyAndCountZero() throws Exception {
        UUID kbId = UUID.randomUUID();
        INDEX.delete(kbId);
        assertEquals(0, INDEX.count(kbId), "缺失索引计数=0");
        assertTrue(INDEX.search(kbId, "anything", 24).isEmpty(), "缺失索引检索=空集");
        assertFalse(INDEX.exists(kbId));
    }

    @Test
    void indexedKbsListsManagedIndicesOnly() throws Exception {
        UUID kbId = kb();
        INDEX.create(kbId);
        INDEX.bulk(kbId, List.of(new EsChunkIndex.IndexEntry(UUID.randomUUID(),
                UUID.randomUUID(), "text")));
        assertTrue(INDEX.indexedKbs().contains(kbId), "utopia-kb-* 列表含本库");
        INDEX.delete(kbId);
        assertFalse(INDEX.indexedKbs().contains(kbId));
    }
}
