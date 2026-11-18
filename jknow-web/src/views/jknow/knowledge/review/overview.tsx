/* 审核总览（对照旧仓 pages/ReviewOverview.tsx 逐行）：等着办的 / 办过的 /
   agent 做过的 / 库的成色。数据全来自 GET /review/summary，这里只画，不算。
   「去处理」的跳转由父组件接（emit）。 */
import { defineComponent, type PropType } from "vue";
import { RouterLink } from "vue-router";
import type { ReviewSummary } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { UChip } from "@/components/knowledge/kit";

/** 总览里能点进去的七档 */
export type WaitingQueue = keyof ReviewSummary["waiting"];

/** 七档队列在总览里的顺序与左栏一致 */
const WAITING: { key: WaitingQueue; label: string }[] = [
  { key: "pending", label: S.review.railPending },
  { key: "duplicates", label: S.review.railDuplicates },
  { key: "conflicts", label: S.review.railConflicts },
  { key: "unconfirmed", label: S.review.railUnconfirmed },
  { key: "lowconf", label: S.review.railLowConfidence },
  { key: "violations", label: S.review.railViolations },
  { key: "defects", label: S.review.railDefects },
  { key: "alignment", label: S.review.railAlignment }
];

/** 从某个时刻到现在整几天；不满一天算 0 */
function daysSince(iso: string): number {
  return Math.max(
    0,
    Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000)
  );
}

/** 一格统计：大数在上，说明在下。卡片本身不是控件——想进那一档，点下面的句子 */
function Stat(props: {
  value: number | string;
  label: string;
  note?: string | null;
  action?: { label: string; to?: string };
}) {
  return (
    <div class="stat-card">
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

export const ReviewOverview = defineComponent({
  name: "ReviewOverview",
  props: {
    summary: {
      type: Object as PropType<ReviewSummary>,
      required: true
    },
    /** 这个库的治理开关（0025）：关着时 Agent 那一段说明去哪里打开 */
    governance: { type: Boolean, default: false },
    settingsTo: { type: String, default: "" }
  },
  emits: ["pick"],
  setup(props) {
    return () => {
      const { waiting, decided, health, agent } = props.summary;
      const waitingTotal = WAITING.reduce((n, w) => n + waiting[w.key].count, 0);
      const share = (n: number) =>
        health.facts === 0
          ? "—"
          : `${Math.round((n / health.facts) * 1000) / 10}%`;
      return (
        <div class="space-y-8">
          {/* 等着办的 */}
          <section>
            <div class="mb-3 text-sub font-medium text-ink-2">
              {S.review.overviewWaiting}
            </div>
            {waitingTotal === 0 ? (
              <div class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2">
                {S.review.overviewAllClear}
              </div>
            ) : (
              <div class="stat-grid">
                {WAITING.filter(w => waiting[w.key].count > 0).map(w => {
                  const q = waiting[w.key];
                  return (
                    <Stat
                      key={w.key}
                      value={q.count}
                      label={w.label}
                      note={
                        q.oldest_at
                          ? S.review.overviewOldest(daysSince(q.oldest_at))
                          : null
                      }
                      action={{
                        label: S.review.overviewOpen,
                        to: `?queue=${w.key}`
                      }}
                    />
                  );
                })}
              </div>
            )}
          </section>

          {/* 办过的 */}
          <section>
            <div class="mb-3 text-sub font-medium text-ink-2">
              {S.review.overviewDecided}
            </div>
            <div class="grid grid-cols-2 gap-3">
              <Stat
                value={decided.last_7d.total}
                label={S.review.overviewLast7}
                note={S.review.overviewAutomatic(decided.last_7d.automatic)}
              />
              <Stat
                value={decided.last_30d.total}
                label={S.review.overviewLast30}
                note={S.review.overviewAutomatic(decided.last_30d.automatic)}
              />
            </div>
            <div class="mt-3 rounded-card border border-line bg-card p-4">
              <div class="mb-3 text-aux text-ink-2">{S.review.overviewDaily}</div>
              <DailyBars days={decided.daily} />
            </div>
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

          {/* agent 做过的（0025）：开关关着且什么都没做过时只说一句去哪里打开 */}
          <section>
            <div class="mb-3 text-sub font-medium text-ink-2">
              {S.review.overviewAgent}
            </div>
            {!props.governance && (
              <p class="mb-3 text-sub text-ink-2">
                {S.review.overviewAgentOff}{" "}
                {props.settingsTo && (
                  <RouterLink class="link-inline text-sub" to={props.settingsTo}>
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
            {(props.governance || agent.open > 0 || agent.last_30d.applied > 0) && (
              <div class="stat-grid">
                <Stat
                  value={agent.open}
                  label={S.review.overviewAgentOpen}
                  action={
                    agent.open > 0
                      ? { label: S.review.overviewOpen, to: "?queue=agent" }
                      : undefined
                  }
                />
                <Stat
                  value={agent.last_7d.applied}
                  label={S.review.overviewAgentApplied}
                  note={S.review.overviewLast7}
                />
                <Stat
                  value={agent.last_30d.accepted}
                  label={S.review.overviewAgentAccepted}
                  note={S.review.overviewAgentOverridden(agent.last_30d.overridden)}
                />
                <Stat
                  value={agent.last_30d.reverted}
                  label={S.review.overviewAgentReverted}
                  note={S.review.overviewLast30}
                />
              </div>
            )}
          </section>

          {/* 库的成色 */}
          <section>
            <div class="mb-3 text-sub font-medium text-ink-2">
              {S.review.overviewHealth}
            </div>
            <p class="mb-3 text-sub text-ink-2">
              {S.review.overviewFacts(health.facts)}
            </p>
            <div class="grid grid-cols-3 gap-3">
              <Stat
                value={health.low_confidence}
                label={S.review.railLowConfidence}
                note={share(health.low_confidence)}
              />
              <Stat
                value={health.unconfirmed}
                label={S.review.railUnconfirmed}
                note={share(health.unconfirmed)}
              />
              <Stat
                value={health.contested}
                label={S.review.overviewContested}
                note={share(health.contested)}
              />
            </div>
          </section>
        </div>
      );
    };
  }
});
