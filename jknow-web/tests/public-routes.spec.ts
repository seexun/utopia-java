// kb 动态路由注册面（源仓 public-routes.spec 的 jknow 对等物）。
// 源仓断言「公开页在壳外」（remaining.ts 不进 pure-admin 拍平树）；jknow 的路由
// 体系换若依 router——T3 的等价语义是「kb 动态路由全部落在 constantRoutes、
// hidden 不进菜单、页面为独立整页路由（不经若依 Layout 拍平）」：
//   - 10 个 /kb/:kbId/* 页各有真组件（含 library/mappings/doc 三条补全）；
//     /kb 是纯重定向记录（→ /library）；
//   - 旧路径族（/graph /search /chat /library /ontology /mappings /review
//     /kb-settings）挂 KbRedirect 兜底组件（源仓 LegacyX 同构）；
//   - /docs 是重定向记录（→ /docs/ingest，源仓 M-3 同款），slug 页有真组件；
//   - 账户层 /account 族（shell+profile/my-kbs/tokens/admin，T5 收口）也是
//     hidden 独立整页——user-menu 与「新建」深链的链接目标。
// 登录校验统一交若依 permission.ts 的 beforeEach（未登录 → /login?redirect=）。
import { describe, it, expect } from "vitest";
import router, { constantRoutes } from "@/router";

function routeOf(path: string) {
  return constantRoutes.find(r => r.path === path);
}

const KB_PAGES = [
  "/kb/:kbId/graph",
  "/kb/:kbId/search",
  "/kb/:kbId/library",
  "/kb/:kbId/doc/:docId",
  "/kb/:kbId/ontology",
  "/kb/:kbId/review",
  "/kb/:kbId/mappings",
  "/kb/:kbId/settings",
  "/kb/:kbId/chat/:conversationId?",
  "/kb/:kbId/docs",
  "/docs/:slug"
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

describe("kb 动态路由在 constantRoutes（hidden 独立整页）", () => {
  it.each(KB_PAGES)("%s 已注册、hidden、带真组件", path => {
    const r = routeOf(path);
    expect(r, `constantRoutes 缺 ${path}`).toBeDefined();
    expect(r!.hidden).toBe(true);
    expect(r!.component).toBeDefined();
  });

  it.each(["/kb", "/docs"])("%s 是重定向记录（不带组件）", path => {
    const r = routeOf(path);
    expect(r, `constantRoutes 缺 ${path}`).toBeDefined();
    expect(r!.component).toBeUndefined();
    expect(String(r!.redirect)).toBe(path === "/kb" ? "/library" : "/docs/ingest");
  });

  it.each(LEGACY_PATHS)("%s 挂 KbRedirect 兜底组件（源仓 LegacyX 同构）", path => {
    const r = routeOf(path);
    expect(r, `constantRoutes 缺 ${path}`).toBeDefined();
    expect(r!.hidden).toBe(true);
    expect(r!.component).toBeDefined();
  });

  it("resolve 面：kb 页不在任何 Layout 壳之下（matched 链长 1）", () => {
    const m = router.resolve({ path: "/kb/k1/graph" });
    expect(m.matched).toHaveLength(1);
    expect(m.matched[0].components?.default).toBeDefined();
  });

  it("resolve 面：旧路径与 /docs slug 同为独立整页", () => {
    for (const p of ["/library", "/graph", "/docs/ingest"]) {
      const m = router.resolve({ path: p });
      expect(m.matched).toHaveLength(1);
      expect(m.matched[0].components?.default).toBeDefined();
    }
  });

  it("hidden 语义对齐：kb 路由不进菜单（全部 hidden:true，菜单开启在 V0102/T4）", () => {
    const kbRoutes = constantRoutes.filter(
      r => r.path.startsWith("/kb") || LEGACY_PATHS.includes(r.path)
    );
    // 10 个 /kb/:kbId/* + /kb 重定向 + 8 条旧路径（/kb-settings 同前缀只计一次）
    expect(kbRoutes.length).toBe(19);
    expect(kbRoutes.every(r => r.hidden === true)).toBe(true);
  });
});

// 账户层路由（T5 收口）：kb 页顶栏 user-menu（个人资料/我的库/部署管理）与
// 「新建知识库」深链（/account/kbs?create=true）的链接目标。源仓
// remaining.ts 的 AccountShell 族——独立整页壳（不经若依 Layout），子项绝对
// 路径仍挂壳渲染（matched 链 = shell + 页面）。
describe("账户层路由在 constantRoutes（AccountShell 独立整页）", () => {
  const ACCOUNT_PAGES = ["/account", "/account/kbs", "/account/tokens", "/admin"];

  it.each(ACCOUNT_PAGES)(
    "%s 解析到 account 壳（matched 链长 2，壳 + 页面）",
    path => {
      const m = router.resolve({ path });
      expect(m.matched).toHaveLength(2);
      expect(m.matched[0].components?.default).toBeDefined();
      expect(m.matched[1].components?.default).toBeDefined();
    }
  );

  it("hidden 语义对齐：/account 父记录不进菜单", () => {
    const r = routeOf("/account");
    expect(r, "constantRoutes 缺 /account").toBeDefined();
    expect(r!.hidden).toBe(true);
    expect(r!.children?.length).toBe(4);
  });
});
