# 测试基建批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 清偿测试基建三项——热身 SQL 测试自足化（清卷空库首跑全绿）、pipeline 挂账关闭（实测已绿）、golden 复活可行性判定（bounded spike 出结论）。

**架构：** 三任务：T1 三测试 fixture 补自建前提（org 根行/General 库链）；T2 拷入源仓 golden+scenarios 数据、用 contract 模块 Recorder/Diff 工具打 jknow 实录抽样对比出判定；T3 清卷全量回归+报告。纯测试面，零产品码改动。

**技术栈：** JUnit5 @BeforeAll fixture、jknow-contract Recorder/Diff CLI（exec.args）、docker compose 清卷。

**规格：** `docs/superpowers/specs/2026-11-21-test-infra-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：后端 27401/前端 24110（读 /tmp/jknow-ports.txt）；Docker compose 栈 pg 15432/redis 36379/es 9200。后端起法：`JKNOW_PG_PORT=15432 SERVER_PORT=27401 JKNOW_DATA_DIR=/d/workspace_com/jknow/data java -jar jknow-admin/target/jknow-admin.jar`（先 `mvn install -q -DskipTests -pl jknow-admin -am` 若 jar 过期）。
- 源仓（golden 数据来源）：`/d/workspace_com/utopia-java/`（golden-java/ 331 快照、scenarios/ 22 剧本）。
- 永不提交：jknow-web/auto-imports.d.ts、jknow-web/vite.config.ts、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md。
- 分支：`feat/test-infra`（自 master 切出）。

---

### 任务 1：热身 SQL 测试自足化（§1）

**文件：**
- 修改：`jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/api/GraphApiTests.java:30-49`（@BeforeAll fixture）
- 修改：`jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/api/OntologyApiTests.java:32` 附近（同款 @BeforeAll）
- 修改：`jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/store/GraphStoreTests.java:141` 附近（General 库前提）
- 修改：`README.md`（验证节热身段落改指向自足）

- [ ] **步骤 1：GraphApiTests/OntologyApiTests 补 org 根行自建**

两测试 @BeforeAll 现有 `SELECT id FROM organizations ORDER BY created_at LIMIT 1`（0 行即失败）。在该 SELECT 前补自建（沿用 p3 热身 SQL 固定 uuid，幂等）：

```java
// 源仓注册流遗产自足化：清卷空库无 organizations 根行，测试自建（p3-report §五-1 固定 uuid）
jdbc.update("""
    INSERT INTO organizations (id, name, created_at)
    VALUES ('0190d3e0-0000-7d5a-8000-0000000000f0', '默认组织', '2026-10-01T00:00:00Z')
    ON CONFLICT (id) DO NOTHING
    """);
