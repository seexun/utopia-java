package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
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
import tech.seexun.jknow.web.knowledge.import_.ImportSupport;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.web.knowledge.rbac.WorkspaceAccess;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.WorkspaceKbWriteService;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.entity.WorkspaceRow;

/**
 * workspace/KB 写面六端点（M4 任务 6——对齐旧仓库 api/workspaces.rs 78 行与 api/kbs.rs
 * 451 行的 POST/PATCH/DELETE；读面在 {@link WorkspaceKbController}）。权限/请求体/响应
 * 形状/错误码逐字：
 * <ul>
 *   <li>POST /workspaces 登录即可（name 1-64 字符，<b>按码点数</b>——Rust chars().count()），
 *       422 {@code bad_name}；</li>
 *   <li>PATCH /workspaces/{id} ws Admin；DELETE ws Owner；</li>
 *   <li>POST /workspaces/{id}/kbs <b>ws Admin+ 或系统管理员</b>（kbs.rs:88-95：先拿
 *       ws_role（Viewer 起步——非成员 404 不泄露存在性），再判 Admin）；创建者自动入矩阵
 *       admin；</li>
 *   <li>PATCH /kbs/{id} KB Admin，十字段可选（审查 M-1 勘误：kbs.rs:30-58 实为 10 个）；governance=true 即刻 enqueueUnlessQueued
 *       （"govern"）+ audit kb.updated（data_conventions 只记是否出现——那段文字进每一次
 *       问数的提示词，谁改过要查得到）；</li>
 *   <li>DELETE /kbs/{id} KB Admin；默认库不可删（default_kb_undeletable）。</li>
 * </ul>
 *
 * <p><b>install_packs 接真（M5 任务 5——422 降级退役）</b>：Rust 建库编排最后一步
 * install_packs（kbs.rs:400-434）逐包 {@code owl_import::apply}，一个失败不回滚已装的
 * （本体是加法，装了一半的库仍然可用），packs&gt;1 时 relink 第二遍补跨包 domain/range
 * （#222）。未知包在建库<b>之后</b>装包阶段失败且不回滚已建的库（Rust 同序——库留下、
 * 包没装上）。M4 任务 6 的 {@code ontology_packs_unsupported} 降级自此退役。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:workspace:list')")
public class WorkspaceKbWriteController {

    private final WorkspaceAccess wsAccess;
    private final KbAccess kbAccess;
    private final WorkspaceKbWriteService store;
    private final JobsService jobs;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final ImportSupport imports;

    public WorkspaceKbWriteController(WorkspaceAccess wsAccess, KbAccess kbAccess,
                                      WorkspaceKbWriteService store,
                                      JobsService jobs, AuditService audit, ObjectMapper mapper,
                                      ImportSupport imports) {
        this.wsAccess = wsAccess;
        this.kbAccess = kbAccess;
        this.store = store;
        this.jobs = jobs;
        this.audit = audit;
        this.mapper = mapper;
        this.imports = imports;
    }

    record OkResponse(boolean ok) {
    }

    // ---- POST /api/v1/workspaces（workspaces.rs:37-45 create：登录即可） ----

    /** workspaces.rs:13-16 NameReq。 */
    record NameReq(String name) {
    }

    /**
     * workspaces.rs:18-27 validate_name：去首尾空白后 1-64 字符（chars().count() 按
     * 码点——Java 的 String.length() 数 char 会把 CJK 扩展 B 的代理对数两遍，用 codePoints）。
     * 返回<b>修剪后</b>的名字（Rust 返回的就是 trim 结果，落库的是它）。
     */
    private static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.codePoints().count() > 64) {
            throw AppException.invalid("bad_name", "Name must be 1-64 characters");
        }
        return name;
    }

    @PreAuthorize("@ss.hasPermi('knowledge:workspace:edit')")
    @PostMapping("/api/v1/workspaces")
    public WorkspaceRow createWorkspace(@RequestBody NameReq req, HttpServletRequest request) {
        UserRow user = user(request);
        String name = validateName(req.name());
        return store.createWorkspace(user.orgId(), user.id(), name);
    }

    // ---- PATCH /api/v1/workspaces/{id}（workspaces.rs:58-68 rename：ws Admin） ----

    @PreAuthorize("@ss.hasPermi('knowledge:workspace:edit')")
    @PatchMapping("/api/v1/workspaces/{id}")
    public WorkspaceRow renameWorkspace(@PathVariable UUID id, @RequestBody NameReq req,
                                        HttpServletRequest request) {
        UserRow user = user(request);
        // 校验先于鉴权（Rust 同序）：名字不合法就别去探测工作区存在性
        String name = validateName(req.name());
        wsAccess.requireRole(user, id, Role.ADMIN);
        return store.renameWorkspace(id, name);
    }

    // ---- DELETE /api/v1/workspaces/{id}（workspaces.rs:70-78 delete：ws Owner） ----

    @PreAuthorize("@ss.hasPermi('knowledge:workspace:edit')")
    @DeleteMapping("/api/v1/workspaces/{id}")
    public OkResponse deleteWorkspace(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        wsAccess.requireRole(user, id, Role.OWNER);
        store.deleteWorkspace(id);
        return new OkResponse(true);
    }

    // ---- POST /api/v1/workspaces/{id}/kbs（kbs.rs:74-126 create：ws Admin+ 或系统管理员） ----

    /** kbs.rs:13-28 CreateKbReq（ontology_packs 缺省空数组——无默认包 #580）。 */
    /**
     * kbs.rs:13-28 CreateKbReq：name 必填（serde String）；kind/description/visibility/
     * ontology_packs 全可选（#[serde(default)]——Option 与空 Vec；ontology_packs 缺省
     * 空数组，无默认包 #580）。请求体按 OntologyController 先例读 JsonNode：全局
     * fail-on-missing-creator-properties 让 record 全参必填，serde 的「Option 隐式可省」
     * （missing_field 反序列化器对 Option 回 None）用显式取键翻译。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:workspace:edit')")
    @PostMapping("/api/v1/workspaces/{id}/kbs")
    public KbRow createKb(@PathVariable("id") UUID workspaceId, @RequestBody JsonNode body,
                          HttpServletRequest request) {
        UserRow user = user(request);
        JsonNode nameNode = body.get("name");
        String name = nameNode != null && nameNode.isTextual() ? nameNode.asText().trim() : "";
        if (name.isEmpty() || name.codePoints().count() > 64) {
            throw AppException.invalid("bad_name", "Name must be 1-64 characters");
        }
        String kind = optText(body, "kind");
        if (kind == null) {
            kind = "knowledge";
        }
        if (!"knowledge".equals(kind) && !"memory".equals(kind)) {
            throw AppException.validation("kind must be 'knowledge' or 'memory'");
        }
        // 建库是部署管理动作（入口在 System settings）：系统管理员或工作区 Admin+
        Role wsRole = wsAccess.requireRole(user, workspaceId, Role.VIEWER);
        if (!user.isAdmin() && wsRole.rank() < Role.ADMIN.rank()) {
            throw AppException.FORBIDDEN;
        }
        KbRow kb = store.createKb(workspaceId, name, kind, optText(body, "description"),
                optText(body, "visibility"), user.id());
        // 装包在库建成之后（Rust 同序）：未知包/装包失败不回滚已建的库
        imports.installPacks(kb.id(), user.id(), packList(body.get("ontology_packs")));
        return kb;
    }

    /** ontology_packs: [id]（字符串数组；缺省/空 = 不装包）。 */
    private static List<String> packList(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode id : n) {
            out.add(id.asText());
        }
        return out;
    }

    /**
     * optXxx 族的<b>类型容忍面</b>（任务 6 审查 M-3 如实注明）：Rust serde 的
     * {@code Option<String>} 对 JSON 数字/布尔等错型键回 422（invalid type），这里的
     * optText/optBoolean/optInt 把错型当<b>缺省</b>（null = 不改）——契约剧本与前端只送
     * 正型键（错型请求不在冻结面内），此差异为已声明的容忍面，不追平。
     */
    private static String optText(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        return n.asText();
    }

    /**
     * install_packs（kbs.rs:400-434）的真身编排已移 {@link ImportSupport}（M5 任务 5
     * 起 422 降级退役；本控制器只留注入与调用点）。
     */

    // ---- PATCH /api/v1/kbs/{id}（kbs.rs:180-225 update：KB Admin，十字段可选） ----

    /**
     * kbs.rs:30-58 UpdateKbReq：十字段全可选（serde 的 Option 隐式可省）。名字在这里
     * <b>不校验长度</b>（Rust update 亦然——只有 workspaces 的 validate_name 管 1-64）；
     * data_conventions 清空送空串、null/缺键 = 不改（与 description 同一约定）。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:workspace:edit')")
    @PatchMapping("/api/v1/kbs/{id}")
    public KbRow updateKb(@PathVariable UUID id, @RequestBody JsonNode body,
                          HttpServletRequest request) {
        UserRow user = user(request);
        kbAccess.requireKb(user, id, Role.ADMIN);
        String name = optText(body, "name");
        String visibility = optText(body, "visibility");
        String dataConventions = optText(body, "data_conventions");
        Boolean governance = optBoolean(body, "governance");
        KbRow kb = store.updateKb(id,
                name == null ? null : name.trim(),
                optText(body, "description"),
                visibility,
                optBoolean(body, "auto_extend_ontology"),
                optText(body, "ontology_lang"),
                optBoolean(body, "materialize_inferences"),
                optInt(body, "inference_interval_minutes"),
                optBoolean(body, "auto_type_resolution"),
                governance,
                dataConventions == null ? null : dataConventions.trim());
        // 打开开关就自动开始处理：排一轮，同库已排着的不重复（kbs.rs:202-206）
        if (Boolean.TRUE.equals(governance)) {
            jobs.enqueueUnlessQueued("govern",
                    mapper.createObjectNode().put("kb_id", id.toString()));
        }
        // 审计只记不阻断（kbs.rs:207-223）；data_conventions 只记是否出现
        ObjectNode detail = mapper.createObjectNode();
        putOrNull(detail, "name", name);
        putOrNull(detail, "visibility", visibility);
        if (governance == null) {
            detail.putNull("governance");
        } else {
            detail.put("governance", governance);
        }
        detail.put("data_conventions", dataConventions != null);
        audit.record(client(request), id, user.id(), "kb.updated", "kb", id,
                detail.toString());
        return kb;
    }

    private static Boolean optBoolean(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.isBoolean() ? n.asBoolean() : null;
    }

    private static Integer optInt(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.isInt() ? n.asInt() : null;
    }

    // ---- DELETE /api/v1/kbs/{id}（kbs.rs:227-246 delete：KB Admin） ----

    @PreAuthorize("@ss.hasPermi('knowledge:workspace:edit')")
    @DeleteMapping("/api/v1/kbs/{id}")
    public OkResponse deleteKb(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        // kb 先取回（删了就没名字可记——台账 detail 带库名）
        KbRow kb = kbAccess.requireKb(user, id, Role.ADMIN);
        store.deleteKb(id);
        // kb_id 置 null：库已级联删除，事件留在部署层（actor 与库名在 detail）
        audit.record(client(request), null, user.id(), "kb.deleted", "kb", id,
                mapper.createObjectNode().put("name", kb.name()).toString());
        return new OkResponse(true);
    }

    private static void putOrNull(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

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
