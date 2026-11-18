package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 聊天域写路径（chat.rs 路由族）：POST /chat 的 SSE agent 循环、stop、reattach、会话
 * 改名/删除。帧序是前端的契约：<b>conversation</b>{id,message_id,generation_id} →
 * step*|sources|delta* → done{stopped}|error{error,code}——SSE 帧名与错误 code 表逐字
 * 对齐旧仓（前端零改依赖这些）。
 *
 * <p><b>生产者-订阅者分离</b>：生成住独立线程（{@link ChatGeneration}），HTTP 连接只是
 * 订阅者（live.attach 快照+broadcast）——浏览器导航丢连接不丢回答；答到一半人走了，
 * 生成照常写完、照常落库，回来就在。代价说清楚：<b>没人看的时候仍然在花钱</b>。这是
 * 有意的——丢答案比多跑一轮贵，而 MAX_ROUNDS 已经给了上限。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:chat:list')")
public class ChatController {

    private static final String NO_MODEL = "Chat model not configured. Go to Settings → Models.";
    private static final int MAX_HISTORY = 20;

    private final ChatFrames frames;
    private final LiveRegistry live;
    private final ChatClients chatClients;
    private final ChatGeneration generation;
    private final ChatRunner runner;
    private final ConversationsService conversations;
    private final SettingsService settings;
    private final DatasourcesService datasources;
    private final KbAccess rbac;

    public ChatController(ChatFrames frames, LiveRegistry live, ChatClients chatClients,
                          ChatGeneration generation, ChatRunner runner,
                          ConversationsService conversations, SettingsService settings,
                          DatasourcesService datasources, KbAccess rbac) {
        this.frames = frames;
        this.live = live;
        this.chatClients = chatClients;
        this.generation = generation;
        this.runner = runner;
        this.conversations = conversations;
        this.settings = settings;
        this.datasources = datasources;
        this.rbac = rbac;
    }

    // ---- POST /api/v1/kbs/{kbId}/chat（chat.rs:668-1214 ask 主入口） ----

