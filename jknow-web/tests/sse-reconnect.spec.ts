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
//   ⑤ 收尾冲刺 T1（SSE fetch 化）：事件流改 fetch 流式读（EventSource 带不了
//      Authorization 头），可控事件源从 FakeEventSource 换 FakeEventStream：
//      emit 写 `event:<type>` 帧（后端同形）、drop 掉线（=旧 emit("error")），
//      重连由实现按退避自动做——退避到点重连成功即旧 emit("open")；
//      blackout 模拟断线期间网络不通（重试全败、不产生恢复）；
//      帧 RETRY 字段压退避，让「攒着的一阵与重连同拍」可造（旧测试靠同步
//      emit("error")+emit("open") 压缩时间）。断言与语义逐字保留。
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
import { HIDDEN_RELEASE_MS, RECONNECT_MS } from "@/domain/eventStream";

const enc = new TextEncoder();

class FakeEventStream {
  static instances: FakeEventStream[] = [];
  /** 断线期间网络不通：fetch 一律失败（模拟掉线到恢复之间的整段不可达） */
  static blackout = false;
  private dropped = false;
  /** 只在 fetch 桩开流时入册——一条 fetch 一条连接，恰好一次 */
  static open(
    url: string,
    signal: AbortSignal,
    controller: ReadableStreamDefaultController<Uint8Array>
  ) {
    FakeEventStream.instances.push(new FakeEventStream(url, signal, controller));
  }
  constructor(
    readonly url: string,
    readonly signal: AbortSignal,
    private readonly controller: ReadableStreamDefaultController<Uint8Array>
  ) {}
  /** 连接被实现关掉（让出/卸载）——旧 source.closed 的对等物 */
  get closed() {
    return this.signal.aborted;
  }
  /** 一个事件帧（后端线上同形：`event:` 冒号后无空格）；RETRY 压下一次退避。
   *  掉线后的流已不可写——迟到帧静默丢弃（正是被测的不作数语义） */
  emit(type: string, opts: { retry?: number } = {}) {
    if (this.dropped) return;
    const retry = opts.retry !== undefined ? `retry: ${opts.retry}\n` : "";
    this.controller.enqueue(
      enc.encode(`event:${type}\ndata: {}\n${retry}\n`)
    );
  }
  /** 掉线（=旧 emit("error")）：错误掉流，实现按退避自动重连 */
  drop() {
    if (this.dropped) return;
    this.dropped = true;
    this.controller.error(new Error("connection lost"));
  }
}

const sseFetch = vi.fn((input: unknown, init?: RequestInit) => {
  if (FakeEventStream.blackout) {
    return Promise.reject(new TypeError("Failed to fetch"));
  }
  const body = new ReadableStream<Uint8Array>({
    // start 同步执行：订阅一发生即可从 instances 取到这条连接（对等旧 new EventSource 的同步性）
    start: controller => FakeEventStream.open(String(input), init?.signal as AbortSignal, controller)
  });
  return Promise.resolve(
    new Response(body, {
      status: 200,
      headers: { "content-type": "text/event-stream" }
    })
  );
});

/** 宿主组件挂载执行 composable（需要 VueQueryPlugin 的 inject 上下文）；
 *  unmount 即 onScopeDispose——旧仓 mock useEffect 直调 effect 的对等物。 */
