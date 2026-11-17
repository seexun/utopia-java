# P1 后端业务迁移验收报告

- 分支 `p1-backend`，基线 `p0-done`（d3d9d74），收尾提交见文末，tag **`p1-done`**
- 目标：utopia-java 后端业务全量迁入 jknow——七域模块（llm/reason/ingest/extract/store/search/contract）依赖序搬运 + utopia-server 业务落位 `jknow-admin web/knowledge` + Flyway V0001-V0101 全链 + 双 token 认证（若依 token / pt- 个人令牌）+ 业务冒烟 smoke-p1
- 规模（p0-done → p1-done 前置 3af68f1）：13 个提交，1140 files，+159,289 / −29
- dev 环境（写实）：PG 15432 / Redis 36379（compose），应用 19080；ES 9200 本期不可达（SKIP 语义），共享对打栈 1517 本期未起（相关 2 例 SKIP）

## 一、十任务勾选状态

| # | 任务 | 提交 | 状态 |
|---|---|---|---|
| 1 | P0 交接收口（surefire 上移根 pluginManagement / 数据源变量对齐规格 §3 JKNOW_JDBC_URL 全串优先 / run.bat JVM 参数序） | `2cde7f4` | [x] DONE |
| 2 | 属性收口（jena/jsqlparser/pdfbox/juniversalchardet/jsoup/bc）+ utopia-common 并入 jknow-common（8 类+RrfTests）+ jknow-llm 搬运 | `b8590ed` | [x] DONE |
| 3 | jknow-reason / jknow-ingest / jknow-extract 三域搬运（20 测试随迁绿，POI 6 兼容面实证） | `16ff4f8` | [x] DONE |
| 4 | jknow-store 搬运（46 mapper†）+ Flyway 全链 V0001-V0101 入库（102 迁移/源 99 文件字节保真含 19 个改号）+ V0100 菜单种子 + V0101 用户重绑（28 FK 改指 26 表） | `00e6581` | [x] DONE |
| 5 | jknow-search 搬运 + ES/业务配置键并入（jknow.* 主段 + utopia.* 别名，ES 不可达启动不炸） | `2da7ab5` | [x] DONE |
| 6 | jknow-contract 搬运（6 测试随迁绿）——七域模块全部就位（13 模块 reactor） | `40eea5c` | [x] DONE |
| 7 | utopia-server 全量业务落位 jknow-admin web.knowledge（16 业务包，auth/oidc 舍弃，ClientContext 桥接若依 LoginUser，users SQL 28 条处置，234 测试随迁 195/删 39）+ packs/docs 资源并入 | `ca17484` | [x] DONE |
| 8 | 认证收口——若依 token / pt- 个人令牌双通（PersonalTokenService jjwt 0.13 + 摘要查表验签 + SecurityConfig 链序 jwt→pt→bridge）+ 业务 Jackson2/SNAKE_CASE 契约修复 + 26 测试重建 | `f6fc4ff` + 审查修复 `e814531` | [x] DONE |
| 9 | smoke-p1 业务冒烟（55 PASS/2 SKIP，p0 零退化）+ 冒烟暴露缺陷修复：安全链放行 ASYNC/ERROR dispatch（kb 导出流式响应被二次鉴权截断） | `b8325dc` + `3af68f1` | [x] DONE |
| 10 | P1 收尾——全测终验、p1-report、README 模块图、.gitignore data/、tag | 本提交（tag `p1-done` 处） | [x] DONE |

计划文档提交：`fcf1332`（P1 实现计划，T1 前置）。各任务详报：`.superpowers/sdd/2026-11-17-p1-backend/task-{1..10}-report.md`（git-ignored 台账）。

† T4 的「46 mapper」为源仓口径：实况 45——OidcMapper 随 oidc 域退役（T7 删 OidcMapper.java/.xml 含 FlowRow，见 §六随迁对账）。

## 二、规格覆盖对照（设计规格 §2/§3/§5-P1）

