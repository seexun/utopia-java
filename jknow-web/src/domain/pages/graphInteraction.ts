// 图谱页交互状态机（任务 9，W3）：旧仓 Graph.tsx 交互段的**决策序**抽成纯函数。
//
// 分界线与 graphCanvas.ts 同一条：这里只回答「此刻这个节点/这条边该是什么角色」
// （hovered/selected/dragging 的派生态），把角色写成 sigma 属性的活儿在
// graphCanvas.ts 的状态表（selectedNode/hoveredNode/softMutedNode/…）与页面
// 组件的 reducer 里。决策序是交互的全部可测面——「选中压过指到」「面板指边
// 压过一切」「悬停不清邻居」这些规矩走样了，画布上就是语义错误而不是观感差异。
//
// 各函数的出处（旧仓 pages/Graph.tsx）：panelFocus 逐字；resolveNodeRole 是
// nodeReducer 决策段的纯函数化；derivedPhase/triangleWave 是 edgeReducer 派生段；
// pinnedPosition 是 FA2 outputReducer + attachDrag（走查微调 5「拖哪钉哪」）。

/* ============ 面板在指哪条边 ============ */

/** 面板此刻在指哪条边：指着的行优先；没有就用钉住的——但钉住的只在钉它的那个实体
 *  还选着时作数，而且画布或面板正指着某个节点时让开，好让「它连着谁」照常读得出
 *  （旧仓 panelFocus 逐字） */
export function panelFocus(
  pointed: string | null,
  pinned: { entity: string; fact: string } | null,
  hovered: string | null,
  selected: string | null,
): string | null {
  if (pointed) return pointed;
  if (!pinned || hovered || pinned.entity !== selected) return null;
  return pinned.fact;
}

/* ============ 聚焦强度（走查微调 7） ============ */

/** 聚焦强度 k 的目标：有任何焦点（悬停/选中/面板指边）就是 1，否则 0 */
export function focusRampTarget(f: {
  hovered: string | null;
  selected: string | null;
  panelEdge: string | null;
}): 0 | 1 {
  return f.hovered || f.selected || f.panelEdge ? 1 : 0;
}

/** k 的一帧步进：每帧朝目标走 frameMs/rampMs，夹在 [0,1]。
 *  旧仓常数 16/FOCUS_RAMP_MS(240)——rAF 每 ~16ms 一帧，全程 ~240ms */
export function focusRampStep(
  cur: number,
  target: number,
  frameMs = 16,
  rampMs = 240,
): number {
  if (cur === target) return cur;
  const step = frameMs / rampMs;
  return Math.max(0, Math.min(1, cur + Math.sign(target - cur) * step));
}

/* ============ 节点角色 ============ */

/** reducer 决策要读的那一帧全局态。selected/hovered 由调用方保证在画布上
 *  （`g.hasNode` 守卫：侧栏跳转/邻域重载间隙里选中的实体可能不在） */
export interface InteractionSnapshot {
  selected: string | null;
  hovered: string | null;
  /** panelFocus 的结果（面板指着或钉着的那条边） */
  panelEdge: string | null;
  /** 面板正指着一条边（panelEdge 在画布上）。指着时其余节点退半档——
   *  要读出来的是「这条边连着谁」，不相干的节点跟着亮就答非所问 */
  panelEdgeActive: boolean;
  /** 时间过滤的「此刻在」节点集；null = 全时段不筛 */
  activeNodes: ReadonlySet<string> | null;
  /** 小库（≤100）聚焦态轻降灰不清名（走查微调 4），大库退场压暗 */
  smallLib: boolean;
}

/** 这个节点自己的两条邻接事实（调用方按图与当前边集算好） */
export interface NodeFacts {
  /** 是面板所指边的端点 */
  isPanelEndpoint: boolean;
  /** 是选中实体的活邻居（此刻还连着：时间滤过 + 派生开关滤过） */
  isSelNeighbor: boolean;
  /** 是悬停实体的活邻居 */
  isHoverNeighbor: boolean;
}

export type NodeRole =
  | "selected"
  | "panelEndpoint"
  | "hovered"
  | "neighbor"
  | "softMuted"
  | "dimmed"
  | "muted"
  | "default";

/** 节点角色判定（旧仓 nodeReducer 决策段，顺序即语义）：
 *
 * 1. **选中压过指到**——指针落到自己刚选中的节点上，它仍是选中的那副
 *    （那副的悬停分量由 deferToHoverLayer 表达，在组件层加）；
 * 2. 面板指边：**端点**按「指到」画，其余退半档（softMuted/dimmed）——
 *    要读的是这条连着谁，不相干的跟着亮就答非所问；正被悬停的节点让开；
 * 3. 悬停不放大、不压邻居——悬停回答的是「它连着谁」；
 * 4. 选中后邻居留出，**悬停的邻居也留出**（选中之后再指别处，亮边不该通向黑点）；
 * 5. 悬停压其余只压一档（softMuted），选中压到底（muted）；小库一律 dimmed 不清名；
 * 6. 「此刻不存在」的节点在悬停分支里直接压到底——提前 return 绕过时间滤门时，
 *    只压一半反而比不 hover 时更亮 */
