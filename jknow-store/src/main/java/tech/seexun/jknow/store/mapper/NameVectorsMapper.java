package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 名字向量 SQL（name_vectors.rs 逐字搬迁）：召回第二条通道（0041 决定 3，第 2 刀）的
 * 待补清单、写入与最近邻。**只提议不决定**——最近的名字是不是同一个东西，由消解那头
 * 按既有规矩（画像、裁决器）去判。
 *
 * <p>维度字面量进 SQL 文本（0035 的两条规矩：{@code <=>} 两侧 cast 到字面维度、谓词带
 * vector_dims(...) = N）——dims 由调用方给，nearest 语句在 XML 用 ${dims} 内插。
 */
public interface NameVectorsMapper {

    /** name_vectors.rs:36-45 一条被召回的名字：(fact_id, entity_id, name)。 */
    record PendingName(UUID factId, UUID entityId, String name) {
    }

    /** name_vectors.rs:83-90 Near：它挂在哪个实体上、那个实体是什么类、名字、余弦。 */
    record Near(UUID entityId, UUID factId, String name, String typeLabel, float similarity) {
    }

    /** name_vectors.rs:36-44 pending：还没有向量的名字事实（按 facts.id = 写入先后）。 */
    List<PendingName> selectPending(@Param("kbId") UUID kbId, @Param("knownAs") String knownAs,
                                    @Param("limit") long limit);

    /**
     * name_vectors.rs:63-66 set 的单条：同一条事实重写就覆盖（换了模型重算）。
     * 向量以 '[a,b,…]' 文本形态传入、PG 侧 cast vector。
     */
    int insertVector(@Param("factId") UUID factId, @Param("kbId") UUID kbId,
                     @Param("entityId") UUID entityId, @Param("embedding") String embedding);

    /**
     * name_vectors.rs:106-125 nearest（逐字，${dims} 按 vector_index::distance/same_dims
     * 展开）：里层按索引能接住的形状取 k*4 条，外层再按事实与实体状态过滤——过滤放在
     * 里层会让索引用不上（0035）。外层次序照 RESORT 规矩（distance + 0, id）。
     */
    List<Near> selectNearest(@Param("kbId") UUID kbId, @Param("query") String query,
                             @Param("dims") int dims, @Param("scan") long scan,
                             @Param("k") long k);
}
