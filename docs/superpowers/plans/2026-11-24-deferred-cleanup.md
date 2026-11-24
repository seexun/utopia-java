# 留档清偿批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 三项留档清偿——图谱行2 边语义图例收弹层（净高回补 ~50px）、本体/审查页同款页头合一、nodeBudget 压测演示库独立种子。

**架构：** 四任务：T1 图谱卡头行2 弹层化（模板改造）；T2 本体/审查两页 PageHeader 删除+标题并入卡头（模板改造）；T3 独立 SQL 种子脚本+README 用法；T4 回归汇总。

**技术栈：** Vue3 SFC + el-popover；SQL generate_series 幂等种子；独立 Playwright 走查。

**规格：** `docs/superpowers/specs/2026-11-24-deferred-cleanup-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：后端 27401/前端 24110 在跑；登录 admin/admin123（验证码算术题人读后填）。
- 图谱页直达 `http://localhost:24110/kb/0190d3e0-0000-7d5a-8000-000000000001/graph`；本体页同 kb 换 `/ontology`；审查页换 `/review`。
- 走查纪律：独立 Playwright + DOM 断言先行；截图存 docs/superpowers/plans/deferred-cleanup/。
- 永不提交：jknow-web/auto-imports.d.ts、jknow-web/vite.config.ts（本地 baseUrl=27401 环境态，若 M 不要 add）、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md。
- 分支：`feat/deferred-cleanup`（自 master 切出）。

---

### 任务 1：图谱行2 边语义图例收弹层（§1）

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（卡头行2 :1316 起区域）

- [ ] **步骤 1：边语义图例收 el-popover**

行2（:1316 `<!-- 行2：搜索… -->` 容器）内，原无边框 inline 边语义图例块（:1530-1567 附近，`ml-auto flex flex-wrap items-center justify-end`，含 `S.graph.edgeLegend` 标题与五项）整体收进：

```html
<el-popover placement="bottom-end" :width="300" trigger="click">
  <template #reference>
    <button
      type="button"
      class="flex items-center gap-1.5 rounded-ctl border border-line px-2 py-1 text-aux text-ink-2 hover:bg-fill"
    >
      <GitCompareIcon width="12" height="12" />
      {{ S.graph.edgeLegend }}
    </button>
  </template>
  <!-- 原五项+标题整体移入（保留原 flex 排版，容器改 flex-col gap-1.5） -->
</el-popover>
```

（图标 import 按仓内 unplugin-icons 惯例补 `~icons/lucide/git-compare` 或近义图标；行2 右段结构变为 `[搜索…类型图例] … [弹层触发钮]`——**单行不折行**。el-popover 暗色随 EP 令牌自动。）

- [ ] **步骤 2：验证**

`cd jknow-web && pnpm typecheck && pnpm test`（425）。
走查（1280×亮暗）：DOM 断言——①行2 单行（卡头总高 ≤ ~64px，`getBoundingClientRect` 实测记录，对照上批 108px）；②触发钮在位、点开弹层五项完整；③画布容器 top 再上移（对照 132px 基准）；④弹层开态截图 `dc-01-popover-open.png`、合态 `dc-02-1280-light.png`、暗态 `dc-03-1280-dark.png`；console 零 error。

- [ ] **步骤 3：Commit**

```bash
git add jknow-web/src/views/jknow/knowledge/graph/index.vue
git commit -m "style(web): 图谱卡头行2 边语义图例收弹层——单行化回补画布净高约 50px"
```

---

### 任务 2：本体/审查页同款页头合一（§2）

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/ontology/index.vue`（:327 PageHeader + 左栏 aside 卡）
- 修改：`jknow-web/src/views/jknow/knowledge/review/index.vue`（:559 PageHeader + 过滤条卡）

- [ ] **步骤 1：本体页改造**

- 删 :327-342 `<PageHeader>` 块及 import（:57）；USegmented 块整体保留迁用。
- 左栏清单卡（`<aside class="card onto-rail …>`，约 :345）内顶部增标题行（在 `.onto-rail` 既有内距语义下）：

