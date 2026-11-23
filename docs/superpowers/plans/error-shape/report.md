# error-shape 批证据汇总报告（任务 2）

日期：2026-11-23 ｜ 分支：feat/error-shape（HEAD `64c0a28`） ｜ 定位：停常驻后端→清卷全量回归复验 + T1 证据归档收口（分支终审前置）

> 本报告全部数字为本次**独立重跑**的书面计数（非引用转抄）：停常驻后端（本批 README 新纪律首次实操）→ docker compose down -v 清卷 → 一次性起后端建 schema → 验收态查询留档 → admin 模块 + 全仓两轮 mvn test → 演示环境重建 → 双 smoke → 前端 425。

---

## §0 批结论一览

| 闸门 | 预期 | 实测 | 判定 |
|---|---|---|---|
| 清卷验收态（public 表数） | 112 | **112** | ✅ |
| admin 模块 `mvn test` | 971（969 基线+新增 2） | **971 run / 0 fail / 0 err / 6 skip，BUILD SUCCESS** | ✅ |
| 全仓 `mvn test` | 1331（1329+2） | **1331 run / 0 fail / 0 err / 9 skip，BUILD SUCCESS** | ✅ |
| 形状测试（本批新增） | 2/2 | **2/2 PASS**（全量跑内，见 §2） | ✅ |
| smoke-p0 | ALL PASS | **ALL PASS**（20 PASS，exit 0） | ✅ |
| smoke-p1 | ALL PASS | **ALL PASS（PASS=67 / SKIP=1）**，exit 0 | ✅ |
| 前端 `pnpm test` | 425 | **425 passed**（58 文件，exit 0） | ✅ |
| 零代码改动 | 无 | 无（本任务未改任何产品代码） | ✅ |

---

## §1 T1 三形状实测摘要（转录自 task-1-report §二，本批回归未触碰）

改动前基线（旧 jar）→ 改动后（`64c0a28` jar）curl 实测：

| 面 | 改动前 | 改动后 |
|---|---|---|
| 未知路由（匿名）`GET /api/v1/nonexistent` | `HTTP 200 {"msg":"请求访问：…认证失败，无法访问系统资源","code":401}` | 同前（认证入口点先于路由，本批不涉） |
| 未知路由（已认证 admin）`GET /api/v1/nonexistent` + Bearer | `HTTP 500 {"error":"Internal server error"}` ← 要修的变形 | **`HTTP 404 {"error":"Not found"}`**（noRoute 扩注解命中，对齐 axum api.fallback） |
| 无码登录 `POST /login {"username":"admin","password":"admin123"}` | `HTTP 500 {"error":"Internal server error"}` ← 要修的变形 | **`HTTP 200 {"msg":"验证码已失效","code":500}`**（BaseException 族分流命中，若依 AjaxResult 惯例形状） |
| 带码正常登录（captcha dance 重放） | — | `code:200` + token（登录主链无回归） |

- **形状测试 2/2**：`AppExceptionHandlerShapeTests`（unknownRouteIs404NotJsonInternalError + ruoyiUserExceptionFamilyGoesBackAsMsgCode），T1 红→绿，本次全量跑内复验 2/2（§2）。
- **CaptchaExpire message 实测字面量**：`验证码已失效`（`i18n/messages.properties:4` `user.jcaptcha.expire`；响应逐字 `{"msg":"验证码已失效","code":500}`）。
- 403/405 既有 handler 零回归抽测（T1 §三）：403 形状 diff 逐字一致、405 空 body 同基线。

## §2 停常驻后端 + 清卷全量回归（本次复跑书面计数）

**流程**：taskkill :27401/:27499（本次起步时点 27401 无监听——Docker daemon 曾重启、jknow compose 栈未自起，taskkill 空操作，清卷语义不受影响）→ `docker compose -f docker-compose.dev.yml down -v && up -d`（pg/es 卷毁重建）→ 一次性起后端建 schema（T1 重建的 `64c0a28` jar，Flyway `Successfully applied 107 migrations to schema "public", now at version v0106`）→ 跑毕即停 → 验收态查询留档：

```
$ docker exec jknow-pg-1 psql -U postgres -d jknow -tc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
   112
```

（停常驻后端纪律执行记录：建 schema 用的 27499 临时后端在 mvn test 前 taskkill——JOBS_WORKER 不干扰测试计数，README L128 新纪律行首次实操通过。）

**admin 模块 `mvn test`（cd jknow-admin）**：

```
[INFO] Running tech.seexun.jknow.web.knowledge.err.AppExceptionHandlerShapeTests
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in tech.seexun.jknow.web.knowledge.err.AppExceptionHandlerShapeTests
[INFO] Tests run: 971, Failures: 0, Errors: 0, Skipped: 6
[INFO] BUILD SUCCESS
```

**全仓 `mvn test`（仓库根，BUILD SUCCESS，exit 0）**：

| 模块 | run/fail/err/skip |
|---|---|
| jknow-common | 6/0/0/0 |
| jknow-ingest | 62/0/0/1 |
| jknow-reason | 107/0/0/0 |
| jknow-store | 24/0/0/0 |
| jknow-search | 5/0/0/0 |
| jknow-extract | 109/0/0/0 |
| jknow-admin | 971/0/0/6 |
| jknow-contract | 47/0/0/2 |
| **合计** | **1331 / 0 / 0 / 9** |

