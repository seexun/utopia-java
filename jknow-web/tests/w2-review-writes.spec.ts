/* W2 审核裁决流（任务 6）：mock api 断言**调用形状**与**失效面触发**。
   ——decide 单条带 rationale（0026：理由跟着决定进台账）/batch 一批一个动作/
   agent answer 三种动作/pending 确认后 pending+review 双键失效（确认卡在对话里
   也挂着——NodCard 同键）。 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { queryClient } from "@/store/knowledge/query";
import { store as globalPinia } from "@/store/knowledge";
import Review from "@/views/jknow/knowledge/review/index.vue";
import { S } from "@/i18n";
import type { ReviewCounts } from "@/api/knowledge/types";

const m = vi.hoisted(() => ({
  review: vi.fn(),
  summary: vi.fn(),
  history: vi.fn(),
  batch: vi.fn(),
  decide: vi.fn(),
  agentAnswer: vi.fn(),
  decidePending: vi.fn(),
  decideDefect: vi.fn(),
  decidePhrase: vi.fn(),
  decideRule: vi.fn(),
  decideKindWord: vi.fn(),
  decideErrata: vi.fn(),
  decideViolation: vi.fn(),
  runCheck: vi.fn(),
  closeFact: vi.fn(),
  resolveConflict: vi.fn(),
  confirmFact: vi.fn(),
  rejectFact: vi.fn(),
  revertMerge: vi.fn(),
  ontology: vi.fn(),
  kbDetail: vi.fn()
}));
vi.mock("@/api/knowledge/review", () => ({
  review: m.review,
  reviewSummary: m.summary,
  reviewHistory: m.history,
  reviewBatch: m.batch,
  decideReview: m.decide,
  agentAnswer: m.agentAnswer,
  decidePending: m.decidePending,
  decideDefect: m.decideDefect,
  decideAlignmentPhrase: m.decidePhrase,
  decideAlignmentRule: m.decideRule,
  decideAlignmentKindWord: m.decideKindWord,
  decideErrata: m.decideErrata,
  decideViolation: m.decideViolation,
  runConsistencyCheck: m.runCheck,
  closeFact: m.closeFact,
  resolveConflict: m.resolveConflict,
  decideMapping: vi.fn()
}));
vi.mock("@/api/knowledge/graph", () => ({
  confirmFact: m.confirmFact,
  rejectFact: m.rejectFact,
  revertMerge: m.revertMerge,
  graphOverview: vi.fn(),
  graphNeighborhood: vi.fn(),
  searchEntities: vi.fn(),
  entityDetail: vi.fn(),
  entityHistory: vi.fn(),
  factEvidence: vi.fn(),
  mergeEntities: vi.fn()
}));
vi.mock("@/api/knowledge/kbs", () => ({ kbDetail: m.kbDetail }));
vi.mock("@/api/knowledge/ontology", () => ({
  ontology: m.ontology,
  uniquenessCandidates: vi.fn(),
  rules: vi.fn()
}));
vi.mock("@/composables/useKbContext", () => ({
  useKbContext: () => ({ rebuildMenus: vi.fn() }),
  resolveLegacyKbPath: vi.fn()
}));

function counts(over: Partial<ReviewCounts> = {}): ReviewCounts {
  return {
    pending: 0,
    duplicates: 0,
    conflicts: 0,
    unconfirmed: 0,
    lowconf: 0,
    mappings: 0,
    violations: 0,
    defects: 0,
    alignment: 0,
    errata: 0,
    merges: 0,
    agent: 0,
    agent_rows: 0,
    agent_running: false,
    duplicates_same_type: 0,
    duplicates_type_conflict: 0,
    ...over
  } as ReviewCounts;
}

const dupItem = {
  id: "rv1",
  score: 0.93,
  reason: "namesake",
  stage: "human",
  created_at: "2026-11-15T00:00:00Z",
  left: {
    name: "星野科技",
    type_label: "公司",
    disambiguator: null,
    degree: 3,
    top_facts: []
  },
  right: {
    name: "星野科技",
    type_label: "公司",
    disambiguator: "上海",
    degree: 2,
    top_facts: []
  },
  proposal: { id: "p1", action: "merge", confidence: 0.8, reason: null }
};

const pendingItem = {
  id: "pf1",
  quote: "[2026-11-14 10:00] 星野科技把总部搬到了苏州。",
  subject_name: "星野科技",
  predicate_label: null,
  phrase: "把总部搬到了",
  proposed_predicate: null,
  object_name: "苏州",
  object_value: null,
  valid_from: "2026-11",
  valid_to: null,
  time_words: [],
  qualifiers: [],
  proposed_by_name: "Contract Zero",
  proposed_token_name: null
};

const agentItem = {
  id: "ad1",
  action: "merge" as const,
  status: "proposed" as const,
  confidence: 0.82,
  left: "星野科技",
  right: "星野科技（苏州）",
  question: null,
  reason: "same name, same type",
  created_at: "2026-11-15T00:00:00Z",
  decided_at: null,
  decided_by_name: null,
  precedents: [],
  trace: []
};

let mounted: ReturnType<typeof mount> | null = null;
let el: HTMLElement | null = null;
let router: Router | null = null;

async function mountReview(path = "/kb/kb1/review") {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/kb/:kbId/review", name: "review", component: Review },
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } }
    ]
  });
  el = document.createElement("div");
  document.body.appendChild(el);
  await router.push(path);
  await router.isReady();
  mounted = mount(Review, {
    global: {
      plugins: [ElementPlus, globalPinia, router, [VueQueryPlugin, { queryClient }]]
    },
    attachTo: el!
  });
  await flushPromises();
  return mounted!;
}

async function clickButton(w: ReturnType<typeof mount>, text: string) {
  // 左栏 rail 行「Merges」文本包含「Merge」——动作按钮必须**整词**匹配，
  // 否则点中的是导航而不是裁决
  const btn = w
    .findAll("button")
    .find(b => b.text().trim() === text && !b.attributes("disabled"));
  expect(btn, `button「${text}」不存在或被禁用`).toBeTruthy();
  await btn!.trigger("click");
  await flushPromises();
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  queryClient.clear();
  // pinia 全局单例跨用例残留上一角色的 kbDetail——viewer 用例会读到 editor
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalPinia.state.value as any)["utopia-kb"] = {
    kbId: "",
    workspaceId: "",
    kbDetail: null,
    loading: false
  };
  for (const f of Object.values(m)) f.mockReset();
  m.kbDetail.mockResolvedValue({
    id: "kb1",
    name: "General",
    governance: false,
    my_role: "editor"
  });
  m.summary.mockResolvedValue({ waiting: {}, decided: {}, health: {}, agent: {} });
  m.history.mockResolvedValue({ events: [], total: 0 });
  m.decide.mockResolvedValue({ ok: true });
  m.batch.mockResolvedValue({
    decided: 1,
    outcomes: [{ id: "rv1", error: null }]
  });
  m.agentAnswer.mockResolvedValue({ ok: true });
  m.decidePending.mockResolvedValue({ ok: true, fact_id: "f1", created: true });
});

afterEach(() => {
  mounted?.unmount();
  el?.remove();
  mounted = null;
  router = null;
});

describe("Review 裁决流（W2）", () => {
  it("decide 单条：merge 带 rationale（0026 理由跟着决定进台账）+ 失效 review/graph", async () => {
    m.review.mockResolvedValue({
      counts: counts({ duplicates: 1 }),
      queue: "duplicates",
      items: [dupItem]
    });
    const w = await mountReview("/kb/kb1/review?queue=duplicates");
    await flushPromises();

    const invalidates = vi.spyOn(queryClient, "invalidateQueries");
    // 词典语言由模块加载时的 localStorage 定（vitest 在 beforeEach 之前 import）——
    // 用 S 自身取占位文案，别把英文写死
    const why = w
      .findAll("input")
      .find(i => i.attributes("placeholder") === S.review.rationalePlaceholder);
    expect(why.exists()).toBe(true);
    await why.setValue("same company, verified by doc");
    await clickButton(w, S.review.merge);

    expect(m.decide).toHaveBeenCalledTimes(1);
    expect(m.decide).toHaveBeenCalledWith(
      "kb1",
      "rv1",
      "merge",
      "same company, verified by doc"
    );
    // 失效面：review + graph（合并改图）都按前缀键失效
    const keys = invalidates.mock.calls.map(
      c => (c[0] as { queryKey?: unknown[] })?.queryKey?.[0]
    );
    expect(keys).toContain("review");
    expect(keys).toContain("graph");
    invalidates.mockRestore();
  });

  it("batch：全选这一页 + 一批一句理由 → reviewBatch(ids, action, rationale)", async () => {
    m.review.mockResolvedValue({
      counts: counts({ duplicates: 1 }),
      queue: "duplicates",
      items: [{ ...dupItem, id: "rv2" }]
    });
    const w = await mountReview("/kb/kb1/review?queue=duplicates");
    await flushPromises();

    // 全选（checkbox 触发 pick(true)）
    const check = w.find(".el-checkbox");
    expect(check.exists()).toBe(true);
    await check.find("input").setValue(true);
    await flushPromises();
    await clickButton(w, S.review.mergeSelected);

    expect(m.batch).toHaveBeenCalledTimes(1);
    expect(m.batch).toHaveBeenCalledWith("kb1", ["rv2"], "merge", "");
    // 成了就清空选中（picked 回落空集）
    await flushPromises();
  });

  it("agent answer（0025）：proposed 建议答 merge → agentAnswer(kb, id, action, rationale)", async () => {
    m.review.mockResolvedValue({
      counts: counts({ agent: 1, agent_rows: 1 }),
      queue: "agent",
      items: [agentItem]
    });
    const w = await mountReview("/kb/kb1/review?queue=agent");
    await flushPromises();

    await clickButton(w, S.review.merge);
    expect(m.agentAnswer).toHaveBeenCalledWith("kb1", "ad1", "merge", "");
  });

  it("pending 确认（0015）：confirm → decidePending + pending/review 双键失效", async () => {
    m.review.mockResolvedValue({
      counts: counts({ pending: 1 }),
      queue: "pending",
      items: [pendingItem]
    });
    const w = await mountReview("/kb/kb1/review?queue=pending");
    await flushPromises();

    const invalidates = vi.spyOn(queryClient, "invalidateQueries");
    await clickButton(w, S.review.confirm);
    expect(m.decidePending).toHaveBeenCalledTimes(1);
    expect(m.decidePending).toHaveBeenCalledWith("kb1", "pf1", "confirm");
    const keys = invalidates.mock.calls.map(
      c => (c[0] as { queryKey?: unknown[] })?.queryKey?.[0]
    );
    expect(keys).toContain("pending");
    expect(keys).toContain("review");
    invalidates.mockRestore();
  });

  it("权限闸：viewer 看得见提议、看不见裁决按钮（与服务端 Editor 门槛同口径）", async () => {
    m.kbDetail.mockResolvedValue({
      id: "kb1",
      name: "General",
      governance: false,
      my_role: "viewer"
    });
    m.review.mockResolvedValue({
      counts: counts({ duplicates: 1 }),
      queue: "duplicates",
      items: [dupItem]
    });
    const w = await mountReview("/kb/kb1/review?queue=duplicates");
    await flushPromises();
    // 左栏 rail「Merges」文本包含「Merge」——这里必须整词判断
    const texts = w.findAll("button").map(b => b.text().trim());
    expect(texts).toContain(S.review.railMerges as string);
    expect(texts.includes(S.review.merge)).toBe(false);
    expect(
      w
        .findAll("input")
        .some(i => i.attributes("placeholder") === S.review.rationalePlaceholder)
    ).toBe(false);
    expect(m.decide).not.toHaveBeenCalled();
  });
});

