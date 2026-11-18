// W1 壳单元测试：库上下文与菜单。
// 覆盖：samePageInKb（旧仓 kb.tsx 纯函数语义）、utopiaMenus（三组 8 页/写菜单
// 随角色显隐）、switchKb（写 store + 失效事件流键 + 停在当前页导航）。
import { describe, it, expect, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { defineComponent, h, nextTick } from "vue";
import {
  createRouter,
  createMemoryHistory,
  type Router
} from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";
import { queryClient } from "@/store/knowledge/query";
import { samePageInKb } from "@/domain/kbContext";
import { utopiaMenus } from "@/router/utopia-menu";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { usePermissionStoreHook } from "@/store/knowledge/modules/permission";
import { STREAM_KEYS } from "@/domain/queryDefaults";
import { useKbContext } from "@/composables/useKbContext";
import { S } from "@/i18n";
import { store as globalPinia } from "@/store/knowledge";

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  useKbContextHook().$reset();
  usePermissionStoreHook().wholeMenus = [];
  // composable 的查询/失效面绑定单例客户端（store/query.ts）——测试前清空
  queryClient.clear();
});

describe("samePageInKb：换库停在同一个页面上（旧仓 kb.tsx 语义）", () => {
  it("一级页面原位跟随", () => {
    expect(samePageInKb("/kb/A/graph", "A", "B")).toBe("/kb/B/graph");
    expect(samePageInKb("/kb/A/library", "A", "B")).toBe("/kb/B/library");
    expect(samePageInKb("/kb/A/settings", "A", "B")).toBe("/kb/B/settings");
  });

  it("深一层的会话/文档 id 是旧库的东西，换库就该丢掉", () => {
    expect(samePageInKb("/kb/A/chat/c1", "A", "B")).toBe("/kb/B/chat");
    expect(samePageInKb("/kb/A/doc/d1", "A", "B")).toBe("/kb/B/library");
  });

  it("不在 /kb 作用域下时保持原行为", () => {
    expect(samePageInKb("/account", "A", "B")).toBe("/account");
  });
});

describe("utopiaMenus：三组覆盖 8 个 KB 页面，写菜单随角色显隐", () => {
  it("可写库：三组、顺序与路径对齐旧仓 Shell 的 MENU（图谱是门面，本体与映射相邻）", () => {
    const menus = utopiaMenus("kb1", true);
    expect(menus).toHaveLength(3);
    // 取词走当前语言包（S 冻结于模块加载，测试不钉语言）
    expect(menus.map(g => g.meta.title)).toEqual([
      S.nav.groupWorkbench,
      S.nav.groupKnowledge,
      S.nav.groupConfig
    ]);
    const paths = menus.flatMap(g => (g.children ?? []).map(i => i.path));
    expect(paths).toEqual([
      "/kb/kb1/graph",
      "/kb/kb1/search",
      "/kb/kb1/chat",
      "/kb/kb1/library",
      "/kb/kb1/review",
      "/kb/kb1/ontology",
      "/kb/kb1/mappings",
      "/kb/kb1/settings"
    ]);
    // 折叠态可用性契约（菜单缺陷修复批）：SidebarItem 折叠分支靠
    // `meta.icon + parentId === null` 收起组头文字只留图标（子项收进 popper）；
    // 本项目 initRouter 不经过 buildHierarchyTree，层级字段由 utopiaMenus
    // 自补——形状漂移会让折叠态退回「工…」截断文字，这里钉住。
    expect(menus.every(g => typeof g.meta.icon === "string")).toBe(true);
    expect(menus.map(g => g.parentId)).toEqual([null, null, null]);
    expect(menus.map(g => g.pathList)).toEqual([[0], [1], [2]]);
    expect(menus.flatMap(g => (g.children ?? []).map(c => c.parentId))).toEqual(
      [0, 0, 0, 1, 1, 1, 1, 2]
    );
    expect(menus.flatMap(g => (g.children ?? []).map(c => c.pathList))).toEqual(
      [
        [0, 0],
        [0, 1],
        [0, 2],
        [1, 0],
        [1, 1],
        [1, 2],
        [1, 3],
        [2, 0]
      ]
    );
  });

  it("只读库（viewer）：菜单与可写库同形——8 项全量（对齐旧仓静态 MENU；设置页内部只读呈现，写操作另有 useKbRole 闸）", () => {
    const menus = utopiaMenus("kb1", false);
    const paths = menus.flatMap(g => (g.children ?? []).map(i => i.path));
    expect(paths).toHaveLength(8);
    expect(paths.some(p => p.endsWith("/settings"))).toBe(true);
  });

  it("kbId 未解析（冷启动极早期）时不渲染菜单，等 KbScope 载入后重组", () => {
    expect(utopiaMenus("", true)).toEqual([]);
  });
});

