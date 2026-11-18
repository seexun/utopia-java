// API 客户端真断言：拦截器的全局失败处置（401 跳转/403·422 toast/5xx 兜底）、
// 拒绝措辞（code 查 i18n err 表）、ApiError 形状。
// axios 走自定 adapter 桩——拦截器链真实在跑，不 mock http 模块本身。
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { AxiosError as AxiosError_ } from "axios";
import { http, ApiError, refusalMessage, navigation, PureHttp } from "@/api/knowledge/client";
import { S } from "@/i18n";

const { toastMock } = vi.hoisted(() => ({ toastMock: vi.fn() }));
// message（toast）桩：断言 403/422/5xx 的全局提示行为
vi.mock("@/utils/message", () => ({ message: toastMock }));

type ResponseInitLike = {
  status: number;
  data: unknown;
  statusText?: string;
};

/** 用一个假 adapter 让 axios 走完真实的拦截器链，回指定状态与正文 */
function stubAdapter(responder: (url: string) => ResponseInitLike | undefined) {
  PureHttp.axiosInstance.defaults.adapter = async config => {
    const res = responder(String(config?.url ?? ""));
    if (!res) {
      throw new AxiosError_("no route", "ERR_BAD_REQUEST", config as never);
    }
    if (res.status >= 200 && res.status < 300) {
      return {
        data: res.data,
        status: res.status,
        statusText: res.statusText ?? "OK",
        headers: {},
        config: config as never
      };
    }
    throw new AxiosError_(
      `Request failed with status code ${res.status}`,
      "ERR_BAD_REQUEST",
      config as never,
      {},
      {
        data: res.data,
        status: res.status,
        statusText: res.statusText ?? "Error",
        headers: {},
        config: config as never
      } as never
    );
  };
}

