// 数据映射域：口径/口径修订/库级数据源挂载（问数）（对照旧仓 api.ts 同域函数）。
import { http } from "./client";
import type { ConceptMapping, MappingRevision, DataSourceView } from "./types";

/** 一页口径。**Viewer 就能看**——问数的答案直接由口径决定，
 *  看得见答案却看不见口径，等于要人信一个不给看的算法 */
export const mappings = (
  kbId: string,
  opts: {
    status?: "proposed" | "confirmed" | "rejected";
    q?: string;
    limit?: number;
    offset?: number;
  } = {}
) =>
  http.request<{
    items: ConceptMapping[];
    total: number;
    counts: { proposed: number; confirmed: number; rejected: number };
    // 最近一轮探索的账（#503）。单看列表答不了「漏了多少」——
    // 十二条提议对着八十列的宽表与刚好覆盖完一个小库长得一样。
    last_run?: {
      id: string;
      started_at: string;
      finished_at: string | null;
      sources: string[];
      tables_scanned: number;
      columns_scanned: number;
      schema_truncated: boolean;
      cap: number;
      returned: number;
      accepted: number;
      dropped: Record<string, { n: number; example: string }>;
      tables_covered: string[];
      error: string | null;
    };
  }>(`/v1/kbs/${kbId}/mappings`, { params: { ...opts } });

/** 改一条口径。改之前那一版自动进 revisions */
export const reviseMapping = (
  kbId: string,
  mappingId: string,
  body: {
    table_name?: string | null;
    expr?: string | null;
    sql?: string | null;
    unit?: string | null;
    summary?: string | null;
    derived: boolean;
  }
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/mappings/${mappingId}`, {
    method: "patch",
    data: body
  });

/** 一条口径改动之前的样子。**整版快照而不是差异**（0006） */
export const mappingRevisions = (kbId: string, mappingId: string) =>
  http.request<{ revisions: MappingRevision[] }>(
    `/v1/kbs/${kbId}/mappings/${mappingId}/revisions`
  );

export const kbDataSources = (kbId: string) =>
  http.request<{ data_sources: DataSourceView[] }>(
    `/v1/kbs/${kbId}/data-sources`
  );

export const kbDataSourcesAvailable = (kbId: string) =>
  http.request<{ data_sources: DataSourceView[] }>(
    `/v1/kbs/${kbId}/data-sources/available`
  );

/** 挂载。**`schema_error` 非空时挂载仍然成了**——源是真挂上的，只是它的
 *  库表结构没摄进来，问数看不见有哪些表。同一件事会进告警中心 */
export const mountDataSource = (kbId: string, dsId: string) =>
  http.request<{
    ok: boolean;
    schema_tables: number;
    schema_error?: string | null;
  }>(`/v1/kbs/${kbId}/data-sources/${dsId}`, { method: "put" });

export const unmountDataSource = (kbId: string, dsId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/data-sources/${dsId}`, {
    method: "delete"
  });

export const exploreMappings = (kbId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/data-sources/explore`, {
    method: "post"
  });

export const syncDataSourceSchema = (kbId: string, dsId: string) =>
  http.request<{ ok: boolean; schema_tables: number }>(
    `/v1/kbs/${kbId}/data-sources/${dsId}/sync-schema`,
    { method: "post" }
  );
