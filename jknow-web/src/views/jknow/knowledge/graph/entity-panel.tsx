/* 实体侧栏（对照旧仓 pages/Graph.tsx 的 EntityPanel 段 + EntityHistory.tsx，W1 读面；
   W3/任务 9 补画布联动：事实行指着 = 画布那条边亮、点 = 钉住 + 镜头移过去、
   宾语词指着 = 那个节点亮；Derived/Blocked 展开取证明链（walkProofSteps 树）；
   幽灵边点进来经 intent 停在「推出来的」那一档、展开那一行。编辑弹窗属任务 6。 */
import { computed, defineComponent, ref, watch, type PropType } from "vue";
import { RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import ArrowRightIcon from "~icons/lucide/arrow-right";
import ArrowLeftIcon from "~icons/lucide/arrow-left";
import ChevronRightIcon from "~icons/lucide/chevron-right";
import ExternalLinkIcon from "~icons/lucide/external-link";
import FileTextIcon from "~icons/lucide/file-text";
import PencilLineIcon from "~icons/lucide/pencil-line";
import Undo2Icon from "~icons/lucide/undo-2";
import MergeIcon from "~icons/lucide/merge";
import TagIcon from "~icons/lucide/tag";
import {
  blockedProof,
  derivedProof,
  entityDetail,
  entityHistory,
  factEvidence
} from "@/api/knowledge/graph";
import type {
  BlockedDerivation,
  DerivedFact,
  EntityFact,
  GraphNode,
  NameView,
  ProofStep
} from "@/api/knowledge/types";
import { S } from "@/i18n";
import { fmtObjectValue } from "@/domain/objectValue";
import { fmtTime } from "@/domain/time";
import { originHint, originLabel } from "@/domain/origin";
import { predicateSentence } from "@/domain/predicateText";
import { localDateTime } from "@/domain/ui";
import { proofIndentClass } from "./proof";
import { UChip, GroupLabel, UPager, UStatus } from "@/components/knowledge/kit";

/** 句子自己没写时间，但它所在那一节的文本说了自己是哪天的（0064） */
export function asOf(f: EntityFact): string {
  if (f.temporal === "eternal" || f.valid_from || f.valid_to || !f.holds_from)
    return "";
  const date = fmtTime(f.holds_from, "day");
  return date ? S.graph.asOf(date, f.attested_by ?? null) : "";
}

export function fmtInterval(f: EntityFact): string {
  // 时态 = 永恒：不画区间
  if (f.temporal === "eternal") return "";
  // 时态 = 事件：单点。from ~ from 读起来像区间错框了
  if (f.temporal === "event") {
    const moment = fmtTime(f.valid_from, f.valid_from_precision);
    if (moment) return moment;
    return fmtTime(f.valid_to, f.valid_to_precision) ?? "";
  }
  const from = fmtTime(f.valid_from, f.valid_from_precision);
  const to = fmtTime(f.valid_to, f.valid_to_precision);
  // 「结束了但不知哪天」绝不能显示成「至今」
  const endedUnknown = !f.valid_to && f.valid_to_precision === "unknown";
  if (!from && !to && !endedUnknown) return "";
  const end = to ?? (endedUnknown ? S.graph.endedUnknown : S.graph.ongoing);
  return from ? `${from} ~ ${end}` : `~ ${end}`;
}

/* ---------- 记录时间轴（EntityHistory） ---------- */

const PER = 20;

/** 事件类型 → 图标组件与色调（语义色只给"被推翻"，其余保持中性） */
const KIND_ICONS: Record<string, [typeof FileTextIcon, string]> = {
  asserted: [FileTextIcon, "text-ink-2"],
  corrected: [PencilLineIcon, "text-warning"],
  rejected: [Undo2Icon, "text-danger"],
  merged: [MergeIcon, "text-ink-2"],
  merged_in: [MergeIcon, "text-ink-2"],
  merged_away: [MergeIcon, "text-ink-2"],
  merge_reverted: [Undo2Icon, "text-warning"],
  retyped: [TagIcon, "text-ink-2"],
  retype_reverted: [Undo2Icon, "text-warning"]
};

const MERGE_KINDS = new Set(["merged_in", "merged_away", "merge_reverted"]);

/* 两个函数故意不一样（旧仓注释）：ymd 给记录时刻（按看的人的时区），ym 给世界时间 */
const ymd = (iso: string) => {
  const d = new Date(iso);
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
};
const ym = (iso: string | null) => (iso ? iso.slice(0, 7) : null);

/** 宾语：实体名优先，其次字面值的摘要/值 */
function objectText(e: {
  other_name: string | null;
  object_value: Record<string, unknown> | null;
}): string {
  if (e.other_name) return e.other_name;
  const v = e.object_value as { summary?: unknown; value?: unknown } | null;
  const raw = v?.summary ?? v?.value;
  return raw === undefined || raw === null ? "—" : String(raw);
}

function intervalNote(e: Record<string, unknown>): string | null {
  if (e.kind === "corrected" && e.valid_to) {
    return S.graph.historyClosedAt(ym(e.valid_to as string)!);
  }
  const from = ym((e.valid_from as string) ?? null);
  if (!from) return null;
  return e.valid_to
    ? `${from} → ${ym(e.valid_to as string)}`
    : `${S.graph.historyFrom(from)} · ${S.graph.historyOngoing}`;
}

const HistoryEventRow = defineComponent({
  name: "HistoryEventRow",
  props: {
    e: { type: Object as PropType<Record<string, unknown>>, required: true },
    kbId: { type: String, required: true }
  },
  setup(props) {
    const e = props.e;
    const kind = String(e.kind);
    const [Icon, tone] = KIND_ICONS[kind] ?? [FileTextIcon, "text-ink-2"];
    const note = intervalNote(e);
    return () => (
      <div class="flex gap-2 p-2 ">
        <Icon width="12" height="12" class={["mt-1 shrink-0", tone]} />
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-baseline gap-2">
            <span class="text-sub font-medium text-ink-2">
              {S.graph.historyKind[kind as keyof typeof S.graph.historyKind] ??
                kind}
            </span>
            {note && (
              <span class="tabular-nums text-aux text-ink-2">{note}</span>
            )}
          </div>
          {/* 改类事件没有谓词也没有宾语，正文换成类的两端 */}
          {kind === "retyped" || kind === "retype_reverted" ? (
            <div class="mt-1 truncate text-body text-ink-2">
              <span class="text-sub text-ink-2">
                {(e.from_type_label as string) ?? S.graph.untyped} →{" "}
              </span>
              <span class="text-ink">{e.to_type_label}</span>
            </div>
          ) : MERGE_KINDS.has(kind) ? (
            /* 合并事件的正文只有对方。**箭头指方向、写在前缀**（M-4，旧仓逐字）：
               并进来是「←」，并出去是「→」，与事实行的读法一致 */
            <div class="mt-1 truncate text-body text-ink-2">
              <span class="text-sub text-ink-2">
                {kind === "merged_away" ? "→ " : "← "}
              </span>
              <span class="text-ink">
                {(e.other_name as string) ?? S.graph.historyGoneEntity}
              </span>
            </div>
          ) : (
            /* 事实行一句话带方向（W3 收口，旧仓逐字）：入边「←」写在谓词前、
               出边「→」缀在谓词后——读法与合并事件一致；说不出关系时斜体降级 */
            <div class="mt-1 truncate text-body text-ink-2">
              <span class="text-sub text-ink-2">
                {e.direction === "in" ? "← " : ""}
                <span
                  class={
                    e.predicate_label === null ? "italic text-ink-2" : undefined
                  }
                >
                  {e.predicate_label
                    ? predicateSentence(e.predicate_label as string)
                    : S.graph.unknownPredicate}
                </span>
                {e.direction === "in" ? "" : " →"}
              </span>{" "}
              <span class="text-ink">{objectText(e as never)}</span>
            </div>
          )}
          {/* 谁在什么时候做的（M-1/M-2，旧仓逐字）：人名，或「引擎」；
              出处文档跟着归因走——「从哪份文档得知」是这条轴要答的问题的一半 */}
          <div class="mt-1 flex items-center gap-2 text-aux text-ink-2">
            <span class="tabular-nums">{ymd(e.at as string)}</span>
            <span>·</span>
            <span>{(e.actor_name as string) ?? S.graph.historyEngine}</span>
            {e.filename && e.document_id && (
              <>
                <span>·</span>
                <RouterLink
                  to={{
                    path: `/kb/${props.kbId}/doc/${e.document_id}`,
                    query: {}
                  }}
                  class="hover-ink truncate"
                  title={(e.quote as string) ?? (e.filename as string)}
                >
                  {e.filename}
                </RouterLink>
              </>
            )}
          </div>
        </div>
      </div>
    );
  }
});

const EntityHistoryPanel = defineComponent({
  name: "EntityHistoryPanel",
  props: {
    kbId: { type: String, required: true },
    entityId: { type: String, required: true }
  },
  setup(props) {
    const page = ref(0);
    const q = useQuery({
      queryKey: computed(() => [
        "entityHistory",
        props.kbId,
        props.entityId,
        page.value
      ]),
      queryFn: () => entityHistory(props.kbId, props.entityId, page.value, PER)
    });
    const total = computed(() => q.data.value?.total ?? 0);
    return () => (
      <div>
        {/* 三态（M-3，旧仓逐字）：取数给加载行；只有「一条都没有」才是空——
            记录轴上首次断言本身就是一次事件；有内容先说一句这条轴答什么 */}
        {q.isPending.value && (
          <p class="p-2 text-body text-ink-2">{S.nav.loading}</p>
        )}
        {!q.isPending.value && total.value === 0 && (
          <p class="p-2 text-sub text-ink-2">{S.graph.historyEmpty}</p>
        )}
        {total.value > 0 && (
          <p class="px-2 pb-2 text-aux text-ink-2">{S.graph.historyHint}</p>
        )}
        {q.data.value && total.value > 0 && (
          <div class="divide-y divide-line">
            {q.data.value.events.map((e, i) => (
              <HistoryEventRow
                key={String(e.fact_id ?? i)}
                e={e as never}
                kbId={props.kbId}
              />
            ))}
          </div>
        )}
        <UPager
          total={total.value}
          pageSize={PER}
          page={page.value}
          onPage={(p: number) => (page.value = p)}
        />
      </div>
    );
  }
});

/* ---------- 证据展开区 ---------- */

const EvidenceList = defineComponent({
  name: "EvidenceList",
  props: {
    kbId: { type: String, required: true },
    fact: { type: Object as PropType<EntityFact>, required: true }
  },
  setup(props) {
    const evidence = useQuery({
      queryKey: computed(() => ["evidence", props.fact.id]),
      queryFn: () => factEvidence(props.kbId, props.fact.id)
    });
    return () => (
      /* 多段就滚，不把整个面板顶长 */
      <div class="max-h-64 space-y-2 overflow-y-auto overscroll-contain">
        {(evidence.data.value?.evidence ?? []).map(ev => (
          /* 一段原文一张卡，卡本身不是链接；跳转收进末尾那个小角标 */
          <div
            key={ev.chunk_id}
            class="rounded-ctl bg-card p-2  text-sub text-ink-2"
          >
            {ev.proposed_predicate &&
              ev.proposed_predicate !== props.fact.predicate_key && (
                <div class="mb-1 text-aux text-ink-2">
                  {S.graph.proposedPredicate(ev.proposed_predicate)}
                </div>
              )}
            {/* 不截断：从前 line-clamp-2，「看原文」看到的是前两行 */}
            <div class="whitespace-pre-wrap italic text-ink">
              {ev.quote ? `“${ev.quote}”` : S.graph.noQuote}
            </div>
            <div class="mt-2 flex items-center gap-2">
              {/* 小角标：出处 + 去文档页看上下文。唯一会走人的地方 */}
              <RouterLink
                to={{
                  path: `/kb/${props.kbId}/doc/${ev.document_id}`,
                  query: { chunk: ev.chunk_id }
                }}
                class="hover-ink inline-flex min-w-0 items-center gap-1 text-aux text-ink-2"
                title={S.graph.openInDoc}
              >
                <span class="truncate">
                  {S.graph.sectionRef(ev.filename, ev.seq + 1)}
                </span>
                <ExternalLinkIcon width="11" height="11" class="shrink-0" />
              </RouterLink>
              {/* 出处不是原文时写一句（0040） */}
              {originLabel(ev.origin, ev.anchor) && (
                <span
                  class="shrink-0 text-aux text-ink-2"
                  title={originHint(ev.origin, ev.origin_model)}
                >
                  {originLabel(ev.origin, ev.anchor)}
                </span>
              )}
              {ev.stale && (
                <span
                  class="shrink-0 tabular-nums text-aux text-ink-2"
                  title={S.graph.staleEvidenceHint}
                >
                  {S.graph.fromVersion(ev.doc_version)}
                </span>
              )}
              {ev.document_deleted && (
                <span
                  class="shrink-0 text-aux text-warning"
                  title={S.graph.sourceDeletedHint}
                >
                  {S.graph.sourceDeleted}
                </span>
              )}
            </div>
          </div>
        ))}
        {evidence.data.value?.evidence.length === 0 && (
          <p class="text-sub text-ink-2">{S.graph.noEvidence}</p>
        )}
        {/* 这条区间不是原文写的，是引擎对账或人工裁决闭合的 */}
        {props.fact.corrected && (
          <p class="text-aux text-ink-2">{S.graph.correctedHint}</p>
        )}
        {/* 置信度只在低到值得怀疑时说话 */}
        {props.fact.confidence < 0.75 && (
          <p class="text-aux text-warning">
            {Math.round(props.fact.confidence * 100)}% {S.graph.confidence}
          </p>
        )}
      </div>
    );
  }
});

/* ---------- 证明链（Derived/Blocked 展开体，旧仓 ProofChain/ProofSteps 逐字语义） ---------- */

/** 一条派生的证明链。展开时才取——证明是「问了才看」的东西。
 *  取不到（派生已失效）就退回列表里带来的那几行文本，不空着 */
const ProofChain = defineComponent({
  name: "ProofChain",
  props: {
    kbId: { type: String, required: true },
    d: { type: Object as PropType<DerivedFact>, required: true }
  },
  setup(props) {
    const proof = useQuery({
      queryKey: computed(() => ["proof", props.d.id]),
      queryFn: () => derivedProof(props.kbId, props.d.id)
    });
    const steps = computed(() => proof.data.value?.proof?.steps ?? null);
    return () => (
      <div>
        {proof.isPending.value && (
          <p class="text-aux text-ink-2">{S.graph.proofLoading}</p>
        )}
        {steps.value && (
          <ProofSteps kbId={props.kbId} steps={steps.value} depth={0} />
        )}
        {/* 派生已失效、证明取不到：退回列表里带来的那几行文本 */}
        {!proof.isPending.value && !steps.value && (
          <ol class="space-y-1">
            {props.d.premises.map((p, i) => (
              <li key={i} class="text-aux text-ink-2">
                {p}
              </li>
            ))}
            {props.d.premises.length === 0 && (
              <li class="text-aux text-ink-2">{S.graph.derivedNoProof}</li>
            )}
          </ol>
        )}
      </div>
    );
  }
});

/** 证明的步，落了地的与没落地的派生共用。递归：每一步的 `premises` 是它的
 *  子证明（叶子为空自然终止）；深度视觉上限 proofIndentClass 管（窄屏） */
const ProofSteps = defineComponent({
  name: "ProofSteps",
  props: {
    kbId: { type: String, required: true },
    steps: { type: Array as PropType<ProofStep[]>, required: true },
    depth: { type: Number, default: 0 }
  },
  setup(props) {
    return () => (
      <ol class="space-y-2">
        {props.steps.map(st => (
          <ProofStepRow
            key={st.fact_id}
            kbId={props.kbId}
            step={st}
            depth={props.depth}
          />
        ))}
      </ol>
    );
  }
});

/** 单条证明步：它的「子证明」在 `step.premises` 里再渲染一次 ProofSteps。
 *  拆出来是为了让递归有界——这一个组件自己只画一层 */
const ProofStepRow = defineComponent({
  name: "ProofStepRow",
  props: {
    kbId: { type: String, required: true },
    step: { type: Object as PropType<ProofStep>, required: true },
    depth: { type: Number, required: true }
  },
  setup(props) {
    const st = props.step;
    return () => (
      <li class="text-aux">
        <div class="flex flex-wrap items-baseline gap-2">
          <span class="shrink-0 tabular-nums text-aux text-ink-2">
            {S.graph.proofStep(st.seq + 1)}
          </span>
          <span class={st.retracted ? "text-ink-2 line-through" : "text-ink-2"}>
            {st.subject}
            <span class="text-ink-2"> — {st.predicate ?? "?"} → </span>
            {st.object ?? "?"}
          </span>
          {st.retracted && (
            <UChip tone="warn" class="shrink-0 text-aux">
              {S.graph.proofRetracted}
            </UChip>
          )}
        </div>
        <div class="mt-1 space-y-1 pl-2">
          {st.evidence.map(ev => (
            <RouterLink
              key={ev.chunk_id}
              to={{
                path: `/kb/${props.kbId}/doc/${ev.document_id}`,
                query: { chunk: ev.chunk_id }
              }}
              class="hover-ink block text-ink-2"
            >
              <div class="line-clamp-2 italic">
                {ev.quote ? `“${ev.quote}”` : S.graph.noQuote}
              </div>
              <div class="mt-1 text-ink-2">
                {S.graph.sectionRef(ev.filename, ev.seq + 1)}
                {ev.stale && (
                  <span
                    class="ml-2 tabular-nums text-aux text-ink-2"
                    title={S.graph.staleEvidenceHint}
                  >
                    {S.graph.fromVersion(ev.doc_version)}
                  </span>
                )}
                {ev.document_deleted && (
                  <span
                    class="ml-2 text-aux text-warning"
                    title={S.graph.sourceDeletedHint}
                  >
                    {S.graph.sourceDeleted}
                  </span>
                )}
              </div>
            </RouterLink>
          ))}
          {st.evidence.length === 0 && (
            <p class="text-ink-2">{S.graph.noEvidence}</p>
          )}
        </div>
        {st.premises.length > 0 && (
          <div class={proofIndentClass(props.depth)}>
            <ProofSteps
              kbId={props.kbId}
              steps={st.premises}
              depth={props.depth + 1}
            />
          </div>
        )}
      </li>
    );
  }
});

/* ---------- 事实行 ---------- */

const FactRow = defineComponent({
  name: "GraphFactRow",
  props: {
    kbId: { type: String, required: true },
    dir: { type: String as PropType<"out" | "in">, required: true },
    fact: { type: Object as PropType<EntityFact>, required: true },
    past: { type: Boolean, default: false },
    /** 证据摊开归面板管（单一展开行）：幽灵边点进来也要能摊开派生那一行 */
    open: { type: Boolean, default: false },
    pinned: { type: Boolean, default: false },
    onToggle: {
      type: Function as PropType<() => void>,
      required: true
    },
    onPointFact: {
      type: Function as PropType<(factId: string | null) => void>,
      default: undefined
    },
    onFocusFact: {
      type: Function as PropType<
        (factId: string, otherId: string | null) => void
      >,
      default: undefined
    },
    onPointEntity: {
      type: Function as PropType<(entityId: string | null) => void>,
      default: undefined
    },
    onNavigate: {
      type: Function as PropType<(id: string) => void>,
      default: undefined
    }
  },
  emits: ["navigate"],
  setup(props) {
    const fact = props.fact;
    const interval = fmtInterval(fact);
    /* **一行三件事，各有各的去处**（旧仓注释）：整行（与谓词）是这条事实——
       指着 = 画布上那条边亮，点 = 钉住它、镜头移过去；宾语是另一个实体——
       指着 = 画布上那个节点亮，点 = 跳过去看它。字面值事实画布上没有边，
       这一行不指画布，点也不做事 */
    const isEdge = !!fact.other_id;
    const focus = isEdge
      ? () => props.onFocusFact?.(fact.id, fact.other_id)
      : undefined;
    return () => (
      <div
        class={(fact.stale || props.past) && "opacity-55"}
        title={fact.stale ? S.graph.staleFactHint : undefined}
      >
        {/* 两行：上面是这条事实，下面是我们对它知道些什么 */}
        <div
          role={focus ? "button" : undefined}
          tabindex={focus ? 0 : undefined}
          aria-pressed={focus ? props.pinned : undefined}
          onClick={focus}
          onKeydown={(ev: KeyboardEvent) => {
            if (focus && ev.key === "Enter") focus();
          }}
          onMouseenter={isEdge ? () => props.onPointFact?.(fact.id) : undefined}
          onMouseleave={isEdge ? () => props.onPointFact?.(null) : undefined}
          class={[
            "group -mx-2 flex items-start gap-2 rounded-ctl px-2 py-1 transition-colors hover:bg-fill",
            focus && "cursor-pointer",
            props.pinned && "bg-fill"
          ]}
        >
          <span class="shrink-0 pt-1 text-violet">
            {props.dir === "out" ? (
              <ArrowRightIcon width="12" height="12" />
            ) : (
              <ArrowLeftIcon width="12" height="12" />
            )}
          </span>
          <span class="min-w-0 flex-1">
            <span class="flex items-center gap-2">
              <span
                class={[
                  "min-w-0 truncate text-body text-ink",
                  fact.predicate_label === null && "italic text-ink-2"
                ]}
                title={
                  fact.predicate_label
                    ? fact.inferred
                      ? `${predicateSentence(fact.predicate_label)} · ${S.graph.inferredPredicate}`
                      : [
                          predicateSentence(fact.predicate_label),
                          fact.said_as ? S.graph.saidAs(fact.said_as) : null
                        ]
                          .filter(Boolean)
                          .join(" · ")
                    : undefined
                }
              >
                {/* 这一行是一句话，谓词拆开读（predicateText） */}
                {fact.predicate_label
                  ? predicateSentence(fact.predicate_label)
                  : S.graph.unknownPredicate}
              </span>
              {isEdge ? (
                <span
                  class="min-w-0 flex-none truncate"
                  onMouseenter={() => {
                    // 指着宾语说的是那个实体，不是这条边：边先放下，出了这个词再拿起来
                    props.onPointFact?.(null);
                    props.onPointEntity?.(fact.other_id);
                  }}
                  onMouseleave={() => {
                    props.onPointEntity?.(null);
                    props.onPointFact?.(fact.id);
                  }}
                >
                  <RouterLink
                    to={{
                      path: `/kb/${props.kbId}/graph`,
                      query: { entity: fact.other_id! }
                    }}
                    class="text-aux text-ink-2 underline-offset-2 hover:text-ink hover:underline"
                    title={S.graph.openEntity(fact.other_name ?? "")}
                    onClick={(ev: MouseEvent) => {
                      // 宾语词是"跳过去看它"，不是"钉这条边"：拦下整行的 focus
                      //（旧仓 goOther 同款 stopPropagation）
                      ev.stopPropagation();
                      props.onNavigate?.(fact.other_id!);
                    }}
                  >
                    {fact.other_name ?? "?"}
                  </RouterLink>
                </span>
              ) : (
                <span class="min-w-0 flex-1 truncate text-aux text-ink-2">
                  {fmtObjectValue(fact.object_value) ?? "?"}
                </span>
              )}
              {/* 边上的属性（0037）：跟在宾语后面，不另起一行 */}
              {(fact.qualifiers ?? []).map(q => (
                <span
                  key={q.qualifier_type_id}
                  class="shrink-0 text-sub text-ink-2"
                >
                  {q.label || q.key}{" "}
                  <span class="text-ink">
                    {q.entity_name ?? fmtObjectValue(q.value) ?? "?"}
                  </span>
                </span>
              ))}
            </span>
            {/* 第二行：何时成立、要不要留神、看证据的入口。没有日期也要说一句 */}
            <span class="flex items-center gap-2">
              <span
                class={[
                  "tabular-nums text-aux",
                  interval ? "text-ink-2" : "italic text-ink-2"
                ]}
              >
                {interval || asOf(fact) || S.graph.undated}
              </span>
              {fact.stale && (
                <UChip class="shrink-0 text-aux">{S.graph.staleFactChip}</UChip>
              )}
              {fact.contested && (
                <UChip tone="contest" title={fact.contested.kind}>
                  {S.graph.contestedChip}
                </UChip>
              )}
              <span class="ml-auto flex shrink-0 items-center gap-2 pl-2">
                {fact.evidence_count > 0 && (
                  <button
                    type="button"
                    class={["link-inline text-aux", props.open && "is-on"]}
                    onClick={(ev: MouseEvent) => {
                      // 不让「看证据」触发整行的钉边：那是两个动作
                      ev.stopPropagation();
                      props.onToggle();
                    }}
                  >
                    {S.graph.sources(fact.evidence_count)}
                  </button>
                )}
              </span>
            </span>
          </span>
        </div>
        {props.open && (
          <div class="pb-2 px-2 ">
            <EvidenceList kbId={props.kbId} fact={fact} />
          </div>
        )}
      </div>
    );
  }
});

/* ---------- 派生（展开取证明链） ---------- */

const DerivedRow = defineComponent({
  name: "DerivedRow",
  props: {
    kbId: { type: String, required: true },
    d: { type: Object as PropType<DerivedFact>, required: true },
    entityId: { type: String, required: true },
    open: { type: Boolean, default: false },
    onToggle: { type: Function as PropType<() => void>, required: true },
    onNavigate: {
      type: Function as PropType<(id: string) => void>,
      default: undefined
    }
  },
  setup(props) {
    const d = props.d;
    const out = d.subject_id === props.entityId;
    /* 字面值结论没有另一端实体（0021）*/
    const otherId = out ? d.object_id : d.subject_id;
    const otherName = out ? d.object : d.subject;
    return () => (
      <div class="group -mx-2 flex items-start gap-2 px-2 py-1">
        <span class="shrink-0 pt-1 text-violet">
          <ArrowRightIcon width="12" height="12" />
        </span>
        <span class="min-w-0 flex-1">
          <span
            class="flex cursor-pointer items-center gap-2"
            role="button"
            tabindex={0}
            onClick={() => props.onToggle()}
            onKeydown={(ev: KeyboardEvent) => {
              if (ev.key === "Enter") props.onToggle();
            }}
          >
            {otherId ? (
              <RouterLink
                to={{
                  path: `/kb/${props.kbId}/graph`,
                  query: { entity: otherId }
                }}
                class="min-w-0 flex-1 truncate text-body text-ink hover:underline"
                onClick={(ev: MouseEvent) => {
                  ev.stopPropagation();
                  props.onNavigate?.(otherId);
                }}
              >
                {otherName}
              </RouterLink>
            ) : (
              <span class="min-w-0 flex-1 truncate text-body text-ink">
                {otherName}
              </span>
            )}
            <span class="ml-auto shrink-0 pl-2 tabular-nums text-aux text-ink-2">
              {d.premises.length}
            </span>
          </span>
          {/* 证明：前提按推导顺序，每条展开到原句（0002 R2）。与 EvidenceList
              同一个位置、同一种缩进——两者是同一件事的两种形态 */}
          {props.open && (
            <span class="mt-1 block pb-1 pl-2">
              <ProofChain kbId={props.kbId} d={d} />
            </span>
          )}
        </span>
      </div>
    );
  }
});

/* ---------- 没落地的派生（0017 §3） ---------- */

const BlockedRow = defineComponent({
  name: "BlockedRow",
  props: {
    kbId: { type: String, required: true },
    b: { type: Object as PropType<BlockedDerivation>, required: true },
    entityId: { type: String, required: true },
    open: { type: Boolean, default: false },
    onToggle: { type: Function as PropType<() => void>, required: true },
    onNavigate: {
      type: Function as PropType<(id: string) => void>,
      default: undefined
    }
  },
  setup(props) {
    const b = props.b;
    const out = b.subject_id === props.entityId;
    const otherId = out ? b.object_id : b.subject_id;
    const otherName = out ? b.object : b.subject;
    const proof = useQuery({
      queryKey: computed(() => ["blocked-proof", b.violation_id]),
      queryFn: () => blockedProof(props.kbId, b.violation_id),
      enabled: computed(() => props.open)
    });
    return () => (
      <div class="group -mx-2 flex items-start gap-2 px-2 py-1">
        <span class="shrink-0 pt-1 text-violet">
          {out ? (
            <ArrowRightIcon width="12" height="12" />
          ) : (
            <ArrowLeftIcon width="12" height="12" />
          )}
        </span>
        <span class="min-w-0 flex-1">
          <span
            class="flex cursor-pointer items-center gap-2"
            role="button"
            tabindex={0}
            onClick={() => props.onToggle()}
            onKeydown={(ev: KeyboardEvent) => {
              if (ev.key === "Enter") props.onToggle();
            }}
          >
            <span class="shrink-0 text-aux text-ink-2">{b.predicate}</span>
            <RouterLink
              to={{
                path: `/kb/${props.kbId}/graph`,
                query: { entity: otherId }
              }}
              class="min-w-0 flex-1 truncate text-body text-ink hover:underline"
              onClick={(ev: MouseEvent) => {
                ev.stopPropagation();
                props.onNavigate?.(otherId);
              }}
            >
              {otherName}
            </RouterLink>
            <span class="ml-auto shrink-0 pl-2 text-aux text-ink-2">
              {S.graph.ruleNames[b.rule as keyof typeof S.graph.ruleNames] ??
                b.rule}
            </span>
          </span>
          {/* 像 DerivedRow 一样的一行，多一句「挡住它的是谁」——
              人在这里看到「引擎本可以画这条边，是什么拦住了它」 */}
          <span class="flex items-center gap-2 pl-2 text-aux">
            <span class="truncate text-warning">
              {S.graph.blockedBy(b.against_text)}
            </span>
            <RouterLink
              to={{
                path: `/kb/${props.kbId}/review`,
                query: { queue: "violations", item: b.violation_id }
              }}
              class="link-inline ml-auto shrink-0 text-ink-2"
            >
              {S.graph.blockedReview} →
            </RouterLink>
          </span>
          {props.open && (
            <span class="mt-1 block pb-1 pl-2">
              {proof.isPending.value && (
                <p class="text-aux text-ink-2">{S.graph.proofLoading}</p>
              )}
              {proof.data.value?.steps && (
                <ProofSteps
                  kbId={props.kbId}
                  steps={proof.data.value.steps}
                  depth={0}
                />
              )}
            </span>
          )}
        </span>
      </div>
    );
  }
});

/* ---------- 面板本体 ---------- */

export const EntityPanel = defineComponent({
  name: "EntityPanel",
  props: {
    kbId: { type: String, required: true },
    entityId: { type: String, required: true },
    exiting: { type: Boolean, default: false },
    /** 点过、钉在画布上的那条事实（行底软色 + aria-pressed） */
    pinnedFact: { type: String, default: null },
    /** 幽灵边点进来：停在「推出来的」那一档、展开那一行。读一次就清（旧仓同款 holder） */
    intent: {
      type: Object as PropType<{
        current: { view: "derived"; open: string } | null;
      }>,
      default: null
    }
  },
  emits: ["close", "navigate", "pointFact", "focusFact", "pointEntity"],
  setup(props, { emit }) {
    const detail = useQuery({
      queryKey: computed(() => ["entity", props.kbId, props.entityId]),
      queryFn: () => entityDetail(props.kbId, props.entityId)
    });
    /** 面板同一时刻只摊开一行（证据/证明/派生共用一个键——旧仓 openFact 语义） */
    const openFact = ref<string | null>(null);
    // Relations = 按关系分组；History = 记录轴；Derived = 推出来的
    const view = ref<"relations" | "history" | "derived">("relations");
    // 幽灵边点进来：读一次 intent，停在 derived、展开那一行（面板按实体 remount）
    const it = props.intent?.current;
    if (it) {
      view.value = it.view;
      openFact.value = it.open;
      props.intent!.current = null;
    }
    // 推出来的那些。单独一个键，不掺进 facts
    const derived = computed(() => detail.data.value?.derived ?? []);
    const blocked = computed(() => detail.data.value?.blocked ?? []);
    /* 按「方向 + 谓词 + 规则」分组（旧仓 derivedGroups 逐字语义） */
    const derivedGroups = computed(() => {
      const map = new Map<
        string,
        {
          key: string;
          direction: "in" | "out";
          predicate: string;
          rule: string;
          rows: DerivedFact[];
        }
      >();
      for (const d of derived.value) {
        const direction = d.subject_id === props.entityId ? "out" : "in";
        const rule = d.rule_name ?? S.graph.ruleNames[d.rule] ?? d.rule;
        const key = `${direction}|${d.predicate}|${d.rule_name ?? d.rule}`;
        const cur = map.get(key);
        if (cur) cur.rows.push(d);
        else
          map.set(key, {
            key,
            direction,
            predicate: d.predicate,
            rule,
            rows: [d]
          });
      }
      return [...map.values()];
    });
    watch(
      () => props.entityId,
      () => (view.value = "relations")
    );

    const e = computed<GraphNode | undefined>(() => detail.data.value?.entity);

    /* Relations 是一张表：从这个实体出发 / 指向这个实体两节，节里一行一条。
       此刻不成立的（0022 的口径按读出来的区间判）折在节尾的「N past」里 */
    const sections = computed(() => {
      const all = detail.data.value?.facts ?? [];
      const nowIso = new Date().toISOString();
      const current = (f: EntityFact) =>
        (!f.holds_from || f.holds_from <= nowIso) &&
        (!f.holds_to || f.holds_to > nowIso);
      const order = (a: EntityFact, b: EntityFact) =>
        predicateSentence(a.predicate_label ?? "\u{FFFD}").localeCompare(
          predicateSentence(b.predicate_label ?? "\u{FFFD}")
        ) || ((a.valid_from ?? "9999") < (b.valid_from ?? "9999") ? -1 : 1);
      const split = (dir: "out" | "in") => {
        const mine = all.filter(f => f.direction === dir);
        return {
          rows: mine.filter(current).sort(order),
          past: mine.filter(f => !current(f)).sort(order)
        };
      };
      return { out: split("out"), in: split("in") };
    });

    const names = computed(
      () => (detail.data.value?.names ?? []) as NameView[]
    );

    const toggleFact = (id: string) =>
      (openFact.value = openFact.value === id ? null : id);

    return () => (
      <div
        class={[
          props.exiting ? "dock-out" : "dock-in",
          "absolute bottom-20 right-3 top-14 z-10 flex w-96 flex-col rounded-card border border-line bg-card shadow-pop"
        ]}
      >
        <div class="flex items-start justify-between border-b border-line p-4 ">
          <div>
            {e.value && (
              <>
                <div class="flex items-center gap-2">
                  <span
                    class="size-2.5  shrink-0 rounded-full"
                    style={{
                      background: e.value.color,
                      boxShadow: `0 0 8px ${e.value.color}55`
                    }}
                  />
                  <span class="truncate text-title font-semibold tracking-tight text-ink">
                    {e.value.name}
                  </span>
                </div>
                <div class="mt-1 text-sub text-ink-2">
                  {e.value.disambiguator &&
                  e.value.disambiguator !== e.value.type_label
                    ? `${e.value.disambiguator} · `
                    : ""}
                  {(e.value.type_label ?? S.graph.untyped) +
                    " · " +
                    (detail.data.value?.facts.length ?? 0) +
                    " " +
                    S.graph.facts}
                </div>
              </>
            )}
          </div>
          <div class="mt-1 flex items-center gap-2">
            {/* 实体修正（名字、类型）在弹窗里：面板只展示；编辑属任务 6 */}
            <button
              type="button"
              class="rounded-ctl p-1 text-ink-2 hover:bg-fill hover:text-ink"
              title={S.graph.close}
              onClick={() => emit("close")}
            >
              <svg
                width="15"
                height="15"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* 视图切换：Relations（过去的折在组尾）| History（记录轴）| Derived */}
        <div class="px-4 pt-3">
          <div class="flex gap-1 rounded-ctl bg-fill p-1">
            {(["relations", "history", "derived"] as const)
              // 推出来的那一档：没有派生就不出现
              .filter(
                v =>
                  v !== "derived" ||
                  derived.value.length > 0 ||
                  blocked.value.length > 0
              )
              .map(v => (
                <button
                  key={v}
                  type="button"
                  class={[
                    "flex-c flex-1   gap-1 rounded-ctl px-3 py-1 text-sub font-medium transition-colors",
                    view.value === v
                      ? "bg-card text-primary shadow-card"
                      : "text-ink-2 hover:text-ink"
                  ]}
                  onClick={() => (view.value = v)}
                >
                  {v === "relations"
                    ? S.graph.viewRelations
                    : v === "history"
                      ? S.graph.viewHistory
                      : S.graph.viewDerived}
                </button>
              ))}
          </div>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2 ">
          {/* 名字在关系之前：先回答「它叫什么」。只有本名一个的时候不出这一节 */}
          {view.value === "relations" && names.value.length > 1 && (
            <div class="mb-2">
              <GroupLabel class="px-2 pb-1 pt-2">{S.graph.names}</GroupLabel>
              {names.value.map(n => (
                <div
                  key={n.fact_id}
                  class="flex items-center gap-2 px-2 py-1 text-sub"
                >
                  <span class="text-ink">{n.name}</span>
                  {!n.canonical && (
                    <span class="text-aux text-ink-2">
                      {n.valid_to
                        ? S.graph.nameUntil(n.valid_to.slice(0, 10))
                        : S.graph.shownName}
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}
          {view.value === "relations" &&
            (["out", "in"] as const).map(dir => {
              const { rows, past } = sections.value[dir];
              if (rows.length === 0 && past.length === 0) return null;
              const name = e.value?.name ?? "";
              return (
                <FactSection
                  key={dir}
                  dir={dir}
                  title={
                    dir === "out"
                      ? S.graph.fromEntity(name)
                      : S.graph.toEntity(name)
                  }
                  count={rows.length + past.length}
                >
                  {rows.map(f => (
                    <FactRow
                      key={f.id}
                      kbId={props.kbId}
                      dir={dir}
                      fact={f}
                      open={openFact.value === f.id}
                      pinned={props.pinnedFact === f.id}
                      onToggle={() => toggleFact(f.id)}
                      onPointFact={(id: string | null) => emit("pointFact", id)}
                      onFocusFact={(id: string, other: string | null) =>
                        emit("focusFact", id, other)
                      }
                      onPointEntity={(id: string | null) =>
                        emit("pointEntity", id)
                      }
                      onNavigate={(id: string) => emit("navigate", id)}
                    />
                  ))}
                  {past.length > 0 && (
                    <PastFold n={past.length}>
                      {past.map(f => (
                        <FactRow
                          key={f.id}
                          kbId={props.kbId}
                          dir={dir}
                          fact={f}
                          past
                          open={openFact.value === f.id}
                          pinned={props.pinnedFact === f.id}
                          onToggle={() => toggleFact(f.id)}
                          onPointFact={(id: string | null) =>
                            emit("pointFact", id)
                          }
                          onFocusFact={(id: string, other: string | null) =>
                            emit("focusFact", id, other)
                          }
                          onPointEntity={(id: string | null) =>
                            emit("pointEntity", id)
                          }
                          onNavigate={(id: string) => emit("navigate", id)}
                        />
                      ))}
                    </PastFold>
                  )}
                </FactSection>
              );
            })}
          {view.value === "history" && (
            <EntityHistoryPanel kbId={props.kbId} entityId={props.entityId} />
          )}
          {view.value === "derived" && (
            <>
              <p class="px-2 pb-2 pt-1 text-aux/relaxed  text-ink-2">
                {S.graph.derivedHint}
              </p>
              {/* 与 Relations 同一个骨架：方向箭头 + 谓词 + 条数的小标题 */}
              {derivedGroups.value.map(gr => (
                <div key={gr.key} class="mb-3 last:mb-1">
                  <GroupLabel
                    class="px-2 pb-1 pt-2"
                    count={gr.rows.length > 1 ? gr.rows.length : undefined}
                  >
                    {gr.predicate}
                    <span class="ml-2 font-normal text-ink-2">{gr.rule}</span>
                  </GroupLabel>
                  <div>
                    {gr.rows.map(d => (
                      <DerivedRow
                        key={d.id}
                        kbId={props.kbId}
                        d={d}
                        entityId={props.entityId}
                        open={openFact.value === d.id}
                        onToggle={() => toggleFact(d.id)}
                        onNavigate={(id: string) => emit("navigate", id)}
                      />
                    ))}
                  </div>
                </div>
              ))}
              {blocked.value.length > 0 && (
                <div class="mb-3 last:mb-1">
                  <GroupLabel
                    class="px-2 pb-1 pt-2"
                    tone="contest"
                    count={blocked.value.length}
                  >
                    {S.graph.blockedTitle}
                  </GroupLabel>
                  <p class="px-2 pb-2 text-aux/relaxed  text-ink-2">
                    {S.graph.blockedHint}
                  </p>
                  {blocked.value.map(b => (
                    <BlockedRow
                      key={b.violation_id}
                      kbId={props.kbId}
                      b={b}
                      entityId={props.entityId}
                      open={openFact.value === b.violation_id}
                      onToggle={() => toggleFact(b.violation_id)}
                      onNavigate={(id: string) => emit("navigate", id)}
                    />
                  ))}
                </div>
              )}
            </>
          )}
          {view.value !== "history" &&
            view.value !== "derived" &&
            detail.data.value?.facts.length === 0 && (
              <p class="p-2 text-body text-ink-2">{S.graph.noFacts}</p>
            )}
        </div>
      </div>
    );
  }
});

/* ---------- 一节（从这个实体出发 / 指向这个实体）：可折叠 ---------- */

import { defineComponent as dc } from "vue";
const FactSection = dc({
  name: "FactSection",
  props: {
    dir: { type: String as PropType<"out" | "in">, required: true },
    title: { type: String, required: true },
    count: { type: Number, required: true }
  },
  setup(props, { slots }) {
    const open = ref(true);
    return () => (
      <div class="mb-2">
        <URowFold open={open.value} onToggle={() => (open.value = !open.value)}>
          <span class="flex items-center gap-2 text-sub font-medium">
            {/* 与下面每条事实行的方向箭头同一尺寸（12）：同一个记号排成一列 */}
            <span class="text-primary">
              {props.dir === "out" ? (
                <ArrowRightIcon width="12" height="12" />
              ) : (
                <ArrowLeftIcon width="12" height="12" />
              )}
            </span>
            <span class="truncate">{props.title}</span>
            <span class="tabular-nums">{props.count}</span>
          </span>
        </URowFold>
        {open.value && <div class="pl-6">{slots.default?.()}</div>}
      </div>
    );
  }
});

const URowFold = dc({
  name: "URowFold",
  props: { open: { type: Boolean, default: true } },
  emits: ["toggle"],
  setup(props, { slots, emit }) {
    return () => (
      <div
        class="group -mx-2 flex w-full cursor-pointer items-center gap-2 rounded-ctl px-2 py-1 text-left transition-colors hover:bg-fill"
        onClick={() => emit("toggle")}
      >
        <span class="flex w-4 justify-center">
          <ChevronRightIcon
            width="12"
            height="12"
            class={["turn", props.open && "rotate-90"]}
          />
        </span>
        <span class="min-w-0 flex-1 truncate">{slots.default?.()}</span>
      </div>
    );
  }
});

/** 已结束的那些折在节尾（旧仓 PastFold 逐字语义） */
const PastFold = dc({
  name: "PastFold",
  props: { n: { type: Number, required: true } },
  setup(props, { slots }) {
    const open = ref(false);
    return () => (
      <div>
        <URowFold open={open.value} onToggle={() => (open.value = !open.value)}>
          <span class="text-sub font-medium text-ink-2">
            {S.graph.past(props.n)}
          </span>
        </URowFold>
        {open.value && <div class="pl-6">{slots.default?.()}</div>}
      </div>
    );
  }
});

void localDateTime;
void UStatus;
