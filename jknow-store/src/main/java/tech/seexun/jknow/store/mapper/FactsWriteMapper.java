package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 事实落库写族 SQL（graph.rs 逐字搬迁；insert_fact_on 门 596-909 的同断言查询、
 * INSERT、时间精化四条，attest_earlier、add_evidence、qualifiers、开放陈述）。
 * 每条 XML 内注明 file:line。门的分支编排在 {@code GraphWriteService}。
 */
public interface FactsWriteMapper {

    // ---- purge_graph（graph.rs:2189-2221，M5 任务 6 重建面落位） ----

    /** 图层清算前的计数（entities_removed / facts_removed 回显）。 */
    long countEntities(@Param("kbId") UUID kbId);

    long countFacts(@Param("kbId") UUID kbId);

    // FK 多为 CASCADE，但两处自引用是 NO ACTION：先解引用再删，顺序显式写出
    // （这段本身就是"图层由什么构成"的定义）
    int deleteFactConflicts(@Param("kbId") UUID kbId);

    int deleteResolutionReviews(@Param("kbId") UUID kbId);

    int deleteEntityMerges(@Param("kbId") UUID kbId);

    int clearSupersedes(@Param("kbId") UUID kbId);

    int deleteFactEvidenceInKb(@Param("kbId") UUID kbId);

    int deleteFactsInKb(@Param("kbId") UUID kbId);

    int clearMergedInto(@Param("kbId") UUID kbId);

    int deleteEntitiesInKb(@Param("kbId") UUID kbId);

    /** 未匹配统计由抽取重新累积。 */
    int deleteOntologyMisses(@Param("kbId") UUID kbId);

    // ---- insert_fact_on（graph.rs:596-909） ----

    /** graph.rs:598-602 同断言查询（实体宾语）：活着的、主谓宾相同的事实。 */
    List<FactSpanRow> selectSameEntity(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                                       @Param("predicateId") UUID predicateId,
                                       @Param("objectId") UUID objectId);

    /** graph.rs:603-607 同断言查询（值宾语）：object_value 全等、object_id 为空。 */
    List<FactSpanRow> selectSameValue(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                                      @Param("predicateId") UUID predicateId,
                                      @Param("objectValue") JsonNode objectValue);

    /** graph.rs:606-608 FactSpanRow（same 查询的四列）。 */
    record FactSpanRow(UUID id, OffsetDateTime validFrom, OffsetDateTime validTo,
                       String validToPrecision) {
    }

    /** graph.rs:838-845 新行 INSERT（实体宾语）。 */
    int insertFactEntity(@Param("id") UUID id, @Param("kbId") UUID kbId,
                         @Param("subjectId") UUID subjectId, @Param("predicateId") UUID predicateId,
                         @Param("objectId") UUID objectId,
                         @Param("from") OffsetDateTime from, @Param("fromPrecision") String fromPrecision,
                         @Param("to") OffsetDateTime to, @Param("toPrecision") String toPrecision,
                         @Param("confidence") float confidence,
                         @Param("attestedAt") OffsetDateTime attestedAt,
                         @Param("fromGrade") String fromGrade);

    /** graph.rs:846-852 新行 INSERT（值宾语）。 */
    int insertFactValue(@Param("id") UUID id, @Param("kbId") UUID kbId,
                        @Param("subjectId") UUID subjectId, @Param("predicateId") UUID predicateId,
                        @Param("objectValue") JsonNode objectValue,
                        @Param("from") OffsetDateTime from, @Param("fromPrecision") String fromPrecision,
                        @Param("to") OffsetDateTime to, @Param("toPrecision") String toPrecision,
                        @Param("confidence") float confidence,
                        @Param("attestedAt") OffsetDateTime attestedAt,
                        @Param("fromGrade") String fromGrade);

    /** graph.rs:868-869 时间精化：裸行作废（新行随后链上）。 */
    void invalidateFact(@Param("factId") UUID factId);

    /** graph.rs:871-872 时间精化：新行 supersedes 裸行。 */
    void setSupersedes(@Param("id") UUID id, @Param("oldId") UUID oldId);

    /** graph.rs:875-881 时间精化：证据随行搬（表层谓词与偏移一起）。 */
    void copyEvidenceFrom(@Param("to") UUID to, @Param("from") UUID from);

    /** graph.rs:884-887 时间精化：边上的属性随行（0037）。 */
    void copyQualifiersFrom(@Param("to") UUID to, @Param("from") UUID from);

    // ---- attest_earlier（graph.rs:919-942） ----

    /**
     * graph.rs:930-933 同一断言又被观察到一次：锚点只往早挪。LEAST 会跳过 NULL——
     * 开放行的 attested_to 是 NULL，直接 least 会凭空长出终点锚。
     */
    int attestEarlier(@Param("factId") UUID factId, @Param("at") OffsetDateTime at,
                      @Param("ended") boolean ended);

    // ---- add_evidence（graph.rs:1167-1230） ----

    /** graph.rs:1211-1213 证据落笔前的存活检查（FOR SHARE——正在改写它的事务持 FOR UPDATE）。 */
    Boolean selectLiveForShare(@Param("factId") UUID factId);

    /** graph.rs:1215-1219 顺着 supersedes 走到改写出来的那一行。 */
    UUID selectSuccessor(@Param("factId") UUID factId);

