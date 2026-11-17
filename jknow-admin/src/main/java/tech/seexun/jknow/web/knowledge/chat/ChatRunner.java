package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.llm.ChatClient.AssistantTurn;
import tech.seexun.jknow.llm.ChatClient.ToolCall;
import tech.seexun.jknow.search.SearchService;
import tech.seexun.jknow.store.ConversationsService.TurnRecord;
import tech.seexun.jknow.store.entity.ChunkView;

/**
 * 自研 runner-with-hooks 的对话循环（chat.rs producer + agent.rs Policy 的合并实现；
 * 0042「chat loop is a runner with hooks」）。循环结构钉住三件事：
 * <ul>
 *   <li><b>一轮对话在调过至少一个工具之前不能结束</b>：首个请求带 tool_choice=required，
 *       直到某个工具跑过为止；no_evidence_needed 是「你好」这类不需要库的问题诚实的出口
 *       ——于是「请稍等，我将调用工具」不再是一个可能的终态。</li>
 *   <li><b>MAX_ROUNDS=6 按模型请求计数</b>（feedback 退回也算一轮——早退回不白给，预算
 *       不因此变长）；第 7 次请求在 I/O 前交给纯作答阶段（{@link ChatFinalizer}）。</li>
 *   <li><b>生产者只往队列里放事件</b>：发布、落库、停止全部在生成驱动
 *       （{@link ChatGeneration}）手里——它 biased 先看取消。</li>
 * </ul>
 */
