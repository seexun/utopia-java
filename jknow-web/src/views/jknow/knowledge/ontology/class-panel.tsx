/* 模式图右侧停靠面板的内容面（对照旧仓 pages/Ontology.tsx 的面板段）：
   面板壳（DockedPanel）、类定义（只读）、关系卡（出/入两组）、属性卡、
   实例列表（服务端分页）。编辑弹窗属任务 6，只留展示。 */
import { computed, defineComponent, ref, watch, type PropType } from "vue";
import { useQuery } from "@tanstack/vue-query";
import { typeEntities as typeEntitiesApi } from "@/api/knowledge/ontology";
import type { EntityTypeView, RelationTypeView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { UChip, URow, UPager, RouterRowLink } from "@/components/knowledge/kit";

/** 一行「标签 / 值」；值空着就写占位，不留白 */
function Def(props: { label: string; children?: unknown }) {
  return (
    <div class="border-b border-line py-2 last:border-0">
      <div class="text-sub text-ink-2">{props.label}</div>
      <div class="mt-1 wrap-break-word text-body text-ink">
        {props.children}
      </div>
    </div>
  );
}

function Description(props: { text: string | null | undefined }) {
  return props.text ? (
    <span>{props.text}</span>
  ) : (
    <span class="text-ink-2">—</span>
  );
}

export const PanelHeader = defineComponent({
  name: "PanelHeader",
  props: {
    color: { type: String, default: undefined },
    square: { type: Boolean, default: false },
    title: { type: String, required: true },
    sub: { type: String, default: undefined },
    builtin: { type: Boolean, default: false }
  },
  setup(props) {
    return () => (
      <>
        <div class="flex items-center gap-2">
          {props.color && (
            <span
              class={[
                "size-2.5  shrink-0",
                props.square ? "scale-90" : "rounded-full"
              ]}
              style={{
                background: props.color,
                boxShadow: `0 0 8px ${props.color}55`
              }}
            />
          )}
          <span class="truncate text-title font-semibold tracking-tight text-ink">
            {props.title}
          </span>
          {props.builtin && <UChip>{S.ontology.builtin}</UChip>}
        </div>
        {props.sub && <div class="mt-1 text-sub text-ink-2">{props.sub}</div>}
      </>
    );
  }
});

/** 停靠面板壳：标题行固定，只有下面的内容滚（旧仓 DockedPanel 同构） */
export const DockedPanel = defineComponent({
  name: "DockedPanel",
  props: { exiting: { type: Boolean, default: false } },
  emits: ["close"],
  setup(props, { slots, emit }) {
    return () => (
      <div
        class={[
          props.exiting ? "dock-out" : "dock-in",
          "absolute inset-y-3 right-3  z-10 flex w-96 flex-col rounded-card border border-line bg-card shadow-pop"
        ]}
      >
        <div class="flex shrink-0 items-start justify-between gap-2 border-b border-line p-4 ">
          <div class="min-w-0">{slots.header?.()}</div>
          <div class="-mr-1 -mt-1 flex shrink-0 items-center gap-1">
            {slots.actions?.()}
            <button
              type="button"
              class="rounded-ctl p-1 text-ink-2 hover:bg-fill hover:text-ink"
              title={S.ontology.schemaClosePanel}
              onClick={() => emit("close")}
            >
              <svg
                width="15"
                height="15"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
        {slots.tabs && (
          <div class="shrink-0 px-4 pb-1 pt-3">{slots.tabs()}</div>
        )}
        <div class="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto overscroll-contain px-4 py-3">
          {slots.default?.()}
        </div>
      </div>
    );
  }
});

/** 类定义（只读） */
export const ClassDefinition = defineComponent({
  name: "ClassDefinition",
  props: {
    cls: { type: Object as PropType<EntityTypeView>, required: true },
    allTypes: { type: Array as PropType<EntityTypeView[]>, required: true }
  },
  emits: ["selectClass"],
  setup(props, { emit }) {
    const nameOf = (id: string) =>
      props.allTypes.find(t => t.id === id)?.label ?? id;
    const classLinks = (ids: string[]) =>
      ids.map((id, index) => (
        <span key={id}>
          {index > 0 && ", "}
          <button
            type="button"
            class="link-inline text-sub"
            onClick={() => emit("selectClass", id)}
          >
            {nameOf(id)}
          </button>
        </span>
      ));
    return () => {
      const cls = props.cls;
      const subclasses = props.allTypes.filter(t => t.parents.includes(cls.id));
      return (
        <div>
          <Def label={S.ontology.parent}>
            {cls.parents.length > 0 ? (
              classLinks(cls.parents)
            ) : (
              <span class="text-ink-2">{S.ontology.noParent}</span>
            )}
          </Def>
          <Def label={S.ontology.subclasses}>
            {subclasses.length > 0 ? (
              classLinks(subclasses.map(t => t.id))
            ) : (
              <span class="text-ink-2">{S.ontology.noSubclasses}</span>
            )}
          </Def>
          <Def label={S.ontology.disjoint}>
            {cls.disjoint.length > 0 ? (
              cls.disjoint.map(nameOf).join(", ")
            ) : (
              <span class="text-ink-2">{S.ontology.noDisjoint}</span>
            )}
          </Def>
          <Def label={S.ontology.description}>
            <Description text={cls.description} />
          </Def>
        </div>
      );
    };
  }
});

/** 关系定义（只读） */
export const PropertyDefinition = defineComponent({
  name: "PropertyDefinition",
  props: {
    rel: { type: Object as PropType<RelationTypeView>, required: true },
    allTypes: { type: Array as PropType<EntityTypeView[]>, required: true },
    allRelations: {
      type: Array as PropType<RelationTypeView[]>,
      required: true
    }
  },
  setup(props) {
    return () => {
      const rel = props.rel;
      const typeName = (id: string) =>
        props.allTypes.find(t => t.id === id)?.label ?? id;
      const relName = (id: string) =>
        props.allRelations.find(r => r.id === id)?.label ?? id;
      const temporal =
        (
          {
            state: S.ontology.temporalState,
            event: S.ontology.temporalEvent,
            eternal: S.ontology.temporalEternal
          } as Record<string, string>
        )[rel.temporal] ?? rel.temporal;
      // 勾了的公理才列：没勾的不是信息
      const axioms = (
        [
          [rel.functional, S.ontology.functional],
          [rel.inverse_functional, S.ontology.inverseFunctional],
          [rel.is_transitive, S.ontology.transitive],
          [rel.is_symmetric, S.ontology.symmetric],
          [rel.is_asymmetric, S.ontology.asymmetric],
          [rel.is_irreflexive, S.ontology.irreflexive]
        ] as const
      )
        .filter(([on]) => on)
        .map(([, name]) => name);
      return (
        <div>
          <Def label={S.ontology.domainLabel}>
            {rel.domains.length > 0
              ? rel.domains.map(typeName).join(", ")
              : S.ontology.anyType}
          </Def>
          <Def label={S.ontology.rangeLabel}>
            {rel.ranges.length > 0
              ? rel.ranges.map(typeName).join(", ")
              : S.ontology.anyType}
          </Def>
          {/* 边上的属性（0037）：旧版本的后端不发它——少一格不该让整块面板崩掉 */}
          <Def label={S.ontology.qualifiers}>
            {(rel.qualifiers ?? []).length > 0
              ? (rel.qualifiers ?? []).map(typeName).join(", ")
              : S.ontology.noQualifiers}
          </Def>
          <Def label={S.ontology.temporal}>{temporal}</Def>
          <Def label={S.ontology.axioms}>
            {axioms.length > 0 ? (
              <span class="flex flex-wrap gap-1">
                {axioms.map(a => (
                  <UChip key={a} tone="info">
                    {a}
                  </UChip>
                ))}
              </span>
            ) : (
              <span class="text-ink-2">{S.ontology.axiomsNone}</span>
            )}
          </Def>
          {rel.inverse_of && (
            <Def label={S.ontology.inverseOf}>{relName(rel.inverse_of)}</Def>
          )}
          {rel.sub_property_of && (
            <Def label={S.ontology.subPropertyOf}>
              {relName(rel.sub_property_of)}
            </Def>
          )}
          <Def label={S.ontology.description}>
            <Description text={rel.description} />
          </Def>
        </div>
      );
    };
  }
});

/** 关系卡：这个类作为主语/宾语连着哪些关系（读面；连接入口属任务 6） */
export const RelationshipsCard = defineComponent({
  name: "RelationshipsCard",
  props: {
    cls: { type: Object as PropType<EntityTypeView>, required: true },
    relations: { type: Array as PropType<RelationTypeView[]>, required: true },
    allTypes: { type: Array as PropType<EntityTypeView[]>, required: true }
  },
  emits: ["select"],
  setup(props, { emit }) {
    const labelOf = (id: string) =>
      props.allTypes.find(t => t.id === id)?.label ?? id;
    // 自环关系（domain 和 range 都是这个类）两边都算
    const outgoing = computed(() =>
      props.relations.filter(r => r.domains.includes(props.cls.id))
    );
    const incoming = computed(() =>
      props.relations.filter(r => r.ranges.includes(props.cls.id))
    );
    const openOut = ref(true);
    const openIn = ref(true);
    const RelationRow = (props2: {
      r: RelationTypeView;
      dir: "out" | "in";
    }) => (
      <URow class="-mx-2" onClick={() => emit("select", props2.r.id)}>
        {{
          default: () => props2.r.label,
          trailing: () => (
            <span class="block max-w-32 truncate">
              {(props2.dir === "out" ? props2.r.ranges : props2.r.domains)
                .map(labelOf)
                .join(", ") || S.ontology.anyType}
            </span>
          )
        }}
      </URow>
    );
    return () => (
      <div>
        {outgoing.value.length === 0 && incoming.value.length === 0 ? (
          <p class="mb-2 text-sub text-ink-2">
            {S.ontology.schemaNoRelationships}
          </p>
        ) : (
          <div class="mb-2">
            {outgoing.value.length > 0 && (
              <div>
                <URow
                  class="-mx-2 mt-1"
                  onClick={() => (openOut.value = !openOut.value)}
                >
                  <span class="flex items-center gap-2 text-sub font-medium">
                    <span class="text-primary">→</span>
                    <span class="truncate">{S.ontology.schemaOutgoing}</span>
                    {outgoing.value.length > 1 && (
                      <span class="tabular-nums">{outgoing.value.length}</span>
                    )}
                  </span>
                </URow>
                {openOut.value && (
                  <div class="pl-6">
                    {outgoing.value.map(r => (
                      <RelationRow key={`out:${r.id}`} r={r} dir="out" />
                    ))}
                  </div>
                )}
              </div>
            )}
            {incoming.value.length > 0 && (
              <div>
                <URow
                  class="-mx-2 mt-1"
                  onClick={() => (openIn.value = !openIn.value)}
                >
                  <span class="flex items-center gap-2 text-sub font-medium">
                    <span class="text-primary">←</span>
                    <span class="truncate">{S.ontology.schemaIncoming}</span>
                    {incoming.value.length > 1 && (
                      <span class="tabular-nums">{incoming.value.length}</span>
                    )}
                  </span>
                </URow>
                {openIn.value && (
                  <div class="pl-6">
                    {incoming.value.map(r => (
                      <RelationRow key={`in:${r.id}`} r={r} dir="in" />
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    );
  }
});

/** 属性卡：选中类的字面值字段（读面） */
export const AttributesCard = defineComponent({
  name: "AttributesCard",
  props: {
    attributes: { type: Array as PropType<RelationTypeView[]>, required: true }
  },
  setup(props) {
    return () => (
      <div>
        <p class="mb-2 text-aux text-ink-2">{S.ontology.attributesHint}</p>
        <div class="divide-y divide-line">
          {props.attributes.map(a => (
            <URow key={a.id} class="-mx-2">
              <span class="flex items-center gap-2">
                <span class="truncate">{a.label}</span>
                <UChip>
                  {
                    S.ontology.datatypeNames[
                      (a.datatype ??
                        "text") as keyof typeof S.ontology.datatypeNames
                    ]
                  }
                </UChip>
                {a.unit && (
                  <span class="shrink-0 text-sub text-ink-2">{a.unit}</span>
                )}
                {a.functional && <UChip tone="info">1:1</UChip>}
              </span>
            </URow>
          ))}
        </div>
      </div>
    );
  }
});

/** 实例列表：选中类的实体（服务端分页，点击进图谱） */
export const InstancesCard = defineComponent({
  name: "InstancesCard",
  props: {
    kbId: { type: String, required: true },
    type: { type: Object as PropType<EntityTypeView>, required: true }
  },
  setup(props) {
    const PER = 12;
    const page = ref(0);
    watch(
      () => props.type.id,
      () => (page.value = 0)
    );
    const q = useQuery({
      queryKey: computed(() => [
        "type-entities",
        props.kbId,
        props.type.id,
        page.value
      ]),
      queryFn: () => typeEntitiesApi(props.kbId, props.type.id, page.value, PER)
    });
    return () => {
      const total = q.data.value?.total ?? 0;
      const rows = q.data.value?.entities ?? [];
      // 这一段自己就是 Instances 那个 tab：没有实例就说一句
      if (!q.isLoading.value && total === 0)
        return (
          <p class="text-sub text-ink-2">{S.ontology.schemaNoInstances}</p>
        );
      return (
        <div>
          <div>
            {rows.map(e => (
              <RouterRowLink
                key={e.id}
                to={{
                  path: `/kb/${props.kbId}/graph`,
                  query: { entity: e.id }
                }}
              >
                <span
                  class={[
                    "size-2  shrink-0",
                    props.type.shape === "square" ? "scale-90" : "rounded-full"
                  ]}
                  style={{ background: props.type.color }}
                />
                <span class="truncate">{e.name}</span>
                <span class="ml-auto shrink-0 tabular-nums text-aux text-ink-2">
                  {S.ontology.instanceFacts(e.fact_count)}
                </span>
              </RouterRowLink>
            ))}
          </div>
          <UPager
            total={total}
            pageSize={PER}
            page={page.value}
            onPage={(p: number) => (page.value = p)}
          />
        </div>
      );
    };
  }
});