    /**
     * graph.rs:1221-1229 证据 INSERT（落笔即记版本；冲突时只补空列不覆盖——
     * 同一分块的同一条事实，第一次记下的说法就是它的说法）。
     */
    int insertEvidence(@Param("factId") UUID factId, @Param("chunkId") UUID chunkId,
                       @Param("quote") String quote, @Param("proposed") String proposed,
                       @Param("quoteStart") Integer quoteStart, @Param("quoteEnd") Integer quoteEnd);

    // ---- 边上的属性（graph.rs:125-154/181-213） ----

    /** graph.rs:126-129 已有的值（先比后写：Same 留、Conflict 报、没有才插）。 */
    JsonNode selectQualifierValue(@Param("factId") UUID factId,
                                  @Param("qualifierTypeId") UUID qualifierTypeId);

    /** graph.rs:131-136 写入（ON CONFLICT DO NOTHING）。 */
    int insertQualifier(@Param("factId") UUID factId,
                        @Param("qualifierTypeId") UUID qualifierTypeId,
                        @Param("value") JsonNode value);

    /** graph.rs:189-199 fact_qualifiers_for 取数（连定义与实体名）。 */
    List<QualifierRow> selectQualifiersFor(@Param("factIds") List<UUID> factIds);

    /** graph.rs:168-176 QualifierRow。 */
    record QualifierRow(UUID factId, UUID qualifierTypeId, String key, String label,
                        JsonNode value, UUID entityId, String entityName) {
    }

    // ---- 开放陈述（graph.rs:1022-1095） ----

    /** graph.rs:1038-1049 去重按 (库, 主语, 短语, 宾语)（实体宾语）。 */
    UUID selectOpenStatementEntity(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                                   @Param("phrase") String phrase, @Param("objectId") UUID objectId);

    /** graph.rs:1050-1057 去重（值宾语：object_value 全等且 object_id 空）。 */
    UUID selectOpenStatementValue(@Param("kbId") UUID kbId, @Param("subjectId") UUID subjectId,
                                  @Param("phrase") String phrase,
                                  @Param("objectValue") JsonNode objectValue);

    /** graph.rs:1065-1071 开放陈述 INSERT（实体宾语）。 */
    int insertOpenStatementEntity(@Param("id") UUID id, @Param("kbId") UUID kbId,
                                  @Param("subjectId") UUID subjectId, @Param("phrase") String phrase,
                                  @Param("objectId") UUID objectId,
                                  @Param("confidence") float confidence,
                                  @Param("attestedAt") OffsetDateTime attestedAt);

    /** graph.rs:1072-1079 开放陈述 INSERT（值宾语）。 */
    int insertOpenStatementValue(@Param("id") UUID id, @Param("kbId") UUID kbId,
                                 @Param("subjectId") UUID subjectId, @Param("phrase") String phrase,
                                 @Param("objectValue") JsonNode objectValue,
                                 @Param("confidence") float confidence,
                                 @Param("attestedAt") OffsetDateTime attestedAt);

    // ---- 开放陈述的时间词落轴（graph.rs:1125-1162 set_open_validity） ----

    /**
     * graph.rs:1139-1146 时间词落轴（只动活着的开放陈述；两端已由调用方按精度截断；
     * attested_to 只在「结束了不知哪天」时锚上）。返回行数（0 = 没命中活陈述）。
     */
    int setOpenValidity(@Param("factId") UUID factId, @Param("from") OffsetDateTime from,
                        @Param("fromPrecision") String fromPrecision,
                        @Param("to") OffsetDateTime to, @Param("toPrecision") String toPrecision,
                        @Param("grade") String grade);

    // ---- 陈述的见证（graph.rs:949-965 attest_statement） ----

    /**
     * graph.rs:957-964 开放陈述的见证只往早挪：它所在那一节里文本说话的那一刻，
     * 连同那条日期的名字。返回是否挪动（rows_affected &gt; 0）。
     */
    int attestStatement(@Param("factId") UUID factId, @Param("at") OffsetDateTime at,
                        @Param("by") String by);

    // ---- 开放陈述的角色词属性（graph.rs:216-283） ----

    /** graph.rs:232-238 写入（ON CONFLICT DO NOTHING——先写者留着）。 */
    int insertStatementQualifier(@Param("factId") UUID factId, @Param("role") String role,
                                 @Param("value") JsonNode value, @Param("entityId") UUID entityId);

    /** graph.rs:269-275 一批开放陈述各自的角色词属性。 */
    List<StatementQualifierRow> selectStatementQualifiersFor(
            @Param("factIds") List<UUID> factIds);

    /** graph.rs:257-265 StatementQualifier（连上实体显示名的一行）。 */
    record StatementQualifierRow(UUID factId, String role, JsonNode value, UUID entityId,
                                 String entityName) {
    }

    /**
     * graph.rs:975-992 witness_evidence 逐字：一条证据记下它那段原文说话的那一刻（连同
     * 那条日期的名字；那一节没说日期的记成空）。attest_statement 只把事实的见证往早挪，
     * 哪份证据给的、别的证据各是哪天都不留——文档删了/撤销删除要照还在的证据重算，靠的
     * 就是这里记下的每一条。无见证传 null。
     */
    int witnessEvidence(@Param("factId") UUID factId, @Param("chunkId") UUID chunkId,
                        @Param("at") OffsetDateTime at, @Param("by") String by);

    /** graph.rs:2042-2056 confirm_fact：人工确认低置信事实——置信度提到 1.0。 */
    int confirmFact(@Param("kbId") UUID kbId, @Param("factId") UUID factId);
}
