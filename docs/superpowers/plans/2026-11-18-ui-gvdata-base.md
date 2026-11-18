# UI 设计基座实现计划（与 gvdata 对齐 · 子项目 1）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** jknow-web 引入 gvdata 设计令牌与 anivia 组件样式体系——令牌层全站换肤（#2563eb 体系）+ 系统页深度对齐 + 业务页换肤打底。

**架构：** 令牌单源（tokens.scss）先于一切全局样式引入并驱动 element 主色；anivia 等 5 件组件样式原样迁入 `assets/styles/gvdesign/`，sidebar/theme/variables 以 gvdata 版为准合并；antd 蓝 9 处硬编码收敛到令牌引用。

**技术栈：** SCSS/CSS 变量 / element-plus 主题覆盖 / vite 样式链 / vitest（视觉无自动化，走查驱动）。

---

## 全局约束

1. 参照仓 `D:\workspace_com\gvdata` **只读**；产物在 jknow-web。
2. gvdesign/ 迁入件与 gvdata 原文件**零改写**（仅允许 import 路径适配，diff 可对账）；sidebar.scss/theme.scss/variables.module.scss 合并点逐条记录。
3. `.knowledge-page` scope 不破；tailwind utilities 与 anivia 类名零冲突（grep 验证）。
4. 闸门：pnpm test 419/typecheck/build、smoke-p0/p1、走查 console 零 error；明暗双主题两态走查。
5. 环境在跑：前端 80（vite.config baseUrl 临时 19080 不还原不提交）/后端 19080/compose 三容器；三演示副产物（auto-imports/vite.config/快照）不带。

## 侦察缺口（T1 首步探明并记录）

- [ ] jknow 主题色运行时源头：`LC_ALL=C grep -rn "handleThemeStyle\|409EFF\|409eff" jknow-web/src --include="*.ts" --include="*.vue" | head`（若依 vue3-ts 的主题定制入口——settings 抽屉 dynamicTheme/缓存 key）；找到后把源头色改 #2563eb。
- [ ] gvdata 侧 anivia.scss/theme.scss/global.scss/table-style-optimized.scss 的引入点：`LC_ALL=C grep -rn "anivia\|theme.scss\|global.scss\|table-style" /d/workspace_com/gvdata/gvdata-ui/src/main.* /d/workspace_com/gvdata/gvdata-ui/src/App.vue 2>/dev/null`（index.scss 只装配了 5 件——其余 4 件的引入位置决定迁移后的装配序）。

---

### 任务 1：令牌层 + 主题源头 + antd 蓝收敛

**文件：**
- 创建：`jknow-web/src/style/tokens.scss`（自 gvdata tokens.scss 原样拷贝）
- 修改：`jknow-web/src/main.ts`（引入序）
- 修改：主题色源头文件（侦察缺口 1 定位——settings store 或 settings.ts）
- 修改：`src/style/theme.scss`(1 处)、`src/style/utopia.scss`(5 处)、`src/style/utopia-pages.css`(3 处)——antd 蓝映射

- [ ] **步骤 1：侦察缺口两项探明**（结果记录进报告——主题源头文件路径与 gvdata 4 件引入点）。

- [ ] **步骤 2：tokens.scss 迁入**：`cp /d/workspace_com/gvdata/gvdata-ui/src/assets/styles/system/tokens.scss jknow-web/src/style/tokens.scss`；main.ts 引入序改为：element css → dark vars → `@/style/tokens.scss` → `@/assets/styles/index.scss` → tailwind → 业务三件套。

- [ ] **步骤 3：主题源头 #2563eb**：按侦察结果改运行时源头（若依惯例是 settings 配置色 + handleThemeStyle 生成内联 el light-N 色阶；jknow 无此入口则 tokens 静态覆盖已生效，仅在报告记录裁定"静态令牌为准"并验证 Settings 抽屉的主题选择不产生 #409EFF 回流——若有回流，把抽屉色板默认项/缓存 key 同步 #2563eb）。

- [ ] **步骤 4：antd 蓝收敛（9 处）**：三文件逐处映射——`#1677ff→var(--color-primary)`、`#0958d9/#1668dc→var(--color-primary-hover)`、对应浅底值→`var(--color-primary-soft)`；`LC_ALL=C grep -rn "#1677ff\|#0958d9\|#1668dc" jknow-web/src/style/` 归零。