export function resolveNodeRole(
  st: InteractionSnapshot,
  node: string,
  f: NodeFacts,
): NodeRole {
  const sel = st.selected;
  const hov = st.hovered;
  if (sel === node) return "selected";
  if (st.panelEdgeActive) {
    if (f.isPanelEndpoint) return "panelEndpoint";
    if (hov !== node) return st.smallLib ? "dimmed" : "softMuted";
  }
  if (hov === node) return "hovered";
  if (sel) {
    if (f.isSelNeighbor || f.isHoverNeighbor) {
      // 邻居不过早退：时间滤门照常过（旧仓 neighbor 段落到尾部的 activeNodes 检查）
      if (st.activeNodes && !st.activeNodes.has(node))
        return st.smallLib ? "dimmed" : "muted";
      return "neighbor";
    }
    return st.smallLib ? "dimmed" : "muted";
  }
  if (hov && hov !== node && !f.isHoverNeighbor) {
    if (st.activeNodes && !st.activeNodes.has(node))
      return st.smallLib ? "dimmed" : "muted";
    return st.smallLib ? "dimmed" : "softMuted";
  }
  if (st.activeNodes && !st.activeNodes.has(node))
    return st.smallLib ? "dimmed" : "muted";
  return "default";
}

/* ============ 拖拽钉位（走查微调 5「拖哪钉哪」） ============ */

export interface XY {
  x: number;
  y: number;
}

/** FA2 outputReducer 的回写位：被拖的钉在光标上，摆过的钉在松手位，其余 null（随模拟）。
 *
 *  机器摆的听机器，用户摆的永远听用户的——松手后钉位同样回流进 worker，
 *  模拟期间邻居绕着它重排，它自己不再被弹簧拉回；只有切布局或换库才重置 */
export function pinnedPosition(
  node: string,
  dragged: string | null,
  dragPos: XY | null,
  userPlaced: ReadonlyMap<string, XY>,
): XY | null {
  if (dragged && node === dragged && dragPos) return dragPos;
  return userPlaced.get(node) ?? null;
}

/* ============ 派生边的时序 ============ */

export interface DerivedToggle {
  at: number;
  on: boolean;
}

export type DerivedPhase =
  /** 入场时序没到：事实先落位，推出来的后到（旧仓 derivedRevealed=false） */
  | { kind: "concealed" }
  /** 开关关着且渐变走完：整条不画 */
  | { kind: "hidden" }
  /** 开关渐变在飞：on=false 淡出 / on=true 淡入，k ∈ (0,1) */
  | { kind: "fade"; k: number; on: boolean }
  /** 常态：开着（呼吸/幽灵），或关着但淡出尚欠最后一段由 fade 表达 */
  | { kind: "shown" };

/** 派生边此刻处在哪个阶段（旧仓 edgeReducer 派生段的头几步）。
 *  `toggleMs` 是开关渐变时长（DERIVED_TOGGLE_MS = 420） */
export function derivedPhase(opts: {
  revealed: boolean;
  showDerived: boolean;
  toggle: DerivedToggle | null;
  now: number;
  toggleMs: number;
}): DerivedPhase {
  if (!opts.revealed) return { kind: "concealed" };
  const tr = opts.toggle;
  if (!tr) return opts.showDerived ? { kind: "shown" } : { kind: "hidden" };
  const k = Math.min(1, (opts.now - tr.at) / opts.toggleMs);
  if (!opts.showDerived) {
    // 关掉了：只有「淡出尚未走完」这一种情况还留着不藏
    if (tr.on || k >= 1) return { kind: "hidden" };
    return { kind: "fade", k, on: false };
  }
  if (tr.on && k < 1) return { kind: "fade", k, on: true };
  return { kind: "shown" };
}

/** 呼吸三角波：两端各停一瞬，看起来是「呼吸」不是「闪」（旧仓逐字）。
 *  返回 0..1：0 = 最暗（EDGE_DERIVED_DIM），1 = 最亮（EDGE_DERIVED） */
export function triangleWave(now: number, periodMs: number): number {
  return Math.abs(((now % periodMs) / periodMs) * 2 - 1);
}

/* ============ 并进来的逆关系说法 ============ */

/** 并进这条边的逆关系说法接在本名后面：`PART OF ⁻¹ CONTAINS`。
 *  只在关注它的时候显示——常驻会把标签拉长一倍（旧仓逐字）。
 *  返回 null = 没有说法、标签不动 */
export function mergeAlsoLabels(
  label: unknown,
  also: readonly string[] | undefined,
): string | null {
  if (!also || also.length === 0) return null;
  return `${String(label ?? "")} ⁻¹ ${also.join(" / ")}`;
}
