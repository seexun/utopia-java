# UI 挂账清偿批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 清偿 UI 账本四项挂账——业务组件硬编码色 var 化、register.vue SaaS Light 化、`--offset-app-content` 布局偏移令牌单源化、折叠弹层+暗色侧栏菜单 44/40 分档。

**架构：** 纯前端样式批（jknow-web），逻辑零动铁律。令牌映射替换（最小改动不重构类名）+ login 家族镜像 + CSS var 抽取（CSS 消费方 calc 引用 / JS 消费方 getComputedStyle 读取）+ body 类桥接 teleport 弹层作用域。

**技术栈：** Vue3 + SCSS + Element Plus；验证链 grep 归零 + Playwright 走查（独立实例 + DOM 断言先行）+ `pnpm test`/typecheck/build + 双 smoke。

**规格：** `docs/superpowers/specs/2026-11-21-ui-ledger-cleanup-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`，前端 `jknow-web/`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：端口读 `/tmp/jknow-ports.txt`（后端 27401 / 前端 24110）；若未起，按 README 恢复（compose up → java -jar（`JKNOW_PG_PORT=15432 SERVER_PORT=<port> JKNOW_DATA_DIR=/d/workspace_com/jknow/data`）→ `pnpm dev --port <port> --strictPort`）。注意 `pnpm dev -- --port X` 会传字面 `--`，禁止。
- 走查纪律（T1）：**独立 Playwright 实例**（不用共享 agent-browser）；截图前先 DOM 断言（断言目标元素存在且文本匹配），防假截图。
- 永不提交：`jknow-web/auto-imports.d.ts`、`jknow-web/vite.config.ts`、`tests/__snapshots__/*.snap`、`.playwright-cli/`、`jknow-web/src/docs/*.md`（CRLF 幻影）。
- 分支：`feat/ui-ledger-cleanup`（自 master 切出）。

---

### 任务 1：业务组件硬编码色 var 化（~23 文件，映射替换）

**文件：**
- 修改（按量降序，均在 `jknow-web/src/` 下）：`layout/components/HeaderNotice/DetailView.vue`(29 处)、`layout/components/TagsView/index.vue`(26)、`components/knowledge/alert-bell/index.vue`(21)、`views/tool/build/index.vue`(19)、`views/system/dict/detail.vue`(17)、`components/TreePanel/index.vue`(16)、`views/jknow/knowledge/account/shell.vue`(13)、`components/HeaderSearch/index.vue`(13)、`layout/components/HeaderNotice/index.vue`(12)、`layout/components/Settings/index.vue`(8)、`views/lock.vue`(7)、`views/tool/build/RightPanel.vue`(6)、`components/knowledge/user-menu/index.vue`(6)、`views/error/404.vue`(4)、`views/error/401.vue`(4)、`layout/components/TopNav/index.vue`(4)、`components/FileUpload/index.vue`(4)、`components/Crontab/index.vue`(4)、`views/tool/build/IconsDialog.vue`(3)、`views/jknow/knowledge/search/index.vue`(4)、`views/jknow/knowledge/review/rows.tsx`(4)、`views/jknow/knowledge/library/index.vue`(4)、`views/jknow/knowledge/review/index.vue`(3)、`views/jknow/knowledge/chat/turn.tsx`(3)
- 不动：`views/register.vue`（任务 2 整体重造）、`src/style/` 目录（令牌定义本身）、`gvdesign/`（零改区）、hljs 代码高亮八色（豁免已记录）、测试文件

- [ ] **步骤 1：写批量替换脚本并执行（8 个确定性映射）**

在仓库根创建 `scripts/replace-hardcoded-colors.mjs`（一次性工具，任务末删除）：