- 与基线逐项对表：**1329+2=1331 全中**，admin 969+2=971 全中；skip 9 均既有 env 门控（admin 6 + ingest 1 + contract 2，归因同 test-infra report §1），零假失败——**停常驻后端纪律下无计数翻倍**（2026-11-22 教训未复现）。
- 13 模块 Reactor 全 SUCCESS（jknow-admin 03:52 min 为最长）。

## §3 演示环境重建 + 双 smoke

清卷态全链重灌：起正式后端（27401，`JKNOW_JOBS_WORKER=true`，JKNOW_DATA_DIR 钉仓库根 data/）→ `seed-demo.sql` 灌库零 ERROR → **带 `JKNOW_ES_RECONCILE=1` 重启**（README L82-84 口径；启动对账日志「全文索引是空的，从库里重建：5 条分块→索引重建完成」，ES 索引 5 docs——README 成功标志）→ 双 smoke：

| 冒烟 | 结果 |
|---|---|
| `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` | **ALL PASS**（login + 11 读接口 + 8 日期面 = 20 PASS，exit 0） |
| `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh` | **ALL PASS（PASS=67 / SKIP=1）**，exit 0；SKIP=chat（JKNOW_LLM_API_KEY 未配，外部依赖非回归）；ES 检索面在重建索引上 PASS |

跑毕处置：smoke-p1 自足段 teardown 了演示数据，已按计划**重灌 seed-demo 并再以 `JKNOW_ES_RECONCILE=1` 重启**。交付态：后端 27401 常驻（jobs worker 开，PID 21800）、admin/admin123 登录 code 200、ES 5 docs、captchaEnabled=true（正常态）。

## §4 前端基线

```
cd jknow-web && pnpm test
 Test Files  58 passed (58)
      Tests  425 passed (425)
（exit 0）
```

与 425 基线一致，零退化。

## §5 README 测试纪律行确认

README.md L128（"测试前提已自足"段后，`64c0a28` 新增）原文在位：

> 跑 `mvn test` 前先停常驻后端进程（taskkill :27401/:27499）——JOBS_WORKER 调度器会扫共享库并干扰测试的 scripted 模型计数（2026-11-22 实测：计数翻倍 3-5 例假失败）。

本任务步骤 1 即按此纪律实操（见 §2），纪律有效性得证（1331 无假失败）。

## §6 与简报的偏差（全部记录）

1. **起步环境实况**：本任务启动时 27401 无监听、jknow compose 栈整体未起（Docker daemon 曾重启，栈未自启；jar 为 T1 重建版在位）。步骤 1 的 taskkill 为空操作、`down -v` 幂等处理缺位栈——清卷语义不变，流程照简报走完。
2. **ES 对账重启需 `JKNOW_ES_RECONCILE=1`**：简报步骤 2 口径「重启后端（ES 对账）」但协调者 env 行未带该变量；首轮重启后 ES 0 索引，按 README L82-84 + p3-report/test-infra report 既有口径补带 flag 重启（启动对账日志与 README 成功标志逐字对上）。检索路径已无逐查对账（ensureFresh 撤为启动 runner），不带 flag 检索面必空——**后续演示重建 env 必须含 `JKNOW_ES_RECONCILE=1`**。
3. **mvn 日志采用重定向而非简报建议的管道过滤**：admin/全仓日志各 137MB（重演上批 143MB 教训），提取汇总行后均已删除（未入库、未长期占用）；后续全量跑建议直接 `mvn test 2>&1 | LC_ALL=C grep -aE "Tests run|BUILD"`。
4. **演示态计数与上批记录不同**：sys_user=325 / knowledge_bases=1（test-infra report 记 164 / 2）。归因：库为 Flyway 107 迁移自带种子+seed+smoke 自然态（上批数字系旧 schema 版本+含 p1-smoke 残留库时的快照）；本批 demo 登录 code 200、双 smoke 对该库全绿、ES 5 docs——演示可用性以本批实测为准，非回归。
5. **临时后端建 schema 期间的既有噪音**（非本批引入，记录备考）：druid StatFilter 对 PG 专有 SQL（`LISTEN`/`CREATE INDEX CONCURRENTLY`）mergeSql 解析 ERROR；个别 Flyway 种子 job「document not found」backoff。

## §7 完成判定对照（计划任务 2）

- [x] 步骤 1 停常驻后端 + 清卷 + 112 表验收态（查询输出留档 §2）
- [x] 步骤 1 admin 模块 mvn test 971（969+2，BUILD SUCCESS）
- [x] 步骤 1 全仓 mvn test 1331（1329+2，BUILD SUCCESS，逐模块对表）
- [x] 步骤 2 演示环境重建（seed + `JKNOW_ES_RECONCILE=1` 重启，ES 5 docs）
- [x] 步骤 2 双 smoke 全绿（p0 ALL PASS / p1 PASS=67 SKIP=1）
- [x] 步骤 2 前端 pnpm test 425
- [x] 步骤 3 本报告归档 + 定向 commit（无代码改动、无禁忌文件）
