package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.BusinessRulesService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.mapper.OntologyMapper;
import tech.seexun.jknow.store.mapper.QuestionsMapper;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.TemporalService.HolderExample;
import tech.seexun.jknow.store.temporal.TemporalService.UniquenessCandidate;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.temporal.TemporalMath.OpenValue;

/**
 * 本体扩展读族九端点（M4 任务 6——核销 ontology 剧本 9 挂账步；对照旧仓库
 * ontology_routes.rs / question_routes.rs / rule_routes.rs 的 GET 面，权限/响应/分页逐字）：
 * <ol>
 *   <li>GET /kbs/{id}/ontology/uniqueness —— uniqueness_candidates 聚合（#341「声明来晚了」：
 *       谁的哪一端挂着两个以上开放值；Viewer；{@code {"candidates":[…]}}，side→axiom 折
 *       functional/inverse_functional）；</li>
 *   <li>GET /kbs/{id}/ontology/proposals —— 存下的待看提案（只摆 agent 提的——旧的
 *       Suggest 路已退役，老库遗留提案摆出来只是一排点不动的按钮；四节空数组起步）；</li>
 *   <li>GET /kbs/{id}/questions —— 能力问题列表（0061：本体由问题验收；新的在前）；</li>
 *   <li>GET /kbs/{id}/questions/report —— 问题命中报告（两个数 + uncovered + 提案采纳率）；</li>
 *   <li>GET /kbs/{id}/ontology/imports —— OWL 导入历史（近 50 条，带导入人显示名）；</li>
 *   <li>GET /kbs/{id}/rules —— 业务规则列表（含条件与「现在推出了多少条」）；</li>
 *   <li>GET /kbs/{id}/rules/{rid}/matches —— 规则命中分页（per 缺省 20 钳 [1,100]）；</li>
 *   <li>GET /kbs/{id}/rules/{rid}/versions —— 规则定义史（0060）。unknown rule 404。</li>
 * </ol>
 * 读族全部 Viewer 起步（rules 0021：读规则要 Viewer，写要 Editor——写面归问题/规则域）。
 */
@RestController
public class OntologyReadFamilyController {

    private final KbAccess rbac;
    private final TemporalService temporal;
    private final OntologyMapper ontology;
    private final OntologyService ontologyService;
    private final QuestionsMapper questions;
    private final BusinessRulesService rules;
    private final ObjectMapper mapper;

    public OntologyReadFamilyController(KbAccess rbac, TemporalService temporal,
                                        OntologyMapper ontology, OntologyService ontologyService,
                                        QuestionsMapper questions, BusinessRulesService rules,
                                        ObjectMapper mapper) {
        this.rbac = rbac;
        this.temporal = temporal;
        this.ontology = ontology;
        this.ontologyService = ontologyService;
        this.questions = questions;
        this.rules = rules;
        this.mapper = mapper;
    }

    // ---- GET /api/v1/kbs/{id}/ontology/uniqueness（ontology_routes.rs:441-479） ----

