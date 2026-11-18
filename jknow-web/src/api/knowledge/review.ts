// 审核域：各档队列/裁决/对齐/勘误/一致性检查/冲突（对照旧仓 api.ts 同域函数，仅换框架层）。
import { http } from "./client";
import type {
  ReviewQueue,
  ReviewTypeFilter,
  ReviewCounts,
  ReviewSummary,
  PendingFactItem,
  ReviewHistoryEvent,
  AlignmentMarks,
  ViolationResolution
} from "./types";

/** 审核队列的各档**真实条数**。与列表分开取——列表有一页的上限，数数没有。
 *  从前徽标读的是数组长度，而接口固定只回 100 条，于是 164 条写成 100。 */
export const review = (
  kbId: string,
  queue: ReviewQueue,
  limit: number,
  offset: number,
  /** 只对 duplicates 有意义：按两边类型的关系筛（#428） */
  types: ReviewTypeFilter = "any"
) =>
  http.request<{
    counts: ReviewCounts;
    queue: ReviewQueue;
    /** 只有当前这一档的一页。类型按档不同，调用处按 queue 收窄 */
    items: unknown[];
  }>(`/v1/kbs/${kbId}/review`, { params: { queue, limit, offset, types } });

/** 一批重复项同一个动作（#428）：每条各自裁、各自记台账，回来逐条说成没成。
 *  `rationale`：人拍板时写的那一句（0026）——什么让你这么定。可不写；写了就跟着
 *  决定一起进台账，下一次裁决器和 agent 读到的先例就不只是结果 */
export const reviewBatch = (
  kbId: string,
  ids: string[],
  action: "merge" | "keep",
  rationale?: string
) =>
  http.request<{
    decided: number;
    outcomes: { id: string; error: string | null }[];
  }>(`/v1/kbs/${kbId}/review/batch`, {
    method: "post",
    data: { ids, action, rationale: rationale || null }
  });

/** 闭合日期带精度（year | month | day）：写多少位就是多少精度，服务端照存 */
export const closeFact = (
  kbId: string,
  factId: string,
  validTo: string,
  precision: string
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/facts/${factId}/close`, {
    method: "post",
    data: { valid_to: validTo, valid_to_precision: precision }
  });

export const resolveConflict = (
  kbId: string,
  conflictId: string,
  body: {
    action: "close" | "keep" | "reject_new";
    close_at?: string;
    close_at_precision?: string;
  }
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/conflicts/${conflictId}`, {
    method: "post",
    data: body
  });

/** 一句记忆抽出的全部待确认项（0015）。空数组 = 这句话没有等着人的东西了 */
export const pendingForChunk = (kbId: string, chunkId: string) =>
  http.request<{ items: PendingFactItem[] }>(`/v1/kbs/${kbId}/review/pending`, {
    params: { chunk_id: chunkId }
  });

export const decidePending = (
  kbId: string,
  pendingId: string,
  action: "confirm" | "reject"
) =>
  http.request<{
    ok: boolean;
    fact_id?: string;
    created?: boolean;
    conflicts?: number;
  }>(`/v1/kbs/${kbId}/review/pending/${pendingId}`, {
    method: "post",
    data: { action }
  });

