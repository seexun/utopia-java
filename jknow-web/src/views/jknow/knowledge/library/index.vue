<!-- 文库页（对照旧仓 pages/Library.tsx，W2 写面全量迁入）：来源栏 + 文档表 +
     状态列 + 抽取进度 + 已删视图 + 来源状态条 + 同步历史。
     写动作（W2）：上传（multipart，选中 folder 来源时 ?source= 归入该夹；拖放区
     highlight）、一键重试失败、文档操作（重跑解析/重抽/墓碑删除——删除 toast 带
     「撤销」原路复活/恢复/真删 purge 打字级确认/失败原文与丢弃详情两张窗）、
     全库重建（清算语义，KB admin，打字级确认）。 -->
<script setup lang="ts">
import { ref, computed, watch, h, type Component } from "vue";
import { useRoute } from "vue-router";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import XIcon from "~icons/lucide/x";
import HistoryIcon from "~icons/lucide/history";
import RefreshCwIcon from "~icons/lucide/refresh-cw";
import UploadIcon from "~icons/lucide/upload";
import SettingsIcon from "~icons/lucide/settings";
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
  type LibrarySelection
} from "@/components/knowledge/sources-rail";
import { RunsPanel } from "./runs-panel";
import { DocRow, DeletedTable, DropsModal, ErrorModal } from "./write";
/* 新建来源弹窗（W3 走查收口补迁）：W1 曾记「新建来源弹窗族留任务 6」，
   任务 6 漏交——对照帧里旧栏的「新建来源」行在新栏缺席，十三种拉取/推送来源
   从此无处可建。弹窗 + 令牌窗都在 ./source-modal。 */
import { SourceModal, SourceTokenModal } from "./source-modal";
/* 来源设置弹窗（遗留 3 收口）：改名/图标（custom）/摄取配置/日程 + danger zone
   删除——已建来源不再需要走 API 手改。 */
import { SourceEditModal } from "./source-edit-modal";
import { UPager } from "@/components/knowledge/kit";

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
        <div class="mb-6">
          <div class="flex items-baseline justify-between gap-4">
            <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
              {{ headerTitle }}
            </h1>
            <div class="flex shrink-0 items-center gap-2">
              <!-- 历史视图下过滤框只藏不撤（invisible 保留占位），标题行高度不塌 -->
              <div class="relative" :class="showHistory ? 'invisible' : ''">
                <SearchIcon
                  class="pointer-events-none absolute left-3 top-1/2 z-10 -translate-y-1/2 text-ink-2"
                  width="13"
                  height="13"
                />
                <el-input
                  v-model="filter"
                  class="library-filter"
                  style="width: 13rem"
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
                class="shrink-0"
                style="width: 11rem"
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
              <el-button v-if="canUpload" size="small" @click="fileInput?.click()">
                <UploadIcon width="12" height="12" class="mr-1" />
                {{ S.library.upload }}
              </el-button>
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
          <div
            class="rounded-card border border-line bg-card"
            :class="dragging ? 'highlight' : ''"
          >
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

            <!-- 文档表 -->
            <table v-else-if="hasDocs" class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">{{ S.library.colFile }}</th>
                  <th v-if="selection === 'all'" class="px-4 py-3 font-medium">
                    {{ S.library.colSource }}
                  </th>
                  <th class="px-4 py-3 font-medium">{{ S.library.colStatus }}</th>
                  <th class="px-4 py-3 font-medium">{{ S.library.colGraph }}</th>
                  <th class="px-4 py-3 font-medium">{{ S.library.colChunks }}</th>
                  <th class="px-4 py-3 font-medium">{{ S.library.colSize }}</th>
                  <!-- 动作两列都不带表头：列名说的是「这一格是什么」，
                      而这两格是「能做什么」，标题是多出来的一行噪声 -->
                  <th class="px-4 py-3" />
                  <th class="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                <DocRow
                  v-for="d in pagedDocs"
                  :key="d.id"
                  :doc="d"
                  :source-name="
                    selection === 'all'
                      ? (docSource(d)?.name ?? null)
                      : undefined
                  "
                  :source-icon-of="selection === 'all' ? rowIcon : undefined"
                  :can-write="canWrite"
                  :busy="
                    remove.isPending.value ||
                    extract.isPending.value ||
                    reprocess.isPending.value
                  "
                  :drop-total="dropTotal(d.id)"
                  @delete="remove.mutate(d.id)"
                  @extract="extract.mutate(d.id)"
                  @reprocess="reprocess.mutate(d.id)"
                  @show-error="
                    (kind: string, text: string) => (errorView = { file: d.filename, kind, text })
                  "
                  @show-drops="
                    dropsByDoc.get(d.id) &&
                      (dropsView = { file: d.filename, rows: dropsByDoc.get(d.id)! })
                  "
                />
              </tbody>
            </table>

            <!-- 空态：筛空与库空是两句话；可上传的空库提示拖放 -->
            <div
              v-else-if="hasQuery"
              class="py-20 text-center text-body text-ink-2"
            >
              {{ S.library.filterNoMatch }}
            </div>
            <div v-else-if="canUpload" class="py-20 text-center text-body text-ink-2">
              {{ S.library.dropHint }}
              <div class="mt-2 text-sub text-ink-2">{{ S.library.formats }}</div>
            </div>
            <div v-else class="py-20 text-center text-body text-ink-2">
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

<style scoped>
/* el-input 内嵌放大镜：让开左缘（与旧仓 w-52 pl-[34px] 同布局） */
.library-filter :deep(.el-input__wrapper) {
  padding-left: 34px;
}
</style>
