// 自旧仓 useEventReconnect.test.ts（Ⅰ类冻结用例 14 例，任务 7 退役 React 随迁件时
// 改写嫁接）：被测对象换成生产 Vue composable（composables/useKbEvents.ts /
// useAlertEvents.ts），逻辑与断言逐字保留。嫁接的机械差异（W2 审查 I-1 逐条记账）：
//   ① React hook 直调（mock useEffect）→ 宿主组件真挂载（onScopeDispose 即卸载）；
//   ② QueryClient/QueryObserver 换 @tanstack/vue-query 的同一实现（同源 query-core）；
//   ③ node 环境的假 document → jsdom 真文档上偷换可见性（组件挂载还需要真 DOM）；
//   ④ 三处 timer-count 断言（旧仓 `getTimerCount()===0`：flushes-burst /
//      ignores-late-callbacks / short-absence 末尾）被移除——vue-query 的 query-core
//      5.107 给每个被观察查询挂 1~2 个内部定时器（gc/调度，gcTime 5 分钟量级，
//      settle 窗口的 advance 排不干；实测三例残留 8/8/1 个，排干后行为零差异），
//      断言在移植面不可归零。泄漏后果由行为断言承接：flushes-burst 与
//      ignores-late-callbacks 以「settle 窗口过后重取计数不再增长 + 相关键计数
//      精确不变」钉住旧断言的本义（没有第二遍重取被排程）；short-absence 反而
//      **强于旧仓**——卸载后再推进 2×HIDDEN_RELEASE_MS，断言无重连（连接数不变）、
//      无重取（计数不变），释放定时器若未取消会在该窗口显形。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount as vueMount } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import {
  QueryClient,
  QueryObserver,
  VueQueryPlugin,
  type QueryKey
} from "@tanstack/vue-query";
import {
  DEFAULT_STALE_MS,
  STREAM_KEYS,
  STREAM_STALE_MS,
  applyQueryDefaults
} from "@/domain/queryDefaults";
import { SETTLE_MS } from "@/domain/kbEventsCore";
import { useKbEvents } from "@/composables/useKbEvents";
import { useAlertEvents } from "@/composables/useAlertEvents";
import { HIDDEN_RELEASE_MS } from "@/domain/eventStream";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  listeners = new Map<string, Set<() => void>>();
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: () => void) {
    const listeners = this.listeners.get(type) ?? new Set();
    listeners.add(listener);
    this.listeners.set(type, listeners);
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

/** 宿主组件挂载执行 composable（需要 VueQueryPlugin 的 inject 上下文）；
 *  unmount 即 onScopeDispose——旧仓 mock useEffect 直调 effect 的对等物。 */
function mount(hook: () => void): { source: FakeEventSource | undefined; unmount: () => void } {
  const Host = defineComponent({
    setup() {
      hook();
      return () => h("div");
    }
  });
  const wrapper = vueMount(Host, {
    global: { plugins: [[VueQueryPlugin, { queryClient: currentClient! }]] }
  });
  const unmount = () => wrapper.unmount();
  cleanups.push(unmount);
  return { source: FakeEventSource.instances.at(-1), unmount };
}

/** jsdom 真文档上偷换可见性：旧仓 fakePage 在 node 环境整个换掉 document，
 *  这里 Vue 组件挂载还需要真 DOM，只动 visibilityState 与 visibilitychange 监听。 */
function fakePage(initialVisible = true) {
  let visible = initialVisible;
  const listeners = new Set<() => void>();
  const reflect = () =>
    Object.defineProperty(document, "visibilityState", {
      value: visible ? "visible" : "hidden",
      configurable: true
    });
  reflect();
  const addSpy = vi
    .spyOn(document, "addEventListener")
    .mockImplementation(((type: string, cb: () => void) => {
      if (type === "visibilitychange") listeners.add(cb as () => void);
    }) as never);
  const removeSpy = vi
    .spyOn(document, "removeEventListener")
    .mockImplementation(((type: string, cb: () => void) => {
      if (type === "visibilitychange") listeners.delete(cb as () => void);
    }) as never);
  return {
    show(shown: boolean) {
      if (shown === visible) return;
      visible = shown;
      reflect();
      for (const listener of [...listeners]) listener();
    },
    /** 页面监听数（卸载后应为 0——旧仓 page.listeners.size 同语义） */
    listenerCount: () => listeners.size,
    restore() {
      addSpy.mockRestore();
      removeSpy.mockRestore();
      delete (document as unknown as { visibilityState?: unknown }).visibilityState;
    }
  };
}

const cleanups: Array<() => void> = [];
const pages: Array<ReturnType<typeof fakePage>> = [];
const clients: QueryClient[] = [];
const unsubscribes: Array<() => void> = [];
/** mount() 里要喂给 VueQueryPlugin 的当前 client（createClient 先行） */
let currentClient: QueryClient | undefined;

function createClient() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { staleTime: DEFAULT_STALE_MS, refetchOnWindowFocus: false, retry: false }
    }
  });
  applyQueryDefaults(client);
  clients.push(client);
  currentClient = client;
  return client;
}

