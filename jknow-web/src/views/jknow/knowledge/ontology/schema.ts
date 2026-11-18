/* 本体模式图的纯函数投影（对照旧仓 pages/OntologySchemaGraph.tsx 取 buildSchemaGraph /
   schemaScope / layOutParallelEdges / classDepths 及其常量——Ⅲ类重写随页迁，逻辑逐字）。
   不碰 Sigma/Vue，方便单独验证语义是否正确：多重继承是不是都进了图、互斥有没有被
   对称声明重复、平行关系有没有叠成一条、未限定的关系有没有被错误地画成全连接。 */
import Graphology from "graphology";
import type {
  BusinessRule,
  EntityTypeView,
  RelationTypeView
} from "@/api/knowledge/types";
import { S } from "@/i18n";
import { STROKE_WHITE, TRANSPARENT, refreshPalette } from "@/domain/pages/graphVisuals";
import { NODE_TYPE_SHELL, NODE_TYPE_SQUARE } from "@/domain/pages/graphCanvas";

/* ============ 边的三种语义，与三种视觉语汇的映射（旧仓常量逐字） ============ */

/** 三种边各自的语义——驱动颜色/暗淡/可点选，与「用哪个 sigma 程序画」分开管 */
export const SUBCLASS_KIND = "subclass";
export const RELATION_KIND = "relation";
export const DISJOINT_KIND = "disjoint";
export const RULE_KIND = "rule";

/** sigma 的渲染派发键。故意与上面的语义分开 */
export const EDGE_TYPE_ARROW = "arrow";
export const EDGE_TYPE_CURVED_ARROW = "curvedArrow";
export const EDGE_TYPE_LINE = "line";
export const EDGE_TYPE_CURVED_LINE = "curvedLine";

/** 方向 A（规格 §3）：结构 1.5、关系 2.5——与 /graph 的边宽同一族 */
export const SUBCLASS_EDGE_SIZE = 1.5;
export const DISJOINT_EDGE_SIZE = 1.5;
export const RULE_EDGE_SIZE = 2.5;
export const RELATION_EDGE_SIZE = 2.5;
/** 同一对类、同一个方向上超过这么多条关系，就并成一条带计数的边 */
export const BUNDLE_ABOVE = 3;
/** sigma 边渲染的最小厚度（像素）——「点得中」的底线 */
export const MIN_EDGE_THICKNESS = 2;
/** 节点大小按层级深度走：根最大，每往下一层小一档 */
export const NODE_SIZE_ROOT = 16;
export const NODE_SIZE_STEP = 2.5;
export const NODE_SIZE_MIN = 8;

/** 并排偏移的步长；同一对类之间的关系边超过一条时用它扇开 */
export const RELATION_CURVATURE_STEP = 0.22;
/** 自环给一个固定起始弯曲，否则退化成一个看不见的点 */
export const SELF_LOOP_BASE_CURVATURE = 1;

/** 类数不超过这个数就全画——几十个类的手工本体，藏起一部分只会让人找不到 */
export const FULL_VIEW_MAX_CLASSES = 60;

/** 画多少个类的可选档位，与实例图的 NODE_BUDGETS 同构 */
export const SCHEMA_BUDGETS: number[] = [30, 60, 120, 240];

export interface SchemaScope {
  /** 画到画布上的类；null 表示全画 */
  drawn: ReadonlySet<string> | null;
  /** 取景依据：all 全画；in-use 有实例的类及其祖先；top 一个实例都没有,画层级最上面两层 */
  basis: "all" | "in-use" | "top";
  /** 没画的类数 */
  hidden: number;
}

/** 默认取景。本体小就全画；大了只画库用到的类——有实例的，连同祖先 */
export function schemaScope(
  entityTypes: EntityTypeView[],
  revealed: ReadonlySet<string>,
  budget: number = SCHEMA_BUDGETS[0]
): SchemaScope {
  if (entityTypes.length <= FULL_VIEW_MAX_CLASSES) {
    return { drawn: null, basis: "all", hidden: 0 };
  }
  const byId = new Map(entityTypes.map(t => [t.id, t]));
  const drawn = new Set<string>();
  // 已经在集合里就不再往上走——父类互指成环也走不死
  const addWithAncestors = (id: string) => {
    const t = byId.get(id);
    if (!t || drawn.has(id)) return;
    drawn.add(id);
    for (const parentId of t.parents) addWithAncestors(parentId);
  };
  /* 用得最多的那几个，按档位取；祖先不占额度 */
  const inUse = entityTypes
    .filter(t => t.usage > 0)
    .sort((a, b) => b.usage - a.usage || a.label.localeCompare(b.label))
    .slice(0, budget);
  for (const t of inUse) addWithAncestors(t.id);
  const basis: SchemaScope["basis"] = drawn.size > 0 ? "in-use" : "top";
  if (basis === "top") {
    // 根：没有一个父类指向真实存在的类（坏引用与自指都不算父类）
    const rootIds = new Set(
      entityTypes
        .filter(t => !t.parents.some(p => p !== t.id && byId.has(p)))
        .map(t => t.id)
    );
    for (const t of entityTypes) {
      if (rootIds.has(t.id) || t.parents.some(p => rootIds.has(p)))
        drawn.add(t.id);
    }
  }
  for (const id of revealed) addWithAncestors(id);
  return { drawn, basis, hidden: entityTypes.length - drawn.size };
}