```html
<div class="flex items-center gap-2 border-b border-line px-3 pb-2 pt-2">
  <h1 class="text-title font-semibold text-ink">本体</h1>
  <span class="truncate rounded-full border border-line bg-fill px-2 py-0.5 text-aux text-ink-2">{{ kbName }}</span>
  <div class="ml-auto flex items-center">
    <!-- 原 USegmented（图/表）原样迁入：value/options/@change 零动 -->
  </div>
</div>
```

（h1 语义：本体页原 PageHeader h1 删除后此 h1 补位，保持单 h1；`本体` 字面量直接写或用 S.ontology.title 均可——与图谱页做法一致择一。aside 宽 w-64=256px 内塞 标题+chip+segmented 会溢出：**segmented 与标题谁让位以实测定**——若 w-64 放不下，备选方案：标题行放 aside 卡头，segmented 移到右侧主区图视图卡头/表格卡头右段（仍是"行右段"语义），报告记录选择。）

- [ ] **步骤 2：审查页改造**

- 删 :559 `<PageHeader>` 及 import；14 档队列过滤条所在卡的第一行加：

```html
<div class="flex items-center gap-2 px-3 pb-2 pt-2">
  <h1 class="text-title font-semibold text-ink">审查</h1>
  <span class="truncate rounded-full border border-line bg-fill px-2 py-0.5 text-aux text-ink-2">{{ kb?.name }}</span>
  <!-- 右段：若页内已有队列总计数现成变量则放 ml-auto，没有则不加（YAGNI） -->
</div>
```

（过滤条 UPill 行保持原样不动。）

- [ ] **步骤 3：验证**

`pnpm typecheck && pnpm test`（425）。
走查四截图（1280×亮暗×两页）：DOM 断言——两页无独立页头带、标题+kb chip 在卡头、本体 segmented 在位且点击切换视图正常（diagram↔table 各截一次或断言视图态）→ `dc-04-ontology-light.png`/`dc-05-ontology-dark.png`/`dc-06-review-light.png`/`dc-07-review-dark.png`；console 零 error。

- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/views/jknow/knowledge/ontology/index.vue jknow-web/src/views/jknow/knowledge/review/index.vue
git commit -m "style(web): 本体/审查页同款页头合一——标题+kb chip 入卡头，本体 segmented 迁行右段"
```

---

### 任务 3：nodeBudget 压测演示库种子（§3）

**文件：**
- 创建：`scripts/seed-nodebudget-demo.sql`
- 修改：`README.md`（验证节补用法一行）

- [ ] **步骤 1：写种子脚本**

先侦察（只读）：`docker exec jknow-pg-1 psql -U postgres -d jknow -c "\d entities"` 与 `\d knowledge_bases`、`\d kb_members` 核列；seed-demo.sql 里 admin 的 kb_members 写法与 uuid 风格。脚本结构（uuid 一律固定段式 `0190d3e0-0000-7d5a-8000-000000a0xxx`——全 hex 合法，与既有演示/热身段不撞）：

```sql
-- nodeBudget 档位演示库（独立脚本，不入默认 seed/smoke 链）：>150 实体使
-- 图谱页节点档位 ± 启用（档位 [150,300,600,1000]）。幂等：固定 uuid ON CONFLICT。
BEGIN;
INSERT INTO organizations (id, name, created_at) VALUES ('0190d3e0-0000-7d5a-8000-000000a0f0', '压测组织', '2026-11-24T00:00:00Z') ON CONFLICT (id) DO NOTHING;
INSERT INTO workspaces (id, org_id, name, created_at) VALUES ('0190d3e0-0000-7d5a-8000-000000a0f1', '…f0', '压测工作区', '2026-11-24T00:00:00Z') ON CONFLICT (id) DO NOTHING;
INSERT INTO knowledge_bases (id, workspace_id, name, visibility, created_at) VALUES ('0190d3e0-0000-7d5a-8000-000000a0f2', '…f1', '演示：档位压测', 'public', '2026-11-24T00:00:01Z') ON CONFLICT (id) DO NOTHING;
-- admin 入会（kb-switcher 可见；user_id 以 sys_user 实测为准，admin=1）
INSERT INTO kb_members (kb_id, user_id, role) SELECT '0190d3e0-0000-7d5a-8000-000000a0f2', 1, 'admin' ON CONFLICT DO NOTHING;
-- 170 实体（type_id NULL=未分类，graph 正常渲染灰点）
INSERT INTO entities (id, kb_id, canonical_name)
SELECT ('0190d3e0-0000-7d5a-8000-000000a1' || lpad(g::text, 3, '0'))::uuid,
       '0190d3e0-0000-7d5a-8000-000000a0f2',
       '压测实体-' || g
