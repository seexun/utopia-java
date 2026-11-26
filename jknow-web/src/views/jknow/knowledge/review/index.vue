<!-- 审核页（对照旧仓 pages/Review.tsx，W2 裁决流全量迁入）：队列过滤卡 +
     单档列表 + 单一分页。写动作（W2）：decide 单条（理由 0026）/batch 多选一批
     一个动作（#428）/agent 建议回答（merge/keep/revert）/各档裁决（待确认点头
     0015/低置信/时态冲突三出路/未再提及闭合/公理违规三出路/本体缺陷两出路/
     对齐绑定/勘误批驳）/一致性检查（同步纯计算，零和零不一样）/合并史撤销。
     错误文案：agent 正在裁的 409 走 reviewErrors 的 Ⅰ类映射；权限闸
     canManage（editor+，与服务端 require_kb(Role::Editor) 同口径）。
     reskin-b4 模板层重造（script 逻辑零动，仅 import 换装）：左栏 rail 改
     队列过滤卡（UPill，计数徽章 badge-soft），行卡列表与概览统计的结构/接线
     原样。页头合一（2026-11-24 留档清偿批）：独立页头带删除，标题+kb chip
     收进过滤卡卡头首行。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import {
  review as reviewApi,
  reviewSummary,
  reviewHistory,
  reviewBatch,
  decideReview,
  agentAnswer,
  decidePending,
  decideDefect,
  decideAlignmentPhrase,
  decideAlignmentRule,
  decideAlignmentKindWord,
  decideErrata,
  decideViolation,
  runConsistencyCheck,
  closeFact,
  resolveConflict
} from "@/api/knowledge/review";
import { confirmFact, rejectFact, revertMerge } from "@/api/knowledge/graph";
import { kbDetail as kbDetailApi } from "@/api/knowledge/kbs";
import { ontology as ontologyApi } from "@/api/knowledge/ontology";
import { alignmentErrorMessage } from "@/domain/pages/reviewErrors";
import type {
  ReviewQueue,
  ReviewTypeFilter,
  PendingFactItem,
  ReviewItem,
  FactReviewItem,
  ConflictItem,
  AxiomViolation,
  ViolationResolution,
  OntologyDefect,
  AlignmentItem,
  AlignmentMarks,
  ErrataItem,
  MergeLog,
  AgentDecision,
  ReviewHistoryEvent,
  ReviewCounts
} from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbScope } from "@/composables/useKbScope";
import { useKbRole } from "@/composables/useKbRole";
import { ReviewOverview } from "./overview";
import {
  PendingFactRow,
  DuplicateCard,
  FactRow,
  UnconfirmedRow,
  ConflictRow,
  ViolationRow,
  type DecideOpts,
  DefectRow,
  AlignmentPhraseRow,
  AlignmentRuleRow,
  AlignmentKindWordRow,
  ErrataRow,
  AgentRow,
  MergeRow,
  DecisionRow
} from "./rows";
import { UPill, UPager, Loading } from "@/components/knowledge/kit";

const DUP_PAGE = 6;
const FACT_PAGE = 10;
const MERGE_PAGE = 10;
const CONFLICT_PAGE = 8;

type Sel =
  | "overview"
  | "pending"
  | "duplicates"
  | "conflicts"
  | "unconfirmed"
  | "lowconf"
  | "violations"
  | "defects"
  | "alignment"
  | "errata"
  | "agent"
  | "decisions"
  | "merges";

/** 走服务端分页的那几档（决策台账另有自己的接口） */
const QUEUE_FETCHED: ReviewQueue[] = [
  "pending",
  "duplicates",
  "conflicts",
  "unconfirmed",
  "lowconf",
  "violations",
  "defects",
  "alignment",
  "errata",
  "merges",
  "agent"
];

const QUEUE_ORDER: Sel[] = [
  "pending",
  "duplicates",
  "conflicts",
  "unconfirmed",
  "lowconf",
  "violations",
  "defects",
  "alignment",
  "errata"
];

/** 左栏行文案（rail 键与档 key 不同名——lowconf → railLowConfidence） */
const RAIL_LABEL: Record<string, string> = {
  pending: S.review.railPending,
  duplicates: S.review.railDuplicates,
  conflicts: S.review.railConflicts,
  unconfirmed: S.review.railUnconfirmed,
  lowconf: S.review.railLowConfidence,
  violations: S.review.railViolations,
  defects: S.review.railDefects,
  alignment: S.review.railAlignment,
  errata: S.review.railErrata
};

const PAGE_SIZE: Record<Exclude<Sel, "overview">, number> = {
  pending: FACT_PAGE,
  duplicates: DUP_PAGE,
  conflicts: CONFLICT_PAGE,
  unconfirmed: FACT_PAGE,
  lowconf: FACT_PAGE,
  violations: FACT_PAGE,
  defects: FACT_PAGE,
  alignment: FACT_PAGE,
  errata: FACT_PAGE,
  merges: MERGE_PAGE,
  decisions: 20,
  agent: 20
};

