package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * users 表行（对齐 Rust utopia-core models::User，FromRow SELECT * 的列集）。
 * 字段为列名驼峰式；{@code deactivated_at} 一并取出——两个 find 查询的
 * {@code WHERE deactivated_at IS NULL} 谓词已把停用行挡在外面，此列仅备用核对。
 * JSON 序列化不含 password_hash：server 侧视图（MeView）负责挑字段，store 行不外泄。
 */
public record UserRow(
        UUID id,
        UUID orgId,
        String email,
        String passwordHash,
        String displayName,
        boolean isAdmin,
        OffsetDateTime createdAt,
        OffsetDateTime deactivatedAt) {
}
