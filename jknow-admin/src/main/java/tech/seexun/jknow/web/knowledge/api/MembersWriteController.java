package tech.seexun.jknow.web.knowledge.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.web.knowledge.rbac.WorkspaceAccess;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.AccessMapper;
import tech.seexun.jknow.store.mapper.MembersWriteMapper;

/**
 * 工作空间/库成员写面（members_routes.rs:61-119 set_role/remove + kbs.rs:280-320
 * set_member/remove_member 逐语义——M5 任务 6 切流演练落位：此前剧本 target:rust 留在
 * Rust 侧）。
 *
 * <p>owner 守卫两条：授予或剥夺 owner 角色只有 owner 能做；最后一个 owner 不许降级/
 * 移除（last_owner_demote / last_owner_remove）。库成员面：Admin 起步、矩阵 UPSERT 不改写
 * 最初邀请人（added_by COALESCE）、审计两笔。
 */
@RestController
public class MembersWriteController {

    private final WorkspaceAccess wsAccess;
    private final KbAccess rbac;
    private final AccessMapper access;
    private final MembersWriteMapper members;
    private final AuditService audit;

    public MembersWriteController(WorkspaceAccess wsAccess, KbAccess rbac, AccessMapper access,
                                  MembersWriteMapper members, AuditService audit) {
        this.wsAccess = wsAccess;
        this.rbac = rbac;
        this.access = access;
        this.members = members;
        this.audit = audit;
    }

    /** members_routes.rs:55-59 SetRoleReq。 */
    public record SetRoleReq(String role) {
    }

    /** members_routes.rs:61-92 set_role。 */
    @PutMapping("/api/v1/workspaces/{id}/members/{userId}")
    public java.util.Map<String, Object> setRole(@PathVariable UUID id, @PathVariable UUID userId,
                                                 @RequestBody SetRoleReq body,
                                                 HttpServletRequest request) {
        UserRow user = user(request);
        Role newRole = Role.parse(body.role());
        if (newRole == null) {
            throw AppException.validation("Role must be one of owner/admin/editor/viewer");
        }
        Role callerRole = wsAccess.requireRole(user, id, Role.ADMIN);
        String raw = access.selectWorkspaceRole(id, userId);
        Role targetRole = raw == null ? null : Role.parse(raw);
        // owner 角色的授予或剥夺，只有 owner 能做
        boolean touchesOwner = newRole == Role.OWNER || targetRole == Role.OWNER;
        if (touchesOwner && callerRole != Role.OWNER) {
            throw AppException.FORBIDDEN;
        }
        // 不能把最后一个 owner 降级
        if (targetRole == Role.OWNER && newRole != Role.OWNER
                && members.countOwners(id) <= 1) {
            throw AppException.invalid("last_owner_demote", "Cannot demote the last owner");
        }
        // 目标用户必须存在而且在职（members.rs:58-66 的防呆）
        if (members.selectActiveUser(userId) == null) {
            throw AppException.NOT_FOUND;
        }
        members.upsertMembership(userId, id, newRole.asStr());
        return java.util.Map.of("ok", true);
    }

    /** members_routes.rs:94-119 remove。 */
    @DeleteMapping("/api/v1/workspaces/{id}/members/{userId}")
    public java.util.Map<String, Object> remove(@PathVariable UUID id, @PathVariable UUID userId,
                                                HttpServletRequest request) {
        UserRow user = user(request);
        Role callerRole = wsAccess.requireRole(user, id, Role.ADMIN);
        String raw = access.selectWorkspaceRole(id, userId);
        if (raw == null) {
            throw AppException.NOT_FOUND;
        }
        Role targetRole = Role.parse(raw);
        if (targetRole == Role.OWNER) {
            if (callerRole != Role.OWNER) {
                throw AppException.FORBIDDEN;
            }
            if (members.countOwners(id) <= 1) {
                throw AppException.invalid("last_owner_remove", "Cannot remove the last owner");
            }
        }
        if (members.deleteMembership(id, userId) == 0) {
            throw AppException.NOT_FOUND;
        }
        return java.util.Map.of("ok", true);
    }

    /** kbs.rs:280-299 set_member：矩阵 UPSERT + 审计 kb.member_set。 */
    @PutMapping("/api/v1/kbs/{id}/members/{userId}")
    public java.util.Map<String, Object> setKbMember(@PathVariable UUID id,
                                                     @PathVariable UUID userId,
                                                     @RequestBody SetRoleReq body,
                                                     HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, id, Role.ADMIN);
        String role = body.role();
        if (!"viewer".equals(role) && !"editor".equals(role) && !"admin".equals(role)) {
            throw AppException.validation("role must be viewer, editor or admin");
        }
        access.insertKbMember(id, userId, role, user.id());
        audit.record(client(request), id, user.id(), "kb.member_set", "user", userId,
                "{\"role\": \"" + role + "\"}");
        return java.util.Map.of("ok", true);
    }

    /** kbs.rs:302-320 remove_member：0 行 → NotFound + 审计 kb.member_removed。 */
    @DeleteMapping("/api/v1/kbs/{id}/members/{userId}")
    public java.util.Map<String, Object> removeKbMember(@PathVariable UUID id,
                                                        @PathVariable UUID userId,
                                                        HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, id, Role.ADMIN);
        if (members.deleteKbMember(id, userId) == 0) {
            throw AppException.NOT_FOUND;
        }
        audit.record(client(request), id, user.id(), "kb.member_removed", "user", userId, "{}");
        return java.util.Map.of("ok", true);
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }
}
