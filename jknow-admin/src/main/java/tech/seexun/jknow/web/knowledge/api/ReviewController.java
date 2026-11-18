package tech.seexun.jknow.web.knowledge.api;

import jakarta.servlet.http.HttpServletRequest;
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
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.AuditEventView;
import tech.seexun.jknow.store.entity.PendingFactView;
import tech.seexun.jknow.store.entity.ReviewCounts;
import tech.seexun.jknow.store.entity.ReviewSummary;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 审核中心端点（对齐旧仓库 review_routes.rs；M1 任务 4 实现四个 GET——
 * 队列（review_routes.rs:75-155）、history（747-759）、summary（762-771）、
 * pending-for-chunk（1087-1096）。裁决/闭合/合并回滚等写路径（decide/close/confirm/
 * reject/merge/revert/batch…）按 m1-plan 归 M2，未实现路径落 404 {"error":"Not found"}。
 *
 * <p>队列纪律：**计数与内容分开取**——counts 每次都回（17 列单查询），内容只回当前
 * 档一页；queue 缺省 duplicates、limit 缺省 10 钳 [1,200]、offset 缺省 0 取 max(0)
 * （review_routes.rs:20/83-86）；未知 queue → 422 unknown_queue（141-147）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:review:list')")
public class ReviewController {

    /** review_routes.rs:20：一页多少条（服务端默认，不是上限）。 */
    private static final long REVIEW_PAGE = 10;

    private final KbAccess rbac;
    private final ReviewService review;

    public ReviewController(KbAccess rbac, ReviewService review) {
        this.rbac = rbac;
        this.review = review;
    }

    // ---- GET /api/v1/kbs/{kbId}/review（review_routes.rs:75-155 list） ----

    public record ReviewResponse(ReviewCounts counts, String queue, List<?> items) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/review")
    public ReviewResponse list(@PathVariable UUID kbId,
                               @RequestParam(required = false) String queue,
                               @RequestParam(required = false) String types,
                               @RequestParam(required = false) Long limit,
                               @RequestParam(required = false) Long offset,
                               HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        ReviewCounts counts = review.counts(kbId);
        String effectiveQueue = queue == null ? "duplicates" : queue;
        // 上限挡住「limit=1000000 把库拖垮」，同时留出足够一页的余量
        long effectiveLimit = limit == null ? REVIEW_PAGE : Math.max(1, Math.min(200, limit));
        long effectiveOffset = offset == null ? 0 : Math.max(0, offset);
        return new ReviewResponse(counts, effectiveQueue,
                review.items(kbId, effectiveQueue, types, effectiveLimit, effectiveOffset));
    }

    // ---- GET /api/v1/kbs/{kbId}/review/pending?chunk_id=…（review_routes.rs:1087-1096） ----

    public record PendingChunkResponse(List<PendingFactView> items) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/review/pending")
    public PendingChunkResponse pendingForChunk(@PathVariable UUID kbId,
                                                @RequestParam("chunk_id") UUID chunkId,
                                                HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return new PendingChunkResponse(review.pendingForChunk(kbId, chunkId));
    }

    // ---- GET /api/v1/kbs/{kbId}/review/history（review_routes.rs:747-759） ----

    public record HistoryResponse(List<AuditEventView> events, long total) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/review/history")
    public HistoryResponse history(@PathVariable UUID kbId,
                                   @RequestParam(required = false) Long page,
                                   @RequestParam(required = false) Long per,
                                   HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        long effectivePer = per == null ? 20 : Math.max(1, Math.min(100, per));
        long effectivePage = page == null ? 0 : Math.max(0, page);
        ReviewService.History h = review.history(kbId, effectivePer, effectivePage * effectivePer);
        return new HistoryResponse(h.events(), h.total());
    }

    // ---- GET /api/v1/kbs/{kbId}/review/summary（review_routes.rs:762-771） ----

    @GetMapping("/api/v1/kbs/{kbId}/review/summary")
    public ReviewSummary summary(@PathVariable UUID kbId, HttpServletRequest request) {
        rbac.requireKb(user(request), kbId, Role.VIEWER);
        return review.summary(kbId);
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
