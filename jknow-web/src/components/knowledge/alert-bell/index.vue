<!-- 顶栏告警（对照旧仓 AlertBell.tsx）：铃铛 + 未读角标 + 弹出面板。
     **弹窗不是页面**：告警是"顺手瞄一眼"的东西，不是一个要专门去逛的地方。
     （旧仓注释原话。）
     一条告警 = 一次故障，写完不再变，没有"已解决"；「已读」逐人。
     未读数走 SSE（useAlertEvents / useKbEvents 失效 ["alerts"]），120 秒轮询兜底。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import {
  alerts as alertsApi,
  alertsUnread,
  alertReadGroup,
  alertsReadAll,
  requeueJobs
} from "@/api/knowledge/alerts";
import type { AlertGroup } from "@/api/knowledge/types";
import { S } from "@/i18n";
// 旧仓 AlertBell 的 toast 走消息总线（domain/toast.ts 是纯单例无 UI 宿主——
// React 的 ToastHost 未迁）；壳层用 Element Plus 的 message 收口
import { message } from "@/utils/message";
import BellIcon from "~icons/lucide/bell";

const PAGE = 8;

/** 明细里给人看的那一行：对象名 — 报错原文或截断范围（旧仓 line 函数逐字语义） */
function line(d: AlertGroup["lines"][number]): string | null {
  const partial = d.warnings?.flatMap(warning => {
    const anchor = warning.detail;
    if (
      warning.kind === "spreadsheet.rows_truncated" &&
      typeof anchor?.sheet === "string" &&
      typeof anchor.rows_read === "number" &&
      typeof anchor.rows_total === "number"
    ) {
      return [
        S.alerts.partialRows(anchor.sheet, anchor.rows_read, anchor.rows_total)
      ];
    }
    if (
      warning.kind === "csv.records_truncated" &&
      typeof anchor?.records_read === "number" &&
      typeof anchor.records_total === "number"
    ) {
      return [
        S.alerts.partialRecords(anchor.records_read, anchor.records_total)
      ];
    }
    return [];
  });
  const parts = [d.name ?? d.job, d.error, ...(partial ?? [])].filter(Boolean);
  return parts.length ? parts.join(" — ") : null;
}

/** 哪些告警带「再跑一遍」：故障修好之后（充值、改端点）任务不会自己回来的那几种 */
const REQUEUE_KINDS = new Set(["llm.out_of_credit", "llm.unreachable"]);

/* 轻重是服务端定的，一组取组里最重的那一档。**告警不全是故障**（旧仓注释） */
const SEVERITY_STYLE: Record<string, { bg: string; color: string }> = {
  error: { bg: "#ef4444", color: "#fff" },
  warning: { bg: "#f59e0b", color: "#fff" },
  info: { bg: "#e6f4ff", color: "#1677ff" }
};

const visible = ref(false);
const q = ref("");
/** 页码 **1 基**（el-pagination current-page 的口径）：offset = (page-1)*PAGE。
 *  审查 I-1：初版直接把 0 基索引绑给 current-page——高亮缺位、点"1"显示第二页、
 *  offset 0 经翻页器不可达。 */
const page = ref(1);
const queryClient = useQueryClient();

// 搜索后回第一页：停在第 4 页看一个只有 2 页的结果，面板会显示空白，
// 而人会读成"没有告警"（旧仓注释原话）
watch(q, () => {
  page.value = 1;
});

const list = useQuery({
  queryKey: computed(() => ["alerts", "list", q.value.trim(), page.value]),
  queryFn: () =>
    alertsApi({
      q: q.value.trim(),
      limit: PAGE,
      offset: (page.value - 1) * PAGE
    }),
  // 翻页时留着上一页，免得面板高度塌一下再弹回来
  placeholderData: (prev: { items: AlertGroup[]; total: number } | undefined) =>
    prev
});

const unread = useQuery({
  queryKey: ["alerts", "unread"],
  queryFn: alertsUnread,
  // 推送是主路，这个只是断流时的兜底（旧仓注释原话）
  refetchInterval: 120_000
});
const unreadCount = computed(() => unread.data.value?.unread ?? 0);

