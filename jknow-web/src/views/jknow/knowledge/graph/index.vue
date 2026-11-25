<!-- 图谱页（对照旧仓 pages/Graph.tsx，W1：数据正确 + 静态视觉对齐；W3/任务 9：交互全量）。
     sigma + graphology：胶囊节点/内联标签（drawNodeLabel 规格）、边色映射
     （graphVisuals 的 live 调色板）、平行边扇开、世界坐标网格；
     FA2 worker 布局动画（stabilizing ~2.5s）+ 拖拽钉位（拖哪钉哪）；
     hover 邻居聚焦 + 其余按 focusK 渐变压暗（graphCanvas 状态表）；
     派生边入场时序/呼吸/开关渐变；边悬停并读逆关系说法；
     面板事实行指/钉与画布边双向联动（panelFocus 优先级）；
     播放淡入（时间岛推进时新元素 320ms 渐入）；
     底部时间岛（TimeScrubber）做双时间轴过滤；实体侧栏（EntityPanel）读面。
     WebGL 拾取合成事件打不进（Playwright 实证）：hover 态在 dev 由
     window.__graphHover 句柄驱动，验收走深链 + DOM 可点面 + 人工走查。 -->
<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { useResizeObserver } from "@vueuse/core";
import Graphology from "graphology";
import { circular, circlepack } from "graphology-layout";
import forceAtlas2 from "graphology-layout-forceatlas2";
import FA2Layout from "graphology-layout-forceatlas2/worker";
import Sigma from "sigma";
import { EdgeRectangleProgram } from "sigma/rendering";
import EdgeCurveProgram from "@sigma/edge-curve";
import SearchIcon from "~icons/lucide/search";
import OrbitIcon from "~icons/lucide/orbit";
import CircleDashedIcon from "~icons/lucide/circle-dashed";
import GrapeIcon from "~icons/lucide/grape";
import ZoomInIcon from "~icons/lucide/zoom-in";
import ZoomOutIcon from "~icons/lucide/zoom-out";
import Loader2Icon from "~icons/lucide/loader-2";
import Maximize2Icon from "~icons/lucide/maximize-2";
import WaypointsIcon from "~icons/lucide/waypoints";
import GitCompareIcon from "~icons/lucide/git-compare";
import {
  graphOverview,
  graphNeighborhood,
  searchEntities
} from "@/api/knowledge/graph";
import { readiness as readinessApi } from "@/api/knowledge/kbs";
import { me as meApi } from "@/api/knowledge/auth";
import type { GraphEdge, GraphNode } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { nextStep } from "@/domain/nextStep";
import { highlightSegments } from "@/utils/highlightSegments";
import { zhEdge } from "./predicate-zh";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbScope } from "@/composables/useKbScope";
import useSettingsStore from "@/store/modules/settings";
import {
  EDGE,
  EDGE_CONTEST,
  EDGE_DERIVED,
  EDGE_DERIVED_DIM,
  EDGE_DIM,
  EDGE_FOCUS,
  EDGE_FOCUS_CONTEST,
  EDGE_FOCUS_DERIVED,
  EDGE_INFERRED,
  STROKE_WHITE,
  TRANSPARENT,
  drawWorldGrid,
  refreshPalette,
  lerpColor,
  MUTED_SHELL,
  NODE_CORE_BASE,
  NODE_SHELL_BASE,
  NODE_BORDER_BASE,
  HOVER_MUTE,
  RING_SELECT
} from "@/domain/pages/graphVisuals";
import {
  NODE_TYPE_SHELL,
  NODE_TYPE_SQUARE,
  sigmaOptions,
  withTopLayer,
  drawLast,
  attachDrag,
  selectedNode,
  hoveredNode,
  neighborNode,
  mutedNode,
  softMutedNode,
  dimmedNode,
  deferToHoverLayer
} from "@/domain/pages/graphCanvas";
import {
  panelFocus,
  focusRampTarget,
  focusRampStep,
  resolveNodeRole,
  derivedPhase,
  triangleWave,
  mergeAlsoLabels,
  pinnedPosition,
  type XY
} from "@/domain/pages/graphInteraction";
import { layOutParallelEdges, computeActiveAt } from "./parallel-edges";
import { TimeScrubber } from "./time-scrubber";
import { EntityPanel } from "./entity-panel";
import {
  UPill,
  ToolButton,
  ToolDivider,
  ToolTower,
  URow,
  CanvasLoading
} from "@/components/knowledge/kit";
defineOptions({ name: "UtopiaGraphPage" });

const LEGEND_MAX = 6;
/* 画多少个节点的可选档位。最大值与后端 GRAPH_NODE_CAP_MAX 对齐 */
const NODE_BUDGETS: number[] = [150, 300, 600, 1000];
const DAY_MS = 24 * 3600 * 1000;
/** 小库（≤100）聚焦态不压暗（走查微调 4）——静态态用它决定常显标签集 */
const SMALL_LIB = 100;
/* ---------- 交互节奏常数（旧仓逐值） ---------- */
/** 播放中新元素的淡入时长 */
const FADE_MS = 320;
/** 聚焦压暗的过渡时长（走查微调 7）：与播放淡入同一量级、略快——它是"对焦" */
const FOCUS_RAMP_MS = 240;
// 呼吸周期。动画不是为了好看，是因为静态的一个色差在几百条边里根本注意不到
const DERIVED_PULSE_MS = 2200;
// 超过这个数就只上色不动画。**写出来而不是悄悄降级**：每帧重算几千条边的颜色，
// 换来的是拖不动图，而那时候用户要的是能拖得动
const DERIVED_ANIMATE_MAX = 400;
// 开关的淡入淡出时长。**比 FADE_MS(320) 略长**：播放淡入是一批边陆续到位，
// 这个是一整批边同时进出，走慢一点才看得清「那批金线是一起退场的」
const DERIVED_TOGGLE_MS = 420;
/* 推出来的边**比事实晚一点进来**，然后整体淡入（旧仓 DERIVE_SETTLE/FADE） */
const DERIVE_SETTLE_MS = 500;
const DERIVE_FADE_MS = 620;

/* 幽灵边（没落地的派生）同一个色相往 EDGE_DIM 混——sigma 的边着色器在预乘混合
   下 alpha 压不暗边，暗度必须编码进 RGB——所以是函数，切主题后重算（旧仓逐字） */
const edgeGhost = () => lerpColor(EDGE_FOCUS_CONTEST, EDGE_DIM, 0.55);
const edgeGhostFocus = () => lerpColor(EDGE_FOCUS_CONTEST, EDGE_DIM, 0.2);

/* ---------- 数据与 URL 双向同步 ---------- */

const route = useRoute();
const router = useRouter();
const kbStore = useKbContextHook();
// KbScope 对等物（每个 kb 页面 setup 调一次）：载详情/记上下文/重组菜单
const scope = useKbScope();
const kbId = scope.kbId;
const kb = computed(() => kbStore.kbDetail ?? scope.kb.value);

const entityParam = computed(() =>
  typeof route.query.entity === "string" ? route.query.entity : ""
);
const selected = ref<string | null>(entityParam.value || null);
const focusEntity = ref<string | null>(
  (typeof route.query.focus === "string" && route.query.focus) ||
    entityParam.value ||
    null
);
/** null = 全时段；数值 = as-of 时刻(ms)。默认 as-of 今天（时态平台默认呈现"现在的世界"） */
const timeT = ref<number | null>((() => {
  if (route.query.at === "all") return null;
  if (typeof route.query.at === "string") {
    const t = Date.parse(route.query.at);
    if (!Number.isNaN(t)) return t;
  }
  return Date.now();
})());
const playing = ref(false);
/** 正在退场的实体。面板不能一取消选中就卸载 */
const exiting = ref<string | null>(null);

// 地址栏 → 画面：这一半是给后退/前进用的
watch(entityParam, v => (selected.value = v || null));

// 画面 → 地址栏（replace 不是 push：点节点是浏览不是导航）。播放中整段跳过
function syncUrl() {
  if (playing.value) return;
  const at =
    timeT.value === null
      ? "all"
      : // 停在「现在」就不写。否则每次打开都在地址栏拖一串今天的日期，
        // 而那本来就是默认值
        Math.abs(timeT.value - Date.now()) < DAY_MS
        ? undefined
        : new Date(timeT.value).toISOString().slice(0, 10);
  const next: Record<string, string> = {};
  if (selected.value) next.entity = selected.value;
  if (focusEntity.value && focusEntity.value !== selected.value)
    next.focus = focusEntity.value;
  if (at) next.at = at;
  router.replace({ path: `/kb/${kbId.value}/graph`, query: next });
}

function deselect() {
  const cur = selected.value;
  if (!cur) return;
  exiting.value = cur;
  selected.value = null;
  syncUrl();
  window.setTimeout(() => (exiting.value = null), 170);
}

/* ---------- 空状态与统计 ---------- */

const me = useQuery({ queryKey: ["me"], queryFn: meApi });
const readiness = useQuery({
  queryKey: computed(() => ["readiness", kbId.value]),
  queryFn: () => readinessApi(kbId.value),
  enabled: computed(() => !!kbId.value),
  staleTime: 10_000
});
const step = computed(() =>
  nextStep(readiness.data.value, {
    kbId: kbId.value,
    isAdmin: !!me.data.value?.is_admin,
    canUpload: kb.value?.my_role !== "viewer"
  })
);

