// W1 壳 SSE 测试（沿 domain/useEventReconnect.test.ts 的风格）：可控事件源注入，
// 失效面用真实 vue-query QueryClient 观察。覆盖 useAlertEvents（全局告警流）与
// useKbEvents（库事件流的合并失效 + 断线补刷）。
//
// 收尾冲刺 T1（SSE fetch 化）：事件流改 fetch 流式读（EventSource 带不了
// Authorization 头），桩随嫁接——FakeEventSource 换 FakeEventStream（fetch 流桩）：
// emit 按后端同形帧写入、drop 掉线（实现按退避自动重连，重连成功即旧
// emit("open")）、signal.aborted 即旧 source.closed。断言与语义逐字保留。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { VueQueryPlugin, QueryClient, type QueryKey } from "@tanstack/vue-query";
import { useAlertEvents } from "@/composables/useAlertEvents";
import { useKbEvents } from "@/composables/useKbEvents";
import { STREAM_KEYS } from "@/domain/queryDefaults";
import { RECONNECT_MS } from "@/domain/eventStream";

const enc = new TextEncoder();

class FakeEventStream {
  static instances: FakeEventStream[] = [];
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
  /** 一个事件帧（后端线上同形：`event:` 冒号后无空格） */
  emit(type: string) {
    this.controller.enqueue(enc.encode(`event:${type}\ndata: {}\n\n`));
  }
  /** 掉线（=旧 emit("error")）：错误掉流，实现按退避自动重连 */
  drop() {
    this.controller.error(new Error("connection lost"));
  }
}

const sseFetch = vi.fn((input: unknown, init?: RequestInit) => {
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

/** 挂一个宿主组件执行 composable（需要 VueQueryPlugin 的 inject 上下文）；
 *  unmount 即 onScopeDispose。 */
function mountComposable(
  queryClient: QueryClient,
  use: () => void
): { source: () => FakeEventStream | undefined; unmount: () => void } {
  const Host = defineComponent({
    setup() {
      use();
      return () => h("div");
    }
  });
  const el = document.createElement("div");
  document.body.appendChild(el);
  const wrapper = mount(Host, {
    global: { plugins: [[VueQueryPlugin, { queryClient }]] },
    attachTo: el
  });
  return {
    source: () => FakeEventStream.instances.at(-1),
    unmount: () => {
      wrapper.unmount();
      el.remove();
    }
  };
}

/** 造一个真实 client 并记录 invalidateQueries 的调用键 */
function makeClient() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } }
  });
  const invalidated: QueryKey[] = [];
  const spy = vi.spyOn(client, "invalidateQueries").mockImplementation(
    (
      arg?: { queryKey?: QueryKey },
      _options?: Record<string, unknown>
    ) => {
      invalidated.push((arg?.queryKey ?? []) as QueryKey);
      return Promise.resolve();
    }
  );
  return { client, invalidated, spy };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("fetch", sseFetch);
  FakeEventStream.instances = [];
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("useAlertEvents：全局告警流（不按库）", () => {
  it("active=false 什么都不订（库事件流顺带送告警，一页只占一条连接）", () => {
    const { client } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useAlertEvents(ref(false))
    );
    expect(FakeEventStream.instances).toHaveLength(0);
    expect(source()).toBeUndefined();
    unmount();
    void client;
  });

  it("闸门翻 true 补订（W1 M-2 收口：settled 解析完成才放开告警流——壳的接线传 settled&&!kb）", async () => {
    const { client } = makeClient();
    const gate = ref(false);
    const { source, unmount } = mountComposable(client, () => useAlertEvents(gate));
    expect(FakeEventStream.instances).toHaveLength(0);
    gate.value = true;
    await Promise.resolve();
    expect(source()).toBeDefined();
    expect(source()!.url).toBe("/api/v1/alerts/events");
    unmount();
    void client;
  });

  it("订阅 /api/v1/alerts/events：alert 事件与重连恢复都失效 [\"alerts\"]", async () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () => useAlertEvents(true));
    const src = source()!;
    expect(src.url).toBe("/api/v1/alerts/events");

    src.emit("alert");
    await vi.advanceTimersByTimeAsync(0);
    expect(invalidated).toEqual([["alerts"]]);

    // 断线期间的告警不会回放：恢复时同时补刷角标与列表（旧仓注释原话）
    src.drop();
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    expect(invalidated).toEqual([["alerts"], ["alerts"]]);
    unmount();
    // 卸载关掉的是此刻活着的连接（重连后的新实例）；掉线的旧 src 本就已作废
    expect(FakeEventStream.instances.at(-1)!.closed).toBe(true);
  });
});

describe("useKbEvents：库事件流——一阵事件只换一次重取", () => {
  it("同一键在静默窗口内爆发多次只失效一次", async () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useKbEvents(ref("kb1"))
    );
    const src = source()!;
    expect(src.url).toBe("/api/v1/kbs/kb1/events");

    for (let i = 0; i < 12; i++) src.emit("graph");
    await vi.advanceTimersByTimeAsync(0);
    expect(invalidated).toEqual([]);
    await vi.advanceTimersByTimeAsync(299);
    expect(invalidated).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    // graph 事件连着本体回归检查一起失效（旧仓事件→键映射同表）
    expect(invalidated).toEqual([["graph"], ["ontologyRegressions", "kb1"]]);
    unmount();
    expect(src.closed).toBe(true);
  });

  it("断线重连补刷：STREAM_KEYS + alerts 按目标库补上并立即 flush", async () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useKbEvents(ref("kb1"))
    );
    const src = source()!;

    src.drop();
    // 事件没有回放：断线后恢复必须补刷漏掉的变化（旧仓注释原话）——
    // 掉线即旧 emit("error")，退避到点重连成功即旧 emit("open")
    await vi.advanceTimersByTimeAsync(RECONNECT_MS);
    const keys = invalidated.map(k => JSON.stringify(k));
    for (const head of STREAM_KEYS)
      expect(keys).toContain(JSON.stringify([head, "kb1"]));
    expect(keys).toContain(JSON.stringify(["alerts"]));
    // 重连补刷走的是立即 flush，不等静默期
    expect(vi.getTimerCount()).toBe(0);
    unmount();
  });

  it("换库：旧连接关闭、新连接指向新库，旧连接攒着的先刷掉", async () => {
    const { client, invalidated } = makeClient();
    const kbId = ref("kb1");
    const { source, unmount } = mountComposable(client, () => useKbEvents(kbId));
    const first = source()!;

    first.emit("graph");
    await vi.advanceTimersByTimeAsync(0);
    kbId.value = "kb2";
    await vi.advanceTimersByTimeAsync(0);
    expect(first.closed).toBe(true);
    // 旧连接攒着的失效被刷掉而不是丢掉（旧仓 cleanup 语义）
    expect(invalidated).toEqual([["graph"], ["ontologyRegressions", "kb1"]]);
    const second = FakeEventStream.instances.at(-1)!;
    expect(second.url).toBe("/api/v1/kbs/kb2/events");
    unmount();
  });

  it("卸载时把攒着的刷掉而不是丢掉", async () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useKbEvents(ref("kb1"))
    );
    const src = source()!;
    src.emit("document");
    await vi.advanceTimersByTimeAsync(0);
    expect(invalidated).toEqual([]);
    unmount();
    expect(invalidated).toEqual([
      ["documents", "kb1"],
      ["graph"]
    ]);
  });
});