const read = useMutation({
  mutationFn: (g: AlertGroup) =>
    alertReadGroup({
      kb_id: g.kb_id,
      kind: g.kind,
      from: g.earliest_at,
      to: g.latest_at
    }),
  onSuccess: () =>
    queryClient.invalidateQueries({ queryKey: ["alerts"] })
});
const readAll = useMutation({
  mutationFn: () => alertsReadAll(),
  onSuccess: () => queryClient.invalidateQueries({ queryKey: ["alerts"] })
});
// 时间窗从这组最早那次故障起——之前失败的不是这次的事（旧仓注释原话）
const requeue = useMutation({
  mutationFn: (g: AlertGroup) =>
    requeueJobs(g.kb_id, { failed_since: g.earliest_at }),
  onSuccess: r => {
    message(S.alerts.requeued(r.requeued), { type: "success" });
    queryClient.invalidateQueries({ queryKey: ["jobs"] });
  },
  onError: (e: unknown) => message(String(e), { type: "error" })
});

const groups = computed(() => list.data.value?.items ?? []);
const total = computed(() => list.data.value?.total ?? 0);
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE)));

/** 组标题与提示：没见过的 kind 也得显示得出来——"有条告警但我不认识它"
 *  远好过"什么都不显示"（旧仓注释原话） */
function worded(kind: string): { title: string; hint: string } | undefined {
  return (
    S.alerts.kinds as Record<string, { title: string; hint: string } | undefined>
  )[kind];
}

/** 明细行去重但**保留 ×n 计数**（审查 I-4 恢复旧仓 tally Map）：
 *  一模一样的行并成一条，右边记个次数——并的是显示，不是计数，
 *  「还有 N 条」用的仍是原始条数（旧仓注释原话） */
function tallyLines(g: AlertGroup): { line: string; n: number }[] {
  const lines = g.lines.map(line).filter((l): l is string => !!l);
  const tally = new Map<string, number>();
  for (const l of lines) tally.set(l, (tally.get(l) ?? 0) + 1);
  return [...tally].map(([l, n]) => ({ line: l, n }));
}

/** 明细原始条数（去重前）：count 数的是整组，lines 只带回前几条——
 *  差额是"还有 N 条"（旧仓注释原话） */
function rawLineCount(g: AlertGroup): number {
  return g.lines.map(line).filter((l): l is string => !!l).length;
}

