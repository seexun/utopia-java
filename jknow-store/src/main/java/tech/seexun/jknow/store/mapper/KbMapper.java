package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.KbRow;

/**
 * knowledge_bases 表读（逐字：kbs.rs:68-73 get、access.rs:51-56 的全库 id）。
 * rbac 判定链（KbAccess）的唯一入口查询；任务 5 增全库 id（告警可见性 is_admin 支）。
 */
public interface KbMapper {

    /** SELECT * FROM knowledge_bases WHERE id = $1（kbs.rs:68-73；无行=NotFound）。 */
    KbRow selectById(@Param("id") UUID id);

    /**
     * access.rs:52（visible_kb_roles 的 is_admin 支：全部 KB，角色在调用侧给 Owner）。
     * Rust 侧 Vec&lt;(Uuid,)&gt; 单列同构——只 SELECT id 就只回 id（复用全列 kbRow 会因
     * 构造器映射缺列抛 ExecutorException，admin 进 /alerts 即 500；走查1 Critical）。
     */
    List<UUID> selectAllIds();

    /**
     * kbs.rs:6-13 list 的 id 面（WHERE workspace_id ORDER BY created_at 逐字）：
     * settings PUT 配好嵌入模型时逐库排 embed_ontology（settings_routes.rs:112-119）。
     */
    List<UUID> selectIdsForWorkspace(@Param("workspaceId") UUID workspaceId);
}