/** infoHint：标题旁 ⓘ 的悬停短句（W3 收口，任务 5 M-1 后半——旧仓 railXxxHint
    短版挂这里；长版 hint 已在标题下白纸黑字）。merges 无副题也就不配 ⓘ（旧仓同）。 */
const SECTION: Record<Sel, { title: string; hint: string | null; infoHint: string | null }> = {
  overview: { title: S.review.overviewTitle, hint: S.review.overviewHint, infoHint: S.review.railOverviewHint },
  pending: { title: S.review.pending, hint: S.review.pendingHint, infoHint: S.review.railPendingHint },
  duplicates: { title: S.review.duplicates, hint: S.review.duplicatesHint, infoHint: S.review.railDuplicatesHint },
  conflicts: { title: S.review.conflicts, hint: S.review.conflictsHint, infoHint: S.review.railConflictsHint },
  unconfirmed: { title: S.review.unconfirmed, hint: S.review.unconfirmedHint, infoHint: S.review.railUnconfirmedHint },
  lowconf: { title: S.review.lowConfidence, hint: S.review.lowConfidenceHint, infoHint: S.review.railLowConfidenceHint },
  violations: { title: S.review.violations, hint: S.review.violationsHint, infoHint: S.review.railViolationsHint },
  defects: { title: S.review.defects, hint: S.review.defectsHint, infoHint: S.review.railDefectsHint },
  alignment: { title: S.review.alignment, hint: S.review.alignmentHint, infoHint: S.review.railAlignmentHint },
  errata: { title: S.review.errata, hint: S.review.errataHint, infoHint: S.review.railErrataHint },
  decisions: { title: S.review.decisionsTitle, hint: S.review.decisionsHint, infoHint: S.review.railDecisionsHint },
  merges: { title: S.review.mergeHistory, hint: null, infoHint: null },
  agent: { title: S.review.agentTitle, hint: S.review.agentHint, infoHint: S.review.railAgentQueueHint }
};

const route = useRoute();
const router = useRouter();
const kbStore = useKbContextHook();
const queryClient = useQueryClient();
// KbScope 对等物（每个 kb 页面 setup 调一次）
const scope = useKbScope();
const kbId = scope.kbId;
const kb = computed(() => kbStore.kbDetail ?? scope.kb.value);
// 权限闸：裁决走人的口径是 Editor+（待确认 0015 的与服务端同款）——
// 其余各档旧仓不显隐（403 兜底在 client 层），W2 统一收进 canDecide 显隐
const { canManage: canDecide } = useKbRole();

// 深链接认九档与 agent 那一档（0025 的告警会指过来）；别的落到总览
const itemParam = computed(() =>
  typeof route.query.item === "string" ? route.query.item : ""
);
const sel = ref<Sel>(
  QUEUE_ORDER.includes((route.query.queue as Sel) ?? ("" as Sel)) ||
    route.query.queue === "agent"
    ? (route.query.queue as Sel)
    : "overview"
);
/* C-1（W3 审查收口）：站内跳转只改 query 不换 path（lay-content 按 path 定
   组件 key，不重挂）——sel 必须跟着 ?queue= 走，否则总览「去处理」深链
   （overview.tsx 的 ?queue=agent，RouterLink 站内 push）落了地却打不开那一档。
   校验与挂载那次（上式）同表；换档回第一页，与左栏点选（515 行）同规矩。
   顺带修好前进/后退：地址栏换成哪一档，画面跟着换 */
const queueParam = computed(() =>
  QUEUE_ORDER.includes((route.query.queue as Sel) ?? ("" as Sel)) ||
    route.query.queue === "agent"
    ? (route.query.queue as Sel)
    : "overview"
);
const page = ref(0);
watch(queueParam, v => {
  if (v === sel.value) return;
  sel.value = v;
  page.value = 0;
});
// 重复项的类型筛选与批量选中（#428）。选中集合按页清：翻页、换档、换筛选
// 之后勾着的东西已经不在眼前，留着会让「合并所选」合掉看不见的东西
const types = ref<ReviewTypeFilter>("any");
const picked = ref(new Set<string>());
const batchWhy = ref("");
watch([page, sel, types], () => picked.value = new Set());

// 队列变化经 SSE 事件流推送（useKbEvents 挂在壳上），无需轮询。
// 按分档 + 页码取：计数每次都回（服务端 COUNT），内容只回当前这一档的一页。
const queueSel = computed<ReviewQueue>(() =>
  QUEUE_FETCHED.includes(
    (sel.value === "overview" ? "duplicates" : sel.value) as ReviewQueue
  )
    ? ((sel.value === "overview" ? "duplicates" : sel.value) as ReviewQueue)
    : "duplicates"
);
const typesForQuery = computed(() =>
  queueSel.value === "duplicates" ? types.value : ("any" as ReviewTypeFilter)
);
const review = useQuery({
  queryKey: computed(() => [
    "review",
    kbId.value,
    queueSel.value,
    page.value,
    typesForQuery.value
  ]),
  queryFn: () =>
    reviewApi(
      kbId.value,
      queueSel.value,
      PAGE_SIZE[queueSel.value as Exclude<Sel, "overview">],
      page.value * PAGE_SIZE[queueSel.value as Exclude<Sel, "overview">],
      typesForQuery.value
    ),
  enabled: computed(() => !!kbId.value),
  // 翻页时别把上一页闪成空白——计数与骨架都还在，只有条目在换
  placeholderData: (prev: unknown) => prev as never
});
// 决策台账：服务端分页，仅选中时拉取
const history = useQuery({
  queryKey: computed(() => ["reviewHistory", kbId.value, page.value]),
  queryFn: () => reviewHistory(kbId.value, page.value),
  enabled: computed(() => !!kbId.value && sel.value === "decisions")
});
// 总览：只在落在它上面时拉
const summary = useQuery({
  queryKey: computed(() => ["reviewSummary", kbId.value]),
  queryFn: () => reviewSummary(kbId.value),
  enabled: computed(() => !!kbId.value && sel.value === "overview")
});