```

（两测试若共用基类则改基类一处；GraphApiTests @BeforeAll 已有后续 INSERT workspaces/kb 链不动——它们挂在查得的 orgId 下，自建后自然取到固定 org。若两测试事务/清理机制会互删该行，按实况把 INSERT 放到 SELECT 后的 0 行分支内——即查不到才插、查到复用累积库行，语义不变。）

- [ ] **步骤 2：GraphStoreTests 补 General 库链自建**

:141 `SELECT id FROM knowledge_bases WHERE name = 'General'` 前补三行链（同款 ON CONFLICT DO NOTHING 幂等；固定 uuid 取 p3 热身 SQL 原值 f0/f1/f2）：

```java
// emptyKbOverview 前提自足：General 库三行链（org→workspace→kb），清卷空库直跑
jdbc.update("INSERT INTO organizations (id, name, created_at) VALUES ('0190d3e0-0000-7d5a-8000-0000000000f0', '默认组织', '2026-10-01T00:00:00Z') ON CONFLICT (id) DO NOTHING");
jdbc.update("INSERT INTO workspaces (id, org_id, name, created_at) VALUES ('0190d3e0-0000-7d5a-8000-0000000000f1', '0190d3e0-0000-7d5a-8000-0000000000f0', '默认工作区', '2026-10-02T00:00:00Z') ON CONFLICT (id) DO NOTHING");
jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility, created_at) VALUES ('0190d3e0-0000-7d5a-8000-0000000000f2', '0190d3e0-0000-7d5a-8000-0000000000f1', 'General', 'restricted', '2026-10-02T00:00:01Z') ON CONFLICT (id) DO NOTHING");
```

（若该测试 @BeforeAll 结构与 GraphApiTests 同族可共用；空库断言 emptyKbOverviewAndSearchAreZero 的语义是"空 kb 的 overview 计数为零"——General 库本身无实体节点，不破坏断言语义，实施时跑通即证。）

- [ ] **步骤 3：清卷空库首跑验证（核心验收）**

```bash
cd /d/workspace_com/jknow && docker compose -f docker-compose.dev.yml down -v && docker compose -f docker-compose.dev.yml up -d && sleep 10
cd jknow-admin && mvn test 2>&1 | grep -E "Tests run:.*Failures|BUILD" | tail -5
```
预期：BUILD SUCCESS，全仓 0 fail（skip ≤11 为既有 env 门控 9+2）——**无任何手工热身 SQL 前置**。同时受影响 3 测试类单独计数记录进报告。

- [ ] **步骤 4：README 热身段改写**

README.md 验证节"共享库热身态"段：改为一句"测试已自足（test-infra 批起 fixture 自建前提），清卷空库可直接 `mvn test`"——保留 p3 报告链接作历史注记。

- [ ] **步骤 5：Commit**

```bash
git add jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/api/GraphApiTests.java jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/api/OntologyApiTests.java jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/store/GraphStoreTests.java README.md
git commit -m "test(admin): 热身 SQL 测试自足化——org 根行/General 库链 fixture 自建，清卷空库首跑全绿"
```

---

### 任务 2：pipeline 挂账关闭 + golden 可行性判定 spike（§2）

**文件：**
- 创建：`jknow-contract/src/test/resources/golden-java/`（源仓拷入，判定为"值得复活"才保留）
- 创建：`jknow-contract/src/test/resources/scenarios/`（同上）
- 创建：`docs/superpowers/plans/test-infra/golden-spike.md`（判定报告）

- [ ] **步骤 1：读 contract 工具源定 spike 工作流**

读 `jknow-contract/src/main/java/tech/seexun/jknow/contract/` 的 Cli.java/Recorder.java/Diff.java 头注与参数解析（Recorder 用法示例：`-Dexec.args="--target http://127.0.0.1:1516 --scenario scenarios/health.yaml --out golden/"`）。确定：(a) Recorder 打 jknow 后端（27401）录制新 golden；(b) Diff 对比源仓 golden-java 与新录目录的命令形态（或 Cli 的子命令集）；(c) 剧本 yaml 里鉴权头/token 如何带（jknow 若依 Bearer 与源仓 utopia auth 的差异点）。工作流与命令写进 spike 报告 §1。

- [ ] **步骤 2：拷入数据 + 抽样实录**

```bash
cp -r /d/workspace_com/utopia-java/golden-java jknow-contract/src/test/resources/golden-java
cp -r /d/workspace_com/utopia-java/scenarios jknow-contract/src/test/resources/scenarios   # 路径以源仓实况为准（find /d/workspace_com/utopia-java -maxdepth 2 -type d -name scenarios）
```

起后端（27401 在跑则复用；Recorder 需要的 seed 数据若缺按 smoke-p0 口径补）。抽样实录：graph/ontology/auth-login 三场景族各 ≤5 step（剧本 yaml 按场景名选），Recorder 录到 `spike-out/`（工作区临时目录，不入库）。

- [ ] **步骤 3：对比与归因**

Diff（或等效对比）源仓 golden-java vs spike-out 实录。逐例归因分类计数：①鉴权包装差异（若依 401/包装 JSON）②路由前缀变化 ③字段演进（新增/删除/改名）④数据前提缺失 ⑤一致 ⑥其它。数字与逐例明细进 spike 报告 §2-3。

- [ ] **步骤 4：判定与处置**

- **值得复活**（≥70% 失配落在 1-2 类、适配量级可控）：报告写明规模预估（normalizer/剧本改多少处），数据拷入保留（步骤 2 的 git add 单独 commit 标注 spike 态），后续立项建议。
- **寿终**（结构性分散）：报告记录结论与证据；`git checkout -- jknow-contract/src/test/resources/`（或 rm 拷入目录）回退不留死数据。
- contract 模块门控关闭态复跑：`cd jknow-contract && mvn test`（47/2S 基线一致）。

- [ ] **步骤 5：报告+Commit**

spike 报告存 `docs/superpowers/plans/test-infra/golden-spike.md`（§1 工作流/§2 抽样数字/§3 归因表/§4 判定+建议；附 pipeline 69/69 关闭记录段）。commit（值得复活分支含数据拷入；寿终分支仅报告）：

```bash
git add docs/superpowers/plans/test-infra/golden-spike.md
# 值得复活分支另加：
# git add jknow-contract/src/test/resources/golden-java jknow-contract/src/test/resources/scenarios
git commit -m "docs(test): golden 可行性判定报告+pipeline 挂账关闭（spike 结论见报告）"
```

---

### 任务 3：清卷全量回归与证据汇总（分支终审前置）

- [ ] **步骤 1：全量验证链**

清卷态复跑（承接 T1 步骤 3 的清卷库，或再 down -v/up -d 一次取最严口径）：`cd jknow-admin && mvn test`（全绿）；`cd jknow-web && pnpm test`（425 基线——本批零前端改动，防意外）；双 smoke：`JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` 与 smoke-p1.sh（零退化；注意 smoke-p1 的 seed 自足段会 teardown 演示数据——跑完如需演示环境重灌 seed-demo）。

- [ ] **步骤 2：报告与证据归档**

`docs/superpowers/plans/test-infra/report.md`：T1 清卷前后对比（首跑结果+3 测试类计数）、T2 spike 判定结论摘要+pipeline 关闭记录、deviation 清单、golden 数据处置结果。commit `docs(plan): test-infra 证据汇总报告`。
