package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 短语签名的向量（signature_vectors.rs 86 行逐字；#1097，迁移 0110）——短语对齐给候选
 * 多的签名开短名单用的缓存：按 (库, 嵌入模型, 文本的哈希) 存，一轮只嵌没见过的文本。
 * 只按键读，不做近邻查询，不建 HNSW、不登记 vector_index；删了下一轮重嵌，结果一样。
 */
public interface SignatureVectorsMapper {

    /**
     * signature_vectors.rs:34-49 get：这些哈希里已经存了向量的那些。embedding 以文本形态
     * 读；哈希走 hex 文本（bytea 数组在 MyBatis 侧没有现成 typehandler，PG 的 bytea 输入
     * 解析 hex 文本，等价不换形状）。
     */
    List<VectorRow> selectByHashes(@Param("kbId") UUID kbId, @Param("model") String model,
                                   @Param("hashes") String[] hashes);

    /** signature_vectors.rs:52-73 put：存一批新嵌的向量；撞键不覆盖（同模型同文本同向量）。 */
    int insertVector(@Param("kbId") UUID kbId, @Param("model") String model,
                     @Param("hash") String hash, @Param("vector") String vector);

    /** signature_vectors.rs:78-86 prune：删掉不再用得上的（哈希不在 keep 里、别的模型嵌的）。 */
    long prune(@Param("kbId") UUID kbId, @Param("model") String model,
               @Param("keep") String[] keep);

    /** (text_hash hex, embedding 文本形态)。 */
    record VectorRow(String hash, String vector) {
    }
}
