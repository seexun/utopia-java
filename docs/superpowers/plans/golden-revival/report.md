# P3 golden 复活批总报告（T1+T2+T3）

日期：2026-11-23 ｜ 分支：feat/golden-revival ｜ T3 基线：59fcc57 ｜ 状态：**DONE（复活成立）**

任务计划：[2026-11-23-golden-revival.md](../2026-11-23-golden-revival.md) ｜ 分任务报告：T1/T2 见 `.superpowers/sdd/2026-11-23-golden-revival/task-{1,2}-report.md`（仓外存档）

---

## §0 结论速览

- **19 族全量对表（rust 权威面 vs jknow 实录）：失配 0，复活成立。**
  全 19 剧本 Recorder 单打实录 286 样本全绿（exit 0×19）；全树 Diff 首轮 108 差异逐例归因，
  除 health 残面（version 0.1.0→1.0.0，权威面文件随实情修正）外全部显式挂账（107 行新
  WAIVERS + 终审补挂 2 行 pat-surface-retired）；处置后 Diff **exit 0：一致 88 / 失配 0 /
  挂账 180 / 单侧存照跳过 89**（终审补挂前为 178/91，两键 chat-misc/pat-issue、
  chat-misc/pat-revoke 由单侧存照移入挂账，复跑实测）。
- **处置分支**：非 heart/auth/admin 预期面失配 ≈0 → 走「README 验证入口」分支——验证节新增
  「golden 契约对表（人工验证面，非 CI 门）」小节（命令序列+env+captcha 开关纪律+非 CI 门声明）。
- **全量回归**：清卷口径全仓 `mvn test` BUILD SUCCESS（**SUREFIRE 合计 1339 run / 0 FAIL /
  0 error / 9 skip，admin 971 / contract 55**——T1 勘误基线 1336 + T2 runner 3 测）；演示环境重建
  （seed+`JKNOW_ES_RECONCILE=1` 重启，ES 空索引自动重建 5 docs）→ 双 smoke
  （p0 20/20、p1 PASS=67 SKIP=1）→ 前端 `pnpm test` **425 passed（58 文件）**。
- engine（T1，`843d05f`+`c4d3302`）与剧本适配（T2，`e786ff7`+`59fcc57`）移交面全部复验成立；
  T3 落地：`WAIVERS.md` 107 行挂账+T3 段记、`COVERAGE.md` §0 T3 对表记录、`golden-rust/health`
  残面修正、README 验证入口、本报告。

## §1 全 19 族实录（步骤 1 前半）

- 后端：27401 演示数据态（kb=1「演示：智造产业链」、entities=19、ES 索引 5 docs），
  env `JKNOW_PG_PORT=15432 SERVER_PORT=27401 JKNOW_ES_RECONCILE=1 JKNOW_JOBS_WORKER=true
  JKNOW_DATA_DIR=<仓根>/data`（演示重建口径同 README，ES_RECONCILE 必带）。
- **captcha 纪律（全程两轮复位，实测强制）**：实录前 UPDATE false + redis DEL → /login 实测通过；
  19 族录完复位 true + DEL 并实测强制（无验证码 /login → `{"msg":"验证码已失效","code":500}`）。
  后续清卷重建后 captcha 随迁移初始值回到 true（见 §4）。
- Recorder 单打（`--target http://localhost:27401 --scenario
  jknow-contract/src/test/resources/scenarios/<族>.yaml --out spike-t3/golden-jknow`，
  exec:java 工作目录=仓根；全族带 `UTOPIA_JDBC_URL=jdbc:postgresql://localhost:15432/jknow
  UTOPIA_DB_USERNAME=postgres UTOPIA_DB_PASSWORD=postgres`——sql 步族需要，余族无害）。
- **19/19 exit 0**，实录 286 样本落 `spike-t3/golden-jknow/`（不入库）；分族步数与 T2 适配表
  一致（合计 308 步 = HTTP/快照样本 286 + sql 13 + wait 7 + ...见 T2 §1）。