function mount(hook: () => void): { source: FakeEventStream | undefined; unmount: () => void } {
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
  return { source: FakeEventStream.instances.at(-1), unmount };
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
  vi.stubGlobal("fetch", sseFetch);
  FakeEventStream.instances = [];
  FakeEventStream.blackout = false;
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
    // 首次正常连接（fetch 已解析）不补刷（=旧 emit("open") 的首个 open）
    await vi.advanceTimersByTimeAsync(0);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(1);

    source!.drop();
    FakeEventStream.blackout = true;
    for (const query of recoveredQueries) query.changeServer();
    // staletime 到期只让缓存变旧，不会自动请求；断线期间（网络不通，重试全败）
    // 完全没有业务事件、也没有恢复。
    await vi.advanceTimersByTimeAsync(STREAM_STALE_MS + 1);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(1);

    FakeEventStream.blackout = false;
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    // 重连不等普通事件的 300ms 合并窗口（失效分发落在下一个时钟滴答，advance 一格即达）
    await vi.advanceTimersByTimeAsync(1);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(2);
    await vi.waitFor(() => {
      for (const query of recoveredQueries)
        expect(query.observer.getCurrentResult().data).toBe("after disconnect");
    });
    // 恢复标记已清：没有新的掉线，连接健康着推进整个 settle 窗口也不再补第二遍
    // （旧第三个 emit("open") 的本义——spurious open 不再补刷）
    await vi.advanceTimersByTimeAsync(SETTLE_MS);
    for (const query of recoveredQueries) expect(query.fetch).toHaveBeenCalledTimes(2);
    expect(client.getQueryState(inactiveKey)?.isInvalidated).toBe(true);
    expect(otherKb.fetch).toHaveBeenCalledTimes(1);
    expect(unrelated.fetch).toHaveBeenCalledTimes(1);
  });

  it("recovers an initial failed connection and clears the recovery flag after open", async () => {
    const client = createClient();
    const query = await watch(client, ["documents", "kb-a"]);
    FakeEventStream.blackout = true;
    mount(() => useKbEvents(ref("kb-a")));
    // 初连即失败，之后多次重试全败——同一段断线只该补刷一次
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    FakeEventStream.blackout = false;
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await vi.advanceTimersByTimeAsync(1);
    expect(query.fetch).toHaveBeenCalledTimes(2);
    // 恢复标记已清：连接健康着再推进也没有第二次补刷（旧第二个 emit("open")）
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(query.fetch).toHaveBeenCalledTimes(2);
  });

  it("flushes a pending burst together with reconnect instead of scheduling another refetch", async () => {
    const client = createClient();
    const graph = await watch(client, ["graph", "kb-a"]);
    const documents = await watch(client, ["documents", "kb-a"]);
    const badge = await watch(client, ["alerts", "unread"]);
    const list = await watch(client, ["alerts", "list", "", 0]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    await vi.advanceTimersByTimeAsync(0);
    source!.emit("document");
    // RETRY 压退避：让重连赶在 300ms 合并窗口收口之前（旧测试靠同步
    // emit("error")+emit("open") 压缩时间，此处用规范字段同效）
    source!.emit("graph", { retry: 10 });
    source!.emit("alert");
    source!.emit("alert");
    await vi.advanceTimersByTimeAsync(0);
    source!.drop();
    await vi.advanceTimersByTimeAsync(10);
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
    await vi.advanceTimersByTimeAsync(0);
    for (let i = 0; i < 12; i++) {
      source!.emit("document");
      source!.emit("graph");
    }
    await vi.advanceTimersByTimeAsync(0);
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
    await vi.advanceTimersByTimeAsync(0);
    // 在途帧（emit 后未及分发就卸载）+ 掉线后旧连接的迟到帧：都不能失效新页面
    old.source!.emit("graph");
    old.source!.drop();
    old.unmount();
    old.source!.emit("document");
    old.source!.emit("alert");
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    // 卸载释放了连接、也取消了重试：没有第二条连接冒出来（实例数不变）
    expect(FakeEventStream.instances).toHaveLength(1);
    // 迟到的旧连接事件一个都没生效（旧连接/新连接/告警键都没被多余重取；
    // 旧仓此处还有 getTimerCount()===0——见文件头注④，由这几行计数承接）
    expect(oldKb.fetch).toHaveBeenCalledTimes(1);
    expect(newKb.fetch).toHaveBeenCalledTimes(1);
    expect(badge.fetch).toHaveBeenCalledTimes(1);
    expect(list.fetch).toHaveBeenCalledTimes(1);
    const next = mount(() => useKbEvents(ref("kb-b")));
    await vi.advanceTimersByTimeAsync(0);
    next.source!.drop();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await vi.advanceTimersByTimeAsync(1);
    expect(oldKb.fetch).toHaveBeenCalledTimes(1);
    expect(newKb.fetch).toHaveBeenCalledTimes(2);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    expect(list.fetch).toHaveBeenCalledTimes(2);
  });

  it("does not subscribe without a selected KB", () => {
    createClient();
    mount(() => useKbEvents(ref(undefined)));
    expect(FakeEventStream.instances).toHaveLength(0);
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
    await vi.advanceTimersByTimeAsync(0);
    expect(badge.fetch).toHaveBeenCalledTimes(1);
    expect(list.fetch).toHaveBeenCalledTimes(1);
    source!.drop();
    badge.changeServer();
    list.changeServer();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
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
    FakeEventStream.blackout = true;
    mount(() => useAlertEvents());
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    FakeEventStream.blackout = false;
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await vi.advanceTimersByTimeAsync(1);
    expect(query.fetch).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(query.fetch).toHaveBeenCalledTimes(2);
  });

  it("removes listeners and ignores late alert callbacks after cleanup", async () => {
    const client = createClient();
    const query = await watch(client, ["alerts", "unread"]);
    const { source, unmount } = mount(() => useAlertEvents());
    await vi.advanceTimersByTimeAsync(0);
    // 在途帧（卸载后才轮到分发）+ 卸载后再掉线/再发帧：一律不作数
    source!.emit("alert");
    unmount();
    source!.emit("alert");
    source!.drop();
    expect(source!.closed).toBe(true);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(FakeEventStream.instances).toHaveLength(1);
    expect(query.fetch).toHaveBeenCalledTimes(1);
  });
});

