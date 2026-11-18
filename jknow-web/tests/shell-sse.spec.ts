// W1 壳 SSE 测试（沿 domain/useEventReconnect.test.ts 的风格）：可控事件源注入，
// 失效面用真实 vue-query QueryClient 观察。覆盖 useAlertEvents（全局告警流）与
// useKbEvents（库事件流的合并失效 + 断线补刷）。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { VueQueryPlugin, QueryClient, type QueryKey } from "@tanstack/vue-query";
import { useAlertEvents } from "@/composables/useAlertEvents";
import { useKbEvents } from "@/composables/useKbEvents";
import { STREAM_KEYS } from "@/domain/queryDefaults";

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

/** 挂一个宿主组件执行 composable（需要 VueQueryPlugin 的 inject 上下文）；
 *  unmount 即 onScopeDispose。 */
function mountComposable(
  queryClient: QueryClient,
  use: () => void
): { source: () => FakeEventSource | undefined; unmount: () => void } {
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
    source: () => FakeEventSource.instances.at(-1),
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
  vi.stubGlobal("EventSource", FakeEventSource);
  FakeEventSource.instances = [];
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
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(source()).toBeUndefined();
    unmount();
    void client;
  });

  it("闸门翻 true 补订（W1 M-2 收口：settled 解析完成才放开告警流——壳的接线传 settled&&!kb）", async () => {
    const { client } = makeClient();
    const gate = ref(false);
    const { source, unmount } = mountComposable(client, () => useAlertEvents(gate));
    expect(FakeEventSource.instances).toHaveLength(0);
    gate.value = true;
    await Promise.resolve();
    expect(source()).toBeDefined();
    expect(source()!.url).toBe("/api/v1/alerts/events");
    unmount();
    void client;
  });

  it("订阅 /api/v1/alerts/events：alert 事件与重连恢复都失效 [\"alerts\"]", () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () => useAlertEvents(true));
    const src = source()!;
    expect(src.url).toBe("/api/v1/alerts/events");

    src.emit("alert");
    expect(invalidated).toEqual([["alerts"]]);

    // 断线期间的告警不会回放：恢复时同时补刷角标与列表（旧仓注释原话）
    src.emit("error");
    src.emit("open");
    expect(invalidated).toEqual([["alerts"], ["alerts"]]);
    unmount();
    expect(src.closed).toBe(true);
  });
});

describe("useKbEvents：库事件流——一阵事件只换一次重取", () => {
  it("同一键在静默窗口内爆发多次只失效一次", () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useKbEvents(ref("kb1"))
    );
    const src = source()!;
    expect(src.url).toBe("/api/v1/kbs/kb1/events");

    for (let i = 0; i < 12; i++) src.emit("graph");
    expect(invalidated).toEqual([]);
    vi.advanceTimersByTime(299);
    expect(invalidated).toEqual([]);
    vi.advanceTimersByTime(1);
    // graph 事件连着本体回归检查一起失效（旧仓事件→键映射同表）
    expect(invalidated).toEqual([["graph"], ["ontologyRegressions", "kb1"]]);
    unmount();
    expect(src.closed).toBe(true);
  });

  it("断线重连补刷：STREAM_KEYS + alerts 按目标库补上并立即 flush", () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useKbEvents(ref("kb1"))
    );
    const src = source()!;

    src.emit("error");
    src.emit("open");
    // 事件没有回放：断线后恢复必须补刷漏掉的变化（旧仓注释原话）
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
    kbId.value = "kb2";
    await Promise.resolve();
    expect(first.closed).toBe(true);
    // 旧连接攒着的失效被刷掉而不是丢掉（旧仓 cleanup 语义）
    expect(invalidated).toEqual([["graph"], ["ontologyRegressions", "kb1"]]);
    const second = FakeEventSource.instances.at(-1)!;
    expect(second.url).toBe("/api/v1/kbs/kb2/events");
    unmount();
  });

  it("卸载时把攒着的刷掉而不是丢掉", () => {
    const { client, invalidated } = makeClient();
    const { source, unmount } = mountComposable(client, () =>
      useKbEvents(ref("kb1"))
    );
    const src = source()!;
    src.emit("document");
    expect(invalidated).toEqual([]);
    unmount();
    expect(invalidated).toEqual([
      ["documents", "kb1"],
      ["graph"]
    ]);
  });
});
