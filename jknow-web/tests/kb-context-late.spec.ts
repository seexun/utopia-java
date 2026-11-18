// 最小复现：workspaces 数据晚到时，kbs 查询的 key/enabled 是否随响应式链更新。
import { describe, it, expect, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { VueQueryPlugin } from "@tanstack/vue-query";
import { queryClient } from "@/store/knowledge/query";
import { useKbContext } from "@/composables/useKbContext";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { store as globalPinia } from "@/store/knowledge";

const { workspacesMock, kbsMock, kbCreateMock } = vi.hoisted(() => ({
  workspacesMock: vi.fn(),
  kbsMock: vi.fn(),
  kbCreateMock: vi.fn()
}));
vi.mock("@/api/knowledge/workspaces", () => ({
  workspaces: workspacesMock,
  workspaceRole: vi.fn(),
  myKbs: vi.fn()
}));
vi.mock("@/api/knowledge/kbs", () => ({
  kbs: kbsMock,
  createKb: kbCreateMock,
  kbDetail: vi.fn()
}));

const WS = [{ id: "ws1", org_id: "o1", name: "Default Workspace", created_at: "x" }];
const KBS = [{ id: "kb1", workspace_id: "ws1", name: "General" }];

describe("useKbContext：workspaces 晚到 → kbs 查询随链启用", () => {
  it("workspaces 解析后 workspace 非空、kbs 查询按实 id 建 key 并取数", async () => {
    localStorage.clear();
    queryClient.clear();
    useKbContextHook().$reset();
    // 工作区数据 200ms 后才到（模拟冷启动晚到）
    let resolveWs: (v: unknown) => void = () => {};
    workspacesMock.mockImplementation(
      () => new Promise(res => (resolveWs = res))
    );
    kbsMock.mockResolvedValue(KBS);

    const Host = defineComponent({
      setup() {
        const ctx = useKbContext();
        (Host as unknown as { __ctx: unknown }).__ctx = ctx;
        return () => h("div");
      }
    });
    const wrapper = mount(Host, {
      global: { plugins: [globalPinia, [VueQueryPlugin, { queryClient }]] }
    });

    await flushPromises();
    const qc = queryClient;
    // 尚无数据：kbs 查询不应取数
    expect(qc.getQueryCache().find({ queryKey: ["workspaces"] })?.state.status).toBe("pending");

    resolveWs(WS);
    await flushPromises();
    expect(qc.getQueryCache().find({ queryKey: ["workspaces"] })?.state.status).toBe("success");

    // workspaces 到手后：kbs 查询应按实 id 建 key 并发起请求
    await flushPromises();
    await flushPromises();
    expect(kbsMock).toHaveBeenCalledWith("ws1");
    expect(qc.getQueryCache().find({ queryKey: ["kbs", "ws1"] })).toBeDefined();

    wrapper.unmount();
  });
});