export interface SchemaGraphResult {
  graph: Graphology;
  /** domains 或 ranges 留空的关系——本体没把它限定在哪个类上，画不出边 */
  unscoped: RelationTypeView[];
}

/** 每个类在层级里的深度：根 0，往下每层 +1；多重继承取最浅的一条 */
function classDepths(entityTypes: EntityTypeView[]): Map<string, number> {
  const byId = new Map(entityTypes.map(t => [t.id, t]));
  const depths = new Map<string, number>();
  const walking = new Set<string>();
  const depthOf = (id: string): number => {
    const known = depths.get(id);
    if (known !== undefined) return known;
    const t = byId.get(id);
    if (!t || walking.has(id)) return 0;
    walking.add(id);
    let depth = 0;
    for (const parentId of t.parents) {
      if (parentId === id || !byId.has(parentId)) continue;
      const d = depthOf(parentId) + 1;
      if (depth === 0 || d < depth) depth = d;
    }
    walking.delete(id);
    depths.set(id, depth);
    return depth;
  };
  for (const t of entityTypes) depthOf(t.id);
  return depths;
}

/** 同一对类之间的多条边各自扇到一条独立的弧上，不叠成一条谁也点不中的线。
 *  不分种类：继承、互斥、关系三种边都可能落在同一对类上。 */
function layOutParallelEdges(graph: Graphology): void {
  const groups = new Map<string, string[]>();
  graph.forEachEdge((edge, _attrs, source, target) => {
    const key =
      source === target ? `loop:${source}` : [source, target].sort().join("|");
    const list = groups.get(key);
    if (list) list.push(edge);
    else groups.set(key, [edge]);
  });
  const typeFor = (kind: string, curved: boolean) =>
    kind === DISJOINT_KIND
      ? curved
        ? EDGE_TYPE_CURVED_LINE
        : EDGE_TYPE_LINE
      : curved
        ? EDGE_TYPE_CURVED_ARROW
        : EDGE_TYPE_ARROW;
  for (const edges of groups.values()) {
    const n = edges.length;
    edges.forEach((edge, i) => {
      const [source, target] = graph.extremities(edge);
      const kind = graph.getEdgeAttribute(edge, "kind") as string;
      if (source === target) {
        graph.mergeEdgeAttributes(edge, {
          curvature: SELF_LOOP_BASE_CURVATURE + i * RELATION_CURVATURE_STEP,
          type: typeFor(kind, true)
        });
        return;
      }
      const offset = n === 1 ? 0 : i - (n - 1) / 2;
      // sigma 的弯曲度相对这条边自己的 source→target 而言，符号得按谁小谁大归一化
      const sign = source < target ? 1 : -1;
      const curvature =
        offset === 0 ? 0 : sign * offset * RELATION_CURVATURE_STEP;
      graph.mergeEdgeAttributes(edge, {
        curvature,
        type: typeFor(kind, curvature !== 0)
      });
    });
  }
}

