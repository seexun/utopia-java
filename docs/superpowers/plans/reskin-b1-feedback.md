# reskin-b1 用户验收反馈修正：侧栏 light 系对齐 gvdata（2026-11-19）

> 分支 reskin-b1（基线 0643d56）收口修正。用户验收反馈：gvdata 目标系统侧栏是 light 系，
> jknow 仍是暗系深色菜单——需对齐主体风格颜色。本文为反馈修正记录（入库）。

## 根因

1. jknow `jknow-web/src/settings.ts` 默认 `sideTheme: 'theme-dark'`，gvdata 默认 `'theme-light'`。
2. settings store `storageSetting.sideTheme || sideTheme`：localStorage `layout-setting` 里的
   存量 `'theme-dark'` 缓存会压过新默认值，改默认不够，需缓存归一化。
3. light 态样式变量（`$menuLight*` 全套）与 gvdata 版 sidebar.scss 浅色形态块虽已在仓，
   但 jknow 侧栏容器从未挂 `sidebar-light` 类，gvdata 的 SaaS-Light 菜单形态块（40px/圆角/
   选中/hover）实际不生效；且 `#app .sidebar-container` 基座规则硬编码深底 `$base-menu-background`
   （ID 权重）压过组件 scoped v-bind 白底，light 态容器底色仍渲染 #001529。

## 修正内容

| # | 文件 | 修正 |
|---|------|------|
| 1 | `jknow-web/src/settings.ts` | `sideTheme` 默认 `'theme-dark'` → `'theme-light'`（对齐 gvdata） |
| 2 | `jknow-web/src/store/modules/settings.ts` | 缓存归一化：存量 `'theme-dark'` 视同未设置，归一回新默认 light（参照 T1 主题色 #409EFF 归一化同款手法） |
| 3 | `jknow-web/src/layout/components/Sidebar/index.vue` | 容器挂 `sidebar-light` 类（light 态）——激活 sidebar.scss 中 gvdata SaaS-Light 形态块 |
| 4 | `jknow-web/src/assets/styles/sidebar.scss` | theme-light 块补：容器白底（盖回基座深底）、Logo 区底部 1px 分割线、选中 `::before` 遮罩 `border-radius: inherit`（不方角压圆角） |
| 5 | `jknow-web/src/layout/components/Navbar.vue` | 顶栏 `box-shadow` 收敛 `--shadow-card` 软影 + 底部 1px `--color-border` 分割线（对齐 gvdata） |
| 6 | `jknow-web/src/assets/styles/variables.module.scss` | html.dark 暗态适配：容器底 `--sidebar-bg` !important 盖回（原先基座 #001529 与菜单 #141414 不同源的细微差一并收敛）；`html.dark #app .sidebar-container.sidebar-light` 选中/hover 去浅底化（primary-soft/neutral-soft 浅片不刺破暗侧栏，选中回深底+主色文字，::before 10% tint 遮罩保留换色跟随） |
| 7 | 同上（审查 M1 追加） | html.dark `.sidebar-container.theme-light` 块补 `.sidebar-logo-container { border-bottom: none !important }`——暗态 Logo 底部分割线撤除（同 border-right:none 口径），light 态分割线保留 |

## 与 gvdata 并排走查——已对齐项（样式层）

浏览器双开（jknow :80 / gvdata :25456）computed-style 逐项核对：

