/* 一轮对话的渲染（对照旧仓 Chat.tsx 的 TurnView）：用户气泡 / 助手正文+轨迹。
   进行中的那一次与落库的历史共用这一个组件（liveAnswer.ts 头注的同一条决定）。 */
import { type PropType, computed, defineComponent } from "vue";
import RotateCcwIcon from "~icons/lucide/rotate-ccw";
import type { Turn } from "@/domain/liveAnswer";
import { answeredWithoutSources, citedSources } from "@/domain/liveAnswer";
import { S } from "@/i18n";
import type { Source } from "@/api/knowledge/types";
import { UMarkdown, segments } from "./markdown";
import { StepRow, Thinking } from "./steps";
import { USourceList, provideChatSources } from "./citations";
import { UCopyButton } from "./chatCopy";
import remend from "remend";

export const UTurn = defineComponent({
  name: "UTurn",
  props: {
    turn: { type: Object as PropType<Turn>, required: true },
    /** 这是正在流的那一轮：无气泡化、引文落款与动作都等说完了才出 */
    live: { type: Boolean, default: false },
    /** 出错的最后一轮、它的问题存着 id 时才有：重答那一问（#936） */
    showRetry: { type: Boolean, default: false },
    kbId: { type: String, required: true }
  },
  emits: ["retry"],
  setup(props, { emit }) {
    return () => {
      const turn = props.turn;
      if (turn.role === "user") {
        return (
          <div class="flex justify-end">
            {/* **对话按 title 那一档读**（16/24，不是正文的 14/22）。这一页是这个
                产品里唯一一块拿来"读"的长文本，其余都是表格、清单、面板里的字段，
                14px 在那些地方是对的，成段读就偏小了（旧仓注释原话） */}
            <div class="bubble-user max-w-[85%] rounded-card px-4 py-2 text-title whitespace-pre-wrap text-ink">
              {turn.content}
            </div>
          </div>
        );
      }
      return (
        <AssistantTurn
          turn={turn}
          live={props.live}
          showRetry={props.showRetry}
          kbId={props.kbId}
          onRetry={() => emit("retry")}
        />
      );
    };
  }
});

/** 助手分支。正文与轨迹按发生顺序穿在一起，引用与动作等说完才出。 */
const AssistantTurn = defineComponent({
  name: "UAssistantTurn",
  props: {
    turn: { type: Object as PropType<Turn>, required: true },
    live: { type: Boolean, default: false },
    showRetry: { type: Boolean, default: false },
    kbId: { type: String, required: true }
  },
  emits: ["retry"],
  setup(props, { emit }) {
    /* provide/inject 而不是 prop：来源一条条到，别让所有正文段跟着失效 */
    const sources = computed(() => (props.turn.sources ?? []) as Source[]);
    provideChatSources(sources);
    return () => {
      const turn = props.turn;
      const live = props.live;
      const thinking = live && !turn.content && !turn.error;
      const lastStep = turn.steps?.[turn.steps.length - 1];
      const cited = citedSources(turn);
      return (
        <div class="max-w-[95%]">
          {/* agent 回复无气泡：正文直接落在画布上（用户消息保留气泡以区分角色） */}
          <div class="py-1 text-base/relaxed text-ink ">
            {/* **轨迹按发生的顺序穿在正文里。** 模型是边说边查的：说一句、调一次、
                再说一句。把调用整块提到最前面，读起来就成了「先查七次再一口气
                说完」——那不是它做的事（旧仓注释原话） */}
            {segments(turn).map((seg, i) =>
              seg.kind === "steps" ? (
                <div
                  key={i}
                  class="my-3 space-y-1 border-l border-line-strong pl-3"
                >
                  {seg.steps.map((s, j) => (
                    <StepRow key={j} step={s} kbId={props.kbId} />
                  ))}
                </div>
              ) : (
                /* react-markdown 承载渲染（皮肤全归 chat-prose 设计系统），
                   流式中经 remend 修补未闭合语法（粗体/围栏/链接），
                   rehype-highlight 做代码高亮——成熟件组装，观感自持。
                   **只有还在长的那一段需要 remend**：先前的段落已经收尾了 */
                <UMarkdown
                  key={i}
                  text={live && seg.last ? remend(seg.text) : seg.text}
                />
              )
            )}
            {thinking && <Thinking step={lastStep} />}
            {turn.stopped && (
              <div class="text-sub text-ink-2">{S.ask.stopped}</div>
            )}
            {turn.error && <div class="text-danger">{turn.error}</div>}
          </div>
          {/* **引用等答案说完再出。** sources 是随检索一次次增量发来的，跟着渲染的话，
              一份还在生长的清单就挂在一段还没写完的话下面，一边长一边把正文往上推。
              它是答案的落款，不是过程的一部分——过程已经由上面的轨迹交代了（旧仓注释原话） */}
          {!live && cited.length > 0 && <USourceList sources={cited} />}
          {/* 没有引用时，引用那一格换成一句「未引用任何来源」（#547）：
              缺席没人读得出来，得写出来。判据见 answeredWithoutSources */}
          {answeredWithoutSources(turn, !!live) && (
            <div class="mt-2 text-sub text-ink-2">{S.ask.noSources}</div>
          )}
          {/* 回答上的动作（#936），同样等说完了才出。复制的是存下的 Markdown，
              角标 `[n]` 照写——表格、代码块贴到别处还是原样。答到一半失败的那一轮
              多一个重试：问题已经在库里，重答不再存一遍（旧仓注释原话） */}
          {!live && (turn.content || props.showRetry) && (
            <div class="mt-2 flex items-center gap-2">
              {turn.content && (
                <UCopyButton
                  label={S.ask.copyAnswer}
                  text={() => turn.content ?? ""}
                />
              )}
              {props.showRetry && (
                <el-button size="small" onClick={() => emit("retry")}>
                  <RotateCcwIcon width={12} height={12} class="mr-1" />
                  {S.ask.retryQuestion}
                </el-button>
              )}
            </div>
          )}
        </div>
      );
    };
  }
});
