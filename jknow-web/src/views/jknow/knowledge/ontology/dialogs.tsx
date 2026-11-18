/* 本体的编辑弹窗（对照旧仓 pages/ontologyDialogs.tsx 逐行同构换框架）：
   类、关系、属性，以及「用一个已有的关系连接这个类」。
   右侧面板只展示，改动开一扇窗——表单里的每一项照旧，只是有了自己的标题、
   自己的页脚和 Esc。校验就地说话：互斥与父类同选当场报（disjointWithParent）、
   对称与反对称同勾当场报（axiomConflict）。 */
import { computed, defineComponent, ref, type PropType } from "vue";
import {
  createEntityType,
  updateEntityType,
  deleteEntityType,
  createRelationType,
  updateRelationType,
  deleteRelationType
} from "@/api/knowledge/ontology";
import type { EntityTypeView, RelationTypeView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { colorForKey } from "@/domain/palette";
import { UFormDialog, type DangerAction } from "@/components/knowledge/dialog";
import { USegmented } from "@/components/knowledge/kit";

/** 父类下拉的候选：树序 + 缩进（层级可见），排除自己与全部后代（防成环）。 */
export function parentOptions(
  allTypes: EntityTypeView[],
  selfId: string | undefined
): { value: string; label: string; indent: number }[] {
  const excluded = new Set<string>();
  if (selfId) {
    excluded.add(selfId);
    // 收后代：反复扫描直到收敛（类的数量级很小，O(n²) 无所谓）
    let grew = true;
    while (grew) {
      grew = false;
      for (const t of allTypes) {
        if (t.parents.some(p => excluded.has(p)) && !excluded.has(t.id)) {
          excluded.add(t.id);
          grew = true;
        }
      }
    }
  }
  const children = new Map<string | null, EntityTypeView[]>();
  for (const t of allTypes) {
    const p = t.primary_parent ?? null;
    if (!children.has(p)) children.set(p, []);
    children.get(p)!.push(t);
  }
  const out: { value: string; label: string; indent: number }[] = [];
  const walk = (parent: string | null, depth: number) => {
    for (const t of children.get(parent) ?? []) {
      if (excluded.has(t.id)) continue;
      out.push({ value: t.id, label: t.label, indent: depth });
      walk(t.id, depth + 1);
    }
  };
  walk(null, 0);
  return out;
}

/* ---------- 类 ---------- */

export const ClassDialog = defineComponent({
  name: "ClassDialog",
  props: {
    kbId: { type: String, required: true },
    existing: {
      type: Object as PropType<EntityTypeView | null>,
      default: null
    },
    /** 新建时的父类（「新建子类」进来时预填）；多父下它是主父 */
    parentId: { type: String as PropType<string | null>, default: null },
    allTypes: { type: Array as PropType<EntityTypeView[]>, required: true }
  },
  emits: {
    close: () => true,
    /** 创建成功时携带新 id，编辑成功时为 undefined */
    saved: (_createdId?: string) => true,
    deleted: () => true
  },
  setup(props, { emit }) {
    const existing = props.existing;
    const key = ref(existing?.key ?? "");
    const label = ref(existing?.label ?? "");
    // 新建时颜色跟着 key 走（与后端 color_for_key 同一个规则）：不改的话，
    // 手动建的类和导入建的类配色体系一致
    const color = ref(existing?.color ?? colorForKey(existing?.key ?? ""));
    const colorTouched = ref(Boolean(existing?.color));
    const shape = ref<"circle" | "square">(existing?.shape ?? "circle");
    const parents = ref<string[]>(
      existing?.parents ?? (props.parentId ? [props.parentId] : [])
    );
    // 互斥：声明「不可能同时是」。一致性检查据此报不可满足的类（0002）
    const disjoint = ref<string[]>(existing?.disjoint ?? []);
    const description = ref(existing?.description ?? "");
    const busy = ref(false);
    const options = computed(() => parentOptions(props.allTypes, existing?.id));
    const invalid = computed(() =>
      disjoint.value.some(d => parents.value.includes(d))
    );
    const canSave = computed(
      () => !!label.value.trim() && (!!existing || !!key.value.trim())
    );
    const remove = ref<DangerAction | null>(
      existing && !existing.builtin
        ? {
            label: S.ontology.delete,
            get disabled() {
              return existing.usage > 0 || busy.value;
            },
            title: existing.usage > 0 ? S.ontology.deleteBlocked : undefined,
            onClick: async () => {
              busy.value = true;
              try {
                await deleteEntityType(props.kbId, existing!.id);
                toast.success(S.toast.deleted);
                emit("deleted");
              } catch (e) {
                onError(e);
              }
              busy.value = false;
            }
          }
        : null
    );
    function onError(e: unknown) {
      // 422 code 直显（client 层已 toast 一遍 422/403；这里补内联一行）
      toast.error(e instanceof Error ? e.message : String(e));
    }
    async function save() {
      busy.value = true;
      try {
        const res = existing
          ? await updateEntityType(props.kbId, existing.id, {
              label: label.value,
              color: color.value,
              shape: shape.value,
              parents: parents.value,
              disjoint: disjoint.value,
              description: description.value
            })
          : await createEntityType(props.kbId, {
              key: key.value,
              label: label.value,
              color: color.value,
              shape: shape.value,
              parents: parents.value,
              disjoint: disjoint.value,
              description: description.value
            });
        toast.success(existing ? S.toast.saved : S.toast.created);
        emit("saved", existing ? undefined : (res as { id?: string })?.id);
      } catch (e) {
        onError(e);
      }
      busy.value = false;
    }
    return () => (
      <UFormDialog
        title={existing ? S.ontology.editClass : S.ontology.newClass}
        description={
          existing ? `${existing.key} · ${S.ontology.usage(existing.usage)}` : undefined
        }
        closeLabel={S.ui.close}
        saveLabel={S.ontology.save}
        cancelLabel={S.ontology.cancel}
        canSave={canSave.value && !invalid.value}
        busy={busy.value}
        onSave={save}
        onCancel={() => emit("close")}
        v-slots={{
          danger: () =>
            remove.value ? (
              <el-button
                size="small"
                disabled={remove.value.disabled || busy.value}
                {...{ title: remove.value.title }}
                onClick={remove.value.onClick}
              >
                {remove.value.label}
              </el-button>
            ) : null,
          default: () => (
            <>
              {/* key 是纯技术标识：已存在时不展示，只在创建时输入 */}
              {!existing && (
                <div class="mb-3">
                  <div class="mb-1 text-sub font-medium text-ink-2">
                    {S.ontology.key}{" "}
                    <span class="text-ink-2">({S.ontology.keyHint})</span>
                  </div>
                  <el-input
                    class="w-full"
                    placeholder="contract"
                    modelValue={key.value}
                    {...{
                      "onUpdate:modelValue": (v: string) => {
                        key.value = v;
                        // 用户没自己挑过色，就让颜色跟着 key 走——与后端同一个规则
                        if (!colorTouched.value) color.value = colorForKey(v);
                      }
                    }}
                  />
                </div>
              )}
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.label}
                </div>
                <el-input
                  class="w-full"
                  modelValue={label.value}
                  {...{
                    "onUpdate:modelValue": (v: string) => (label.value = v)
                  }}
                />
              </div>
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.shapeColor}
                </div>
                <div class="flex items-center gap-2">
                  <el-color-picker
                    modelValue={color.value}
                    {...{
                      "onUpdate:modelValue": (c: string | null) => {
                        if (c) {
                          color.value = c;
                          colorTouched.value = true;
                        }
                      }
                    }}
                  />
                  {/* 形状：与图谱节点渲染一一对应（circle=实色圆点 / square=同款方形） */}
                  <USegmented
                    size="sm"
                    value={shape.value}
                    options={(["circle", "square"] as const).map(sh => ({
                      value: sh,
                      title: sh,
                      label: (
                        <span class="flex h-4 items-center">
                          <span
                            class={[
                              "h-3 w-3 border-[1.5px] border-current",
                              sh === "circle" ? "rounded-full" : "scale-90"
                            ]}
                          />
                        </span>
                      )
                    }))}
                    onChange={(v: string) => (shape.value = v as "circle" | "square")}
                  />
                </div>
              </div>
              {/* 多父：subClassOf 可以有多条。第一个当主父——界面说明这条 */}
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.parent}
                  {parents.value.length > 1 && (
                    <span class="ml-2 text-aux text-ink-2">
                      {S.ontology.primaryParentHint}
                    </span>
                  )}
                </div>
                <el-select
                  multiple
                  filterable
                  class="w-full"
                  modelValue={parents.value}
                  placeholder={S.ontology.searchTypes}
                  {...{
                    "onUpdate:modelValue": (v: string[]) => (parents.value = v)
                  }}
                >
                  {options.value.map(o => (
                    <el-option key={o.value} value={o.value} label={o.label}>
                      <span style={{ paddingLeft: `${o.indent * 12}px` }}>
                        {o.label}
                      </span>
                    </el-option>
                  ))}
                </el-select>
              </div>
              {/* 互斥：紧挨着父类——父类说「也是」，互斥说「不可能同时是」，
                  一致性检查正是在这两者打架时报出「这个类永远不可能有实例」 */}
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.disjoint}
                </div>
                {invalid.value && (
                  <p class="mb-1 text-sub text-danger">
                    {S.ontology.disjointWithParent}
                  </p>
                )}
                <el-select
                  multiple
                  filterable
                  class="w-full"
                  modelValue={disjoint.value}
                  placeholder={S.ontology.searchTypes}
                  {...{
                    "onUpdate:modelValue": (v: string[]) => (disjoint.value = v)
                  }}
                >
                  {options.value.map(o => (
                    <el-option key={o.value} value={o.value} label={o.label}>
                      <span style={{ paddingLeft: `${o.indent * 12}px` }}>
                        {o.label}
                      </span>
                    </el-option>
                  ))}
                </el-select>
              </div>
              {/* 语义指引：整段注入抽取 prompt，直接影响抽取归类质量 */}
              <div class="mb-1">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.description}
                  <span class="ml-2 text-aux text-ink-2">
                    {S.ontology.descriptionHint}
                  </span>
                </div>
                <el-input
                  type="textarea"
                  rows={5}
                  class="w-full"
                  modelValue={description.value}
                  {...{
                    "onUpdate:modelValue": (v: string) =>
                      (description.value = v)
                  }}
                />
              </div>
            </>
          )
        }}
      />
    );
  }
});

