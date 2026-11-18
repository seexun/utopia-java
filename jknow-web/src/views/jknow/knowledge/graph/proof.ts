/* 证明树的形状层（任务 9，自旧仓 Graph.tsx 逐字移植）：walkProofSteps 把递归
   premises 树压平成行（单测据此断言「深度单调、叶子不展开」），proofIndentClass
   是子证明块的缩进（六层封顶——384px 的面板吃不起无限缩进）。渲染是组件的事
   （entity-panel.tsx 的 ProofSteps），shape 是这里的事。 */
import type { ProofStep } from "@/api/knowledge/types";

/** 证明树压平后的一行：保留深度与父链，单测据此断言 */
export interface WalkedRow {
  fact_id: string;
  depth: number;
  subject: string;
  predicate: string | null;
  object: string | null;
  retracted: boolean;
  has_premises: boolean;
  evidence_count: number;
}

/** 把递归 `premises` 树压平成一行行。深度优先，子证明排在父之后。
 *
 * 这是递归渲染的「骨架」：组件递归地走 `premises`，单测通过这个函数
 * 断言「三层嵌套时，叶子不再展开，深度单调递增，相邻行深度差不超过 1」 */
export function walkProofSteps(steps: ProofStep[], depth = 0): WalkedRow[] {
  const out: WalkedRow[] = [];
  const visit = (nodes: ProofStep[], d: number) => {
    for (const s of nodes) {
      out.push({
        fact_id: s.fact_id,
        depth: d,
        subject: s.subject,
        predicate: s.predicate,
        object: s.object,
        retracted: s.retracted,
        has_premises: s.premises.length > 0,
        evidence_count: s.evidence.length
      });
      if (s.premises.length > 0) {
        visit(s.premises, d + 1);
      }
    }
  };
  visit(steps, depth);
  return out;
}

/** 缩进到这一层为止。再深就不缩了——每层 ml-4 + pl-3 是 28 px，六层已经
 *  吃掉 168 px，而面板只有 384 px 宽。服务器那道递归上限（0030）管的是链
 *  能有多长，这里管的是窄屏里还看得清 */
export const PROOF_INDENT_CAP = 6;

/** 子证明那一块的缩进类。
 *
 * **按条件拼基础类，不叠覆盖类**：同时发 `ml-4` 与 `ml-0` 时，谁生效由
 * 样式表顺序决定，跟这里写的先后无关——那样的封顶等于没封 */
export function proofIndentClass(depth: number): string {
  return depth >= PROOF_INDENT_CAP
    ? "mt-1"
    : "mt-1 ml-4 pl-3 border-l border-edge";
}
