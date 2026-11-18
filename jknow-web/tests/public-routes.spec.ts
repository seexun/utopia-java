// kb 动态路由注册面（源仓 public-routes.spec 的 jknow 对等物）。
// 源仓断言「公开页在壳外」（remaining.ts 不进 pure-admin 拍平树）；jknow 的路由
// 体系换若依 router——kb/account 若依壳内联（规格 2026-11-18-kb-inline-layout）
// 后语义翻转为「全部业务页在若依 Layout 壳内」：
//   - /kb 是 Layout 壳记录（redirect → /library 保留在记录上），10 个
//     :kbId/* 页全部相对 path 挂 children、hidden 不进菜单、带真组件；
//   - 旧路径族（/graph /search /chat /library /ontology /mappings /review
//     /kb-settings）仍挂 KbRedirect 兜底组件（源仓 LegacyX 同构，独立整页）；
//   - /docs 是重定向记录（→ /docs/ingest，源仓 M-3 同款），slug 页有真组件
//     （顶级独立整页，不经 Layout）；
//   - 账户层 /account 族挂 Layout children："" 子记录 redirect →
//     /account/profile，去壳化 shell 容器（页内 tab + router-view）带
//     profile/kbs/tokens 三页，/admin 并入容器（管理子页签住在 shell 上）。
// 登录校验统一交若依 permission.ts 的 beforeEach（未登录 → /login?redirect=）。
import { describe, it, expect } from "vitest";
import router, { constantRoutes } from "@/router";

function routeOf(path: string) {
  return constantRoutes.find(r => r.path === path);
}

// /kb Layout 记录的 10 个子页（path 相对化：:kbId/…，无 /kb/ 前缀）
const KB_CHILD_PATHS = [
  ":kbId/graph",
  ":kbId/search",
  ":kbId/library",
  ":kbId/doc/:docId",
  ":kbId/ontology",
  ":kbId/review",
  ":kbId/mappings",
  ":kbId/settings",
  ":kbId/chat/:conversationId?",
  ":kbId/docs"
];

const LEGACY_PATHS = [
  "/graph",
  "/search",
  "/chat",
  "/library",
  "/ontology",
  "/mappings",
  "/review",
  "/kb-settings"
];

describe("kb 动态路由在 constantRoutes 的 Layout children（若依壳内联）", () => {
  it("/kb 是 Layout 壳记录：hidden、redirect → /library、children 10 条", () => {
    const r = routeOf("/kb");
    expect(r, "constantRoutes 缺 /kb").toBeDefined();
    expect(r!.hidden).toBe(true);
    // 非懒加载的直挂组件（Layout import），区别于子页的 () => import(...) 惰函数
    expect(typeof r!.component).toBe("object");
    expect(String(r!.redirect)).toBe("/library");
    expect(r!.children).toHaveLength(10);
  });

  it.each(KB_CHILD_PATHS)("/kb 子路由 %s：相对 path、hidden、带真组件", p => {
    const r = routeOf("/kb")!.children!.find(c => c.path === p);
    expect(r, `/kb children 缺 ${p}`).toBeDefined();
    expect(r!.hidden).toBe(true);
    expect(r!.component).toBeDefined();
    expect(p.startsWith("/kb/")).toBe(false);
  });

  it.each(["/docs"])("%s 是重定向记录（不带组件）", path => {
    const r = routeOf(path);
    expect(r, `constantRoutes 缺 ${path}`).toBeDefined();
    expect(r!.component).toBeUndefined();
    expect(String(r!.redirect)).toBe("/docs/ingest");
  });

  it.each(LEGACY_PATHS)("%s 挂 KbRedirect 兜底组件（源仓 LegacyX 同构）", path => {
    const r = routeOf(path);
    expect(r, `constantRoutes 缺 ${path}`).toBeDefined();
    expect(r!.hidden).toBe(true);
    expect(r!.component).toBeDefined();
  });

  it("resolve 面：kb 页在若依壳内（matched 链 Layout → 页面，长度 2）", () => {
    for (const p of [
      "/kb/k1/graph",
      "/kb/k1/search",
      "/kb/k1/library",
      "/kb/k1/doc/d1",
      "/kb/k1/ontology",
      "/kb/k1/review",
      "/kb/k1/mappings",
      "/kb/k1/settings",
      "/kb/k1/chat",
      "/kb/k1/chat/c1",
      "/kb/k1/docs"
    ]) {
      const m = router.resolve({ path: p });
      expect(m.matched, p).toHaveLength(2);
      expect(m.matched[0].path, p).toBe("/kb");
      expect(m.matched[0].components?.default, p).toBeDefined();
      expect(m.matched[1].components?.default, p).toBeDefined();
    }
  });

  it("resolve 面：旧路径与 /docs slug 仍为独立整页（matched 链长 1）", () => {
    for (const p of ["/library", "/graph", "/docs/ingest"]) {
      const m = router.resolve({ path: p });
      expect(m.matched).toHaveLength(1);
      expect(m.matched[0].components?.default).toBeDefined();
    }
  });

  it("hidden 语义对齐：kb 路由不进菜单（壳记录与全部子记录 hidden:true，菜单开启在 V0102/T4）", () => {
    const kbWrapper = routeOf("/kb")!;
    expect(kbWrapper.children!.every(c => c.hidden === true)).toBe(true);
    const legacy = constantRoutes.filter(r => LEGACY_PATHS.includes(r.path));
    expect(legacy.length).toBe(8);
    expect(legacy.every(r => r.hidden === true)).toBe(true);
  });
});