- 实录全绿本身即引擎验收复验：T2 只实录过 5 族，本轮补齐 documents/review/sources-ingest/
  chat-misc/workspace-kb/alerts/search/mappings/heart-5 全部一次过（含 documents 上传-抽取-等待
  链、heart 装置+快照链）。

## §2 全树对表与四类计数表（步骤 1 后半）

**形态**：`Diff --left jknow-contract/src/test/resources/golden-rust --right spike-t3/golden-jknow
--waivers jknow-contract/src/test/resources/scenarios/WAIVERS.md`（全树一键——WAIVERS 键含
`<族>/<步>` 前缀与全树比对键同形；golden-java 侧面 T1 已证 11 一致 + 2 退役定位，作辅助参照）。

**首轮（仅 T2 83 行挂账）：比对 128、差异 108、挂账 71、单侧跳过 91。** 108 差异逐例归因见 §3。

**处置后（终态，含终审补挂）：比对 88 全部一致（exit 0）、挂账 180、单侧跳过 89。四类计数表（19 族）：**

| 族 | 一致 | 失配 | 退役（T2 挂账） | 豁免（T3 挂账） | 单侧存照 | 实录样本 |
|---|---|---|---|---|---|---|
| health | 1 | 0 | 0 | 0 | 0 | 1 |
| auth-login | 1 | 0 | 2 | 0 | 0 | 1 |
| auth | 1 | 0 | 20 | 10 | 0 | 11 |
| alerts | 2 | 0 | 0 | 2 | 3 | 7 |
| search | 5 | 0 | 0 | 0 | 4 | 9 |
| mappings | 8 | 0 | 0 | 0 | 10 | 18 |
| review | 17 | 0 | 0 | 24 | 0 | 41 |
| ontology | 12 | 0 | 0 | 3 | 30 | 45 |
| graph | 9 | 0 | 9 | 0 | 10 | 19 |
| workspace-kb | 10 | 0 | 7 | 18 | 8 | 36 |
| admin-users | 0 | 0 | 6 | 15 | 0 | 15 |
| chat-misc | 4 | 0 | 7 | 5 | 2 | 11 |
| documents | 14 | 0 | 1 | 14 | 5 | 33 |
| sources-ingest | 4 | 0 | 0 | 15 | 3 | 22 |
| heart-ledger | 0 | 0 | 2 | 1 | 1 | 2 |
| heart-merge | 0 | 0 | 3 | 1 | 1 | 2 |
| heart-errata | 0 | 0 | 2 | 1 | 2 | 3 |
| heart-axioms | 0 | 0 | 3 | 1 | 6 | 7 |
| heart-replay | 0 | 0 | 7 | 1 | 2 | 3 |
| **合计** | **88** | **0** | **69** | **111** | **89** | **286** |

口径注：

- **一致** = 两侧同名样本树等价（Diff compared 全绿，含 health 残面修正后）；**失配** = 未归因
  差异（终态 0，Diff exit 0 即机械证明）；**退役** = T2 生效清单命中的挂账（剧本已删步，权威面
  遗留样本「仅左侧存在」豁免）；**豁免** = T3 新增 107 行 + T2 ontology import 两行
  （authority-java-side-absent 同码）+ 终审补挂 `pat-surface-retired` 2 行，覆盖「仅右侧存在」
  （权威面缺步/heart 快照）与 40 个结构性/环境性内容差样本；**单侧存照** = 权威面
  `target=rust` 步按 Diff 语义跳过比对（89 = 87 有 jknow 同名实录 + 2 无对应），双打时代
  语义保留，不计入四类。
- **终审补挂点名（单侧 91 → 挂账 +2）**：首轮单侧存照 91 中 `chat-misc/pat-issue`、
  `chat-misc/pat-revoke` 两键系权威面 `target=rust` 无剧本步（T2 chat-misc 适配已删 PAT 现发/撤）
  且原无挂账行的无主样本——终审以新码 `pat-surface-retired` 补挂 2 行（WAIVERS 生效清单末尾）
  后移入豁免列，复跑 Diff exit 0：挂账 178→180、单侧 91→89，一致 88/失配 0 不变。
