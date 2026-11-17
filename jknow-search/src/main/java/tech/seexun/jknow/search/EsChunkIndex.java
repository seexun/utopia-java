package tech.seexun.jknow.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ES 分块索引（对等旧仓库 utopia-search/src/lib.rs SearchIndex——Tantivy 单索引多 KB
 * 换 ES 每 KB 一索引）：索引名 {@code utopia-kb-{kbId}}（UUID 小写规范），
 * mapping = chunk_id/document_id（keyword）+ text（ik_max_word 分词，对等 jieba 语义，
 * 差异进 M1 任务 6 语义等价集评审）。
 *
 * <p>检索（search）对等 lib.rs:127-170：查询切词后按 OR 组合 + BM25——ES 的
 * {@code match} 查询（默认 operator=or，走字段 analyzer=ik_max_word）即该语义；
 * 空词元（纯标点/空白）match 零命中（zero_terms_query 缺省 none），对等 lib.rs:145-147
 * 的「term_queries 空→回空」。分值公式 ES BM25 与 Tantivy BM25 实现不同——
 * 语义等价集口径允许顺序与分数不同（规格 §8）。
 */
public class EsChunkIndex {

    /** 索引文档形状；字段名 @JsonProperty 钉死（不共享 Boot 全局 SNAKE_CASE ObjectMapper）。 */
    record ChunkDoc(@com.fasterxml.jackson.annotation.JsonProperty("chunk_id") String chunkId,
                    @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId,
                    @com.fasterxml.jackson.annotation.JsonProperty("text") String text) {
    }

    private final ElasticsearchClient client;

    public EsChunkIndex(ElasticsearchClient client) {
        this.client = client;
    }

    /** 索引名：每 KB 一索引（UUID#toString 本身即小写规范）。 */
    public static String indexName(UUID kbId) {
        return "utopia-kb-" + kbId;
    }

    /** 索引存在性。ES 不可达向上抛 IOException（调用方决定告警还是报错）。 */
    public boolean exists(UUID kbId) throws IOException {
        return client.indices().exists(e -> e.index(indexName(kbId))).value();
    }

    /** 索引里有多少条分块；索引不存在=0（对等 SearchIndex::len 对空索引回 0）。 */
    public long count(UUID kbId) throws IOException {
        try {
            return client.count(c -> c.index(indexName(kbId))).count();
        } catch (ElasticsearchException e) {
            // 404 index_not_found = 还没建过 → 0；其余（含 IK 未装等 mapping 校验错）照抛
            if (e.status() == 404) {
                return 0;
            }
            throw e;
        }
    }

    /** 删索引（不存在则无事）——重建=删了再建（幂等）。 */
    public void delete(UUID kbId) throws IOException {
        if (exists(kbId)) {
            client.indices().delete(d -> d.index(indexName(kbId)));
        }
    }

    /** 建索引（假定不存在；chunk_id/document_id keyword + text ik_max_word，单分片零副本）。 */
    public void create(UUID kbId) throws IOException {
        client.indices().create(c -> c
                .index(indexName(kbId))
                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m
                        .properties("chunk_id", p -> p.keyword(k -> k))
                        .properties("document_id", p -> p.keyword(k -> k))
                        .properties("text", p -> p.text(t -> t.analyzer("ik_max_word")))));
    }

    /**
     * 批量写入分块（_id=chunk_id 幂等 upsert），随后 refresh——写后即可读（对等 lib.rs:100-101）。
     *
     * <p><b>索引缺席先建</b>（任务 5 前端链路诊断）：增量路径（reindexDocument）不再经过
     * 重建的 delete+create，直接 bulk 会让 ES 按动态 mapping 自动建索引——text 落 standard
     * 分析器（CJK 单字切分），ik_max_word 的检索词命中不了单字索引（查询零命中）。这里
     * 每次写入前确保索引带着 {@link #create} 的 mapping 存在；两写并发撞 resource_already_exists
     * 视为已建（存在性检查与建之间的窗口不是正确性问题——两方建的 mapping 相同）。
     */
    public void bulk(UUID kbId, List<IndexEntry> entries) throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        if (!exists(kbId)) {
            try {
                create(kbId);
            } catch (ElasticsearchException e) {
                // 已存在（并发首写/外部创建）：继续写——mapping 由先到者按 create() 建好
                if (!"resource_already_exists_exception".equals(
                        e.error().type() == null ? null : e.error().type())) {
                    throw e;
                }
            }
        }
        String index = indexName(kbId);
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (IndexEntry e : entries) {
            ChunkDoc doc = new ChunkDoc(e.chunkId().toString(), e.documentId().toString(), e.text());
            bulk.operations(op -> op.index(i -> i.index(index)
                    .id(e.chunkId().toString()).document(doc)));
        }
        if (client.bulk(bulk.build()).errors()) {
            throw new IOException("bulk index reported errors for kb " + kbId);
        }
        client.indices().refresh(r -> r.index(index));
    }

    /** 一条索引记录：chunk_id + 所属文档 + 正文（对等 reindex_document 的 (chunk_id, text) 对 + kb/document 字段）。 */
    public record IndexEntry(UUID chunkId, UUID documentId, String text) {
    }

    /**
     * BM25 检索（该 KB 索引内），返回 chunk_id 按名次降序，上限 limit。
     * 索引不存在（库无分块）=空集。
     */
    public List<String> search(UUID kbId, String query, int limit) throws IOException {
        SearchRequest req = new SearchRequest.Builder()
                .index(indexName(kbId))
                .size(limit)
                .query(q -> q.match(m -> m.field("text").query(query).analyzer("ik_max_word")))
                .build();
        try {
            List<String> hits = new ArrayList<>();
            for (Hit<ChunkDoc> hit : client.search(req, ChunkDoc.class).hits().hits()) {
                if (hit.source() != null) {
                    hits.add(hit.source().chunkId());
                }
            }
            return hits;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * 删一篇文档的全部索引条目（对等 lib.rs:104-110 delete_document：delete_term(document_id)
     * + commit + reload——「写后即可读」由 refresh(true) 承担）。索引不存在（库从未建索引）=无事。
     */
    public void deleteDocument(UUID kbId, UUID documentId) throws IOException {
        String index = indexName(kbId);
        try {
            client.deleteByQuery(d -> d.index(index)
                    .query(q -> q.term(t -> t.field("document_id").value(documentId.toString())))
                    .refresh(true));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return;
            }
            throw e;
        }
    }

    /**
     * 重建一篇文档的索引条目（先删后加，幂等；对等 lib.rs:78-101 reindex_document——
     * 墓碑恢复路由层把「正文一直都在」的分块重写一遍索引条目）。
     */
    public void reindexDocument(UUID kbId, UUID documentId, List<IndexEntry> entries)
            throws IOException {
        deleteDocument(kbId, documentId);
        List<IndexEntry> mine = new ArrayList<>();
        for (IndexEntry e : entries) {
            if (documentId.equals(e.documentId())) {
                mine.add(e);
            }
        }
        bulk(kbId, mine);
    }

    /** 全部 utopia-kb-* 索引对应的 KB id（启动对账/清孤儿用）。 */
    public List<UUID> indexedKbs() throws IOException {
        List<UUID> out = new ArrayList<>();
        for (String index : client.indices().get(g -> g.index("utopia-kb-*")).result().keySet()) {
            String suffix = index.substring("utopia-kb-".length());
            try {
                out.add(UUID.fromString(suffix));
            } catch (IllegalArgumentException notAUuid) {
                // 人工建的杂项索引：不是本组件管的，跳过（不删）
            }
        }
        return out;
    }
}
