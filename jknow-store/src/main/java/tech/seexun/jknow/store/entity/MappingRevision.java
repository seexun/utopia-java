package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 口径改版历史行（models.rs:1178-1183 MappingRevision；mappings.rs:518-540，before=整版快照）。 */
public record MappingRevision(
        UUID id,
        JsonNode before,
        String changedByName,
        OffsetDateTime changedAt) {
}
