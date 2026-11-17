package tech.seexun.jknow.web.knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.SourceRow;
import tech.seexun.jknow.store.entity.SourceView;
import tech.seexun.jknow.store.entity.SyncRunView;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.secrets.SourceSecretKeys;

/**
 * 来源域全生命周期端点（对齐旧仓库 api/sources_routes.rs:62-362/795-833，M2 任务 4）：
 * 15 kind CRUD（keep_secrets 三态/封印只进不出）/sync_now 幂等/runs（每源 50 条修剪）/
 * token 明文查看与轮换（Editor）/re-extract（422 source_not_extracted）/
 * cleanup_missing（与单篇删除同一收尾，一批一次 settle）。
 *
 * <p><b>config 只进不出</b>：列表 SQL 侧剔（Rust {@code config - $2::text[]}，Java 等价
 * 在 SourcesService.list）、create/update 响应 mask_secrets（开封后剔除）、密钥查看走
 * Editor 专用 token 端点——客户端没有办法把旧值原样送回来，keep_secrets 规则只能长在
 * 路由层（sources_routes.rs:187-190）。
 */
@RestController
public class SourcesController {

    private static final Logger log = LoggerFactory.getLogger(SourcesController.class);

    /** sources_routes.rs:340 runs 的固定页大小。 */
    private static final long RUNS_LIMIT = 20;

    /** sources_routes.rs:107 拉取型建好立即同步一次（有配置即产出，无需等下一个调度周期）。 */
    private static final List<String> IMMEDIATELY_SYNCED = List.of("url", "rss", "custom");

    private final SourcesService sources;
    private final ReasoningService reasoning;
    private final DocumentsWriteService documents;
    private final SourceIngestService ingest;
    private final JobsService jobs;
    private final KbAccess rbac;
    private final AuditService audit;
    private final AppEventBus events;
    private final ChunkIndexer search;
    private final KbMapper kbs;
    private final ObjectMapper mapper;

    public SourcesController(SourcesService sources, DocumentsWriteService documents,
                             SourceIngestService ingest, JobsService jobs, KbAccess rbac,
                             AuditService audit, AppEventBus events, ChunkIndexer search,
                             KbMapper kbs, ObjectMapper mapper, ReasoningService reasoning) {
        this.sources = sources;
        this.reasoning = reasoning;
        this.documents = documents;
        this.ingest = ingest;
        this.jobs = jobs;
        this.rbac = rbac;
        this.audit = audit;
        this.events = events;
        this.search = search;
        this.kbs = kbs;
        this.mapper = mapper;
    }

    /** create/update 响应里的来源（models.rs:142-165 Source 的序列出面：无 ingest_token）。 */
    public record SourceResponse(UUID id, UUID kbId, String kind, String name, JsonNode config,
                                 String icon, Integer syncIntervalMinutes, String syncCron,
                                 OffsetDateTime lastSyncAt, String lastSyncStatus,
                                 String lastSyncError, int lastSyncAdded,
                                 OffsetDateTime createdAt) {
    }

    public record ListResponse(List<SourceView> sources) {
    }

    public record CreateResponse(SourceResponse source, String ingestToken) {
    }

    public record UpdateResponse(SourceResponse source) {
    }

    public record OkResponse(boolean ok) {
    }

    public record QueuedResponse(boolean queued) {
    }

    public record RunsResponse(List<SyncRunView> runs) {
    }

    public record CountResponse(int queued) {
    }

    public record DeletedResponse(int deleted) {
    }

    public record TokenResponse(String ingestToken) {
    }

    // ---- GET /api/v1/kbs/{kbId}/sources（sources_routes.rs:62-70 list，Viewer） ----

    @GetMapping("/api/v1/kbs/{kbId}/sources")
    public ListResponse list(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        return new ListResponse(sources.list(kbId));
    }

    // ---- POST /api/v1/kbs/{kbId}/sources（sources_routes.rs:87-137 create，Editor） ----

    @PostMapping("/api/v1/kbs/{kbId}/sources")
    public CreateResponse create(@PathVariable UUID kbId, @RequestBody JsonNode body,
                                 HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String kind = body.path("kind").asText(null);
        JsonNode config = body.path("config");
        validateRssConfig(kind, config);
        SourceRow source = sources.create(kbId, kind, body.path("name").asText(null), config,
                textOrNull(body, "icon"),
                intOrNull(body, "sync_interval_minutes"), textOrNull(body, "sync_cron"));
        // 拉取型来源建好立即同步一次
        if (IMMEDIATELY_SYNCED.contains(source.kind())) {
            sources.markQueued(source.id());
            jobs.enqueue("sync_source",
                    mapper.createObjectNode().put("source_id", source.id().toString()));
        }
        // api 来源：生成专属推送密钥（此后可随时经 token 端点查看）
        String ingestToken = null;
        if (SourcesService.hasPushToken(source)) {
            ingestToken = PushTokens.newIngestToken();
            sources.setIngestToken(source.id(), ingestToken);
        }
        events.emitSource(kbId);
        audit.record(client(request), kbId, user.id(), "source.created", "source", source.id(),
                mapper.createObjectNode().put("kind", source.kind()).put("name", source.name())
                        .toString());
        return new CreateResponse(mask(source), ingestToken);
    }

