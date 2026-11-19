# 打磨+加固批实现计划（docs/legal+画布 fit+小项+V0106+暗态令牌）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 清偿全部打磨级遗留——docs/legal 两页重造+画布 fit padding+小项清扫+V0106 demo 只读角色+暗态令牌层。

**架构：** 前端三件照模板重造/适配（逻辑零动）；V0106 一条迁移建 demo 角色授 27 行知识菜单+9999 改绑+孤儿行通用清；暗态层 tokens.scss/tailwind.css 两处令牌源加 html.dark 覆盖档。

**技术栈：** Vue3 模板层 / sigma fitView / Flyway / CSS 变量暗档 / vitest / Testcontainers。

---

## 全局约束

1. 两页重造+小项逻辑零动铁律（script 仅 import/纯展示 computed）；令牌纪律零硬编码色。
2. MigrationTests 107；`pnpm test` 422 基线（受影响 spec 适配有账目）；双 smoke 零退化；走查独立 Playwright+DOM 断言。
3. 环境：前端 80（baseUrl 19080 保留不提交）/后端 19080（JKNOW_PG_PORT=15432）/compose 三容器/LLM 已配 DeepSeek（对话可真流验）；`LC_ALL=C grep -a`；截图存 `docs/superpowers/plans/polish-b/`。

---

### 任务 1：docs + legal 两页重造（631 行）

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/docs/index.vue`（514，模板层）
- 修改：`jknow-web/src/views/jknow/knowledge/legal/index.vue`（112，模板层+9 处硬编码色清零）

- [ ] **步骤 1：读实况定形**：docs 骨架（目录导航位/分节正文位/parts.tsx 引用位）；legal 骨架（条文列表/9 处硬编码定位）。
- [ ] **步骤 2：模板重造**：docs=PageHeader(产品文档)+目录卡（锚点导航 anivia 化）+正文卡；legal=PageHeader(法务信息)+条文卡；硬编码色全令牌化。逻辑零动。
- [ ] **步骤 3：验证**：422+typecheck 绿 → 走查（Playwright+DOM 断言：docs 目录锚点跳转+正文渲染；legal 条文）→ 截图 2 张 → console 零 error。
- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/views/jknow/knowledge/docs jknow-web/src/views/jknow/knowledge/legal docs/superpowers/plans/polish-b
git commit -m "feat(reskin): docs/legal 两页重造——目录/正文卡+条文卡（631 行逻辑零动，legal 9 处硬编码清零）"
```

---

### 任务 2：画布 fit padding + 小项清扫

**文件：**
- 修改：`jknow-web/src/domain/pages/graphCanvas.ts`（fitView 边距——图谱共用）
- 修改：`jknow-web/src/views/jknow/knowledge/ontology/schema-graph.tsx`（本体画布同修——若共用 graphCanvas 则零改，核实）
- 修改：`jknow-web/src/components/PageHeader/index.vue`（class 透传——若需要）
- 修改：`doc-viewer/index.vue`（mb-1 挂法）、`system/notice/ReadUsers.vue`+`tool/build/*`（旧蓝扫尾）、`admin/deployment-tab.vue`（重复类名）、`kb-settings/index.vue`（保存钮 primary）

