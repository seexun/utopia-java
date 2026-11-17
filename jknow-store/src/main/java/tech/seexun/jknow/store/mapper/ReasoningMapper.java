package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 公理检查的取数与落库（reasoning.rs 全族——SQL 逐字搬迁，每条注明 file:line）。
 * 判断本身在 utopia-reason（那一层不碰数据库）。
 */
public interface ReasoningMapper {

    /** reasoning.rs:75-81 axioms 的取数行（只取声明过至少一位的 relation_types）。 */
    record AxiomRow(UUID id, boolean transitive, boolean symmetric, boolean asymmetric,
                    boolean irreflexive, boolean functional, boolean inverseFunctional,
                    UUID inverseOf, UUID subPropertyOf) {
    }

    List<AxiomRow> selectAxioms(@Param("kbId") UUID kbId);

    /**
     * reasoning.rs:155-181 signature_breaks：主语不在 domain / 宾语不在 range 的活事实
     * （#190 / #196）。{@code only} 给了就只看这些事实（合并之后对搬动过的那几条立刻查）；
     * null 是全量。
     */
    List<UUID> selectSignatureBreaks(@Param("kbId") UUID kbId, @Param("only") List<UUID> only);

    /**
     * reasoning.rs:200-203 record_signature_breaks 的单条 upsert：同一条事实重复报不重复
     * 入库。返回新插的 id；DO NOTHING 落空时返回 null（Java 侧据 null 计 inserted）。
     */
    UUID insertSignatureBreak(@Param("id") UUID id, @Param("kbId") UUID kbId,
                              @Param("fact") UUID fact);

    /** reasoning.rs:1293-1300 timed_edges 的取数行（只有断言——派生分表天然隔离）。 */
    record TimedEdgeRow(UUID id, UUID predicateId, UUID subjectId, UUID objectId,
                        OffsetDateTime validFrom, OffsetDateTime validTo,
                        String validFromPrecision, String validToPrecision, float confidence,
                        OffsetDateTime attestedFrom, OffsetDateTime attestedTo) {
    }

    List<TimedEdgeRow> selectTimedEdgeRows(@Param("kbId") UUID kbId);

    /** reasoning.rs:1306-1308 谓词的时间语义（一次取全，按谓词查）。 */
    record TemporalRow(UUID id, String temporal) {
    }

    List<TemporalRow> selectTemporals(@Param("kbId") UUID kbId);

    /**
     * settle_violations 的行回读（reasoning.rs:426-436）：id / status / resolution /
     * xmax=0（只有真正新插的行才成立——DO UPDATE 会让 RETURNING 对更新也出一行，不这么
     * 分就会把「本来就在」数成「新插的」）。
     */
    record UpsertRow(UUID id, String status, String resolution, boolean fresh) {
    }

    /** reasoning.rs:412-417 环的 upsert（环按整条 path 定键，#641/0054）。 */
    UpsertRow upsertViolationCycle(@Param("id") UUID id, @Param("kbId") UUID kbId,
                                   @Param("kind") String kind, @Param("left") UUID left,
                                   @Param("right") UUID right, @Param("path") List<UUID> path,
                                   @Param("detail") JsonNode detail);

    /** reasoning.rs:419-424 非环的 upsert（按 (kind, left, right) 定键）。 */
    UpsertRow upsertViolationGrouped(@Param("id") UUID id, @Param("kbId") UUID kbId,
                                     @Param("kind") String kind, @Param("left") UUID left,
                                     @Param("right") UUID right,
                                     @Param("path") List<UUID> path,
                                     @Param("detail") JsonNode detail);

    /** reasoning.rs:448-451 破约重开：resolved 的行又算出来，回到 open（#202）。 */
    int reopenViolation(@Param("id") UUID id);

    /** reasoning.rs:466-469 环没搜完的谓词，上一轮的 open 环不清（#642）。 */
    List<UUID> selectKeptCycles(@Param("kbId") UUID kbId,
                                @Param("predicates") List<UUID> predicates);

    /** reasoning.rs:579-580 run 的清陈：这一轮没算出来的 open 行是陈的。 */
    int deleteStaleOpenViolations(@Param("kbId") UUID kbId, @Param("fresh") List<UUID> fresh);

