/* 本体导入（对照旧仓 pages/Ontology.tsx 的 ImportPanel + ImportHistory）：
   上传只算出计划、一个字节都不写库（preview）；人看过三类警告与三段计划再确认
   （apply）。谁在什么时候拿哪个文件动过本体——导入历史一张表，细账在详情弹窗。 */
import { computed, defineComponent, ref } from "vue";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  previewOntologyImport,
  applyOntologyImport,
  ontologyImports
} from "@/api/knowledge/ontology";
import type { ImportPlan, OntologyImportView, PlannedItem } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { localDateTime } from "@/domain/ui";
import { UDialog } from "@/components/knowledge/dialog";
import { LinkButton } from "@/components/knowledge/kit";

/** 键被占的计划项数（三段合计）。占了键的更新不了，只能先改名或先删 */
function takenCount(p: ImportPlan): number {
  return [...p.classes, ...p.relations, ...p.attributes].filter(
    i => i.disposition === "key_taken"
  ).length;
}

/** `{no_domain: 3, unknown_domain: 1}` → 4。缺就是 0 */
function sumCounts(m: Record<string, number> | undefined): number {
  return Object.values(m ?? {}).reduce((a, b) => a + b, 0);
}

/** 一行警告：计数之前先说事。展开列名单 */
const Warning = defineComponent({
  name: "ImportWarning",
  props: {
    show: { type: Boolean, default: false },
    tone: { type: String as PropType<"warn" | "danger">, default: "warn" },
    title: { type: String, required: true },
    body: { type: String, required: true },
    items: { type: Array as PropType<string[]>, required: true }
  },
  setup(props) {
    const open = ref(false);
    return () => {
      if (!props.show) return null;
      return (
        <div
          class={[
            "mt-2 rounded-card border px-4 py-3",
            props.tone === "danger"
              ? "border-danger bg-card"
              : "border-warning bg-card"
          ]}
        >
          <div class="flex items-center gap-2">
            <span
              class={props.tone === "danger" ? "text-danger" : "text-warning"}
            >
              {props.title}
            </span>
            {props.items.length > 0 && (
              <LinkButton class="text-sub" onClick={() => (open.value = !open.value)}>
                {open.value ? S.ui.close : `(${props.items.length})`}
              </LinkButton>
            )}
          </div>
          <p class="mt-1 text-sub text-ink-2">{props.body}</p>
          {open.value && props.items.length > 0 && (
            <ul class="mt-2 space-y-1">
              {props.items.map(i => (
                <li key={i} class="break-all font-mono text-aux text-ink-2">
                  {i}
                </li>
              ))}
            </ul>
          )}
        </div>
      );
    };
  }
});