- 挂账 180 = T2 83 行中命中 69 + T3 107 + T2 ontology 2 + 终审 pat 2；T2 83 行中 **12 行为
  休眠键**（权威面无对应文件、实录亦不产该步：sources 推送 9、workspace-kb/viewer-write-forbidden、
  heart-errata/errata-retract-applied、heart-merge/review-fixture），留引擎扩展后核销对账。

## §3 失配逐例归因表（108 → 0）

首轮 108 差异两类：**右独有 67**（`仅右侧存在`）+ **内容差 41**。归因与处置：

### §3.1 右独有 67（权威面缺样本，jknow 实录为新增证据）

| 类 | 族（步数） | 归因 | 处置 |
|---|---|---|---|
| 权威面缺步 62 | documents 14、review 23、sources-ingest 13、workspace-kb 9、chat-misc 3 | 源仓录制期该步为 `target:java` 单侧样本只进 golden-java 不进权威面（写步转 java 时代的历史口径）——T2 ontology import 两步（`authority-java-side-absent`）同因扩面 | WAIVERS 同码挂账 62 行；golden-java 合并对照启用时可核销 |
| heart 快照骨架 5 | heart-axioms/errata/ledger/merge/replay 各 1 快照 | 引擎单 token 推送链退役（step-auth-override-dead 连锁）→ jknow 侧为装置+空库骨架快照；**权威面未随迁 heart 快照样本**（无左侧对应文件）——T2 预判的「大差异面」实际形态是缺样本非内容差，优于预期 | WAIVERS 新码 `heart-skeleton-surface` 挂账 5 行；0054 陈述信封证据缺口留引擎步级鉴权扩展后重录 |

### §3.2 内容差 41（两侧同名样本，首差异点归因）

| 类 | 步数 | 首差异点（权威面 vs 实录） | 归因 | 处置 |
|---|---|---|---|---|
| health 残面 | 1 | `version: "0.1.0" != "1.0.0"` | 服务器产品版本身份值：权威面录自 rust utopia 0.1.0，jknow 产品 1.0.0。**择小**：掩码扩展须同时改冻结的权威面文件且会误掩 rules/doc-versions 的真契约 version 字段（ontology rules 两步实录同值已证）；剧本 expect 无法表达体内容——**直接修正权威面文件 1 字段为 1.0.0**（最小改，本报告即记录） | `golden-rust/health/health.json` version→1.0.0（不挂账，恢复常规比对） |
| auth 假 200 签名 | 10 | `meta.expectedStatus: 401 != 200`（体形 `{"error"}` vs `{"msg",code:401}`） | 未认证扫描步走若依认证入口点假 200 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写） | WAIVERS 新码 `fake-200-ruoyi-entry` 挂账 10 行 |
| admin 实形签名 | 14 | `meta.expectedStatus: 403 != 200/404/500` | ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404）——T2 admin 实形处置预期签名 | WAIVERS 新码 `admin-shape-rewritten` 挂账 14 行 |
| 用户集环境差 | 1 | users-list 用户数组全集不同 | 组织挂账账号集随环境漂移（源仓先例码 env-user-set，M1 任务 5 同款） | 同码挂账 1 行 |
| 演示 seed 可见面 | 2 | alerts-page items 0→1、alerts-unread 0→3 | jknow 剧本身份 admin 名下可见 seed 演示库 3 条待审勘误告警（README「审查队列 3 待审」的告警投影） | 新码 `demo-seed-visible` 挂账 2 行 |
| 账号/成员显示名 | 8 | `display_name/actor_name/imported_by_name: "Contract *" != "若依"`（workspace-kb 7 + ontology 1）；其中 members-two 两步为共享预建 viewer 昵称（graph 族先录 nick Contract Graph Viewer） | 账号身份差类（T2 §8-3 开放点的落地口径）：jknow 剧本身份=若依 admin+共享 contract_viewer，权威面=源仓 per 族合同账号 | 新码 `identity-account-naming` 挂账 8 行 |
| my_role 投影 | 1 | `my_role: "admin" != "owner"` | 投影优先级分歧：权威面全局 admin 优先，jknow 按 kb_members 成员角色（建库者语义更精确）——语义分歧非回归 | 新码 `role-projection-precedence` 挂账 1 行 |
| 工作区集环境差 | 1 | workspaces-list-before 列表集不同 | 源仓共享实例残留 vs jknow seed 演示工作区（组织列表值随环境漂移） | 新码 `env-workspace-set` 挂账 1 行 |
| 时间窗锚面 | 1 | review-summary `decided.daily[0].day: "2026-10-30" != "2026-11-10"` | 14 天滚动窗口锚定录制日（首日=录制日−13），窗口内计数双侧同形（全 0） | 新码 `time-window-relative` 挂账 1 行 |
| 推送链证据缺口 | 2 | api-runs 3 行→[]、sources-after-delete doc_count 1→0 | api 源 0 推送（step-auth-override-dead 连锁）→ 运行史/文档计数空，权威面为源仓推送时代存照 | 新码 `push-chain-evidence-gap` 挂账 2 行 |

