/* W2 设置与映射写流（任务 6）：mock api 断言**调用形状**与**只送自己那几项**。
   ——KbSettings 四卡各一个 PATCH（改名字那一下不该把开关一起送上去——九字段分四卡）；
   Mappings 逐条拍板（decideMapping(kb, id, status)）与 revise（改之前那一版自动进
   revisions，三格互相定义整组替换）。 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { queryClient } from "@/store/knowledge/query";
import { store as globalPinia } from "@/store/knowledge";
import KbSettings from "@/views/jknow/knowledge/kb-settings/index.vue";
import Mappings from "@/views/jknow/knowledge/mappings/index.vue";
import { parentOptions } from "@/views/jknow/knowledge/ontology/dialogs";
import type { EntityTypeView } from "@/api/knowledge/types";
import { S } from "@/i18n";

const m = vi.hoisted(() => ({
  kbDetail: vi.fn(),
  updateKb: vi.fn(),
  deleteKb: vi.fn(),
  kbMembers: vi.fn(),
  setKbMember: vi.fn(),
  removeKbMember: vi.fn(),
  kbAudit: vi.fn(),
  orgUsers: vi.fn(),
  failedJobs: vi.fn(),
  requeueJobs: vi.fn(),
  mappings: vi.fn(),
  decideMapping: vi.fn(),
  reviseMapping: vi.fn(),
  mappingRevisions: vi.fn(),
  kbDataSources: vi.fn(),
  kbDataSourcesAvail: vi.fn(),
  mount: vi.fn(),
  unmount: vi.fn(),
  syncSchema: vi.fn(),
  explore: vi.fn(),
  me: vi.fn()
}));
vi.mock("@/api/knowledge/kbs", () => ({
  kbDetail: m.kbDetail,
  updateKb: m.updateKb,
  deleteKb: m.deleteKb,
  kbMembers: m.kbMembers,
  setKbMember: m.setKbMember,
  removeKbMember: m.removeKbMember,
  kbAudit: m.kbAudit
}));
vi.mock("@/api/knowledge/workspaces", () => ({
  orgUsers: m.orgUsers
}));
vi.mock("@/api/knowledge/alerts", () => ({
  failedJobs: m.failedJobs,
  requeueJobs: m.requeueJobs
}));
vi.mock("@/api/knowledge/mappings", () => ({
  mappings: m.mappings,
  reviseMapping: m.reviseMapping,
  mappingRevisions: m.mappingRevisions,
  kbDataSources: m.kbDataSources,
  kbDataSourcesAvailable: m.kbDataSourcesAvail,
  mountDataSource: m.mount,
  unmountDataSource: m.unmount,
  syncDataSourceSchema: m.syncSchema,
  exploreMappings: m.explore
}));
vi.mock("@/api/knowledge/review", () => ({
  decideMapping: m.decideMapping
}));
vi.mock("@/api/knowledge/auth", () => ({
  me: m.me,
  login: vi.fn(),
  logout: vi.fn(),
  register: vi.fn(),
  updateMe: vi.fn(),
  changePassword: vi.fn(),
  oidcStatus: vi.fn(),
  oidcMe: vi.fn(),
  oidcUnlinkMe: vi.fn()
}));

const kbRow = {
  id: "kb1",
  name: "General",
  description: "demo",
  visibility: "restricted" as const,
  is_default: false,
  materialize_inferences: false,
  auto_type_resolution: false,
  governance: false,
  governance_since: null,
  inference_interval_minutes: 60,
  last_inference_at: null,
  ontology_lang: "en" as const,
  my_role: "owner" as const
};

const mappingRow = {
  id: "cm1",
  concept_id: "c1",
  concept_name: "月活跃用户",
  source: "warehouse.main.events",
  table_name: null,
  expr: null,
  sql: null,
  unit: null,
  summary: null,
  derived: false,
  status: "proposed" as const,
  created_at: "2026-11-15T00:00:00Z",
  updated_at: "2026-11-15T00:00:00Z"
};

let mounted: ReturnType<typeof mount> | null = null;
let el: HTMLElement | null = null;
let router: Router | null = null;

async function mountAt(
  component: unknown,
  path: string,
  routes: unknown[] = []
) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...(routes as never[]),
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } }
    ]
  });
  el = document.createElement("div");
  document.body.appendChild(el);
  await router.push(path);
  await router.isReady();
  mounted = mount(component as never, {
    global: {
      plugins: [ElementPlus, globalPinia, router, [VueQueryPlugin, { queryClient }]]
    },
    attachTo: el!
  });
  await flushPromises();
  return mounted!;
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  queryClient.clear();
  // pinia 全局单例跨用例残留（utopia-kb）——与 w2-review-writes 同口径复位
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalPinia.state.value as any)["utopia-kb"] = {
    kbId: "kb1",
    workspaceId: "ws1",
    kbDetail: null,
    loading: false
  };
  for (const f of Object.values(m)) f.mockReset();
  m.kbDetail.mockResolvedValue(kbRow);
  m.updateKb.mockResolvedValue({ ...kbRow, name: "x" });
  m.failedJobs.mockResolvedValue({ failed: 0 });
  m.kbAudit.mockResolvedValue({ events: [], total: 0, actions: [] });
  m.kbMembers.mockResolvedValue({
    members: [{ user_id: "u1", email: "a@b.c", display_name: "A", role: "editor" }]
  });
  m.orgUsers.mockResolvedValue([
    { id: "u2", email: "b@b.c", display_name: "B", is_admin: false }
  ]);
  m.me.mockResolvedValue({ id: "u9", email: "me@b.c", display_name: "Me", is_admin: false });
  m.mappings.mockResolvedValue({
    items: [mappingRow],
    total: 1,
    counts: { proposed: 1, confirmed: 0, rejected: 0 }
  });
  m.decideMapping.mockResolvedValue({ ok: true });
  m.reviseMapping.mockResolvedValue({ ok: true });
  m.kbDataSources.mockResolvedValue({ data_sources: [] });
  m.kbDataSourcesAvail.mockResolvedValue({ data_sources: [] });
});

afterEach(() => {
  mounted?.unmount();
  el?.remove();
  mounted = null;
  router = null;
});

async function clickExact(w: ReturnType<typeof mount>, text: string) {
  const btn = w
    .findAll("button")
    .find(b => b.text().trim() === text && !b.attributes("disabled"));
  expect(btn, `button「${text}」不存在或被禁用`).toBeTruthy();
  await btn!.trigger("click");
  await flushPromises();
}

describe("KbSettings 写流（W2）：每张卡只送自己那几项", () => {
  it("身份卡保存：PATCH 只带 name/description，不带任何开关", async () => {
    const w = await mountAt(KbSettings, "/kb/kb1/settings", [
      { path: "/kb/:kbId/settings", component: KbSettings as never }
    ]);
    // 身份卡的名字输入框（第一张卡里改一个字）
    const nameInput = w
      .findAll("input")
      .find(i => (i.element as HTMLInputElement).value === "General");
    expect(nameInput).toBeTruthy();
    await nameInput!.setValue("General2");
    await clickExact(w, S.kbset.save as string);
    expect(m.updateKb).toHaveBeenCalledTimes(1);
    expect(m.updateKb).toHaveBeenCalledWith("kb1", {
      name: "General2",
      description: "demo"
    });
    const body = m.updateKb.mock.calls[0][1] as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(["description", "name"]);
  });

  it("自动化卡保存：四字段一次送（materialize/autoResolve/governance/interval）", async () => {
    const w = await mountAt(KbSettings, "/kb/kb1/settings", [
      { path: "/kb/:kbId/settings", component: KbSettings as never }
    ]);
    // 勾上治理（0025）——/el-checkbox 的原生 input
    const gov = w
      .findAll(".el-checkbox")
      .find(c => c.text().includes(S.kbset.governance as string));
    expect(gov).toBeTruthy();
    await gov!.find("input").setValue(true);
    // 身份卡没动过——只有自动化卡是脏的；页面上有两个「Save」（身份/自动化/可见性/语言），
    // 点自动化卡里的那一个（卡片容器内查询）
    const cards = w.findAll(".rounded-card.border");
    const autoCard = cards.find(c =>
      c.text().includes(S.kbset.cardAutomation as string)
    );
    expect(autoCard).toBeTruthy();
    const saveBtn = autoCard!
      .findAll("button")
      .find(b => b.text().trim() === (S.kbset.save as string));
    await saveBtn!.trigger("click");
    await flushPromises();
    expect(m.updateKb).toHaveBeenCalledTimes(1);
    expect(m.updateKb).toHaveBeenCalledWith("kb1", {
      materialize_inferences: false,
      auto_type_resolution: false,
      governance: true,
      inference_interval_minutes: 60
    });
  });
});

describe("Mappings 写流（W2）", () => {
  it("逐条拍板：approve → decideMapping(kb, id, 'confirmed')", async () => {
    const w = await mountAt(Mappings, "/kb/kb1/mappings", [
      { path: "/kb/:kbId/mappings", component: Mappings as never }
    ]);
    await clickExact(w, S.mapping.approve as string);
    expect(m.decideMapping).toHaveBeenCalledTimes(1);
    expect(m.decideMapping).toHaveBeenCalledWith("kb1", "cm1", "confirmed");
  });

  it("revise：行内编辑填 SQL 保存 → reviseMapping(kb, id, 六字段整组)", async () => {
    const w = await mountAt(Mappings, "/kb/kb1/mappings", [
      { path: "/kb/:kbId/mappings", component: Mappings as never }
    ]);
    await clickExact(w, S.mapping.edit as string);
    // 表单出现（工具行的搜索/筛选输入不算）：在编辑容器内按标签定位 SQL 那格
    const editBox = w.findAll("div").find(d => d.classes().includes("border-t"));
    expect(editBox).toBeTruthy();
    // 编辑表单五格的顺序固定：table / unit / expr / sql / summary（标签在 .el-input 外面）
    const boxes = editBox!.findAll(".el-input");
    expect(boxes.length).toBe(5);
    await boxes[3].find("input").setValue("count(distinct u)");
    await clickExact(w, S.mapping.save as string);
    expect(m.reviseMapping).toHaveBeenCalledTimes(1);
    expect(m.reviseMapping).toHaveBeenCalledWith("kb1", "cm1", {
      table_name: "",
      expr: "",
      sql: "count(distinct u)",
      unit: "",
      summary: "",
      derived: false
    });
  });
});

describe("ontology dialogs：parentOptions 纯函数（弹窗共用的候选表）", () => {
  const t = (id: string, parents: string[]): EntityTypeView =>
    ({
      id,
      key: id,
      label: id,
      color: "#000",
      shape: "circle",
      builtin: false,
      parents,
      primary_parent: parents[0] ?? null,
      disjoint: [],
      description: "",
      usage: 0
    }) as EntityTypeView;

  it("排除自己与全部后代（防成环）；树序 + 缩进", () => {
    const all = [
      t("root", []),
      t("child", ["root"]),
      t("grand", ["child"]),
      t("other", ["root"])
    ];
    const opts = parentOptions(all, "child");
    const ids = opts.map(o => o.value);
    expect(ids).toContain("root");
    expect(ids).toContain("other");
    // 自己与后代（grand）不在候选里
    expect(ids).not.toContain("child");
    expect(ids).not.toContain("grand");
    const childIdx = opts.find(o => o.value === "other")!;
    expect(childIdx.indent).toBe(1);
  });
});
