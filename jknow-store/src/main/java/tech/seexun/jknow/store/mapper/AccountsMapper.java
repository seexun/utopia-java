package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.entity.WorkspaceRow;

/**
 * 账户域 mapper（对齐旧仓库 utopia-store/src/accounts.rs，SQL 逐字搬迁见
 * resources/mapper/AccountsMapper.xml，每条注释标 file:line）。
 * 注册事务的编排（首用户/加入者分支）在 AccountsService——多条语句同一事务。
 */
@Mapper
public interface AccountsMapper {

    /** 组织是否已存在（首用户判定）：accounts.rs:29-31。 */
    UUID selectOrgId();

    /** 建组织（首用户引导）：accounts.rs:36-37。 */
    int insertOrg(@Param("id") UUID id, @Param("name") String name);

    /** 建用户（RETURNING *），email 撞唯一索引抛 DataIntegrityViolation：accounts.rs:109-120。 */
    UserRow insertUser(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("email") String email,
                       @Param("passwordHash") String passwordHash, @Param("displayName") String displayName,
                       @Param("isAdmin") boolean isAdmin);

    /** 建工作区（RETURNING *）：accounts.rs:46-49 / 91-95。 */
    WorkspaceRow insertWorkspace(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("name") String name);

    /** 建工作区成员关系：accounts.rs:131-135。 */
    int insertMembership(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId,
                         @Param("role") String role);

    /** 默认（最早的）工作区，无则 null：accounts.rs:70-74。 */
    WorkspaceRow selectEarliestWorkspace(@Param("orgId") UUID orgId);

    /** 首用户连带建的 General 库：accounts.rs:159-166。返回 kb id 由调用方持有。 */
    int insertGeneralKb(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId,
                        @Param("description") String description);

    /** General 库建库者记 admin（与手动建库路径一致）：accounts.rs:171-176。 */
    int insertGeneralKbMember(@Param("kbId") UUID kbId, @Param("ownerId") UUID ownerId);

    /** 按 email 找账号——只找在职的：accounts.rs:228-232。 */
    UserRow selectUserByEmail(@Param("email") String email);

    /** 按 id 找账号——只找在职的（会话校验，停用立即生效）：accounts.rs:234-241。 */
    UserRow selectUserById(@Param("id") UUID id);

    /** 改显示名（RETURNING *）：accounts.rs:245-252。无行返回 null（调用方转 NotFound）。 */
    UserRow updateUserDisplayName(@Param("id") UUID id, @Param("displayName") String displayName);

    /** 改密码（server 层已验旧密并完成哈希）：accounts.rs:255-262。 */
    int updateUserPassword(@Param("id") UUID id, @Param("passwordHash") String passwordHash);
}
