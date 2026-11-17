package tech.seexun.jknow.web.knowledge.rbac;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.AccessMapper;

/**
 * 工作区级权限判定（workspaces.rs:52-75 role_of / require_role 逐字语义）：
 * 非成员 → NotFound（不泄露存在性）；角色不足 → Forbidden；角色序 viewer&lt;editor&lt;admin&lt;owner。
 * SQL=AccessMapper.selectWorkspaceRole（memberships 行）。
 */
@Component
public class WorkspaceAccess {

    private final AccessMapper access;

    public WorkspaceAccess(AccessMapper access) {
        this.access = access;
    }

    /** workspaces.rs:64-75 require_role：返回调用者在工作区中的角色。 */
    public Role requireRole(UserRow user, UUID workspaceId, Role min) {
        String raw = access.selectWorkspaceRole(workspaceId, user.id());
        if (raw == null) {
            throw AppException.NOT_FOUND;
        }
        Role role = Role.parse(raw);
        if (role == null) {
            // 脏角色值（role_of 的 parse 失败=None）：按非成员同路 NotFound——
            // memberships.role 有 CHECK 约束，此为理论路径，与 Rust 逐字
            throw AppException.NOT_FOUND;
        }
        if (role.rank() < min.rank()) {
            throw AppException.FORBIDDEN;
        }
        return role;
    }

    /** workspaces.rs:52-60 role_of：非成员返回 null（页面组装用，不抛）。 */
    public Role roleOf(UserRow user, UUID workspaceId) {
        String raw = access.selectWorkspaceRole(workspaceId, user.id());
        return raw == null ? null : Role.parse(raw);
    }
}
