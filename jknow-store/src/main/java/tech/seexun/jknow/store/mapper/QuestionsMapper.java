package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.Param;

/**
 * 能力问题读族 mapper（M4 任务 6——逐字搬迁旧仓库 crates/utopia-store/src/
 * competency_questions.rs 的两条聚合读；写面 create/update/set_status 归问题域写任务）。
 *
 * <p>问题域语义（0061 决定 1）：一个库该回答什么，写成行；本体的元素凭它们判。
 * 人写的直接 accepted；代理提的先 proposed 等人认。
 */
public interface QuestionsMapper {

    /** competency_questions.rs:11-27 Question 十二列（serde 字段名 snake_case 全集）。 */
    record QuestionRow(UUID id, UUID kbId, String question, String expectedAnswer,
                       JsonNode needs, String origin, String status, UUID createdBy,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       OffsetDateTime lastCheckedAt, JsonNode lastResult) {
    }

    /** competency_questions.rs:49-55 list：库里全部问题，新的在前。 */
    List<QuestionRow> selectQuestions(@Param("kbId") UUID kbId);

    /** competency_questions.rs:207-216 QuestionReport：接受的问题里问过/答对/凭图答的各几条。 */
    record QuestionReportRow(long accepted, long proposed, long checked, long answered,
                             long answeredWithGraph, long answeredGraphOnly) {
    }

    /** competency_questions.rs:218-237 report 六格 FILTER 聚合（问数工作台的两个数之一）。 */
    QuestionReportRow selectQuestionReport(@Param("kbId") UUID kbId);

    // ---- M5 任务 5：问题写面（本体代理 propose_questions 的生产侧 + 采纳链） ----

    /** competency_questions.rs:59-66 accepted：接受了的问题（本体提案轮的输入）。 */
    List<QuestionRow> selectAcceptedQuestions(@Param("kbId") UUID kbId);

    /** competency_questions.rs:80-106 create。 */
    int insertQuestion(@Param("id") UUID id, @Param("kbId") UUID kbId,
                       @Param("question") String question,
                       @Param("expectedAnswer") String expectedAnswer,
                       @Param("needs") JsonNode needs, @Param("origin") String origin,
                       @Param("status") String status, @Param("createdBy") UUID createdBy);

    /** competency_questions.rs:121-140 update：没给的不动；期望答案给空串是清掉。 */
    int updateQuestion(@Param("kbId") UUID kbId, @Param("id") UUID id,
                       @Param("question") String question,
                       @Param("expectedAnswer") String expectedAnswer,
                       @Param("needs") JsonNode needs);

    /** competency_questions.rs:162-171 set_status：认/拒/退役。 */
    int setQuestionStatus(@Param("kbId") UUID kbId, @Param("id") UUID id,
                          @Param("status") String status);

    /** competency_questions.rs:173-179 delete。 */
    int deleteQuestion(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** competency_questions.rs:182-193 record_result：什么时候、答成了什么。 */
    int recordQuestionResult(@Param("kbId") UUID kbId, @Param("id") UUID id,
                             @Param("result") JsonNode result);

    /** competency_questions.rs:196-202 exists_text：同一句话（不分大小写）在库里了吗。 */
    boolean questionTextExists(@Param("kbId") UUID kbId, @Param("question") String question);

    /** ontology_agent.rs:1267-1281 度数最高的实体（TopEntity 的素材）。 */
    record DegreeRow(String name, String klass, long degree) {
    }

    /** ontology_agent.rs:1267-1281 逐字（度数=活事实的端点数；名字序稳定）。 */
    List<DegreeRow> selectTopEntityDegrees(@Param("kbId") UUID kbId, @Param("limit") long limit);
}
