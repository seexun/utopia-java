package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.web.knowledge.jobs.ProposeOntologyJob;
import tech.seexun.jknow.web.knowledge.jobs.ResolveTypesJob;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.OntologyService.RelationAxioms;
import tech.seexun.jknow.store.Palette;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 本体端点（对齐旧仓库 ontology_routes.rs 逐 handler；M1 任务 4 实现两个 GET——
 * 两视图（ontology_routes.rs:25-39 get）与实例列表（54-67 list_entity_instances）；
 * M3 任务 10 补<b>编辑器的四个写入口</b>（create/update entity-type :128-217、
 * create/update relation-type :319-410——reindex_then_align 钩子随 embed_ontology job
 * 真身一并落地）。其余 ontology_routes handler（uniqueness、提案、导入、类型消解等
 * 写路径与扩展读面）按分期归 M4/M5，未实现路径落 404 {"error":"Not found"}。
 *
 * <p>查看 = viewer（本体直接影响后续抽取的白名单，ontology_routes.rs 头注）；编辑 =
 * Editor。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:ontology:list')")
public class OntologyController {

    private final KbAccess rbac;
    private final OntologyService ontology;
    private final JobsService jobs;
    private final AuditService audit;
    private final AppEventBus events;
    private final TemporalService temporal;
    private final ProposeOntologyJob proposeOntology;
    private final ResolveTypesJob resolveTypes;
    private final ResolutionService resolution;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OntologyController(KbAccess rbac, OntologyService ontology, JobsService jobs,
                              AuditService audit, AppEventBus events, TemporalService temporal,
                              ProposeOntologyJob proposeOntology,
                              ResolveTypesJob resolveTypes,
                              ResolutionService resolution) {
        this.rbac = rbac;
        this.ontology = ontology;
        this.jobs = jobs;
        this.audit = audit;
        this.events = events;
        this.temporal = temporal;
        this.proposeOntology = proposeOntology;
        this.resolveTypes = resolveTypes;
        this.resolution = resolution;
    }