/** 计划一段：类 / 关系 / 属性。每行键 + 标签 + 处置芯片 */
const PlanRow = defineComponent({
  name: "ImportPlanRow",
  props: {
    label: { type: String, required: true },
    items: { type: Array as PropType<PlannedItem[]>, required: true },
    note: { type: String, default: undefined }
  },
  setup(props) {
    const open = ref(true);
    const chip = (d: PlannedItem["disposition"]) => {
      const tone =
        d === "create"
          ? "badge-soft-success"
          : d === "update" || d === "aligned"
            ? "badge-soft-info"
            : d === "superseded"
              ? "badge-soft"
              : "badge-soft-danger";
      const word =
        d === "create"
          ? S.ontology.importCreatedN(1).replace(/^1 /, "")
          : d === "update"
            ? S.ontology.importUpdatedN(1).replace(/^1 /, "")
            : d === "aligned"
              ? "aligned"
              : d === "superseded"
                ? "superseded"
                : "key taken";
      return { cls: ["badge-soft", tone] as string[], word };
    };
    return () => (
      <div class="rounded-card border border-line bg-card px-4 py-3">
        <button
          type="button"
          class="flex w-full items-center gap-2 text-left"
          onClick={() => (open.value = !open.value)}
        >
          <span class="text-body font-medium text-ink">{props.label}</span>
          <span class="tabular-nums text-sub text-ink-2">{props.items.length}</span>
          <span class="ml-auto text-ink-2">{open.value ? "▾" : "▸"}</span>
        </button>
        {props.note && <p class="mt-1 text-aux text-ink-2">{props.note}</p>}
        {open.value && props.items.length > 0 && (
          <ul class="mt-2 space-y-1">
            {props.items.map(i => {
              const c = chip(i.disposition);
              return (
                <li key={i.key} class="flex flex-wrap items-baseline gap-2 text-sub">
                  <span class="break-all font-mono text-ink-2">{i.key}</span>
                  <span class="text-ink-2">{i.label}</span>
                  <span class={c.cls}>{c.word}</span>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    );
  }
});

export const ImportPanel = defineComponent({
  name: "ImportPanel",
  props: {
    kbId: { type: String, required: true }
  },
  emits: ["changed"],
  setup(props, { emit }) {
    const queryClient = useQueryClient();
    const file = ref<File | null>(null);
    const previewing = ref(false);
    const applying = ref(false);
    const plan = ref<ImportPlan | null>(null);
    const error = ref<string | null>(null);
    const pick = ref<HTMLInputElement | null>(null);

    async function preview(f: File) {
      previewing.value = true;
      error.value = null;
      try {
        const res = await previewOntologyImport(props.kbId, f);
        plan.value = res.plan;
      } catch (e) {
        file.value = null;
        error.value = e instanceof Error ? e.message : String(e);
      }
      previewing.value = false;
    }

    function choose(f: File | undefined) {
      if (!f) return;
      file.value = f;
      plan.value = null;
      preview(f);
    }

    async function apply() {
      if (!file.value) return;
      applying.value = true;
      try {
        const res = await applyOntologyImport(props.kbId, file.value);
        const p = res.plan;
        toast.success(
          S.ontology.importDone(
            p.classes.filter(c => c.disposition === "create").length,
            p.classes.filter(c => c.disposition === "update").length
          )
        );
        file.value = null;
        plan.value = null;
        // 导入历史键 + 本体读面键：导入改的是本体，SSE graph 事件也覆盖后者
        queryClient.invalidateQueries({
          queryKey: ["ontology-imports", props.kbId]
        });
        queryClient.invalidateQueries({ queryKey: ["ontology", props.kbId] });
        queryClient.invalidateQueries({ queryKey: ["graph"] });
        emit("changed");
      } catch (e) {
        error.value = e instanceof Error ? e.message : String(e);
      }
      applying.value = false;
    }

    const empty = computed(
      () =>
        plan.value &&
        plan.value.classes.length === 0 &&
        plan.value.relations.length === 0 &&
        plan.value.attributes.length === 0
    );

    return () => (
      <div>
        <div class="mb-6">
          <h1 class="break-words text-page font-extrabold text-ink">
            {S.ontology.importTitle}
          </h1>
          <p class="mt-1 text-sub text-ink-2">{S.ontology.importHint}</p>
        </div>

        <input
          ref={pick}
          type="file"
          accept=".owl,.rdf,.ttl,.xml,.n3"
          class="hidden"
          onChange={(e: Event) => {
            const el = e.target as HTMLInputElement;
            choose(el.files?.[0]);
            el.value = "";
          }}
        />
        <div class="flex items-center gap-2">
          <el-button
            size="small"
            disabled={previewing.value || applying.value}
            onClick={() => pick.value?.click()}
          >
            {file.value ? S.ontology.importChange : S.ontology.importPick}
          </el-button>
          {file.value && (
            <span class="truncate text-sub text-ink-2">
              <span class="font-mono">{file.value.name}</span>
              <span class="text-ink-2">
                {" "}
                · {S.ontology.importSize(file.value.size)}
              </span>
            </span>
          )}
          {previewing.value && (
            <span class="text-sub text-ink-2">{S.ontology.importReading}</span>
          )}
        </div>
        {error.value && <p class="mt-2 text-body text-danger">{error.value}</p>}

        {plan.value && (
          <div class="mt-4">
            <p class="tabular-nums text-aux text-ink-2">
              {S.ontology.importParsed(plan.value.format, plan.value.triples)}
            </p>

            {empty.value ? (
              <p class="mt-2 text-body text-ink-2">{S.ontology.importNothing}</p>
            ) : (
              <>
                {/* 三条警告在计数之前：人只会读第一屏 */}
                <Warning
                  show={plan.value.functional_relations > 0}
                  tone="warn"
                  title={S.ontology.warnFunctional(plan.value.functional_relations)}
                  body={S.ontology.warnFunctionalBody}
                  items={plan.value.relations
                    .filter(r => r.functional)
                    .map(r => r.key)}
                />
                <Warning
                  show={plan.value.classes_without_description > 0}
                  tone="warn"
                  title={S.ontology.warnNoDescription(
                    plan.value.classes_without_description
                  )}
                  body={S.ontology.warnNoDescriptionBody}
                  items={plan.value.classes
                    .filter(c => !c.has_description)
                    .map(c => c.key)}
                />
                <Warning
                  show={takenCount(plan.value) > 0}
                  tone="danger"
                  title={S.ontology.warnKeyTaken(takenCount(plan.value))}
                  body={S.ontology.warnKeyTakenBody}
                  items={[
                    ...plan.value.classes,
                    ...plan.value.relations,
                    ...plan.value.attributes
                  ]
                    .filter(i => i.disposition === "key_taken")
                    .map(
                      i =>
                        `${i.key} — ${S.ontology.importTakenBy(i.conflict_with ?? null)}`
                    )}
                />

                <div class="mt-3 grid gap-2">
                  <PlanRow
                    label={S.ontology.importClasses}
                    items={plan.value.classes}
                  />
                  <PlanRow
                    label={S.ontology.importRelations}
                    items={plan.value.relations}
                  />
                  <PlanRow
                    label={S.ontology.importAttributes}
                    items={plan.value.attributes}
                    note={
                      plan.value.attributes.length > 0
                        ? S.ontology.importAttributesLater
                        : undefined
                    }
                  />
                </div>

                {plan.value.unprojected.length > 0 && (
                  <div class="mt-3 rounded-card border border-line bg-card px-4 py-3">
                    <div class="text-sub font-medium text-ink-2">
                      {S.ontology.importUnprojected} ({plan.value.unprojected.length})
                    </div>
                    <p class="mt-1 text-aux text-ink-2">
                      {S.ontology.importUnprojectedBody}
                    </p>
                    <ul class="mt-2 space-y-1">
                      {plan.value.unprojected.map(([iri, n]) => (
                        <li key={iri} class="flex gap-2 text-aux">
                          <span class="truncate font-mono text-ink-2" title={iri}>
                            {iri}
                          </span>
                          <span class="shrink-0 tabular-nums text-ink-2">×{n}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <div class="mt-4 flex items-center gap-2">
                  {/* 确认按钮：计划看过才点得动——预览与落库同一个文件重算一遍 */}
                  <el-button
                    type="primary"
                    size="small"
                    disabled={previewing.value || applying.value}
                    loading={applying.value}
                    onClick={apply}
                  >
                    {applying.value ? S.ontology.importApplying : S.ontology.importApply}
                  </el-button>
                  <el-button
                    size="small"
                    disabled={previewing.value || applying.value}
                    onClick={() => {
                      file.value = null;
                      plan.value = null;
                    }}
                  >
                    {S.ontology.importCancel}
                  </el-button>
                </div>
              </>
            )}
          </div>
        )}

        <ImportHistory kbId={props.kbId} />
      </div>
    );
  }
});

/** 导入历史：每次导入记一整本账（建了几个、更新了几个、几个键被占、多少三元组）。 */
const ImportHistory = defineComponent({
  name: "ImportHistory",
  props: { kbId: { type: String, required: true } },
  setup(props) {
    const history = useQuery({
      queryKey: computed(() => ["ontology-imports", props.kbId]),
      queryFn: () => ontologyImports(props.kbId)
    });
    const detail = ref<OntologyImportView | null>(null);
    const rows = computed(() => history.data.value?.imports ?? []);
    const detailCounts = computed(() => {
      const s = detail.value?.summary ?? {};
      return [
        {
          label: S.ontology.importClasses,
          created: s.classes_created,
          updated: s.classes_updated,
          taken: s.classes_key_taken
        },
        {
          label: S.ontology.importRelations,
          created: s.relations_created,
          updated: s.relations_updated
        },
        {
          label: S.ontology.importAttributes,
          created: s.attributes_created,
          skipped: sumCounts(s.attributes_skipped)
        }
      ];
    });
    return () => (
      <div class="mt-6">
        <h4 class="mb-2 text-sub font-medium text-ink-2">
          {S.ontology.importHistory}
        </h4>
        {/* 还在取的时候不能说「还没有导入过」——那句话是假的 */}
        {history.isLoading.value ? (
          <p class="text-sub text-ink-2">{S.nav.loading}</p>
        ) : rows.value.length === 0 ? (
          <p class="text-sub text-ink-2">{S.ontology.importNoHistory}</p>
        ) : (
          <div class="overflow-hidden rounded-card border border-line bg-card">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">{S.ontology.importColFile}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importColFormat}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importColSize}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importClasses}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importRelations}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importAttributes}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importColTriples}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.importColWhen}</th>
                  <th class="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                {rows.value.map(im => {
                  const s = im.summary ?? {};
                  const cell = (created?: number, updated?: number, extra?: number) => {
                    const parts: string[] = [];
                    if (created) parts.push(`+${created}`);
                    if (updated) parts.push(`~${updated}`);
                    if (extra) parts.push(`×${extra}`);
                    return parts.join(" / ") || "—";
                  };
                  return (
                    <tr key={im.id} class="border-b border-line last:border-0">
                      <td class="px-4 py-3">
                        <div class="max-w-64 truncate text-sub text-ink" title={im.filename}>
                          {im.filename}
                        </div>
                      </td>
                      <td class="px-4 py-3 text-sub text-ink-2">{im.format}</td>
                      <td class="whitespace-nowrap px-4 py-3 tabular-nums text-sub text-ink-2">
                        {S.ontology.importSize(im.byte_size)}
                      </td>
                      <td class="px-4 py-3 text-sub text-ink-2">
                        {cell(s.classes_created, s.classes_updated, s.classes_key_taken)}
                      </td>
                      <td class="px-4 py-3 text-sub text-ink-2">
                        {cell(s.relations_created, s.relations_updated)}
                      </td>
                      <td class="px-4 py-3 text-sub text-ink-2">
                        {cell(s.attributes_created, undefined, sumCounts(s.attributes_skipped))}
                      </td>
                      <td class="px-4 py-3 tabular-nums text-sub text-ink-2">
                        {s.triples ?? "—"}
                      </td>
                      <td class="whitespace-nowrap px-4 py-3 text-sub text-ink-2">
                        {S.ontology.importBy(
                          im.imported_by_name ?? "—",
                          localDateTime(im.imported_at).slice(0, 16)
                        )}
                      </td>
                      <td class="px-4 py-3 text-right">
                        <LinkButton onClick={() => (detail.value = im)}>
                          {S.ontology.importDetail}
                        </LinkButton>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* 细账：只有出了问题才有人读，所以收在这里，而不是摊在表上 */}
        <UDialog
          open={!!detail.value}
          {...{ "onUpdate:open": (o: boolean) => !o && (detail.value = null) }}
          width="lg"
          closeLabel={S.ui.close}
          v-slots={{
            title: () => <span>{detail.value?.filename ?? ""}</span>,
            description: () =>
              detail.value
                ? S.ontology.importBy(
                    detail.value.imported_by_name ?? "—",
                    localDateTime(detail.value.imported_at)
                  )
                : undefined,
            default: () =>
              detail.value && (
                <div class="space-y-3">
                  <p class="text-sub text-ink-2">{S.ontology.importUnprojectedBody}</p>
                  {detailCounts.value.map(c => (
                    <div key={c.label} class="flex items-baseline gap-3 text-sub">
                      <span class="w-24 shrink-0 text-ink-2">{c.label}</span>
                      <span class="tabular-nums text-ink">
                        {c.created ?? 0} + / {c.updated ?? 0} ~
                        {c.taken ? ` / ${c.taken} taken` : ""}
                        {c.skipped ? ` / ${c.skipped} skipped` : ""}
                      </span>
                    </div>
                  ))}
                  {(detail.value.summary.unprojected ?? []).length > 0 && (
                    <ul class="space-y-1">
                      {(detail.value.summary.unprojected ?? []).map(([iri, n]) => (
                        <li key={iri} class="flex gap-2 text-aux">
                          <span class="truncate font-mono text-ink-2" title={iri}>
                            {iri}
                          </span>
                          <span class="shrink-0 tabular-nums text-ink-2">×{n}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ),
            footer: () => (
              <el-button
                size="small"
                type="primary"
                onClick={() => (detail.value = null)}
              >
                {S.ui.close}
              </el-button>
            )
          }}
        />
      </div>
    );
  }
});
