package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * Agent/对话记忆 episodes 快速路径（memory.rs 逐字搬迁）：隐式 Memory 来源 / Memory log
 * 文档 / episode chunk 追加。每条 XML 内注明 file:line。
 */
public interface MemoryMapper {

    /** memory.rs:22-26 取隐式 Memory 来源。 */
    UUID selectMemorySource(@Param("kbId") UUID kbId, @Param("kind") String kind);

    /** memory.rs:27-34 建（kind='memory'，name 固定 'Memory'）。 */
    int insertMemorySource(@Param("id") UUID id, @Param("kbId") UUID kbId,
                           @Param("kind") String kind);

    /** memory.rs:35-40 取 Memory log 文档（external_key='memory:log'）。 */
    UUID selectMemoryDoc(@Param("kbId") UUID kbId, @Param("externalKey") String externalKey);

    /** memory.rs:41-56 建（sha256 哨兵值绕开同内容去重；doc_time_source='none'）。 */
    int insertMemoryDoc(@Param("id") UUID id, @Param("kbId") UUID kbId,
                        @Param("sourceId") UUID sourceId, @Param("externalKey") String externalKey);

    /** memory.rs:97-103 episode 落 chunk（seq 取现行最大 +1）。 */
    int insertEpisodeChunk(@Param("id") UUID id, @Param("kbId") UUID kbId,
                           @Param("documentId") UUID documentId, @Param("text") String text,
                           @Param("charEnd") int charEnd);

    /** memory.rs:111-116 文档账面（chunk_count/size_bytes）跟着走。 */
    int bumpMemoryDoc(@Param("id") UUID id, @Param("addedBytes") long addedBytes);

    /** memory.rs:132-137 is_memory_document 的 SELECT（命中即记忆日志）。 */
    Integer selectIsMemoryDocument(@Param("documentId") UUID documentId,
                                   @Param("kind") String kind);
}
