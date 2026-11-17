package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.chat.LiveRegistry.Attached;
import tech.seexun.jknow.web.knowledge.chat.LiveRegistry.Frame;

/**
 * 正在生成的回答能被重新接上（live.rs 内联 7 例，断言原值移植）：占位/释放的独占序、
 * 快照与订阅的边界、迟到的把手不能写进新的生成。
 */
class ChatLiveRegistryTests {

    private final ChatFrames frames = new ChatFrames();
    private final LiveRegistry registry = new LiveRegistry(frames);

    private Frame delta(String text) {
        return frames.deltaEvent(text);
    }

    private Frame done(boolean stopped) {
        return new Frame("done", "{\"stopped\":" + stopped + "}");
    }

    private Frame take(Attached attached) throws InterruptedException {
        return attached.queue.poll(5, TimeUnit.SECONDS);
    }

    @Test
    void retiredHandleCannotUnregisterOrWriteIntoCurrentGeneration() throws Exception {
        UUID id = id();
        LiveRegistry.Handle old = registry.begin(id);
        old.start();
        old.emit(delta("old"));
        old.retire();
        LiveRegistry.Handle current = registry.begin(id);
        current.start();
        current.emit(delta("new"));
        Attached attached = registry.attach(id);
        assertEquals("new", attached.snapshot.content.toString());
        old.emit(delta("late old text"));
        Attached again = registry.attach(id);
        assertEquals("new", again.snapshot.content.toString());
        // 老把手的迟到 emit 不能广播给新订阅者
        assertNull(attached.queue.poll(200, TimeUnit.MILLISECONDS));
        current.emit(delta(" answer"));
        assertEquals(delta(" answer").data(), take(attached).data());
        current.retire();
        assertNull(registry.attach(id));
    }

    @Test
    void onlyCurrentOwnerCanRemoveEntryInAnyFinishOrder() throws Exception {
        for (int[] order : new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0},
                {2, 0, 1}, {2, 1, 0}}) {
            LiveRegistry registry = new LiveRegistry(frames);
            UUID id = id();
            UUID otherId = id();
            LiveRegistry.Handle other = registry.begin(otherId);
            other.start();
            other.emit(delta("unrelated"));
            LiveRegistry.Handle[] handles = new LiveRegistry.Handle[3];
            for (int i = 0; i < 3; i++) {
                LiveRegistry.Handle h = registry.begin(id);
                h.start();
                if (i < 2) {
                    h.retire();
                }
                handles[i] = h;
            }
            boolean currentFinished = false;
            for (int index : order) {
                handles[index].retire();
                currentFinished |= index == 2;
                assertEquals(currentFinished, registry.attach(id) == null,
                        java.util.Arrays.toString(order));
                assertEquals("unrelated",
                        registry.attach(otherId).snapshot.content.toString());
            }
            other.retire();
            assertNull(registry.attach(otherId));
        }
    }

    @Test
    void snapshotAndSubscriptionPartitionConcurrentEmission() throws Exception {
        UUID id = id();
        LiveRegistry.Handle handle = registry.begin(id);
        handle.start();
        // 两种顺序都合法：每个增量必须恰好出现在快照或订阅中一次，既不重复也不遗漏
        for (int index = 0; index < 64; index++) {
            if (index % 2 == 0) {
                // emit 先行：增量进快照，新订阅者从下一帧起收
                handle.emit(delta("x"));
                Attached attached = registry.attach(id);
                assertEquals(index + 1, attached.snapshot.content.toString().length());
                assertNull(attached.queue.poll(100, TimeUnit.MILLISECONDS));
            } else {
                // attach 先行：增量进订阅，快照还是 attach 那一刻的
                java.util.concurrent.CompletableFuture<Attached> waiter =
                        new java.util.concurrent.CompletableFuture<>();
                new Thread(() -> {
                    try {
                        waiter.complete(registry.attach(id));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
                Attached attached = waiter.get(5, TimeUnit.SECONDS);
                int before = attached.snapshot.content.toString().length();
                handle.emit(delta("x"));
                Frame frame = attached.queue.poll(2, TimeUnit.SECONDS);
                assertNotNull(frame);
                assertEquals("x", frames.parse(frame.data()).path("text").asText());
                assertNull(attached.queue.poll(100, TimeUnit.MILLISECONDS));
                assertEquals(index + 1, registry.attach(id).snapshot.content.toString()
                        .length());
                assertEquals(before, attached.snapshot.content.toString().length(),
                        "the snapshot stays at the attach moment");
            }
        }
        handle.retire();
    }

    @Test
    void aPreparingReservationIsExclusiveButNotSubscribable() throws Exception {
        UUID id = id();
        LiveRegistry.Handle handle = registry.begin(id);
        assertThrowsExactly(AppException.class, () -> registry.begin(id));
        // 准备中的占位不可订阅（attach 等 ready），释放后醒来回 idle
        handle.retire();
        assertNull(registry.attach(id));
        LiveRegistry.Handle next = registry.begin(id);
        assertTrue(next != null);
    }

    @Test
    void anAttachmentWaitingForPreparationJoinsTheStartedGeneration() throws Exception {
        UUID id = id();
        LiveRegistry.Handle handle = registry.begin(id);
        java.util.concurrent.CompletableFuture<Attached> attached =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return registry.attach(id);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });
        Thread.sleep(100);
        assertFalse(attached.isDone(), "准备未完成时 attach 等待");
        handle.start();
        Attached a = attached.get(5, TimeUnit.SECONDS);
        assertEquals(handle.generationId, a.snapshot.generationId);
        handle.emit(delta("answer"));
        assertEquals(delta("answer").data(), take(a).data());
        handle.complete(done(false));
        assertEquals("done", take(a).event());
    }

    @Test
    void admissionIsExclusiveAndDroppingAReservationReleasesIt() throws Exception {
        UUID id = id();
        // 两个并发的 begin 恰好一个成功（同步锁下顺序调用同样成立：第二个 409）
        LiveRegistry.Handle first = registry.begin(id);
        UUID oldGeneration = first.generationId;
        AppException conflict = assertThrowsExactly(AppException.class,
                () -> registry.begin(id));
        assertEquals("answer_running", conflict.code().orElse(null));
        first.retire();
        assertNull(registry.attach(id));
        LiveRegistry.Handle current = registry.begin(id);
        LiveRegistry.Cancellation cancellation = current.cancellation();
        registry.stop(id, oldGeneration);
        assertFalse(cancellation.isCancelled(), "迟到的 Stop 不取消新的生成");
        registry.stop(id, current.generationId);
        registry.stop(id, current.generationId);
        // Stop 保留（可能在第一个等待者之前就到）
        cancellation.cancelledFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void theTerminalIsSentAfterTheConversationIsAvailable() throws Exception {
        UUID id = id();
        LiveRegistry.Handle handle = registry.begin(id);
        handle.start();
        Attached attached = registry.attach(id);
        handle.complete(done(true));
        assertEquals("done", take(attached).event());
        // 订阅者可能立即追问
        LiveRegistry.Handle next = registry.begin(id);
        assertTrue(next != null);
    }

    private static UUID id() {
        return UUID.randomUUID();
    }
}
