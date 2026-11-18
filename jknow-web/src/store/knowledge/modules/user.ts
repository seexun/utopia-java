// 用户会话 store：知识域的会话生命周期（源仓 store/modules/user.ts 的 jknow 落位）。
//
// jknow 适配口径（T3，对齐 T2 client.ts 的 Bearer 改造与「auth 断点以若依登录
// 体系闭合」裁定）：
//   - 真会话是若依 JWT（Admin-Token cookie）；本 store 的本地快照
//     （storageLocal "user-info"）只承担知识域的展示缓存与 spec 语义
//     （hydrate/ensureMe 的回填源），会话有效性由若依 permission.ts 守卫 +
//     client.ts 的 401 处置线兜底——快照不是登录证据；
//   - 源仓 utils/auth.ts 的会话快照三件（getSession/setToken/removeToken）
//     收拢进本模块（若依 @/utils/auth 同名不同形，不能混用）；
//   - authApi 换 @/api/knowledge/auth 适配层（me/login/register/logout 映射
//     若依 /getInfo、/login、/register、/logout）；
//   - logOut 不再触碰 pure-admin 的 multiTags/resetRouter（若依 tagsView 与
//     动态路由各自管理）——清快照、清 queryClient、回登录页三件事保留。
import { defineStore } from "pinia";
import { storageLocal } from "@pureadmin/utils";
import store from "@/store";
import * as authApi from "@/api/knowledge/auth";
import { ApiError, resetUnauthorizedGate } from "@/api/knowledge/client";
import { useShellStoreHook } from "./shell";
import { queryClient } from "@/store/knowledge/query";
import router from "@/router";
import type { User } from "@/api/knowledge/types";

/** 知识域会话快照（源仓 utils/auth.ts SessionInfo 原样收拢） */
export interface SessionInfo {
  /** 用户 id（ulid） */
  userId: string;
  /** 登录邮箱 */
  username: string;
  /** 显示名称 */
  nickname: string;
  /** 部署管理员（/auth/me 的 is_admin） */
  isAdmin: boolean;
  /** 路由/菜单用的角色：admin → ["admin"]，普通 → ["user"] */
  roles: Array<string>;
  /** 头像（当前由名字生成，无图片字段） */
  avatar?: string;
  /** 按钮级别权限：管理员放开全部（基座 hasPerms 机制兼容用） */
  permissions?: Array<string>;
}

export const userKey = "user-info";

/** 本地会话记录（可能不存在——未登录或刚被 401 清掉） */
export function getSession(): SessionInfo | null {
  return storageLocal().getItem<SessionInfo>(userKey);
}

/** 登录/注册成功或 me() 刷新后，落一份用户信息快照并回填 store */
export function setToken(user: User) {
  const roles = user.is_admin ? ["admin"] : ["user"];
  const record: SessionInfo = {
    userId: user.id,
    username: user.email,
    nickname: user.display_name,
    isAdmin: user.is_admin,
    roles,
    avatar: "",
    permissions: user.is_admin ? ["*:*:*"] : []
  };
  storageLocal().setItem(userKey, record);
  useUserStoreHook().hydrate(record);
}

/** 删除本地用户信息缓存（服务端登出走若依 /logout 清 JWT） */
export function removeToken() {
  storageLocal().removeItem(userKey);
}

export type userType = SessionInfo & {
  /** 判断登录页面显示哪个组件（0：登录（默认）、1：注册） */
  currentPage: number;
  /** 是否勾选了登录页的免登录 */
  isRemembered: boolean;
  /** 登录页的免登录存储几天，默认7天 */
  loginDay: number;
};

/** me() 的生命周期标志（源仓 I-1）：本应用生命周期内服务端真值是否已校验过。
 *  模块级而非 store state——它描述的是"应用进程"而不是"用户会话"。 */
let meVerified = false;

export const useUserStore = defineStore("pure-user", {
  state: (): userType => ({
    avatar: getSession()?.avatar ?? "",
    username: getSession()?.username ?? "",
    nickname: getSession()?.nickname ?? "",
    roles: getSession()?.roles ?? [],
    permissions: getSession()?.permissions ?? [],
    isAdmin: getSession()?.isAdmin ?? false,
    userId: getSession()?.userId ?? "",
    currentPage: 0,
    isRemembered: false,
    loginDay: 7
  }),
  actions: {
    /** 把一份会话快照灌进 store。**只回填展示面**——服务端真值仍由
     *  ensureMe 的 me() 校验（I-1），本地快照不是会话有效的证据。 */
    hydrate(session: SessionInfo) {
      this.avatar = session.avatar ?? "";
      this.username = session.username;
      this.nickname = session.nickname;
      this.roles = session.roles;
      this.permissions = session.permissions ?? [];
      this.isAdmin = session.isAdmin;
      this.userId = session.userId;
    },
    /** 登录（经若依 /login 适配层）。jknow 下真实登录走若依登录页 + 若依 user
     *  store；本动作保留给知识域自身语义与 spec（会话边界清缓存 I-2）。 */
    async loginByUsername(data: { email: string; password: string }) {
      const { user } = await authApi.login(data.email, data.password);
      setToken(user);
      // 会话边界（I-1/I-2）：下一轮导航对全新会话再校验一次；
      // 401 闸门复位——SPA 不整页刷新，第二次过期仍要能跳；
      // **清空整个服务端数据缓存**——换人不靠过期，靠登录与登出清空
      // （domain/queryDefaults.ts 头注的旧仓决定）
      meVerified = false;
      resetUnauthorizedGate();
      queryClient.clear();
      return user;
    },
    /** 注册（开注册的部署）：成功即登录（jknow 下经若依 /register 适配层） */
    async registerAccount(data: {
      email: string;
      password: string;
      displayName: string;
    }) {
      const { user } = await authApi.register(
        data.email,
        data.password,
        data.displayName
      );
      setToken(user);
      meVerified = false;
      resetUnauthorizedGate();
      queryClient.clear();
      return user;
    },
    /** 向服务端校验会话并刷新本地快照（I-1）。
     *  无会话/已过期返回 false；is_admin/roles 等以服务端真值为准回填——
     *  本地快照只当展示缓存。
     *  **后端不可达 ≠ 没登录**（W1，对照旧仓 Shell 的 ServerDown 语义）：
     *  网络断/5xx/其他非 401 失败时置 shell store 的 serverDown 并返回 true
     *  放行导航——壳据它渲染 ServerDown 页，而不是把人错撵回登录页。 */
    async ensureMe(force = false): Promise<boolean> {
      if (!force && meVerified) return true;
      try {
        const user = await authApi.me();
        setToken(user);
        meVerified = true;
        useShellStoreHook().markAlive();
        return true;
      } catch (e) {
        const status = e instanceof ApiError ? e.status : 0;
        if (status === 401) return false;
        useShellStoreHook().markServerDown();
        return true;
      }
    },
    /** 登出：服务端清会话（尽力而为），本地清快照回登录页 */
    async logOut() {
      try {
        await authApi.logout();
      } catch {
        // 会话已失效也照样走本地登出
      }
      this.username = "";
      this.nickname = "";
      this.roles = [];
      this.permissions = [];
      this.isAdmin = false;
      this.userId = "";
      meVerified = false;
      resetUnauthorizedGate();
      removeToken();
      // 清空整个服务端数据缓存（旧仓 UserMenu.logout 的 queryClient.clear()）：
      // 下一会话不能看见上一人的库列表/告警未读（审查 I-2）
      queryClient.clear();
      await router.push("/login");
    }
  }
});

export function useUserStoreHook() {
  return useUserStore(store);
}
