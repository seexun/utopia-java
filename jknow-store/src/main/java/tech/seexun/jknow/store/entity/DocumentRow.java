package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * documents 表行（models.rs:84-137 Document 的列集，SELECT * 全 25 列按名映射）。
 * tags text[]；time_context jsonb；墓碑 deleted_at / 真删 purged_at（#268）。
 */
public record DocumentRow(
        UUID id,
        UUID kbId,
        UUID sourceId,
        String filename,
        String mime,
        long sizeBytes,
        String sha256,
        String status,
        String error,
        OffsetDateTime docTime,
        String docTimeSource,
        JsonNode timeContext,
        OffsetDateTime timeContextAt,
        String graphStatus,
        String graphError,
        int textLen,
        int chunkCount,
        List<String> tags,
        String externalKey,
        OffsetDateTime missingSince,
        OffsetDateTime deletedAt,
        OffsetDateTime purgedAt,
        String readerNeeded,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
