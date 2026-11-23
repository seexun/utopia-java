# M0 契约剧本覆盖矩阵（COVERAGE）

## §0 P3 golden 复活批（2026-11-23，T2 剧本适配与退役记录）

jknow 对表形态下（对照面 `golden-rust/` 权威快照 vs jknow 实录，Recorder 单打），22 剧本全部
适配引擎 `setup.mode: ruoyi_login`（若依 admin/admin123，Bearer 会话引擎默认注入；health 无
setup 原样）。本节记录设计性退役与挂账（挂账明细见 WAIVERS.md 生效清单六组）：

- **auth 域退役（auth-retired）**：`/api/v1/auth/*` 在 jknow 无路由（P1 源仓设计性退役）。
  - auth-login：login/me 两步删除（退役定位：T1 报告 §4 + 本节）。
  - auth：31 步 → 11 步。保留的无会话扫描步按 jknow 实形处置——未认证请求走若依认证入口
    点返回 **HTTP 200 + {"msg":...,"code":401} 假 200 形**，expect 显式声明 200（若依实形），
    体形状由 normalized 对表裁决（10 步；source-push-unknown-404 匿名路由真 404 维持原样）。
    退役 20 步：OIDC 四步（oidc-status/start/callback/exchange）、注册/登录错误分支五步
    （login-wrong-password/register-duplicate/register-bad-email/register-short-password/
    register-missing-display-name）、会话段十一步（login/me/me-patch/me-patch-empty-name/
    password-wrong-current/password-set-temp/password-revert/logout/me-after-logout/
    oidc-me/oidc-me-unlink）——语义依赖的业务码（409/422/303）与响应形状不可复现，录了
    只是路由缺失痕迹。OIDC happy 恢复路径同 §4-D5/D6（jknow 域实装后）。
- **多用户与令牌头挂账（multi-user-single-token / step-auth-override-dead）**：引擎单 token
  会话模型——bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），
  步级 Authorization 覆盖落空。影响：graph/workspace-kb 的 viewer 身份步 7 步、来源/陈述推送
  与 MCP/PAT 头步（含 heart 五剧本的推送链）26 步挂账退役；第二用户本体由剧本 sql 步预建
  （contract_viewer，sys_user 固定 utopia_uuid + admin bcrypt 串 + role 2），成员矩阵步以
  admin 身份保留。规格开放点：引擎增步级会话切换后恢复（任务 2 报告）。
- **admin 实形改写**：admin-users 剧本身份从「非 admin 合同账号」换 ruoyi admin——403 现状
  步按实录实形改 expect（可读面 200、未实装写端点 500、OIDC 管理面 404）；PAT 五步
  （admin-pat-ratchet）与 admin-ds-create（admin-ds-create-writes）挂账退役。
- 以下 M0 矩阵为源仓口径存照（参照服务 1516 已不在对表面）；jknow 对表账目以 golden-rust
  快照面与 WAIVERS.md 生效清单为准。

---

- 参照服务：http://127.0.0.1:1516（Rust utopia 0.1.0，开放注册，共享实例）（存照，已退役）
- 剧本：14 个 YAML（任务 3 的 `health` / `auth-login` + 任务 4 的 12 个域剧本），合计 **331 步**，全部对 1516 录制绿（Recorder exit 0）
- 黄金样本：`utopia-contract/golden/<剧本>/<步骤>.json`（规范化后：uuid/ts 占位、对象数组排序、浮点 6 位容差、噪声字段掩码）
- 路由清单来自 `docs/design/m0-plan.md` §前置知识的展开：计划口径为 60 条路由，速记展开为 **58 个端点**
  （review 家族按 5 计、oidc 家族按 4 计，含 health 与 sources/{sid}/ingest）。
  本矩阵为账实相符另列 4 个各域剧本必需的核心端点——`GET/PATCH /auth/me`、`GET/PATCH/DELETE /kbs/{id}`、
  `GET/DELETE /auth/oidc/me`、`GET /kbs/{id}/conversations/{cid}/stream`——共 **62 行**（58 + 4）；计划 60 条全部在列，
  实际覆盖端点仍远超矩阵（见 §3）。
  审查更正（走查 1）：首版矩阵误以 auth/me 与 kbs/{id} 换出计划明列的 health 与 sources/{sid}/ingest（覆盖无洞、账目漏列），本版补回

