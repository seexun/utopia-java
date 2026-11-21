# UI 挂账清偿批规格（硬编码色 var 化+register SaaS Light+offset 令牌+折叠弹层/暗色分档）

- 日期：2026-11-21
- 状态：已获用户批准（四节设计 OK）
- 背景：UI 规范校准批（3bd5bf4）已合并；本批清偿账本中全部 UI 级挂账，此后 UI 面仅剩长期挂账（hljs 豁免记录等已归档项）。

## 设计

### 1. 业务组件硬编码色 var 化（壳层+原生页清扫，~24 文件）

令牌映射表（右侧值即规范值，替换即校准）：

| 字面量 | 令牌 | 出现量（侦察值） |
|---|---|---|
| `#6b7280` | `var(--color-ink-2)` | 16 |
| `#e5eaf0` | `var(--color-border)` | 8 |
| `#9ca3af` | `var(--color-ink-3)` | 7 |
| `#1677ff` | `var(--color-primary)` | 5 |
| `#f1f5f9` | `var(--color-neutral-soft)` | 4 |
| `#e6f4ff` | `var(--color-primary-soft)` | 3 |
| `#1f2937` | `var(--color-ink)` | 3 |
| `#f5f7fa` | `var(--color-bg)` | 2 |
| `#f59e0b` | `var(--color-warning)` | 1 |
| `#ef4444` | `var(--color-danger)` | 1 |
| `#fff` | 按语境：危险/主色底上的白字保留；卡片底 → `var(--color-card)` | 逐处判断 |

- 覆盖文件（按量降序）：HeaderNotice/DetailView.vue(29)、TagsView/index.vue(26)、alert-bell/index.vue(21)、tool/build/index.vue(19)、system/dict/detail.vue(17)、TreePanel/index.vue(16)、account/shell.vue(13)、HeaderSearch/index.vue(13)、HeaderNotice/index.vue(12)、Settings/index.vue(8)、lock.vue(7)、tool/build/RightPanel.vue(6)、user-menu/index.vue(6)、register.vue(4，随 §2 重造不单独扫)、error/404.vue(4)、error/401.vue(4)、TopNav/index.vue(4)、FileUpload/index.vue(4)、Crontab/index.vue(4)、IconsDialog.vue(3)、search/index.vue(4)、review/rows.tsx(4)、library/index.vue(4)、review/index.vue(3)、chat/turn.tsx(3)。
- 手法：inline `style="color:#6b7280"` 原位换 `var(...)`，最小改动不重构类名。
- 豁免维持：hljs 八色（代码高亮，已记录）、gvdesign/ 零改区、src/style/ 目录令牌定义本身、测试文件。

### 2. register.vue SaaS Light 化（逻辑零动）

- 现状：`login-background.jpg 照片底 + #707070 标题 + 400px 白卡`（220 行）。
- 目标：镜像 login.vue 家族——`radial-gradient(--color-primary-soft) 顶层 + --color-bg 底 + 白卡 + --color-border 描边 + --color-ink 族文字 + var(--radius-card)`。
- `getCode/validate/handleRegister` 全链路零动；script 仅样式无关联不触碰。
- 页面不可达（sys_config register=false）——直连 URL 走查验收。

### 3. `--offset-app-content` 令牌抽取（124 单源，9 处点位）

- tokens.scss :root 增 `--offset-app-content: 124px`（注释：`124 = 顶栏 54 + 标签栏 38 + .app-main 上下 padding 16×2`）。
- CSS 消费方（6 处）：
  - tool/build/index.vue :326/:334/:435/:459/:475 → `calc(100vh - var(--offset-app-content))` / `calc(100vh - var(--offset-app-content) - 42px)` / `calc(100vh - var(--offset-app-content) - 69px)`（保持各点位的附加扣减原样）。
  - tool/build/RightPanel.vue :801 陈旧算式 `calc(100vh - 50px - 40px - 42px)`（旧 50 顶栏+40 标签基数已过时）→ `calc(100vh - var(--offset-app-content) - 42px)`。
- JS 消费方（3 处）：iFrame/index.vue :20/:29、InnerLink/index.vue :26 → `parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--offset-app-content')) || 124`，resize 监听逻辑不动。
- AppMain 的 92/54 字面量保留（已有注释、属 margin-top 链条，不扩面）。

### 4. 折叠弹层 + 暗色侧栏菜单高分档

- **暗色分档**：sidebar.scss `&.theme-dark` 块（约 95-140 行）现无 height 声明 → 镜像 light 块的 44/40 分档结构（`.el-menu-item/.el-sub-menu__title` 44px + `.nest-menu` 回归 40px + margin 2px 8px + `var(--radius-ctl)`），色板维持暗色系现有取值，选中态 `::before` 覆盖层与分档共存（inset:0 随项自适应）。
- **折叠弹层**：折叠时 el-menu 弹层 teleport 到 body，逃出 `#app .sidebar-container.sidebar-light` 作用域，44/40/margin/圆角/hover 全失效。
  - 方案：sideTheme 为 light 时给 body 挂 `sidebar-light-mode` 类（layout 处 watch 一行，含初始化同步与卸载清理）；全局样式层（sidebar.scss 尾部）写弹层规则：弹层容器卡化（border/radius/shadow）+ 菜单项 40px 分档 + hover `--color-neutral-soft`。
  - 实施时以 Playwright 折叠态 DOM 实测确定弹层选择器（预期 `.el-popper` 内 `.el-menu--popup`），不凭空写死。
  - 暗色侧栏的折叠弹层维持 Element 默认暗色形态（不动）。

## 验收标准

1. 映射表 grep 归零（豁免清单外）：`LC_ALL=C grep -rn -iE "#6b7280|#e5eaf0|#9ca3af|#1677ff|#f1f5f9|#e6f4ff|#1f2937|#f5f7fa" jknow-web/src --include="*.vue" --include="*.tsx"` 仅剩豁免项。
2. 走查截图（独立 Playwright+DOM 断言先行的 T1 纪律）：alert-bell 弹层、user-menu 弹层、tags-view 栏、折叠态侧栏亮/暗各一、register 直连、tool/build 页、404 页；console 零 error。
3. `pnpm test` 基线 + typecheck + build 绿；双 smoke（smoke-p0/p1）零退化。
4. 折叠弹层分档实测：折叠后 hover 展开二级，菜单项 40px 形态与展开态家族一致（截图对比）。

## 不做的事

- 不动任何业务逻辑/路由/接口/权限/菜单结构；不做 vendor-utils 拆包/SSE 竞态/pipeline 测试等长期挂账；AppMain 92/54 链条不扩面；hljs 八色与 gvdesign/ 豁免维持；暗色折叠弹层不改造（维持 EP 默认）。
