package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.KbMemberView;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.MyKbInfo;
import tech.seexun.jknow.store.entity.Readiness;

/**
 * KB 家族读 mapper（逐字搬迁自旧仓库 utopia-store/src/kbs.rs、access.rs；
 * 每条 SQL 的 file:line 见 KbFamilyMapper.xml）。
 */
public interface KbFamilyMapper {

    /** kbs.rs:16-37 list_visible：系统管理员全见；open 库 + 矩阵内 restricted 库。 */
    List<KbRow> listVisible(@Param("workspaceId") UUID workspaceId,
                            @Param("userId") UUID userId,
                            @Param("isAdmin") boolean isAdmin);

    /** kbs.rs:173-199 readiness：一次查询拿全（模型/文档/进行中/失败/实体五列）。 */
    Readiness selectReadiness(@Param("kbId") UUID kbId);

    /** access.rs:101-112 kb_members：库成员矩阵（停用的人不出现）。 */
    List<KbMemberView> selectKbMembers(@Param("kbId") UUID kbId);

    /** access.rs:142-165 my_kb_infos：我在各库的成员信息 + 概览统计。 */
    List<MyKbInfo> selectMyKbInfos(@Param("kbIds") List<UUID> kbIds,
                                   @Param("userId") UUID userId);

    /**
     * access.rs:50-80 visible_kb_roles：kb_role 的集合版（告警可见性用）。
     * 返回 (kb_id, role) 对；is_admin → 全部 KB Owner。
     */
    List<VisibleKbRole> selectVisibleKbRoles(@Param("userId") UUID userId);

    /** selectVisibleKbRoles 的行（三条分支与 kb_role 一一对应）。 */
    record VisibleKbRole(UUID id, String matrixRole, boolean openMember) {
    }

    // ---- 写面（kbs.rs:39-66 create / :100-171 update / :201-217 delete；M4 任务 6） ----

    /**
     * kbs.rs:50-56 INSERT：部署的第一个库自动 is_default；ontology_lang 取部署默认
     * （deployment_settings LIMIT 1，缺省 'en'）。RETURNING *（KbRow 全 20 列）。
     */
    KbRow insertKb(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId,
                   @Param("name") String name, @Param("kind") String kind,
                   @Param("description") String description);

    /**
     * kbs.rs:138-155 UPDATE：11 个可选字段的 COALESCE 形态；governance 从关到开的那一刻
     * 记 governance_since（SET 右边读的是旧值）。visibility 传参（建库路径的可选第二步）
     * 之外的参数由调用方按 Rust 形态给 null。RETURNING *（无行=NotFound）。
     */
    KbRow updateKb(@Param("id") UUID id,
                   @Param("name") String name,
                   @Param("description") String description,
                   @Param("visibility") String visibility,
                   @Param("autoExtendOntology") Boolean autoExtendOntology,
                   @Param("ontologyLang") String ontologyLang,
                   @Param("materializeInferences") Boolean materializeInferences,
                   @Param("inferenceIntervalMinutes") Integer inferenceIntervalMinutes,
                   @Param("autoTypeResolution") Boolean autoTypeResolution,
                   @Param("governance") Boolean governance,
                   @Param("dataConventions") String dataConventions);

    /** kbs.rs:209-212 DELETE（返回行数；0=NotFound；is_default 拦在服务层）。 */
    int deleteKb(@Param("id") UUID id);

    /**
     * kbs.rs:80-97 {@code set_data_description}：探索每跑一次重写描述与问题
     * （人写的 data_conventions 在另一列另一条路——两个写路径互不覆盖，#570）。
     */
    int setDataDescription(@Param("id") UUID id, @Param("description") String description,
                           @Param("questions") com.fasterxml.jackson.databind.JsonNode questions);
}