## 1. 路由矩阵（62 行；计划的 60 条全含，每条 ≥1 happy 或 deferred+现状形状）

| # | 路由 | 剧本.步骤（分支） | happy |
|---|------|------------------|-------|
| 1 | POST /auth/register | auth: register-duplicate(409)/register-bad-email(422)/register-short-password(422)/register-missing-display-name(422) | setup.register/ensure（不进黄金）；200 首录见 §4-D13 |
| 2 | POST /auth/login | auth-login: login(200)；auth: login-wrong-password(401)/login(200) | ✅ |
| 3 | POST /auth/logout | auth: logout(200)/me-after-logout(200，JWT 会话不失效为现状) | ✅ |
| 4 | POST /auth/password | auth: password-wrong-current(422)/password-set-temp(200)/password-revert(200) | ✅ |
| 5 | GET /auth/me | auth-login: me(200)；auth: me(200)/me-patch(200)/me-patch-empty-name(422)/me-401(401) | ✅ |
| 6 | GET/PATCH/DELETE /kbs/{id} | workspace-kb: kb-get(200)/kb-patch(200)/kb-delete-default-refused(422)/kb-delete-second(200)/kb-get-zero-404(404) | ✅ |
| 7 | GET /auth/oidc/status | auth: oidc-status(200) | ✅ |
| 8 | GET /auth/oidc/start | auth: oidc-start(303) | §4-D5 |
| 9 | GET /auth/oidc/callback | auth: oidc-callback(303) | §4-D5 |
| 10 | POST /auth/oidc/exchange | auth: oidc-exchange(404) | §4-D6 |
| 11 | GET /auth/oidc/me | auth: oidc-me(200)/oidc-me-unlink(422) | ✅（未配置态） |
| 12 | GET /alerts | auth: alerts-401(401)；alerts: alerts-page(200)/alerts-search-no-match(200) | ✅ |
| 13 | GET /alerts/unread | alerts: alerts-unread(200)/alerts-unread-after-read-all(200) | ✅ |
| 14 | POST /alerts/read-all | alerts: alerts-read-all(200) | ✅ |
| 15 | POST /alerts/read-group | alerts: alerts-read-group(200)/alerts-read-group-missing-range(422) | ✅ |
| 16 | GET /alerts/events | chat-misc: alerts-events-401(401) | §4-D1 |
| 17 | GET+POST /workspaces/{id}/kbs | workspace-kb: ws-kbs-empty(200)/ws-kbs-one(200)/kb-create(200)/kb-create-second(200) | ✅ |
| 18 | GET /workspaces/{id}/my-kbs | workspace-kb: my-kbs(200) | ✅ |
| 19 | GET /workspaces/{id}/members | workspace-kb: ws-members-initial(200)/ws-members-two(200) | ✅ |
| 20 | GET /ontology-packs | auth: ontology-packs-401(401)；workspace-kb: ontology-packs(200) | ✅ |
| 21 | GET /users | auth: users-401(401)；admin-users: users-list(200，§5 环境敏感) | ✅ |
| 22 | GET /users/deactivated | admin-users: users-deactivated-forbidden(403) | §4-D7 |
| 23 | POST /admin/users | admin-users: admin-create-user-forbidden(403) | §4-D7 |
| 24 | GET /admin/oidc/identities | admin-users: admin-oidc-identities-forbidden(403)/admin-oidc-unbind-forbidden(403) | §4-D7 |
| 25 | GET /kbs/{id}/audit | workspace-kb: kb-audit(200) | ✅ |
| 26 | POST /kbs/{id}/chat | chat-misc: chat-no-model-422(422 no_chat_model，结构断言) | §4-D4 |
| 27 | POST /kbs/{id}/chat/{cid}/stop | chat-misc: chat-stop-unknown-404(404) | ✅（形状） |
| 28 | GET /kbs/{id}/conversations | chat-misc: conversations-empty(200)/conversations-search(200)/detail-rename-delete(404) | ✅ |
| 29 | GET /kbs/{id}/conversations/{cid}/stream | chat-misc: conversation-stream-401(401) | §4-D3 |
| 30 | GET /kbs/{id}/data-sources | mappings: kb-data-sources(200)/kb-data-sources-available(200) | ✅ |
| 31 | GET /kbs/{id}/entities | graph: entities-search(200)；search: entities-search-empty(200) | ✅ |
| 32 | GET /kbs/{id}/events | chat-misc: kb-events-401(401) | §4-D2 |
| 33 | GET /kbs/{id}/export | auth: kb-export-401(401)；workspace-kb: kb-export(200，RDF) | ✅ |
| 34 | GET /kbs/{id}/graph/overview | auth-login: graph-404(404)；graph: overview(200)×2 + viewer-overview-ok(200)/viewer-kb-hidden-404(404) | ✅ |
| 35 | POST /kbs/{id}/graph/rebuild | graph: graph-rebuild(200)/viewer-rebuild-403(403) | ✅ |
| 36 | POST /kbs/{id}/ingest | sources-ingest: kb-ingest-created(200)/kb-ingest-unchanged(200)/kb-ingest-empty-filename(422) | ✅ |
| 37 | GET /kbs/{id}/jobs/failed | documents: jobs-failed-empty-kb(200) | ✅ |
| 38 | POST /kbs/{id}/jobs/requeue | documents: jobs-requeue-kb(200) | ✅ |
| 39 | POST /jobs/requeue | documents: jobs-requeue-all-forbidden(403) | §4-D7 |
| 40 | GET /kbs/{id}/jobs/{job_id} | documents: jobs-nonexistent-404(404) | §4-D8 |
| 41 | POST /kbs/{id}/mappings/preview | mappings: mapping-preview-empty-422(422)/mapping-preview-unmounted-422(422) | §4-D11 |
| 42 | GET /kbs/{id}/mappings/relevant | mappings: mappings-relevant(200)/mappings-relevant-missing-q-400(400) | ✅ |
| 43 | POST /kbs/{id}/mcp | auth: kb-mcp-401(401)；chat-misc: mcp-initialize/mcp-tools-list/mcp-unknown-method(200，Bearer PAT) | ✅ |
| 44 | GET /kbs/{id}/members | workspace-kb: kb-members-initial(200)/kb-members-two(200)/kb-members-after-remove(200) | ✅ |
| 45 | GET /kbs/{id}/ontology | ontology: ontology-empty(200) | ✅ |
| 46 | POST /kbs/{id}/ontology/propose | ontology: ontology-propose-queued(200) | ✅（排队回执） |
| 47 | GET /kbs/{id}/questions/report | ontology: question-report(200) | ✅ |
| 48 | GET /kbs/{id}/readiness | auth: kb-readiness-401(401)；workspace-kb: kb-readiness(200)/viewer-read-ok(200)/viewer-kb-hidden(404) | ✅ |
| 49 | GET /kbs/{id}/review | review: 全 12 队列(200)+分页(200)+types=conflict(200)+queue-unknown-422(422) | ✅ |
| 50 | POST /kbs/{id}/review/batch | review: review-batch-empty-422(422)/review-batch-unknown-ids(200) | ✅ |
| 51 | GET /kbs/{id}/review/history | review: review-history(200) | ✅ |
| 52 | GET /kbs/{id}/review/summary | review: review-summary(200) | ✅ |
| 53 | POST /kbs/{id}/review/{review_id} | review: review-decide-unknown-404(404) | ✅（形状） |
| 54 | POST /kbs/{id}/rules/run | ontology: rules-run(200) | ✅ |
| 55 | POST /kbs/{id}/search | auth: kb-search-401(401)；search: search-known-term(200)/search-no-match(200)/search-missing-q-422(422)/search-unknown-kb-404(404) | ✅（M1 任务6 命中路径已绿：BM25 命中双打逐字一致；嵌入路 §4-D12 只剩向量通道） |
| 56 | GET /documents/{id}/content | documents: doc-content(200，M2 任务3 写步转 java+D9 消除后双侧 200；挂账核销) | ✅（M2 任务3 重录） |
| 57 | POST /documents/{id}/extract | documents: doc-extract(200 {job_id}，排队回执) | ✅（回执形状） |
| 58 | POST /documents/{id}/purge | documents: doc-purge(200)/doc-purge-conflict(409) | ✅ |
| 59 | POST /documents/{id}/restore | documents: doc-restore(200)/doc-restore-conflict(409)/doc-restore-purged-409(409) | ✅ |
| 60 | GET /documents/{id}/versions | documents: doc-versions(200) | ✅ |
| 61 | POST /sources/{source_id}/ingest | sources-ingest: push-created(200)/push-unchanged(200)/push-updated(200)/push-stale-token-401(401)/push-bogus-token-401(401)；auth: source-push-unknown-404(404) | ✅ |
| 62 | GET /api/v1/health | health: health(200，golden meta.path 实录 /api/v1/health；裸 /health 为 SPA HTML) | ✅ |