| 规格条目 | 落点 | 证据 |
|---|---|---|
| §2.3 七域搬运（common/llm/reason/ingest/extract/store/search/contract） | T2-T6 | 13 模块 reactor `mvn -q -DskipTests compile` 绿；源↔迁 diff 差异行 100% 为包名替换 |
| §2.4 server 拆解（业务落 web.knowledge，auth/oidc 舍弃） | T7 | 27 Controller / 163 路由（137 去重）就位；auth/oidc 域删净（Argon2/JwtService grep 0） |
| §2.2 认证重宿主 + pt- 前缀 | T7/T8 | 双通 curl：若依 token 与 pt- 各打 /api/v1/workspaces 同形 200；撤销后即时 401 源体形状 |
| §2.5 ES/LLM/worker/MCP 保留 | T5（键并入）/T9（条件断言） | ES 不可达启动不炸；worker jobs.worker-enabled 门控在位；MCP PAT 认证切 PersonalTokenService |
| §3 V0001-V0099 / V0100 / V0101 + 闸门 MigrationTests | T4、T10 复验 | 102 迁移文件；MigrationTests 2/2（重放全链 + 幂等）随全仓绿 |
| §5-P1 验收（全量 API 冒烟 + MigrationTests + 随迁测试绿） | T9/T10 | smoke-p1 55 PASS/2 SKIP；smoke-p0 20/20 零退化；全仓 1328 run 0F 0E 22S |
| p0-report §八交接（surefire/变量名/run.bat/迁移约定） | T1 | 四项全收口（见任务 1 行） |

## 三、终验输出（2026-11-17，任务 10 实测）

序列：`mvn install -q -DskipTests -pl jknow-admin -am`（exit 0，13 模块）→ `JKNOW_PG_PORT=15432 SERVER_PORT=19080 mvn spring-boot:run -pl jknow-admin` → smoke-p0 → smoke-p1 → 停应用（taskkill 残留 fork 进程，19080 复测不可达）→ `mvn -q test`（exit 0）→ 停净复查（无 jknow/spring/maven java 进程）。

### 启动关键日志

```
DbValidate - Successfully validated 102 migrations
t.s.j.JknowApplication - Started JknowApplication in 10.551 seconds
```

（启动期唯一 ERROR 为探测 curl --max-time 提前断连触发的客户端中止写，非应用缺陷。）

### smoke-p0.sh（20/20，exit=0，P0 面零退化）

```
PASS: login / getInfo / getRouters
PASS: system/user|role|menu|dict/type|config|notice/list
PASS: monitor/operlog|logininfor|job/list
PASS: 8 面 date-range（user/role/dict.type/config/jobLog/logininfor/operlog/tool gen）
ALL PASS
```

### smoke-p1.sh（55 PASS / 2 SKIP，exit=0）

```
PASS: login
PASS: 4 造数（workspace → kb → entity-type → relation-type，实体/关系实例无直建端点，由 ingest→extraction 管线产出）
PASS: 45 GET（workspace 6 / kb 5 / chat 1 / data-sources 2 / documents 1 / graph 3 / jobs 1 /
      mappings 2 / ontology 5 / questions 2 / review 4 / rules+sources 2 / tokens 1 /
      packs+users 3 / alerts 2 / admin 2 / health 2 / extraction-drops 1）
PASS: 5 pt-（签发前缀 / 两读面 200 / 撤销 200 / 撤销后即时 401）
SKIP: POST api/v1/kbs/{id}/search（es 不可达，ES 通→必须 200）
SKIP: POST api/v1/kbs/{id}/chat（JKNOW_LLM_API_KEY 未配；配后还需 Settings 配模型）
ALL PASS (PASS=55 SKIP=2)
```

断言账目：1 登录 + 4 造数 + 45 GET + 5 pt- + 2 条件 = 最高 57，本轮 55 PASS + 2 SKIP；脚本幂等（固定名 p1-smoke 前缀跑前清理，异常残档重跑自愈）。

### mvn -q test 全仓（exit=0）

| 模块 | run | fail | err | skip | skip 明细 |
|---|---|---|---|---|---|
| jknow-common | 6 | 0 | 0 | 0 | — |
| jknow-reason | 107 | 0 | 0 | 0 | — |
| jknow-ingest | 62 | 0 | 0 | 1 | AlignmentFixtureGenTests（align.gen 属性门，源仓同语义） |
| jknow-extract | 109 | 0 | 0 | 0 | — |
| jknow-store | 24 | 0 | 0 | 0 | — |
| jknow-search | 5 | 0 | 0 | 5 | EsChunkIndexTests（ES 门控整类跳过） |
| jknow-contract | 47 | 0 | 0 | 2 | SnapshotPgTests（1517 共享栈未起） |
| jknow-admin | 968 | 0 | 0 | 14 | RetrievalHybrid 4 + SearchApi 命中面 1 + ChatFallback legacyRAG 3（ES 门控）；HumanPhrase 3 + SealInterop 3（env 门控） |
| **合计** | **1328** | **0** | **0** | **22** | 全为环境门控缺省跳过，语义正常 |

