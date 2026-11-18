// 文档域：文库分页/上传/删除/恢复/清除/抽取/检索（对照旧仓 api.ts 同域函数，仅换框架层）。
import { http } from "./client";
import type {
  Doc,
  ExtractionDrop,
  SearchResult,
  ChunkFull,
  ChunkFact
} from "./types";

/** 文库一页。**服务端筛选与分页**——从前一次取回整库、前端切片，
 *  而客户端筛选只筛得到已经拿下来的那些 */
export const documents = (
  kbId: string,
  opts: {
    source?: string;
    q?: string;
    graph?: string;
    /** "deleted" = 「已删除」视图：只列墓碑（#268） */
    state?: "deleted";
    limit: number;
    offset: number;
  }
) =>
  http.request<{
    docs: Doc[];
    total: number;
    /** 下面四个**只按来源作用域算**，不受名字/状态筛选影响——
     *  它们是批量按钮的作用范围 */
    ready: number;
    /** `graph_status = 'done'`：抽取进度条的分子 */
    done: number;
    extracting: number;
    failed: number;
    /** 整库的墓碑数（删了、没清的），不随作用域变 */
    deleted: number;
  }>(`/v1/kbs/${kbId}/documents`, { params: { ...opts } });

/** 一键重试这个作用域里全部抽取失败的文档 */
export const retryFailedDocs = (kbId: string, source?: string) =>
  http.request<{ queued: number; found: number }>(
    `/v1/kbs/${kbId}/documents/retry-failed`,
    { method: "post", params: source ? { source } : {} }
  );

/** 整库一次取回：行数按 (文档 × 原因 × 对象) 聚合后很小，避免逐行发请求 */
export const extractionDrops = (kbId: string) =>
  http.request<{ drops: ExtractionDrop[] }>(`/v1/kbs/${kbId}/extraction-drops`);

export const upload = (kbId: string, files: File[], sourceId?: string) => {
  const form = new FormData();
  for (const f of files) form.append("files", f, f.name);
  return http.request<{ created: Doc[]; skipped: unknown[] }>(
    `/v1/kbs/${kbId}/documents`,
    {
      method: "post",
      data: form,
      params: sourceId ? { source: sourceId } : {},
      // 大文件上传不限时（全局 60s 兜底对慢链路不够，见 client.ts 取舍注释）
      timeout: 0
    }
  );
};

export const deleteDocument = (id: string) =>
  http.request<{
    ok: boolean;
    deletion_id: string;
    invalidated_facts: number;
  }>(`/v1/documents/${id}`, { method: "delete" });

/** 撤销删除（#268）：文档、分块、随之作废的事实原路复活 */
export const restoreDocument = (id: string) =>
  http.request<{ ok: boolean }>(`/v1/documents/${id}/restore`, {
    method: "post"
  });

/** 真删（#268 下半）：只对已删除的开放，库管理员，不可撤销 */
export const purgeDocument = (id: string) =>
  http.request<{ ok: boolean; chunks: number; blobs: number }>(
    `/v1/documents/${id}/purge`,
    { method: "post" }
  );

export const documentDetail = (id: string) =>
  http.request<{ document: Doc; chunks: ChunkFull[] }>(`/v1/documents/${id}`);

export const extractDocument = (id: string) =>
  http.request<{ job_id: number }>(`/v1/documents/${id}/extract`, {
    method: "post"
  });

export const reprocessDocument = (id: string) =>
  http.request<{ job_id: number }>(`/v1/documents/${id}/reprocess`, {
    method: "post"
  });

/** 分块的抽取产物（文档查看器右栏） */
export const documentExtractions = (docId: string) =>
  http.request<{ facts: ChunkFact[] }>(`/v1/documents/${docId}/extractions`);

/** 全文检索（关键词 + 语义混合） */
export const search = (kbId: string, q: string) =>
  http.request<{ results: SearchResult[] }>(`/v1/kbs/${kbId}/search`, {
    method: "post",
    data: { q }
  });
