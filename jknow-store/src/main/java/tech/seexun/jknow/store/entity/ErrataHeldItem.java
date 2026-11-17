package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 勘误闸门留给人的一笔（errata.rs:726-739 HeldItem serde 形状；SQL errata.rs:741-755）。
 * proposed/detail 为 jsonb。
 */
public record ErrataHeldItem(
        UUID id,
        UUID documentId,
        String document,
        String action,
        String flag,
        UUID factId,
        JsonNode proposed,
        String reason,
        String quote,
        String detail,
        OffsetDateTime createdAt) {
}