（T9 全量记录 1327/22S；本期 1328 为环境门控用例计数浮动 ±1，同 T7 报告 825→831 先例，两轮均 0F 0E。）

## 四、Boot 4 / 兼容断裂点台账（十任务累积汇总）

源仓基线 Spring Boot 3.3.13 → jknow 4.1.2。逐任务断裂面与修法：

| 任务 | 断裂点 | 修法/裁定 |
|---|---|---|
| T2 llm | 零断裂（无 spring/jakarta import，纯 JDK HttpClient） | Boot 4.1.2 BOM 仍 import jackson-2-bom 2.21.7 托管 Jackson2，无版本依赖直接解析 |
| T3 三域 | 零断裂（JDK+jackson+poi/pdfbox/jsoup 系） | POI 5.2.3→6.0.0 零 API 断裂；行为面双验证（ParseMatrix 23 例往返 + ChunkAlignment 7 例对 Rust fixture 逐字段一致）；勘误：POI 面在 jknow-ingest（非简报所称 extract） |
| T4 store | 编译/测试零断裂（mybatis-spring-boot 4.1.0，46 mapper 未改一字） | 运行面 4 项交接 T7：users SQL 28 条 / utopia.data-dir 键 / utopia_jobs 通道 / UserRow |
| T5 search | **ES 客户端真断裂**：Boot4 BOM elasticsearch-java 9.4.8 对 ES 服务器 8.14.3 发 compatible-with=9 头被拒（5/5 全错 status 400） | 客户端锁 **8.13.4**（源仓 Boot3.3.13 BOM 同款），根 pom 属性+dependencyManagement 双构件同版本收口——对全局约束"冲突取高"的有据偏离；升 9.x 需 2 处 API 改名（GetIndexResponse/GetMappingResponse `result()`→`indices()`/`mappings()`，已试过并回退存档） |
| T6 contract | 零断裂；依赖上浮全兼容（junit 5→6.0.3、snakeyaml 2.2→2.6、jackson 2.17.3→2.21.7、postgresql→42.7.14） | 47 用例绿 |
| T7 server 落位 | ①测试基类断裂：若依 DruidConfig 不读裸 spring.datasource.*；②**Boot4 MVC 默认 Jackson3（tools.jackson）与业务 Jackson2/SNAKE_CASE 契约断裂**（JsonNode 入参 500、响应 camelCase） | ①StoreTestBase 族改 spring.datasource.druid.master.* + flyway.enabled=false + fixture 改写（32 文件，jknow_test_user plpgsql 助手族）；②移交 T8 修复。另：MysqlEngineTests 时钟漂移炸为宿主环境问题（两仓同炸，w32tm /resync 修复，非回归） |
| T8 认证+契约 | Jackson2 契约修复四件套 + T1 遗留 jsqlparser 冲突 | KnowledgeJacksonConfig（SNAKE_CASE + fail-on-missing-creator-properties + ISO 日期 + 忽略未知字段）+ BusinessJackson2HttpMessageConverter prepend（声明集=JsonNode 子树+业务包类型+业务裸 Map/List，排除集含 TableDataInfo/若依系）；**jsqlparser 5.4→4.7**（pagehelper 6.1.1 只配 4.7，5.4 删 Select.getASTNode 致若依分页面运行时 NoSuchMethodError——T1 属性收口埋雷、T8 冒烟暴露修复；QueryEngines 只用 4/5 兼容稳定 API 已核对）；jjwt 0.13 铸 pt- JWT（HKDF-SHA256 派生子密钥与若依会话密钥隔离） |
| T9 smoke | **StreamingResponseBody 二次鉴权截断**：ASYNC dispatch 再过安全链，上下文为空 Access Denied，chunked 终止块丢失（curl 18） | SecurityConfig `.dispatcherTypeMatchers(ASYNC, ERROR).permitAll()`（Spring Security 对 streaming/async 端点既定做法；REQUEST dispatch 鉴权不弱化） |
| T10 | 无新断裂（文档/收尾任务） | — |

