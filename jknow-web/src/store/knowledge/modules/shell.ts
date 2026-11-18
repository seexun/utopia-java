// 壳级状态（W1）：ServerDown 态。
// 旧仓 Shell 的触发语义：me 查询失败且不是 401（会话有效但后端不可达——网络断、
// 5xx）时，整个壳被 ServerDown 页替换；401 才回登录页。新世界的对应物：
// 守卫的 ensureMe 探测拿到非 401/403 的失败时置位本标志并放行导航，
// layout 据此渲染 ServerDown 而不是三栏壳；下一次成功的 /auth/me 清位。
import { defineStore } from "pinia";
import store from "@/store";

export const useShellStore = defineStore("utopia-shell", {
  state: () => ({
    /** 后端不可达（非会话问题）：壳被 ServerDown 页替换 */
    serverDown: false
  }),
  actions: {
    markServerDown() {
      this.serverDown = true;
    },
    /** 服务端真值校验成功后清位（后端回来了） */
    markAlive() {
      this.serverDown = false;
    }
  }
});

export function useShellStoreHook() {
  return useShellStore(store);
}
