// 审查 I-3：公开页不进壳——/docs /docs/:slug /privacy /terms 在 remaining.ts
// （与 /login 同款独立路由，不经 formatTwoStageRoutes 拍到 Home(Layout) 之下）。
// 匿名访问渲染独立整页：不起壳、不发 workspaces/health/unread（结构保证：
// 不在 Home 之下就不会渲染 Layout，壳的查询链根本不创建）。
// W3 补：/docs 改重定向记录（→ /docs/ingest，M-3 收口）——重定向记录无
// components，单独断言 redirect 指向（其余公开页照旧断言真组件）。
import { describe, it, expect } from "vitest";
import { router, constantRoutes } from "@/router";

const PUBLIC_PATHS = ["/privacy", "/terms", "/docs/ingest"];

describe("公开页在壳外（审查 I-3）", () => {
  it.each(PUBLIC_PATHS)(
    "%s 的 matched 链不含 Home/Layout——独立整页路由",
    path => {
      const m = router.resolve({ path });
      expect(m.matched).toHaveLength(1);
      expect(m.matched[0].name).not.toBe("Home");
      expect(m.matched[0].components?.default).toBeDefined();
    }
  );

  it("/docs 是重定向记录（→ /docs/ingest，M-3）：不在壳下且不带组件", () => {
    const m = router.resolve({ path: "/docs" });
    expect(m.matched).toHaveLength(1);
    expect(m.matched[0].name).not.toBe("Home");
    expect(m.matched[0].components?.default).toBeUndefined();
    expect(String(m.matched[0].redirect)).toBe("/docs/ingest");
  });

  it("对照组：kb 页仍挂在 Home(Layout) 壳之下", () => {
    const m = router.resolve({ path: "/kb/k1/graph" });
    expect(m.matched.length).toBeGreaterThan(1);
    expect(m.matched[0].name).toBe("Home");
  });

  it("拍平树（Home 的 children）里没有公开路由路径", () => {
    const home = constantRoutes.find(r => r.path === "/");
    const paths = (home?.children ?? []).map(c => c.path);
    for (const p of ["/privacy", "/terms", "/docs"]) {
      expect(paths).not.toContain(p);
    }
    // kb 作用域页仍应在拍平树里（壳的宿主）
    expect(paths).toContain("/kb/:kbId/graph");
  });
});
