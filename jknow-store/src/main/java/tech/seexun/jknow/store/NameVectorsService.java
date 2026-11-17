package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.store.mapper.NameVectorsMapper;
import tech.seexun.jknow.store.mapper.NameVectorsMapper.Near;
import tech.seexun.jknow.store.mapper.NameVectorsMapper.PendingName;
import tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText;

/**
 * 名字向量（name_vectors.rs 逐字对等）：召回的第二条通道（0041 决定 3，第 2 刀）。
 *
 * <p>通道 1 是字面相等：mention 的名字与某条名字事实 normalize 之后一样。它找不到简称
 * （海探1 ↔ 海洋探测器1号）、找不到另一种文字写的同一个名字（#709），而这两种恰恰是
 * 「多名」被错拆成两个实体的主因。这里给每条名字事实存名字字符串本身的向量，查询时
 * 在同一个库里取最近的几条；<b>只提议，不决定</b>——最近的名字是不是同一个东西，
 * 由消解那头按既有规矩（画像、裁决器，往后是第 3 刀的证据）去判。
 */
@Service
public class NameVectorsService {

    /**
     * name_vectors.rs:22 TOP_K：一次最多提议几条。名字向量的近邻里真正相关的很少超过
     * 前几个；再多只是给裁决器添噪音。
     */
    public static final int TOP_K = 8;

    /**
     * name_vectors.rs:26 SIM_FLOOR：余弦下限。名字字符串的向量比整段文本的向量「紧」
     * ——两个不相干的名字也能有 0.4 上下的余弦——所以这条线比画像的 SIM_ATTACH 高。
     */
    public static final float SIM_FLOOR = 0.60f;

    private final NameVectorsMapper vectors;
    private final VectorIndexService vectorIndexes;
    private final TransactionTemplate tx;

    public NameVectorsService(NameVectorsMapper vectors, VectorIndexService vectorIndexes,
                              PlatformTransactionManager txManager) {
        this.vectors = vectors;
        this.vectorIndexes = vectorIndexes;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** name_vectors.rs:31-53 {@code pending}：还没有向量的名字事实（按写入先后）。 */
    public List<PendingName> pending(UUID kbId, long limit) {
        return vectors.selectPending(kbId, NamesService.KNOWN_AS, limit);
    }

    /**
     * name_vectors.rs:57-80 {@code set}：写一批名字向量 {@code (fact_id, entity_id, embedding)}。
     * 同一条事实重写就覆盖（换了模型重算）。第一次写下这个维度的向量，索引就该排上了
     * （0035）——build_vector_index 任务本体在 M3 任务 10 已落真身
     * （{@link VectorIndexService#build}）。
     */
    public void set(UUID kbId, List<NameVectorWrite> items) {
        if (items.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(t -> {
            for (NameVectorWrite w : items) {
                vectors.insertVector(w.factId(), kbId, w.entityId(), VectorText.literal(w.embedding()));
            }
        });
        int dims = items.get(0).embedding().length;
        vectorIndexes.request(VectorIndexService.Target.NAME_VECTORS, dims);
    }

    /** 一次写入的形状：(名字事实 id, 实体 id, 向量)。 */
    public record NameVectorWrite(UUID factId, UUID entityId, float[] embedding) {
    }

    /**
     * name_vectors.rs:99-135 {@code nearest}：同库里与查询向量最近的 k 条名字，按相似度
     * 降序。只看现行的名字事实、没被合并掉的实体。维度是 SQL 文本的一部分（0035 规矩），
     * 交给 mapper 内插。
     */
    public List<Near> nearest(UUID kbId, float[] query, int k) {
        int dims = query.length;
        if (dims == 0 || k == 0) {
            return List.of();
        }
        return vectors.selectNearest(kbId, VectorText.literal(query), dims, (long) k * 4, k);
    }

    // ---- embed_names（extraction_open.rs:814-850 的批语义，嵌入调用方注入） ----

    /** extraction_open.rs:814 NAME_EMBED_BATCH：一次送去嵌入的名字数（端点按请求限批）。 */
    public static final int NAME_EMBED_BATCH = 16;

    /** 嵌入端：一批文本 → 各自的向量（顺序对应）。实现方自管限流与退避。 */
    @FunctionalInterface
    public interface Embedder {
        List<float[]> embed(List<String> texts) throws Exception;
    }

    /**
     * extraction_open.rs:821-850 {@code embed_names}：一批名字各算一条向量，键是
     * name_key。<b>数量对不上整批放弃</b>（配对按位置，错一条全体错位）；任何失败只
     * 返回已算到的——名字向量是召回的辅助，抽取不因它失败（失败退字面召回）。
     */
    public static Map<String, float[]> embedNames(List<Map.Entry<String, String>> names,
                                                  Embedder client) {
        Map<String, float[]> out = new java.util.LinkedHashMap<>();
        for (int from = 0; from < names.size(); from += NAME_EMBED_BATCH) {
            List<Map.Entry<String, String>> batch =
                    names.subList(from, Math.min(from + NAME_EMBED_BATCH, names.size()));
            List<String> texts = batch.stream().map(Map.Entry::getValue).toList();
            List<float[]> vectors;
            try {
                vectors = client.embed(texts);
            } catch (Exception e) {
                // 名字向量没算出来，这一批退回字面召回——不抛
                continue;
            }
            if (vectors == null || vectors.size() != batch.size()) {
                // 数量对不上，这一批放弃（位置配对会全体错位）
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                out.put(batch.get(i).getKey(), vectors.get(i));
            }
        }
        return out;
    }

    /**
     * extraction_open.rs:853-876 {@code embed_pending_names} 的批语义：抽完一篇文档，
     * 把这个库里还没有向量的名字事实补一批（数量不符整批放弃——这里是补写，错位
     * 会把向量安到错的名字上，失败上抛由调用方记日志，不静默）。
     */
    public int embedPendingNames(UUID kbId, long limit, Embedder client) throws Exception {
        List<PendingName> pendingNames = pending(kbId, limit);
        if (pendingNames.isEmpty()) {
            return 0;
        }
        List<NameVectorWrite> items = new ArrayList<>();
        for (int from = 0; from < pendingNames.size(); from += NAME_EMBED_BATCH) {
            List<PendingName> batch =
                    pendingNames.subList(from, Math.min(from + NAME_EMBED_BATCH, pendingNames.size()));
            List<float[]> vectors = client.embed(batch.stream().map(PendingName::name).toList());
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("嵌入返回 " + (vectors == null ? 0 : vectors.size())
                        + " 条，送去的是 " + batch.size() + " 条");
            }
            for (int i = 0; i < batch.size(); i++) {
                items.add(new NameVectorWrite(batch.get(i).factId(), batch.get(i).entityId(),
                        vectors.get(i)));
            }
        }
        set(kbId, items);
        return items.size();
    }
}