    /**
     * chat.rs ChatReq：message 必填；conversation_id/retry_message_id 带 serde(default)。
     * 自定义反序列化（SearchReqDeserializer 先例）——全局 fail-on-missing-creator-properties
     * 对记录组件不豁免可缺省项，必填缺失按 serde 对等文案报。
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = ChatController.ChatReqDeserializer.class)
    public record ChatReq(UUID conversationId, String message, UUID retryMessageId) {
    }

    static final class ChatReqDeserializer
            extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<ChatReq> {

        ChatReqDeserializer() {
            super(ChatReq.class);
        }

        @Override
        public ChatReq deserialize(com.fasterxml.jackson.core.JsonParser p,
                com.fasterxml.jackson.databind.DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            UUID conversationId = uuidOrNull(node.path("conversation_id"));
            UUID retryMessageId = uuidOrNull(node.path("retry_message_id"));
            String message = null;
            if (node.hasNonNull("message")) {
                message = node.get("message").asText();
            }
            return new ChatReq(conversationId, message, retryMessageId);
        }

        private static UUID uuidOrNull(JsonNode n) {
            if (n == null || !n.isTextual()) {
                return null;
            }
            try {
                return UUID.fromString(n.asText());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    @PreAuthorize("@ss.hasPermi('knowledge:chat:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/chat")
    public SseEmitter chat(@PathVariable UUID kbId, @RequestBody ChatReq req,
                           HttpServletRequest request) {
        return sseFrom(chatAttached(kbId, req, request));
    }

    /**
     * 测试面：同 chat() 的准备与启动，但把订阅句柄交出来——与 Rust 直调 chat 处理函数
     * 再读 SSE 体等位（快照帧 + 广播帧逐帧同构）。
     */
    LiveRegistry.Attached chatAttached(UUID kbId, ChatReq req, HttpServletRequest request) {
        UserRow user = user(request);
        KbRow kb = rbac.requireKb(user, kbId, Role.VIEWER);
        // 写工具跟人走：editor 及以上的对话才带 remember，viewer 纯只读；
        // 挂载的数据源决定 query_data 是否入列（问数是读，viewer 亦可用）
        List<DataSourceView> mountedSources = datasources.mounted(kbId);
        Role role = rbac.kbRole(user, kb);
        boolean canWrite = role != null && role.ordinal() >= Role.EDITOR.ordinal();

        // settings 出库即开封（SettingsService.opened）；对话客户端按工作区缓存
        LlmSettingsRow row = settings.get(kb.workspaceId());
        ChatClient client = chatClients.get(kb.workspaceId(), row);
        if (client == null) {
            throw AppException.invalid("no_chat_model", NO_MODEL);
        }

        String query = req.message() == null ? "" : req.message().trim();
        if (req.retryMessageId() == null && query.isEmpty()) {
            throw AppException.validation("Missing user message");
        }
        UUID conversationId;
        if (req.conversationId() != null) {
            conversations.requireOwned(kbId, user.id(), req.conversationId());
            conversationId = req.conversationId();
        } else if (req.retryMessageId() != null) {
            throw AppException.invalid("retry_needs_conversation",
                    "A retry names the conversation its question is in");
        } else {
            conversationId = conversations.create(kbId, user.id(), query);
        }
        // 会话持久化：用户消息即刻落库，上下文由服务端从库里拼——前端只送新消息。
        // 新问题和重试共用入口：先占住会话（一次一个生成者），再查重试资格或存问题。
        LiveRegistry.Handle handle = live.begin(conversationId);
        UUID generationId = handle.generationId;
        String question;
        UUID userMessageId;
        // live.begin 到 generation.submit 之间任何一步失败都要撤占位（Rust 由 Drop 保证
        // 「准备失败自动释放」，chat.rs:719——live.rs 的 admission_is_exclusive_and_
        // dropping_a_reservation_releases_it 钉住该语义）：重试被拒（retry_answered 是
        // 正常用户流）或库里抖一下，不放掉占位的话该会话在注册表里永驻——之后每次提问
        // 409 answer_running 直到重启，接上的人还会在 ready 上等成死等。retire 幂等：
        // 空问题分支已手写退过的，这里是第二次调用也是无操作。
        try {
            if (req.retryMessageId() != null) {
                // 重答的那一问已经在库里，就地回答它，不再存一遍（#936）
                question = conversations.retryQuestion(conversationId, req.retryMessageId())
                        .trim();
                userMessageId = req.retryMessageId();
            } else {
                userMessageId = conversations.appendMessage(conversationId, "user", query,
                        TurnRecord.empty(frames.mapper));
                question = query;
            }
            if (question.isEmpty()) {
                handle.retire();
                throw AppException.validation("Missing user message");
            }
            ConversationsService.History history = conversations.recentContext(conversationId,
                    MAX_HISTORY);
            // 这一问已经落库，所以它在历史里；runner 又把它当 prompt 发一次。两份都发就有
            // 两条一样的 user——按落库 id 剔除，不按文本：同一会话里并发的另一问可能排在它
            // 后面。收尾那一步用同一个位置把当前问题从背景里剔出去
            Integer current = null;
            for (int i = 0; i < history.turnIds.size(); i++) {
                if (history.turnIds.get(i).equals(userMessageId)) {
                    current = i;
                    break;
                }
            }
            List<String[]> prior = new ArrayList<>();
            for (int i = 0; i < history.turns.size(); i++) {
                if (current != null && current == i) {
                    continue;
                }
                prior.add(history.turns.get(i));
            }
            String previousAnswer = null;
            for (int i = prior.size() - 1; i >= 0; i--) {
                if ("assistant".equals(prior.get(i)[0])) {
                    previousAnswer = prior.get(i)[1];
                    break;
                }
            }

            return start(user.id(), conversationId, handle, generationId, userMessageId,
                    client, kb, mountedSources, canWrite, question, prior, history,
                    previousAnswer);
        } catch (Exception e) {
            handle.retire();
            throw e;
        }
    }

