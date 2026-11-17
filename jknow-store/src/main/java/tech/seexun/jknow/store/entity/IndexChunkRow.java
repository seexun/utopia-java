package tech.seexun.jknow.store.entity;

import java.util.UUID;

/**
 * 全文索引重建的源行（对齐旧仓库 documents.rs:1843 all_chunks_for_index 的元组
 * {@code (kb_id, document_id, id, text)}）——M1 任务 6 ChunkIndexer 读共享库 chunks
 * 全量建 ES 索引的行形状。
 */
public record IndexChunkRow(UUID kbId, UUID documentId, UUID id, String text) {
}
