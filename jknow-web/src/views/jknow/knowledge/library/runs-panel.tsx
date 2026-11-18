/* 同步历史面板（对照旧仓 Library.tsx 的 RunsPanel 读面）。 */
import { computed, defineComponent } from "vue";
import { useQuery } from "@tanstack/vue-query";
import { sourceRuns } from "@/api/knowledge/sources";
import { S } from "@/i18n";

export const RunsPanel = defineComponent({
  name: "RunsPanel",
  props: {
    kbId: { type: String, required: true },
    sourceId: { type: String, required: true }
  },
  setup(props) {
    const runs = useQuery({
      queryKey: computed(() => ["sourceRuns", props.kbId, props.sourceId]),
      queryFn: () => sourceRuns(props.kbId, props.sourceId)
    });
    return () => {
      const list = runs.data.value?.runs ?? [];
      return (
        <div class="rounded-card border border-line bg-card">
          {runs.isLoading.value ? (
            <div class="py-20 text-center text-body text-ink-2">
              {S.nav.loading}
            </div>
          ) : list.length === 0 ? (
            <div class="py-20 text-center text-body text-ink-2">
              {S.library.noRuns}
            </div>
          ) : (
            <div class="px-4 py-2">
              {list.map(r => (
                <div
                  key={r.id}
                  class="flex items-center gap-3 border-b border-line py-2 text-sub last:border-0"
                >
                  <span
                    class="h-1.5 w-1.5 shrink-0 rounded-full"
                    style={{
                      background:
                        r.status === "ok"
                          ? "var(--color-success)"
                          : r.status === "failed"
                            ? "var(--color-danger)"
                            : "var(--color-warning)"
                    }}
                  />
                  <span class="shrink-0 whitespace-nowrap tabular-nums text-ink-2">
                    {r.started_at.slice(0, 16).replace("T", " ")}
                  </span>
                  <span class="shrink-0 whitespace-nowrap text-ink-2">
                    {r.created_docs > 0 && S.library.runNew(r.created_docs)}
                    {r.created_docs > 0 && r.updated_docs > 0 && " · "}
                    {r.updated_docs > 0 && S.library.runUpdated(r.updated_docs)}
                    {r.status === "ok" &&
                      r.created_docs === 0 &&
                      r.updated_docs === 0 && (
                        <span class="text-ink-2">{S.library.runNothing}</span>
                      )}
                  </span>
                  {r.error && (
                    <span class="min-w-0 truncate text-danger" title={r.error}>
                      {r.error}
                    </span>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      );
    };
  }
});
