package tech.seexun.jknow.store.entity;

import java.util.UUID;

/** KB 成员矩阵行（models.rs:1136-1144 KbMemberView；access.rs:101-112 列集，无 is_admin）。 */
public record KbMemberView(
        UUID userId,
        String email,
        String displayName,
        String role) {
}
