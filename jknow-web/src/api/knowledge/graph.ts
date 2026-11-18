// 图谱域：总览/邻域/实体/事实/证明/合并（对照旧仓 api.ts 同域函数，仅换框架层）。
import { http } from "./client";
import type {
  GraphNode,
  GraphEdge,
  EntityFact,
  NameView,
  DerivedFact,
  BlockedDerivation,
  Evidence,
  Proof,
  ProofStep,
  EntityHistoryEvent
} from "./types";

export const graphOverview = (kbId: string, limit?: number) =>
  http.request<{
    nodes: GraphNode[];
    edges: GraphEdge[];
    /** 库里一共有多少。**与 nodes.length 不是一回事**——画布只画度数最高的
     *  那一批，把上限当成规模显示是这个接口从前最误导人的地方 */
    total_nodes?: number;
    total_edges?: number;
  }>(`/v1/kbs/${kbId}/graph/overview`, { params: limit ? { limit } : {} });

/** 邻域视图**没有总数**：它本来就只是一小片，说「共 325 个」没有意义。
 *  两个字段声明成可选，好让调用方与总览共用一个类型 */
export const graphNeighborhood = (kbId: string, entityId: string) =>
  http.request<{
    nodes: GraphNode[];
    edges: GraphEdge[];
    total_nodes?: number;
    total_edges?: number;
  }>(`/v1/kbs/${kbId}/graph/neighborhood`, {
    params: { entity: entityId, hops: 2 }
  });

/** 按名字找实体。**一并回总数**——「宁分勿合」会造出一堆同名，
 *  固定十条时想找的那个可能根本不在这十条里 */
export const searchEntities = (kbId: string, q: string, limit = 10) =>
  http.request<{ entities: GraphNode[]; total: number }>(
    `/v1/kbs/${kbId}/entities`,
    { params: { q, limit } }
  );

export const entityDetail = (kbId: string, entityId: string) =>
  http.request<{
    entity: GraphNode;
    facts: EntityFact[];
    /** 这个实体的名字（0041）：本名、简称、曾用名，各带出处与有效期。
     *  名字事实不在 facts 里——它不是一条「关于它的事」 */
    names: NameView[];
    /** 推出来的那些**单独一个键**，不掺进 facts：混在同一个列表里，
     *  用户看不出「文档里写的」和「引擎推的」的区别 */
    derived: DerivedFact[];
    /** 没落地的派生（0017 §3）：连 `derived_facts` 都不在，所以也单独一个键 */
    blocked: BlockedDerivation[];
    /** 同名的其他实体。**打开面板就给**——合并入口要长在能看见同名的地方，
     *  而不是藏在「改一次名」之后 */
    same_name: GraphNode[];
  }>(`/v1/kbs/${kbId}/entities/${entityId}`);

/** 人工修正实体的类型或名字。同名不拦——返回的 same_name 供界面提示是否合并。 */
export const updateEntity = (
  kbId: string,
  entityId: string,
  body: { type_id?: string; canonical_name?: string }
) =>
  http.request<{ entity: GraphNode; same_name: GraphNode[] }>(
    `/v1/kbs/${kbId}/entities/${entityId}`,
    { method: "patch", data: body }
  );

/** 人工修正一条事实的有效区间（302）。**整体替换**：四个值一起提交，
 *  服务端作废旧行、插修正行——不是原地改，所以这次修改会出现在 History 上。 */
export const updateFactTime = (
  kbId: string,
  factId: string,
  body: {
    valid_from: string | null;
    valid_from_precision: string | null;
    valid_to: string | null;
    valid_to_precision: string | null;
    note?: string;
  }
) =>
  http.request<{
    ok: boolean;
    unchanged?: boolean;
    fact_id?: string;
    closed?: number;
    conflicts?: number;
  }>(`/v1/kbs/${kbId}/facts/${factId}`, { method: "patch", data: body });

/** 实体的一次认知变更（记录时间轴）：服务端分页 */
export const entityHistory = (
  kbId: string,
  entityId: string,
  page: number,
  per = 30
) =>
  http.request<{ events: EntityHistoryEvent[]; total: number }>(
    `/v1/kbs/${kbId}/entities/${entityId}/history`,
    { params: { page, per } }
  );

export const factEvidence = (kbId: string, factId: string) =>
  http.request<{ evidence: Evidence[] }>(
    `/v1/kbs/${kbId}/facts/${factId}/evidence`
  );

/** 一条派生事实的证明（0002 R2）：前提按推导顺序，每条带证据，一路到原句。
 *  派生已失效时回 null——不是错误 */
export const derivedProof = (kbId: string, derivedId: string) =>
  http.request<{ proof: Proof | null }>(
    `/v1/kbs/${kbId}/derived/${derivedId}/proof`
  );

/** 没落地的派生的证明链（0017 §3）：前提在违规的 path 里 */
export const blockedProof = (kbId: string, violationId: string) =>
  http.request<{ steps: ProofStep[] | null }>(
    `/v1/kbs/${kbId}/violations/${violationId}/proof`
  );

/** 手动合并：把 source 并进 target。**方向要紧**——source 消失，
 *  它的事实搬到 target 上；合并可整体回滚（entity_merges 记着快照） */
export const mergeEntities = (
  kbId: string,
  source: string,
  target: string,
  rationale?: string
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/entities/merge`, {
    method: "post",
    data: { source, target, rationale: rationale || null }
  });

export const revertMerge = (kbId: string, mergeId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/merges/${mergeId}/revert`, {
    method: "post"
  });

export const confirmFact = (kbId: string, factId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/facts/${factId}/confirm`, {
    method: "post"
  });

export const rejectFact = (kbId: string, factId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/facts/${factId}/reject`, {
    method: "post"
  });