- [ ] **步骤 5：验证**：`pnpm dev` 刷新 → 全站主色/链接/按钮已 #2563eb（截图：登录页+用户管理+图谱页 3 张存 docs/superpowers/plans/ui-walkthrough/t1-*.png）→ `pnpm test` 419 绿 + typecheck。

- [ ] **步骤 6：Commit**

```bash
git add jknow-web/src/style/tokens.scss jknow-web/src/main.ts jknow-web/src/style/theme.scss jknow-web/src/style/utopia.scss jknow-web/src/style/utopia-pages.css <主题源头文件>
git commit -m "feat(ui): gvdata 令牌层引入——tokens.scss 单源全站换肤(#2563eb)+主题源头对齐+antd 蓝 9 处收敛令牌（子项目 1/规格 2026-11-18-ui-gvdata-base）"
```

---

### 任务 2：组件样式层迁移 + 壳对齐 + 全链验收

**文件：**
- 创建：`jknow-web/src/assets/styles/gvdesign/{anivia,gvdata,btn,table-style-optimized,global}.scss`（原样迁入）
- 修改：`src/assets/styles/sidebar.scss`、`theme.scss`、`variables.module.scss`、`index.scss`（gvdata 版为准合并+装配序）、`element-ui.scss`（若 gvdata 版有实质差异一并合并）
- 修改：`main.ts`/`index.scss`（gvdesign 装配引入）

- [ ] **步骤 1：gvdesign 5 件迁入**（cp + import 路径适配；`diff` 逐件对账零改写）。

- [ ] **步骤 2：同名合并**：sidebar.scss(383)/theme.scss(378) 以 gvdata 版替换 jknow 若依版——先 diff 两版记录合并点（若依侧被 jknow 改过的行——kb-switcher 注入的 scoped 不在此文件、utopia.scss 的 .kb-switcher 块在业务三件套不受影响）；variables.module.scss 菜单色对齐（$base-menu-background #001529 及 light 态全套）；element-ui.scss 按 gvdata 版对齐（diff 记录）。

- [ ] **步骤 3：装配序**：index.scss 对齐 gvdata 装配（variables→mixin→transition→element-ui→sidebar→btn→gvdata）+ gvdesign 其余 4 件按侦察缺口 2 探明的 gvdata 引入位置等位引入（main.ts 或 index 尾部）；tokens 仍最先。

- [ ] **步骤 4：验证与走查**：`pnpm test` 419 + typecheck + `pnpm build:prod` 绿 → smoke-p0/p1 零退化 → 浏览器走查：
  - 并排对比：jknow 登录页/用户管理/字典 vs gvdata 同页（gvdata-ui 若能本地起则双开截图；起不了则截 gvdata 仓内截图/docs 参照或按 token 清单目验）——观感一致（主色/圆角/表格/按钮/侧栏 #001529）
  - 业务页换肤 3 张（图谱/检索/文库）
  - 明暗双主题两态（Settings 抽屉切换）
  - kb-switcher 取色跟随
  - console 零 error；截图入 docs/superpowers/plans/ui-walkthrough/t2-*.png（≥6 张）

- [ ] **步骤 5：Commit**

```bash
git add jknow-web/src/assets/styles jknow-web/src/main.ts docs/superpowers/plans/ui-walkthrough
git commit -m "feat(ui): anivia 组件层迁移+壳对齐——gvdesign 5 件原样迁入/sidebar·theme·variables 合并 #001529/装配序对齐（系统页深度对齐+业务页换肤打底，走查 6+ 截图）"
```

---

## 自检记录

- **规格覆盖度**：§1 令牌层（T1 步骤 2-4）/§2 组件层（T2 步骤 1-3）/§3 壳对齐验收四条（T2 步骤 4 含双主题与 kb-switcher）/风险两条（element 版本差走查兜底在 T2 步骤 4、冲突边界在全局约束 3）——全覆盖。
- **占位符扫描**：主题源头与 gvdata 4 件引入点两个未知已显式列为"侦察缺口"首步探明（有 grep 命令与裁定规则），非待定设计；antd 蓝 9 处有分布统计与映射规则。
- **类型一致性**：tokens.scss 路径/引入序/gvdesign 文件名全计划一致；#2563eb/#001529 与规格一致。