export const decideReview = (
  kbId: string,
  reviewId: string,
  action: "merge" | "keep",
  rationale?: string
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/review/${reviewId}`, {
    method: "post",
    data: { action, rationale: rationale || null }
  });

/** 对一条数据映射口径表态（0011）。改状态不删行——拒绝留痕，下一轮探索不再提议它 */
export const decideMapping = (
  kbId: string,
  mappingId: string,
  status: "confirmed" | "rejected"
) =>
  http.request<{ ok: boolean }>(
    `/v1/kbs/${kbId}/review/mappings/${mappingId}`,
    { method: "post", data: { status } }
  );

/** 跑一遍一致性检查。同步返回——纯计算，没有模型调用也没有网络 */
export const runConsistencyCheck = (kbId: string) =>
  http.request<{
    edges: number;
    /** **零和零不一样**：没有公理时结论是「没有判据」，不是「未发现矛盾」 */
    predicates_with_axioms: number;
    found: number;
    inserted: number;
    cleared: number;
    /** 环没搜完的谓词数（#642）。不为零时那些谓词上的环只报了一部分 */
    cycles_capped: number;
    classes: number;
    /** 本体自己的矛盾**单独回**，不加进 found：两个数不是一类东西 */
    defects_found: number;
    defects_new: number;
  }>(`/v1/kbs/${kbId}/consistency/check`, { method: "post" });

/** 人定一条短语签名：属性与方向，或没有（陈述留在开放图谱）。判定和它的重算任务
 *  一次提交，答 202 和 job id（0051）；类型化图谱在后台重算 */
export const decideAlignmentPhrase = (
  kbId: string,
  bindingId: string,
  property: string | null,
  direction: "forward" | "reverse",
  /** 只在状态属性下给；null = 不说（只带一个日期的陈述留在开放图谱） */
  marks: AlignmentMarks | null
) =>
  http.request<{ ok: boolean; job_id: number; status: "accepted" }>(
    `/v1/kbs/${kbId}/review/alignment/phrases/${bindingId}`,
    { method: "post", data: { property, direction, marks } }
  );

/** 人批或驳一条蕴含规则：答 202 和 job id，隐含事实在后台算（0044 决定 3 第五片） */
export const decideAlignmentRule = (
  kbId: string,
  ruleId: string,
  approve: boolean
) =>
  http.request<{ ok: boolean; job_id: number; status: "accepted" }>(
    `/v1/kbs/${kbId}/review/alignment/rules/${ruleId}`,
    { method: "post", data: { approve } }
  );

/** 人答勘误 agent 留下的一笔（0044 决定 7）：批了就执行，否了只记 */
export const decideErrata = (
  kbId: string,
  actionId: string,
  approve: boolean
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/review/errata/${actionId}`, {
    method: "post",
    data: { approve }
  });

/** 人定一个类别词：类，或没有。它名下的实体换类，短语签名跟着重判 */
export const decideAlignmentKindWord = (
  kbId: string,
  kindWord: string,
  cls: string | null
) =>
  http.request<{ ok: boolean }>(
    `/v1/kbs/${kbId}/review/alignment/kind-words/${encodeURIComponent(kindWord)}`,
    { method: "post", data: { class: cls } }
  );

/** 对一处本体缺陷表态。**两个出路**——它压根没看数据，没有「数据错了」这条 */
export const decideDefect = (
  kbId: string,
  defectId: string,
  resolution: "fixed" | "accepted"
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/review/defects/${defectId}`, {
    method: "post",
    data: { resolution }
  });

/** 对一处公理违规表态。三个出路——第三个是这一档独有的：可能是定义错了 */
export const decideViolation = (
  kbId: string,
  violationId: string,
  resolution: ViolationResolution,
  opts: { closeAt?: string; closeAtPrecision?: string; factId?: string } = {}
) =>
  http.request<{ ok: boolean }>(
    `/v1/kbs/${kbId}/review/violations/${violationId}`,
    {
      method: "post",
      data: {
        resolution,
        close_at: opts.closeAt ?? null,
        close_at_precision: opts.closeAtPrecision ?? null,
        fact_id: opts.factId ?? null
      }
    }
  );

export const reviewSummary = (kbId: string) =>
  http.request<ReviewSummary>(`/v1/kbs/${kbId}/review/summary`);

/** 回答 agent 的一笔（0025）：merge / keep 答一条建议，revert 撤回一条自动合并，
 *  merge 也能推翻一条自动分开。走的是人的裁决路径，成为新先例 */
export const agentAnswer = (
  kbId: string,
  decisionId: string,
  action: "merge" | "keep" | "revert",
  rationale?: string
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/review/agent/${decisionId}`, {
    method: "post",
    data: { action, rationale: rationale || null }
  });

export const reviewHistory = (kbId: string, page: number, per = 20) =>
  http.request<{ events: ReviewHistoryEvent[]; total: number }>(
    `/v1/kbs/${kbId}/review/history`,
    { params: { page, per } }
  );