- [ ] **步骤 1：画布 fit**：读 graphCanvas.ts 的 sigma 初始化与 fitView/refresh 调用——加 padding（如 `fitView({ padding: 0.15 })` 或 settings minCameraRatio 调整——以 sigma 版本 API 实况定）；图谱与本体前后截图对比（底缘节点完整）。
- [ ] **步骤 2：小项五件**：①PageHeader class 透传核实（Vue3 attrs 默认透传——若 `class="mb-1"` 直接生效则 doc-viewer 改挂法；不生效则组件加 `defineProps` 外的 attrs 落根元素核实）；②ReadUsers/tool-build `#409eff/#409EFF`→`var(--color-primary)`；③deployment 重复类名去重；④kb-settings 保存钮 `plain`→`type="primary"`（与 models-tab 卡语言对齐——先读 models-tab 现状择同款）；⑤14 档过滤条极窄视口核对（破了才动）。
- [ ] **步骤 3：验证**：422+typecheck 绿 → 画布 fit 前后截图 1 张 → `LC_ALL=C grep -rn "409eff\|409EFF" jknow-web/src --include="*.vue" | grep -v "// \|注释"` 归零 → console 零 error。
- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/domain/pages/graphCanvas.ts jknow-web/src/views/jknow/knowledge/ontology/schema-graph.tsx jknow-web/src/components/PageHeader jknow-web/src/views/jknow/knowledge/doc-viewer jknow-web/src/views/system jknow-web/src/views/tool jknow-web/src/views/jknow/knowledge/admin/deployment-tab.vue jknow-web/src/views/jknow/knowledge/kb-settings docs/superpowers/plans/polish-b
git commit -m "fix(polish): 画布 fitView 边距（图谱+本体底缘不裁）+小项五件清扫——mb-1 挂法/旧蓝归零/重复类名/保存钮 primary（422 绿）"
```

---

### 任务 3：V0106 demo 只读角色 + 孤儿行清理

**文件：**
- 创建：`jknow-admin/src/main/resources/db/migration/V0106__demo_readonly_role.sql`
- 修改：`jknow-admin/src/test/java/tech/seexun/jknow/MigrationTests.java`（107+断言群）

- [ ] **步骤 1：V0106 SQL**：

```sql
-- V0106__demo_readonly_role：演示访客专用只读角色（只授知识菜单，不授系统菜单）+孤儿行通用清
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
VALUES (3, '演示访客', 'demo', 3, '1', true, true, '0', '0', 'admin', now(), '只读演示角色——仅知识菜单（规格 2026-11-20-polish-harden §4）')
ON CONFLICT (role_id) DO NOTHING;
-- 列数以 V0000 sys_role DDL 实况对齐（20 列模板同前批）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 3, menu_id FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2299
ON CONFLICT DO NOTHING;
DELETE FROM sys_user_role WHERE user_id = 9999;            -- 解绑 role 2
INSERT INTO sys_user_role (user_id, role_id) VALUES (9999, 3) ON CONFLICT DO NOTHING;
DELETE FROM sys_role_menu WHERE menu_id NOT IN (SELECT menu_id FROM sys_menu);  -- 孤儿行通用清（V0103 (2,4) 在内）
```

- [ ] **步骤 2：MigrationTests**：MIGRATION_COUNT=107；断言：role 3 知识菜单区间=27/role 3 系统菜单（menu_id<2100）=0/9999 绑定=3 且无 2/全表 sys_role_menu 孤儿行=0/role 2 授权仍在（V0105 不回退）。
- [ ] **步骤 3：验证**：MigrationTests 2/2 绿（107 重放）→ 重启后端应用 V0106 → **demo 登录浏览器实证**：侧栏只见三分组无系统管理/监控/工具，图谱/检索/对话全通（对话真流——LLM key 已配）→ 截图 2 张 → smoke 双绿。
- [ ] **步骤 4：Commit**

```bash
git add jknow-admin/src/main/resources/db/migration/V0106__demo_readonly_role.sql jknow-admin/src/test/java/tech/seexun/jknow/MigrationTests.java docs/superpowers/plans/polish-b
git commit -m "feat(menu): V0106 demo 只读角色——仅知识菜单 27 行+9999 改绑+孤儿行通用清（demo 登录无系统菜单实证，107 重放绿）"
```

---

### 任务 4：暗态令牌层 + 总验收

**文件：**
- 修改：`jknow-web/src/style/tokens.scss`（尾部 html.dark 块）
- 修改：`jknow-web/src/style/tailwind.css`（同名令牌 --color-line-strong/--color-fill 暗档——两处令牌源同批）

- [ ] **步骤 1：tokens.scss html.dark 块**：

```scss
/* 暗态覆盖档（规格 §5）——亮态值不动，仅 html.dark 下覆盖 */
html.dark {
  --color-bg: #1a1a1a;
  --color-neutral-soft: #262626;
  --color-border: #3a3a3a;
  --color-ink: #e8e8e8;
  --color-ink-2: #a0a0a0;
  --color-ink-3: #6b6b6b;
  --color-primary-soft: rgb(37 99 235 / 24%);
  --color-primary-glow: rgb(37 99 235 / 28%);
  --color-success-soft: rgb(16 185 129 / 22%);
  --color-warning-soft: rgb(245 158 11 / 22%);
  --color-danger-soft: rgb(239 68 68 / 22%);
}
```

tailwind.css 的 `--color-line-strong/--color-fill` 同批加 `html.dark` 暗档（值以现亮值推暗一档——读现值定）。
- [ ] **步骤 2：走查**：暗态切换——知识页（图谱/检索/文库）自定义面翻暗无刺眼白块、徽章 soft 底可辨；亮态零回归截图。
- [ ] **步骤 3：总验收**：422+typecheck+build、MigrationTests 2/2、双 smoke、全链截图归档（demo 菜单/docs/legal/画布 fit/暗态两态≥8 张）→ `pnpm build:prod` 绿。
- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/style/tokens.scss jknow-web/src/style/tailwind.css docs/superpowers/plans/polish-b
git commit -m "feat(theme): 暗态令牌层——html.dark 覆盖档（bg/ink/border/soft 族，两处令牌源同批）+打磨批总验收全绿"
```

---

## 自检记录

- **规格覆盖度**：§1 两页（T1）/§2 画布 fit（T2 步骤 1）/§3 小项五件（T2 步骤 2）/§4 V0106+孤儿（T3）/§5 暗态（T4 步骤 1）/验收五条（各任务+T4 步骤 3）——全覆盖；"不做的事"维持归档。
- **占位符扫描**：sigma fitView 以版本 API 实况定（有读取指令）；sys_role 列数以 DDL 实况对齐（20 列模板同前批惯例）；暗档值具体给出。无 TODO/待定。
- **类型一致性**：role 3/9999 绑定口径全计划一致；`--color-*` 令牌名与 tokens.scss 一致；polish-b 目录四任务一致。