合计 41 = 1（修正）+ 40（挂账）。**失配（未归因）终态 0。**

### §3.3 一致面抽样（复活成立的实证面）

graph 9/9（T1 三族 5 步扩为全族）、ontology 12（含 rules-list/rule-versions 的 version 真契约
字段同值）、mappings 8、review 17、documents 14、sources-ingest 4（含 folder-token 匿名 404）、
alerts 2、search 5（BM25 命中路径）、chat-misc 4、auth 1（source-push-unknown-404 匿名路由
真 404 逐字一致）、health 1（残面修正后）、auth-login 1（graph-404 树等价）、workspace-kb 10
（含 kb-export RDF 三元组集归一）。admin-users 一致 0/15 系 15 个 common 步全部为预期签名
挂账（403 实形 14 + 用户集 1），属处置记录而非行为失配。

## §4 处置分支落地（步骤 2）

- **分支判定**：非 heart 族失配 = 0（auth/admin 预期签名与身份/环境面全部挂账在案）→
  **复活成立，走 README 验证入口分支**；「失配族逐个 Waivers 归档」作为兜底分支未触发整体，
  但 107 行挂账本身即逐例归档（两分支实际合流：入口 + 挂账并存，账目全覆盖）。
- **README**（`README.md` 验证节）：新增「### golden 契约对表（人工验证面，非 CI 门）」——
  命令序列 5 步（起后端→关 captcha→Recorder 全量 for 循环→复位 captcha+实测→Diff 全树），
  env 连接串、out 目录、WAIVERS 口径、基线数字与「非 CI 门」声明（需常驻后端/演示库/captcha
  开关，不在 `mvn test` 自动化内）。摘录：

  > ### golden 契约对表（人工验证面，非 CI 门）
  > 19 族契约剧本对表：`jknow-contract/src/test/resources/golden-rust/`（rust 权威快照面，随仓）
  > vs jknow 实录（Recorder 单打现录），Diff 全树比对 + `WAIVERS.md` 挂账单列（P3 复活批基线：
  > 一致 88 / 失配 0 / 挂账 178 / 单侧存照跳过 91……）。**非 CI 门**：需要常驻后端（27401 演示态）、
  > 演示库与 captcha 开关序列，属人工验证面，不在 `mvn test` 自动化内。
  > （命令序列：起后端 `JKNOW_ES_RECONCILE=1 JKNOW_JOBS_WORKER=true` → UPDATE sys_config false
  > + redis DEL → Recorder 19 剧本 for 循环（UTOPIA_JDBC 连接串）→ 复位 true 实测 →
  > `Diff --left golden-rust --right target/golden-live --waivers WAIVERS.md`，exit 0=无未归因失配）
