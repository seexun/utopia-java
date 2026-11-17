package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;
import tech.seexun.jknow.store.temporal.TRow;

/**
 * 时态引擎 SQL（temporal.rs 逐字搬迁；每条 XML 内注明 file:line）。锁序铁律：
 * 先锁时间线再改行（谓词级共享→时间线级排他，两级各自排序）。
 */
public interface TemporalMapper {

    // ---- 咨询锁（temporal.rs:256-285） ----

    /** temporal.rs:265-266 谓词级共享锁（&lt;=BULK_TIMELINES 的路径）。 */
    void lockPredicateShared(@Param("key") String key);

    /** temporal.rs:264/281 排他锁（谓词级批量路径，与时间线键共用一条）。 */
    void lockExclusive(@Param("key") String key);

    // ---- load_timeline（temporal.rs:289-315） ----

    /** temporal.rs:298-315 主语侧持有者（FOR UPDATE OF f 锁行，DESCRIBED 表达式逐字）。 */
    List<TRow> selectTimelineSubject(@Param("kbId") UUID kbId, @Param("holder") UUID holder,
                                     @Param("predicateId") UUID predicateId);

    /** temporal.rs:298-315 宾语侧持有者（holder 列换成 f.object_id，其余逐字）。 */
    List<TRow> selectTimelineObject(@Param("kbId") UUID kbId, @Param("holder") UUID holder,
                                    @Param("predicateId") UUID predicateId);

    // ---- timelines_of（temporal.rs:321-383） ----

    /** temporal.rs:342-350 放置查询：只算 state 且声明了唯一性的谓词。 */
    List<PlacedFact> selectTimelinesOf(@Param("kbId") UUID kbId,
                                       @Param("factIds") List<UUID> factIds);

    /** temporal.rs:331-338 Placed。 */
    record PlacedFact(UUID subjectId, UUID objectId, UUID predicateId,
                      boolean functional, boolean inverseFunctional) {
    }

    // ---- reconcile_new_fact（temporal.rs:414-420） ----

    /** temporal.rs:414-415 谓词是否两侧都唯一（查无此谓词 → null，服务层按 false）。 */
    Boolean selectBothSides(@Param("predicateId") UUID predicateId);

    // ---- reconcile_predicate（temporal.rs:817-848） ----

    /** temporal.rs:817-823 声明（functional/inverse_functional/temporal；查无 → null）。 */
    Declared selectDeclared(@Param("kbId") UUID kbId, @Param("predicateId") UUID predicateId);

    record Declared(boolean functional, boolean inverseFunctional, String temporal) {
    }

    /** temporal.rs:840-846 这条谓词上要对账的活事实。 */
    List<UUID> selectPredicateFactIds(@Param("kbId") UUID kbId,
                                      @Param("predicateId") UUID predicateId);

    // ---- retract（temporal.rs:858-861） ----

    /** temporal.rs:859-860 作废（已作废不算——返回受影响行数）。 */
    int retractFact(@Param("factId") UUID factId, @Param("kbId") UUID kbId);

    // ---- state_derived_end（temporal.rs:929-931） ----

    /** temporal.rs:930 锁行读 end_derived（行没了/已作废 → null）。 */
    Boolean selectEndDerivedForUpdate(@Param("factId") UUID factId);

    // ---- rewrite_end_tx（temporal.rs:949-1021） ----

    /** temporal.rs:956-961 FOR UPDATE 锁旧行（requireOpen=只改还开着的；不在/不活 → null）。 */
    RewriteAlive selectForRewrite(@Param("factId") UUID factId,
                                  @Param("requireOpen") boolean requireOpen);

    record RewriteAlive(OffsetDateTime validFrom, UUID predicateId) {
    }

    /**
     * temporal.rs:987-1003 修正行 INSERT（逐字；CASE WHEN $4::text='unknown' 的锚点、
     * corrected_ends 并集保留起点那一半）。
     */
    int insertRewriteEnd(@Param("corrected") UUID corrected, @Param("factId") UUID factId,
                         @Param("validTo") OffsetDateTime validTo,
                         @Param("precision") String precision,
                         @Param("anchor") OffsetDateTime anchor, @Param("derived") boolean derived);

    /** temporal.rs:1110-1113 carry_open_conflicts 第一条（old 侧换行）。 */
    int carryConflictsOld(@Param("from") UUID from, @Param("to") UUID to);

    /** temporal.rs:1120-1123 carry_open_conflicts 第二条（new 侧换行）。 */
    int carryConflictsNew(@Param("from") UUID from, @Param("to") UUID to);

    // ---- rehome_tx（temporal.rs:1056-1100，撤回合并的活改写行换持有者） ----

    /** temporal.rs:1063-1065 FOR UPDATE 锁活行，SELECT id（Rust Option&lt;(Uuid,)&gt;；不在/不活 → null）。 */
    UUID selectAliveForUpdate(@Param("factId") UUID factId);

    /** temporal.rs:1068-1080 新持有者上的修正行 INSERT（逐字；COALESCE 只换指定一侧）。 */
    int insertRehome(@Param("moved") UUID moved, @Param("factId") UUID factId,
                     @Param("subjectId") UUID subjectId, @Param("objectId") UUID objectId);

    /** temporal.rs:1013 旧行作废（认知轴事件，不删）。 */
    void invalidateFact(@Param("factId") UUID factId);

    /** temporal.rs:1159-1164 证据引用随修正行复制。 */
    void copyEvidence(@Param("to") UUID to, @Param("from") UUID from);

