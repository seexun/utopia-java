# golden 可行性判定 spike 报告（test-infra 任务 2）

日期：2026-11-21 ｜ 分支：feat/test-infra ｜ 判定对象：源仓 utopia-java golden 对表工装（Recorder/Diff，已随迁 jknow-contract）能否复活用于 jknow 后端

---

## §1 工作流（实录与对比怎么跑）

**工具形态**（jknow-contract/src/main/java/tech/seexun/jknow/contract/，头注定案）：

- Recorder 单打：`mvn -q -pl jknow-contract compile exec:java -Dexec.mainClass=tech.seexun.jknow.contract.Recorder -Dexec.args="--target http://127.0.0.1:27401 --scenario <yaml> --out <dir>"` —— 全部步骤打同一服务，先清后录，剧本全绿才落盘。
- Diff 两目录：`mvn -q -pl jknow-contract compile exec:java -Dexec.mainClass=tech.seexun.jknow.contract.Diff -Dexec.args="--left golden-java/<族> --right spike-out/golden-jknow/<族> --waive <步>#<原因码>"` —— Jackson 树等价比对，四分类（一致/挂账/单侧/差异）。
- 剧本 YAML 关键能力（实测确认）：`steps[].headers` 支持自定义头且**值过 `${var}` 替换**；`capture: {var: body.<路径>}` 可把响应体字段捕进变量。⇒ **若依 Bearer 鉴权无需改引擎**：首步 `POST /login` capture `token: body.token`，后续步 `headers: {Authorization: 'Bearer ${token}'}` 即可（本 spike 25 步实录全部走通）。

**实录命令实形**（三族适配剧本在 spike-out/scenarios/，不入库）：

```bash
mvn -q -pl jknow-contract compile exec:java -Dexec.mainClass=tech.seexun.jknow.contract.Recorder \
  -Dexec.args="--target http://127.0.0.1:27401 \
    --scenario spike-out/scenarios/spike-auth-login.yaml \
    --scenario spike-out/scenarios/spike-graph.yaml \
    --scenario spike-out/scenarios/spike-ontology.yaml \
    --out spike-out/golden-jknow"
```

适配剧本三处改动（相对源仓 yaml）：① 删 `setup.register/ensure`（utopia 注册流已退役）；② 首步 `ruoyi-login`（POST /login，捕 body.token）；③ 各步注入 Bearer header。源仓同名步骤原样保留供对表。

**环境一次性改动（记录在案）**：`sys_config.sys.account.captchaEnabled` true→false（UPDATE + DEL Redis 键 `sys_config:sys.account.captchaEnabled`）——若依 /login 验证码是无人值守录制的 blocker，且与「admin/admin123 可登录」的环境承诺冲突。恢复：改回 true 并清同名 Redis 键。复活立项建议配 test profile 固化关验证码。

**零适配对照实验**（归因①的决定性证据）：源仓 auth-login.yaml **原样**跑 Recorder → `[FAIL] 剧本 auth-login 步骤 login: 响应无 set-cookie 头，无法建立会话`。机理：若依 `AuthenticationEntryPointImpl` 用 renderString 写未认证响应——**HTTP 状态恒 200、错误只在 body `{"msg":...,"code":401}`**；ScenarioRunner.setup 把 /api/v1/auth/register 的假 200 误判成功、cookieOf 取不到 set-cookie 返回 null，login 步 capture set-cookie 落空失败。状态码面被包装破坏是复活时引擎必须处理的陷阱。

## §2 抽样数字