/** 画多少个。进 queryKey——不进的话调了档位不会重新取数 */
const nodeBudget = ref<number>(NODE_BUDGETS[0]);
const data = useQuery<{
  nodes: GraphNode[];
  edges: GraphEdge[];
  total_nodes?: number;
  total_edges?: number;
}>({
  queryKey: computed(() => [
    "graph",
    kb.value?.id,
    focusEntity.value,
    nodeBudget.value
  ]),
  queryFn: () =>
    focusEntity.value
      ? graphNeighborhood(kb.value!.id, focusEntity.value)
      : graphOverview(kb.value!.id, nodeBudget.value),
  enabled: computed(() => !!kb.value)
});

// 全图模式走全库实体搜索；子图模式只在已加载的子图内客户端过滤
const inSubgraph = computed(() => !!focusEntity.value);
const searchInput = ref("");
const searchQ = ref("");
watch(
  () => searchInput.value,
  v => (searchQ.value = v.trim())
);
const searchLimit = ref(10);
watch(searchQ, () => (searchLimit.value = 10));
const candidates = useQuery({
  queryKey: computed(() => [
    "entitySearch",
    kb.value?.id,
    searchQ.value,
    searchLimit.value
  ]),
  queryFn: () => searchEntities(kb.value!.id, searchQ.value, searchLimit.value),
  enabled: computed(
    () => !!kb.value && searchQ.value.length > 0 && !inSubgraph.value
  ),
  placeholderData: (prev: unknown) => prev as never
});
const subgraphHits = computed(() => {
  if (!inSubgraph.value || !searchQ.value || !data.data.value) return [];
  const q = searchQ.value.toLowerCase();
  return data.data.value.nodes
    .filter(
      n =>
        n.name.toLowerCase().includes(q) ||
        n.disambiguator?.toLowerCase().includes(q)
    )
    .slice(0, 10);
});
const searchHits = computed(() =>
  inSubgraph.value ? subgraphHits.value : (candidates.data.value?.entities ?? [])
);

/* 实体名按命中词切段（reskin-b4 归一到 highlightSegments）：hit 段渲染成
   <mark>——与检索结果卡 .result-quote mark 同一形态（主色 glow 底+主色深档字，
   蓝=检索/浮搜命中；琥珀 warning 系是 chat 引用预览/docs 查看器的引文定位——
   两色区分是设计意图，见规格 §3），切段语义（长词优先/转义/全命中都标）与
   检索/对话引用/docs 同一份实现。只读 searchQ 做展示切分，不碰上面的过滤 */

/* ---------- 图例（类型显隐） ---------- */

const hiddenTypes = ref(new Set<string>());
const showDerived = ref(true);
const legendQ = ref("");

