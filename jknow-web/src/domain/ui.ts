/* 【任务3抽离】自旧仓 web/src/ui/index.tsx 取纯时间函数（localDate/localDateTime，
   steps.ts 消费 localDateTime）；React 组件面留在旧仓（Ⅲ类-重写）。除本头注外逐字原样。 */

/* 时刻按看的人的时区显示。**只给时刻用**：recorded_at、created_at、上传时间这类
   "何时发生"的值。文档里写的日历日期（"2019 年 5 月"）没有时区，另走 ISO 切片，
   转成本地会让 UTC-5 的读者看到前一天。EntityHistory 里的判据同一条 */
export function localDate(iso: string): string {
  const d = new Date(iso);
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}
export function localDateTime(iso: string): string {
  const d = new Date(iso);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${localDate(iso)} ${hh}:${mm}`;
}
