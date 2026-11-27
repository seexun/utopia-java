<!-- 数据映射（对照旧仓 pages/Mappings.tsx 全量）：定义/数据源两档（页头 segmented）。
     定义：状态筛选（计数在徽章里）+ 搜索 + 全选批量拍板 + 最近一轮探索的账；
     未表态的给确认/拒绝，已表态的给编辑（留痕 revisions）与改版史。
     数据源：挂载/同步表结构/卸载 + 探索触发（#503）。Viewer 能看——
     问数的答案直接由口径决定，看得见答案却看不见口径等于要人信一个不给看的算法；
     写动作（拍板/编辑/挂载/探索）canManage 显隐，403 兜底在 client 层。
     final-sprint T2 模板层重造（script 逻辑零动，仅 import 换装 + 纯展示 computed）：
     左栏 rail 退役，定义/数据源升页头 segmented（本体页图/表同款）；状态筛升
     页下 UPill 过滤条（审查页 queue-pill 同款，计数徽章 badge-soft）；映射表落
     el-table 卡（文库范式：搜索行+表格一体；列集以原行实况不增删——概念/数据源/
     怎么算/状态+动作；行内展开的编辑表单与改版史落「怎么算」列）。 -->
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
import { USegmented, UPager, UChip, Loading } from "@/components/knowledge/kit";

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
// 库名副题（纯展示，final-sprint T2）：页头 PageHeader 的 subtitle 料
const kbName = computed(() => scope.kb.value?.name ?? "");
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
/** 搜索栏「重置」（若依列表页同款）：状态回「全部」、清关键词、回第一页 */
function resetSearch() {
  status.value = "all";
  q.value = "";
  page.value = 0;
}

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
  <div class="knowledge-page h-full overflow-y-auto overscroll-contain px-8 py-6">
    <!-- final-sprint T2：整页单列滚动（审查/检索同款根）；kb 页在若依 Layout 的
         AppMain 内渲染（h-full 即内容窗口高，与批次 1-4 七页同根）。注意：
         本注释必须留在根 div 内——dev 模式根级注释是真实节点，会让组件根变
         Fragment，AppMain 的 <Transition mode="out-in"> 离场卡死后吞掉 enter，
         渲染空白主区。 -->
    <div>
      <!-- 整页收进一张卡（重排批 2026-11-27 治"难看"走查）：原裸 PageHeader +
           三行灰字 + 无卡表格，与图谱/审查"白卡 + 卡片头"的壳脱节。
           卡头=标题 + kb chip + 口径/数据源两档，与本体页图/表同位 -->
      <div class="card">
        <div class="flex items-center gap-2 border-b border-line pb-2.5">
          <h1 class="text-title font-semibold text-ink">{{ S.mapping.title }}</h1>
          <span
            class="truncate rounded-full border border-line bg-fill px-2 py-0.5 text-aux text-ink-2"
          >
            {{ kbName }}
          </span>
          <USegmented
            class="ml-auto"
            :value="tab"
            :options="[
              { value: 'definitions', label: S.mapping.tabDefinitions },
              { value: 'sources', label: S.mapping.tabSources }
            ]"
            @change="(v: string) => (tab = v as Tab)"
          />
        </div>

        <!-- 说明句收进卡身首行（原页头下方的裸段落） -->
        <p class="pt-3 text-sub text-ink-2">{{ S.mapping.hint }}</p>

      <!-- ======== 数据源 ======== -->
      <div v-if="tab === 'sources'" class="space-y-4 pt-4">
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
      <!-- 国内 B 端标准列表页（二版 2026-11-27，照若依原生列表：搜索栏一行 +
           通栏表格 + 蓝色文字链接操作列）。一版的"SQL 代码块 + 展开收起 + ⋯ 下拉"
           把 SQL 当主角、每格视觉重量都不同，被走查否掉——表格是用来扫读的，
           SQL 只是普通一列，所有操作直接可见 -->
      <div v-else>
        <!-- 搜索栏（若依列表页同款 el-form inline）：状态 + 关键词 + 查询/重置 -->
        <el-form :inline="true" class="map-search-form" @submit.prevent>
          <el-form-item :label="S.mapping.colStatus">
            <el-select v-model="status" class="map-w-36" @change="page = 0">
              <el-option
                v-for="f in FILTERS"
                :key="f.key"
                :label="f.n != null && f.n > 0 ? f.label + ' (' + f.n + ')' : f.label"
                :value="f.key"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="q"
              class="map-w-64"
              :placeholder="S.mapping.searchPlaceholder"
              clearable
              @input="page = 0"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="page = 0">{{
              S.mapping.query
            }}</el-button>
            <el-button @click="resetSearch">{{ S.mapping.reset }}</el-button>
          </el-form-item>
        </el-form>

        <!-- 工具行：左侧批量动作（有选中才出现），与表格隔一条线 -->
        <div
          class="map-toolbar"
          :class="{ 'map-toolbar--empty': !(selectedIds.length > 0 && canManage) }"
        >
          <template v-if="selectedIds.length > 0 && canManage">
            <span class="text-sub text-ink-2">
              {{ S.mapping.selected(selectedIds.length) }}
            </span>
            <el-button size="small" @click="decideBatch(selectedIds, 'confirmed')">
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

        <Loading v-if="data.isLoading.value" />
        <!-- 最近一轮探索的账（#503）：单看列表答不了「漏了多少」 -->
        <p v-else-if="data.data.value?.last_run" class="map-run-note">
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
        <p v-if="status === 'rejected'" class="map-run-note">
          {{ S.mapping.rejectedHint }}
        </p>

        <!-- 通栏表格：表头浅灰、行高一致；操作列蓝色文字链接（若依同款） -->
        <div v-else class="map-table-card">
          <el-table
            :data="data.data.value?.items ?? []"
            row-key="id"
            class="map-defs-table"
          >
            <!-- 选择列（若依同款首列）：表头全选/半选，仅待审批行可勾 -->
            <el-table-column width="48" align="center">
              <template #header>
                <el-checkbox
                  v-if="selectable.length > 0 && canManage"
                  :model-value="selectable.every(m => picked.has(m.id))"
                  :indeterminate="
                    selectable.some(m => picked.has(m.id)) &&
                    !selectable.every(m => picked.has(m.id))
                  "
                  @change="
                    (v: unknown) =>
                      (picked = v ? new Set(selectable.map(m => m.id)) : new Set())
                  "
                />
              </template>
              <template #default="{ row: m }">
                <el-checkbox
                  v-if="m.status === 'proposed' && canManage"
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
              </template>
            </el-table-column>
            <el-table-column :label="S.mapping.colConcept" min-width="220">
              <template #default="{ row: m }">
                <!-- 概念名=蓝色可点（若依列表"名称即入口"惯例）：点开行内编辑 -->
                <span class="map-link" @click="openEdit(m)">{{
                  m.concept_name
                }}</span>
                <span v-if="m.unit" class="ml-1 text-ink-2">[{{ m.unit }}]</span>
                <span v-if="m.derived" class="map-tag">{{
                  S.mapping.derivedBadge
                }}</span>
              </template>
            </el-table-column>
            <el-table-column
              :label="S.mapping.colSource"
              min-width="120"
              prop="source"
            />
            <el-table-column :label="S.mapping.colDefinition" min-width="360">
              <template #default="{ row: m }">
                <!-- SQL 是普通一列：等宽、单行省略、悬停看全文（title）——
                     不再做代码块/展开收起，行高才一致 -->
                <div class="map-sql" :title="howComputed(m) ?? ''">
                  {{ howComputed(m) ?? S.mapping.noDefinition }}
                </div>
                <div v-if="m.summary" class="map-summary">{{ m.summary }}</div>

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
              </template>
            </el-table-column>
            <el-table-column :label="S.mapping.colStatus" width="96" align="center">
              <template #default="{ row: m }">
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
              </template>
            </el-table-column>
            <!-- 操作列（若依同款）：蓝色文字链接一排横排，带「操作」表头、居中。
                 全部直接可见——二版撤掉一版的实心按钮 + ⋯ 下拉（国内列表页的
                 惯例是操作一眼看全，不藏）。待审批行：确认 / 拒绝 / 编辑 / 改版历史 -->
            <el-table-column :label="S.mapping.colActions" width="230" align="center">
              <template #default="{ row: m }">
                <div v-if="canManage" class="flex items-center justify-center gap-4">
                  <template v-if="m.status === 'proposed'">
                    <button
                      type="button"
                      class="map-act"
                      :disabled="deciding.has(m.id) || picked.has(m.id)"
                      @click="decideOne(m, 'confirmed')"
                    >
                      {{ S.mapping.approve }}
                    </button>
                    <button
                      type="button"
                      class="map-act map-act--danger"
                      :disabled="deciding.has(m.id) || picked.has(m.id)"
                      @click="decideOne(m, 'rejected')"
                    >
                      {{ S.mapping.reject }}
                    </button>
                  </template>
                  <button
                    type="button"
                    class="map-act"
                    :disabled="editingId === m.id"
                    @click="openEdit(m)"
                  >
                    {{ S.mapping.edit }}
                  </button>
                  <button
                    type="button"
                    class="map-act"
                    @click="historyId = historyId === m.id ? null : m.id"
                  >
                    {{ S.mapping.history }}
                  </button>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <div class="py-8 text-body text-ink-2">
                {{
                  q || status !== "all" ? S.mapping.emptyFiltered : S.mapping.empty
                }}
              </div>
            </template>
          </el-table>
          <UPager
            :total="data.data.value?.total ?? 0"
            :page-size="PAGE"
            :page="page"
            @page="(p: number) => (page = p)"
          />
        </div>
      </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* ---------- 国内 B 端标准列表页（二版 2026-11-27，照若依原生列表）---------- */

