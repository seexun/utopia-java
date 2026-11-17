package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.AgentDecisionView;
import tech.seexun.jknow.store.entity.Namesake;
import tech.seexun.jknow.store.entity.Precedent;

/**
 * 治理 store 全量 SQL（M4 任务 1，逐字搬迁旧仓库 crates/utopia-store/src/governance.rs；
 * 每条 SQL 的 Rust 源 file:line 见 GovernanceMapper.xml 头注与各语句注释）。流程控制与
 * 纯函数族在 {@link tech.seexun.jknow.store.GovernanceService}。
 */
public interface GovernanceMapper {

    // ---- 先例三族（governance.rs:77-145 precedents_for 的四条查询） ----

    /** governance.rs:89-100 same_pair（COLS 模板 + is_pair 双向对 + LIMIT）。 */
    List<Precedent> selectSamePair(@Param("kbId") UUID kbId, @Param("a") String a,
                                   @Param("b") String b, @Param("limit") long limit);

    /** governance.rs:102-114 same_name（外加 NOT is_pair 且 L/R IN）。 */
    List<Precedent> selectSameName(@Param("kbId") UUID kbId, @Param("a") String a,
                                   @Param("b") String b, @Param("limit") long limit);

    /** governance.rs:116-126 reverts（action='merge.revert' AND touches，不限 actor）。 */
    List<Precedent> selectRevertPrecedents(@Param("kbId") UUID kbId, @Param("a") String a,
                                           @Param("b") String b, @Param("limit") long limit);

    /** governance.rs:128-132 两侧实体的 type_id（先查两侧都有才走类型对统计）。 */
    List<TypeIdRow> selectEntityTypes(@Param("ids") List<UUID> ids);

    /** governance.rs:154-167 type_pair_stats 的 merged/kept 两列（resolution_reviews JOIN entities 双序 FILTER）。 */
    TypePairCountsRow selectTypePairCounts(@Param("kbId") UUID kbId, @Param("ta") UUID ta,
                                           @Param("tb") UUID tb);

    /** governance.rs:168-179 type_pair_stats 的 reverted 计数（entity_merges.reverted_at）。 */
    long selectTypePairReverted(@Param("kbId") UUID kbId, @Param("ta") UUID ta, @Param("tb") UUID tb);

    /** governance.rs:581-591 decided_before：同一对实体人裁过没有（status='merged'，无行=null）。 */
    Boolean selectDecidedBefore(@Param("kbId") UUID kbId, @Param("a") UUID a, @Param("b") UUID b);

    // ---- 队列与簇（governance.rs:664-706） ----

    /** governance.rs:665-670 queue 的审核行（OPEN_PROPOSAL 片段在 XML <sql> 里）。 */
    List<ReviewMapper.ReviewRow> selectQueueRows(@Param("kbId") UUID kbId,
                                                 @Param("limit") long limit);

    /** governance.rs:685-693 cluster_of 的审核行（同实体/同名，FIFO）。 */
    List<ReviewMapper.ReviewRow> selectClusterRows(@Param("kbId") UUID kbId,
                                                   @Param("headId") UUID headId,
                                                   @Param("entityIds") List<UUID> entityIds,
                                                   @Param("names") List<String> names,
                                                   @Param("limit") long limit);

    // ---- agent_decisions 写读（governance.rs:727-1121） ----

    /** governance.rs:729-748 record 的 14 列 INSERT（target_kind 固定 'review'）。 */
    int insertDecision(@Param("id") UUID id, @Param("kbId") UUID kbId,
                       @Param("runId") UUID runId, @Param("targetId") UUID targetId,
                       @Param("action") String action, @Param("confidence") float confidence,
                       @Param("reason") String reason,
                       @Param("precedents") JsonNode precedents, @Param("status") String status,
                       @Param("mergeId") UUID mergeId, @Param("question") String question,
                       @Param("trace") JsonNode trace, @Param("calls") int calls);

    /** governance.rs:755-758 loop_calls_today（今天这个库在循环里花了几次模型调用）。 */
    long selectLoopCallsToday(@Param("kbId") UUID kbId);

