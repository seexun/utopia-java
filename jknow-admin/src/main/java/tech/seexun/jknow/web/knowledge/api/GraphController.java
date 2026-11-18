package tech.seexun.jknow.web.knowledge.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import java.util.Map;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.entity.EvidenceRow;
import tech.seexun.jknow.store.entity.GraphEdge;
import tech.seexun.jknow.store.entity.GraphNodeRow;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 图谱视图族端点（对齐旧仓库 utopia-server/src/api/graph_routes.rs 逐 handler；
 * M1 任务 3 实现 overview / neighborhood / entities 搜索 / facts evidence 四个 GET——
 * 其余 graph_routes handler（实体详情/改名/合并/重建等写路径与重读面）按 m1-plan 分期，
 * 未实现路径落 404 {"error":"Not found"}（对未知 id 的 Rust 404 天然同形，见剧本处置）。
 *
 * <p>时刻参数 at/as_of（世界轴/记录轴，0019）与 limit 钳位逐字（graph_routes.rs:27-44/46-52）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:graph:list')")
public class GraphController {

    /** graph_routes.rs:48：总览默认画 150 个节点。 */
    private static final long GRAPH_NODE_CAP = 150;
    /** graph_routes.rs:52：调得再高也得有天花板（力导布局 O(n²)）。 */
    private static final long GRAPH_NODE_CAP_MAX = 1000;

    private final KbAccess rbac;
    private final GraphService graph;
    private final ReasoningService reasoning;
    private final GraphWriteService writes;
    private final NamesService names;
    private final ResolutionService resolution;
    private final DocumentsWriteService documents;
    private final AppEventBus events;
    private final AuditService audit;

    public GraphController(KbAccess rbac, GraphService graph, ReasoningService reasoning,
                           GraphWriteService writes, DocumentsWriteService documents,
                           AppEventBus events, AuditService audit, NamesService names,
                           ResolutionService resolution) {
        this.rbac = rbac;
        this.graph = graph;
        this.reasoning = reasoning;
        this.writes = writes;
        this.documents = documents;
        this.events = events;
        this.audit = audit;
        this.names = names;
        this.resolution = resolution;
    }

