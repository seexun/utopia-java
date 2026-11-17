package tech.seexun.jknow.web.knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.SourceRow;

/**
 * api 来源推送（sources_routes.rs:500-555 push）与推陈述（:563-793 push_statements）：
 * 来源专属密钥认证（Bearer ingest_token，常量时间比对），<b>无会话</b>——SessionFilter
 * 对这两条路径放行到路由层，源不存在/kind 不符由这里 404，未认证请求不写任何记录
 * （不给匿名流量制造落库路径）。
 *
 * <p>三路身份语义：新 external_id → 新增；同 ID 同内容 → 无操作；同 ID 新内容 →
 * 原地更新+版本记录。认证通过后每次推送都记一条 run（含格式错误——集成调试全靠它）。
 * 推送失败的两种性质分开（sources_routes.rs:427-450）：调用方发错了（Rejected）记进
 * run 但不算来源同步失败；我们这边没接住（Failed）才把来源标 failed 并进告警中心。
 */
@RestController
public class ApiPushController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourcesService sources;
    private final SourceIngestService ingest;
    private final AppEventBus events;

    public ApiPushController(SourcesService sources, SourceIngestService ingest,
                             AppEventBus events) {
        this.sources = sources;
        this.ingest = ingest;
        this.events = events;
    }

    public record ActionResponse(String action) {
    }

    // ---- POST /api/v1/sources/{sourceId}/ingest（sources_routes.rs:504-555 push） ----

    @PostMapping("/api/v1/sources/{sourceId}/ingest")
    public ActionResponse push(@PathVariable UUID sourceId, @RequestBody byte[] raw,
                               HttpServletRequest request) {
        SourceRow source = requirePushSource(sourceId, "api");
        requireBearer(source, request);
        UUID run = sources.startRun(source.id());
        try {
            SourceIngestService.IngestAction action = handlePush(source, raw);
            return finish(source, run, action);
        } catch (StatementsContract.StatementRejectedException e) {
            // 4xx：负载不合格（JSON 解析、缺字段）——记进 run 供集成调试，但不算来源
            // 同步失败：来源没坏，是那一次请求不合格
            return fail(source, run, e.getMessage(), false);
        } catch (PushFailedException e) {
            // 摄入本身失败：该把来源标成 failed 并进告警中心
            return fail(source, run, e.getMessage(), true);
        }
    }

    // ---- POST /api/v1/sources/{sourceId}/statements（sources_routes.rs:563-793） ----

    /**
     * 推陈述（0054）：请求体就是开放抽取契约（e/s/n）外加 api 推送的那层信封。同一把
     * 钥匙、同一套身份语义（{@code statements:{external_id}}）、同一份 run 记录；不同
     * 的只有两点，都在门口定死：载荷照原样成为文档，一整块；信封与契约之外的任何键
     * 都拒收。
     */
    @PostMapping("/api/v1/sources/{sourceId}/statements")
    public ActionResponse pushStatements(@PathVariable UUID sourceId, @RequestBody byte[] raw,
                                         HttpServletRequest request) {
        SourceRow source = requirePushSource(sourceId, "statements");
        requireBearer(source, request);
        UUID run = sources.startRun(source.id());
        try {
            SourceIngestService.IngestAction action = handleStatementsPush(source, raw);
            return finish(source, run, action);
        } catch (StatementsContract.StatementRejectedException e) {
            return fail(source, run, e.getMessage(), false);
        } catch (PushFailedException e) {
            return fail(source, run, e.getMessage(), true);
        }
    }

    // ==== 认证（sources_routes.rs:505-524） ====

    /** 源不存在或 kind 不符 → 404（与其它来源端点同一「当不存在」口径）。 */
    private SourceRow requirePushSource(UUID sourceId, String kind) {
        SourceRow source;
        try {
            source = sources.get(sourceId);
        } catch (AppException e) {
            throw AppException.NOT_FOUND;
        }
        if (!kind.equals(source.kind())) {
            throw AppException.NOT_FOUND;
        }
        return source;
    }

    /** Bearer 密钥校验（常量时间比对）：缺失/空/不符一律 401。 */
    private static void requireBearer(SourceRow source, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        if (token == null || token.isEmpty()) {
            throw AppException.UNAUTHORIZED;
        }
        if (!PushTokens.pushKeyMatches(source.ingestToken(), token)) {
            throw AppException.UNAUTHORIZED;
        }
    }

    // ==== 处理（sources_routes.rs:447-498 handle_push / :762-793 handle_statements_push） ====

    private SourceIngestService.IngestAction handlePush(SourceRow source, byte[] raw) {
        JsonNode body;
        try {
            body = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw rejected("Invalid JSON payload: " + e.getMessage());
        }
        String filename = body.path("filename").asText("");
        String content = body.path("content").asText("");
        String externalId = body.path("external_id").isTextual()
                ? body.path("external_id").asText().trim() : "";
        String identity = externalId.isEmpty() ? filename.trim() : externalId;
        if (identity.isEmpty()) {
            throw rejected("external_id or filename is required");
        }
        String key = "api:" + identity;

        // 墓碑：标记「不在来源中」（与 custom 的 deleted[] 同一条路径），content 可省略
        if (body.path("deleted").asBoolean(false)) {
            try {
                ingest.markMissingKeys(source.id(), List.of(key));
            } catch (Exception e) {
                throw new PushFailedException(messageOf(e));
            }
            return SourceIngestService.IngestAction.Tombstoned;
        }

        if (filename.trim().isEmpty() || content.trim().isEmpty()) {
            throw rejected("filename and content are required");
        }
        OffsetDateTime docTime = docTime(body);
        try {
            SourceIngestService.IngestAction action = ingest.ingestItem(source.kbId(),
                    source.id(), key, filename.trim(), "text/plain",
                    content.getBytes(StandardCharsets.UTF_8), docTime);
            // 失而复得：曾被墓碑标记的身份再次正常推送，摘掉 missing 标记
            ingest.clearMissingKeys(source.id(), List.of(key));
            return action;
        } catch (Exception e) {
            throw new PushFailedException(messageOf(e));
        }
    }

    private SourceIngestService.IngestAction handleStatementsPush(SourceRow source, byte[] raw) {
        StatementsContract.Validated validated = StatementsContract.validate(raw);
        String key = "statements:" + validated.externalId();
        if (validated.deleted()) {
            try {
                ingest.markMissingKeys(source.id(), List.of(key));
            } catch (Exception e) {
                throw new PushFailedException(messageOf(e));
            }
            return SourceIngestService.IngestAction.Tombstoned;
        }
        // 整份一块：mimeType application/json，文件名 {identity}.json
        String filename = validated.externalId() + ".json";
        try {
            SourceIngestService.IngestAction action = ingest.ingestItem(source.kbId(),
                    source.id(), key, filename, "application/json",
                    validated.content().getBytes(StandardCharsets.UTF_8), validated.docTime());
            ingest.clearMissingKeys(source.id(), List.of(key));
            return action;
        } catch (Exception e) {
            throw new PushFailedException(messageOf(e));
        }
    }

    private static StatementsContract.StatementRejectedException rejected(String message) {
        return new StatementsContract.StatementRejectedException(message);
    }

    // ==== run 收尾（sources_routes.rs:527-554） ====

    private ActionResponse finish(SourceRow source, UUID run,
                                  SourceIngestService.IngestAction action) {
        int created = action == SourceIngestService.IngestAction.Created ? 1 : 0;
        int updated = action == SourceIngestService.IngestAction.Updated
                || action == SourceIngestService.IngestAction.Moved ? 1 : 0;
        sources.finishRun(run, source.id(), null, created, updated);
        // 来源行同步反映「上一次推送」：操作条/左栏状态点直接可用
        sources.finishSync(source.id(), null, created);
        events.emitSource(source.kbId());
        return new ActionResponse(action.wire());
    }

    private ActionResponse fail(SourceRow source, UUID run, String message,
                                boolean failedSync) {
        sources.finishRun(run, source.id(), message, 0, 0);
        if (failedSync) {
            sources.finishSync(source.id(), message, 0);
        }
        events.emitSource(source.kbId());
        throw AppException.validation(message);
    }

    private static OffsetDateTime docTime(JsonNode body) {
        JsonNode node = body.get("doc_time");
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (Exception e) {
            throw rejected("Invalid JSON payload: invalid doc_time");
        }
    }

    /** 嵌套异常链拼一句（Rust format!("{e}") 的近似：取最深一层可读消息）。 */
    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    /** 摄入本身失败（我们没接住）——来源标 failed 并进告警中心。 */
    static final class PushFailedException extends RuntimeException {
        PushFailedException(String message) {
            super(message);
        }
    }
}