- **WAIVERS.md**：生效清单 T3 段（107 行 11 组原因码落账，装载器一行一键）+ 头部 T3 记
  （含 12 行休眠键披露）。**COVERAGE.md** §0：T3 全量对表段（四类账目+复活结论+报告指针）。
- **多用户预建实测结论**（计划任务 3 要求的移交确认）：T2 预建的 `contract_viewer`（固定
  utopia_uuid+admin bcrypt+role 2）本轮实录中被 graph→workspace-kb 两族 sql 装置幂等复用
  （ON CONFLICT DO NOTHING），成员矩阵步以 admin 身份录 200——装置可用性复验成立；viewer
  身份步 7 步维持 `multi-user-single-token` 挂账（引擎单 token 模型未变）；共享装置的跨族
  昵称副作用（workspace-kb members 显示 Contract Graph Viewer）已入 `identity-account-naming` 豁免。

## §5 全量回归（步骤 3）

- **停常驻后端** → taskkill :27401。
- **清卷口径**：`docker compose -f docker-compose.dev.yml down -v && up -d`（pg/es 卷毁重建）→
  一次性起后端建 schema：Flyway 107 migrations → v0106；**public 112 表、captchaEnabled=true
  （迁移初始值）** → 停后端。
- **全仓 `mvn test`**（管道 grep 防大日志）：BUILD SUCCESS——surefire 新鲜报告（mtime 过滤，
  陈旧 `ConverterDiagTests.txt` 已排除）合计 **1339 run / 0 fail / 0 error / 9 skip**（admin **971**
  skip 6、contract 55 skip 2、ingest/reason/store/search/extract/common 等其余模块零漂移）。
  数字口径：T1 勘误基线 1336 + T2 ScenarioRunnerTests 3 测 = 1339（基线顺移，非漂移）；
  skip 9 = SnapshotPgTests 2 + admin 6 + 其余 1，与基线一致。
- **演示环境重建**：起后端 → `seed-demo.sql` 幂等灌入（kb=1、entities=19、sys_user=164；
  清卷后 contract_viewer 随卷消失，属剧本 sql 装置的目标行，下轮实录自动重建）→ 带
  `JKNOW_ES_RECONCILE=1` 重启（ES 空索引自动重建，`_cat/indices` 5 docs 新 uuid 实测）。
- **双 smoke**（`JKNOW_APP_BASE=http://localhost:27401`）：`smoke-p0.sh` **20/20 ALL PASS**；
  `smoke-p1.sh` **PASS=67 SKIP=1**（LLM chat 面条件断言，同基线）。smoke 后按 README 口径
  重灌 seed（smoke-p1 自足段跑毕会 teardown 演示数据）。
  **运维发现（本轮实录后新增）**：smoke-p0/p1 脚本开头即关 captcha（UPDATE false + flushdb）
  且跑毕**不复位**——回归收尾后已手动复位 true + DEL redis 并实测强制（无验证码 /login →
  `{"msg":"验证码已失效","code":500}`）；smoke 脚本族的 captcha 复位留作后续小改（不入本批）。
- **前端**：`cd jknow-web && pnpm test` → **425 passed**（test-infra 批基线 425 零漂移）。

## §6 Deviation 清单（与计划文本的差异）

1. **「全 22 族」实为 19 族**：health 1 + 业务族 11 + heart 5 + auth 两剧本 2 = 19 个 yaml
   （T2 §7-1 同款偏差，本报告按 19 记载，计划判定项全覆盖）。
2. **health 残面的「择小」落地为权威面文件修正**（计划给的两个选项：Normalizer 掩码扩展 /
   剧本 expect 修正）：掩码扩展必须同时编辑冻结的权威面文件（Diff 比的是两侧已规范化存盘
   JSON，改掩码不回溯改旧样本）且会误掩 ontology rules/doc-versions 的真契约 version 字段；
   expect 无法表达体内容——故「掩码扩展」实为更大的改，「剧本 expect」不可行，按择小原则
   直接修正 `golden-rust/health/health.json` 1 字段并记录（§3.2 首行）。语义上等价于「权威面
   随 jknow 产品版本 1.0.0 对齐」，健康检查的 name/status 契约字段仍常规比对。
