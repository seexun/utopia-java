/* 业务规则面板（对照旧仓 pages/RulesPanel.tsx，W1 读面）：规则表（判据/结论/
   推出几条/开着没有）+ 依赖对话框 + 命中与定义史对话框。建/改/删/跑属任务 6。
   操作数与依赖分析在 domain（ruleDependencies / parseOperand 随迁件）。 */
import { computed, defineComponent, ref, type PropType } from "vue";
import { useQuery } from "@tanstack/vue-query";
import PlayIcon from "~icons/lucide/play";
import { rules as rulesApi, ruleMatches as ruleMatchesApi, ruleVersions as ruleVersionsApi } from "@/api/knowledge/ontology";
import type {
  BusinessRule,
  EntityTypeView,
  RelationTypeView,
  RuleMatch
} from "@/api/knowledge/types";
import { S } from "@/i18n";
import { ruleDependencies } from "@/domain/pages/ruleDependencies";
import { expressionText } from "@/domain/pages/ruleExpressions";
import {
  UChip as Chip,
  GroupLabel,
  LinkButton,
  Loading
} from "@/components/knowledge/kit";

/** 条件的操作数 → 文本（旧仓 operandText 逐字） */
function operandText(
  op: string,
  operand: unknown,
  attributes: RelationTypeView[] = []
): string {
  if (operand && typeof operand === "object" && !Array.isArray(operand)) {
    return expressionText(operand, attributes, S.ontology.ruleUnknownExpression);
  }
  const OPS: Record<string, "num" | "range" | "set" | "none"> = {
    gt: "num",
    gte: "num",
    lt: "num",
    lte: "num",
    between: "range",
    in: "set",
    not_in: "set",
    present: "none"
  };
  const kind = OPS[op] ?? "num";
  if (kind === "none") return "";
  if (kind === "set") return Array.isArray(operand) ? operand.join(", ") : "";
  if (kind === "range") return Array.isArray(operand) ? operand.join(" - ") : "";
  return operand === null || operand === undefined ? "" : String(operand);
}

/** 规则操作动词（op → 那句话里的动词；与 domain parseOperand 的 OPS 同表） */
function opLabel(op: string): string {
  const map: Record<string, () => string> = {
    gt: () => S.ontology.ruleOpGt,
    gte: () => S.ontology.ruleOpGte,
    lt: () => S.ontology.ruleOpLt,
    lte: () => S.ontology.ruleOpLte,
    between: () => S.ontology.ruleOpBetween,
    in: () => S.ontology.ruleOpIn,
    not_in: () => S.ontology.ruleOpNotIn,
    present: () => S.ontology.ruleOpPresent
  };
  return map[op]?.() ?? op;
}

/** 条件按组切开，组序升序（0029）。没带组号的当第 0 组 */
function byGroup<T extends { group?: number }>(conditions: T[]): T[][] {
  const groups = new Map<number, T[]>();
  for (const c of conditions) {
    const g = c.group ?? 0;
    const list = groups.get(g);
    if (list) list.push(c);
    else groups.set(g, [c]);
  }
  return [...groups.entries()].sort((a, b) => a[0] - b[0]).map(([, v]) => v);
}

/** 判据。这一格就是规则的全部语义（旧仓 RuleCriterion 逐字） */
const RuleCriterion = defineComponent({
  name: "RuleCriterion",
  props: {
    rule: { type: Object as PropType<BusinessRule>, required: true },
    attributes: { type: Array as PropType<RelationTypeView[]>, required: true }
  },
  setup(props) {
    return () => (
      <div class="space-y-1">
        {byGroup(props.rule.conditions as BusinessRule["conditions"]).map(
          (group, gi) =>
          group.map((c, i) => (
            <div key={`${gi}-${i}`} class="flex items-baseline gap-2">
              <span class="w-6 shrink-0 text-right text-aux text-ink">
                {gi > 0 && i === 0 ? S.ontology.ruleOr : ""}
              </span>
              <span class="text-sub text-ink-2">
                <span class="mr-1 tabular-nums text-aux text-ink-2">
                  {c.side === "y" ? S.ontology.ruleSideY : S.ontology.ruleSideX}
                </span>{" "}
                <span class="text-ink">{c.predicate_label}</span>{" "}
                {opLabel(c.op)}{" "}
                <span class="tabular-nums text-ink">
                  {operandText(c.op, c.operand, props.attributes)}
                </span>
              </span>
            </div>
          ))
        )}
      </div>
    );
  }
});

/** 结论（旧仓 RuleConclusion 逐字） */
const RuleConclusion = defineComponent({
  name: "RuleConclusion",
  props: {
    rule: { type: Object as PropType<BusinessRule>, required: true },
    attributes: { type: Array as PropType<RelationTypeView[]>, required: true }
  },
  setup(props) {
    return () => {
      const r = props.rule;
      if (r.conclusion === "typing") return <>{r.conclude_type_label}</>;
      if (r.conclusion === "computed")
        return (
          <>
            {r.conclude_predicate_label} ={" "}
            {expressionText(
              r.conclude_expr,
              props.attributes,
              S.ontology.ruleUnknownExpression
            )}
          </>
        );
      if (r.conclusion === "attribute")
        return (
          <>
            {r.conclude_predicate_label} = {JSON.stringify(r.conclude_value)}
          </>
        );
      if (r.conclusion === "relation")
        return (
          <>
            {S.ontology.ruleConcludesRelationText(
              r.join_predicate_label ?? r.join_predicate_id ?? "",
              r.conclude_predicate_label ?? r.conclude_predicate_id ?? ""
            )}
          </>
        );
      return <>{S.ontology.ruleUnknownExpression}</>;
    };
  }
});

