package tech.seexun.jknow.web.knowledge.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.store.entity.AlertGroup;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 告警中心 GET 面（alerts_routes.rs:61-97）：**跨库**，不挂在某个 KB 下面。
 * 可见性在 AlertsService→SQL（VISIBLE 谓词只写一份）。read-all/read-group 写步归 M2
 * （剧本定向 rust）。/alerts/events 是 SSE：无会话 401 由 SessionFilter 承担
 * （与 Rust AuthUser 提取器同位），已认证的流式推送 M1 deferred（COVERAGE.md 同款处置）。
 */
@RestController
public class AlertsController {

    /** alerts_routes.rs:22 PAGE：一页几组（弹窗里放得下的量）。 */
    private static final long PAGE = 8;

    /** alerts_routes.rs:24 MAX_PAGE：防有人把 limit 写成 100000 让服务端去数全表。 */
    private static final long MAX_PAGE = 50;

    private final AlertsService alerts;

    public AlertsController(AlertsService alerts) {
        this.alerts = alerts;
    }

    public record ListResponse(List<AlertGroup> items, long total) {
    }

    /** alerts_routes.rs:61-88 list：按组分页，时间倒序。 */
    @GetMapping("/api/v1/alerts")
    public ListResponse list(@RequestParam(required = false) String q,
                             @RequestParam(required = false) Long limit,
                             @RequestParam(required = false) Long offset,
                             HttpServletRequest request) {
        UserRow user = user(request);
        long effLimit = limit == null ? PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        long effOffset = offset == null ? 0 : Math.max(0, offset);
        AlertsService.AlertsPage page = alerts.list(user, q, effLimit, effOffset);
        return new ListResponse(page.items(), page.total());
    }

    /** alerts_routes.rs:91-97 unread：角标（每开一页都要，单独一条路由）。 */
    public record UnreadResponse(long unread) {
    }

    @GetMapping("/api/v1/alerts/unread")
    public UnreadResponse unread(HttpServletRequest request) {
        UserRow user = user(request);
        return new UnreadResponse(alerts.unread(user));
    }

    /** alerts_routes.rs:100-105 ReadGroupBody：组的时间区间，原样来自列表的 earliest/latest。 */
    public record ReadGroupBody(java.util.UUID kbId, String kind,
                                 java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
    }

    /** alerts_routes.rs:107-122 mark_group_read（M5 任务 6 落位）。 */
    @PostMapping("/api/v1/alerts/read-group")
    public java.util.Map<String, Object> readGroup(@RequestBody ReadGroupBody body,
                                                   HttpServletRequest request) {
        UserRow user = user(request);
        return java.util.Map.of("marked", alerts.markGroupRead(user, body.kbId(), body.kind(),
                body.from(), body.to()));
    }

    /** alerts_routes.rs:124-130 mark_all_read（M5 任务 6 落位）。 */
    @PostMapping("/api/v1/alerts/read-all")
    public java.util.Map<String, Object> readAll(HttpServletRequest request) {
        UserRow user = user(request);
        return java.util.Map.of("marked", alerts.markAllRead(user));
    }

    /**
     * alerts_routes.rs:138-163 stream：全局 SSE（事件不带数据，收到的人回头重取列表）。
     * 已认证的流式行为 M1 deferred——此处开一条即完成的空流占位（形状面由 401 段承担）。
     */
    @GetMapping(value = "/api/v1/alerts/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        user(request);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.complete();
        return emitter;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