/* ---------- 关系 ---------- */

export const PropertyDialog = defineComponent({
  name: "PropertyDialog",
  props: {
    kbId: { type: String, required: true },
    existing: {
      type: Object as PropType<RelationTypeView | null>,
      default: null
    },
    allTypes: { type: Array as PropType<EntityTypeView[]>, required: true },
    /** 本库的关系（不含属性）。逆与子属性的下拉从这里取 */
    allRelations: {
      type: Array as PropType<RelationTypeView[]>,
      required: true
    },
    /** 从一个类出发新建关系时，domain 预填成那个类——只影响初始值 */
    initialDomain: { type: String as PropType<string | null>, default: null }
  },
  emits: {
    close: () => true,
    saved: (_createdId?: string) => true,
    deleted: () => true
  },
  setup(props, { emit }) {
    const existing = props.existing;
    const key = ref(existing?.key ?? "");
    const label = ref(existing?.label ?? "");
    const temporal = ref(existing?.temporal ?? "state");
    const functional = ref(existing?.functional ?? false);
    const inverseFunctional = ref(existing?.inverse_functional ?? false);
    // 其余四条 OWL 公理。**推理机的判据全在这里**
    const transitive = ref(existing?.is_transitive ?? false);
    const symmetric = ref(existing?.is_symmetric ?? false);
    const asymmetric = ref(existing?.is_asymmetric ?? false);
    const irreflexive = ref(existing?.is_irreflexive ?? false);
    // 空串 = 没声明
    const inverseOf = ref(existing?.inverse_of ?? "");
    const subPropertyOf = ref(existing?.sub_property_of ?? "");
    const description = ref(existing?.description ?? "");
    const domains = ref<string[]>(
      existing?.domains ?? (props.initialDomain ? [props.initialDomain] : [])
    );
    const ranges = ref<string[]>(existing?.ranges ?? []);
    // 边上的属性（0037）：选的是本库里 kind=attribute 的定义
    const qualifiers = ref<string[]>(existing?.qualifiers ?? []);
    const busy = ref(false);
    const typeOpts = computed(() => parentOptions(props.allTypes, undefined));
    // 两个下拉的选项：本库的其它关系。唯一的例外是**当前值就是自己**：
    // OWL 导入进来的可以长这样，从列表里漏掉它就会让下拉显示空白
    const linkOptions = (current: string) => [
      { value: "", label: S.ontology.noLink },
      ...(existing && current === existing.id
        ? [{ value: existing.id, label: existing.label, hint: existing.key }]
        : []),
      ...props.allRelations
        .filter(r => r.id !== existing?.id)
        .map(r => ({ value: r.id, label: r.label, hint: r.key }))
    ];
    const nameOf = (id: string) =>
      props.allRelations.find(r => r.id === id)?.label ?? id;
    const axiomConflict = computed(
      () => symmetric.value && asymmetric.value
    );
    const canSave = computed(
      () => !!label.value.trim() && (!!existing || !!key.value.trim())
    );
    const remove = existing && !existing.builtin
      ? {
          label: S.ontology.delete,
          get disabled() {
            return existing.usage > 0 || busy.value;
          },
          title: existing.usage > 0 ? S.ontology.deleteBlocked : undefined,
          onClick: async () => {
            busy.value = true;
            try {
              await deleteRelationType(props.kbId, existing!.id);
              toast.success(S.toast.deleted);
              emit("deleted");
            } catch (e) {
              onError(e);
            }
            busy.value = false;
          }
        }
      : null;
    function onError(e: unknown) {
      toast.error(e instanceof Error ? e.message : String(e));
    }
    async function save() {
      busy.value = true;
      try {
        const body = {
          label: label.value,
          temporal: temporal.value,
          functional: functional.value,
          inverse_functional: inverseFunctional.value,
          is_transitive: transitive.value,
          is_symmetric: symmetric.value,
          is_asymmetric: asymmetric.value,
          is_irreflexive: irreflexive.value,
          // 空串要变成 null 再送——服务端收 Option<Uuid>，"" 是 422 不是「清空」
          inverse_of: inverseOf.value || null,
          sub_property_of: subPropertyOf.value || null,
          description: description.value,
          domains: domains.value,
          ranges: ranges.value,
          qualifiers: qualifiers.value
        };
        const res = existing
          ? await updateRelationType(props.kbId, existing.id, body)
          : await createRelationType(props.kbId, { key: key.value, ...body });
        toast.success(existing ? S.toast.saved : S.toast.created);
        emit("saved", existing ? undefined : (res as { id?: string })?.id);
      } catch (e) {
        onError(e);
      }
      busy.value = false;
    }
    const AXIOMS = computed(() =>
      (
        [
          [functional, S.ontology.functional, S.ontology.functionalHint],
          [inverseFunctional, S.ontology.inverseFunctional, S.ontology.inverseFunctionalHint],
          [transitive, S.ontology.transitive, S.ontology.transitiveHint],
          [symmetric, S.ontology.symmetric, S.ontology.symmetricHint],
          [asymmetric, S.ontology.asymmetric, S.ontology.asymmetricHint],
          [irreflexive, S.ontology.irreflexive, S.ontology.irreflexiveHint]
        ] as const
      ).map(([r, title, hint]) => ({ r, title, hint }))
    );
    return () => (
      <UFormDialog
        width="lg"
        title={existing ? S.ontology.editProperty : S.ontology.newProperty}
        description={
          existing ? `${existing.key} · ${S.ontology.usage(existing.usage)}` : undefined
        }
        closeLabel={S.ui.close}
        saveLabel={S.ontology.save}
        cancelLabel={S.ontology.cancel}
        canSave={canSave.value && !axiomConflict.value}
        busy={busy.value}
        onSave={save}
        onCancel={() => emit("close")}
        v-slots={{
          danger: () =>
            remove ? (
              <el-button
                size="small"
                disabled={remove.disabled || busy.value}
                {...{ title: remove.title }}
                onClick={remove.onClick}
              >
                {remove.label}
              </el-button>
            ) : null,
          default: () => (
            <>
              {!existing && (
                <div class="mb-3">
                  <div class="mb-1 text-sub font-medium text-ink-2">
                    {S.ontology.key}{" "}
                    <span class="text-ink-2">({S.ontology.keyHint})</span>
                  </div>
                  <el-input
                    class="w-full"
                    placeholder="signed_with"
                    modelValue={key.value}
                    {...{
                      "onUpdate:modelValue": (v: string) => (key.value = v)
                    }}
                  />
                </div>
              )}
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.label}
                </div>
                <el-input
                  class="w-full"
                  modelValue={label.value}
                  {...{
                    "onUpdate:modelValue": (v: string) => (label.value = v)
                  }}
                />
              </div>
              {/* 类型签名。界面显示标签，进提示词的是 key——那一步在服务端（0004） */}
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.signature}
                  <span class="ml-2 text-aux text-ink-2">
                    {S.ontology.signatureHint}
                  </span>
                </div>
                <div class="grid gap-2 sm:grid-cols-2">
                  <div class="min-w-0">
                    <div class="mb-1 text-sub text-ink-2">{S.ontology.domainLabel}</div>
                    <el-select
                      multiple
                      filterable
                      class="w-full"
                      modelValue={domains.value}
                      placeholder={S.ontology.searchTypes}
                      {...{
                        "onUpdate:modelValue": (v: string[]) =>
                          (domains.value = v)
                      }}
                    >
                      {typeOpts.value.map(o => (
                        <el-option key={o.value} value={o.value} label={o.label} />
                      ))}
                    </el-select>
                  </div>
                  <div class="min-w-0">
                    <div class="mb-1 text-sub text-ink-2">{S.ontology.rangeLabel}</div>
                    <el-select
                      multiple
                      filterable
                      class="w-full"
                      modelValue={ranges.value}
                      placeholder={S.ontology.searchTypes}
                      {...{
                        "onUpdate:modelValue": (v: string[]) =>
                          (ranges.value = v)
                      }}
                    >
                      {typeOpts.value.map(o => (
                        <el-option key={o.value} value={o.value} label={o.label} />
                      ))}
                    </el-select>
                  </div>
                  <div class="min-w-0">
                    {/* 边上的属性（0037）：关系不能把自己声明成自己的属性 */}
                    <div class="mb-1 text-sub text-ink-2">{S.ontology.qualifiers}</div>
                    <el-select
                      multiple
                      filterable
                      class="w-full"
                      modelValue={qualifiers.value}
                      placeholder={S.ontology.searchTypes}
                      {...{
                        "onUpdate:modelValue": (v: string[]) =>
                          (qualifiers.value = v)
                      }}
                    >
                      {props.allRelations
                        .filter(
                          r => r.kind === "attribute" && r.id !== existing?.id
                        )
                        .map(r => (
                          <el-option key={r.id} value={r.id} label={r.label} />
                        ))}
                    </el-select>
                  </div>
                </div>
              </div>
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.temporal}
                </div>
                <el-select
                  class="w-full"
                  modelValue={temporal.value}
                  {...{
                    "onUpdate:modelValue": (v: string) => (temporal.value = v)
                  }}
                >
                  <el-option value="state" label={S.ontology.temporalState} />
                  <el-option value="event" label={S.ontology.temporalEvent} />
                  <el-option value="eternal" label={S.ontology.temporalEternal} />
                </el-select>
              </div>
              {/* 六条公理并成一组。每一条底下写清「勾了会发生什么」。
                  对称与反对称同时勾是自相矛盾的（只对空关系成立），当场说一句 */}
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.axioms}
                  <span class="ml-2 text-aux text-ink-2">{S.ontology.axiomsHint}</span>
                </div>
                {axiomConflict.value && (
                  <p class="mb-1 text-sub text-danger">{S.ontology.axiomConflict}</p>
                )}
                <div class="space-y-2">
                  {AXIOMS.value.map(({ r, title, hint }) => (
                    <div key={title}>
                      <el-checkbox
                        modelValue={r.value}
                        label={title}
                        {...{
                          "onUpdate:modelValue": (v: boolean) => (r.value = v)
                        }}
                      />
                      <div class="pl-6 text-aux text-ink-2">{hint}</div>
                    </div>
                  ))}
                </div>
              </div>
              {/* 同一组的后两条指向**另一个关系**，所以是下拉不是复选框。
                  选了之后当场把话说全——这两条推出来的事实主宾未必同向 */}
              <div class="grid gap-2 sm:grid-cols-2">
                <div>
                  <div class="mb-1 text-sub text-ink-2">
                    {S.ontology.inverseOf}
                    <span class="ml-2 text-aux text-ink-2">
                      {S.ontology.inverseOfHint}
                    </span>
                  </div>
                  <el-select
                    filterable
                    class="w-full"
                    modelValue={inverseOf.value}
                    placeholder={S.ontology.noLink}
                    {...{
                      "onUpdate:modelValue": (v: string) =>
                        (inverseOf.value = v)
                    }}
                  >
                    {linkOptions(inverseOf.value).map(o => (
                      <el-option key={o.value || "_"} value={o.value} label={o.label} />
                    ))}
                  </el-select>
                </div>
                <div>
                  <div class="mb-1 text-sub text-ink-2">
                    {S.ontology.subPropertyOf}
                    <span class="ml-2 text-aux text-ink-2">
                      {S.ontology.subPropertyOfHint}
                    </span>
                  </div>
                  <el-select
                    filterable
                    class="w-full"
                    modelValue={subPropertyOf.value}
                    placeholder={S.ontology.noLink}
                    {...{
                      "onUpdate:modelValue": (v: string) =>
                        (subPropertyOf.value = v)
                    }}
                  >
                    {linkOptions(subPropertyOf.value).map(o => (
                      <el-option key={o.value || "_"} value={o.value} label={o.label} />
                    ))}
                  </el-select>
                </div>
              </div>
              {(inverseOf.value || subPropertyOf.value) && (
                <div class="mb-4 mt-2 space-y-1 text-aux leading-relaxed text-ink-2">
                  {inverseOf.value && (
                    <div>
                      {S.ontology.linkMeansInverse(
                        label.value.trim() || key.value || "?",
                        nameOf(inverseOf.value)
                      )}
                    </div>
                  )}
                  {subPropertyOf.value && (
                    <div>
                      {S.ontology.linkMeansSuper(
                        label.value.trim() || key.value || "?",
                        nameOf(subPropertyOf.value)
                      )}
                    </div>
                  )}
                </div>
              )}
              <div class="mb-1">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.description}
                  <span class="ml-2 text-aux text-ink-2">
                    {S.ontology.descriptionHint}
                  </span>
                </div>
                <el-input
                  type="textarea"
                  rows={5}
                  class="w-full"
                  modelValue={description.value}
                  {...{
                    "onUpdate:modelValue": (v: string) =>
                      (description.value = v)
                  }}
                />
              </div>
            </>
          )
        }}
      />
    );
  }
});

