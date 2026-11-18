// 来源域：同步连接器的增删改查/令牌/同步史/重抽/图谱重建（对照旧仓 api.ts 同域函数）。
import { http } from "./client";
import type { SourceView, SyncRun } from "./types";

export const sources = (kbId: string) =>
  http.request<{ sources: SourceView[] }>(`/v1/kbs/${kbId}/sources`);

export const createSource = (kbId: string, body: Record<string, unknown>) =>
  http.request<{ source: SourceView; ingest_token?: string | null }>(
    `/v1/kbs/${kbId}/sources`,
    { method: "post", data: body }
  );

export const sourceToken = (kbId: string, sourceId: string) =>
  http.request<{ ingest_token: string | null }>(
    `/v1/kbs/${kbId}/sources/${sourceId}/token`
  );

export const rotateSourceToken = (kbId: string, sourceId: string) =>
  http.request<{ ingest_token: string }>(
    `/v1/kbs/${kbId}/sources/${sourceId}/rotate-token`,
    { method: "post" }
  );

export const updateSource = (
  kbId: string,
  sourceId: string,
  body: Record<string, unknown>
) =>
  http.request<{ source: SourceView }>(`/v1/kbs/${kbId}/sources/${sourceId}`, {
    method: "patch",
    data: body
  });

export const deleteSource = (kbId: string, sourceId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/sources/${sourceId}`, {
    method: "delete"
  });

export const cleanupMissing = (kbId: string, sourceId: string) =>
  http.request<{ deleted: number }>(
    `/v1/kbs/${kbId}/sources/${sourceId}/missing/cleanup`,
    { method: "post" }
  );

export const syncSource = (kbId: string, sourceId: string) =>
  http.request<{ queued: boolean }>(
    `/v1/kbs/${kbId}/sources/${sourceId}/sync`,
    { method: "post" }
  );

export const sourceRuns = (kbId: string, sourceId: string) =>
  http.request<{ runs: SyncRun[] }>(`/v1/kbs/${kbId}/sources/${sourceId}/runs`);

/** 来源级全量重抽（增量语义：既有决策保留） */
export const reExtractSource = (kbId: string, sourceId: string) =>
  http.request<{ queued: number }>(
    `/v1/kbs/${kbId}/sources/${sourceId}/re-extract`,
    { method: "post" }
  );

/** 图谱重建（清算语义：清空图层后全量重抽；KB admin） */
export const rebuildGraph = (kbId: string) =>
  http.request<{
    entities_removed: number;
    facts_removed: number;
    queued: number;
  }>(`/v1/kbs/${kbId}/graph/rebuild`, { method: "post" });
