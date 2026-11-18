/* 图谱页数据面（W1，任务 5）：平行边扇开与时间过滤的纯函数——自旧仓
   Graph.tsx 逐字移植（Ⅲ类重写随页迁，抽到独立模块供 vitest 直接钉语义）。
   交互/hover/拖拽属任务 9，不在此。 */
import type { GraphEdge } from "@/api/knowledge/types";

const EDGE_CURVATURE_STEP = 0.18;
export const DAY_MS = 24 * 3600 * 1000;

/** 一条边画在哪条弧上；`curvature === 0` = 直线。 */
export interface PlacedEdge {
  edge: GraphEdge;
  curvature: number;
  /** 并进这条边的别的说法（逆关系），悬停时一并显示 */
  alsoLabels: string[];
}

/** 同一对节点之间的边，画在各自的弧上；逆关系推出来的那些先并掉。
 *
 *  **两件事，顺序有讲究：先减后分。**
 *
 *  一、`A works_at B` 与它推出的 `B employs A` 是**同一件事的两种说法**，
 *  不是两条知识。画成两条弧只是把冗余画得好看一点。所以逆关系推出来的边
 *  并进它的来源边，说法挂在那条边上。`sub_property`（`ceo_of ⊑ works_at`）
 *  不并——那是两条粒度不同的事实，各自成立。
 *
 *  二、剩下的按**无向对**分组扇开。无向是要点：一条边和它的反向边起终点相反，
 *  按有向对分组会各自成组、各自以为自己是独苗，于是又叠回同一条直线上。
 *  分组用 min/max，而落到弧上时按边自己的方向翻符号——sigma 的曲率是相对
 *  source→target 的，不翻的话反向的弧会绕到同一侧。 */
export function layOutParallelEdges(edges: GraphEdge[]): {
  edges: PlacedEdge[];
  folded: number;
} {
  const pairKey = (a: string, b: string) => (a < b ? `${a} ${b}` : `${b} ${a}`);
  const push = (m: Map<string, GraphEdge[]>, k: string, e: GraphEdge) => {
    const list = m.get(k);
    if (list) list.push(e);
    else m.set(k, [e]);
  };

  // ---- 一、并掉逆关系推出来的边
  const survivors: GraphEdge[] = [];
  const inverses: GraphEdge[] = [];
  for (const e of edges) {
    if (e.derived && e.rule === "inverse") inverses.push(e);
    else survivors.push(e);
  }
  /* **按前提找来源边，不是按节点对。**
     一度写成「取那一对节点上的第一条边」，于是 `contains` 挂到了恰好也连着
     那两点的 `allied_with` 上——而 `contains` 属于 `part_of`。挂错之后
     界面上看着完全正常，是最难发现的那一种。前提是服务端算出来的，用它。 */
  const onScreen = new Map<string, GraphEdge>();
  for (const e of survivors) onScreen.set(e.id, e);

  const also = new Map<string, string[]>();
  let folded = 0;
  for (const e of inverses) {
    const host = (e.premises ?? []).map(p => onScreen.get(p)).find(Boolean);
    if (!host) {
      // 来源边不在这一屏（时间轴筛掉了，或它自己也是推出来的而被过滤了）。
      // **那就留着它**——并进一条不存在的边等于把这条知识删了
      survivors.push(e);
      continue;
    }
    const list = also.get(host.id) ?? [];
    // 去重：传递推出来的几条 `part_of` 各有各的逆，而前提链都回到同一条边上，
    // 于是同一个说法会被挂三遍。**说法是名字，不是计数**
    const name = e.label ?? e.predicate ?? "";
    if (!list.includes(name)) list.push(name);
    also.set(host.id, list);
    folded++;
  }

  // ---- 二、剩下的按无向对扇开
  const groups = new Map<string, GraphEdge[]>();
  for (const e of survivors) push(groups, pairKey(e.source, e.target), e);

  const placed: PlacedEdge[] = [];
  for (const group of groups.values()) {
    const n = group.length;
    group.forEach((e, i) => {
      // 围绕直线对称铺开：n=1 → [0]；n=2 → [-0.5, 0.5]；n=3 → [-1, 0, 1]
      const offset = n === 1 ? 0 : i - (n - 1) / 2;
      const sign = e.source < e.target ? 1 : -1;
      placed.push({
        edge: e,
        curvature: offset === 0 ? 0 : sign * offset * EDGE_CURVATURE_STEP,
        alsoLabels: also.get(e.id) ?? []
      });
    });
  }
  return { edges: placed, folded };
}

/** 时间过滤：计算 T 时刻的活跃边/节点集合（旧仓 recomputeActive 逐字）。
 *  `t === null` = 全时段（两个集合都为 null——reducer 以 null 判「不筛」）。
 *  没起点的边从最早证据起才亮，结束了不知哪天的到说出它的那份文档就灭；
 *  NULL 一端才是开放。没有任何边的孤立节点保持可见。 */
export function computeActiveAt(
  d: { nodes: { id: string }[]; edges: GraphEdge[] },
  t: number | null
): { nodes: Set<string> | null; edges: Set<string> | null; count: number } {
  if (t === null) {
    return { nodes: null, edges: null, count: d.edges.length };
  }
  const edges = new Set<string>();
  const nodes = new Set<string>();
  const touched = new Set<string>();
  for (const e of d.edges) {
    const hf = e.holds_from ? Date.parse(e.holds_from) : null;
    const ht = e.holds_to ? Date.parse(e.holds_to) : null;
    touched.add(e.source);
    touched.add(e.target);
    const active = (hf === null || hf <= t) && (ht === null || ht > t);
    if (active) {
      edges.add(e.id);
      nodes.add(e.source);
      nodes.add(e.target);
    }
  }
  for (const n of d.nodes) if (!touched.has(n.id)) nodes.add(n.id);
  return { nodes, edges, count: edges.size };
}
