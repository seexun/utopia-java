// 本体域：类/关系/属性/规则/提案/导入/类型归一/推理（对照旧仓 api.ts 同域函数，仅换框架层）。
import { http } from "./client";
import type {
  EntityTypeView,
  RelationTypeView,
  OntologyProposals,
  OntologyRegressionCase,
  UniquenessCandidate,
  CompetencyQuestion,
  QuestionReport,
  ImportPlan,
  OntologyImportView,
  TypeSuggestion,
  ResolutionOutcome,
  RuleInput,
  RuleCondition,
  BusinessRule,
  RuleMatch,
  RuleVersion
} from "./types";

export const ontology = (kbId: string) =>
  http.request<{
    entity_types: EntityTypeView[];
    relation_types: RelationTypeView[];
  }>(`/v1/kbs/${kbId}/ontology`);

export const ontologyRegressions = (kbId: string) =>
  http.request<{ cases: OntologyRegressionCase[] }>(
    `/v1/kbs/${kbId}/ontology/regressions`
  );

export const addOntologyRegression = (
  kbId: string,
  body: {
    statement_id: string;
    relation_type_id: string;
    direction: "forward" | "reverse";
  }
) =>
  http.request<{ id: string }>(`/v1/kbs/${kbId}/ontology/regressions`, {
    method: "post",
    data: body
  });

export const createEntityType = (kbId: string, body: Record<string, unknown>) =>
  http.request<{ id: string }>(`/v1/kbs/${kbId}/ontology/entity-types`, {
    method: "post",
    data: body
  });

export const updateEntityType = (
  kbId: string,
  id: string,
  body: Record<string, unknown>
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/ontology/entity-types/${id}`, {
    method: "patch",
    data: body
  });

export const deleteEntityType = (kbId: string, id: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/ontology/entity-types/${id}`, {
    method: "delete"
  });

export const typeEntities = (
  kbId: string,
  typeId: string,
  page: number,
  per = 12
) =>
  http.request<{
    entities: { id: string; name: string; fact_count: number }[];
    total: number;
  }>(`/v1/kbs/${kbId}/ontology/entity-types/${typeId}/entities`, {
    params: { page, per }
  });

export const createRelationType = (
  kbId: string,
  body: Record<string, unknown>
) =>
  http.request<{ id: string }>(`/v1/kbs/${kbId}/ontology/relation-types`, {
    method: "post",
    data: body
  });

/** 一端挂着两个以上开放值的谓词（#341）：本体没人声明唯一性时接任不闭合前任 */
export const uniquenessCandidates = (kbId: string) =>
  http.request<{ candidates: UniquenessCandidate[] }>(
    `/v1/kbs/${kbId}/ontology/uniqueness`
  );

/** 补上声明之后把已经在账上的开放行对一遍。声明本身走 updateRelationType */
export const reconcileRelationType = (kbId: string, id: string) =>
  http.request<{ corrected: number; conflicts: number }>(
    `/v1/kbs/${kbId}/ontology/relation-types/${id}/reconcile`,
    { method: "post" }
  );

export const updateRelationType = (
  kbId: string,
  id: string,
  body: Record<string, unknown>
) =>
  http.request<{ ok: boolean }>(
    `/v1/kbs/${kbId}/ontology/relation-types/${id}`,
    { method: "patch", data: body }
  );

export const deleteRelationType = (kbId: string, id: string) =>
  http.request<{ ok: boolean }>(
    `/v1/kbs/${kbId}/ontology/relation-types/${id}`,
    { method: "delete" }
  );

/** 上次算出来、还没人表态的提案（0049）。刷新页面靠它，不必重跑模型 */
export const storedProposals = (kbId: string) =>
  http.request<OntologyProposals>(`/v1/kbs/${kbId}/ontology/proposals`);

/** 一条提案有人表态了。改状态不删行——拒绝留痕，下一轮 Suggest 不再刷回待看 */
export const decideProposal = (
  kbId: string,
  section: string,
  key: string,
  status: "adopted" | "rejected",
  /** 拒绝的理由（0061）：下一轮代理读得到 */
  reason?: string
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/ontology/proposals`, {
    method: "post",
    data: { section, key, status, reason }
  });

/** 叫本体代理来看一眼（0061 决定 2）：排一份任务，结果落在 storedProposals */
export const proposeOntology = (kbId: string) =>
  http.request<{ queued: boolean }>(`/v1/kbs/${kbId}/ontology/propose`, {
    method: "post",
    data: "{}"
  });

/** 采纳代理的一条提案（0061 决定 3）：服务端建元素、标记、排对齐 */
export const adoptProposal = (
  kbId: string,
  section: string,
  key: string,
  edits?: {
    label?: string;
    description?: string;
    datatype?: string;
    domains?: string[];
    ranges?: string[];
    parents?: string[];
  }
) =>
  http.request<{ id: string }>(`/v1/kbs/${kbId}/ontology/proposals/adopt`, {
    method: "post",
    data: { section, key, ...(edits ?? {}) }
  });

/** 能力问题（0061 决定 1） */
export const listQuestions = (kbId: string) =>
  http.request<CompetencyQuestion[]>(`/v1/kbs/${kbId}/questions`);

export const createQuestion = (
  kbId: string,
  body: { question: string; expected_answer?: string }
) =>
  http.request<{ id: string }>(`/v1/kbs/${kbId}/questions`, {
    method: "post",
    data: body
  });

export const updateQuestion = (
  kbId: string,
  qid: string,
  body: {
    question?: string;
    expected_answer?: string;
    status?: "accepted" | "rejected" | "retired";
  }
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/questions/${qid}`, {
    method: "patch",
    data: body
  });