/** 命中列表（这一条此刻推出了哪些结论）：一行 = 实体 + 结论一句话 */
const Matches = defineComponent({
  name: "RuleMatches",
  props: {
    kbId: { type: String, required: true },
    ruleId: { type: String, required: true }
  },
  setup(props) {
    const q = useQuery({
      queryKey: computed(() => ["ruleMatches", props.kbId, props.ruleId]),
      queryFn: () => ruleMatchesApi(props.kbId, props.ruleId)
    });
    return () => {
      const matches: RuleMatch[] = q.data.value?.matches ?? [];
      if (q.isLoading.value) return <Loading />;
      return (
        <div class="space-y-1">
          {matches.map((m, i) => (
            <div key={m.derived_id ?? i} class="text-sub text-ink-2">
              <span class="text-ink">{m.entity}</span>
              {m.concluded && ` · ${m.concluded}`}
            </div>
          ))}
          {matches.length === 0 && (
            <p class="text-sub text-ink-2">{S.ontology.rulesNoMatch}</p>
          )}
        </div>
      );
    };
  }
});

export const RulesPanel = defineComponent({
  name: "RulesPanel",
  props: {
    kbId: { type: String, required: true },
    /** 从模式图上点一条规则边过来时，那一行点亮 */
    focusId: { type: String, default: undefined },
    classes: { type: Array as PropType<EntityTypeView[]>, required: true },
    attributes: { type: Array as PropType<RelationTypeView[]>, required: true },
    relations: { type: Array as PropType<RelationTypeView[]>, required: true }
  },
  setup(props) {
    const rulesQ = useQuery({
      queryKey: computed(() => ["rules", props.kbId]),
      queryFn: () => rulesApi(props.kbId)
    });
    const filter = ref("");
    /** 展开了哪条规则的命中列表。一次只展开一条 */
    const opened = ref<string | null>(null);
    /** 依赖对话框看的是哪一条 */
    const dependenciesOf = ref<string | null>(null);
    const historyOf = ref<string | null>(null);

    const all = computed(() => rulesQ.data.value?.rules ?? []);
    const dependencies = computed(() =>
      ruleDependencies(all.value, props.classes, props.attributes)
    );
    const inspecting = computed(
      () => all.value.find(r => r.id === dependenciesOf.value) ?? null
    );
    const needle = computed(() => filter.value.trim().toLowerCase());
    const list = computed(() =>
      needle.value
        ? all.value.filter(r =>
            [
              r.name,
              r.description ?? "",
              r.subject_label,
              r.conclude_type_label ?? "",
              r.conclude_predicate_label ?? ""
            ]
              .join(" ")
              .toLowerCase()
              .includes(needle.value)
          )
        : all.value
    );
    const opening = computed(
      () => list.value.find(r => r.id === opened.value) ?? null
    );
    const historyVersions = useQuery({
      queryKey: computed(() => ["ruleVersions", props.kbId, historyOf.value]),
      queryFn: () => ruleVersionsApi(props.kbId, historyOf.value!),
      enabled: computed(() => !!historyOf.value)
    });

    return () => (
      <div class="space-y-4">
        <div class="mb-2">
          <div class="flex items-baseline justify-between gap-4">
            <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
              {S.ontology.rulesTitle}
            </h1>
          </div>
          <p class="mt-1 text-sub text-ink-2">{S.ontology.rulesHint}</p>
        </div>

        {/* 搜索与两个动作同一行（W1 只留搜索；跑一遍/新建属任务 6） */}
        <div class="flex flex-wrap items-center gap-2">
          <el-input
            v-model={filter.value}
            class="w-64"
            placeholder={S.ontology.ruleSearch}
          />
          <div class="ml-auto flex items-center gap-2 text-ink-2">
            <PlayIcon width="12" height="12" />
            <span class="text-sub">{all.value.length}</span>
          </div>
        </div>

        {!list.value.length ? (
          <p class="text-sub text-ink-2">
            {needle.value ? S.ontology.rulesNoMatch : S.ontology.rulesEmpty}
          </p>
        ) : (
          /* 一张表，不是一条一张卡片。第一列仍然是那句话 */
          <div class="overflow-hidden rounded-card border border-line bg-card">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">{S.ontology.ruleColRule}</th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.ruleConditions}
                  </th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.ruleConcludes}
                  </th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.ruleColDerived}
                  </th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.ruleColStatus}
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.value.map(r => (
                  <tr
                    key={r.id}
                    id={`rule-${props.kbId}-${r.id}`}
                    class={[
                      "border-b border-line last:border-0",
                      !r.enabled && "opacity-55",
                      (r.id === props.focusId || r.id === dependenciesOf.value) &&
                        "bg-fill"
                    ]}
                  >
                    <td class="px-4 py-3">
                      <div class="text-body text-ink">{r.name}</div>
                      <LinkButton
                        onClick={() => (dependenciesOf.value = r.id)}
                      >
                        {S.ontology.ruleDependencies}
                      </LinkButton>
                      <LinkButton
                        class="ml-2 tabular-nums"
                        onClick={() => (historyOf.value = r.id)}
                      >
                        {S.ontology.ruleVersion(r.version ?? 1)}
                      </LinkButton>
                      {r.description && (
                        <div class="text-aux text-ink-2">{r.description}</div>
                      )}
                    </td>
                    <td class="px-4 py-3">
                      <RuleCriterion rule={r} attributes={props.attributes} />
                    </td>
                    <td class="max-w-96 break-words px-4 py-3 text-sub text-ink">
                      <span class="text-ink-2">{r.subject_label} → </span>
                      <RuleConclusion rule={r} attributes={props.attributes} />
                    </td>
                    <td class="whitespace-nowrap px-4 py-3">
                      {r.derived_count > 0 ? (
                        <LinkButton
                          class="tabular-nums"
                          onClick={() => (opened.value = r.id)}
                        >
                          {S.ontology.ruleDerivedCount(r.derived_count)}
                        </LinkButton>
                      ) : (
                        <span class="text-sub text-ink-2">—</span>
                      )}
                      {r.capped > 0 && (
                        <Chip tone="warn" class="ml-2" title={S.ontology.ruleCappedHint}>
                          {S.ontology.ruleCappedChip}
                        </Chip>
                      )}
                    </td>
                    <td class="px-4 py-3">
                      <Chip tone={r.enabled ? "success" : "neutral"}>
                        {r.enabled
                          ? S.ontology.ruleEnabled
                          : S.ontology.ruleDisabled}
                      </Chip>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* 依赖：谁推出它、它推出谁 */}
        <el-dialog
          model-value={!!inspecting.value}
          title={inspecting.value?.name ?? ""}
          onUpdate:modelValue={(v: boolean) => !v && (dependenciesOf.value = null)}
          width="560px"
        >
          {inspecting.value && (
            <div class="space-y-4">
              {dependencies.value.incomplete && (
                <p class="text-sub text-warning">
                  {S.ontology.ruleDependenciesIncomplete}
                </p>
              )}
              <div>
                <GroupLabel class="mb-2">
                  {S.ontology.rulePotentialProducers}
                </GroupLabel>
                {all.value
                  .filter(r =>
                    dependencies.value.links
                      .get(inspecting.value!.id)
                      ?.producers.has(r.id)
                  )
                  .map(r => (
                    <div key={r.id} class="text-body text-ink-2">
                      <LinkButton onClick={() => (dependenciesOf.value = r.id)}>
                        {r.name}
                      </LinkButton>
                    </div>
                  ))}
              </div>
              <div>
                <GroupLabel class="mb-2">
                  {S.ontology.rulePotentialConsumers}
                </GroupLabel>
                {all.value
                  .filter(r =>
                    dependencies.value.links
                      .get(inspecting.value!.id)
                      ?.consumers.has(r.id)
                  )
                  .map(r => (
                    <div key={r.id} class="text-body text-ink-2">
                      <LinkButton onClick={() => (dependenciesOf.value = r.id)}>
                        {r.name}
                      </LinkButton>
                    </div>
                  ))}
              </div>
            </div>
          )}
        </el-dialog>

        {/* 命中：这一条此刻推出了哪些结论 */}
        <el-dialog
          model-value={!!opening.value}
          title={opening.value?.name ?? ""}
          onUpdate:modelValue={(v: boolean) => !v && (opened.value = null)}
          width="560px"
        >
          {opening.value && (
            <Matches kbId={props.kbId} ruleId={opening.value.id} />
          )}
        </el-dialog>

        {/* 定义史：这一条改过几次、每一版怎么说 */}
        <el-dialog
          model-value={!!historyOf.value}
          title={
            list.value.find(r => r.id === historyOf.value)?.name ?? ""
          }
          onUpdate:modelValue={(v: boolean) => !v && (historyOf.value = null)}
          width="560px"
        >
          {historyOf.value && (
            <div class="space-y-3">
              {(historyVersions.data.value?.versions ?? []).map(v => (
                <div key={v.id} class="rounded-card border border-line p-3">
                  <GroupLabel class="mb-2" count={v.seq}>
                    {S.ontology.ruleVersion(v.seq)}
                  </GroupLabel>
                  <div class="mt-1">
                    <RuleCriterion
                      rule={
                        {
                          ...v.definition,
                          conditions: (v.definition.conditions ?? []).map(
                            c => ({
                              ...c
                            })
                          )
                        } as unknown as BusinessRule
                      }
                      attributes={props.attributes}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </el-dialog>
      </div>
    );
  }
});


