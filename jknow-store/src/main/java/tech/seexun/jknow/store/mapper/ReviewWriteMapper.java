package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 审核队列写面的编排快照读（M4 任务 2，逐字搬迁 review_routes.rs 各写端点内联 SQL——
 * Rust 把它们写在路由文件里，Java 归 mapper；token 流一字不动，每条注明 file:line）。
 *
 * <p><b>为什么快照在动作之前取</b>：reject 之后事实从图里消失、merge 之后 source 一侧的
 * 名字已不在原处——决策台账必须自包含展示文本，先抄后动（review_routes.rs:26 头注）。
 * 查不到行（事实已死/审核行不存在）回 null，调用方跳过台账那一段——动作本身与台账
 * 各自独立，快照拿不到不拦业务（Rust 的 {@code .ok().flatten()} 语义）。
 */
public interface ReviewWriteMapper {

    /**
     * review_routes.rs:33-48 fact_snapshot：决策台账用的事实三元组快照。宾语可能是实体
     * 或字面值；结构化字面值优先取 summary（与队列卡片同一显示口径）。confidence 是
     * f32（serde 进 JSON 的 float）。
     */
    FactSnapshotRow selectFactSnapshot(@Param("factId") UUID factId, @Param("kbId") UUID kbId);

    /** fact_snapshot 的一行：subject/predicate/object（object 可 null）与置信度。 */
    record FactSnapshotRow(String subject, String predicate, String object, float confidence) {
    }

    /** review_routes.rs:320-331 decide 的快照：两侧名字与队列分。 */
    DecideSnapshotRow selectDecideSnapshot(@Param("reviewId") UUID reviewId,
                                           @Param("kbId") UUID kbId);

    /** decide 快照行。 */
    record DecideSnapshotRow(String left, String right, float score) {
    }

    /** review_routes.rs:531-542 batch 的快照：台账要的名字与分数在裁决前取（一批）。 */
    List<DecideSnapshotWithIdRow> selectBatchSnapshots(@Param("kbId") UUID kbId,
                                                       @Param("ids") List<UUID> ids);

    /** batch 快照行。 */
    record DecideSnapshotWithIdRow(UUID id, String left, String right, float score) {
    }

    /**
     * review_routes.rs:248-262 resolve_conflict 的快照：冲突双方三元组
     * (旧主语, 旧宾语, 新主语, 新宾语, 谓词标签)——裁决会作废/改写事实，先抄后动。
     */
    ConflictSnapshotRow selectConflictSnapshot(@Param("conflictId") UUID conflictId,
                                               @Param("kbId") UUID kbId);

    /** 冲突快照行（宾语侧 LEFT JOIN，可 null）。 */
    record ConflictSnapshotRow(String oldSubject, String oldObject, String newSubject,
                               String newObject, String predicate) {
    }

    /** review_routes.rs:644-654 revert_merge 的快照：合并双方名字。 */
    MergeSnapshotRow selectMergeSnapshot(@Param("mergeId") UUID mergeId,
                                         @Param("kbId") UUID kbId);

    /** 合并快照行。 */
    record MergeSnapshotRow(String source, String target) {
    }

    /**
     * review_routes.rs:693-702 manual_merge 的快照：{@code 'a → b'} 拼串（Rust 单列拼好
     * 再 split_once(" → ")，这里照抄单列形态、拆在 Java 侧）。FROM 双表无 kb 过滤——
     * 归属校验在 merge_entities 自己的读档里。
     */
    String selectManualMergePair(@Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId);

    /** review_routes.rs:193-201 close_fact 的归属与状态校验：本库、未作废、开放区间。 */
    UUID selectClosableFact(@Param("factId") UUID factId, @Param("kbId") UUID kbId);

    /** review_routes.rs:854-858 decide_violation 的开场：open 的违规行（kind/left/right/path）。 */
    ViolationRow selectOpenViolation(@Param("violationId") UUID violationId,
                                     @Param("kbId") UUID kbId);

    /** 违规行（path 是 uuid[]）。 */
    record ViolationRow(String kind, UUID leftFact, UUID rightFact, List<UUID> path) {
    }

    /**
     * review_routes.rs:911-916 decide_violation 的 fact_closed 支：旧断言是否还开放
     * （无 kb 过滤——left 是上一查捞出的本库事实）。null = 已有结束日期或已撤。
     */
    UUID selectOpenFactForClose(@Param("factId") UUID factId);

    /** review_routes.rs:449-456 agent_answer 的 applied+merge 支：审核行两侧实体。 */
    ReviewPairRow selectReviewPair(@Param("reviewId") UUID reviewId, @Param("kbId") UUID kbId);

    /** 审核行两侧。 */
    record ReviewPairRow(UUID leftId, UUID rightId) {
    }

    /** review_routes.rs:1178-1181 run_inference 的开关读取（无行 → 异常，调用方转 500）。 */
    Boolean selectMaterializeInferences(@Param("kbId") UUID kbId);
}
