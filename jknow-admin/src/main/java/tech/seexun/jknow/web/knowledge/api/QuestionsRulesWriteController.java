package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.BusinessRulesService;
import tech.seexun.jknow.store.BusinessRulesService.ConditionInput;
import tech.seexun.jknow.store.BusinessRulesService.ConclusionInput;
import tech.seexun.jknow.store.CompetencyQuestionsService;
import tech.seexun.jknow.store.CompetencyQuestionsService.NewQuestion;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 竞争性问题与业务规则的写面（question_routes.rs:30-242 + rule_routes.rs:70-246 逐语义
 * ——M5 任务 6 切流演练落位：此前剧本 target:rust 留在 Rust 侧，演练翻出）。store 真身
 * M3/M5 已在（CompetencyQuestionsService/BusinessRulesService），本控制器接 HTTP 路由。
 *
 * <p>注记：Rust create/update 前有 rule_expression_input::compile_inputs（表达式型结论的人话
 * 编译）——Java 侧 ConclusionInput.expr 原样透传（M3 移植口径），剧本与前端表单走结构化
 * 形状不经该段；记录在台账。
 */
@RestController
public class QuestionsRulesWriteController {

    private final KbAccess rbac;
    private final CompetencyQuestionsService questions;
    private final BusinessRulesService rules;
    private final JobsService jobs;
    private final TypedMaterializeService materialize;
    private final AuditService audit;
    private final AppEventBus events;

    public QuestionsRulesWriteController(KbAccess rbac, CompetencyQuestionsService questions,
                                         BusinessRulesService rules, JobsService jobs,
                                         TypedMaterializeService materialize, AuditService audit,
                                         AppEventBus events) {
        this.rbac = rbac;
        this.questions = questions;
        this.rules = rules;
        this.jobs = jobs;
        this.materialize = materialize;
        this.audit = audit;
        this.events = events;
    }

    // ---- 竞争性问题（question_routes.rs） ----

    /** question_routes.rs:37-67 create：人提的问题一落地就是 accepted——它就是人对这个库的要求。 */
    @PostMapping("/api/v1/kbs/{kbId}/questions")
    public ObjectNode create(@PathVariable UUID kbId, @RequestBody JsonNode body,
                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String text = body.path("question").asText("").trim();
        if (text.isEmpty()) {
            throw AppException.invalid("question_required", "question is required");
        }
        String expected = body.path("expected_answer").asText("").trim();
        UUID id = questions.create(kbId, new NewQuestion(text,
                expected.isEmpty() ? null : expected,
                body.has("needs") && !body.get("needs").isNull() ? body.get("needs") : null,
                "person", "accepted", user.id()));
        audit.record(client(request), kbId, user.id(), "competency_question.created",
                "competency_question", id, "{\"question\": \"" + text + "\"}");
        ObjectNode out = mapper().createObjectNode();
        out.put("id", id.toString());
        return out;
    }

    /** question_routes.rs:92-119 update：字段级可选（serde Option 语义——JsonNode 显式取键）。 */
    @PatchMapping("/api/v1/kbs/{kbId}/questions/{id}")
    public ObjectNode update(@PathVariable UUID kbId, @PathVariable UUID id,
                             @RequestBody JsonNode body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String question = body.hasNonNull("question") ? body.get("question").asText() : null;
        if (question != null && question.trim().isEmpty()) {
            throw AppException.invalid("question_required", "question is required");
        }
        String expected = body.hasNonNull("expected_answer")
                ? body.get("expected_answer").asText().trim() : null;
        JsonNode needs = body.hasNonNull("needs") ? body.get("needs") : null;
        if (question != null || body.hasNonNull("expected_answer") || needs != null) {
            questions.update(kbId, id, question == null ? null : question.trim(),
                    expected, needs);
        }
        if (body.hasNonNull("status")) {
            questions.setStatus(kbId, id, body.get("status").asText());
        }
        return ok();
    }