    // ---- PATCH /api/v1/kbs/{kbId}/sources/{sourceId}（sources_routes.rs:230-269 update） ----

    @PatchMapping("/api/v1/kbs/{kbId}/sources/{sourceId}")
    public UpdateResponse update(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                                 @RequestBody JsonNode body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        SourceRow existing = sourceInKb(kbId, sourceId);
        // 凭据只进不出：响应从不回显，表单留空/没传=保留库里原值，显式 null=删，有值=换
        JsonNode config = body.get("config");
        if (config != null && config.isObject()) {
            keepSecrets((ObjectNode) config, existing.config());
            validateRssConfig(existing.kind(), config);
        }
        JsonNode schedule = body.get("schedule");
        boolean scheduleTouched = schedule != null && schedule.isObject();
        SourceRow source = sources.update(sourceId, textOrNull(body, "name"), config,
                textOrNull(body, "icon"),
                scheduleTouched ? intOrNull(schedule, "sync_interval_minutes") : null,
                scheduleTouched ? textOrNull(schedule, "sync_cron") : null,
                scheduleTouched);
        events.emitSource(kbId);
        // 审计不落凭据：config 只记「改没改」（name 没传记 null，与 Rust json! 同形）
        ObjectNode detail = mapper.createObjectNode();
        detail.put("name", body.path("name").isTextual() ? body.path("name").asText() : null);
        detail.put("config_changed", config != null);
        audit.record(client(request), kbId, user.id(), "source.updated", "source", sourceId,
                detail.toString());
        return new UpdateResponse(mask(source));
    }

    // ---- DELETE /api/v1/kbs/{kbId}/sources/{sourceId}（sources_routes.rs:302-330） ----

    @DeleteMapping("/api/v1/kbs/{kbId}/sources/{sourceId}")
    public OkResponse delete(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // Memory 来源常驻：记忆空间不因来源整理而蒸发（记忆文档本身可在 Library 删除）
        SourceRow source = sourceInKb(kbId, sourceId);
        if (SourcesService.MEMORY_SOURCE_KIND.equals(source.kind())) {
            throw AppException.invalid("memory_source_permanent", "The Memory source is permanent.");
        }
        sources.delete(sourceId);
        events.emitSource(kbId);
        audit.record(client(request), kbId, user.id(), "source.deleted", "source", sourceId, "{}");
        return new OkResponse(true);
    }

    // ---- POST .../sync（sources_routes.rs:344-362 sync_now：mark_queued 幂等） ----

    @PostMapping("/api/v1/kbs/{kbId}/sources/{sourceId}/sync")
    public QueuedResponse syncNow(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                                  HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        sourceInKb(kbId, sourceId);
        boolean queued = sources.markQueued(sourceId);
        if (queued) {
            jobs.enqueue("sync_source",
                    mapper.createObjectNode().put("source_id", sourceId.toString()));
            events.emitSource(kbId);
        }
        return new QueuedResponse(queued);
    }

    // ---- GET .../runs（sources_routes.rs:333-342，Viewer） ----

    @GetMapping("/api/v1/kbs/{kbId}/sources/{sourceId}/runs")
    public RunsResponse runs(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        sourceInKb(kbId, sourceId);
        return new RunsResponse(sources.listRuns(sourceId, RUNS_LIMIT));
    }

    // ---- POST .../re-extract（sources_routes.rs:795-833 来源级全量重抽） ----

    @PostMapping("/api/v1/kbs/{kbId}/sources/{sourceId}/re-extract")
    public CountResponse reExtract(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                                   HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        SourceRow source = sourceInKb(kbId, sourceId);
        // queue_extraction 自己也会把这种来源的文档滤掉，那样回的是「排了 0 篇」——
        // 点按钮的人该听到的是为什么
        if (!source.extracts()) {
            throw AppException.invalid("source_not_extracted",
                    "Documents under this source are searched, not extracted");
        }
        List<UUID> ids = documents.queueExtraction(kbId, sourceId);
        for (UUID id : ids) {
            events.emitDocument(kbId, id);
        }
        audit.record(client(request), kbId, user.id(), "source.re_extract", "source", sourceId,
                mapper.createObjectNode().put("name", source.name())
                        .put("documents", ids.size()).toString());
        return new CountResponse(ids.size());
    }

    // ---- POST .../missing/cleanup（sources_routes.rs:271-296 批量清理缺文件） ----

    @PostMapping("/api/v1/kbs/{kbId}/sources/{sourceId}/missing/cleanup")
    public DeletedResponse cleanupMissing(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                                          HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        sourceInKb(kbId, sourceId);
        List<UUID> ids = ingest.listMissing(sourceId);
        for (UUID id : ids) {
            documents.delete(kbId, id, user.id());
            try {
                search.deleteDocument(kbId, id);
            } catch (IOException e) {
                throw AppException.other("search delete failed", e);
            }
        }
        // 与单篇删除同一个收尾：前提没了，派生当场跟上（#875）。一批只推一遍
        if (!ids.isEmpty()) {
            settleDerivations(kbId);
        }
        events.emitSource(kbId);
        return new DeletedResponse(ids.size());
    }

