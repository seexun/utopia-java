/* 审核队列各档的行（对照旧仓 Review.tsx / PendingFacts.tsx 的行组件，W2 写面全量
   迁入）：每档的裁决按钮组、理由框（0026）、批量勾选、agent 建议回答、闭合日期、
   对齐绑定表单。类型按档收窄；写按钮显隐由 canDecide（editor+，与服务端
   require_kb(Role::Editor) 同口径）门控——403 兜底在 client 层。
   reskin-b4 样式层（数据/接线零动）：行卡统一 hover neutral-soft（bg-fill 同源
   令牌，URow 行 hover 同档反馈），置信度/来源（谁说的、勘误的文档）升为带
   --cat-N 色点的 badge-soft 徽章（检索页来源色点同款取法，键稳定同色）。 */
import { computed, defineComponent, ref, type PropType } from "vue";
import type {
  AlignmentMarks,
  PendingFactItem,
  ReviewItem,
  FactReviewItem,
  ConflictItem,
  AxiomViolation,
  OntologyDefect,
  AlignmentItem,
  ErrataItem,
  MergeLog,
  AgentDecision,
  AgentPrecedent,
  ReviewHistoryEvent,
  EntityTypeView,
  RelationTypeView
} from "@/api/knowledge/types";
import { S } from "@/i18n";
import { fmtObjectValue } from "@/domain/objectValue";
import { parseDateInput } from "@/domain/time";
import { asksMarks, phraseStart } from "@/domain/pages/alignmentPhrase";
import {
  UChip,
  UStatus,
  LinkButton,
  USegmented
} from "@/components/knowledge/kit";

/** 卡片页脚（旧仓 CARD_ACTIONS 逐字）：动作在左，「谁说的」推到右边 */
const CARD_ACTIONS = "mt-3 flex flex-wrap items-center gap-2";

/** 行卡皮（reskin-b4）：卡容器 + 行 hover neutral-soft。内距三档（p-4 大卡 /
    p-3 密卡 / px-4 py-3 紧凑行）由各行列自行叠加 */
const ROW_CARD =
  "rounded-card border border-line bg-card transition-colors hover:bg-fill";

/** 来源/置信度徽章的 cat 色点（reskin-b4，纯展示）：--cat-1..6 按行键稳定
    轮转——同一来源/同一行跨重渲染、翻页不换色（检索页 srcCat 同款取法） */
function catDot(key: string) {
  let h = 0;
  for (const ch of key) h = (h * 31 + (ch.codePointAt(0) ?? 0)) >>> 0;
  return (
    <span
      class="h-1.5 w-1.5 shrink-0 rounded-full"
      style={{ background: `var(--cat-${(h % 6) + 1})` }}
    />
  );
}

const ym = (iso: string | null) => (iso ? iso.slice(0, 7) : null);

function dateRange(from: string | null, to: string | null): string | null {
  if (!from && !to) return null;
  return `${ym(from) ?? "…"} → ${ym(to) ?? S.review.ongoing}`;
}

/** `code` 或 `code|detail`。查不到就原样显示——存量行里还是旧的英文散文 */
export function escalationText(reason: string): string {
  const [code, detail] = reason.split("|");
  const worded = S.review.escalated[code as keyof typeof S.review.escalated];
  if (!worded) return reason;
  // 执行闸门（0027）留下的：detail 是 `kind value`，按 kind 措辞
  return detail ? S.errDetail(worded, detail) : worded;
}

function SideCard(props: { side: ReviewItem["left"] }) {
  const side = props.side;
  return (
    <div class="min-w-0 flex-1">
      {/* 只写名字，不画那颗色点：卡上两个实体，类名下一行白纸黑字写着 */}
      <div class="mb-1 flex items-center gap-2">
        <span class="truncate text-body font-medium text-ink">{side.name}</span>
        {side.disambiguator && (
          <span class="truncate text-sub text-ink-2">
            · {side.disambiguator}
          </span>
        )}
      </div>
      <div class="mb-2 text-sub text-ink-2">
        {(side.type_label ?? S.graph.untyped) +
          " · " +
          S.review.factsCount(side.degree)}
      </div>
      {side.top_facts.length > 0 ? (
        <ul class="space-y-1">
          {side.top_facts.map((f, i) => (
            <li key={i} class="truncate text-sub text-ink-2">
              {f}
            </li>
          ))}
        </ul>
      ) : (
        <p class="text-sub text-ink-2">{S.review.noFacts}</p>
      )}
    </div>
  );
}

/** 两边都有类型且不一样：同名异义，合了就错 */
function typesDiffer(item: ReviewItem): boolean {
  return (
    item.left.type_label !== null &&
    item.right.type_label !== null &&
    item.left.type_label !== item.right.type_label
  );
}

/** 重复项卡：状态/类型冲突/agent 建议/相似度/两边的事实 + 裁决页脚。
 *  批量勾选（#428）勾在卡片左上；理由框（0026）可不写，写了跟决定进台账。 */
