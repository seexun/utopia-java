/* 轨迹行与思考指示（对照旧仓 Chat.tsx 的 StepRow/stepIcon/Thinking + PendingFacts
   的 NodCard）。话按读者的语言说（domain/steps Ⅰ类，#942）；没做成的那一步用
   危险色。问数那一步可以展开它跑的 SQL（#936）：答案里的数是这条语句算出来的，
   读的人该能自己核，而不只看到一句「查了一下」。 */
import { type PropType, computed, defineComponent, ref } from "vue";
import { useQuery } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import BookOpenIcon from "~icons/lucide/book-open";
import WaypointsIcon from "~icons/lucide/waypoints";
import HistoryIcon from "~icons/lucide/history";
import GitCompareIcon from "~icons/lucide/git-compare-arrows";
import DatabaseIcon from "~icons/lucide/database";
import WrenchIcon from "~icons/lucide/wrench";
import { pendingForChunk, decidePending } from "@/api/knowledge/review";
import type { ChatStep } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { stepDetail, stepLabel } from "@/domain/steps";
import { toast } from "@/domain/toast";
import { useKbRole } from "@/composables/useKbRole";
import { queryClient } from "@/store/knowledge/query";
import { LinkButton } from "@/components/knowledge/kit";
import { PendingFactRow } from "@/views/jknow/knowledge/review/rows";

function stepIcon(kind: ChatStep["kind"]) {
  if (kind === "search") return <SearchIcon width={11} height={11} />;
  if (kind === "docs") return <BookOpenIcon width={11} height={11} />;
  if (kind === "entity" || kind === "neighbors" || kind === "path")
    return <WaypointsIcon width={11} height={11} />;
  if (kind === "facts" || kind === "timeline")
    return <HistoryIcon width={11} height={11} />;
  // facts 读世界轴、changes 读认知轴，两个图谱工具给不同的图标——
  // 用户看步骤条时该看得出问的是哪根轴（旧仓注释原话）
  if (kind === "changes") return <GitCompareIcon width={11} height={11} />;
  if (kind === "query") return <DatabaseIcon width={11} height={11} />;
  return <WrenchIcon width={11} height={11} />;
}

/** 轨迹上的一行。话按读者的语言说（`stepDetail`，#942）；没做成的那一步用危险色。
 *  SQL 展开是**这一行自己的事**：open 状态收在 setup 内部（旧仓 StepRow 同款形态），
 *  不提升成 prop/emit——唯一调用点在 turn.tsx，提升过去只会两头都忘了接（I-1 教训） */
const StepRow = defineComponent({
  name: "UStepRow",
  props: {
    step: { type: Object as PropType<ChatStep>, required: true },
    kbId: { type: String, required: true }
  },
  setup(props) {
    const sqlOpen = ref(false);
    return () => {
      const step = props.step;
      return (
        <div>
          <div class="flex items-center gap-2 text-sub">
            <span class="text-ink-2">{stepIcon(step.kind)}</span>
            <span class="text-ink-2 sle">{stepLabel(step)}</span>
            <span
              class={[
                "shrink-0",
                step.status === "failed" ? "text-danger" : "text-ink-2"
              ]}
            >
              · {stepDetail(step)}
            </span>
            {step.sql && (
              <LinkButton
                class="shrink-0"
                aria-expanded={sqlOpen.value}
                onClick={() => (sqlOpen.value = !sqlOpen.value)}
              >
                {S.ask.step.sql}
              </LinkButton>
            )}
          </div>
          {sqlOpen.value && step.sql && (
            /* reskin-b3：SQL 原文从 fill 凹底改白底描边井——轨迹段已是 bg-fill
               软卡，同一档底色会糊在一起 */
            <pre class="mt-1 rounded-card border border-line bg-card px-3 py-2 font-mono text-sub text-ink-2 whitespace-pre-wrap wrap-break-word">
              {step.sql}
            </pre>
          )}
          {/* remember 那一步后面跟着确认卡（0015）：这句话抽出的事实先等人点头。
              抽取是异步的，卡片在任务完成时才长出来；回放时按同一个 chunk 重画（旧仓注释原话） */}
          {step.chunk_id && (
            <UNodCard kbId={props.kbId} chunkId={step.chunk_id} />
          )}
        </div>
      );
    };
  }
});

/** remember 的确认卡（对照旧仓 PendingFacts.tsx NodCard）：按 chunk 取这轮记忆
 *  抽出的待确认项。点头是写图的动作，editor 起步（useKbRole.canManage 口径） */
export const UNodCard = defineComponent({
  name: "UNodCard",
  props: {
    kbId: { type: String, required: true },
    chunkId: { type: String, required: true }
  },
  setup(props) {
    const { canManage } = useKbRole();
    const q = useQuery({
      queryKey: computed(() => ["pending", props.kbId, props.chunkId]),
      queryFn: () => pendingForChunk(props.kbId, props.chunkId)
    });
    const busyId = ref<string | null>(null);
    async function decide(id: string, action: "confirm" | "reject") {
      busyId.value = id;
      try {
        await decidePending(props.kbId, id, action);
      } catch (e) {
        toast.error(e instanceof Error ? e.message : String(e));
      } finally {
        busyId.value = null;
        // 确认卡在对话里也挂着——pending 与 review 双键失效（旧仓 onSettled 同款）
        queryClient.invalidateQueries({ queryKey: ["pending", props.kbId] });
        queryClient.invalidateQueries({ queryKey: ["review", props.kbId] });
      }
    }
    const items = computed(() => q.data.value?.items ?? []);
    return () =>
      items.value.length === 0 ? null : (
        <div class="my-2 space-y-2">
          <div class="text-sub text-ink-2">
            {S.review.nodCardTitle(items.value.length)}
          </div>
          {items.value.map(f => (
            <PendingFactRow
              key={f.id}
              fact={f}
              busy={busyId.value === f.id}
              canDecide={canManage.value}
              onConfirm={() => decide(f.id, "confirm")}
              onReject={() => decide(f.id, "reject")}
            />
          ))}
        </div>
      );
  }
});

/** 思考指示：**正在做的那句话自己在发光**。
 *
 * 现在没有零件：亮带横扫过那行字。它既是「还在动」，也是「动的是这句话」。
 * 还没有步骤可说的时候就扫「Thinking…」——那也是一句话，不是一个图标（旧仓注释原话） */
export const Thinking = defineComponent({
  name: "UThinking",
  props: { step: { type: Object as PropType<ChatStep>, default: undefined } },
  setup(props) {
    return () => (
      <span class="thinking sle text-sub">
        {props.step
          ? `${stepLabel(props.step)} · ${stepDetail(props.step)}`
          : S.ask.thinking}
      </span>
    );
  }
});

export { StepRow };