const types = computed(() => {
  const map = new Map<
    string,
    { label: string; color: string; shape: string; count: number }
  >();
  for (const n of data.data.value?.nodes ?? []) {
    const key = n.type_key ?? "";
    const cur = map.get(key);
    if (cur) cur.count++;
    else
      map.set(key, {
        label: n.type_label ?? S.graph.untyped,
        color: n.color,
        shape: n.shape,
        count: 1
      });
  }
  return [...map.entries()].sort(
    (a, b) => b[1].count - a[1].count || a[1].label.localeCompare(b[1].label)
  );
});
const legendShown = computed(() => types.value.slice(0, LEGEND_MAX));
const legendRest = computed(() => types.value.slice(LEGEND_MAX));
const legendRestFiltered = computed(() =>
  legendRest.value.filter(([, t]) =>
    t.label.toLowerCase().includes(legendQ.value.toLowerCase())
  )
);
const derivedCount = computed(
  () => (data.data.value?.edges ?? []).filter(e => e.derived).length
);
function toggleHidden(key: string) {
  const next = new Set(hiddenTypes.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  hiddenTypes.value = next;
  sigmaRef.value?.refresh();
}

/* ---------- 交互态（reducer/事件共读的那一帧） ---------- */

const sigmaRef = ref<Sigma | null>(null);
/** 画布上正被指着的节点（sigma enterNode / 面板 pointEntity） */
const hoverNode = ref<string | null>(null);
/** 画布上正被指着的边：把并进来的逆关系说法亮出来 */
const hoverEdgeId = ref<string | null>(null);
/** 面板指着的那条事实（指针停在事实行上） */
const panelEdge = ref<string | null>(null);
/** 点了一条事实：钉住那条边。记着是在哪个实体的面板里钉的：换了实体就不作数 */
const pin = ref<{ entity: string; fact: string } | null>(null);
const pinnedFact = computed(() =>
  pin.value && pin.value.entity === selected.value ? pin.value.fact : null
);
/** 面板此刻在指哪条边（旧仓 panelFocus：指着优先，钉住次之，指到节点时让开） */
const panelEdgeFocus = computed(() =>
  panelFocus(panelEdge.value, pin.value, hoverNode.value, selected.value)
);
/** 聚焦强度 k（走查微调 7）：进出聚焦用 rAF 渐变，reducer 按 k 插值 dim/boost */
const focusK = ref(0);
/** 播放淡入表：本轮新激活的节点/边 id → 激活时刻（rAF 循环驱动至到位） */
const fadeMap = new Map<string, number>();
/** 推出来的边：演完之前不出现（"开关是显不显示，这个是演到了没有"） */
const derivedRevealed = ref(false);
/** 开关/入场的渐变：{ 起始时刻, 朝哪个方向 }；null = 没有过渡在飞 */
const derivedToggle = ref<{ at: number; on: boolean } | null>(null);
/** 国内风收口（走查 A3）：边标签显示开关——默认隐藏（off），开=全量显示中文
 *  标签。hover/钉住单条边的显示走 edgeChip 浮层，与本开关互斥（开关开着时
 *  浮层不出现，避免重复）。renderEdgeLabels 由它作为唯一事实源驱动 */
const showAllEdgeLabels = ref(false);
/** 相机变动记号：边标签浮层（edgeChip）按它重算视口坐标 */
const camTick = ref(0);

/* 开关是 renderEdgeLabels 的唯一事实源（zoom 联动已移除，国内风收口走查 A3） */
watch(showAllEdgeLabels, v => {
  sigmaRef.value?.setSetting("renderEdgeLabels", v);
});

/* 主题切换时画布整套重上妆（国内风收口走查 A1 暗色档）：refreshPalette 读
   令牌（computed style 随 html.dark 翻转）→ sigma 重画节点/边 → 网格层重铺
   底色与点阵。此前主题切换画布不重绘（pill/边沿用旧主题妆），底色填充会
   把这一点放大成暗色下刺眼的亮灰，故补齐 */
const settingsStore = useSettingsStore();
watch(
  () => settingsStore.isDark,
  () => {
    /* 先等一拍：useDark 的 html.dark 类是 post-flush 才落 DOM，本回调
       （pre-flush）跑在它之前——立刻取令牌会拿到旧主题值（走查实证：
       暗色下画布被画成亮灰）。nextTick 在整条 flush 队列之后，类已落好 */
    nextTick(() => {
      refreshPalette();
      sigmaRef.value?.refresh();
      if (gridRef.value && sigmaRef.value)
        drawWorldGrid(gridRef.value, sigmaRef.value);
    });
  }
);

/* 悬停/钉住那条边的中文标签浮层：开关关闭时在边中点显示（开关开着全量已显，
   浮层退场避免重复）。坐标随相机变动（camTick 在 sigma init 的 updated 钩子里
   自增）重算；钉住（panelEdgeFocus）优先级低于悬停（正在悬停哪条看哪条） */
const edgeChip = computed(() => {
  const sigma = sigmaRef.value;
  if (!sigma || showAllEdgeLabels.value) return null;
  const id = hoverEdgeId.value ?? panelEdgeFocus.value;
  const gg = sigma.getGraph();
  if (!id || !gg.hasEdge(id)) return null;
  const s = gg.source(id);
  const t = gg.target(id);
  if (!gg.hasNode(s) || !gg.hasNode(t)) return null;
  void camTick.value;
  const sx = gg.getNodeAttribute(s, "x") as number;
  const sy = gg.getNodeAttribute(s, "y") as number;
  const tx = gg.getNodeAttribute(t, "x") as number;
  const ty = gg.getNodeAttribute(t, "y") as number;
  const v = sigma.graphToViewport({ x: (sx + tx) / 2, y: (sy + ty) / 2 });
  const raw = String(gg.getEdgeAttribute(id, "label") ?? "");
  return { x: v.x, y: v.y - 16, label: zhEdge(raw) };
});
/** 幽灵边点进来：面板停在「推出来的」那一档、展开那一行（读一次就清） */
const panelIntentHolder: { current: { view: "derived"; open: string } | null } = {
  current: null
};

/* ---------- 时间过滤：计算 T 时刻的活跃边/节点集合 ---------- */

const activeEdges = ref<Set<string> | null>(null);
const activeNodes = ref<Set<string> | null>(null);
const activeCount = ref(0);

function recomputeActive(t: number | null) {
  const d = data.data.value;
  if (!d) return;
  if (t === null) {
    activeNodes.value = null;
    activeEdges.value = null;
    activeCount.value = d.edges.length;
  } else {
    const prevNodes = activeNodes.value;
    const prevEdges = activeEdges.value;
    const r = computeActiveAt(d, t);
    // 播放推进时新出现的元素淡入登场；手动拖动保持瞬时切换
    if (playing.value) {
      const now = performance.now();
      for (const id of r.edges ?? [])
        if (prevEdges && !prevEdges.has(id)) fadeMap.set(id, now);
      for (const id of r.nodes ?? [])
        if (prevNodes && !prevNodes.has(id)) fadeMap.set(id, now);
      if (fadeMap.size) kickFade();
    }
    activeNodes.value = r.nodes;
    activeEdges.value = r.edges;
    activeCount.value = r.count;
  }
  sigmaRef.value?.refresh();
}
watch(
  [timeT, () => data.data.value],
  () => recomputeActive(timeT.value),
  { immediate: true }
);

/* ---------- 动画驱动（rAF 循环；句柄 cancel 后必须清零——否则「已在跑」守卫从此拒诊） ---------- */

let fadeRaf = 0;
let focusRaf = 0;
function kickFade() {
  if (fadeRaf) return;
  const step = () => {
    const now = performance.now();
    for (const [id, start] of fadeMap)
      if (now - start >= FADE_MS) fadeMap.delete(id);
    sigmaRef.value?.refresh();
    fadeRaf = fadeMap.size ? requestAnimationFrame(step) : 0;
  };
  fadeRaf = requestAnimationFrame(step);
}

function kickFocus() {
  if (focusRaf) return; // 已在跑：目标值每帧现读，无需重启
  const step = () => {
    const target = focusRampTarget({
      hovered: hoverNode.value,
      selected: selected.value,
      panelEdge: panelEdge.value
    });
    focusK.value = focusRampStep(focusK.value, target);
    sigmaRef.value?.refresh();
    focusRaf = focusK.value !== target ? requestAnimationFrame(step) : 0;
  };
  focusRaf = requestAnimationFrame(step);
}

// 播放停止：未完成的淡入直接到位
watch(playing, v => {
  if (!v) {
    fadeMap.clear();
    sigmaRef.value?.refresh();
  }
});

/* ---------- 派生边的入场/开关/呼吸 ---------- */

let deriveRaf = 0;
let revealTimer: ReturnType<typeof setTimeout> | null = null;
/** 进页面/手动打开开关之后：事实先落位，推出来的后到，整体淡入 */
function revealDerived() {
  derivedRevealed.value = true;
  // 复用开关那套淡入：方向为"开"，从近背景色亮到常态
  derivedToggle.value = { at: performance.now(), on: true };
  const step = () => {
    const tr = derivedToggle.value;
    const done = !tr || performance.now() - tr.at >= DERIVE_FADE_MS;
    if (done) derivedToggle.value = null;
    sigmaRef.value?.refresh();
    deriveRaf = done ? 0 : requestAnimationFrame(step);
  };
  cancelAnimationFrame(deriveRaf);
  deriveRaf = requestAnimationFrame(step);
}

watch(
  [showDerived, () => data.data.value, derivedRevealed],
  ([shown], _prev, onCleanup) => {
    if (!shown || !data.data.value) {
      if (!shown) derivedRevealed.value = false;
      return;
    }
    if (derivedRevealed.value) return;
    if (revealTimer) clearTimeout(revealTimer);
    revealTimer = setTimeout(revealDerived, DERIVE_SETTLE_MS);
    onCleanup(() => {
      if (revealTimer) clearTimeout(revealTimer);
      revealTimer = null;
    });
  },
  { immediate: true }
);

let derivedRaf = 0;
// 开关切换时走一段渐变，而不是瞬间消失。**判「是不是真的切换了」只能靠上一值**
// ——watch 的依赖里还有 derivedCount，「Run now 推出新边」会改 count 却没碰开关
let prevShowDerived = showDerived.value;
watch([showDerived, derivedCount], ([shown, count]) => {
  const changed = prevShowDerived !== shown;
  prevShowDerived = shown;
  if (!changed) return;
  // 数量太多时不淡：与呼吸同一条线——每帧重算几千条边的颜色换来的是卡顿
  if (count > DERIVED_ANIMATE_MAX) return;
  const now = performance.now();
  const prev = derivedToggle.value;
  // 半途反向（用户连点两下）：从当前进度接着走，而不是从头开始——
  // 否则会看见一次亮度的跳变
  const at =
    prev && prev.on !== shown
      ? now - Math.max(0, DERIVED_TOGGLE_MS - (now - prev.at))
      : now;
  derivedToggle.value = { at, on: shown };
  // **得自己驱动重绘**——关掉时呼吸定时器不转了，没人推 sigma 重画，
  // 淡出就会卡在第一帧。循环自己会终止；取消只该发生在卸载时
  const step = () => {
    const tr = derivedToggle.value;
    const done = !tr || performance.now() - tr.at >= DERIVED_TOGGLE_MS;
    if (done) derivedToggle.value = null;
    sigmaRef.value?.refresh();
    derivedRaf = done ? 0 : requestAnimationFrame(step);
  };
  cancelAnimationFrame(derivedRaf);
  derivedRaf = requestAnimationFrame(step);
});

let pulseTimer: ReturnType<typeof setInterval> | null = null;
// 派生边的呼吸。**只在有派生边、且开着显示、且数量不多时才转**——
// 一个没开推理的库不该为这件事每两秒重画一次（30fps：呼吸是慢动作）
watch([showDerived, derivedCount], ([shown, n]) => {
  if (pulseTimer) {
    clearInterval(pulseTimer);
    pulseTimer = null;
  }
  if (!shown || n === 0 || n > DERIVED_ANIMATE_MAX) return;
  pulseTimer = setInterval(() => sigmaRef.value?.refresh(), 1000 / 30);
});

/* ---------- 画布 ---------- */

const containerRef = ref<HTMLDivElement | null>(null);
const gridRef = ref<HTMLCanvasElement | null>(null);
type LayoutMode = "force" | "circular" | "pack";
const layoutMode = ref<LayoutMode>("force");
const layoutCtlRef = ref<{ apply: (m: LayoutMode) => void } | null>(null);
const stabilizing = ref(false);

onBeforeUnmount(() => {
  // 每一颗可能在飞的定时器/帧都收掉，句柄清零（旧仓「地雷」条注的同一条规矩）
  if (focusRaf) cancelAnimationFrame(focusRaf);
  focusRaf = 0;
  if (fadeRaf) cancelAnimationFrame(fadeRaf);
  fadeRaf = 0;
  if (deriveRaf) cancelAnimationFrame(deriveRaf);
  deriveRaf = 0;
  if (derivedRaf) cancelAnimationFrame(derivedRaf);
  derivedRaf = 0;
  if (pulseTimer) clearInterval(pulseTimer);
  pulseTimer = null;
  if (stabilizeTimer) clearTimeout(stabilizeTimer);
  stabilizeTimer = null;
  if (settleTimer) clearTimeout(settleTimer);
  settleTimer = null;
  if (revealTimer) clearTimeout(revealTimer);
  revealTimer = null;
  fa2Ref?.kill();
  fa2Ref = null;
  sigmaRef.value?.kill();
  sigmaRef.value = null;
});
useResizeObserver(containerRef, () => sigmaRef.value?.refresh());

watch(
  () => [data.data.value, containerRef.value] as const,
  async () => {
    await nextTick();
    buildCanvas();
  },
  { immediate: true }
);

/* FA2 worker 句柄与拖拽态——buildCanvas 闭包内直握，这里只留清理口 */
let fa2Ref: InstanceType<typeof FA2Layout> | null = null;
let stabilizeTimer: ReturnType<typeof setTimeout> | null = null;
let settleTimer: ReturnType<typeof setTimeout> | null = null;

function stopStabilizing() {
  if (stabilizeTimer) clearTimeout(stabilizeTimer);
  stabilizeTimer = null;
  fa2Ref?.stop();
  stabilizing.value = false;
}

function buildCanvas() {
  const d = data.data.value;
  const container = containerRef.value;
  if (!container || !d) return;
  // 上一轮的机器先停干净（换库/换邻域重建闭包：钉位随之作废）
  stopStabilizing();
  if (settleTimer) clearTimeout(settleTimer);
  settleTimer = null;
  fa2Ref?.kill();
  fa2Ref = null;
  refreshPalette();
  const g = new Graphology({ multi: true });
  for (const n of d.nodes) {
    if (!g.hasNode(n.id)) {
      g.addNode(n.id, {
        label: n.name,
        // 方向 A（规格 §2）：类型色 100% 实心 + 白描边
        color: n.color,
        shellColor: n.color,
        borderColor: STROKE_WHITE,
        ringColor: TRANSPARENT,
        typeColor: n.color,
        typeLabel: n.type_label ?? S.graph.untyped,
        typeKey: n.type_key ?? "",
        type: n.shape === "square" ? NODE_TYPE_SQUARE : NODE_TYPE_SHELL,
        size: 9 + Math.min(9, Math.sqrt(Number(n.degree)) * 1.8)
      });
    }
  }
  /* 标签分级常显（规格 graph-capsule-nodes.md）：小图全部常显；大图取度数前 25 */
  const smallLib = d.nodes.length <= SMALL_LIB;
  const pinned = new Set(
    smallLib
      ? d.nodes.map(n => n.id)
      : [...d.nodes]
          .sort((a, b) => Number(b.degree) - Number(a.degree))
          .slice(0, 25)
          .map(n => n.id)
  );
  const placed = layOutParallelEdges(
    d.edges.filter(e => g.hasNode(e.source) && g.hasNode(e.target))
  );
  for (const { edge: e, curvature, alsoLabels } of placed.edges) {
    g.addEdgeWithKey(e.id, e.source, e.target, {
      // 国内风收口（走查 A3）：谓词键/逆说法中文化（未知键 fallback 原文）
      label: (e.contested ? "⚠ " : "") + zhEdge(e.label ?? ""),
      alsoLabels: alsoLabels?.map(zhEdge),
      // 方向 A（规格 §3）：普通/推断 2.5、禁用 1.5、派生/争议 3
      size: e.blocked ? 1.5 : e.derived || e.contested ? 3 : 2.5,
      color: e.blocked
        ? edgeGhost()
        : e.contested
          ? EDGE_CONTEST
          : e.derived
            ? EDGE_DERIVED
            : e.inferred
              ? EDGE_INFERRED
              : EDGE,
      contested: e.contested,
      blocked: e.blocked,
      type: curvature === 0 ? "line" : "curved",
      curvature,
      derived: e.derived
    });
  }

  // 布局：先静态铺开，再用 worker 动画稳定 ~2.5s（Semantica 式 stabilizing）
  // 拖拽状态先于 fa2 声明：outputReducer 闭包引用它们
  let dragged: string | null = null;
  let dragPos: XY | null = null;
  /* 用户摆放位（走查微调 5「拖哪钉哪」）：拖过的节点松手后钉在松手位，
     FA2 稳定窗口内连边的弹簧不再把它弹回去——机器摆的听机器，用户摆的
     永远听用户的；只有切布局（apply 里 clear）或换库（重建闭包）才重置 */
  const userPlaced = new Map<string, XY>();
  let fa2Settings: ReturnType<typeof forceAtlas2.inferSettings> | null = null;
  if (g.order > 0) {
    circular.assign(g, { scale: 300 });
    /* 可读性轮（规格 §2）：斥力增强 + 向心减弱 = 散开占满画布 */
    const settings = {
      ...forceAtlas2.inferSettings(g),
      gravity: 0.15,
      scalingRatio: 32,
      outboundAttractionDistribution: true
    };
    fa2Settings = settings;
    forceAtlas2.assign(g, { iterations: 60, settings });
    fa2Ref = new FA2Layout(g, {
      settings,
      // 关键：回写时把被拖节点钉回光标（不闪）；松手后从钉光标换到钉松手位
      outputReducer: (node, attr) => {
        const pos = pinnedPosition(node, dragged, dragPos, userPlaced);
        if (pos) {
          attr.x = pos.x;
          attr.y = pos.y;
        }
        return attr;
      }
    });
    fa2Ref.start();
    stabilizing.value = true;
    stabilizeTimer = setTimeout(() => {
      fa2Ref?.stop();
      stabilizing.value = false;
    }, 2500);
  }
  // 数据重建后布局回到 force（世界重新长出来）
  layoutMode.value = "force";

  // 任意布局结果统一缩放到 FA2 同量级世界（±target），相机 reset 观感一致
  const rescaleWorld = (target = 300) => {
    let minX = Infinity,
      maxX = -Infinity,
      minY = Infinity,
      maxY = -Infinity;
    g.forEachNode((_n, a) => {
      minX = Math.min(minX, a.x as number);
      maxX = Math.max(maxX, a.x as number);
      minY = Math.min(minY, a.y as number);
      maxY = Math.max(maxY, a.y as number);
    });
    const span = Math.max(maxX - minX, maxY - minY) || 1;
    const k = (target * 2) / span;
    const cx = (minX + maxX) / 2;
    const cy = (minY + maxY) / 2;
    g.updateEachNodeAttributes((_n, a) => ({
      ...a,
      x: (a.x - cx) * k,
      y: (a.y - cy) * k
    }));
  };

  // 布局切换控制（挂到 ref 供组件层按钮调用；闭包内直握 g / fa2）
  layoutCtlRef.value = {
    apply: mode => {
      if (g.order === 0) return;
      // 用户主动要求重新布局 = 放弃手工摆放：钉位全部解除（走查微调 5）
      userPlaced.clear();
      stopStabilizing();
      if (mode === "force") {
        forceAtlas2.assign(g, {
          iterations: 60,
          settings: fa2Settings ?? undefined
        });
        fa2Ref?.start();
        stabilizing.value = true;
        stabilizeTimer = setTimeout(() => {
          fa2Ref?.stop();
          stabilizing.value = false;
        }, 2500);
      } else if (mode === "circular") {
        circular.assign(g, { scale: 300 });
      } else {
        // 按实体类型聚簇：同类型挤进同一个圆
        circlepack.assign(g, { hierarchyAttributes: ["typeKey"] });
        rescaleWorld(300);
      }
      sigma.setCustomBBox(null);
      sigma.refresh();
      sigma.getCamera().animatedReset({ duration: 300 });
    }
  };

  /* **此刻还连着吗**：areNeighbors 只问图上有没有这条边，不问时间轴停的这一刻
     它还成不成立；两边不一致，画出来就是「节点亮着、线却没有」。按焦点缓存
     （旧仓 liveNbr 同思路）：键带 activeEdges 身份 + showDerived + 边数兜底 */
  const liveNbr = {
    focus: "",
    edges: null as Set<string> | null,
    derived: true,
    size: -1,
    set: new Set<string>()
  };
  const isLiveNeighbor = (focus: string, node: string) => {
    const ae = activeEdges.value;
    if (
      liveNbr.focus !== focus ||
      liveNbr.edges !== ae ||
      liveNbr.derived !== showDerived.value ||
      liveNbr.size !== g.size
    ) {
      const set = new Set<string>();
      g.forEachEdge(focus, (e, attrs, src, tgt) => {
        if (ae && !ae.has(e)) return;
        if (!showDerived.value && attrs.derived === true) return;
        set.add(src === focus ? tgt : src);
      });
      liveNbr.focus = focus;
      liveNbr.edges = ae;
      liveNbr.derived = showDerived.value;
      liveNbr.size = g.size;
      liveNbr.set = set;
    }
    return liveNbr.set.has(node);
  };

  sigmaRef.value?.kill();
  refreshPalette();
  const sigma = new Sigma(g, container, {
    ...sigmaOptions({
      defaultEdgeType: "line",
      edgeProgramClasses: withTopLayer({
        line: EdgeRectangleProgram,
        curved: EdgeCurveProgram
      }),
      // 边上写的是谓词（已中文化）：**默认隐藏**（国内风收口走查 A3）——
      // 显示由 showAllEdgeLabels 开关作唯一事实源；hover/钉住单边走 edgeChip 浮层
      renderEdgeLabels: false,
      minEdgeThickness: 2,
      // 上千个节点，得缩得比本体页更远才看得见全貌
      minCameraRatio: 0.04,
      maxCameraRatio: 8
    }),
    nodeReducer: (node, attrs) => {
      const res = { ...attrs };
      const base = attrs.size as number;
      if (hiddenTypes.value.has(attrs.typeKey as string)) {
        res.hidden = true;
        return res;
      }
      const sel =
        selected.value && g.hasNode(selected.value) ? selected.value : null;
      const hov =
        hoverNode.value && g.hasNode(hoverNode.value) ? hoverNode.value : null;
      const pe =
        panelEdgeFocus.value && g.hasEdge(panelEdgeFocus.value)
          ? panelEdgeFocus.value
          : null;
      const role = resolveNodeRole(
        {
          selected: sel,
          hovered: hov,
          panelEdge: pe,
          panelEdgeActive: !!pe,
          activeNodes: activeNodes.value,
          smallLib
        },
        node,
        {
          isPanelEndpoint: !!pe && g.extremities(pe).includes(node),
          isSelNeighbor: !!sel && isLiveNeighbor(sel, node),
          isHoverNeighbor: !!hov && isLiveNeighbor(hov, node)
        }
      );
      const k = focusK.value;
      switch (role) {
        case "selected": {
          selectedNode(res, attrs, base);
          // 选中的这一个同时被指着：名字改由高亮层画，标签层让开
          return hov === node ? deferToHoverLayer(res) : res;
        }
        case "panelEndpoint":
          // 端点按「指到」画：先回到 ×0.8 静止基准
          res.size = (res.size as number) * 0.8;
          return hoveredNode(res);
        case "hovered":
          // 悬停不放大（微调 1）：早退在默认 ×0.8 缩放之前，同一基准
          res.size = (res.size as number) * 0.8;
          return hoveredNode(res);
        case "dimmed":
          return dimmedNode(res, attrs, k);
        case "softMuted":
          return softMutedNode(res, attrs, base);
        case "muted":
          return mutedNode(res, base);
        case "neighbor":
          // 不提前 return：时间滤门已并入角色判定，这里落到尾部播淡入
          neighborNode(res, base, 0.76, k);
          if (smallLib) res.forceLabel = true;
          break;
        default: {
          // default ×0.8——节点提档到 9–18（规格 §2）后默认态跟着提一档
          res.size = base * 0.8;
          // 标签分级常显（规格 §2.3）：top-25 的名字不随缩放阈值藏
          if (pinned.has(node)) {
            res.forceLabel = true;
            // 胶囊高 26 → 命中区提到半高 13，胶囊可点即命中（规格 §2）
            res.size = Math.max(res.size as number, 13);
          }
        }
      }
      // 播放淡入：从 muted 形态渐变到本帧算出的正常形态
      const fs = fadeMap.get(node);
      if (fs !== undefined) {
        const t = Math.min(1, (performance.now() - fs) / FADE_MS);
        res.size = (res.size as number) * (0.55 + 0.45 * t);
        res.color = lerpColor(
          MUTED_SHELL,
          String(res.color ?? NODE_CORE_BASE),
          t
        );
        res.shellColor = lerpColor(
          MUTED_SHELL,
          String(res.shellColor ?? NODE_SHELL_BASE),
          t
        );
        res.borderColor = lerpColor(
          "rgba(0,0,0,0)",
          String(res.borderColor ?? NODE_BORDER_BASE),
          t
        );
        if (t < 0.7) res.label = "";
      }
      return res;
    },
    edgeReducer: (edge, attrs) => {
      const res = { ...attrs };
      const s = g.source(edge);
      const t = g.target(edge);
      // 近距离写字的 zoom 联动已移除（国内风收口走查 A3）：renderEdgeLabels
      // 由 showAllEdgeLabels 开关作唯一事实源；hover/钉住单边显示走 edgeChip 浮层
      /* 并进这条边的逆关系说法，接在本名后面（`PART OF ⁻¹ CONTAINS`）。
         两个触发点：鼠标压在这条边上，或压在它两端任一个节点上——
         后者才是实际用得上的那个（边只有一像素宽，精确悬停很难命中） */
      const also = attrs.alsoLabels as string[] | undefined;
      if (also && also.length > 0) {
        const hov = hoverNode.value;
        const sel =
          selected.value && g.hasNode(selected.value) ? selected.value : null;
        const focused =
          edge === hoverEdgeId.value ||
          hov === s ||
          hov === t ||
          sel === s ||
          sel === t;
        if (focused) {
          const merged = mergeAlsoLabels(attrs.label, also);
          if (merged) res.label = merged;
        }
      }
      const sk = g.getNodeAttribute(s, "typeKey") as string;
      const tk = g.getNodeAttribute(t, "typeKey") as string;
      if (hiddenTypes.value.has(sk) || hiddenTypes.value.has(tk)) {
        res.hidden = true;
        return res;
      }
      // 推出来的边：先看藏不藏/渐变到哪一档，再决定呼吸色。
      // **放在最前面**——藏起来的边不必再算后面那些提亮/压暗
      const isDerived = attrs.derived === true;
      if (isDerived) {
        const phase = derivedPhase({
          revealed: derivedRevealed.value,
          showDerived: showDerived.value,
          toggle: derivedToggle.value,
          now: performance.now(),
          toggleMs: DERIVED_TOGGLE_MS
        });
        if (phase.kind === "concealed" || phase.kind === "hidden") {
          res.hidden = true;
          return res;
        }
        if (phase.kind === "fade" && !phase.on) {
          /* 由它此刻本来的样子渐灭到近背景色。起点不能一律写死成满亮的金：
             一条本来被压成暗色的无关派生边会先跳回满亮再淡出——那一跳就是
             "关派生时无关的边闪一下" */
          const selNow =
            selected.value && g.hasNode(selected.value) ? selected.value : null;
          const focused = selNow ?? hoverNode.value;
          const ghost = attrs.blocked === true;
          const from = !focused
            ? ghost
              ? edgeGhost()
              : EDGE_DERIVED
            : s === focused || t === focused
              ? ghost
                ? edgeGhostFocus()
                : EDGE_FOCUS_DERIVED
              : EDGE_DIM;
          res.color = lerpColor(from, EDGE_DIM, phase.k);
          res.label = "";
          return res;
        }
        // 幽灵边不呼吸：它不是知识，是一条没走通的路
        const pulse =
          attrs.blocked === true
            ? edgeGhost()
            : lerpColor(
                EDGE_DERIVED_DIM,
                EDGE_DERIVED,
                triangleWave(performance.now(), DERIVED_PULSE_MS)
              );
        // 打开中：从近背景色亮起来，接上呼吸
        res.color =
          phase.kind === "fade" ? lerpColor(EDGE_DIM, pulse, phase.k) : pulse;
      }
      /* 提亮：聚焦强度 k 渐变——进出聚焦的那一帧不再全图换色 */
      const boost = () => {
        const k = focusK.value;
        const to =
          attrs.blocked === true
            ? edgeGhostFocus()
            : attrs.contested === true
              ? EDGE_FOCUS_CONTEST
              : isDerived
                ? EDGE_FOCUS_DERIVED
                : EDGE_FOCUS;
        res.color = lerpColor(String(res.color), to, k);
        res.size = Math.max((attrs.size as number) * (1 + 0.42 * k), 1.85);
        res.zIndex = 5;
        // 换到最后画的那批（见 edgeProgramClasses）：光有 zIndex 压不住别的程序
        res.type = drawLast(String(res.type ?? "line"));
      };
      /* **时间轴停在某一刻时，这条边此刻存不存在**。悬停的两条分支都会提前
         return，绕过下面那道时间过滤——不带上它的话，一 hover，所有"还没长
         出来"的边会从近背景色跳到常态色的 45%，看起来是被点亮了 */
      const liveNow = !activeEdges.value || activeEdges.value.has(edge);
      /* **面板指着的那一条压过一切**：选中一个实体时，连着它的边本来就全亮，
         指着的那条得比它们更亮、写字；同一实体的其余边退回一半 */
      const pe =
        panelEdgeFocus.value && g.hasEdge(panelEdgeFocus.value)
          ? panelEdgeFocus.value
          : null;
      if (pe) {
        if (edge === pe) {
          boost();
          // 钉住那一条的 ×2.4 也随 k 渐变（微调 7；k=1 时与原值一致）
          res.size = Math.max(
            (attrs.size as number) * (1 + 1.4 * focusK.value),
            2.8
          );
          res.forceLabel = true;
          return res;
        }
        const from = liveNow ? String(res.color) : EDGE_DIM;
        res.color = lerpColor(from, EDGE_DIM, HOVER_MUTE * focusK.value);
        res.size = (attrs.size as number) * (1 - 0.4 * focusK.value);
        res.label = "";
        return res;
      }
      const hov =
        hoverNode.value && g.hasNode(hoverNode.value) ? hoverNode.value : null;
      const sel =
        selected.value && g.hasNode(selected.value) ? selected.value : null;
      if (hov && (s === hov || t === hov) && liveNow) {
        boost();
      } else if (hov && !sel) {
        // 悬停时其余的边也退下去，但**只退一半**（HOVER_MUTE）。粗细不动、
        // 只降亮度（走查微调 8）：悬停是轻量动作，变细是点选的待遇
        const from = liveNow ? String(res.color) : EDGE_DIM;
        res.color = lerpColor(from, EDGE_DIM, HOVER_MUTE * focusK.value);
        res.label = "";
        return res;
      } else if (sel) {
        if (s === sel || t === sel) {
          boost();
        } else {
          // 压暗程度随聚焦强度 k 渐变（微调 7）：k=0 时保持常态色
          res.color = lerpColor(String(res.color), EDGE_DIM, focusK.value);
          res.size = (attrs.size as number) * (1 - 0.4 * focusK.value);
          res.label = "";
          return res;
        }
      }
      if (activeEdges.value && !activeEdges.value.has(edge)) {
        res.color = EDGE_DIM;
        /* **压暗了就退出最上层那批**（见 boost()）。这一档在 boost() 之后：
           一条挨着选中项、但此刻时间轴上还不存在的边，颜色已被压回背景色，
           却还留着置顶程序——那就成了画在所有高亮边之上的暗线 */
        res.type = attrs.type as string;
        res.label = "";
        return res;
      }
      // 播放淡入：边从近背景色渐亮到常规色
      const fs = fadeMap.get(edge);
      if (fs !== undefined) {
        const t = Math.min(1, (performance.now() - fs) / FADE_MS);
        res.color = lerpColor(EDGE_DIM, String(res.color), t);
        if (t < 0.8) res.label = "";
      }
      return res;
    }
  });

  /* ---------- 事件（旧仓交互清单逐项） ---------- */
  sigma.on("clickNode", ({ node }) => {
    selected.value = node;
    syncUrl();
  });
  // 幽灵边点一下：打开它主语的面板，停在「推出来的」那一档、展开那一行（0017 §3）
  sigma.on("clickEdge", ({ edge }) => {
    if (g.getEdgeAttribute(edge, "blocked") !== true) return;
    panelIntentHolder.current = { view: "derived", open: edge };
    selected.value = g.source(edge);
    syncUrl();
  });
  sigma.on("doubleClickNode", ({ node, event }) => {
    event.preventSigmaDefault();
    focusEntity.value = node;
    selected.value = node;
    syncUrl();
  });
  sigma.on("clickStage", () => {
    if (!selected.value) return;
    deselect();
  });
  sigma.on("enterNode", ({ node }) => {
    hoverNode.value = node;
    kickFocus();
    sigma.refresh();
  });
  sigma.on("leaveNode", () => {
    hoverNode.value = null;
    kickFocus();
    sigma.refresh();
  });
  // 悬停一条边，把并进来的说法亮出来（reducer 的 also 段读 hoverEdgeId）
  sigma.on("enterEdge", ({ edge }) => {
    hoverEdgeId.value = edge;
    sigma.refresh();
  });
  sigma.on("leaveEdge", () => {
    hoverEdgeId.value = null;
    sigma.refresh();
  });

  /* 国内风收口（走查 A3）：zoom 驱动的边标签联动移除——renderEdgeLabels 由
     showAllEdgeLabels 开关（卡头行2 弹层内）作唯一事实源；hover/钉住单边
     显示走 edgeChipView 浮层（相机变动时 camTick 触发重算） */
  sigma.getCamera().on("updated", () => {
    camTick.value++;
  });
  // 世界坐标网格：相机变动/容器尺寸变动时重绘
  const renderGrid = () => {
    if (gridRef.value) drawWorldGrid(gridRef.value, sigma);
  };
  sigma.getCamera().on("updated", renderGrid);
  sigma.on("resize", renderGrid);
  renderGrid();

  // 节点拖拽 + 活的力导反馈。按下只记候选：视口位移 >4px 才升格为拖拽
  //（attachDrag 的阈值/包围盒冻结那一套）；被拖节点由 fa2 的 outputReducer
  // 钉在光标上，松手钉到松手位（userPlaced）再稳定 ~1.2s 停机
  attachDrag(sigma, {
    onStart: node => {
      dragged = node;
      if (settleTimer) clearTimeout(settleTimer);
      // 静态布局（circular/pack）下拖拽不唤醒力模拟——否则一碰就散架
      if (layoutMode.value === "force" && fa2Ref && !fa2Ref.isRunning())
        fa2Ref.start();
    },
    onMove: (_node, pos) => {
      dragPos = pos;
    },
    onEnd: node => {
      /* 拖哪钉哪（走查微调 5）：松手位记进 userPlaced——此刻图属性里就是
         松手位（拖拽的每一步都写进了图），outputReducer 从钉光标无缝换到
         钉这里，FA2 剩下的模拟里它不再被弹回 */
      userPlaced.set(node, {
        x: g.getNodeAttribute(node, "x") as number,
        y: g.getNodeAttribute(node, "y") as number
      });
      dragged = null;
      dragPos = null;
      settleTimer = setTimeout(() => fa2Ref?.stop(), 1200);
    }
  });

  sigmaRef.value = sigma;
  if (import.meta.env.DEV) {
    // 调试句柄（仅 dev）：无头环境检查 reducer 输出 / 驱动 hover 态截图
    (window as unknown as Record<string, unknown>).__g = g;
    (window as unknown as Record<string, unknown>).__sigma = sigma;
    (window as unknown as Record<string, unknown>).__graphHover = (
      id: string | null
    ) => {
      hoverNode.value = id && g.hasNode(id) ? id : null;
      kickFocus();
      sigma.refresh();
    };
  }
  recomputeActive(timeT.value);
}

/* ---------- 面板 ↔ 画布联动 ---------- */

/** 面板指着一条事实：画布上那条边亮起来。不在画布上的（时间轴滤掉、邻域外）
 *  什么也不亮，**不拿同一对节点之间的另一条边顶替** */
function pointFact(factId: string | null) {
  const sigma = sigmaRef.value;
  if (!sigma) return;
  const next = factId && sigma.getGraph().hasEdge(factId) ? factId : null;
  if (panelEdge.value === next) return;
  panelEdge.value = next;
  kickFocus();
  sigma.refresh();
}

/** 面板指着一个实体（宾语那个词）：画布上它按「指到」画 */
function pointEntity(entityId: string | null) {
  const sigma = sigmaRef.value;
  if (!sigma) return;
  hoverNode.value =
    entityId && sigma.getGraph().hasNode(entityId) ? entityId : null;
  kickFocus();
  sigma.refresh();
}

/** 点了一条事实：钉住那条边，镜头移到两端之间、缩到两端都在画面里。
 *  边不在画布上就退回去跳到宾语——至少把人带到它连着的那个东西 */
function focusFact(factId: string, otherId: string | null) {
  const sigma = sigmaRef.value;
  const g = sigma?.getGraph();
  const entity = selected.value;
  if (!sigma || !g || !entity || !g.hasEdge(factId)) {
    if (otherId) {
      focusEntity.value = otherId;
      selected.value = otherId;
      syncUrl();
    }
    return;
  }
  // 再点一次同一行：放开
  const current = pin.value;
  if (current && current.entity === entity && current.fact === factId) {
    pin.value = null;
    kickFocus();
    sigma.refresh();
    return;
  }
  pin.value = { entity, fact: factId };
  kickFocus();
  const [s, t] = g.extremities(factId);
  const a = sigma.getNodeDisplayData(s);
  const b = sigma.getNodeDisplayData(t);
  if (a && b) {
    // 相机坐标与节点显示坐标同在归一化的画框里：两端相距 span，1.3 倍留边距。
    // 上限 0.6 是为了越过「放大到 0.7 以下才写边标签」那道线
    const span = Math.hypot(a.x - b.x, a.y - b.y);
    sigma.getCamera().animate(
      {
        x: (a.x + b.x) / 2,
        y: (a.y + b.y) / 2,
        ratio: Math.min(0.6, Math.max(0.12, span * 1.3))
      },
      { duration: 400 }
    );
  }
  sigma.refresh();
}

// 选中一换：面板指着的那条边作数（它是上一个面板指的），聚焦渐变跟进
watch(selected, () => {
  panelEdge.value = null;
  kickFocus();
  sigmaRef.value?.refresh();
});

/* ---------- 顶栏统计 ---------- */

const empty = computed(
  () => data.isSuccess.value && (data.data.value?.nodes.length ?? 0) === 0
);
const nodeCount = computed(() => data.data.value?.nodes.length ?? 0);
const edgeCount = computed(() => data.data.value?.edges.length ?? 0);
const totalNodes = computed(
  () => data.data.value?.total_nodes ?? nodeCount.value
);
const totalEdges = computed(
  () => data.data.value?.total_edges ?? edgeCount.value
);
const capped = computed(() => totalNodes.value > nodeCount.value);
const statsText = computed(() => {
  if (data.isPending.value) return null;
  if (capped.value)
    return S.graph.statsCapped(
      nodeCount.value,
      totalNodes.value,
      edgeCount.value,
      totalEdges.value,
      timeT.value === null ? null : activeCount.value
    );
  return S.graph.stats(
    nodeCount.value,
    edgeCount.value,
    timeT.value === null ? null : activeCount.value
  );
});
</script>

<template>
  <div class="knowledge-page flex h-full flex-col p-6">
    <!-- 画布主卡：全幅画布 + 工作区型两行卡头（2026-11-24 合一批，方案 A）——独立页头带
         删除，标题/kb chip/节点档位/统计/搜索/图例全部收进卡头，画布上移满铺；卡形态
         只在外框/圆角，画布无边距全填充（内距归零见 scoped .graph-canvas-card） -->
    <div class="card graph-canvas-card relative flex min-h-0 flex-1 flex-col">
      <!-- 卡头（两行）：行1 标题+kb chip / 节点档位+统计；行2 搜索+类型图例 /
           边语义图例弹层触发钮（2026-11-24 留档清偿批：图例收弹层，行2 单行）。
           bg-card 显式白底：外层卡已收敛为细框（背景透明），白/灰两段式靠它分界 -->
      <div class="border-b border-line bg-card px-3 pb-2 pt-2">
        <!-- 行1：标题 + kb chip / 稳定提示 + 节点档位步进器 + 统计 -->
        <div class="flex items-center gap-2">
          <h1 class="text-title font-semibold text-ink">{{ S.nav.graph }}</h1>
          <span
            class="rounded-full border border-line bg-fill px-2 py-0.5 text-aux text-ink-2"
          >
            {{ kb?.name }}
          </span>
          <div class="ml-auto flex items-center gap-2">
            <!-- 稳定提示 tag 化（国内风收口走查 A5）：裸文本改浅色 tag+spinner，
                 完成即消失（v-if 语义不变） -->
            <span
              v-if="stabilizing"
              class="flex items-center gap-1 rounded-full bg-primary-soft px-2 py-0.5 text-aux text-primary"
            >
              <Loader2Icon width="11" height="11" class="animate-spin" />
              {{ S.graph.stabilizing }}
            </span>
            <!-- 节点档位 ± 步进器：控制画多少节点的档位（功能控件，非缩放）原样迁入 -->
            <div
              class="flex items-center overflow-hidden rounded-ctl border border-line"
            >
              <button
                type="button"
                class="px-2 py-1.5 text-ink-2 hover:bg-fill disabled:opacity-40"
                :title="S.graph.nodeBudgetLess"
                :disabled="nodeBudget <= NODE_BUDGETS[0]"
                @click="
                  nodeBudget =
                    NODE_BUDGETS[Math.max(0, NODE_BUDGETS.indexOf(nodeBudget) - 1)]
                "
              >
                −
              </button>
              <button
                type="button"
                class="px-2 py-1.5 text-ink-2 hover:bg-fill disabled:opacity-40"
                :title="S.graph.nodeBudgetMore"
                :disabled="
                  !capped || nodeBudget >= NODE_BUDGETS[NODE_BUDGETS.length - 1]
                "
                @click="
                  nodeBudget =
                    NODE_BUDGETS[
                      Math.min(
                        NODE_BUDGETS.length - 1,
                        NODE_BUDGETS.indexOf(nodeBudget) + 1
                      )
                    ]
                "
              >
                +
              </button>
            </div>
            <span class="tabular-nums text-aux text-ink-2">{{ statsText }}</span>
          </div>
        </div>
        <!-- 行2：搜索（建议框 absolute 以本行为参照）+ 返回全图 + 类型图例 /
             边语义图例触发钮（五项在弹层里，行2 单行不折行） -->
        <div class="mt-2 flex items-start gap-2">
          <div class="relative">
            <div class="relative">
              <SearchIcon
                class="pointer-events-none absolute left-3 top-1/2 z-10 -translate-y-1/2 text-ink-2"
                width="12"
                height="12"
              />
              <el-input
                v-model="searchInput"
                class="graph-search w-58"
                :placeholder="
                  inSubgraph ? S.graph.searchInSubgraph : S.graph.searchEntity
                "
              />
            </div>
            <!-- 搜索建议框（reskin-b3：max-height 防极短视口溢出 + 实体名命中词
                 <mark> 高亮——形态与检索结果卡统一，见 scoped .graph-suggest） -->
            <div
              v-if="searchQ && searchHits.length > 0"
              class="graph-suggest absolute mt-1 w-full overflow-hidden rounded-card border border-line bg-card shadow-pop"
            >
              <URow
                v-for="c in searchHits"
                :key="c.id"
                density="menu"
                @click="
                  () => {
                    if (!inSubgraph) focusEntity = c.id;
                    selected = c.id;
                    searchInput = '';
                    searchQ = '';
                    syncUrl();
                  }
                "
              >
                <span class="flex min-w-0 items-center gap-2">
                  <span
                    class="h-2.5 w-2.5 shrink-0 rounded-full"
                    :style="{ background: c.color }"
                  />
                  <span class="truncate">
                    <template
                      v-for="(seg, i) in highlightSegments(c.name, [searchQ.trim()])"
                      :key="i"
                    >
                      <mark v-if="seg.hit">{{ seg.text }}</mark>
                      <template v-else>{{ seg.text }}</template>
                    </template>
                  </span>
                  <span
                    v-if="c.disambiguator && c.disambiguator !== c.type_label"
                    class="min-w-0 max-w-[40%] truncate text-sub text-ink-2"
                  >
                    · {{ c.disambiguator }}
                  </span>
                </span>
                <template #trailing>{{ c.type_label }}</template>
              </URow>
              <!-- 还有更多没显示。说清剩多少 -->
              <el-button
                v-if="
                  !inSubgraph &&
                  (candidates.data.value?.total ?? 0) > searchHits.length
                "
                class="w-full justify-start rounded-none border-t border-line"
                size="small"
                text
                @click="searchLimit += 20"
              >
                {{
                  S.graph.searchMore(
                    candidates.data.value!.total - searchHits.length
                  )
                }}
              </el-button>
            </div>
          </div>
          <el-button
            v-if="focusEntity"
            class="graph-shadow"
            @click="
              () => {
                focusEntity = null;
                syncUrl();
              }
            "
          >
            {{ S.graph.backToOverview }}
          </el-button>

          <!-- 图例（点击切换类型显隐）。只摆前 LEGEND_MAX 个；卡头内允许折行 -->
          <div class="flex min-w-0 flex-wrap gap-2">
            <UPill
              v-for="[key, t] in legendShown"
              :key="key"
              :dim="hiddenTypes.has(key)"
              @click="toggleHidden(key)"
            >
              <span
                :class="[
                  'h-2 w-2',
                  t.shape === 'square' ? 'scale-90' : 'rounded-full'
                ]"
                :style="{ background: t.color }"
              />
              <span>{{ t.label }}</span>
            </UPill>

            <!-- +N 个类：弹层里全列表（查找 / 全开 / 全关 / 只看这个） -->
            <el-popover v-if="legendRest.length > 0" width="288" trigger="click">
              <template #reference>
                <UPill :title="S.graph.legendAllHint">
                  {{ S.graph.legendMore(types.length) }}
                  <span
                    v-if="legendRest.some(([k]) => hiddenTypes.has(k))"
                    class="h-1.5 w-1.5 rounded-full bg-ink-2"
                  />
                </UPill>
              </template>
              <div
                class="border-b border-line px-3 py-2 text-body font-medium text-ink"
              >
                {{ S.graph.legendMore(types.length) }}
              </div>
              <div class="flex items-center gap-2 border-b border-line px-3 py-2">
                <button
                  type="button"
                  class="link-inline text-sub"
                  :disabled="hiddenTypes.size === 0"
                  @click="
                    () => {
                      hiddenTypes = new Set();
                      sigmaRef?.refresh();
                    }
                  "
                >
                  {{ S.graph.legendShowAll(hiddenTypes.size) }}
                </button>
                <button
                  type="button"
                  class="link-inline ml-auto text-sub"
                  :disabled="hiddenTypes.size === types.length"
                  @click="
                    () => {
                      hiddenTypes = new Set(types.map(([k]) => k));
                      sigmaRef?.refresh();
                    }
                  "
                >
                  {{ S.graph.legendHideAll }}
                </button>
              </div>
              <div class="border-b border-line px-3 py-2">
                <el-input
                  v-model="legendQ"
                  :placeholder="S.graph.legendSearch"
                  size="small"
                />
              </div>
              <div class="max-h-64 overflow-y-auto overscroll-contain px-2 py-1">
                <div
                  v-for="[key, t] in legendRestFiltered"
                  :key="key"
                  class="group flex items-center gap-2"
                >
                  <button
                    type="button"
                    class="flex min-w-0 flex-1 items-center justify-start rounded-none px-0"
                    @click="toggleHidden(key)"
                  >
                    <span
                      class="h-2 w-2 shrink-0"
                      :class="t.shape === 'square' ? 'scale-90' : 'rounded-full'"
                      :style="{
                        background: t.color,
                        opacity: hiddenTypes.has(key) ? 0.35 : 1
                      }"
                    />
                    <span
                      :class="[
                        'truncate text-sub',
                        hiddenTypes.has(key)
                          ? 'text-ink-2 line-through'
                          : 'text-ink'
                      ]"
                    >
                      {{ t.label }}
                    </span>
                  </button>
                  <button
                    type="button"
                    class="reveal shrink-0 text-sub text-ink-2 hover:text-ink"
                    @click="
                      () => {
                        hiddenTypes = new Set(
                          types.map(([k]) => k).filter(k => k !== key)
                        );
                        sigmaRef?.refresh();
                      }
                    "
                  >
                    {{ S.graph.legendOnly }}
                  </button>
                  <span class="shrink-0 tabular-nums text-aux text-ink-2">
                    {{ t.count }}
                  </span>
                </div>
                <div
                  v-if="legendRestFiltered.length === 0"
                  class="px-2 py-2 text-sub text-ink-2"
                >
                  {{ S.graph.legendNone }}
                </div>
              </div>
            </el-popover>
          </div>

          <!-- 边语义图例（规格 graph-edge-legend.md §1）：卡头行2 单行化（2026-11-24
               留档清偿批）——inline 五项收进 el-popover，行2 只留触发钮（暗色随 EP
               令牌自动） -->
          <el-popover placement="bottom-end" :width="300" trigger="click">
            <template #reference>
              <button
                type="button"
                class="ml-auto flex shrink-0 items-center gap-1.5 rounded-ctl border border-line px-2 py-1 text-aux text-ink-2 hover:bg-fill"
                :title="S.graph.edgeLegend"
              >
                <GitCompareIcon width="12" height="12" />
                {{ S.graph.edgeLegend }}
              </button>
            </template>
            <!-- 原标题+五项整体移入（保留原 flex 排版，容器改 flex-col gap-1.5） -->
            <div
              class="flex flex-col items-start gap-1.5 text-aux text-ink-3"
            >
              <span class="font-semibold">{{ S.graph.edgeLegend }}</span>
              <span class="flex items-center gap-1.5">
                <span
                  class="inline-block h-[3px] w-4 rounded-full"
                  :style="{ background: EDGE }"
                />
                {{ S.graph.edgeFact }}
              </span>
              <span class="flex items-center gap-1.5">
                <span
                  class="inline-block h-[3px] w-4 rounded-full"
                  :style="{ background: EDGE_INFERRED }"
                />
                {{ S.graph.edgeInferred }}
              </span>
              <span class="flex items-center gap-1.5">
                <span
                  class="inline-block h-[4px] w-4 rounded-full"
                  :style="{ background: EDGE_DERIVED }"
                />
                {{ S.graph.edgeDerived }}
              </span>
              <span class="flex items-center gap-1.5">
                <span
                  class="inline-block h-[4px] w-4 rounded-full"
                  :style="{ background: EDGE_CONTEST }"
                />
                ⚠ {{ S.graph.edgeContested }}
              </span>
              <span class="flex items-center gap-1.5">
                <span
                  class="inline-block h-[2px] w-4 rounded-full"
                  :style="{ background: edgeGhost() }"
                />
                {{ S.graph.edgeBlocked }}
              </span>
              <!-- 国内风收口（走查 A3）：边标签显示开关——默认隐藏，开=全量
                   显示中文标签（renderEdgeLabels 唯一事实源） -->
              <el-divider style="margin: 4px 0" />
              <el-checkbox v-model="showAllEdgeLabels" size="small">
                {{ S.graph.showAllEdgeLabels }}
              </el-checkbox>
            </div>
          </el-popover>
        </div>
      </div>

      <!-- 画布：世界坐标网格层垫在 sigma WebGL 层下；卡头下方 in-flow 满铺
           （flex-1 min-h-0）。错误行/Loading/空态也锚进本画布层（相对画布定位）
           ——卡头 static 化后不能再以整卡为锚，否则错误文案叠卡头行2、空态/
           Loading 视觉中心偏移约半个卡头高（T1 审查修正） -->
      <div class="relative min-h-0 flex-1">
        <canvas ref="gridRef" class="absolute inset-0 h-full w-full" />
        <div ref="containerRef" class="absolute inset-0" />

        <!-- 取数失败给一行错误（与 Search 的错误行同构）：空画布会读成“库是空的”，
            那是另一回事（旧仓 KbScope 注释的同一条判断）；top-6 按画布参照 -->
        <p
          v-if="data.isError.value"
          class="pointer-events-none absolute left-1/2 top-6 z-10 -translate-x-1/2 text-body text-danger"
        >
          {{ String(data.error.value) }}
        </p>

        <CanvasLoading v-if="data.isPending.value" />

        <!-- 悬停/钉住边的中文标签浮层（国内风收口走查 A3）：开关关闭时在边
             中点显示该边中文标签；开关开着全量已显，浮层退场避免重复 -->
        <div
          v-if="edgeChip"
          class="pointer-events-none absolute z-20 max-w-40 -translate-x-1/2 -translate-y-1/2 truncate rounded-ctl border border-line bg-card px-2 py-0.5 text-aux text-ink shadow-pop"
          :style="{ left: edgeChip.x + 'px', top: edgeChip.y + 'px' }"
        >
          {{ edgeChip.label }}
        </div>

        <!-- 空状态：readiness 统一判定（#313），步骤话术 + 至多一个动作 -->
        <div
          v-if="empty"
          class="pointer-events-none absolute inset-0 grid place-items-center pb-20"
        >
          <div class="pointer-events-auto">
            <div class="max-w-xs text-center text-body text-ink-2">
              {{ step?.line ?? (S.steps.nothingExtracted as string) }}
              <RouterLink
                v-if="step?.action"
                class="link-inline mt-3 block"
                :to="{ path: step.action.to, query: step.action.search as never }"
              >
                {{ step.action.label }}
              </RouterLink>
            </div>
          </div>
        </div>
      </div>

      <!-- 左下控件塔：推出来的边 + 布局切换 + 相机 -->
      <div class="absolute bottom-4 left-3 z-10 flex flex-col items-start gap-2">
        <ToolTower v-if="derivedCount > 0">
          <ToolButton
            :active="showDerived"
            :label="S.graph.viewDerived"
            :title="`${S.graph.derivedEdges(derivedCount)} · ${S.graph.derivedHint}`"
            @click="
              () => {
                showDerived = !showDerived;
                sigmaRef?.refresh();
              }
            "
          >
            <template #icon>
              <WaypointsIcon
                width="15"
                height="15"
                :style="showDerived ? { color: EDGE_FOCUS_DERIVED } : undefined"
              />
            </template>
          </ToolButton>
          <ToolDivider />
          <ToolButton
            :active="layoutMode === 'force'"
            :label="S.graph.layoutForce"
            @click="
              () => {
                layoutMode = 'force';
                layoutCtlRef?.apply('force');
              }
            "
          >
            <template #icon>
              <OrbitIcon width="15" height="15" />
            </template>
          </ToolButton>
          <ToolButton
            :active="layoutMode === 'circular'"
            :label="S.graph.layoutCircular"
            @click="
              () => {
                layoutMode = 'circular';
                layoutCtlRef?.apply('circular');
              }
            "
          >
            <template #icon>
              <CircleDashedIcon width="15" height="15" />
            </template>
          </ToolButton>
          <ToolButton
            :active="layoutMode === 'pack'"
            :label="S.graph.layoutPack"
            @click="
              () => {
                layoutMode = 'pack';
                layoutCtlRef?.apply('pack');
              }
            "
          >
            <template #icon>
              <GrapeIcon width="15" height="15" />
            </template>
          </ToolButton>
          <ToolDivider />
          <ToolButton
            :label="S.graph.zoomIn"
            @click="sigmaRef?.getCamera().animatedZoom({ duration: 220 })"
          >
            <template #icon>
              <ZoomInIcon width="15" height="15" />
            </template>
          </ToolButton>
          <ToolButton
            :label="S.graph.zoomOut"
            @click="sigmaRef?.getCamera().animatedUnzoom({ duration: 220 })"
          >
            <template #icon>
              <ZoomOutIcon width="15" height="15" />
            </template>
          </ToolButton>
          <ToolDivider />
          <ToolButton
            :label="S.graph.fitView"
            @click="sigmaRef?.getCamera().animatedReset({ duration: 300 })"
          >
            <template #icon>
              <Maximize2Icon width="15" height="15" />
            </template>
          </ToolButton>
        </ToolTower>
      </div>

      <!-- 底部居中悬浮时间岛（双时间轴过滤） -->
      <TimeScrubber
        v-if="edgeCount > 0 && data.data.value"
        :edges="data.data.value.edges"
        :value="timeT"
        :playing="playing"
        @change="
          (v: number | null) => {
            timeT = v;
            syncUrl();
          }
        "
        @playing-change="(v: boolean) => (playing = v)"
      />

      <!-- 实体侧栏。取消选中之后还要多留 170ms：那段时间它在演退场 -->
      <EntityPanel
        v-if="(selected || exiting) && kbId"
        :key="(selected ?? exiting)!"
        :kb-id="kbId"
        :entity-id="(selected ?? exiting)!"
        :exiting="!selected"
        :pinned-fact="pinnedFact"
        :intent="panelIntentHolder"
        @close="deselect"
        @navigate="
          (id: string) => {
            focusEntity = id;
            selected = id;
            syncUrl();
          }
        "
        @point-fact="pointFact"
        @focus-fact="focusFact"
        @point-entity="pointEntity"
      />
    </div>
  </div>
