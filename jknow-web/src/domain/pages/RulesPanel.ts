/* 【任务3抽离】自旧仓 web/src/pages/RulesPanel.tsx 取纯操作数解析（OPS/operandKind/
   parseOperand；ruleEmptyOperands、ruleRangeOperands 两用例随迁全绿）；
   规则编辑台组件面留在旧仓（Ⅲ类-重写）。除本头注与 import 外逐字原样。 */
import { S } from "../i18n";


/** op → 那句话里的动词。**数字与集合两类分开**，因为它们的操作数长得不一样 */
const OPS: {
  value: string;
  label: () => string;
  operand: "num" | "range" | "set" | "none";
}[] = [
  { value: "gt", label: () => S.ontology.ruleOpGt, operand: "num" },
  { value: "gte", label: () => S.ontology.ruleOpGte, operand: "num" },
  { value: "lt", label: () => S.ontology.ruleOpLt, operand: "num" },
  { value: "lte", label: () => S.ontology.ruleOpLte, operand: "num" },
  { value: "between", label: () => S.ontology.ruleOpBetween, operand: "range" },
  { value: "in", label: () => S.ontology.ruleOpIn, operand: "set" },
  { value: "not_in", label: () => S.ontology.ruleOpNotIn, operand: "set" },
  { value: "present", label: () => S.ontology.ruleOpPresent, operand: "none" },
];

const operandKind = (op: string) =>
  OPS.find((o) => o.value === op)?.operand ?? "num";

/** 输入框文本 → 操作数。**解析不出来就返回 undefined**，由调用方拦在保存之前 */
export function parseOperand(op: string, text: string): unknown | undefined {
  const kind = operandKind(op);
  if (kind === "none") return undefined;
  const t = text.trim();
  if (!t) return undefined;
  if (kind === "set") {
    const set = t
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    return set.length ? set : undefined;
  }
  if (kind === "range") {
    const number = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
    const match = new RegExp(`^(${number})\\s*[-~]\\s*(${number})$`).exec(t);
    const parts = match ? [Number(match[1]), Number(match[2])] : [];
    return parts.length === 2 && parts.every((n) => Number.isFinite(n))
      ? parts
      : undefined;
  }
  const n = Number(t);
  return Number.isFinite(n) ? n : undefined;
}
