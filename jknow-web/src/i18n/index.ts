// 界面语言：**看的人自己定**，不经过后端（旧仓 docs/decisions/0004 的同款决定）。
// 和暗色模式同类——并发上限那种设置描述的是部署，界面语言描述的是读者。
//
// 词典真源在 `./dicts/`（旧仓 web/src/i18n/ 原样搬运：en 定结构，zh 补中文）。
// 本文件只做三件事：解析语言、给出当前语言包 `S`、提供切换。
//
// **函数值条目**（如 `S.library.deletedWithFacts(n)`）保留为函数——vue-i18n
// 的消息只能是字符串，表达不了旧词典里的三元组/复数逻辑。所以领域页面的
// 取词一律走 `S`（与旧仓 React 页面逐行同构，搬运零改写）；vue-i18n 实例
// （plugins/i18n.ts）额外装了词典的字符串子集，供基座 `$t`/`transformI18n`
// 与纯字符串键的场景使用。两处的键来自同一份词典，不会分叉。
import { en, type Strings } from "./dicts/en";
import { zh } from "./dicts/zh";

export type { Strings };

export const LANGS = ["en", "zh"] as const;
export type Lang = (typeof LANGS)[number];

/** 语言自己的名字，永远不翻译——在切换器里，"中文"对看不懂英文的人才是路标 */
export const LANG_NAMES: Record<Lang, string> = { en: "English", zh: "中文" };

const BUNDLES: Record<Lang, Strings> = { en, zh };
const KEY = "utopia.lang";

function detect(): Lang {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved && (LANGS as readonly string[]).includes(saved))
      return saved as Lang;
  } catch {
    // 隐私模式下 localStorage 会抛——回落到默认语言，别让首屏挂掉
  }
  // **不跟随浏览器语言**，默认中文（2026-11-09 产品决策：中文包已追平，
  // 主要用户为中文团队）。人自己选过的仍然作数（上面那段）
  return "zh";
}

export const lang: Lang = detect();

/** 当前语言包。模块加载时定一次——所以模块级常量（`const X = S.a.b`）也是对的 */
export const S: Strings = BUNDLES[lang];

/**
 * 切语言走整页重载，不是重挂载（与旧仓同一决定）。
 *
 * 页面里已经有一处在模块顶层求值（如规则编辑器的选项表），
 * 重挂载会让它们停在旧语言且**不报错**；重载让整个模块图重新求值，
 * 代价只是一次刷新——切语言一年也没几次。
 */
export function setLang(next: Lang) {
  if (next === lang) return;
  try {
    localStorage.setItem(KEY, next);
  } catch {
    // 存不下就只对本次会话生效，好过什么都不发生
  }
  location.reload();
}

/** 给 vue-i18n 用的消息投影：词典里所有**字符串值**条目的嵌套对象；
 *  函数值条目跳过（vue-i18n 消息只能是字符串）。两个语言投影成对生成，
 *  键集合一致（zh 的结构由 en 定型，编译期保证）。 */
export function stringsProjection(bundle: Strings): Record<string, unknown> {
  const walk = (node: unknown): Record<string, unknown> | string => {
    if (typeof node === "string") return node;
    if (node && typeof node === "object") {
      const out: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
        if (typeof v === "string" || typeof v === "number") out[k] = v;
        else if (v && typeof v === "object") out[k] = walk(v);
        // 函数值条目：不进 vue-i18n（走 S 取）
      }
      return out;
    }
    return "";
  };
  return walk(bundle) as Record<string, unknown>;
}
