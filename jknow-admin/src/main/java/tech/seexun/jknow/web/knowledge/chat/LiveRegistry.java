package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import tech.seexun.jknow.common.AppException;

/**
 * 正在生成的回答，能被重新接上（live.rs 逐语义移植）。
 *
 * <p><b>一条 SSE 流绑在一个 HTTP 请求上，而回答比请求活得长。</b>生成已经搬进独立线程
 * （{@link ChatController}），所以刷新页面不会再丢答案；但那条流断了就是断了，刷新之后只能
 * 等它落库，中间那段看不见。这里补上最后一段：生成期间把它登记下来，谁都可以再接上。
 *
 * <p><b>接上时先给一份快照，不是重放事件。</b>事件流会无限长，缓冲它等于把一次对话的全部
 * 增量都留在内存里；而快照的大小就是那个回答本身的大小，有天然上限。客户端拿快照覆盖当前
 * 状态，然后照常收增量。
 *
 * <p>与 Rust 的结构对应：tokio broadcast channel → 每个订阅者一个有界的
 * {@link FrameQueue}（容量 256，溢出即 lagged——订阅者收 {@code stream_lagged}，与 Rust
 * 广播缓冲甩下同语义）；watch&lt;bool&gt; ready → {@link CompletableFuture}；快照与订阅的
 * 边界由 {@code emitLock} 的写锁保证（emit 握写锁记快照+广播，attach 握读锁拿快照+订阅，
 * 两者互斥——接上的时刻要么整个在这次 emit 之前，要么整个在它之后，不漏不重）。
 */
@org.springframework.stereotype.Component
public class LiveRegistry {

    /** 一个 SSE 事件：事件名 + 已经序列化好的 data（live.rs Frame）。 */
    public record Frame(String event, String data) {
    }

    /** 广播缓冲容量（live.rs broadcast::channel(256)）；读得太慢被甩下即 lagged。 */
    static final int BROADCAST_CAPACITY = 256;

    /** 到此刻为止这个回答长什么样。接上的人先拿到它（live.rs Snapshot）。 */
    public static final class Snapshot {

        public final UUID generationId;
        public final StringBuilder content = new StringBuilder();
        static final UUID UNSET = new UUID(0, 0);
        public final List<JsonNode> steps = new ArrayList<>();
        public final List<JsonNode> sources = new ArrayList<>();
        private Frame terminal;

        Snapshot(UUID generationId) {
            this.generationId = generationId == null ? UNSET : generationId;
        }

        /** 快照由事件本身推出来，不另设一套写入口——两套写法迟早对不上。 */
        void apply(ChatFrames frames, Frame f) {
            switch (f.event()) {
                case "delta" -> {
                    JsonNode v = frames.parse(f.data());
                    String t = v.path("text").asText(null);
                    if (t != null) {
                        content.append(t);
                    }
                }
                case "step" -> steps.add(frames.parse(f.data()));
                // sources 是全量重发，不是追加
                case "sources" -> {
                    JsonNode v = frames.parse(f.data());
                    if (v.isArray()) {
                        sources.clear();
                        v.forEach(sources::add);
                    }
                }
                default -> {
                }
            }
        }

        /** 终态帧（done/error）：已终结的会话重接时补发它。 */
        public Frame terminal() {
            return terminal;
        }

        /** 定格一份（Rust attach 的 guard.clone()）：订阅者拿到的快照不再随后续 emit 变形。 */
        Snapshot frozen() {
            Snapshot copy = new Snapshot(generationId);
            copy.content.append(content);
            copy.steps.addAll(steps);
            copy.sources.addAll(sources);
            copy.terminal = terminal;
            return copy;
        }

        public Frame toFrame(ChatFrames frames) {
            ObjectNode out = frames.mapper.createObjectNode();
            out.put("generation_id", generationId.toString());
            out.put("content", content.toString());
            ArrayNode st = out.putArray("steps");
            steps.forEach(st::add);
            ArrayNode so = out.putArray("sources");
            sources.forEach(so::add);
            return new Frame("snapshot", out.toString());
        }
    }

    /** 保留取消信号：Stop 可能早于生成器开始等待（live.rs Cancellation）。 */
    public static final class Cancellation {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CompletableFuture<Void> fired = new CompletableFuture<>();

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                fired.complete(null);
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 阻塞到取消发生（biased select 的「先看取消」那一支）。 */
        public CompletableFuture<Void> cancelledFuture() {
            return fired;
        }
    }

    /** 每个订阅者一条有界队列：慢读者被甩下时置 lagged（tokio broadcast RecvError::Lagged）。 */
    static final class FrameQueue {

