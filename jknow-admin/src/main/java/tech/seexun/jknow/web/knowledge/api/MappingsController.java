package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.MappingsService.MappingPage;
import tech.seexun.jknow.store.NameVectorsService.Embedder;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.MappingRevision;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 语义层口径读族端点（对齐旧仓库 mapping_routes.rs:36-73/147-155/168-182 与
 * datasource_routes.rs mounted/mountable；M1 任务 5）。preview/revise/create 写路径
 * 归 M2（剧本定向 rust）。
 *
 * <p>非 SQL 语义逐字：list 的 status 白名单 422（mapping_routes.rs:43-52
 * "status must be proposed, confirmed or rejected"）、limit 缺省 25 钳 [1,200]；
 * relevant 的 k 缺省 DEFINITIONS_IN_PROMPT=8 钳 [1,50]、缺 q 的 400 文本对齐 axum
 * Query 拒绝原文（"Failed to deserialize query string: missing field `q`"）。
 */
@RestController
public class MappingsController {

    /** mapping_routes.rs:20 MAPPING_PAGE：一页多少条（口径比审阅队列密）。 */
    private static final long MAPPING_PAGE = 25;

    private final MappingsService mappings;
    private final DatasourcesService datasources;
    private final SettingsService settings;
    private final tech.seexun.jknow.web.knowledge.llm.LlmSupport llm;
    private final tech.seexun.jknow.web.knowledge.query.QueryRunner runner;
    private final tech.seexun.jknow.store.ResolutionService resolution;
    private final tech.seexun.jknow.web.knowledge.auth.AuditService audit;
    private final KbAccess rbac;
    private final ObjectMapper mapper;

    public MappingsController(MappingsService mappings, DatasourcesService datasources,
                              SettingsService settings,
                              tech.seexun.jknow.web.knowledge.llm.LlmSupport llm,
                              tech.seexun.jknow.web.knowledge.query.QueryRunner runner,
                              tech.seexun.jknow.store.ResolutionService resolution,
                              tech.seexun.jknow.web.knowledge.auth.AuditService audit,
                              KbAccess rbac, ObjectMapper mapper) {
        this.mappings = mappings;
        this.datasources = datasources;
        this.settings = settings;
        this.llm = llm;
        this.runner = runner;
        this.resolution = resolution;
        this.audit = audit;
        this.rbac = rbac;
        this.mapper = mapper;
    }

    // ---- GET /api/v1/kbs/{kbId}/mappings（mapping_routes.rs:36-73） ----