```js
// 一次性映射替换（规格 §1 映射表；#fff/#f59e0b/#ef4444 量小且需语境判断，留手动）
import { readFileSync, writeFileSync } from 'node:fs'

const FILES = [
  'jknow-web/src/layout/components/HeaderNotice/DetailView.vue',
  'jknow-web/src/layout/components/TagsView/index.vue',
  'jknow-web/src/components/knowledge/alert-bell/index.vue',
  'jknow-web/src/views/tool/build/index.vue',
  'jknow-web/src/views/system/dict/detail.vue',
  'jknow-web/src/components/TreePanel/index.vue',
  'jknow-web/src/views/jknow/knowledge/account/shell.vue',
  'jknow-web/src/components/HeaderSearch/index.vue',
  'jknow-web/src/layout/components/HeaderNotice/index.vue',
  'jknow-web/src/layout/components/Settings/index.vue',
  'jknow-web/src/views/lock.vue',
  'jknow-web/src/views/tool/build/RightPanel.vue',
  'jknow-web/src/components/knowledge/user-menu/index.vue',
  'jknow-web/src/views/error/404.vue',
  'jknow-web/src/views/error/401.vue',
  'jknow-web/src/layout/components/TopNav/index.vue',
  'jknow-web/src/components/FileUpload/index.vue',
  'jknow-web/src/components/Crontab/index.vue',
  'jknow-web/src/views/tool/build/IconsDialog.vue',
  'jknow-web/src/views/jknow/knowledge/search/index.vue',
  'jknow-web/src/views/jknow/knowledge/review/rows.tsx',
  'jknow-web/src/views/jknow/knowledge/library/index.vue',
  'jknow-web/src/views/jknow/knowledge/review/index.vue',
  'jknow-web/src/views/jknow/knowledge/chat/turn.tsx',
]
const MAP = [
  [/#6b7280/gi, 'var(--color-ink-2)'],
  [/#e5eaf0/gi, 'var(--color-border)'],
  [/#9ca3af/gi, 'var(--color-ink-3)'],
  [/#1677ff/gi, 'var(--color-primary)'],
  [/#f1f5f9/gi, 'var(--color-neutral-soft)'],
  [/#e6f4ff/gi, 'var(--color-primary-soft)'],
  [/#1f2937/gi, 'var(--color-ink)'],
  [/#f5f7fa/gi, 'var(--color-bg)'],
]
for (const f of FILES) {
  let s = readFileSync(f, 'utf8')
  const before = s
  for (const [re, to] of MAP) s = s.replace(re, to)
  if (s !== before) { writeFileSync(f, s); console.log('updated', f) }
}
```

运行：`cd /d/workspace_com/jknow && node scripts/replace-hardcoded-colors.mjs`

- [ ] **步骤 2：逐文件 diff 审查**

`git diff --stat` 后逐文件 `git diff jknow-web/src/<file>` 核对：替换仅发生在颜色字面量位置（template inline style / style 块 / :style 绑定均合法），无误伤（如把组件 prop 里的非色值字面串改掉）。可疑处回读上下文确认。

- [ ] **步骤 3：手动处理语境色（#f59e0b/#ef4444/#fff 及脚本未覆盖残留）**

侦察：`cd jknow-web && LC_ALL=C grep -rn -a -iE "#f59e0b|#ef4444|#fff\b|#[0-9a-f]{6}" src/layout src/components src/views/jknow src/views/system src/views/tool src/views/error src/views/lock.vue --include="*.vue" --include="*.tsx" | grep -v "src/style" | grep -viE "hljs|gvdesign|spec|test"` 逐处判断：
- `#f59e0b` → `var(--color-warning)`；`#ef4444` → `var(--color-danger)`；
- `#fff`：作为 danger/primary 底上的**文字色**（如 alert-bell 徽章 `color: "#fff"`）→ 保留字面量（语义是"反白"而非"卡片白"）；作为**卡片/面板背景** → `var(--color-card)`（tailwind.css 已定义，暗档自动翻转）；
- 其余杂色（如 TagsView 的旧渐变残留、TopNav 暗色系值）：同族令牌可表达 → 换；暗色专属且无对应令牌 → 保留并加行注释 `/* dark-only */`。

- [ ] **步骤 4：grep 归零验证**

```bash
cd jknow-web && LC_ALL=C grep -rn -a -iE "#6b7280|#e5eaf0|#9ca3af|#1677ff|#f1f5f9|#e6f4ff|#1f2937|#f5f7fa" src --include="*.vue" --include="*.tsx"
```
预期：**零输出**（style/ 定义区与豁免清单不在 src/views|layout|components 消费面——若有输出逐处归类：确认是豁免则记录，否则补替）。

- [ ] **步骤 5：构建与走查**