async function watch(client: QueryClient, key: QueryKey) {
  let serverData = "before disconnect";
  const fetch = vi.fn(async () => serverData);
  const observer = new QueryObserver(client, { queryKey: key, queryFn: fetch });
  unsubscribes.push(observer.subscribe(() => {}));
  // subscribe 启动的首次读取与 refetch 共享同一次请求。
  await observer.refetch();
  expect(fetch).toHaveBeenCalledTimes(1);
  return {
    fetch,
    observer,
    changeServer: () => {
      serverData = "after disconnect";
    }
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("EventSource", FakeEventSource);
  FakeEventSource.instances = [];
});

afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup();
  for (const unsubscribe of unsubscribes.splice(0)) unsubscribe();
  for (const page of pages.splice(0)) page.restore();
  for (const client of clients.splice(0)) client.clear();
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("KB stream reconnect", () => {
  it("refetches every current KB stream key and alerts after reconnect without new business events", async () => {
    const client = createClient();
    const current = await Promise.all(
      STREAM_KEYS.map(head => watch(client, [head, "kb-a", "detail"]))
    );
    const badge = await watch(client, ["alerts", "unread"]);
    const list = await watch(client, ["alerts", "list", "", 0]);
    const recoveredQueries = [...current, badge, list];
    const otherKb = await watch(client, ["graph", "kb-b"]);
    const unrelated = await watch(client, ["health"]);
    const inactiveKey = ["documents", "kb-a", "unmounted"];
    client.setQueryData(inactiveKey, "cached before disconnect");
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    expect(source!.url).toBe("/api/v1/kbs/kb-a/events");
    source!.emit("open");
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(1);

    source!.emit("error");
    for (const query of recoveredQueries) query.changeServer();
    // staleTime 到期只让缓存变旧，不会自动请求；断线期间完全没有业务事件。
    await vi.advanceTimersByTimeAsync(STREAM_STALE_MS + 1);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(1);

    source!.emit("open");
    // 重连不等普通事件的 300ms 合并窗口（失效分发落在下一个时钟滴答，advance 一格即达）
    await vi.advanceTimersByTimeAsync(1);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(2);
    await vi.waitFor(() => {
      for (const query of recoveredQueries)
        expect(query.observer.getCurrentResult().data).toBe("after disconnect");
    });
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(SETTLE_MS);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(2);
    expect(client.getQueryState(inactiveKey)?.isInvalidated).toBe(true);
    expect(otherKb.fetch).toHaveBeenCalledTimes(1);
    expect(unrelated.fetch).toHaveBeenCalledTimes(1);
  });

  it("recovers an initial failed connection and clears the recovery flag after open", async () => {
    const client = createClient();
    const query = await watch(client, ["documents", "kb-a"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    source!.emit("error");
    query.changeServer();
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(query.fetch).toHaveBeenCalledTimes(2);
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(query.fetch).toHaveBeenCalledTimes(2);
  });

  it("flushes a pending burst together with reconnect instead of scheduling another refetch", async () => {
    const client = createClient();
    const graph = await watch(client, ["graph", "kb-a"]);
    const documents = await watch(client, ["documents", "kb-a"]);
    const badge = await watch(client, ["alerts", "unread"]);
    const list = await watch(client, ["alerts", "list", "", 0]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    source!.emit("open");
    source!.emit("document");
    source!.emit("graph");
    source!.emit("alert");
    source!.emit("alert");
    source!.emit("error");
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(graph.fetch).toHaveBeenCalledTimes(2);
    expect(documents.fetch).toHaveBeenCalledTimes(2);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    expect(list.fetch).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(SETTLE_MS);
    // 攒着的一阵与重连补刷合并成同一次重取：settle 窗口过去也不再补第二遍
    // （旧仓此处还有 getTimerCount()===0——见文件头注④，泄漏后果由这几行计数承接）
    expect(graph.fetch).toHaveBeenCalledTimes(2);
    expect(documents.fetch).toHaveBeenCalledTimes(2);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    expect(list.fetch).toHaveBeenCalledTimes(2);
  });

  it("keeps normal business events coalesced", async () => {
    const client = createClient();
    const graph = await watch(client, ["graph", "kb-a"]);
    const documents = await watch(client, ["documents", "kb-a"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    source!.emit("open");
    for (let i = 0; i < 12; i++) {
      source!.emit("document");
      source!.emit("graph");
    }
    await vi.advanceTimersByTimeAsync(SETTLE_MS - 1);
    expect(graph.fetch).toHaveBeenCalledTimes(1);
    expect(documents.fetch).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(graph.fetch).toHaveBeenCalledTimes(2);
    expect(documents.fetch).toHaveBeenCalledTimes(2);
  });

  it("ignores late callbacks from the old connection after switching KB", async () => {
    const client = createClient();
    const oldKb = await watch(client, ["graph", "kb-a"]);
    const newKb = await watch(client, ["graph", "kb-b"]);
    const badge = await watch(client, ["alerts", "unread"]);
    const list = await watch(client, ["alerts", "list", "", 0]);
    const old = mount(() => useKbEvents(ref("kb-a")));
    old.source!.emit("open");
    old.source!.emit("error");
    const lateOpen = [...(old.source!.listeners.get("open") ?? [])];
    const lateGraph = [...(old.source!.listeners.get("graph") ?? [])];
    const lateAlert = [...(old.source!.listeners.get("alert") ?? [])];
    old.unmount();
    const next = mount(() => useKbEvents(ref("kb-b")));
    next.source!.emit("open");
    for (const callback of [...lateOpen, ...lateGraph, ...lateAlert]) callback();
    old.source!.emit("open");
    old.source!.emit("document");
    await vi.advanceTimersByTimeAsync(SETTLE_MS);
    expect(old.source!.closed).toBe(true);
    expect(
      [...old.source!.listeners.values()].every(listeners => listeners.size === 0)
    ).toBe(true);
    // 迟到的旧连接回调一个都没生效（旧连接/新连接/告警键都没被多余重取；
    // 旧仓此处还有 getTimerCount()===0——见文件头注④，由这几行计数承接）
    expect(oldKb.fetch).toHaveBeenCalledTimes(1);
    expect(newKb.fetch).toHaveBeenCalledTimes(1);
    expect(badge.fetch).toHaveBeenCalledTimes(1);
    expect(list.fetch).toHaveBeenCalledTimes(1);
    next.source!.emit("error");
    next.source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(oldKb.fetch).toHaveBeenCalledTimes(1);
    expect(newKb.fetch).toHaveBeenCalledTimes(2);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    expect(list.fetch).toHaveBeenCalledTimes(2);
  });

  it("does not subscribe without a selected KB", () => {
    createClient();
    mount(() => useKbEvents(ref(undefined)));
    expect(FakeEventSource.instances).toHaveLength(0);
  });
});

describe("alert stream reconnect", () => {
  it("refreshes both badge and list after missed alerts, without refetching on the initial open", async () => {
    const client = createClient();
    const badge = await watch(client, ["alerts", "unread"]);
    const list = await watch(client, ["alerts", "list", "", 0]);
    const other = await watch(client, ["documents", "kb-a"]);
    const { source } = mount(() => useAlertEvents());
    expect(source!.url).toBe("/api/v1/alerts/events");
    source!.emit("open");
    expect(badge.fetch).toHaveBeenCalledTimes(1);
    expect(list.fetch).toHaveBeenCalledTimes(1);
    source!.emit("error");
    badge.changeServer();
    list.changeServer();
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    expect(list.fetch).toHaveBeenCalledTimes(2);
    expect(other.fetch).toHaveBeenCalledTimes(1);
    await vi.waitFor(() => {
      expect(badge.observer.getCurrentResult().data).toBe("after disconnect");
      expect(list.observer.getCurrentResult().data).toBe("after disconnect");
    });
  });

  it("also recovers when the first alert connection failed before open", async () => {
    const client = createClient();
    const query = await watch(client, ["alerts", "unread"]);
    const { source } = mount(() => useAlertEvents());
    source!.emit("error");
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(query.fetch).toHaveBeenCalledTimes(2);
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(query.fetch).toHaveBeenCalledTimes(2);
  });

  it("removes listeners and ignores late alert callbacks after cleanup", async () => {
    const client = createClient();
    const query = await watch(client, ["alerts", "unread"]);
    const { source, unmount } = mount(() => useAlertEvents());
    source!.emit("open");
    source!.emit("error");
    const lateCallbacks = [...source!.listeners.values()].flatMap(listeners => [...listeners]);
    unmount();
    for (const callback of lateCallbacks) callback();
    expect(source!.closed).toBe(true);
    expect(
      [...source!.listeners.values()].every(listeners => listeners.size === 0)
    ).toBe(true);
    expect(query.fetch).toHaveBeenCalledTimes(1);
  });
});

describe("one notification stream per page (#1028)", () => {
  it("carries alerts on the KB stream, and refreshes them when it recovers", async () => {
    const client = createClient();
    const badge = await watch(client, ["alerts", "unread"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    source!.emit("open");
    source!.emit("alert");
    await vi.advanceTimersByTimeAsync(SETTLE_MS);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    source!.emit("error");
    source!.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(badge.fetch).toHaveBeenCalledTimes(3);
  });

  it("opens no alert stream while a KB stream is open", () => {
    createClient();
    mount(() => useKbEvents(ref("kb-a")));
    mount(() => useAlertEvents(ref(false)));
    expect(FakeEventSource.instances.map(source => source.url)).toEqual([
      "/api/v1/kbs/kb-a/events"
    ]);
  });

  it("gives up the connection after the page has been hidden a while, and catches up on return", async () => {
    const page = fakePage(true);
    pages.push(page);
    const client = createClient();
    const documents = await watch(client, ["documents", "kb-a"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    source!.emit("open");

    page.show(false);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS - 1);
    expect(source!.closed).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(source!.closed).toBe(true);
    expect(
      [...source!.listeners.values()].every(listeners => listeners.size === 0)
    ).toBe(true);

    documents.changeServer();
    page.show(true);
    const reopened = FakeEventSource.instances.at(-1)!;
    expect(reopened).not.toBe(source);
    expect(documents.fetch).toHaveBeenCalledTimes(1);
    reopened.emit("open");
    await vi.advanceTimersByTimeAsync(1);
    expect(documents.fetch).toHaveBeenCalledTimes(2);
  });

  it("keeps the connection through a short absence, and removes its page listener on cleanup", async () => {
    const page = fakePage(true);
    pages.push(page);
    const client = createClient();
    const documents = await watch(client, ["documents", "kb-a"]);
    const { source, unmount } = mount(() => useKbEvents(ref("kb-a")));
    source!.emit("open");
    page.show(false);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS - 1);
    page.show(true);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS);
    expect(source!.closed).toBe(false);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(documents.fetch).toHaveBeenCalledTimes(1);

    page.show(false);
    unmount();
    // 卸载把页面监听摘干净（释放定时器也一并取消——若未取消，键会在下面这
    // 段时间窗里被失效/重连搅动，fetch 计数与连接数都会变）
    expect(page.listenerCount()).toBe(0);
    // 旧仓此处还有 getTimerCount()===0——见文件头注④；本例断言强于旧仓：
    // 卸载后再推进 2×HIDDEN_RELEASE_MS，无重连（连接数不变）、无重取（计数不变）
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS * 2);
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(documents.fetch).toHaveBeenCalledTimes(1);
  });

  it("waits for a page opened in the background to be shown before connecting", () => {
    const page = fakePage(false);
    pages.push(page);
    createClient();
    mount(() => useAlertEvents());
    expect(FakeEventSource.instances).toHaveLength(0);
    page.show(true);
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
