/* 文库写面行与详情弹窗（对照旧仓 Library.tsx 的 DocRow/DeletedTable/
   ErrorModal/DropsModal，逐行同构换框架）。上传/重试/重建的按钮组在
   index.vue 页头；这里只管一行文档「能拿它怎么办」与失败/丢弃的两张详情窗。 */
import { defineComponent, type Component, type PropType } from "vue";
import { RouterLink } from "vue-router";
import RefreshCwIcon from "~icons/lucide/refresh-cw";
import { copyAndSay } from "@/domain/clipboard";
import { UDialog } from "@/components/knowledge/dialog";
import { localDateTime } from "@/domain/ui";
import type { Doc, ExtractionDrop } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { formatSize } from "@/components/knowledge/sources-rail";
import { LinkButton, StatusCell, UIconButton } from "@/components/knowledge/kit";

/** 一行文档。徽章说的是这一行现在是什么状态，动作说的是能拿它怎么办。
 *  这一格至多一个动作：重跑解析要 status=failed，重抽要 status=ready，
 *  两者互斥——所以不必再排一次谁在前。一个图标，不是三种字（旧仓注释原话）。 */
export const DocRow = defineComponent({
  name: "DocRow",
  props: {
    doc: { type: Object as PropType<Doc>, required: true },
    /** undefined = 不渲染来源列；null = Uploads（source_id 为空） */
    sourceName: { type: String as PropType<string | null | undefined>, default: undefined },
    /** 来源图标工厂（doc → 组件/VNode；Vue 的 keyed patch 下必须每帧新造，同 SourcesRail） */
    sourceIconOf: {
      type: Function as PropType<((doc: Doc) => Component | null) | undefined>,
      default: undefined
    },
    canWrite: { type: Boolean, default: false },
    busy: { type: Boolean, default: false },
    dropTotal: { type: Number, default: 0 }
  },
  emits: ["delete", "extract", "reprocess", "showError", "showDrops"],
  setup(props, { emit }) {
    return () => {
      const d = props.doc;
      const statusText =
        S.library.status[d.status as keyof typeof S.library.status] ?? d.status;
      const graphText =
        S.library.graphStatus[d.graph_status as keyof typeof S.library.graphStatus] ??
        d.graph_status;
      return (
        <tr class="border-b border-line">
          <td class="max-w-xs truncate px-4 py-3" title={d.filename}>
            <RouterLink
              to={`/kb/${d.kb_id}/doc/${d.id}`}
              class="link-inline text-ink"
            >
              {d.filename}
            </RouterLink>
          </td>
          {props.sourceName !== undefined && (
            <td class="px-4 py-3">
              <span class="flex items-center gap-2 text-sub text-ink-2">
                {props.sourceIconOf?.(d)}
                <span class="max-w-28 truncate">
                  {props.sourceName ?? S.library.uploads}
                </span>
              </span>
            </td>
          )}
          <td class="px-4 py-3">
            <StatusCell
              danger={d.status === "failed"}
              onClick={
                d.status === "failed" && d.error
                  ? () => emit("showError", S.library.errorParse, d.error!)
                  : undefined
              }
            >
              {statusText}
            </StatusCell>
            {d.missing_since && (
              <span
                class="ml-3 text-sub text-ink-2"
                title={d.missing_since.slice(0, 16).replace("T", " ")}
              >
                {S.library.notInSource}
              </span>
            )}
          </td>
          <td class="px-4 py-3">
            <StatusCell
              danger={d.graph_status === "failed"}
              onClick={
                d.graph_status === "failed" && d.graph_error
                  ? () => emit("showError", S.library.errorGraph, d.graph_error!)
                  : undefined
              }
            >
              {graphText}
            </StatusCell>
            {/* 抽出来却没落地的事实。不给警示色：漏掉的事实不是故障，是窄本体
                本来就会做的事。它只是个值得点开看看的数 */}
            {props.dropTotal > 0 && (
              <LinkButton
                underline
                class="ml-3 text-ink-2"
                onClick={() => emit("showDrops")}
              >
                {S.library.dropsChip(props.dropTotal)}
              </LinkButton>
            )}
          </td>
          <td class="px-4 py-3 text-ink-2">{d.chunk_count || "—"}</td>
          <td class="px-4 py-3 text-ink-2">{formatSize(d.size_bytes)}</td>
          <td class="px-4 py-3">
            {d.status === "failed" ? (
              <UIconButton
                label={S.library.reprocess}
                disabled={props.busy}
                onClick={() => emit("reprocess")}
              >
                <RefreshCwIcon width="12" height="12" />
              </UIconButton>
            ) : d.status === "ready" &&
              ["none", "failed", "done"].includes(d.graph_status) ? (
              /* done 也可重抽：本体（描述/新类）调整后强制全量重抽正是常规操作 */
              <UIconButton
                label={d.graph_status === "done" ? S.library.reExtract : S.library.extract}
                disabled={props.busy}
                onClick={() => emit("extract")}
              >
                <RefreshCwIcon width="12" height="12" />
              </UIconButton>
            ) : null}
          </td>
          <td class="px-4 py-3 text-right">
            {props.canWrite && (
              <LinkButton tone="danger" disabled={props.busy} onClick={() => emit("delete")}>
                {S.library.delete}
              </LinkButton>
            )}
          </td>
        </tr>
      );
    };
  }
});