describe("api client：响应拦截器的全局失败处置", () => {
  let redirectSpy: ReturnType<typeof vi.fn<(url: string) => void>>;

  beforeEach(() => {
    redirectSpy = vi.fn();
    vi.spyOn(navigation, "to").mockImplementation(url => redirectSpy(url));
    toastMock.mockClear();
    // 401 一次性闸门逐用例复位（jknow T2：跳改整页跳转后同文件内不再有真实
    // 刷新复位，闸门置位会跨用例泄漏）
    PureHttp.redirecting401 = false;
    window.history.replaceState({}, "", "/graph");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("401：非登录页 → 整页跳 /login?redirect=当前路由（jknow T2 若依惯例改造）", async () => {
    // jknow 若依壳：createWebHistory——真实路由在 pathname（源仓 hash 断言随之改造）
    window.history.replaceState({}, "", "/graph/overview");
    stubAdapter(() => ({
      status: 401,
      data: { error: "Not signed in or invalid credentials" }
    }));
    await expect(http.request("/v1/kbs/x/graph/overview")).rejects.toMatchObject({
      status: 401,
      message: "Not signed in or invalid credentials"
    });
    expect(redirectSpy).toHaveBeenCalledTimes(1);
    const url = redirectSpy.mock.calls[0][0] as string;
    // 若依惯例（permission.ts 守卫同款）：/login?redirect= 带回落原处
    expect(url.startsWith("/login?redirect=")).toBe(true);
    expect(decodeURIComponent(url)).toContain("/graph/overview");
  });

  it("401：已在登录页（密码错）→ 不跳转，错误原样抛给页面内联显示", async () => {
    // history 路由：登录页就是 /login
    window.history.replaceState({}, "", "/login");
    stubAdapter(() => ({
      status: 401,
      data: { error: "Not signed in or invalid credentials" }
    }));
    await expect(
      http.request("/v1/auth/login", { method: "post" })
    ).rejects.toMatchObject({ status: 401 });
    expect(redirectSpy).not.toHaveBeenCalled();
  });

  it("skipGlobal401：守卫的会话探测不触发全局跳转", async () => {
    window.history.replaceState({}, "", "/library");
    stubAdapter(() => ({ status: 401, data: { error: "Not signed in" } }));
    await expect(
      http.request("/v1/auth/me", { skipGlobal401: true })
    ).rejects.toMatchObject({ status: 401 });
    expect(redirectSpy).not.toHaveBeenCalled();
  });

  it("200+体{code:401}（若依 entry point 形状，T4 裁定不改后端）→ 截住走同一条 401 处置线", async () => {
    window.history.replaceState({}, "", "/graph");
    stubAdapter(() => ({ status: 200, data: { code: 401, msg: "认证失败" } }));
    await expect(http.request("/v1/kbs/x")).rejects.toMatchObject({
      status: 401,
      message: "认证失败"
    });
    expect(redirectSpy).toHaveBeenCalledTimes(1);
    expect(redirectSpy.mock.calls[0][0] as string).toContain(
      "/login?redirect="
    );
  });

  it("403：toast 不跳转（无权限提示在当前页停住）", async () => {
    window.history.replaceState({}, "", "/graph");
    stubAdapter(() => ({ status: 403, data: { error: "forbidden" } }));
    await expect(http.request("/v1/kbs/x/rebuild")).rejects.toMatchObject({
      status: 403
    });
    expect(toastMock).toHaveBeenCalledWith("forbidden", { type: "error" });
    expect(redirectSpy).not.toHaveBeenCalled();
  });

  it("422：后端 error 键文案直显（code 查词典后的句子进 toast）", async () => {
    stubAdapter(() => ({
      status: 422,
      data: { error: "bad key", code: "bad_key" }
    }));
    await expect(http.request("/v1/kbs/x/ontology")).rejects.toMatchObject({
      status: 422
    });
    expect(toastMock).toHaveBeenCalledWith(S.err.bad_key, { type: "error" });
    expect(redirectSpy).not.toHaveBeenCalled();
  });

  it("5xx：兜底 toast（词典 nav.serverUnreachable）", async () => {
    stubAdapter(() => ({ status: 503, data: "unavailable" }));
    await expect(http.request("/v1/anything")).rejects.toBeInstanceOf(ApiError);
    expect(toastMock).toHaveBeenCalledWith(S.nav.serverUnreachable, {
      type: "error"
    });
  });

  it("成功：2xx 直回解析后的响应体（snake_case 直用）", async () => {
    stubAdapter(url =>
      url.includes("/v1/auth/me")
        ? {
            status: 200,
            data: {
              id: "u1",
              email: "contract0@test.local",
              display_name: "Contract Zero",
              is_admin: false
            }
          }
        : undefined
    );
    const user = await http.request<{ id: string; display_name: string }>(
      "/v1/auth/me"
    );
    expect(user.display_name).toBe("Contract Zero");
  });
});

describe("api client：拒绝措辞（refusalMessage）", () => {
  it("有 code → 按 i18n err 表直显后端错误键文案", () => {
    const text = refusalMessage({ code: "password_too_short" }, "fallback");
    expect(text).toBe(S.err.password_too_short);
    expect(text).toBe("密码至少 8 位。");
  });

  it("无 code → 退回服务端英文原句（error 字段）", () => {
    expect(
      refusalMessage({ error: "Not signed in or invalid credentials" }, "HTTP")
    ).toBe("Not signed in or invalid credentials");
  });

  it("带 detail → 词典句 + 原文括注（errDetail）", () => {
    const text = refusalMessage(
      { code: "schema_sync_failed", detail: "permission denied" },
      "HTTP"
    );
    expect(text).toBe(
      S.errDetail(S.err.schema_sync_failed as string, "permission denied")
    );
    expect(text).toContain("permission denied");
  });

  it("未知 code（还没进表的）→ 退回 error 原句，不显示键名", () => {
    expect(refusalMessage({ error: "boom", code: "not_in_table" }, "HTTP")).toBe(
      "boom"
    );
  });
});

describe("api client：ApiError 形状", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("status/message/code 三元组可被页面 catch 收窄", async () => {
    stubAdapter(() => ({
      status: 422,
      data: { error: "bad key", code: "bad_key" }
    }));
    try {
      await http.request("/v1/kbs/x/ontology");
      expect.unreachable("应该 reject");
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.status).toBe(422);
      expect(err.code).toBe("bad_key");
      expect(err.message).toBe(S.err.bad_key);
    }
  });
});
