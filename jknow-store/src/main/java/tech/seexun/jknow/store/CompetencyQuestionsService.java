package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.QuestionsMapper;
import tech.seexun.jknow.store.mapper.QuestionsMapper.QuestionRow;

/**
 * 能力问题域的服务编排（competency_questions.rs 全量对等；读面 list/report 自 M4 任务 6
 * 起由 {@link QuestionsMapper} 直接服务，本类补写面与 accepted/exists_text——本体代理
 * propose_questions 的生产侧、采纳链、以及 0061 决定 5 的问过记账）。
 */
@Service
public class CompetencyQuestionsService {

    private final QuestionsMapper questions;

    public CompetencyQuestionsService(QuestionsMapper questions) {
        this.questions = questions;
    }

    /** competency_questions.rs:49-55 list。 */
    public List<QuestionRow> list(UUID kbId) {
        return questions.selectQuestions(kbId);
    }

    /** competency_questions.rs:59-66 accepted。 */
    public List<QuestionRow> accepted(UUID kbId) {
        return questions.selectAcceptedQuestions(kbId);
    }

    /** competency_questions.rs:69-77 NewQuestion。 */
    public record NewQuestion(String question, String expectedAnswer, JsonNode needs,
                              String origin, String status, UUID createdBy) {
    }

    /** competency_questions.rs:80-106 create（含 validate：空/超长问题拒绝）。 */
    public UUID create(UUID kbId, NewQuestion q) {
        String question = validate(q.question());
        if (!("person".equals(q.origin()) || "agent".equals(q.origin()))
                || !("accepted".equals(q.status()) || "proposed".equals(q.status()))) {
            throw AppException.invalid("bad_question",
                    "origin is person or agent; status is accepted or proposed");
        }
        UUID id = Uuids.nowV7();
        questions.insertQuestion(id, kbId, question, trimToNull(q.expectedAnswer()), q.needs(),
                q.origin(), q.status(), q.createdBy());
        return id;
    }

    /** competency_questions.rs:33-47 validate：去首尾空白、非空、≤2000 字符（码点口径）。 */
    private static String validate(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            throw AppException.invalid("empty_question", "A question needs words.");
        }
        if (q.codePoints().count() > 2000) {
            throw AppException.invalid("long_question", "A question is at most 2000 characters.");
        }
        return q;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** competency_questions.rs:108-140 update：没给的不动；期望答案空串是清掉。 */
    public void update(UUID kbId, UUID id, String question, String expectedAnswer, JsonNode needs) {
        String q = question == null ? null : validate(question);
        int n = questions.updateQuestion(kbId, id, q, expectedAnswer, needs);
        if (n == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /** competency_questions.rs:142-171 set_status：认（accepted）/拒（rejected）/退役（retired）。 */
    public void setStatus(UUID kbId, UUID id, String status) {
        if (!("accepted".equals(status) || "rejected".equals(status)
                || "retired".equals(status))) {
            throw AppException.invalid("bad_status",
                    "status is accepted, rejected or retired");
        }
        int n = questions.setQuestionStatus(kbId, id, status);
        if (n == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /** competency_questions.rs:173-179 delete。 */
    public void delete(UUID kbId, UUID id) {
        if (questions.deleteQuestion(kbId, id) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /** competency_questions.rs:182-193 record_result：返回那条还在不在。 */
    public boolean recordResult(UUID kbId, UUID id, JsonNode result) {
        return questions.recordQuestionResult(kbId, id, result) > 0;
    }

    /** competency_questions.rs:196-202 exists_text：同一句话（不分大小写）在库里了吗。 */
    public boolean existsText(UUID kbId, String question) {
        return questions.questionTextExists(kbId, question);
    }
}