/** 本体 → 模式图的纯函数投影（旧仓 buildSchemaGraph 逐字） */
export function buildSchemaGraph(
  entityTypes: EntityTypeView[],
  relationTypes: RelationTypeView[],
  /** 取景（见 schemaScope）：只有这些类进画布；null 全画 */
  drawn: ReadonlySet<string> | null = null,
  /** 业务规则：一条得出类的规则就是两个类之间的一条边 */
  rules: BusinessRule[] = []
): SchemaGraphResult {
  // 边色烤进图属性，构图前先把调色板读成当前主题的值（0038）
  refreshPalette();
  const graph = new Graphology({ multi: true });
  const byId = new Map(entityTypes.map(t => [t.id, t]));
  const depths = classDepths(entityTypes);

  for (const t of entityTypes) {
    if (drawn && !drawn.has(t.id)) continue;
    graph.addNode(t.id, {
      label: t.label,
      size: Math.max(
        NODE_SIZE_MIN,
        NODE_SIZE_ROOT - NODE_SIZE_STEP * (depths.get(t.id) ?? 0)
      ),
      // 与 /graph 同一套实色语言（规格 §2）：类型色实心 + 白描边
      color: t.color,
      shellColor: t.color,
      borderColor: STROKE_WHITE,
      ringColor: TRANSPARENT,
      typeColor: t.color,
      typeLabel: t.key,
      type: t.shape === "square" ? NODE_TYPE_SQUARE : NODE_TYPE_SHELL,
      key: t.key
    });
  }

  // subClassOf：子 → 父。全部 parents：多重继承必须在图上看得见
  for (const t of entityTypes) {
    if (!graph.hasNode(t.id)) continue;
    for (const parentId of t.parents) {
      if (parentId === t.id || !graph.hasNode(parentId)) continue;
      graph.addEdgeWithKey(`sub:${t.id}:${parentId}`, t.id, parentId, {
        kind: SUBCLASS_KIND,
        type: EDGE_TYPE_ARROW,
        size: SUBCLASS_EDGE_SIZE
      });
    }
  }

  // 互斥：无向声明，两边经常互相写一遍——按排序后的 id 对去重，只画一条
  const disjointSeen = new Set<string>();
  for (const t of entityTypes) {
    if (!graph.hasNode(t.id)) continue;
    for (const otherId of t.disjoint) {
      if (otherId === t.id || !graph.hasNode(otherId)) continue;
      const pairKey = [t.id, otherId].sort().join("|");
      if (disjointSeen.has(pairKey)) continue;
      disjointSeen.add(pairKey);
      graph.addEdgeWithKey(`dis:${pairKey}`, t.id, otherId, {
        kind: DISJOINT_KIND,
        type: EDGE_TYPE_LINE,
        size: DISJOINT_EDGE_SIZE
      });
    }
  }

  // 关系：attribute 的宾语是字面值，不是类，这里只处理 kind === "relation"
  const unscoped: RelationTypeView[] = [];
  const pairs = new Map<string, { d: string; rg: string; rels: RelationTypeView[] }>();
  for (const r of relationTypes) {
    if (r.kind !== "relation") continue;
    const domains = [...new Set(r.domains)].filter(id => byId.has(id));
    const ranges = [...new Set(r.ranges)].filter(id => byId.has(id));
    // domains/ranges 留空 = 本体没把这条关系限定在某个类上
    if (
      r.domains.length === 0 ||
      r.ranges.length === 0 ||
      domains.length === 0 ||
      ranges.length === 0
    ) {
      unscoped.push(r);
      continue;
    }
    const drawnDomains = domains.filter(id => graph.hasNode(id));
    const drawnRanges = ranges.filter(id => graph.hasNode(id));
    for (const d of drawnDomains) {
      for (const rg of drawnRanges) {
        const key = `${d}|${rg}`;
        const pair = pairs.get(key);
        if (pair) pair.rels.push(r);
        else pairs.set(key, { d, rg, rels: [r] });
      }
    }
  }

  /* 规则边。关掉的规则也画，只是暗一档 */
  for (const r of rules) {
    if (r.conclusion !== "typing" || !r.conclude_type_id) continue;
    if (!graph.hasNode(r.subject_type_id) || !graph.hasNode(r.conclude_type_id))
      continue;
    if (r.subject_type_id === r.conclude_type_id) continue;
    graph.addEdgeWithKey(`rule:${r.id}`, r.subject_type_id, r.conclude_type_id, {
      kind: RULE_KIND,
      ruleId: r.id,
      type: EDGE_TYPE_CURVED_ARROW,
      curvature: 0.35,
      label: r.name,
      size: RULE_EDGE_SIZE,
      enabled: r.enabled
    });
  }

  // 先按有向类对归堆再画：少的各画各的，多的并成一条带计数的边
  for (const { d, rg, rels } of pairs.values()) {
    if (rels.length <= BUNDLE_ABOVE) {
      for (const r of rels) {
        graph.addEdgeWithKey(`rel:${r.id}:${d}:${rg}`, d, rg, {
          kind: RELATION_KIND,
          relationIds: [r.id],
          label: r.label,
          size: RELATION_EDGE_SIZE
        });
      }
      continue;
    }
    graph.addEdgeWithKey(`bundle:${d}:${rg}`, d, rg, {
      kind: RELATION_KIND,
      relationIds: rels.map(r => r.id),
      label: S.ontology.schemaBundle(rels.length),
      // 并得越多越粗一点，但封顶
      size: RELATION_EDGE_SIZE + Math.min(2, Math.log2(rels.length) * 0.6)
    });
  }

  layOutParallelEdges(graph);
  return { graph, unscoped };
}
