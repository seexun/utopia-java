/* 审核总览（对照旧仓 pages/ReviewOverview.tsx 逐行）：等着办的 / 办过的 /
   agent 做过的 / 库的成色。数据全来自 GET /review/summary，这里只画，不算。
   「去处理」的跳转由父组件接（emit）。
   reskin-b4 样式层（数据/跳转零动）：统计卡复用文库统计卡语言（.stat-grid +
   icon 圆盘 --*-soft 底/深档字 + 大数），段头升 GroupLabel（kit 原件，计数尾随）。 */
import { defineComponent, type PropType } from "vue";
import { RouterLink } from "vue-router";
import type { ReviewSummary } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { UChip, GroupLabel } from "@/components/knowledge/kit";
import ClockIcon from "~icons/lucide/clock";
import CopyIcon from "~icons/lucide/copy";
import TriangleAlertIcon from "~icons/lucide/triangle-alert";
import CircleDashedIcon from "~icons/lucide/circle-dashed";
import GaugeIcon from "~icons/lucide/gauge";
import ScaleIcon from "~icons/lucide/scale";
import BoxesIcon from "~icons/lucide/boxes";
import Link2Icon from "~icons/lucide/link-2";
import BotIcon from "~icons/lucide/bot";
import HistoryIcon from "~icons/lucide/history";
import CheckCheckIcon from "~icons/lucide/check-check";
import Undo2Icon from "~icons/lucide/undo-2";
import SwordsIcon from "~icons/lucide/swords";

/** 总览里能点进去的七档 */
export type WaitingQueue = keyof ReviewSummary["waiting"];

/** icon 圆盘的软底/深档字档位（文库统计卡 .lib-stat-icon 的 is-* 同语言，
    令牌直引） */
type StatTone = "primary" | "success" | "warning" | "danger" | "neutral";
const STAT_TONE: Record<StatTone, { color: string; background: string }> = {
  primary: {
    color: "var(--color-primary-text)",
    background: "var(--color-primary-soft)"
  },
  success: {
    color: "var(--color-success-text)",
    background: "var(--color-success-soft)"
  },
  warning: {
    color: "var(--color-warning-text)",
    background: "var(--color-warning-soft)"
  },
  danger: {
    color: "var(--color-danger-text)",
    background: "var(--color-danger-soft)"
  },
  neutral: { color: "var(--color-ink-2)", background: "var(--color-neutral-soft)" }
};

/** 七档队列在总览里的顺序与队列过滤条一致；各配 icon 与色档（纯展示） */
const WAITING: {
  key: WaitingQueue;
  label: string;
  icon: typeof ClockIcon;
  tone: StatTone;
}[] = [
  { key: "pending", label: S.review.railPending, icon: ClockIcon, tone: "primary" },
  { key: "duplicates", label: S.review.railDuplicates, icon: CopyIcon, tone: "primary" },
  { key: "conflicts", label: S.review.railConflicts, icon: TriangleAlertIcon, tone: "warning" },
  { key: "unconfirmed", label: S.review.railUnconfirmed, icon: CircleDashedIcon, tone: "neutral" },
  { key: "lowconf", label: S.review.railLowConfidence, icon: GaugeIcon, tone: "warning" },
  { key: "violations", label: S.review.railViolations, icon: ScaleIcon, tone: "danger" },
  { key: "defects", label: S.review.railDefects, icon: BoxesIcon, tone: "warning" },
  { key: "alignment", label: S.review.railAlignment, icon: Link2Icon, tone: "primary" }
];

/** 从某个时刻到现在整几天；不满一天算 0 */
function daysSince(iso: string): number {
  return Math.max(
    0,
    Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
  );
}

/** 一格统计（文库统计卡语言）：icon 圆盘 + 大数 + 说明。卡片本身不是控件——
    想进那一档，点下面的句子 */