- 侧栏容器：白底、无投影、右侧 1px `--color-border`(#e5eaf1) 分割线 ✓
- 菜单项形态：40px 行高、圆角 8px(`--radius-ctl`)、margin 2px/8px ✓
- 选中项：`--color-primary-soft`(#eff4fe) 底 + #2563eb 文字 + 700 字重 ✓
- hover：`--color-neutral-soft`(#f1f5f9) 底 + ink 深字 ✓
- 一级分组标题（el-sub-menu title）：600 字重 + `--color-ink-2`(#475569) ✓
- Logo 区：白底 + 底部 1px 分割线 ✓
- 顶栏：白底 + `--shadow-card`(0 1px 2px rgba(15,23,42,.06)) + 底部 1px 分割线 ✓
- kb-switcher（jknow 特有底部切库器）：白底 + menuLightText 跟随 ✓（sideTheme 计算链复验）

## 留批次（结构差异，非一行可对齐）

1. **Logo/顶栏高度**：jknow 50px vs gvdata 56px——jknow 壳既定尺寸，`has-logo` calc(100% - 50px)
   连动，改动涉及布局基线，留批次统一。
2. **Logo 形态**：jknow 32px logo 图+600 字重标题 vs gvdata 34px 图标+800 字重+10px 间距——品牌区
   内容结构差异，留批次。
3. **底部区**：jknow 是 kb-switcher（切库器）vs gvdata sidebar-foot（折叠工具+状态点）——jknow
   特有组件位，已随 light 态配色，不做形变。
4. **选中色来源**：gvdata 固定 `--color-primary`；jknow 保留 `--current-color` 换色跟随（Settings
   抽屉改主色后选中态同步，`::before` 10% tint 遮罩实现）——jknow 特性保留，默认色下两者一致。
5. **（M2）light 选中文字不随自定义主色**：gvdata 形态块的 `color: var(--color-primary) !important`
   （#app+5 类）权重高于 jknow `--current-color` 规则（#app+4 类），改主色后仅 `::before` 10% tint
   遮罩跟随、文字钉在 --color-primary——要文字也跟随需提升 jknow 规则权重或 gvdata 块让位，留批次。
6. **（M3）hover 文字色等价差异**：jknow hover 文字 rgba(0,0,0,0.85) vs gvdata `--color-ink`(#0f172a)
   ——同族等价差异，非肉眼可辨级，留批次随 tokens 统一时收口。

## 缓存归一化取舍（记录）

- 存量 `layout-setting.sideTheme === 'theme-dark'`（旧默认落盘）视同未设置 → 归一回 light；
- **边界**：用户在 Settings 抽屉「主题风格设置」重选深色并「保存配置」后，落盘字面量仍是
  `'theme-dark'`，下次刷新会被同样归一回 light（与 T1 #409EFF 归一化同款边界）——即深色偏好
  不跨刷新持久。本批验收以 light 为目标默认，接受此取舍；若后续要持久化深色偏好，需换缓存
  schema/版本标记（如 sideThemeVer 字段区分新旧写入），留批次。
- 抽屉内即时重选深色/浅色均即时生效（handleTheme 直改 store，已浏览器复验）。

## 验证

- 缓存归一化：手灌 `{"sideTheme":"theme-dark","theme":"#409EFF"}` 后刷新——侧栏 light、
  主色 #2563eb（两项归一化同时生效）；
- light 走查：/system/user（选中/hover）、图谱/检索/文库三知识页（kb-switcher 跟随、无样式撕裂）；
- 暗态（html.dark 顶栏切换钮）：容器/菜单/Logo/kb-switcher 全 #141414 同源，选中深底+主色，
  hover #2d2d2d；三知识页暗态复验无撕裂；切回 light 恢复正常；
  **更正（审查 M1）**：初版该表述漏计 Logo 底部分割线——html.dark 且 sideTheme=light 时
  theme-light 块的 1px #e5eaf1 浅线仍画在 #141414 暗底上（feedback-dark-recheck.png x=100,y=49
  可见）。已在 html.dark `.sidebar-container.theme-light` 块补 `.sidebar-logo-container
  { border-bottom: none !important }`（同既有 border-right:none 口径），复验暗态无线、light 态
  分割线保留；该截图拍摄于修正前，留作边角证据；
- Settings 抽屉：theme-dark 重选即时生效（容器 #001529），重选 light 恢复；
- `pnpm test` 56 文件 / **419 全绿**；`pnpm typecheck`（vue-tsc --noEmit）零错误。
- 截图（docs/superpowers/plans/reskin-b1/）：
  - `feedback-01-light-as-is.png` 修正前 as-is（容器深底撕裂证据）
  - `feedback-light-sidebar-full.png` light 侧栏全景（修正后）
  - `feedback-vs-gvdata-side-by-side.png` 与 gvdata 并排对比
  - `feedback-dark-recheck.png` 暗态复验
