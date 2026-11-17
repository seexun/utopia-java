package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审计事件视图（models.rs:391-404 AuditEventView serde 形状；history SQL audit.rs:102-106）。
 * actor_name NULL = 引擎自动；actor_id 有值而 actor_name NULL = 人已被移除。
 */
public record AuditEventView(
        UUID id,
        String action,
        String targetKind,
        UUID targetId,
        JsonNode detail,
        UUID actorId,
        String actorName,
        OffsetDateTime createdAt) {
}
