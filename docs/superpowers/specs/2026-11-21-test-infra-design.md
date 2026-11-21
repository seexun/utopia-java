# 测试基建批规格（热身 SQL 测试自足化+pipeline 挂账关闭+golden 可行性判定）

- 日期：2026-11-21
- 状态：已获用户批准（三节设计 OK）
- 背景：性能批（de493d5）已合并；本批清偿账本最后一组——测试基建三项。本批完成后账本仅剩单项观察级挂账（Editor 按需注册等）。

## 侦察结论（规格依据）

1. **pipeline 7 测试挂账已失效**：本次实测 `mvn test -Dtest="...pipeline.*Tests"` 全绿——GithubIssues 9/GuardedHttp 19/HydrationMinor 5/JiraIssues 5/NotionConnector 16/ObjectStorage 11/SignatureVector 4 = **69/69 通过**（主代码 11 类在位、夹具齐、无 pom 排除）。挂账记录过时，关闭即可。
2. **热身 SQL 真身**（p3-report §五-1）：清卷空库首跑 3 例错——GraphApiTests/OntologyApiTests fixture `SELECT id FROM organizations ORDER BY created_at LIMIT 1` 0 行；GraphStoreTests.emptyKbOverviewAndSearchAreZero 缺 `name='General'` 库。源仓注册流遗产（jknow 无建行路径）。现靠 README 手工热身 SQL 三行（固定 uuid 幂等）。长期去向二选一（p3-report 注）：测试自足化 / V0103 产品种子化——**本批选测试自足化**（不动产品迁移链）。
3. **golden 实况**（p1#15）：runner=已随迁 jknow-contract 模块（SnapshotPg/SealInterop env 门控缺省跳过，47 测试 2 skip）；数据=源仓 `/d/workspace_com/utopia-java/golden-java` 331 快照（12+ 场景族目录）+scenarios 22 未拷入。快照形态：每 step 一 JSON（meta: scenario/step/method/path/target=both/expectedStatus/status + normalized）。

## 设计

### §1 热身 SQL 测试自足化（任务 1）

- 受影响 3 测试的 fixture 前提自建：
  - GraphApiTests / OntologyApiTests：setup（或类级 @BeforeAll/@Sql）插入 organizations 最小行（沿用 p3-report:95 热身 SQL 的固定 uuid，幂等写法 `ON CONFLICT DO NOTHING` 或先删后插——以现测试事务边界实况择一，报告记录选择）。
  - GraphStoreTests.emptyKbOverviewAndSearchAreZero：同法补 `name='General'` 库行。
  - 形态优先共享脚本：`jknow-admin/src/test/resources/db/warmup-fixtures.sql` + `@Sql` 引用；若三测试的持久化上下文（Testcontainers vs 共享库）不一致致 @Sql 不适用，则退为各测试 setup 内联插入（择小改）。
- 废除前置：README 验证节的"共享库热身态"段落改为指向测试自足（保留历史注记一句）。
- **验收**：`docker compose -f docker-compose.dev.yml down -v`（清卷）→ `up -d` → `cd jknow-admin && mvn test` **首跑全绿**（1330 基线+pipeline 69 含在总数内，0 fail；9+ skip 为既有 env 门控）——无任何手工 SQL 前置。

### §2 pipeline 挂账关闭 + golden 可行性判定 spike（任务 2）

- **pipeline 关闭**：报告记录本次实测 69/69 证据，账本项标注"已失效关闭"。
- **golden spike**（只判定、不全量适配、不修 normalizer）：
  1. 拷入源仓数据：`golden-java/`（331 快照）+ `scenarios/`（22）→ `jknow/jknow-contract/src/test/resources/`（或 contract 模块现行数据目录惯例位置——以 SnapshotPg 现代码的加载路径为准，实施时读 jknow-contract 源定位）。
  2. 开 env 门控（读 jknow-contract 源确定门控变量名与取值），试跑抽样：graph/ontology/auth-login 三场景族各 ≤5 例（共 ≤15）。
  3. 统计：通过/失配计数；失配逐例归因分类（预期类别：若依鉴权包装/路由前缀变化/字段演进/数据前提缺失/其它）。
  4. **判定分支**：
     - 失配集中可归因（≥70% 落在 1-2 类、normalizer/适配量级可控）→ 结论"值得复活"，规模预估（多少处适配）写明，后续单独立项。
     - 失配结构性分散（API 已实质分叉）→ 结论"golden 寿终"，正式归档（账本 p1#15 关闭）。
  5. spike 产出=判定报告（数字+归因表+结论+建议），**无论哪支都关账**；spike 引入的临时改动（env 开关、数据拷入）按判定结论处置：值得复活→数据留仓（单独 commit 标注 spike 态）；寿终→回退拷入（不留死数据）。
- **验收**：spike 报告完整（抽样数字+归因表+明确结论）；jknow-contract 现有 47/2S 基线不回归（门控关闭态复跑一致）。

## 总验收

1. 清卷空库 mvn test 首跑全绿（无热身前置）——§1 核心。
2. pipeline 挂账关闭记录 + golden spike 判定报告（含结论）。
3. 双 smoke 零退化；前端 425 基线不涉（本批零前端改动）。

## 不做的事

- golden 全量复活适配实施（按 spike 结论另立项或归档）；scenarios 22 只随拷不跑（与 golden 同判定处置）。
- 不选 V0103 产品种子化路线（不动产品迁移链）。
- 不动 pipeline 7 测试（已绿零工作）；不动前端。
