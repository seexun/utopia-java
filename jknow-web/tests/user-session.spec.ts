// 会话生命周期（审查 I-1 / I-2 收口）：
// I-1  localStorage 有快照（守卫 hydrate 后 userId 已真）时，首次导航的
//      ensureMe 仍真发 /auth/me——服务端角色/会话变化在进壳前被发现；
// I-2  401 一次性闸门在登录成功后复位——同一 SPA 页面里两次会话过期各跳一次。
// 网络层 mock：@/api/knowledge/auth（me/login/register/logout）；axios 走自定 adapter 桩。
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AxiosError as AxiosError_ } from "axios";
import { storageLocal } from "@pureadmin/utils";
import { useUserStoreHook } from "@/store/modules/user";
import { http, navigation, PureHttp } from "@/api/knowledge/client";

const { meMock, loginMock, registerMock, logoutMock } = vi.hoisted(() => ({
  meMock: vi.fn(),
  loginMock: vi.fn(),
  registerMock: vi.fn(),
  logoutMock: vi.fn()
}));
vi.mock("@/api/knowledge/auth", () => ({
  me: meMock,
  login: loginMock,
  register: registerMock,
  logout: logoutMock
}));
// client 的 toast 依赖桩（避免测试里弹真实提示）
vi.mock("@/utils/message", () => ({ message: vi.fn() }));

/** 服务端真值（与本地快照刻意不同：is_admin/nickname 都变了） */
const serverUser = {
  id: "u1",
  org_id: "o1",
  email: "contract0@test.local",
  display_name: "Fresh Name",
  is_admin: true,
  created_at: "2026-11-14T00:00:00Z"
};

/** 本地旧快照（refresh 后守卫 hydrate 的来源）：旧昵称、非管理员 */
function seedStaleSnapshot() {
  storageLocal().setItem("user-info", {
    userId: "u1",
    username: "contract0@test.local",
    nickname: "Stale Name",
    isAdmin: false,
    roles: ["user"],
    permissions: []
  });
}

/** 让 axios 走真实拦截器链并回 401（闸门测试用） */
function stub401Adapter() {
  PureHttp.axiosInstance.defaults.adapter = async config => {
    throw new AxiosError_(
      "Request failed with status code 401",
      "ERR_BAD_REQUEST",
      config as never,
      {},
      {
        data: { error: "Not signed in or invalid credentials" },
        status: 401,
        statusText: "Unauthorized",
        headers: {},
        config: config as never
      } as never
    );
  };
}

describe("user store：会话生命周期（I-1）", () => {
  beforeEach(() => {
    meMock.mockReset();
    loginMock.mockReset();
    registerMock.mockReset();
    logoutMock.mockReset();
    storageLocal().removeItem("user-info");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("本地有快照（userId 已真）时，首次 ensureMe 仍真发 /auth/me 并回填服务端真值", async () => {
    seedStaleSnapshot();
    const store = useUserStoreHook();
    // 守卫 hydrate 之后的状态：userId 已真——修复前 ensureMe 在此短路
    expect(store.userId).toBe("u1");
    expect(store.isAdmin).toBe(false);
    meMock.mockResolvedValue(serverUser);

    const alive = await store.ensureMe();

    expect(alive).toBe(true);
    expect(meMock).toHaveBeenCalledTimes(1);
    // 服务端真值回填：角色变化立即生效（is_admin 撤销/恢复不靠 401 兜底）
    expect(store.isAdmin).toBe(true);
    expect(store.nickname).toBe("Fresh Name");
    expect(store.roles).toEqual(["admin"]);
  });

  it("生命周期标志：同一生命周期内第二次 ensureMe 不再发请求", async () => {
    const store = useUserStoreHook();
    meMock.mockResolvedValue(serverUser);
    await store.ensureMe(); // 前面用例后标志已置位；force=false 不再打
    const after = meMock.mock.calls.length;
    await store.ensureMe();
    expect(meMock.mock.calls.length).toBe(after);
  });

  it("登录成功复位标志：下一次 ensureMe 对新会话再校验一次（I-1 复位点）", async () => {
    const store = useUserStoreHook();
    loginMock.mockResolvedValue({ user: serverUser });
    meMock.mockResolvedValue(serverUser);
    await store.loginByUsername({
      email: "contract0@test.local",
      password: "Contract0!2026"
    });
    const before = meMock.mock.calls.length;
    await store.ensureMe();
    expect(meMock.mock.calls.length).toBe(before + 1);
  });
});

describe("401 一次性闸门（I-2）：登录成功后复位", () => {
  let redirectSpy: ReturnType<typeof vi.fn<(url: string) => void>>;

  beforeEach(() => {
    redirectSpy = vi.fn();
    vi.spyOn(navigation, "to").mockImplementation(url => redirectSpy(url));
    loginMock.mockReset();
    meMock.mockReset();
    window.history.replaceState({}, "", "/#/graph");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("两次会话过期各自跳一次（中间夹登录成功）", async () => {
    stub401Adapter();
    const store = useUserStoreHook();

    // 第一次会话过期：跳 /login?returnTo=
    await expect(http.request("/v1/anything")).rejects.toMatchObject({
      status: 401
    });
    expect(redirectSpy).toHaveBeenCalledTimes(1);
    expect(redirectSpy.mock.calls[0][0]).toMatch(/^#\/login\?returnTo=/);

    // 第二次 401（未登录中间没有登录成功）：闸门吞掉，不重复跳
    await expect(http.request("/v1/anything")).rejects.toMatchObject({
      status: 401
    });
    expect(redirectSpy).toHaveBeenCalledTimes(1);

    // 登录成功：闸门复位（SPA 不整页刷新）
    loginMock.mockResolvedValue({ user: serverUser });
    await store.loginByUsername({
      email: "contract0@test.local",
      password: "Contract0!2026"
    });

    // 第三次会话过期（新会话再过期）：闸门已复位，再跳
    await expect(http.request("/v1/anything")).rejects.toMatchObject({
      status: 401
    });
    expect(redirectSpy).toHaveBeenCalledTimes(2);
    expect(redirectSpy.mock.calls[1][0]).toMatch(/^#\/login\?returnTo=/);
  });
});