// 库详情（治理开关在总览 Agent 段用；与 KbScope 共键）
const kbDetail = useQuery({
  queryKey: computed(() => ["kbOne", kbId.value]),
  queryFn: () => kbDetailApi(kbId.value),
  enabled: computed(() => !!kbId.value)
});

// 对齐卡片要列本体的类与属性给人选；只在这一档拉
const ontology = useQuery({
  queryKey: computed(() => ["ontology", kbId.value]),
  queryFn: () => ontologyApi(kbId.value),
  enabled: computed(() => !!kbId.value && queueSel.value === "alignment")
});

const reviewData = computed(() => review.data.value);
const counts = computed<Record<Sel | "mappings", number>>(() => {
  const c: ReviewCounts | undefined = reviewData.value?.counts;
  return {
    overview: 0,
    pending: c?.pending ?? 0,
    duplicates: c?.duplicates ?? 0,
    conflicts: c?.conflicts ?? 0,
    unconfirmed: c?.unconfirmed ?? 0,
    lowconf: c?.lowconf ?? 0,
    mappings: c?.mappings ?? 0,
    violations: c?.violations ?? 0,
    defects: c?.defects ?? 0,
    alignment: c?.alignment ?? 0,
    errata: c?.errata ?? 0,
    merges: c?.merges ?? 0,
    decisions: history.data.value?.total ?? 0,
    agent: c?.agent ?? 0
  };
});
const rows = computed<unknown[]>(() =>
  reviewData.value && reviewData.value.queue === queueSel.value
    ? (reviewData.value.items ?? [])
    : []
);
const queueEmpty = computed(() => QUEUE_ORDER.every(k => counts.value[k] === 0));
const active = computed<Sel>(() => sel.value);
const isQueueSel = computed(() => QUEUE_ORDER.includes(active.value));
const agentRunning = computed(() => reviewData.value?.counts.agent_running ?? false);
const agentRows = computed(() => reviewData.value?.counts.agent_rows ?? 0);
const dupSame = computed(() => reviewData.value?.counts.duplicates_same_type ?? 0);
const dupConflict = computed(
  () => reviewData.value?.counts.duplicates_type_conflict ?? 0
);

/* ---------- 裁决 mutation 族（对照旧仓 Review.tsx 逐个对齐） ---------- */

// 每一次决定之后：队列本身、总览（它数的正是这些）、台账、图（合并/闭合改图）
const invalidate = () => {
  for (const key of [
    ["review", kbId.value],
    ["reviewSummary", kbId.value],
    ["reviewHistory", kbId.value],
    ["graph"]
  ])
    queryClient.invalidateQueries({ queryKey: key });
};
// 待确认（0015）还有自己的键：确认卡在对话里也挂着（NodCard 同键）
const invalidatePending = () => {
  invalidate();
  queryClient.invalidateQueries({ queryKey: ["pending", kbId.value] });
};

