// W1 壳：ServerDown 态（对照旧仓 Shell 的 me 失败语义）——
// 后端不可达（网络断/5xx/其他非 401）→ ensureMe 放行 + 置 shell store 标志；
// 401 才是没登录。成功校验清位。
import { describe, it, expect, vi, beforeEach } from "vitest";
import { useUserStoreHook } from "@/store/knowledge/modules/user";
import { useShellStoreHook } from "@/store/knowledge/modules/shell";
import { ApiError } from "@/api/knowledge/client";

const { meMock } = vi.hoisted(() => ({ meMock: vi.fn() }));
vi.mock("@/api/knowledge/auth", () => ({
  me: meMock,
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn()
}));
vi.mock("@/utils/message", () => ({ message: vi.fn() }));

beforeEach(() => {
  meMock.mockReset();
  useShellStoreHook().markAlive();
});

describe("ensureMe 的 ServerDown 语义（W1）", () => {
  it("网络不可达（status 0）：返回 true 放行导航，置 serverDown", async () => {
    meMock.mockRejectedValue(new ApiError(0, "Network Error"));
    const alive = await useUserStoreHook().ensureMe(true);
    expect(alive).toBe(true);
    expect(useShellStoreHook().serverDown).toBe(true);
  });

  it("5xx：同样按后端不可达处置", async () => {
    meMock.mockRejectedValue(new ApiError(502, "Bad Gateway"));
    const alive = await useUserStoreHook().ensureMe(true);
    expect(alive).toBe(true);
    expect(useShellStoreHook().serverDown).toBe(true);
  });

  it("401：没登录——返回 false，不置 serverDown（守卫回登录页）", async () => {
    meMock.mockRejectedValue(new ApiError(401, "Not signed in"));
    const alive = await useUserStoreHook().ensureMe(true);
    expect(alive).toBe(false);
    expect(useShellStoreHook().serverDown).toBe(false);
  });

  it("成功校验清位：后端回来了", async () => {
    useShellStoreHook().markServerDown();
    meMock.mockResolvedValue({
      id: "u1",
      org_id: "o1",
      email: "contract0@test.local",
      display_name: "Contract Zero",
      is_admin: false,
      created_at: "2026-11-14T00:00:00Z"
    });
    const alive = await useUserStoreHook().ensureMe(true);
    expect(alive).toBe(true);
    expect(useShellStoreHook().serverDown).toBe(false);
  });
});
