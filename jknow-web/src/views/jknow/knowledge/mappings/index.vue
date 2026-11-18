<!-- 数据映射（对照旧仓 pages/Mappings.tsx 全量）：左栏切功能（定义/数据源）。
     定义：状态筛选（计数在标签里）+ 搜索 + 全选批量拍板 + 最近一轮探索的账；
     未表态的给确认/拒绝，已表态的给编辑（留痕 revisions）与改版史。
     数据源：挂载/同步表结构/卸载 + 探索触发（#503）。Viewer 能看——
     问数的答案直接由口径决定，看得见答案却看不见口径等于要人信一个不给看的算法；
     写动作（拍板/编辑/挂载/探索）canManage 显隐，403 兜底在 client 层。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import {
  mappings as mappingsApi,
  reviseMapping,
  mappingRevisions,
  kbDataSources,
  kbDataSourcesAvailable,
  mountDataSource,
  unmountDataSource,
  syncDataSourceSchema,
  exploreMappings
} from "@/api/knowledge/mappings";
import { decideMapping } from "@/api/knowledge/review";
import type { ConceptMapping, DataSourceView } from "@/api/knowledge/types";
import { me as meApi } from "@/api/knowledge/auth";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { useKbScope } from "@/composables/useKbScope";
import { useKbRole } from "@/composables/useKbRole";
import { RailItem, UPager, UChip, Loading } from "@/components/knowledge/kit";

const PAGE = 25;

type Tab = "definitions" | "sources";
type StatusFilter = "all" | "proposed" | "confirmed" | "rejected";
type MappingDecision = "confirmed" | "rejected";

const route = useRoute();
const router = useRouter();
const queryClient = useQueryClient();
// KbScope 对等物（每个 kb 页面 setup 调一次）
const scope = useKbScope();
const kbId = scope.kbId;
const { canManage } = useKbRole();
// 注册新连接是部署级动作：管理员给直达入口（["me"] 会话内不变）
const me = useQuery({ queryKey: ["me"], queryFn: meApi });

const tab = ref<Tab>("definitions");
const status = ref<StatusFilter>("all");
const q = ref("");
const page = ref(0);
const picked = ref(new Set<string>());
const deciding = ref(new Set<string>());
watch([tab, status, q, () => kbId.value], () => (picked.value = new Set()));

const data = useQuery({
  queryKey: computed(() => [
    "mappings",
    kbId.value,
    status.value,
    q.value,
    page.value
  ]),
  queryFn: () =>
    mappingsApi(kbId.value, {
      status: status.value === "all" ? undefined : status.value,
      q: q.value || undefined,
      limit: PAGE,
      offset: page.value * PAGE
    }),
  enabled: computed(() => !!kbId.value)
});

const refresh = () =>
  queryClient.invalidateQueries({ queryKey: ["mappings", kbId.value] });

/** 逐条拍板、逐条记账：一条失败整批报错，成了的清选中 */
async function decideBatch(ids: string[], decision: MappingDecision) {
  const outcomes = await Promise.allSettled(
    ids.map(id => decideMapping(kbId.value, id, decision))
  );
  const failure = outcomes.find(o => o.status === "rejected");
  if (failure?.status === "rejected")
    toast.error(
      failure.reason instanceof Error
        ? failure.reason.message
        : String(failure.reason)
    );
  picked.value = new Set();
  refresh();
}

const counts = computed(() => data.data.value?.counts);
const selectable = computed(
  () =>
    data.data.value?.items.filter(
      m => m.status === "proposed" && !deciding.value.has(m.id)
    ) ?? []
);
const selectedIds = computed(
  () =>
    data.data.value?.items
      .filter(
        m =>
          m.status === "proposed" &&
          picked.value.has(m.id) &&
          !deciding.value.has(m.id)
      )
      .map(m => m.id) ?? []
);
const FILTERS = computed(() => [
  {
    key: "all" as StatusFilter,
    label: S.mapping.filterAll,
    n: undefined as number | undefined
  },
  {
    key: "proposed" as StatusFilter,
    label: S.mapping.filterProposed,
    n: counts.value?.proposed
  },
  {
    key: "confirmed" as StatusFilter,
    label: S.mapping.filterConfirmed,
    n: counts.value?.confirmed
  },
  {
    key: "rejected" as StatusFilter,
    label: S.mapping.filterRejected,
    n: counts.value?.rejected
  }
]);

