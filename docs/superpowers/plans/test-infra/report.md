# test-infra 批证据汇总报告（任务 3）

日期：2026-11-22 ｜ 分支：feat/test-infra（71de6ba..本 commit） ｜ 定位：清卷全量回归复验 + T1/T2 证据归档收口（分支终审前置）

> 续任说明：本任务首任代理中途被切断（smoke-p0 口头通过但无书面证据、无 commit）。本报告由续任代理在**严格重清卷**后全链重验产生——T1 清卷证据不是引用前任数字，而是本次独立复跑的书面计数（日志留 `.superpowers/sdd/2026-11-21-test-infra/t3-*.log` 工作副本）。

---

## §1 T1 清卷空库全量回归（本次复跑书面计数）

**清卷流程**（README L124 口径）：`docker compose -f docker-compose.dev.yml down -v && up -d`（pg/es 卷毁）→ 一次性起后端建 schema（jar，含 V0104–V0106 全部迁移；Flyway `Successfully applied 107 migrations to schema "public", now at version v0106`）→ 跑毕即停 → 验收态：**public 112 表，organizations/workspaces/knowledge_bases/entities 全 0 行，sys_user=2（若依基线迁移自带），captchaEnabled=true（迁移初始值）**——无任何手工热身 SQL。

**全仓 `mvn test`（仓库根，清卷空库首跑，BUILD SUCCESS，Total 04:51 min，exit 0）**：

| 模块 | run/fail/err/skip |
|---|---|
| jknow-common | 6/0/0/0 |
| jknow-ingest | 62/0/0/1 |
| jknow-reason | 107/0/0/0 |
| jknow-store | 24/0/0/0 |
| jknow-search | 5/0/0/0 |
| jknow-extract | 109/0/0/0 |
| jknow-admin | 969/0/0/6 |
| jknow-contract | 47/0/0/2 |
| **合计** | **1329 / 0 / 0 / 9** |

与 T1（71de6ba）首跑逐模块一致；skip 9 均为既有 env 门控（admin 6 + ingest 1 + contract 2，同 T1 §3.2 归因）。注：简报原文口径为 `cd jknow-admin && mvn test`（969），本次取更强的全仓根口径（jknow-admin 为其子集），与 T1 的 1329 直接可比。

**受影响 3 测试类（T1 改动面复验）**：

- 全量跑内：GraphApiTests **7/7 绿**（清卷空库由自建 org 根行通过）、OntologyApiTests **6/6 绿**、GraphStoreTests **0 run**（类级 `assumeTrue` 守卫"共享库无带实体的示例库"，P1 期既有 env 门控，非本次改动面）。
- 单独跑（`mvn test -Dtest="GraphApiTests,OntologyApiTests,GraphStoreTests"`，按 T1 同款条件临时铺 1 个带 2 实体+1 事实的示例库使 GraphStoreTests 真执行，跑毕已删净）：

```
GraphApiTests      Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
OntologyApiTests   Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0
GraphStoreTests    Tests run: 8,  Failures: 0, Errors: 0, Skipped: 1   （skip=factEvidence 无证据行自守卫）
合计               Tests run: 21, Failures: 0, Errors: 0, Skipped: 1，BUILD SUCCESS
```

与 T1 §3.3 完全一致。跑后库终态与 T1 §六-3 记录同款（fixture f0/f1/f2 三行 + 测试残留 org 行 189 + 0 实体），临时示例库零残留。

## §2 T2 spike 判定结论摘要 + pipeline 关闭记录

详细版：[golden-spike.md](golden-spike.md)（bc7212a）。要点：

- **判定：值得复活**——三族 13 可比步逐字（规范化树等价）一致 **11/13（84.6%）**，差异 2/13 同根因（源仓 auth/login、auth/me 域设计性退役）；路由前缀/数据前提失配为 0，≥70% 阈值极大余量达成。Recorder/Diff 引擎零改动可用（若依 Bearer 经 `headers ${var}` + `capture body.token` 注入，25 步实录全绿）。
- 适配量级预估 **2–3 人日**（引擎 ~40 行若依 setup 模式+假 200 识别；22 剧本删 setup；auth 401 族 7 步与 login/me 2 步重写或挂账），建议独立立项「golden 复活」批，不阻塞本批收口。
- **golden-rust 权威面未随迁**——复活立项时须先把源仓 golden-rust 快照拷入对表（本批只随迁了 golden-java 侧）。
- golden 数据处置：**随仓保留**（03359ae：golden-java 253 快照 + 22 剧本，spike 态标注）；适配剧本/实录零适配对照等 spike 工作产物留盘 `spike-out/` 不入库（本报告日已清理删除，判定数据均在仓内 golden-spike.md 与 03359ae）。
- **pipeline 挂账关闭**：七类复跑 `Tests run: 69, Failures: 0, Errors: 0, Skipped: 0`（2026-11-21，jknow-admin），挂账失效关闭；contract 门控复跑 47/2S 与基线一致。

