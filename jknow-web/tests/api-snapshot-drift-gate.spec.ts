/* API 快照漂移闸（终审 Minor-4 归档裁定：保留 + 漂移闸）。
   domain/api.ts 是旧仓 api.ts 的 2951 行全文逐字快照（Ⅱ(c)，ASSET-LEDGER 在案）：
   领域 Ⅰ类资产（steps/liveAnswer/chatStream 用例/ruleExpressions 内存桩等）的
   import 面零改写要求依赖它的旧世界语义，删则必须改用例——违反搬运铁律，
   故**保留**。但它是「第二个 API 真源」：快照里的端点集合若与 src/api/（axios
   新世界）分叉，读它的人会被带偏。本闸做**文本层契约断言**：
     快照的 (method, path) 端点集合 ⊆ src/api 域模块的实际端点集合。
   快照端点从真实代码面消失（服务端裁撤/改名）时本闸先炸——台账里「保留」的
   理由随事实失效，届时应重新评估裁撤。反向多余（src/api 新增）是新世界的
   自主演进，不设闸（快照不必追新）。
   解析面：调用点文本扫描（括号配平的参数文本内取首个模板/字符串字面量 +
   method:）；模板插值按「整段占位（/${x}/ 保留 :v）/ 尾缀插值（?query、
   条件路径 → 截断）」归一化；两侧统一剥 /api 前缀（client baseURL 承担）。 */
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(__dirname, "..");

/** 去注释（行/块）——避免把 client.ts 头注里的示例路径当端点。
 *  行注释只剥「空白后起 //」的：https:// 这种串内协议不误伤。 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[\s])\/\/[^\n]*/g, "$1");
}

function walk(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (/\.tsx?$/.test(name)) out.push(p);
  }
  return out;
}

/** 从 open 的 "(" 找配对的 ")"；字符串/模板字面量整体跳过 */
function balanced(text: string, open: number): number {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (c === "(") depth++;
    else if (c === ")") {
      depth--;
      if (depth === 0) return i;
    } else if (c === "`" || c === '"' || c === "'") {
      const q = c;
      i++;
      while (i < text.length && text[i] !== q) {
        if (text[i] === "\\") i++;
        i++;
      }
    }
  }
  return -1;
}

/** 模板归一化：段中插值 → :v；尾缀插值（query/条件尾）→ 截断；再剥 query */
function normTemplate(tpl: string): string {
  let out = "";
  let i = 0;
  while (i < tpl.length) {
    if (tpl[i] === "$" && tpl[i + 1] === "{") {
      let depth = 1;
      let j = i + 2;
      while (j < tpl.length && depth > 0) {
        if (tpl[j] === "{") depth++;
        else if (tpl[j] === "}") depth--;
        else if (tpl[j] === "`") {
          j++;
          while (tpl[j] !== "`") j++;
        }
        j++;
      }
      if (tpl.slice(j).startsWith("/")) out += ":v";
      else break;
      i = j;
    } else {
      out += tpl[i];
      i++;
    }
  }
  return out.replace(/\?.*$/, "");
}

type Endpoint = string; // `METHOD /v1/...`

function extractRequestEndpoints(text: string, callRe: RegExp): Set<Endpoint> {
  const src = stripComments(text);
  const set = new Set<Endpoint>();
  const re = new RegExp(callRe.source, "g");
  let m: RegExpExecArray | null;
  while ((m = re.exec(src))) {
    const open = src.indexOf("(", m.index);
    const close = balanced(src, open);
    if (close < 0) continue;
    const args = src.slice(open + 1, close);
    const lit = args.match(/`([^`]*)`/) ?? args.match(/["']([^"']+)["']/);
    if (!lit) continue;
    const p = normTemplate(lit[1]).replace(/\$\{[^}]*\}/g, ":v");
    if (!p.startsWith("/")) continue;
    const method = args.match(/method:\s*["'](\w+)["']/)?.[1]?.toUpperCase() ?? "GET";
    set.add(`${method} ${p.replace(/^\/api/, "")}`);
  }
  return set;
}

function extractFetchEndpoints(text: string): Set<Endpoint> {
  const src = stripComments(text);
  const set = new Set<Endpoint>();
  for (const m of src.matchAll(/fetch\(\s*`([^`]*)`/g))
    set.add(`SSE ${normTemplate(m[1]).replace(/^\/api/, "")}`);
  return set;
}

const snapshotText = readFileSync(join(ROOT, "src/domain/api.ts"), "utf8");
const snapshot: Set<Endpoint> = new Set([
  ...extractRequestEndpoints(snapshotText, /(?:^|[^\w.$])request(?:<[^()]*>)?\(/),
  ...extractFetchEndpoints(snapshotText)
]);

/** jknow T2 裁撤登记：auth 域 9 端点不迁（auth.ts 明确不拷——登录/登出/当前
 *  用户/改密/OIDC 全走若依体系 /login、/getInfo、/logout）。本闸头注自带的
 *  演进条款（"快照端点从真实代码面消失（服务端裁撤/改名）时本闸先炸——届时
 *  应重新评估裁撤"）预设的正是这一刻：此处登记裁撤决定，闸对其余 157 个
 *  知识图谱端点的约束力保持不变（裁定落字 T4 报告）。 */
const RETIRED_AUTH = new Set<Endpoint>([
  "POST /v1/auth/login",
  "POST /v1/auth/register",
  "POST /v1/auth/logout",
  "GET /v1/auth/me",
  "PATCH /v1/auth/me",
  "POST /v1/auth/password",
  "GET /v1/auth/oidc/status",
  "GET /v1/auth/oidc/me",
  "DELETE /v1/auth/oidc/me"
]);
for (const e of RETIRED_AUTH) snapshot.delete(e);

const actual: Set<Endpoint> = new Set();
for (const f of walk(join(ROOT, "src/api"))) {
  const t = readFileSync(f, "utf8");
  extractRequestEndpoints(t, /http\.request(?:<[^()]*>)?\(/).forEach(e => actual.add(e));
  extractFetchEndpoints(t).forEach(e => actual.add(e));
}

describe("API 快照漂移闸：domain/api.ts ⊆ src/api", () => {
  it("快照的每个端点（method+path）都在 src/api 实际端点集合里", () => {
    const missing = [...snapshot].filter(e => !actual.has(e)).sort();
    expect(
      missing,
      `快照端点已从 src/api 消失（${missing.length} 条）——domain/api.ts 的「保留」` +
        `裁定前提失效，请重新评估裁撤或更新 src/api：\n  ${missing.join("\n  ")}`
    ).toEqual([]);
  });

  it("两侧端点都在百级规模（提取器失灵即炸）", () => {
    expect(snapshot.size).toBeGreaterThan(120);
    expect(actual.size).toBeGreaterThan(120);
    // 已知的新世界演进面（仅记录，不设闸）：SSE 流端点的两种 host 形态、
    // 上传 multipart 直连——快照不必追新，但变化要留痕。
    const extras = [...actual].filter(e => !snapshot.has(e)).sort();
    expect(extras.length).toBeLessThan(10);
    expect(extras).toMatchSnapshot();
  });
});