/* ---------- 属性（一个类的字面值字段） ---------- */

export const AttributeDialog = defineComponent({
  name: "AttributeDialog",
  props: {
    kbId: { type: String, required: true },
    typeId: { type: String, required: true },
    existing: {
      type: Object as PropType<RelationTypeView | null>,
      default: null
    }
  },
  emits: {
    close: () => true,
    saved: () => true,
    deleted: () => true
  },
  setup(props, { emit }) {
    const existing = props.existing;
    const key = ref(existing?.key ?? "");
    const label = ref(existing?.label ?? "");
    const datatype = ref(existing?.datatype ?? "text");
    const unit = ref(existing?.unit ?? "");
    // 单值 = functional：新值经时态引擎闭合旧值（属性历史的来源）。多数属性如此，默认开
    const single = ref(existing?.functional ?? true);
    const description = ref(existing?.description ?? "");
    const busy = ref(false);
    const canSave = computed(
      () => !!label.value.trim() && (!!existing || !!key.value.trim())
    );
    const remove = existing
      ? {
          label: S.ontology.delete,
          get disabled() {
            return existing.usage > 0 || busy.value;
          },
          title: existing.usage > 0 ? S.ontology.deleteBlocked : undefined,
          onClick: async () => {
            busy.value = true;
            try {
              await deleteRelationType(props.kbId, existing!.id);
              toast.success(S.toast.deleted);
              emit("deleted");
            } catch (e) {
              onError(e);
            }
            busy.value = false;
          }
        }
      : null;
    function onError(e: unknown) {
      toast.error(e instanceof Error ? e.message : String(e));
    }
    async function save() {
      busy.value = true;
      try {
        if (existing) {
          await updateRelationType(props.kbId, existing.id, {
            label: label.value,
            temporal: existing.temporal,
            functional: single.value,
            inverse_functional: false,
            description: description.value,
            datatype: datatype.value,
            unit: unit.value
          });
        } else {
          await createRelationType(props.kbId, {
            key: key.value,
            label: label.value,
            kind: "attribute",
            domains: [props.typeId],
            temporal: "state",
            functional: single.value,
            inverse_functional: false,
            description: description.value,
            datatype: datatype.value,
            unit: unit.value
          });
        }
        toast.success(existing ? S.toast.saved : S.toast.created);
        emit("saved");
      } catch (e) {
        onError(e);
      }
      busy.value = false;
    }
    return () => (
      <UFormDialog
        title={existing ? S.ontology.editAttribute : S.ontology.newAttribute}
        description={
          existing ? `${existing.key} · ${S.ontology.usage(existing.usage)}` : undefined
        }
        closeLabel={S.ui.close}
        saveLabel={S.ontology.save}
        cancelLabel={S.ontology.cancel}
        canSave={canSave.value}
        busy={busy.value}
        onSave={save}
        onCancel={() => emit("close")}
        v-slots={{
          danger: () =>
            remove ? (
              <el-button
                size="small"
                disabled={remove.disabled || busy.value}
                {...{ title: remove.title }}
                onClick={remove.onClick}
              >
                {remove.label}
              </el-button>
            ) : null,
          default: () => (
            <>
              {existing ? (
                <div class="mb-3">
                  <div class="mb-1 text-sub font-medium text-ink-2">
                    {S.ontology.label}
                  </div>
                  <el-input
                    class="w-full"
                    modelValue={label.value}
                    {...{
                      "onUpdate:modelValue": (v: string) => (label.value = v)
                    }}
                  />
                </div>
              ) : (
                <div class="mb-3 grid grid-cols-2 gap-4">
                  <div>
                    <div class="mb-1 text-sub font-medium text-ink-2">
                      {S.ontology.key}
                    </div>
                    <el-input
                      class="w-full"
                      placeholder="salary"
                      modelValue={key.value}
                      {...{
                        "onUpdate:modelValue": (v: string) => (key.value = v)
                      }}
                    />
                  </div>
                  <div>
                    <div class="mb-1 text-sub font-medium text-ink-2">
                      {S.ontology.label}
                    </div>
                    <el-input
                      class="w-full"
                      modelValue={label.value}
                      {...{
                        "onUpdate:modelValue": (v: string) => (label.value = v)
                      }}
                    />
                  </div>
                </div>
              )}
              <div class="mb-3 grid grid-cols-2 gap-4">
                <div>
                  <div class="mb-1 text-sub font-medium text-ink-2">
                    {S.ontology.attrDatatype}
                  </div>
                  <el-select
                    class="w-full"
                    modelValue={datatype.value}
                    {...{
                      "onUpdate:modelValue": (v: string) =>
                        (datatype.value = v as typeof datatype.value)
                    }}
                  >
                    {(["text", "number", "date", "bool"] as const).map(d => (
                      <el-option
                        key={d}
                        value={d}
                        label={
                          S.ontology.datatypeNames[
                            d as keyof typeof S.ontology.datatypeNames
                          ]
                        }
                      />
                    ))}
                  </el-select>
                </div>
                <div>
                  <div class="mb-1 text-sub font-medium text-ink-2">
                    {S.ontology.attrUnit}{" "}
                    <span class="text-ink-2">({S.ontology.attrUnitHint})</span>
                  </div>
                  <el-input
                    class="w-full"
                    modelValue={unit.value ?? ""}
                    {...{
                      "onUpdate:modelValue": (v: string) => (unit.value = v)
                    }}
                  />
                </div>
              </div>
              <div class="mb-3">
                <el-checkbox
                  modelValue={single.value}
                  label={S.ontology.attrSingle}
                  {...{
                    "onUpdate:modelValue": (v: boolean) => (single.value = v)
                  }}
                />
              </div>
              <div class="mb-1">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.description}
                </div>
                <el-input
                  type="textarea"
                  rows={4}
                  class="w-full"
                  modelValue={description.value}
                  {...{
                    "onUpdate:modelValue": (v: string) =>
                      (description.value = v)
                  }}
                />
              </div>
            </>
          )
        }}
      />
    );
  }
});

