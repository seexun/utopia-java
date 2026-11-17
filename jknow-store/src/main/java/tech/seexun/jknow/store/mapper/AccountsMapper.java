package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 账户域 mapper（源 utopia-store/src/accounts.rs）。P1 任务 7 身份主档切 sys_user：
 * <ul>
 * <li>注册族四条（insertUser/selectUserByEmail/updateUserDisplayName/updateUserPassword）
 *     随 auth 域舍弃删除（登录/注册由若依持有）；</li>
 * <li>注册编排语句族（selectOrgId/insertOrg/insertWorkspace/insertMembership/
 *     selectEarliestWorkspace/insertGeneralKb/insertGeneralKbMember）的编排者
 *     AccountsService 同删，一并退役——建库路径走 WorkspaceKbWriteService；</li>
 * <li>存活仅 selectUserById：MCP PAT 两道认证的「人」校验（utopia_uuid → sys_user）。</li>
 * </ul>
 */
@Mapper
public interface AccountsMapper {

    /**
     * 按 utopia_uuid 找账号——只找未删未停用的（MCP PAT 校验，停用立即生效）：
     * accounts.rs:234-241 语义，身份主档 sys_user（V0101 桥接列）。
     */
    UserRow selectUserById(@Param("id") UUID id);
}
