package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 等人点头的一条记忆事实（models.rs:1410-1447 PendingFactView serde 形状，26 列全量）。
 * quote=那句记忆的全文；object_value/qualifiers/time_words 为 jsonb（JsonNode 透传）；
 * phrase 非空 = 开放陈述（0044）。
 */
public record PendingFactView(
        UUID id,
        UUID subjectId,
        String subjectName,
        UUID predicateId,
        String predicateLabel,
        String proposedPredicate,
        UUID objectId,
        String objectName,
        JsonNode objectValue,
        OffsetDateTime validFrom,
        String validFromPrecision,
        OffsetDateTime validTo,
        String validToPrecision,
        float confidence,
        UUID chunkId,
        String quote,
        UUID proposedBy,
        String proposedByName,
        String proposedTokenName,
        OffsetDateTime createdAt,
        String phrase,
        JsonNode qualifiers,
        JsonNode timeWords,
        Integer quoteStart,
        Integer quoteEnd) {
}
