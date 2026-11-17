package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.AgentDecisionView;
import tech.seexun.jknow.store.entity.AuditEventView;
import tech.seexun.jknow.store.entity.ConceptMapping;
import tech.seexun.jknow.store.entity.ConflictView;
import tech.seexun.jknow.store.entity.ErrataHeldItem;
import tech.seexun.jknow.store.entity.FactReviewItem;
import tech.seexun.jknow.store.entity.MergeLogView;
import tech.seexun.jknow.store.entity.OntologyDefect;
import tech.seexun.jknow.store.entity.PendingFactView;
import tech.seexun.jknow.store.entity.ReviewCounts;
import tech.seexun.jknow.store.entity.ReviewItem;

/**
 * review 读路径 mapper（M1 任务 4：counts + 12 队列 + history，逐字搬迁；每条 SQL 的
 * Rust 源 file:line 与 format! 片段展开规则见 ReviewMapper.xml 头注）。队列分发与
 * 组装（duplicates 两侧摘要/alignment 三合一内存分页/violations 线索）在 ReviewService。
 */
public interface ReviewMapper {

    // ---- counts（review.rs:35-84：17 列单查询，LOW_CONFIDENCE_BELOW=0.75） ----

    ReviewCounts selectCounts(@Param("kbId") UUID kbId,
                              @Param("below") float below);

    // ---- pending 队列（pending.rs:201-233 VIEW_SELECT；for_chunk 241-248） ----

    List<PendingFactView> selectPendingFacts(@Param("kbId") UUID kbId,
                                             @Param("limit") long limit, @Param("offset") long offset);

    List<PendingFactView> selectPendingForChunk(@Param("kbId") UUID kbId, @Param("chunkId") UUID chunkId);

    // ---- duplicates 队列（resolution.rs:1668-1691 list_reviews + assemble 1576-1628） ----

    /** 审核行（types=any；TypeFilter::Any.clause()="TRUE"）。 */
    List<ReviewRow> selectDuplicateReviewsAny(@Param("kbId") UUID kbId,
                                              @Param("limit") long limit, @Param("offset") long offset);

    /** 审核行（types=same：两边都有类型且相等，#428）。 */
    List<ReviewRow> selectDuplicateReviewsSame(@Param("kbId") UUID kbId,
                                               @Param("limit") long limit, @Param("offset") long offset);

    /** 审核行（types=conflict：两边都有类型且不等）。 */
    List<ReviewRow> selectDuplicateReviewsConflict(@Param("kbId") UUID kbId,
                                                   @Param("limit") long limit, @Param("offset") long offset);

    /** 两侧实体摘要（resolution.rs:1434-1458 review_sides；LEFT JOIN 无类型实体照进）。 */
    List<SideRow> selectReviewSides(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids);

    /** 每实体 top-N 事实摘要行（resolution.rs:1499-1547 entity_fact_lines_many 的 LATERAL）。 */
    List<FactLineRow> selectEntityFactLines(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                            @Param("limit") long limit);

    /** 本名以外的别名（names.rs:289-312 other_names_many；$2=known_as）。 */
    List<NameRow> selectOtherNames(@Param("ids") List<UUID> ids, @Param("knownAs") String knownAs);

    /** 一页上开着的 agent 建议（resolution.rs:1593-1596）。 */
    List<ProposalRow> selectOpenProposals(@Param("ids") List<UUID> ids);

    // ---- conflicts / unconfirmed / lowconf / mappings ----

    List<ConflictView> selectConflicts(@Param("kbId") UUID kbId,
                                       @Param("limit") long limit, @Param("offset") long offset);

    /** unconfirmed（graph.rs:2007-2038 stale_facts：证据全停留在旧版的现行事实）。 */
    List<FactReviewItem> selectUnconfirmedFacts(@Param("kbId") UUID kbId,
                                                @Param("limit") long limit, @Param("offset") long offset);

    /** lowconf（graph.rs:1969-1997：confidence 升序在前）。 */
    List<FactReviewItem> selectLowConfidenceFacts(@Param("kbId") UUID kbId, @Param("below") float below,
                                                  @Param("limit") long limit, @Param("offset") long offset);

    List<ConceptMapping> selectProposedMappings(@Param("kbId") UUID kbId,
                                                @Param("limit") long limit, @Param("offset") long offset);

    // ---- alignment 队列（alignment_queue.rs:116-225：三查询合一 + 签名，内存分页） ----

