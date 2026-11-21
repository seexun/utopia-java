# 全局 UI 规范校准实现计划（令牌/尺寸对齐+原生页走查）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 按用户提供的《若依管理系统全局 UI 主题与布局优化规范》做差距收口——8 令牌+2 圆角+4 尺寸校准+若依原生 17 页走查修残。

**架构：** 参数校准集中在 tokens.scss/tailwind.css/variables.module.scss 三令牌源+layout 组件联动；原生页修残优先公共层（anivia/theme/element-ui.scss），走查驱动 Playwright+DOM 断言按 §十四 10 条验收。

**技术栈：** SCSS/CSS 变量 / element-plus 色阶公式 / vitest / Playwright。

---

## 全局约束

1. 不改业务功能/接口/路由/权限/菜单结构（规范 §12.3 全条）；不引入新依赖。
2. 令牌三源同批同步（tokens.scss/tailwind.css/utopia.scss 垫片引用处）；暗档机制保留不破坏。
3. 尺寸改动的联动偏移逐处核对（fixed-header/app-main padding-top/折叠态/logo 区/kb-switcher 宽度链）。
4. 闸门：`pnpm test` 421 基线 + typecheck + build、双 smoke 零退化、走查 console 零 error、1920/2560 双档抽查。
5. 环境：前端 dev 随机端口（读 /tmp/jknow-ports.txt，baseUrl 临时指向不提交）/后端 JKNOW_DATA_DIR 指仓库 data；走查独立 Playwright；`LC_ALL=C grep -a`；截图存 `docs/superpowers/plans/ui-calib/`。

---

### 任务 1：令牌值校准（8 令牌+2 圆角+色阶重算+暗档联动）

**文件：**
- 修改：`jknow-web/src/style/tokens.scss`（:root 8 值+2 圆角+el 色阶 4 档重算；html.dark 联动核对）
- 修改：`jknow-web/src/style/tailwind.css`（同名令牌 @theme/card/fill/ink 族同步）
- 修改：`jknow-web/src/style/utopia.scss`（垫片旧字面量引用同步——grep 旧值定位）

- [ ] **步骤 1：新值清单落 tokens.scss**——8 令牌/2 圆角按规格表改；el 静态色阶重算（light-3/5/7/8 = #2563EB 与白按 N/10 混合：light-3=#6692f1（不变，同公式同主色）、light-5/7/8 重算核对；dark-2=#1d4ed8 不变）。**注意 radius-card 12→8 与 radius-ctl 8→6 的消费面**（.card 全局类/badge 圆角/各页 scoped 引用 var 处自动跟随，字面量 12px/8px 圆角残留 grep 收口）。
- [ ] **步骤 2：暗档联动核对**——html.dark 块中 soft 系透明度底色基于主色族不变；ink 系暗档独立调色不动；`grep` 暗档引用旧亮值字面量处同步。
- [ ] **步骤 3：tailwind.css/utopia.scss 同步**——同名令牌+垫片字面量；`grep -rn "eff4fe\|f6f8fb\|0f172a\|475569\|94a3b8\|e5eaf1" src/style/` 归零（旧值六串）。
- [ ] **步骤 4：验证**：421+typecheck 绿 → Playwright computed 抽样（背景 #f5f7fa/ink #1f2937/卡片 radius 8px）+亮暗切换 → 业务页抽验 2 张（检索+图谱）+系统页 1 张（用户管理）截图。
- [ ] **步骤 5：Commit**

```bash
git add jknow-web/src/style docs/superpowers/plans/ui-calib
git commit -m "feat(ui): 规范令牌校准——8 色值+2 圆角按 §三/§八 精确对齐（色阶重算+暗档联动+三源同步，旧值 grep 归零）"
```

---

### 任务 2：布局尺寸校准（侧栏 210/导航 54/标签 38/菜单 44-40 分档）

**文件：**
- 修改：`jknow-web/src/assets/styles/variables.module.scss`（$base-sidebar-width 200→210 + :export）
- 修改：`jknow-web/src/layout/components/Navbar.vue`（height 50→54 + line-height/logo 联动）
- 修改：`jknow-web/src/layout/components/TagsView/index.vue`（高 38 + 关联 padding）
- 修改：`jknow-web/src/assets/styles/sidebar.scss`（一级 44/二级 40 分档）
- 修改：布局偏移联动处（grep 50px/$base-sidebar-width 消费面——fixed-header/app-main/tags-views 计算引用）

- [ ] **步骤 1：消费面清单**——`LC_ALL=C grep -rn "base-sidebar-width\|50px\|34px\|35px" src/layout src/assets/styles --include="*.vue" --include="*.scss" | head -20` 列联动点再动手。
- [ ] **步骤 2：四尺寸改+联动**——按消费面清单逐处（含 main.ts 侧栏宽度 DOM style 设置处若有：`grep -rn "200" src/layout/index.vue`）。
- [ ] **步骤 3：验证**：421+typecheck → Playwright 走查：折叠/展开双态、菜单分档高 44/40、标签栏、kb-switcher 底部区跟随、移动端断点不破 → 截图 3 张（展开/折叠/标签栏）。
- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/layout jknow-web/src/assets/styles docs/superpowers/plans/ui-calib
git commit -m "feat(ui): 布局尺寸校准——侧栏 210/导航 54/标签 38/菜单 44-40 分档（联动偏移逐处核对，折叠/移动端不破）"
```

---

### 任务 3：原生 17 页走查修残（§十四 验收驱动）

**文件：**
- 修改：公共层优先（`src/assets/styles/gvdesign/anivia.scss` 不动[零改写纪律]→ 改 `src/style/theme.scss`/`src/assets/styles/element-ui.scss`/`ruoyi.scss`）
- 修改：个别页面微调（走查后定——预期：分页条/代码生成/表单构建/druid 外框）

- [ ] **步骤 1：走查脚本**——Playwright 独立实例按页集循环（登录/首页/系统管理 8/监控 6/工具 3/个人中心）每页：§十四 10 条核对（截图+console 断言+1920/2560 两档 viewport 抽查 4 页）→ 输出走查台账（每页 pass/issue 清单）。
- [ ] **步骤 2：按台账修残**——公共层能修的进公共层（分页按钮/表格 hover/对话框圆角等预期项）；页面级残留逐处最小修；台账外不动。
- ] **步骤 3：复走查**——issue 页全转 pass；业务页抽验 2 页零回归。
- [ ] **步骤 4：总验收**：421+typecheck+build、双 smoke、暗态切换抽查、截图归档（17+ 页）。
- [ ] **步骤 5：Commit**

```bash
git add jknow-web/src docs/superpowers/plans/ui-calib
git commit -m "feat(ui): 原生 17 页规范走查修残——公共层优先+页面级微调（§十四 10 条全过，1920/2560 双档，台账留档）"
```

---

## 自检记录

- **规格覆盖度**：§A 令牌（T1）/§B 尺寸（T2）/§C 走查（T3）/验收七条（T1 步 4+T2 步 3+T3 步 4）——全覆盖；不做的事在约束体现。
- **占位符扫描**：色阶公式/旧值六串 grep 口径/消费面清单先行指令均具体；走查修残"按台账定"是走查驱动的固有形态（台账本身是交付物）。无 TODO/待定。
- **类型一致性**：新令牌值全计划唯一口径（#EFF6FF/#F5F7FA/#1F2937/#6B7280/#9CA3AF/#E5EAF0/8px/6px/210/54/38/44-40）与规格表一致。