    /** reasoning.rs:651-653 reconcile 的陈行快照：还没对账于新本体的 open 行。 */
    record StaleRow(UUID id, String kind, UUID leftFact, UUID rightFact, List<UUID> path) {
    }

    List<StaleRow> selectOpenViolationsNotIn(@Param("kbId") UUID kbId,
                                             @Param("fresh") List<UUID> fresh);

    /** reasoning.rs:669-672 旧基线里有的 → criterion_changed 收场（0062）。 */
    int markCriterionChanged(@Param("ids") List<UUID> ids);

    /** reasoning.rs:679-680 旧基线里没有的（早就陈了）→ 照旧删掉。 */
    int deleteViolations(@Param("ids") List<UUID> ids);

    /** reasoning.rs:705-707 accepted_groups 的行：人认可过并存的互斥组。 */
    record AcceptedRow(String kind, UUID leftFact, UUID rightFact, List<UUID> path) {
    }

    List<AcceptedRow> selectAcceptedGroups(@Param("kbId") UUID kbId);

    /** reasoning.rs:955-956 retract_from_violation 的违规行回读。 */
    StaleRow selectViolationForRetract(@Param("id") UUID violationId, @Param("kbId") UUID kbId);

    /** reasoning.rs:984-986 decide：改状态不删行，表过态这件事本身要留痕。 */
    int decideViolation(@Param("kbId") UUID kbId, @Param("violationId") UUID violationId,
                        @Param("resolution") String resolution, @Param("actor") UUID actor);

    // ===================== 本体自洽（reasoning.rs:1015-1090） =====================

    /** (a, b) 二元组行：subClassOf 边或互斥对。 */
    record Pair(UUID a, UUID b) {
    }

    /** reasoning.rs:1018-1021 subClassOf 边（child, parent）。 */
    List<Pair> selectParents(@Param("kbId") UUID kbId);

    /** reasoning.rs:1026-1027 互斥对（导入侧两个方向各一行）。 */
    List<Pair> selectDisjoint(@Param("kbId") UUID kbId);

    /** reasoning.rs:1030 类数（报告口径）。 */
    long countClasses(@Param("kbId") UUID kbId);

    /** reasoning.rs:1046-1048 已在库里的缺陷行（重算不掉 resolved——人的决定）。 */
    UUID selectDefectId(@Param("kbId") UUID kbId, @Param("kind") String kind,
                        @Param("subject") UUID subject, @Param("other") UUID other);

    /** reasoning.rs:1061-1063 新缺陷 INSERT。 */
    void insertDefect(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("kind") String kind,
                      @Param("subject") UUID subject, @Param("other") UUID other,
                      @Param("path") List<UUID> path);

    /** reasoning.rs:1079-1081 open 行可重算掉；rules_disagree 另一条清陈规矩。 */
    int deleteStaleDefects(@Param("kbId") UUID kbId, @Param("fresh") List<UUID> fresh);

    /** reasoning.rs:2742-2764 decide_defect：人对一处本体缺陷表态（fixed|accepted），open 行才动。 */
    int decideDefect(@Param("kbId") UUID kbId, @Param("defectId") UUID defectId,
                     @Param("resolution") String resolution, @Param("actor") UUID actor);

    // ===================== rules_disagree（reasoning.rs:483-553） =====================

    /** reasoning.rs:529-532 互撞规则对的 upsert（唯一键只到谓词对，detail 刷新）。 */
    UUID upsertRulesDisagree(@Param("id") UUID id, @Param("kbId") UUID kbId,
                             @Param("subject") UUID subject, @Param("other") UUID other,
                             @Param("detail") JsonNode detail);

    /** reasoning.rs:544-546 open 的陈行删除。 */
    int deleteStaleRulesDisagree(@Param("kbId") UUID kbId, @Param("fresh") List<UUID> fresh);

    /** names_for 子集（reasoning.rs:780-782）：规则对 detail 里的谓词标签。 */
    List<LabelRow> selectPredicateLabels(@Param("ids") List<UUID> ids);

