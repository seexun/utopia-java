package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 失败任务回队列（#216）与 noop 队列验证端点（对齐旧仓库
 * crates/utopia-server/src/api/jobs_routes.rs:69-127 + api/mod.rs:598/642-650）。
 * 两个重排入口：库内（Editor）与全局（管理员），范围可按种类与失败时间收窄——
 * 告警上的「再跑一遍」传的就是那次故障的时间窗。
 *
 * <p><b>GET /kbs/{id}/jobs/failed 与 GET /kbs/{id}/jobs/{jobId} 不在这里</b>：
 * M1 已随 documents 读族落进 {@code DocumentsController}（同一 KB_SCOPE 三键 SQL，
 * DocumentsMapper.countFailedJobs/selectJobInKb），此处再加映射即 Ambiguous mapping。
 * {@link JobsService#failedCount}/{@link JobsService#statusInKb} 是框架侧同一 SQL 的
 * 服务面（jobs.rs:306-341 一并搬迁），由测试与后续域复用。
 *
 * <p><b>requeue 双路合计</b>（jobs_routes.rs:22-30，M3 任务 9 补齐 RSS 支线）：通用路
 * 排除 hydrate_rss_entry（jobs.rs:285）；RSS 自己的重试走
 * rss_full_content::requeue_failed 的对等物——同一套源锁/代际/purge 栅栏/容量上限。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:ingest:list')")
public class JobsController {

    private final JobsService jobs;
    private final KbAccess kbAccess;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final tech.seexun.jknow.store.RssFullContentService rss;

    public JobsController(JobsService jobs, KbAccess kbAccess, AuditService audit,
                          ObjectMapper mapper,
                          tech.seexun.jknow.store.RssFullContentService rss) {
        this.jobs = jobs;
        this.kbAccess = kbAccess;
        this.audit = audit;
        this.mapper = mapper;
        this.rss = rss;
    }

    /**
     * jobs_routes.rs:35-42 RequeueBody：两字段 #[serde(default)]（缺键=None）。全局
     * fail-on-missing-creator-properties 对记录组件不豁免可缺省项——沿用
     * SearchReqDeserializer 先例自定义反序列化恢复 serde 缺省语义。
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = JobsController.RequeueBodyDeserializer.class)
    public record RequeueBody(String kind, OffsetDateTime failedSince) {
    }

    static final class RequeueBodyDeserializer
            extends com.fasterxml.jackson.databind.JsonDeserializer<RequeueBody> {
        @Override
        public RequeueBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                       com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode n = p.getCodec().readTree(p);
            String kind = n.hasNonNull("kind") && n.get("kind").isTextual()
                    ? n.get("kind").asText() : null;
            OffsetDateTime failedSince = null;
            if (n.hasNonNull("failed_since") && n.get("failed_since").isTextual()) {
                try {
                    failedSince = OffsetDateTime.parse(n.get("failed_since").asText());
                } catch (java.time.format.DateTimeParseException e) {
                    throw com.fasterxml.jackson.databind.exc.MismatchedInputException.from(p,
                            RequeueBody.class, "invalid timestamp: "
                                    + n.get("failed_since").asText());
                }
            }
            return new RequeueBody(kind, failedSince);
        }
    }

    public record RequeueResponse(long requeued) {
    }

    public record NoopResponse(long jobId) {
    }

    /** jobs_routes.rs:69-96 requeue_in_kb（Editor）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/jobs/requeue")
    public RequeueResponse requeueInKb(@PathVariable UUID kbId,
                                       @RequestBody(required = false) RequeueBody body,
                                       HttpServletRequest request) {
        UserRow user = user(request);
        kbAccess.requireKb(user, kbId, Role.EDITOR);
        RequeueBody scope = body == null ? new RequeueBody(null, null) : body;
        int requeued = requeueAll(new JobsService.RequeueScope(kbId, scope.kind(),
                scope.failedSince()));
        audit.record(client(request), kbId, user.id(), "jobs.requeued", "kb", kbId, detail(requeued, scope));
        return new RequeueResponse(requeued);
    }

    /** jobs_routes.rs:99-127 requeue_all：系统级告警（没有库的）从这里走，只给管理员。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/jobs/requeue")
    public RequeueResponse requeueAll(@RequestBody(required = false) RequeueBody body,
                                      HttpServletRequest request) {
        UserRow user = user(request);
        if (!user.isAdmin()) {
            throw AppException.FORBIDDEN;
        }
        RequeueBody scope = body == null ? new RequeueBody(null, null) : body;
        int requeued = requeueAll(new JobsService.RequeueScope(null, scope.kind(),
                scope.failedSince()));
        audit.record(client(request), null, user.id(), "jobs.requeued", "system", null, detail(requeued, scope));
        return new RequeueResponse(requeued);
    }

    /** jobs_routes.rs:22-30：两个范围要应用同一套重试策略，并报告合并总数。 */
    private int requeueAll(JobsService.RequeueScope scope) {
        int generic = jobs.requeueFailed(scope);
        int rssCount = rss.requeueFailed(scope);
        return generic + rssCount;
    }

    /**
     * P0 队列验证端点（api/mod.rs:598/642-650 jobs_noop）：入队一个 noop 任务
     * （后续里程碑移除）；worker 未启用的部署里它只是安静排着。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/jobs/noop")
    public NoopResponse noop(HttpServletRequest request) {
        user(request);
        long id = jobs.enqueue("noop", mapper.createObjectNode());
        return new NoopResponse(id);
    }

    private String detail(long requeued, RequeueBody body) {
        return "{\"requeued\":" + requeued
                + ",\"kind\":" + (body.kind() == null ? "null" : "\"" + body.kind() + "\"")
                + ",\"failed_since\":" + (body.failedSince() == null ? "null"
                        : "\"" + body.failedSince() + "\"") + "}";
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