    // ---- GET .../token（sources_routes.rs:145-157：Editor；密钥明文只在此出） ----

    @GetMapping("/api/v1/kbs/{kbId}/sources/{sourceId}/token")
    public TokenResponse getToken(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                                  HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        SourceRow source = sourceInKb(kbId, sourceId);
        if (!SourcesService.hasPushToken(source)) {
            throw AppException.NOT_FOUND;
        }
        return new TokenResponse(source.ingestToken());
    }

    // ---- POST .../rotate-token（sources_routes.rs:159-173：旧密钥立即失效） ----

    @PostMapping("/api/v1/kbs/{kbId}/sources/{sourceId}/rotate-token")
    public TokenResponse rotateToken(@PathVariable UUID kbId, @PathVariable UUID sourceId,
                                     HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        SourceRow source = sourceInKb(kbId, sourceId);
        if (!SourcesService.hasPushToken(source)) {
            throw AppException.NOT_FOUND;
        }
        String token = PushTokens.newIngestToken();
        sources.setIngestToken(sourceId, token);
        return new TokenResponse(token);
    }

    // ==== 路由层共用 ====

    /**
     * sources_routes.rs:50-60 source_in_kb：require_kb 只查人对库的权限；来源 id 是另一个
     * 维度——不比对的话，A 库的 Editor 拿着 B 库来源的 id 就能同步、清理、删除它。
     * 不属于就当不存在（404）。
     */
    private SourceRow sourceInKb(UUID kbId, UUID sourceId) {
        SourceRow source = sources.get(sourceId);
        if (!source.kbId().equals(kbId)) {
            throw AppException.NOT_FOUND;
        }
        return source;
    }

    /** sources_routes.rs:180-185：rss 的 content_mode 只在建/改 rss 时校验。 */
    private static void validateRssConfig(String kind, JsonNode config) {
        if ("rss".equals(kind)) {
            SourcesService.rssContentMode(config);
        }
    }

    /**
     * 更新时凭据的合并规则（sources_routes.rs:190-210 逐字）：新配置里<b>没有</b>这个键
     * 或值是空串 → 保留库里的原值（表单留空就是「别动」）；显式 {@code null} → 删掉；
     * 其余照新值。existing 是 opened 后的明文。
     */
    static void keepSecrets(ObjectNode next, JsonNode existing) {
        for (String key : SourceSecretKeys.KEYS) {
            boolean keep;
            JsonNode value = next.get(key);
            if (value == null) {
                keep = true;
            } else if (value.isNull()) {
                next.remove(key);
                keep = false;
            } else {
                keep = value.isTextual() && value.asText().trim().isEmpty();
            }
            if (keep) {
                next.remove(key);
                JsonNode prev = existing == null ? null : existing.get(key);
                if (prev != null) {
                    next.set(key, prev.deepCopy());
                }
            }
        }
    }

    /** sources_routes.rs:175-178 mask_secrets：响应前剔除凭据（只进不出）。 */
    static SourceResponse mask(SourceRow source) {
        ObjectNode config = source.config() != null && source.config().isObject()
                ? (ObjectNode) source.config().deepCopy() : null;
        if (config != null) {
            for (String key : SourceSecretKeys.KEYS) {
                config.remove(key);
            }
        }
        return new SourceResponse(source.id(), source.kbId(), source.kind(), source.name(),
                config, source.icon(), source.syncIntervalMinutes(), source.syncCron(),
                source.lastSyncAt(), source.lastSyncStatus(), source.lastSyncError(),
                source.lastSyncAdded(), source.createdAt());
    }

    /**
     * 前提变了就重推一遍，让派生跟上——开关关着的库不推（documents_routes.rs:561-571
     * settle_derivations 逐字语义）。删除、撤销、同步复活三条路共用。M3 任务 7 换真身：
     * reasoning::materialize 的对等物是 {@link ReasoningService#materialize}（任务 6 落的
     * 派生物化——断言边检查+业务规则不动点+六元组对账/派生 stale 作废，单一求解源）；
     * 公理违规/本体缺陷的对账（settle_violations/settle_defects）在 {@link ReasoningService#run}
     * 一侧（materialize_inferences 定时任务与本体编辑钩子触发，recon §4.2 的说明）。
     */
    private void settleDerivations(UUID kbId) {
        KbRow kb = kbs.selectById(kbId);
        // kb 缺行按 NotFound 上抛（kbs.rs:68-73 kbs::get 的口径；审查 M-2）——静默跳过
        // 会吞掉「库没了还来收尾」的错配
        if (kb == null) {
            throw AppException.NOT_FOUND;
        }
        if (kb.materializeInferences()) {
            reasoning.materialize(kbId);
        }
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Integer intOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
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

    /** 供 ApiPushController 复用的 doc_time 解析（RFC 3339）。 */
    static OffsetDateTime parseDocTime(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw AppException.validation("doc_time must be an RFC 3339 timestamp");
        }
    }
}
