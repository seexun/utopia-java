package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.MemberView;
import tech.seexun.jknow.store.entity.OrgUser;
import tech.seexun.jknow.store.entity.WorkspaceRow;

/**
 * workspace 域读 mapper（逐字搬迁自旧仓库 utopia-store/src/workspaces.rs、members.rs、
 * settings.rs；每条 SQL 的 file:line 见 WorkspaceMapper.xml）。
 */
public interface WorkspaceMapper {

    /** workspaces.rs:6-17 list_for_user：我的工作区（membership 联表，created_at 序）。 */
    List<WorkspaceRow> listForUser(@Param("userId") UUID userId);

    /** workspaces.rs:44-50 get：require_role 的存在性判定，无行→NotFound。 */
    WorkspaceRow selectById(@Param("id") UUID id);

    /** members.rs:6-19 list：工作区成员（停用的人不出现，成员关系那一行留着）。 */
    List<MemberView> selectMembers(@Param("workspaceId") UUID workspaceId);

    /** members.rs:22-31 org_users：部署内全部在职用户（成员选人器）。 */
    List<OrgUser> selectOrgUsers(@Param("orgId") UUID orgId);

    /** members.rs:99-108 deactivated_users：已停用账号（仅管理员；按停用时刻倒序）。 */
    List<OrgUser> selectDeactivatedUsers(@Param("orgId") UUID orgId);

    /**
     * settings.rs:17-24 get：llm_settings 行。四把 api_key **封印形态原样**（LlmSettingsRow
     * javadoc 纪律——不解密，读面只折 has_*_key 布尔）。
     */
    LlmSettingsRow selectSettings(@Param("workspaceId") UUID workspaceId);

    // ---- 写面（workspaces.rs:20-42 create / :77-84 rename / :86-95 delete；M4 任务 6） ----

    /** workspaces.rs:28-33 INSERT ... RETURNING *（id 由调用方生成，v7）。 */
    WorkspaceRow insertWorkspace(@Param("id") UUID id, @Param("orgId") UUID orgId,
                                 @Param("name") String name);

    /**
     * workspaces.rs:34-38 建区同事务落 owner membership（role 列值由 SQL 字面量给出——
     * Rust 侧绑 Role::Owner.as_str()，值同 "owner"）。
     */
    void insertOwnerMembership(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    /** workspaces.rs:78-83 UPDATE ... RETURNING *（无行=NotFound）。 */
    WorkspaceRow renameWorkspace(@Param("id") UUID id, @Param("name") String name);

    /** workspaces.rs:87-90 DELETE（返回行数；0=NotFound）。 */
    int deleteWorkspace(@Param("id") UUID id);
}
