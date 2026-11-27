<!-- 本体页（对照旧仓 pages/Ontology.tsx，W1 读面；reskin-b3 双视图重造）：
     工作区型页头合一（2026-11-24 留档清偿批，与图谱页同家族）：独立页头带删除，
     标题+kb chip 收进左栏清单卡卡头；图/表 segmented 迁右侧主区卡头右段
     （表格卡=既有头行右段，图示卡=slim 卡头右段）+ 左栏清单卡（filter +
     Classes/Properties 两小节 + 底部六个入口）+ 右侧主区卡——图示=画布卡
     （批次 2 手法：卡壳 padding 归零、sigma 随卡头之下满铺）/ 表格=三张表卡；
     选中类/关系停靠右侧详情面板（absolute 浮层位随包壳）；
     Rules/Uniqueness/Cases/Misses 是接管主区的整页视图。
     建/改/删/导入/类型消解属任务 6-7——入口行不渲染或降为读面。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import { useResizeObserver } from "@vueuse/core";
import {
  ontology as ontologyApi,
  uniquenessCandidates,
  rules as rulesApi
} from "@/api/knowledge/ontology";
import type { EntityTypeView, RelationTypeView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbScope } from "@/composables/useKbScope";
import { useKbRole } from "@/composables/useKbRole";
import { treeParent, OntologyTables, type TableTab } from "./ontology-tables";
import { SchemaGraphCanvas, type SchemaSelection } from "./schema-graph";
import { SCHEMA_BUDGETS } from "./schema";
import {
  DockedPanel,
  PanelHeader,
  ClassDefinition,
  PropertyDefinition,
  RelationshipsCard,
  AttributesCard,
  InstancesCard
} from "./class-panel";
import { RulesPanel } from "./rules-panel";
import {
  UniquenessPanel,
  OntologyRegressionsPanel,
  MissesPanel
} from "./side-panels";
import {
  ClassDialog,
  PropertyDialog,
  AttributeDialog,
  ConnectDialog
} from "./dialogs";
import { ImportPanel } from "./import-panel";
import {
  URow,
  RailItem,
  UPager,
  SkeletonRows,
  USegmented
} from "@/components/knowledge/kit";

/** 左栏行高（py-2 + 13px 文字 + space-y 间隙）与底部预留（分页器） */
const RAIL_ROW_H = 34;
const RAIL_RESERVED = 80;
/** 上次看的是表还是图。per 浏览器不 per 库（旧仓 VIEW_KEY 同名） */
const VIEW_KEY = "utopia.ontology.view";
/** 兜底页行数（首帧未量到高度时用） */
const RAIL_PAGE = 14;

type Sel =
  | { kind: "class"; id: string }
  | { kind: "relation"; id: string }
  | { kind: "misses" }
  | { kind: "regressions" }
  | { kind: "uniqueness" }
  | { kind: "rules"; focusId?: string }
  | { kind: "import" }
  | { kind: "refine" }
  | { kind: "schema" }
  | null;

/** 编辑弹窗（W2）：面板只展示，建、改、删、连都在窗里发生 */
type Edit =
  | { kind: "class"; existing: EntityTypeView | null; parentId: string | null }
  | {
      kind: "property";
      existing: RelationTypeView | null;
      initialDomain: string | null;
    }
  | { kind: "attribute"; typeId: string; existing: RelationTypeView | null }
  | { kind: "connect"; cls: EntityTypeView }
  | null;

/** 这次选中会不会在模式图右侧停靠一块面板 */
const onPanel = (s: Sel) => s?.kind === "class" || s?.kind === "relation";

const route = useRoute();
const router = useRouter();
const kbStore = useKbContextHook();
const queryClient = useQueryClient();
// KbScope 对等物（每个 kb 页面 setup 调一次）
useKbScope();
// 权限闸（W2）：改本体是写动作——editor+ 才给新建/编辑/连接/导入入口
const { canManage: canEdit } = useKbRole();
const kbId = computed(
  () => (route.params as { kbId?: string } | undefined)?.kbId ?? ""
);