    /** temporal.rs:1140-1143 边上的属性随修正行复制（0037）。 */
    void copyQualifiers(@Param("to") UUID to, @Param("from") UUID from);

    /** temporal.rs:1033-1035 物化来源（typed）随修正行复制（#899）。 */
    void copyTypedSources(@Param("to") UUID to, @Param("from") UUID from);

    /** temporal.rs:1042-1044 物化来源（implied）随修正行复制（#899）。 */
    void copyImpliedSources(@Param("to") UUID to, @Param("from") UUID from);

    // ---- predicate_temporal（graph.rs:437-441，rewrite/改区间共用的判断） ----

    /** graph.rs:438 谓词的时间语义（查无 → null，服务层按 State）。 */
    String selectPredicateTemporal(@Param("predicateId") UUID predicateId);

    // ---- correct_interval（temporal.rs:1194-1267） ----

    /** temporal.rs:1201-1202 行的谓词（行没了 → null，INSERT 随后自然 0 行）。 */
    UUID selectFactPredicateId(@Param("factId") UUID factId);

    /**
     * temporal.rs:1215-1243 人工改区间的 INSERT…RETURNING（逐字；start_marked/end_marked
     * 的 LATERAL 判别 + corrected_ends 并集）。0 行 = 已被并发改写/作废。
     */
    UUID insertCorrectInterval(@Param("corrected") UUID corrected, @Param("factId") UUID factId,
                               @Param("from") OffsetDateTime from,
                               @Param("fromPrecision") String fromPrecision,
                               @Param("to") OffsetDateTime to,
                               @Param("toPrecision") String toPrecision);

    // ---- record_conflict_tx（temporal.rs:1271-1310） ----

    /**
     * temporal.rs:1281-1301 记一对冲突（WHERE NOT EXISTS 前身豁免 + 反向对去重 +
     * ON CONFLICT DO NOTHING）。返回受影响行数——1 = 第一次记。
     */
    int insertRecordConflict(@Param("id") UUID id, @Param("kbId") UUID kbId,
                             @Param("oldFactId") UUID oldFactId,
                             @Param("newFactId") UUID newFactId, @Param("reason") String reason);

    // ---- resolve_conflict（temporal.rs:1357-1429） ----

    /** temporal.rs:1365-1369 待裁的一行（旧/新事实、新事实起点及精度）。 */
    ConflictRow selectConflictRow(@Param("conflictId") UUID conflictId, @Param("kbId") UUID kbId);

    record ConflictRow(UUID oldFactId, UUID newFactId, OffsetDateTime newValidFrom,
                       String newValidFromPrecision) {
    }

    /** temporal.rs:1402-1405 reject_new 的波及：同新事实的其他 open 冲突一并出队。 */
    void resolveOtherOpenConflicts(@Param("newFactId") UUID newFactId,
                                   @Param("conflictId") UUID conflictId);

    /** temporal.rs:1419-1422 冲突落判。 */
    void markConflictResolved(@Param("conflictId") UUID conflictId, @Param("kbId") UUID kbId,
                              @Param("resolution") String resolution);

    // ---- uniqueness_candidates（temporal.rs:1476-1554 双 SQL） ----

    /** temporal.rs:1498-1523 主语侧（functional）候选取数。 */
    List<CandidateRow> selectUniquenessSubjectSide(@Param("kbId") UUID kbId);

    /** temporal.rs:1528-1550 宾语侧（inverse functional）候选取数。 */
    List<CandidateRow> selectUniquenessObjectSide(@Param("kbId") UUID kbId);

    /** temporal.rs:1480-1494 uniqueness_candidates 的取数行。 */
    record CandidateRow(UUID id, UUID predicateId, String key, String label, String kind,
                        boolean declared, String holderName, String otherName,
                        JsonNode objectValue, OffsetDateTime validFrom,
                        String validFromGrade, float confidence) {
    }

    // ---- merge_would_overlap（temporal.rs:616-712） ----

    /** temporal.rs:630-642 两实体共有的、可能有接替关系的谓词。 */
    List<SharedPredicate> selectSharedPredicates(@Param("kbId") UUID kbId, @Param("a") UUID a,
                                                 @Param("b") UUID b);

    record SharedPredicate(UUID predicateId, String label, boolean functional,
                           boolean inverseFunctional, String temporal) {
    }

    /** temporal.rs:664-671 主语侧两边的行（= ANY($2)，无 FOR UPDATE——闸门只读）。 */
    List<TRow> selectMergeRowsSubject(@Param("kbId") UUID kbId, @Param("holders") List<UUID> holders,
                                      @Param("predicateId") UUID predicateId);

    /** temporal.rs:664-671 宾语侧两边的行（holder 列换成 f.object_id）。 */
    List<TRow> selectMergeRowsObject(@Param("kbId") UUID kbId, @Param("holders") List<UUID> holders,
                                     @Param("predicateId") UUID predicateId);

    // ---- reattest_tx（documents.rs:1020-1066；自 DocumentsWriteMapper 迁入，SQL 不变） ----

    /** documents.rs:1028-1043 第一条：开放陈述重锚到还在的证据。 */
    int reattestOpen(@Param("factIds") List<UUID> factIds);

    /** materialize.rs:457-485 第二条：类型化行抄陈述见证（$2=only 名单）。 */
    List<UUID> reattestTyped(@Param("kbId") UUID kbId, @Param("only") List<UUID> only);

    /** documents.rs:1049-1064 第三条：其余行按活文档最早文档日期。 */
    int reattestNonOpen(@Param("factIds") List<UUID> factIds);
}
