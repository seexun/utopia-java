package tech.seexun.jknow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.VectorIndexService.Target;

/**
 * 移植自 crates/utopia-store/src/vector_index.rs 内联 mod tests（:357-380，3 例）——
 * 索引名带表/列/维度、维度写进 SQL 字面量（读路径三条规矩的头两条）、目标经任务载荷
 * 往返。纯函数直译，无库（与 Rust 同模块内联测试对等）。
 *
 * <p>这三条是「索引悄悄不生效」的第一道闸：名字或 SQL 拼错不报错，只是查询退回顺扫——
 * 见 the_nearest_chunk_is_found_however_it_is_reached（真库对照）。
 */
class VectorIndexPureTests {

    @Test
    void theIndexNameCarriesTableColumnAndDims() {
        assertEquals("chunks_embedding_hnsw_1024",
                VectorIndexService.indexName(Target.CHUNKS, 1024));
        assertEquals("entities_profile_embedding_hnsw_768",
                VectorIndexService.indexName(Target.ENTITY_PROFILES, 768));
    }

    @Test
    void theSqlWritesTheDimensionAsALiteral() {
        assertEquals("vector_dims(c.embedding) = 1024",
                VectorIndexService.sameDims("c.embedding", 1024));
        assertEquals("c.embedding::vector(1024) <=> $2::vector(1024)",
                VectorIndexService.distance("c.embedding", 2, 1024));
    }

    @Test
    void aTargetRoundTripsThroughThePayload() {
        for (Target t : new Target[] {Target.CHUNKS, Target.ENTITY_PROFILES}) {
            assertEquals(t, Target.parse(t.key()));
        }
        assertNull(Target.parse("documents"));
    }
}
