/* W3 图谱交互（任务 9）：证明树 walker 的形状钉（自旧仓 Graph.test.ts 对齐移植）
   ——walkProofSteps 深度单调、叶子不展开、相邻行深度差 ≤1；缩进类六层封顶且
   不发互相冲突的覆盖类。节点/边的交互状态机本体在 domain/pages/graphInteraction
   （colocated 例已入 domain 测试面），此处是面板证明链的 shape 层。 */
import { describe, expect, it } from "vitest";
import {
  walkProofSteps,
  proofIndentClass,
  PROOF_INDENT_CAP,
  type WalkedRow
} from "@/views/jknow/knowledge/graph/proof";
import type { ProofStep } from "@/api/knowledge/types";

function step(
  id: string,
  premises: ProofStep[] = [],
  partial: Partial<ProofStep> = {}
): ProofStep {
  return {
    seq: 0,
    fact_id: id,
    subject_id: id,
    subject: id,
    predicate_id: null,
    predicate: "→",
    object_id: null,
    object: id,
    valid_from: null,
    valid_to: null,
    confidence: 1,
    retracted: false,
    evidence: [],
    premises,
    ...partial
  };
}

describe("walkProofSteps：递归证明树压平成行", () => {
  it("叶子（premises 为空）只有一个深度 0 的行", () => {
    const rows = walkProofSteps([step("leaf")]);
    expect(rows).toHaveLength(1);
    expect(rows[0].depth).toBe(0);
    expect(rows[0].has_premises).toBe(false);
    expect(rows[0].fact_id).toBe("leaf");
  });

  it("三层嵌套的树产出 9 行，深度 0..3 单调（DFS 前序）", () => {
    const tree = [
      step("A", [
        step("B", [step("D", [step("G")]), step("E", [step("H")])]),
        step("C", [step("F", [step("I")])])
      ])
    ];
    expect(walkProofSteps(tree).map((r: WalkedRow) => r.fact_id)).toEqual([
      "A", "B", "D", "G", "E", "H", "C", "F", "I"
    ]);
    expect(walkProofSteps(tree).map((r: WalkedRow) => r.depth)).toEqual([
      0, 1, 2, 3, 2, 3, 1, 2, 3
    ]);
    // 深度从不超过 4；相邻行深度差不超过 1（刚下钻或爬回/平移）
    const rows = walkProofSteps(tree);
    for (const r of rows) {
      expect(r.depth).toBeGreaterThanOrEqual(0);
      expect(r.depth).toBeLessThanOrEqual(3);
    }
    for (let i = 1; i < rows.length; i++) {
      expect(rows[i].depth - rows[i - 1].depth).toBeLessThanOrEqual(1);
    }
  });

  it("叶子行 has_premises=false；非叶子行必为 true；撤回与证据数照抄", () => {
    const rows = walkProofSteps([
      step("A", [step("B", [step("leaf")])], { retracted: true, evidence: [] })
    ]);
    const byId = Object.fromEntries(rows.map(r => [r.fact_id, r]));
    expect(byId["A"].has_premises).toBe(true);
    expect(byId["B"].has_premises).toBe(true);
    expect(byId["leaf"].has_premises).toBe(false);
    expect(byId["A"].retracted).toBe(true);
    expect(byId["leaf"].evidence_count).toBe(0);
  });

  it("深度起点偏移：depth=2 时整棵树每个节点的深度都比直接调用多 2", () => {
    const tree = [step("A", [step("B")])];
    expect(walkProofSteps(tree, 0).map(r => r.depth)).toEqual([0, 1]);
    expect(walkProofSteps(tree, 2).map(r => r.depth)).toEqual([2, 3]);
  });
});

// 缩进封顶：仓库里没有 tailwind-merge，同时发 `ml-4` 与 `ml-0` 时谁生效由
// 样式表顺序决定——那样的封顶形同虚设。这里钉住的是「深到一定层数就不再发
// 缩进类」，而不是「再发一个类把它盖掉」
describe("proofIndentClass：六层封顶", () => {
  it("封顶以内：带缩进、内边距与那条竖线", () => {
    for (let depth = 0; depth < PROOF_INDENT_CAP; depth++) {
      const cls = proofIndentClass(depth);
      expect(cls).toContain("ml-4");
      expect(cls).toContain("pl-3");
      expect(cls).toContain("border-l");
    }
  });

  it("到了封顶就不再发缩进类", () => {
    for (const depth of [PROOF_INDENT_CAP, PROOF_INDENT_CAP + 1, 12]) {
      const cls = proofIndentClass(depth);
      expect(cls).not.toContain("ml-4");
      expect(cls).not.toContain("pl-3");
      expect(cls).not.toContain("border-l");
    }
  });

  it("任何深度都不会同时发出互相冲突的两个类", () => {
    for (let depth = 0; depth <= 12; depth++) {
      const parts = proofIndentClass(depth).split(/\s+/).filter(Boolean);
      for (const [a, b] of [
        ["ml-4", "ml-0"],
        ["pl-3", "pl-0"],
        ["border-l", "border-l-0"]
      ]) {
        expect(parts.includes(a) && parts.includes(b)).toBe(false);
      }
      const margins = parts.filter(c => /^ml-/.test(c));
      expect(margins.length).toBeLessThanOrEqual(1);
    }
  });

  it("每一层都留着 mt-1，封顶只去掉横向的缩进", () => {
    for (let depth = 0; depth <= 12; depth++) {
      expect(proofIndentClass(depth)).toContain("mt-1");
    }
  });
});