const sel = ref<Sel>(null);
const edit = ref<Edit>(null);
const railTab = ref<"classes" | "properties">("classes");
/* 主区看图还是看表（#498）。默认图，且记住上次那一版——进 localStorage 不进 URL */
const view = ref<"table" | "diagram">(
  localStorage.getItem(VIEW_KEY) === "table" ? "table" : "diagram"
);
const tableTab = ref<TableTab>("classes");
function switchView(v: "table" | "diagram") {
  view.value = v;
  try {
    localStorage.setItem(VIEW_KEY, v);
  } catch {
    // 存不下就只在这一次会话里记着
  }
  if (!onPanel(sel.value)) sel.value = { kind: "schema" };
}
/** Import / Rules / Refine / Overlaps / Unmatched 那几页把主区整个接管了 */
const inWorkflow = computed(
  () =>
    sel.value?.kind === "import" ||
    sel.value?.kind === "refine" ||
    sel.value?.kind === "uniqueness" ||
    sel.value?.kind === "rules" ||
    sel.value?.kind === "misses" ||
    sel.value?.kind === "regressions"
);
/** 面板停在哪一段。跨选中保留：在实例上挨个类看下去是真实的读法 */
const panelTab = ref<"definition" | "relations" | "attributes" | "instances">(
  "definition"
);
const filter = ref("");
const collapsed = ref(new Set<string>());
// 每页行数按列表区实际高度动态算：窗口多高铺多满
const listRef = ref<HTMLElement | null>(null);
const railRows = ref(RAIL_PAGE);
useResizeObserver(listRef, entries => {
  const el = entries[0]?.target as HTMLElement | undefined;
  if (!el) return;
  railRows.value = Math.max(
    5,
    Math.floor((el.clientHeight - RAIL_RESERVED) / RAIL_ROW_H)
  );
});

const data = useQuery({
  queryKey: computed(() => ["ontology", kbId.value]),
  queryFn: () => ontologyApi(kbId.value),
  enabled: computed(() => !!kbId.value)
});
// 并存的取值（#341）：单独取，因为它扫的是事实不是本体
const uniqueness = useQuery({
  queryKey: computed(() => ["uniqueness", kbId.value]),
  queryFn: () => uniquenessCandidates(kbId.value),
  enabled: computed(() => !!kbId.value)
});
// 业务规则：模式图把它们画成边，规则页列出来——同一个 query key 取一次
const rules = useQuery({
  queryKey: computed(() => ["rules", kbId.value]),
  queryFn: () => rulesApi(kbId.value),
  enabled: computed(() => !!kbId.value)
});

const loading = computed(() => data.isPending.value);
const entity_types = computed(() => data.data.value?.entity_types ?? []);
const relation_types = computed(() => data.data.value?.relation_types ?? []);
const relations = computed(() =>
  relation_types.value.filter(r => r.kind !== "attribute")
);

const selectedClass = computed(() => {
  const s = sel.value;
  if (!s || s.kind !== "class") return null;
  return entity_types.value.find(t => t.id === s.id) ?? null;
});
const selectedProp = computed(() => {
  const s = sel.value;
  if (!s || s.kind !== "relation") return null;
  return relation_types.value.find(r => r.id === s.id) ?? null;
});
// 选中类身上挂着的属性，Attributes 一段用它
const classAttributes = computed(() =>
  selectedClass.value
    ? relation_types.value.filter(
        r =>
          r.kind === "attribute" && r.domains.includes(selectedClass.value!.id)
      )
    : []
);

const schemaSelected = computed<SchemaSelection | null>(() => {
  if (sel.value?.kind === "class") return { kind: "class", id: sel.value.id };
  if (sel.value?.kind === "relation")
    return { kind: "relation", id: sel.value.id };
  return null;
});
const schemaBudget = ref(SCHEMA_BUDGETS[0]);

