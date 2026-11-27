<!-- 文库页（对照旧仓 pages/Library.tsx，W2 写面全量迁入）：来源栏 + 文档表 +
     状态列 + 抽取进度 + 已删视图 + 来源状态条 + 同步历史。
     写动作（W2）：上传（multipart，选中 folder 来源时 ?source= 归入该夹；拖放区
     highlight）、一键重试失败、文档操作（重跑解析/重抽/墓碑删除——删除 toast 带
     「撤销」原路复活/恢复/真删 purge 打字级确认/失败原文与丢弃详情两张窗）、
     全库重建（清算语义，KB admin，打字级确认）。
     reskin-b1 模板层重造（script 逻辑零动，仅 import + 纯展示 computed）：
     PageHeader + 统计卡条 + 搜索行/表格一体卡（el-table，形态来自 theme.scss
     全局 .el-table 规则）+ 空库虚线拖放卡；行内容自旧壳 write.tsx 行组件逐格
     迁入模板，交互接线（mutation/弹窗）全部原样。 -->
<script setup lang="ts">
import { ref, computed, watch, h, type Component } from "vue";
import { useRoute, RouterLink } from "vue-router";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import XIcon from "~icons/lucide/x";
import HistoryIcon from "~icons/lucide/history";
import RefreshCwIcon from "~icons/lucide/refresh-cw";
import UploadIcon from "~icons/lucide/upload";
import SettingsIcon from "~icons/lucide/settings";
/* 重造新增 import（reskin-b1）：纯展示件——统计卡图标 + 失败徽章图标 */
import CircleAlertIcon from "~icons/lucide/circle-alert";
import FileTextIcon from "~icons/lucide/file-text";
import FolderOpenIcon from "~icons/lucide/folder-open";
import LayersIcon from "~icons/lucide/layers";
import {
  documents,
  extractionDrops,
  upload as uploadApi,
  deleteDocument,
  restoreDocument,
  purgeDocument,
  extractDocument,
  reprocessDocument,
  retryFailedDocs
} from "@/api/knowledge/documents";
import { rebuildGraph, sources as sourcesApi, deleteSource } from "@/api/knowledge/sources";
import type { Doc, ExtractionDrop, SourceView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { useKbScope } from "@/composables/useKbScope";
import { useKbRole } from "@/composables/useKbRole";
import { DangerConfirm } from "@/components/knowledge/dialog";
import {
  SourcesRail,
  scheduleLabel,
  sourceIcon,
  SYNCING_KINDS,
  formatSize,
  type LibrarySelection
} from "@/components/knowledge/sources-rail";
import { RunsPanel } from "./runs-panel";
/* reskin-b2 拆解收敛：write.tsx 已退役（reskin-b1 起行内容逐格迁入 el-table
   列模板，行组件死导出随之消亡）；仍在用的已删视图与两张详情窗独立成文件 */
import { DeletedTable } from "./DeletedTable";
import { DropsModal, ErrorModal } from "./modals";
/* 新建来源弹窗（W3 走查收口补迁）：W1 曾记「新建来源弹窗族留任务 6」，
   任务 6 漏交——对照帧里旧栏的「新建来源」行在新栏缺席，十三种拉取/推送来源
   从此无处可建。弹窗 + 令牌窗都在 ./source-modal。 */
import { SourceModal, SourceTokenModal } from "./source-modal";
/* 来源设置弹窗（遗留 3 收口）：改名/图标（custom）/摄取配置/日程 + danger zone
   删除——已建来源不再需要走 API 手改。 */
import { SourceEditModal } from "./source-edit-modal";
import { UPager, LinkButton, UIconButton } from "@/components/knowledge/kit";
/* PageHeader（T1 自 gvdata 零改写迁入）：22/800 标题 + 副题 + extra 动作区 */
import PageHeader from "@/components/PageHeader/index.vue";

const PAGE_SIZE = 15;
/** 只有手动语义的目的地可上传：All/Uploads/folder。拉取型来源（url/rss/api/custom）
 *  由同步填充，手动塞文件会在下次同步时变成"来源里不存在"的孤儿 */
const ACCEPT =
  ".pdf,.docx,.xlsx,.xls,.ods,.pptx,.md,.txt,.html,.htm,.csv,.tsv,.json,.yaml,.yml,.xml,.log,.png,.jpg,.jpeg,.webp,.tif,.tiff,.bmp,.gif,.mp3,.wav,.m4a,.flac,.ogg,.opus,.aac,.amr";

const route = useRoute();
const queryClient = useQueryClient();
// KbScope 对等物（每个 kb 页面 setup 调一次）；库名给重建确认的打字解锁用
const scope = useKbScope();
const kbName = computed(() => scope.kb.value?.name ?? "");
// 权限闸（W2）：canWrite=非 viewer（上传/删除/重抽/重试），isKbAdmin（purge/重建）
const { canWrite, isKbAdmin } = useKbRole();
const kbId = computed(
  () => (route.params as { kbId?: string } | undefined)?.kbId ?? ""
);
// 从文档查看页的来源栏跳回时带 ?src= 定位到对应文件夹
const srcParam = computed(() =>
  typeof route.query.src === "string" ? route.query.src : ""
);
const selection = ref<LibrarySelection>(srcParam.value || "all");
// 新建来源：弹窗开关 + 建成的推送来源（api/statements）转令牌窗（0014）
const addingSource = ref(false);
const tokenTarget = ref<{ id: string; kind: string } | null>(null);
// 来源设置（遗留 3）：编辑弹窗开关 + 删除 mutation（文档保留落回 Uploads）
const editingSource = ref(false);
const removeSource = useMutation({
  mutationFn: (id: string) => deleteSource(kbId.value, id),
  onSuccess: () => {
    // 来源没了：落回 All documents（旧仓 setSelection("all") 同款）
    selection.value = "all";
    invalidate();
  },
  onError: (e: Error) => toast.error(e.message)
});
function closeEdit() {
  editingSource.value = false;
}
function deleteEditing() {
  const s = selectedSource.value;
  editingSource.value = false;
  if (s) removeSource.mutate(s.id);
}
function onSourceCreated(id?: string, pushKind?: string) {
  addingSource.value = false;
  queryClient.invalidateQueries({ queryKey: ["sources", kbId.value] });
  queryClient.invalidateQueries({ queryKey: ["docCount", kbId.value] });
  if (id && pushKind) tokenTarget.value = { id, kind: pushKind };
}
watch(srcParam, v => (selection.value = v || "all"));
const page = ref(0);
const filter = ref("");
// 按抽取状态筛。**空 = 不筛**——五篇失败混在二十七篇里，从前只能一页页看
const graphFilter = ref("");
const showHistory = ref(false);
// 拖放区 highlight + 上传入口
const dragging = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
// 失败详情弹窗：{文件名, 哪条管道, 原文}
const errorView = ref<{ file: string; kind: string; text: string } | null>(null);
// 丢弃详情弹窗：这篇文档抽出来却没落地的事实
const dropsView = ref<{ file: string; rows: ExtractionDrop[] } | null>(null);
// 真删（#268 下半）与全库重建：打字级确认
const purging = ref<Doc | null>(null);
const rebuilding = ref(false);

// 切换文件夹回到第一页、清空过滤、退出历史视图；过滤词变化回到第一页
watch(selection, () => {
  page.value = 0;
  filter.value = "";
  graphFilter.value = "";
  showHistory.value = false;
});
watch([filter, graphFilter], () => (page.value = 0));

const docs = useQuery({
  queryKey: computed(() => [
    "documents",
    kbId.value,
    selection.value,
    filter.value,
    graphFilter.value,
    page.value
  ]),
  queryFn: () =>
    documents(kbId.value, {
      source:
        selection.value === "all" || selection.value === "deleted"
          ? undefined
          : selection.value === "uploads"
            ? "none"
            : selection.value,
      state: selection.value === "deleted" ? "deleted" : undefined,
      q: filter.value.trim() || undefined,
      graph: graphFilter.value || undefined,
      limit: PAGE_SIZE,
      offset: page.value * PAGE_SIZE
    }),
  // 翻页时别把上一页闪成空白（旧仓 placeholderData 同语义）
  placeholderData: (prev: unknown) => prev as never
});
const sources = useQuery({
  queryKey: computed(() => ["sources", kbId.value]),
  queryFn: () => sourcesApi(kbId.value)
});
// 整库一次取回后按文档分组：抽取丢弃的行数很小，好过每行发一个请求
const drops = useQuery({
  queryKey: computed(() => ["extraction-drops", kbId.value]),
  queryFn: () => extractionDrops(kbId.value)
});
const dropsByDoc = computed(() => {
  const m = new Map<string, ExtractionDrop[]>();
  for (const d of drops.data.value?.drops ?? []) {
    const list = m.get(d.document_id);
    if (list) list.push(d);
    else m.set(d.document_id, [d]);
  }
  return m;
});

/** 写动作的失效面（对照旧仓 Library.invalidate 逐键）：documents 走 SSE 事件流
 *  的键头，docCount 是来源栏的计数键 */
function invalidate() {
  for (const key of [
    ["documents", kbId.value],
    ["docCount", kbId.value],
    ["sources", kbId.value],
    ["extraction-drops", kbId.value]
  ])
    queryClient.invalidateQueries({ queryKey: key });
}

// 一键重试这个作用域里全部失败的。**逐篇入队在服务端做**——排队还带着
// 别的动作（解雇在跑的任务、清增量标记），绕过去会留下半截状态
const retryFailed = useMutation({
  mutationFn: () =>
    retryFailedDocs(
      kbId.value,
      selection.value === "all"
        ? undefined
        : selection.value === "uploads"
          ? "none"
          : selection.value
    ),
  onSuccess: r => {
    toast.success(S.library.retryQueued(r.queued));
    invalidate();
  },
  onError: (e: Error) => toast.error(e.message)
});

const upload = useMutation({
  // 选中 folder 来源时，上传直接归入该文件夹（?source=）
  mutationFn: (files: FileList | File[]) => {
    const folder = sources.data.value?.sources.find(
      s => s.id === selection.value && s.kind === "folder"
    );
    return uploadApi(kbId.value, Array.from(files), folder?.id);
  },
  onSuccess: invalidate
});
// 删除是墓碑（#268）：提示里给一个「撤销」，点了原路复活
const restore = useMutation({
  mutationFn: (id: string) => restoreDocument(id),
  onSuccess: () => {
    toast.success(S.library.restored);
    invalidate();
  },
  onError: (e: unknown) => toast.error(String(e))
});
const remove = useMutation({
  mutationFn: (id: string) => deleteDocument(id),
  onSuccess: (r, id) => {
    toast.info(S.library.deletedWithFacts(r.invalidated_facts), {
      label: S.library.undo,
      onClick: () => restore.mutate(id)
    });
    invalidate();
  }
});
// 真删（#268 下半）：打字级确认，库管理员，回不来
const purge = useMutation({
  mutationFn: (id: string) => purgeDocument(id),
  onSuccess: () => {
    purging.value = null;
    toast.success(S.library.purged);
    invalidate();
  },
  onError: (e: unknown) => toast.error(String(e))
});
const extract = useMutation({
  mutationFn: (id: string) => extractDocument(id),
  onSuccess: invalidate
});
const reprocess = useMutation({
  mutationFn: (id: string) => reprocessDocument(id),
  onSuccess: invalidate
});
// 全库重建：清算语义，仅 KB admin
const rebuild = useMutation({
  mutationFn: () => rebuildGraph(kbId.value),
  onSuccess: r => {
    rebuilding.value = false;
    toast.success(
      S.library.rebuildDone(r.entities_removed, r.facts_removed, r.queued)
    );
    invalidate();
  },
  onError: (e: Error) => toast.error(e.message)
});

function onPickFiles(e: Event) {
  const files = (e.target as HTMLInputElement).files;
  // **先快照再清空**：`value = ""` 会把 FileList 掏空——mutation 真正发请求时
  // 拿到的是空表，服务端回 no_files（走查实测）。File 引用不随输入框清空失效
  if (files?.length) upload.mutate(Array.from(files));
  (e.target as HTMLInputElement).value = "";
}
function onDrop(e: DragEvent) {
  e.preventDefault();
  dragging.value = false;
  if (!canUpload.value) return;
  // 同上：DataTransfer 在事件处理外不可靠，快照成普通数组
  if (e.dataTransfer?.files.length) upload.mutate(Array.from(e.dataTransfer.files));
}

const pagedDocs = computed(() => docs.data.value?.docs ?? []);
const totalDocs = computed(() => docs.data.value?.total ?? 0);
const sourceList = computed(() => sources.data.value?.sources ?? []);
const selectedSource = computed(() =>
  sourceList.value.find(s => s.id === selection.value)
);
const headerTitle = computed(() => {
  const sel = selection.value;
  return (
    selectedSource.value?.name ??
    (sel === "uploads"
      ? S.library.uploads
      : sel === "deleted"
        ? S.library.deleted
        : S.library.title)
  );
});
const hasQuery = computed(
  () => filter.value.trim().length > 0 || graphFilter.value.length > 0
);
const canUpload = computed(
  () =>
    canWrite.value &&
    (selection.value === "all" ||
      selection.value === "uploads" ||
      selectedSource.value?.kind === "folder")
);
const hasDocs = computed(() => pagedDocs.value.length > 0);

/** 同一语汇：拉取型与推送型都是「同步」状态行 */
function isSyncingSource(s: SourceView): boolean {
  return SYNCING_KINDS.has(s.kind) || s.kind === "api" || s.kind === "statements";
}
function sourceConfigSummary(s: SourceView): string {
  const cfg = s.config ?? {};
  const urls = cfg.urls;
  return (
    cfg.feed_url ??
    cfg.endpoint ??
    cfg.repo ??
    (Array.isArray(urls) ? `${urls.length} URLs` : "")
  );
}
function sourceIconData(s: SourceDoc | SourceView): Component {
  return sourceIcon(s as SourceView);
}
/** 行内来源图标：Vue 的 keyed patch 下 VNode 必须每帧新造（同 SourcesRail） */
function rowIcon(d: SourceDoc): Component | null {
  const s = docSource(d);
  return s ? h(sourceIconData(s), { width: 12, height: 12, class: "shrink-0 text-ink-2" }) : null;
}
function dropTotal(docId: string): number {
  return (dropsByDoc.value.get(docId) ?? []).reduce((n, d) => n + d.count, 0);
}
function docSource(doc: SourceDoc): SourceView | null {
  return sourceList.value.find(s => s.id === doc.source_id) ?? null;
}
/** 墓碑行的来源列名（null = Uploads） */
function deletedSourceName(d: Doc): string {
  return docSource(d)?.name ?? S.library.uploads;
}
type SourceDoc = { source_id: string | null };

/* ---------- 重造新增的纯展示辅助（reskin-b1）：只读上面已有的展示态
   （docs/sources 查询、mutation pending），不碰 useQuery/mutation/watch、
   权限闸与任何写语义。统计口径：文档数 = documents page 的 total（随
   所选作用域——all 视图即全库）；文本块 = 当前页文档行 chunk_count 之和
   （列表侧聚合，无全库分块端点）；来源数 = sources 列表长度。 ---------- */

/** 表格行内动作的联动禁用（原 write.tsx 行组件 busy prop 等值，纯展示） */
const rowBusy = computed(
  () =>
    remove.isPending.value ||
    extract.isPending.value ||
    reprocess.isPending.value
);
/** 统计卡「文本块」：当前页各行分块数之和（口径见上） */
const chunkSum = computed(() =>
  pagedDocs.value.reduce((n, d) => n + (d.chunk_count ?? 0), 0)
);
/** 状态/图谱列文案查表（原 write.tsx 行组件内同款，纯展示） */
function statusText(d: { status: string }): string {
  return S.library.status[d.status as keyof typeof S.library.status] ?? d.status;
}
function graphText(d: { graph_status: string }): string {
  return (
    S.library.graphStatus[
      d.graph_status as keyof typeof S.library.graphStatus
    ] ?? d.graph_status
  );
}
/** 来源列图标（纯展示）：返回组件交 <component :is> 渲染（VNode 不能进 :is，
    rowIcon 的 h() 形态留给旧壳行组件的 prop 工厂） */
function rowSourceIcon(row: SourceDoc): Component | null {
  const s = docSource(row);
  return s ? sourceIconData(s) : null;
}
</script>

<template>
  <div class="knowledge-page flex h-full">
    <SourcesRail
      :kb-id="kbId"
      :active="selection"
      :show-add="true"
      @select="(sel: LibrarySelection) => (selection = sel)"
      @add="addingSource = true"
    />

    <!-- 文档区（视图切换时滚动条出没不再引起水平抖动）；它同时是拖放区——
         真反馈是 dragging 时的 highlight，常态下指针经过亮一下边框 -->
    <div
      class="min-w-0 flex-1 overflow-y-auto overscroll-contain px-8 py-6 [scrollbar-gutter:stable]"
      @dragover.prevent="canUpload && (dragging = true)"
      @dragleave="dragging = false"
      @drop="onDrop"
    >
      <div>
        <!-- 页头（reskin-b1 六要素之一）：PageHeader 范式。标题语义原样
             （headerTitle 随所选作用域：all=文库/选中来源=来源名/…）；库名副题
             已撤（左下角库切换器常驻显示，用户走查）；
             唯一主行动=上传按钮，canUpload 闸原样（canWrite 且手动语义目的地） -->
        <PageHeader :title="headerTitle">
          <template #extra>
            <el-button
              v-if="canUpload"
              type="primary"
              @click="fileInput?.click()"
            >
              <UploadIcon width="12" height="12" class="mr-1" />
              {{ S.library.upload }}
            </el-button>
          </template>
        </PageHeader>

        <!-- 统计卡条（六要素之二，styleGuide 范式）：文档数 / 文本块 / 来源数。
             口径见 script 纯展示段注释（文档数=当前作用域 total、文本块=当前页
             行和、来源数=来源列表长度——不新增后端端点） -->
        <div class="stat-grid lib-stats">
          <div class="lib-stat">
            <div class="lib-stat-icon is-primary">
              <FileTextIcon width="20" height="20" />
            </div>
            <div class="lib-stat-info">
              <div class="lib-stat-value">{{ totalDocs }}</div>
              <div class="lib-stat-label">{{ S.nav.docs }}</div>
            </div>
          </div>
          <div class="lib-stat">
            <div class="lib-stat-icon is-success">
              <LayersIcon width="20" height="20" />
            </div>
            <div class="lib-stat-info">
              <div class="lib-stat-value">{{ chunkSum }}</div>
              <div class="lib-stat-label">{{ S.library.colChunks }}</div>
            </div>
          </div>
          <div class="lib-stat">
            <div class="lib-stat-icon is-warning">
              <FolderOpenIcon width="20" height="20" />
            </div>
            <div class="lib-stat-info">
              <div class="lib-stat-value">{{ sourceList.length }}</div>
              <div class="lib-stat-label">{{ S.library.sources }}</div>
            </div>
          </div>
        </div>

        <input
          ref="fileInput"
          type="file"
          multiple
          hidden
          :accept="ACCEPT"
          @change="onPickFiles"
        />

        <!-- 选中来源的状态条：同步状态 / 上次同步 / History 切换（读面） -->
        <div
          v-if="selectedSource"
          class="mb-3 rounded-card border border-line bg-card"
        >
          <div class="flex items-center gap-3 px-4 py-3 text-sub">
            <template v-if="isSyncingSource(selectedSource)">
              <span
                class="h-1.5 w-1.5 shrink-0 rounded-full"
                :class="
                  selectedSource.last_sync_status === 'ok'
                    ? 'bg-success'
                    : selectedSource.last_sync_status === 'failed'
                      ? 'bg-danger'
                      : 'bg-warning'
                "
              />
              <span class="shrink-0 whitespace-nowrap text-ink-2">
                {{
                  (selectedSource.kind === "api" ||
                  selectedSource.kind === "statements"
                    ? S.library.pushStatus
                    : S.library.syncStatus)[selectedSource.last_sync_status]
                }}
              </span>
              <span
                v-if="selectedSource.last_sync_at"
                class="shrink-0 whitespace-nowrap tabular-nums text-ink-2"
              >
                {{ selectedSource.last_sync_at.slice(0, 16).replace("T", " ") }}
              </span>
              <span
                v-if="
                  selectedSource.last_sync_status === 'ok' &&
                  selectedSource.last_sync_added > 0
                "
                class="text-success"
              >
                {{ S.library.lastSyncAdded(selectedSource.last_sync_added) }}
              </span>
              <span
                v-if="selectedSource.last_sync_error"
                class="min-w-0 truncate text-danger"
                :title="selectedSource.last_sync_error"
              >
                {{ selectedSource.last_sync_error }}
              </span>
              <span
                v-if="SYNCING_KINDS.has(selectedSource.kind)"
                class="min-w-0 truncate text-ink-2"
              >
                {{ sourceConfigSummary(selectedSource) }}
              </span>
              <span
                v-if="SYNCING_KINDS.has(selectedSource.kind)"
                class="shrink-0 tabular-nums text-ink-2"
              >
                {{ scheduleLabel(selectedSource) }}
              </span>
            </template>
            <span v-else class="min-w-0 truncate text-ink-2">
              {{
                S.library.sourceKindHints[
                  selectedSource.kind as keyof typeof S.library.sourceKindHints
                ] ?? ""
              }}
            </span>
            <div class="ml-auto flex shrink-0 items-center gap-2">
              <el-button
                v-if="isSyncingSource(selectedSource)"
                size="small"
                :type="showHistory ? 'primary' : 'default'"
                @click="showHistory = !showHistory"
              >
                <HistoryIcon width="11" height="11" class="mr-1" />
                {{ S.library.syncHistory }}
              </el-button>
              <!-- 来源设置（遗留 3 收口，对照旧仓状态条的 Settings 图标钮）：
                  编辑是写动作，随新壳 W2 权限闸纪律只给非 viewer -->
              <el-button
                v-if="canWrite"
                size="small"
                :title="S.library.sourceSettings"
                @click="editingSource = true"
              >
                <SettingsIcon width="12" height="12" />
              </el-button>
            </div>
          </div>
        </div>

        <!-- 抽取进度。数来自服务端，按来源作用域算——翻一页进度条不会跳 -->
        <div
          v-if="(docs.data.value?.extracting ?? 0) > 0"
          class="mb-3 rounded-card border border-line bg-card px-4 py-3"
        >
          <div class="mb-2 flex items-center justify-between text-sub text-ink-2">
            <span>{{
              S.library.extractProgress(
                docs.data.value!.done,
                docs.data.value!.done + docs.data.value!.extracting
              )
            }}</span>
            <span class="tabular-nums text-ink-2">{{
              Math.round(
                (docs.data.value!.done /
                  Math.max(
                    docs.data.value!.done + docs.data.value!.extracting,
                    1
                  )) *
                  100
              )
            }}%</span>
          </div>
          <div class="h-1 overflow-hidden rounded-full bg-fill">
            <div
              class="progress-fill h-full bg-warning"
              :style="{
                width: `${
                  (docs.data.value!.done /
                    Math.max(
                      docs.data.value!.done + docs.data.value!.extracting,
                      1
                    )) *
                  100
                }%`
              }"
            />
          </div>
        </div>

        <!-- 上传中/失败两行：进度反馈在列表上方，不抢正文的位 -->
        <div
          v-if="upload.isPending.value"
          class="mb-3 text-body text-warning"
        >
          {{ S.library.uploading }}
        </div>
        <div
          v-if="upload.isError.value"
          class="mb-3 text-body text-danger"
        >
          {{ S.library.uploadFailed }}: {{ String(upload.error.value) }}
        </div>

        <RunsPanel
          v-if="showHistory && selectedSource"
          :kb-id="kbId"
          :source-id="selectedSource.id"
        />
        <template v-else>
          <!-- 表格卡（styleGuide 范式：搜索行 + 表格 + 分页一体）；它同时是
               拖放落点的视觉反馈——dragging 时主色描边+光晕，拖放事件仍挂在
               外层文档区容器上（上传交互零动） -->
          <div
            class="card lib-table-card"
            :class="dragging ? 'is-dragging' : ''"
          >
            <!-- 搜索行：名称过滤 / 抽取状态筛 / 一键重试 / 全库重建 -->
            <div class="lib-search-bar">
              <div class="relative min-w-0 flex-1">
                <SearchIcon
                  class="pointer-events-none absolute left-3 top-1/2 z-10 -translate-y-1/2 text-ink-2"
                  width="13"
                  height="13"
                />
                <el-input
                  v-model="filter"
                  class="library-filter"
                  :placeholder="S.library.filterPlaceholder"
                  @keydown.escape="filter = ''"
                />
                <button
                  v-if="filter"
                  type="button"
                  class="absolute right-2 top-1/2 z-10 -translate-y-1/2 text-ink-2 hover:text-ink"
                  :title="S.library.close"
                  @click="filter = ''"
                >
                  <XIcon width="12" height="12" />
                </button>
              </div>
              <!-- 按抽取状态筛。选项写死成那五个：一组固定的管道状态 -->
              <el-select
                v-if="selection !== 'deleted'"
                v-model="graphFilter"
                class="lib-graph-filter"
                size="small"
                :placeholder="S.library.anyStatus"
              >
                <el-option value="" :label="S.library.anyStatus" />
                <el-option value="failed" :label="S.library.statusFailed" />
                <el-option value="done" :label="S.library.statusDone" />
                <el-option value="queued" :label="S.library.statusQueued" />
                <el-option
                  value="extracting"
                  :label="S.library.statusExtracting"
                />
                <el-option value="none" :label="S.library.statusNone" />
              </el-select>
              <!-- 一键重试。**只在真有失败时出现**——没有失败的库不该看到一个
                  点了什么都不会发生的按钮。数字写在按钮上，点之前就知道会动几篇 -->
              <el-button
                v-if="(docs.data.value?.failed ?? 0) > 0 && canUpload"
                size="small"
                :disabled="retryFailed.isPending.value"
                @click="retryFailed.mutate()"
              >
                <RefreshCwIcon width="12" height="12" class="mr-1" />
                {{ S.library.retryFailed(docs.data.value!.failed) }}
              </el-button>
              <!-- 全库重建：清算语义，仅 KB admin；放在 All documents 视图。
                  **按钮本身是普通色**：它只是打开确认框，危险色留给确认框里那个
                  真正动手的按钮——工具栏上常驻一块红，看久了就不红了 -->
              <el-button
                v-if="selection === 'all' && isKbAdmin"
                size="small"
                @click="rebuilding = true"
              >
                <RefreshCwIcon width="12" height="12" class="mr-1" />
                {{ S.library.rebuild }}
              </el-button>
            </div>

            <!-- 已删除视图（#268 墓碑）：恢复 + purge（库管理员，打字级确认） -->
            <DeletedTable
              v-if="selection === 'deleted'"
              :docs="pagedDocs"
              :source-name="deletedSourceName"
              :can-purge="isKbAdmin"
              :busy="restore.isPending.value || purge.isPending.value"
              @restore="(id: string) => restore.mutate(id)"
              @purge="(d: Doc) => (purging = d)"
            />

            <!-- 文档表（reskin-b1 六要素之四：el-table 形态来自 theme.scss
                 全局 .el-table 规则；table-style-optimized.scss 留仓零改写，
                 待真实消费点（终审收口：原页内装配系空转，已移除））。列集与原表一一对应不增删：文件/[来源]/
                 状态/图谱/文本块/大小/动作两列；行内容=旧壳 write.tsx 行组件逐格迁入模板，
                 删除/重跑解析/重抽/失败原文/丢弃详情的接线全部原样 -->
            <el-table
              v-else-if="hasDocs"
              :data="pagedDocs"
              row-key="id"
              class="lib-docs-table"
            >
              <el-table-column :label="S.library.colFile" min-width="200">
                <template #default="{ row }">
                  <RouterLink
                    :to="`/kb/${row.kb_id}/doc/${row.id}`"
                    class="link-inline lib-doc-name"
                    :title="row.filename"
                  >
                    {{ row.filename }}
                  </RouterLink>
                </template>
              </el-table-column>
              <el-table-column
                v-if="selection === 'all'"
                :label="S.library.colSource"
                width="150"
              >
                <template #default="{ row }">
                  <span class="flex items-center gap-2 text-sub text-ink-2">
                    <component
                      :is="rowSourceIcon(row)"
                      :width="12"
                      :height="12"
                      class="shrink-0"
                    />
                    <span class="max-w-28 truncate">
                      {{ docSource(row)?.name ?? S.library.uploads }}
                    </span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="S.library.colStatus" width="130">
                <template #default="{ row }">
                  <button
                    v-if="row.status === 'failed' && row.error"
                    type="button"
                    class="badge-soft badge-soft-danger"
                    @click="
                      errorView = {
                        file: row.filename,
                        kind: S.library.errorParse,
                        text: row.error
                      }
                    "
                  >
                    <CircleAlertIcon class="shrink-0" width="12" height="12" />
                    {{ statusText(row) }}
                  </button>
                  <span
                    v-else
                    class="badge-soft"
                    :class="
                      row.status === 'failed'
                        ? 'badge-soft-danger'
                        : 'badge-soft-info'
                    "
                  >{{ statusText(row) }}</span>
                  <span
                    v-if="row.missing_since"
                    class="ml-2 text-sub text-ink-2"
                    :title="row.missing_since.slice(0, 16).replace('T', ' ')"
                  >
                    {{ S.library.notInSource }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="S.library.colGraph" width="150">
                <template #default="{ row }">
                  <button
                    v-if="row.graph_status === 'failed' && row.graph_error"
                    type="button"
                    class="badge-soft badge-soft-danger"
                    @click="
                      errorView = {
                        file: row.filename,
                        kind: S.library.errorGraph,
                        text: row.graph_error
                      }
                    "
                  >
                    <CircleAlertIcon class="shrink-0" width="12" height="12" />
                    {{ graphText(row) }}
                  </button>
                  <span
                    v-else
                    class="badge-soft"
                    :class="
                      row.graph_status === 'failed'
                        ? 'badge-soft-danger'
                        : 'badge-soft-info'
                    "
                  >{{ graphText(row) }}</span>
                  <!-- 抽出来却没落地的事实。不给警示色：漏掉的事实不是故障，是窄本体
                      本来就会做的事。它只是个值得点开看看的数 -->
                  <LinkButton
                    v-if="dropTotal(row.id) > 0"
                    underline
                    class="ml-2 text-ink-2"
                    @click="
                      dropsByDoc.get(row.id) &&
                        (dropsView = {
                          file: row.filename,
                          rows: dropsByDoc.get(row.id)!
                        })
                    "
                  >
                    {{ S.library.dropsChip(dropTotal(row.id)) }}
                  </LinkButton>
                </template>
              </el-table-column>
              <el-table-column
                :label="S.library.colChunks"
                width="90"
                align="center"
              >
                <template #default="{ row }">
                  <span class="text-ink-2">{{ row.chunk_count || "—" }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="S.library.colSize" width="90">
                <template #default="{ row }">
                  <span class="text-ink-2">{{ formatSize(row.size_bytes) }}</span>
                </template>
              </el-table-column>
              <!-- 动作两列都不带表头：列名说的是「这一格是什么」，
                  而这两格是「能做什么」，标题是多出来的一行噪声。
                  这一格至多一个动作：重跑解析要 status=failed，重抽要
                  status=ready，两者互斥——一个图标，不是三种字 -->
              <el-table-column width="56" align="center">
                <template #default="{ row }">
                  <UIconButton
                    v-if="row.status === 'failed'"
                    :label="S.library.reprocess"
                    :disabled="rowBusy"
                    @click="reprocess.mutate(row.id)"
                  >
                    <RefreshCwIcon width="12" height="12" />
                  </UIconButton>
                  <UIconButton
                    v-else-if="
                      row.status === 'ready' &&
                        ['none', 'failed', 'done'].includes(row.graph_status)
                    "
                    :label="
                      row.graph_status === 'done'
                        ? S.library.reExtract
                        : S.library.extract
                    "
                    :disabled="rowBusy"
                    @click="extract.mutate(row.id)"
                  >
                    <RefreshCwIcon width="12" height="12" />
                  </UIconButton>
                </template>
              </el-table-column>
              <!-- done 也可重抽：本体（描述/新类）调整后强制全量重抽正是常规操作 -->
              <el-table-column width="70" align="right">
                <template #default="{ row }">
                  <LinkButton
                    v-if="canWrite"
                    tone="danger"
                    :disabled="rowBusy"
                    @click="remove.mutate(row.id)"
                  >
                    {{ S.library.delete }}
                  </LinkButton>
                </template>
              </el-table-column>
            </el-table>

            <!-- 空态：筛空与库空是两句话；可上传的空库升为虚线拖放卡
                （六要素之五：--color-border 虚线、拖入态由表格卡的
                is-dragging 主色光晕反馈，dropHint/formats 文案原样） -->
            <div v-else-if="hasQuery" class="lib-empty">
              {{ S.library.filterNoMatch }}
            </div>
            <div v-else-if="canUpload" class="lib-dropzone">
              <UploadIcon width="22" height="22" class="lib-dropzone-icon" />
              <p class="lib-dropzone-line">{{ S.library.dropHint }}</p>
              <p class="lib-dropzone-formats">{{ S.library.formats }}</p>
            </div>
            <div v-else class="lib-empty">
              {{ S.library.emptyPull }}
            </div>
          </div>

          <UPager
            :total="totalDocs"
            :page-size="PAGE_SIZE"
            :page="page"
            @page="(p: number) => (page = p)"
          />
        </template>
      </div>
    </div>

    <!-- 失败详情 / 丢弃详情两张窗 -->
    <ErrorModal
      v-if="errorView"
      :file="errorView.file"
      :kind="errorView.kind"
      :text="errorView.text"
      @close="errorView = null"
    />
    <DropsModal
      v-if="dropsView"
      :file="dropsView.file"
      :rows="dropsView.rows"
      @close="dropsView = null"
    />
    <!-- 真删：打字级确认（内容没了，回不来） -->
    <DangerConfirm
      v-if="purging"
      :title="S.library.purgeTitle"
      :hint="S.library.purgeHint(purging.filename)"
      :require-text="purging.filename"
      :confirm-label="S.library.purgeConfirm"
      :cancel-label="S.library.cancel"
      :busy="purge.isPending.value"
      @confirm="purge.mutate(purging.id)"
      @cancel="purging = null"
    />
    <!-- 全库重建：打字级确认（清空图层不可逆）。解锁文本是**库名**（旧仓 kb.name）——
        all 视图标题是静态词「文库」，用它等于没有解锁 -->
    <DangerConfirm
      v-if="rebuilding"
      :title="S.library.rebuildTitle"
      :hint="S.library.rebuildHint(docs.data.value?.ready ?? 0, kbName)"
      :require-text="kbName"
      :confirm-label="S.library.rebuildConfirm"
      :cancel-label="S.library.cancel"
      :busy="rebuild.isPending.value"
      @confirm="rebuild.mutate()"
      @cancel="rebuilding = false"
    />

    <!-- 新建来源（W3 补迁）：建成 api/statements 直接转令牌窗（0014 的一次性显形） -->
    <SourceModal
      v-if="addingSource"
      :kb-id="kbId"
      :on-done="onSourceCreated"
    />
    <!-- 来源设置（遗留 3 收口）：保存 → 关窗失效；删除 → 关窗再删（旧仓同分工）。
        选中来源随查询刷新，删除后 selection 落回 all（removeSource onSuccess） -->
    <SourceEditModal
      v-if="editingSource && selectedSource"
      :kb-id="kbId"
      :source="selectedSource"
      :on-done="closeEdit"
      :on-delete="deleteEditing"
    />
    <SourceTokenModal
      v-if="tokenTarget"
      :kb-id="kbId"
      :source-id="tokenTarget.id"
      :kind="tokenTarget.kind"
      :on-close="() => (tokenTarget = null)"
    />
  </div>
</template>

<style scoped lang="scss">
/* 重造层（reskin-b1）：只引令牌（--color-* / --radius-* / --shadow-* /
   --transition-base），禁硬编码色值；卡容器/徽章/表格基础形态来自全局
   .card / .badge-soft / .el-table（theme.scss） */

/* el-input 内嵌放大镜：让开左缘（与旧仓 w-52 pl-[34px] 同布局） */
.library-filter :deep(.el-input__wrapper) {
  padding-left: 34px;
}

/* ===== 统计卡条（styleGuide StatCard 形态同构；3 卡满轨 3 列，窄屏降
   2 列——断点对齐全局 .stat-grid 的 ≤1200px 语义；观感取舍：不收空位）===== */
.lib-stats {
  grid-template-columns: repeat(3, 1fr);
  margin-bottom: 16px;

  @media (max-width: 1200px) {
    grid-template-columns: repeat(2, 1fr);
  }
}

.lib-stat {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 20px;
  background-color: var(--el-bg-color);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

.lib-stat-icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  font-size: 20px;
  border-radius: 50%;

  &.is-primary {
    color: var(--color-primary);
    background: var(--color-primary-soft);
  }
  &.is-success {
    color: var(--color-success-text);
    background: var(--color-success-soft);
  }
  &.is-warning {
    color: var(--color-warning-text);
    background: var(--color-warning-soft);
  }
}

.lib-stat-value {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.lib-stat-label {
  margin-top: 2px;
  font-size: 13px;
  color: var(--color-ink-3);
}

/* ===== 搜索行（原模板内联 13rem/11rem 宽度移入）===== */
.lib-search-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;

  .library-filter {
    width: 13rem;
  }

  .lib-graph-filter {
    width: 11rem;
  }
}

/* ===== 表格卡 ===== */
.lib-table-card {
  /* 文件名链接：列宽内省略（title 提示全称，语义与原 td truncate 同） */
  .lib-doc-name {
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    vertical-align: bottom;
  }

  /* 拖放落点反馈：主色描边 + 光晕（旧 .highlight 硬编码 rgba 的令牌化替代，
     拖放事件与 dragging 态接线零动） */
  &.is-dragging {
    border-color: var(--color-primary);
    box-shadow:
      var(--shadow-card),
      0 0 0 3px var(--color-primary-glow);
  }
}

/* ===== 空态（筛空 / 拉取型来源空）===== */
.lib-empty {
  padding: 80px 0;
  font-size: 14px;
  text-align: center;
  color: var(--color-ink-2);
}

/* ===== 拖放上传区（可上传的空库）：虚线卡 ===== */
.lib-dropzone {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 48px 24px;
  text-align: center;
  border: 1.5px dashed var(--color-border);
  border-radius: var(--radius-card);
}

.lib-dropzone-icon {
  color: var(--color-ink-3);
}

.lib-dropzone-line {
  margin: 0;
  font-size: 14px;
  color: var(--color-ink-2);
}

.lib-dropzone-formats {
  margin: 0;
  font-size: 13px;
  color: var(--color-ink-3);
}
</style>