    /** governance.rs:881-888 list：Agent 队列，最新的在前。 */
    List<AgentDecisionView> selectDecisionList(@Param("kbId") UUID kbId,
                                               @Param("limit") long limit,
                                               @Param("offset") long offset);

    /** governance.rs:892-898 get（无行=null，服务层转 NotFound）。 */
    AgentDecisionView selectDecision(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** governance.rs:903-909 agent_running：这个库的 govern 任务此刻在跑（kb 以字符串比对 payload）。 */
    Boolean selectAgentRunning(@Param("kbId") String kbId);

    /** governance.rs:915-918 queue_len：等人的、还没有开着的建议的对。 */
    long selectQueueLen(@Param("kbId") UUID kbId);

    /** governance.rs:928-930 lock：agent 正在裁的一簇标成 adjudicating。 */
    int lockReviews(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids);

    /** governance.rs:941-943 release_locks：放开所有 adjudicating 的等待行。 */
    long releaseLocks(@Param("kbId") UUID kbId);

    /** governance.rs:955-962 locked_by_agent（逐字；三条件缺一不算锁）。 */
    Boolean selectLockedByAgent(@Param("kbId") UUID kbId, @Param("reviewId") UUID reviewId);

    /** governance.rs:972-974 count_proposed。 */
    long selectCountProposed(@Param("kbId") UUID kbId);

    /** governance.rs:990-992 settle（0 行=已经答过，服务层转 Conflict）。 */
    int settleDecision(@Param("kbId") UUID kbId, @Param("id") UUID id,
                       @Param("status") String status, @Param("userId") UUID userId);

    /** governance.rs:1018-1022 answer_open（同 action=accepted、异=overridden）。 */
    int answerOpen(@Param("kbId") UUID kbId, @Param("reviewId") UUID reviewId,
                   @Param("action") String action, @Param("userId") UUID userId);

    /** governance.rs:1036-1049 supersede_siblings（跨四表 UPDATE…FROM）。 */
    long supersedeSiblings(@Param("kbId") UUID kbId, @Param("reviewId") UUID reviewId);

    /** governance.rs:1065-1067 reverts_since（从某一刻起撤回几次）。 */
    long selectRevertsSince(@Param("kbId") UUID kbId, @Param("since") OffsetDateTime since);

    /** governance.rs:1078-1080 trip：跳闸（行数=是否这次真的关了）。 */
    int tripGovernance(@Param("kbId") UUID kbId);

    /** governance.rs:1097-1100 settle_by_merge（按 merge_id 找 applied 行记 reverted，RETURNING id）。 */
    UUID selectSettleByMerge(@Param("kbId") UUID kbId, @Param("mergeId") UUID mergeId,
                             @Param("userId") UUID userId);

    /** governance.rs:1112-1116 due：开关开着、还有 agent 没看过的对的库。 */
    List<UUID> selectDueKbs();

    // ---- 第二刀三工具（governance.rs:772-861） ----

    /** governance.rs:778-794 quotes_of（DISTINCT ON chunk + known_as 名字引文排后剔除）。 */
    List<QuoteRow> selectQuotesOf(@Param("kbId") UUID kbId, @Param("entityId") UUID entityId,
                                  @Param("limit") long limit);

    /** governance.rs:812-817 ledger_search（audit_events LIKE 检索，只认人的）。 */
    List<Precedent> selectLedgerSearch(@Param("kbId") UUID kbId, @Param("like") String like,
                                       @Param("limit") long limit);

    /** governance.rs:843-853 namesakes（名字包含 query 的其他实体）。 */
    List<Namesake> selectNamesakes(@Param("kbId") UUID kbId, @Param("like") String like,
                                   @Param("limit") long limit);

    // ---- 行记录（组装输入） ----

    /** governance.rs:129（id, type_id）两列。 */
    record TypeIdRow(UUID id, UUID typeId) {
    }

    /** governance.rs:154-167（count FILTER merged, count FILTER kept）两列。 */
    record TypePairCountsRow(long merged, long kept) {
    }

    /** governance.rs:778 filename/quote 两列（Rust Vec&lt;(String, String)&gt;）。 */
    record QuoteRow(String filename, String quote) {
    }
}
