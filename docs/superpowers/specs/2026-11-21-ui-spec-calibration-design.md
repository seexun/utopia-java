# 全局 UI 规范校准规格（SaaS Light 参数对齐+尺寸校准+原生页走查）

- 日期：2026-11-21
- 状态：已获用户批准（差距分析+方案 OK）
- 输入：《若依管理系统全局 UI 主题与布局优化规范》（用户提供的完整规范文档，本规格的权威取值源）
- 现状：系统已实现规范 ~85%（gvdata 对齐+七页重造同源 SaaS Light）；本规格只做**差距收口**，不重做。

## 差距与处置

### A. 令牌值校准（`jknow-web/src/style/tokens.scss` :root + html.dark 联动）

| 令牌 | 规范值 | 现值 | 动作 |
|---|---|---|---|
| --color-primary-soft | #EFF6FF | #eff4fe | 改 |
| --color-bg | #F5F7FA | #f6f8fb | 改 |
| --color-ink | #1F2937 | #0f172a | 改 |
| --color-ink-2 | #6B7280 | #475569 | 改 |
| --color-ink-3 | #9CA3AF | #94a3b8 | 改 |
| --color-border | #E5EAF0 | #e5eaf1 | 改 |
| --radius-card | 8px | 12px | 改 |
| --radius-ctl | 6px | 8px | 改 |

- 主色/悬浮/成功/警告/危险**已精确一致，不动**。
- **element 色阶联动**：tokens.scss 内 --el-color-primary-light-3/5/7/8 静态色阶及 dark-2 随新主色族重算（规范 §三 只给主值，色阶按 Boot BOM 惯例 light-N=主色×(1-N/10)+白×N/10 逐档重算；handleThemeStyle 运行时同公式自动一致）。
- **html.dark 档联动**：ink/ink-2/ink-3 暗档按新亮值保持既定暗色关系不变（暗档为独立调色，非亮值函数——仅 soft 系透明度底色随主色族不变即无需动；核对后如有引用亮值字面量的暗档键同步）。
- **tailwind.css 同名令牌双源**（--color-card/--color-fill/--color-line-strong 及 @theme 注册的 ink 族）与新值同步；utopia.scss 垫片如引用旧字面量同步。

### B. 布局尺寸校准（variables.module.scss + layout 组件）

| 项 | 规范 | 现值 | 动作 |
|---|---|---|---|
| 侧栏宽度 $base-sidebar-width | 210（208~220 中值） | 200px | 改（含 :export 与 --base-sidebar-width CSS 变量链） |
| 顶部导航高 | 54（52~56 中值） | 50px | 改（Navbar.vue height/line-height + logo 区联动 + fixed-header 偏移） |
| 标签页高 | 38（36~40 中值） | 实测后定 | 改（TagsView style + 关联 padding） |
| 一级菜单高 | 44px（一级）/40px（二级） | 统一 40 | 一级 44/二级 40 分档（sidebar.scss light 块） |

- 中值取法：规范给区间时取中值，避免反复；现有响应式机制（折叠/hideSidebar/mobile）不破坏——尺寸改动逐处核对联动偏移（.fixed-header + .app-main 的 padding-top 等计算引用）。
- **kb-switcher 底部区**随侧栏宽度链自动跟随（--base-sidebar-width 消费方 grep 确认）。

### C. 若依原生页面走查（§十四 验收驱动）

- 走查页集：系统管理 8 页（用户/角色/菜单/部门/岗位/字典/参数/通知公告）+ 监控 6 页（在线用户/定时任务/数据监控/服务监控/缓存监控/缓存列表）+ 工具 3 页（表单构建/代码生成/系统接口）+ 登录/首页/个人中心。
- 走查口径：§十四 的 10 条验收逐页核对（背景/导航/卡片/强调色一致、无灰白混杂/边框过重/布局松散、同类组件跨页一致、对齐规整、1920 与 2560 布局正常、无图标错位/文字截断/内容溢出/多余滚动条）。
- 修法优先级：公共层（anivia/theme.scss/element-ui.scss）能修的修公共层；个别页面残留做页面级微调（预期集中在：RuoYi 自带分页条样式、代码生成页工具样式、druid 监控 iframe 页外框、表单构建拖拽区）。
- 知识业务七页（已重造）抽验 2 页确认参数校准无回归（检索+图谱）。

## 验收标准（§十四 逐条）

1. 全套令牌/尺寸与规范表一致（grep 逐值核对）；2. 17 个原生页走查截图+console 零 error；3. 1920/2560 双分辨率抽查（Playwright viewport 两档）；4. `pnpm test` 421 基线+typecheck+build 绿；5. 双 smoke 零退化；6. 暗态切换不异常（暗档机制保留）；7. 业务页抽验零回归。

## 不做的事

- 不改任何业务功能/接口/路由/权限/菜单结构（规范 §12.3 全条）；不引入新依赖/UI 框架；不做业务页重设计（七页已造）；灰度旧值不保留兼容层（单源自建系统无外部消费者）。