function closePanel() {
  if (!onPanel(sel.value)) return;
  sel.value = { kind: "schema" };
}

function selectFromSchema(next: SchemaSelection | null) {
  if (!next) {
    closePanel();
    return;
  }
  if (next.kind === "rule") sel.value = { kind: "rules", focusId: next.id };
  else sel.value = next as Sel;
}

function onClassSelect(id: string) {
  sel.value = { kind: "class", id };
  if (view.value === "table") {
    tableTab.value = "classes";
  }
}

/* ---------- 左栏清单（类树 / 关系列表） ---------- */

const treeRows = computed(() => {
  const types = entity_types.value;
  const q = filter.value.trim().toLowerCase();
  if (q) {
    return types
      .filter(
        t =>
          t.label.toLowerCase().includes(q) || t.key.toLowerCase().includes(q)
      )
      .map(t => ({ t, depth: 0, hasChildren: false }));
  }
  const children = new Map<string | null, EntityTypeView[]>();
  for (const t of types) {
    const p = treeParent(t);
    if (!children.has(p)) children.set(p, []);
    children.get(p)!.push(t);
  }
  const out: { t: EntityTypeView; depth: number; hasChildren: boolean }[] = [];
  const walk = (parent: string | null, depth: number) => {
    for (const t of children.get(parent) ?? []) {
      const kids = children.get(t.id) ?? [];
      out.push({ t, depth, hasChildren: kids.length > 0 });
      if (!collapsed.value.has(t.id)) walk(t.id, depth + 1);
    }
  };
  walk(null, 0);
  return out;
});
const treePage = ref(0);
watch(filter, () => (treePage.value = 0));
const treePaged = computed(() => {
  const rows = treeRows.value;
  const pageCount = Math.max(1, Math.ceil(rows.length / railRows.value));
  const safe = Math.min(treePage.value, pageCount - 1);
  return {
    rows: rows.slice(safe * railRows.value, (safe + 1) * railRows.value),
    safe,
    total: rows.length
  };
});

const propRows = computed(() => {
  const q = filter.value.trim().toLowerCase();
  return q
    ? relations.value.filter(
        r =>
          r.label.toLowerCase().includes(q) || r.key.toLowerCase().includes(q)
      )
    : relations.value;
});
const propPage = ref(0);
const propPaged = computed(() => {
  const rows = propRows.value;
  const pageCount = Math.max(1, Math.ceil(rows.length / railRows.value));
  const safe = Math.min(propPage.value, pageCount - 1);
  return {
    rows: rows.slice(safe * railRows.value, (safe + 1) * railRows.value),
    safe,
    total: rows.length
  };
});

const uniquenessData = computed(() => uniqueness.data.value?.candidates ?? []);
/** 表格里「选中的那一位」（模板里 sel 展开联合后无法收窄，收口在这） */
const tableSelected = computed(() =>
  sel.value?.kind === "class" || sel.value?.kind === "relation"
    ? { kind: sel.value.kind, id: sel.value.id }
    : null
);

/* ---------- W2 编辑弹窗与导入接线 ---------- */

/** 本体改完（建/改/删/连/导入）之后的公共收尾：失效本体与图键、
 *  再选中建成的那一位（能看到、能接着改） */
function afterOntologyChange(created?: {
  kind: "class" | "relation";
  id?: string;
}) {
  for (const key of [
    ["ontology", kbId.value],
    ["rules", kbId.value],
    ["graph"],
    ["ontologyRegressions", kbId.value]
  ])
    queryClient.invalidateQueries({ queryKey: key });
  if (created?.id)
    sel.value =
      created.kind === "class"
        ? { kind: "class", id: created.id }
        : { kind: "relation", id: created.id };
}
</script>

