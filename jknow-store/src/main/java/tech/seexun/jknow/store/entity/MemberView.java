package tech.seexun.jknow.store.entity;

import java.util.UUID;

/** 工作区成员视图（models.rs:27-34 MemberView；members.rs:6-19 列集）。 */
public record MemberView(
        UUID userId,
        String email,
        String displayName,
        String role,
        boolean isAdmin) {
}