矩阵小计：**62 行全部有 ≥1 个已黄金化步骤**（计划 60 条路由全含）；happy 已录（✅）47 行（含 3 个形状级断言：
chat/{cid}/stop、review/{review_id} 的 404 形状与 extract 的排队回执——真实数据依赖，M1 双打时以真实数据补全），
happy deferred 15 行（现状/错误形状已录，逐行指向 §4 的 D 项）。

## 2. 步骤数汇总（实际录制数字）

| 剧本 | 文件 | 步骤 | 覆盖要点 |
|------|------|-----|---------|
| health | health.yaml | 1 | /api/v1/health |
| auth-login | auth-login.yaml | 3 | 登录链路（任务 3 遗留，语法范本） |
| auth | auth.yaml | 31 | 401 扫描×11 + OIDC 族 + 注册/登录错误分支 + 改密往返 + 登出 |
| alerts | alerts.yaml | 7 | 告警四操作 + 422 |
| workspace-kb | workspace-kb.yaml | 42 | 工作区/库全周期 + 两级成员 + viewer 403 + 审计/readiness/export/设置 |
| admin-users | admin-users.yaml | 21 | /users + admin 面 403×15 + PAT 全周期 |
| documents | documents.yaml | 34 | 上传(写步转 java)/筛选/内容 200(D9 消除)/抽取排队/墓碑-恢复-真删/库内 jobs |
| sources-ingest | sources-ingest.yaml | 31 | 三类来源 + api 推送三分支 + 陈述契约 + ingest + sync/runs/轮换/清理 |
| graph | graph.yaml | 28 | 视图族 + 实体端点族 + 证明链 + 重建 + viewer 403/404 |
| search | search.yaml | 9 | 检索 4 分支 |
| ontology | ontology.yaml | 45 | 本体 CRUD + 类型消解 + 提案 + OWL 导入 + 问题 + 规则全周期 |
| review | review.yaml | 41 | 12 队列×分页/筛 + 汇总/历史/批量 + 逐项裁决 404/422 + 一致性/推理 |
| mappings | mappings.yaml | 18 | 口径列表/相关/预览 422 + 库内数据源面 |
| chat-misc | chat-misc.yaml | 20 | SSE 401 段 + chat 422 + 会话形状 + MCP×3 + noop |
| **合计** | **14** | **331** | ≥300 达标（12 个新域剧本 327 步） |

