package tech.seexun.jknow.store.entity;

import java.util.UUID;

/**
 * 检索命中的分块视图（对齐旧仓库 utopia-core/src/models.rs:441-447 ChunkView）：
 * {@code {id, document_id, seq, text, filename}}——search 域响应 results 数组的元素形状，
 * 契约剧本按字段集逐字比对（snake_case 出口经全局 Jackson 命名策略）。
 */
public record ChunkView(UUID id, UUID documentId, int seq, String text, String filename) {
}