    /** names_for 子集（reasoning.rs:774-776）：detail 里的实体名。 */
    List<LabelRow> selectEntityNames(@Param("ids") List<UUID> ids);

    /** (id, label/name) 行。 */
    record LabelRow(UUID id, String label) {
    }

    // ===================== 推理物化（M3 任务 6：reasoning.rs:1096-2407） =====================

    /** reasoning.rs:1486-1488 compile_rules 的规则 INSERT（幂等：ON CONFLICT DO NOTHING）。 */
    int insertRule(@Param("id") UUID id, @Param("kbId") UUID kbId,
                   @Param("predicateId") UUID predicateId, @Param("kind") String kind);

    /** reasoning.rs:1495-1497 编译后按 (kb, 谓词, 种类) 取回规则 id（历史不被新 id 断）。 */
    UUID selectRuleId(@Param("kbId") UUID kbId, @Param("predicateId") UUID predicateId,
                      @Param("kind") String kind);

    /** reasoning.rs:1584-1594 attribute_rules 的规则行（enabled，按 r.id 排序保证明稳定）。 */
    record RuleDefRow(UUID id, UUID subjectTypeId, String conclusion, UUID concludeTypeId,
                      UUID concludePredicateId, JsonNode concludeValue, JsonNode concludeExpr,
                      String iri, String key, UUID joinPredicateId, UUID versionId) {
    }

    List<RuleDefRow> selectAttributeRules(@Param("kbId") UUID kbId);

    /** reasoning.rs:1609 类名（IRI 优先，没有才 key）↔ 类 id 两个方向各一份。 */
    record TypeRow(UUID id, String iri, String key) {
    }

    List<TypeRow> selectEntityTypes(@Param("kbId") UUID kbId);

    /** reasoning.rs:1623-1626 归类结论要落在内建 is_a 上；取不到就说明库被手改过——跳过。 */
    UUID selectIsAPredicate(@Param("kbId") UUID kbId);

    /** reasoning.rs:1631-1636 条件（组序在前：两组推出同一区间时证明稳定）。 */
    record RuleConditionRow(UUID ruleId, int groupSeq, UUID predicateId, String op,
                            JsonNode operand, String subjectSide) {
    }

    List<RuleConditionRow> selectRuleConditions(@Param("ids") List<UUID> ids);

    /** reasoning.rs:1795-1802 一个类连同它的全部子类（规则写在父类上，子类实体也该被看）。 */
    List<UUID> selectDescendantTypes(@Param("kbId") UUID kbId, @Param("root") UUID root);

    /** reasoning.rs:1840-1851 属性事实（字面值通道，两边都只看断言）。 */
    record AttrFactRow(UUID id, UUID subjectId, UUID predicateId, JsonNode objectValue,
                       OffsetDateTime validFrom, OffsetDateTime validTo,
                       String validFromPrecision, String validToPrecision, float confidence,
                       UUID typeId, OffsetDateTime attestedFrom, OffsetDateTime attestedTo) {
    }

    List<AttrFactRow> selectAttributeFacts(@Param("kbId") UUID kbId);

    /** reasoning.rs:1354-1357 人认可过并存的（派生三元组, 断言）对（0017 §2）。 */
    record AcceptedClashRow(UUID leftFact, JsonNode detail) {
    }

    List<AcceptedClashRow> selectAcceptedClashes(@Param("kbId") UUID kbId);

    /** reasoning.rs:2492-2493 展不完的组合数按规则写回（表里常驻，不只在提示里闪一下）。 */
    int updateRuleCapped(@Param("ruleId") UUID ruleId, @Param("capped") int capped);

    /** reasoning.rs:2501-2505 落库对账的 live 行查询。 */
    record LiveDerivedRow(UUID id, UUID subjectId, UUID predicateId, UUID objectId,
                          JsonNode objectValue, OffsetDateTime validFrom, OffsetDateTime validTo) {
    }

    List<LiveDerivedRow> selectLiveDerived(@Param("kbId") UUID kbId);

    /** reasoning.rs:2538 不再成立的派生置 invalidated_at（作废不是删，0002 第 3 节）。 */
    int invalidateDerived(@Param("ids") List<UUID> ids);

