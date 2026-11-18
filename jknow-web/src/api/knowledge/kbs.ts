// 知识库域：列表/详情/成员/审计/就绪度/本体包（对照旧仓 api.ts 同域函数，仅换框架层）。
import { http } from "./client";
import type { Kb, KbMember, AuditEvent, OntologyPack } from "./types";

export const kbs = (workspaceId: string) =>
  http.request<Kb[]>(`/v1/workspaces/${workspaceId}/kbs`);

export const createKb = (
  workspaceId: string,
  body: {
    name: string;
    description?: string | null;
    visibility?: string;
    /** 预置本体包 id，顺序有意义：第一个会认领同名的种子类 */
    ontology_packs?: string[];
  }
) =>
  http.request<Kb>(`/v1/workspaces/${workspaceId}/kbs`, {
    method: "post",
    data: body
  });

/** 建库时可选的预置本体包（`GET /ontology-packs`） */
export const ontologyPacks = () =>
  http.request<{ packs: OntologyPack[] }>("/v1/ontology-packs");

/** 库详情——**调用者的角色 my_role 只有详情接口返回**：前端据此门控破坏性入口 */
export const kbDetail = (kbId: string) => http.request<Kb>(`/v1/kbs/${kbId}`);

export const updateKb = (kbId: string, body: Record<string, unknown>) =>
  http.request<Kb>(`/v1/kbs/${kbId}`, { method: "patch", data: body });

export const deleteKb = (kbId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}`, { method: "delete" });

export const kbMembers = (kbId: string) =>
  http.request<{ members: KbMember[] }>(`/v1/kbs/${kbId}/members`);

export const setKbMember = (kbId: string, userId: string, role: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/members/${userId}`, {
    method: "put",
    data: { role }
  });

export const removeKbMember = (kbId: string, userId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/members/${userId}`, {
    method: "delete"
  });

/** 审计台账。**分页 + 筛选**——台账是合规材料，只看最近 100 条等于查不了历史。
 *  `action` 是前缀：`entity.` 捞出 entity.retyped / entity.renamed 一族。 */
export const kbAudit = (
  kbId: string,
  opts: {
    action?: string;
    actor?: string;
    since?: string;
    until?: string;
    limit: number;
    offset: number;
  }
) =>
  http.request<{
    events: AuditEvent[];
    total: number;
    /** 这个库实际发生过的动作，筛选下拉按它填 */
    actions: string[];
  }>(`/v1/kbs/${kbId}/audit`, { params: { ...opts } });

/** 这个库走到哪一步了（#313）：四个页面的空状态共用。只回布尔与计数 */
export const readiness = (kbId: string) =>
  http.request<{
    has_chat_model: boolean;
    documents: number;
    processing: number;
    failed: number;
    entities: number;
  }>(`/v1/kbs/${kbId}/readiness`);