/* ---------- 用一个已有的关系连接这个类 ---------- */

export const ConnectDialog = defineComponent({
  name: "ConnectDialog",
  props: {
    kbId: { type: String, required: true },
    cls: { type: Object as PropType<EntityTypeView>, required: true },
    /** kind === "relation" 的那些 */
    relations: { type: Array as PropType<RelationTypeView[]>, required: true }
  },
  emits: {
    close: () => true,
    /** 连完带着那条关系的 id：调用方跳过去，域/值域改没改一眼可见 */
    connected: (_relationId: string) => true
  },
  setup(props, { emit }) {
    const relationId = ref("");
    const side = ref<"domain" | "range">("domain");
    const busy = ref(false);
    async function connect() {
      busy.value = true;
      try {
        const rel = props.relations.find(r => r.id === relationId.value);
        if (!rel) throw new Error("relation not found");
        // 只加不减：已经连着的那一侧原样保留，另一侧才可能被追加。
        // Set 去重——挑到已经连过的关系时这是个无害的空操作
        const domains =
          side.value === "domain"
            ? [...new Set([...rel.domains, props.cls.id])]
            : rel.domains;
        const ranges =
          side.value === "range"
            ? [...new Set([...rel.ranges, props.cls.id])]
            : rel.ranges;
        await updateRelationType(props.kbId, rel.id, {
          label: rel.label,
          temporal: rel.temporal,
          functional: rel.functional,
          inverse_functional: rel.inverse_functional,
          is_transitive: rel.is_transitive,
          is_symmetric: rel.is_symmetric,
          is_asymmetric: rel.is_asymmetric,
          is_irreflexive: rel.is_irreflexive,
          inverse_of: rel.inverse_of,
          sub_property_of: rel.sub_property_of,
          description: rel.description,
          domains,
          ranges
        });
        toast.success(
          S.ontology.schemaConnected(
            props.relations.find(r => r.id === relationId.value)?.label ?? ""
          )
        );
        emit("connected", relationId.value);
      } catch (e) {
        toast.error(e instanceof Error ? e.message : String(e));
      }
      busy.value = false;
    }
    return () => (
      <UFormDialog
        width="sm"
        title={S.ontology.connectTitle}
        description={S.ontology.schemaConnectHint}
        closeLabel={S.ui.close}
        saveLabel={S.ontology.schemaConnect}
        cancelLabel={S.ontology.cancel}
        canSave={!!relationId.value}
        busy={busy.value}
        onSave={connect}
        onCancel={() => emit("close")}
        v-slots={{
          default: () => (
            <>
              <div class="mb-3">
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.tabProperties}
                </div>
                <el-select
                  filterable
                  class="w-full"
                  placeholder={S.ontology.schemaConnectPlaceholder}
                  modelValue={relationId.value}
                  {...{
                    "onUpdate:modelValue": (v: string) =>
                      (relationId.value = v)
                  }}
                >
                  {props.relations.map(r => (
                    <el-option key={r.id} value={r.id} label={r.label} />
                  ))}
                </el-select>
              </div>
              <div>
                <div class="mb-1 text-sub font-medium text-ink-2">
                  {S.ontology.schemaConnectAs} {props.cls.label}
                </div>
                <USegmented
                  size="sm"
                  value={side.value}
                  options={[
                    { value: "domain", label: S.ontology.domainLabel },
                    { value: "range", label: S.ontology.rangeLabel }
                  ]}
                  onChange={(v: string) => (side.value = v as "domain" | "range")}
                />
              </div>
            </>
          )
        }}
      />
    );
  }
});