- `cd jknow-web && pnpm typecheck && pnpm build`（替换不涉 TS 类型，绿即过）。
- dev 环境起（读 /tmp/jknow-ports.txt；未起按 README 恢复），独立 Playwright 实例走查 5 屏（DOM 断言先行）：告警铃弹层（点顶栏铃铛 → 断言面板项存在 → 截图 `t1-01-alert-bell.png`）、用户菜单弹层（`t1-02-user-menu.png`）、标签栏（`t1-03-tagsview.png`）、字典详情（system/dict 任一行详情抽屉，`t1-04-dict-detail.png`）、404（直连不存在路由，`t1-05-404.png`）。console 零 error。截图存 `docs/superpowers/plans/ui-ledger-cleanup/`。

- [ ] **步骤 6：删除一次性脚本 + Commit**

```bash
rm scripts/replace-hardcoded-colors.mjs
git add jknow-web/src
git commit -m "style(web): 业务组件硬编码色清偿——8+语境色全量 var 化（映射见 ui-ledger-cleanup 规格 §1）"
```

---

### 任务 2：register.vue SaaS Light 化（逻辑零动）

**文件：**
- 修改：`jknow-web/src/views/register.vue:158-220`（仅 `<style>` 段与 template 中引背景的 class；script 区 1-156 行零动）

- [ ] **步骤 1：替换 style 段为 login 家族版式**

用以下内容替换 `<style lang='scss' scoped>` 段内**对应选择器**（结构对位替换，不改选择器名）：

```scss
/* SaaS Light 对齐（镜像 login.vue 家族——register 不可达但启用即合规）：
   旧版为照片背景+#707070 标题；改令牌底色+主色 soft 氛围光，暗档由 html.dark 令牌翻转自动成立 */
.register {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background:
    radial-gradient(1100px 560px at 78% -8%, var(--color-primary-soft), transparent 62%),
    var(--color-bg);
}
.title {
  margin: 0px auto 30px auto;
  text-align: center;
  color: var(--color-ink);
}
.register-form {
  border-radius: var(--radius-card);
  background: var(--el-bg-color);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-card);
  width: 400px;
  padding: 25px 25px 5px 25px;
  z-index: 1;
}
```

段内其余选择器（.el-input/.input-icon/.register-code 等）保留原样，只把其中残留的硬编码色按任务 1 映射表清偿（该文件 4 处 hex 一并归零）。末尾补暗影段（login 同款）：`html.dark .register-form { box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5); }`

- [ ] **步骤 2：grep 验证该文件 hex 归零**

`cd jknow-web && LC_ALL=C grep -n -a -iE "#[0-9a-f]{3,6}" src/views/register.vue`
预期：零输出。

- [ ] **步骤 3：走查（直连 URL）**

dev 环境起，独立 Playwright 直连 `http://localhost:<fe-port>/register`（路由白名单外页面但 register 路由常驻）：DOM 断言标题"注册"与表单存在 → 截图 `t2-01-register.png`；console 零 error。（验证码接口 getCode 在页面加载即调——返回 captchaEnabled=false 时验证码框不渲染，属正常。）

- [ ] **步骤 4：typecheck + Commit**

```bash
cd jknow-web && pnpm typecheck
git add src/views/register.vue
git commit -m "style(web): register 页 SaaS Light 化——镜像 login 家族（逻辑零动）"
```

---

### 任务 3：`--offset-app-content` 令牌抽取（124 单源，9 处点位）

**文件：**
- 修改：`jknow-web/src/style/tokens.scss`（:root 增 1 令牌）
- 修改：`jknow-web/src/views/tool/build/index.vue:326,334,435,459,475`（5 处 calc）
- 修改：`jknow-web/src/views/tool/build/RightPanel.vue:801`（1 处陈旧 calc）
- 修改：`jknow-web/src/components/iFrame/index.vue:19-30`（2 处 JS）
- 修改：`jknow-web/src/layout/components/InnerLink/index.vue:25-27`（1 处 JS）

- [ ] **步骤 1：tokens.scss :root 块末尾（颜色令牌之后、html.dark 之前）增**

```scss
/* ── 布局偏移单源（ui-ledger-cleanup §3）──
   124 = 顶栏 54 + 标签栏 38 + .app-main 上下 padding 16×2
   消费方：tool/build、RightPanel（CSS calc）+ iFrame、InnerLink（JS 读取） */
--offset-app-content: 124px;
```

- [ ] **步骤 2：tool/build/index.vue 五处 calc 替换**