## 3. 矩阵 62 行之外一并覆盖的端点（bonus）

workspaces CRUD（list/create/get/patch/delete）、workspaces/{id}/settings 族（get/put/ocr/transcribe/test）、
me/tokens（list/issue/revoke）、admin/deployment（get/put 403）、admin/data-sources 全家（list/create/delete/test/grants 403）、
kbs/{id}/documents（list+multipart upload）、documents/retry-failed、extraction-drops、documents/{id}（detail/delete）、
extractions、reprocess、sources CRUD（folder/api/statements）+ sync/runs/re-extract/token/rotate-token/missing-cleanup、
sources/{sid}/statements、ontology entity-types（create/patch/delete/entities）、relation-types（create/patch/delete/reconcile）、
uniqueness、type-resolution（preview/apply/approve/undo）、proposals（stored/decide/adopt）、ontology/imports（list/preview/apply）、
questions CRUD+propose、rules CRUD（create/patch/matches/versions/delete）、review 逐项端点
（pending/agent/alignment phrases|kind-words|rules/errata/mappings/violations/defects）、consistency/check、inference/run、
facts confirm/reject/close、merges revert、conflicts resolve、entities merge/history/patch、facts evidence、
derived/violations proof、graph/neighborhood、jobs/noop。

## 4. deferred 清单（13 条，D1–D13，每条给 M5 补法；矩阵 15 个 deferred 行映射其中 11 项——D5 管 2 行、D7 管 4 行，D10/D12 对应矩阵外端点）