- 抽样面：graph/ontology/auth-login 三族 **13 个可比步**（有源仓 golden-java 样本的读步族；auth-login 3 / graph 5 / ontology 5），另 12 步为脚手架（ruoyi-login/ws/kb/本体建/收尾删）。实录 25 步**全绿**，一次通过。
- **一致：11/13（84.6%）** —— graph 5/5、ontology 5/5、auth-login 的 graph-404，normalized 内容与源仓 golden-java **规范化树等价一致**（Jackson 树等价比对，非字节级；含 `{"error":"Not found"}` 的 404 形状与空集形状）。
- **差异：2/13（15.4%）** —— login/me 两步，同根因（auth 域退役），见 §3。
- **路由前缀差异：0**（/api/v1/... 全承接，workspaces/kbs/graph/ontology 端点逐一在位）；**数据前提缺失：0**（自建装置 ws→kb→本体全链路可用）。
- Diff 原始输出：auth-login「2 比 3 差」（2 处 meta.expectedStatus——适配剧本漏写 expect 的自伤，内容层 graph-404 零差；1 处 login 右侧独有——源侧该步在 golden-rust 未随迁）；graph「5 比 0 内容差」；ontology「5 比 0 内容差」（其余「仅左侧存在」均为未抽样的源步，非失配）。
- 适配路径系统性差异（探针实测，非抽样步）：
  - 未认证任意端点：jknow **HTTP 200** + `{"msg":"...","code":401}` vs 源 **HTTP 401** + `{"error":"Not signed in or invalid credentials"}` → ①（auth.yaml 401 族 7 步整族受影响）。
  - /login 响应体：`{"msg","code","token"}`（若依 AjaxResult）vs 源 `{token,user{...}}` → ①（每剧本 login 步必改）。
  - /api/v1/health：version `"0.1.0"`→`"1.0.0"` → ③（1 处）。
  - 缺失路由形态：源 404 `{"error":"Not found"}` → jknow **500** `{"error":"Internal server error"}`（NoResourceFoundException 落 AppExceptionHandler.unexpected，未映射 404）→ ③/⑥。

## §3 逐例归因表

分类：①鉴权包装差异 ②路由前缀变化 ③字段/端点演进 ④数据前提缺失 ⑤一致 ⑥其它

| 族 | 步 | 源 golden-java | jknow 27401 实录 | 归因 |
|---|---|---|---|---|
| auth-login | login | （golden-rust 侧：200 `{token,user}`） | 500 `{"error":"Internal server error"}` | ③ /api/v1/auth/login 退役（P1 auth 域退役）；缺失路由经 NoResourceFoundException→500 |
| auth-login | me | 200 `{created_at,display_name,email,id,is_admin,org_id}` | 500 同上 | ③ /api/v1/auth/me 退役（同根因） |
| auth-login | graph-404 | 404 `{"error":"Not found"}` | 404 `{"error":"Not found"}` 规范化树等价一致 | ⑤（meta.expectedStatus 差为适配剧本漏写 expect 的自伤⑥，内容零差） |
| graph | graph-overview-default-limit | `{edges:[],nodes:[],total_edges:0,total_nodes:0}` | 同 | ⑤ |
| graph | entities-search | `{entities:[],total:0}` | 同 | ⑤ |
| graph | graph-neighborhood-unknown | 空邻域形状 | 同 | ⑤ |
| graph | entity-detail-404 | `{"error":"Not found"}` | 同 | ⑤ |
| graph | entity-history-unknown | 空历史形状 | 同 | ⑤ |
| ontology | ontology-empty | `{dismissed_misses:[],entity_types:[],misses:[],relation_types:[]}` | 同 | ⑤ |
| ontology | entity-type-instances | 空实例分页形状 | 同 | ⑤ |
| ontology | uniqueness-candidates | `{candidates:[]}` | 同 | ⑤ |
| ontology | proposals-stored-empty | 空提案形状 | 同 | ⑤ |
| ontology | imports-list-empty | 空导入形状 | 同 | ⑤ |

**分类计数**（13 可比步）：⑤=11、③=2、①=0（带内）、②=0、④=0、⑥=2 例 meta 噪声（自伤，非 jknow 行为）。**真实失配 2 例 100% 落在③单一根因**；计入适配路径①证据仍在 1-2 类内。

## §4 判定结论 + 建议

### 判定：值得复活

