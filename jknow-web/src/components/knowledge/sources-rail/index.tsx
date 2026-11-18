/* 来源栏（"来源即文件夹"）：Library 与 DocViewer 共用的左侧导航。
   对照旧仓 pages/SourcesRail.tsx 重写为 Vue（tsx 渲染函数）。
   图标走 unplugin-icons（~icons/lucide/*，离线打包，私有部署无运行时取图）。
   W1 读面：rail 清单与计数；新建/编辑来源属任务 6。 */
import { computed, defineComponent, h, type Component, type PropType } from "vue";
import { useQuery } from "@tanstack/vue-query";
import FolderOpenIcon from "~icons/lucide/folder-open";
import GlobeIcon from "~icons/lucide/globe";
import RssIcon from "~icons/lucide/rss";
import WebhookIcon from "~icons/lucide/webhook";
import BookOpenIcon from "~icons/lucide/book-open";
import NewspaperIcon from "~icons/lucide/newspaper";
import FileTextIcon from "~icons/lucide/file-text";
import DatabaseIcon from "~icons/lucide/database";
import CloudIcon from "~icons/lucide/cloud";
import HardDriveIcon from "~icons/lucide/hard-drive";
import InboxIcon from "~icons/lucide/inbox";
import ArchiveIcon from "~icons/lucide/archive";
import BriefcaseIcon from "~icons/lucide/briefcase";
import Building2Icon from "~icons/lucide/building-2";
import FlaskConicalIcon from "~icons/lucide/flask-conical";
import NotebookIcon from "~icons/lucide/notebook";
import RocketIcon from "~icons/lucide/rocket";
import ServerIcon from "~icons/lucide/server";
import UsersIcon from "~icons/lucide/users";
import BracesIcon from "~icons/lucide/braces";
import BrainIcon from "~icons/lucide/brain";
import CircleDotIcon from "~icons/lucide/circle-dot";
import SquareKanbanIcon from "~icons/lucide/square-kanban";
import PuzzleIcon from "~icons/lucide/puzzle";
import UploadIcon from "~icons/lucide/upload";
import LibraryIcon from "~icons/lucide/library";
import PlusIcon from "~icons/lucide/plus";
import Trash2Icon from "~icons/lucide/trash-2";
import { documents } from "@/api/knowledge/documents";
import { sources as sourcesApi } from "@/api/knowledge/sources";
import type { SourceView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { RailItem } from "@/components/knowledge/kit";

/** 左栏选择：全部 / 手动上传 / 某个来源 id / deleted（墓碑视图） */
export type LibrarySelection = "all" | "uploads" | "deleted" | string;

/** 可选的来源图标（lucide 图标名 → 组件）。 */
export const SOURCE_ICONS: Record<string, Component> = {
  "folder-open": FolderOpenIcon,
  globe: GlobeIcon,
  rss: RssIcon,
  webhook: WebhookIcon,
  "book-open": BookOpenIcon,
  newspaper: NewspaperIcon,
  "file-text": FileTextIcon,
  database: DatabaseIcon,
  cloud: CloudIcon,
  "hard-drive": HardDriveIcon,
  inbox: InboxIcon,
  archive: ArchiveIcon,
  briefcase: BriefcaseIcon,
  "building-2": Building2Icon,
  "flask-conical": FlaskConicalIcon,
  notebook: NotebookIcon,
  rocket: RocketIcon,
  server: ServerIcon,
  users: UsersIcon
};

/** 按 SourceKind 键全：加一种来源没配图标，tsc 就红 */
export const KIND_ICON: Record<string, Component> = {
  folder: FolderOpenIcon,
  url: GlobeIcon,
  rss: RssIcon,
  api: WebhookIcon,
  statements: BracesIcon,
  custom: PuzzleIcon,
  github_issues: CircleDotIcon,
  jira_issues: SquareKanbanIcon,
  s3: HardDriveIcon,
  azure_blob: CloudIcon,
  gcs: CloudIcon,
  webdav: FolderOpenIcon,
  notion: NotebookIcon,
  memory: BrainIcon,
  upload: UploadIcon
};

/** 有拉取/同步语义的来源类型（folder/api 无同步概念） */
export const SYNCING_KINDS = new Set([
  "url",
  "rss",
  "custom",
  "github_issues",
  "jira_issues",
  "s3",
  "azure_blob",
  "gcs",
  "webdav",
  "notion"
]);

/** 同步状态点：实色小点 */
export const SYNC_DOT: Record<SourceView["last_sync_status"], string> = {
  never: "bg-ink-2",
  queued: "bg-warning",
  running: "bg-warning animate-pulse",
  ok: "bg-success",
  failed: "bg-danger"
};

export function sourceIcon(s: SourceView): Component {
  // 内置类型图标固定，只有 custom 尊重用户自选图标
  if (s.kind === "custom" && s.icon && SOURCE_ICONS[s.icon])
    return SOURCE_ICONS[s.icon];
  return KIND_ICON[s.kind] || GlobeIcon;
}

/** 图标走插槽（VNode 复用会撞 Vue 的 keyed patch——icon 必须每帧新造） */
function railIcon(Icon: Component, size = 14) {
  return () => h(Icon as never, { width: size, height: size });
}

export const SourcesRail = defineComponent({
  name: "SourcesRail",
  props: {
    kbId: { type: String, required: true },
    active: {
      type: String as PropType<LibrarySelection | null>,
      default: null
    },
    /** 缺省时隐藏「新建来源」行（如文档查看页） */
    showAdd: { type: Boolean, default: false }
  },
  emits: ["select", "add"],
  setup(props, { emit }) {
    // 左栏只要两个数：整库多少篇、没有来源的多少篇。各取一页零条
    const docs = useQuery({
      queryKey: computed(() => ["docCount", props.kbId]),
      queryFn: () => documents(props.kbId, { limit: 1, offset: 0 })
    });
    const uploads = useQuery({
      queryKey: computed(() => ["docCount", props.kbId, "uploads"]),
      queryFn: () =>
        documents(props.kbId, { source: "none", limit: 1, offset: 0 })
    });
    const sources = useQuery({
      queryKey: computed(() => ["sources", props.kbId]),
      queryFn: () => sourcesApi(props.kbId)
    });
    return () => {
      const sourceList = sources.data.value?.sources ?? [];
      const uploadsCount = uploads.data.value?.total ?? 0;
      const deleted = docs.data.value?.deleted ?? 0;
      return (
        <aside class="flex w-64 shrink-0 flex-col border-r border-line bg-card">
          {/* 全部文档置顶为一级入口。不带计数：它是总数 */}
          <div class="px-2 pt-3">
            <RailItem
              active={props.active === "all"}
              onClick={() => emit("select", "all")}
            >
              {{
                icon: railIcon(LibraryIcon),
                default: () => S.library.allDocs
              }}
            </RailItem>
          </div>
          {props.showAdd && (
            <div class="px-2 pt-1">
              <RailItem onClick={() => emit("add")}>
                {{
                  icon: railIcon(PlusIcon),
                  default: () => S.library.newSourceTitle
                }}
              </RailItem>
            </div>
          )}
          <div class="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto overscroll-contain px-2 pb-3 pt-1">
            <RailItem
              active={props.active === "uploads"}
              onClick={() => emit("select", "uploads")}
              count={uploadsCount}
            >
              {{
                icon: railIcon(UploadIcon),
                default: () => S.library.uploads
              }}
            </RailItem>
            {sourceList.map(s => (
              <RailItem
                key={s.id}
                active={props.active === s.id}
                onClick={() => emit("select", s.id)}
                count={s.doc_count}
                dot={
                  SYNCING_KINDS.has(s.kind) ||
                  s.kind === "api" ||
                  s.kind === "statements"
                    ? SYNC_DOT[s.last_sync_status]
                    : undefined
                }
              >
                {{
                  icon: railIcon(sourceIcon(s)),
                  default: () => s.name
                }}
              </RailItem>
            ))}
          </div>
          {/* 已删除（#268）：墓碑在这里等着被恢复或清除。一个都没有就不占一行 */}
          {(deleted > 0 || props.active === "deleted") && (
            <div class="px-2 pb-3">
              <RailItem
                active={props.active === "deleted"}
                onClick={() => emit("select", "deleted")}
                count={deleted}
              >
                {{
                  icon: railIcon(Trash2Icon),
                  default: () => S.library.deleted
                }}
              </RailItem>
            </div>
          )}
        </aside>
      );
    };
  }
});

/** 调度器产出的值：interval 与 cron 互斥（对照旧仓 scheduleLabel）。 */
export function scheduleLabel(s: SourceView): string {
  const pad2 = (n: number | string) => String(n).padStart(2, "0");
  if (s.sync_cron) {
    const daily = s.sync_cron.match(/^(\d{1,2}) (\d{1,2}) \* \* \*$/);
    if (daily)
      return S.library.schedule.dailyAt(`${pad2(daily[2])}:${pad2(daily[1])}`);
    const weekly = s.sync_cron.match(/^(\d{1,2}) (\d{1,2}) \* \* ([A-Za-z,-]+)$/);
    if (weekly) return `${weekly[3]} ${pad2(weekly[2])}:${pad2(weekly[1])}`;
    return s.sync_cron;
  }
  if (s.sync_interval_minutes)
    return S.library.intervalEvery(s.sync_interval_minutes);
  return S.library.intervalManual;
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
