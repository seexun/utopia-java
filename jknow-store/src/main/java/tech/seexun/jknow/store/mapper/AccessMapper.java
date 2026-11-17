package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 权限判定链两条查询（逐字：access.rs:12-37 kb_role 的两个 sqlx::query_as）。
 * 语义见 server/rbac/KbAccess：矩阵优先于 open；open 库 workspace membership → Viewer；
 * restricted 无记录 → 不可见（404，不泄露存在性）。
 */
public interface AccessMapper {

    /** access.rs:17：SELECT role FROM kb_members WHERE kb_id = $1 AND user_id = $2。 */
    String selectKbMemberRole(@Param("kbId") UUID kbId, @Param("userId") UUID userId);

    /** access.rs:29：SELECT role FROM memberships WHERE workspace_id = $1 AND user_id = $2。 */
    String selectWorkspaceRole(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);

    /**
     * access.rs:114-137 set_kb_member（M4 任务 6）：矩阵 UPSERT——改角色不改写最初邀请人
     * （加入信息记录的是"谁引进来的"，added_by COALESCE 保旧）。角色白名单
     * viewer|editor|admin 在服务层（Rust 在函数头，422 Validation 无 code）。
     */
    void insertKbMember(@Param("kbId") UUID kbId, @Param("userId") UUID userId,
                        @Param("role") String role, @Param("addedBy") UUID addedBy);
}