    /**
     * ontology_routes.rs:483-507 reconcile_relation_type（M5 任务 6 落位）：补上声明之后，
     * 把这条谓词已经在账上的开放行对一遍（#341）。与落库时同一条路：作废+改写，
     * supersedes 链回旧行；拿不准的进人审。谓词没有唯一性声明时拒绝——声明是人的事。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/relation-types/{id}/reconcile")
    public java.util.Map<String, Object> reconcileRelationType(@PathVariable UUID kbId,
                                                               @PathVariable UUID id,
                                                               HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        TemporalService.ReconcileReport report = temporal.reconcilePredicate(kbId, id);
        audit.record(client(request), kbId, user.id(), "relation_type.reconciled",
                "relation_type", id,
                "{\"corrected\": " + report.corrected.size() + ", \"conflicts\": "
                        + report.conflicts + "}");
        return java.util.Map.of("corrected", report.corrected.size(), "conflicts",
                report.conflicts, "corrected_ids", report.corrected);
    }

    // ---- 写面收尾（M5 任务 6 切流演练落位：decide/adopt/delete/type-resolution） ----

    /**
     * ontology_routes.rs:541-578 decide_proposal：一条提案有人表态了。**改状态不删行**——
     * 采纳发生过、拒绝也发生过，而拒绝留痕正是下一轮代理不再把它刷回待看的依据。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/proposals")
    public java.util.Map<String, Object> decideProposal(@PathVariable UUID kbId,
                                                        @RequestBody JsonNode body,
                                                        HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String section = body.path("section").asText("");
        String key = body.path("key").asText("");
        String status = body.path("status").asText("");
        if (!"adopted".equals(status) && !"rejected".equals(status)) {
            throw AppException.invalid("bad_status", "status 只能是 adopted 或 rejected");
        }
        String reason = body.path("reason").asText("").trim();
        if ("rejected".equals(status) && !reason.isEmpty()) {
            ontology.rejectProposal(kbId, section, key, reason, user.id());
        } else {
            ontology.decideProposal(kbId, section, key, status, user.id());
        }
        return java.util.Map.of("ok", true);
    }

    /**
     * /ontology/proposals/adopt（mod.rs:384→question_routes adopt）：采纳一条提案（人改过
     * 的几格随请求来）：建元素、标记、排对齐。走 ProposeOntologyJob.adopt 真身（M5 任务 5）。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/proposals/adopt")
    public java.util.Map<String, Object> adoptProposal(@PathVariable UUID kbId,
                                                       @RequestBody JsonNode body,
                                                       HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ProposeOntologyJob.AdoptEdits edits = new ProposeOntologyJob.AdoptEdits(
                body.path("label").asText(null), body.path("description").asText(null),
                body.path("datatype").asText(null), stringList(body, "domains"),
                stringList(body, "ranges"), stringList(body, "parents"));
        UUID id = proposeOntology.adopt(kbId, body.path("section").asText(""),
                body.path("key").asText(""), edits, user.id());
        return java.util.Map.of("id", id.toString());
    }

    private static java.util.List<String> stringList(JsonNode body, String field) {
        if (!body.path(field).isArray()) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        body.path(field).forEach(v -> out.add(v.asText()));
        return out;
    }

    /** ontology_routes.rs delete_entity_type（store 真身 M3 在）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @DeleteMapping("/api/v1/kbs/{kbId}/ontology/entity-types/{id}")
    public java.util.Map<String, Object> deleteEntityType(@PathVariable UUID kbId,
                                                          @PathVariable UUID id,
                                                          HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ontology.deleteEntityType(kbId, id);
        audit.record(client(request), kbId, user.id(), "entity_type.deleted", "entity_type",
                id, "{}");
        return java.util.Map.of("ok", true);
    }

    /** ontology_routes.rs delete_relation_type（store 真身 M3 在）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @DeleteMapping("/api/v1/kbs/{kbId}/ontology/relation-types/{id}")
    public java.util.Map<String, Object> deleteRelationType(@PathVariable UUID kbId,
                                                            @PathVariable UUID id,
                                                            HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ontology.deleteRelationType(kbId, id);
        audit.record(client(request), kbId, user.id(), "relation_type.deleted", "relation_type",
                id, "{}");
        return java.util.Map.of("ok", true);
    }

    /** ontology_routes.rs:703-708 type_resolution_preview（ResolveTypesJob.preview 真身）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/type-resolution/preview")
    public java.util.Map<String, Object> typeResolutionPreview(@PathVariable UUID kbId,
                                                               HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.EDITOR);
        return java.util.Map.of("items", resolveTypes.preview(kbId));
    }

    /** ontology_routes.rs:717-736 type_resolution_apply：跑一遍并落库（三档处置）+ 审计。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/type-resolution")
    public java.util.Map<String, Object> typeResolutionApply(@PathVariable UUID kbId,
                                                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ResolveTypesJob.ResolutionOutcome outcome = resolveTypes.resolveWith(kbId, true);
        audit.record(client(request), kbId, user.id(), "ontology.types_resolved",
                "knowledge_base", kbId,
                "{\"retyped\": " + outcome.retyped() + ", \"for_review\": "
                        + outcome.forReview().size() + "}");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("retyped", outcome.retyped());
        out.put("for_review", outcome.forReview());
        out.put("left_alone", outcome.leftAlone());
        out.put("batch", outcome.batch() == null ? null : outcome.batch().toString());
        return out;
    }

    /**
     * ontology_routes.rs:771-817 approve_refinement：认可「粗类→细类」配对并把随请求
     * 带来的实体改过去。认可的是类对，改的是实体——两件事分开，调用方可以只认可
     * 规则而暂不动任何实体。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/type-resolution/approve")
    public java.util.Map<String, Object> approveRefinement(@PathVariable UUID kbId,
                                                           @RequestBody JsonNode body,
                                                           HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        UUID fromTypeId = UUID.fromString(body.path("from_type_id").asText());
        UUID toTypeId = UUID.fromString(body.path("to_type_id").asText());
        resolution.approveRefinement(kbId, fromTypeId, toTypeId, user.id());
        java.util.List<tech.seexun.jknow.store.ResolutionService.EntityRetype> picks =
                new java.util.ArrayList<>();
        for (JsonNode id : body.path("entity_ids")) {
            picks.add(new tech.seexun.jknow.store.ResolutionService.EntityRetype(
                    UUID.fromString(id.asText()), toTypeId));
        }
        UUID batch = null;
        int moved = 0;
        if (!picks.isEmpty()) {
            var outcome = resolution.retypeEntities(kbId, picks, user.id());
            batch = outcome.batchId();
            moved = outcome.moved();
        }
        audit.record(client(request), kbId, user.id(), "ontology.refinement_approved",
                "entity_type", toTypeId,
                "{\"from\": \"" + fromTypeId + "\", \"to\": \"" + toTypeId
                        + "\", \"moved\": " + moved + "}");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("moved", moved);
        out.put("batch", batch == null ? null : batch.toString());
        return out;
    }

    /** ontology_routes.rs:747-765 type_resolution_undo：把那一批实体放回原来的类。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @DeleteMapping("/api/v1/kbs/{kbId}/ontology/type-resolution/{batchId}")
    public java.util.Map<String, Object> typeResolutionUndo(@PathVariable UUID kbId,
                                                            @PathVariable UUID batchId,
                                                            HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        int reverted = resolution.unadoptTypes(kbId, batchId);
        audit.record(client(request), kbId, user.id(), "ontology.types_resolution_reverted",
                "knowledge_base", kbId,
                "{\"batch\": \"" + batchId + "\", \"reverted\": " + reverted + "}");
        return java.util.Map.of("reverted", reverted);
    }

    // ---- GET /api/v1/kbs/{kbId}/ontology（ontology_routes.rs:25-39 + 参照实例契约） ----

    /**
     * 四键：entity_types/relation_types 两视图 + misses/dismissed_misses 抽取未匹配。
     * 后两键是**参照实例二进制（rc9）的实测契约**——旧仓库 main 的 get 已只剩两视图
     * （源比二进制新，与 regressions 路由缺失同一批 rc9 漂移，方向相反）；契约权威是
     * 参照实例（M0 纪律），形态考古记录在 OntologyMapper.xml 头注与 task-4 报告。
     */
    public record OntologyResponse(Object entityTypes, Object relationTypes,
                                   Object misses, Object dismissedMisses) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/ontology")
    public OntologyResponse get(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        // Rust 侧两视图 try_join 并行；此处顺序执行（只读无依赖，结果等价）
        OntologyService.Misses m = ontology.misses(kbId);
        return new OntologyResponse(ontology.entityTypes(kbId), ontology.relationTypes(kbId),
                m.misses(), m.dismissedMisses());
    }