const decide = useMutation({
  mutationFn: ({
    id,
    action,
    rationale
  }: {
    id: string;
    action: "merge" | "keep";
    rationale?: string;
  }) => decideReview(kbId.value, id, action, rationale),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
// 回答 agent 的一笔（0025）：走人的裁决路径，成为下一轮的先例
const answerAgent = useMutation({
  mutationFn: ({
    id,
    action,
    rationale
  }: {
    id: string;
    action: "merge" | "keep" | "revert";
    rationale?: string;
  }) => agentAnswer(kbId.value, id, action, rationale),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
// 批量裁决：一批一个动作，回来逐条说成没成；没成的留在列表里，成了的消失
const batch = useMutation({
  mutationFn: ({
    ids,
    action,
    rationale
  }: {
    ids: string[];
    action: "merge" | "keep";
    rationale?: string;
  }) => reviewBatch(kbId.value, ids, action, rationale),
  onSuccess: r => {
    const failed = r.outcomes.filter(o => o.error).length;
    if (failed > 0) toast.error(S.review.batchDone(r.decided, failed));
    else toast.success(S.review.batchDone(r.decided, 0));
  },
  onError: (e: Error) => toast.error(e.message),
  onSettled: () => {
    picked.value = new Set();
    batchWhy.value = "";
    invalidate();
  }
});
const factAction = useMutation({
  mutationFn: ({ id, action }: { id: string; action: "confirm" | "reject" }) =>
    action === "confirm"
      ? confirmFact(kbId.value, id)
      : rejectFact(kbId.value, id),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
const pendingAction = useMutation({
  mutationFn: ({ id, action }: { id: string; action: "confirm" | "reject" }) =>
    decidePending(kbId.value, id, action),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidatePending
});
const defectAction = useMutation({
  mutationFn: ({
    id,
    resolution
  }: {
    id: string;
    resolution: "fixed" | "accepted";
  }) => decideDefect(kbId.value, id, resolution),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
const alignmentPhraseAction = useMutation({
  mutationFn: ({
    id,
    property,
    direction,
    marks
  }: {
    id: string;
    property: string | null;
    direction: "forward" | "reverse";
    marks: AlignmentMarks | null;
  }) => decideAlignmentPhrase(kbId.value, id, property, direction, marks),
  // 202：判定收下了，类型化图谱在后台重算；算完 review / graph 事件会把
  // 队列和图刷一遍，这里只告诉人「已保存」，不编一个数字出来
  onSuccess: () => toast.success(S.review.alignmentAccepted),
  onError: (e: unknown) => toast.error(alignmentErrorMessage(e)),
  onSettled: invalidate
});
const alignmentRuleAction = useMutation({
  mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
    decideAlignmentRule(kbId.value, id, approve),
  onSuccess: () => toast.success(S.review.alignmentRuleAccepted),
  onError: (e: unknown) => toast.error(alignmentErrorMessage(e)),
  onSettled: invalidate
});
const alignmentKindWordAction = useMutation({
  mutationFn: ({ kindWord, cls }: { kindWord: string; cls: string | null }) =>
    decideAlignmentKindWord(kbId.value, kindWord, cls),
  // 409 说的是冲突，不说哪个操作冲突。busy → agent 正在裁那一类，别从文案倒推
  onError: (e: unknown) => toast.error(alignmentErrorMessage(e)),
  onSettled: invalidate
});
const errataAction = useMutation({
  mutationFn: ({ id, approve }: { id: string; approve: boolean }) =>
    decideErrata(kbId.value, id, approve),
  onSuccess: () => toast.success(S.review.errataDecided),
  onError: (e: unknown) =>
    toast.error(e instanceof Error ? e.message : String(e)),
  onSettled: invalidate
});
const violationAction = useMutation({
  mutationFn: ({
    id,
    resolution,
    opts
  }: {
    id: string;
    resolution: ViolationResolution;
    opts?: DecideOpts;
  }) => decideViolation(kbId.value, id, resolution, opts ?? {}),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
// 检查是同步的纯计算,所以直接 mutate 不排队。跑完把报告留在按钮旁边——
// **零和零不一样**：没有公理时要说「无从判起」,不能说「未发现矛盾」
const runCheck = useMutation({
  mutationFn: () => runConsistencyCheck(kbId.value),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
const revert = useMutation({
  mutationFn: (mergeId: string) => revertMerge(kbId.value, mergeId),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
const conflictAction = useMutation({
  mutationFn: ({
    id,
    action,
    closeAt,
    closeAtPrecision
  }: {
    id: string;
    action: "close" | "keep" | "reject_new";
    closeAt?: string;
    closeAtPrecision?: string;
  }) =>
    resolveConflict(kbId.value, id, {
      action,
      close_at: closeAt,
      close_at_precision: closeAtPrecision
    }),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});
const closeFactAction = useMutation({
  mutationFn: ({
    id,
    validTo,
    precision
  }: {
    id: string;
    validTo: string;
    precision: string;
  }) => closeFact(kbId.value, id, validTo, precision),
  onError: (e: Error) => toast.error(e.message),
  onSettled: invalidate
});

const dupItems = computed(() => rows.value as ReviewItem[]);
const pendingItems = computed(() => rows.value as PendingFactItem[]);
const factItems = computed(() => rows.value as FactReviewItem[]);
const conflictItems = computed(() => rows.value as ConflictItem[]);
const violationItems = computed(() => rows.value as AxiomViolation[]);
const defectItems = computed(() => rows.value as OntologyDefect[]);
const alignmentItems = computed(() => rows.value as AlignmentItem[]);
const errataItems = computed(() => rows.value as ErrataItem[]);
const mergeItems = computed(() => rows.value as MergeLog[]);
const agentItems = computed(() => rows.value as AgentDecision[]);
const historyEvents = computed(
  () => (history.data.value?.events ?? []) as ReviewHistoryEvent[]
);
const historyTotal = computed(() => history.data.value?.total ?? 0);
const pagedTotal = computed(() =>
  active.value === "agent"
    ? agentRows.value
    : active.value === "decisions"
      ? historyTotal.value
      : counts.value[active.value]
);

// agent 正在裁的一对（0025）：任务在跑、这一对标着 adjudicating。批量选页时跳过它们
const lockedByAgent = (item: ReviewItem) =>
  !!reviewData.value?.counts.agent_running && item.stage === "adjudicating";
const selectable = computed(() =>
  dupItems.value.filter(d => !lockedByAgent(d))
);

function select(s: Sel) {
  sel.value = s;
  page.value = 0;
}

function setPicked(on: boolean, id?: string) {
  const next = new Set(picked.value);
  if (on && id) next.add(id);
  else if (!on && id) next.delete(id);
  else if (on) for (const d of selectable.value) next.add(d.id);
  else next.clear();
  picked.value = next;
}
</script>

<template>
  <div class="knowledge-page h-full overflow-y-auto overscroll-contain px-8 py-6">
    <!-- reskin-b4：整页单列滚动（检索/文库同款根）；rail 左栏退役，档位选择
         上移为队列过滤卡。kb 页在若依 Layout 的 AppMain 内渲染
         （h-full 即内容窗口高，与批次 1-3 六页同根）。注意：本注释必须留在
         根 div 内——根级注释会让组件根变 Fragment，AppMain 的
         <Transition mode="out-in"> 对 Fragment 根离场卡死后吞掉 enter，
         菜单跳转渲染空白主区（dev 注释是真实节点，prod 丢弃故不复现）。 -->
    <div>
      <!-- 队列过滤卡：页头合一（2026-11-24 留档清偿批，图谱/本体页同家族）——
           独立页头带删除，标题=审核 + kb chip=库名收进卡头首行（border-b 分隔，
           单 h1 补位；页内无现成队列总计数变量，右段按 YAGNI 不加）；
           14 档药丸原样入卡身。 -->
      <div class="card mb-[15px]">
        <!-- 卡头首行：标题 + kb chip -->
        <div class="flex items-center gap-2 border-b border-line pb-2">
          <h1 class="text-title font-semibold text-ink">{{ S.review.title }}</h1>
          <span
            class="truncate rounded-full border border-line bg-fill px-2 py-0.5 text-aux text-ink-2"
          >
            {{ kb?.name }}
          </span>
        </div>

        <!-- 队列过滤条（重造要素二）：14 档一排药丸——总览 / 九队列 + agent /
             历史（台账 + 合并史）/ 数据映射（外链）。计数徽章 badge-soft 读
             服务端 COUNT（SSE 推动刷新）；agent 跑动中的琥珀脉冲点保留。
             选中 primary-soft（scoped 补皮），bg-primary-soft 类同时留在
             元素上——w1-review spec 以它为选中锚 -->
        <div class="flex flex-wrap items-center gap-1.5 pt-3">
        <UPill
          :active="active === 'overview'"
          :class="['queue-pill', active === 'overview' && 'bg-primary-soft']"
          @click="select('overview')"
        >
          {{ S.review.railOverview }}
        </UPill>
        <span class="mx-1 h-4 w-px shrink-0 bg-line-strong" aria-hidden />
        <UPill
          v-for="q in QUEUE_ORDER"
          :key="q"
          :active="active === q"
          :class="['queue-pill', active === q && 'bg-primary-soft']"
          @click="select(q)"
        >
          <span class="truncate">{{ RAIL_LABEL[q] }}</span>
          <span
            v-if="counts[q] > 0"
            class="badge-soft badge-soft-info tabular-nums"
          >
            {{ counts[q] }}
          </span>
        </UPill>
        <!-- agent 的队列（0025）：徽标是等人回答的建议数 -->
        <UPill
          :active="active === 'agent'"
          :class="['queue-pill', active === 'agent' && 'bg-primary-soft']"
          @click="select('agent')"
        >
          <span class="truncate">{{ S.review.railAgent }}</span>
          <span
            v-if="agentRunning"
            class="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-warning"
          />
          <span
            v-if="counts.agent > 0"
            class="badge-soft badge-soft-info tabular-nums"
          >
            {{ counts.agent }}
          </span>
        </UPill>
        <span class="mx-1 h-4 w-px shrink-0 bg-line-strong" aria-hidden />
        <!-- 历史组：台账 + 合并史 -->
        <span class="mr-1 shrink-0 text-aux font-medium text-ink-3">
          {{ S.review.tabHistory }}
        </span>
        <UPill
          :active="active === 'decisions'"
          :class="['queue-pill', active === 'decisions' && 'bg-primary-soft']"
          @click="select('decisions')"
        >
          {{ S.review.railDecisions }}
        </UPill>
        <UPill
          :active="active === 'merges'"
          :class="['queue-pill', active === 'merges' && 'bg-primary-soft']"
          @click="select('merges')"
        >
          <span class="truncate">{{ S.review.railMerges }}</span>
          <span
            v-if="counts.merges > 0"
            class="badge-soft badge-soft-info tabular-nums"
          >
            {{ counts.merges }}
          </span>
        </UPill>
        <span class="mx-1 h-4 w-px shrink-0 bg-line-strong" aria-hidden />
        <!-- 数据映射：两组都不属的外链。计数留着——收件箱该说「有几条等你」；
             无内容区标题，ⓘ 留在行内（旧仓第 5 轮例外，同位） -->
        <RouterLink class="pill" :to="`/kb/${kbId}/mappings`">
          <span class="truncate">{{ S.review.railMappings }}</span>
          <el-tooltip :content="S.review.railMappingsHint" placement="top">
            <span
              class="inline-grid size-[14px] shrink-0 cursor-help place-items-center rounded-full border border-ink-3 text-aux text-ink-3"
            >i</span>
          </el-tooltip>
          <span
            v-if="counts.mappings > 0"
            class="badge-soft badge-soft-info tabular-nums"
          >
            {{ counts.mappings }}
          </span>
        </RouterLink>
        </div>
      </div>

      <!-- 一次只显示选中的一类，单一分页 -->
      <Loading v-if="review.isLoading.value && isQueueSel" />
      <p v-if="review.isError.value" class="text-body text-danger">
        {{ String(review.error.value) }}
      </p>

      <section v-if="reviewData || sel === 'decisions' || sel === 'overview'">
        <!-- 档标题行：队列过滤条已承担导航，这里降一档（text-title）保留
             档名 + ⓘ 悬停短句 + 白纸黑字的长版 hint（信息零丢失） -->
        <div class="mb-4">
          <div class="flex items-center gap-2">
            <h2 class="text-title font-bold text-ink">
              {{ SECTION[active].title }}
            </h2>
            <!-- ⓘ 悬停短句（railXxxHint）：第 4 轮走查从左栏行内移来（旧仓同位） -->
            <el-tooltip
              v-if="SECTION[active].infoHint"
              :content="SECTION[active].infoHint"
              placement="right"
            >
              <span
                class="inline-grid size-[14px] shrink-0 cursor-help place-items-center rounded-full border border-ink-3 text-aux text-ink-3"
              >i</span>
            </el-tooltip>
          </div>
          <p v-if="SECTION[active].hint" class="mt-1 text-sub text-ink-2">
            {{ SECTION[active].hint }}
          </p>
        </div>

        <!-- 空态：整个待办全清 vs 单类清空（公理/缺陷两档除外） -->
        <div
          v-if="isQueueSel && active !== 'violations' && active !== 'defects' && counts[active] === 0"
          class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
        >
          {{ queueEmpty ? S.review.empty : S.review.categoryEmpty }}
        </div>

        <!-- 总览 -->
        <template v-if="active === 'overview'">
          <Loading v-if="summary.isLoading.value" />
          <p v-else-if="summary.isError.value" class="text-body text-danger">
            {{ String(summary.error.value) }}
          </p>
          <ReviewOverview
            v-else-if="summary.data.value"
            :summary="summary.data.value"
            :governance="kb?.governance ?? kbDetail.data.value?.governance ?? false"
            :settings-to="`/kb/${kbId}/settings`"
            @pick="(q: string) => select(q as Sel)"
          />
        </template>

        <!-- 重复项（带类型筛选 + 批量裁决 #428） -->
        <template v-if="active === 'duplicates' && counts.duplicates > 0">
          <div class="space-y-3">
            <div class="flex flex-wrap items-center gap-3">
              <div class="flex gap-1 rounded-ctl bg-fill p-1">
                <button
                  v-for="t in ([
                    { v: 'any', label: S.review.typesAny, n: counts.duplicates },
                    { v: 'same', label: S.review.typesSame, n: dupSame },
                    { v: 'conflict', label: S.review.typesConflict, n: dupConflict }
                  ] as const)"
                  :key="t.v"
                  type="button"
                  class="flex items-center justify-center gap-1 rounded-ctl px-2 py-1 text-aux font-medium transition-colors"
                  :class="types === t.v ? 'bg-card text-primary shadow-card' : 'text-ink-2 hover:text-ink'"
                  @click="((types = t.v as ReviewTypeFilter), (page = 0))"
                >
                  {{ t.label }}
                  <span class="tabular-nums">{{ t.n }}</span>
                </button>
              </div>
              <!-- 批量：勾选这一页的，一个动作裁一批。按钮只在有选中时出现 -->
              <el-checkbox
                v-if="selectable.length > 0 && canDecide"
                class="ml-auto"
                :model-value="selectable.length > 0 && selectable.every(d => picked.has(d.id))"
                :disabled="batch.isPending.value"
                :label="S.review.selectPage"
                @change="(v: unknown) => setPicked(!!v)"
              />
              <template v-if="picked.size > 0">
                <span class="tabular-nums text-sub text-ink-2">
                  {{ S.review.selected(picked.size) }}
                </span>
                <!-- 一批一句理由（0026）：这一批为什么一起这么定 -->
                <el-input
                  v-model="batchWhy"
                  size="small"
                  style="width: 11rem"
                  :placeholder="S.review.rationalePlaceholder"
                  :disabled="batch.isPending.value"
                />
                <el-button
                  size="small"
                  :disabled="batch.isPending.value"
                  @click="batch.mutate({ ids: [...picked], action: 'keep', rationale: batchWhy })"
                >
                  {{ S.review.keepSelected }}
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  :disabled="batch.isPending.value"
                  @click="batch.mutate({ ids: [...picked], action: 'merge', rationale: batchWhy })"
                >
                  {{ S.review.mergeSelected }}
                </el-button>
              </template>
            </div>
            <div
              v-if="dupItems.length === 0"
              class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
            >
              {{ S.review.typesEmpty }}
            </div>
            <DuplicateCard
              v-for="item in dupItems"
              :key="item.id"
              :item="item"
              :locked="lockedByAgent(item)"
              :picked="picked.has(item.id)"
              :busy="
                (decide.isPending.value && decide.variables.value?.id === item.id) ||
                (batch.isPending.value && picked.has(item.id))
              "
              :can-decide="canDecide"
              @pick="(on: boolean) => setPicked(on, item.id)"
              @decide="
                (action: 'merge' | 'keep', rationale?: string) =>
                  decide.mutate({ id: item.id, action, rationale })
              "
            />
          </div>
        </template>

        <template v-if="active === 'conflicts' && counts.conflicts > 0">
          <div class="space-y-3">
            <ConflictRow
              v-for="c in conflictItems"
              :key="c.id"
              :conflict="c"
              :busy="conflictAction.isPending.value && conflictAction.variables.value?.id === c.id"
              :can-decide="canDecide"
              @resolve="
                (action: 'close' | 'keep' | 'reject_new', closeAt?: string, closeAtPrecision?: string) =>
                  conflictAction.mutate({ id: c.id, action, closeAt, closeAtPrecision })
              "
            />
          </div>
        </template>

        <!-- 未再提及：Reject（抽取错误）或 Close at date（这事结束了） -->
        <template v-if="active === 'unconfirmed' && counts.unconfirmed > 0">
          <div class="space-y-3">
            <UnconfirmedRow
              v-for="f in factItems"
              :key="f.id"
              :fact="f"
              :busy="
                (factAction.isPending.value && factAction.variables.value?.id === f.id) ||
                (closeFactAction.isPending.value && closeFactAction.variables.value?.id === f.id)
              "
              :can-decide="canDecide"
              @reject="factAction.mutate({ id: f.id, action: 'reject' })"
              @close="
                (validTo: string, precision: string) =>
                  closeFactAction.mutate({ id: f.id, validTo, precision })
              "
            />
          </div>
        </template>

        <!-- 等人点头的事实（0015）：确认进账本，驳回记进 rejected_facts -->
        <template v-if="active === 'pending' && counts.pending > 0">
          <div class="space-y-3">
            <PendingFactRow
              v-for="f in pendingItems"
              :key="f.id"
              :fact="f"
              :busy="pendingAction.isPending.value && pendingAction.variables.value?.id === f.id"
              :can-decide="canDecide"
              @confirm="pendingAction.mutate({ id: f.id, action: 'confirm' })"
              @reject="pendingAction.mutate({ id: f.id, action: 'reject' })"
            />
          </div>
        </template>

        <template v-if="active === 'lowconf' && counts.lowconf > 0">
          <div class="space-y-3">
            <FactRow
              v-for="f in factItems"
              :key="f.id"
              :fact="f"
              :busy="factAction.isPending.value && factAction.variables.value?.id === f.id"
              :can-decide="canDecide"
              @confirm="factAction.mutate({ id: f.id, action: 'confirm' })"
              @reject="factAction.mutate({ id: f.id, action: 'reject' })"
            />
          </div>
        </template>

        <!-- 本体缺陷（defects）：两出路 -->
        <template v-if="active === 'defects'">
          <div class="space-y-3">
            <div
              v-if="counts.defects === 0"
              class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
            >
              {{ S.review.categoryEmpty }}
            </div>
            <DefectRow
              v-for="d in defectItems"
              :key="d.id"
              :defect="d"
              :busy="defectAction.isPending.value && defectAction.variables.value?.id === d.id"
              :can-decide="canDecide"
              @decide="
                (resolution: 'fixed' | 'accepted') =>
                  defectAction.mutate({ id: d.id, resolution })
              "
            />
          </div>
        </template>

        <!-- 勘误（errata）：批了就执行，否了只记 -->
        <template v-if="active === 'errata'">
          <div class="space-y-3">
            <div
              v-if="counts.errata === 0"
              class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
            >
              {{ S.review.categoryEmpty }}
            </div>
            <ErrataRow
              v-for="i in errataItems"
              :key="i.id"
              :item="i"
              :busy="errataAction.isPending.value && errataAction.variables.value?.id === i.id"
              :can-decide="canDecide"
              @decide="(approve: boolean) => errataAction.mutate({ id: i.id, approve })"
            />
          </div>
        </template>

        <!-- 对齐（alignment）：短语绑定 / 蕴含规则 / 类别词，三种行三种表单 -->
        <template v-if="active === 'alignment'">
          <div class="space-y-3">
            <div
              v-if="counts.alignment === 0"
              class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
            >
              {{ S.review.categoryEmpty }}
            </div>
            <template
              v-for="i in alignmentItems"
              :key="'id' in i ? i.id : i.kind_word"
            >
              <AlignmentPhraseRow
                v-if="i.kind === 'phrase'"
                :item="i"
                :properties="ontology.data.value?.relation_types ?? []"
                :busy="alignmentPhraseAction.isPending.value && alignmentPhraseAction.variables.value?.id === i.id"
                :can-decide="canDecide"
                @decide="
                  (property: string | null, direction: 'forward' | 'reverse', marks: AlignmentMarks | null) =>
                    alignmentPhraseAction.mutate({ id: i.id, property, direction, marks })
                "
              />
              <AlignmentRuleRow
                v-else-if="i.kind === 'rule'"
                :item="i"
                :busy="alignmentRuleAction.isPending.value && alignmentRuleAction.variables.value?.id === i.id"
                :can-decide="canDecide"
                @decide="(approve: boolean) => alignmentRuleAction.mutate({ id: i.id, approve })"
              />
              <AlignmentKindWordRow
                v-else
                :item="i"
                :classes="ontology.data.value?.entity_types ?? []"
                :busy="alignmentKindWordAction.isPending.value && alignmentKindWordAction.variables.value?.kindWord === i.kind_word"
                :can-decide="canDecide"
                @decide="(cls: string | null) => alignmentKindWordAction.mutate({ kindWord: i.kind_word, cls })"
              />
            </template>
          </div>
        </template>

        <!-- 公理违规（violations）：检查按钮在这一档里，报告留在按钮旁边 -->
        <template v-if="active === 'violations'">
          <div class="space-y-3">
            <div class="flex items-center gap-3">
              <el-button
                size="small"
                :disabled="runCheck.isPending.value"
                @click="runCheck.mutate()"
              >
                {{ runCheck.isPending.value ? S.review.checking : S.review.runCheck }}
              </el-button>
              <span v-if="runCheck.data.value" class="text-sub text-ink-2">
                <!-- 三种结果说三句话。**`found` 不是要报的数**：
                    重跑会把已裁决的那些重新算出来 -->
                {{
                  runCheck.data.value.predicates_with_axioms === 0
                    ? S.review.checkNoAxioms
                    : runCheck.data.value.inserted > 0
                      ? S.review.checkFound(runCheck.data.value.inserted)
                      : runCheck.data.value.found > 0
                        ? S.review.checkNothingNew
                        : S.review.checkClean(runCheck.data.value.edges)
                }}
              </span>
            </div>
            <div
              v-if="counts.violations === 0 && !runCheck.data.value"
              class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
            >
              {{ S.review.checkNeverRun }}
            </div>
            <div
              v-for="v in violationItems"
              :key="v.id"
              :class="v.id === itemParam ? 'rounded-card ring-1' : ''"
              :style="v.id === itemParam ? { boxShadow: '0 0 0 1px var(--color-warning)' } : undefined"
            >
              <ViolationRow
                :violation="v"
                :busy="violationAction.isPending.value && violationAction.variables.value?.id === v.id"
                :can-decide="canDecide"
                @decide="
                  (resolution: ViolationResolution, opts?: DecideOpts) =>
                    violationAction.mutate({ id: v.id, resolution, opts })
                "
                @duplicates="select('duplicates')"
                @ontology="router.push('/kb/' + kbId + '/ontology')"
              />
            </div>
          </div>
        </template>

        <!-- agent（0025）：建议等人答，自动裁的可撤 -->
        <template v-if="active === 'agent'">
          <div
            v-if="agentRows === 0"
            class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
          >
            {{ S.review.agentEmpty }}
          </div>
          <div v-else class="space-y-2">
            <AgentRow
              v-for="d in agentItems"
              :key="d.id"
              :d="d"
              :busy="answerAgent.isPending.value && answerAgent.variables.value?.id === d.id"
              :can-decide="canDecide"
              @answer="
                (action: 'merge' | 'keep' | 'revert', rationale?: string) =>
                  answerAgent.mutate({ id: d.id, action, rationale })
              "
            />
          </div>
        </template>

        <template v-if="active === 'merges'">
          <div
            v-if="counts.merges === 0"
            class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
          >
            {{ S.review.historyEmpty }}
          </div>
          <div v-else class="space-y-2">
            <MergeRow
              v-for="m in mergeItems"
              :key="m.id"
              :merge="m"
              :busy="revert.isPending.value && revert.variables.value === m.id"
              :can-decide="canDecide"
              @revert="revert.mutate(m.id)"
            />
          </div>
        </template>

        <template v-if="active === 'decisions'">
          <Loading v-if="history.isLoading.value" />
          <div
            v-else-if="historyTotal === 0"
            class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
          >
            {{ S.review.decisionsEmpty }}
          </div>
          <div v-else class="space-y-2">
            <DecisionRow v-for="e in historyEvents" :key="e.id" :e="e" />
          </div>
        </template>

        <!-- 单一分页 -->
        <UPager
          v-if="active !== 'overview'"
          :total="pagedTotal"
          :page-size="PAGE_SIZE[active as Exclude<Sel, 'overview'>]"
          :page="page"
          @page="(p: number) => (page = p)"
        />
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 队列过滤条（reskin-b4）：UPill 的基态皮 .pill 在 utopia-pages.css（未分层，
   恒压 tailwind utilities 层）——选中态的 primary-soft 底/主色深档字在这里补，
   并一并压过 .pill:hover（免得悬停时选中片闪回 border 档）。bg-primary-soft
   工具类同时留在选中元素上：w1-review spec 以它为选中锚（实际着色以本条为准，
   未分层的 .pill 背景会压过 utilities 层的它） */
.queue-pill.is-on,
.queue-pill.is-on:hover {
  color: var(--color-primary-text);
  background: var(--color-primary-soft);
}
</style>
