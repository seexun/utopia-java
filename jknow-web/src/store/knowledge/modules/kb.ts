// 当前知识库上下文（对照旧仓 kb.tsx + wsStore.ts：URL 优先、localStorage 记忆）。
// W1 把 W0 的最小状态机扩成完整的库上下文：
//   - kbId：URL 里的 kbId 为准（KbScope 载入时调 load），否则回落 localStorage 记忆；
//   - kbDetail：当前库详情——my_role 只在详情接口里，是 useKbRole 的判定源；
//   - workspaceId：记忆的工作区（对照旧仓 wsStore("utopia.ws")）。
// 库列表 / 工作区列表不在这里：它们是服务端状态，由 useKbContext()（composables/）
// 用 vue-query 管缓存，多个组件（切换器/重定向页）共享同一份。
// 切库动作（写 store + vue-query 失效 + 路由跳转）也在 useKbContext() 里——它需要
// router 与 queryClient，pinia store 只存状态。
import { defineStore } from "pinia";
import store from "@/store";
import * as kbsApi from "@/api/knowledge/kbs";
import type { Kb } from "@/api/knowledge/types";

const KB_KEY = "utopia.kb";
const WS_KEY = "utopia.ws";

function readLocal(key: string): string {
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}

export const useKbContext = defineStore("utopia-kb", {
  state: () => ({
    // 记忆里的当前库 id（URL 里有 kbId 时以 URL 为准，由 load() 进来）
    kbId: readLocal(KB_KEY),
    // 记忆里的当前工作区 id（对照旧仓 wsStore("utopia.ws")）
    workspaceId: readLocal(WS_KEY),
    // 当前库详情（my_role 只在详情接口里——useKbRole 的判定源）
    kbDetail: null as Kb | null,
    loading: false
  }),
  actions: {
    /** 载入库详情并记为当前库（URL 优先语义的入口：KbScope 对着路由参数调它）。
     *  **直接问后端，而不是在工作区列表里找**：链接可能指向另一个工作区里的库，
     *  列表里没有它但用户其实有权限——照列表判断会错杀（旧仓 KbScope 同款注释）。 */
    async load(kbId: string): Promise<Kb | null> {
      if (!kbId) return null;
      this.loading = true;
      try {
        const kb = await kbsApi.kbDetail(kbId);
        this.kbDetail = kb;
        this.kbId = kbId;
        // 打开哪个库，"上次看的"就跟到哪个库；工作区也一并对齐，
        // 否则顶栏的切换器显示的还是上一个工作区（旧仓 KbScope effect 同语义）
        this.remember(kbId);
        this.workspaceId = kb.workspace_id;
        return kb;
      } finally {
        this.loading = false;
      }
    },
    /** 只记一笔（切库时详情还没回来）：localStorage 记忆对齐旧仓 kbStore.set */
    remember(kbId: string) {
      this.kbId = kbId;
      try {
        localStorage.setItem(KB_KEY, kbId);
      } catch {
        /* 存不下就只对本次会话生效 */
      }
    },
    /** 换库：清掉旧详情（换库是一次导航，页面里的东西不带走） */
    reset() {
      this.kbDetail = null;
    }
  }
});

export function useKbContextHook() {
  return useKbContext(store);
}
