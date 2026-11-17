package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;

/**
 * 驱动可取消的生成，再不受取消打断地保存唯一结果（chat_generation.rs 逐语义）。
 *
 * <p>生产者（{@link ChatRunner}）跑在独立线程上，事件进队列；这里 biased 先看取消——
 * Stop 到了就丢弃生产者（interrupt，best-effort），按<b>已发出的快照</b>存部分答案：
 * 迟到的 Stop 打不断提交，迟到的产出作废。
 */
@Service
public class ChatGeneration {

    private static final Logger log = LoggerFactory.getLogger(ChatGeneration.class);

    /** 生成线程池：每个会话一次生成独占一条线程（浏览器断线不丢回答的代价是它继续跑）。 */
    private final ExecutorService producers = Executors.newCachedThreadPool();

    private final ChatFrames frames;
    private final ConversationsService conversations;
    private final ChatRunner runner;

    public ChatGeneration(ChatFrames frames, ConversationsService conversations,
                          ChatRunner runner) {
        this.frames = frames;
        this.conversations = conversations;
        this.runner = runner;
    }

    /**
     * 生成登记在案、订阅接上之后由控制器调用（顺序：begin→start→attach→submit——订阅
     * 先于生成，首帧不丢）：跑完整场生成到唯一终态。
     */
    public void submit(UUID conversationId, LiveRegistry.Handle handle, ChatRunner.RunInput input,
                       String previousAnswer, List<JsonNode> previousSources) {
        producers.submit(() -> {
            BlockingQueue<ChatRunner.ProducerEvent> queue = new LinkedBlockingQueue<>();
            Thread producer = new Thread(() -> runner.produce(input, queue),
                    "chat-producer-" + conversationId);
            producer.start();
            LiveRegistry.Frame terminal = drive(conversationId, handle, queue, producer,
                    previousAnswer, previousSources);
            handle.complete(terminal);
        });
    }

    private LiveRegistry.Frame drive(UUID conversationId, LiveRegistry.Handle handle,
                                     BlockingQueue<ChatRunner.ProducerEvent> queue,
                                     Thread producer, String previousAnswer,
                                     List<JsonNode> previousSources) {
        LiveRegistry.Cancellation cancellation = handle.cancellation();
        List<JsonNode> resolved = new ArrayList<>();
        List<JsonNode> toolExchange = new ArrayList<>();
        boolean gathered = false;
        while (true) {
            // biased：先看取消——保存部分答案期间不能继续执行模型或工具
            if (cancellation.isCancelled()) {
                producer.interrupt();
                LiveRegistry.Snapshot snapshot = handle.snapshot();
                List<JsonNode> sources = new ArrayList<>(snapshot.sources);
                if (!gathered && sources.isEmpty()) {
                    sources = ChatRunner.carriedSources(snapshot.content.toString(),
                            previousAnswer == null ? "" : previousAnswer, previousSources);
                }
                ChatRunner.Answer answer = new ChatRunner.Answer(snapshot.content.toString(),
                        new TurnRecord(true, toArray(snapshot.steps), toArray(sources),
                                toArray(resolved), toArray(completedExchange(toolExchange))),
                        null);
                return save(conversationId, handle, answer);
            }
            ChatRunner.ProducerEvent event;
            try {
                event = queue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            }
            if (event == null) {
                if (!producer.isAlive() && exhausted(queue)) {
                    return frames.errorEvent("stream_ended",
                            "Answer stream ended unexpectedly");
                }
                continue;
            }
            if (event instanceof ChatRunner.ProducerEvent.Progress progress) {
                LiveRegistry.Frame frame = progress.frame();
                if ("done".equals(frame.event()) || "error".equals(frame.event())) {
                    // 生产者把终态当进度发 = 内部协议破坏，按失败收口（#857 那一族）
                    return frames.errorEvent("answer_failed",
                            "Producer sent a terminal as progress");
                }
                // 旧 RAG 路径即使没搜到结果也会发布 sources
                gathered |= "step".equals(frame.event()) || "sources".equals(frame.event());
                handle.emit(frame);
            } else if (event instanceof ChatRunner.ProducerEvent.Context context) {
                resolved = context.resolved();
                toolExchange = context.toolExchange();
                gathered |= context.gathered();
            } else if (event instanceof ChatRunner.ProducerEvent.Outcome outcome) {
                // 迟到的 Outcome 不消费：取消之后的产出作废
                if (cancellation.isCancelled()) {
                    continue;
                }
                if (outcome.answer() != null) {
                    return save(conversationId, handle, outcome.answer());
                }
                // 失败只送给浏览器一份，服务端一行不留，线上就只能请人打开开发者工具抄
                // 回来。code 与原句都记：原句是端点说的话，code 是界面查措辞的键
                log.warn("Answer failed: conversation={} code={} error={}", conversationId,
                        outcome.failure().code(), outcome.failure().message());
                return frames.errorEvent(outcome.failure().code(), outcome.failure().message());
            }
        }
    }

