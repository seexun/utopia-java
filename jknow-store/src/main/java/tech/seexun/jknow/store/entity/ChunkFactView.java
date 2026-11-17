package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 反向证据链行（models.rs:328-345 ChunkFactView；graph.rs:2067-2097 document_extractions）。 */
public record ChunkFactView(
        UUID chunkId,
        UUID factId,
        UUID subjectId,
        String subject,
        String predicate,
        boolean inferred,
        boolean isOpenStatement,
        UUID objectId,
        String object,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        float confidence) {
}
