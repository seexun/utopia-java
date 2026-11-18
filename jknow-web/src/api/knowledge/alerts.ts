// 告警域：告警中心分组/未读数/已读/失败任务回队列（对照旧仓 api.ts 同域函数）。
import { http } from "./client";
import type { AlertGroup } from "./types";

export const alerts = (o: { q?: string; limit?: number; offset?: number }) =>
  http.request<{ items: AlertGroup[]; total: number }>("/v1/alerts", {
    params: {
      ...(o.q?.trim() ? { q: o.q.trim() } : {}),
      ...(o.limit != null ? { limit: o.limit } : {}),
      ...(o.offset ? { offset: o.offset } : {})
    }
  });

export const alertsUnread = () =>
  http.request<{ unread: number }>("/v1/alerts/unread");

/** 失败任务回队列（#216）。库内一条、全局一条（管理员）；范围可按种类与失败时间收窄 */
export const failedJobs = (kbId: string) =>
  http.request<{ failed: number }>(`/v1/kbs/${kbId}/jobs/failed`);

export const requeueJobs = (
  kbId: string | null,
  body: { kind?: string; failed_since?: string } = {}
) =>
  http.request<{ requeued: number }>(
    kbId ? `/v1/kbs/${kbId}/jobs/requeue` : "/v1/jobs/requeue",
    { method: "post", data: body }
  );

/** 把一组（同库同类同一窗口）一起标已读：earliest_at 圈定窗口，原样发回 */
export const alertReadGroup = (g: {
  kb_id: string | null;
  kind: string;
  from: string;
  to: string;
}) =>
  http.request<{ marked: number }>("/v1/alerts/read-group", {
    method: "post",
    data: g
  });

export const alertsReadAll = () =>
  http.request<{ ok: boolean }>("/v1/alerts/read-all", { method: "post" });
