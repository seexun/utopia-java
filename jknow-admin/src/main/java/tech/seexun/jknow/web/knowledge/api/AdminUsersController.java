package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.query.QueryEngines;
import tech.seexun.jknow.store.entity.ModelLimitRow;
import tech.seexun.jknow.store.entity.OrgUser;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.mapper.AdminMapper;
import tech.seexun.jknow.store.mapper.DeploymentSettingsMapper;
import tech.seexun.jknow.store.mapper.WorkspaceMapper;

/**
 * 组织用户面 + 管理员面 + 部署配置读（对齐旧仓库 members_routes.rs:30-54、
 * admin_routes.rs:15-41）。**15×403 判定链**：/users/deactivated 与 /admin/* 全部端点
 * 在 Rust 都是 require_admin 首步（admin_routes.rs:15-21 require_admin /
 * members_routes.rs:48-50 / datasource_routes 的管理员分支），非 admin 一律
 * 403 "You don't have permission to do that"——Java 侧同一道门先于一切。
 *
 * <p>管理员 happy path：GET /admin/deployment 与 GET /admin/data-sources、
 * GET /admin/data-sources/{id}/grants 读面照实实现（SQL 逐字）；admin 数据源写面
 * （建删源/连通测试/授权/收回）自 M5 任务 3 起接真（query 包六引擎落地）；其余 admin
 * 写路径（代开账号/停复活/改部署配置）已过 403 门后落 500（不谎报成功）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:settings:list')")
public class AdminUsersController {

    private final WorkspaceMapper workspaces;
    private final AdminMapper admin;
    private final DatasourcesService datasources;
    private final DeploymentSettingsMapper deploymentSettings;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public AdminUsersController(WorkspaceMapper workspaces, AdminMapper admin,
                                DatasourcesService datasources,
                                DeploymentSettingsMapper deploymentSettings,
                                AuditService audit, ObjectMapper mapper) {
        this.workspaces = workspaces;
        this.admin = admin;
        this.datasources = datasources;
        this.deploymentSettings = deploymentSettings;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ---- GET /api/v1/users（members_routes.rs:30-37 org_users：非 admin 可读的选人器） ----

    @GetMapping("/api/v1/users")
    public List<OrgUser> orgUsers(HttpServletRequest request) {
        UserRow user = user(request);
        return workspaces.selectOrgUsers(user.orgId());
    }

    // ---- GET /api/v1/users/deactivated（members_routes.rs:44-54：仅管理员） ----

    @GetMapping("/api/v1/users/deactivated")
    public List<OrgUser> deactivatedUsers(HttpServletRequest request) {
        UserRow user = requireAdmin(request);
        return workspaces.selectDeactivatedUsers(user.orgId());
    }

    // ---- GET /api/v1/admin/deployment（admin_routes.rs:23-41） ----

    @GetMapping("/api/v1/admin/deployment")
    public JsonNode getDeployment(HttpServletRequest request) {
        requireAdmin(request);
        String ontoLang = admin.selectDefaultOntologyLang();
        Integer workers = admin.selectWorkerConcurrency();
        List<ModelLimitRow> limits = admin.selectModelLimits();
        Integer dflt = admin.selectDefaultModelConcurrency();
        ArrayNode inUse = mapper.createArrayNode();
        for (AdminMapper.InUseModel m : admin.selectModelsInUse()) {
            ObjectNode row = mapper.createObjectNode();
            row.put("base_url", m.baseUrl());
            row.put("model", m.model());
            row.put("kind", m.kind());
            inUse.add(row);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("open_registration", openRegistration());
        out.put("worker_concurrency", workers == null ? 64 : workers);
        out.put("default_ontology_lang", ontoLang == null ? "en" : ontoLang);
        out.set("model_limits", mapper.valueToTree(limits));
        out.put("default_model_concurrency", dflt == null ? 10 : dflt);
        out.set("models_in_use", inUse);
        return out;
    }

    /** access.rs:183-189 open_registration：无行缺省 true。 */
    private boolean openRegistration() {
        Boolean v = deploymentSettings.selectOpenRegistration();
        return v == null || v;
    }

    // ---- admin 面其余端点：403 判定链对齐；happy 写路径归 M2 ----

    /** admin_routes.rs:107-146 create_user（写路径归 M2：非 admin 403 形状对齐）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/admin/users")
    public JsonNode createUser(HttpServletRequest request) {
        requireAdmin(request);
        throw AppException.other("admin user creation is M2 (write path)", null);
    }

    /** admin_routes.rs:157-175 deactivate_user（DELETE=断访问不是删行；归 M2）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @DeleteMapping("/api/v1/admin/users/{id}")
    public JsonNode deactivateUser(@PathVariable UUID id, HttpServletRequest request) {
        requireAdmin(request);
        throw AppException.other("admin user deactivation is M2 (write path)", null);
    }

    /** admin_routes.rs:182-200 reactivate_user（归 M2）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/admin/users/{id}")
    public JsonNode reactivateUser(@PathVariable UUID id, HttpServletRequest request) {
        requireAdmin(request);
        throw AppException.other("admin user reactivation is M2 (write path)", null);
    }

    /** admin_routes.rs:68-94 put_deployment（写路径归 M2：非 admin 403 形状对齐）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PutMapping("/api/v1/admin/deployment")
    public JsonNode putDeployment(@RequestBody JsonNode body, HttpServletRequest request) {
        requireAdmin(request);
        throw AppException.other("deployment settings update is M2 (write path)", null);
    }

    // OIDC 管理面（GET/DELETE /api/v1/admin/oidc/identities…）自 M5 任务 2 起由
    // oidc/OidcController 真实现持有（此前的 422 未配置占位随之退役）。

    // ---- 管理员数据源面（datasource_routes.rs admin 分支；M5 任务 3 写面接真） ----

    /** datasource_routes list：全部署每一个源（连接串→无凭据摘要）。 */
    @GetMapping("/api/v1/admin/data-sources")
    public JsonNode listDataSources(HttpServletRequest request) {
        requireAdmin(request);
        ObjectNode out = mapper.createObjectNode();
        out.set("data_sources", mapper.valueToTree(datasources.listAll()));
        return out;
    }

    /**
     * datasource_routes.rs:50-87 create。引擎跟着 scheme 走，界面只有一个连接串输入框；
     * body.engine 只为兼容旧调用留着。连接串的形状在登记时就校验（缺令牌、缺
     * warehouse……），错误信息里带写法——否则要等到「测试」才知道，而那一步只回 ok:false。
     *
     * <p><b>登记完就能挂。</b> 授权是按工作区的（0014），可工作区在界面上已经隐形——
     * 单租户部署只有一个，谁也没见过它的名字。从前登记完还要在卡片上先「授权给工作区」，
     * 等于让人授权一件从没见过的东西，挂载时只得到一句「未授权」。所以登记人所在的每个
     * 工作区一并授权；要收窄，卡片上仍能撤销。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/admin/data-sources")
    public JsonNode createDataSource(@RequestBody JsonNode body, HttpServletRequest request) {
        UserRow user = requireAdmin(request);
        String connString = body.path("conn_string").asText("");
        String engine = DatasourcesController.engineOf(connString);
        try {
            QueryEngines.engineFor(engine, connString);
        } catch (Exception e) {
            throw AppException.invalid("bad_conn_string", String.valueOf(e.getMessage()));
        }
        UUID id = datasources.create(body.path("name").asText(""), engine, connString, user.id());
        for (var ws : workspaces.listForUser(user.id())) {
            datasources.grant(id, ws.id(), user.id());
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("id", id.toString());
        return out;
    }

    /**
     * datasource_routes.rs:99-133 test_conn：存之前先试一次。<b>这一步不落库</b>——登记
     * 表单里那个「测试连接」按的是它。把引擎的报错原样带回去：密码错、库名拼错、端口不通，
     * 是三件不同的事，人得知道是哪一件。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/admin/data-sources/test")
    public JsonNode testDataSources(@RequestBody JsonNode body, HttpServletRequest request) {
        requireAdmin(request);
        String connString = body.path("conn_string").asText("");
        String engine = DatasourcesController.engineOf(connString);
        // 连接串的形状（缺令牌、缺 warehouse……）与连得上连不上分开报：前者是写错了，
        // 后者是环境问题，两句话对人的下一步动作不一样
        ObjectNode out = mapper.createObjectNode();
        try {
            QueryEngines.engineFor(engine, connString);
        } catch (Exception e) {
            throw AppException.invalid("bad_conn_string", String.valueOf(e.getMessage()));
        }
        out.put("ok", true);
        out.put("engine", engine);
        try {
            QueryEngines.engineFor(engine, connString).test();
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** datasource_routes.rs:135-148 test（按 id）：结果落行（record_test）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/admin/data-sources/{id}/test")
    public JsonNode testDataSource(@PathVariable UUID id, HttpServletRequest request) {
        requireAdmin(request);
        var ec = datasources.engineAndConn(id);
        boolean ok;
        try {
            QueryEngines.engineFor(ec.engine(), ec.conn()).test();
            ok = true;
        } catch (Exception e) {
            ok = false;
        }
        datasources.recordTest(id, ok);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", ok);
        return out;
    }

    /** datasource_routes.rs:89-97 delete。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @DeleteMapping("/api/v1/admin/data-sources/{id}")
    public JsonNode deleteDataSource(@PathVariable UUID id, HttpServletRequest request) {
        requireAdmin(request);
        datasources.delete(id);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /** datasource_routes.rs:404-416 grants：一个源授给了哪些工作区。 */
    @GetMapping("/api/v1/admin/data-sources/{id}/grants")
    public JsonNode grants(@PathVariable UUID id, HttpServletRequest request) {
        requireAdmin(request);
        ObjectNode out = mapper.createObjectNode();
        out.set("workspaces", mapper.valueToTree(datasources.grantsForSource(id)));
        return out;
    }

    /**
     * datasource_routes.rs:418-436 grant：授权一个工作区用这个源（幂等）。
     * 授权与挂载是两层，各有各的主人（0014）——两层都是多对多，挂载只能在授权过的集合里挑。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PutMapping("/api/v1/admin/data-sources/{id}/grants/{workspaceId}")
    public JsonNode grant(@PathVariable UUID id, @PathVariable UUID workspaceId,
                          HttpServletRequest request) {
        UserRow user = requireAdmin(request);
        datasources.grant(id, workspaceId, user.id());
        audit.recordOpt(client(request), null, user.id(), "data_source.granted", "data_source",
                id, mapper.createObjectNode().put("workspace_id", workspaceId.toString())
                        .toString());
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /**
     * datasource_routes.rs:441-459 revoke：收回授权，<b>连同该工作区里已挂上的一起卸掉</b>
     * （store 层一个事务）。返回卸掉了几个，好让界面说得出「顺带卸了 3 个库」而不是悄悄
     * 断人家的连接。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @DeleteMapping("/api/v1/admin/data-sources/{id}/grants/{workspaceId}")
    public JsonNode revoke(@PathVariable UUID id, @PathVariable UUID workspaceId,
                           HttpServletRequest request) {
        UserRow user = requireAdmin(request);
        long unmounted = datasources.revoke(id, workspaceId);
        audit.recordOpt(client(request), null, user.id(), "data_source.revoked", "data_source",
                id, mapper.createObjectNode().put("workspace_id", workspaceId.toString())
                        .put("unmounted", unmounted).toString());
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("unmounted", unmounted);
        return out;
    }

    /** admin_routes.rs:15-21 require_admin：非 admin → 403（同形状）。 */
    private static UserRow requireAdmin(HttpServletRequest request) {
        UserRow user = user(request);
        if (!user.isAdmin()) {
            throw AppException.FORBIDDEN;
        }
        return user;
    }

    /** OIDC 审计同位的 client ctx（M5 任务 2 起 admin 写面带 ip/ua）。 */
    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
