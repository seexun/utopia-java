package tech.seexun.jknow.store;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.WorkspaceRow;
import tech.seexun.jknow.store.mapper.AccessMapper;
import tech.seexun.jknow.store.mapper.KbFamilyMapper;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.WorkspaceMapper;

/**
 * workspace/KB 写面 store（M4 任务 6——逐字对齐旧仓库 crates/utopia-store/src 的
 * workspaces.rs:20-95 与 kbs.rs:39-217、access.rs:114-137；HTTP 编排在
 * WorkspaceKbWriteController）。
 *
 * <p><b>事务口径</b>：Rust 的 workspaces::create 在一个 tx 里落「工作区行 + 创建者
 * owner membership」；kbs 侧三步（建库 → 可选 visibility 更新 → 创建者入矩阵 admin）
 * Rust 各语句独立提交，Java 侧包进一个 {@link TransactionTemplate}——建库是部署级
 * 管理动作，半途失败留下「没有管理员的库」比整体回滚更糟（本体导入失败不回滚的是
 * <i>包</i>，不是库本身）。语义等价点：三步全成才可见，与 Rust 顺序执行的外部效果一致。
 */
@Service
public class WorkspaceKbWriteService {

    private final WorkspaceMapper workspaces;
    private final KbFamilyMapper kbFamily;
    private final KbMapper kbs;
    private final AccessMapper access;
    private final TransactionTemplate tx;

    public WorkspaceKbWriteService(WorkspaceMapper workspaces, KbFamilyMapper kbFamily,
                                   KbMapper kbs, AccessMapper access,
                                   PlatformTransactionManager txManager) {
        this.workspaces = workspaces;
        this.kbFamily = kbFamily;
        this.kbs = kbs;
        this.access = access;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---- workspaces.rs:20-42 create：创建者为 owner ----

    /** workspaces.rs:20-42：组织内新建工作区，创建者 membership=owner（同一事务）。 */
    public WorkspaceRow createWorkspace(UUID orgId, UUID userId, String name) {
        return tx.execute(status -> {
            WorkspaceRow ws = workspaces.insertWorkspace(Uuids.nowV7(), orgId, name);
            workspaces.insertOwnerMembership(userId, ws.id());
            return ws;
        });
    }

    // ---- workspaces.rs:77-84 rename ----

    /** workspaces.rs:77-84：UPDATE ... RETURNING *（无行=NotFound）。 */
    public WorkspaceRow renameWorkspace(UUID id, String name) {
        WorkspaceRow ws = workspaces.renameWorkspace(id, name);
        if (ws == null) {
            throw AppException.NOT_FOUND;
        }
        return ws;
    }

    // ---- workspaces.rs:86-95 delete ----

    /** workspaces.rs:86-95：删工作区（级联）；行数 0=NotFound。 */
    public void deleteWorkspace(UUID id) {
        if (workspaces.deleteWorkspace(id) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    // ---- kbs.rs:39-66 create + kbs.rs:74-126 建库编排（本体包见 controller 的降级注记） ----

    /**
     * 建库三步（kbs.rs:96-124 逐序）：create → 可选 visibility update（走同一条
     * {@link #updateKb}——默认库 open 语义、bad 枚举都在那里）→ 创建者入矩阵 admin
     * （access.rs:114-137 set_kb_member）。收尾回读 KB（kbs.rs:124 的 kbs::get——
     * 编排可能补写过 visibility，读的是落库后的真相；M5 装包真身落地后回读同样成立）。
     */
    public KbRow createKb(UUID workspaceId, String name, String kind, String description,
                          String visibility, UUID creatorId) {
        UUID kbId = tx.execute(status -> {
            KbRow kb = kbFamily.insertKb(Uuids.nowV7(), workspaceId, name, kind, description);
            if (visibility != null) {
                updateKb(kb.id(), null, null, visibility, null, null, null, null, null,
                        null, null);
            }
            setKbMember(kb.id(), creatorId, "admin", creatorId);
            return kb.id();
        });
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            // 不可达路径（事务已提交，行必在）——保持 Rust kbs::get 的 NotFound 语义
            throw AppException.NOT_FOUND;
        }
        return kb;
    }

    // ---- access.rs:114-137 set_kb_member ----

    /** access.rs:114-137：矩阵 UPSERT（角色白名单 viewer|editor|admin；422 Validation）。 */
    public void setKbMember(UUID kbId, UUID userId, String role, UUID addedBy) {
        if (!"viewer".equals(role) && !"editor".equals(role) && !"admin".equals(role)) {
            throw AppException.validation("role must be viewer, editor or admin");
        }
        access.insertKbMember(kbId, userId, role, addedBy);
    }

    // ---- kbs.rs:100-171 update ----

    /**
     * 库设置更新（kbs.rs:100-171 逐分支）：bad_lang / visibility 枚举 / 默认库永远 open
     * 三道校验在 UPDATE 之前；governance 从关到开的 governance_since 由 SQL 的 CASE 记。
     * 无行=NotFound。
     */
    public KbRow updateKb(UUID id, String name, String description, String visibility,
                          Boolean autoExtendOntology, String ontologyLang,
                          Boolean materializeInferences, Integer inferenceIntervalMinutes,
                          Boolean autoTypeResolution, Boolean governance,
                          String dataConventions) {
        // 改语言不回头重写已有的类——这一列往后管的是新描述写成什么语言
        if (ontologyLang != null && !"en".equals(ontologyLang) && !"zh".equals(ontologyLang)) {
            throw AppException.invalid("bad_lang", "language must be en or zh");
        }
        if (visibility != null) {
            if (!"open".equals(visibility) && !"restricted".equals(visibility)) {
                throw AppException.validation("visibility must be open or restricted");
            }
            // 默认库永远 open：公共空间语义可依赖（改名/改描述不受限）
            if ("restricted".equals(visibility)) {
                KbRow current = kbs.selectById(id);
                if (current == null) {
                    throw AppException.NOT_FOUND;
                }
                if (current.isDefault()) {
                    throw AppException.invalid("default_kb_open",
                            "The default knowledge base stays open to everyone.");
                }
            }
        }
        KbRow updated = kbFamily.updateKb(id, name, description, visibility, autoExtendOntology,
                ontologyLang, materializeInferences, inferenceIntervalMinutes,
                autoTypeResolution, governance, dataConventions);
        if (updated == null) {
            throw AppException.NOT_FOUND;
        }
        return updated;
    }

    // ---- kbs.rs:201-217 delete ----

    /** kbs.rs:201-217：默认库不可删（API 强制 + DB CHECK 同一道语义）；无行=NotFound。 */
    public void deleteKb(UUID id) {
        KbRow current = kbs.selectById(id);
        if (current == null) {
            throw AppException.NOT_FOUND;
        }
        if (current.isDefault()) {
            throw AppException.invalid("default_kb_undeletable",
                    "The default knowledge base cannot be deleted.");
        }
        if (kbFamily.deleteKb(id) == 0) {
            throw AppException.NOT_FOUND;
        }
    }
}