    // ---- GET /api/v1/kbs/{kbId}/ontology/entity-types/{typeId}/entities ----
    // （ontology_routes.rs:54-67 list_entity_instances）

    public record InstancesResponse(Object entities, long total) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/ontology/entity-types/{typeId}/entities")
    public InstancesResponse instances(@PathVariable UUID kbId, @PathVariable UUID typeId,
                                       @RequestParam(required = false) Long page,
                                       @RequestParam(required = false) Long per,
                                       HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        long effectivePer = per == null ? 12 : Math.max(1, Math.min(100, per));
        long effectivePage = page == null ? 0 : Math.max(0, page);
        OntologyService.Instances result =
                ontology.entityInstances(kbId, typeId, effectivePer, effectivePage * effectivePer);
        return new InstancesResponse(result.entities(), result.total());
    }

    // ---- 编辑器写入口（ontology_routes.rs:128-217 / :319-410；M3 任务 10） ----

    /**
     * ontology_routes.rs:105-126 reindex_then_align：本体编辑之后「先补向量、再对齐」。
     * <b>新元素要有向量，对齐的短名单才看得见它</b>——候选多于短名单长度时按向量挑，
     * 没向量的元素永远进不了名单，形状的指纹就不变，建了也不重判。描述改了也算：向量按
     * 「当时嵌的原文」判陈，改了描述的行会被同一个任务重嵌。
     *
     * <p><b>排任务，不就地跑</b>：就地嵌一次要付一趟嵌入请求的固定开销，本体页一口气建
     * 28 条属性就是 28 趟；排任务的话一批编辑只嵌一次。挡的只是排着的
     * （enqueue_unless_queued_after），不挡在跑的：在跑的那份已经读完待嵌集合，这条编辑
     * 它看不见，得再排一份。去抖比对齐短，对齐任务开跑前还会看一眼这个任务有没有排着
     * 或跑着，排着就等。
     */
    void reindexThenAlign(UUID kbId, String alignKind) {
        ObjectNode embed = mapper.createObjectNode();
        embed.put("kb_id", kbId.toString());
        jobs.enqueueUnlessQueuedAfter("embed_ontology", embed, Duration.ofSeconds(2));
        ObjectNode align = mapper.createObjectNode();
        align.put("kb_id", kbId.toString());
        // 去抖：一批编辑（导一个包、建一串属性）只排一次
        jobs.enqueueUnlessPending(alignKind, align, Duration.ofSeconds(5));
    }