    private boolean exhausted(BlockingQueue<ChatRunner.ProducerEvent> queue) {
        // 生产者死了且队列空了还没有 Outcome：流断了（stream_ended）
        return queue.isEmpty();
    }

    /**
     * 落库后发终态帧（save）：终稿（final_text）<b>先落库后发 delta</b>——发布未提交的
     * 文本，刷新的人会看到一段闪现又消失的回答；先提交再发，读到 delta 的人去查历史
     * 一定查得到。失败按 answer_not_saved 收口，绝不做 done（#857）。
     */
    private LiveRegistry.Frame save(UUID conversationId, LiveRegistry.Handle handle,
                                    ChatRunner.Answer answer) {
        UUID saved;
        try {
            saved = conversations.appendMessage(conversationId, "assistant", answer.content(),
                    answer.record());
        } catch (Exception e) {
            log.error("Could not persist chat answer: conversation={}", conversationId, e);
            return frames.errorEvent("answer_not_saved",
                    "Could not confirm that the answer was saved.");
        }
        handle.emit(new LiveRegistry.Frame("sources", answer.record().sources().toString()));
        if (answer.finalText() != null) {
            handle.emit(frames.deltaEvent(answer.finalText()));
        }
        ObjectNode done = frames.mapper.createObjectNode();
        done.put("stopped", answer.record().stopped());
        return new LiveRegistry.Frame("done", done.toString());
    }

    private JsonNode toArray(List<JsonNode> list) {
        ArrayNode arr = frames.mapper.createArrayNode();
        list.forEach(arr::add);
        return arr;
    }

    /**
     * 并行工具可能只完成一部分就被停止。不能回放没有结果的 tool_call：模型服务要求每个
     * 保留的调用都有对应结果。模型服务可能在后续轮次复用 ID：倒序遍历，让每条助手消息
     * 只匹配它之后、下一条助手消息之前的工具结果。
     */
    public static List<JsonNode> completedExchange(List<JsonNode> exchange) {
        List<JsonNode> copy = new ArrayList<>();
        for (JsonNode m : exchange) {
            copy.add(m.deepCopy());
        }
        Set<String> answered = new HashSet<>();
        for (int i = copy.size() - 1; i >= 0; i--) {
            JsonNode message = copy.get(i);
            if ("tool".equals(message.path("role").asText())) {
                String id = message.path("tool_call_id").asText(null);
                if (id != null) {
                    answered.add(id);
                }
                continue;
            }
            JsonNode calls = message.get("tool_calls");
            if (calls != null && calls.isArray()) {
                List<JsonNode> keep = new ArrayList<>();
                for (JsonNode call : calls) {
                    if (answered.contains(call.path("id").asText(null))) {
                        keep.add(call);
                    }
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) message)
                        .set("tool_calls", toArrayNode(keep));
            }
            answered.clear();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode message : copy) {
            if (!"assistant".equals(message.path("role").asText())) {
                out.add(message);
                continue;
            }
            JsonNode calls = message.path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                out.add(message);
            }
        }
        return out;
    }

    private static ArrayNode toArrayNode(List<JsonNode> list) {
        ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        list.forEach(arr::add);
        return arr;
    }
}
