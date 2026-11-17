package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作空间/库成员写面（members.rs:52-91 + access.rs:167-177 逐字，M5 任务 6 切流演练
 * 落位——此前剧本 target:rust 留在 Rust 侧）。
 */
@Mapper
public interface MembersWriteMapper {

    /** members.rs:61-66：目标用户必须存在于本组织**而且在职**——否则一条谁也发现不了的授权。 */
    UUID selectActiveUser(@Param("id") UUID id);

    /** members.rs:68-76 set_role：UPSERT；在职检查在先（selectActiveUser）。 */
    void upsertMembership(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId,
                          @Param("role") String role);

    /** members.rs:81-91 remove：0 行 = 不是成员 → NotFound 由服务层报。 */
    int deleteMembership(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId);

    /** members.rs:33-41 owner_count：最后一个 owner 不许降/删。 */
    long countOwners(@Param("workspaceId") UUID workspaceId);

    /** access.rs:167-177 remove_kb_member：0 行 = 不是成员 → NotFound。 */
    int deleteKbMember(@Param("kbId") UUID kbId, @Param("userId") UUID userId);
}
