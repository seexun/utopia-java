/**
 * 谓词键 → 中文展示映射（国内风收口 2026-11-24 走查 A3）。
 *
 * 关系边标签来自图谱 API 的 relation_types.key（invested_in 这类 snake_case），
 * 与全中文界面混杂。这里按 relation_types 的 key 全量建表（值取同表 label 列
 * 的中文）；未知键 fallback 原文显示。数据源：
 *   SELECT key, label FROM relation_types ORDER BY key;
 * 新增关系类型时此表同步补一行（展示层常量，不做后端字典）。
 */
export const PREDICATE_ZH: Record<string, string> = {
  ceo_of: "出任CEO",
  co_builds: "共建",
  expands_at: "扩产",
  founded: "成立于",
  invested_in: "投资了",
  known_as: "known as",
  located_in: "位于",
  partners_with: "合作",
  revenue: "营收",
  supplies_to: "供货给",
  works_at: "任职于",
};

/** 边标签中文映射（未知键 fallback 原文）。⚠ 前缀（争议边）由调用方处理。 */
export function zhEdge(label: string): string {
  return PREDICATE_ZH[label] ?? label;
}
