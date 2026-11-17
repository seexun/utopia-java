package tech.seexun.jknow.store.entity;

import java.util.UUID;

/** 抽取丢弃信号行（models.rs:637-643 ExtractionDrop；extraction_drops.rs:100-108 for_kb）。 */
public record ExtractionDrop(
        UUID documentId,
        String reason,
        String detail,
        int count,
        String example) {
}