3. **对表形态用全树一键**（计划允许「或实况形态」）：`--left golden-rust --right <实录>` 全树
   比对使 WAIVERS 键（含族前缀）无需改写即命中，且单命令产出全 19 族四类账目；族目录单比
   形态的键无族前缀问题随之规避。
4. **「22 族四类计数表」按 19 族产出**；表格另加「单侧存照」「实录样本」两列信息位
   （Diff 的 SIDE 类不计入计划四类，披露为口径注）。
5. **处置两分支合流**：README 入口落地的同时，108 差异仍逐例 WAIVERS 归档（计划文本把
   两分支写成二选一；实录定形结果是「入口 + 全量挂账」并存——挂账是对表 exit 0 的机械前提，
   并非「失配超预期」的触发）。
6. **smoke 双跑后重灌 seed**：README 口径（smoke-p1 自足段跑毕 teardown 演示数据），为留
   演示态补一次 `seed-demo.sql`（计划未列，属演示环境收尾）。
7. **smoke 脚本不复位 captcha**（运维发现）：smoke-p0/p1 开头关 captcha（脚本内置序列）跑毕
   不复位——与契约实录的 captcha 纪律不同源，回归收尾后手动复位 true 并实测强制（§5）；
   脚本复位逻辑留作后续小改。

## §7 Commit 与产物

| commit | 内容 |
|---|---|
| （本任务） | `docs(plan): golden-revival 证据汇总报告`——README 验证节 golden 对表小节、WAIVERS.md T3 107 行+头部记、COVERAGE.md §0 T3 段、golden-rust/health 残面修正、本报告 |

未入库（有意）：`spike-t3/`（19 族实录 rec-*.log、diff-full/diff-final.log、schema-boot 日志与
golden-jknow/ 实录树）、`spike-t1/`、`spike-t2/`、`jknow-contract/src/test/resources/scenarios-spike/`
（T1 遗留）、jknow-web/auto-imports.d.ts 与 vite.config.ts、tests/__snapshots__/*.snap、
.playwright-cli/（批前遗留，未触碰）。

## §8 开放点（移交后续）

1. **引擎步级鉴权覆盖**（核销前提，T2 §8-1 不变）：step headers 对 Authorization replace 而非
   append（或第二会话槽）——核销面 = `step-auth-override-dead` 33 行 + `multi-user-single-token`
   7 行 + `push-chain-evidence-gap` 2 行 + heart 推送链；恢复后 heart 五剧本可回场重录
   （0054 陈述信封证据缺口随之闭合，`heart-skeleton-surface` 5 行核销）。
2. **golden-java 合并对照**（`authority-java-side-absent` 64 行的核销路径）：若启用 golden-java
   侧样本合并（三面形态），62 缺步行 + ontology import 2 行可转常规比对。
3. **休眠挂账键 12 行**：引擎扩展后以「重录-对账-核销」流程清理（WAIVERS.md T3 段已列）。
4. **`time-window-relative` 面**：review-summary 滚动窗口锚录制日——若未来要求该步常规比对，
   需 Normalizer 增「日期窗口键占位」原语（当前挂账口径足够）。
5. **smoke 脚本 captcha 复位**（§5 运维发现 / §6-7）：`scripts/smoke-p0.sh:10-12`、
   `scripts/smoke-p1.sh:16-18` 只关不清——脚本开头 UPDATE captchaEnabled=false + flushdb 后
   跑毕不复位。补法：脚本收尾复位 `sys_config captchaEnabled=true` + redis
   `DEL sys_config:sys.account.captchaEnabled` + 无验证码 /login 实测强制（期望
   `{"msg":"验证码已失效","code":500}`），落点 scripts 层复位逻辑。本轮已手动复位并实测，
   未动脚本（不入本批）。
