package tech.seexun.jknow.store;

import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.Registered;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.entity.WorkspaceRow;
import tech.seexun.jknow.store.mapper.AccountsMapper;

/**
 * 注册（单事务，对齐旧仓库 utopia-store/src/accounts.rs:16-108 register 的编排逐分支）：
 * <ul>
 * <li>部署内还没有组织 → 首个用户：建 org + admin 用户 + 默认工作区 + owner membership
 *     + General 库（kind knowledge / is_default TRUE / visibility open）+ kb_members admin；</li>
 * <li>已有组织 → 加入该组织（is_admin=FALSE），viewer 进最早工作区（无工作区则补建给 owner）；
 *     open_registration=false 时 422 registration_closed（仅引导首用户例外）。</li>
 * </ul>
 * email 撞唯一索引（users_email_active_idx，只约束在职账号）→ 409 "This email is already registered"。
 */
@Service
public class AccountsService {

    private final AccountsMapper accounts;

    public AccountsService(AccountsMapper accounts) {
        this.accounts = accounts;
    }

    /** General 库描述（accounts.rs:165 原文）。 */
    static final String GENERAL_KB_DESCRIPTION = "Shared space for the whole deployment. Everyone can read it.";

    @Transactional
    public Registered register(String email, String passwordHash, String displayName,
                               String orgName, boolean openRegistration) {
        UUID existingOrg = accounts.selectOrgId();

        UserRow user;
        WorkspaceRow workspace;
        UUID generalKb;

        if (existingOrg == null) {
            // 首个用户：引导整个部署（org 名缺省 'Default Organization'）
            UUID orgId = Uuids.nowV7();
            accounts.insertOrg(orgId, orgName != null ? orgName : "Default Organization");

            user = insertUser(orgId, email, passwordHash, displayName, true);

            workspace = accounts.insertWorkspace(Uuids.nowV7(), orgId, "Default Workspace");

            accounts.insertMembership(user.id(), workspace.id(), "owner");
            generalKb = insertGeneralKb(workspace.id(), user.id());
        } else {
            if (!openRegistration) {
                throw AppException.invalid("registration_closed",
                        "Registration is closed for this deployment. Contact your administrator.");
            }
            user = insertUser(existingOrg, email, passwordHash, displayName, false);

            // 加入默认（最早的）工作区；异常情况下组织没有工作区则补建一个
            WorkspaceRow defaultWs = accounts.selectEarliestWorkspace(existingOrg);
            if (defaultWs != null) {
                accounts.insertMembership(user.id(), defaultWs.id(), "viewer");
                workspace = defaultWs;
                generalKb = null;
            } else {
                WorkspaceRow ws = accounts.insertWorkspace(Uuids.nowV7(), existingOrg, "Default Workspace");
                accounts.insertMembership(user.id(), ws.id(), "owner");
                workspace = ws;
                generalKb = null;
            }
        }

        return new Registered(user, workspace, generalKb);
    }

    /** insert_user + 唯一冲突翻译（accounts.rs:109-123：仅此一处把 unique violation 译成 409）。 */
    private UserRow insertUser(UUID orgId, String email, String passwordHash, String displayName,
                               boolean isAdmin) {
        try {
            return accounts.insertUser(Uuids.nowV7(), orgId, email, passwordHash, displayName, isAdmin);
        } catch (DataAccessException e) {
            if (uniqueViolation(e)) {
                throw AppException.conflict("This email is already registered");
            }
            throw e;
        }
    }

    /** PG SQLState 23505 = unique_violation（对齐 sqlx db.is_unique_violation()；按 SQLException 判，不引驱动编译依赖）。 */
    private static boolean uniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** General 库 + 建库者 admin（accounts.rs:153-177 insert_general_kb）。 */
    private UUID insertGeneralKb(UUID workspaceId, UUID ownerId) {
        UUID kbId = Uuids.nowV7();
        accounts.insertGeneralKb(kbId, workspaceId, GENERAL_KB_DESCRIPTION);
        accounts.insertGeneralKbMember(kbId, ownerId);
        return kbId;
    }
}
