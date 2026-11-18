// W2 SSE 接线测试（任务 7）：六个读页正在用的 vue-query key 形态 × useKbEvents 的
// 事件→键映射，用**真实 QueryClient**（不 mock invalidateQueries）钉住「页面里
// 的 key 真被对应事件失效」。键形态逐字取自各页源码：
//   graph/index.vue   ["graph", kbId, focus, budget] / ["readiness", kbId] / ["me"]
//     entity-panel     ["entity", kbId, entityId] / ["evidence", factId]
//   search/index.vue  ["search", kbId, q]
//   ontology/index.vue ["ontology", kbId] / ["uniqueness", kbId] / ["rules", kbId]
//     side-panels      ["ontologyRegressions", kbId] / ["questionReport", kbId] / ["storedProposals", kbId]
//   review/index.vue  ["review", kbId, queueSel, page, types] / ["reviewHistory", kbId, page]
//     / ["reviewSummary", kbId] / ["kbOne", kbId] / ["ontology", kbId]
//   library/index.vue ["documents", kbId, selection, filter, graphFilter, page] / ["sources", kbId]
//     / ["extraction-drops", kbId]；sources-rail ["docCount", kbId]
//   mappings/index.vue ["mappings", kbId, status, q, page] / ["mappingRevisions", kbId, historyId]
//     / ["kbDataSources", kbId]
// 事件映射与旧仓 useKbEvents 同表：document→documents+graph；graph→graph+
// ontologyRegressions；review→review+mappings+ontologyRegressions；pending→pending+
// review；source→sources+documents；alert→alerts。不在表上的键（me/search/readiness/
// docCount/extraction-drops/entity/evidence/kbOne/reviewHistory/reviewSummary/
// questionReport/storedProposals/mappingRevisions/kbDataSources）不被事件失效——
// 旧仓同口径（写路径的 mutation 自带逐键 invalidate，新鲜期另有 30s 窗口）。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useKbEvents } from "@/composables/useKbEvents";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  listeners = new Map<string, Set<() => void>>();
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: () => void) {
    const set = this.listeners.get(type) ?? new Set();
    set.add(listener);
    this.listeners.set(type, set);
  }

  removeEventListener(type: string, listener: () => void) {
    this.listeners.get(type)?.delete(listener);
  }

  emit(type: string) {
    for (const listener of this.listeners.get(type) ?? []) listener();
  }

  close() {
    this.closed = true;
  }
}

/** 页面键形态 → SSE 失效核对表（一个事件该失效哪些页键） */
const KB = "kb-a";

/** 六个读页正在用的键（形态逐字，kbId=kb-a） */
const PAGE_KEYS = {
  graphMain: ["graph", KB, null, 150] as const, // 图谱主数据（focus=null 档位 150）
  graphReady: ["readiness", KB] as const, // 图谱空状态（10s 自带 staleTime）
  search: ["search", KB, "热x"] as const, // 搜索结果
  ontology: ["ontology", KB] as const, // 本体页主数据（审核对齐卡共用）
  regressions: ["ontologyRegressions", KB] as const, // 本体回归面板
  questionReport: ["questionReport", KB] as const,
  storedProposals: ["storedProposals", KB] as const,
  reviewQueue: ["review", KB, "duplicates", 0, "any"] as const, // 审核队列（档+页+类型）
  reviewHistory: ["reviewHistory", KB, 0] as const,
  reviewSummary: ["reviewSummary", KB] as const,
  kbOne: ["kbOne", KB] as const, // 库详情（KbScope/各页共用）
  documents: ["documents", KB, "all", "", "", 0] as const, // 文库列表（作用域+名+态+页）
  sources: ["sources", KB] as const, // 来源栏
  docCount: ["docCount", KB] as const, // 来源栏计数
  drops: ["extraction-drops", KB] as const,
  entity: ["entity", KB, "e-1"] as const, // 实体侧栏
  evidence: ["evidence", "f-1"] as const, // 事实证据
  mappings: ["mappings", KB, "all", "", 0] as const, // 映射队列（档+词+页）
  mappingRevisions: ["mappingRevisions", KB, "m-1"] as const,
  kbDataSources: ["kbDataSources", KB] as const,
  pending: ["pending", KB] as const, // 对话确认卡
  me: ["me"] as const,
  alertsUnread: ["alerts", "unread"] as const,
  alertsList: ["alerts", "list", "", 0] as const
};

