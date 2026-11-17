package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;
import tech.seexun.jknow.store.entity.PendingFactView;

/**
 * 等人点头的事实写族 SQL（pending.rs 逐字搬迁）：propose 前置三查 + 20 列 INSERT、
 * 单条 get、点头的锚点两查、时态对账要不要做、拒绝落账、出队。每条 XML 内注明 file:line。
 */
public interface PendingMapper {

    /** pending.rs:83-90 三查之「活开放行同键」（开放陈述按 (主语, 短语, 宾语)）。 */
    Integer selectAssertedOpen(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                               @Param("phrase") String phrase, @Param("objectId") UUID objectId,
                               @Param("objectValue") JsonNode objectValue);

    /** pending.rs:102-109 三查之「活事实同键」（带本体形状按 (主语, 谓词, 宾语)）。 */
    Integer selectAssertedTyped(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                                @Param("predicateId") UUID predicateId,
                                @Param("objectId") UUID objectId,
                                @Param("objectValue") JsonNode objectValue);

    /** pending.rs:123-129 三查之「pending_facts 同键」（短语也进键）。 */
    Integer selectPendingDup(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                             @Param("predicateId") UUID predicateId,
                             @Param("objectId") UUID objectId,
                             @Param("objectValue") JsonNode objectValue,
                             @Param("phrase") String phrase);

    /**
     * pending.rs:148-154 三查之「人拒绝过」——只按 (主语, 谓词/短语, 宾语实体)。
     * <b>字面值事实不查拒收表</b>：rejected_facts 没有 object_value 列，按 (主语, 谓词)
     * 挡会把「薪水 28000 被拒」扩大成「薪水属性永不再提」。
     */
    Integer selectRejected(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                           @Param("predicateId") UUID predicateId, @Param("objectId") UUID objectId,
                           @Param("phrase") String phrase);

    /** pending.rs:167-175 提议落队（20 列）。 */
    int insertPending(@Param("id") UUID id, @Param("kbId") UUID kbId,
                      @Param("subjectId") UUID subjectId, @Param("predicateId") UUID predicateId,
                      @Param("objectId") UUID objectId, @Param("objectValue") JsonNode objectValue,
                      @Param("proposedPredicate") String proposedPredicate,
                      @Param("from") OffsetDateTime from, @Param("fromPrecision") String fromPrecision,
                      @Param("to") OffsetDateTime to, @Param("toPrecision") String toPrecision,
                      @Param("confidence") float confidence, @Param("chunkId") UUID chunkId,
                      @Param("proposedBy") UUID proposedBy, @Param("proposedToken") UUID proposedToken,
                      @Param("phrase") String phrase, @Param("qualifiers") JsonNode qualifiers,
                      @Param("timeWords") JsonNode timeWords,
                      @Param("quoteStart") Integer quoteStart, @Param("quoteEnd") Integer quoteEnd);

    /** pending.rs:250-253 单条 get（VIEW_SELECT 主体，给人看的那一行）。 */
    PendingFactView selectById(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** pending.rs:301-306 带本体形状点头的锚点：那句记忆所在文档的日期。 */
    OffsetDateTime selectChunkDocTime(@Param("chunkId") UUID chunkId);

    /** pending.rs:428-433 开放陈述点头的锚点：只认文档自己说的日期（content/source）。 */
    OffsetDateTime selectChunkDocTimeStated(@Param("chunkId") UUID chunkId);

    /** pending.rs:360-364 谓词的 (functional, inverse_functional, temporal)。 */
    RelationMeta selectRelationMeta(@Param("predicateId") UUID predicateId);

    /** pending.rs:360-364 的三元组。 */
    record RelationMeta(boolean functional, boolean inverseFunctional, String temporal) {
    }

    /** pending.rs:571-575 拒绝落账 rejected_facts。 */
    int insertRejected(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                       @Param("predicateId") UUID predicateId, @Param("objectId") UUID objectId,
                       @Param("rejectedBy") UUID rejectedBy, @Param("phrase") String phrase);

    /** pending.rs:396/523/583 出队。 */
    int deletePending(@Param("id") UUID id);

    /** pending.rs:552-558 那句记忆所在的文档。 */
    UUID selectDocumentOfChunk(@Param("chunkId") UUID chunkId);
}
