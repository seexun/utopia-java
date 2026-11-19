/* 本体读成三张表（对照旧仓 pages/ontologyTables.tsx）：类（层级序 + 缩进）/
   关系（签名 + 公理芯片）/ 属性（挂在哪个类上）。排序把层级拍平；不列 key。
   新建行/属性编辑入口属任务 6。
   reskin-b3：root 自带内距归零（表格视图主区卡 .card 提供内距），
   表体 .table-box 全局配方原样。 */
import { computed, defineComponent, ref, type PropType, type VNode } from "vue";
import type { EntityTypeView, RelationTypeView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { UChip, UPager, chipLike, pageSlice, LinkButton } from "@/components/knowledge/kit";

export type TableTab = "classes" | "properties" | "attributes";

/** 这一行挂在谁下面。没有主父类就退回第一个父类 */
export function treeParent(t: EntityTypeView): string | null {
  return t.primary_parent ?? t.parents[0] ?? null;
}

/** 名字列的缩进：一层 12px。与左栏那棵树同一个做法 */
const INDENT = 12;
/** 一页画多少行（旧仓 TABLE_PAGE） */
const TABLE_PAGE = 50;

type Sort = { col: string; dir: "asc" | "desc" } | null;

function sorted<T>(
  rows: T[],
  sort: Sort,
  key: (r: T) => string | number
): T[] {
  if (!sort) return rows;
  const out = [...rows].sort((a, b) => {
    const x = key(a);
    const y = key(b);
    return typeof x === "number" && typeof y === "number"
      ? x - y
      : String(x).localeCompare(String(y));
  });
  return sort.dir === "asc" ? out : out.reverse();
}

/** 按 primary_parent 排成层级序，带每行的深度。过滤或排序时拍平 */
function classRows(
  types: EntityTypeView[],
  flat: boolean
): { t: EntityTypeView; depth: number }[] {
  if (flat) return types.map(t => ({ t, depth: 0 }));
  const children = new Map<string | null, EntityTypeView[]>();
  for (const t of types) {
    const p = treeParent(t);
    if (!children.has(p)) children.set(p, []);
    children.get(p)!.push(t);
  }
  const out: { t: EntityTypeView; depth: number }[] = [];
  const seen = new Set<string>();
  const walk = (parent: string | null, depth: number) => {
    for (const t of children.get(parent) ?? []) {
      if (seen.has(t.id)) continue; // 父类互指成环也走不死
      seen.add(t.id);
      out.push({ t, depth });
      walk(t.id, depth + 1);
    }
  };
  walk(null, 0);
  for (const t of types) if (!seen.has(t.id)) out.push({ t, depth: 0 });
  return out;
}

/* ---------- 表头（可排序的那几列） ---------- */

/* 可排序的表头（当前未启用排序点击——读面按名字序；层级序在类表由 classRows 承担。
   排序交互随任务 6+ 触及本表时补，结构已留好：Sort 状态 + sorted()。 */
void (0 as unknown as Sort);

/** 公理做成几个字母的芯片而不是六个勾 */
function Axioms(props: { r: RelationTypeView }) {
  const r = props.r;
  const on: [boolean, string, string][] = [
    [r.functional, "F", S.ontology.functional],
    [r.inverse_functional, "IF", S.ontology.inverseFunctional],
    [r.is_transitive, "T", S.ontology.axiomTransitive],
    [r.is_symmetric, "S", S.ontology.axiomSymmetric],
    [r.is_asymmetric, "A", S.ontology.axiomAsymmetric],
    [r.is_irreflexive, "IR", S.ontology.axiomIrreflexive]
  ];
  const shown = on.filter(([v]) => v);
  if (shown.length === 0) return <span class="text-ink-2">—</span>;
  return (
    <span class="flex flex-wrap gap-1">
      {shown.map(([, mark, title]) => (
        <span key={mark} class={chipLike("neutral", "px-2 text-aux")} title={title}>
          {mark}
        </span>
      ))}
    </span>
  );
}

export const OntologyTables = defineComponent({
  name: "OntologyTables",
  props: {
    loading: { type: Boolean, default: false },
    tab: { type: String as PropType<TableTab>, required: true },
    selected: {
      type: Object as PropType<{ kind: "class" | "relation"; id: string } | null>,
      default: null
    },
    entityTypes: { type: Array as PropType<EntityTypeView[]>, required: true },
    relationTypes: { type: Array as PropType<RelationTypeView[]>, required: true },
    filter: { type: String, default: "" }
  },
  emits: {
    openClass: (_t: EntityTypeView) => true,
    openProperty: (_r: RelationTypeView) => true,
    seeInstances: (_t: EntityTypeView) => true,
    switchTab: (_tab: TableTab) => true
  },
  setup(props, { emit }) {
    const sort = ref<Sort>(null);
    const page = ref(0);
    const nameOf = computed(
      () => new Map(props.entityTypes.map(t => [t.id, t.label]))
    );
    const attrCount = computed(() => {
      const m = new Map<string, number>();
      for (const a of (props.relationTypes as RelationTypeView[]).filter(
        r => r.kind === "attribute"
      ))
        for (const d of a.domains) m.set(d, (m.get(d) ?? 0) + 1);
      return m;
    });
    const attributes = computed(() =>
      (props.relationTypes as RelationTypeView[]).filter(
        r => r.kind === "attribute"
      )
    );
    const relations = computed(() =>
      (props.relationTypes as RelationTypeView[]).filter(
        r => r.kind === "relation"
      )
    );
    const relName = computed(
      () =>
        new Map(
          (props.relationTypes as RelationTypeView[]).map(r => [r.id, r.label])
        )
    );

    return () => {
      const q = props.filter.trim().toLowerCase();
      void sort;
      // 三张表同一具渲染函数里的分支产物（vue jsx 渲染节点；VNode = Vue JSX
      // 编译产物的静态类型——遗留 5-① 类型收敛）
      let body: VNode;
      if (props.tab === "classes") {
        const shown = props.entityTypes.filter(
          t =>
            !q ||
            t.label.toLowerCase().includes(q) ||
            t.key.toLowerCase().includes(q)
        );
        const rows = classRows(
          sorted(
            shown,
            sort.value,
            t =>
              sort.value?.col === "usage"
                ? t.usage
                : sort.value?.col === "attrs"
                  ? (attrCount.value.get(t.id) ?? 0)
                  : t.label
          ),
          !!sort.value || !!q
        );
        const paged = pageSlice(rows, page.value, TABLE_PAGE);
        body = (
          <div class="table-box">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">{S.ontology.colName}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.parent}</th>
                  <th class="hidden px-4 py-3 font-medium lg:table-cell">
                    {S.ontology.disjoint}
                  </th>
                  <th class="px-4 py-3 text-right font-medium">
                    {S.ontology.colInstances}
                  </th>
                  <th class="px-4 py-3 text-right font-medium">
                    {S.ontology.schemaTabAttributes}
                  </th>
                </tr>
              </thead>
              <tbody>
                {paged.rows.map(({ t, depth }) => (
                  <tr
                    key={t.id}
                    class={[
                      "cursor-pointer border-b border-line last:border-0 hover:bg-fill",
                      t.id === props.selected?.id && "bg-primary-soft"
                    ]}
                    onClick={() => emit("openClass", t)}
                  >
                    <td class="px-4 py-3">
                      <span
                        class="flex items-center gap-2"
                        style={{ paddingLeft: depth * INDENT }}
                      >
                        <span
                          class={[
                            "h-2 w-2 shrink-0",
                            t.shape === "square" ? "scale-90" : "rounded-full"
                          ]}
                          style={{ background: t.color }}
                        />
                        <span class="truncate">{t.label}</span>
                        {t.parents.length > 1 && (
                          <span
                            class={chipLike("neutral", "shrink-0 px-2 text-aux")}
                            title={S.ontology.multiParentHint}
                          >
                            +{t.parents.length - 1}
                          </span>
                        )}
                        {t.builtin && <UChip>{S.ontology.builtin}</UChip>}
                      </span>
                    </td>
                    <td class="text-sub text-ink-2">
                      {t.parents.length
                        ? t.parents.map(p => nameOf.value.get(p) ?? p).join(", ")
                        : "—"}
                    </td>
                    <td class="hidden text-sub text-ink-2 lg:table-cell">
                      {t.disjoint.length
                        ? t.disjoint.map(d => nameOf.value.get(d) ?? d).join(", ")
                        : "—"}
                    </td>
                    <td class="text-right">
                      {t.usage > 0 ? (
                        <LinkButton
                          class="tabular-nums"
                          onClick={() => emit("seeInstances", t)}
                        >
                          {t.usage}
                        </LinkButton>
                      ) : (
                        <span class="tabular-nums text-ink-2">0</span>
                      )}
                    </td>
                    <td class="tabular-nums text-right text-ink-2">
                      {attrCount.value.get(t.id) ?? 0}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <UPager
              className="table-pager"
              total={rows.length}
              pageSize={TABLE_PAGE}
              page={paged.safe}
              onPage={(p: number) => (page.value = p)}
            />
          </div>
        );
      } else if (props.tab === "properties") {
        const shown = relations.value.filter(
          r =>
            !q ||
            r.label.toLowerCase().includes(q) ||
            r.key.toLowerCase().includes(q)
        );
        const side = (ids: string[]) =>
          ids.length
            ? ids.map(i => nameOf.value.get(i) ?? i).join(", ")
            : S.ontology.anyType;
        const rows = sorted(
          shown as RelationTypeView[],
          sort.value,
          (r: RelationTypeView) =>
            (sort.value?.col === "usage" ? r.usage : r.label) as
              | string
              | number
        );
        const paged = pageSlice(rows, page.value, TABLE_PAGE);
        body = (
          <div class="table-box">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">{S.ontology.colName}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.colSignature}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.temporal}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.axioms}</th>
                  <th class="hidden px-4 py-3 font-medium lg:table-cell">
                    {S.ontology.inverseOf}
                  </th>
                  <th class="px-4 py-3 text-right font-medium">
                    {S.ontology.colFacts}
                  </th>
                </tr>
              </thead>
              <tbody>
                {paged.rows.map(r => (
                  <tr
                    key={r.id}
                    class={[
                      "cursor-pointer border-b border-line last:border-0 hover:bg-fill",
                      r.id === props.selected?.id && "bg-primary-soft"
                    ]}
                    onClick={() => emit("openProperty", r)}
                  >
                    <td class="px-4 py-3">
                      <span class="flex items-center gap-2">
                        <span class="truncate">{r.label}</span>
                        {r.builtin && <UChip>{S.ontology.builtin}</UChip>}
                      </span>
                    </td>
                    <td class="text-sub text-ink-2">
                      {side(r.domains)} → {side(r.ranges)}
                    </td>
                    <td class="text-sub text-ink-2">
                      {r.temporal === "event"
                        ? S.ontology.temporalEvent
                        : r.temporal === "eternal"
                          ? S.ontology.temporalEternal
                          : S.ontology.temporalState}
                    </td>
                    <td>
                      <Axioms r={r} />
                    </td>
                    <td class="hidden text-sub text-ink-2 lg:table-cell">
                      {r.inverse_of
                        ? (relName.value.get(r.inverse_of) ?? "—")
                        : "—"}
                    </td>
                    <td class="tabular-nums text-right text-ink-2">{r.usage}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <UPager
              className="table-pager"
              total={rows.length}
              pageSize={TABLE_PAGE}
              page={paged.safe}
              onPage={(p: number) => (page.value = p)}
            />
          </div>
        );
      } else {
        const shown = attributes.value.filter(
          a =>
            !q ||
            a.label.toLowerCase().includes(q) ||
            a.key.toLowerCase().includes(q)
        );
        const rows = sorted(
          shown as RelationTypeView[],
          sort.value,
          (a: RelationTypeView) =>
            (sort.value?.col === "usage"
              ? a.usage
              : sort.value?.col === "datatype"
                ? (a.datatype ?? "")
                : a.label) as string | number
        );
        const paged = pageSlice(rows, page.value, TABLE_PAGE);
        body = (
          <div class="table-box">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">{S.ontology.colName}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.colOnClass}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.colDatatype}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.colUnit}</th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.colSingleValued}
                  </th>
                  <th class="px-4 py-3 text-right font-medium">
                    {S.ontology.colFacts}
                  </th>
                </tr>
              </thead>
              <tbody>
                {paged.rows.map((a: RelationTypeView) => (
                  <tr
                    key={a.id}
                    class="cursor-pointer border-b border-line last:border-0 hover:bg-fill"
                    onClick={() => emit("openProperty", a)}
                  >
                    <td class="px-4 py-3">
                      <span class="flex items-center gap-2">
                        <span class="truncate">{a.label}</span>
                        {a.builtin && <UChip>{S.ontology.builtin}</UChip>}
                      </span>
                    </td>
                    <td class="text-sub text-ink-2">
                      {a.domains.length
                        ? a.domains
                            .map(i => nameOf.value.get(i) ?? i)
                            .join(", ")
                        : "—"}
                    </td>
                    <td class="text-sub text-ink-2">
                      {S.ontology.datatypeNames[
                        (a.datatype ?? "text") as keyof typeof S.ontology.datatypeNames
                      ]}
                    </td>
                    <td class="text-sub text-ink-2">{a.unit ?? "—"}</td>
                    <td class="text-sub text-ink-2">
                      {a.functional ? "1:1" : "—"}
                    </td>
                    <td class="tabular-nums text-right text-ink-2">{a.usage}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <UPager
              className="table-pager"
              total={rows.length}
              pageSize={TABLE_PAGE}
              page={paged.safe}
              onPage={(p: number) => (page.value = p)}
            />
          </div>
        );
      }
      return (
        <div class="h-full overflow-y-auto overscroll-contain">
          {props.loading ? (
            <div class="text-sub text-ink-2">{S.nav.loading}</div>
          ) : (
            body
          )}
        </div>
      );
    };
  }
});