        final Object lock = new Object();
        final Frame[] ring = new Frame[BROADCAST_CAPACITY];
        int head;
        int tail;
        long dropped;
        boolean closed;
        CompletableFuture<Void> wakeup = new CompletableFuture<>();

        void push(Frame f) {
            synchronized (lock) {
                int next = (tail + 1) % ring.length;
                if (next == head) {
                    // 缓冲满：按 tokio broadcast 语义丢最旧的，读者报 lagged
                    head = (head + 1) % ring.length;
                    dropped++;
                }
                ring[tail] = f;
                tail = next;
                wakeup.complete(null);
            }
        }

        void close() {
            synchronized (lock) {
                closed = true;
                wakeup.complete(null);
            }
        }

        /** 限时取一帧：到点没等到回 null（恰在此刻入队的帧由下一次 poll 取走）；
         * lagged 抛 IllegalStateException(dropped)；closed 且空后恒 null。 */
        Frame poll(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (true) {
                synchronized (lock) {
                    if (dropped > 0) {
                        long n = dropped;
                        dropped = 0;
                        throw new IllegalStateException("lagged:" + n);
                    }
                    if (head != tail) {
                        Frame f = ring[head];
                        ring[head] = null;
                        head = (head + 1) % ring.length;
                        return f;
                    }
                    if (closed) {
                        return null;
                    }
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    synchronized (lock) {
                        if (dropped > 0) {
                            long n = dropped;
                            dropped = 0;
                            throw new IllegalStateException("lagged:" + n);
                        }
                        return null;
                    }
                }
                Thread.sleep(Math.min(5, java.util.concurrent.TimeUnit.NANOSECONDS
                        .toMillis(remaining) + 1));
            }
        }

