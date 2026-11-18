/* locales 死键闸（遗留 5-②）：根目录 locales/{en,zh-CN}.yaml 是 pure-admin 基座
   的 $t 词条。遗留收口时对照实际引用裁掉了 181 条死键（上游演示页的词条——
   menus.pureFlowChart/login.pureQRCodeLogin 这类在净化后的基座里已无消费）。
   本闸防两向回潮：①yaml 里再长出无引用的死键；②src 引用了 yaml 里不存在的键。
   动态引用面（路由 meta.title 经 $t(...)）已被静态扫描覆盖（remaining.ts 四键
   逐一在册）；词典真源 S（src/i18n/dicts）不归本闸管（tests/i18n.spec.ts）。 */
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { parse as parseYaml } from "yaml";

const ROOT = join(__dirname, "..");

function leafKeys(node: Record<string, unknown>, prefix = ""): string[] {
  const out: string[] = [];
  for (const [k, v] of Object.entries(node ?? {})) {
    if (v && typeof v === "object") out.push(...leafKeys(v as Record<string, unknown>, `${prefix}${k}.`));
    else out.push(prefix + k);
  }
  return out;
}

/** src 下全部 .vue/.ts/.tsx 源文本（node_modules/dist 除外） */
function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (name === "node_modules" || name === "dist" || name === "assets") continue;
      out.push(...sourceFiles(p));
    } else if (/\.(vue|ts|tsx)$/.test(name)) out.push(p);
  }
  return out;
}

const SOURCES = sourceFiles(join(ROOT, "src")).map(p => [
  p,
  readFileSync(p, "utf8")
] as const);
/** groups 与裁剪口径一致：yaml 顶层组 × pure 前缀键（基座词条命名习惯） */
const USED_RE =
  /(?:buttons|search|panel|menus|status|login|tableBar)\.pure[A-Za-z0-9-]+/g;
const used = new Set<string>();
for (const [, text] of SOURCES) for (const m of text.matchAll(USED_RE)) used.add(m[0]);

const en = parseYaml(readFileSync(join(ROOT, "locales/en.yaml"), "utf8"));
const zh = parseYaml(readFileSync(join(ROOT, "locales/zh-CN.yaml"), "utf8"));
const enKeys = leafKeys(en);
const zhKeys = leafKeys(zh);

describe("locales：死键闸（裁剪后不回潮）", () => {
  it("yaml 每一个键在 src 里都有静态引用（死键=0）", () => {
    const dead = enKeys.filter(k => !used.has(k));
    expect(dead, `死键回潮：${dead.join(", ")}`).toEqual([]);
  });

  it("src 引用的每个 pure 键在两份 yaml 里都存在（引用悬空=0）", () => {
    const missing = [...used].filter(k => !enKeys.includes(k) || !zhKeys.includes(k));
    expect(missing, `悬空引用：${missing.join(", ")}`).toEqual([]);
  });

  it("en 与 zh-CN 键集合完全一致（翻译补齐不缺行）", () => {
    expect([...zhKeys].sort()).toEqual([...enKeys].sort());
  });

  it("裁剪后仍保住基座必需键（：布局件与错误页在用）", () => {
    // 这几条是纯面板/页签/错误页的活键——裁剪脚本若失手全删，这里先炸
    for (const k of [
      "buttons.pureReload",
      "menus.pureLogin",
      "menus.pureAccessDenied",
      "menus.pureServerError",
      "status.pureLoad",
      "panel.pureSystemSet"
    ]) {
      expect(enKeys).toContain(k);
      expect(zhKeys).toContain(k);
    }
    expect(enKeys.length).toBeGreaterThan(60);
    expect(enKeys.length).toBeLessThan(100);
  });
});
