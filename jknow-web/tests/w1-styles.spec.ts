/* W1 样式层序回归（审查 C-1）：按钮基线复位必须在 @layer base 里。
   未分层 CSS 在级联层序里恒压过全部 layer（特异性无关）——首版按钮复位
   直接写在 utopia-pages.css 顶层，把 utilities 里的 bg-primary-soft /
   text-ink-2 / hover:bg-fill 全体击穿（URow 激活蓝底胶囊、Segmented 激活片
   全灭，导航态丢失）。tailwind.css 首行声明层序 theme < base < components <
   utilities——base 层的复位兜底、工具类自然赢回。本 spec 在**源文件层**钉住
   这两条（jsdom 不跑构建后的 CSSOM，检索式是最接近事实的断言）。

   内页样式回归根治（用户报「rail 菜单裸奔」）追加：reset.scss 的**元素复位**
   全量入 @layer base（第二个 C-1 同族——该文件 main.ts 最先导入，裸写的
   button/a/ul/p/svg 复位一直压过 utilities，领域页按钮/链接褪成裸文字）。
   钉法：剥掉全部 @layer 块后，reset.scss 顶层只允许 #app / .dark / .clearfix
   三个非元素选择器块；任何元素选择器出现在顶层即失败。 */
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(__dirname, "..");
const pages = readFileSync(
  resolve(root, "src/style/utopia-pages.css"),
  "utf-8"
);
const tailwind = readFileSync(resolve(root, "src/style/tailwind.css"), "utf-8");
const reset = readFileSync(resolve(root, "src/style/reset.scss"), "utf-8");

describe("按钮基线复位必须住在 @layer base（C-1 回归钉）", () => {
  it("button 复位规则包在 @layer base 块内", () => {
    const m = pages.match(/@layer base\s*\{[\s\S]*?\n\}/g);
    expect(m).toBeTruthy();
    const hasButtonReset = (m ?? []).some(block =>
      /@layer base\s*\{[\s\S]*\bbutton\b[\s\S]*background:\s*transparent/.test(
        block
      )
    );
    expect(hasButtonReset).toBe(true);
    // 顶层（未分层）不允许再出现 button 复位：剥掉所有 @layer 块后不得再有
    const outside = pages.replace(/@layer[\s\S]*?\n\}/g, "");
    expect(outside.includes("button {")).toBe(false);
  });

  it("tailwind.css 声明层序 theme→base→components→utilities（utilities 赢回的前提）", () => {
    expect(tailwind).toMatch(
      /@layer theme,\s*base,\s*components,\s*utilities\s*;/
    );
  });
});

describe("reset.scss 元素复位全量入 @layer base（内页样式回归根治钉）", () => {
  // 与实现保持同表：这些元素/伪类/属性选择器的复位规则不允许再回到顶层
  const elementHeads = [
    "html",
    "body",
    "hr",
    "abbr",
    "a",
    "b",
    "strong",
    "code",
    "kbd",
    "samp",
    "pre",
    "small",
    "sub",
    "sup",
    "table",
    "button",
    "input",
    "optgroup",
    "select",
    "textarea",
    "progress",
    "summary",
    "blockquote",
    "dl",
    "dd",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "figure",
    "p",
    "fieldset",
    "legend",
    "ol",
    "ul",
    "menu",
    "svg",
    "video",
    "canvas",
    "audio",
    "iframe",
    "embed",
    "object",
    "img",
    "label",
    "*",
    ":disabled",
    "[hidden]",
    "[type",
    "[role",
    "div:focus",
    "::-webkit"
  ];

  it("reset.scss 存在 @layer base 块", () => {
    expect(reset).toMatch(/@layer base\s*\{/);
  });

  it("剥掉 @layer 块后顶层不得残留任何元素选择器复位", () => {
    // 去注释 → 去掉 @layer 块（非贪婪到「行首 }」；块内 scss 嵌套不影响——
    // 元素复位都是平铺规则）→ 剩余顶层选择器逐行断言
    const noComments = reset.replace(/\/\*[\s\S]*?\*\//g, "");
    const outside = noComments.replace(/@layer base\s*\{[\s\S]*?\n\}/g, "");
    const lines = outside
      .split("\n")
      .map(l => l.trim())
      .filter(l => l && !l.startsWith("}") && !l.startsWith("@"));
    const offenders = lines.filter(l =>
      elementHeads.some(head =>
        l.startsWith(head + " ") ||
        l.startsWith(head + ",") ||
        l.startsWith(head + "{") ||
        l === head
      )
    );
    expect(offenders).toEqual([]);
  });

  it("三个保留的顶层块仍是 #app / .dark / .clearfix（不夹带元素复位）", () => {
    const noComments = reset.replace(/\/\*[\s\S]*?\*\//g, "");
    const outside = noComments.replace(/@layer base\s*\{[\s\S]*?\n\}/g, "");
    const heads = outside
      .split("\n")
      .filter(l => /^[^\s}]/.test(l) && l.trim().endsWith("{"))
      .map(l => l.trim());
    expect(heads).toEqual(["#app {", ".dark {", ".clearfix {"]);
  });
});
