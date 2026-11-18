// 知识域菜单 store（源仓 store/modules/permission.ts 的 jknow 落位）。
//
// jknow 适配口径（T3）：源仓的 wholeMenus 是 pure-admin 侧栏的菜单管线
// （constantMenus + 后端路由 → ascending/filterTree/filterNoPermissionTree），
// 侧栏本体未迁——若依侧栏由若依 permission store 的后端菜单树负责（V0102/T4
// 开菜单）。本 store 只保留知识域自身的菜单载体：useKbContext.rebuildMenus
// 把 utopiaMenus(kbId, canWrite) 喂进 wholeMenus（kb-switcher/测试消费），
// handleWholeMenus 直赋——pure-admin 管线对这组输入（无 rank/无 roles meta）
// 的恒等变形，语义等价。
import { defineStore } from "pinia";
import store from "@/store";

export const usePermissionStore = defineStore("pure-permission", {
  state: () => ({
    // kb 作用域菜单（utopiaMenus 运行时组装；切库/详情载入后重组）
    wholeMenus: [] as any[]
  }),
  actions: {
    /** 组装整体路由生成的菜单（jknow：直赋，见文件头注） */
    handleWholeMenus(routes: any[]) {
      this.wholeMenus = routes;
    },
    /** 清空 */
    clearAllCachePage() {
      this.wholeMenus = [];
    }
  }
});

export function usePermissionStoreHook() {
  return usePermissionStore(store);
}