export const deleteQuestion = (kbId: string, qid: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/questions/${qid}`, {
    method: "delete"
  });

/** 让代理给库提问题（0061 决定 1）：排任务，结果是 proposed 的问题 */
export const proposeQuestions = (kbId: string) =>
  http.request<{ queued: boolean }>(`/v1/kbs/${kbId}/questions/propose`, {
    method: "post",
    data: "{}"
  });

/** 两个数（0061 决定 5） */
export const questionReport = (kbId: string) =>
  http.request<QuestionReport>(`/v1/kbs/${kbId}/questions/report`);

/** 上传本体文件只算出计划，一个字节都不写库 */
export const previewOntologyImport = (kbId: string, file: File) => {
  const form = new FormData();
  form.append("file", file, file.name);
  return http.request<{ filename: string; plan: ImportPlan }>(
    `/v1/kbs/${kbId}/ontology/imports/preview`,
    {
      method: "post",
      data: form,
      // OWL 文件上传解析不限时（同 client.ts 取舍注释）
      timeout: 0
    }
  );
};

/** 执行刚看过的那个计划——服务端重新算一遍，两条路径共用同一段代码 */
export const applyOntologyImport = (kbId: string, file: File) => {
  const form = new FormData();
  form.append("file", file, file.name);
  return http.request<{ import_id: string; plan: ImportPlan }>(
    `/v1/kbs/${kbId}/ontology/imports`,
    {
      method: "post",
      data: form,
      timeout: 0
    }
  );
};

export const ontologyImports = (kbId: string) =>
  http.request<{ imports: OntologyImportView[] }>(
    `/v1/kbs/${kbId}/ontology/imports`
  );

/* ---- 类型归一（0009 后续）：粗类 → 细类，只算不写 / 落库 / 撤销 ---- */

export const typeResolutionPreview = (kbId: string) =>
  http.request<{ items: TypeSuggestion[] }>(
    `/v1/kbs/${kbId}/ontology/type-resolution/preview`,
    { method: "post" }
  );

/** 跑一遍并落库。三档分开回：自动改的、留给人的、说「都不是」的 */
export const typeResolutionApply = (kbId: string) =>
  http.request<ResolutionOutcome>(`/v1/kbs/${kbId}/ontology/type-resolution`, {
    method: "post"
  });

/** 认可一个「粗类 → 细类」的配对，并把带上的实体改过去。
 *  **认可的是类对，改的是实体**——认可一次，之后同一对不再进人工 */
export const approveRefinement = (
  kbId: string,
  body: { from_type_id: string; to_type_id: string; entity_ids: string[] }
) =>
  http.request<{ retyped: number }>(
    `/v1/kbs/${kbId}/ontology/type-resolution/approve`,
    { method: "post", data: body }
  );

/** 撤销一整批：把那批实体放回原来的类 */
export const typeResolutionUndo = (kbId: string, batchId: string) =>
  http.request<{ reverted: number }>(
    `/v1/kbs/${kbId}/ontology/type-resolution/${batchId}`,
    { method: "delete" }
  );

/** 跑一遍推理（R1）。开关关着时后端回 inference_off */
export const runInference = (kbId: string) =>
  http.request<{
    /** 编译出来的规则条数。为零时是「没有规则」而不是「推不出东西」 */
    rules: number;
    edges: number;
    derived: number;
    inserted: number;
    /** 前提没了、跟着作废的 */
    invalidated: number;
    /** 撞上单谓词上限、没推完的谓词个数 */
    capped: number;
  }>(`/v1/kbs/${kbId}/inference/run`, { method: "post" });

/* ---- 业务规则（0021 / #277）：人写下的判据，引擎按物化的节奏跑 ---- */

export const rules = (kbId: string) =>
  http.request<{ rules: BusinessRule[] }>(`/v1/kbs/${kbId}/rules`);

export const createRule = (kbId: string, body: RuleInput) =>
  http.request<{ id: string }>(`/v1/kbs/${kbId}/rules`, {
    method: "post",
    data: body
  });

export const updateRule = (
  kbId: string,
  ruleId: string,
  body: {
    name?: string;
    description?: string;
    enabled?: boolean;
    conditions?: RuleCondition[];
    /** 结论整组替换：三格互相定义，只改一格会留下半截状态 */
    conclusion?: "typing" | "attribute" | "relation";
    conclude_type_id?: string;
    conclude_predicate_id?: string;
    join_predicate_id?: string;
    conclude_value?: unknown;
  }
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/rules/${ruleId}`, {
    method: "patch",
    data: body
  });

/** 一条规则此刻标了谁。规则卡片上那个数字点开就是它 */
export const ruleMatches = (kbId: string, ruleId: string, page = 0, per = 20) =>
  http.request<{ matches: RuleMatch[]; total: number }>(
    `/v1/kbs/${kbId}/rules/${ruleId}/matches`,
    { params: { page, per } }
  );

/** 一条规则的定义史：改过几次、每一版怎么说 */
export const ruleVersions = (kbId: string, ruleId: string) =>
  http.request<{ versions: RuleVersion[] }>(
    `/v1/kbs/${kbId}/rules/${ruleId}/versions`
  );

export const deleteRule = (kbId: string, ruleId: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/rules/${ruleId}`, {
    method: "delete"
  });

/** 立刻跑一遍。物化默认一小时一轮，而写规则的人想马上看见它推出了什么 */
export const runRules = (kbId: string) =>
  http.request<{
    rules: number;
    hits: number;
    capped: number;
    inserted: number;
    invalidated: number;
    /** 链跑了几轮：1 就是没有链，2 就是一条规则读了另一条的结论（0030） */
    rounds: number;
    /** 跑满上限还在产出：链比 MAX_DEPTH 长，后面那几环没接上 */
    rounds_capped: boolean;
  }>(`/v1/kbs/${kbId}/rules/run`, { method: "post" });
