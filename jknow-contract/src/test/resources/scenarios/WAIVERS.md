# 挂账清单（WAIVERS）

> waiver 语义：命中的步骤在 Diff（`--dual` 双打比对 / `--left --right` 自比对）与 Replayer 中
> **跳过比对并单列报告（[WAIVE]）——不算差异也不算通过**，显式挂账。
> 键写法等价形都可（`admin-users/tokens-list` / `admin-users/tokens-list.json` /
> `golden/admin-users/tokens-list.json`），装载器统一归一化。
> 临时挂账可不走本清单：`--waive <键>#<原因码>` 可重复参数（与 `--waivers` 本清单叠加，命令行优先）。
> 每条挂账必须有原因码与说明；核销（转回正常双打）时删行并在当期任务报告记录。

## 生效清单

| 步骤 | 原因码 | 说明 |
|---|---|---|
| （无——M5 任务 6 挂账清零：pat-ratchet 2 条与 fulltext-store-ownership 1 条均已核销，见下方已核销节） | | |

## 已核销

| 步骤 | 原因码 | 核销记录 |
|---|---|---|
| admin-users/tokens-list、admin-users/tokens-list-after-issue（两步一行记） | pat-ratchet | M5 任务 6 核销（固定行数处置——剧本改造）：注册体带 `${run_id}` 唯一后缀，每轮全新账号——tokens-list 行数由剧本构造钉死为 0、tokens-list-after-issue 钉死为 1（本轮 issue 的那一枚），全量 body 亦随之确定（掩码前缀在同轮两实现读同一行）。棘轮的来源（固定账号逐轮累积 issue 行，撤销只打戳不删行——tokens.rs:130 语义不变）不再进入录制面。2026-11-13 重录双侧逐键一致，恢复常规比对。 |
| search/search-known-term | fulltext-store-ownership-m2 | M5 任务 6 核销（切流演练重定位）：ingest-doc 与 search-known-term 均转 target:java 单侧验证（wait 步等待 job 静默替代固定 pause）。java 轮（Rust 停机 + Java worker 持写路径）ES 索引自持建成，该步**真命中**（golden-java 样本含命中行）；缺省双打轮 Java worker 关（共享 job 由 Rust 消费进 Tantivy 不进 ES）该步为空集样本——单侧步不参与双打比对。search-no-match 维持 both 正常比对（双侧空集语义等价）。ES 自持写路径证据=2026-11-13 全 19 剧本 java 轮绿 + Diff 0 差异。 |
| workspace-kb/kb-export | rdf-serializer-m1-deferred | M5 任务 1 核销：Java 侧 RDF 导出落地（ExportController + ExportStore 八族分页 + RdfEmitter——Jena StreamRDF 对等 oxrdfio；两道流前预检/四拒导码/审计先行/REPEATABLE READ 单连接流式；rdf.rs 29 内联测试原值移植全绿）。kb-export 步重录双侧真导出 200；**比对口径升级为三元组集归一**（Normalizer.triples：Turtle/JSON-LD 解析回 Model 落排序 N-Triples 行，空白节点按排序后首现重编——Jena 与 oxrdfio 序列化空白/前缀展开顺序不同，字节比必假红），方法记录在剧本 kb-export 步注释。恢复常规比对（2026-11-13 重录 Diff 0 差异）。 |
| auth/oidc-status、oidc-start、oidc-callback、oidc-me、oidc-me-unlink（5 步一行记） | oidc-m1-out-of-scope | M5 任务 2 核销：Java 侧 OIDC 域 8 端点落地（oidc/OidcController——status/start/callback/me/unlink + exchange（0066 受信 audience 换会话，oidc_exchanges 一次性）+ admin identities GET/DELETE；OidcConfig 六环境变量/DiscoveryCache 600s+kid 强刷 60s 冷却/FlowService PKCE S256+nonce+state ct_eq+一次性消费/TokenVerifier RS256 手写验签与 claims 矩阵；SessionFilter 公开四端点精确枚举，me/unlink/管理面回归常规鉴权；AdminUsersController 的 422 占位桩退役）。未配置 IdP 现状双侧同形：status enabled=false、start/callback 303 回跳、me 过会话 enabled=false+linked=false、unlink 422 SSO is not configured；exchange 未配受信表 404（本就不挂账）。auth.yaml 重录（2026-11-13）OIDC 5 步双侧 200/303/422 逐键一致，恢复常规比对。 |

| heart-ledger/ws-create、heart-ledger/kb-create | ws-kb-write-java-deferred | M3 任务 11 核销（条目退役）：heart 系五剧本的 ws/kb 固定装置整体改 **SQL 步**（kb-fixture：工作空间/kb/kb_members 一事务直插，两轮同串执行）——java 轮 Rust 参照实例停机（共享队列双 worker 会抢 job，Java 须消费自己的抽取/物化链，切流预演口径），剧本不再含 target:both 装置步，两个挂账键随步骤一起退役。POST /workspaces 的 Java 实现仍属 M4+（真正核销 ws 写面时另行记录）；快照证据（--writes 两轮 Diff 0 差异）不依赖该端点。 |


