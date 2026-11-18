/* 内置文档内容与检索纯函数（任务 10 W3 收口，对照旧仓 pages/Docs.tsx 纯逻辑面
   逐字移植）。文档本体自旧仓 web/src/docs/ 原样搬运（??raw 构建期打进 bundle，
   私有部署离线可用；公开路由——登录前也可读，旧仓注释原话）。
   全文检索/目录提取都是纯函数：docs-search.spec.ts 钉行为。 */
import ingestMd from "@/docs/ingest.md?raw";
import mcpMd from "@/docs/mcp.md?raw";

/** 文档清单：slug → 标题 + 内容（构建期打进 bundle） */
export const DOCS: { slug: string; title: string; body: string }[] = [
  { slug: "ingest", title: "Ingest interfaces", body: ingestMd },
  { slug: "mcp", title: "Agents over MCP", body: mcpMd }
];

/** 全文检索（客户端，文档已在 bundle 里）：命中行 → 文档 + 以命中词为中心的摘录 */
export function searchDocs(
  q: string
): { slug: string; title: string; snippet: string }[] {
  const needle = q.trim().toLowerCase();
  if (needle.length < 2) return [];
  const hits: { slug: string; title: string; snippet: string }[] = [];
  for (const d of DOCS) {
    for (const line of d.body.split("\n")) {
      const cleaned = line
        .replace(/[#`*|>-]/g, " ")
        .replace(/\s+/g, " ")
        .trim();
      const idx = cleaned.toLowerCase().indexOf(needle);
      if (idx < 0) continue;
      // 摘录窗口以命中词为中心，避免命中被 90 字截断截丢
      const start = Math.max(0, idx - 30);
      const snippet =
        (start > 0 ? "…" : "") + cleaned.slice(start, start + 100);
      hits.push({ slug: d.slug, title: d.title, snippet });
      if (hits.length >= 8) return hits;
    }
  }
  return hits;
}

/** 标题 → 锚点 id（TOC 与正文用同一函数保证对得上） */
export function slugify(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9一-龥]+/g, "-")
    .replace(/(^-+|-+$)/g, "");
}

/** 从 markdown 源提取 h2/h3 目录 */
export function tocOf(
  body: string
): { id: string; text: string; level: 2 | 3 }[] {
  const out: { id: string; text: string; level: 2 | 3 }[] = [];
  for (const line of body.split("\n")) {
    const m = /^(#{2,3})\s+(.+)$/.exec(line);
    if (!m) continue;
    const text = m[2].replace(/[`*]/g, "").trim();
    out.push({ id: slugify(text), text, level: m[1].length as 2 | 3 });
  }
  return out;
}

export function docBySlug(slug: string) {
  return DOCS.find(d => d.slug === slug) ?? DOCS[0];
}