**仍在锁的低版本约束**（有意裁定，非欠账）：jsqlparser 4.7（pagehelper 配对）、elasticsearch-java 8.13.4（服务器 8.14.3 线配对）——两者升级条件分别绑 pagehelper 升级与 ES 服务器升 9.x，见遗留清单。

## 五、迁移与用户重绑台账（V0000-V0101，102 文件）

- **V0001-V0099**：源仓 99 个迁移文件（源版本号 V0001-V0080 连续 + V0090-V0110 空洞段）整链拷入；其中 19 个**改号**为 V0081-V0099（只改文件名前缀，SQL 字节不动，`cmp` 逐字节 0 diff）——裁定缘由：源已存在自己的 V0100/V0101 且 V0109 仍 REFERENCES users(id)，用户重绑必须排在全部源内容之后。**备案：jknow 迁移历史与 utopia-server 版本号不再一一对应（内容顺序一致），后续增量同步按内容顺序对齐而非版本号。**
- **V0100**（新写）：知识图谱菜单 25 行（1 顶级目录 M + 8 个 C 页 + 16 个 F 按钮），id 2100-2182，全部 visible='1' 隐藏（P2 落地后再开）。
- **V0101**（新写）：`sys_user.utopia_uuid` 桥接列（uuid NOT NULL DEFAULT gen_random_uuid() + 唯一索引）+ 28 条 FK 改指 `sys_user`（26 张表，约束名 PG 缺省 表_列_fkey）+ 收尾 `DROP TABLE users`。
- **闸门**：MigrationTests（Testcontainers 空库）重放 102 全链 + 幂等双测，断言表数 111 / sys_menu 110 / 桥接列在位 / memberships FK 指向 sys_user；本期终验随全仓绿。
- **空库策略**：V0101 不回填旧 users→sys_user 存量映射（jknow 是空库起步）；若需存量导入，须先建 utopia_uuid 映射再迁数据。

## 六、随迁测试对账（源 → jknow）

源仓 src/test 全量 **266** 个 .java（计划口径 265，实测 266；256 个 *Tests.java + 10 个助手类），全部位于九个 utopia 模块：

| 对账项 | 文件数 | 说明 |
|---|---|---|
| 七域模块随迁（1:1 同名同数） | 32 | common 1 / llm 0 / reason 5 / ingest 5 / extract 10 / store 4 / search 1 / contract 6；CRLF→LF 落盘形态，内容 diff 100% 为包名替换 |
| utopia-server → jknow-admin web/knowledge | 234 → 221 | 文件级对账（按基名 comm）：存活同名 219 + 语义并入 2 + 永久退役 13 + jknow 侧新增 2 |
| ├ 永久退役（13） | 13 | auth/oidc 域 6（AccountsRegister/SessionCompat/Argon2Service/JwtService/OidcExchange/OidcRoutes）；源 MigrationTests 1（去重——jknow 已有 P0 闸门版覆盖 V0000-V0101）；pipeline 族 6（PipelineTestBase/GithubSync/ProcessDocumentJob/RssSyncContract/SourceCheckpointAndFilename/SyncSourceJob） |
| ├ 语义并入（2） | — | OntologyReadFamilyApiTests→OntologyApiTests；ReviewRoutesPhraseTests→ReviewApiTests（决策等待/job 面由 TypeAlignment+JobsWorker 覆盖） |
| └ jknow 侧新增（2） | +2 | ApiTestBase（MockMvc+LoginUser 形态基类，替代源 TestRestTemplate+cookie）、TokensPatApiTests（pt- 双通验收位） |
| T7→T8 中间态 | 39 删→24 文件级重建 + 2 并入 + 2 新增 | T7 删 39（auth/oidc/pipeline 闭包：HTTP 族测试铸 JwtService cookie 的全数退役）；T8 按 MockMvc+LoginUser 与直调 ChatFx 两形态重建（146+ 用例），pipeline 族 7 文件有意不重建（见遗留） |
| jknow-admin 测试树终态 | 222 | 221 业务 + 1 P0 原生 MigrationTests |
| **jknow 全仓测试树终态** | **254** | 32 七域 + 222 admin；运行 1328 用例 0F 0E 22S（见 §三） |

