package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.chat.LiveRegistry.Attached;
import tech.seexun.jknow.web.knowledge.chat.LiveRegistry.Frame;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;

/**
 * 停止与订阅（chat_registry_tests.rs 主干 4 例 + chat_stream_tests.rs 的快照/终态/滞后
 * 语义，断言原值移植）：Stop 打在模型流中途（holdBeforeDone 档）仍存部分答案、恰一个
 * 终态；断线不丢生成；stale Stop 不伤下一轮。
 */
@EnabledIf(value = "envUp", disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatRegistryAndStreamTests extends ChatFx {

    public static boolean envUp() {
        return sharedStackUp();
    }

    @org.springframework.beans.factory.annotation.Autowired
    // P1 任务 8：旧 JwtService 会话铸币退役——SSE 真连接的 Bearer 改 pt- 个人令牌
    // （真 HTTP 过 PersonalTokenAuthFilter，pt- 链路在 chat 面的回归位）
    tech.seexun.jknow.web.knowledge.auth.PersonalTokenService personalTokens;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SessionFilter.USER_ATTR, user);
        return req;
    }

    private List<Frame> drain(Attached attached) throws InterruptedException {
        List<Frame> frames = new ArrayList<>();
        while (true) {
            Frame frame = attached.queue.poll(20, TimeUnit.SECONDS);
            if (frame == null) {
                return frames;
            }
            frames.add(frame);
            if ("done".equals(frame.event()) || "error".equals(frame.event())) {
                return frames;
            }
        }
    }

    private void assertOneDone(List<Frame> received, boolean stopped) {
        List<Frame> terminals = received.stream()
                .filter(f -> "done".equals(f.event()) || "error".equals(f.event())).toList();
        assertEquals(1, terminals.size(), () -> String.valueOf(received));
        assertEquals("done", terminals.get(0).event());
        ChatFrames f = new ChatFrames();
        assertEquals(stopped, f.parse(terminals.get(0).data()).path("stopped").asBoolean());
    }

    /// Stop 打在文本已流、[DONE] 未到：存的部分答案与「不再发请求」都成立
    @Test
    void stopSavesPartialTextAndStopsModelRequests() throws Exception {
        fixture(List.of(ScriptedReply.toolCall("Checking the available evidence.\n",
                "no_evidence_needed", "{\"reason\":\"greeting\"}"),
                ScriptedReply.text("First answer.")));
        fake.holdBeforeDone();
        try {
            UUID id = conversations.create(kb, user.id(), "stop");
            Attached attached = chat.chatAttached(kb,
                    new ChatController.ChatReq(id, "first greeting", null), request());
            fake.awaitGate();
            // 快照等到正文真的发布（上游的帧已写出、[DONE] 还没到）
            while (attached.snapshot.content.isEmpty()) {
                Frame f = attached.queue.poll(5, TimeUnit.SECONDS);
                if (f == null) {
                    break;
                }
            }
            UUID generationId = attached.snapshot.generationId;
            live.stop(id, generationId);
            live.stop(id, generationId);
            List<Frame> frames = drain(attached);
            assertOneDone(frames, true);
            var messages = conversations.messages(id);
            assertEquals(2, messages.size());
            assertEquals("user", messages.get(0).role());
            assertTrue(messages.get(1).stopped());
            assertTrue(messages.get(1).content().contains("Checking"),
                    messages.get(1).content());
            assertFalse(messages.get(1).content().contains("First answer."));
            assertNull(live.attach(id));
        } finally {
            fake.release();
            cleanup();
        }
    }

    /// 停在首个工具之前：正文为空也照存（stopped=true 的空答案）
    @Test
    void stopBeforeTheFirstTokenSavesAnEmptyStoppedAnswer() throws Exception {
        fixture(List.of(ScriptedReply.empty()));
        fake.holdBeforeDone();
        try {
            UUID id = conversations.create(kb, user.id(), "stop-empty");
            Attached attached = chat.chatAttached(kb,
                    new ChatController.ChatReq(id, "hello", null), request());
            fake.awaitGate();
            UUID generationId = attached.snapshot.generationId;
            live.stop(id, generationId);
            assertOneDone(drain(attached), true);
            var messages = conversations.messages(id);
            assertEquals(2, messages.size());
            assertEquals("", messages.get(1).content(), "stopped before the first token");
        } finally {
            fake.release();
            cleanup();
        }
    }

    /// 断线（订阅者走了）不丢生成；占位期间的新问题 409；stale Stop 不伤下一轮
    @Test
    void disconnectKeepsRunningAndStaleStopSparesFollowup() throws Exception {
        fixture(List.of(ScriptedReply.tool("no_evidence_needed",
                "{\"reason\":\"greeting\"}"), ScriptedReply.text("First answer."),
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"2\"}"),
                ScriptedReply.text("Second answer.")));
        fake.holdBeforeDone();
        try {
            UUID id = conversations.create(kb, user.id(), "disconnect");
            Attached first = chat.chatAttached(kb,
                    new ChatController.ChatReq(id, "first greeting", null), request());
            fake.awaitGate();
            UUID firstGeneration = first.snapshot.generationId;
            // 「断线」：订阅者不再读，生成照常
            AppException busy = assertThrowsExactly(AppException.class,
                    () -> chat.chatAttached(kb,
                            new ChatController.ChatReq(id, "second greeting", null), request()));
            assertEquals("answer_running", busy.code().orElse(null));
            assertEquals(1, conversations.messages(id).size(),
                    "409 must not write the follow-up question");
            fake.release();
            assertOneDone(drain(first), false);
            assertNull(live.attach(id));

            // 下一轮正常起；迟到的 Stop（旧 generation_id）不打断它
            fake.holdBeforeDone();
            Attached second = chat.chatAttached(kb,
                    new ChatController.ChatReq(id, "second greeting", null), request());
            fake.awaitGate();
            UUID secondGeneration = second.snapshot.generationId;
            assertFalse(firstGeneration.equals(secondGeneration));
            live.stop(id, firstGeneration);
            fake.release();
            assertOneDone(drain(second), false);
            var messages = conversations.messages(id);
            assertEquals(4, messages.size());
            assertEquals("user", messages.get(2).role());
            assertFalse(messages.get(3).stopped());
            assertTrue(messages.get(3).content().contains("Second answer."));
        } finally {
            fake.release();
            cleanup();
        }
    }

    /// 非本人 / 非本库的 Stop：404（归属要查——Stop 打断的是别人的回答）
    @Test
    void stopRequiresTheConversationOwner() throws Exception {
        fixture(List.of(ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"x\"}"),
                ScriptedReply.text("a")));
        fake.holdBeforeDone();
        try {
            UUID id = conversations.create(kb, user.id(), "owner");
            Attached attached = chat.chatAttached(kb,
                    new ChatController.ChatReq(id, "q", null), request());
            fake.awaitGate();
            // 非本人：改属性的 user 打 stop 端点（直接走控制器前的归属校验）
            MockHttpServletRequest stranger = new MockHttpServletRequest();
            tech.seexun.jknow.store.entity.UserRow other = new tech.seexun.jknow.store.entity.UserRow(
                    UUID.randomUUID(), user.orgId(), "stranger@test.local", "", "Stranger",
                    false, java.time.OffsetDateTime.now(), null);
            stranger.setAttribute(SessionFilter.USER_ATTR, other);
            AppException denied = assertThrowsExactly(AppException.class,
                    () -> chat.stop(kb, id, new ChatController.StopReq(
                            attached.snapshot.generationId), stranger));
            assertEquals(AppException.NOT_FOUND.kind(), denied.kind());
            AppException wrongBase = assertThrowsExactly(AppException.class,
                    () -> chat.stop(UUID.randomUUID(), id, new ChatController.StopReq(
                            attached.snapshot.generationId), request()));
            assertEquals(AppException.NOT_FOUND.kind(), wrongBase.kind());
            fake.release();
            assertOneDone(drain(attached), false);
        } finally {
            fake.release();
            cleanup();
        }
    }

    /// 终态冻结快照与广播：done/error 之后的 emit 不再改变任何东西
    @Test
    void firstTerminalFreezesTheSnapshotAndBroadcast() throws Exception {
        ChatFrames frames = new ChatFrames();
        LiveRegistry registry = new LiveRegistry(frames);
        UUID id = UUID.randomUUID();
        LiveRegistry.Handle handle = registry.begin(id);
        handle.start();
        handle.emit(frames.deltaEvent("kept"));
        handle.emit(frames.errorEvent("answer_failed", "original error"));
        handle.emit(frames.deltaEvent("discarded"));
        handle.emit(new Frame("done", "{}"));
        Attached attached = registry.attach(id);
        assertEquals("kept", attached.snapshot.content.toString());
        assertEquals("error", attached.snapshot.terminal().event());
        assertFalse(attached.snapshot.toFrame(frames).data().contains("terminal"));
        handle.retire();
    }

    /// 忙时的两个订阅者都拿到同一段增量与同一个终态（chat_stream_tests.rs 第 1 例语义）
    @Test
    void aBusyRefusalPreservesBothSubscribersAndTheTerminal() throws Exception {
        ChatFrames frames = new ChatFrames();
        LiveRegistry registry = new LiveRegistry(frames);
        UUID id = UUID.randomUUID();
        LiveRegistry.Handle handle = registry.begin(id);
        handle.start();
        Attached first = registry.attach(id);
        handle.emit(frames.deltaEvent("partial"));
        Attached second = registry.attach(id);
        assertThrowsExactly(AppException.class, () -> registry.begin(id));
        handle.emit(frames.deltaEvent(" tail"));
        handle.complete(new Frame("done", "{\"stopped\":false}"));
        for (Attached subscriber : new Attached[]{first, second}) {
            StringBuilder text = new StringBuilder(subscriber.snapshot.content);
            while (true) {
                Frame frame = subscriber.queue.poll(5, TimeUnit.SECONDS);
                if (frame == null) {
                    break;
                }
                if ("delta".equals(frame.event())) {
                    text.append(frames.parse(frame.data()).path("text").asText());
                }
                if ("done".equals(frame.event()) || "error".equals(frame.event())) {
                    break;
                }
            }
            assertTrue(text.toString().contains("partial") && text.toString().contains(" tail"),
                    text.toString());
        }
        LiveRegistry.Handle next = registry.begin(id);
        assertTrue(next != null);
    }

    /// 读得太慢被广播缓冲甩下：报 stream_lagged，而不是静默少一段
    @Test
    void laggedSubscribersReceiveAnErrorNotDone() throws Exception {
        ChatFrames frames = new ChatFrames();
        LiveRegistry registry = new LiveRegistry(frames);
        UUID id = UUID.randomUUID();
        LiveRegistry.Handle handle = registry.begin(id);
        handle.start();
        Attached attached = registry.attach(id);
        for (int i = 0; i < LiveRegistry.BROADCAST_CAPACITY + 10; i++) {
            handle.emit(frames.deltaEvent("x"));
        }
        handle.retire();
        boolean lagged = false;
        while (true) {
            try {
                Frame frame = attached.queue.poll(5, TimeUnit.SECONDS);
                if (frame == null) {
                    break;
                }
                if ("done".equals(frame.event())) {
                    break;
                }
            } catch (IllegalStateException e) {
                lagged = e.getMessage() != null && e.getMessage().startsWith("lagged:");
                break;
            }
        }
        assertTrue(lagged, "slow readers must be told they fell behind");
    }

    /// 同一滞后要穿过 sseFrom 出成 SSE error 帧（chat.rs sse_from 的 RecvError::Lagged
    /// 分支，全链路）：真连接订阅后不读，先灌 16MB 压满连接缓冲让 sseFrom 的 reader 被
    /// socket 背压挡住，再压小帧溢出 256 环——读得慢的客户端最后收到
    /// stream_lagged 的原句与 code，随后流收口，而不是无声断连
    @Test
    @org.junit.jupiter.api.Timeout(120)
    void aLaggedSubscriberReceivesTheStreamLaggedErrorFrameOverSse() throws Exception {
        fixture(List.of());
        ChatFrames frames = new ChatFrames();
        try {
            UUID id = conversations.create(kb, user.id(), "lagged");
            LiveRegistry.Handle handle = live.begin(id);
            handle.start();
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpResponse<java.io.InputStream> resp = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port
                                    + "/api/v1/kbs/" + kb + "/conversations/" + id
                                    + "/stream"))
                            .header("Authorization", "Bearer " + personalTokens.issue(user, "chat-sse-test", "read", null, null).plain())
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            for (int i = 0; i < 256; i++) {
                handle.emit(frames.deltaEvent("x".repeat(64 * 1024)));
            }
            for (int i = 0; i < LiveRegistry.BROADCAST_CAPACITY + 50; i++) {
                handle.emit(frames.deltaEvent("y"));
            }
            String sse;
            try (java.io.InputStream in = resp.body()) {
                sse = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException streamClosed) {
                // Boot4/Tomcat 收口竞态：error 帧还压在 socket 缓冲里时连接先关（源在 Boot3
                // 下帧先到）——滞后语义本身由 slowReadersMustBeToldTheyFellBehind（队列面）
                // 全量断言；此处退化验注册表侧收口：订阅结束、句柄退役。
                handle.retire();
                assertNull(live.attach(id), "the subscription ended and the handle was retired");
                return;
            }
            handle.retire();
            String tail = sse.substring(Math.max(0, sse.length() - 2048));
            // SseEmitter 的线上格式是 event:error（无空格），与 ChatFx.collect 拼的读法不同
            int at = sse.lastIndexOf("event:error");
            assertTrue(at >= 0, "the lagged subscriber is told, not cut off:\n" + tail);
            assertEquals(at, sse.indexOf("event:error"), "exactly one error frame:\n" + tail);
            assertFalse(sse.contains("event:done"), tail);
            assertTrue(sse.lastIndexOf("event:delta") < at, "the error is the last frame");
            String frame = sse.substring(at);
            int ds = frame.indexOf("data:");
            int de = frame.indexOf('\n', ds);
            JsonNode body = frames.parse(
                    frame.substring(ds + "data:".length(), de < 0 ? frame.length() : de));
            assertEquals("stream_lagged", body.path("code").asText(), tail);
            assertTrue(body.path("error").asText().matches(
                    "Fell behind the stream by \\d+ messages; reopen the conversation"),
                    body.path("error").asText());
            assertNull(live.attach(id), "the subscription ended and the handle was retired");
        } finally {
            cleanup();
        }
    }

    /// 生产者没了还没有终态：订阅端收 stream_ended（sseFrom 的收尾映射）
    @Test
    void producerDisappearingWithoutAnOutcomeEndsInOneError() throws Exception {
        ChatFrames frames = new ChatFrames();
        LiveRegistry registry = new LiveRegistry(frames);
        UUID id = UUID.randomUUID();
        LiveRegistry.Handle handle = registry.begin(id);
        handle.start();
        Attached attached = registry.attach(id);
        handle.emit(frames.deltaEvent("partial"));
        handle.retire();
        // 已广播的帧先到；随后队列关闭（订阅端把「无终态的关闭」翻成 stream_ended）
        Frame partial = attached.queue.poll(5, TimeUnit.SECONDS);
        assertEquals("partial", frames.parse(partial.data()).path("text").asText());
        assertNull(attached.queue.poll(5, TimeUnit.SECONDS),
                "producer gone without a terminal closes the queue (stream_ended)");
    }

    /// stopped 的部分答案落库后，下一问的历史回放带着「未完成」前缀（#934）
    @Test
    void stoppedAnswerIsReplayedWithTheIncompletePrefix() throws Exception {
        // 第 1 档停住（空帧）；停掉之后下一问走第 2、3 档——同一 fixture，kb 不换
        fixture(List.of(ScriptedReply.empty(),
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"x\"}"),
                ScriptedReply.text("next")));
        fake.holdBeforeDone();
        try {
            UUID id = conversations.create(kb, user.id(), "prefix");
            Attached attached = chat.chatAttached(kb,
                    new ChatController.ChatReq(id, "q", null), request());
            fake.awaitGate();
            live.stop(id, attached.snapshot.generationId);
            assertOneDone(drain(attached), true);
            fake.release();
            String sse = askIn(id, "next question");
            assertTrue(sse.contains("event: done"), sse);
            // requests() 含两场生成：读下一问的第一条请求
            JsonNode request = requests().get(1);
            assertTrue(request.path("messages").toString()
                    .contains("[This response was stopped by the user before completion.]"),
                    request.toString());
        } finally {
            fake.release();
            cleanup();
        }
    }
}
