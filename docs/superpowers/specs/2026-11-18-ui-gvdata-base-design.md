# UI 设计基座规格（与 gvdata 对齐 · 子项目 1/全站深度重造）

- 日期：2026-11-18
- 状态：已获用户批准（三节设计 OK；拆分方案"基座先行+分批重造"已拍板）
- 参照系：`D:\workspace_com\gvdata\gvdata-ui`（Vue3+element-plus 2.7.6+若依系壳，企业科技感 SaaS Light，令牌单源纪律）
- 范围声明：本规格仅覆盖**设计基座**（令牌层+组件样式层+壳对齐+业务页换肤打底）。知识业务页布局/组件形态重造为子项目 2..N（图谱/检索/对话/文库/本体/审查/设置·账户逐批，每批独立 规格→计划→执行→走查），不在本规格内。

## 决策记录

- 对齐深度：令牌+组件级深度对齐（用户拍板）；业务页最终形态=全站深度重造（分批兑现，本批只做换肤打底）。
- 拆分：基座先行，避免各族重造时令牌口径漂移。

## 第 1 节：令牌层

- **`jknow-web/src/style/tokens.scss`**：自 gvdata `src/assets/styles/system/tokens.scss` 原样迁入（130 行：`:root` 下项目自有 token——primary #2563eb/hover #1d4ed8/soft #eff4fe/glow、accent #06b6d4、gradient-brand、success/warning/danger 三态 ×base/soft/text、bg #f6f8fb、border、neutral-soft、ink 三级、cat 6 色板、radius card12/ctl8/full、shadow card/pop、transition 0.2s——加 element 覆盖 `--el-color-primary: var(--color-primary)` 及 light-3/5/7/8/9、dark-2 静态色阶）。
- **引入序**（main.ts）：element-plus css → dark vars → **tokens.scss** → assets/styles/index.scss → tailwind → theme/utopia 业务三件套（令牌先于一切全局与业务样式）。
- **运行时主题源头**：jknow 若依 settings 的 theme 色改 `#2563eb`（handleThemeStyle 生成的内联 el light-N 与 tokens 静态色阶一致——gvdata 注释明示的对齐关系）；主题面板（Settings 抽屉）保留可切换能力。
- **业务页 antd 蓝收敛**：`src/style/theme.scss`、`src/style/utopia.scss`、`src/style/utopia-pages.css` 中的 #1677ff/#0958d9 系硬编码色值映射为 `var(--color-primary)` / `var(--color-primary-hover)` 等令牌引用（语义等价替换：主色→primary、hover 深→primary-hover、浅底→primary-soft）；图谱/检索等页面内联色不动（留子项目 2..N 重造时处理）。

## 第 2 节：组件样式层迁移（anivia 体系）

- **迁入 `jknow-web/src/assets/styles/gvdesign/`**（自 gvdata 原样拷贝+import 路径适配）：`anivia.scss`(1162)、`gvdata.scss`(517)、`btn.scss`(99)、`table-style-optimized.scss`(123)、`global.scss`(164)。
- **同名合并（gvdata 版为准）**：`sidebar.scss`(383) 与 `theme.scss`(378)——jknow 的若依原版同名文件按 gvdata 版替换并 diff 记录合并点；`variables.module.scss` 菜单色对齐（`$base-menu-background: #001529`、light 态全套）；`index.scss` 装配序对齐 gvdata 的 index.scss（index→global→gvdesign 各件→btn→table→sidebar→theme→transition）。
- **冲突边界**：anivia 是全局 el 面向（系统页深度对齐）；utopia.scss/utopia-pages.css 保持 `.knowledge-page` scope 不破——业务页内 el 组件同步吃到 anivia 全局形态属**预期效果**（换肤打底）；tailwind utilities 与 anivia 类名零冲突（前缀体系不同，迁移时以 grep 验证）。
- **样式文件职责单一**：gvdesign/ 各件保持 gvdata 原职责切分，不合并不改写（可 diff 对账）。

## 第 3 节：壳对齐与验收

- 壳元素随层落地：侧栏深色 #001529（logo/选中/hover 按 sidebar.scss）、`.app-main` 背景 `--color-bg`、卡片 radius/shadow、Navbar/TagsView 形态；kb-switcher 取色链（sideTheme/isDark 计算）自动跟随——走查验证点。
- **验收标准**：
  1. 并排对比截图：jknow 系统管理三页 vs gvdata 同页（登录页/用户管理/字典）观感一致（主色/圆角/表格/按钮/侧栏）
  2. 业务页换肤截图 ≥3 张（图谱/检索/文库）：主色/背景/圆角已 gvdata 化，布局保留原设计
  3. `pnpm test` 419 全绿、typecheck/build 绿、smoke-p0/p1 零退化、走查 console 零 error
  4. 明暗双主题切换（Settings 抽屉）两态正常
- **不做**：业务页布局重造/组件重排/文案（子项目 2..N）；图标库更换；gvdata 后端任何改动。

## 风险与对策

- anivia 全局覆盖与 utopia.scss 特异性冲突：走查驱动逐点调和（scope 已隔离，冲突面预计集中在 el 组件在 .knowledge-page 内的形态差异——属预期，不调和）。
- element-plus 版本差（jknow 2.14.7 vs gvdata 2.7.6）：anivia 选择器基于 el 2.x 通用类名，跨小版本兼容性走查兜底；如遇 2.14 新结构类名失效，局部补选择器并记录。