**users 表删除后的 AccountsService 适配实况**（brief 点名项）：AccountsService（注册编排，随 auth 域退役）与 Registered.java、OidcMapper.java/.xml 整体删除；AccountsMapper 28 条 users 相关 SQL 处置=删 14 条（4 条用户 CRUD 随注册流 + 7 条 org/workspace 建库族 + 3 条 oidc）+ 改写 18 条（`users u` join → `sys_user u ON u.utopia_uuid`，email→user_name、display_name→nick_name、在职谓词 del_flag='0' AND status='0'、is_admin 双道谓词 [user_id=1 OR role_id=1 授予]）；残留 grep `FROM users\b|INTO users\b|UPDATE users\b` = 0。测试侧 32 个 fixture 文件经 StoreTestBase 的 jknow_test_user plpgsql 助手族改写（Java 实参表零改动）。

## 七、遗留清单（P2/P3 候选，均不阻塞验收）

**行为差异（对齐源契约）**

1. **必填参数缺失回 500 而非源 400**：缺 `q`/`chunk_id`/`entity` 三面，全局异常处理器把 MissingServletRequestParameterException 归 500「Internal server error」（源 axum 400/反序列化原文）。冒烟按契约补参绕开；P2 前后端联调时对齐（AppExceptionHandler 补 400 分支即可）。**（P1 终审已修：补 MissingServletRequestParameterException 分支回 400 源文案「Failed to deserialize query string: missing field \`x\`」，冒烟已挂 q/chunk_id 两面断言）**
2. **若依 entry point 无凭据是 200+体 401，与源真 401 有差异**：RuoYi 原生 renderString 行为（HTTP 200 + {"code":401}）；pt- 凭据失效面走 filter 直写真 401 源体形状。业务 API 前端拦截器若按真 401 判会漏——**升级为 P2 硬依赖（见追加 4）**。
3. **SSE 滞后帧 Boot4/Tomcat 收口竞态容忍**：流早关时退化验注册表侧收口（帧断言保留），slowReaders 队列面滞后语义已全量断言；若要求帧级确定性需 sseFrom 收口前 flush。

**测试/冒烟覆盖面**

4. **pipeline 族 7 测试不重建**（有意）：依赖已退役的 /api/v1/auth/register 注册流 + 真 ES 索引 + Testcontainers 专属库；存活覆盖=纯面（Adjudication/ExploreMappings/TypeAlignment/JobsFramework）+ HTTP 写面（DocumentsWrite/Sources）+ worker 面（JobWorker）；深管线语义归冒烟条件断言与 P3。
5. **17 个明细 GET 未入冒烟**：`/documents/{id}`(+content/extractions/versions) 等"具体资源明细"读，冒烟零造档无现成 id，硬造随机 id 只会测 404；**ES/LLM 面 SKIP**（search 2 例、chat 1 例条件断言）——P3 起 ES 后真验（chat 还需 Settings 配模型）。
6. MysqlEngineTests 对宿主时钟敏感（TLS 证书 NotBefore 窗）：宿主钟漂则两仓同炸，修法=w32tm /resync，非代码问题（T7 §7.3 存档）。

**版本锁定约束**

7. **jsqlparser 锁 4.7**（源仓 5.4）：pagehelper 6.1.1 配对约束（5.4 删 getASTNode 致分页面炸）；全仓唯一 Java 使用方 QueryEngines 只用 4/5 兼容稳定 API（query 域测试全绿佐证）。升 5.x 需连 pagehelper 一起评估。
8. **ES 客户端锁 8.13.4**：Boot4 BOM 9.4.8 对 8.14.3 服务端实测不兼容（§四 T5 行）；升 9.x 需两处 API 改名（存档 T5 报告 §0）。

**数据/迁移**

9. **V0101 不回填旧 users 映射**（空库策略）：存量导入需先建 utopia_uuid 映射（§五）。
10. **迁移改号备案**：源 V0090-V0110 → V0081-V0099（字节一致），增量同步按内容顺序对齐（§五）。

**方案 B 桥接列的 P2/P3 评估项**（brief 点名项）

11. is_admin 双道谓词（user_id=1 OR role_id=1）——若依侧角色模型与业务超管语义的长期统一；
12. 用户管理面两套并存：若依 /system/user 与业务 /api/v1/users（selectOrgUsers 的 orgId 参数仅签名兼容保留，单租户语义=部署内全部在职）；selectDeactivatedUsers 以 update_time 近似停用时刻（sys_user 无停用时刻列）；
13. UtopiaUuidResolver/pt- 反查进程内缓存无失效：用户停用/解绑生效窗口到进程重启（立即生效面只有表行撤销/过期）；
14. 菜单 2100-2199 visible='1' 隐藏待 P2 开页（开页只需 UPDATE sys_menu，不动迁移文件）；M/C 层级若前端路由生成有不同要求同法调整。

**Minor（账本全量，T4-T9 报告归集）**

15. 幂等清理对字段序的耦合（jknow_test_user_cleanup 变参序）；ASYNC dispatch 修复无自动化回归（curl 18 场景，export 断言在冒烟覆盖）；token.secret 轮换致 pt- 全失效（源为 DB 密钥收敛，形态决策已知代价）；jknow-contract 的 1517/UTOPIA_* 双打语境键保留（SnapshotPg/SealInterop env 门控缺省跳过）；scenarios/(22)+golden/(331) 契约工装数据未随迁（复活口径：从源仓整体拷入即可，T6 §4）；utopia_jobs 通道名与 utopia-kb- 索引前缀 wire 级标识有意保留。

**本任务收口项**

16. `data/secret.key` 运行时产物 → `.gitignore` 已补 `data/`（本提交）；README 后端模块结构图补 13 模块清单（本提交）。

**P1 终审补录（第 1/5 轮收口后追加，四项）**

17. **公开路由面回归与修复（终审 C1）**：源 SessionFilter 的 PUBLIC_EXACT 族（health 两形、sources 按推送 token 的 ingest/statements 两族）随 auth 域退役后无人承接——匿名 `GET /api/v1/health` 落到若依鉴权面（200+体 code401，监控探针不可用）。修复：SecurityConfig authorizeHttpRequests 补两组 permitAll（GET `/api/v1/health`+`/health`；POST `/api/v1/sources/*/ingest`+`/statements`）——推送端点自带 requireBearer 推送密钥/404 判定，安全面与源等价；auth/oidc 族继续舍弃不放开。smoke-p1 已挂匿名 health（200+version）与推送无密钥 401 断言。
18. **pt- scope 的 REST 面强制（终审 I1）**：read 令牌此前只读得了（RBAC 各端点自判），写方法未被 scope 拦——PersonalTokenAuthFilter 对非 GET/HEAD 加 `canWrite()` 校验（tokens.rs:192-194 同判：scope 是上限，RBAC 收窄在其后照常），read pt- 打写接口 → 401 源体形状。
19. **@PreAuthorize 规格项 P1 未挂注解为有意延后——P2 硬任务**：业务面权限目前由 RBAC（KbAccess/矩阵角色）持有，菜单权限注解（`@PreAuthorize("@ss.hasPermi(...)")`）未挂。P2 落地时须一并定夺 **pt- 权限集策略**：本实现给 pt- LoginUser 的 `knowledge:*` 在若依 hasPermi 的**精确匹配**下不匹配任何 `knowledge:xxx:yyy` 实权限——二选一在 P2 计划落字：(a) 按 V0100 perms 全集展开授给 pt- LoginUser；(b) PermissionService 支持前缀匹配（`*` 通配）。挂注解而不先定此策略会让 pt- 面全线 403。
20. **entry point 200+体401 升级为 P2 硬依赖**：前端 401 拦截按真 401 判会话失效，现状（RuoYi renderString 的 HTTP 200+体 code401）会让业务 API 前端拦截器漏判——必须与 P2 前后端联调同轮处理（改若依 entry point 影响若依前端面，需一并回归）。

## 七'、复跑指引

见 `README.md`：compose 起依赖（PG 15432/Redis 36379）→ `mvn install -q -DskipTests` → `JKNOW_PG_PORT=15432 SERVER_PORT=19080 mvn spring-boot:run -pl jknow-admin` → `./scripts/smoke-p0.sh`（20/20）→ `./scripts/smoke-p1.sh`（55 PASS/2 SKIP，ES/LLM 条件断言按环境）。注意：冒烟脚本会 flushdb Redis（若依会话全失效）；spring-boot:run 前必须先 install（SNAPSHOT 本地仓陷阱）。