/** 「已删除」视图（#268）：墓碑在这里等着被恢复或清除。清除只有库管理员能按 */
export const DeletedTable = defineComponent({
  name: "DeletedTable",
  props: {
    docs: { type: Array as PropType<Doc[]>, required: true },
    sourceName: {
      type: Function as PropType<(doc: Doc) => string>,
      required: true
    },
    canPurge: { type: Boolean, default: false },
    busy: { type: Boolean, default: false }
  },
  emits: ["restore", "purge"],
  setup(props, { emit }) {
    return () => {
      if (!props.docs.length)
        return (
          <div class="py-20 text-center text-body text-ink-2">
            {S.library.deletedEmpty}
          </div>
        );
      return (
        <table class="w-full text-body">
          <thead>
            <tr class="border-b border-line text-left text-sub text-ink-2">
              <th class="px-4 py-3 font-medium">{S.library.colFile}</th>
              <th class="px-4 py-3 font-medium">{S.library.colSource}</th>
              <th class="px-4 py-3 font-medium">{S.library.colDeleted}</th>
              <th class="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {props.docs.map(d => (
              <tr key={d.id} class="border-b border-line last:border-0">
                <td class="px-4 py-3 text-ink-2">{d.filename}</td>
                <td class="px-4 py-3 text-ink-2">{props.sourceName(d)}</td>
                <td class="px-4 py-3 tabular-nums text-ink-2">
                  {d.deleted_at ? localDateTime(d.deleted_at) : ""}
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-right">
                  <LinkButton disabled={props.busy} onClick={() => emit("restore", d.id)}>
                    {S.library.restore}
                  </LinkButton>
                  {props.canPurge && (
                    <LinkButton
                      tone="danger"
                      class="ml-4"
                      disabled={props.busy}
                      onClick={() => emit("purge", d)}
                    >
                      {S.library.purge}
                    </LinkButton>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    };
  }
});

/** 抽取丢弃详情：抽出来了、被挡掉了，这里说清楚挡在哪、丢了多少。 */
export const DropsModal = defineComponent({
  name: "DropsModal",
  props: {
    file: { type: String, required: true },
    rows: { type: Array as PropType<ExtractionDrop[]>, required: true }
  },
  emits: ["close"],
  setup(props, { emit }) {
    return () => (
      <UDialog
        open
        {...{ "onUpdate:open": (o: boolean) => !o && emit("close") }}
        closeLabel={S.library.close}
        v-slots={{
          title: () => <span>{S.library.dropsTitle}</span>,
          description: () => (
            <>
              <span class="block truncate" title={props.file}>
                {props.file}
              </span>
              <span class="mt-1 block">{S.library.dropsNote}</span>
            </>
          ),
          default: () => (
            <div class="max-h-80 overflow-y-auto overscroll-contain">
              {props.rows.map(r => (
                <div key={`${r.reason}:${r.detail}`} class="border-b border-line py-3 last:border-0">
                  <div class="flex items-baseline gap-2">
                    <span class="text-body text-ink">
                      {S.library.dropReason[r.reason as keyof typeof S.library.dropReason] ??
                        r.reason}
                    </span>
                    <span class="ml-auto tabular-nums text-sub text-ink-2">×{r.count}</span>
                  </div>
                  <div class="mt-1 break-all font-mono text-aux text-ink-2">{r.detail}</div>
                  {r.example && (
                    <div class="mt-1 break-all text-aux text-ink-2">
                      {S.library.dropsExample} {r.example}
                    </div>
                  )}
                </div>
              ))}
            </div>
          ),
          footer: () => (
            <el-button
              size="small"
              onClick={() => emit("close")}
            >
              {S.library.close}
            </el-button>
          )
        }}
      />
    );
  }
});

/** 失败详情：完整原文，可复制。tooltip 装不下也拷不走，所以要弹窗。 */
export const ErrorModal = defineComponent({
  name: "ErrorModal",
  props: {
    file: { type: String, required: true },
    kind: { type: String, required: true },
    text: { type: String, required: true }
  },
  emits: ["close"],
  setup(props, { emit }) {
    return () => (
      <UDialog
        open
        {...{ "onUpdate:open": (o: boolean) => !o && emit("close") }}
        closeLabel={S.library.close}
        v-slots={{
          title: () => <span>{S.library.errorTitle}</span>,
          description: () => (
            <span class="block truncate" title={props.file}>
              {props.file} · {props.kind}
            </span>
          ),
          default: () => (
            <pre class="max-h-72 overflow-auto overscroll-contain whitespace-pre-wrap break-words rounded-card border border-line bg-card p-3 text-sub leading-relaxed text-ink-2">
              {props.text}
            </pre>
          ),
          footer: () => (
            <>
              <el-button
                size="small"
                onClick={() => copyAndSay(props.text, S.library.errorCopied)}
              >
                {S.library.copyError}
              </el-button>
              <el-button size="small" type="primary" onClick={() => emit("close")}>
                {S.library.close}
              </el-button>
            </>
          )
        }}
      />
    );
  }
});