FROM generate_series(1, 170) g
ON CONFLICT (id) DO NOTHING;
COMMIT;
-- 清理段（需要时手动启用）：
-- BEGIN;
-- DELETE FROM entities WHERE kb_id='0190d3e0-0000-7d5a-8000-000000a0f2';
-- DELETE FROM kb_members WHERE kb_id='…a0f2';
-- DELETE FROM knowledge_bases WHERE id='…a0f2';
-- DELETE FROM workspaces WHERE id='…a0f1';
-- DELETE FROM organizations WHERE id='…a0f0';
-- COMMIT;
```

（uuid 构造段 `…a1||lpad(g,3)`：170→`a1001`~`a1170` 共 16 hex 合法；列名/可见性/成员表列以 `\d` 实测为准微调；BEGIN 内 ON CONFLICT 幂等。）

- [ ] **步骤 2：灌库+功能验证**

```bash
docker exec -i jknow-pg-1 psql -U postgres -d jknow < scripts/seed-nodebudget-demo.sql   # 跑两遍验证幂等
docker exec jknow-pg-1 psql -U postgres -d jknow -tc "SELECT count(*) FROM entities WHERE kb_id='0190d3e0-0000-7d5a-8000-000000a0f2';"   # 预期 170
```

独立 Playwright：图谱页（演示库）→ 左下 kb-switcher 切"演示：档位压测"→ DOM 断言 nodeBudget ± 均 enabled → 点 − → 画布节点数实减（前后节点计数或 statsText 变化记录）→ 截图 `dc-08-nodebudget.png`；切回原演示库正常。

- [ ] **步骤 3：README 用法行**

README 验证节补：`可选演示：灌 nodeBudget 档位压测库 —— docker exec -i jknow-pg-1 psql -U postgres -d jknow < scripts/seed-nodebudget-demo.sql（独立于 seed-demo，图谱页节点档位 ± 生效需 >150 实体）`。

- [ ] **步骤 4：Commit**

```bash
git add scripts/seed-nodebudget-demo.sql README.md
git commit -m "feat(scripts): nodeBudget 档位压测演示库种子（独立幂等脚本，>150 实体启用图谱节点档位）"
```

---

### 任务 4：全量回归与证据汇总

- [ ] **步骤 1：全量验证链**

`cd jknow-web && pnpm test && pnpm typecheck && pnpm build:prod`；双 smoke `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` + smoke-p1.sh（**跑毕复位 captcha**：UPDATE true + redis DEL + 复查——上批纪律）。压测库不影响 smoke（独立 uuid）。

- [ ] **步骤 2：报告与归档**

`docs/superpowers/plans/deferred-cleanup/report.md`：三节验收逐项（弹层开合高度对照、两页四截图、nodeBudget 前后节点数）、uuid 段说明、deviation 清单。截图一并 add。commit `docs(plan): deferred-cleanup 证据汇总报告`。