function Stat(props: {
  value: number | string;
  label: string;
  note?: string | null;
  action?: { label: string; to?: string };
  icon?: typeof ClockIcon;
  tone?: StatTone;
}) {
  return (
    <div class="stat-card flex items-center gap-3.5">
      {props.icon && (
        <span
          class="flex size-[42px] shrink-0 items-center justify-center rounded-full"
          style={STAT_TONE[props.tone ?? "neutral"]}
        >
          <props.icon width={20} height={20} />
        </span>
      )}
      <div class="min-w-0">
        <div class="stat-value">{props.value}</div>
        <div class="stat-label">{props.label}</div>
        {props.note && <div class="text-aux text-ink-2">{props.note}</div>}
        {props.action && props.action.to && (
          <div class="mt-2">
            <RouterLink class="link-inline text-sub" to={props.action.to}>
              {props.action.label}
            </RouterLink>
          </div>
        )}
      </div>
    </div>
  );
}

/** 近 14 天每天一根柱。等距、含零：服务端已把没有决定的天补成 0。
    高度按这 14 天里的最大值归一，最矮留 2px 当基线。柱子有上限宽。 */
function DailyBars(props: { days: ReviewSummary["decided"]["daily"] }) {
  const days = props.days;
  const max = Math.max(1, ...days.map(d => d.count));
  return (
    <div>
      <div class="flex h-16 items-end gap-1">
        {days.map(d => (
          <div
            key={d.day}
            class="flex h-full flex-1 items-end justify-center"
          >
            <div
              class="w-full max-w-[18px] rounded-none bg-ink-3"
              style={{
                height: `${Math.max(2, Math.round((d.count / max) * 64))}px`,
                opacity: d.count === 0 ? 0.25 : 1
              }}
              title={`${d.day} · ${d.count}`}
            />
          </div>
        ))}
      </div>
      <div class="mt-1 flex justify-between text-aux text-ink-2">
        <span>{days[0]?.day}</span>
        <span>{days[days.length - 1]?.day}</span>
      </div>
    </div>
  );
}

/* ===== 重排批（2026-11-26 治"乱"走查，用户拍板）=====
   原 ReviewOverview（8 张统计大卡平铺）拆成两件：
   - ReviewKpiBar：页头卡里的四个数（待我处理/近30天已办/最久等待/超14天队列），
     一行扫完，取代平铺大卡；
   - ReviewStatsCard：右栏收尾的折叠统计卡（办过的/agent/成色），默认收起——
     待办在前、统计在后。等着办的 8 卡撤除：队列入口与计数只住在左导航。
   数据依旧全来自 GET /review/summary，这里只画不算。 */
import ChevronDownIcon from "~icons/lucide/chevron-down";

/** KPI 概览条：四个数一行扫完。超 14 天未处理的量染红（国内 B 端告警档） */
export const ReviewKpiBar = defineComponent({
  name: "ReviewKpiBar",
  props: {
    summary: {
      type: Object as PropType<ReviewSummary>,
      required: true
    }
  },
  setup(props) {
    return () => {
      /* 空摘要防御：调用方（如 W2 写测试）可能给 `{}` 形状的 summary——
         每个字段兜底，KPI 条显示 0 而不是渲染崩掉整页 */
      const waiting = (props.summary.waiting ?? {}) as Record<
        string,
        { count: number; oldest_at: string | null }
      >;
      const waitingTotal = WAITING.reduce(
        (n, w) => n + (waiting[w.key]?.count ?? 0),
        0
      );
      const atOf = (w: { oldest_at: string | null }): number =>
        w.oldest_at ? daysSince(w.oldest_at) : 0;
      const oldest = Math.max(0, ...WAITING.map(w => atOf(waiting[w.key] ?? { count: 0, oldest_at: null })));
      const highRisk = WAITING.filter(
        w => (waiting[w.key]?.count ?? 0) > 0 && atOf(waiting[w.key] ?? { count: 0, oldest_at: null }) >= 14
      ).length;
      const decided30 = props.summary.decided?.last_30d?.total ?? 0;
      const items = [
        {
          value: waitingTotal,
          label: S.review.kpiWaiting,
          cls: waitingTotal > 0 ? "text-primary" : "text-ink"
        },
        { value: decided30, label: S.review.kpiDone30, cls: "text-ink" },
        {
          value: oldest,
          label: S.review.kpiOldest,
          cls: oldest >= 14 ? "text-danger" : "text-ink"
        },
        {
          value: highRisk,
          label: S.review.kpiHighRisk,
          cls: highRisk > 0 ? "text-danger" : "text-ink"
        }
      ];
      return (
        <div class="flex flex-wrap items-center gap-x-8 gap-y-2">
          {items.map(it => (
            <div key={it.label} class="flex items-baseline gap-2">
              <span
                class={["text-[26px] font-bold leading-none tabular-nums", it.cls]}
              >
                {it.value}
              </span>
              <span class="text-sub text-ink-2">{it.label}</span>
            </div>
          ))}
        </div>
      );
    };
  }
});

