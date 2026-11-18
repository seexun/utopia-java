// 审查收口：告警面板挂载型测试。
// I-1 翻页 1 基：初渲染 offset 0、点"下一页"offset=PAGE、改搜索词回第一页；
// I-4 ×n 计数：一模一样的行并成一条且记 ×n，「还有 N 条」按原始条数。
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { VueQueryPlugin } from "@tanstack/vue-query";
import AlertBell from "@/views/utopia/alert-bell/index.vue";
import { S } from "@/i18n";
import type { AlertGroup } from "@/api/knowledge/types";

const { alertsMock, unreadMock } = vi.hoisted(() => ({
  alertsMock: vi.fn(),
  unreadMock: vi.fn()
}));
vi.mock("@/api/knowledge/alerts", () => ({
  alerts: alertsMock,
  alertsUnread: unreadMock,
  alertReadGroup: vi.fn(),
  alertsReadAll: vi.fn(),
  requeueJobs: vi.fn()
}));
vi.mock("@/utils/message", () => ({ message: vi.fn() }));

function group(over: Partial<AlertGroup> = {}): AlertGroup {
  return {
    kb_id: "kb1",
    kb_name: "General",
    kind: "source.sync_failed",
    severity: "error",
    count: 1,
    unread: 1,
    latest_at: "2026-11-14T08:00:00Z",
    earliest_at: "2026-11-14T08:00:00Z",
    lines: [{ error: "boom" }],
    ...over
  };
}

let mounted: ReturnType<typeof mount> | null = null;

async function mountBell() {
  mounted = mount(AlertBell, {
    global: {
      plugins: [ElementPlus, [VueQueryPlugin, { queryClient: undefined as never }]]
    }
  });
  await flushPromises();
  return mounted;
}

beforeEach(() => {
  alertsMock.mockReset();
  unreadMock.mockReset();
  unreadMock.mockResolvedValue({ unread: 0 });
});

afterEach(() => {
  mounted?.unmount();
  mounted = null;
});

describe("告警面板翻页（审查 I-1：页码 1 基）", () => {
  it("初渲染即请求 offset 0（0 基时代经翻页器不可达的那一页）", async () => {
    alertsMock.mockResolvedValue({ items: [group()], total: 1 });
    await mountBell();
    expect(alertsMock).toHaveBeenCalledWith({ q: "", limit: 8, offset: 0 });
  });

  it("点「下一页」→ 请求 offset=PAGE；改搜索词 → 回第一页 offset 0", async () => {
    alertsMock.mockImplementation((o: { offset?: number }) => ({
      items: [group({ lines: [{ error: `page-${(o.offset ?? 0) / 8 + 1}` }] })],
      total: 16
    }));
    const wrapper = await mountBell();
    expect(alertsMock).toHaveBeenLastCalledWith({ q: "", limit: 8, offset: 0 });

    // 打开弹层（面板内容 teleported 到 body）再点「下一页」
    await wrapper.find(".alert-bell-trigger").trigger("click");
    await flushPromises();
    const next = document.querySelector(".alert-bell-pop .btn-next") as HTMLElement;
    expect(next).toBeTruthy();
    next.click();
    await flushPromises();
    expect(alertsMock).toHaveBeenLastCalledWith({ q: "", limit: 8, offset: 8 });
    // 面板显示第二页数据
    expect(document.querySelector(".alert-bell-pop")?.textContent).toContain(
      "page-2"
    );

    // 改搜索词：回第一页（watch(q) 重置为 1）
    const input = document.querySelector(
      ".alert-bell-pop input"
    ) as HTMLInputElement;
    input.value = "sync";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    await flushPromises();
    expect(alertsMock).toHaveBeenLastCalledWith({ q: "sync", limit: 8, offset: 0 });
  });
});

describe("明细行 ×n 计数（审查 I-4：并的是显示，不是计数）", () => {
  it("一模一样的行并成一条并记 ×n；「还有 N 条」按原始条数", async () => {
    alertsMock.mockResolvedValue({
      items: [
        group({
          kind: "llm.out_of_credit",
          count: 5,
          lines: [{ error: "boom" }, { error: "boom" }, { error: "other" }]
        })
      ],
      total: 1
    });
    const wrapper = await mountBell();
    await wrapper.find(".alert-bell-trigger").trigger("click");
    await flushPromises();

    const text = document.querySelector(".alert-bell-pop")?.textContent ?? "";
    expect(text).toContain("boom ×2");
    expect(text).toContain("other");
    // 原始明细 3 行、整组 5 次 → 还有 2 条
    expect(text).toContain(S.alerts.andMore(2));
    // 去重前「boom」只出现一次（并成了一条）
    expect(text.split("boom").length - 1).toBe(1);
  });
});
