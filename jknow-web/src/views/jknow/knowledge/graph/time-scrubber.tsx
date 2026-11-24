/* 时间轴（底部居中悬浮岛：播放 + 密度带 + 拖动）。
   对照旧仓 pages/Graph.tsx 的 TimeScrubber 逐行重写为 Vue。
   柱子按边开始成立的时刻分格；播放连续推进（单位只管显示不管步长）；
   All time / Now 双锚点分段。 */
import { computed, defineComponent, onBeforeUnmount, ref, watch, type PropType } from "vue";
import PlayIcon from "~icons/lucide/play";
import PauseIcon from "~icons/lucide/pause";
import type { GraphEdge } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { SCRUB_FUTURE, SCRUB_PAST, SCRUB_PLAY, refreshPalette } from "@/domain/pages/graphVisuals";
import { USegmented } from "@/components/knowledge/kit";

const DAY_MS = 24 * 3600 * 1000;
/** 轨道两端的余量，等于轨道自己的圆角 */
const SCRUB_INSET = 10;
type ScrubUnit = "year" | "month" | "day";
/** 一根柱子最多画多少根。超过就把相邻的桶并起来画——只影响画，不影响播放步长 */
const SCRUB_MAX_BARS = 220;
/** 整条轨走完的目标时长。与单位无关 */
const SCRUB_PLAY_MS = 18000;

function bucketStart(ts: number, unit: ScrubUnit): number {
  const d = new Date(ts);
  if (unit === "year") return Date.UTC(d.getUTCFullYear(), 0, 1);
  if (unit === "month")
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
}
function bucketNext(ts: number, unit: ScrubUnit): number {
  const d = new Date(ts);
  if (unit === "year") return Date.UTC(d.getUTCFullYear() + 1, 0, 1);
  if (unit === "month")
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
  return ts + DAY_MS;
}

/** 轨道 clientX → 对齐天步进的时间值 */
function scrubValueAt(
  clientX: number,
  track: HTMLDivElement | null,
  minTs: number,
  maxTs: number
): number {
  if (!track) return maxTs;
  const rect = track.getBoundingClientRect();
  if (rect.width < 1) return maxTs;
  const span = rect.width - SCRUB_INSET * 2;
  if (span < 1) return maxTs;
  const frac = Math.min(
    1,
    Math.max(0, (clientX - rect.left - SCRUB_INSET) / span)
  );
  const raw = minTs + frac * (maxTs - minTs);
  return Math.min(maxTs, minTs + Math.round((raw - minTs) / DAY_MS) * DAY_MS);
}

