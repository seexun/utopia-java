package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 个人访问令牌视图（models.rs:1689-1701 TokenView；tokens.rs:119-128 列集）。 */
public record TokenView(
        UUID id,
        String name,
        String tokenPrefix,
        String scope,
        List<UUID> kbIds,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt) {
}