/** 右栏收尾的折叠统计卡：办过的（7/30 天 + 每日柱 + 按动作/按人）+ agent +
    成色。默认收起；零数据段落不出（每日柱全零时只留一句说明）。 */
export const ReviewStatsCard = defineComponent({
  name: "ReviewStatsCard",
  props: {
    summary: {
      type: Object as PropType<ReviewSummary>,
      required: true
    },
    governance: { type: Boolean, default: false },
    settingsTo: { type: String, default: "" }
  },
  setup(props) {
    const open = ref(false);
    return () => {
      /* 空摘要防御（同 KPI 条）：`??` 挡不住 `{}` 形状的 mock——按字段判空，
         缺了就整体换零值形状（折叠态只显示头部大数，展开段落各自兜底） */
      const decided =
        props.summary.decided && props.summary.decided.last_30d
          ? props.summary.decided
          : ({
              last_7d: { total: 0, automatic: 0 },
              last_30d: { total: 0, automatic: 0, by_action: [], by_actor: [] },
              daily: []
            } as unknown as ReviewSummary["decided"]);
      const health =
        props.summary.health && props.summary.health.facts != null
          ? props.summary.health
          : ({
              facts: 0,
              low_confidence: 0,
              unconfirmed: 0,
              contested: 0
            } as unknown as ReviewSummary["health"]);
      const agent =
        props.summary.agent && props.summary.agent.open != null
          ? props.summary.agent
          : ({
              running: false,
              queue: 0,
              open: 0,
              last_7d: { applied: 0 },
              last_30d: { applied: 0, accepted: 0, overridden: 0, reverted: 0 }
            } as unknown as ReviewSummary["agent"]);
      const share = (n: number) =>
        health.facts === 0
          ? "—"
          : `${Math.round((n / health.facts) * 1000) / 10}%`;
      return (
        <div class="card overflow-hidden">
          <button
            type="button"
            class="flex w-full items-center gap-2 px-4 py-3 text-left"
            onClick={() => (open.value = !open.value)}
          >
            <ChevronDownIcon
              width={14}
              height={14}
              class="shrink-0 text-ink-2 transition-transform duration-150"
              style={open.value ? undefined : { transform: "rotate(-90deg)" }}
            />
            <span class="text-body font-medium text-ink">
              {S.review.statsTitle(decided.last_30d.total)}
            </span>
          </button>
          {open.value && (
            <div class="space-y-7 border-t border-line px-4 py-4">
              {/* 办过的 */}
              <section>
                <div class="mb-3 text-body font-medium text-ink">
                  {S.review.overviewDecided}
                </div>
                <div class="grid grid-cols-2 gap-3">
                  <Stat
                    value={decided.last_7d.total}
                    label={S.review.overviewLast7}
                    icon={HistoryIcon}
                    tone="primary"
                    note={S.review.overviewAutomatic(decided.last_7d.automatic)}
                  />
                  <Stat
                    value={decided.last_30d.total}
                    label={S.review.overviewLast30}
                    icon={HistoryIcon}
                    tone="neutral"
                    note={S.review.overviewAutomatic(decided.last_30d.automatic)}
                  />
                </div>
                {decided.daily.some(d => d.count > 0) && (
                  <div class="mt-3 rounded-card border border-line bg-card p-4">
                    <div class="mb-3 text-aux text-ink-2">
                      {S.review.overviewDaily}
                    </div>
                    <DailyBars days={decided.daily} />
                  </div>
                )}
                {decided.last_30d.total === 0 ? (
                  <p class="mt-3 text-sub text-ink-2">
                    {S.review.overviewNoDecisions}
                  </p>
                ) : (
                  <div class="mt-3 grid gap-3 md:grid-cols-2">
                    <div class="rounded-card border border-line bg-card p-4">
                      <div class="mb-2 text-aux text-ink-2">
                        {S.review.overviewByAction}
                      </div>
                      <div class="flex flex-wrap gap-2">
                        {decided.last_30d.by_action.map(a => (
                          <UChip key={a.action}>
                            {(S.review.decisionActions[
                              a.action as keyof typeof S.review.decisionActions
                            ] ?? a.action) + ` · ${a.count}`}
                          </UChip>
                        ))}
                      </div>
                    </div>
                    <div class="rounded-card border border-line bg-card p-4">
                      <div class="mb-2 text-aux text-ink-2">
                        {S.review.overviewByActor}
                      </div>
                      <div class="space-y-1">
                        {decided.last_30d.by_actor.map(a => (
                          <div
                            key={a.actor_id ?? "auto"}
                            class="flex items-center justify-between text-body"
                          >
                            <span class="truncate text-ink-2">
                              {a.actor_id === null
                                ? S.review.aiActor
                                : (a.label ?? a.actor_id)}
                            </span>
                            <span class="tabular-nums text-ink-2">{a.count}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                )}
              </section>

              {/* agent 做过的（0025） */}
              <section>
                <div class="mb-3 text-body font-medium text-ink">
                  {S.review.overviewAgent}
                </div>
                {!props.governance && (
                  <p class="mb-3 text-sub text-ink-2">
                    {S.review.overviewAgentOff}{" "}
                    {props.settingsTo && (
                      <RouterLink
                        class="link-inline text-sub"
                        to={props.settingsTo}
                      >
                        {S.review.overviewAgentSettings}
                      </RouterLink>
                    )}
                  </p>
                )}
                {agent.running ? (
                  <p class="mb-3 flex items-center text-sub text-ink-2">
                    <span class="mr-2 inline-block h-2 w-2 animate-pulse rounded-full bg-warning" />
                    {S.review.overviewAgentRunning(agent.queue)}
                  </p>
                ) : props.governance && agent.queue > 0 ? (
                  <p class="mb-3 text-sub text-ink-2">
                    {S.review.overviewAgentQueue(agent.queue)}
                  </p>
                ) : null}
                {(props.governance ||
                  agent.open > 0 ||
                  agent.last_30d.applied > 0) && (
                  <div class="stat-grid">
                    <Stat
                      value={agent.open}
                      label={S.review.overviewAgentOpen}
                      icon={BotIcon}
                      tone="primary"
                      action={
                        agent.open > 0
                          ? { label: S.review.overviewOpen, to: "?queue=agent" }
                          : undefined
                      }
                    />
                    <Stat
                      value={agent.last_7d.applied}
                      label={S.review.overviewAgentApplied}
                      icon={BotIcon}
                      tone="success"
                      note={S.review.overviewLast7}
                    />
                    <Stat
                      value={agent.last_30d.accepted}
                      label={S.review.overviewAgentAccepted}
                      icon={CheckCheckIcon}
                      tone="success"
                      note={S.review.overviewAgentOverridden(
                        agent.last_30d.overridden
                      )}
                    />
                    <Stat
                      value={agent.last_30d.reverted}
                      label={S.review.overviewAgentReverted}
                      icon={Undo2Icon}
                      tone="neutral"
                      note={S.review.overviewLast30}
                    />
                  </div>
                )}
              </section>

              {/* 库的成色 */}
              <section>
                <div class="mb-3 text-body font-medium text-ink">
                  {S.review.overviewHealth}
                </div>
                <p class="mb-3 text-sub text-ink-2">
                  {S.review.overviewFacts(health.facts)}
                </p>
                <div class="grid grid-cols-3 gap-3">
                  <Stat
                    value={health.low_confidence}
                    label={S.review.railLowConfidence}
                    icon={GaugeIcon}
                    tone="warning"
                    note={share(health.low_confidence)}
                  />
                  <Stat
                    value={health.unconfirmed}
                    label={S.review.railUnconfirmed}
                    icon={CircleDashedIcon}
                    tone="neutral"
                    note={share(health.unconfirmed)}
                  />
                  <Stat
                    value={health.contested}
                    label={S.review.overviewContested}
                    icon={SwordsIcon}
                    tone="danger"
                    note={share(health.contested)}
                  />
                </div>
              </section>
            </div>
          )}
        </div>
      );
    };
  }
});
