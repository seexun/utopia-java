package tech.seexun.jknow.ingest;

/**
 * 一个分好的块（chunker.rs ChunkPiece 逐字段）。块的正文是原文的切片，一个字不改；
 * 前缀（面包屑、说明句、表头）也是原文的切片，只是从别处抄来的——
 * {@code charStart}/{@code charEnd}（UTF-8 字节偏移，含头不含尾，与 Rust chunks 表同尺度）
 * 指正文那一段，不含前缀。
 *
 * @param heading 这块所在的章节路径，" › " 分隔；没有标题的文档为 null
 */
public record ChunkPiece(int seq, String text, int charStart, int charEnd, String heading,
        Provenance provenance) {
}