    /** POST /entity-types（ontology_routes.rs:128-175 create_entity_type）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/entity-types")
    public JsonNode createEntityType(@PathVariable UUID kbId, @RequestBody JsonNode body,
                                     HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        return createEntityType(user, kbId, body);
    }

    /** PATCH /entity-types/{typeId}（ontology_routes.rs:177-217 update_entity_type——Rust 路由是 patch，任务 10 曾误落 PUT，任务 11 契约对齐修正）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PatchMapping("/api/v1/kbs/{kbId}/ontology/entity-types/{typeId}")
    public JsonNode updateEntityType(@PathVariable UUID kbId, @PathVariable UUID typeId,
                                     @RequestBody JsonNode body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        return updateEntityType(user, kbId, typeId, body);
    }

    /** POST /relation-types（ontology_routes.rs:319-365 create_relation_type）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ontology/relation-types")
    public JsonNode createRelationType(@PathVariable UUID kbId, @RequestBody JsonNode body,
                                       HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        return createRelationType(user, kbId, body);
    }

    /** PATCH /relation-types/{typeId}（ontology_routes.rs:367-410 update_relation_type——同上，任务 11 契约对齐修正）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ontology:edit')")
    @PatchMapping("/api/v1/kbs/{kbId}/ontology/relation-types/{typeId}")
    public JsonNode updateRelationType(@PathVariable UUID kbId, @PathVariable UUID typeId,
                                       @RequestBody JsonNode body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        return updateRelationType(user, kbId, typeId, body);
    }

    /** create_entity_type 主体（RBAC 之外；phrase_alignment 第 19 例直调此层）。 */
    public ObjectNode createEntityType(UserRow user, UUID kbId, JsonNode req) {
        String key = optText(req, "key");
        if (key == null || key.isEmpty()) {
            throw AppException.invalid("key_required", "key is required");
        }
        String label = requiredText(req, "label");
        String description = optText(req, "description");
        List<UUID> parents = uuidList(req.get("parents"));
        // 不给颜色就按 key 取一个，而不是所有类共用一个灰蓝
        String color = optText(req, "color");
        if (color == null) {
            color = Palette.colorForKey(key);
        }
        String shape = optText(req, "shape");
        UUID id = ontology.createEntityType(kbId, key, label, color,
                shape == null ? "circle" : shape, parents,
                description == null ? "" : description);
        // 互斥缺省 = 不动：不管它的调用方（提案采纳、冷启动）不该因为建一个类
        // 就把已有的声明清空
        List<UUID> disjoint = uuidListOrNull(req.get("disjoint"));
        if (disjoint != null) {
            ontology.setDisjointFor(kbId, id, disjoint);
        }
        // 审计只记不阻断（ontology_routes.rs:161-170）
        ObjectNode detail = mapper.createObjectNode();
        detail.put("key", key);
        detail.put("label", label);
        audit.record(null, kbId, user.id(), "entity_type.created", "entity_type", id,
                detail.toString());
        // 本体多了一个类：类别词的绑定里那些「没有」和「没定」的要重判（0044 对齐第一片）。
        // 先补它的向量，候选类的检索才找得到它
        reindexThenAlign(kbId, "align_types");
        ObjectNode out = mapper.createObjectNode();
        out.put("id", id.toString());
        return out;
    }