    /**
     * uniqueness_candidates 聚合（ontology_routes.rs:446-476 的 json! 形状逐键）：
     * declared=true 的行是声明了却还没对过账的（导入的、声明之前就在的）。证据摆出来
     * 人决定——引擎永不自动推断 functional。
     */
    @GetMapping("/api/v1/kbs/{kbId}/ontology/uniqueness")
    public JsonNode uniqueness(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        ArrayNode items = mapper.createArrayNode();
        for (UniquenessCandidate c : temporal.uniquenessCandidates(kbId)) {
            ObjectNode o = items.addObject();
            o.put("predicate_id", c.predicateId().toString());
            o.put("key", c.key());
            o.put("label", c.label());
            o.put("kind", c.kind());
            o.put("side", c.side());
            o.put("axiom", "subject".equals(c.side()) ? "functional" : "inverse_functional");
            o.put("declared", c.declared());
            o.put("holders", c.holders());
            o.put("open_facts", c.openFacts());
            o.put("would_close", c.wouldClose());
            o.put("would_review", c.wouldReview());
            ArrayNode examples = o.putArray("examples");
            for (HolderExample e : c.examples()) {
                ObjectNode ex = examples.addObject();
                ex.put("holder", e.holder());
                ArrayNode values = ex.putArray("values");
                for (OpenValue v : e.values()) {
                    ObjectNode vn = values.addObject();
                    vn.put("fact_id", v.factId().toString());
                    vn.put("name", v.name());
                    if (v.validFrom() != null) {
                        vn.put("valid_from", v.validFrom().toString());
                    } else {
                        vn.putNull("valid_from");
                    }
                    vn.put("confidence", v.confidence());
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("candidates", items);
        return out;
    }

    // ---- GET /api/v1/kbs/{id}/ontology/proposals（ontology_routes.rs:510-544） ----

    /**
     * 还等着人看的提案，按接口原来的形状拼回去（前端不必区分「刚算出来的」与
     * 「上次存下的」）。四节起步全空；只摆 agent 提的，每条多带三格：
     * proposed_by/serves/signatures。
     */
    @GetMapping("/api/v1/kbs/{kbId}/ontology/proposals")
    public JsonNode storedProposals(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        ObjectNode out = mapper.createObjectNode();
        out.putArray("entity_types");
        out.putArray("relation_types");
        out.putArray("attribute_types");
        out.putArray("map_to");
        for (OntologyMapper.StoredProposalRow p : ontology.selectOpenProposals(kbId)) {
            // 只给代理提的（0061）；旧的 Suggest 那条路已退役
            if (!"agent".equals(p.proposedBy())) {
                continue;
            }
            JsonNode section = out.get(p.section());
            if (section == null || !section.isArray()) {
                continue;
            }
            // payload 原样入列；三格只在它是 object 时补（Rust as_object_mut 的 Option 语义）
            if (p.payload() != null && p.payload().isObject()) {
                ObjectNode item = ((ArrayNode) section).addObject();
                item.setAll((ObjectNode) p.payload());
                item.put("proposed_by", "agent");
                ArrayNode serves = item.putArray("serves");
                for (UUID id : p.serves()) {
                    serves.add(id.toString());
                }
                item.set("signatures", p.signatures());
            } else {
                ((ArrayNode) section).add(p.payload());
            }
        }
        return out;
    }

    // ---- GET /api/v1/kbs/{id}/questions（question_routes.rs:18-24） ----

    @GetMapping("/api/v1/kbs/{kbId}/questions")
    public List<QuestionsMapper.QuestionRow> listQuestions(@PathVariable UUID kbId,
                                                           HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return questions.selectQuestions(kbId);
    }

    // ---- GET /api/v1/kbs/{id}/questions/report（question_routes.rs:193-217） ----

    /**
     * 两个数（0061 决定 5）：问题答对了几条；代理的提案人改过或拒掉的占几成。另带
     * uncovered（文档说了本体还放不下的类别词/短语形状各有多少——工作台靠它说还缺什么）。
     * changed_share 在 decided=0 时为 null（Rust Option::None）。
     */
    @GetMapping("/api/v1/kbs/{kbId}/questions/report")
    public JsonNode questionReport(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        QuestionsMapper.QuestionReportRow q = questions.selectQuestionReport(kbId);
        OntologyMapper.AgentProposalReportRow p = ontology.selectAgentProposalReport(kbId);
        OntologyMapper.Uncovered uncovered = ontologyService.uncovered(kbId);
        long decided = p.adopted() + p.rejected();
        long changed = p.adoptedEdited() + p.rejected();
        ObjectNode out = mapper.createObjectNode();
        ObjectNode qn = out.putObject("questions");
        qn.put("accepted", q.accepted());
        qn.put("proposed", q.proposed());
        qn.put("checked", q.checked());
        qn.put("answered", q.answered());
        qn.put("answered_with_graph", q.answeredWithGraph());
        qn.put("answered_graph_only", q.answeredGraphOnly());
        ObjectNode un = out.putObject("uncovered");
        un.put("kind_words", uncovered.kindWords());
        un.put("phrases", uncovered.phrases());
        ObjectNode pn = out.putObject("proposals");
        pn.put("open", p.open());
        pn.put("adopted", p.adopted());
        pn.put("adopted_edited", p.adoptedEdited());
        pn.put("rejected", p.rejected());
        pn.put("decided", decided);
        pn.put("changed", changed);
        if (decided > 0) {
            pn.put("changed_share", (double) changed / decided);
        } else {
            pn.putNull("changed_share");
        }
        return out;
    }

    // ---- GET /api/v1/kbs/{id}/ontology/imports（ontology_routes.rs:661-669） ----

    record ImportsResponse(List<OntologyMapper.ImportViewRow> imports) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/ontology/imports")
    public ImportsResponse listImports(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return new ImportsResponse(ontology.selectOntologyImports(kbId));
    }

    // ---- GET /api/v1/kbs/{id}/rules（rule_routes.rs:67-75） ----

    record RulesResponse(List<ObjectNode> rules) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/rules")
    public RulesResponse listRules(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return new RulesResponse(rules.list(kbId));
    }

    // ---- GET /api/v1/kbs/{id}/rules/{rid}/matches（rule_routes.rs:190-203） ----

    /** rule_routes.rs:201-204 MatchQuery：per 缺省 20 钳 [1,100]；page 缺省 0 不为负。 */
    record MatchesResponse(List<ObjectNode> matches, long total) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/rules/{ruleId}/matches")
    public MatchesResponse ruleMatches(@PathVariable UUID kbId, @PathVariable UUID ruleId,
                                       @RequestParam(required = false) Long page,
                                       @RequestParam(required = false) Long per,
                                       HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        long effPer = per == null ? 20 : Math.max(1, Math.min(100, per));
        long effPage = page == null ? 0 : Math.max(0, page);
        BusinessRulesService.Matches m = rules.matches(kbId, ruleId, effPer, effPage * effPer);
        return new MatchesResponse(m.rows(), m.total());
    }

    // ---- GET /api/v1/kbs/{id}/rules/{rid}/versions（rule_routes.rs:205-215） ----

    record VersionsResponse(List<ObjectNode> versions) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/rules/{ruleId}/versions")
    public VersionsResponse ruleVersions(@PathVariable UUID kbId, @PathVariable UUID ruleId,
                                         HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return new VersionsResponse(rules.versions(kbId, ruleId));
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            // SessionFilter 已保证受保护路由必有用户；此处兜底（不可达路径）
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