- **依据**：≥70% 归因集中阈值以极大余量达成——真实失配 100% 集中于③的**单一根因**（auth 域退役，属设计性退役而非漂移）；②④为零说明 API 面零结构漂移；11/13 规范化树等价一致证明 golden 工装的规范化器（Normalizer 掩码/占位/键序）与 jknow 响应面完全兼容，Recorder/Diff 原样可用（本 spike 零引擎改动跑通全流程）。
- **适配量级预估**：
  - 引擎（jknow-contract，约 40 行）：ScenarioRunner 增若依 setup 模式（POST /login 捕 token 进 vars + 默认 headers 注入 Bearer，替代 setup.register 的 /api/v1/auth/register，~30 行）；识别「HTTP 200 + code:401」假成功（~10 行）。sql/snapshot 步**零改动**（连接串走 env：`UTOPIA_JDBC_URL=jdbc:postgresql://localhost:15432/jknow` + `UTOPIA_DB_USERNAME/PASSWORD=postgres`）。
  - 剧本（22 个 yaml）：每个删 setup 块（22 处，机械）；步级 headers 可免（引擎默认注入）；auth.yaml 401 族（7 步）与 auth-login 的 login/me（2 步）按若依面重写或挂账退役；多用户步（graph viewer-* 5 步、review 等）需预建 sys_user+utopia_uuid 绑定装置（剧本 sql 步即可，工具已支持）。
  - jknow 后端（可选 2 处，让两族 golden 直接复用否则重录）：AuthenticationEntryPointImpl 的 401 包装对齐 utopia 形状（HTTP 401 + error 体）；AppExceptionHandler 补 NoResourceFoundException→404 映射。
  - golden 数据：golden-java 253 快照读步族可直接对表（本 spike 证实）；**权威对照面 golden-rust 未随迁**——复活立项时须一并拷入，对表形态升级为「源 rust 权威 vs jknow 实录」。
- **后续立项建议**：立「golden 复活」批——引擎 40 行 + 22 剧本机械适配 + 2 族重写 + golden-rust 拷入 + 重录扩展到全 22 剧本；预估 2-3 人日。优先级建议排在 T3 清卷回归之后、作为独立批（不阻塞本测试基建批收口）。

### §5 pipeline 69/69 挂账关闭记录

- 挂账背景（规格 2026-11-21-test-infra-design）：「pipeline 7 测试挂账已失效」——GithubIssues 9 / GuardedHttp 19 / HydrationMinor 5 / JiraIssues 5 / NotionConnector 16 / ObjectStorage 11 / SignatureVector 4 = 69。
- 本次复跑实测（2026-11-21，`mvn -pl jknow-admin test -Dtest="GithubIssuesTests,GuardedHttpTests,HydrationMinorTests,JiraIssuesTests,NotionConnectorTests,ObjectStorageTests,SignatureVectorTests"`）：逐类 9/19/5/5/16/11/4 全绿，合计 **Tests run: 69, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**。
- **结论：挂账关闭**——账本项「pipeline 7 测试挂账」标注已失效关闭（主代码 11 类在位、夹具齐、无 pom 排除，实测两轮一致）。
- contract 门控基线复跑（判定处置后）：`cd jknow-contract && mvn test` → **Tests run: 47, Failures: 0, Errors: 0, Skipped: 2**（47/2S 与基线一致，拷入的 golden-java/scenarios 资源不影响门控），BUILD SUCCESS。

### §6 commit 记录

- 数据拷入（值得复活分支保留，spike 态标注）：见本 commit 的前一提交（`test(contract): golden 331 快照+22 剧本随仓`；标题内 331 为规格侦察期口径失准的传导，实拷 golden-java 253 json——2026-11-22 终审勘误）。
- 本报告：本提交（`docs(test): golden 可行性判定报告+pipeline 挂账关闭`）。
- spike 工作产物（spike-out/ 适配剧本与实录、零适配对照）不入库，留盘供 T3 走查。