    /** reasoning.rs:2570-2593 新派生行 INSERT（14 列）。 */
    int insertDerived(@Param("id") UUID id, @Param("kbId") UUID kbId,
                      @Param("subjectId") UUID subjectId, @Param("predicateId") UUID predicateId,
                      @Param("objectId") UUID objectId,
                      @Param("objectValue") JsonNode objectValue,
                      @Param("validFrom") OffsetDateTime validFrom,
                      @Param("validTo") OffsetDateTime validTo,
                      @Param("validFromPrecision") String validFromPrecision,
                      @Param("validToPrecision") String validToPrecision,
                      @Param("confidence") float confidence, @Param("ruleId") UUID ruleId,
                      @Param("attributeRuleId") UUID attributeRuleId,
                      @Param("attributeRuleVersionId") UUID attributeRuleVersionId);

    /** reasoning.rs:2603-2606 结论没变、定义换了版本：行留着，版本换过来（0060）。 */
    int updateDerivedVersion(@Param("id") UUID id, @Param("versionId") UUID versionId);

    /** reasoning.rs:2621-2624 kept 行存着的证明（一次查完再逐条比，只有真不一样的才重写）。 */
    record StoredPremiseRow(UUID derivedFactId, UUID premiseFactId, UUID premiseDerivedId) {
    }

    List<StoredPremiseRow> selectStoredPremises(@Param("ids") List<UUID> ids);

    /** reasoning.rs:2651 结论没变理由变了：删重写 fact_derivations。 */
    int deleteDerivations(@Param("derivedFactId") UUID derivedFactId);

    /** reasoning.rs:2656-2659 / 2679-2683 前提两列二选一，seq 是跨两种的一个序。 */
    int insertDerivation(@Param("derivedFactId") UUID derivedFactId,
                         @Param("premiseFactId") UUID premiseFactId,
                         @Param("premiseDerivedId") UUID premiseDerivedId, @Param("seq") int seq);

    /** reasoning.rs:2830-2833 一条派生的直接前提（按 seq）。 */
    record PremiseRow(int seq, UUID premiseFactId, UUID premiseDerivedId) {
    }

    List<PremiseRow> selectPremiseRows(@Param("derivedFactId") UUID derivedFactId);

    /** reasoning.rs:2963-2973 前提展开成证明的步（unnest 保序；一条链最多 MAX_DEPTH 步）。 */
    record ProofStepRow(long seq, UUID factId, UUID subjectId, String subject, UUID predicateId,
                        String predicate, UUID objectId, String object,
                        OffsetDateTime validFrom, OffsetDateTime validTo, float confidence,
                        boolean retracted) {
    }

    List<ProofStepRow> selectStepsFor(@Param("premises") List<UUID> premises);

    /** reasoning.rs:2888-2902 一条派生前提读成证明的一步（三元组跟断言同一副样子）。 */
    record DerivedStepRow(UUID factId, UUID subjectId, String subject, UUID predicateId,
                          String predicate, UUID objectId, String object,
                          OffsetDateTime validFrom, OffsetDateTime validTo, float confidence,
                          boolean retracted) {
    }

    DerivedStepRow selectDerivedStep(@Param("id") UUID id);

    /** reasoning.rs:3071-3074 没落地的派生的证明链：前提在那条违规的 path 里（unnest 展开）。 */
    List<UUID> selectViolationPath(@Param("id") UUID violationId, @Param("kbId") UUID kbId);

    /** 同上一条的「违规存在吗」——无行返回 null（区分「空 path」与「找不到」）。 */
    Integer selectViolationExists(@Param("id") UUID violationId, @Param("kbId") UUID kbId);

    /** reasoning.rs:3034-3057 blocked_for_entity（violation_open_at 谓词按 as_of 拼接）。 */
    List<java.util.Map<String, Object>> selectBlockedForEntity(@Param("kbId") UUID kbId,
                                                               @Param("entityId") UUID entityId,
                                                               @Param("asOf") OffsetDateTime asOf);

