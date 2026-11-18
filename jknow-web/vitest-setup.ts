/* vitest 全局 setup：给 node 环境一个最小 localStorage 桩，并把界面语言
   固定为英文。测试里的文案断言（按钮定位、SSR 输出、fmtInterval 结果等）
   全部锚定英文包；产品默认语言（i18n/index.ts 的 detect()）与测试解耦，
   以后改默认语言不再牵动测试。 */
const store = new Map<string, string>([["utopia.lang", "en"]]);
globalThis.localStorage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
} as unknown as Storage;