    /** sse_from：把一次「接上」变成 SSE——先补一份快照，再照常收增量；没在跑回 idle。 */
    private SseEmitter sseFrom(LiveRegistry.Attached attached) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread reader = new Thread(() -> {
            try {
                if (attached == null) {
                    // 客户端每次打开会话都会问一次，「没有在跑」是最常见的答案，不是错误
                    emitter.send(SseEmitter.event().name("idle").data("{}"));
                    emitter.complete();
                    return;
                }
                emitter.send(SseEmitter.event().name("snapshot")
                        .data(attached.snapshot.toFrame(frames).data()));
                if (attached.snapshot.terminal() != null) {
                    emitter.send(SseEmitter.event()
                            .name(attached.snapshot.terminal().event())
                            .data(attached.snapshot.terminal().data()));
                    emitter.complete();
                    return;
                }
                while (true) {
                    LiveRegistry.Frame frame;
                    try {
                        frame = attached.queue.take();
                    } catch (IllegalStateException queueState) {
                        // take 的 IllegalStateException 只有滞后一种来源（wakeup 从不以
                        // 异常完成，ExecutionException 包装到不了）；不认得的原样交给外层
                        if (queueState.getMessage() == null
                                || !queueState.getMessage().startsWith("lagged:")) {
                            throw queueState;
                        }
                        // 这个客户端读得太慢，被广播缓冲甩下了。**说出来**——静默继续会让
                        // 它少掉中间一段而毫不知情（chat.rs:1340-1347 原文）
                        String dropped = queueState.getMessage().substring("lagged:".length());
                        emitter.send(SseEmitter.event().name("error")
                                .data(errorData("stream_lagged", "Fell behind the stream by "
                                        + dropped + " messages; reopen the conversation")));
                        break;
                    }
                    if (frame == null) {
                        // 生产者没了还没有终态：说出来——静默继续会让客户端少掉中间一段
                        // 而毫不知情
                        emitter.send(SseEmitter.event().name("error")
                                .data(errorData("stream_ended",
                                        "Answer stream ended unexpectedly")));
                        break;
                    }
                    try {
                        emitter.send(SseEmitter.event().name(frame.event()).data(frame.data()));
                    } catch (Exception disconnected) {
                        // 浏览器断线不是错误：生成仍在独立线程上跑
                        break;
                    }
                    if ("done".equals(frame.event()) || "error".equals(frame.event())) {
                        break;
                    }
                }
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignore) {
                    // 连接已死
                }
            }
        }, "chat-sse-reader");
        reader.setDaemon(true);
        reader.start();
        emitter.onCompletion(reader::interrupt);
        emitter.onTimeout(reader::interrupt);
        return emitter;
    }

    /** 订阅先于生成开始（首帧不丢），再把生成交给独立线程。 */
    private LiveRegistry.Attached start(UUID actor, UUID conversationId,
                                        LiveRegistry.Handle handle, UUID generationId,
                                        UUID userMessageId, ChatClient client, KbRow kb,
                                        List<DataSourceView> mountedSources, boolean canWrite,
                                        String question, List<String[]> prior,
                                        ConversationsService.History history,
                                        String previousAnswer) {
        ChatRunner.RunInput input = new ChatRunner.RunInput(client, kb,
                new ToolExecutor.ToolContext(kb.id(), kb.workspaceId(), mountedSources, canWrite,
                        actor, question, true),
                conversationId, userMessageId, generationId, question, prior,
                history.lastToolExchange, history.entities, previousAnswer,
                history.lastSources, handle.cancellation());
        // 生成登记在案，然后这条连接也只是去「接上」它——与刷新之后那条重连走的是同一段
        // 代码。两条路分开写的话，迟早只有一条是对的。订阅先于生成开始：首帧不丢。
        handle.start();
        LiveRegistry.Attached attached;
        try {
            attached = live.attach(conversationId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        generation.submit(conversationId, handle, input, previousAnswer, history.lastSources);
        return attached;
    }

    // ---- 会话面的其余端点（chat.rs:1372-1590） ----

    public record StopReq(UUID generationId) {
    }

    /** 只通知生成的持有者，由它保存部分答案并发送终态。 */
    @PreAuthorize("@ss.hasPermi('knowledge:chat:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/chat/{conversationId}/stop")
    public ObjectNode stop(@PathVariable UUID kbId, @PathVariable UUID conversationId,
                           @RequestBody StopReq req, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        conversations.requireOwned(kbId, user.id(), conversationId);
        live.stop(conversationId, req.generationId());
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /** 接上一次正在跑的生成（刷新页面之后走这里）。归属要查：会话 id 可以猜。 */
    @GetMapping(value = "/api/v1/kbs/{kbId}/conversations/{conversationId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reattach(@PathVariable UUID kbId, @PathVariable UUID conversationId,
                               HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        conversations.requireOwned(kbId, user.id(), conversationId);
        try {
            return sseFrom(live.attach(conversationId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String errorData(String code, String message) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("error", message);
        out.put("code", code);
        return out.toString();
    }

    // ---- 会话改名与删除（chat.rs:1548-1590） ----

    public record RenameConversationReq(String title) {
    }

    /**
     * 改会话标题。标题本来是从第一句话自动取的，而一段对话跑偏是常态——改名让人能按
     * 自己记得的方式找回它。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:chat:edit')")
    @PatchMapping("/api/v1/kbs/{kbId}/conversations/{conversationId}")
    public ObjectNode rename(@PathVariable UUID kbId, @PathVariable UUID conversationId,
                             @RequestBody RenameConversationReq req, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        conversations.rename(kbId, user.id(), conversationId, req.title());
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    @PreAuthorize("@ss.hasPermi('knowledge:chat:edit')")
    @DeleteMapping("/api/v1/kbs/{kbId}/conversations/{conversationId}")
    public ObjectNode delete(@PathVariable UUID kbId, @PathVariable UUID conversationId,
                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        conversations.delete(kbId, user.id(), conversationId);
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
