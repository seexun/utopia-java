package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.store.StoreTestBase;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 聊天域集成测试的公共夹具（chat_empty_reply_tests.rs::Fx 同位）：真库、真处理函数
 * （直调 ChatController 的准备-启动路径，与 Rust 直调 chat 处理函数等位），模型由
 * {@link ChatScriptServer} 按脚本假扮——「模型回空」这类真模型上几十轮才碰一次的事，
 * 在这里每次都发生。SSE 断言读的是订阅队列（快照帧 + 广播帧），与读 HTTP 体逐帧同构。
 *
 * <p>自建自拆：org 级联清理，绝不碰已有的库。
 */
abstract class ChatFx extends StoreTestBase {

    @Autowired
    ChatController chat;

    @Autowired
    LiveRegistry live;

    @Autowired
    ConversationsService conversations;

    @Autowired
    SettingsService settings;

    ChatScriptServer fake;
    UUID org;
    UUID kb;
    UserRow user;

    /** 起假模型 + 建一套 org/workspace/kb/user（viewer），chat 设置指向假端点。 */
    void fixture(List<ScriptedReply> replies) throws Exception {
        // 直调面登录态（P2 任务 4 起 ChatController 挂 @PreAuthorize——hasPermi 要 LoginUser）
        loginAsAdminDirectCall();
        fake = ChatScriptServer.start(replies);
        org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        kb = UUID.randomUUID();
        UUID uid = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id,name) VALUES(?,'chat-test')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES(?,?,'chat-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES(?,?,'chat-test')",
                kb, ws);
        // 身份主档 sys_user（P1 任务 7 起桥接列制）：jknow_test_user 助手建行（is_admin
        // 折超级管理员角色），UserRow 旧列形状经 sys_user 别名折回（SixDomainStoreTests 同法）
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Chat Test', TRUE)",
                uid, org, "chat-" + uid + "@test.local");
        jdbc.update("INSERT INTO kb_members(kb_id,user_id,role) VALUES(?,?,'viewer')", kb, uid);
        settings.upsert(ws, fake.endpoint(), null, "scripted-chat", null, null, null, null);
        user = jdbc.queryForObject("""
                SELECT utopia_uuid AS id, NULL::uuid AS org_id, user_name AS email,
                       NULL::text AS password_hash, nick_name AS display_name,
                       (user_id = 1 OR EXISTS (SELECT 1 FROM sys_user_role ur
                            WHERE ur.user_id = sys_user.user_id AND ur.role_id = 1)) AS is_admin,
                       create_time AT TIME ZONE 'UTC' AS created_at,
                       NULL::timestamptz AS deactivated_at
                  FROM sys_user WHERE utopia_uuid = ?""",
                (rs, i) -> new UserRow(rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("display_name"),
                        rs.getBoolean("is_admin"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("deactivated_at", java.time.OffsetDateTime.class)), uid);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SessionFilter.USER_ATTR, user);
        return req;
    }

    /** 问一句（新会话），把整条 SSE 收成文本。 */
    String ask(String message) {
        return collect(chat.chatAttached(kb,
                new ChatController.ChatReq(null, message, null), request()));
    }

    /** 在既有会话里问一句（chat_history_tests.rs::ask_in 同位）。 */
    String askIn(UUID conversationId, String message) {
        return collect(chat.chatAttached(kb,
                new ChatController.ChatReq(conversationId, message, null), request()));
    }

    /** 任意请求体（重试档带 retry_message_id）。 */
    String post(ChatController.ChatReq req) {
        return collect(chat.chatAttached(kb, req, request()));
    }

    /** 收一场订阅的全部帧：快照在前、终态收尾；拼成与 SSE 体逐帧同构的文本。 */
    String collect(LiveRegistry.Attached attached) {
        StringBuilder sse = new StringBuilder();
        sse.append("event: snapshot\ndata: ")
                .append(attached.snapshot.toFrame(frames()).data()).append("\n\n");
        long deadline = System.currentTimeMillis() + 60_000;
        try {
            while (System.currentTimeMillis() < deadline) {
                LiveRegistry.Frame frame = attached.queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                sse.append("event: ").append(frame.event()).append("\ndata: ")
                        .append(frame.data()).append("\n\n");
                if ("done".equals(frame.event()) || "error".equals(frame.event())) {
                    return sse.toString();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("stream did not finish:\n" + sse);
    }

    ChatFrames frames() {
        return new ChatFrames();
    }

    /** 最近一条助手消息的正文（失败轮没有）。 */
    String storedAnswer() {
        List<String> rows = jdbc.queryForList("""
                SELECT m.content FROM conversation_messages m
                   JOIN conversations c ON c.id = m.conversation_id
                  WHERE c.kb_id = ? AND m.role = 'assistant'
                  ORDER BY m.created_at DESC LIMIT 1
                """, String.class, kb);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 发给模型的请求（提示词断言用）。 */
    List<JsonNode> requests() {
        return fake.requests();
    }

    UUID onlyConversation() {
        return jdbc.queryForObject("SELECT id FROM conversations WHERE kb_id = ?", UUID.class, kb);
    }

    void cleanup() {
        fake.stop();
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        logoutDirectCall();
    }
}
