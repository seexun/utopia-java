/* 【任务3抽离】自旧仓 web/src/pages/Library.tsx 取纯日程状态函数 scheduleToPickerState；
   weeklyScheduleLocale、scheduleIncompleteTime 两用例走 SchedulePicker 组件树（React 绑定），
   留旧仓Ⅲ类-重写；页面组件面留在旧仓。除本头注外逐字原样。 */

/** 调度器产出的值：interval 与 cron 互斥。 */
interface ScheduleValue {
  sync_interval_minutes: number | null;
  sync_cron: string | null;
}

const pad2 = (n: number | string) => String(n).padStart(2, "0");

type ScheduleMode = "manual" | "interval" | "daily" | "weekly" | "advanced";

/** 已存日程 → 选择器初始状态（编辑模式回显；识别不了的 cron 落到 Advanced）。 */
const CRON_WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;

export function scheduleToPickerState(initial?: ScheduleValue) {
  const base = {
    mode: "manual" as ScheduleMode,
    every: 30,
    unit: "minutes" as "minutes" | "hours",
    time: "09:00",
    days: new Set([0]),
    cron: "",
  };
  if (!initial) return base;
  if (initial.sync_cron) {
    const daily = initial.sync_cron.match(/^(\d{1,2}) (\d{1,2}) \* \* \*$/);
    if (daily)
      return { ...base, mode: "daily" as ScheduleMode, time: `${pad2(daily[2])}:${pad2(daily[1])}` };
    const weekly = initial.sync_cron.match(/^(\d{1,2}) (\d{1,2}) \* \* ([A-Za-z,]+)$/);
    if (weekly) {
      const idx = weekly[3]
        .split(",")
        .map((n) => (CRON_WEEKDAYS as readonly string[]).indexOf(n))
        .filter((i) => i >= 0);
      if (idx.length)
        return {
          ...base,
          mode: "weekly" as ScheduleMode,
          time: `${pad2(weekly[2])}:${pad2(weekly[1])}`,
          days: new Set(idx),
        };
    }
    return { ...base, mode: "advanced" as ScheduleMode, cron: initial.sync_cron };
  }
  if (initial.sync_interval_minutes) {
    const m = initial.sync_interval_minutes;
    return m % 60 === 0
      ? { ...base, mode: "interval" as ScheduleMode, every: m / 60, unit: "hours" as const }
      : { ...base, mode: "interval" as ScheduleMode, every: m, unit: "minutes" as const };
  }
  return base;
}
