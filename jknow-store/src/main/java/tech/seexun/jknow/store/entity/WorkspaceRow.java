package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** workspaces 表行（对齐 Rust models::Workspace，serde 字段 id/org_id/name/created_at）。 */
public record WorkspaceRow(
        UUID id,
        UUID orgId,
        String name,
        OffsetDateTime createdAt) {
}
