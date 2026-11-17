package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 人的对齐例子回归 SQL（ontology_regressions.rs 230 行逐字搬迁，0061 cut 2 第一片）：
 * 例子比较现有对齐账本，不额外调用模型。每条 XML 内注明 file:line。
 */
public interface OntologyRegressionsMapper {

    /** ontology_regressions.rs:52-69 views：工作台读_saved 结果与活的源标签（不评估不写）。 */
    List<JsonNode> selectViews(@Param("kbId") UUID kbId);

    /** ontology_regressions.rs:74-83 list：还活着的陈述上的例子。 */
    List<Case> selectList(@Param("kbId") UUID kbId);

    /** ontology_regressions.rs:85-93 get。 */
    Case selectGet(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** ontology_regressions.rs:10-22 Case。 */
    record Case(UUID id, UUID kbId, UUID statementId, UUID expectedPropertyId,
                String expectedDirection, UUID createdBy, OffsetDateTime createdAt,
                String origin, OffsetDateTime lastCheckedAt, JsonNode lastResult) {
    }

    /**
     * ontology_regressions.rs:117-125 陈述的宾语是不是字面值（FOR SHARE；layer=open 且活着
     * 且短语非空）。null = 陈述不在。
     */
    Boolean selectStatementIsValueForShare(@Param("kbId") UUID kbId,
                                           @Param("statementId") UUID statementId);

    /** ontology_regressions.rs:127-132 属性的 kind（FOR SHARE）。null = 属性不在。 */
    String selectPropertyKindForShare(@Param("kbId") UUID kbId,
                                      @Param("propertyId") UUID propertyId);

    /**
     * ontology_regressions.rs:142-147 add_on 的 INSERT（ON CONFLICT DO NOTHING）——
     * 返回 id；撞键时返回 null（再补一条 SELECT 捡回已有的）。
     */
    UUID insertCase(@Param("id") UUID id, @Param("kbId") UUID kbId,
                    @Param("statementId") UUID statementId,
                    @Param("expectedPropertyId") UUID expectedPropertyId,
                    @Param("expectedDirection") String expectedDirection,
                    @Param("createdBy") UUID createdBy, @Param("origin") String origin);

    /** ontology_regressions.rs:151-159 撞键时捡回已有那行的 id。 */
    UUID selectCaseId(@Param("kbId") UUID kbId, @Param("statementId") UUID statementId,
                      @Param("expectedPropertyId") UUID expectedPropertyId,
                      @Param("expectedDirection") String expectedDirection);

    /**
     * ontology_regressions.rs:164-171/178-195 记回结果（fresh=false 用 b.decided_at 当
     * last_checked_at：既有的判定按它原时刻报，不当成一次新的模型检查）。只动还没查过的。
     */
    int recordExisting(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /**
     * ontology_regressions.rs:197-219 record_for_signature：活陈述此刻的签名命中的绑定
     * 记成一次 fresh 检查（checked_at=now()）。人的绑定保得住：human_bound 区分
     * 「人护住的」与「机器独立答对的」。
     */
    int recordForSignature(@Param("kbId") UUID kbId, @Param("phrase") String phrase,
                           @Param("subjectTypeId") UUID subjectTypeId,
                           @Param("objectTypeId") UUID objectTypeId,
                           @Param("objectIsValue") boolean objectIsValue);

    /** ontology_regressions.rs:222-230 record_human：护住生产跳过时的人的选择。 */
    int recordHuman(@Param("kbId") UUID kbId);
}