    /**
     * graph_routes.rs:168-202 entity_detail（M5 任务 6 切流演练落位——store 真身 M3 在，
     * 此前 HTTP 未接）：面板的一条实体。世界轴不过滤（at=null：面板要整条时间线）；
     * 派生/未落地派生各自单独一个键，同名打开面板时就给。
     */
    @GetMapping("/api/v1/kbs/{kbId}/entities/{entityId}")
    public java.util.Map<String, Object> entityDetail(@PathVariable UUID kbId,
                                                      @PathVariable UUID entityId,
                                                      @RequestParam(required = false) String at,
                                                      @RequestParam(required = false) String asOf,
                                                      HttpServletRequest currentRequest) {
        rbac.requireKb(user(currentRequest), kbId, Role.VIEWER);
        OffsetDateTime asOfT = parseInstant("as_of", asOf);
        GraphService.EntityDetail detail = graph.entityDetail(kbId, entityId, null, asOfT);
        if (detail == null) {
            throw tech.seexun.jknow.common.AppException.NOT_FOUND;
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("entity", detail.node());
        out.put("facts", detail.facts());
        out.put("names", names.forEntity(kbId, entityId, asOfT));
        out.put("derived", reasoning.derivedForEntity(kbId, entityId, null, asOfT));
        out.put("blocked", reasoning.blockedForEntity(kbId, entityId, asOfT));
        out.put("same_name", graph.sameNamePeers(kbId, entityId, asOfT));
        return out;
    }


    /**
     * graph_routes.rs:217-273 update_entity（M5 任务 6 切流演练落位）：改类型（才标
     * type_source='human'——只改名不该顺手把类型的来源盖成人工）或改名（人改的名字也是
     * 一条名字事实 0041，旧名字不作废——召回靠它认出来）。台账分开记 retyped/renamed；
     * 返回改后的实体与当下的同名列。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:graph:edit')")
    @PatchMapping("/api/v1/kbs/{id}/entities/{entityId}")
    public java.util.Map<String, Object> updateEntity(@PathVariable UUID id,
                                                      @PathVariable UUID entityId,
                                                      @RequestBody com.fasterxml.jackson.databind.JsonNode body,
                                                      HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        rbac.requireKb(user, id, Role.EDITOR);
        UUID typeId = body.hasNonNull("type_id")
                ? UUID.fromString(body.get("type_id").asText()) : null;
        String canonicalName = body.hasNonNull("canonical_name")
                ? body.get("canonical_name").asText() : null;
        if (typeId == null && canonicalName == null) {
            throw tech.seexun.jknow.common.AppException.invalid("nothing_to_update",
                    "Nothing to update");
        }
        String newName = null;
        if (canonicalName != null) {
            newName = canonicalName.trim();
            if (newName.isEmpty()) {
                throw tech.seexun.jknow.common.AppException.invalid("entity_name_required",
                        "Name cannot be empty");
            }
            if (newName.codePointCount(0, newName.length()) > 100) {
                throw tech.seexun.jknow.common.AppException.invalid("entity_name_too_long",
                        "Name is too long (max 100)");
            }
        }
        var before = graph.entityNode(id, entityId);
        if (before == null) {
            throw tech.seexun.jknow.common.AppException.NOT_FOUND;
        }
        if (typeId != null) {
            Integer exists = writes.entityTypeExists(id, typeId);
            if (exists == null) {
                throw tech.seexun.jknow.common.AppException.invalid("unknown_entity_type",
                        "No such entity type in this KB");
            }
        }
        // 改了类型才标 human；COALESCE 三态：$3 IS NULL = 本次没提供类型
        writes.updateEntity(id, entityId, typeId, newName);
        if (newName != null) {
            names.record(id, entityId, newName, null, null);
        }
        var after = graph.entityNode(id, entityId);
        if (!String.valueOf(before.typeKey()).equals(String.valueOf(after.typeKey()))) {
            audit.record((ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR), id,
                    user.id(), "entity.retyped", "entity", entityId,
                    "{\"name\": \"" + after.name() + "\"}");
        }
        if (!String.valueOf(before.name()).equals(String.valueOf(after.name()))) {
            audit.record((ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR), id,
                    user.id(), "entity.renamed", "entity", entityId,
                    "{\"from\": \"" + before.name() + "\", \"to\": \"" + after.name()
                            + "\", \"type\": \"" + after.typeLabel() + "\"}");
        }
        if (newName != null && !newName.equalsIgnoreCase(String.valueOf(before.name()))) {
            resolution.refreshDisambiguators(id, String.valueOf(before.name()));
            resolution.refreshDisambiguators(id, newName);
        } else if (typeId != null) {
            resolution.refreshDisambiguators(id, String.valueOf(before.name()));
        }
        events.emitReview(id);
        return java.util.Map.of("entity", after, "same_name",
                graph.sameNamePeers(id, entityId, null));
    }

    /**
     * graph_routes.rs:517-545 rebuild（M5 任务 6 落位）：图谱重建（清算语义，KB admin）。
     * 与来源级重抽的分工——重抽保留既有决策，重建放弃它们，换取「当前语料 × 当前本体」
     * 的确定性重演（早期脏抽取、本体大改后的收场手段）。决策台账与裁决缓存刻意保留。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:graph:edit')")
    @PostMapping("/api/v1/kbs/{id}/graph/rebuild")
    public java.util.Map<String, Object> rebuild(@PathVariable UUID id,
                                                 HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        rbac.requireKb(user, id, Role.ADMIN);
        long[] removed = writes.purgeGraph(id);
        List<UUID> ids = documents.queueExtraction(id, null);
        for (UUID docId : ids) {
            events.emitDocument(id, docId);
        }
        events.emitReview(id);
        audit.record((ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR), id,
                user.id(), "graph.rebuild", "kb", id,
                "{\"entities_removed\": " + removed[0] + ", \"facts_removed\": " + removed[1]
                        + ", \"documents\": " + ids.size() + "}");
        return java.util.Map.of("entities_removed", removed[0], "facts_removed", removed[1],
                "queued", ids.size());
    }

    // ---- 响应形状（json! 对齐；键序经规范器排序，字段集一致即可） ----

    record OverviewResponse(List<GraphNodeRow> nodes, List<GraphEdge> edges, long totalNodes,
                            long totalEdges) {
    }

    record NeighborhoodResponse(List<GraphNodeRow> nodes, List<GraphEdge> edges) {
    }

    record SearchResponse(List<GraphNodeRow> entities, long total) {
    }

    record EvidenceResponse(List<EvidenceRow> evidence) {
    }

    // ---- GET /api/v1/kbs/{id}/graph/overview（graph_routes.rs:67-88） ----

    @GetMapping("/api/v1/kbs/{kbId}/graph/overview")
    public OverviewResponse overview(@PathVariable UUID kbId,
                                     @RequestParam(required = false) String at,
                                     @RequestParam(name = "as_of", required = false) String asOf,
                                     @RequestParam(required = false) Long limit,
                                     HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        OffsetDateTime atT = parseInstant("at", at);
        OffsetDateTime asOfT = parseInstant("as_of", asOf);
        // 不给就用默认值；给了也钳在 [10, 1000]——别让一个 limit=999999 把库拖垮
        long effective = limit == null ? GRAPH_NODE_CAP : Math.max(10, Math.min(GRAPH_NODE_CAP_MAX, limit));
        GraphService.Overview o = graph.overview(kbId, effective, atT, asOfT);
        return new OverviewResponse(o.nodes(), o.edges(), o.totalNodes(), o.totalEdges());
    }

    // ---- GET /api/v1/kbs/{id}/graph/neighborhood（graph_routes.rs:101-120） ----

    @GetMapping("/api/v1/kbs/{kbId}/graph/neighborhood")
    public NeighborhoodResponse neighborhood(@PathVariable UUID kbId,
                                             @RequestParam UUID entity,
                                             @RequestParam(required = false) Integer hops,
                                             @RequestParam(required = false) String at,
                                             @RequestParam(name = "as_of", required = false) String asOf,
                                             HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        OffsetDateTime atT = parseInstant("at", at);
        OffsetDateTime asOfT = parseInstant("as_of", asOf);
        GraphService.Neighborhood n = graph.neighborhood(kbId, entity,
                hops == null ? 2 : hops, atT, asOfT);
        return new NeighborhoodResponse(n.nodes(), n.edges());
    }

    // ---- GET /api/v1/kbs/{id}/entities（graph_routes.rs:135-159 search_entities） ----

    @GetMapping("/api/v1/kbs/{kbId}/entities")
    public Object searchEntities(@PathVariable UUID kbId,
                                 @RequestParam String q,
                                 @RequestParam(required = false) Long limit,
                                 @RequestParam(required = false) Long offset,
                                 @RequestParam(name = "as_of", required = false) String asOf,
                                 HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        // 先校验时刻：一个写错的 as_of 配空查询也该回 400，而不是看起来成功
        // （graph_routes.rs:142-143；错误文案/状态对齐 Rust Validation→422）
        OffsetDateTime asOfT = parseInstant("as_of", asOf);
        if (q.strip().isEmpty()) {
            return new SearchResponse(List.of(), 0);
        }
        GraphService.Search s = graph.searchEntities(kbId, q,
                limit == null ? 10 : Math.max(1, Math.min(100, limit)),
                offset == null ? 0 : Math.max(0, offset),
                asOfT);
        return new SearchResponse(s.entities(), s.total());
    }

    // ---- GET /api/v1/kbs/{id}/facts/{factId}/evidence（graph_routes.rs:451-459） ----

    @GetMapping("/api/v1/kbs/{kbId}/facts/{factId}/evidence")
    public EvidenceResponse factEvidence(@PathVariable UUID kbId, @PathVariable UUID factId,
                                         HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return new EvidenceResponse(graph.factEvidence(factId));
    }

    // ---- GET /api/v1/kbs/{id}/entities/{entityId}/history（graph_routes.rs:559-571） ----

    record HistoryResponse(List<tech.seexun.jknow.store.entity.EntityHistoryEvent> events,
                           long total) {
    }

    /** per 钳 [1,200]、page ≥ 0（graph_routes.rs:559-571；default per=30）。 */
    @GetMapping("/api/v1/kbs/{kbId}/entities/{entityId}/history")
    public HistoryResponse entityHistory(@PathVariable UUID kbId, @PathVariable UUID entityId,
                                         @RequestParam(defaultValue = "0") long page,
                                         @RequestParam(defaultValue = "30") long per,
                                         HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        long effectivePer = Math.max(1, Math.min(200, per));
        long effectivePage = Math.max(0, page);
        var result = graph.entityHistory(kbId, entityId, effectivePer,
                effectivePage * effectivePer);
        return new HistoryResponse(result.events(), result.total());
    }

    // ---- GET /api/v1/kbs/{id}/derived/{derivedId}/proof（graph_routes.rs:463-473） ----

    /** 派生已失效或不存在时 proof 为 null——不是错误，界面据此退回文本前提。 */
    @GetMapping("/api/v1/kbs/{kbId}/derived/{derivedId}/proof")
    public Map<String, Object> derivedProof(@PathVariable UUID kbId,
                                            @PathVariable UUID derivedId,
                                            HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        // Map.of 不收 null：proof 找不到时就是 {"proof":null}
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("proof", reasoning.proof(kbId, derivedId));
        return out;
    }

    // ---- GET /api/v1/kbs/{id}/violations/{violationId}/proof（graph_routes.rs:475-483） ----

    /** 没落地的派生的证明链：违规不存在时 steps 为 null。 */
    @GetMapping("/api/v1/kbs/{kbId}/violations/{violationId}/proof")
    public Map<String, Object> blockedProof(@PathVariable UUID kbId,
                                            @PathVariable UUID violationId,
                                            HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("steps", reasoning.blockedProof(kbId, violationId));
        return out;
    }

    // ---- 时刻参数解析（graph_routes.rs:27-44 parse_instant 逐字语义） ----

    /**
     * YYYY-MM-DD → 当日 00:00 UTC；否则 RFC3339；trim 后空 = 未给。
     * 解析失败 → 422 {@code Invalid `at` (expected YYYY-MM-DD or RFC3339)}（AppError::Validation）。
     * static 供 GraphInstantsTests 直测。
     */
    static OffsetDateTime parseInstant(String field, String raw) {
        String s = raw == null ? null : raw.strip();
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(s);
            return d.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        } catch (java.time.format.DateTimeParseException notADate) {
            // 落到 RFC3339
        }
        try {
            // chrono parse_from_rfc3339 后 with_timezone(Utc)：任意 offset 归一到 UTC 同一时刻
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            throw AppException.validation("Invalid `" + field + "` (expected YYYY-MM-DD or RFC3339)");
        }
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