export const TimeScrubber = defineComponent({
  name: "TimeScrubber",
  props: {
    edges: { type: Array as PropType<GraphEdge[]>, required: true },
    value: { type: Number as PropType<number | null>, default: null },
    playing: { type: Boolean, default: false }
  },
  emits: ["change", "playingChange"],
  setup(props, { emit }) {
    /* 默认年：大多数库跨度都以年计，一进来先给能一眼看全的那一档 */
    const unit = ref<ScrubUnit>("year");
    /* 走完整条的次数。拿它当 key——同一个元素上重复触发同一个动画不会重播 */
    const sweep = ref(0);
    /* 指针在轨道上时，已走过的那段提亮 */
    const trackHover = ref(false);
    const trackRef = ref<HTMLDivElement | null>(null);
    const draggingRef = ref(false);
    /* 播放循环有自己的浮点累加器，不读 value——否则每帧的取整误差会积起来 */
    const seekRef = ref<number | null>(null);
    let raf = 0;
    onBeforeUnmount(() => cancelAnimationFrame(raf));

    const setPlaying = (v: boolean) => emit("playingChange", v);
    const seek = (v: number) => {
      seekRef.value = v;
      emit("change", v);
    };

    const minTs = ref(Date.now() - 5 * 365 * 24 * 3600 * 1000);
    const maxTs = ref(Date.now());
    const bars = ref<{ ts: number; h: number; n: number }[]>([]);
    const merged = ref(1);
    const trackW = ref(660);

    watch(
      () => [props.edges, unit.value] as const,
      () => {
        const now = Date.now();
        // 柱子按边开始成立的时刻分格（0022）：没起点的边从最早证据起算
        const froms = props.edges
          .map(e => (e.holds_from ? Date.parse(e.holds_from) : NaN))
          .filter(t => !Number.isNaN(t));
        const min = froms.length
          ? Math.min(...froms)
          : now - 5 * 365 * 24 * 3600 * 1000;
        const start = bucketStart(min, unit.value);
        const counts = new Map<number, number>();
        for (const t of froms) {
          const k = bucketStart(t, unit.value);
          counts.set(k, (counts.get(k) ?? 0) + 1);
        }
        const raw: { ts: number; n: number }[] = [];
        for (let t = start; t <= now; t = bucketNext(t, unit.value))
          raw.push({ ts: t, n: counts.get(t) ?? 0 });
        // 画不下就并桶。并的是画，不是步长
        const group = Math.max(1, Math.ceil(raw.length / SCRUB_MAX_BARS));
        const cells: { ts: number; n: number }[] = [];
        for (let i = 0; i < raw.length; i += group) {
          const slice = raw.slice(i, i + group);
          cells.push({
            ts: slice[0].ts,
            n: slice.reduce((a, b) => a + b.n, 0)
          });
        }
        const peak = Math.max(1, ...cells.map(c => c.n));
        minTs.value = start;
        maxTs.value = now;
        bars.value = cells.map(c => ({ ts: c.ts, h: c.n / peak, n: c.n }));
        merged.value = group;
        // 单位越大 → 桶越少 → 岛越短。岛里那排固定控件本身就要四百多像素
        trackW.value = Math.min(780, Math.max(660, 380 + cells.length * 2));
      },
      { immediate: true, deep: false }
    );

    /* 播放按日推进（数据即 day 精度）。rAF 时间驱动，值只在跨天时下发 */
    watch(
      () => props.playing,
      playing => {
        cancelAnimationFrame(raf);
        if (!playing) return;
        const SPEED = (maxTs.value - minTs.value) / SCRUB_PLAY_MS;
        let last = performance.now();
        let acc = props.value ?? minTs.value;
        let lastPushed = 0;
        const step = (now: number) => {
          // 有人拖过了：从落点接着走，而不是沿原来的轨迹
          if (seekRef.value !== null) {
            acc = seekRef.value;
            seekRef.value = null;
          }
          acc += (now - last) * SPEED;
          last = now;
          if (acc >= maxTs.value) {
            setPlaying(false);
            emit("change", null);
            // 走到头了扫一道光。这是个收尾
            sweep.value = sweep.value + 1;
            return;
          }
          // 连续推进，不按桶跳；限到 ~30fps
          if (now - lastPushed >= 33) {
            lastPushed = now;
            emit("change", Math.round(acc));
          }
          raf = requestAnimationFrame(step);
        };
        raf = requestAnimationFrame(step);
      }
    );

    const label = computed(() => {
      if (props.value === null) return S.graph.allTime;
      const d = new Date(props.value);
      const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
      const dd = String(d.getUTCDate()).padStart(2, "0");
      // 精度跟着单位：年单位下写出「2019-01-01」是假精确
      if (unit.value === "year") return `${d.getUTCFullYear()}`;
      if (unit.value === "month") return `${d.getUTCFullYear()}-${mm}`;
      return `${d.getUTCFullYear()}-${mm}-${dd}`;
    });

    const minYear = computed(() =>
      bars.value.length ? new Date(bars.value[0].ts).getUTCFullYear() : undefined
    );
    const maxYear = computed(() =>
      bars.value.length
        ? new Date(bars.value[bars.value.length - 1].ts).getUTCFullYear()
        : undefined
    );

    return () => {
      const value = props.value;
      // 柱色随播放头变——亮度档在渲染时读（refreshPalette 的 live 导出）
      refreshPalette();
      return (
        <div
          class={[
            "scrub-island absolute bottom-4 left-1/2 z-10 flex -translate-x-1/2 items-center gap-3 rounded-card border border-line bg-card px-3 py-2",
            props.playing && "solid"
          ]}
          /* 外壳卡化（reskin-b2，仅容器层）：浮层海拔对齐实体面板（shadow-pop；
             .scrub-island 全局类是未分层 CSS，utility 压不过，inline 顶开）；
             宽度基准从视口改为定位卡（reskin 后画布收进卡内，100vw 会溢出卡缘） */
          style={{
            width: `min(${trackW.value}px, calc(100% - 2rem))`,
            boxShadow: "var(--shadow-pop)"
          }}
        >
          <button
            type="button"
            class="shrink-0 rounded-ctl p-1.5 text-ink-2 hover:bg-fill hover:text-ink"
            title={props.playing ? S.graph.pause : S.graph.play}
            onClick={() => {
              // 已经在末端（Now）时按播放要从头来
              if (
                !props.playing &&
                (value === null ||
                  value >= maxTs.value - (maxTs.value - minTs.value) * 0.02)
              )
                emit("change", minTs.value);
              setPlaying(!props.playing);
            }}
          >
            {props.playing ? (
              <PauseIcon width="13" height="13" />
            ) : (
              <PlayIcon width="13" height="13" />
            )}
          </button>

          {/* 步长。播放与柱子共用它 */}
          <USegmented
            class="shrink-0"
            size="sm"
            value={unit.value}
            options={(["year", "month", "day"] as const).map(u => ({
              value: u,
              title: S.graph.scrubUnitHint,
              label:
                u === "year"
                  ? S.graph.scrubUnitYear
                  : u === "month"
                    ? S.graph.scrubUnitMonth
                    : S.graph.scrubUnitDay
            }))}
            onChange={v => (unit.value = v as ScrubUnit)}
          />

          <span class="shrink-0 tabular-nums text-aux text-ink-2">
            {minYear.value}
          </span>

          {/* 密度带轨道：内嵌浅色井 + 每年事实量柱 */}
          <div
            ref={trackRef}
            class="relative h-9 min-w-[150px] flex-1 overflow-hidden rounded-ctl bg-card"
            onMouseenter={() => (trackHover.value = true)}
            onMouseleave={() => (trackHover.value = false)}
          >
            {sweep.value > 0 && (
              <span
                key={sweep.value}
                class="sweep"
                onAnimationend={() => {
                  sweep.value = 0;
                }}
              />
            )}
            {/* 间隙必须随密度收：密了就贴在一起当密度带看 */}
            <div
              class="absolute bottom-1.5 top-1.5 flex items-end"
              style={{
                left: `${SCRUB_INSET}px`,
                right: `${SCRUB_INSET}px`,
                gap:
                  bars.value.length > 120 ? 0 : bars.value.length > 40 ? 1 : 2
              }}
            >
              {bars.value.map(b => {
                // 进入即亮（桶起点为判据）：播放头脚下的柱子即已覆盖
                const past = value !== null && b.ts <= value;
                const d = new Date(b.ts);
                const stamp =
                  unit.value === "year"
                    ? `${d.getUTCFullYear()}`
                    : unit.value === "month"
                      ? `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`
                      : d.toISOString().slice(0, 10);
                return (
                  <div
                    key={b.ts}
                    class="flex h-full flex-1 items-end"
                    title={`${stamp} · ${b.n}${
                      merged.value > 1
                        ? ` · ${S.graph.scrubBarMerged(merged.value)}`
                        : ""
                    }`}
                  >
                    <div
                      class="bar w-full"
                      style={{
                        height: `${Math.max(10, b.h * 100)}%`,
                        // 播放中已扫过的提亮，还没走到的压到近乎不可见
                        background:
                          value !== null && past && (props.playing || trackHover.value)
                            ? SCRUB_PLAY
                            : value === null || past
                              ? SCRUB_PAST
                              : SCRUB_FUTURE
                      }}
                    />
                  </div>
                );
              })}
            </div>
            <input
              type="range"
              class="scrubber-range"
              style={{
                left: `${SCRUB_INSET}px`,
                right: `${SCRUB_INSET}px`,
                width: "auto"
              }}
              min={minTs.value}
              max={maxTs.value}
              step={DAY_MS}
              value={value ?? maxTs.value}
              /* 拖动不停播：拖是"我要看那一段"，不是"我要停下" */
              onChange={(e: Event) =>
                seek(Number((e.target as HTMLInputElement).value))
              }
              onPointerdown={(e: PointerEvent) => {
                draggingRef.value = true;
                try {
                  (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
                } catch {
                  /* 合成事件的 pointerId 可能无效，忽略 */
                }
                seek(
                  scrubValueAt(e.clientX, trackRef.value, minTs.value, maxTs.value)
                );
              }}
              onPointermove={(e: PointerEvent) => {
                if (draggingRef.value)
                  seek(
                    scrubValueAt(
                      e.clientX,
                      trackRef.value,
                      minTs.value,
                      maxTs.value
                    )
                  );
              }}
              onPointerup={() => (draggingRef.value = false)}
              onPointercancel={() => (draggingRef.value = false)}
            />
          </div>

          {/* 轴端年份与当前值同文本时只显示一个（国内风收口走查 A7：
              2026/2026 双标签重复——注意 maxYear 是 number、label 是 string，
              必须先归一再比较） */}
          {String(maxYear.value) !== label.value && (
            <span class="shrink-0 tabular-nums text-aux text-ink-2">
              {maxYear.value}
            </span>
          )}

          <div class="w-[5.6rem] shrink-0 text-center tabular-nums text-sub text-ink">
            {label.value}
          </div>

          <div class="h-5 w-px shrink-0 bg-fill" />

          {/* 双锚点分段：所处锚点高亮、点击即跳；拖在中间某天时两者皆不亮 */}
          <USegmented
            class="shrink-0"
            size="sm"
            value={
              value === null
                ? "all"
                : maxTs.value - value < DAY_MS
                  ? "now"
                  : "none"
            }
            options={[
              { value: "all", label: S.graph.allTime },
              { value: "now", label: S.graph.nowBtn }
            ]}
            onChange={k => {
              setPlaying(false);
              emit("change", k === "all" ? null : maxTs.value);
            }}
          />
        </div>
      );
    };
  }
});
