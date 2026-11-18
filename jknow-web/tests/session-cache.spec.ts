// 审查 I-2：会话边界清 vue-query 缓存——换人不靠过期，靠登录与登出清空
// （domain/queryDefaults.ts 头注的旧仓决定）。A 登出、B 登录后，
// A 的库列表/告警未读等缓存键不允许残留。
import { describe, it, expect, vi, beforeEach } from "vitest";
import { queryClient } from "@/store/knowledge/query";
import { useUserStoreHook } from "@/store/knowledge/modules/user";

const { loginMock, registerMock, logoutMock } = vi.hoisted(() => ({
  loginMock: vi.fn(),
  registerMock: vi.fn(),
  logoutMock: vi.fn()
}));
vi.mock("@/api/knowledge/auth", () => ({
  me: vi.fn(),
  login: loginMock,
  register: registerMock,
  logout: logoutMock
}));
vi.mock("@/utils/message", () => ({ message: vi.fn() }));

const userB = {
  id: "u2",
  org_id: "o1",
  email: "user-b@test.local",
  display_name: "User B",
  is_admin: false,
  created_at: "2026-11-14T00:00:00Z"
};

beforeEach(() => {
  loginMock.mockReset();
  registerMock.mockReset();
  logoutMock.mockReset();
  queryClient.clear();
});

describe("会话边界清 vue-query 缓存（审查 I-2）", () => {
  it("登录成功：上一会话（A）的缓存键全部清除", async () => {
    // A 会话的残留：库列表 / 未读 / 当前库详情
    queryClient.setQueryData(["kbs", "ws-a"], [{ id: "kb-a", name: "A 的库" }]);
    queryClient.setQueryData(["alerts", "unread"], { unread: 3 });
    queryClient.setQueryData(["kbOne", "kb-a"], { id: "kb-a" });
    expect(queryClient.getQueryCache().getAll().length).toBe(3);

    loginMock.mockResolvedValue({ user: userB });
    await useUserStoreHook().loginByUsername({
      email: "user-b@test.local",
      password: "whatever8"
    });

    const keys = queryClient.getQueryCache().getAll().map(q => JSON.stringify(q.queryKey));
    expect(keys).toEqual([]);
    expect(
      queryClient.getQueryCache().find({ queryKey: ["kbs", "ws-a"] })
    ).toBeUndefined();
  });

  it("注册成功：同样清缓存", async () => {
    queryClient.setQueryData(["kbs", "ws-a"], [{ id: "kb-a" }]);
    registerMock.mockResolvedValue({ user: userB, workspace: { id: "ws-b" } });
    await useUserStoreHook().registerAccount({
      email: "user-b@test.local",
      password: "whatever8",
      displayName: "User B"
    });
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it("登出：清缓存（服务端登出失败也照样清——本地登出口径）", async () => {
    queryClient.setQueryData(["alerts", "unread"], { unread: 3 });
    queryClient.setQueryData(["graph", "kb-a"], { nodes: [] });
    logoutMock.mockRejectedValue(new Error("already signed out"));
    await useUserStoreHook().logOut();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });
});