    /** reasoning.rs:3091-3129 derived_one：按 id 取一条派生（失效的也取：证明要能回看）。 */
    DerivedFactView selectDerivedOne(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** reasoning.rs:3157-3199 derived_for_entity（record/world 两轴谓词按 at/as_of 拼接）。 */
    List<DerivedFactView> selectDerivedForEntity(@Param("kbId") UUID kbId,
                                                 @Param("entityId") UUID entityId,
                                                 @Param("at") OffsetDateTime at,
                                                 @Param("asOf") OffsetDateTime asOf);

    /**
     * 一条派生事实，配好展示与证明所需的文本（DerivedFactView，models.rs serde 形状）。
     * premises 数组按 seq。
     */
    record DerivedFactView(UUID id, UUID predicateId, JsonNode objectValue, UUID ruleId,
                           UUID attributeRuleId, OffsetDateTime invalidatedAt,
                           String validFromPrecision, String validToPrecision, UUID subjectId,
                           String subject, UUID objectId, String object, String predicate,
                           String rule, String ruleName, Integer ruleVersion,
                           JsonNode ruleDefinition, OffsetDateTime validFrom,
                           OffsetDateTime validTo, float confidence, OffsetDateTime derivedAt,
                           List<String> premises) {
    }

    /** reasoning.rs:2770-2779 到点该重推的库（从没推过的算到期）。 */
    List<UUID> selectDueForInference();

    /** reasoning.rs:2787 推完就记，哪怕什么都没变——没变化的库不该每分钟被重算。 */
    int markInferenceRan(@Param("kbId") UUID kbId);

    // ===================== 业务规则（business_rules.rs） =====================

    /** business_rules.rs:170-176 建规则的 INSERT（唯一名冲突 → duplicate_rule）。 */
    int insertAttributeRule(@Param("id") UUID id, @Param("kbId") UUID kbId,
                            @Param("name") String name, @Param("description") String description,
                            @Param("subjectTypeId") UUID subjectTypeId,
                            @Param("conclusion") String conclusion,
                            @Param("concludeTypeId") UUID concludeTypeId,
                            @Param("concludePredicateId") UUID concludePredicateId,
                            @Param("concludeValue") JsonNode concludeValue,
                            @Param("concludeExpr") JsonNode concludeExpr,
                            @Param("joinPredicateId") UUID joinPredicateId);

    /** business_rules.rs:786-794 条件 INSERT（seq 在组内编号，两组各自从 0 起）。 */
    int insertRuleCondition(@Param("id") UUID id, @Param("ruleId") UUID ruleId,
                            @Param("groupSeq") int groupSeq, @Param("seq") int seq,
                            @Param("predicateId") UUID predicateId, @Param("op") String op,
                            @Param("operand") JsonNode operand,
                            @Param("subjectSide") String subjectSide);

    /** business_rules.rs:338-350 定义快照（DEFINITION_SQL 与迁移 0076 回填一字不差）。 */
    JsonNode selectRuleDefinition(@Param("ruleId") UUID ruleId);

    /** business_rules.rs:366-371 当前版本（superseded_at IS NULL）。 */
    record VersionRow(UUID id, int seq, JsonNode definition) {
    }

    VersionRow selectCurrentVersion(@Param("ruleId") UUID ruleId);

    /** business_rules.rs:376-377 / 385-389 关掉当前版本 / 开新版本。 */
    int supersedeVersion(@Param("id") UUID id);

    int insertVersion(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId,
                      @Param("seq") int seq, @Param("definition") JsonNode definition);

    /** business_rules.rs:270-281 改规则（结论给了就三格一起换，COALESCE 做不了显式清空）。 */
    int updateAttributeRule(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId,
                            @Param("name") String name, @Param("description") String description,
                            @Param("enabled") Boolean enabled,
                            @Param("conclusion") String conclusion,
                            @Param("concludeTypeId") UUID concludeTypeId,
                            @Param("concludePredicateId") UUID concludePredicateId,
                            @Param("concludeValue") JsonNode concludeValue,
                            @Param("concludeExpr") JsonNode concludeExpr,
                            @Param("joinPredicateId") UUID joinPredicateId);

    /** business_rules.rs:309-315 事务内终检：没有连接的规则读了 y 侧（#1109）。 */
    boolean selectReadsYWithoutJoin(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId);

    /** business_rules.rs:494 删规则（派生行随 CASCADE 走——规则没了结论没有依据）。 */
    int deleteAttributeRule(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId);

    /** business_rules.rs:507-527 规则列表行。 */
    record RuleListRow(UUID id, String name, String description, UUID subjectTypeId,
                       String subjectLabel, String conclusion, UUID concludeTypeId,
                       String concludeTypeLabel, UUID concludePredicateId,
                       String concludePredicateLabel, JsonNode concludeValue,
                       JsonNode concludeExpr, boolean enabled, UUID joinPredicateId,
                       String joinPredicateLabel, Integer version, long derivedCount,
                       int capped) {
    }

    List<RuleListRow> selectRuleList(@Param("kbId") UUID kbId);

    /** business_rules.rs:535-542 列表的条件行。 */
    record RuleListConditionRow(UUID ruleId, int groupSeq, UUID predicateId, String predicateLabel,
                                String op, JsonNode operand, String subjectSide) {
    }

    List<RuleListConditionRow> selectRuleListConditions(@Param("ids") List<UUID> ids);

    /** business_rules.rs:646-648 matches 的总数。 */
    long countMatches(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId);

    /** business_rules.rs:661-686 matches 的行（结论读人看的名字，前提可读形态）。 */
    record MatchRow(UUID derivedId, UUID entityId, String entity, UUID objectId,
                    String objectEntity, String concluded, String relationPredicate,
                    OffsetDateTime validFrom, OffsetDateTime validTo, String validFromPrecision,
                    String validToPrecision, List<String> premises) {
    }

    List<MatchRow> selectMatches(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId,
                                 @Param("limit") long limit, @Param("offset") long offset);

    // ---- versions（business_rules.rs:400-501；M4 任务 6 规则读族） ----

    /** business_rules.rs:417-427 版本行（definition 是当时的整棵定义 JSON）。 */
    record RuleVersionRow(UUID id, int seq, JsonNode definition, OffsetDateTime recordedAt,
                          OffsetDateTime supersededAt, long derivedCount) {
    }

    /** business_rules.rs:417-427 一条规则的定义史（新的在前）。 */
    List<RuleVersionRow> selectRuleVersions(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId);

    /** business_rules.rs:433-436 规则存在性（不在本库=404，与不存在同答）。 */
    UUID selectRuleIdInKb(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId);

    /**
     * business_rules.rs:462-463 标签回查（历史里的 id 可能已删，标签按现在查得到的给）。
     * 表名由调用方枚举传入（entity_types / relation_types 两值——selectExistsIn 同法）。
     */
    List<IdLabel> selectIdLabels(@Param("table") String table, @Param("ids") List<UUID> ids);

    /** selectIdLabels 的行。 */
    record IdLabel(UUID id, String label) {
    }

    /** business_rules.rs:832-835 条件/结论谓词的 kind 检查。 */
    String selectPredicateKind(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** business_rules.rs:974-977 exists 检查（entity_types / 其它表；无行返回 null）。 */
    Integer selectExistsIn(@Param("table") String table, @Param("kbId") UUID kbId,
                           @Param("id") UUID id);

    /** business_rules.rs:240-249 update 的旧行回读（不看 enabled——关掉的规则也能改）。 */
    RuleDefRow selectRuleDef(@Param("kbId") UUID kbId, @Param("ruleId") UUID ruleId);

    /** business_rules.rs:300-302 update 的条件整组替换（先删后插）。 */
    int deleteRuleConditions(@Param("ruleId") UUID ruleId);

    /** business_rules.rs:20-27 ensure_is_a 第一步：按 key 取已有谓词。 */
    UUID selectPredicateIdByKey(@Param("kbId") UUID kbId, @Param("key") String key);

    /** business_rules.rs:29-34 ensure_is_a：内建 is_a 不存在时建出来（ON CONFLICT 并发兜底）。 */
    int insertIsAPredicate(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("key") String key,
                           @Param("description") String description);
}