</template>

<style scoped>
/* 重造层（reskin-b2）：只引令牌（--color-* / --radius-* / --shadow-*），
   禁硬编码色值；卡容器形态来自全局 .card（theme.scss） */

/* 画布主卡：sigma 画布全幅无内距（.card 的 20px 内距归零），圆角由
   overflow 收口（WebGL 层随容器矩形，超出圆角部分由卡裁掉）。
   画布底与卡面同白（2026-11-25 回归纯白，方案 A）：曾试过「透明细框 + 灰画布」
   两段式（2026-11-24），画布的灰与卡头的明度断层读成"框住的灰盒子"，已撤——
   卡片回到 .card 默认（白底+细边+阴影），只覆写内距与溢出 */
.graph-canvas-card {
  padding: 0;
  overflow: hidden;
}

/* 浮动搜索卡（anivia 查询卡语言，对齐首批 search 查询卡）：圆角 --radius-card、
   实底卡面、pop 浮层阴影；focus 光晕由全局主题层提供
   （theme.scss：.el-input__wrapper.is-focus → primary 描边 + glow） */
.graph-search :deep(.el-input__wrapper),
.graph-search :deep(.el-input__wrapper:hover) {
  padding-left: 30px;
  border-radius: var(--radius-card);
  background-color: var(--color-card);
  box-shadow:
    0 0 0 1px var(--color-border) inset,
    var(--shadow-pop);
}

/* 顶部「全图」返回钮：与搜索卡同一浮层海拔（原硬编码 rgb() 阴影令牌化） */
.graph-shadow {
  box-shadow: var(--shadow-pop);
}

/* 搜索建议框（reskin-b3 清扫）：极短视口下建议列表不再顶出屏外（16rem 内滚，
   x 向仍由模板的 overflow-hidden 收口圆角）；命中词 <mark> 与检索结果卡
   .result-quote mark 同一形态——主色 glow 底 + 主色深档字（两消费点同改） */
.graph-suggest {
  max-height: 16rem;
  overflow-y: auto;
}
.graph-suggest mark {
  padding: 0 2px;
  font-weight: 600;
  color: var(--color-primary-text);
  background: var(--color-primary-glow);
  border-radius: 4px;
}
</style>
