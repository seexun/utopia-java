// 个人域：个人访问令牌（0014）——给 MCP 客户端的长命钥匙（对照旧仓 api.ts 同域函数）。
import { http } from "./client";
import type { TokenView } from "./types";

/** 我发过的个人令牌。撤销过的也在——撤过这件事本身要看得见 */
export const tokens = () =>
  http.request<{ tokens: TokenView[] }>("/v1/me/tokens");

/** 发一枚。**明文只在这一次的响应里**，列表永远给不出它 */
export const issueToken = (body: {
  name: string;
  scope: "read" | "write";
  /** 缺省 = 这个人能进的全部库 */
  kb_ids?: string[] | null;
  /** 0 = 不过期 */
  expires_in_days: number;
}) =>
  http.request<{ token: string; info: TokenView }>("/v1/me/tokens", {
    method: "post",
    data: body
  });

export const revokeToken = (tokenId: string) =>
  http.request<{ ok: boolean }>(`/v1/me/tokens/${tokenId}`, {
    method: "delete"
  });