## §3 双 smoke（演示环境重建后，零退化）

演示环境重建（本次从清卷态全链重灌）：起正式后端（27401，`JKNOW_JOBS_WORKER=true`，JKNOW_DATA_DIR 钉仓库根 data/）→ `seed-demo.sql`（sys_user=164 / knowledge_bases=2 / entities=19）→ 带 `JKNOW_ES_RECONCILE=1` 重启（启动对账日志「全文索引是空的，从库里重建：5 条分块」，ES 索引 5 docs——README L91-92 成功标志）→ 双 smoke：

| 冒烟 | 结果 |
|---|---|
| `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` | **ALL PASS**（login + 11 读接口 + 8 日期面 = 20 PASS，exit 0） |
| `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh` | **ALL PASS（PASS=67 / SKIP=1）**，exit 0；SKIP=chat（JKNOW_LLM_API_KEY 未配，外部依赖非回归）；ES 检索面在重建索引上 PASS |

smoke-p1 内嵌 seed→断言→teardown 流程按预期执行；跑毕按计划重灌 seed-demo 并再以 `JKNOW_ES_RECONCILE=1` 重启后端。**交付态**：后端 27401 常驻（jobs worker 开）、164 用户/2 库/19 实体、ES 5 chunks、captchaEnabled=true、admin/admin123 可登录（smoke 已证）。

## §4 captchaEnabled 复位记录

状态流转全程：清卷后迁移初始值 **true** → smoke-p0/p1 脚本自足段自动置 **false**（DB+redis 双清，脚本设计）→ 本次收尾执行 `UPDATE sys_config SET config_value='true' WHERE config_key='sys.account.captchaEnabled'` + flush redis → **交付态 true**（T2 遗留"captchaEnabled 现态"挂账就此关闭）。

## §5 前端基线（防意外面，本批零前端改动）

`cd jknow-web && pnpm test`：**58 test files / 425 tests 全过**，exit 0（22.26s）——与 425 基线一致，无意外。

## §6 deviation 清单

1. **前任断点与续任重验**：前任代理 mvn test 与 smoke-p0 仅有口头回传（无书面证据、无 commit，工作区残留其进程污染）；本次以严格重清卷为起点全链独立复验，所有计数为本次书面证据。
2. **前任遗留进程**：一次性 schema 后端（27499）当时经 MSYS PID kill 未死透、演示后端（27401）未停——续任以 Windows PID（netstat 定位）taskkill 清理；清理前一次重启因 27401 端口被占报 `APPLICATION FAILED TO START`，清理后恢复。教训：Git Bash 下停 java 须用 Windows PID。
3. **D:\d\tmp\probe-golden 残留**（41K）：T2 探针 POSIX 路径在原生进程侧解析偏移所留，T2 报告"探针工作区已清理"漏此一处（仓库外不可见）；本次已删除。
4. **观察项（非回归）**：captchaEnabled=true 下裸 `POST /login`（不带 code/uuid）返回 500 `{"error":"Internal server error"}`——`AppExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜底优先于若依 GlobalExceptionHandler 同名处理（AppExceptionHandler.java L140/L151 注释明示设计），`CaptchaExpireException` 被兜进 500。真实用户路径（前端 GET /captchaImage 取码后带 code 登录）不受影响；本批零后端源码改动，该行为为分支前既有。是否需要把若依 ServiceException 族从兜底中分流（回若依 `{"msg","code"}` 形状），建议另立小账。
5. **运行时污染文件（永续不入库清单）**：`jknow-web/auto-imports.d.ts`、`jknow-web/vite.config.ts`、`jknow-web/tests/__snapshots__/*.snap`、`jknow-web/src/docs/ingest.md`、`jknow-web/src/docs/mcp.md`（后两者为 CRLF 行尾差异，内容无实变）、`.playwright-cli/`——均为工具链运行覆写，未提交；`spike-out/` 本次已删除。
6. **口径裁量**：清卷 mvn test 取全仓根（1329）而非简报原文 `cd jknow-admin`（969），前者覆盖后者且与 T1 §3.2 可比。

## §7 批收口

三任务证据链齐备：T1（71de6ba，热身自足化+清卷首跑 1329/0/0/9）→ T2（03359ae+bc7212a，spike 判定值得复活+pipeline 69/69 关闭）→ T3（本报告，清卷复验 1329/0/0/9 + 3 类 21/0/0/1 + 双 smoke 全绿 + 前端 425 + 演示环境重建交付）。分支具备终审条件。
