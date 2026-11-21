// tests/eventStream.spec.ts —— SSE 竞态窗口固化（final-ledger-sweep §4）
// 审计结论先行：eventStream 三窗口逻辑闭环无显性洞；本套用例把契约钉死：
// 测出洞 → 同任务修；无洞 → 测试即交付。
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

const { subscribeEvents, RECONNECT_MS, HIDDEN_RELEASE_MS } = await import("@/domain/eventStream");

vi.mock("@/domain/api", () => ({ bearerHeaders: () => ({ Authorization: "Bearer t" }) }));

/** 可控的 SSE 假流：手动吐帧/断流 */
function fakeStream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
  return {
    res: { ok: true, status: 200, body: stream },
    push: (text: string) => controller.enqueue(new TextEncoder().encode(text)),
    drop: () => controller.close(),
  };
}

describe("subscribeEvents 竞态窗口", () => {
  let streams: ReturnType<typeof fakeStream>[];
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    streams = [];
    fetchMock = vi.fn(async () => { const s = fakeStream(); streams.push(s); return s.res as unknown as Response; });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  /** 推进到 fetch 落定 + 微任务排空（connect 的 await 全走完） */
  const settle = () => vi.advanceTimersByTimeAsync(0);

  it("卸载后迟到帧不作数：dispose 后 reader 吐帧不触达 handlers", async () => {
    const onEvent = vi.fn();
    const dispose = subscribeEvents("/sse", { tick: onEvent });
    await settle();
    expect(streams.length).toBe(1);
    dispose();
    streams[0].push("event: tick\ndata: {}\n\n");
    await settle();
    expect(onEvent).not.toHaveBeenCalled();
  });

  it("重连窗口 close：流断排程 retry 期间 dispose，不再重建连接", async () => {
    const dispose = subscribeEvents("/sse", {});
    await settle();
    streams[0].drop();               // 服务端断流 → 排程 retry
    await settle();
    dispose();                        // retry 计时器挂着时卸载
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(1);  // 没有第二条连接
  });

  it("release 与重连交错：hidden 让出→visible 重连成功触发 onRecover 补刷", async () => {
    const onRecover = vi.fn();
    subscribeEvents("/sse", {}, onRecover);
    await settle();
    // 藏起来满 HIDDEN_RELEASE_MS：让出连接 + missed 打标
    document.dispatchEvent(new Event("visibilitychange"));
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS + 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);  // 连接已让出（fetch 未再调）
    // 回前台：重建连接，missed=true → onRecover 补刷一次
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onRecover).toHaveBeenCalledTimes(1);
  });

  it("open 重入闸：controller 占位期间 visible 抖动不开第二条连接", async () => {
    subscribeEvents("/sse", {});
    await settle();
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    document.dispatchEvent(new Event("visibilitychange"));  // 立刻再触发
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