| 步骤 | 原因码 | 核销记录 |
|---|---|---|
| ontology/uniqueness-candidates、proposals-stored-empty、questions-list、question-report、imports-list-empty、imports-list-after-apply、rules-list、rule-matches、rule-versions | ontology-read-family-m1-deferred | M4 任务 6 核销（九步一行记）：本体扩展读族九端点落地（OntologyReadFamilyController——uniqueness_candidates 聚合（M3 探测 SQL 接 HTTP，side→axiom 折 functional/inverse_functional）/open_proposals 只摆 agent 提的（四节空数组起步，payload+proposed_by+serves+signatures）/competency_questions 12 列列表+报告聚合（questions 六格 FILTER+uncovered+提案四格与 changed_share 空判 null）/list_imports 近 50/attribute_rules list+matches（per 缺省 20 钳 [1,100]）+versions（labels 按现在查得到的给））。ontology.yaml 重录双打（2026-11-13）九步双侧 200 一致，恢复常规比对。 |
| graph/entity-history-unknown | entity-family-m1-deferred | M3 任务 6 核销：实体历史端点 GET /kbs/{id}/entities/{entity_id}/history（graph_routes.rs:559-571 handler + graph.rs:2232-2423 entity_history SQL——SQL 任务 4 已落 GraphMapper.selectEntityHistory，本任务接 HTTP 路由：per 钳 [1,200]/page≥0/total 含合并撤销三支）。graph.yaml 重录（--writes 缺省双打，2026-11-12）双侧 200 一致，恢复常规比对。 |
| graph/derived-proof-unknown | entity-family-m1-deferred | M3 任务 6 核销：证明链端点 GET /kbs/{id}/derived/{derived_id}/proof（graph_routes.rs:463-473 + reasoning.rs:2802-2812 proof——premise_steps 按 MAX_DEPTH 递归展开/derived_step 读派生步/evidence 逐条）。Rust 200 `{"proof":null}` 与 Java 同形，重录双侧一致。 |
| graph/blocked-proof-unknown | entity-family-m1-deferred | M3 任务 6 核销：幽灵证明链端点 GET /kbs/{id}/violations/{violation_id}/proof（graph_routes.rs:475-483 + reasoning.rs:3066-3083 blocked_proof——前提在违规 path 里，steps_for 展开）。Rust 200 `{"steps":null}` 与 Java 同形，重录双侧一致。 |
| heart-ledger/ledger-snapshot.snapshot | statements-doc-key-order-m2 | M3 任务 1 审查收口核销：StatementsContract.reserialize 改 BTreeMap 字典序输出（doc_time < e < external_id < n < s，与 serde_json 无 preserve_order 的 to_string_pretty 字节对齐）+ SourcesTests.STATEMENTS_STORED 断言同步修正；重录双轮（--writes rust / --writes java）后 Diff 快照步双侧一致（2026-11-11）。挂账生命周期：M3 任务 1 快照器首抓获→审查三重取证坐实→本提交修复核销，全程不到一天——锁 4 尺子价值的直接证据。 |
| sources-ingest/sources-empty | sources-read-family-m1-deferred | M2 任务 4 核销：来源域读面（GET /kbs/{id}/sources 列表——sources.rs:83-143 list 逐字：doc_count/missing_count 子查询 + RSS LATERAL 整块 + config 剔凭据键）随 SourcesController 落地。写步已转 target:java，读步恢复双打常规比对（2026-11-11 重录双侧 200 一致）。 |
| sources-ingest/sources-list-three | sources-read-family-m1-deferred | 同上（三来源列表，SourceView 13 键投影与 Rust serde 字段集一致）。 |
| sources-ingest/folder-token-kind-mismatch-404 | sources-read-family-m1-deferred | 同上（folder 无密钥 → has_push_token 判 false → 404；语义验证不再是偶然同形）。 |
| sources-ingest/api-token | sources-read-family-m1-deferred | 同上（Editor 专用密钥视图端点；ingest_token 经 Normalizer 掩码，跨侧恒等）。 |
| sources-ingest/api-runs | sources-read-family-m1-deferred | 同上（来源运行历史 runs.rs 投影，每源 50 条修剪在 finish_run 收尾）。 |
| sources-ingest/sources-after-delete | sources-read-family-m1-deferred | 同上（删源后列表；documents.source_id FK SET NULL 语义由库约束担保）。 |
| documents/doc-content | d9-upload-no-version-ledger | M2 任务 3 核销：documents 剧本写步转 target:java 时**消除 D9**——Java 上传路径按 create_with_version_and_processing 形状写 document_versions 版本 1 + 同事务入队（DocumentsWriteService.createWithVersion）；双实现共享同一库与 data_dir，读步（both）两侧看到的台账行与 blob 一致，content 由 500 转 200（2026-11-11 重录 documents.yaml 双侧 200，Diff 全绿）。参照实例自身上传路径若仍走 create_from_upload，其**自有**录制（upload 步 target:rust 的形态）才会复现 500——该形态自本任务起不再出现在剧本里。原「参照实例修复后核销」条件由「Java 侧消除 + 写步转移」提前满足。 |
| admin-users/users-list | env-user-set | M1 任务 5 核销：Java 实现 GET /users（members.rs:22-31 org_users SQL 逐字）后做形状专项比对——2026-11-11 对共享库同账号集双侧实测（Java 17001 / Rust 1516 同请求归一化逐键相等，含行序）；行结构 = OrgUser 四键 {id,email,display_name,is_admin}、ORDER BY created_at，与 Rust golden（admin-users/users-list.json）字段集一致。原豁免理由（环境账号集噪声，值差异豁免）随双打转正由 Diff 常规比对承担。 |
