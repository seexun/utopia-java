/* 本体页左栏底部那几个入口的读面（对照旧仓 Ontology.tsx 的面板段）：
   唯一性候选（读）、回归用例（读）、未匹配信号 + 提案（读）。
   采纳/拒绝/提问代理等写动作属任务 6。 */
import { computed, defineComponent, ref, type PropType } from "vue";
import { useQuery } from "@tanstack/vue-query";
import RefreshCwIcon from "~icons/lucide/refresh-cw";
import {
  questionReport,
  storedProposals,
  ontologyRegressions
} from "@/api/knowledge/ontology";
import type {
  UniquenessCandidate,
  RelationTypeView,
  OntologyRegressionCase,
  OntologyProposals
} from "@/api/knowledge/types";
import { S } from "@/i18n";
import { fmtObjectValue } from "@/domain/objectValue";
import { localDateTime } from "@/domain/ui";
import { UChip, UStatus, LinkButton, Loading } from "@/components/knowledge/kit";

/* ---------- 唯一性候选（#341 读面） ---------- */

export const UniquenessPanel = defineComponent({
  name: "UniquenessPanel",
  props: {
    kbId: { type: String, required: true },
    candidates: {
      type: Array as PropType<UniquenessCandidate[]>,
      required: true
    },
    relations: {
      type: Array as PropType<RelationTypeView[]>,
      required: true
    },
    pending: { type: Boolean, default: false }
  },
  setup(props) {
    return () => (
      <div class="space-y-4">
        <div class="mb-2">
          <div class="flex items-baseline justify-between gap-4">
            <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
              {S.ontology.uniqueness}
            </h1>
          </div>
          <p class="mt-1 text-sub text-ink-2">{S.ontology.uniquenessHint}</p>
        </div>
        {props.pending ? (
          <Loading />
        ) : props.candidates.length === 0 ? (
          <p class="text-sub text-ink-2">{S.ontology.uniquenessEmpty}</p>
        ) : (
          <div class="space-y-2">
            {props.candidates.map(c => (
              <div
                key={`${c.predicate_id}-${c.side}`}
                class="rounded-card border border-line bg-card px-3 py-3"
              >
                <div class="flex items-center gap-2">
                  <span class="text-body text-ink">{c.label}</span>
                  <span class="font-mono text-aux text-ink-2">{c.key}</span>
                  {c.declared && <UChip>{S.ontology.uniquenessDeclared}</UChip>}
                  {/* 声明/对账按钮属任务 6；这里只留状态读面 */}
                </div>
                <p class="mt-1 text-sub text-ink-2">
                  {c.side === "subject"
                    ? S.ontology.uniquenessSubject(c.holders)
                    : S.ontology.uniquenessObject(c.holders)}
                  {" · "}
                  {S.ontology.uniquenessEffect(c.would_close, c.would_review)}
                </p>
                {/* 证据：谁挂着哪些值。这是人做判断的依据，不是装饰 */}
                {c.examples.length > 0 && (
                  <div class="mt-2 space-y-1 border-t border-line pt-2">
                    {c.examples.slice(0, 3).map(ex => (
                      <div
                        key={ex.holder}
                        class="flex items-baseline gap-2 text-sub"
                      >
                        <span class="shrink-0 text-ink-2">{ex.holder}</span>
                        <span class="flex flex-wrap gap-x-3 gap-y-1 text-ink-2">
                          {ex.values.map(v => (
                            <span key={v.fact_id}>
                              {v.name ?? "—"}
                              {v.valid_from && (
                                <span class="ml-1 tabular-nums text-ink-2">
                                  {S.ontology.uniquenessSince(
                                    v.valid_from.slice(0, 10)
                                  )}
                                </span>
                              )}
                            </span>
                          ))}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }
});

/* ---------- 回归用例（读面） ---------- */

const directionText = (direction: "forward" | "reverse") =>
  direction === "reverse" ? S.ontology.caseReverse : S.ontology.caseForward;

const CaseRow = defineComponent({
  name: "OntologyCaseRow",
  props: {
    item: { type: Object as PropType<OntologyRegressionCase>, required: true }
  },
  setup(props) {
    const item = props.item;
    const open = ref(false);
    return () => {
      const result = item.last_result;
      return (
        <>
          <tr class="border-b border-line">
            <td class="px-4 py-3">
              <div class="flex items-start gap-2">
                <button
                  type="button"
                  class="mt-0.5 rounded-ctl p-1 text-ink-2 hover:bg-fill"
                  title={
                    open.value
                      ? S.ontology.caseHideEvidence
                      : S.ontology.caseEvidence
                  }
                  onClick={() => (open.value = !open.value)}
                >
                  <RefreshCwIcon
                    width="14"
                    height="14"
                    class={[
                      "transition-transform",
                      open.value ? "rotate-90" : ""
                    ]}
                  />
                </button>
                <div class="min-w-0 break-words">
                  <span>{item.subject_label}</span>{" "}
                  <span class="text-ink-2">{item.phrase}</span>{" "}
                  <span>
                    {item.object_label ??
                      fmtObjectValue(item.object_value) ??
                      "—"}
                  </span>
                </div>
              </div>
            </td>
            <td class="px-4 py-3">
              <div>{item.expected_property_label}</div>
              <div class="text-aux text-ink-2">
                {directionText(item.expected_direction)}
              </div>
            </td>
            <td class="px-4 py-3">
              <div>
                {item.actual_property_label ??
                  (result?.status === "bound"
                    ? S.ontology.caseUnavailableProperty
                    : result?.status === "none"
                      ? S.ontology.caseUnbound
                      : result
                        ? S.ontology.caseUndecided
                        : "—")}
              </div>
              {result?.actual_direction && (
                <div class="text-aux text-ink-2">
                  {directionText(result.actual_direction)}
                </div>
              )}
              {result?.decided_at && (
                <div class="mt-1 text-aux text-ink-2">
                  {S.ontology.caseDecidedAt}:{" "}
                  <span class="tabular-nums">
                    {localDateTime(result.decided_at)}
                  </span>
                </div>
              )}
            </td>
            <td class="px-4 py-3">
              {result ? (
                <UStatus tone={result.passed ? "success" : "warn"}>
                  {result.passed
                    ? result.human_bound
                      ? S.ontology.caseHumanBound
                      : S.ontology.casePassed
                    : S.ontology.caseMismatch}
                </UStatus>
              ) : (
                <span class="text-sub text-ink-2">{S.ontology.caseNotChecked}</span>
              )}
            </td>
            <td class="px-4 py-3 tabular-nums text-sub text-ink-2">
              {item.last_checked_at
                ? localDateTime(item.last_checked_at)
                : "—"}
            </td>
            <td class="px-4 py-3 text-sub text-ink-2">
              {item.origin === "person"
                ? S.ontology.casePerson
                : S.ontology.caseAdoption}
            </td>
          </tr>
          {open.value && (
            <tr class="border-b border-line bg-fill">
              <td colspan="6" class="px-4 py-3">
                <div class="space-y-1 text-sub text-ink-2">
                  {result?.human_bound && (
                    <div>{S.ontology.caseHumanHint}</div>
                  )}
                  <div>
                    {item.created_by_label ?? S.ontology.caseUnknownCreator} ·{" "}
                    <span class="tabular-nums">
                      {item.created_at.slice(0, 10)}
                    </span>
                  </div>
                </div>
              </td>
            </tr>
          )}
        </>
      );
    };
  }
});

export const OntologyRegressionsPanel = defineComponent({
  name: "OntologyRegressionsPanel",
  props: { kbId: { type: String, required: true } },
  setup(props) {
    const cases = useQuery({
      queryKey: computed(() => ["ontologyRegressions", props.kbId]),
      queryFn: () => ontologyRegressions(props.kbId)
    });
    return () => (
      <div>
        <div class="mb-6">
          <div class="flex items-baseline justify-between gap-4">
            <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
              {S.ontology.casesTitle}
            </h1>
            <LinkButton onClick={() => cases.refetch()}>
              <RefreshCwIcon width="14" height="14" class="mr-1 inline" />
              {S.nav.refresh}
            </LinkButton>
          </div>
          <p class="mt-1 text-sub text-ink-2">{S.ontology.casesHint}</p>
        </div>
        {cases.isError.value ? (
          <p role="alert" class="text-body text-danger">
            {(cases.error.value as Error).message}
          </p>
        ) : cases.isPending.value ? (
          <Loading />
        ) : cases.data.value!.cases.length === 0 ? (
          <p class="text-body text-ink-2">{S.ontology.casesEmpty}</p>
        ) : (
          <div class="overflow-hidden rounded-card border border-line bg-card">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.caseStatement}
                  </th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.caseExpected}
                  </th>
                  <th class="px-4 py-3 font-medium">{S.ontology.caseActual}</th>
                  <th class="px-4 py-3 font-medium">{S.ontology.caseResult}</th>
                  <th class="px-4 py-3 font-medium">
                    {S.ontology.caseChecked}
                  </th>
                  <th class="px-4 py-3 font-medium">{S.ontology.caseOrigin}</th>
                </tr>
              </thead>
              <tbody>
                {cases.data.value!.cases.map(item => (
                  <CaseRow key={item.id} item={item} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  }
});

/* ---------- 未匹配信号 + 提案（读面） ---------- */

function proposalCount(d: OntologyProposals | null | undefined): number {
  if (!d) return 0;
  return (
    (d.map_to?.length ?? 0) +
    d.entity_types.length +
    d.relation_types.length +
    (d.attribute_types?.length ?? 0)
  );
}

export const MissesPanel = defineComponent({
  name: "MissesPanel",
  props: { kbId: { type: String, required: true } },
  setup(props) {
    // 文档说了、本体还放不下的有多少（代理读的那两样）
    const report = useQuery({
      queryKey: computed(() => ["questionReport", props.kbId]),
      queryFn: () => questionReport(props.kbId)
    });
    // 还没人表态的提案存在库里（0049）
    const proposalsQ = useQuery({
      queryKey: computed(() => ["storedProposals", props.kbId]),
      queryFn: () => storedProposals(props.kbId)
    });
    return () => {
      const uncovered = report.data.value?.uncovered;
      const proposals = proposalsQ.data.value;
      return (
        <div>
          <div class="mb-6">
            <div class="flex items-baseline justify-between gap-4">
              <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
                {S.ontology.misses}
              </h1>
            </div>
            <p class="mt-1 text-sub text-ink-2">{S.ontology.missesHint}</p>
          </div>

          {/* 两个数还没取回来时不说话：先说一句「没有在等的」再改口，比空着更糟 */}
          {uncovered && (
            <p class="text-body text-ink-2">
              {uncovered.kind_words + uncovered.phrases > 0
                ? S.ontology.uncovered(uncovered.kind_words, uncovered.phrases)
                : S.ontology.noMisses}
            </p>
          )}

          {proposals && (
            <div class="mt-4 border-t border-line pt-3">
              <h4 class="mb-2 text-sub font-semibold text-ink-2">
                {S.ontology.proposals}
              </h4>
              <div class="space-y-2">
                {/* 排在最前：它说的是"本体已经有了"，而这正是最该先看见的一句 */}
                {(proposals.map_to ?? []).map(
                  (p: NonNullable<OntologyProposals["map_to"]>[number]) => (
                    <div key={`map-${p.key}`} class="flex items-center gap-2 text-body">
                      <UChip tone="success">=</UChip>
                      <span class="font-mono text-ink-2">{p.key}</span>
                      {p.label && <span class="text-ink">{p.label}</span>}
                      {!!p.forms?.length && (
                        <span
                          class="truncate text-sub text-ink-2"
                          title={p.forms.join(" · ")}
                        >
                          {p.forms.join(" · ")}
                        </span>
                      )}
                      {p.reason && (
                        <span class="truncate text-sub text-ink-2">
                          {p.reason}
                        </span>
                      )}
                    </div>
                  )
                )}
                {proposals.entity_types.map(p => (
                  <div key={p.key} class="flex items-center gap-2 text-body">
                    <UChip tone="info">C</UChip>
                    <span class="font-mono text-ink-2">{p.key}</span>
                    <span class="text-ink">{p.label}</span>
                    {p.reason && (
                      <span class="truncate text-sub text-ink-2">{p.reason}</span>
                    )}
                  </div>
                ))}
                {proposals.relation_types.map(p => (
                  <div key={p.key} class="flex items-center gap-2 text-body">
                    <UChip tone="violet">P</UChip>
                    <span class="font-mono text-ink-2">{p.key}</span>
                    <span class="text-ink">{p.label}</span>
                    {p.temporal && <UChip>{p.temporal}</UChip>}
                    {p.reason && (
                      <span class="truncate text-sub text-ink-2">{p.reason}</span>
                    )}
                  </div>
                ))}
                {(proposals.attribute_types ?? []).map(p => (
                  <div key={`attr-${p.key}`} class="flex items-center gap-2 text-body">
                    {/* A 而不是 P：字面值那一档跟关系是两回事，界面上分得清 */}
                    <UChip tone="warn">A</UChip>
                    <span class="font-mono text-ink-2">{p.key}</span>
                    <span class="text-ink">{p.label}</span>
                    <UChip>{p.datatype ?? "text"}</UChip>
                    {p.unit && <UChip>{p.unit}</UChip>}
                    {p.reason && (
                      <span class="truncate text-sub text-ink-2">{p.reason}</span>
                    )}
                  </div>
                ))}
                {proposalCount(proposals) === 0 && (
                  <p class="text-body text-ink-2">—</p>
                )}
              </div>
            </div>
          )}
        </div>
      );
    };
  }
});
