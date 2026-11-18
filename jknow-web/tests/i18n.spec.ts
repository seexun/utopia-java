// i18n 词典真断言：旧仓 web/src/i18n/ 搬运后的键存在性抽检。
// ①结构同构：en 定结构（Strings = typeof en），zh 的键集合与之完全一致（深度）；
// ②审定领域术语抽检：关键键在两个语言的取值与旧仓一致；
// ③vue-i18n 投影：字符串值条目在，函数值条目不进投影。
import { describe, it, expect } from "vitest";
import { en } from "@/i18n/dicts/en";
import { zh } from "@/i18n/dicts/zh";
import { stringsProjection } from "@/i18n";
import { S } from "@/i18n";

/** 深度收集对象的所有叶键路径（函数视为叶） */
function leafPaths(node: unknown, prefix = ""): string[] {
  if (node === null || node === undefined) return [];
  if (typeof node === "function") return [prefix];
  if (typeof node === "object") {
    const out: string[] = [];
    for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
      out.push(...leafPaths(v, prefix ? `${prefix}.${k}` : k));
    }
    return out;
  }
  return [prefix];
}

describe("i18n 词典：结构同构（en 定结构，zh 补齐）", () => {
  it("zh 与 en 的叶键集合完全一致（多、少、拼错都是编译+本测试双闸）", () => {
    const enKeys = leafPaths(en).sort();
    const zhKeys = leafPaths(zh).sort();
    expect(zhKeys).toEqual(enKeys);
    expect(enKeys.length).toBeGreaterThan(400);
  });

  it("顶栏领域分组齐全：nav/login/library/graph/ontology/review/mapping/account/alerts", () => {
    for (const group of [
      "nav",
      "login",
      "library",
      "graph",
      "ontology",
      "review",
      "mapping",
      "account",
      "alerts",
      "err",
      "app",
      "legal",
      "settings",
      "search",
      "ask"
    ]) {
      expect(en).toHaveProperty(group);
      expect(zh).toHaveProperty(group);
    }
  });
});

describe("i18n 词典：审定术语抽检（zh 取值与旧仓一致）", () => {
  it("登录页字段与文案", () => {
    expect(zh.login.signIn).toBe("登录");
    expect(zh.login.signUp).toBe("注册");
    expect(zh.login.email).toBe("邮箱");
    expect(zh.login.password).toBe("密码（至少 8 位）");
    expect(zh.login.networkError).toBe("网络异常，请重试");
    expect(zh.login.displayName).toBe("显示名称");
  });

  it("错误键表（422 直显文案的来源）", () => {
    expect(zh.err.password_too_short).toBe("密码至少 8 位。");
    expect(zh.err.bad_key).toBe("key 只能用小写字母、数字和下划线，最长 40 个字符。");
    expect(zh.err.registration_closed).toContain("自助注册");
  });

  it("导航与领域词", () => {
    expect(zh.nav.graph).toBe("图谱");
    expect(zh.nav.library).toBe("文库");
    expect(zh.nav.ask).toBe("对话");
    expect(zh.nav.search).toBe("检索");
    expect(zh.nav.settings).toBe("设置");
    expect(zh.nav.signOut).toBe("退出登录");
    expect(zh.review.title).toBe("审核");
    expect(zh.ontology.title).toBe("本体");
    expect(zh.mapping.title).toBe("数据映射");
    expect(zh.account.roleNames.owner).toBe("所有者");
    expect(zh.account.roleNames.viewer).toBe("只读");
  });

  it("英文侧同键存在（en 为结构权威）", () => {
    expect(en.login.signIn).toBe("Sign in");
    expect(en.nav.graph).toBe("Graph");
    expect(en.account.roleNames.owner).toBe("Owner");
  });

  it("带参函数条目在 S 上保持函数语义（当前语言=zh）", () => {
    expect(typeof S.library.deletedWithFacts).toBe("function");
    expect(S.library.deletedWithFacts(0)).toBe("文档已删除");
    expect(S.library.deletedWithFacts(3)).toContain("3 条事实");
    expect(typeof S.errDetail).toBe("function");
    expect(S.errDetail("没有配模型", "timeout")).toContain("timeout");
  });
});

describe("i18n → vue-i18n 投影（stringsProjection）", () => {
  it("字符串条目进投影，函数条目不进（vue-i18n 消息只能是字符串）", () => {
    const proj = stringsProjection(zh);
    const paths = leafPaths(proj);
    expect(paths).toContain("login.signIn");
    expect(paths).toContain("err.password_too_short");
    expect(paths.some(p => p.startsWith("library.deletedWithFacts"))).toBe(false);
    expect(paths.some(p => p.startsWith("errDetail"))).toBe(false);
  });

  it("两语言投影的字符串键集合一致", () => {
    const zhKeys = leafPaths(stringsProjection(zh)).sort();
    const enKeys = leafPaths(stringsProjection(en)).sort();
    expect(zhKeys).toEqual(enKeys);
  });
});