describe("one notification stream per page (#1028)", () => {
  it("carries alerts on the KB stream, and refreshes them when it recovers", async () => {
    const client = createClient();
    const badge = await watch(client, ["alerts", "unread"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    await vi.advanceTimersByTimeAsync(0);
    source!.emit("alert");
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(SETTLE_MS);
    expect(badge.fetch).toHaveBeenCalledTimes(2);
    source!.drop();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    await vi.advanceTimersByTimeAsync(1);
    expect(badge.fetch).toHaveBeenCalledTimes(3);
  });

  it("opens no alert stream while a KB stream is open", () => {
    createClient();
    mount(() => useKbEvents(ref("kb-a")));
    mount(() => useAlertEvents(ref(false)));
    expect(FakeEventStream.instances.map(source => source.url)).toEqual([
      "/api/v1/kbs/kb-a/events"
    ]);
  });

  it("gives up the connection after the page has been hidden a while, and catches up on return", async () => {
    const page = fakePage(true);
    pages.push(page);
    const client = createClient();
    const documents = await watch(client, ["documents", "kb-a"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    await vi.advanceTimersByTimeAsync(0);

    page.show(false);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS - 1);
    expect(source!.closed).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(source!.closed).toBe(true);

    documents.changeServer();
    page.show(true);
    const reopened = FakeEventStream.instances.at(-1)!;
    expect(reopened).not.toBe(source);
    expect(documents.fetch).toHaveBeenCalledTimes(1);
    // 回前台重连成功（=旧 reopened.emit("open")）即补刷
    await vi.advanceTimersByTimeAsync(1);
    expect(documents.fetch).toHaveBeenCalledTimes(2);
  });

  it("keeps the connection through a short absence, and removes its page listener on cleanup", async () => {
    const page = fakePage(true);
    pages.push(page);
    const client = createClient();
    const documents = await watch(client, ["documents", "kb-a"]);
    const { source, unmount } = mount(() => useKbEvents(ref("kb-a")));
    await vi.advanceTimersByTimeAsync(0);
    page.show(false);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS - 1);
    page.show(true);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS);
    expect(source!.closed).toBe(false);
    expect(FakeEventStream.instances).toHaveLength(1);
    expect(documents.fetch).toHaveBeenCalledTimes(1);

    page.show(false);
    unmount();
    // 卸载把页面监听摘干净（释放定时器也一并取消——若未取消，键会在下面这
    // 段时间窗里被失效/重连搅动，fetch 计数与连接数都会变）
    expect(page.listenerCount()).toBe(0);
    // 旧仓此处还有 getTimerCount()===0——见文件头注④；本例断言强于旧仓：
    // 卸载后再推进 2×HIDDEN_RELEASE_MS，无重连（连接数不变）、无重取（计数不变）
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS * 2);
    expect(FakeEventStream.instances).toHaveLength(1);
    expect(documents.fetch).toHaveBeenCalledTimes(1);
  });

  it("gives up a pending reconnect too when the page hides through a dropout (#1028 让出闸)", async () => {
    const page = fakePage(true);
    pages.push(page);
    const client = createClient();
    const documents = await watch(client, ["documents", "kb-a"]);
    const { source } = mount(() => useKbEvents(ref("kb-a")));
    await vi.advanceTimersByTimeAsync(0);
    // 掉线（退避挂着）且断网：重试全败，页面随即藏起来直到让出到点
    source!.drop();
    FakeEventStream.blackout = true;
    page.show(false);
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS + 1);
    // 让出必须把挂起的退避一并取消：网络恢复（黑障解除）后不再重建——隐藏的页
    // 不持有连接（若只看 controller 放行挂着的 retry，此处会冒出第二条连接）
    FakeEventStream.blackout = false;
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS * 2);
    expect(FakeEventStream.instances).toHaveLength(1);
    expect(documents.fetch).toHaveBeenCalledTimes(1);
    // 回前台照常重连补刷（missed 在让出时打上，重连成功即刷）
    page.show(true);
    await vi.advanceTimersByTimeAsync(RECONNECT_MS + 1);
    expect(FakeEventStream.instances).toHaveLength(2);
    expect(documents.fetch).toHaveBeenCalledTimes(2);
  });

  it("waits for a page opened in the background to be shown before connecting", async () => {
    const page = fakePage(false);
    pages.push(page);
    createClient();
    mount(() => useAlertEvents());
    expect(FakeEventStream.instances).toHaveLength(0);
    page.show(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventStream.instances).toHaveLength(1);
  });
});
