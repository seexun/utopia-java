package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.ConversationsService.ConversationPage;
import tech.seexun.jknow.store.entity.ConversationMessage;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.WorkspaceMapper;

/**
 * chat-misc 读族端点（对齐旧仓库 api/chat.rs:1527-1590 的会话 GET 面、events_routes.rs kb_events、
 * alerts SSE；M1 任务 5）。**剧本期望形状裁量实现**：
 * <ul>
 *   <li>SSE 三端点（/kbs/{id}/events、/alerts/events、/kbs/{id}/conversations/{cid}/stream）——
 *       无会话 401 由 SessionFilter 承担（与 Rust AuthUser 提取器同位，金样本 401 段）；
 *       已认证的流式推送 M1 deferred（开一条即完成的空流占位，COVERAGE.md 同款处置）；</li>
 *   <li>POST /kbs/{id}/chat——无对话模型时的 422 no_chat_model 是参照实例现状黄金
 *       （chat.rs:691-696 的判定序：settings→chat_ready→(M1 不可达的生成路径)）；
 *       模型配好之后的生成流属写路径（M2），此分支如实落 500；</li>
 *   <li>会话 rename/delete/stop 与 MCP 族按剧本定向 rust（写路径，M1 未实现）。</li>
 * </ul>
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:chat:list')")
public class ChatMiscController {

    private final ConversationsService conversations;
    private final WorkspaceMapper workspaces;
    private final KbAccess rbac;
    private final AppEventBus events;

    public ChatMiscController(ConversationsService conversations, WorkspaceMapper workspaces,
                              KbAccess rbac, AppEventBus events) {
        this.conversations = conversations;
        this.workspaces = workspaces;
        this.rbac = rbac;
        this.events = events;
    }

    // ---- GET /api/v1/kbs/{kbId}/conversations（chat.rs:1527-1546） ----

    public record ConversationsResponse(List<?> conversations, long total) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/conversations")
    public ConversationsResponse list(@PathVariable UUID kbId,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) Long limit,
                                      @RequestParam(required = false) Long offset,
                                      HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        long effLimit = limit == null ? 30 : Math.max(1, Math.min(100, limit));
        long effOffset = offset == null ? 0 : Math.max(0, offset);
        ConversationPage page = conversations.list(kbId, user.id(), q, effLimit, effOffset);
        return new ConversationsResponse(page.conversations(), page.total());
    }

    // ---- GET /api/v1/kbs/{kbId}/conversations/{conversationId}（chat.rs:1570-1580） ----

    public record MessagesResponse(List<ConversationMessage> messages) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/conversations/{conversationId}")
    public MessagesResponse detail(@PathVariable UUID kbId, @PathVariable UUID conversationId,
                                   HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        // 归属要查：会话 id 可以猜，别人的回答不能念
        conversations.requireOwned(kbId, user.id(), conversationId);
        return new MessagesResponse(conversations.messages(conversationId));
    }

    // ---- SSE 族（401 形状在 SessionFilter；kb 事件流 M2 任务 3 起实播） ----

    /**
     * events_routes.rs:23-31 kb_events：本库文档/抽取/审核推送（Viewer）。
     * M1 是「开一条即完成的空流」占位；M2 任务 3 起接 AppEventBus 实播
     * （无业务数据 + alert 不按库过滤 + KeepAlive，形状见 AppEventBus 类注释）。
     * chat 的 SSE（POST /chat、/conversations/{cid}/stream reattach）自 M5 任务 4 起
     * 在 {@link tech.seexun.jknow.web.knowledge.chat.ChatController}——那条流是生成事件，不是事件总线。
     */
    @GetMapping(value = "/api/v1/kbs/{kbId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter kbEvents(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        return events.subscribe(kbId);
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