| # | 项 | 现状记录 | M5 补法 |
|---|----|---------|---------|
| D1 | GET /alerts/events（认证 SSE 流） | 401 未认证已录 | 引擎加「首帧截取/读超时」原语后补录首事件帧 |
| D2 | GET /kbs/{id}/events（认证 SSE 流） | 401 已录 | 同 D1 |
| D3 | GET /conversations/{cid}/stream（认证 SSE 快照） | 401 已录 | 同 D1（快照帧断言） |
| D4 | POST /kbs/{id}/chat 真问答 | 422 no_chat_model 已录（结构断言） | 配置假 OpenAI 兼容端点（chat 模型）后补录会话创建+消息结构 |
| D5 | /auth/oidc/start、callback happy | 303 已录 | 本地假 IdP（OIDC 授权码流程）后补录 |
| D6 | /auth/oidc/exchange happy | 404 已录 | 同 D5 |
| D7 | admin 全家 happy（deployment/users/oidc/data-sources/grants、users/deactivated、POST /jobs/requeue 全局） | 403 已录 | 参照实例种子 admin 账号后补录 |
| D8 | GET /kbs/{id}/jobs/{job_id} 真实任务 | 404（不存在）已录 | 任务状态含重试退避时序；用固定 noop 任务+等终态原语补 |
| D9 | GET /documents/{id}/content 200 | **M2 任务 3 已清偿**：Java 上传路径消除 D9（写台账版本 1），documents 写步转 target:java 后双侧读同一共享台账——200 双录一致（原 500 挂账 d9-upload-no-version-ledger 随之核销，见 WAIVERS.md 已核销节） | 完成（2026-11-11） |
| D10 | /kbs/{id}/ontology/regressions 两端点 | 404 已录（参照二进制未装配该路由，仓库源码有） | 升级参照服务后补录 |
| D11 | mappings/preview 挂载 happy、mount/sync-schema happy | 422（未授权/未挂载）已录 | 并入 D7（admin 建源授权后挂载补录） |
| D12 | search 向量通道（embeddings） | BM25 命中路径 M1 任务 6 已录绿（ES+IK 双打一致）；无嵌入模型=纯 BM25 现状 | 配假 embed 端点后补录向量通道（RRF 第二路） |
| D13 | POST /auth/register 200 黄金 | 409/422 已录；200 由 setup 承担（不进黄金） | 干净实例首录时补 200 样本（或在 M1 双打以 Replayer 现场注册对照） |

## 5. 环境敏感步骤（重录轮之间的已知漂移，M1 回放需按形状比对）

| 步骤 | 漂移原因 |
|------|---------|
| admin-users: users-list / tokens-list* | 组织用户全体随录制轮注册增长；PAT 行打戳不删行、逐轮累积。**M1 任务1 起经 WAIVERS.md 挂账**（Diff/Replayer 加 `--waivers scenarios/WAIVERS.md` 单列跳过，不算差异也不算通过）；PAT 存量已全部 revoke（0 枚活跃），行仍在是设计语义 |
| documents: doc-content | ~~D9 细化诊断~~ **M2 任务 3 核销**：写步转 target:java + D9 消除（上传写台账版本 1+同事务入队），双实现共享库/data_dir 读同一份台账与 blob——200 双录一致，不再漂移 |
| documents: docs-list / retry-failed | 文档 status/failed 计数随后台摄取-抽取管道时序（已用 pause_ms 缓解主档位；M2 任务3 起 retry-failed 为 target:java 单侧步，不进跨侧比对） |
| sources-ingest: sources-empty 等 6 读步 | ~~M1 未实现挂账~~ **M2 任务 4 核销**：sources 读族（列表/密钥/运行历史）随来源域落地，写步转 target:java 后两侧读同一共享库——6 步双打一致（147 样本全绿）；22 条来源域写步自此为 target:java 单侧步（不进跨侧比对），api-runs 行集为剧本内确定性推送（每轮自建库，无跨轮漂移） |

设计原则：每剧本专属账号（contract-&lt;域&gt;@test.local）+ 自建专属工作区/库（名字带剧本名）+ 结尾删工作区收口；
重录轮间不依赖顺序、不依赖历史对象。