// 账户层路由（T5 收口 + 壳内联重排）：kb 页顶栏 user-menu（个人资料/我的库/
// 部署管理）与「新建知识库」深链（/account/kbs?create=true）的链接目标。
// /account 裸址由 "" 子记录 redirect → /account/profile；四页解析链均为
// Layout → 去壳化 shell 容器 → 页面（/admin 并入容器，matched 链长 3）。
describe("账户层路由在 constantRoutes 的 Layout children（去壳化容器）", () => {
  const ACCOUNT_PAGES = [
    ["/account/profile", 3],
    ["/account/kbs", 3],
    ["/account/tokens", 3],
    ["/admin", 3]
  ] as const;

  it.each(ACCOUNT_PAGES)(
    "%s 解析到若依壳内（matched 链 Layout → 壳容器 → 页面，长度 %i）",
    (path, len) => {
      const m = router.resolve({ path });
      expect(m.matched, path).toHaveLength(len);
      expect(m.matched[0].path, path).toBe("/account");
      expect(m.matched[0].components?.default, path).toBeDefined();
      expect(m.matched[1].components?.default, path).toBeDefined();
      expect(m.matched[len - 1].components?.default, path).toBeDefined();
    }
  );

  it("/account 是 Layout 壳记录：hidden、空 path 子记录 redirect → /account/profile", () => {
    const r = routeOf("/account");
    expect(r, "constantRoutes 缺 /account").toBeDefined();
    expect(r!.hidden).toBe(true);
    expect(typeof r!.component).toBe("object");
    expect(r!.children).toHaveLength(2);
    const empty = r!.children!.find(c => c.path === "");
    expect(empty, "缺 /account → /account/profile 的 redirect 子记录").toBeDefined();
    expect(String(empty!.redirect)).toBe("/account/profile");
    const shellRec = r!.children!.find(c => c.path === "/account");
    expect(shellRec, "缺去壳化 shell 容器记录").toBeDefined();
    expect(shellRec!.children).toHaveLength(4);
    expect(shellRec!.children!.every(c => c.hidden === true)).toBe(true);
  });
});

// 首页直达链（menu-ia T2 步骤 1，选型①）：'' 顶级记录（vue-router 里等价 '/'）
// redirect 改指 /graph，登录（push redirect || '/'）与 Logo 点击（to='/'）由此
// 进入 KbRedirect 接管链 → /kb/{当前库}/graph。②新增 {path:'/'} 落选：与 ''
// 同路径重复注册、先注册者恒胜。/index 若依默认首页记录保留仍可直达。
describe("首页直达链（'' 根记录 redirect → /graph）", () => {
  it("'' 顶级记录 redirect → /graph（不新增 '/' 重复记录）", () => {
    const root = constantRoutes.find(r => r.path === "");
    expect(root, "constantRoutes 缺 '' 根记录").toBeDefined();
    expect(String(root!.redirect)).toBe("/graph");
    // 落选方案②不得回流：同路径两条根记录 = 注册序遮蔽 bug
    expect(
      constantRoutes.filter(r => r.path === "" || r.path === "/"),
      "根路径记录应唯一（''/ '/' 二选一）"
    ).toHaveLength(1);
  });

  it("链尾在位：/graph → /kb/:kbId/graph 两条腿都能解析", () => {
    const legacy = router.resolve({ path: "/graph" });
    expect(legacy.matched).toHaveLength(1);
    expect(legacy.matched[0].components?.default).toBeDefined();
    const kb = router.resolve({ path: "/kb/k1/graph" });
    expect(kb.matched).toHaveLength(2);
    expect(kb.matched[0].path).toBe("/kb");
  });
});
