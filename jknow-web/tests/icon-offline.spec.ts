/* 图标离线化覆盖闸（内页样式回归根治，与任务 10 菜单图标离线化同族）：
   领域页此前直接 import { Icon } from "@iconify/vue"——**在线**运行时，
   注册表里没有的键会在运行时向 api.iconify.design 现拉，内网/离线环境
   静默消失（用户帧里页面图标全裸）。根治两件事，本 spec 各钉一条：
   ① src/views 下不许再出现 "@iconify/vue" 在线 Icon 导入——页面级 Icon
     一律走 "@iconify/vue/dist/offline"（与 offlineIcon.ts 的 addIcon 同一
     模块实例、同一份注册表；两个运行时各持一份注册表，只注册不换导入无效）；
   ② 页面里静态写出的每一个 "prefix:name" 图标键必须在 offlineIcon.ts 里
     有 addIcon 注册——新页面用了新键而没注册，此闸当场红。 */
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(__dirname, "..");

function walk(dir: string, exts: string[], out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, exts, out);
    else if (exts.some(e => name.endsWith(e))) out.push(p);
  }
  return out;
}

const offlineIconSrc = readFileSync(
  join(root, "src/components/knowledge/ReIcon/offlineIcon.ts"),
  "utf-8"
);
const registered = new Set(
  [...offlineIconSrc.matchAll(/\["([a-z0-9][a-z0-9:-]*)"/g)].map(m => m[1])
);

describe("页面级 Icon 必须走离线运行时与离线注册表", () => {
  it("src/views 下没有在线 @iconify/vue Icon 导入（一律 dist/offline）", () => {
    const offenders = walk(join(root, "src/views"), [
      ".vue",
      ".tsx",
      ".ts"
    ]).filter(f => /from\s+"@iconify\/vue"\s*;/.test(readFileSync(f, "utf-8")));
    expect(offenders).toEqual([]);
  });

  it("页面静态使用的每个图标键都在 offlineIcon.ts 注册过", () => {
    // 领域代码里带冒号的**非图标**字面量白名单（Vue emit 名 / SSE 事件名）；
    // 出现新的非图标冒号字面量时在此显式登记，避免静默漏网
    const NON_ICON_KEYS = new Set([
      "update:open",
      "chat:draft",
      // 若依视图的 v-model emit 名（walk 面扩到 src/views 全体后浮现）
      "update:visible",
      // jknow 侧若依视图的内联样式字面量（walk 面扩到 src/views 全体后浮现）
      "cursor:pointer",
      "float:right",
      // offlineIcon.ts 自身头注里的示例键（文件落位 knowledge 后进了扫描面）
      "lucide:xxx"
    ]);
    const files = [
      ...walk(join(root, "src/views"), [".vue", ".tsx", ".ts"]),
      ...walk(join(root, "src/components/knowledge"), [".vue", ".tsx", ".ts"])
    ];
    const missing: { key: string; file: string }[] = [];
    for (const f of files) {
      const src = readFileSync(f, "utf-8");
      // 只认完整的 "prefix:name" 字面量（prefix 字母开头、name 字母数字开头，
      // 排除时间/路径之类误报——领域页里带冒号字符串字面量就是图标键）
      for (const m of src.matchAll(
        /["']([a-z][a-z0-9]*:[a-z][a-z0-9-]*)["']/g
      )) {
        const key = m[1];
        if (!registered.has(key) && !NON_ICON_KEYS.has(key))
          missing.push({ key, file: f });
      }
    }
    expect(missing).toEqual([]);
  });

  it("已知 26 个页面图标键全部注册（lucide×25 + simple-icons×1）", () => {
    const expected = [
      "lucide:arrow-left-right",
      "lucide:book-open",
      "lucide:check-check",
      "lucide:chevron-right",
      "lucide:database",
      "lucide:history",
      "lucide:inbox",
      "lucide:key-round",
      "lucide:layers",
      "lucide:lock",
      "lucide:network",
      "lucide:pencil",
      "lucide:plug",
      "lucide:plus",
      "lucide:scale",
      "lucide:settings-2",
      "lucide:shield-check",
      "lucide:split",
      "lucide:table",
      "lucide:triangle-alert",
      "lucide:upload",
      "lucide:user-round",
      "lucide:users",
      "lucide:wand-2",
      "lucide:x",
      "simple-icons:github"
    ];
    const notRegistered = expected.filter(k => !registered.has(k));
    expect(notRegistered).toEqual([]);
  });
});
