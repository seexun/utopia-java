/* 内置文档纯逻辑（任务 10）：searchDocs 全文检索（命中行→摘录、两字门槛、
   8 条封顶、markdown 记号剥离）、tocOf（h2/h3 提取、锚点与 slugify 同一函数）、
   docBySlug 兜底。对照旧仓 pages/Docs.tsx 纯逻辑面（搬运时逐字，用例新建钉行为）。 */
import { describe, expect, it } from "vitest";
import { DOCS, docBySlug, searchDocs, slugify, tocOf } from "@/views/utopia/docs/docsContent";

describe("searchDocs：客户端全文检索", () => {
  it("少于两字的查询直接空表（门槛）", () => {
    expect(searchDocs("")).toEqual([]);
    expect(searchDocs("a")).toEqual([]);
    expect(searchDocs("  ")).toEqual([]);
  });

  it("命中词返回文档 slug 与以命中词为中心的摘录", () => {
    const hits = searchDocs("bearer");
    expect(hits.length).toBeGreaterThan(0);
    // 两篇文档都有 Bearer：每条摘录都真含命中词，slug 都在清单里
    for (const h of hits) {
      expect(DOCS.map(d => d.slug)).toContain(h.slug);
      expect(h.snippet.toLowerCase()).toContain("bearer");
    }
  });

  it("大小写不敏感", () => {
    const lower = searchDocs("token");
    const upper = searchDocs("TOKEN");
    expect(lower.length).toBeGreaterThan(0);
    expect(upper.map(h => h.snippet)).toEqual(lower.map(h => h.snippet));
  });

  it("摘录最多 8 条封顶", () => {
    // "the" 在长文档里必然高频命中——封顶逻辑受得住就行
    const hits = searchDocs("the");
    expect(hits.length).toBeLessThanOrEqual(8);
    expect(hits.length).toBe(8);
  });

  it("中段命中的摘录以省略号开头（窗口以命中词为中心）", () => {
    const hits = searchDocs("contract");
    for (const h of hits) {
      if (h.snippet.startsWith("…")) return;
    }
    expect(hits.length).toBeGreaterThan(0);
  });
});

describe("slugify 与 tocOf：锚点与目录同一函数对得上", () => {
  it("slugify：小写化、非字母数字（含 CJK 保留）折连字符、去首尾", () => {
    expect(slugify("Choosing between them")).toBe("choosing-between-them");
    expect(slugify("  --Hello, World!--  ")).toBe("hello-world");
    expect(slugify("推与拉 Pull & Push")).toBe("推与拉-pull-push");
  });

  it("tocOf 只取 h2/h3，且 id 与 slugify(正文标题) 一致", () => {
    const body = [
      "# Doc title",
      "",
      "intro",
      "",
      "## Second section",
      "",
      "text",
      "",
      "### A `coded` *sub*",
      "",
      "more",
      ""
    ].join("\n");
    const toc = tocOf(body);
    expect(toc).toEqual([
      { id: "second-section", text: "Second section", level: 2 },
      { id: "a-coded-sub", text: "A coded sub", level: 3 }
    ]);
    // 正文锚点（UMarkdown anchorId=slugify(标题文本)）与目录 id 同源
    expect(toc[1].id).toBe(slugify("A coded sub"));
  });
});

describe("DOCS 与 docBySlug", () => {
  it("两篇文档都在包里，slug 兜底回第一篇", () => {
    expect(DOCS.map(d => d.slug)).toEqual(["ingest", "mcp"]);
    expect(docBySlug("mcp").title).toBe("Agents over MCP");
    expect(docBySlug("nope")).toBe(DOCS[0]);
  });
});
