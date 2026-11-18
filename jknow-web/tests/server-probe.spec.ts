// 壳级探活（任务 7，收 W1 遗留②）：health 60s 周期探针——后端中途挂掉翻
// ServerDown（对照旧仓 me 查询 refetch 的隐式兜底），恢复自愈清位；401 是
// 会话问题不翻面。沿 server-down.spec.ts 的 mock 口径 + shell-sse.spec.ts 的
// 宿主挂载口径。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { useServerProbe, SERVER_PROBE_MS } from "@/composables/useServerProbe";
import { useShellStoreHook } from "@/store/modules/shell";
import { ApiError } from "@/api/knowledge/client";

const { healthMock } = vi.hoisted(() => ({ healthMock: vi.fn() }));
vi.mock("@/api/knowledge/admin", () => ({
  health: healthMock
}));

/** 假定时器下等查询落定（flushPromises 走 setTimeout 会被假定时器冻住，不用） */
const tick = () => vi.advanceTimersByTimeAsync(1);

function mountProbe() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } }
  });
  const Host = defineComponent({
    setup() {
      useServerProbe();
      return () => h("div");
    }
  });
  const wrapper = mount(Host, {
    global: { plugins: [[VueQueryPlugin, { queryClient: client }]] }
  });
  return {
    settle: tick,
    unmount: () => {
      wrapper.unmount();
      client.clear();
    }
  };
}

beforeEach(() => {
  vi.useFakeTimers();
  healthMock.mockReset();
  useShellStoreHook().markAlive();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("壳级探活：后端中途挂掉的翻面与自愈", () => {
  it("探针失败（5xx）翻 ServerDown——壳开着时后端挂掉也有人管", async () => {
    healthMock.mockRejectedValue(new ApiError(502, "Bad Gateway"));
    const probe = mountProbe();
    await probe.settle();
    expect(useShellStoreHook().serverDown).toBe(true);
    probe.unmount();
  });

  it("401 不翻面：那是会话问题，守卫送登录页", async () => {
    healthMock.mockRejectedValue(new ApiError(401, "Not signed in"));
    const probe = mountProbe();
    await probe.settle();
    expect(useShellStoreHook().serverDown).toBe(false);
    probe.unmount();
  });

  it("网络断（status 0）同样按不可达处置", async () => {
    healthMock.mockRejectedValue(new ApiError(0, "Network Error"));
    const probe = mountProbe();
    await probe.settle();
    expect(useShellStoreHook().serverDown).toBe(true);
    probe.unmount();
  });

  it("恢复自愈：挂掉后的下一个周期探到成功 → 清位（ServerDown 不用手点刷新）", async () => {
    healthMock.mockRejectedValue(new ApiError(502, "Bad Gateway"));
    const probe = mountProbe();
    await probe.settle();
    expect(useShellStoreHook().serverDown).toBe(true);

    healthMock.mockResolvedValue({ status: "ok", name: "utopia", version: "0.1.0" });
    await vi.advanceTimersByTimeAsync(SERVER_PROBE_MS);
    expect(useShellStoreHook().serverDown).toBe(false);
    probe.unmount();
  });

  it("周期续探：成功态下每 60s 再探一次（翻面才能及时）", async () => {
    healthMock.mockResolvedValue({ status: "ok", name: "utopia", version: "0.1.0" });
    const probe = mountProbe();
    await probe.settle();
    expect(healthMock).toHaveBeenCalledTimes(1);
    // 间隔定时器锚在首拍落定时：settle 已走过 1ms，这里再差 2ms 不触发
    await vi.advanceTimersByTimeAsync(SERVER_PROBE_MS - 2);
    expect(healthMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(2);
    expect(healthMock).toHaveBeenCalledTimes(2);
    expect(useShellStoreHook().serverDown).toBe(false);
    probe.unmount();
  });
});
