/* W3 审查收口（I-1）：站内改 query 不重挂（lay-content 按 path 定 key）之后，
   「setup 一次性读」的页面参数必须改 watch ?create= 才接得住第二回。
   kb-switcher 的「新建知识库」push /account/kbs?create=true —— 同页落地
   （已在 /account/kbs 时 path 不变）必须直接开表单。 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { queryClient } from "@/store/knowledge/query";
import { store as globalPinia } from "@/store/knowledge";
import MyKbs from "@/views/jknow/knowledge/account/my-kbs.vue";
import { S } from "@/i18n";

const { myKbsMock, wsRoleMock, meMock, packsMock } = vi.hoisted(() => ({
  myKbsMock: vi.fn(),
  wsRoleMock: vi.fn(),
  meMock: vi.fn(),
  packsMock: vi.fn()
}));
vi.mock("@/api/knowledge/workspaces", () => ({
  myKbs: myKbsMock,
  workspaceRole: wsRoleMock
}));
vi.mock("@/api/knowledge/kbs", () => ({
  createKb: vi.fn(),
  ontologyPacks: packsMock,
  kbDetail: vi.fn()
}));

let mounted: ReturnType<typeof mount> | null = null;
let router: Router | null = null;

async function mountKbs(path = "/account/kbs") {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/account/kbs", name: "my-kbs", component: MyKbs },
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } }
    ]
  });
  await router.push(path);
  await router.isReady();
  mounted = mount(MyKbs, {
    global: {
      plugins: [ElementPlus, globalPinia, router, [VueQueryPlugin, { queryClient }]]
    }
  });
  await flushPromises();
  return mounted!;
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  queryClient.clear();
  myKbsMock.mockReset();
  wsRoleMock.mockReset();
  meMock.mockReset();
  packsMock.mockReset();
  meMock.mockResolvedValue({ id: "u1", is_admin: false });
  wsRoleMock.mockResolvedValue({ my_role: "admin" });
  packsMock.mockResolvedValue({ packs: [] });
  myKbsMock.mockResolvedValue({
    kbs: [
      {
        kb: { id: "kb1", name: "Alpha", description: null },
        my_role: "owner",
        joined_at: null,
        added_by_name: null,
        doc_count: 1,
        member_count: 1
      }
    ]
  });
});

afterEach(() => {
  mounted?.unmount();
  mounted = null;
  router = null;
});

describe("MyKbs ?create=true（I-1）", () => {
  /* UDialog 开着才挂载（Teleport 到 body）；触发按钮与弹窗标题同词，
     所以按 .modal-scrim 里的 dialog 断言，不按全文断言 */
  const openDialog = () =>
    document.querySelector(".modal-scrim [role='dialog']");
  const dialogText = () => openDialog()?.textContent ?? "";

  it("站内 push 改 query：同页落地直接开建库表单，并把参数从地址栏清掉", async () => {
    const w = await mountKbs("/account/kbs");
    await flushPromises();
    // 没带参：表单关着（UDialog open=false 不挂载）
    expect(openDialog()).toBeNull();
    // kb-switcher 的那一步：path 不变、只改 query（组件不重挂）
    await router!.push({ path: "/account/kbs", query: { create: "true" } });
    await flushPromises();
    expect(openDialog()).not.toBeNull();
    expect(dialogText()).toContain(S.settings.kbs.newKb);
    // 开表单之后地址栏清干净（replace）
    expect(router!.currentRoute.value.fullPath).toBe("/account/kbs");
    w.unmount();
  });

  it("首挂带 ?create=true 照旧直接开表单（原 onMounted 路径不回归）", async () => {
    const w = await mountKbs("/account/kbs?create=true");
    await flushPromises();
    expect(openDialog()).not.toBeNull();
    expect(dialogText()).toContain(S.settings.kbs.newKb);
    w.unmount();
  });
});