- `:326` `height: calc(100vh - 124px);` → `height: calc(100vh - var(--offset-app-content));`
- `:334` 同上替换
- `:435` 同上替换
- `:459` `height: calc(100vh - 124px - 42px);` → `height: calc(100vh - var(--offset-app-content) - 42px);`
- `:475` `height: calc(100vh - 124px - 69px);` → `height: calc(100vh - var(--offset-app-content) - 69px);`
- 各处如带 `/* 124 = ... */` 旧注释改为 `/* 偏移单源见 tokens.scss --offset-app-content */`

- [ ] **步骤 3：RightPanel.vue:801 陈旧基数修正**

`height: calc(100vh - 50px - 40px - 42px);` → `height: calc(100vh - var(--offset-app-content) - 42px);`
（旧式 50 顶栏+40 标签基数随 §B 尺寸校准已过时——此替换同时修正 14px 偏差。）

- [ ] **步骤 4：JS 消费方读取令牌（iFrame ×2 + InnerLink ×1）**

iFrame/index.vue（:19-30 区域）改为：

```ts
/* 偏移单源 tokens.scss --offset-app-content（124 = 54+38+16×2）；fallback 兜底 */
function appOffset(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--offset-app-content')
  const parsed = parseFloat(raw)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 124
}
const height = ref<string>(document.documentElement.clientHeight - appOffset() + 'px;')
```

其 resize/onMounted 内同源的 `document.documentElement.clientHeight - 124` 一并改 `- appOffset()`。InnerLink/index.vue :25-27 同款（注意其行尾无分号，保持原样：`+ 'px'`）。两文件旧 `/* 124 = … */` 注释删除（由 tokens.scss 单源注释承担）。

- [ ] **步骤 5：grep 验证 124 消费面归零 + 走查**

```bash
cd jknow-web && LC_ALL=C grep -rn -a -E "100vh - 124|clientHeight - 124|100vh - 50px - 40px" src --include="*.vue"
```
预期：零输出（tokens.scss 定义处是 `124px` 赋值不算消费）。

dev 环境走查：表单构建页（菜单 工具→表单构建，DOM 断言拖拽区存在 → 截图 `t3-01-build.png`，页面无纵向双滚动条）；任一 iframe 型页面（如 监控→数据监控 若可用，否则跳过并在报告记录）；console 零 error。

- [ ] **步骤 6：Commit**

```bash
git add jknow-web/src/style/tokens.scss jknow-web/src/views/tool/build/index.vue jknow-web/src/views/tool/build/RightPanel.vue jknow-web/src/components/iFrame/index.vue jknow-web/src/layout/components/InnerLink/index.vue
git commit -m "refactor(web): 内容区高度偏移抽 --offset-app-content 令牌（124 单源，10 处点位收口+RightPanel 陈旧基数修正）"
```

---

### 任务 4：折叠弹层 + 暗色侧栏菜单 44/40 分档

**文件：**
- 修改：`jknow-web/src/layout/components/Sidebar/index.vue`（script 增 body 类桥接 watch）
- 修改：`jknow-web/src/assets/styles/sidebar.scss`（theme-dark 块加分档；尾部增弹层规则）

- [ ] **步骤 1：Sidebar/index.vue 增 body 类桥接**

script 区（sideTheme computed 已在 :48）增：

```ts
// 折叠弹层 teleport 到 body，逃出 .sidebar-light 作用域——body 类桥接（ui-ledger-cleanup §4）
function syncSidebarLightMode(light: boolean): void {
  document.body.classList.toggle('sidebar-light-mode', light)
}
watch(sideTheme, v => syncSidebarLightMode(v === 'theme-light'), { immediate: true })
onUnmounted(() => syncSidebarLightMode(false))
```

`watch/onUnmounted` 从 `vue` 补进现有 import（勿动其它 import）。

- [ ] **步骤 2：sidebar.scss theme-dark 块内加分档**

在 `&.theme-dark {`（约 :405 起，含 `.el-menu-item.is-active` 的块）内、色板规则之间插入（与 light 块（:356 起）分档结构镜像，色板不动）：