function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } }
  });
  for (const key of Object.values(PAGE_KEYS)) client.setQueryData([...key], "seed");
  const Host = defineComponent({
    setup() {
      useKbEvents(ref(KB));
      return () => h("div");
    }
  });
  const wrapper = mount(Host, {
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] }
  });
  const source = FakeEventSource.instances.at(-1)!;
  const invalidated = (...keys: Array<readonly unknown[]>) =>
    keys.map(key => client.getQueryState([...key])?.isInvalidated === true);
  return {
    client,
    source,
    invalidated,
    unmount: () => wrapper.unmount()
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("EventSource", FakeEventSource);
  FakeEventSource.instances = [];
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("六个读页的键真被事件失效（接线核对表）", () => {
  it("document → 文库列表 + 图谱主数据（readiness/me 不在事件表上）", () => {
    const { source, invalidated, unmount } = setup();
    source.emit("document");
    vi.advanceTimersByTime(300);
    expect(invalidated(PAGE_KEYS.documents, PAGE_KEYS.graphMain)).toEqual([true, true]);
    expect(invalidated(PAGE_KEYS.graphReady, PAGE_KEYS.me)).toEqual([false, false]);
    unmount();
  });

  it("graph → 图谱主数据 + 本体回归面板（search 不动）", () => {
    const { source, invalidated, unmount } = setup();
    source.emit("graph");
    vi.advanceTimersByTime(300);
    expect(invalidated(PAGE_KEYS.graphMain, PAGE_KEYS.regressions)).toEqual([true, true]);
    expect(invalidated(PAGE_KEYS.search, PAGE_KEYS.ontology)).toEqual([false, false]);
    unmount();
  });

  it("review → 审核队列 + 映射队列 + 本体回归（台账/总览/库详情不动）", () => {
    const { source, invalidated, unmount } = setup();
    source.emit("review");
    vi.advanceTimersByTime(300);
    expect(invalidated(PAGE_KEYS.reviewQueue, PAGE_KEYS.mappings, PAGE_KEYS.regressions)).toEqual([
      true,
      true,
      true
    ]);
    expect(
      invalidated(PAGE_KEYS.reviewHistory, PAGE_KEYS.reviewSummary, PAGE_KEYS.kbOne)
    ).toEqual([false, false, false]);
    unmount();
  });

  it("pending → 对话确认卡 + 审核队列", () => {
    const { source, invalidated, unmount } = setup();
    source.emit("pending");
    vi.advanceTimersByTime(300);
    expect(invalidated(PAGE_KEYS.pending, PAGE_KEYS.reviewQueue)).toEqual([true, true]);
    unmount();
  });

  it("source → 来源栏 + 文库列表（docCount/drops 走写路径失效，不随事件）", () => {
    const { source, invalidated, unmount } = setup();
    source.emit("source");
    vi.advanceTimersByTime(300);
    expect(invalidated(PAGE_KEYS.sources, PAGE_KEYS.documents)).toEqual([true, true]);
    expect(invalidated(PAGE_KEYS.docCount, PAGE_KEYS.drops)).toEqual([false, false]);
    unmount();
  });

  it("alert → 告警角标与列表（库事件流顺带送告警 #1028）", () => {
    const { source, invalidated, unmount } = setup();
    source.emit("alert");
    vi.advanceTimersByTime(300);
    expect(invalidated(PAGE_KEYS.alertsUnread, PAGE_KEYS.alertsList)).toEqual([true, true]);
    unmount();
  });

  it("重连补刷把六个页族的流覆盖键一次补齐（前缀去重，立即 flush）", () => {
    const { client, source, invalidated, unmount } = setup();
    source.emit("error");
    source.emit("open");
    expect(
      invalidated(
        PAGE_KEYS.graphMain,
        PAGE_KEYS.documents,
        PAGE_KEYS.reviewQueue,
        PAGE_KEYS.mappings,
        PAGE_KEYS.regressions,
        PAGE_KEYS.pending,
        PAGE_KEYS.sources,
        PAGE_KEYS.alertsUnread,
        PAGE_KEYS.alertsList
      )
    ).toEqual([true, true, true, true, true, true, true, true, true]);
    // 重连不等合并窗口（立即 flush）；非流键（me/ontology/questionReport/storedProposals/
    // reviewHistory/reviewSummary/kbOne/readiness/search/docCount/drops/entity/evidence/
    // mappingRevisions/kbDataSources）仍不动
    expect(
      invalidated(
        PAGE_KEYS.me,
        PAGE_KEYS.ontology,
        PAGE_KEYS.questionReport,
        PAGE_KEYS.storedProposals,
        PAGE_KEYS.reviewHistory,
        PAGE_KEYS.reviewSummary,
        PAGE_KEYS.kbOne,
        PAGE_KEYS.graphReady,
        PAGE_KEYS.search,
        PAGE_KEYS.docCount,
        PAGE_KEYS.drops,
        PAGE_KEYS.entity,
        PAGE_KEYS.evidence,
        PAGE_KEYS.mappingRevisions,
        PAGE_KEYS.kbDataSources
      )
    ).toEqual([false, false, false, false, false, false, false, false, false, false, false, false, false, false, false]);
    void client;
    unmount();
  });
});