    List<PhraseRow> selectWaitingPhrases(@Param("kbId") UUID kbId);

    List<RuleRow> selectProposedRules(@Param("kbId") UUID kbId);

    List<KindWordRow> selectUndecidedKindWords(@Param("kbId") UUID kbId);

    /** 类别词签名（type_bindings.rs:57-110：写法/例名/短语各取前 3，三趟聚合）。 */
    List<SignatureRow> selectKindWordSignatures(@Param("kbId") UUID kbId);

    // ---- errata / violations / defects / merges / agent ----

    List<ErrataHeldItem> selectHeldErrata(@Param("kbId") UUID kbId,
                                          @Param("limit") long limit, @Param("offset") long offset);

    /** CTE triple + jsonb_agg path + left_open + same_name_peers（reasoning.rs:795-855）。 */
    List<ViolationRow> selectOpenViolations(@Param("kbId") UUID kbId,
                                            @Param("limit") long limit, @Param("offset") long offset);

    List<OntologyDefect> selectOpenDefects(@Param("kbId") UUID kbId,
                                           @Param("limit") long limit, @Param("offset") long offset);

    List<MergeLogView> selectMerges(@Param("kbId") UUID kbId,
                                    @Param("limit") long limit, @Param("offset") long offset);

    List<AgentDecisionView> selectAgentDecisions(@Param("kbId") UUID kbId,
                                                 @Param("limit") long limit, @Param("offset") long offset);

    // ---- history（audit.rs:94-119 review_history：review 域四族动作，服务端分页） ----

    List<AuditEventView> selectReviewHistory(@Param("kbId") UUID kbId,
                                             @Param("limit") long limit, @Param("offset") long offset);

    long countReviewHistory(@Param("kbId") UUID kbId);

    // ---- 行记录（组装输入；serde 形状由实体层承接） ----

    /** resolution.rs:1425-1432 ReviewRow。 */
    record ReviewRow(UUID id, UUID leftId, UUID rightId, float score, String reason,
                     String stage, OffsetDateTime createdAt) {
    }

    /** resolution.rs:1439-1447 SideRow（degree 排除 known_as，color 有 SQL 缺省）。 */
    record SideRow(UUID id, String name, String typeLabel, String color, String disambiguator,
                   long degree) {
    }

    /** resolution.rs:1508-1518 LATERAL 行（entity_id 来自 requested）。 */
    record FactLineRow(UUID entityId, String direction, String predicateLabel, String otherName,
                       OffsetDateTime validFrom, OffsetDateTime validTo, float confidence,
                       OffsetDateTime recordedAt) {
    }

    /** names.rs:296 二元组 (entity_id, v)。 */
    record NameRow(UUID entityId, String name) {
    }

    /** resolution.rs:1593 元组 (target_id, id, action, confidence, reason)。 */
    record ProposalRow(UUID targetId, UUID id, String action, float confidence, String reason) {
    }

    /** alignment_queue.rs:83-95 PhraseRow（kind 标签由 service 补）。 */
    record PhraseRow(UUID id, String phrase, String subjectClass, String objectClass,
                     boolean objectIsValue, int statementCount, List<String> examples,
                     JsonNode votes, OffsetDateTime decidedAt, String boundTo, String direction) {
    }

    /** alignment_queue.rs:65-80 RuleRow。 */
    record RuleRow(UUID id, String trigger, String phrase, String subjectClass, String objectClass,
                   boolean objectIsValue, String property, String propertyLabel, String reading,
                   int statementCount, List<String> examples, JsonNode votes,
                   OffsetDateTime decidedAt) {
    }

    /** alignment_queue.rs:107-113 KindWordRow。 */
    record KindWordRow(String kindWord, List<String> words, JsonNode votes, OffsetDateTime decidedAt) {
    }

    /** type_bindings.rs:42-54 KindWordSignature。 */
    record SignatureRow(String kindWord, List<String> words, long count,
                        List<String> examples, List<String> phrases) {
    }

    /** reasoning.rs:881-897 ViolationRow（hint/path 在 service 按 Rust 尾部 map 展开）。 */
    record ViolationRow(UUID id, String kind, String predicate, UUID leftFact, String leftText,
                        UUID rightFact, String rightText, int pathLen, OffsetDateTime detectedAt,
                        JsonNode detail, JsonNode path, boolean leftOpen, float leftConfidence,
                        boolean sameNamePeers) {
    }
}
