package tech.seexun.jknow.web.knowledge.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 服务内事件总线 + SSE 广播（对齐旧仓库 state.rs:9-18 AppEvent / broadcast(256) +
 * events_routes.rs:15-52 kb_event_stream——M1 随事件面整体 deferred，M2 任务 3 起实播）。
 *
 * <ul>
 *   <li><b>事件无业务数据</b>（AppEvent{kb_id,kind,document_id} 是刷新信号不是载荷）：
 *       前端收到只做 react-query 失效重取，天然幂等；</li>
 *   <li><b>本库事件原样送出</b>（event=kind、data=AppEvent JSON），<b>告警不按库过滤、
 *       不带数据</b>（event=alert、data={}，收到的人一律重取列表——「谁能看见什么」在列表
 *       查询里判且只判一次）；</li>
 *   <li><b>KeepAlive</b>：15s 一条注释心跳（对等 axum KeepAlive::default）；</li>
 *   <li><b>掉线即断</b>：Rust 侧 broadcast(256) 的 Lagged 即断（丢掉的可能是唯一通知，
 *       结束响应让 EventSource 重连补刷）；Java SseEmitter 无背压通道，以「发送失败即
 *       注销订阅」近似——死连接由容器写失败暴露，重连补刷的客户端语义相同。
 *       <b>不会静默丢单条事件</b>（直发无队列），这是与 Rust 有界通道的可解释偏差，
 *       慢消费者在 TCP 层自然背压。</li>
 * </ul>
 */
@Component
public class AppEventBus {

    private static final Logger log = LoggerFactory.getLogger(AppEventBus.class);

    /** axum KeepAlive::default()：15s 间隔的心跳注释。 */
    private static final long KEEPALIVE_MS = 15_000;

    /**
     * 服务内事件（state.rs:11-18 逐字段）：kind=document/source/review/pending/graph/alert；
     * kbId null=系统级（告警角标跨库）。字段名经全局 SNAKE_CASE 序列化为
     * kb_id/kind/document_id（与 Rust serde 同名同序）。
     */
    public record AppEvent(UUID kbId, String kind, UUID documentId) {
    }

    /** 一个 SSE 订阅：目标库 + 发射器（onCompletion/onTimeout/onError 即注销）。 */
    private record Subscription(UUID kbId, SseEmitter emitter) {
    }

    private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;

    public AppEventBus(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 无订阅者时静默忽略（state.rs:85-92 的 `let _ =` 语义）。 */
    public void emitDocument(UUID kbId, UUID documentId) {
        send(new AppEvent(kbId, "document", documentId));
    }

    public void emitSource(UUID kbId) {
        send(new AppEvent(kbId, "source", null));
    }

    /** state.rs:94-98 emit_review：审核队列多了东西才叫这一声。 */
    public void emitReview(UUID kbId) {
        send(new AppEvent(kbId, "review", null));
    }

    /** state.rs:104-108 emit_pending：待确认队列（记忆日志）多了东西——对话里那张确认卡靠它。 */
    public void emitPending(UUID kbId) {
        send(new AppEvent(kbId, "pending", null));
    }

    /** state.rs:114-118 emit_graph：图变了（抽取落库/合并/物化）。 */
    public void emitGraph(UUID kbId) {
        send(new AppEvent(kbId, "graph", null));
    }

    public void emitAlert() {
        send(new AppEvent(null, "alert", null));
    }

    /** 当前订阅数（测试/观测用）。 */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * 订阅一个库的事件流（events_routes.rs:23-31 kb_events）：Viewer 校验在路由层。
     * 初次连接不凭空发刷新事件（Rust 测试同款语义）。
     */
    public SseEmitter subscribe(UUID kbId) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription sub = new Subscription(kbId, emitter);
        Runnable drop = () -> subscribers.remove(sub);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        subscribers.add(sub);
        return emitter;
    }

    /**
     * relay + 送出（events_routes.rs:35-52/74-91 逐分支）。帧语义逐字对齐 axum
     * （event=kind、data=AppEvent JSON / alert 恒 {}）；Spring SseEmitter 的线上写法
     * 冒号后无空格（{@code event:document}，SSE 规范两种都合法，EventSource 等价解析），
     * 与 Rust（{@code event: document}）相差一个空格——线上字节不同形，语义相同，
     * 契约剧本无 SSE 步（差异不进黄金比对）。
     */
    private void send(AppEvent event) {
        if (subscribers.isEmpty()) {
            return;
        }
        boolean alert = "alert".equals(event.kind());
        for (Subscription sub : subscribers) {
            try {
                if (alert) {
                    // 告警：不按库过滤、不带数据（#1028/「推送这条路上零权限逻辑」）；
                    // "{}" 以纯文本送出（不经 JSON 转换器——那会多一对引号）
                    sub.emitter().send(SseEmitter.event().name(event.kind()).data("{}"));
                } else if (event.kbId() != null && event.kbId().equals(sub.kbId())) {
                    sub.emitter().send(SseEmitter.event().name(event.kind())
                            .data(event, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException dead) {
                // 掉线即断：注销订阅，客户端 EventSource 重连后补刷
                subscribers.remove(sub);
            }
        }
    }

    /** 心跳（KeepAlive::default 15s；失败的订阅顺带被注销）。 */
    @Scheduled(fixedDelay = KEEPALIVE_MS)
    public void keepAlive() {
        for (Subscription sub : subscribers) {
            try {
                sub.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException dead) {
                subscribers.remove(sub);
            }
        }
    }
}