export const DuplicateCard = defineComponent({
  name: "DuplicateCard",
  props: {
    item: { type: Object as PropType<ReviewItem>, required: true },
    busy: { type: Boolean, default: false },
    /** agent 正在裁这一对（0025）：这几分钟里人不能动它，接口也会拒绝 */
    locked: { type: Boolean, default: false },
    /** 批量选中（#428） */
    picked: { type: Boolean, default: false },
    /** 点头是写图的动作：Viewer 看得见提议、看不见按钮 */
    canDecide: { type: Boolean, default: false }
  },
  emits: {
    pick: (_picked: boolean) => true,
    decide: (_action: "merge" | "keep", _rationale?: string) => true
  },
  setup(props, { emit }) {
    const item = props.item;
    const reasonCode = item.reason?.split("|", 1)[0];
    const why = ref("");
    return () => (
      <div
        class={[ROW_CARD, "p-4", props.picked && "border-line-strong"]}
      >
        {/* 为什么是这一对，写在两边之前：等谁裁、类型对不对得上、agent 建议什么、凭什么说它们像 */}
        <div class="mb-3 flex flex-wrap items-center gap-3">
          {props.locked ? (
            <UStatus tone="warn" pulse>
              {S.review.agentDeciding}
            </UStatus>
          ) : (
            <UStatus tone={item.stage === "human" ? "warn" : "neutral"}>
              {item.stage === "human"
                ? S.review.stageHuman
                : S.review.stageAdjudicating}
            </UStatus>
          )}
          {typesDiffer(item) && (
            <UChip tone="warn" title={S.review.typesDifferHint}>
              {S.review.typesDiffer(
                item.left.type_label ?? "",
                item.right.type_label ?? ""
              )}
            </UChip>
          )}
          {item.proposal && (
            <UChip tone="info" title={item.proposal.reason ?? undefined}>
              {S.review.agentSuggests(
                S.review.agentActions[item.proposal.action],
                Math.round(item.proposal.confidence * 100)
              )}
            </UChip>
          )}
          {reasonCode !== "namesake" && (
            <span class="text-sub text-ink-2">
              {S.review.similarity(Math.round(item.score * 100))}
            </span>
          )}
          {item.reason && (
            <span class="truncate text-sub text-ink-2">
              {escalationText(item.reason)}
            </span>
          )}
        </div>
        <div class="flex gap-4">
          {props.canDecide && (
            <el-checkbox
              class="shrink-0 self-start"
              modelValue={props.picked}
              disabled={props.busy || props.locked}
              {...{
                "onUpdate:modelValue": (v: boolean) => emit("pick", !!v),
                "aria-label": S.review.pickPair,
                title: S.review.pickPair
              }}
            />
          )}
          <SideCard side={item.left} />
          <div class="shrink-0 self-center text-body text-ink-2">≟</div>
          <SideCard side={item.right} />
        </div>
        {/* 页脚只剩动作，与别的卡同一副（CARD_ACTIONS）：左下，危险的排最后 */}
        <div class={[CARD_ACTIONS, "border-t border-line pt-3"]}>
          {props.canDecide && (
            <>
              {/* 理由框（0026）：可不写；写了就跟着决定进台账，下一次先例带着它 */}
              <el-input
                size="small"
                style="width: 14rem"
                placeholder={S.review.rationalePlaceholder}
                modelValue={why.value}
                disabled={props.busy || props.locked}
                {...{
                  "onUpdate:modelValue": (v: string) => (why.value = v)
                }}
              />
              <el-button
                type="primary"
                size="small"
                disabled={props.busy || props.locked}
                onClick={() => emit("decide", "merge", why.value)}
              >
                {S.review.merge}
              </el-button>
              <el-button
                size="small"
                disabled={props.busy || props.locked}
                onClick={() => emit("decide", "keep", why.value)}
              >
                {S.review.keep}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** 记忆抽出、等人点头的事实（0015）：确认进账本，驳回记进 rejected_facts。
 *  原句在上，三元组在下——只列三元组等于要人凭空判断它对不对。 */
export const PendingFactRow = defineComponent({
  name: "PendingFactRow",
  props: {
    fact: { type: Object as PropType<PendingFactItem>, required: true },
    busy: { type: Boolean, default: false },
    /** 点头是写图的动作，Editor 起步——与服务端 require_kb(Role::Editor) 同一口径 */
    canDecide: { type: Boolean, default: false }
  },
  emits: ["confirm", "reject"],
  setup(props, { emit }) {
    const f = props.fact;
    /** 记忆落库时正文前面带着 `[YYYY-MM-DD HH:MM] `，剥掉 */
    const sentence = (quote: string) =>
      quote.replace(/^\[\d{4}-\d{2}-\d{2}(?: \d{2}:\d{2})?\]\s*/, "");
    const objectText = computed(() => {
      if (f.object_name) return f.object_name;
      return (
        fmtObjectValue(f.object_value as Record<string, unknown> | null) ?? "?"
      );
    });
    return () => {
      const from = ym(f.valid_from);
      const to = ym(f.valid_to);
      // 开放陈述（0044）带的是照抄的时间词，不是算出来的区间：有词就显示词
      const timeWords = (f.time_words ?? []).map(t => t.text).join(" · ");
      const range = timeWords
        ? timeWords
        : from || to
          ? `${from ?? "…"} → ${to ?? S.review.ongoing}`
          : null;
      const qualifiers = f.qualifiers ?? [];
      return (
        <div class={[ROW_CARD, "p-4"]}>
          {/* 原句先出。它是人自己说的，判断的依据就是它 */}
          <p class="text-sub italic text-ink-2">“{sentence(f.quote)}”</p>
          <div class="mt-3 flex flex-wrap items-center gap-2">
            <span class="text-body font-medium text-ink">{f.subject_name}</span>
            <span class="text-sub text-ink-2">
              —{" "}
              {f.predicate_label ? (
                <span>{f.predicate_label}</span>
              ) : f.phrase ? (
                <span
                  class="italic text-ink-2"
                  title={S.review.pendingOwnWords}
                >
                  {f.phrase}
                </span>
              ) : (
                <span
                  class="italic text-ink-2"
                  title={S.review.pendingNoPredicate}
                >
                  {f.proposed_predicate ?? S.graph.unknownPredicate}
                </span>
              )}{" "}
              →
            </span>
            <span class="text-body font-medium text-ink">
              {objectText.value}
            </span>
            {range && <span class="text-sub text-ink-2">({range})</span>}
            {!f.predicate_label && !f.phrase && (
              <UStatus tone="warn" class="ml-auto shrink-0">
                {S.review.pendingNoPredicateChip}
              </UStatus>
            )}
          </div>
          {qualifiers.length > 0 && (
            /* 限定按文档的角色词挂着（0044）：金额、对象、比较基准 */
            <div class="mt-2 flex flex-wrap gap-1">
              {qualifiers.map(q => (
                <span key={q.role} class="badge-soft badge-soft-info">
                  {q.role}: {q.entity_name ?? String(q.value ?? "")}
                </span>
              ))}
            </div>
          )}
          {/* 与其余卡同一副页脚（CARD_ACTIONS）：动作在左，「谁说的」推到右边 */}
          <div class={CARD_ACTIONS}>
            {props.canDecide && (
              <>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("confirm")}
                >
                  {S.review.confirm}
                </el-button>
                <el-button
                  type="danger"
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("reject")}
                >
                  {S.review.reject}
                </el-button>
              </>
            )}
            {/* 「谁说的」= 这条事实的来源（reskin-b4）：cat 色点按提出者稳定轮转 */}
            {f.proposed_by_name && (
              <span class="ml-auto flex min-w-0 shrink-0 items-center gap-1 text-aux text-ink-2">
                {catDot(f.proposed_by_name)}
                <span class="truncate">
                  {f.proposed_token_name
                    ? S.review.pendingSaidVia(
                        f.proposed_by_name,
                        f.proposed_token_name
                      )
                    : S.review.pendingSaidBy(f.proposed_by_name)}
                </span>
              </span>
            )}
          </div>
        </div>
      );
    };
  }
});

/** 事实行（低置信）：确认进账本 / 驳回。置信度是一个素色数字 */
export const FactRow = defineComponent({
  name: "FactRow",
  props: {
    fact: { type: Object as PropType<FactReviewItem>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["confirm", "reject"],
  setup(props, { emit }) {
    const fact = props.fact;
    const range = dateRange(fact.valid_from, fact.valid_to);
    return () => (
      <div class={[ROW_CARD, "p-4"]}>
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-body font-medium text-ink">
            {fact.subject_name}
          </span>
          <span class="text-sub text-ink-2">
            —{" "}
            <span
              class={
                fact.predicate_label === null ? "italic text-ink-2" : undefined
              }
            >
              {fact.predicate_label ?? S.graph.unknownPredicate}
            </span>{" "}
            →
          </span>
          <span class="text-body font-medium text-ink">
            {fact.object_name ?? "?"}
          </span>
          {range && (
            <span class="tabular-nums text-sub text-ink-2">({range})</span>
          )}
          {/* 置信度徽章（reskin-b4）：cat 色点按行 id 稳定轮转 */}
          <span class="badge-soft badge-soft-info ml-auto shrink-0 tabular-nums">
            {catDot(fact.id)}
            {S.review.confidence(Math.round(fact.confidence * 100))}
          </span>
        </div>
        {fact.quote && (
          <p class="mt-2 line-clamp-2 text-sub italic text-ink-2">
            “{fact.quote}”
          </p>
        )}
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-button
                size="small"
                disabled={props.busy}
                onClick={() => emit("confirm")}
              >
                {S.review.confirm}
              </el-button>
              <el-button
                type="danger"
                size="small"
                disabled={props.busy}
                onClick={() => emit("reject")}
              >
                {S.review.reject}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** "文档新版没再提"的事实行：Reject（抽取错误）或 Close at date（这事结束了）。
    不用日期选择器：它逼人给出一个日，而「那年结束的」正是这里常见的答案。 */
export const UnconfirmedRow = defineComponent({
  name: "UnconfirmedRow",
  props: {
    fact: { type: Object as PropType<FactReviewItem>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["reject", "close"],
  setup(props, { emit }) {
    const fact = props.fact;
    const closeAt = ref("");
    const closeParsed = computed(() => parseDateInput(closeAt.value.trim()));
    const range = dateRange(fact.valid_from, fact.valid_to);
    return () => (
      <div class={[ROW_CARD, "p-4"]}>
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-body font-medium text-ink">
            {fact.subject_name}
          </span>
          <span class="text-sub text-ink-2">
            —{" "}
            <span
              class={
                fact.predicate_label === null ? "italic text-ink-2" : undefined
              }
            >
              {fact.predicate_label ?? S.graph.unknownPredicate}
            </span>{" "}
            →
          </span>
          <span class="text-body font-medium text-ink">
            {fact.object_name ?? "?"}
          </span>
          {range && (
            <span class="tabular-nums text-sub text-ink-2">({range})</span>
          )}
        </div>
        {fact.quote && (
          <p class="mt-2 text-sub italic text-ink-2">“{fact.quote}”</p>
        )}
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-input
                size="small"
                class="close-at"
                placeholder={S.review.closeAtPlaceholder}
                modelValue={closeAt.value}
                {...{
                  "onUpdate:modelValue": (v: string) => (closeAt.value = v)
                }}
              />
              <el-button
                size="small"
                disabled={props.busy || !closeParsed.value}
                onClick={() =>
                  closeParsed.value &&
                  emit(
                    "close",
                    closeParsed.value.iso,
                    closeParsed.value.precision
                  )
                }
              >
                {closeAt.value.trim()
                  ? S.review.closeFactAt(closeAt.value.trim())
                  : S.review.closeFact}
              </el-button>
              <el-button
                type="danger"
                size="small"
                disabled={props.busy}
                onClick={() => emit("reject")}
              >
                {S.review.reject}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** 时态冲突行：旧事实 vs 新事实，三个动作（Keep both / Close old at / Reject new） */
export const ConflictRow = defineComponent({
  name: "ConflictRow",
  props: {
    conflict: { type: Object as PropType<ConflictItem>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["resolve"],
  setup(props, { emit }) {
    const c = props.conflict;
    const closeAt = ref("");
    const needsDate = !c.new_valid_from;
    // 写多少位就是多少精度（time.ts）：「2023-06」闭合在那个月，不编一个 1 日
    const closeParsed = computed(() => parseDateInput(closeAt.value.trim()));
    return () => (
      <div class={[ROW_CARD, "p-4"]}>
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-body font-medium text-ink">{c.old_subject}</span>
          <span class="text-sub text-ink-2">— {c.predicate_label} →</span>
          <span class="text-body font-medium text-ink">
            {c.old_object ?? "?"}
          </span>
          {c.old_valid_from && (
            <span class="tabular-nums text-sub text-ink-2">
              ({S.review.conflictSince(c.old_valid_from.slice(0, 10))})
            </span>
          )}
          <span class="text-sub text-ink-2">{S.review.conflictVs}</span>
          <span class="text-body font-medium text-ink">{c.new_subject}</span>
          <span class="text-sub text-ink-2">— {c.predicate_label} →</span>
          <span class="text-body font-medium text-ink">
            {c.new_object ?? "?"}
          </span>
          {c.new_valid_from && (
            <span class="tabular-nums text-sub text-ink-2">
              ({S.review.conflictSince(c.new_valid_from.slice(0, 10))})
            </span>
          )}
          <UStatus tone="warn" class="ml-auto shrink-0">
            {S.review.conflictReason[
              c.reason as keyof typeof S.review.conflictReason
            ] ?? c.reason}
          </UStatus>
        </div>
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-button
                size="small"
                disabled={props.busy}
                onClick={() => emit("resolve", "keep")}
              >
                {S.review.keepBoth}
              </el-button>
              {needsDate && (
                <el-input
                  size="small"
                  class="close-at"
                  placeholder={S.review.closeAtPlaceholder}
                  modelValue={closeAt.value}
                  {...{
                    "onUpdate:modelValue": (v: string) => (closeAt.value = v)
                  }}
                />
              )}
              <el-button
                size="small"
                disabled={props.busy || (needsDate && !closeParsed.value)}
                onClick={() =>
                  emit(
                    "resolve",
                    "close",
                    closeParsed.value?.iso,
                    closeParsed.value?.precision
                  )
                }
              >
                {c.new_valid_from
                  ? S.review.closeOldAt(c.new_valid_from.slice(0, 10))
                  : S.review.closeOld}
              </el-button>
              <el-button
                type="danger"
                size="small"
                disabled={props.busy}
                onClick={() => emit("resolve", "reject_new")}
              >
                {S.review.rejectNew}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** 一处本体缺陷。**两个按钮而不是三个**——这一档压根没看数据，所以没有
 *  「数据错了」这条出路，只能是「我去改了本体」或「先放着」。 */
export const DefectRow = defineComponent({
  name: "DefectRow",
  props: {
    defect: { type: Object as PropType<OntologyDefect>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["decide"],
  setup(props, { emit }) {
    const d = props.defect;
    const what = {
      symmetric_and_asymmetric: S.review.defectSymAsym,
      transitive_and_functional: S.review.defectTransFunc,
      subclass_cycle: S.review.defectCycle,
      disjoint_with_ancestor: S.review.defectDisjointAncestor,
      inherits_disjoint: S.review.defectInheritsDisjoint,
      inverse_of_itself: S.review.defectInverseSelf,
      inverse_not_mutual: S.review.defectInverseNotMutual,
      sub_property_cycle: S.review.defectSubPropertyCycle,
      rules_disagree: S.review.defectRulesDisagree
    }[d.kind];
    const unsatisfiable =
      d.kind === "disjoint_with_ancestor" || d.kind === "inherits_disjoint";
    const rules =
      d.kind === "rules_disagree"
        ? (((d.detail as Record<string, unknown>).rules as unknown[]) ?? [])
        : [];
    return () => (
      <div class={[ROW_CARD, "p-3"]}>
        <div class="flex flex-wrap items-baseline gap-2">
          <span class="text-body text-danger">{what}</span>
          {d.subject_label && (
            <span class="text-sub text-ink-2">{d.subject_label}</span>
          )}
          {d.other_label && (
            <span class="text-sub text-ink-2">↔ {d.other_label}</span>
          )}
        </div>
        {d.path_labels.length > 0 && (
          <div class="mt-1 text-sub text-ink-2">
            {d.path_labels.join(" → ")} → {d.path_labels[0]}
          </div>
        )}
        {/* 后两类的后果值得写出来：不可满足的类不会报错，它只是永远空着 */}
        {unsatisfiable && (
          <p class="mt-1 text-sub text-ink-2">
            {S.review.defectNeverInstantiable}
          </p>
        )}
        {rules.length > 0 && (
          <div class="mt-1 space-y-1 text-sub text-ink-2">
            {rules.map((r, i) => {
              const rr = r as Record<string, unknown>;
              return (
                <div key={i}>
                  <div class="text-ink-2">
                    {S.review.rulesDisagreeRule(
                      String(rr.rule_a ?? ""),
                      String(rr.via_a ?? ""),
                      String(rr.rule_b ?? ""),
                      String(rr.via_b ?? ""),
                      String(rr.axiom ?? "")
                    )}
                  </div>
                  {((rr.examples as [string, string][]) ?? []).map(
                    ([x, y], j) => (
                      <div key={j} class="pl-3 text-ink-2">
                        {x} <span class="text-ink-2">·</span> {y}
                      </div>
                    )
                  )}
                </div>
              );
            })}
          </div>
        )}
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-button
                size="small"
                disabled={props.busy}
                onClick={() => emit("decide", "accepted")}
              >
                {S.review.defectAccepted}
              </el-button>
              <el-button
                type="primary"
                size="small"
                disabled={props.busy}
                onClick={() => emit("decide", "fixed")}
              >
                {S.review.defectFixed}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** 对齐的两票一句话（含 marks：绑过的签名带「单个日期标什么」） */
function voteText(
  v: {
    property: string;
    direction: string;
    marks?: AlignmentMarks | null;
  } | null
): string {
  if (v === undefined || v === null) return S.review.alignmentNone;
  if (typeof v === "string") return v;
  const side =
    v.direction === "reverse"
      ? S.review.alignmentReverse
      : S.review.alignmentForward;
  const marks = v.marks
    ? ` · ${S.review.alignmentMarks} ${marksText(v.marks)}`
    : "";
  return `${v.property} · ${side}${marks}`;
}

function marksText(m: AlignmentMarks): string {
  return m === "start"
    ? S.review.alignmentMarksStart
    : m === "end"
      ? S.review.alignmentMarksEnd
      : S.review.alignmentMarksNone;
}

function kindWordVote(v: string | null | undefined): string {
  return v ? v : S.review.alignmentNone;
}

/** 一条短语签名：短语、两端的类、例句、两票；人选属性与方向，或「没有」。
 *  选的是状态属性时，还要说单个日期标的是开始、结束，还是都不是（#966） */
export const AlignmentPhraseRow = defineComponent({
  name: "AlignmentPhraseRow",
  props: {
    item: {
      type: Object as PropType<Extract<AlignmentItem, { kind: "phrase" }>>,
      required: true
    },
    properties: {
      type: Array as PropType<RelationTypeView[]>,
      required: true
    },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: {
    decide: (
      _property: string | null,
      _direction: "forward" | "reverse",
      _marks: AlignmentMarks | null
    ) => true
  },
  setup(props, { emit }) {
    const item = props.item;
    const start = phraseStart(item);
    const property = ref<string>(start.property);
    const direction = ref<"forward" | "reverse">(start.direction);
    const marks = ref<AlignmentMarks | null>(start.marks);
    // 字面值当宾语的签名只配属性（attribute），两样东西之间的只配关系（relation）
    const fitting = computed(() =>
      props.properties.filter(p =>
        item.object_is_value ? p.kind === "attribute" : p.kind === "relation"
      )
    );
    const state = computed(() => asksMarks(fitting.value, property.value));
    // 状态属性下单个日期标什么没有预选：人选了才能绑
    const unread = computed(() => state.value && marks.value === null);
    return () => (
      <div class={[ROW_CARD, "p-3"]}>
        <div class="flex flex-wrap items-baseline gap-2">
          <span class="text-body text-ink">“{item.phrase}”</span>
          <span class="text-sub text-ink-2">
            {item.subject_class ?? "?"} →{" "}
            {item.object_is_value
              ? S.review.alignmentValue
              : (item.object_class ?? "?")}
          </span>
          <span class="text-sub text-ink-2">
            {S.review.alignmentStatements(item.statement_count)}
          </span>
        </div>
        {item.examples.length > 0 && (
          <div class="mt-1 space-y-1 text-sub text-ink-2">
            {item.examples.map((e, i) => (
              <div key={i}>{e}</div>
            ))}
          </div>
        )}
        {item.bound_to && (
          <div class="mt-1 text-sub text-ink-2">
            {S.review.alignmentAwaitsMarks(item.bound_to)}
          </div>
        )}
        {/* 绑上了的签名只在代理投过票时列两票：它们各说了单个日期标什么 */}
        {(!item.bound_to || item.votes?.first || item.votes?.second) && (
          <div class="mt-1 text-sub text-ink-2">
            {S.review.alignmentVotes(
              voteText(item.votes?.first ?? null),
              voteText(item.votes?.second ?? null)
            )}
          </div>
        )}
        {/* 候选多到没问模型的签名（0053）：说清是这个原因，不是两票都投了空 */}
        {item.votes?.reason === "too_many_candidates" && (
          <div class="mt-1 text-sub text-ink-2">
            {S.review.alignmentTooMany(item.votes?.candidates ?? 0)}
          </div>
        )}
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-select
                filterable
                size="small"
                style="width: 14rem"
                modelValue={property.value}
                {...{
                  "onUpdate:modelValue": (v: string) => (property.value = v)
                }}
              >
                <el-option value="" label={S.review.alignmentNone} />
                {fitting.value.map(p => (
                  <el-option key={p.key} value={p.key} label={p.label} />
                ))}
              </el-select>
              {property.value && !item.object_is_value && (
                <USegmented
                  size="sm"
                  value={direction.value}
                  options={[
                    { value: "forward", label: S.review.alignmentForward },
                    { value: "reverse", label: S.review.alignmentReverse }
                  ]}
                  onChange={(v: string) =>
                    (direction.value = v as "forward" | "reverse")
                  }
                />
              )}
              {state.value && (
                <>
                  <span class="text-sub text-ink-2">
                    {S.review.alignmentMarks}
                  </span>
                  <USegmented
                    size="sm"
                    value={marks.value ?? ""}
                    options={[
                      {
                        value: "start",
                        label: S.review.alignmentMarksStart,
                        title: S.review.alignmentMarksStartHint
                      },
                      {
                        value: "end",
                        label: S.review.alignmentMarksEnd,
                        title: S.review.alignmentMarksEndHint
                      },
                      {
                        value: "none",
                        label: S.review.alignmentMarksNone,
                        title: S.review.alignmentMarksNoneHint
                      }
                    ]}
                    onChange={(v: string) =>
                      (marks.value = (v || null) as AlignmentMarks | null)
                    }
                  />
                </>
              )}
              <el-button
                size="small"
                disabled={props.busy || unread.value}
                {...{
                  title: unread.value
                    ? S.review.alignmentMarksRequired
                    : undefined
                }}
                onClick={() =>
                  emit(
                    "decide",
                    property.value || null,
                    direction.value,
                    state.value ? marks.value : null
                  )
                }
              >
                {property.value
                  ? S.review.alignmentBind
                  : S.review.alignmentLeaveOpen}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** 对齐器提的一条蕴含规则（0044 决定 3 第五片）：这种形状还蕴含哪条属性、
 *  宾语怎么来；人批或驳 */
export const AlignmentRuleRow = defineComponent({
  name: "AlignmentRuleRow",
  props: {
    item: {
      type: Object as PropType<Extract<AlignmentItem, { kind: "rule" }>>,
      required: true
    },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["decide"],
  setup(props, { emit }) {
    const item = props.item;
    return () => {
      const shape =
        item.trigger === "kind_word"
          ? S.review.alignmentRuleKindWord(item.phrase)
          : `${item.subject_class ?? "?"} —${item.phrase}→ ${
              item.object_is_value ? "value" : (item.object_class ?? "?")
            }`;
      return (
        <div class={[ROW_CARD, "p-3"]}>
          <div class="text-body font-medium">{shape}</div>
          <div class="mt-1 text-sub">
            {S.review.alignmentRuleImplies(
              item.property_label || item.property
            )}
            {item.reading &&
              ` · ${S.review.alignmentRuleReading(item.reading)}`}
          </div>
          {item.examples.length > 0 && (
            <div class="mt-1 space-y-1 text-sub text-ink-2">
              {item.examples.map((e, i) => (
                <div key={i}>{e}</div>
              ))}
            </div>
          )}
          <div class={CARD_ACTIONS}>
            {props.canDecide && (
              <>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("decide", true)}
                >
                  {S.review.alignmentApprove}
                </el-button>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("decide", false)}
                >
                  {S.review.alignmentReject}
                </el-button>
              </>
            )}
          </div>
        </div>
      );
    };
  }
});

/** 一个类别词：词、写法、例名、它们参与的短语、两票；人选类，或「没有」 */
export const AlignmentKindWordRow = defineComponent({
  name: "AlignmentKindWordRow",
  props: {
    item: {
      type: Object as PropType<Extract<AlignmentItem, { kind: "kind_word" }>>,
      required: true
    },
    classes: { type: Array as PropType<EntityTypeView[]>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["decide"],
  setup(props, { emit }) {
    const item = props.item;
    const first = item.votes?.first ?? null;
    const second = item.votes?.second ?? null;
    const cls = ref<string>(first ?? second ?? "");
    return () => (
      <div class={[ROW_CARD, "p-3"]}>
        <div class="flex flex-wrap items-baseline gap-2">
          <span class="text-body text-ink">{item.kind_word}</span>
          {item.words.length > 0 && (
            <span class="text-sub text-ink-2">{item.words.join(" · ")}</span>
          )}
          <span class="text-sub text-ink-2">
            {S.review.alignmentEntities(item.entity_count)}
          </span>
        </div>
        {item.examples.length > 0 && (
          <div class="mt-1 text-sub text-ink-2">
            {item.examples.join(" · ")}
          </div>
        )}
        {item.phrases.length > 0 && (
          <div class="mt-1 text-sub text-ink-2">
            {item.phrases.map(p => `—${p}→`).join("  ")}
          </div>
        )}
        <div class="mt-1 text-sub text-ink-2">
          {S.review.alignmentVotes(
            kindWordVote(item.votes?.first),
            kindWordVote(item.votes?.second)
          )}
        </div>
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-select
                filterable
                size="small"
                style="width: 14rem"
                modelValue={cls.value}
                {...{ "onUpdate:modelValue": (v: string) => (cls.value = v) }}
              >
                <el-option value="" label={S.review.alignmentNone} />
                {props.classes.map(c => (
                  <el-option key={c.key} value={c.key} label={c.label} />
                ))}
              </el-select>
              <el-button
                size="small"
                disabled={props.busy}
                onClick={() => emit("decide", cls.value || null)}
              >
                {cls.value
                  ? S.review.alignmentBind
                  : S.review.alignmentLeaveOpen}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/** 勘误 agent 留给人的一笔（0044 决定 7）：哪份文档、想对哪条事实做什么、
 *  凭哪句原话、为什么留下。批了就执行，否了只记。 */
export const ErrataRow = defineComponent({
  name: "ErrataRow",
  props: {
    item: { type: Object as PropType<ErrataItem>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["decide"],
  setup(props, { emit }) {
    const item = props.item;
    const verb =
      item.action === "retract"
        ? S.review.errataRetract
        : item.action === "revise"
          ? S.review.errataRevise
          : S.review.errataAdd;
    const p = item.proposed;
    return () => (
      <div class={[ROW_CARD, "p-3"]}>
        {/* 来源文档（reskin-b4）：cat 色点按文档名稳定轮转 */}
        <div class="flex items-center gap-1 text-sub text-ink-2">
          {catDot(item.document)}
          <span class="truncate">{item.document}</span>
        </div>
        <div class="mt-1 text-body">
          <span class="font-medium">{verb}</span>{" "}
          {p ? `${p.subject} —${p.property}→ ${p.object}` : ""}
        </div>
        {item.flag && (
          <div class="mt-1 text-sub text-ink-2">
            {S.review.errataFlag(item.flag)}
          </div>
        )}
        {item.reason && <div class="mt-1 text-sub">{item.reason}</div>}
        {item.quote && (
          <div class="mt-1 text-sub text-ink-2">
            {S.review.errataQuote} “{item.quote}”
          </div>
        )}
        {item.detail && (
          <div class="mt-1 text-sub text-warning">
            {S.review.errataHeld(item.detail)}
          </div>
        )}
        <div class={CARD_ACTIONS}>
          {props.canDecide && (
            <>
              <el-button
                size="small"
                disabled={props.busy}
                onClick={() => emit("decide", true)}
              >
                {S.review.errataApprove}
              </el-button>
              <el-button
                size="small"
                disabled={props.busy}
                onClick={() => emit("decide", false)}
              >
                {S.review.errataReject}
              </el-button>
            </>
          )}
        </div>
      </div>
    );
  }
});

/* ---------- 公理违规（0002 R0）：三个出路，第三个是这一档独有的 ---------- */

/** 裁决的附加参数：闭合日期（fact_closed）、撤哪条（fact_retracted） */
export type DecideOpts = {
  closeAt?: string;
  closeAtPrecision?: string;
  factId?: string;
};

/** 一处公理违规。矛盾可能出在定义上（用户导的本体把某个属性声明成反对称，
 *  而他的语料里那关系其实双向），这时该改的是本体，不是二十条事实。 */
export const ViolationRow = defineComponent({
  name: "ViolationRow",
  props: {
    violation: { type: Object as PropType<AxiomViolation>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["decide", "duplicates", "ontology"],
  setup(props, { emit }) {
    const v = props.violation;
    const what = {
      self_loop: S.review.violationSelfLoop,
      asymmetry: S.review.violationAsymmetry,
      cycle: S.review.violationCycle,
      functional: S.review.violationFunctional,
      inverse_functional: S.review.violationInverseFunctional,
      signature: S.review.violationSignature,
      derived_contradiction: S.review.violationDerived
    }[v.kind];
    if (v.kind === "derived_contradiction") {
      // 派生撞上断言（0017）：卡片是一次审核，线索指向上游的错
      return () => (
        <ContradictionRow
          v={v}
          what={what}
          busy={props.busy}
          canDecide={props.canDecide}
          onDecide={(...a: unknown[]) => emit("decide", ...(a as never[]))}
          onDuplicates={() => emit("duplicates")}
          onOntology={() => emit("ontology")}
        />
      );
    }
    return () => {
      // 自反那一类两条事实是同一条——显示一遍就够，显示两遍像个 bug
      const single = v.left_fact === v.right_fact;
      // 「数据错了」要撤具体哪一条：环逐条列，双事实的两条各一个按钮，单事实的不用问（#202）
      const facts =
        v.path.length > 0
          ? v.path
          : single
            ? [{ id: v.left_fact, text: v.left_text }]
            : [
                { id: v.left_fact, text: v.left_text },
                { id: v.right_fact, text: v.right_text }
              ];
      return (
        <div class={[ROW_CARD, "p-3"]}>
          <div class="flex flex-wrap items-baseline gap-2">
            <span class="text-body text-warning">{what}</span>
            {v.predicate && (
              <span class="text-aux text-ink-2">
                {S.review.violationVia(v.predicate)}
              </span>
            )}
            {v.kind === "cycle" && v.path_len > 0 && (
              <span class="text-aux text-ink-2">
                {S.review.violationPath(v.path_len)}
              </span>
            )}
          </div>
          <div class="mt-2 space-y-1">
            {facts.map(f => (
              <div key={f.id} class="flex items-center gap-2">
                <span class="min-w-0 flex-1 text-sub text-ink-2">{f.text}</span>
                {!single && props.canDecide && (
                  <el-button
                    size="small"
                    class="shrink-0"
                    disabled={props.busy}
                    {...{ title: S.review.retractThisHint }}
                    onClick={() =>
                      emit("decide", "fact_retracted", { factId: f.id })
                    }
                  >
                    {S.review.retractThis}
                  </el-button>
                )}
              </div>
            ))}
          </div>
          <div class={CARD_ACTIONS}>
            {props.canDecide && (
              <>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("decide", "accepted")}
                >
                  {S.review.acceptBoth(facts.length)}
                </el-button>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("decide", "axiom_relaxed")}
                >
                  {S.review.relaxAxiom}
                </el-button>
                {single && (
                  <el-button
                    type="primary"
                    size="small"
                    disabled={props.busy}
                    onClick={() =>
                      emit("decide", "fact_retracted", { factId: v.left_fact })
                    }
                  >
                    {S.review.retractFact}
                  </el-button>
                )}
              </>
            )}
          </div>
        </div>
      );
    };
  }
});

/** 派生撞上断言（0017）：修法就在卡片上，端点替人执行 */
const ContradictionRow = defineComponent({
  name: "ContradictionRow",
  props: {
    v: { type: Object as PropType<AxiomViolation>, required: true },
    what: { type: String, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["decide", "duplicates", "ontology"],
  setup(props, { emit }) {
    const closeAt = ref("");
    const v = props.v;
    const hint =
      v.hint === "stale"
        ? S.review.hintStale
        : v.hint === "duplicate"
          ? S.review.hintDuplicate
          : v.hint === "unsure"
            ? S.review.hintUnsure
            : S.review.hintReadBoth;
    return () => {
      const d = v.detail as Record<string, unknown> | null;
      const parsed = parseDateInput(closeAt.value.trim());
      return (
        <div
          class={[ROW_CARD, "p-3"]}
          style={{
            borderColor:
              "color-mix(in srgb, var(--color-warning) 35%, transparent)"
          }}
        >
          <div class="flex flex-wrap items-baseline gap-2">
            <span class="text-body text-warning">{props.what}</span>
            {v.predicate && (
              <span class="text-aux text-ink-2">
                {S.review.violationVia(v.predicate)}
              </span>
            )}
          </div>
          <div class="mt-2 space-y-1">
            <div class="text-sub text-ink-2">
              {S.review.derivedLine(
                String(d?.subject ?? "?"),
                String(d?.predicate ?? "?"),
                String(d?.object ?? "?")
              )}
              {d?.rule && d?.via_label && (
                <span class="ml-2 text-ink-2">
                  {S.review.derivedBy(String(d.rule), String(d.via_label))}
                </span>
              )}
            </div>
            <div class="text-sub text-ink-2">
              {S.review.assertedLine(v.left_text)}
            </div>
          </div>
          <p class="mt-2 text-sub text-ink-2">{hint}</p>
          <div class={CARD_ACTIONS}>
            {props.canDecide && (
              <>
                {/* 不用日期选择器：它逼人给出一个日，而「那年结束的」正是这里常见的答案 */}
                <el-input
                  size="small"
                  class="close-at"
                  placeholder={S.review.closeAtPlaceholder}
                  modelValue={closeAt.value}
                  {...{
                    title: S.review.closeAssertion,
                    "onUpdate:modelValue": (val: string) =>
                      (closeAt.value = val)
                  }}
                />
                <el-button
                  type="primary"
                  size="small"
                  disabled={props.busy || !parsed}
                  onClick={() => {
                    if (parsed)
                      emit("decide", "fact_closed", {
                        closeAt: parsed.iso,
                        closeAtPrecision: parsed.precision
                      });
                  }}
                >
                  {S.review.closeAssertion}
                </el-button>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("decide", "fact_retracted")}
                >
                  {S.review.retractAssertion}
                </el-button>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("duplicates")}
                >
                  {S.review.seeDuplicates}
                </el-button>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("ontology")}
                >
                  {S.review.openOntology}
                </el-button>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("decide", "accepted")}
                >
                  {S.review.letBothStand}
                </el-button>
              </>
            )}
          </div>
        </div>
      );
    };
  }
});

/* ---------- agent 的一笔（0025） ---------- */

const AGENT_ACTION_TONE: Record<AgentDecision["action"], string> = {
  merge: "violet",
  keep: "neutral",
  unsure: "warn"
};

const AGENT_STATUS_TONE: Record<AgentDecision["status"], string> = {
  proposed: "warn",
  applied: "info",
  accepted: "success",
  overridden: "neutral",
  reverted: "danger",
  superseded: "neutral"
};

/** 一条先例写成一句话；类型对的习惯是汇总，单独一句 */
function precedentText(p: AgentPrecedent): string {
  if (p.family === "type_pair")
    return S.review.agentPrecedentHabit(p.merged, p.kept, p.reverted);
  const verb =
    p.action === "merge.revert"
      ? S.review.agentPrecedentReverted
      : p.action === "review.keep"
        ? S.review.agentPrecedentKept
        : S.review.agentPrecedentMerged;
  const line = `${p.left} ≟ ${p.right} · ${verb} · ${p.at.slice(0, 10)}`;
  // 人写的那一句（0026）跟在后面：先例不只是结果
  return p.why ? `${line} · “${p.why}”` : line;
}

/** agent 的一笔：建议等人答（merge/keep），自动裁的可撤（revert）。
 *  回答 agent 也能带一句理由（0026）——它走的正是人的裁决路径 */
export const AgentRow = defineComponent({
  name: "AgentRow",
  props: {
    d: { type: Object as PropType<AgentDecision>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: {
    answer: (_action: "merge" | "keep" | "revert", _rationale?: string) => true
  },
  setup(props, { emit }) {
    const d = props.d;
    const open = ref(false);
    const why = ref("");
    const precedents = computed(() => d.precedents ?? []);
    const trace = computed(() => d.trace ?? []);
    const hasDetail = computed(
      () => precedents.value.length > 0 || trace.value.length > 0
    );
    return () => (
      <div class={[ROW_CARD, "px-4 py-3"]}>
        <div class="flex items-center gap-3">
          <UStatus tone={AGENT_ACTION_TONE[d.action] as never}>
            {S.review.agentActions[d.action]}
          </UStatus>
          <span class="min-w-0 truncate text-body text-ink-2">
            {d.left ?? "?"} ≟ {d.right ?? "?"}
          </span>
          {/* 置信度徽章（reskin-b4）：cat 色点按行 id 稳定轮转 */}
          <span class="badge-soft badge-soft-info shrink-0 tabular-nums">
            {catDot(d.id)}
            {Math.round(d.confidence * 100)}%
          </span>
          <UStatus
            tone={AGENT_STATUS_TONE[d.status] as never}
            class="ml-auto shrink-0"
          >
            {S.review.agentStatus[d.status]}
          </UStatus>
        </div>
        {/* defer 留下的问题（第二刀）：这是给人看的正文，不是注脚 */}
        {d.question && (
          <p class="mt-1 text-body text-ink">
            <span class="text-ink-2">{S.review.agentAsks} </span>
            {d.question}
          </p>
        )}
        {(d.reason || hasDetail.value) && (
          <div class="mt-1 flex items-center gap-3 text-sub text-ink-2">
            {d.reason && <span class="min-w-0 truncate">{d.reason}</span>}
            {hasDetail.value && (
              <LinkButton
                class="shrink-0"
                onClick={() => (open.value = !open.value)}
              >
                {[
                  precedents.value.length > 0
                    ? S.review.agentPrecedents(precedents.value.length)
                    : null,
                  trace.value.length > 0
                    ? S.review.agentLookups(trace.value.length)
                    : null
                ]
                  .filter(Boolean)
                  .join(" · ")}
              </LinkButton>
            )}
          </div>
        )}
        {open.value && hasDetail.value && (
          <ul class="mt-2 space-y-1 border-t border-line pt-2 text-sub text-ink-2">
            {precedents.value.map((p, i) => (
              <li key={`p${i}`} class="truncate">
                {precedentText(p)}
              </li>
            ))}
            {/* 轨迹就是解释：它查了什么，一行一次 */}
            {trace.value.map((t, i) => (
              <li key={`t${i}`} class="truncate">
                {S.review.agentLookedAt} {t.note}
              </li>
            ))}
          </ul>
        )}
        <div class="mt-2 flex items-center gap-2">
          <span class="text-aux text-ink-2">
            {d.decided_by_name
              ? S.review.agentAnsweredBy(
                  d.decided_by_name,
                  (d.decided_at ?? d.created_at).slice(0, 10)
                )
              : d.created_at.slice(0, 10)}
          </span>
          <div class="ml-auto flex shrink-0 items-center gap-2">
            {props.canDecide &&
              (d.status === "proposed" || d.status === "applied") && (
                <el-input
                  size="small"
                  style="width: 14rem"
                  placeholder={S.review.rationalePlaceholder}
                  modelValue={why.value}
                  disabled={props.busy}
                  {...{ "onUpdate:modelValue": (v: string) => (why.value = v) }}
                />
              )}
            {props.canDecide && d.status === "proposed" && (
              <>
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("answer", "keep", why.value)}
                >
                  {S.review.keep}
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("answer", "merge", why.value)}
                >
                  {S.review.merge}
                </el-button>
              </>
            )}
            {props.canDecide &&
              d.status === "applied" &&
              d.action === "merge" && (
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("answer", "revert", why.value)}
                >
                  {S.review.revert}
                </el-button>
              )}
            {props.canDecide &&
              d.status === "applied" &&
              d.action === "keep" && (
                <el-button
                  size="small"
                  disabled={props.busy}
                  onClick={() => emit("answer", "merge", why.value)}
                >
                  {S.review.merge}
                </el-button>
              )}
          </div>
        </div>
      </div>
    );
  }
});

/** 合并历史行：撤回一条自动合并（revert） */
export const MergeRow = defineComponent({
  name: "MergeRow",
  props: {
    merge: { type: Object as PropType<MergeLog>, required: true },
    busy: { type: Boolean, default: false },
    canDecide: { type: Boolean, default: false }
  },
  emits: ["revert"],
  setup(props, { emit }) {
    const m = props.merge;
    return () => (
      <div class={[ROW_CARD, "flex items-center gap-3 px-4 py-3"]}>
        <div class="min-w-0 flex-1">
          <div class="truncate text-body text-ink-2">
            <span>{m.source_name}</span>
            <span> → </span>
            <span class="text-ink">{m.target_name}</span>
          </div>
          <div class="truncate text-sub text-ink-2">
            {m.merged_by_name
              ? S.review.mergedBy(m.merged_by_name)
              : S.review.mergedByAi}
            {" · "}
            {m.created_at.slice(0, 10)}
            {m.reason ? ` · ${escalationText(m.reason)}` : ""}
          </div>
        </div>
        {m.reverted_at ? (
          <UStatus class="shrink-0">{S.review.reverted}</UStatus>
        ) : (
          props.canDecide && (
            <el-button
              size="small"
              class="shrink-0"
              disabled={props.busy}
              onClick={() => emit("revert")}
            >
              {S.review.revert}
            </el-button>
          )
        )}
      </div>
    );
  }
});

/* ---------- 决策台账行 ---------- */

const DECISION_TONE: Record<string, string> = {
  "review.merge": "violet",
  "merge.manual": "violet",
  "review.keep": "neutral",
  "fact.confirm": "success",
  "fact.reject": "danger",
  "conflict.reject_new": "danger",
  "fact.close": "info",
  "conflict.close_old": "info",
  "conflict.keep_both": "neutral",
  "merge.revert": "warn"
};

export const DecisionRow = defineComponent({
  name: "DecisionRow",
  props: {
    e: { type: Object as PropType<ReviewHistoryEvent>, required: true }
  },
  setup(props) {
    const e = props.e;
    // detail 是决策时的自包含快照——不 join 活数据，事实删了台账也完整
    const text = computed(() => {
      const d = e.detail as Record<string, unknown>;
      if (e.action.startsWith("review.")) return `${d.left} ≟ ${d.right}`;
      if (e.action.startsWith("fact."))
        return `${d.subject} — ${d.predicate ?? "?"} → ${d.object ?? "?"}`;
      if (e.action.startsWith("conflict."))
        return `${d.old_subject} — ${d.predicate} → ${d.old_object ?? "?"} · vs · ${
          d.new_object ?? d.new_subject
        }`;
      return `${d.source} → ${d.target}`;
    });
    return () => {
      const d = e.detail as Record<string, unknown>;
      return (
        <div class={[ROW_CARD, "flex items-center gap-3 px-4 py-3"]}>
          <UChip tone={(DECISION_TONE[e.action] ?? "neutral") as never}>
            {S.review.decisionActions[
              e.action as keyof typeof S.review.decisionActions
            ] ?? e.action}
          </UChip>
          <span class="min-w-0 truncate text-body text-ink-2">
            {text.value}
          </span>
          {/* 人（或模型）写的那一句（0026）；老行没有 */}
          {typeof d.why === "string" && d.why && (
            <span class="min-w-0 truncate text-sub text-ink-2">“{d.why}”</span>
          )}
          {typeof d.confidence === "number" && (
            <span class="badge-soft badge-soft-info shrink-0 tabular-nums">
              {catDot(e.id)}
              {Math.round(d.confidence * 100)}%
            </span>
          )}
          {typeof d.valid_to === "string" && (
            <span class="shrink-0 tabular-nums text-sub text-ink-2">
              → {String(d.valid_to).slice(0, 10)}
            </span>
          )}
          <span class="ml-auto shrink-0 text-sub text-ink-2">
            {(e.actor_name ?? S.review.aiActor) + " · "}
            <span class="tabular-nums">{e.created_at.slice(0, 10)}</span>
          </span>
        </div>
      );
    };
  }
});
