/* 可视化同步日程选择器（W3 走查收口补迁，对照旧仓 pages/Library.tsx 的
   SchedulePicker：Manual / Interval / Daily / Weekly 构建，Advanced 才暴露 cron）。
   状态折叠复用 domain/pages/Library 的 scheduleToPickerState（Ⅱ(a) 已随迁）；
   CRON_WEEKDAYS 在旧仓是 Library.tsx 模块常量，此处按 UI 面就近复制（语义锚
   domain 侧同表，服务端 cron 三字母星期，别改）。 */
import { defineComponent, ref, watch, type PropType } from "vue";
import { ElInput } from "element-plus";
import { USegmented } from "@/components/knowledge/kit";
import { S } from "@/i18n";
import { scheduleToPickerState } from "@/domain/pages/Library";

export interface ScheduleValue {
  sync_interval_minutes: number | null;
  sync_cron: string | null;
}

const CRON_WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;

type ScheduleMode = "manual" | "interval" | "daily" | "weekly" | "advanced";

const pad2 = (n: number) => String(n).padStart(2, "0");
const TIME_SHAPE = /^([01]?\d|2[0-3]):[0-5]\d$/;

export const SchedulePicker = defineComponent({
  name: "USchedulePicker",
  props: {
    /** 编辑模式的既有日程；新建缺省从 Manual 起步（旧仓同） */
    initial: {
      type: Object as PropType<ScheduleValue | undefined>,
      default: undefined
    }
  },
  emits: ["change"],
  setup(props, { emit }) {
    const init = scheduleToPickerState(props.initial);
    const mode = ref<ScheduleMode>(init.mode as ScheduleMode);
    const every = ref(init.every);
    const unit = ref<"minutes" | "hours">(init.unit as "minutes" | "hours");
    const time = ref(init.time);
    const days = ref<Set<number>>(new Set(init.days));
    const cron = ref(init.cron);

    // 时间格式未成形时（手输中途）不更新日程，保留上一个有效值（旧仓 emit 同款守卫）
    const emitMode = (
      m: ScheduleMode,
      v: {
        every?: number;
        unit?: "minutes" | "hours";
        time?: string;
        days?: Set<number>;
        cron?: string;
      }
    ) => {
      const t = v.time ?? time.value;
      const [hh, mm] = t.split(":").map(Number);
      if ((m === "daily" || m === "weekly") && !TIME_SHAPE.test(t)) return;
      switch (m) {
        case "manual":
          emit("change", { sync_interval_minutes: null, sync_cron: null });
          break;
        case "interval": {
          const n = Math.max(1, v.every ?? every.value);
          const u = v.unit ?? unit.value;
          emit("change", {
            sync_interval_minutes: u === "hours" ? n * 60 : n,
            sync_cron: null
          });
          break;
        }
        case "daily":
          emit("change", {
            sync_interval_minutes: null,
            sync_cron: `${pad2(mm)} ${pad2(hh)} * * *`
          });
          break;
        case "weekly": {
          const ds = [...(v.days ?? days.value)].sort();
          const names = ds.map(i => CRON_WEEKDAYS[i]).join(",");
          emit("change", {
            sync_interval_minutes: null,
            sync_cron: ds.length ? `${pad2(mm)} ${pad2(hh)} * * ${names}` : null
          });
          break;
        }
        case "advanced":
          emit("change", {
            sync_interval_minutes: null,
            sync_cron: (v.cron ?? cron.value).trim() || null
          });
          break;
      }
    };

    // 挂载即有一个初值（旧仓初版从 initial 折出发；新建=manual=null/null）
    watch(
      mode,
      m => emitMode(m, {}),
      { immediate: true }
    );

    const modes: { key: ScheduleMode; label: string }[] = [
      { key: "manual", label: S.library.schedule.manual },
      { key: "interval", label: S.library.schedule.interval },
      { key: "daily", label: S.library.schedule.daily },
      { key: "weekly", label: S.library.schedule.weekly },
      { key: "advanced", label: S.library.schedule.advanced }
    ];

    return () => (
      <div>
        <USegmented
          fill
          size="sm"
          class="mb-2"
          value={mode.value}
          onChange={(k: string) => {
            mode.value = k as ScheduleMode;
            emitMode(k as ScheduleMode, {});
          }}
          options={modes.map(({ key, label }) => ({ value: key, label }))}
        />

        {mode.value === "interval" && (
          <div class="flex items-center gap-2 text-body text-ink-2">
            {S.library.schedule.every}
            <ElInput
              style={{ width: "64px" }}
              modelValue={every.value}
              {...{
                "onUpdate:modelValue": (v: string | number) => {
                  const n = Number(v) || 1;
                  every.value = n;
                  emitMode("interval", { every: n });
                }
              }}
            />
            {/* 两个选项不配下拉：分段切换，与上方模式条同配方（旧仓注释原话） */}
            <USegmented
              size="sm"
              value={unit.value}
              onChange={(u: string) => {
                unit.value = u as "minutes" | "hours";
                emitMode("interval", { unit: unit.value });
              }}
              options={(["minutes", "hours"] as const).map(u => ({
                value: u,
                label: S.library.schedule[u]
              }))}
            />
          </div>
        )}

        {(mode.value === "daily" || mode.value === "weekly") && (
          <div class="space-y-2">
            {mode.value === "weekly" && (
              <div class="flex gap-1">
                {(S.library.schedule.daysShort as readonly string[]).map((d, i) => (
                  <button
                    key={d}
                    type="button"
                    class={[
                      "flex-1 rounded-ctl border px-2 py-1 text-aux transition-colors",
                      days.value.has(i)
                        ? "border-primary bg-primary-soft text-primary"
                        : "border-line bg-card text-ink-2 hover:bg-fill"
                    ]}
                    onClick={() => {
                      const next = new Set<number>(days.value);
                      if (next.has(i)) next.delete(i);
                      else next.add(i);
                      days.value = next;
                      emitMode("weekly", { days: next });
                    }}
                  >
                    {d}
                  </button>
                ))}
              </div>
            )}
            <div class="flex items-center gap-2 text-body text-ink-2">
              {S.library.schedule.at}
              {/* 24h 纯文本输入：原生 time 控件块头大且跟随系统语言（旧仓注释原话） */}
              <ElInput
                style={{ width: "80px" }}
                class={["tabular-nums", !TIME_SHAPE.test(time.value) ? "time-bad" : ""]}
                placeholder="09:00"
                modelValue={time.value}
                {...{
                  "onUpdate:modelValue": (v: string) => {
                    time.value = v;
                    if (TIME_SHAPE.test(v)) emitMode(mode.value, { time: v });
                  }
                }}
              />
            </div>
          </div>
        )}

        {mode.value === "advanced" && (
          <div>
            {/* 表达式用 mono，placeholder 回默认字体（旧仓注释原话） */}
            <ElInput
              class="w-full font-mono"
              placeholder={(S.library.schedule.cronPlaceholder as string)}
              modelValue={cron.value}
              {...{
                "onUpdate:modelValue": (v: string) => {
                  cron.value = v;
                  emitMode("advanced", { cron: v });
                }
              }}
            />
            <a
              href={(S.library.schedule.cronDocsUrl as string)}
              target="_blank"
              rel="noreferrer"
              class="link-inline mt-2 inline-block text-aux"
            >
              {S.library.schedule.whatIsCron}
            </a>
          </div>
        )}
      </div>
    );
  }
});
