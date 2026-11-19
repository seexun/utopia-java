/* 文库「已删除」视图（自 write.tsx 迁出，reskin-b2 拆解收敛：write.tsx 的
   死导出行组件随拆解退役，在用件各自独立成文件）。 */
import { defineComponent, type PropType } from "vue";
import { localDateTime } from "@/domain/ui";
import type { Doc } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { LinkButton } from "@/components/knowledge/kit";

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