/** 「这个数怎么算」。SQL / 表达式 / 表名按这个优先级取一个 */
function howComputed(m: ConceptMapping): string | null {
  return m.sql ?? m.expr ?? m.table_name ?? null;
}
const statusLabel = (s: string) =>
  s === "proposed"
    ? S.mapping.filterProposed
    : s === "confirmed"
      ? S.mapping.filterConfirmed
      : S.mapping.filterRejected;

/* ---------- 编辑表单（revise：改之前那一版自动进 revisions） ---------- */
const editingId = ref<string | null>(null);
const form = ref({
  table: "",
  expr: "",
  sql: "",
  unit: "",
  summary: "",
  derived: false
});
const saving = ref(false);
function openEdit(m: ConceptMapping) {
  editingId.value = m.id;
  form.value = {
    table: m.table_name ?? "",
    expr: m.expr ?? "",
    sql: m.sql ?? "",
    unit: m.unit ?? "",
    summary: m.summary ?? "",
    derived: m.derived
  };
}
async function saveEdit(m: ConceptMapping) {
  saving.value = true;
  try {
    await reviseMapping(kbId.value, m.id, {
      table_name: form.value.table,
      expr: form.value.expr,
      sql: form.value.sql,
      unit: form.value.unit,
      summary: form.value.summary,
      derived: form.value.derived
    });
    editingId.value = null;
    toast.success(S.toast.saved);
    refresh();
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
  saving.value = false;
}

/* ---------- 改版史：整版快照而不是差异（0006） ---------- */
const historyId = ref<string | null>(null);
const revs = useQuery({
  queryKey: computed(() => ["mappingRevisions", kbId.value, historyId.value]),
  queryFn: () => mappingRevisions(kbId.value, historyId.value!),
  enabled: computed(() => !!historyId.value)
});

async function decideOne(m: ConceptMapping, decision: MappingDecision) {
  deciding.value = new Set([...deciding.value, m.id]);
  try {
    await decideMapping(kbId.value, m.id, decision);
    refresh();
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
  const next = new Set(deciding.value);
  next.delete(m.id);
  deciding.value = next;
}

/* ---------- 数据源（挂载/同步/卸载/探索） ---------- */
const mounted = useQuery({
  queryKey: computed(() => ["kbDataSources", kbId.value]),
  queryFn: () => kbDataSources(kbId.value),
  enabled: computed(() => !!kbId.value && tab.value === "sources")
});
const available = useQuery({
  queryKey: computed(() => ["kbDataSourcesAvail", kbId.value]),
  queryFn: () => kbDataSourcesAvailable(kbId.value),
  enabled: computed(() => !!kbId.value && tab.value === "sources")
});
const mountPick = ref("");
const notice = ref<string | null>(null);
// 与 notice 分开：一个是「成了」，一个是「成了一半」，配色也不同
const warning = ref<string | null>(null);
const invalidDs = () =>
  queryClient.invalidateQueries({ queryKey: ["kbDataSources", kbId.value] });

async function mount(dsId: string) {
  try {
    const r = await mountDataSource(kbId.value, dsId);
    mountPick.value = "";
    notice.value = null;
    // 挂载成了、schema 没成，是两件事。服务端此时回的是 ok（源确实挂上了）
    warning.value = r.schema_error ? S.mapping.schemaFailed : null;
    if (!r.schema_error) notice.value = S.mapping.schemaSynced(r.schema_tables);
    invalidDs();
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}
async function unmount(dsId: string) {
  try {
    await unmountDataSource(kbId.value, dsId);
    invalidDs();
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}
async function sync(dsId: string) {
  try {
    const r = await syncDataSourceSchema(kbId.value, dsId);
    notice.value = S.mapping.schemaSynced(r.schema_tables);
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}
const exploring = ref(false);
async function explore() {
  exploring.value = true;
  try {
    await exploreMappings(kbId.value);
    notice.value = S.mapping.exploreQueued;
    refresh();
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
  exploring.value = false;
}
const mountedList = computed(() => mounted.data.value?.data_sources ?? []);
const mountedIds = computed(() => new Set(mountedList.value.map(d => d.id)));
const mountable = computed(() =>
  (available.data.value?.data_sources ?? []).filter(
    d => !mountedIds.value.has(d.id)
  )
);
const hasMounted = computed(() => mountedList.value.length > 0);
</script>

<template>
  <div class="knowledge-page flex h-full">
    <!-- 左栏切功能，不是标签页：定义与数据源是两件不同的事 -->
    <aside
      class="flex w-64 shrink-0 flex-col gap-0.5 border-r border-line bg-card px-2 py-3"
    >
      <RailItem :active="tab === 'definitions'" @click="tab = 'definitions'">
        <template #icon>
          <Icon icon="lucide:database" width="14" height="14" />
        </template>
        {{ S.mapping.tabDefinitions }}
      </RailItem>
      <RailItem :active="tab === 'sources'" @click="tab = 'sources'">
        <template #icon>
          <Icon icon="lucide:plug" width="14" height="14" />
        </template>
        {{ S.mapping.tabSources }}
      </RailItem>
    </aside>

    <div class="min-w-0 flex-1 overflow-y-auto overscroll-contain px-8 py-6">
      <div class="mb-4">
        <h1 class="wrap-break-word text-page font-extrabold text-ink">
          {{ S.mapping.title }}
        </h1>
        <p class="mt-1 text-sub text-ink-2">{{ S.mapping.hint }}</p>
      </div>

      <!-- ======== 数据源 ======== -->
      <div v-if="tab === 'sources'" class="space-y-4">
        <p class="text-sub text-ink-2">{{ S.mapping.sourcesHint }}</p>
        <div
          class="divide-y divide-line rounded-card border border-line bg-card"
        >
          <div
            v-for="d in mountedList"
            :key="d.id"
            class="flex items-center gap-3 px-4 py-3"
          >
            <div class="min-w-0 flex-1">
              <div class="text-body text-ink">{{ d.name }}</div>
              <div class="truncate tabular-nums text-sub text-ink-2">
                {{ d.summary }}
              </div>
            </div>
            <template v-if="canManage">
              <el-button size="small" @click="sync(d.id)">{{
                S.mapping.syncSchema
              }}</el-button>
              <el-button
                size="small"
                type="danger"
                plain
                @click="unmount(d.id)"
              >
                {{ S.mapping.unmount }}
              </el-button>
            </template>
          </div>
          <p v-if="!hasMounted" class="px-4 py-6 text-body text-ink-2">
            {{ S.mapping.sourcesEmpty }}
          </p>
        </div>
        <div
          v-if="canManage && mountable.length > 0"
          class="flex items-center gap-2"
        >
          <el-select
            v-model="mountPick"
            filterable
            class="flex-1"
            :placeholder="S.mapping.mount + '…'"
          >
            <el-option
              v-for="d in mountable"
              :key="d.id"
              :value="d.id"
              :label="d.name"
            >
              <span class="flex flex-col">
                <span>{{ d.name }}</span>
                <span class="text-aux text-ink-2">{{ d.summary }}</span>
              </span>
            </el-option>
          </el-select>
          <el-button
            type="primary"
            size="small"
            class="shrink-0"
            :disabled="!mountPick"
            @click="mount(mountPick)"
          >
            {{ S.mapping.mount }}
          </el-button>
        </div>
        <!-- 注册新连接是部署级动作：管理员直达入口，其他人指路找管理员 -->
        <el-button
          v-if="me.data.value?.is_admin"
          size="small"
          class="-ml-2"
          @click="
            router.push({ path: '/admin', query: { tab: 'datasources' } })
          "
        >
          <Icon icon="lucide:plus" width="12" height="12" class="mr-1" />
          {{ S.mapping.newConn }}
        </el-button>
        <p
          v-else-if="
            mountable.length === 0 && available.data.value && !hasMounted
          "
          class="text-sub text-ink-2"
        >
          {{ S.mapping.sourcesNoneAvailable }}
        </p>
        <div
          v-if="hasMounted && canManage"
          class="flex items-center gap-3 rounded-card border border-line bg-card px-4 py-3"
        >
          <p class="flex-1 text-sub text-ink-2">{{ S.mapping.exploreHint }}</p>
          <el-button size="small" :disabled="exploring" @click="explore()">
            {{ S.mapping.explore }}
          </el-button>
        </div>
        <p v-if="notice" class="text-sub text-success">{{ notice }}</p>
        <p v-if="warning" class="text-sub text-warning">{{ warning }}</p>
      </div>

      <!-- ======== 定义 ======== -->
      <div v-else class="space-y-4">
        <!-- 一条工具行：筛选、搜索、全选、批量动作排在一起 -->
        <div class="flex flex-wrap items-center gap-2">
          <el-input
            v-model="q"
            :placeholder="S.mapping.searchPlaceholder"
            style="width: 16rem"
            @input="page = 0"
          />
          <el-select v-model="status" style="width: 10rem" @change="page = 0">
            <el-option
              v-for="f in FILTERS"
              :key="f.key"
              :value="f.key"
              :label="f.n != null ? `${f.label} (${f.n})` : f.label"
            />
          </el-select>
          <el-checkbox
            v-if="selectable.length > 0 && canManage"
            class="ml-auto shrink-0"
            :model-value="selectable.every(m => picked.has(m.id))"
            :label="S.mapping.selectPage"
            @change="
              (v: unknown) =>
                (picked = v ? new Set(selectable.map(m => m.id)) : new Set())
            "
          />
          <template v-if="selectedIds.length > 0 && canManage">
            <span class="tabular-nums text-sub text-ink-2">
              {{ S.mapping.selected(selectedIds.length) }}
            </span>
            <el-button
              size="small"
              @click="decideBatch(selectedIds, 'confirmed')"
            >
              {{ S.mapping.approve }}
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              @click="decideBatch(selectedIds, 'rejected')"
            >
              {{ S.mapping.reject }}
            </el-button>
          </template>
        </div>

        <p v-if="status === 'rejected'" class="text-sub text-ink-2">
          {{ S.mapping.rejectedHint }}
        </p>
        <!-- 最近一轮探索的账（#503）：单看列表答不了「漏了多少」 -->
        <p v-if="data.data.value?.last_run" class="text-sub text-ink-2">
          {{
            S.mapping.lastRun({
              tables: data.data.value.last_run.tables_scanned,
              columns: data.data.value.last_run.columns_scanned,
              returned: data.data.value.last_run.returned,
              accepted: data.data.value.last_run.accepted,
              truncated: data.data.value.last_run.schema_truncated
            })
          }}
        </p>
        <p v-else class="text-sub text-ink-2">{{ S.mapping.lastRunMissing }}</p>

        <Loading v-if="data.isLoading.value" />
        <div
          v-else-if="data.data.value && data.data.value.items.length === 0"
          class="py-12 text-center text-body text-ink-2"
        >
          {{
            q || status !== "all" ? S.mapping.emptyFiltered : S.mapping.empty
          }}
        </div>
        <div
          v-else
          class="divide-y divide-line rounded-card border border-line bg-card"
        >
          <div
            v-for="m in data.data.value?.items ?? []"
            :key="m.id"
            class="px-4 py-3"
            :class="picked.has(m.id) ? 'border-line-strong' : ''"
          >
            <div class="flex flex-wrap items-baseline gap-2">
              <el-checkbox
                v-if="m.status === 'proposed' && canManage"
                class="shrink-0 self-center"
                :model-value="picked.has(m.id)"
                :disabled="deciding.has(m.id)"
                :aria-label="S.mapping.selectMapping(m.concept_name, m.source)"
                @change="
                  (v: unknown) => {
                    const next = new Set(picked);
                    v ? next.add(m.id) : next.delete(m.id);
                    picked = next;
                  }
                "
              />
              <span class="text-body text-ink">{{ m.concept_name }}</span>
              <span class="text-aux text-ink-2">{{ m.source }}</span>
              <span v-if="m.unit" class="text-aux text-ink-2"
                >[{{ m.unit }}]</span
              >
              <UChip v-if="m.derived" tone="warn">{{
                S.mapping.derivedBadge
              }}</UChip>
              <span class="flex-1" />
              <UChip
                :tone="
                  m.status === 'confirmed'
                    ? 'success'
                    : m.status === 'proposed'
                      ? 'warn'
                      : 'neutral'
                "
              >
                {{ statusLabel(m.status) }}
              </UChip>
            </div>

            <div class="mt-1 break-all tabular-nums text-sub text-ink-2">
              {{ howComputed(m) ?? S.mapping.noDefinition }}
            </div>
            <p v-if="m.summary" class="mt-1 text-sub text-ink-2">
              {{ m.summary }}
            </p>

            <!-- 编辑表单（revise）：行内展开，改之前那一版自动进 revisions -->
            <div
              v-if="editingId === m.id"
              class="mt-3 space-y-2 border-t border-line pt-3"
            >
              <p class="text-aux text-ink-2">{{ S.mapping.editTitle }}</p>
              <div class="grid grid-cols-2 gap-2">
                <div>
                  <div class="text-aux text-ink-2">
                    {{ S.mapping.fieldTable }}
                  </div>
                  <el-input
                    v-model="form.table"
                    size="small"
                    class="mt-1 tabular-nums"
                  />
                </div>
                <div>
                  <div class="text-aux text-ink-2">
                    {{ S.mapping.fieldUnit }}
                  </div>
                  <el-input v-model="form.unit" size="small" class="mt-1" />
                </div>
              </div>
              <div>
                <div class="text-aux text-ink-2">{{ S.mapping.fieldExpr }}</div>
                <el-input
                  v-model="form.expr"
                  size="small"
                  class="mt-1 tabular-nums"
                />
              </div>
              <div>
                <div class="text-aux text-ink-2">{{ S.mapping.fieldSql }}</div>
                <el-input
                  v-model="form.sql"
                  size="small"
                  class="mt-1 tabular-nums"
                />
              </div>
              <div>
                <div class="text-aux text-ink-2">
                  {{ S.mapping.fieldSummary }}
                </div>
                <el-input v-model="form.summary" size="small" class="mt-1" />
              </div>
              <el-checkbox
                v-model="form.derived"
                :label="S.mapping.fieldDerived"
              />
              <p
                v-if="
                  !form.table.trim() && !form.expr.trim() && !form.sql.trim()
                "
                class="text-sub text-danger"
              >
                {{ S.mapping.needOne }}
              </p>
              <div class="flex gap-2">
                <el-button size="small" @click="editingId = null">
                  {{ S.mapping.cancel }}
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  :disabled="
                    (!form.table.trim() &&
                      !form.expr.trim() &&
                      !form.sql.trim()) ||
                    saving
                  "
                  @click="saveEdit(m)"
                >
                  {{ S.mapping.save }}
                </el-button>
              </div>
            </div>
            <!-- 行里的动作：**拍板那一下描边，其余不是实底** -->
            <div
              v-else-if="canManage"
              class="mt-2 flex flex-wrap items-center gap-1"
            >
              <template v-if="m.status === 'proposed'">
                <el-button
                  size="small"
                  :disabled="deciding.has(m.id) || picked.has(m.id)"
                  @click="decideOne(m, 'confirmed')"
                >
                  {{ S.mapping.approve }}
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :disabled="deciding.has(m.id) || picked.has(m.id)"
                  @click="decideOne(m, 'rejected')"
                >
                  {{ S.mapping.reject }}
                </el-button>
              </template>
              <el-button size="small" text @click="openEdit(m)">
                {{ S.mapping.edit }}
              </el-button>
              <el-button
                size="small"
                text
                @click="historyId = historyId === m.id ? null : m.id"
              >
                {{ S.mapping.history }}
              </el-button>
            </div>

            <!-- 改版史：留痕表是「上季度这个数是怎么算的」的兑现处 -->
            <div
              v-if="historyId === m.id"
              class="mt-3 space-y-2 border-t border-line pt-3"
            >
              <p class="text-aux text-ink-2">{{ S.mapping.historyHint }}</p>
              <p v-if="revs.isLoading.value" class="text-sub text-ink-2">
                {{ S.nav.loading }}
              </p>
              <p v-else-if="revs.isError.value" class="text-body text-danger">
                {{ String(revs.error.value) }}
              </p>
              <p
                v-else-if="(revs.data.value?.revisions ?? []).length === 0"
                class="text-sub text-ink-2"
              >
                {{ S.mapping.historyEmpty }}
              </p>
              <div
                v-for="r in revs.data.value?.revisions ?? []"
                v-else
                :key="r.id"
                class="text-sub"
              >
                <div class="text-ink-2">
                  {{
                    S.mapping.historyBy(
                      r.changed_by_name ?? S.mapping.historyUnknown
                    )
                  }}
                  <span class="ml-2 tabular-nums text-ink-2">
                    {{ r.changed_at.slice(0, 16).replace("T", " ") }}
                  </span>
                </div>
                <div class="break-all tabular-nums text-ink-2">
                  {{
                    r.before.sql ??
                    r.before.expr ??
                    r.before.table_name ??
                    S.mapping.noDefinition
                  }}
                </div>
              </div>
            </div>
          </div>
        </div>
        <UPager
          :total="data.data.value?.total ?? 0"
          :page-size="PAGE"
          :page="page"
          @page="(p: number) => (page = p)"
        />
      </div>
    </div>
  </div>
</template>