```scss
    // 分档（与 theme-light 同构）：一级 44px / 二级及以下 40px
    .el-menu-item,
    .el-sub-menu__title {
      height: 44px;
      line-height: 44px;
      margin: 2px 8px;
      border-radius: var(--radius-ctl);
    }
    .nest-menu .el-menu-item,
    .nest-menu .el-sub-menu__title {
      height: 40px;
      line-height: 40px;
    }
    // 二级菜单项宽度不撑破带 margin 的圆角容器（与 light 同款）
    .nest-menu .el-sub-menu > .el-sub-menu__title,
    .el-sub-menu .el-menu-item {
      min-width: 0 !important;
    }
```

（插入位置注意：is-active 的 `::before` 覆盖层为 `position:absolute; inset:0`——随带 margin 的项自适应，共存无冲突；若实测选中竖条/覆盖层错位，将 ::before 挂点改为随圆角，报告记录。）

- [ ] **步骤 3：sidebar.scss 尾部增折叠弹层规则**

文件末尾追加（**选择器以实测 DOM 为准**——先走查折叠态取真实类名，下方按 Element Plus 惯例 `.el-menu--vertical`（垂直折叠弹层容器）预写）：

```scss
/* ===== 折叠弹层（teleport 到 body 逃出上方作用域——body.sidebar-light-mode 桥接；ui-ledger-cleanup §4）===== */
body.sidebar-light-mode {
  .el-menu--vertical {
    // 弹层容器卡化
    .el-menu--popup {
      border: 1px solid var(--color-border);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-card);
      overflow: hidden;
      min-width: 176px;
    }
    .el-menu-item,
    .el-sub-menu__title {
      height: 40px;
      line-height: 40px;
      border-radius: var(--radius-ctl);
      transition: background-color var(--transition-base), color var(--transition-base);
      &:hover {
        background-color: var(--color-neutral-soft) !important;
        color: var(--color-ink) !important;
      }
    }
    .el-menu-item.is-active {
      background-color: var(--color-primary-soft) !important;
      color: var(--color-primary) !important;
      font-weight: 600;
    }
  }
}
```

- [ ] **步骤 4：走查实测（折叠态，亮/暗双主题）**

dev 环境，独立 Playwright：
1. 亮色（默认）：点击折叠按钮（Navbar hamburger）→ 断言 `document.body.classList.contains('sidebar-light-mode')` 为 true → hover 一级菜单展开弹层 → DOM 断言弹层内 `.el-menu-item` 存在 → **测项高** `getComputedStyle(item).height === '40px'` → 截图 `t4-01-collapse-light.png`。
2. 暗色：Settings 抽屉切"深色侧栏"→ 断言 body 类已摘除、侧栏 `.el-menu-item` 高 `44px`、`.nest-menu` 项 `40px` → 展开二级截图 `t4-02-dark-tiered.png`；切回亮色确认类恢复。
3. 若弹层真实类名与 `.el-menu--vertical` 预写不符（Element Plus 版本差异）：以实测类名修正步骤 3 选择器后复测。
4. console 零 error。

- [ ] **步骤 5：typecheck + build + Commit**

```bash
cd jknow-web && pnpm typecheck && pnpm build
git add src/layout/components/Sidebar/index.vue src/assets/styles/sidebar.scss
git commit -m "feat(web): 折叠弹层亮色化（body 类桥接 teleport 作用域）+暗色侧栏菜单 44/40 分档"
```

---

### 任务 5：全量回归与证据汇总（分支终审前置）

- [ ] **步骤 1：全量验证链**

```bash
cd jknow-web && pnpm test && pnpm typecheck && pnpm build
cd /d/workspace_com/jknow && JKNOW_APP_BASE=http://localhost:<be-port> ./scripts/smoke-p0.sh && JKNOW_APP_BASE=http://localhost:<be-port> ./scripts/smoke-p1.sh
```
预期：test 全绿（421 基线；无受影响 spec，若有适配须在报告记账）、双 smoke 零退化。

- [ ] **步骤 2：证据归档**

走查截图（t1-01…t4-02 共 ≥10 张）确认存 `docs/superpowers/plans/ui-ledger-cleanup/`；逐张核对截图内容与文件名对应（防 t1-03 假证据复发）。补一张折叠弹层与展开态侧栏的同屏家族对比（可选拼图或两图）。

- [ ] **步骤 3：报告**

`docs/superpowers/plans/ui-ledger-cleanup/report.md`：四任务逐项验收口径+结果、豁免清单终态（hljs/dark-only 逐处列出）、deviation 记录（选择器实测修正、#fff 语境判断逐处）。
