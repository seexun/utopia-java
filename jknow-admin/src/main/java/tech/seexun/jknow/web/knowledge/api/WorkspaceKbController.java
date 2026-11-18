package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.web.knowledge.rbac.WorkspaceAccess;
import tech.seexun.jknow.store.entity.AuditEventView;
import tech.seexun.jknow.store.entity.KbMemberView;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.MemberView;
import tech.seexun.jknow.store.entity.MyKbInfo;
import tech.seexun.jknow.store.entity.Readiness;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.entity.WorkspaceRow;
import tech.seexun.jknow.store.mapper.AuditMapper;
import tech.seexun.jknow.store.mapper.KbFamilyMapper;
import tech.seexun.jknow.store.mapper.WorkspaceMapper;

/**
 * workspace-kb 读族端点（对齐旧仓库 api/workspaces.rs、api/kbs.rs、api/members_routes.rs、
 * api/settings_routes.rs GET 面；M1 任务 5）。ws/kb 建改删六写端点自 M4 任务 6 起在
 * {@link WorkspaceKbWriteController}；成员与 llm 设置写面仍归后续里程碑；
 * GET /kbs/{id}/export 的 RDF 序列化器未实现（挂账见 WAIVERS.md）。
 *
 * <p>非 SQL 语义逐字：kbs.rs:60-71 list=Viewer 起步（先 require_role）；get_one 附带调用者
 * 角色（kbs.rs:167-177）；my_kbs 四键行（kbs.rs:129-154）；audit 分页筛选四参数
 * （kbs.rs:323-383，action 前缀匹配、日期按天或 RFC3339）；settings-get 脱敏视图——
 * 四把 api_key 只回「是否已配置」布尔（settings_routes.rs:15-43），封印串绝不出库。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:workspace:list')")
public class WorkspaceKbController {

    private final WorkspaceMapper workspaces;
    private final KbFamilyMapper kbFamily;
    private final AuditMapper audit;
    private final WorkspaceAccess wsAccess;
    private final KbAccess kbAccess;
    private final ObjectMapper mapper;

    public WorkspaceKbController(WorkspaceMapper workspaces, KbFamilyMapper kbFamily,
                                 AuditMapper audit, WorkspaceAccess wsAccess, KbAccess kbAccess,
                                 ObjectMapper mapper) {
        this.workspaces = workspaces;
        this.kbFamily = kbFamily;
        this.audit = audit;
        this.wsAccess = wsAccess;
        this.kbAccess = kbAccess;
        this.mapper = mapper;
    }

    // ---- GET /api/v1/workspaces（workspaces.rs:29-35 list） ----

    @GetMapping("/api/v1/workspaces")
    public List<WorkspaceRow> listWorkspaces(HttpServletRequest request) {
        UserRow user = user(request);
        return workspaces.listForUser(user.id());
    }

    // ---- GET /api/v1/workspaces/{id}（workspaces.rs:47-56 get_one：附调用者角色） ----

    public record WorkspaceResponse(WorkspaceRow workspace, String role) {
    }

    @GetMapping("/api/v1/workspaces/{id}")
    public WorkspaceResponse getWorkspace(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        Role role = wsAccess.requireRole(user, id, Role.VIEWER);
        WorkspaceRow ws = workspaces.selectById(id);
        if (ws == null) {
            throw AppException.NOT_FOUND;
        }
        return new WorkspaceResponse(ws, role.asStr());
    }

    // ---- GET /api/v1/workspaces/{id}/kbs（kbs.rs:61-71 list：Viewer 起步的可见列表） ----

    @GetMapping("/api/v1/workspaces/{id}/kbs")
    public List<KbRow> listKbs(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        wsAccess.requireRole(user, id, Role.VIEWER);
        return kbFamily.listVisible(id, user.id(), user.isAdmin());
    }

    // ---- GET /api/v1/workspaces/{id}/my-kbs（kbs.rs:129-154 my_kbs 四键行组装） ----

    @GetMapping("/api/v1/workspaces/{id}/my-kbs")
    public JsonNode myKbs(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        wsAccess.requireRole(user, id, Role.VIEWER);
        List<KbRow> kbs = kbFamily.listVisible(id, user.id(), user.isAdmin());
        List<UUID> ids = kbs.stream().map(KbRow::id).toList();
        List<MyKbInfo> infos = ids.isEmpty() ? List.of() : kbFamily.selectMyKbInfos(ids, user.id());
        List<JsonNode> rows = new ArrayList<>(kbs.size());
        for (KbRow kb : kbs) {
            MyKbInfo info = infos.stream().filter(i -> i.kbId().equals(kb.id())).findFirst().orElse(null);
            Role role = kbAccess.kbRole(user, kb);
            ObjectNode row = mapper.createObjectNode();
            row.set("kb", mapper.valueToTree(kb));
            if (role == null) {
                row.putNull("my_role");
            } else {
                row.put("my_role", role.asStr());
            }
            // info 行由 k.id = ANY($1) 保证逐库都有；null 兜底保持 Rust unwrap_or 的语义
            if (info == null || info.joinedAt() == null) {
                row.putNull("joined_at");
            } else {
                row.put("joined_at", info.joinedAt().toString());
            }
            if (info == null || info.addedByName() == null) {
                row.putNull("added_by_name");
            } else {
                row.put("added_by_name", info.addedByName());
            }
            row.put("doc_count", info == null ? 0 : info.docCount());
            row.put("member_count", info == null ? 0 : info.memberCount());
            rows.add(row);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("kbs", mapper.valueToTree(rows));
        return out;
    }

    // ---- GET /api/v1/workspaces/{id}/members（members_routes.rs:17-27 list） ----

    @GetMapping("/api/v1/workspaces/{id}/members")
    public List<MemberView> wsMembers(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        wsAccess.requireRole(user, id, Role.VIEWER);
        return workspaces.selectMembers(id);
    }

    // ---- GET /api/v1/kbs/{id}（kbs.rs:167-177 get_one：my_role 附在 kb JSON 上） ----

    @GetMapping("/api/v1/kbs/{id}")
    public JsonNode getKb(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        KbRow kb = kbAccess.requireKb(user, id, Role.VIEWER);
        Role role = kbAccess.kbRole(user, kb);
        ObjectNode body = mapper.valueToTree(kb);
        if (role == null) {
            body.putNull("my_role");
        } else {
            body.put("my_role", role.asStr());
        }
        return body;
    }

    // ---- GET /api/v1/kbs/{id}/readiness（kbs.rs:252-259：Viewer 起步） ----

    @GetMapping("/api/v1/kbs/{id}/readiness")
    public Readiness readiness(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        kbAccess.requireKb(user, id, Role.VIEWER);
        return kbFamily.selectReadiness(id);
    }

    // ---- GET /api/v1/kbs/{id}/members（kbs.rs:265-273：库 admin 起步） ----

    public record MembersResponse(List<KbMemberView> members) {
    }

    @GetMapping("/api/v1/kbs/{id}/members")
    public MembersResponse kbMembers(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        kbAccess.requireKb(user, id, Role.ADMIN);
        return new MembersResponse(kbFamily.selectKbMembers(id));
    }

    // ---- GET /api/v1/kbs/{id}/audit（kbs.rs:323-383：admin 起步；分页+四筛选） ----

    public record AuditResponse(List<AuditEventView> events, long total, List<String> actions) {
    }

    @GetMapping("/api/v1/kbs/{id}/audit")
    public AuditResponse auditLog(@PathVariable UUID id,
                                  @RequestParam(required = false) String action,
                                  @RequestParam(required = false) UUID actor,
                                  @RequestParam(required = false) String since,
                                  @RequestParam(required = false) String until,
                                  @RequestParam(required = false) Long limit,
                                  @RequestParam(required = false) Long offset,
                                  HttpServletRequest request) {
        UserRow user = user(request);
        kbAccess.requireKb(user, id, Role.ADMIN);
        long effLimit = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        long effOffset = offset == null ? 0 : Math.max(0, offset);
        String actionPrefix = action == null || action.trim().isEmpty() ? null : action.trim();
        OffsetDateTime sinceAt = parseDay(since);
        OffsetDateTime untilAt = parseDay(until);
        List<AuditEventView> events = audit.selectKbAuditEvents(id, actionPrefix, actor,
                sinceAt, untilAt, effLimit, effOffset);
        long total = audit.countKbAuditEvents(id, actionPrefix, actor, sinceAt, untilAt);
        return new AuditResponse(events, total, audit.selectKbActions(id));
    }

    // ---- GET /api/v1/ontology-packs（kbs.rs:437-451：静态清单，登录即可） ----

    /** ontology_packs.rs:34-80 PACKS 的展示五字段（gz 字节与本任务无关——装包是写路径）。 */
    private static final List<Pack> PACKS = List.of(
            new Pack("schema-org", "schema.org",
                    "People, organizations, products, events, creative works", 1010, 1676),
            new Pack("w3c-org", "W3C Org",
                    "Departments, posts, memberships, reporting lines", 13, 34),
            new Pack("prov-o", "PROV-O",
                    "Provenance: who produced what, when, from which source", 49, 69),
            new Pack("foaf", "FOAF", "People and social relations", 12, 62),
            new Pack("iof-core", "IOF Core", "Industrial manufacturing", 294, 75));

    public record PacksResponse(List<Pack> packs) {
    }

    public record Pack(String id, String name, String summary, int classes, int properties) {
    }

    @GetMapping("/api/v1/ontology-packs")
    public PacksResponse listPacks() {
        return new PacksResponse(PACKS);
    }

    // ---- GET /api/v1/workspaces/{id}/settings（settings_routes.rs:15-43 脱敏视图） ----

    /**
     * 密钥只回传是否已配置（has_*_key 布尔）。四把 api_key 以封印形态留在库里
     * （secrets.rs AES-256-GCM）；本端点**原样读封印列、只折非空布尔，绝不解密**——
     * seal 不产生空串，封印列非空 ⟺ 解密后非空（settings_routes.rs:28 的语义）。
     */
    @GetMapping("/api/v1/workspaces/{id}/settings")
    public JsonNode getSettings(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        wsAccess.requireRole(user, id, Role.ADMIN);
        LlmSettingsRow s = workspaces.selectSettings(id);
        if (s == null) {
            return mapper.createObjectNode();
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("chat_base_url", s.chatBaseUrl());
        out.put("chat_model", s.chatModel());
        out.put("chat_reasoning_effort", s.chatReasoningEffort());
        out.put("has_chat_key", nonEmpty(s.chatApiKey()));
        out.put("embed_base_url", s.embedBaseUrl());
        out.put("embed_model", s.embedModel());
        out.put("embed_dim", s.embedDim());
        out.put("has_embed_key", nonEmpty(s.embedApiKey()));
        out.put("ocr_provider", s.ocrProvider());
        out.put("ocr_base_url", s.ocrBaseUrl());
        out.put("ocr_backend", s.ocrBackend());
        out.put("ocr_model", s.ocrModel());
        out.put("has_ocr_key", nonEmpty(s.ocrApiKey()));
        out.put("transcribe_base_url", s.transcribeBaseUrl());
        out.put("transcribe_model", s.transcribeModel());
        out.put("has_transcribe_key", nonEmpty(s.transcribeApiKey()));
        return out;
    }

    private static boolean nonEmpty(String sealed) {
        return sealed != null && !sealed.isEmpty();
    }

    /**
     * kbs.rs:342-352 parse_day：按天给（YYYY-MM-DD→当日 00:00 UTC）或 RFC3339 都收；
     * 空白当没给；两者都不是 → 422 bad_date "expected YYYY-MM-DD or RFC3339"。
     */
    private static OffsetDateTime parseDay(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        } catch (Exception ignored) {
            // 落 RFC3339 分支
        }
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            throw AppException.invalid("bad_date", "expected YYYY-MM-DD or RFC3339");
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