<template>
  <div class="knowledge-page flex h-full flex-col p-6">
    <div class="flex min-h-0 flex-1 gap-4">
      <!-- 左栏清单卡（reskin-b3）：卡头标题行 + filter + 两小节 + 底部入口。
           页头合一（2026-11-24）：标题=本体、kb chip=库名收进卡头首行（单 h1
           补位）；图/表视图切换不在此行（w-64 三件放不下，segmented 迁右侧
           主区卡头右段，见下方两视图卡）——switchView 原函数原语义：
           localStorage 持久化 + 非 onPanel 选中时回到 schema。内距由
           .onto-rail 收到 12px（标题行不再加 px/pt，分隔线为栏内既有的
           内缩式，同底部入口 border-t），行/树/分页零动 -->
      <aside class="card onto-rail flex w-64 shrink-0 flex-col">
      <!-- 卡头：标题（图谱页卡头同款）。库名 chip 已撤——左下角库切换器
           常驻显示当前库名，页头再说一遍是重复（用户走查） -->
      <div class="flex items-center gap-2 border-b border-line pb-2">
        <h1 class="text-title font-semibold text-ink">{{ S.ontology.title }}</h1>
      </div>
      <!-- 筛选框、两档、清单只属于图那一档——换档时折起来而不是瞬间消失 -->
      <div class="rail-fold" :class="view === 'table' && 'is-folded'">
        <div class="pb-2 pt-1">
          <el-input
            v-model="filter"
            :placeholder="S.ontology.filter"
            size="default"
          />
        </div>
        <div class="pb-2">
          <USegmented
            fill
            :value="railTab"
            :options="[
              { value: 'classes', label: S.ontology.tabClasses },
              { value: 'properties', label: S.ontology.tabProperties }
            ]"
            @change="(v: string) => (railTab = v as 'classes' | 'properties')"
          />
        </div>
        <div ref="listRef" class="flex min-h-0 flex-1 flex-col pb-2">
          <SkeletonRows v-if="loading" :rows="8" />
          <!-- 新建行置顶：随当前段建类/建关系。本体还没到就不给建：
              新类要挑父类，而父类清单此刻是空的 -->
          <template v-else>
            <URow
              v-if="canEdit && !filter.trim()"
              class="mb-1"
              @click="
                edit =
                  railTab === 'classes'
                    ? { kind: 'class', existing: null, parentId: null }
                    : { kind: 'property', existing: null, initialDomain: null }
              "
            >
              <template #icon>
                <Icon icon="lucide:plus" width="14" height="14" />
              </template>
              {{
                railTab === "classes"
                  ? S.ontology.newClass
                  : S.ontology.newProperty
              }}
            </URow>
            <!-- 类层级树（可折叠；filter 时拍平） -->
            <template v-if="railTab === 'classes'">
              <div class="flex flex-col gap-0.5">
                <URow
                  v-for="{ t, depth, hasChildren } in treePaged.rows"
                  :key="t.id"
                  :indent="depth"
                  :active="selectedClass?.id === t.id"
                  @click="onClassSelect(t.id)"
                >
                  <template #icon>
                    <span
                      v-if="hasChildren"
                      @click.stop="
                        (() => {
                          const next = new Set(collapsed);
                          next.has(t.id) ? next.delete(t.id) : next.add(t.id);
                          collapsed = next;
                        })()
                      "
                    >
                      <Icon
                        icon="lucide:chevron-right"
                        width="12"
                        height="12"
                        :class="['turn', !collapsed.has(t.id) && 'rotate-90']"
                      />
                    </span>
                    <span v-else class="block w-3" />
                  </template>
                  <span class="flex items-center gap-2">
                    <span
                      :class="[
                        'size-2.5  shrink-0',
                        t.shape === 'square' ? 'scale-90' : 'rounded-full'
                      ]"
                      :style="{ background: t.color }"
                    />
                    <span class="truncate">{{ t.label }}</span>
                  </span>
                </URow>
              </div>
              <UPager
                :total="treePaged.total"
                :page-size="railRows"
                :page="treePaged.safe"
                @page="(p: number) => (treePage = p)"
              />
            </template>
            <!-- 关系列表 -->
            <template v-else>
              <div class="flex flex-col gap-0.5">
                <URow
                  v-for="r in propPaged.rows"
                  :key="r.id"
                  :active="selectedProp?.id === r.id"
                  @click="sel = { kind: 'relation', id: r.id }"
                >
                  <template #icon>
                    <span class="block w-3" />
                  </template>
                  <span class="flex items-center gap-2">
                    <span class="truncate">{{ r.label }}</span>
                    <span
                      v-if="r.functional"
                      class="badge-soft badge-soft-primary"
                      >1:1</span
                    >
                  </span>
                </URow>
              </div>
              <UPager
                :total="propPaged.total"
                :page-size="railRows"
                :page="propPaged.safe"
                @page="(p: number) => (propPage = p)"
              />
            </template>
          </template>
        </div>
      </div>

      <!-- 底部常驻：关于本体的几个入口。一条分隔线说明它们是钉住的
           （图档展开时清单区占满余高、入口钉底；表格档折叠后同样钉底） -->
      <div
        class="mt-auto flex shrink-0 flex-col gap-0.5 border-t border-line pt-2"
      >
        <RailItem
          :active="sel?.kind === 'import'"
          @click="sel = { kind: 'import' }"
        >
          <template #icon>
            <Icon icon="lucide:upload" width="14" height="14" />
          </template>
          {{ S.ontology.importShort }}
        </RailItem>
        <RailItem
          :active="sel?.kind === 'rules'"
          @click="sel = { kind: 'rules' }"
        >
          <template #icon>
            <Icon icon="lucide:scale" width="14" height="14" />
          </template>
          {{ S.ontology.rulesShort }}
        </RailItem>
        <RailItem
          :active="sel?.kind === 'refine'"
          @click="sel = { kind: 'refine' }"
        >
          <template #icon>
            <Icon icon="lucide:wand-2" width="14" height="14" />
          </template>
          {{ S.ontology.refineShort }}
        </RailItem>
        <RailItem
          :active="sel?.kind === 'uniqueness'"
          :count="loading ? undefined : uniquenessData.length"
          @click="sel = { kind: 'uniqueness' }"
        >
          <template #icon>
            <Icon icon="lucide:split" width="14" height="14" />
          </template>
          {{ S.ontology.uniquenessShort }}
        </RailItem>
        <RailItem
          :active="sel?.kind === 'regressions'"
          @click="sel = { kind: 'regressions' }"
        >
          <template #icon>
            <Icon icon="lucide:check-check" width="14" height="14" />
          </template>
          {{ S.ontology.casesShort }}
        </RailItem>
        <RailItem
          :active="sel?.kind === 'misses'"
          @click="sel = { kind: 'misses' }"
        >
          <template #icon>
            <Icon icon="lucide:inbox" width="14" height="14" />
          </template>
          {{ S.ontology.missesShort }}
        </RailItem>
      </div>
    </aside>

    <!-- 右侧：详情。schema/class/relation 共享同一块工作区（模式图常驻做背景，
        表单停靠右侧）；workflow 几页接管主区（reskin-b3：整页视图收进卡） -->
    <template v-if="inWorkflow">
      <div class="card min-h-0 min-w-0 flex-1 overflow-y-auto overscroll-contain">
        <div>
          <div v-if="loading" class="grid place-items-center py-20">
            <span
              class="inline-block animate-spin rounded-full border-2 border-current border-t-transparent text-ink-2"
              style="width: 20px; height: 20px"
            />
          </div>
          <UniquenessPanel
            v-else-if="sel?.kind === 'uniqueness'"
            :kb-id="kbId"
            :candidates="uniquenessData"
            :relations="relation_types"
            :pending="uniqueness.isPending.value"
          />
          <OntologyRegressionsPanel
            v-else-if="sel?.kind === 'regressions'"
            :kb-id="kbId"
          />
          <RulesPanel
            v-else-if="sel?.kind === 'rules'"
            :kb-id="kbId"
            :focus-id="sel.focusId"
            :classes="entity_types"
            :attributes="relation_types.filter(r => r.kind === 'attribute')"
            :relations="relation_types.filter(r => r.kind === 'relation')"
          />
          <MissesPanel v-else-if="sel?.kind !== 'import'" :kb-id="kbId" />
          <!-- Import（W2 写面板）：预览→确认→落库；Refine 属任务 7 仍占位 -->
          <ImportPanel
            v-if="sel?.kind === 'import'"
            :kb-id="kbId"
            @changed="afterOntologyChange()"
          />
        </div>
      </div>
    </template>
    <template v-else>
      <div class="relative min-h-0 min-w-0 flex-1">
        <!-- 表格视图：主区卡（reskin-b3）——三张表切换 + 表体；页头合一后
             图/表 segmented 落本头行右段（ml-auto，"行右段"语义） -->
        <div
          v-if="view === 'table'"
          class="card onto-table-card absolute inset-0 flex flex-col"
        >
          <div class="flex shrink-0 items-center gap-2 pb-3">
            <USegmented
              size="sm"
              :value="tableTab"
              :options="[
                { value: 'classes', label: S.ontology.tabClasses },
                { value: 'properties', label: S.ontology.tabProperties },
                { value: 'attributes', label: S.ontology.schemaTabAttributes }
              ]"
              @change="(v: string) => (tableTab = v as TableTab)"
            />
            <!-- 视图切换（原页头 extra 原样迁用：value/options/@change 零动） -->
            <USegmented
              class="ml-auto"
              :value="view"
              :options="[
                { value: 'diagram', label: S.ontology.viewDiagram },
                { value: 'table', label: S.ontology.viewTable }
              ]"
              @change="(v: string) => switchView(v as 'table' | 'diagram')"
            />
          </div>
          <OntologyTables
            class="min-h-0 flex-1"
            :loading="loading"
            :tab="tableTab"
            :selected="tableSelected"
            :entity-types="entity_types"
            :relation-types="relation_types"
            :filter="filter"
            @open-class="(t: EntityTypeView) => onClassSelect(t.id)"
            @open-property="
              (r: RelationTypeView) => (sel = { kind: 'relation', id: r.id })
            "
            @see-instances="
              (t: EntityTypeView) => {
                panelTab = 'instances';
                sel = { kind: 'class', id: t.id };
              }
            "
          />
        </div>
        <!-- 图示视图：画布卡（reskin-b3，批次 2 手法）——卡壳内距归零、sigma
             WebGL 在卡头之下满铺，圆角由卡 overflow 收口；页头合一后本卡新增
             slim 卡头（视图 segmented 居右段，与表格卡头右段同位，两视图切换
             控件位置恒定）；浮件（图例/取景/缩放塔）随卡定位锚整体迁移，
             schema-graph 数据流零动 -->
        <div v-else class="card onto-canvas-card absolute inset-0 flex flex-col">
          <div class="shrink-0 border-b border-line px-3 pb-2 pt-2">
            <div class="flex items-center gap-2">
              <!-- 视图切换（原页头 extra 原样迁用：value/options/@change 零动） -->
              <USegmented
                class="ml-auto"
                :value="view"
                :options="[
                  { value: 'diagram', label: S.ontology.viewDiagram },
                  { value: 'table', label: S.ontology.viewTable }
                ]"
                @change="(v: string) => switchView(v as 'table' | 'diagram')"
              />
            </div>
          </div>
          <div class="relative min-h-0 flex-1">
            <SchemaGraphCanvas
              class="absolute inset-0"
              :loading="loading"
              :entity-types="entity_types"
              :relation-types="relation_types"
              :rules="rules.data.value?.rules ?? []"
              :selected="schemaSelected"
              :hidden-count="0"
              :budget="schemaBudget"
              :capped="false"
              @select="selectFromSchema"
              @budget="(b: number) => (schemaBudget = b)"
            />
          </div>
        </div>

        <!-- 类详情面板 -->
        <DockedPanel
          v-if="selectedClass"
          :exiting="!onPanel(sel)"
          @close="closePanel"
        >
          <template #actions>
            <!-- 编辑类（W2）：面板只展示，改动开一扇窗 -->
            <el-tooltip
              v-if="canEdit"
              :content="S.ontology.editClass"
              placement="top"
            >
              <button
                type="button"
                class="rounded-ctl p-1 text-ink-2 hover:bg-fill hover:text-ink"
                @click="
                  edit = {
                    kind: 'class',
                    existing: selectedClass,
                    parentId: null
                  }
                "
              >
                <Icon icon="lucide:pencil" width="13" height="13" />
              </button>
            </el-tooltip>
          </template>
          <template #header>
            <PanelHeader
              :color="selectedClass.color"
              :square="selectedClass.shape === 'square'"
              :title="selectedClass.label"
              :sub="`${selectedClass.key} · ${S.ontology.usage(selectedClass.usage)}`"
              :builtin="selectedClass.builtin"
            />
          </template>
          <template #tabs>
            <USegmented
              fill
              size="sm"
              :value="panelTab"
              :options="[
                { value: 'definition', label: S.ontology.schemaTabDefinition },
                { value: 'relations', label: S.ontology.schemaTabRelations },
                { value: 'attributes', label: S.ontology.schemaTabAttributes },
                { value: 'instances', label: S.ontology.schemaTabInstances }
              ]"
              @change="(v: string) => (panelTab = v as typeof panelTab)"
            />
          </template>
          <!-- 四段用 hidden 藏，不卸载：实例列表翻到的第几页，切回来的时候还在 -->
          <div :class="panelTab === 'definition' ? '' : 'hidden'">
            <ClassDefinition
              :cls="selectedClass"
              :all-types="entity_types"
              @select-class="onClassSelect"
            />
            <!-- 新增入口与列表里的行同一副身材（旧仓 ClassDefinition 的 newSubClass 行）：
                预填父类为当前类 -->
            <URow
              v-if="canEdit"
              class="-mx-2 mt-2"
              @click="
                edit = {
                  kind: 'class',
                  existing: null,
                  parentId: selectedClass.id
                }
              "
            >
              <template #icon>
                <Icon icon="lucide:plus" width="14" height="14" />
              </template>
              {{ S.ontology.newSubClass }}
            </URow>
          </div>
          <div :class="panelTab === 'relations' ? '' : 'hidden'">
            <RelationshipsCard
              :cls="selectedClass"
              :relations="relations"
              :all-types="entity_types"
              @select="(id: string) => (sel = { kind: 'relation', id })"
            />
            <!-- 连接 / 新建关系（W2）：连完跳到那条关系，域/值域改没改一眼可见 -->
            <div v-if="canEdit" class="mt-3 flex items-center gap-2">
              <el-button
                size="small"
                @click="edit = { kind: 'connect', cls: selectedClass }"
              >
                {{ S.ontology.connectOpen }}
              </el-button>
              <el-button
                size="small"
                @click="
                  edit = {
                    kind: 'property',
                    existing: null,
                    initialDomain: selectedClass.id
                  }
                "
              >
                {{ S.ontology.newProperty }}
              </el-button>
            </div>
          </div>
          <div :class="panelTab === 'attributes' ? '' : 'hidden'">
            <AttributesCard :attributes="classAttributes" />
            <el-button
              v-if="canEdit"
              class="mt-3"
              size="small"
              @click="
                edit = {
                  kind: 'attribute',
                  typeId: selectedClass.id,
                  existing: null
                }
              "
            >
              {{ S.ontology.newAttribute }}
            </el-button>
          </div>
          <div :class="panelTab === 'instances' ? '' : 'hidden'">
            <InstancesCard :kb-id="kbId" :type="selectedClass" />
          </div>
        </DockedPanel>

        <!-- 关系详情面板 -->
        <DockedPanel
          v-if="selectedProp"
          :exiting="!onPanel(sel)"
          @close="closePanel"
        >
          <template #actions>
            <!-- 编辑（W2）：面板只展示，改动开一扇窗 -->
            <el-tooltip
              v-if="canEdit"
              :content="S.ontology.editProperty"
              placement="top"
            >
              <button
                type="button"
                class="rounded-ctl p-1 text-ink-2 hover:bg-fill hover:text-ink"
                @click="
                  edit = {
                    kind: 'property',
                    existing: selectedProp,
                    initialDomain: null
                  }
                "
              >
                <Icon icon="lucide:pencil" width="13" height="13" />
              </button>
            </el-tooltip>
          </template>
          <template #header>
            <PanelHeader
              :title="selectedProp.label"
              :sub="`${selectedProp.key} · ${S.ontology.usage(selectedProp.usage)}`"
              :builtin="selectedProp.builtin"
            />
          </template>
          <PropertyDefinition
            :rel="selectedProp"
            :all-types="entity_types"
            :all-relations="relations"
          />
        </DockedPanel>
      </div>
    </template>
    </div>

    <!-- 编辑弹窗（W2）：建成的立刻选中——能看到、能接着改；删掉的把面板收起来 -->
    <ClassDialog
      v-if="edit?.kind === 'class'"
      :kb-id="kbId"
      :existing="edit.existing"
      :parent-id="edit.parentId"
      :all-types="entity_types"
      @close="edit = null"
      @saved="
        (id?: string) => {
          const k = edit;
          edit = null;
          afterOntologyChange(
            k?.kind === 'class' ? { kind: 'class', id } : undefined
          );
        }
      "
      @deleted="
        () => {
          edit = null;
          closePanel();
          afterOntologyChange();
        }
      "
    />
    <PropertyDialog
      v-if="edit?.kind === 'property'"
      :kb-id="kbId"
      :existing="edit.existing"
      :all-types="entity_types"
      :all-relations="relation_types"
      :initial-domain="edit.initialDomain"
      @close="edit = null"
      @saved="
        (id?: string) => {
          const k = edit;
          edit = null;
          afterOntologyChange(
            k?.kind === 'property' ? { kind: 'relation', id } : undefined
          );
        }
      "
      @deleted="
        () => {
          edit = null;
          closePanel();
          afterOntologyChange();
        }
      "
    />
    <AttributeDialog
      v-if="edit?.kind === 'attribute'"
      :kb-id="kbId"
      :type-id="edit.typeId"
      :existing="edit.existing"
      @close="edit = null"
      @saved="
        () => {
          edit = null;
          afterOntologyChange();
        }
      "
      @deleted="
        () => {
          edit = null;
          afterOntologyChange();
        }
      "
    />
    <ConnectDialog
      v-if="edit?.kind === 'connect'"
      :kb-id="kbId"
      :cls="edit.cls"
      :relations="relations"
      @close="edit = null"
      @connected="
        (id: string) => {
          edit = null;
          afterOntologyChange();
          sel = { kind: 'relation', id };
        }
      "
    />
  </div>
</template>

<style scoped>
/* 重造层（reskin-b3）：只引令牌（--color-* / --radius-* / --shadow-*），
   禁硬编码色值；卡容器形态来自全局 .card（theme.scss）。script 逻辑零动——
   本块只管左栏卡 / 画布卡的容器身材 */

/* 左栏清单卡：256px 窄栏不吃 .card 默认 20px 内距，收到 12px
   （对话页会话列表卡同款）；清单滚动条随之落在卡内缘 */
.onto-rail {
  padding: 12px;
}

/* 图示画布卡（批次 2 手法照抄）：sigma 画布无内距（.card 的 20px 内距归零），
   卡头（视图 segmented 行）之下由包裹层满铺余高，圆角由 overflow 收口——
   WebGL 层随容器矩形，超出圆角部分由卡裁掉 */
.onto-canvas-card {
  padding: 0;
  overflow: hidden;
}
</style>
