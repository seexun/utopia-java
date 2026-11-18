// 认证域适配层（T3「auth 断点以若依登录体系闭合」的落字）：
// 源仓 api/auth.ts 的 9 个 /v1/auth/* 端点在 jknow 后端已随 auth 域裁撤
// （登录/登出/当前用户/改密/OIDC 走若依体系——快照闸 RETIRED_AUTH 9 端点在案）。
// 本模块保持源仓的**导出面**（函数名/返回形状）不变，实现映射到若依端点：
//   me           → GET  /getInfo（若依 api/login.getInfo；roles 含 admin ⇔ 后端
//                   is_admin 口径：user_id=1 或超管角色，UtopiaUuidResolver 同源）
//   login        → POST /login（若依 api/login.login；验证码关闭的部署直通）
//   register     → POST /register（若依 api/login.register）
//   logout       → POST /logout（若依 api/login.logout）
//   updateMe     → PUT  /system/user/profile（nickName ← display_name）
//   changePassword → PUT /system/user/profile/updatePwd
//   oidcStatus / oidcMe / oidcUnlinkMe → 无若依对等面，恒返回「未启用」态
// （成员自比较用的 User.id 取若依 userId 字符串——utopia uuid 不在 getInfo
//  响应面，仅 admin/members-tab 的自列守卫受影响，见任务 3 报告对账）。
// 注意：本文件**不走** http.request("/v1/...")——快照漂移闸的 actual 面因此
// 不变（RETIRED_AUTH 登记继续成立）。
import { getInfo, login as ruoyiLogin, register as ruoyiRegister, logout as ruoyiLogout } from "@/api/login";
import { updateUserProfile, updateUserPwd } from "@/api/system/user";
import type { SysUser } from "@/types";
import type { User } from "./types";

/** 若依 getInfo → 知识域 User 形状 */
function mapUser(res: {
  user: SysUser;
  roles?: string[];
}): User {
  const u = res.user ?? {};
  return {
    id: String(u.userId ?? ""),
    org_id: "",
    email: u.userName ?? "",
    display_name: u.nickName || u.userName || "",
    is_admin: !!res.roles?.includes("admin"),
    created_at: ""
  };
}

/** 登录（源仓签名保留）：成功返回 { user }——JWT 由若依体系自持 */
export const login = async (email: string, password: string) => {
  await ruoyiLogin(email, password, "", "");
  return { user: { ...emptyUser(), email } };
};

/** 注册（开注册的部署）：成功即登录 */
export const register = async (
  email: string,
  password: string,
  displayName: string
) => {
  await ruoyiRegister({ userName: email, password });
  return { user: { ...emptyUser(), email, display_name: displayName } };
};

function emptyUser(): User {
  return {
    id: "",
    org_id: "",
    email: "",
    display_name: "",
    is_admin: false,
    created_at: ""
  };
}

/** 当前登录用户（源仓 skipGlobal401 语义：探测自带处置——若依 request 的
 *  401 拦截接管会话过期提示，这里不再叠加全局跳转） */
export const me = async (): Promise<User> => {
  const res = await getInfo();
  return mapUser(res);
};

export const updateMe = async (displayName: string): Promise<User> => {
  await updateUserProfile({ nickName: displayName } as SysUser);
  return me();
};

export const changePassword = async (
  currentPassword: string,
  newPassword: string
) => {
  await updateUserPwd(currentPassword, newPassword);
  return { ok: true };
};

/** 是否配置了单点登录：jknow 后端无 OIDC 面，恒 false */
export const oidcStatus = async () => ({ enabled: false });

export const oidcMe = async () => ({
  enabled: false,
  linked: false,
  subject: null as string | null
});

export const oidcUnlinkMe = async () => ({ ok: true });

/** 会话登出（若依 /logout；本地清理在 user store 的 logOut） */
export const logout = async () => {
  await ruoyiLogout();
  return { ok: true };
};
