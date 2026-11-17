package tech.seexun.jknow.search;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.Rrf;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.mapper.DocumentsMapper;

/**
 * 混合检索编排（逐字对齐旧仓库 utopia-server/src/retrieval.rs:23-119 hybrid——
 * BM25 路 + 向量路 RRF 融合；M3 任务 10 补齐向量第二路，m2-report §7.5 D12 挂账核销）。
 *
 * <p><b>向量路的降级语义</b>（retrieval.rs:62-99）：没配嵌入模型、嵌入请求失败、回了空，
 * 都是「这一路缺席」——检索照常，只剩 BM25（静默降级）；向量查询本身失败仍是错误：
 * 那是库的事，不是模型的事，吞掉会把坏库伪装成没配模型。
 *
 * <p><b>两路顺序钉死</b>（retrieval.rs:111-118 channel_lists）：BM25 恒在第一位，向量有就
 * 在第二位——不看谁先回来，今天的 RRF 不看顺序，将来若给通道加权就看了，不能让它取决
 * 于哪路网络快。
 *
 * <p>与 Rust 的结构性差异（语义等价集口径，规格 §8 / m1-recon §4.4）：
 * BM25 路 Tantivy（jieba 单索引多 KB）→ ES（ik_max_word 每 KB 一索引）——词元与 BM25
 * 实现不同，结果集顺序/分数允许不同，document_id 命中集需等价评审。两路从前在 Rust 侧
 * 并发（join!，#515——延迟取大不取和）；Java 侧顺序执行，结果集与融合语义等价（两路
 * 互不依赖，先后不改变任何一路的召回）。
 *
 * <p><b>M2 任务 5 起索引新鲜度走写路径增量</b>（M1 任务 6 自审 4 的回收承诺）：
 * process_document 的 indexing 步单文档 reindex、删除/恢复走 deleteDocument/reindexDocument
 * ——检索路径<b>不再</b>做 count 对账（M1 的 ensureFresh 每查一次全库对账，撤下；它降为
 * 显式入口：启动对账/手动 --reindex，见 {@link ChunkIndexer} 类注释入口节）。
 */
@Service
public class SearchService {

    /** retrieval.rs:23 RECALL_PER_CHANNEL：每路召回上限。 */
    static final int RECALL_PER_CHANNEL = 24;

    /**
     * 查询嵌入端（retrieval.rs:76-99 的查询侧半）：返回查询向量；<b>null = 这一路缺席</b>
     * ——没配模型/嵌入失败/空回复的静默降级由实现方判定并记日志。实现在 server 层包住
     * EmbedClient（utopia-search 不依赖 utopia-llm）。
     */
    @FunctionalInterface
    public interface QueryEmbedder {

        float[] embed(String query);
    }

    private final ChunkIndexer indexer;
    private final DocumentsMapper documents;
    private final VectorIndexService vectorIndexes;

    public SearchService(ChunkIndexer indexer, DocumentsMapper documents,
                         VectorIndexService vectorIndexes) {
        this.indexer = indexer;
        this.documents = documents;
        this.vectorIndexes = vectorIndexes;
    }

    /**
     * hybrid 的单通道形（M1 兼容入口）：无嵌入模型时的现状——retrieval.rs:62-99
     * vector_channel 恒返回 None 的那一档。
     */
    public List<ChunkView> search(UUID kbId, String query, int topK, OffsetDateTime asOf) {
        return search(kbId, query, topK, asOf, null);
    }

    /**
     * hybrid（retrieval.rs:25-60）：BM25 top24 + 向量 top24 → RRF（candidate_limit=路长和，
     * 上限在融合之后裁，不是每路各裁）→ 按 id 取块过滤 SQL（记录轴谓词）→ 按 RRF 名次重排
     * → truncate(top_k)。ES 故障译 500（BM25 路失败是错误不是降级——降级只有向量路，
     * retrieval.rs:46-48）。向量路缺席时 RRF 单通道退化为名次直取。
     */
    public List<ChunkView> search(UUID kbId, String query, int topK, OffsetDateTime asOf,
                                  QueryEmbedder embedder) {
        try {
            List<String> bm25 = indexer.searchUnderReadLock(kbId, query, RECALL_PER_CHANNEL);
            // 向量路失败是降级不是错误（模型的事），向量查询失败上抛（库的事）——
            // retrieval.rs:40-41：join! 不是 try_join! 的理由
            List<String> vector = vectorChannel(kbId, query, asOf, embedder);

            List<List<String>> lists = channelLists(bm25, vector);
            // 上限在融合之后裁，不是每路各裁（rrf_tests the_limit_cuts_after_fusion）
            int candidateLimit = lists.stream().mapToInt(List::size).sum();
            List<String> fused = Rrf.fuse(lists, candidateLimit);

            // Rust fused.iter().filter_map(|s| s.parse().ok())：解析不了的防霭丢掉
            List<UUID> ids = new ArrayList<>(fused.size());
            for (String s : fused) {
                try {
                    ids.add(UUID.fromString(s));
                } catch (IllegalArgumentException notAUuid) {
                    // 索引侧 _id 恒为 UUID 文本——理论不可达，防御对齐
                }
            }

            // 按 id 取块（superseded/deleted 过滤，as_of 记录轴谓词），行序不保证——
            // 恢复 RRF 名次顺序（documents.rs:1721-1724 的 HashMap 重排）
            List<ChunkView> rows = documents.selectChunksByIds(kbId, ids, asOf);
            Map<UUID, ChunkView> byId = new HashMap<>();
            for (ChunkView row : rows) {
                byId.put(row.id(), row);
            }
            List<ChunkView> chunks = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                ChunkView chunk = byId.remove(id);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
            // 别让历史查询的限额吃掉更晚的命中（retrieval.rs:51-53）——边界过滤后才截断
            return chunks.size() > topK ? new ArrayList<>(chunks.subList(0, topK)) : chunks;
        } catch (IOException e) {
            throw AppException.other("BM25 检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * retrieval.rs:68-109 vector_channel：向量那一路。embedder 为 null（没配模型）或返回
     * null（嵌入失败/空回复——降级判定在实现方）→ 这一路缺席；近邻查询本身失败照原样
     * 上抛（库的事，吞掉会把坏库伪装成没配模型）。
     */
    private List<String> vectorChannel(UUID kbId, String query, OffsetDateTime asOf,
                                       QueryEmbedder embedder) {
        if (embedder == null) {
            return null;
        }
        float[] vector = embedder.embed(query);
        if (vector == null || vector.length == 0) {
            return null;
        }
        List<UUID> ids = vectorIndexes.chunkVectorSearch(kbId, vector, RECALL_PER_CHANNEL, asOf);
        List<String> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            out.add(id.toString());
        }
        return out;
    }

    /**
     * retrieval.rs:111-118 channel_lists：送进 RRF 的列表——BM25 恒在第一位，向量有就在
     * 第二位。顺序钉死、不看谁先回来（内联测试 the_channels_keep_their_places…）。
     */
    static List<List<String>> channelLists(List<String> bm25, List<String> vector) {
        List<List<String>> lists = new ArrayList<>();
        lists.add(bm25);
        if (vector != null) {
            lists.add(vector);
        }
        return lists;
    }
}