    /** update_entity_type 主体（color 缺省 = 保持原色，不是重置）。 */
    public ObjectNode updateEntityType(UserRow user, UUID kbId, UUID id, JsonNode req) {
        String label = requiredText(req, "label");
        String color = optText(req, "color");
        String shape = optText(req, "shape");
        String description = optText(req, "description");
        ontology.updateEntityType(kbId, id, label, color, shape == null ? "circle" : shape,
                uuidList(req.get("parents")), description == null ? "" : description);
        List<UUID> disjoint = uuidListOrNull(req.get("disjoint"));
        if (disjoint != null) {
            ontology.setDisjointFor(kbId, id, disjoint);
        }
        ObjectNode detail = mapper.createObjectNode();
        detail.put("label", label);
        if (color != null) {
            detail.put("color", color);
        }
        if (req.hasNonNull("shape")) {
            detail.put("shape", req.get("shape").asText());
        }
        if (description != null) {
            detail.put("description", description);
        }
        audit.record(null, kbId, user.id(), "entity_type.updated", "entity_type", id,
                detail.toString());
        // 类的定义改了：绑到它的类别词过期，重判。标签或描述改了向量也陈了，先重嵌
        reindexThenAlign(kbId, "align_types");
        // 对账可能在同一事务里收/开过违规行——审核队列变了，说一声（0062）
        events.emitReview(kbId);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /** create_relation_type 主体。 */
    public ObjectNode createRelationType(UserRow user, UUID kbId, JsonNode req) {
        String key = optText(req, "key");
        if (key == null || key.isEmpty()) {
            throw AppException.invalid("key_required", "key is required");
        }
        String label = requiredText(req, "label");
        String temporal = optText(req, "temporal");
        RelationAxioms ax = axiomsOf(req);
        String description = optText(req, "description");
        String kind = optText(req, "kind");
        List<UUID> domains = uuidListOrNull(req.get("domains"));
        List<UUID> ranges = uuidListOrNull(req.get("ranges"));
        String datatype = optText(req, "datatype");
        String unit = optText(req, "unit");
        if (unit != null && unit.isEmpty()) {
            unit = null;
        }
        UUID id = ontology.createRelationType(kbId, key, label,
                temporal == null ? "state" : temporal, ax,
                description == null ? "" : description,
                kind == null ? "relation" : kind,
                domains == null ? List.of() : domains,
                ranges == null ? List.of() : ranges, datatype, unit);
        List<UUID> qualifiers = uuidListOrNull(req.get("qualifiers"));
        if (qualifiers != null) {
            ontology.setRelationQualifiers(kbId, id, qualifiers);
        }
        // 多了一个属性：判成 none / undecided 的签名也许对得上了（0044 对齐第二片）。
        // 先补它的向量，短名单才看得见它
        reindexThenAlign(kbId, "align_phrases");
        ObjectNode detail = mapper.createObjectNode();
        detail.put("key", key);
        detail.put("label", label);
        detail.put("temporal", temporal == null ? "state" : temporal);
        detail.put("kind", kind == null ? "relation" : kind);
        audit.record(null, kbId, user.id(), "relation_type.created", "relation_type", id,
                detail.toString());
        ObjectNode out = mapper.createObjectNode();
        out.put("id", id.toString());
        return out;
    }

    /** update_relation_type 主体（domains/ranges 整组语义：两侧都缺 = 签名不动）。 */
    public ObjectNode updateRelationType(UserRow user, UUID kbId, UUID id, JsonNode req) {
        String label = requiredText(req, "label");
        String temporal = optText(req, "temporal");
        RelationAxioms ax = axiomsOf(req);
        String description = optText(req, "description");
        String datatype = optText(req, "datatype");
        String unit = optText(req, "unit");
        if (unit != null && unit.isEmpty()) {
            unit = null;
        }
        List<UUID> domains = uuidListOrNull(req.get("domains"));
        List<UUID> ranges = uuidListOrNull(req.get("ranges"));
        ontology.updateRelationType(kbId, id, label, temporal == null ? "state" : temporal, ax,
                description == null ? "" : description, datatype, unit, domains, ranges);
        List<UUID> qualifiers = uuidListOrNull(req.get("qualifiers"));
        if (qualifiers != null) {
            ontology.setRelationQualifiers(kbId, id, qualifiers);
        }
        // 属性改了定义或域/值域：绑到它的签名过期，判成 none 的也许对得上了。
        // 标签或描述改了向量也陈了，先重嵌（只改域/值域的话补齐任务一查就退）
        reindexThenAlign(kbId, "align_phrases");
        ObjectNode detail = mapper.createObjectNode();
        detail.put("label", label);
        detail.put("temporal", temporal == null ? "state" : temporal);
        detail.put("functional", req.path("functional").asBoolean(false));
        detail.put("inverse_functional", req.path("inverse_functional").asBoolean(false));
        if (description != null) {
            detail.put("description", description);
        }
        audit.record(null, kbId, user.id(), "relation_type.updated", "relation_type", id,
                detail.toString());
        // 对账可能在同一事务里收/开过违规行——审核队列变了，说一声（0062）
        events.emitReview(kbId);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /** RelationTypeReq.axioms（ontology_routes.rs:298-313）：六位布尔+两条链打包。 */
    private static RelationAxioms axiomsOf(JsonNode req) {
        return new RelationAxioms(req.path("functional").asBoolean(false),
                req.path("inverse_functional").asBoolean(false),
                req.path("is_transitive").asBoolean(false),
                req.path("is_symmetric").asBoolean(false),
                req.path("is_asymmetric").asBoolean(false),
                req.path("is_irreflexive").asBoolean(false),
                optUuid(req.get("inverse_of")), optUuid(req.get("sub_property_of")));
    }

    private static String requiredText(JsonNode req, String field) {
        JsonNode n = req.get(field);
        if (n == null || !n.isTextual()) {
            // serde 对 Option 之外必填字段的缺键拒绝（missing field `label`）
            throw AppException.invalid("missing_field", "missing field `" + field + "`");
        }
        return n.asText().trim();
    }

    private static String optText(JsonNode req, String field) {
        JsonNode n = req.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.isTextual() ? n.asText().trim() : null;
    }

    private static UUID optUuid(JsonNode n) {
        if (n == null || !n.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(n.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<UUID> uuidList(JsonNode n) {
        List<UUID> out = uuidListOrNull(n);
        return out == null ? List.of() : out;
    }

    /** serde Vec/Uuid 反序列化：缺键/Null = None；格式坏的条目按 serde 拒。 */
    private static List<UUID> uuidListOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw AppException.invalid("invalid_type", "invalid type: expected a sequence");
        }
        List<UUID> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual()) {
                throw AppException.invalid("invalid_uuid", "invalid type: expected a uuid string");
            }
            try {
                out.add(UUID.fromString(item.asText()));
            } catch (IllegalArgumentException e) {
                throw AppException.invalid("invalid_uuid", item.asText() + " is not a uuid");
            }
        }
        return out;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            // SessionFilter 已保证受保护路由必有用户；此处兜底（不可达路径）
            throw tech.seexun.jknow.common.AppException.UNAUTHORIZED;
        }
        return user;
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }
}
