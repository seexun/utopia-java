# 业务页重造·首批实现计划（检索+文库，批次 1）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 检索/文库两页照 gvdata styleGuide 范式（PageHeader+卡容器）重造为 anivia 观感——逻辑零动纯模板层；graphVisuals 双蓝顺手修；沉淀"列表型页重造模板"。

**架构：** PageHeader 组件（48 行）零改写迁入；两页模板层重写（数据流/权限/写动作全保留）；文库页装配 table-style-optimized.scss（首个消费点）。

**技术栈：** Vue3 SFC / element-plus / anivia 卡片形态（CSS 变量令牌）/ vitest。

---

## 全局约束

1. 参照仓 gvdata 只读；PageHeader 零改写迁入（import 适配除外，diff 对账）。
2. **逻辑零动**：检索页 useQuery/地址栏语义、文库页权限闸/上传/删除/拖放/来源选择——只动 `<template>` 与纯样式；script 改动仅限 import（PageHeader/图标）与必要的纯展示 computed（如统计聚合）。
3. 令牌纪律：新样式只引 `--color-*`/`--radius-*`/`--shadow-*` 变量，禁硬编码色值（cat 色板例外——按令牌名引用 `--cat-N`）。
4. 闸门：pnpm test 419/typecheck/build、console 零 error；走查截图 vs uiKit 范式对照。
5. 环境：前端 80（baseUrl 19080 保留不提交）/后端 19080/compose 三容器/seed 演示库在；`LC_ALL=C grep -a`。

---

### 任务 1：PageHeader 迁入 + 检索页重造 + graphVisuals 双蓝

**文件：**
- 创建：`jknow-web/src/components/PageHeader/index.vue`（自 gvdata 零改写迁入）
- 修改：`jknow-web/src/views/jknow/knowledge/search/index.vue`（模板层重造，156 行）
- 修改：`jknow-web/src/domain/pages/graphVisuals.ts`（RING_SELECT 1 行）

- [ ] **步骤 1：PageHeader 迁入**：cp gvdata 件 → diff 对账（仅 import 适配若有）；读组件确认 props 面（title/subtitle/主行动插槽——以 48 行实况为准，报告记录 props 契约）。

- [ ] **步骤 2：graphVisuals 双蓝**：`RING_SELECT` 常量从 `"#1677ff"` 改为与 node-core/edge-focus 同法的 `--color-primary` 读取（读 graphVisuals.ts 现有 getVar 用法照抄）。

- [ ] **步骤 3：检索页模板重造**（script 逻辑零动）：
  - 根：`.knowledge-page` 保留（scope 依赖）→ 内部改 `PageHeader(:title="S.nav.search" :subtitle="kb 名")` + 内容列
  - 查询卡：`div.card`（anivia 卡类，以 uiKit 实况类名为准——styleGuide 数据源示例页的卡片用法照抄）内 el-input（大号、prefix 图标、focus 态 `--color-primary-glow`）+ 主行动按钮
  - 结果卡：v-for 每条 `div.card`——文档名（ink 标题阶）+段位徽章（`--color-primary-soft` 底/`--color-primary-text` 字）、引文高亮（命中词 mark 形态）、来源徽章 `--cat-N` 轮转、时间戳 ink-3
  - 空态/加载/错误三态：anivia 空态形态（图标+文案+引导链接保留 #313 语义）
  - 分页：el-pagination，table-style 分页条形态

- [ ] **步骤 4：验证**：`pnpm test`+typecheck 绿 → 浏览器走查（seed 数据真查"星辰智造"：命中高亮/卡片形态/分页）→ 截图 2 张（重造后全景+vs uiKit 范式对照）存 `docs/superpowers/plans/reskin-b1/` → 图谱页选中节点截图 1 张（环与节点主色一致）→ console 零 error。

- [ ] **步骤 5：Commit**

```bash
git add jknow-web/src/components/PageHeader jknow-web/src/views/jknow/knowledge/search/index.vue jknow-web/src/domain/pages/graphVisuals.ts docs/superpowers/plans/reskin-b1
git commit -m "feat(reskin): 首批·检索页照 styleGuide 范式重造——PageHeader 迁入+查询/结果卡 anivia 形态+graphVisuals 双蓝收口（逻辑零动）"
```

---

### 任务 2：文库页重造 + 首批验收

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/library/index.vue`（模板层重造，762 行）
- 修改：`jknow-web/src/style/theme.scss` 或页内（`table-style-optimized.scss` 装配引入——首个消费点）

- [ ] **步骤 1：装配 table-style-optimized**：在 theme.scss 尾部（re-assert 组旁）`@use '../assets/styles/gvdesign/table-style-optimized.scss';`（或 index.scss 对齐 gvdata 装配位——以 gvdata 实况引入点为准）。

- [ ] **步骤 2：文库页模板重造**（script 逻辑零动，纯展示 computed 允许）：
  - `PageHeader(title=文库, 主行动=上传按钮[canWrite 闸原样])`
  - 统计卡条：文档数/分块数/来源数——数据从现有页数据聚合（如 documents page 的 total、sources 列表长度、分块和；无端点的项以列表侧聚合实现，报告记录口径）
  - 来源侧栏卡：sources 列表（anivia 列表项、选中 `--color-primary-soft`）
  - 文档列表卡：el-table（table-style-optimized 形态；列：名称/来源/分块/大小/时间/操作——列集以现有页实况为准不增删）
  - 拖放上传区：虚线卡（`--color-border` 虚线、拖入 `--color-primary-glow`）+ 上传交互零动
  - 抽屉/确认框：el 原生+anivia 形态自动跟随

- [ ] **步骤 3：验证**：`pnpm test`+typecheck+build 绿 → 走查：文库页全景截图+范式对照截图 → **功能回归**：上传测试文件→列表出现→进详情→删除（回收纪律）→ 检索页复验（T1 成果不回归）→ console 零 error → smoke-p0/p1 零退化。

- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/views/jknow/knowledge/library/index.vue jknow-web/src/style/theme.scss docs/superpowers/plans/reskin-b1
git commit -m "feat(reskin): 首批·文库页重造——PageHeader+统计卡条+来源侧栏+table-style 首装配+拖放卡（762 行逻辑零动，上传/删除回归绿）"
```

---

## 自检记录

- **规格覆盖度**：§1 模板件（T1 步骤 1-2）/§2 检索页（T1 步骤 3-4）/§3 文库页（T2 步骤 1-2）/验收四条（T1 步骤 4+T2 步骤 3）/模板沉淀（两 commit 信息+注释约定）/不做的事（约束与步骤内体现）——全覆盖。
- **占位符扫描**：PageHeader props 契约与 anivia 卡类名两处以"实况为准+报告记录"处理（有参照文件路径与照抄指令，非待定）；统计口径给了聚合实现规则。无 TODO/待定。
- **类型一致性**：`--color-primary-glow/soft/text`、`--cat-N`、`--radius/shadow` 均为 tokens.scss 真实令牌名；PageHeader 路径与 import 一致；reskin-b1 截图目录两任务一致。
