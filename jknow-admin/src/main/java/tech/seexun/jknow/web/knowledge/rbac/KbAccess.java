package tech.seexun.jknow.web.knowledge.rbac;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.AccessMapper;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * KB 级访问判定（逐字对齐旧仓库 crates/utopia-store/src/access.rs:1-95）——
 * 全部 KB 作用域路由的唯一鉴权入口。判定链：
 * <ol>
 *   <li>系统管理员（users.is_admin）→ Owner 全通（access.rs:13-15）；</li>
 *   <li>kb_members 矩阵有记录 → 矩阵角色（access.rs:16-24；脏角色值 parse 失败=不可见）；</li>
 *   <li>open 库 → 按部署角色：该库所在 workspace 的 membership 有行 = Viewer
 *       （access.rs:25-35；写权限一律来自本库矩阵，部署角色不向库内映射写权）；</li>
 *   <li>restricted 库无记录 → <b>NotFound</b>（不泄露库的存在性，access.rs:93）；</li>
 *   <li>角色不足 → <b>Forbidden</b>（access.rs:92）。</li>
 * </ol>
 * SQL 在 AccessMapper/KbMapper（逐字，file:line 注释）。
 */
@Component
public class KbAccess {

    private final KbMapper kbs;
    private final AccessMapper access;

    public KbAccess(KbMapper kbs, AccessMapper access) {
        this.kbs = kbs;
        this.access = access;
    }

    /** access.rs:83-95 require_kb：先库存在性（kbs::get 68-73，无行 NotFound）再判定链。 */
    public KbRow requireKb(UserRow user, UUID kbId, Role min) {
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw AppException.NOT_FOUND;
        }
        Role role = kbRole(user, kb);
        if (role == null) {
            throw AppException.NOT_FOUND;
        }
        if (role.rank() < min.rank()) {
            throw AppException.FORBIDDEN;
        }
        return kb;
    }

    /** access.rs:12-37 kb_role：用户在某 KB 的有效角色（null = 不可见）。 */
    public Role kbRole(UserRow user, KbRow kb) {
        if (user.isAdmin()) {
            return Role.OWNER;
        }
        String matrix = access.selectKbMemberRole(kb.id(), user.id());
        if (matrix != null) {
            // 矩阵优先于 open——有记录就不再看 visibility；parse 失败（脏角色）= 不可见
            return Role.parse(matrix);
        }
        if ("open".equals(kb.visibility())) {
            // open 库 = 部署内人人可读（有 workspace membership 即 Viewer）
            String ws = access.selectWorkspaceRole(kb.workspaceId(), user.id());
            return ws != null ? Role.VIEWER : null;
        }
        return null;
    }
}