function localDateTime(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
</script>

<template>
  <el-popover
    v-model:visible="visible"
    placement="bottom-end"
    :width="420"
    :teleported="true"
    popper-class="shell-pop alert-bell-pop"
    trigger="click"
  >
    <template #reference>
      <span class="alert-bell-trigger" :title="S.alerts.badgeLabel as string" role="button" tabindex="0">
        <BellIcon width="15" height="15" style="color: #6b7280" />
        <!-- 角标也是个点，不是数字。"有事没看"是二元的，具体几条打开就知道；
             数字还会随重试一路往上跳，跳到三位数就把铃铛撑变形了（旧仓注释原话） -->
        <span v-if="unreadCount > 0" class="unread-dot" />
      </span>
    </template>

    <!-- 标题行。**不再兼任关闭键**：关闭归 Esc、外点与铃铛本身（旧仓注释原话） -->
    <div class="flex items-center gap-3 border-b px-4 py-3" style="border-color: #e5eaf0">
      <BellIcon width="15" height="15" style="color: #6b7280" />
      <span class="min-w-0 flex-1 truncate text-sm font-medium">
        {{ S.alerts.title }}
      </span>
    </div>

    <!-- 查找与库切换器同一副样子：它是面板的一段，不是面板里摆的一个控件 -->
    <div class="border-b px-4 py-3" style="border-color: #e5eaf0">
      <el-input
        v-model="q"
        :placeholder="S.alerts.searchPlaceholder as string"
        :bordered="false"
        size="default"
      />
    </div>

    <el-scrollbar max-height="420px">
      <div v-if="groups.length === 0" class="px-4 py-8 text-center">
        <p class="text-sm" style="color: #6b7280">
          {{ q ? S.alerts.noMatch : S.alerts.empty }}
        </p>
        <p v-if="!q" class="mt-1 text-xs" style="color: #9ca3af">
          {{ S.alerts.emptyHint }}
        </p>
      </div>
      <div v-else>
        <div
          v-for="g in groups"
          :key="`${g.kb_id ?? 'system'}|${g.kind}|${g.latest_at}`"
          role="button"
          tabindex="0"
          class="alert-row"
          :style="g.unread === 0 ? 'opacity: 0.65' : ''"
          @click="g.unread > 0 && read.mutate(g)"
          @keydown.enter="g.unread > 0 && read.mutate(g)"
        >
          <div class="min-w-0">
            <div class="flex items-center gap-2">
              <span
                class="min-w-0 flex-1 truncate text-sm"
                :style="g.unread > 0 ? 'font-weight:500;color:#1f2937' : 'color:#6b7280'"
              >
                {{ worded(g.kind)?.title ?? S.alerts.unknownKind(g.kind) }}
              </span>
              <!-- 次数是"这件事发生了几回"，不是一句补充说明（旧仓注释原话） -->
              <span
                v-if="g.count > 1"
                class="rounded-full px-2 py-0.5 text-xs tabular-nums"
                :style="{
                  background: SEVERITY_STYLE[g.severity]?.bg ?? '#f1f5f9',
                  color: SEVERITY_STYLE[g.severity]?.color ?? '#6b7280'
                }"
                >{{ g.count }}</span
              >
            </div>
            <!-- 哪个库、什么时候：落款单独一行（旧仓注释原话） -->
            <div class="mt-1 flex items-center gap-2">
              <span
                class="rounded px-1.5 py-0.5 text-xs"
                :style="
                  g.kb_name
                    ? 'background:#f1f5f9;color:#6b7280'
                    : 'background:#e6f4ff;color:#1677ff'
                "
              >
                {{ g.kb_name ?? S.alerts.system }}
              </span>
              <span
                class="ml-auto shrink-0 text-xs tabular-nums"
                style="color: #9ca3af"
              >
                {{ localDateTime(g.latest_at) }}
              </span>
            </div>
            <p v-if="worded(g.kind)" class="mt-1 text-xs" style="color: #6b7280">
              {{ worded(g.kind)!.hint }}
            </p>
            <ul v-if="g.lines.length > 0" class="mt-1 space-y-1">
              <li
                v-for="t in tallyLines(g)"
                :key="t.line"
                class="text-xs break-words"
                style="color: #9ca3af"
              >
                {{ t.line
                }}<span v-if="t.n > 1" class="tabular-nums"> ×{{ t.n }}</span>
              </li>
              <li
                v-if="g.count > rawLineCount(g)"
                class="text-xs"
                style="color: #9ca3af"
              >
                {{ S.alerts.andMore(g.count - rawLineCount(g)) }}
              </li>
            </ul>
            <!-- 修好之后接着跑：把这次故障窗口里失败的任务放回队列（#216） -->
            <el-button
              v-if="REQUEUE_KINDS.has(g.kind)"
              size="small"
              class="mt-2"
              :disabled="requeue.isPending.value"
              @click.stop="requeue.mutate(g)"
            >
              {{ S.alerts.runAgain }}
            </el-button>
          </div>
        </div>
      </div>
    </el-scrollbar>

    <!-- 底栏：整张列表级的动作跟翻页放一起，离光标最远（旧仓注释原话） -->
    <div
      v-if="groups.length > 0"
      class="flex items-center gap-3 border-t px-4 py-2"
      style="border-color: #e5eaf0"
    >
      <!-- underline 用新 API（'always'|'hover'|'never'）：布尔值在 EP 3.0 移除
           （遗留 5-③ EP deprecation 消音，告警铃底栏「全部已读」） -->
      <el-link
        v-if="groups.some(g => g.unread > 0)"
        type="primary"
        underline="never"
        @click="readAll.mutate()"
      >
        {{ S.alerts.markAllRead }}
      </el-link>
      <el-pagination
        v-model:current-page="page"
        class="ml-auto"
        size="small"
        layout="prev, pager, next"
        :total="total"
        :page-size="PAGE"
        :page-count="pageCount"
        :pager-count="5"
      />
    </div>
  </el-popover>
</template>

<style scoped>
.alert-row {
  border-bottom: 1px solid #e5eaf0;
  padding: 12px 16px;
  cursor: pointer;
}

.alert-row:last-child {
  border-bottom: none;
}

.alert-row:hover {
  background: var(--el-fill-color-light);
}
</style>
