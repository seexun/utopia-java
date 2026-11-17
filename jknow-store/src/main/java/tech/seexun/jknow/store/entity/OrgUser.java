package tech.seexun.jknow.store.entity;

import java.util.UUID;

/** 部署内用户行（models.rs:37-43 OrgUser；org_users / deactivated_users 共用形状）。 */
public record OrgUser(
        UUID id,
        String email,
        String displayName,
        boolean isAdmin) {
}
