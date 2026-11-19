/* 文库详情弹窗（自 write.tsx 迁出，reskin-b2 拆解收敛）：抽取丢弃详情与
   失败原文两张窗——这里只管「说清楚」：挡在哪、丢了多少、错在哪。 */
import { defineComponent, type PropType } from "vue";
import { copyAndSay } from "@/domain/clipboard";
import { UDialog } from "@/components/knowledge/dialog";
import type { ExtractionDrop } from "@/api/knowledge/types";
import { S } from "@/i18n";

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
