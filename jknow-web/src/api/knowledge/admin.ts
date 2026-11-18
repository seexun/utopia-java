// 管理域：部署设置/用户管理/数据源登记与授权/OIDC 管理面（对照旧仓 api.ts 同域函数）。
import { http } from "./client";
import type { User, OrgUser, OidcIdentity, DataSourceView } from "./types";

export const adminDeployment = () =>
  http.request<{
    open_registration: boolean;
    /** 外层兜底，防任务无限堆积；真正的节流是按模型的限额 */
    worker_concurrency: number;
    default_model_concurrency: number;
    /** 新建知识库的本体语言默认值。**不是界面语言**——那个在客户端 */
    default_ontology_lang: "en" | "zh";
    model_limits: {
      base_url: string;
      model: string;
      max_concurrent: number;
    }[];
    models_in_use: { base_url: string; model: string; kind: string }[];
  }>("/v1/admin/deployment");

export const saveAdminDeployment = (
  openRegistration: boolean,
  workerConcurrency?: number,
  defaultModelConcurrency?: number,
  modelLimit?: {
    base_url: string;
    model: string;
    max_concurrent: number | null;
  },
  defaultOntologyLang?: "en" | "zh"
) =>
  http.request<{ ok: boolean }>("/v1/admin/deployment", {
    method: "put",
    data: {
      open_registration: openRegistration,
      ...(workerConcurrency !== undefined
        ? { worker_concurrency: workerConcurrency }
        : {}),
      ...(defaultModelConcurrency !== undefined
        ? { default_model_concurrency: defaultModelConcurrency }
        : {}),
      ...(modelLimit ? { model_limit: modelLimit } : {}),
      ...(defaultOntologyLang
        ? { default_ontology_lang: defaultOntologyLang }
        : {})
    }
  });

export const adminCreateUser = (body: {
  email: string;
  display_name: string;
  password: string;
  role: string;
}) =>
  http.request<{ user: User }>("/v1/admin/users", {
    method: "post",
    data: body
  });

/** 已停用的账号。**没有它恢复就够不着**——那个人从所有列表里消失，
 *  而恢复接口要的正是他的 id */
export const deactivatedUsers = () =>
  http.request<OrgUser[]>("/v1/users/deactivated");

/** 恢复一个停用的账号 */
export const adminReactivateUser = (userId: string) =>
  http.request<{ ok: boolean }>(`/v1/admin/users/${userId}`, {
    method: "post"
  });

/** 停用一个账号（软删除）。归因照旧查得到——审计、合并日志、改类账本都靠它 */
export const adminDeactivateUser = (userId: string) =>
  http.request<{ ok: boolean }>(`/v1/admin/users/${userId}`, {
    method: "delete"
  });

export const adminDataSources = () =>
  http.request<{ data_sources: DataSourceView[] }>("/v1/admin/data-sources");

export const adminCreateDataSource = (body: {
  name: string;
  conn_string: string;
}) =>
  http.request<{ id: string }>("/v1/admin/data-sources", {
    method: "post",
    data: body
  });

export const adminDeleteDataSource = (id: string) =>
  http.request<{ ok: boolean }>(`/v1/admin/data-sources/${id}`, {
    method: "delete"
  });

/** 存之前先试一次：**不落库**。回来的是 ok，连不上时还有一句原因——
 *  密码错、库名拼错、端口不通是三件不同的事 */
export const adminTestConnString = (conn_string: string) =>
  http.request<{ ok: boolean; engine?: string; error?: string }>(
    "/v1/admin/data-sources/test",
    { method: "post", data: { conn_string } }
  );

export const adminTestDataSource = (id: string) =>
  http.request<{ ok: boolean }>(`/v1/admin/data-sources/${id}/test`, {
    method: "post"
  });

/** 这个源授权给了哪些工作区（0014）。授权与挂载是两层：
 *  授权由系统管理员写，挂载由 KB 管理员在授权过的集合里挑 */
export const dataSourceGrants = (id: string) =>
  http.request<{ workspaces: { id: string; name: string }[] }>(
    `/v1/admin/data-sources/${id}/grants`
  );

export const grantDataSource = (id: string, workspaceId: string) =>
  http.request<{ ok: boolean }>(
    `/v1/admin/data-sources/${id}/grants/${workspaceId}`,
    { method: "put" }
  );

/** 收回授权。**连同该工作区里已挂上的一起卸掉**，返回卸了几个 */
export const revokeDataSource = (id: string, workspaceId: string) =>
  http.request<{ ok: boolean; unmounted: number }>(
    `/v1/admin/data-sources/${id}/grants/${workspaceId}`,
    { method: "delete" }
  );

/** 谁的哪个身份提供方 subject 绑定到了这个部署的哪个账号（只读 + 解绑） */
export const oidcIdentities = () =>
  http.request<{
    issuer: string;
    client_id: string;
    redirect_uri: string;
    identities: OidcIdentity[];
  }>("/v1/admin/oidc/identities");

/** 管理员解绑。没有替人绑定的接口——那是一条冒充别人登录的路 */
export const oidcUnlink = (userId: string) =>
  http.request<{ ok: boolean }>(`/v1/admin/oidc/identities/${userId}`, {
    method: "delete"
  });

/** 存活与版本（壳顶栏的 GitHub·版本胶囊）：`{status, name, version}` */
export const health = () =>
  http.request<{ status: string; name: string; version: string }>("/v1/health");