    /** question_routes.rs:121-128 delete。 */
    @DeleteMapping("/api/v1/kbs/{kbId}/questions/{id}")
    public ObjectNode delete(@PathVariable UUID kbId, @PathVariable UUID id,
                             HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.EDITOR);
        questions.delete(kbId, id);
        return ok();
    }

    /** question_routes.rs:132-142 propose_questions：排一份任务，结果是 proposed 的问题等人接受。 */
    @PostMapping("/api/v1/kbs/{kbId}/questions/propose")
    public ObjectNode proposeQuestions(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.EDITOR);
        ObjectNode payload = mapper().createObjectNode();
        payload.put("kb_id", kbId.toString());
        Long queued = jobs.enqueueUnlessQueued("propose_questions", payload);
        ObjectNode out = mapper().createObjectNode();
        out.put("queued", queued != null);
        return out;
    }

    /** question_routes.rs:227-242 propose（本体提案）：glossary=full 可选放大词表。 */
    @PostMapping("/api/v1/kbs/{kbId}/ontology/propose")
    public ObjectNode proposeOntology(@PathVariable UUID kbId, @RequestBody JsonNode body,
                                      HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.EDITOR);
        ObjectNode payload = mapper().createObjectNode();
        payload.put("kb_id", kbId.toString());
        if ("full".equals(body.path("glossary").asText(null))) {
            payload.put("glossary", "full");
        }
        Long queued = jobs.enqueueUnlessQueued("propose_ontology", payload);
        ObjectNode out = mapper().createObjectNode();
        out.put("queued", queued != null);
        return out;
    }

    // ---- 业务规则（rule_routes.rs） ----

    /** rule_routes.rs:77-117 create。 */
    @PostMapping("/api/v1/kbs/{kbId}/rules")
    public ObjectNode createRule(@PathVariable UUID kbId, @RequestBody JsonNode body,
                                 HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ConclusionInput c = conclusion(body);
        UUID id = rules.create(kbId, body.path("name").asText(null),
                body.path("description").asText(null),
                body.hasNonNull("subject_type_id")
                        ? UUID.fromString(body.get("subject_type_id").asText()) : null,
                c.kind(), c.typeId(), c.predicateId(), c.value(), c.expr(), c.joinPredicateId(),
                conditions(body));
        audit.record(client(request), kbId, user.id(), "rule.created", "attribute_rule", id,
                "{\"name\": \"" + body.path("name").asText("") + "\", \"conclusion\": \""
                        + body.path("conclusion").asText("") + "\"}");
        ObjectNode out = mapper().createObjectNode();
        out.put("id", id.toString());
        return out;
    }

    /** rule_routes.rs:119-167 update：字段级可选；conditions 整份替换。 */
    @PatchMapping("/api/v1/kbs/{kbId}/rules/{ruleId}")
    public ObjectNode updateRule(@PathVariable UUID kbId, @PathVariable UUID ruleId,
                                 @RequestBody JsonNode body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ConclusionInput conclusion = body.hasNonNull("conclusion") ? conclusion(body) : null;
        rules.update(kbId, ruleId,
                body.hasNonNull("name") ? body.get("name").asText() : null,
                body.hasNonNull("description") ? body.get("description").asText() : null,
                body.hasNonNull("enabled") ? body.get("enabled").asBoolean() : null,
                body.has("conditions") ? conditions(body) : null, conclusion);
        audit.record(client(request), kbId, user.id(), "rule.updated", "attribute_rule", ruleId,
                "{\"enabled\": " + (body.hasNonNull("enabled") ? body.get("enabled").asBoolean()
                        : null) + ", \"conditions_replaced\": " + body.has("conditions") + "}");
        return ok();
    }

    /** rule_routes.rs:169-188 delete：派生行随 CASCADE 走。 */
    @DeleteMapping("/api/v1/kbs/{kbId}/rules/{ruleId}")
    public ObjectNode deleteRule(@PathVariable UUID kbId, @PathVariable UUID ruleId,
                                 HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        rules.delete(kbId, ruleId);
        audit.record(client(request), kbId, user.id(), "rule.deleted", "attribute_rule", ruleId,
                "{}");
        return ok();
    }

    /**
     * rule_routes.rs:227-246 run_now：不等下一个物化周期——写规则的人想立刻看见它推出了什么。
     * 走的是同一个 materialize（预览与正式是同一条路）。Java 的 Outcome 不分规则子计数
     * （rules/rounds 族字段缺省 0——台账注记）；单侧步，形状差异不进双打。
     */
    @PostMapping("/api/v1/kbs/{kbId}/rules/run")
    public ObjectNode runRules(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.EDITOR);
        TypedMaterializeService.Outcome report = materialize.materialize(kbId);
        events.emitGraph(kbId);
        ObjectNode out = mapper().createObjectNode();
        out.put("rules", 0);
        out.put("hits", 0);
        out.put("capped", false);
        out.put("inserted", report.added());
        out.put("invalidated", report.retired());
        out.put("rounds", 0);
        out.put("rounds_capped", false);
        return out;
    }

    // ---- 共用 ----

    private static ConclusionInput conclusion(JsonNode body) {
        JsonNode expr = body.hasNonNull("conclude_expr") ? body.get("conclude_expr") : null;
        return new ConclusionInput(body.path("conclusion").asText(null),
                body.hasNonNull("conclude_type_id")
                        ? UUID.fromString(body.get("conclude_type_id").asText()) : null,
                body.hasNonNull("conclude_predicate_id")
                        ? UUID.fromString(body.get("conclude_predicate_id").asText()) : null,
                body.hasNonNull("conclude_value") ? body.get("conclude_value") : null, expr,
                body.hasNonNull("join_predicate_id")
                        ? UUID.fromString(body.get("join_predicate_id").asText()) : null);
    }

    private static List<ConditionInput> conditions(JsonNode body) {
        List<ConditionInput> out = new ArrayList<>();
        ArrayNode arr = body.has("conditions") && body.get("conditions").isArray()
                ? (ArrayNode) body.get("conditions") : mapper().createArrayNode();
        for (JsonNode c : arr) {
            out.add(ConditionInput.of(c.path("group").asInt(0),
                    UUID.fromString(c.path("predicate_id").asText()),
                    c.path("op").asText(null),
                    c.hasNonNull("operand") ? c.get("operand") : null,
                    c.path("side").asText(null)));
        }
        return out;
    }

    private static ObjectNode ok() {
        ObjectNode out = mapper().createObjectNode();
        out.put("ok", true);
        return out;
    }

    private static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return MapperHolder.MAPPER;
    }

    private static final class MapperHolder {

        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }
}