/** 挂一个宿主组件拿到 useKbContext（pinia/router/vue-query 全部走模块单例）。 */
function makeHarness() {
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/kb/:kbId/graph", name: "g", component: { template: "<div/>" } },
      { path: "/kb/:kbId/library", name: "l", component: { template: "<div/>" } },
      { path: "/kb/:kbId/chat/:cid", name: "c", component: { template: "<div/>" } },
      { path: "/account", name: "acc", component: { template: "<div/>" } }
    ]
  });

  const Host = defineComponent({
    name: "Host",
    setup() {
      const ctx = useKbContext();
      (Host as unknown as { __ctx: ReturnType<typeof useKbContext> }).__ctx = ctx;
      return () => h("div");
    }
  });

  const el = document.createElement("div");
  document.body.appendChild(el);
  const wrapper = mount(Host, {
    global: {
      plugins: [globalPinia, router, [VueQueryPlugin, { queryClient }]]
    },
    attachTo: el
  });
  const ctx = (Host as unknown as { __ctx: ReturnType<typeof useKbContext> }).__ctx;
  const unmount = () => {
    wrapper.unmount();
    el.remove();
  };
  return { router, queryClient, ctx, unmount };
}

describe("switchKb：换库是一次导航，不只是记一笔", () => {
  it("从 /kb/A/chat/c1 切到 B：落在 /kb/B/chat（会话不带走），记忆与菜单跟着走", async () => {
    const { router, ctx, unmount } = makeHarness();
    await router.push("/kb/A/chat/c1");
    await nextTick();
    await flushPromises();

    ctx.switchKb("B");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/kb/B/chat");
    expect(localStorage.getItem("utopia.kb")).toBe("B");
    // 菜单重组：条目路径换成新库
    const menuPaths = usePermissionStoreHook().wholeMenus
      .flatMap(g => (g.children ?? []) as { path: string }[])
      .map(i => i.path);
    expect(menuPaths.length).toBeGreaterThan(0);
    for (const p of menuPaths) expect(p).toContain("/kb/B/");
    unmount();
  });

  it("切库按 STREAM_KEYS 主动失效目标库的键，不牵连会话键", async () => {
    const { router, queryClient, ctx, unmount } = makeHarness();
    await router.push("/kb/A/library");
    await nextTick();
    await flushPromises();

    // 预填缓存：目标库 B 的两个流键 + 一个会话键
    queryClient.setQueryData(["graph", "B"], "stale-B");
    queryClient.setQueryData(["documents", "B"], "stale-docs-B");
    queryClient.setQueryData(["me"], "session");

    ctx.switchKb("B");
    await flushPromises();

    const cache = queryClient.getQueryCache();
    expect(cache.find({ queryKey: ["graph", "B"] })!.state.isInvalidated).toBe(
      true
    );
    expect(
      cache.find({ queryKey: ["documents", "B"] })!.state.isInvalidated
    ).toBe(true);
    // 会话内不变的键不受切库牵连
    expect(cache.find({ queryKey: ["me"] })!.state.isInvalidated).toBe(false);
    unmount();
  });

  it("地址里没有 kbId 时（账户页）只记一笔，不被拽走", async () => {
    const { router, ctx, unmount } = makeHarness();
    await router.push("/account");
    await nextTick();
    await flushPromises();

    ctx.switchKb("B");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/account");
    expect(localStorage.getItem("utopia.kb")).toBe("B");
    unmount();
  });
});

// STREAM_KEYS 表与旧仓逐字一致（切库失效面依赖它）
it("STREAM_KEYS 七个键头（旧仓 queryDefaults.ts 原表）", () => {
  expect([...STREAM_KEYS]).toEqual([
    "documents",
    "graph",
    "review",
    "pending",
    "sources",
    "mappings",
    "ontologyRegressions"
  ]);
});