        Frame take() throws InterruptedException {
            while (true) {
                CompletableFuture<Void> wait;
                synchronized (lock) {
                    if (dropped > 0) {
                        long n = dropped;
                        dropped = 0;
                        throw new IllegalStateException("lagged:" + n);
                    }
                    if (head != tail) {
                        Frame f = ring[head];
                        ring[head] = null;
                        head = (head + 1) % ring.length;
                        return f;
                    }
                    if (closed) {
                        return null;
                    }
                    wait = wakeup;
                }
                try {
                    wait.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static final class Entry {

        final UUID generationId;
        final Snapshot snap;
        final Cancellation cancellation;
        final List<FrameQueue> subscribers = new CopyOnWriteArrayList<>();
        /** ready：占位完成（start）之前 attach 要等它；失败释放时等的人醒来回 idle。 */
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        final ReentrantReadWriteLock emitLock = new ReentrantReadWriteLock();
        /** 谁占的谁才撤得掉：retire 只在快照还是自己那份时移除（Handle 身份等价）。 */
        boolean retired;

        Entry(UUID generationId) {
            this.generationId = generationId;
            this.snap = new Snapshot(generationId);
            this.cancellation = new Cancellation();
        }
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final ChatFrames frames;

    public LiveRegistry(ChatFrames frames) {
        this.frames = frames;
    }

    /**
     * 一次生成期间握着的把手（live.rs Handle）。发事件、结束时注销。
     * 生成器线程持有；HTTP 连接只是去 attach。
     */
    public final class Handle {

        public final UUID conversationId;
        public final UUID generationId;
        private final Entry entry;

        Handle(UUID conversationId, Entry entry) {
            this.conversationId = conversationId;
            this.generationId = entry.generationId;
            this.entry = entry;
        }

        public Cancellation cancellation() {
            return entry.cancellation;
        }

        public Snapshot snapshot() {
            entry.emitLock.readLock().lock();
            try {
                return entry.snap;
            } finally {
                entry.emitLock.readLock().unlock();
            }
        }

        /** 准备成功后才开放订阅；被拒的重试只释放占位，不会留下等待终态的客户端。 */
        public void start() {
            entry.ready.complete(true);
        }

        /**
         * 发一个事件：记进快照，然后广播。广播时仍然握着快照的写锁——只保证「先写后发」
         * 挡不住重复：接上的人在两步之间订阅，就会既在快照里看到这一段、又从广播里再收
         * 一次。握着锁发，attach 那边握着读锁订阅，两者互斥。
         */
        public void emit(Frame frame) {
            entry.emitLock.writeLock().lock();
            try {
                if (entry.snap.terminal != null) {
                    return;
                }
                entry.snap.apply(frames, frame);
                if (frame.event().equals("done") || frame.event().equals("error")) {
                    entry.snap.terminal = frame;
                }
                for (FrameQueue q : entry.subscribers) {
                    q.push(frame);
                }
            } finally {
                entry.emitLock.writeLock().unlock();
            }
        }

        /**
         * 持久化完成后，先释放会话（下一条提问即可开始），再通知订阅者终态；快照和订阅仍
         * 同一边界。终态若已由 {@link #emit} 发出（error/done 走了 emit），这里只做释放。
         */
        public void complete(Frame terminal) {
            unregister();
            entry.emitLock.writeLock().lock();
            try {
                if (entry.snap.terminal != null) {
                    return;
                }
                entry.snap.terminal = terminal;
                for (FrameQueue q : entry.subscribers) {
                    q.push(terminal);
                }
                for (FrameQueue q : entry.subscribers) {
                    q.close();
                }
                entry.ready.complete(true);
            } finally {
                entry.emitLock.writeLock().unlock();
            }
        }

        /** 释放占位（Drop 语义）：准备失败时等待 attach 的人醒来回 idle。 */
        public void retire() {
            unregister();
            // 与 Rust 的 Drop 对齐：watch 的发送端没了，attach 的等待者以 false 醒来
            entry.ready.complete(false);
            for (FrameQueue q : entry.subscribers) {
                q.close();
            }
        }

        /** 从注册表移除自己那份占位——只在自己仍是在册的那个时（老把手不能撤新人的）。 */
        private void unregister() {
            synchronized (LiveRegistry.this.entries) {
                Entry current = entries.get(conversationId);
                if (current == entry && !entry.retired) {
                    entries.remove(conversationId);
                    entry.retired = true;
                }
            }
        }
    }

    /**
     * 检查和占位在同一把锁下，新问题与重试都只允许一个生成者
     * （live.rs begin：409 {@code answer_running}）。
     */
    public Handle begin(UUID conversationId) {
        synchronized (entries) {
            if (entries.containsKey(conversationId)) {
                throw AppException.codedConflict("answer_running",
                        "This conversation is still generating an answer.");
            }
            UUID generationId = tech.seexun.jknow.common.uuids.Uuids.nowV7();
            Entry entry = new Entry(generationId);
            entries.put(conversationId, entry);
            return new Handle(conversationId, entry);
        }
    }

    /** 延迟或重复的 Stop 不能取消下一轮生成（live.rs stop：generation_id 对不上就忽略）。 */
    public void stop(UUID conversationId, UUID generationId) {
        synchronized (entries) {
            Entry entry = entries.get(conversationId);
            if (entry != null && entry.generationId.equals(generationId)) {
                entry.cancellation.cancel();
            }
        }
    }

    /** attach 的产物：快照 + 之后增量的队列；null = 没有在跑的生成（那不是错）。 */
    public static final class Attached {

        public final Snapshot snapshot;
        public final FrameQueue queue;

        Attached(Snapshot snapshot, FrameQueue queue) {
            this.snapshot = snapshot;
            this.queue = queue;
        }
    }

    /**
     * 接上一次正在跑的生成：拿到此刻的快照，以及之后的增量。先等 ready（准备失败时醒来
     * 返回 null 回 idle），然后<b>握着快照的读锁再订阅</b>——emit 是握着写锁广播的，这一段
     * 与任何一次 emit 互斥：拿到的快照与订阅起点严丝合缝，不漏不重。
     */
    public Attached attach(UUID conversationId) throws InterruptedException {
        Entry entry;
        CompletableFuture<Boolean> ready;
        synchronized (entries) {
            entry = entries.get(conversationId);
            if (entry == null) {
                return null;
            }
            ready = entry.ready;
        }
        boolean started;
        try {
            started = ready.get();
        } catch (java.util.concurrent.ExecutionException e) {
            started = false;
        }
        if (!started) {
            // 占位被放弃（准备失败）：回到 idle
            return null;
        }
        entry.emitLock.readLock().lock();
        try {
            FrameQueue queue = new FrameQueue();
            entry.subscribers.add(queue);
            // 终态已定（complete 先于这次 attach）：把终态亲手递给新订阅者，
            // 它错过广播，但快照里记着它
            if (entry.snap.terminal != null) {
                queue.push(entry.snap.terminal);
                queue.close();
            }
            return new Attached(entry.snap.frozen(), queue);
        } finally {
            entry.emitLock.readLock().unlock();
        }
    }

    /** 测试与运维面：当前占用的会话（幂等观察）。 */
    public boolean running(UUID conversationId) {
        synchronized (entries) {
            return entries.containsKey(conversationId);
        }
    }
}
