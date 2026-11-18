/* W1 图谱页数据面（任务 5）：平行边扇开与时间过滤——旧仓 Graph.tsx 的两段
   纯逻辑在 Vue 侧的语义钉（抽到 views/jknow/knowledge/graph/parallel-edges.ts）。 */
import { describe, it, expect } from "vitest";
import {
  layOutParallelEdges,
  computeActiveAt,
  DAY_MS
} from "@/views/jknow/knowledge/graph/parallel-edges";
import type { GraphEdge } from "@/api/knowledge/types";

function edge(over: Partial<GraphEdge> & { id: string; source: string; target: string }): GraphEdge {
  return {
    label: "works for",
    predicate: "worksFor",
    derived: false,
    inferred: false,
    contested: false,
    blocked: false,
    rule: null,
    premises: null,
    holds_from: null,
    holds_to: null,
    ...over
  } as GraphEdge;
}

describe("layOutParallelEdges：逆关系先并掉，剩下的按无向对扇开", () => {
  it("逆关系推出来的边并进它的来源边（按前提找，不按节点对），说法去重", () => {
    const host = edge({ id: "e1", source: "A", target: "B", label: "part of" });
    const inv1 = edge({
      id: "e2",
      source: "B",
      target: "A",
      derived: true,
      rule: "inverse",
      label: "contains",
      premises: ["e1"]
    });
    const inv2 = edge({
      id: "e3",
      source: "B",
      target: "A",
      derived: true,
      rule: "inverse",
      label: "contains",
      premises: ["e1"]
    });
    // 诱饵：同一对节点上无关的边——前提不在它身上，不许挂错
    const decoy = edge({ id: "e4", source: "A", target: "B", label: "allied with" });
    const r = layOutParallelEdges([host, inv1, inv2, decoy]);
    expect(r.folded).toBe(2);
    // 两条边：host 与 decoy 同一无向对——双双扇开，不叠一条直线
    const placed = r.edges.map(p => p.edge.id);
    expect(placed).toEqual(["e1", "e4"]);
    const hostPlaced = r.edges.find(p => p.edge.id === "e1")!;
    // 说法只挂在 host 上且去重成一条
    expect(hostPlaced.alsoLabels).toEqual(["contains"]);
    const decoyCurv = r.edges.find(p => p.edge.id === "e4")!.curvature;
    expect(hostPlaced.curvature).toBe(-decoyCurv);
    expect(hostPlaced.curvature).not.toBe(0);
  });

  it("同一无向对的两条边对称扇开：曲率值相等——sigma 的曲率相对 source→target，     方向相反的两条边拿同一个值恰好绕到两侧（旧仓注释的语义）", () => {
    const a = edge({ id: "e1", source: "B", target: "A", label: "x" });
    const b = edge({ id: "e2", source: "A", target: "B", label: "y" });
    const r = layOutParallelEdges([a, b]);
    const ca = r.edges.find(p => p.edge.id === "e1")!.curvature;
    const cb = r.edges.find(p => p.edge.id === "e2")!.curvature;
    expect(ca).toBe(cb);
    expect(ca).not.toBe(0);
  });

  it("来源边不在这一屏的逆关系保留——并进不存在的边等于把这条知识删了", () => {
    const inv = edge({
      id: "e2",
      source: "B",
      target: "A",
      derived: true,
      rule: "inverse",
      label: "contains",
      premises: ["gone"]
    });
    const r = layOutParallelEdges([inv]);
    expect(r.edges.map(p => p.edge.id)).toEqual(["e2"]);
  });
});

describe("computeActiveAt：双时间轴过滤（0022 的口径按读出来的区间判）", () => {
  const data = {
    nodes: [{ id: "A" }, { id: "B" }, { id: "C" }, { id: "Z" }],
    edges: [
      edge({
        id: "e1",
        source: "A",
        target: "B",
        holds_from: "2020-01-01T00:00:00Z",
        holds_to: null
      }),
      edge({
        id: "e2",
        source: "B",
        target: "C",
        holds_from: "2021-01-01T00:00:00Z",
        holds_to: "2022-06-01T00:00:00Z"
      }),
      // 孤立节点：没有任何边
      edge({ id: "e3", source: "X", target: "Y" })
    ]
  };

  it("null = 全时段：不筛（reducer 以 null 判「不筛」）", () => {
    const r = computeActiveAt(data, null);
    expect(r.nodes).toBeNull();
    expect(r.edges).toBeNull();
    expect(r.count).toBe(3);
  });

  it("T=2021 中：e1/e2 已亮，e3（无时态）恒亮；触达节点在列", () => {
    const t = Date.parse("2021-06-01T00:00:00Z");
    const r = computeActiveAt(data, t);
    expect(r.edges).toEqual(new Set(["e1", "e2", "e3"]));
    expect(r.nodes).toEqual(new Set(["A", "B", "C", "X", "Y", "Z"]));
  });

  it("T=2023 中：e2 已结束（读出的 to < T 而灭）；孤立节点 Z 保持可见", () => {
    const t = Date.parse("2023-01-01T00:00:00Z");
    const r = computeActiveAt(data, t);
    expect(r.edges).toEqual(new Set(["e1", "e3"]));
    expect(r.nodes).toEqual(new Set(["A", "B", "X", "Y", "Z"]));
  });

  it("边界：holds_to === T 不算活跃（开放区间右开）", () => {
    const t = Date.parse("2022-06-01T00:00:00Z");
    const r = computeActiveAt(data, t);
    expect(r.edges.has("e2")).toBe(false);
    void DAY_MS;
  });
});
