// 工作区域：列表/成员/模型设置（对照旧仓 api.ts 同域函数，仅换框架层）。
import { http } from "./client";
import type {
  Workspace,
  LlmSettingsView,
  Member,
  OrgUser,
  MyKb
} from "./types";

export const workspaces = () => http.request<Workspace[]>("/v1/workspaces");

/** 这个工作区，以及**我在里面是什么角色**。建库要 Admin+，而 `GET /workspaces`
 *  那份列表不带角色 */
export const workspaceRole = (workspaceId: string) =>
  http.request<{ workspace: Workspace; role: string }>(
    `/v1/workspaces/${workspaceId}`
  );

export const members = (workspaceId: string) =>
  http.request<Member[]>(`/v1/workspaces/${workspaceId}/members`);

export const orgUsers = () => http.request<OrgUser[]>("/v1/users");

export const setMemberRole = (
  workspaceId: string,
  userId: string,
  role: string
) =>
  http.request<{ ok: boolean }>(
    `/v1/workspaces/${workspaceId}/members/${userId}`,
    { method: "put", data: { role } }
  );

export const removeMember = (workspaceId: string, userId: string) =>
  http.request<{ ok: boolean }>(
    `/v1/workspaces/${workspaceId}/members/${userId}`,
    { method: "delete" }
  );

/** 账户层「我的知识库」行：库 + 我的角色 + 加入信息 + 概览统计 */
export const myKbs = (workspaceId: string) =>
  http.request<{ kbs: MyKb[] }>(`/v1/workspaces/${workspaceId}/my-kbs`);

/** 工作区模型设置（OpenAI 兼容协议：对话/向量/OCR/转写） */
export const settings = (workspaceId: string) =>
  http.request<LlmSettingsView>(`/v1/workspaces/${workspaceId}/settings`);

export const saveSettings = (
  workspaceId: string,
  body: Record<string, unknown>
) =>
  http.request<{ ok: boolean }>(`/v1/workspaces/${workspaceId}/settings`, {
    method: "put",
    data: body
  });

export const testSettings = (workspaceId: string) =>
  http.request<{
    chat: { ok: boolean; reply?: string; error?: string };
    embed: { ok: boolean; dim?: number; error?: string };
    ocr?: { ok: boolean; version?: string | null; error?: string };
    transcribe?: { ok: boolean; error?: string };
  }>(`/v1/workspaces/${workspaceId}/settings/test`, { method: "post" });

/** 读扫描件的服务：存它不碰对话与嵌入那几列。
 *  `requeued`：因为缺它而等着的文件，这一存重新排进了处理队列几份 */
export const saveOcrSettings = (
  workspaceId: string,
  body: {
    provider: string;
    base_url: string;
    api_key: string;
    backend: string;
    model: string;
  }
) =>
  http.request<{ ok: boolean; requeued: number }>(
    `/v1/workspaces/${workspaceId}/settings/ocr`,
    { method: "put", data: body }
  );

export const saveTranscribeSettings = (
  workspaceId: string,
  body: { base_url: string; api_key: string; model: string }
) =>
  http.request<{ ok: boolean; requeued: number }>(
    `/v1/workspaces/${workspaceId}/settings/transcribe`,
    { method: "put", data: body }
  );
