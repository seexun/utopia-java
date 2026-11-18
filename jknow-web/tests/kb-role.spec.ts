// useKbRole：当前库角色判定（对照旧仓 kb.tsx / 各页 my_role 门控口径）。
import { describe, it, expect, vi, beforeEach } from "vitest";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbRole } from "@/composables/useKbRole";

// M-2（审查收口）：网络层 mock——store.load 走真实现，kbDetail 打桩
const { kbDetailMock } = vi.hoisted(() => ({ kbDetailMock: vi.fn() }));
vi.mock("@/api/knowledge/kbs", () => ({ kbDetail: kbDetailMock }));

function kbWithRole(my_role: unknown) {
  return {
    id: "kb1",
    workspace_id: "ws1",
    name: "General",
    kind: "normal",
    description: null,
    visibility: "open",
    is_default: true,
    auto_extend_ontology: true,
    materialize_inferences: false,
    auto_type_resolution: true,
    governance: false,
    governance_since: null,
    inference_interval_minutes: 60,
    last_inference_at: null,
    ontology_lang: "zh",
    my_role
  };
}

describe("useKbRole：库角色门控口径", () => {
  beforeEach(() => {
    // store hook 绑定在单例 pinia（@/store）：直接清状态，保证用例互不带走
    const store = useKbContextHook();
    store.kbDetail = null;
    store.kbId = "";
    store.loading = false;
    localStorage.removeItem("utopia.kb");
  });

  it("owner：可管理、是库管理员、可写", () => {
    useKbContextHook().kbDetail = kbWithRole("owner") as never;
    const { role, canWrite, canManage, isKbAdmin } = useKbRole();
    expect(role.value).toBe("owner");
    expect(canWrite.value).toBe(true);
    expect(canManage.value).toBe(true);
    expect(isKbAdmin.value).toBe(true);
  });

  it("admin：可管理、是库管理员", () => {
    useKbContextHook().kbDetail = kbWithRole("admin") as never;
    const { canManage, isKbAdmin } = useKbRole();
    expect(canManage.value).toBe(true);
    expect(isKbAdmin.value).toBe(true);
  });

  it("editor：可写、可管理内容、不是库管理员", () => {
    useKbContextHook().kbDetail = kbWithRole("editor") as never;
    const { canWrite, canManage, isKbAdmin } = useKbRole();
    expect(canWrite.value).toBe(true);
    expect(canManage.value).toBe(true);
    expect(isKbAdmin.value).toBe(false);
  });

  it('viewer：只读——不可写、不可管理（旧仓 my_role !== "viewer" 口径）', () => {
    useKbContextHook().kbDetail = kbWithRole("viewer") as never;
    const { role, canWrite, canManage, isKbAdmin } = useKbRole();
    expect(role.value).toBe("viewer");
    expect(canWrite.value).toBe(false);
    expect(canManage.value).toBe(false);
    expect(isKbAdmin.value).toBe(false);
  });

  it("详情未加载（无库）：canWrite 缺省放行（旧仓 kb?.my_role !== viewer 的 undefined 通路），manage 收紧", () => {
    const { role, canWrite, canManage } = useKbRole();
    expect(role.value).toBe(null);
    expect(canWrite.value).toBe(true);
    expect(canManage.value).toBe(false);
  });

  it("store.load：真调 kbDetail 拉详情并记忆 kbId（URL 优先语义的进料口）", async () => {
    const store = useKbContextHook();
    kbDetailMock.mockResolvedValue(kbWithRole("editor"));
    await store.load("kb1");
    // 真实现被调用（打桩的是网络层，不是 load 本身）
    expect(kbDetailMock).toHaveBeenCalledWith("kb1");
    expect(store.kbId).toBe("kb1");
    expect(localStorage.getItem("utopia.kb")).toBe("kb1");
    const { role, canManage } = useKbRole();
    expect(role.value).toBe("editor");
    expect(canManage.value).toBe(true);
  });
});
