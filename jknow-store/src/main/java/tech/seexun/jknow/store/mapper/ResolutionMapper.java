package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.mapper.ReviewMapper.ReviewRow;

/**
 * 消解与合并 SQL（resolution.rs 逐字搬迁）：候选召回（通道 1）、跨类型同名、事实旁证、
 * 包含扫描、kept-apart、类型互斥/亲族、建实体（咨询锁）、画像、消歧后缀、审核队列
 * 写面、合并/撤回全族。每条 XML 内注明 file:line。
 *
 * <p>向量列（profile_embedding）以 {@code ::text} 读出（"[1,0,0]"）、以字面量
 * {@code #{...}::vector} 写入——与 DocumentsWriteMapper.setEmbeddings 同一口径。
 */
public interface ResolutionMapper {

    /** resolution.rs:190-197 Candidate（profile 以文本形态读出）。 */
    record Candidate(UUID id, String canonicalName, String profileEmbedding, int profileN,
                     long degree) {

        public float[] profile() {
            return VectorText.parse(profileEmbedding);
        }
    }

    /** resolution.rs:816-828 CrossCandidate：type_key/type_id 可空（0009 未分类）。 */
    record CrossCandidate(UUID id, String canonicalName, String typeKey, UUID typeId,
                          String typeSource, String profileEmbedding, int profileN) {

        public float[] profile() {
            return VectorText.parse(profileEmbedding);
        }
    }

    /** resolution.rs:714 ContainRow：(id, 本名, 类型 key, 类型 id, 画像)。 */
    record ContainRow(UUID id, String canonicalName, String typeKey, UUID typeId,
                      String profileEmbedding) {

        public float[] profile() {
            return VectorText.parse(profileEmbedding);
        }
    }

    /** resolution.rs:2200-2211 MergeRow（revert 读回的账目形状）。 */
    record MergeRow(UUID sourceId, UUID targetId, List<UUID> movedSubjectFacts,
                    List<UUID> movedObjectFacts, List<UUID> invalidatedFacts,
                    String targetProfileBefore, int targetProfileNBefore,
                    UUID targetTypeBefore, OffsetDateTime revertedAt) {
    }

    /** resolution.rs:1904-1912 EntityFull（合并双方的开场读档）。 */
    record EntityFull(UUID typeId, String canonicalName, String profileEmbedding, int profileN,
                      UUID mergedInto) {
    }

    /** pgvector 文本形态 "[1,0,0]" 与 float[] 的互转（读侧）。 */
    final class VectorText {

        private VectorText() {
        }

