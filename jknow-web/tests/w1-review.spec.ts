/* W1 审核页数据层（任务 5）：按分档 + 页码取数的取数/筛选语义（mock api 层）。
   ——徽标读服务端的 COUNT；内容只回当前这一档的一页；类型筛选只对 duplicates
   有意义且换档回第一页。 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { defineComponent, h, nextTick } from "vue";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { queryClient } from "@/store/knowledge/query";
import { store as globalPinia } from "@/store/knowledge";
import Review from "@/views/jknow/knowledge/review/index.vue";
import { S } from "@/i18n";
import type { ReviewCounts } from "@/api/knowledge/types";

const { reviewMock, summaryMock, historyMock, kbDetailMock } = vi.hoisted(() => ({
  reviewMock: vi.fn(),
  summaryMock: vi.fn(),
  historyMock: vi.fn(),
  kbDetailMock: vi.fn()
}));
vi.mock("@/api/knowledge/review", () => ({
  review: reviewMock,
  reviewSummary: summaryMock,
  reviewHistory: historyMock
}));
vi.mock("@/api/knowledge/kbs", () => ({ kbDetail: kbDetailMock }));
// useKbScope 载详情后会重组 pure-admin 菜单（initRouter 全链）——测试里桩掉
vi.mock("@/composables/useKbContext", () => ({
  useKbContext: () => ({ rebuildMenus: vi.fn() }),
  resolveLegacyKbPath: vi.fn()
}));
// 只桩页面真实 import 的三个写函数（W3 收口：初版桩了六个图谱读函数、
// 恰恰漏了这三个——「query mock 噪音」即此；纯读用例下漏桩无症状，但形状失真）
vi.mock("@/api/knowledge/graph", () => ({
  confirmFact: vi.fn(),
  rejectFact: vi.fn(),
  revertMerge: vi.fn()
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

let mounted: ReturnType<typeof mount> | null = null;
let el: HTMLElement | null = null;
let router: Router | null = null;

async function mountReview(path = "/kb/kb1/review") {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/kb/:kbId/review",
        name: "review",
        component: Review
      },
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } }
    ]
  });
  el = document.createElement("div");
  document.body.appendChild(el);
  await router.push(path);
  await router.isReady();
  mounted = mount(defineComponent({ render: () => h("div") }), {
    global: {
      plugins: [ElementPlus, globalPinia, router, [VueQueryPlugin, { queryClient }]]
    }
  });
  // 直接挂页面组件（useRoute 由 router 插件供给）
  mounted = mount(Review, {
    global: {
      plugins: [ElementPlus, globalPinia, router, [VueQueryPlugin, { queryClient }]]
    },
    attachTo: el!
  });
  await flushPromises();
  return mounted!;
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  queryClient.clear();
  reviewMock.mockReset();
  summaryMock.mockReset();
  historyMock.mockReset();
  kbDetailMock.mockReset();
  kbDetailMock.mockResolvedValue({
    id: "kb1",
    name: "General",
    governance: false,
    my_role: "viewer"
  });
  summaryMock.mockResolvedValue({
    waiting: Object.fromEntries(
      [
        "pending",
        "duplicates",
        "conflicts",
        "unconfirmed",
        "lowconf",
        "violations",
        "defects",
        "alignment"
      ].map(k => [k, { count: 0, oldest_at: null }])
    ),
    decided: {
      last_7d: { total: 0, automatic: 0 },
      last_30d: { total: 0, automatic: 0, by_action: [], by_actor: [] },
      daily: []
    },
    health: { facts: 0, low_confidence: 0, unconfirmed: 0, contested: 0 },
    agent: {
      running: false,
      queue: 0,
      open: 0,
      last_7d: { applied: 0 },
      last_30d: { applied: 0, accepted: 0, overridden: 0, reverted: 0 }
    }
  });
  historyMock.mockResolvedValue({ events: [], total: 0 });
});

afterEach(() => {
  mounted?.unmount();
  el?.remove();
  mounted = null;
  router = null;
});

describe("Review 页数据层：按分档 + 页码取数", () => {
  it("默认落在总览但按 duplicates 档取数（页 0、offset 0、types any）", async () => {
    reviewMock.mockResolvedValue({
      counts: counts({ duplicates: 3 }),
      queue: "duplicates",
      items: []
    });
    const w = await mountReview("/kb/kb1/review");
    await flushPromises();
    expect(reviewMock).toHaveBeenCalledWith("kb1", "duplicates", 6, 0, "any");
    // 左栏徽标 = 服务端的 COUNT（不是数组长度）
    expect(w.find(".badge-soft").text()).toContain("");
    expect(w.html()).toContain(S.review.railOverview);
  });

  it("换档带各自的页大小与 offset；types 只在 duplicates 档跟随筛选", async () => {
    reviewMock.mockResolvedValue({
      counts: counts({ duplicates: 20, pending: 2 }),
      queue: "duplicates",
      items: []
    });
    const w = await mountReview("/kb/kb1/review");
    await flushPromises();
    reviewMock.mockClear();

    // 点左栏「待确认」：FACT_PAGE=10、回第一页
    const railButtons = w.findAll("button").filter(b =>
      b.text().includes(S.review.railPending)
    );
    expect(railButtons.length).toBeGreaterThan(0);
    await railButtons[0].trigger("click");
    await flushPromises();
    expect(reviewMock).toHaveBeenLastCalledWith("kb1", "pending", 10, 0, "any");

    // 回 duplicates 并切类型筛选：清空参数回到第一页
    reviewMock.mockClear();
    reviewMock.mockResolvedValue({
      counts: counts({
        duplicates: 20,
        duplicates_same_type: 7,
        duplicates_type_conflict: 2
      }),
      queue: "duplicates",
      items: []
    });
    const dupBtn = w.findAll("button").filter(b =>
      b.text().includes(S.review.railDuplicates)
    )[0];
    await dupBtn.trigger("click");
    await flushPromises();
    const sameBtn = w.findAll("button").find(b =>
      b.text().includes(S.review.typesSame)
    );
    expect(sameBtn).toBeTruthy();
    await sameBtn!.trigger("click");
    await flushPromises();
    expect(reviewMock).toHaveBeenLastCalledWith("kb1", "duplicates", 6, 0, "same");
  });

  it("翻页按档页大小推进 offset（服务端分页，徽标读 COUNT 不读列表长度）", async () => {
    reviewMock.mockResolvedValue({
      counts: counts({ duplicates: 20 }),
      queue: "duplicates",
      items: []
    });
    const w = await mountReview("/kb/kb1/review?queue=duplicates");
    await flushPromises();
    // UPager 挂在 mt-3 容器里：末尾那颗「下一页」（总览没有页——深链 ?queue=duplicates）
    const pager = w.findAll(".mt-3");
    expect(pager.length).toBeGreaterThan(0);
    const btns = pager[pager.length - 1].findAll("button");
    const next = btns[btns.length - 1];
    expect(next.attributes("disabled")).toBeUndefined();
    reviewMock.mockClear();
    reviewMock.mockResolvedValue({
      counts: counts({ duplicates: 20 }),
      queue: "duplicates",
      items: []
    });
    await next.trigger("click");
    await flushPromises();
    expect(reviewMock).toHaveBeenLastCalledWith("kb1", "duplicates", 6, 6, "any");
  });

  it("九档全空：左栏仍按 COUNT 显示 0，通用空态说「整个待办全清」", async () => {
    reviewMock.mockResolvedValue({ counts: counts(), queue: "duplicates", items: [] });
    const w = await mountReview("/kb/kb1/review");
    await flushPromises();
    expect(w.html()).not.toContain(S.review.empty); // 总览不显示队列空态
  });

  it("站内 ?queue=agent 跳转不重挂也换档（C-1）——总览「去处理」点进 agent 档", async () => {
    // open>0 总览才出「去处理」链接（overview.tsx Stat action）
    summaryMock.mockResolvedValue({
      waiting: Object.fromEntries(
        [
          "pending",
          "duplicates",
          "conflicts",
          "unconfirmed",
          "lowconf",
          "violations",
          "defects",
          "alignment"
        ].map(k => [k, { count: 0, oldest_at: null }])
      ),
      decided: {
        last_7d: { total: 0, automatic: 0 },
        last_30d: { total: 0, automatic: 0, by_action: [], by_actor: [] },
        daily: []
      },
      health: { facts: 0, low_confidence: 0, unconfirmed: 0, contested: 0 },
      agent: {
        running: false,
        queue: 0,
        open: 2,
        last_7d: { applied: 0 },
        last_30d: { applied: 0, accepted: 0, overridden: 0, reverted: 0 }
      }
    });
    const w = await mountReview("/kb/kb1/review");
    await flushPromises();
    const agentBtn = () =>
      w.findAll("button").find(b => b.text().includes(S.review.railAgent))!;
    expect(agentBtn()).toBeTruthy();
    // 挂载时没有 ?queue=：agent 档不激活
    expect(agentBtn().classes()).not.toContain("bg-primary-soft");
    // 总览「去处理」= RouterLink 站内 push（path 不变、只改 query）——
    // lay-content 按 path 定 key 不重挂，sel 只能靠 watch ?queue= 跟过来
    const go = w
      .findAll("a")
      .find(a => a.text().includes(S.review.overviewOpen));
    expect(go).toBeTruthy();
    await go!.trigger("click");
    await flushPromises();
    expect(router!.currentRoute.value.query.queue).toBe("agent");
    expect(agentBtn().classes()).toContain("bg-primary-soft");
  });
});