    /** 四段响应（items/total/counts/last_run；键序由 SNAKE_CASE 策略无关——固定名）。 */
    public record ListResponse(List<?> items, long total, MappingsService.Counts counts,
                               Object lastRun) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/mappings")
    public ListResponse list(@PathVariable UUID kbId,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) Long limit,
                             @RequestParam(required = false) Long offset,
                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        String effStatus = status == null || status.isEmpty() ? null : status;
        if (effStatus != null && !"proposed".equals(effStatus) && !"confirmed".equals(effStatus)
                && !"rejected".equals(effStatus)) {
            throw AppException.invalid("bad_status", "status must be proposed, confirmed or rejected");
        }
        String needle = q == null || q.trim().isEmpty() ? null : q.trim();
        long effLimit = limit == null ? MAPPING_PAGE : Math.max(1, Math.min(200, limit));
        long effOffset = offset == null ? 0 : Math.max(0, offset);
        MappingPage page = mappings.page(kbId, effStatus, needle, effLimit, effOffset);
        return new ListResponse(page.items(), page.total(), page.counts(), page.lastRun());
    }

    // ---- GET /api/v1/kbs/{kbId}/mappings/relevant（mapping_routes.rs:168-182） ----

    /**
     * 缺 q → 400 文本 "Failed to deserialize query string: missing field `q`"
     * （axum Query&lt;RelevantQuery&gt; 的拒绝原文；金样本逐字）。
     */
    @GetMapping("/api/v1/kbs/{kbId}/mappings/relevant")
    public ResponseEntity<String> relevant(@PathVariable UUID kbId,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) Integer k,
                                           HttpServletRequest request) {
        UserRow user = user(request);
        if (q == null) {
            // axum 的 400 是 text/plain 原文（非 JSON 包装），对齐原文（金样本逐字）
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to deserialize query string: missing field `q`");
        }
        KbRow kb = rbac.requireKb(user, kbId, Role.VIEWER);
        int effK = k == null ? MappingsService.DEFINITIONS_IN_PROMPT : Math.max(1, Math.min(50, k));
        // 向量一路（mapping_index.rs:132-174）：工作区配了嵌入模型才在；模型名要传——
        // 只比同一个模型嵌出来的口径。每次嵌入调用占一张全局闸门许可
        // （mapping_index.rs:64-66 补嵌逐批 / :146-152 查询嵌入——补齐别把共享闸门占死）。
        // 设置读不出（封印钥匙不对等）时向量路缺席、问数照答——Rust 侧 refresh 与
        // vector_channel 各自读设置，两处的错都被 relevant 收成「这一路没有」
        tech.seexun.jknow.store.entity.LlmSettingsRow settingsRow = null;
        Embedder embedder = null;
        try {
            settingsRow = settings.get(kb.workspaceId());
            tech.seexun.jknow.llm.EmbedClient embed = LlmSupport.embedClient(settingsRow);
            if (embed != null) {
                final var row = settingsRow;
                embedder = texts -> {
                    java.util.concurrent.Semaphore permit = llm.acquirePermit(
                            row.embedBaseUrl(), row.embedModel());
                    try {
                        return embed.embed(texts);
                    } finally {
                        if (permit != null) {
                            permit.release();
                        }
                    }
                };
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MappingsController.class)
                    .warn("kb={} 口径检索读不到嵌入设置，只用词面: {}", kbId, e.getMessage());
        }
        String model = settingsRow == null ? null : settingsRow.embedModel();
        ObjectNode out = mapper.createObjectNode();
        out.set("items", mapper.valueToTree(mappings.relevant(kbId, q, effK, embedder, model)));
        out.put("k", effK);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(out.toString());
    }

    // ---- GET /api/v1/kbs/{kbId}/mappings/{mappingId}/revisions（mapping_routes.rs:147-155） ----

    public record RevisionsResponse(List<MappingRevision> revisions) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/mappings/{mappingId}/revisions")
    public RevisionsResponse revisions(@PathVariable UUID kbId, @PathVariable UUID mappingId,
                                       HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        return new RevisionsResponse(mappings.revisions(kbId, mappingId));
    }

    // ---- GET /api/v1/kbs/{kbId}/data-sources（datasource_routes mounted：Viewer） ----

    public record DataSourcesResponse(List<DataSourceView> dataSources) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/data-sources")
    public DataSourcesResponse mounted(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        return new DataSourcesResponse(datasources.mounted(kbId));
    }

    // ---- GET /api/v1/kbs/{kbId}/data-sources/available（datasource_routes mountable：Admin） ----

    @GetMapping("/api/v1/kbs/{kbId}/data-sources/available")
    public DataSourcesResponse available(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        var kb = rbac.requireKb(user, kbId, Role.ADMIN);
        return new DataSourcesResponse(datasources.grantedToWorkspace(kb.workspaceId()));
    }

    // ====================================================================
    // 写面（mapping_routes.rs:186-389 create/revise/preview；M5 任务 3）
    // ====================================================================

    /** mapping_routes.rs:184-190 clean：空白等于没填——落库该是 NULL 而不是空串。 */
    static String clean(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /**
     * mapping_routes.rs:192-206 definition_shape：一条口径至少得有表、表达式、SQL 之一，
     * 否则它不是一个可执行的定义。{@code create}、{@code preview} 与 {@code revise}
     * 判的是同一条。
     */
    static void definitionShape(String tableName, String expr, String sql) {
        if (tableName == null && expr == null && sql == null) {
            throw AppException.invalid("empty_mapping",
                    "A mapping needs at least one of table, expression or SQL");
        }
    }

    /**
     * mapping_routes.rs:208-223 render_sql：一条口径跑起来是哪句 SQL——给了 {@code sql}
     * 用 {@code sql}（去尾分号——闸门只放行单条语句），否则 {@code expr} + {@code table}
     * 拼一句。测量台（scripts/bench/mappings.mjs 的 proposalSql）判分用的是同一个拼法，
     * 所以页面上预览到的数就是测量台会打分的数。
     */
    static String renderSql(String tableName, String expr, String sql) {
        if (sql != null) {
            String s = sql;
            while (s.endsWith(";")) {
                s = s.substring(0, s.length() - 1);
            }
            return s.trim();
        }
        if (expr != null && tableName != null) {
            return "SELECT " + expr + " FROM " + tableName;
        }
        return null;
    }

    /** mapping_routes.rs:225-242 CreateReq（kind 缺省 metric）。 */
    public record CreateBody(String concept, String kind, String source, String tableName,
                             String expr, String sql, String unit, String summary,
                             boolean derived) {
    }

    /**
     * mapping_routes.rs:244-334 create：人从零写一条口径（#562）。从前这张表只有探索一条
     * 来路——一个数据团队手上有自己的指标口径文档，却没有地方把它填进去（而口径进了问数
     * 的提示词，宽表语料从 1/18 到 17/18，#520）。
     *
     * <p>概念仍落成 Metric/Dimension 类的实体（概念消解 resolve_mention 同名归并，跟探索
     * 提的那些落在一处）——那是这张表的现状（concept_id 非空指向实体）。0035 要退役这个
     * 形状（#556），但保留这张表并往里渲染，人写的 SQL 搬得过去。源按名字点单，且必须是
     * 本库挂载的——与 query_data 同一条安全边界。
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/v1/kbs/{kbId}/mappings")
    public JsonNode create(@PathVariable UUID kbId, @RequestBody CreateBody req,
                           HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String concept = req.concept() == null ? "" : req.concept().trim();
        if (concept.isEmpty()) {
            throw AppException.invalid("empty_concept", "A definition needs a concept name");
        }
        String kind = req.kind() == null || req.kind().isEmpty() ? "metric" : req.kind();
        if (!"metric".equals(kind) && !"dimension".equals(kind)) {
            throw AppException.invalid("bad_kind", "kind must be metric or dimension");
        }
        String tableName = clean(req.tableName());
        String expr = clean(req.expr());
        String sql = clean(req.sql());
        String unit = clean(req.unit());
        String summary = clean(req.summary());
        definitionShape(tableName, expr, sql);
        DataSourceView ds = mountedSource(kbId, req.source());
        mappings.ensureConceptTypes(kbId);
        UUID typeId = mappings.entityTypeId(kbId, kind);
        // 概念实体走消解：同名归并，跟探索提的那些落在一处
        UUID entityId = resolution.resolveMention(kbId, typeId, concept, null, null, null,
                java.util.List.of()).entityId();
        UUID id = mappings.create(kbId, entityId, ds.name(), tableName, expr, sql, unit, summary,
                req.derived(), user.id());
        audit.recordOpt(client(request), kbId, user.id(), "mapping.written", "concept_mapping",
                id, mapper.createObjectNode().put("concept", concept).put("source", ds.name())
                        .toString());
        ObjectNode out = mapper.createObjectNode();
        out.put("id", id.toString());
        out.put("concept_id", entityId.toString());
        return out;
    }

    /** mapping_routes.rs:75-84 ReviseReq。 */
    public record ReviseBody(String tableName, String expr, String sql, String unit,
                             String summary, boolean derived) {
    }

    /**
     * mapping_routes.rs:86-143 revise：改一条口径。<b>在此之前 mappings::revise 是零调用
     * 的</b>——函数在、留痕表在、没有路由，于是口径确认之后就再没人改得动。改版先进
     * revisions（答「上季度这个数是怎么算的」，0006）。
     */
    @org.springframework.web.bind.annotation.PatchMapping(
            "/api/v1/kbs/{kbId}/mappings/{mappingId}")
    public JsonNode revise(@PathVariable UUID kbId, @PathVariable UUID mappingId,
                           @RequestBody ReviseBody req, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String tableName = clean(req.tableName());
        String expr = clean(req.expr());
        String sql = clean(req.sql());
        String unit = clean(req.unit());
        String summary = clean(req.summary());
        definitionShape(tableName, expr, sql);
        mappings.revise(kbId, mappingId, tableName, expr, sql, unit, summary, req.derived(),
                user.id());
        audit.recordOpt(client(request), kbId, user.id(), "mapping.revised", "concept_mapping",
                mappingId, mapper.createObjectNode().toString());
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /** mapping_routes.rs:336-342 PreviewReq。 */
    public record PreviewBody(String source, String tableName, String expr, String sql) {
    }

    /**
     * mapping_routes.rs:344-389 preview：把一条口径过只读闸跑一遍，回第一行——写口径的人
     * 当场看到它算出来的数。这正是测量台判分的那个动作（#501/#520：把定义跑一遍，跟
     * gold 比数）。跑不通的定义无害，它失败得很响；跑得通而算错的才是全部风险，而看一眼
     * 数是人能做的唯一一道检查。只有表名不是一个可执行的定义（422）。
     */
    @org.springframework.web.bind.annotation.PostMapping(
            "/api/v1/kbs/{kbId}/mappings/preview")
    public JsonNode preview(@PathVariable UUID kbId, @RequestBody PreviewBody req,
                            HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String tableName = clean(req.tableName());
        String expr = clean(req.expr());
        String sql = clean(req.sql());
        definitionShape(tableName, expr, sql);
        String rendered = renderSql(tableName, expr, sql);
        if (rendered == null) {
            throw AppException.invalid("empty_mapping",
                    "A preview needs SQL, or an expression together with a table");
        }
        DataSourceView ds = mountedSource(kbId, req.source());
        // 引擎的报错原样带回：列名拼错、语法错、超时是三件不同的事
        String out;
        try {
            out = runner.runQuery(ds.id(), rendered);
        } catch (Exception e) {
            String message = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            throw AppException.invalid("preview_failed", message);
        }
        // 结果是 JSON Lines；第一行就是这条口径算出来的那个数（或那一组数）
        java.util.List<com.fasterxml.jackson.databind.JsonNode> rows = new java.util.ArrayList<>();
        for (String line : out.split("\n", -1)) {
            if (rows.size() >= 5) {
                break;
            }
            try {
                rows.add(mapper.readTree(line.trim()));
            } catch (Exception ignored) {
                // 头尾的 "(no rows)"/"(n rows)" 行不是 JSON——按 Rust filter_map(ok) 同档跳过
            }
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("sql", rendered);
        response.set("row", rows.isEmpty() ? mapper.nullNode() : rows.get(0));
        response.set("rows", mapper.valueToTree(rows));
        return response;
    }

    /** 挂载源按名字点单（mapping_routes.rs:276-286/365-375）：不在挂载列表 → 422 source_not_mounted。 */
    private DataSourceView mountedSource(UUID kbId, String source) {
        String want = source == null ? "" : source.trim();
        return datasources.mounted(kbId).stream()
                .filter(d -> d.name().equalsIgnoreCase(want))
                .findFirst()
                .orElseThrow(() -> AppException.invalid("source_not_mounted",
                        "That data source is not mounted on this knowledge base"));
    }

    private static tech.seexun.jknow.web.knowledge.auth.ClientContext client(
            HttpServletRequest request) {
        return (tech.seexun.jknow.web.knowledge.auth.ClientContext)
                request.getAttribute(SessionFilter.CLIENT_ATTR);
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