@Service
public class ChatRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatRunner.class);

    public static final int MAX_ROUNDS = 6;
    /** 回放几个已认下的实体：长会话会攒出几十个，全贴回去就把省下来的上下文又花掉了。 */
    public static final int KNOWN_ENTITY_LIMIT = 20;

    /** 一次没答成的生成（0004）：code 给界面查措辞，英文原句留给日志与不做本地化的客户端。 */
    public record Failure(String code, String message) {
    }

    /** 终稿：正文、落库五元组、以及验证过但尚未发布的终稿（先落库后发 delta）。 */
    public record Answer(String content, TurnRecord record, String finalText) {
    }

    /** 生产者产出的事件（generation.rs ProducerEvent）。 */
    public sealed interface ProducerEvent {

        record Progress(LiveRegistry.Frame frame) implements ProducerEvent {
        }

        /** 仅供历史使用，不通过 SSE 发送；停止时只保留已完成的调用。 */
        record Context(List<JsonNode> resolved, List<JsonNode> toolExchange,
                       boolean gathered) implements ProducerEvent {
        }

        record Outcome(Answer answer, Failure failure) implements ProducerEvent {
        }
    }

    /** 一场对话的工具共用面（agent.rs Shared）：引用清单、闸门与一次性退回的记性。 */
    private static final class Shared {

        final ToolSink sink = new ToolSink();
        /** 任何工具（含 no_evidence_needed）跑过一次：required 的闸门就过了。 */
        boolean gatePassed = false;
        boolean nudged = false;
        boolean askedAgain = false;
        boolean markupRetried = false;
        boolean finalizing = false;
        boolean answerRequested = false;
    }

    /** 一场生成的全部入参（chat.rs 入口攒下来的那盘）。 */
    public static final class RunInput {

        final ChatClient client;
        final tech.seexun.jknow.store.entity.KbRow kb;
        final ToolExecutor.ToolContext toolContext;
        final UUID conversationId;
        final UUID userMessageId;
        final UUID generationId;
        final String question;
        final List<String[]> priorTurns;
        final List<JsonNode> lastToolExchange;
        final List<JsonNode> knownEntities;
        final String previousAnswer;
        final List<JsonNode> lastSources;
        final LiveRegistry.Cancellation cancellation;

        public RunInput(ChatClient client, tech.seexun.jknow.store.entity.KbRow kb,
                        ToolExecutor.ToolContext toolContext, UUID conversationId,
                        UUID userMessageId, UUID generationId, String question,
                        List<String[]> priorTurns, List<JsonNode> lastToolExchange,
                        List<JsonNode> knownEntities, String previousAnswer,
                        List<JsonNode> lastSources, LiveRegistry.Cancellation cancellation) {
            this.client = client;
            this.kb = kb;
            this.toolContext = toolContext;
            this.conversationId = conversationId;
            this.userMessageId = userMessageId;
            this.generationId = generationId;
            this.question = question;
            this.priorTurns = priorTurns;
            this.lastToolExchange = lastToolExchange;
            this.knownEntities = knownEntities;
            this.previousAnswer = previousAnswer;
            this.lastSources = lastSources;
            this.cancellation = cancellation;
        }
    }

    /** 生产者被要求停止（取消之后的任何检查点抛出，等同 rig 的 PromptCancelled 丢弃）。 */
    static final class StoppedException extends RuntimeException {

        StoppedException() {
            super(null, null, false, false);
        }
    }

    /** 终稿超 1MiB：从 onDelta 回调里抛出来中止流（answer_too_long）。 */
    static final class AnswerTooLongException extends RuntimeException {

        AnswerTooLongException() {
            super(null, null, false, false);
        }
    }

    private final ChatFrames frames;
    private final ChatToolSchema schema;
    private final ToolExecutor tools;
    private final ChatFinalizer finalizer;
    private final SearchService search;
    private final tech.seexun.jknow.store.MappingsService mappings;
    private final tech.seexun.jknow.store.SettingsService settings;
    private final tech.seexun.jknow.web.knowledge.llm.LlmSupport llm;

    public ChatRunner(ChatFrames frames, ChatToolSchema schema, ToolExecutor tools,
                      ChatFinalizer finalizer, SearchService search,
                      tech.seexun.jknow.store.MappingsService mappings,
                      tech.seexun.jknow.store.SettingsService settings,
                      tech.seexun.jknow.web.knowledge.llm.LlmSupport llm) {
        this.frames = frames;
        this.schema = schema;
        this.tools = tools;
        this.finalizer = finalizer;
        this.search = search;
        this.mappings = mappings;
        this.settings = settings;
        this.llm = llm;
    }

    /**
     * 整条生产循环（chat.rs 的 async_stream producer）。跑在生成线程上，事件进队列；
     * 取消之后的第一个检查点直接抛 {@link StoppedException}——驱动不消费它的产出。
     */
    public void produce(RunInput input, java.util.concurrent.BlockingQueue<ProducerEvent> out) {
        try {
            runLoop(input, out);
        } catch (StoppedException stopped) {
            // 取消：驱动已按快照收尾，这里的产出作废
        } catch (AnswerTooLongException tooLong) {
            push(out, new ProducerEvent.Outcome(null, new Failure("answer_too_long",
                    "Model final answer exceeded the size limit")));
        } catch (Exception e) {
            log.warn("chat producer ended unexpectedly", e);
            push(out, new ProducerEvent.Outcome(null, new Failure("answer_failed",
                    "LLM stream ended unexpectedly")));
        }
    }

    private void runLoop(RunInput input,
                         java.util.concurrent.BlockingQueue<ProducerEvent> out) throws Exception {
        // 依赖模型的准备工作之前先发送身份，早到的 Stop 也能指向本轮。会话 id 先行下发
        // （新会话由此告知前端）；这一问存下的 id 一起下发：答到一半失败了，界面凭它重答
        ObjectNode identity = frames.mapper.createObjectNode();
        identity.put("id", input.conversationId.toString());
        identity.put("message_id", input.userMessageId.toString());
        identity.put("generation_id", input.generationId.toString());
        push(out, new ProducerEvent.Progress(
                new LiveRegistry.Frame("conversation", identity.toString())));

        Shared shared = new Shared();
        ChatHistoryContext context = ChatHistoryContext.of(input.priorTurns,
                input.lastToolExchange, input.client.historyCharBudget());

        // 语义层：跟这个问题有关的那几条确认口径进 system prompt——问数优先用确认口径，
        // 而不是每次从 schema 猜。挑口径要调嵌入模型，所以放在生产者里：Stop 丢掉生成器，
        // 这个请求跟着取消（0063）。代价是它失败得晚了：从前是什么都没写的 500，现在问题
        // 已经存下，失败是一帧 error。这一轮的嵌入缓存从挑口径起就在：问题在这里嵌一次，
        // 随后检索分块、按名字查实体都拿同一个向量（#971 的后续）
        List<tech.seexun.jknow.store.entity.ConceptMapping> relevant = List.of();
        if (!input.toolContext.mountedSources.isEmpty()) {
            try {
                relevant = mappings.relevant(input.toolContext.kbId, input.question,
                        tech.seexun.jknow.store.MappingsService.DEFINITIONS_IN_PROMPT,
                        cachedEmbedder(shared.sink, input), embedModelName(input));
            } catch (Exception e) {
                log.warn("Could not retrieve conversation mappings: {}", e.toString());
                push(out, new ProducerEvent.Outcome(null,
                        new Failure("mapping_search_failed",
                                "Could not retrieve relevant mappings.")));
                return;
            }
        }
        List<String> dsNames = new ArrayList<>();
        input.toolContext.mountedSources.forEach(d -> dsNames.add(d.name()));
        JsonNode toolsSchema = schema.toolsSchema(input.toolContext.canWrite, dsNames);
        String systemPrompt = buildSystemPrompt(input, dsNames, relevant);

        // 消息列表整轮不变：system 前导 + 完整历史 + 认下的实体（system，紧挨问题）+ 问题
        List<JsonNode> messages = new ArrayList<>();
        messages.add(systemMessage(systemPrompt));
        messages.addAll(ChatGuards.historyMessages(frames, input.priorTurns,
                input.lastToolExchange));
        String entitiesBlock = ChatGuards.knownEntitiesBlock(frames, input.knownEntities,
                KNOWN_ENTITY_LIMIT);
        if (entitiesBlock != null) {
            messages.add(systemMessage(entitiesBlock));
        }
        ObjectNode questionMsg = frames.mapper.createObjectNode();
        questionMsg.put("role", "user");
        questionMsg.put("content", input.question);
        messages.add(questionMsg);

        // 落库累积：assistant 全文与行动轨迹（历史回放用）
        StringBuilder answerAcc = new StringBuilder();
        List<JsonNode> stepsAcc = new ArrayList<>();
        // 这一轮的工具往返，按协议原样留一份落库：下一轮回放它，模型才知道自己做过什么
        List<JsonNode> exchangeAcc = new ArrayList<>();
        // 当前模型回合里说的话与发出的调用；回合的结果一到，攒成一条 assistant 消息
        StringBuilder turnText = new StringBuilder();
        List<JsonNode> turnCalls = new ArrayList<>();
        // 这一回合的正文发出去了多少；后面扣着（可能是写成正文的工具调用，#937）
        int[] turnPublished = {0};
        boolean[] turnHolding = {false};
        List<JsonNode> publishedSources = new ArrayList<>();
        boolean finished = false;
        int turn = 0;

        while (true) {
            checkStop(input);
            // ---- 钩子 on_completion_call：第 MAX_ROUNDS+1 次请求在 I/O 前交给纯作答 ----
            turn++;
            shared.finalizing = turn > MAX_ROUNDS;
            if (shared.finalizing) {
                // 路由只在 answer_requested 时读这个标记（RigModel 钩子的 Stop 分支）
                shared.answerRequested = true;
                break;
            }
            JsonNode toolChoice = !shared.gatePassed
                    ? frames.mapper.getNodeFactory().textNode("required") : null;

            // ---- 开一条流：端点不认 tool_choice 就去掉重发一次；超限裁历史重发 ----
            AssistantTurn modelTurn = null;
            Throwable loopError = null;
            JsonNode choice = toolChoice;
            turnText.setLength(0);
            turnPublished[0] = 0;
            turnHolding[0] = false;
            turnCalls = new ArrayList<>();
            while (true) {
                checkStop(input);
                List<JsonNode> wire = context.apply(frames, messages);
                try {
                    modelTurn = streamTurn(input.client, wire, toolsSchema, choice,
                            shared, turnText, turnPublished, turnHolding, answerAcc, out);
                    break;
                } catch (AnswerTooLongException tooLong) {
                    throw tooLong;
                } catch (Exception e) {
                    loopError = e;
                    if (context.recover(input.client, e)) {
                        continue;
                    }
                    if (choice != null && ChatFinalizer.rejectedShape(e)) {
                        log.warn("端点不接受 tool_choice，去掉重发");
                        choice = null;
                        continue;
                    }
                    break;
                }
            }
            if (modelTurn == null) {
                // ---- 循环内失败：code 表 + 仅在「端点拒绝带工具的请求且尚无产出」时降级 ----
                String code = ChatFinalizer.modelFailureCode(loopError);
                Failure failure = new Failure(code == null ? "answer_failed" : code,
                        loopError.getMessage() == null ? String.valueOf(loopError)
                                : loopError.getMessage());
                boolean rejected = ChatFinalizer.rejectedShape(loopError);
                // 只有「端点拒绝了带工具的请求」才降级：从前首轮的任何错误都走这条路，
                // 一次网络抖动被记成「tool-calling 不可用」，然后 RAG 死在同一个抖动上
                if (rejected && answerAcc.length() == 0 && stepsAcc.isEmpty()) {
                    log.warn("端点拒绝工具调用，降级为一次性 RAG");
                    legacyRag(input, context, out);
                    return;
                }
                push(out, new ProducerEvent.Outcome(null, failure));
                return;
            }
            for (ToolCall c : modelTurn.toolCalls) {
                ObjectNode call = frames.mapper.createObjectNode();
                call.put("id", c.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", c.name());
                fn.put("arguments", c.arguments());
                turnCalls.add(call);
            }

            // ---- 钩子 on_model_turn_finished：空回复 / 写成正文的调用 / 该调不调，各退一次 ----
            boolean hasToolCall = !modelTurn.toolCalls.isEmpty();
            boolean hasText = modelTurn.content != null && !modelTurn.content.trim().isEmpty();
            boolean writtenCall = !hasToolCall
                    && ChatGuards.toolCallText(turnText.toString(), input.question);
            String feedback = null;
            if (!hasToolCall && !hasText) {
                // 空回复不是一个结论，只是没说话：重问一次；再空才以 answer_empty 收尾
                if (!shared.askedAgain) {
                    shared.askedAgain = true;
                    feedback = ChatGuards.EMPTY_REPLY_RETRY;
                }
            } else if (writtenCall) {
                // 什么都没执行，这段也不是答案：退回一次（保留那一回合，模型看得见自己写了什么）
                if (!shared.markupRetried) {
                    shared.markupRetried = true;
                    feedback = ChatGuards.MARKUP_RETRY;
                } else {
                    log.warn("退回一次后仍把工具调用写成正文，按错误收尾");
                }
            } else if (!hasToolCall && !shared.gatePassed) {
                // 只有文字、闸门没过：端点无视了 required。退回去一次；再不调就认了
                if (!shared.nudged) {
                    shared.nudged = true;
                    feedback = ChatGuards.MUST_CALL;
                } else {
                    log.warn("退回一次后仍无工具调用，按原文收尾（sources 为空）");
                }
            }
            if (feedback != null) {
                // 已流给用户的话收不回来，接下来的正文另起一段；退回的那句话放句尾
                if (turnPublished[0] > 0) {
                    answerAcc.append("\n\n");
                    push(out, new ProducerEvent.Progress(frames.deltaEvent("\n\n")));
                }
                if (modelTurn.content != null && !modelTurn.content.isEmpty()) {
                    ObjectNode assistant = frames.mapper.createObjectNode();
                    assistant.put("role", "assistant");
                    assistant.put("content", modelTurn.content);
                    messages.add(assistant);
                } else if (hasToolCall) {
                    messages.add(assistantWithCalls(frames, turnCalls));
                }
                messages.add(userMessage(feedback));
                turnText.setLength(0);
                turnPublished[0] = 0;
                turnHolding[0] = false;
                turnCalls = new ArrayList<>();
                continue;
            }

            if (hasToolCall) {
                // ---- 执行工具（按调用序；全部结果到齐才进入下一轮请求） ----
                checkStop(input);
                // 扣着的是标记就丢掉也不回放；不是（围栏里的示例）就补发，赶在结果之前，
                // `at` 才对得上
                if (turnPublished[0] < turnText.length()) {
                    if (ChatGuards.toolCallText(turnText.toString(), input.question)) {
                        turnText.setLength(turnPublished[0]);
                    } else {
                        String rest = turnText.substring(turnPublished[0]);
                        answerAcc.append(rest);
                        push(out, new ProducerEvent.Progress(frames.deltaEvent(rest)));
                    }
                }
                // 工具轮带了叙述文本：与后续轮次的正文之间补一个段落分隔
                if (turnText.length() > 0) {
                    answerAcc.append("\n\n");
                    push(out, new ProducerEvent.Progress(frames.deltaEvent("\n\n")));
                }
                ObjectNode assistantMsg = assistantWithCalls(frames, turnCalls);
                if (turnText.length() == 0) {
                    assistantMsg.putNull("content");
                } else {
                    assistantMsg.put("content", turnText.toString());
                }
                exchangeAcc.add(assistantMsg);
                messages.add(assistantMsg);
                String narrated = turnText.toString();
                turnText.setLength(0);
                turnPublished[0] = 0;
                turnHolding[0] = false;

                for (JsonNode call : turnCalls) {
                    checkStop(input);
                    String callId = call.path("id").asText("");
                    String name = call.path("function").path("name").asText("");
                    String rawArgs = call.path("function").path("arguments").asText("{}");
                    String resultText;
                    JsonNode step = null;
                    boolean isError = false;
                    boolean gateTool = ChatToolSchema.NO_EVIDENCE_TOOL.equals(name);
                    if (gateTool) {
                        // 闸门工具不留轨迹：「你好」下面挂一条「声明不用查」是噪音
                        log.info("模型声明这题不用查: reason={}",
                                frames.parse(rawArgs).path("reason").asText(""));
                        shared.gatePassed = true;
                        resultText = "Understood. Answer the user directly now.";
                    } else {
                        ChatToolSchema.CheckResult checked = schema.checkCall(toolsSchema,
                                name, rawArgs);
                        if (checked.refused()) {
                            step = checked.refuseStep();
                            isError = true;
                            resultText = checked.refuseMessage();
                        } else {
                            ToolExecutor.ToolResult result = tools.dispatch(input.toolContext,
                                    shared.sink, name, checked.args());
                            shared.gatePassed = true;
                            step = result.step();
                            isError = result.isError();
                            resultText = result.text();
                        }
                    }
                    ObjectNode toolMsg = ChatGuards.toolResultMessage(frames, callId,
                            resultText, gateTool ? null : isError);
                    exchangeAcc.add(toolMsg);
                    messages.add(toolMsg);
                    if (!gateTool) {
                        // 这一步发生在正文的哪个位置：单位是 UTF-16 码元——切分发生在
                        // 浏览器里，JS 的 length 数的就是它；字节或码点在中文和 emoji 上
                        // 都会切歪
                        if (step != null && step.isObject()) {
                            ((ObjectNode) step).put("at", answerAcc.length());
                        }
                        if (step != null) {
                            stepsAcc.add(step);
                        }
                        // 先保留已完成工具的历史，再把步骤发给可能立即按 Stop 的客户端：
                        // 保存停止的答案时，生成驱动会移除尚未完成的并行调用
                        push(out, new ProducerEvent.Context(new ArrayList<>(shared.sink.resolved),
                                new ArrayList<>(exchangeAcc), !stepsAcc.isEmpty()));
                        if (step != null) {
                            push(out, new ProducerEvent.Progress(
                                    new LiveRegistry.Frame("step", step.toString())));
                        }
                        // cite() 只增不改，图谱引用还能给检索登过的条目补引文（#968 后续）：
                        // 清单一变就再发，不只是变长
                        if (!publishedSources.equals(shared.sink.sources)) {
                            publishedSources = new ArrayList<>(shared.sink.sources);
                            push(out, new ProducerEvent.Progress(
                                    new LiveRegistry.Frame("sources", jsonArray(
                                            shared.sink.sources))));
                        }
                    }
                }
                narrated.length();
                continue;
            }

            // 这一回合没有工具调用：终稿候选，出循环走发布闸
            finished = true;
            break;
        }

        // ---- 预算边界：纯作答（无工具），验证过才发布 ----
        if (shared.answerRequested) {
            checkStop(input);
            List<JsonNode> sources = shared.sink.sources;
            List<JsonNode> resolved = shared.sink.resolved;
            ChatHistoryContext.Snapshot bounded = context.snapshot();
            ChatFinalizer.AnswerContext answerInput = new ChatFinalizer.AnswerContext(
                    input.question, bounded.turns(), null, bounded.exchange(), exchangeAcc,
                    sources, resolved);
            try {
                // 终稿增量不发布（有界缓冲在 finalizer 里）：先落库后发 delta，防闪烁
                String answer = finalizer.answerWithContext(input.client, frames, answerInput,
                        context, delta -> { });
                turnText.setLength(0);
                turnText.append(answer);
                turnCalls = new ArrayList<>();
                finished = true;
            } catch (Exception e) {
                String message = "Model could not produce a final answer: " + e.getMessage();
                String code = ChatFinalizer.modelFailureCode(e);
                if (code == null) {
                    code = ChatFinalizer.unpublishableCode(message);
                }
                if (code == null) {
                    code = "answer_failed";
                }
                push(out, new ProducerEvent.Outcome(null, new Failure(code, message)));
                return;
            }
        }
        if (!finished) {
            push(out, new ProducerEvent.Outcome(null, new Failure("answer_failed",
                    "LLM stream ended unexpectedly")));
            return;
        }
        // 检查终稿候选，不是更早的叙述。钩子是策略边界；这是发布与落库前的最后一道闸。
        List<JsonNode> sources = new ArrayList<>(shared.sink.sources);
        List<JsonNode> resolved = new ArrayList<>(shared.sink.resolved);
        String finalText = null;
        if (shared.finalizing) {
            String reason = ChatGuards.finalizationError(turnText.toString(),
                    !turnCalls.isEmpty(), input.question);
            if (reason != null) {
                String code = ChatFinalizer.unpublishableCode(reason);
                push(out, new ProducerEvent.Outcome(null,
                        new Failure(code == null ? "answer_failed" : code, reason)));
                return;
            }
            answerAcc.append(turnText);
            finalText = turnText.toString();
        } else {
            // 纯文字的最后一回合还扣着东西：写成正文的工具调用，钩子已退回过一次，这是
            // 第二次——报错不存（#937）；不是（围栏里的示例），补发
            if (turnPublished[0] < turnText.length()) {
                if (ChatGuards.toolCallText(turnText.toString(), input.question)) {
                    push(out, new ProducerEvent.Outcome(null,
                            new Failure("answer_tool_text", ChatGuards.CONTROL_TEXT)));
                    return;
                }
                String rest = turnText.substring(turnPublished[0]);
                answerAcc.append(rest);
                push(out, new ProducerEvent.Progress(frames.deltaEvent(rest)));
            }
            if (turnText.toString().trim().isEmpty()) {
                push(out, new ProducerEvent.Outcome(null,
                        new Failure("answer_empty", "Model returned an empty answer")));
                return;
            }
        }
        // 一个工具都没查的一轮照抄上一条回答的 [n]：沿用那些号在上一条里被引用过的来源，
        // 角标才点得开（#943）；查过东西的一轮只认自己查到的
        if (!shared.finalizing && stepsAcc.isEmpty() && sources.isEmpty()) {
            sources = carriedSources(answerAcc.toString(),
                    input.previousAnswer == null ? "" : input.previousAnswer,
                    input.lastSources);
        }
        TurnRecord record = new TurnRecord(false, toArray(stepsAcc), toArray(sources),
                toArray(resolved), toArray(exchangeAcc));
        push(out, new ProducerEvent.Outcome(
                new Answer(answerAcc.toString(), record, finalText), null));
    }

    /** 一次带工具的流式模型回合：增量边到边按行发布（finalizing 时只入有界缓冲）。 */
    private AssistantTurn streamTurn(ChatClient client, List<JsonNode> messages, JsonNode tools,
                                     JsonNode toolChoice, Shared shared, StringBuilder turnText,
                                     int[] published, boolean[] holding, StringBuilder answerAcc,
                                     java.util.concurrent.BlockingQueue<ProducerEvent> out)
            throws IOException, InterruptedException {
        return client.chatToolsStream(messages, tools, toolChoice, delta -> {
            if (shared.finalizing) {
                // 终稿只进有界缓冲：验证过才发布，防「先闪一段又收回去」
                if (turnText.length() + delta.length() > ChatGuards.MAX_FINAL_ANSWER_BYTES) {
                    throw new AnswerTooLongException();
                }
                turnText.append(delta);
                return;
            }
            turnText.append(delta);
            // Tool-round narration stays live, line by line; a line that is or may become a
            // tool call written as text is held (#937)
            int upto = ChatGuards.publishable(turnText.toString(), published[0], holding);
            if (upto > published[0]) {
                String fresh = turnText.substring(published[0], upto);
                published[0] = upto;
                answerAcc.append(fresh);
                push(out, new ProducerEvent.Progress(frames.deltaEvent(fresh)));
            }
        });
    }

    // ---- 事件与共享工具方法 ------------------------------------------------------------

    /**
     * 语义层拼装（chat.rs:819-876）：基础提示 + 记忆段（可写）+ 数据段（挂了源才给）
     * + 数据描述 + 约定 + 挑出来的口径。描述说的是 schema 里有的（粒度、单位、码值、
     * 时间轴），约定说的是 schema 里没有的（哪些行算数、哪列才是那个数）——后者是问数
     * 从 2/18 到 14/18 的那一半（#520），每次都在，不靠检索碰运气。
     */
    String buildSystemPrompt(RunInput input, List<String> dsNames,
                             List<tech.seexun.jknow.store.entity.ConceptMapping> mappings) {
        StringBuilder out = new StringBuilder(ChatToolSchema.SYSTEM_PROMPT);
        if (input.toolContext.canWrite && ChatToolSchema.REMEMBER_ENABLED) {
            out.append('\n').append(ChatToolSchema.MEMORY_PROMPT);
        }
        if (!dsNames.isEmpty()) {
            out.append("\nData: query_data runs read-only SQL (in each source's own dialect) "
                    + "against: ").append(String.join(", ", dsNames))
                    .append(". For questions about numbers/metrics, search for the source's "
                            + "schema document first, then query. State units and the time "
                            + "range you used in the answer.");
            String description = input.kb == null ? null : input.kb.dataDescription();
            if (description != null && !description.trim().isEmpty()) {
                out.append("\nAbout the data (written from the schema; states only what the "
                        + "schema says):\n").append(description);
            }
            String conventions = input.kb == null ? null : input.kb.dataConventions();
            if (conventions != null && !conventions.trim().isEmpty()) {
                out.append("\nConventions stated by the owner of this base — apply them in "
                        + "every query and every answer (filters, units, which column is the "
                        + "figure):\n").append(conventions);
            }
            if (!mappings.isEmpty()) {
                out.append("\nSemantic layer (confirmed definitions — use these instead of "
                        + "guessing from schema):");
                // 字段是列，只挑问数用得上的那几样铺开：sql/expr 是「怎么算」，unit 是
                // 答里必须带的量纲，summary 是给模型的一句人话
                for (tech.seexun.jknow.store.entity.ConceptMapping m : mappings) {
                    String how = m.sql() != null ? m.sql()
                            : m.expr() != null ? m.expr()
                            : m.tableName() != null ? m.tableName() : "-";
                    String unit = m.unit() == null || m.unit().isEmpty() ? ""
                            : " [" + m.unit() + "]";
                    String note = m.summary() == null ? "" : " — " + m.summary();
                    out.append("\n- ").append(m.conceptName()).append(" (").append(m.source())
                            .append(")").append(unit).append(": ").append(how).append(note);
                }
            }
        }
        return out.toString();
    }

    /** 挑口径/检索共用的嵌入端：同一句话在这一轮里只嵌一次（ToolSink.embeddings 当缓存）。 */
    tech.seexun.jknow.store.NameVectorsService.Embedder cachedEmbedder(ToolSink sink,
                                                                        RunInput input) {
        return texts -> {
            // 命中缓存的整批直取；否则一次嵌入、逐条入缓存（挑口径与检索共用一个向量）
            java.util.List<float[]> out = new ArrayList<>(texts.size());
            java.util.List<Integer> missing = new ArrayList<>();
            java.util.List<String> missingTexts = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] hit = sink.embeddings.get(texts.get(i));
                if (hit != null) {
                    out.add(hit);
                } else {
                    out.add(null);
                    missing.add(i);
                    missingTexts.add(texts.get(i));
                }
            }
            if (!missing.isEmpty()) {
                tech.seexun.jknow.store.entity.LlmSettingsRow row =
                        settings.get(input.kb.workspaceId());
                tech.seexun.jknow.llm.EmbedClient client = tech.seexun.jknow.web.knowledge.llm.LlmSupport
                        .embedClient(row);
                if (client == null) {
                    throw new IllegalStateException("no embedding model");
                }
                java.util.concurrent.Semaphore permit = row.embedBaseUrl() == null ? null
                        : llm.acquirePermit(row.embedBaseUrl(), row.embedModel());
                java.util.List<float[]> got;
                try {
                    got = client.embed(missingTexts);
                } finally {
                    if (permit != null) {
                        permit.release();
                    }
                }
                for (int i = 0; i < missing.size() && i < got.size(); i++) {
                    float[] v = got.get(i);
                    out.set(missing.get(i), v);
                    if (v != null) {
                        sink.embeddings.put(missingTexts.get(i), v);
                    }
                }
            }
            return out;
        };
    }

    private String embedModelName(RunInput input) {
        tech.seexun.jknow.store.entity.LlmSettingsRow row = settings.get(input.kb.workspaceId());
        return row == null ? null : row.embedModel();
    }

    static void push(java.util.concurrent.BlockingQueue<ProducerEvent> out, ProducerEvent e) {
        try {
            out.put(e);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StoppedException();
        }
    }

    void checkStop(RunInput input) {
        if (input.cancellation != null && input.cancellation.isCancelled()) {
            throw new StoppedException();
        }
    }

    ObjectNode systemMessage(String content) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("role", "system");
        out.put("content", content);
        return out;
    }

    ObjectNode userMessage(String content) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("role", "user");
        out.put("content", content);
        return out;
    }

    ObjectNode assistantWithCalls(ChatFrames f, List<JsonNode> calls) {
        ObjectNode assistant = f.mapper.createObjectNode();
        assistant.put("role", "assistant");
        assistant.putNull("content");
        ArrayNode arr = assistant.putArray("tool_calls");
        calls.forEach(arr::add);
        return assistant;
    }

    JsonNode toArray(List<JsonNode> list) {
        ArrayNode arr = frames.mapper.createArrayNode();
        list.forEach(arr::add);
        return arr;
    }

    String jsonArray(List<JsonNode> list) {
        return toArray(list).toString();
    }

    /**
     * 不查东西的一轮沿用上一条回答的来源（#943）：只在这一轮引用的号<b>全都</b>在上一条
     * 回答里引用过时才给，取上一条存下的来源里被引用到的那几条，号不变；否则一条不给
     * ——不去猜这些号原本属于哪一轮。
     */
    public static List<JsonNode> carriedSources(String answer, String previousAnswer,
                                                List<JsonNode> lastSources) {
        Set<Long> cited = citedNumbers(answer);
        if (cited.isEmpty() || !citedNumbers(previousAnswer).containsAll(cited)) {
            return new ArrayList<>();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : lastSources) {
            if (cited.contains(s.path("n").asLong(0))) {
                out.add(s);
            }
        }
        return out;
    }

    /** 正文里的引用号，与界面画角标的 citeRe 同一个形状：[1]、[1][2]、[1, 2]、[1，2]。 */
    public static Set<Long> citedNumbers(String text) {
        Set<Long> out = new TreeSet<>();
        int at = 0;
        while ((at = text.indexOf('[', at)) >= 0) {
            int close = text.indexOf(']', at + 1);
            if (close < 0) {
                break;
            }
            String inner = text.substring(at + 1, close);
            String[] parts = inner.split("[,，]", -1);
            List<Long> nums = new ArrayList<>();
            boolean ok = true;
            for (int k = 0; k < parts.length; k++) {
                String digits = parts[k];
                if (k > 0) {
                    digits = digits.stripLeading();
                }
                if (k + 1 < parts.length) {
                    digits = digits.stripTrailing();
                }
                if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    ok = false;
                    break;
                }
                try {
                    nums.add(Long.parseLong(digits));
                } catch (NumberFormatException e) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                for (Long n : nums) {
                    if (n > 0) {
                        out.add(n);
                    }
                }
            }
            at = close;
        }
        return out;
    }

    // ---- legacy RAG 降级 -----------------------------------------------------------------

    /** 降级路径：端点不支持工具调用时的一次性 RAG 注入（chat.rs legacy_rag）。 */
    void legacyRag(RunInput input, ChatHistoryContext context,
                   java.util.concurrent.BlockingQueue<ProducerEvent> out) throws Exception {
        // 兜底这一路一轮只走一次，问题自己嵌：它没有这一轮的 sink（#971 的缓存在那上面）
        List<ChunkView> chunks;
        try {
            chunks = search.search(input.toolContext.kbId, input.question, 8, null, null);
        } catch (Exception e) {
            log.warn("fallback document retrieval failed: {}", e.toString());
            push(out, new ProducerEvent.Outcome(null,
                    new Failure("search_failed", "Could not search the documents.")));
            return;
        }
        List<JsonNode> legacySources = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            legacySources.add(legacySourceJson(i + 1, chunks.get(i)));
        }
        push(out, new ProducerEvent.Progress(
                new LiveRegistry.Frame("sources", jsonArray(legacySources))));
        StringBuilder answerAcc = new StringBuilder();
        while (true) {
            checkStop(input);
            ChatHistoryContext.Snapshot snapshot = context.snapshot();
            List<JsonNode> lmsgs = new ArrayList<>();
            lmsgs.add(systemMessage(legacySystemPrompt(chunks)));
            for (String[] turn : snapshot.turns()) {
                ObjectNode m = frames.mapper.createObjectNode();
                m.put("role", turn[0]);
                m.put("content", turn[1]);
                lmsgs.add(m);
            }
            lmsgs.add(userMessage(input.question));
            try {
                input.client.chatStreamRaw(lmsgs, delta -> {
                    answerAcc.append(delta);
                    push(out, new ProducerEvent.Progress(frames.deltaEvent(delta)));
                });
            } catch (Exception e) {
                if (context.recover(input.client, e)) {
                    continue;
                }
                String code = ChatFinalizer.modelFailureCode(e);
                push(out, new ProducerEvent.Outcome(null,
                        new Failure(code == null ? "answer_failed" : code,
                                e.getMessage() == null ? e.toString() : e.getMessage())));
                return;
            }
            break;
        }
        if (answerAcc.toString().trim().isEmpty()) {
            push(out, new ProducerEvent.Outcome(null,
                    new Failure("answer_empty", "Model returned an empty answer")));
            return;
        }
        TurnRecord record = new TurnRecord(false, toArray(List.of()),
                toArray(legacySources), toArray(List.of()), toArray(List.of()));
        push(out, new ProducerEvent.Outcome(
                new Answer(answerAcc.toString(), record, null), null));
    }

    JsonNode legacySourceJson(int n, ChunkView c) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("n", n);
        out.put("chunk_id", c.id().toString());
        out.put("document_id", c.documentId().toString());
        out.put("filename", c.filename());
        out.put("excerpt", ToolExecutor.truncate(c.text(), 160));
        return out;
    }

    /** 降级路径的系统提示（tool-calling 不可用时的一次性注入）。 */
    static String legacySystemPrompt(List<ChunkView> chunks) {
        if (chunks.isEmpty()) {
            return "You are an enterprise knowledge base assistant. No relevant sources were "
                    + "retrieved for this question. Tell the user the knowledge base lacks "
                    + "material on this topic, answer cautiously from general knowledge, and "
                    + "clearly separate sourced statements from speculation. Always respond in "
                    + "the same language as the user's question.";
        }
        StringBuilder prompt = new StringBuilder(
                "You are an enterprise knowledge base assistant. Answer strictly based on the "
                        + "numbered sources below. When a source supports a statement, append "
                        + "its citation number, e.g. [1] or [2], at the end of the sentence. If "
                        + "the sources are insufficient, say so explicitly — never fabricate. "
                        + "Always respond in the same language as the user's question."
                        + "\n\n### Sources\n");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkView c = chunks.get(i);
            prompt.append("\n[").append(i + 1).append("] \"").append(c.filename())
                    .append("\" section ").append(c.seq() + 1).append(":\n").append(c.text())
                    .append('\n');
        }
        return prompt.toString();
    }
}