        /** "[1,0,0]" → float[]；null/空 → null。 */
        public static float[] parse(String text) {
            if (text == null) {
                return null;
            }
            String body = text.strip();
            if (body.startsWith("[")) {
                body = body.substring(1);
            }
            if (body.endsWith("]")) {
                body = body.substring(0, body.length() - 1);
            }
            if (body.isEmpty()) {
                return new float[0];
            }
            String[] parts = body.split(",");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i].strip());
            }
            return out;
        }

        /** float[] → "[1.0,0.0]"（pgvector 文本输入格式）。 */
        public static String literal(float[] vector) {
            StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(vector[i]);
            }
            return sb.append(']').toString();
        }
    }

    // ---- 通道 1：候选召回（resolution.rs:381-397） ----

    /** 同类型同名候选（含 known_as 名字事实召回；度数不数名字事实）。 */
    List<Candidate> selectCandidates(@Param("kbId") UUID kbId, @Param("typeId") UUID typeId,
                                     @Param("keys") List<String> keys);

    /** resolution.rs:961-971 跨类型同名候选（IS DISTINCT FROM——未分类实体不漏）。 */
    List<CrossCandidate> selectCrossCandidates(@Param("kbId") UUID kbId,
                                               @Param("typeId") UUID typeId,
                                               @Param("keys") List<String> keys);

    /** resolution.rs:279-290 kept_apart_from：判过「不是一个」的那些实体。 */
    List<UUID> selectKeptApart(@Param("kbId") UUID kbId, @Param("entityId") UUID entityId);

    // ---- 旁证（resolution.rs:620-642） ----

    /** 每候选取若干「同伴不指向」的宾语名（窗口排序：range>state>functional>置信度）。 */
    List<CorroboratingRow> selectCorroboratingObjects(@Param("kbId") UUID kbId,
                                                      @Param("ids") List<UUID> ids,
                                                      @Param("limit") long limit);

    /** (subject_id, name) 行。 */
    record CorroboratingRow(UUID subjectId, String name) {
    }

    // ---- 包含关系（resolution.rs:746-776） ----

    List<ContainRow> selectContainRows(@Param("kbId") UUID kbId, @Param("newId") UUID newId,
                                       @Param("lower") String lower,
                                       @Param("minChars") int minChars,
                                       @Param("limit") long limit);

    // ---- 类型面 ----

    /** resolution.rs:731-734/954 entity_types.key（未分类/不存在 → null）。 */
    String selectTypeKey(@Param("typeId") UUID typeId);

    /** resolution.rs:350 type_label（通道 2 大类比对用）。 */
    String selectTypeLabel(@Param("typeId") UUID typeId);

    /** resolution.rs:858-872 declared_disjoint_from（递归 CTE：祖先声明沿子链展开）。 */
    List<UUID> selectDeclaredDisjointFrom(@Param("kbId") UUID kbId, @Param("typeId") UUID typeId);

    /** resolution.rs:891-906 types_are_kin（一方是另一方祖先，或共有非根祖先 #226）。 */
    Boolean selectTypesAreKin(@Param("a") UUID a, @Param("b") UUID b);

    // ---- 建实体（resolution.rs:1107-1176/1191-1216） ----

    /** resolution.rs:1126 pg_advisory_xact_lock(hashtext(kb), hashtext(name))：同名同类串行。 */
    void lockNameCreation(@Param("kb") String kb, @Param("name") String name);

    /** resolution.rs:1133-1136 锁内回捞（id <> ALL(weighed)：不捞回刚拒绝的候选）。 */
    List<UUID> selectExistingNameEntity(@Param("kbId") UUID kbId, @Param("name") String name,
                                        @Param("typeId") UUID typeId,
                                        @Param("weighed") List<UUID> weighed);

    /** resolution.rs:1150-1151 INSERT entities（画像/计数随建）。 */
    int insertEntity(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("typeId") UUID typeId,
                     @Param("name") String name, @Param("profile") String profile,
                     @Param("profileN") int profileN);

    /**
     * resolution.rs:1164-1165 与实体同一个事务的 known_as 名字事实——召回按名字事实
     * 找它，建出来却查不到名字的那一瞬间不能有。
     */
    int insertNameFact(@Param("id") UUID id, @Param("kbId") UUID kbId,
                       @Param("entityId") UUID entityId, @Param("knownAs") UUID knownAs,
                       @Param("name") String name);

    /** resolution.rs:1205-1208 create_described（不写 known_as）。 */
    int insertDescribed(@Param("id") UUID id, @Param("kbId") UUID kbId,
                        @Param("description") String description,
                        @Param("kindWord") String kindWord);

    /** resolution.rs:1218-1220 touch_entity（v1 兼容归并时）。 */
    int touchEntity(@Param("id") UUID id);

    /**
     * resolution.rs:1027-1030 Recall 升格：候选还没判出类型而这次抽取判出来了。
     * <b>type_source != 'human' 才升</b>（调用方判定）——人说过「没有类型」不算
     * 「还没判出来」（0009 之后两者都是 NULL，只看 type_id 分不出）。
     */
    int upgradeEntityType(@Param("id") UUID id, @Param("typeId") UUID typeId);

    // ---- retype（resolution.rs:2903-2982） ----

    /**
     * resolution.rs:2933-2955 retype 的 CTE UPDATE。<b>旧类型从 CTE 里读</b>：RETURNING
     * 给的是新值而账本要记改之前的那个；条件一并放进 CTE（还在、没被合并、确实要变）。
     * <b>IS DISTINCT FROM 而不是 &lt;&gt;</b>：0009 之后起点常常是 NULL，NULL &lt;&gt; uuid
     * 是 NULL 不是 true——「给没有类的实体定类」这个主场景会整个变成空操作。
     */
    RetypeRow retypeOne(@Param("entityId") UUID entityId, @Param("typeId") UUID typeId,
                        @Param("kbId") UUID kbId, @Param("actor") UUID actor);

    /** retype 的 RETURNING 行：(改之前的类型, 实体名)。 */
    record RetypeRow(UUID fromType, String name) {
    }

    /** resolution.rs:2962-2968 entity_retypes 账本行（撤销按 batch 回放）。 */
    int insertEntityRetype(@Param("batchId") UUID batchId, @Param("kbId") UUID kbId,
                           @Param("entityId") UUID entityId, @Param("fromTypeId") UUID fromTypeId,
                           @Param("toTypeId") UUID toTypeId, @Param("actorId") UUID actorId);

    // ---- 画像与消歧后缀 ----

    /** resolution.rs:1230 读现画像（update_profile 的增量质心输入）。 */
    String selectProfileEmbedding(@Param("id") UUID id);

    /** resolution.rs:1249-1250 update_profile 落库（维度不匹配重置由 Java 判定）。 */
    int updateProfile(@Param("id") UUID id, @Param("profile") String profile,
                      @Param("profileN") int profileN);

    /** resolution.rs:1318-1321 同名组（不问类型、并掉的不算）。 */
    List<UUID> selectDisambiguatorGroup(@Param("kbId") UUID kbId, @Param("name") String name);

    /** resolution.rs:1329 组内唯一：清空后缀。 */
    int clearDisambiguator(@Param("id") UUID id);

    /** resolution.rs:1342-1359 最能把自己和同名者分开的那条事实的宾语名。 */
    String selectDisambiguatingLabel(@Param("kbId") UUID kbId, @Param("id") UUID id,
                                     @Param("peers") List<UUID> peers);

    /** resolution.rs:1370-1373 退到类型标签（类型也可能没有，0009）。 */
    String selectTypeLabelOfEntity(@Param("id") UUID id);

    /** resolution.rs:1379 写后缀。 */
    int setDisambiguator(@Param("id") UUID id, @Param("value") String value);

    /** resolution.rs:1297-1314 existing_by_name（事实最多的同名者；不问类型）。 */
    UUID selectExistingByName(@Param("kbId") UUID kbId, @Param("keys") List<String> keys);

    // ---- 审核队列写面（resolution.rs:1393-1421/1742-1772/1788-1840） ----

    /**
     * resolution.rs:1402-1419 create_review 幂等 upsert（逐字）——同一 pending 对只在
     * 阶段升 human 时改写。pairSharedName 的 shared_name 排队也走这一条（统一搬运点）。
     */
    int insertResolutionReview(@Param("id") UUID id, @Param("kbId") UUID kbId,
                               @Param("leftId") UUID leftId, @Param("rightId") UUID rightId,
                               @Param("score") float score, @Param("reason") String reason,
                               @Param("stage") String stage);

    /** resolution.rs:1744-1746 escalate_review（LLM 不确定/未配模型 → 转人工）。 */
    int escalateReview(@Param("reviewId") UUID reviewId, @Param("reason") String reason);

    /** resolution.rs:1763-1765 close_review_auto（merged/kept；回关上几行）。 */
    int closeReviewAuto(@Param("reviewId") UUID reviewId, @Param("status") String status,
                        @Param("reason") String reason);

    /** resolution.rs:1724-1728 pending_adjudications（stage='adjudicating' 先到先裁）。 */
    List<ReviewRow> selectPendingAdjudications(@Param("kbId") UUID kbId,
                                               @Param("limit") long limit);

    /** resolution.rs:1787-1789 decide_review 的开场 SELECT（pending 行）。 */
    ReviewRow selectPendingReview(@Param("reviewId") UUID reviewId, @Param("kbId") UUID kbId);

    /** resolution.rs:1825-1828 裁决落账：merged。 */
    int markReviewMerged(@Param("reviewId") UUID reviewId, @Param("userId") UUID userId,
                         @Param("rationale") String rationale);

    /** resolution.rs:1837-1840 裁决落账：kept。 */
    int markReviewKept(@Param("reviewId") UUID reviewId, @Param("userId") UUID userId,
                       @Param("rationale") String rationale);

    // ---- LLM 裁决缓存（resolution.rs:2386-2415） ----

    /** resolution.rs:2390-2394 get_verdict：缓存的一笔（same 可空 = 模型也不确定）。 */
    VerdictRow selectVerdict(@Param("kbId") UUID kbId, @Param("pairKey") String pairKey);

    /** get_verdict 的行：same 为 NULL 表示 unsure 也缓存过（不重复付费）。 */
    record VerdictRow(Boolean same, float confidence) {
    }

    /** resolution.rs:2399-2415 put_verdict：UPSERT——同键重写（先例变了键就变，见 pair_key）。 */
    int upsertVerdict(@Param("kbId") UUID kbId, @Param("pairKey") String pairKey,
                      @Param("same") Boolean same, @Param("confidence") float confidence,
                      @Param("model") String model);

    // ---- survivor / merge_direction（resolution.rs:1855-1898） ----

    /**
     * resolution.rs:1858 survivor 的单跳：merged_into。Rust 读 Option&lt;Option&lt;Uuid&gt;&gt;
     * ——空列表 = 实体行不存在（survivor 报 NotFound）；[null] = 活着；[id] = 还要再走。
     */
    List<UUID> selectMergedInto(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /**
     * resolution.rs:1876-1880 merge_direction 的度数：kb 由标量子查询取（OR 两侧都能按
     * 复合索引定位）。<b>不排除名字事实</b>——这里量的是「这一侧身上挂着多少东西」，
     * 与召回侧「度数不数名字事实」口径不同（名字在合并里一样要搬）。
     */
    long selectFactDegree(@Param("entityId") UUID entityId);

    /** resolution.rs:1915-1918 entity_full（合并/撤回的开场读档）。 */
    EntityFull selectEntityFull(@Param("kbId") UUID kbId, @Param("id") UUID id);

    // ---- merge_entities 事务（resolution.rs:1955-2149） ----

    /** resolution.rs:1955-1957 先取 moving（source 的全部事实）→ timelines_of(swap)。 */
    List<UUID> selectMovingFacts(@Param("kbId") UUID kbId, @Param("sourceId") UUID sourceId);

    /** resolution.rs:1968-1972 互指事实（合并后变自环）作废 RETURNING id。 */
    List<UUID> invalidateCrossFacts(@Param("kbId") UUID kbId, @Param("sourceId") UUID sourceId,
                                    @Param("targetId") UUID targetId);

    /** resolution.rs:1981-1983 搬主语 RETURNING id。 */
    List<UUID> moveSubjectFacts(@Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId,
                                @Param("kbId") UUID kbId);

    /** resolution.rs:1992-1994 搬宾语 RETURNING id。 */
    List<UUID> moveObjectFacts(@Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId,
                               @Param("kbId") UUID kbId);

    /** resolution.rs:2010-2015 合并后 SPO+valid_from 重复组除首外的 id（宾语两侧分组）。 */
    List<UUID> selectDuplicateFactIds(@Param("kbId") UUID kbId, @Param("targetId") UUID targetId);

    /** resolution.rs:2023 重复事实作废。 */
    int invalidateFactIds(@Param("ids") List<UUID> ids);

    /** resolution.rs:2060-2062 画像加权合并 + 类型调和（target.type_id.or(source)）。 */
    int updateMergedEntity(@Param("targetId") UUID targetId, @Param("profile") String profile,
                           @Param("profileN") int profileN, @Param("typeId") UUID typeId);

    /** resolution.rs:2070 source 标记 merged_into。 */
    int markMergedInto(@Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId);

    /**
     * resolution.rs:2087-2103 先关两类真正过时的（重定向自环、目标对已在队）——
     * 其余的改指不关掉（「关掉就是永远关掉」的教训，Mr. Holmes 案例）。
     */
    int supersedeObsoleteReviews(@Param("kbId") UUID kbId, @Param("sourceId") UUID sourceId,
                                 @Param("targetId") UUID targetId);

    /** resolution.rs:2113-2120 剩下的 pending 审核项改指到目标（reason 加 |redirected）。 */
    int redirectReviews(@Param("kbId") UUID kbId, @Param("sourceId") UUID sourceId,
                        @Param("targetId") UUID targetId);

    /** resolution.rs:2129-2134 entity_merges INSERT（12 列，逐字）。 */
    int insertEntityMerge(@Param("id") UUID id, @Param("kbId") UUID kbId,
                          @Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId,
                          @Param("movedSubject") List<UUID> movedSubject,
                          @Param("movedObject") List<UUID> movedObject,
                          @Param("invalidated") List<UUID> invalidated,
                          @Param("profileBefore") String profileBefore,
                          @Param("profileNBefore") int profileNBefore,
                          @Param("typeBefore") UUID typeBefore,
                          @Param("mergedBy") UUID mergedBy, @Param("reason") String reason);

    /** resolution.rs:2183-2185 修正行 id 写回合并账本。 */
    int setMergeCorrections(@Param("id") UUID id, @Param("corrections") List<UUID> corrections);

    // ---- revert_merge（resolution.rs:2245-2350） ----

    /** resolution.rs:2245-2249 合并账目读档（reverted_at 已非空 → 上层报已撤回）。 */
    MergeRow selectMergeRow(@Param("mergeId") UUID mergeId, @Param("kbId") UUID kbId);

    /** resolution.rs:2264-2269 holders：target 的 merged_into 递归链（连环合并，#679）。 */
    List<UUID> selectHolderChain(@Param("targetId") UUID targetId);

    /** resolution.rs:2218-2225 with_rewrites：沿 supersedes 收全部改写行。 */
    List<UUID> selectWithRewrites(@Param("roots") List<UUID> roots);

    /** resolution.rs:2288 账面行原地改回主语（只在还挂在链上时）。 */
    int revertSubjectFacts(@Param("sourceId") UUID sourceId,
                           @Param("movedSubject") List<UUID> movedSubject,
                           @Param("holders") List<UUID> holders);

    /** resolution.rs:2294 账面行原地改回宾语。 */
    int revertObjectFacts(@Param("sourceId") UUID sourceId,
                          @Param("movedObject") List<UUID> movedObject,
                          @Param("holders") List<UUID> holders);

    /** resolution.rs:2305-2307 合并后改写出的活行（不在账面上的）——subject 侧。 */
    List<UUID> selectLiveRewriteSubjects(@Param("rows") List<UUID> rows,
                                         @Param("ledger") List<UUID> ledger,
                                         @Param("holders") List<UUID> holders);

    /** 同上 object 侧。 */
    List<UUID> selectLiveRewriteObjects(@Param("rows") List<UUID> rows,
                                        @Param("ledger") List<UUID> ledger,
                                        @Param("holders") List<UUID> holders);

    /** resolution.rs:2323 invalidated_facts 复活。 */
    int reviveFacts(@Param("ids") List<UUID> ids);

    /** resolution.rs:2331-2335 target 画像/类型恢复快照（coalesce 保人的后续改类）。 */
    int restoreTargetEntity(@Param("targetId") UUID targetId, @Param("profile") String profile,
                            @Param("profileN") int profileN, @Param("typeId") UUID typeId);

    /** resolution.rs:2343 source 复活（merged_into=NULL）。 */
    int reviveSource(@Param("sourceId") UUID sourceId);

    /** resolution.rs:2347 账目标记撤回时刻。 */
    int markMergeReverted(@Param("mergeId") UUID mergeId);

    /**
     * resolution.rs:2736-2740 set_specific_type 逐字：模型说它是什么（kind 词首写不覆盖
     * ——specific_type IS NULL 才写，left($2,80) 截 80），类的绑定等对齐来做。
     */
    int setSpecificType(@Param("entityId") UUID entityId, @Param("value") String value);

    // ---- 类型消解候选与近邻（resolution.rs:2617-2896；M4 任务 1 随治理测试移植） ----

    /** resolution.rs:2575-2608 TypeCandidateSubject（行记录；子查询三数组 + 事实计数）。 */
    record TypeCandidateSubject(
            UUID id,
            String canonicalName,
            List<String> otherNames,
            String coarseKey,
            UUID coarseId,
            String coarseDescription,
            String proposedType,
            String specificType,
            List<String> roles,
            List<String> quotes,
            long factCount) {
    }

    /** resolution.rs:2617-2696 entities_for_type_resolution（值得送去精化类型的实体，逐字）。 */
    List<TypeCandidateSubject> selectTypeCandidates(@Param("kbId") UUID kbId,
                                                    @Param("limit") long limit,
                                                    @Param("unattended") boolean unattended);

    /** resolution.rs:2700-2708 mark_type_judged（引擎看过了：改了/留给人/不动都算）。 */
    long markTypeJudged(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids);

    /** resolution.rs:2985-2995 的一行：人认可过的 (粗类, 细类) 配对。 */
    record ApprovedPair(UUID fromTypeId, UUID toTypeId) {
    }

    /** resolution.rs:2985-2995 approved_refinements：人认可过的 (粗类, 细类) 配对。 */
    List<ApprovedPair> selectApprovedRefinements(@Param("kbId") UUID kbId);

    /** resolution.rs:2715-2729 descendants_of（某个类的全部后代含自身，递归 CTE）。 */
    List<UUID> selectDescendants(@Param("kbId") UUID kbId, @Param("root") UUID root);

    /** resolution.rs:2812-2817 主语自己的画像向量（文本形态；没有=null）。 */
    String selectProfileEmbeddingInKb(@Param("kbId") UUID kbId, @Param("entityId") UUID entityId);
}
