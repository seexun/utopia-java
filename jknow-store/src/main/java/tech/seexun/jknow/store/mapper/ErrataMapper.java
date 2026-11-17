package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 勘误 SQL（errata.rs 850 行逐字搬迁，0044 决定 7 第六刀）：candidates（结构报）、
 * documents_due、runs、record（动作落账 + 动图）、held/waiting/decide_held。
 * 每条 XML 内注明 file:line。errata_review 任务本体（LLM 复审循环）属 M4。
 */
public interface ErrataMapper {

    /** errata.rs:116-126 candidates（CANDIDATES_SQL :51-113）：结构报了的在前。 */
    List<Candidate> selectCandidates(@Param("kbId") UUID kbId,
                                     @Param("documentId") UUID documentId);

    /** errata.rs:31-47 Candidate。 */
    record Candidate(UUID factId, UUID statementId, UUID predicateId, UUID subjectId,
                     String subject, String subjectClass, String property, String propertyLabel,
                     UUID objectId, String object, String objectClass, String flag,
                     String quote) {
    }

    /** errata.rs:143-165 documents_due：还有活着的类型化行没看过的，以及抽完了却一次都没看过的。 */
    List<UUID> selectDocumentsDue(@Param("kbId") UUID kbId, @Param("limit") long limit);

    /** errata.rs:168-174 这份文档看过几次。 */
    long runsOf(@Param("documentId") UUID documentId);

    /** errata.rs:178-197 start_run：一次复审开账。 */
    void insertRun(@Param("id") UUID id, @Param("kbId") UUID kbId,
                   @Param("documentId") UUID documentId, @Param("flagged") int flagged,
                   @Param("sampled") int sampled);

    /** errata.rs:200-218 finish_run：结账（端点没报用量就空着）。 */
    void finishRun(@Param("runId") UUID runId, @Param("requests") int requests,
                   @Param("promptTokens") Long promptTokens,
                   @Param("completionTokens") Long completionTokens);

    /** errata.rs:319-331 property_by_key：属性键在这个库里对应的行（id、是关系还是属性）。 */
    PropertyRow selectPropertyByKey(@Param("kbId") UUID kbId, @Param("key") String key);

    /** errata.rs:325-330 (id, kind)。 */
    record PropertyRow(UUID id, String kind) {
    }

    /** errata.rs:422-427 revise 换属性时：目标属性自己的 kind（没随候选给时）。 */
    String selectPropertyKind(@Param("predicateId") UUID predicateId);

    /** errata.rs:428-431 被改的那行的宾语（object 没给时沿用旧的）。 */
    ObjectRow selectFactObject(@Param("factId") UUID factId);

    /** errata.rs:428-431 (object_id, object_value)。 */
    record ObjectRow(UUID objectId, JsonNode objectValue) {
    }

    /** errata.rs:703-712 empty_state_revision 的判定：旧行是不是起止同值。 */
    Boolean selectIsMoment(@Param("factId") UUID factId);

    /** errata.rs:617-624 撤/改前读旧行的时间（新行接旧行的时间）。 */
    ValidityRow selectValidityOf(@Param("factId") UUID factId);

    /** errata.rs:715-723 ValidityRow。 */
    record ValidityRow(OffsetDateTime validFrom, String validFromPrecision,
                       String validFromGrade, OffsetDateTime validTo, String validToPrecision,
                       OffsetDateTime attestedFrom) {
    }

    /** errata.rs:636-640 文档的日期（加的那一行锚在它上）。 */
    OffsetDateTime selectDocTime(@Param("documentId") UUID documentId);

    /** errata.rs:551-572 record 的动作落账（keep/retract/revise/add；refused/held 也记）。 */
    void insertAction(@Param("id") UUID id, @Param("kbId") UUID kbId,
                      @Param("runId") UUID runId, @Param("documentId") UUID documentId,
                      @Param("factId") UUID factId, @Param("statementId") UUID statementId,
                      @Param("predicateId") UUID predicateId, @Param("flag") String flag,
                      @Param("action") String action, @Param("reason") String reason,
                      @Param("quote") String quote, @Param("proposed") JsonNode proposed,
                      @Param("status") String status, @Param("detail") String detail);

    /** errata.rs:587-590 落地的新行记回动作上。 */
    void setNewFact(@Param("id") UUID id, @Param("newFactId") UUID newFactId);

    /**
     * errata.rs:668-674 证据：引文所在的分块（引文跨块时退到文档的第一块由服务层再试）。
     */
    int insertEvidenceInChunk(@Param("factId") UUID factId, @Param("documentId") UUID documentId,
                              @Param("quote") String quote, @Param("first") boolean first);

    /** errata.rs:784-788 decide_held 前读：held 那笔的动作、事实、目标与引文。 */
    HeldRow selectHeldAction(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** errata.rs:767-774 HeldRow。 */
    record HeldRow(String action, UUID factId, UUID documentId, JsonNode proposed,
                   String quote) {
    }

    /** errata.rs:819-829 人答：批了执行过、否则记 rejected（只动还 held 的）。 */
    int decideHeld(@Param("id") UUID id, @Param("kbId") UUID kbId,
                   @Param("status") String status, @Param("newFactId") UUID newFactId,
                   @Param("actor") UUID actor);

    /** errata.rs:741-755 held：闸门留给人的一笔（文档名连上）。 */
    List<HeldItem> selectHeld(@Param("kbId") UUID kbId, @Param("limit") long limit,
                              @Param("offset") long offset);

    /** errata.rs:726-739 HeldItem。 */
    record HeldItem(UUID id, UUID documentId, String document, String action, String flag,
                    UUID factId, JsonNode proposed, String reason, String quote, String detail,
                    OffsetDateTime createdAt) {
    }

    /** errata.rs:758-765 waiting：等人的几笔，最老的从什么时候起等。 */
    WaitingRow selectWaiting(@Param("kbId") UUID kbId);

    /** errata.rs:758-765 (count, min(created_at))。 */
    record WaitingRow(long count, OffsetDateTime oldest) {
    }
}