/* 搜索栏：el-form inline 一行排开；与下方表格隔 12px */
.map-search-form {
  margin-top: 16px;
  margin-bottom: 4px;
}
.map-w-36 { width: 9rem; }
.map-w-64 { width: 16rem; }

/* 工具行：有批量动作时占一行并压底边线；没有时收成 0 高，不留空隙 */
.map-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  padding: 4px 0;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--color-border);
}
.map-toolbar--empty {
  min-height: 0;
  padding: 0;
  margin-bottom: 8px;
  border-bottom: 0;
}

/* 账目说明 / 已拒绝提示：表格上方一行小字 */
.map-run-note {
  margin-bottom: 8px;
  color: var(--color-ink-2);
  font-size: 13px;
  line-height: 20px;
}

/* 表格：表头浅灰底 + 加粗、行高一致（若依 el-table 默认皮） */
:deep(.map-defs-table) {
  font-size: 13px;
}
:deep(.map-defs-table th.el-table__cell) {
  background-color: var(--color-fill);
  color: var(--color-ink);
  font-weight: 600;
}
:deep(.map-defs-table td.el-table__cell) {
  padding-top: 10px;
  padding-bottom: 10px;
}

/* 概念名=蓝色可点（若依列表「名称即入口」惯例） */
.map-link {
  color: var(--color-primary);
  cursor: pointer;
}
.map-link:hover {
  text-decoration: underline;
}

/* 派生标记：浅灰小标签，不抢概念名 */
.map-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--color-fill);
  color: var(--color-ink-2);
  font-size: 12px;
  line-height: 18px;
}

/* SQL：普通一列——等宽、单行省略，悬停看全文（title）。不做代码块底色，
   行与行之间才齐 */
.map-sql {
  overflow: hidden;
  color: var(--color-ink);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.map-summary {
  margin-top: 2px;
  color: var(--color-ink-2);
  font-size: 12px;
  line-height: 18px;
}

/* 操作列：蓝色文字链接（若依同款）；拒绝走危险色。禁用降透明度 */
.map-act {
  padding: 0;
  border: 0;
  background: none;
  color: var(--color-primary);
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
}
.map-act--danger {
  color: var(--color-danger);
}
.map-act:hover:not(:disabled) {
  text-decoration: underline;
}
.map-act:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}
</style>
