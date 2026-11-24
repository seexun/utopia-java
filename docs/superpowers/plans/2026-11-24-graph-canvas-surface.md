# 图谱页容器层次修正 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 消「页面灰→白卡→画布灰」三层套娃——外层卡收敛为细框、卡头保持白底、画布铺独立令牌 `--color-canvas`（亮 #FAFBFC/暗 #161616），形成「白卡头→细线→灰画布」两段式。

**架构：** 单任务小改。新增 1 个语义令牌（tokens.scss 双档 + tailwind.css 同步）；`.graph-canvas-card` 背景透明仅留边框圆角；卡头块显式 `bg-card`；`drawWorldGrid` 底色读 `--color-canvas`。本体页 schema-graph 共用绘制层自动跟随。

**技术栈：** CSS 令牌 + Canvas 2D 绘制；独立 Playwright 像素实测走查。

**规格：** `docs/superpowers/specs/2026-11-24-graph-canvas-surface-design.md`（已获批准）

**侦察事实（实现者必读）：**
- 令牌双源：`src/style/tokens.scss`（:28 `--color-bg: #f5f7fa;` 在 :root；:143 `--color-bg: #1a1a1a;` 在 html.dark 块）与 `src/style/tailwind.css`（:80 `--color-bg`、:86 `--color-card: #ffffff`、:130 html.dark `--color-card: #212121`）。**两处都要加 `--color-canvas`**。
- `.card` 全局定义：`src/style/theme.scss:33`（`background-color: var(--el-bg-color)` + border + radius + shadow）——图谱卡在其上加 `.graph-canvas-card` 覆写（scoped，`index.vue:1844`：`padding:0; overflow:hidden`）。
- 卡头块：`index.vue` 模板 `:1312` 附近 `<div class="border-b border-line px-3 pb-2 pt-2">`（两行卡头容器）——卡片背景透明后需显式加 `bg-card`。
- 画布底色绘制：`src/domain/pages/graphVisuals.ts` `drawWorldGrid()` 内 `ctx.fillStyle = token("--color-bg", CANVAS_BG);`——改读 `--color-canvas`；模块常量 `CANVAS_BG` 初值同步改 `#fafbfc`。
- 本体页图示卡：`ontology/index.vue:626` `class="card onto-canvas-card …"`——共用 `drawWorldGrid`，画布底自动跟随；其卡头（上批加的 slim 头）需同样确认白底（`.onto-canvas-card` 卡背景是否透明以实测定——**规格只要求画布底同令牌，本体卡结构不动**；若本体卡仍整白则画布灰在白卡内，属本体页既有形态，本批不改，报告记录）。
- 点阵 alpha：`graphVisuals.ts` 点阵 `GRID_MAX_ALPHA * 3 * t`——画布底变亮后目验，过重则降到 `* 2.2`。
- 主题切换重绘已就位（上批 `watch(settingsStore.isDark)` + nextTick）——令牌翻转自动生效，无需新增逻辑。
- 环境：dev 24110/27401（挂了按 README 恢复：Docker Desktop→compose up→后端 jar→pnpm dev --port 24110 --strictPort）。图谱页 `/kb/0190d3e0-0000-7d5a-8000-000000000001/graph`。走查脚本可复用 `.playwright-cli/gs-walkthrough.js`（captcha 经 `dc1-lib2.js` docker exec redis）。
- 永不提交：auto-imports.d.ts、vite.config.ts（baseUrl 环境态）、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md。
- 分支：`feat/graph-canvas-surface`（自 master 切出）。

---

### 任务 1：令牌 + 卡片收敛 + 画布底换档 + 验证

**文件：**
- 修改：`jknow-web/src/style/tokens.scss`（:root + html.dark 各加 1 行）
- 修改：`jknow-web/src/style/tailwind.css`（亮 + html.dark 各加 1 行）
- 修改：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（卡头 `bg-card` + scoped 卡片背景透明）
- 修改：`jknow-web/src/domain/pages/graphVisuals.ts`（底色读新令牌 + 常量初值 + 点阵 alpha 如需）

- [ ] **步骤 1：新增 `--color-canvas` 令牌（双源四行）**

tokens.scss :root（:28 `--color-bg` 后）：
```scss
  --color-bg: #f5f7fa;
  /* 画布底（图谱/本体工作区）：亮比页灰亮一档、比纯卡白暗一档——夹在白卡头与
     页面灰之间层次连续，且与页面灰不同色（同色会读成"白卡开了个洞"） */
  --color-canvas: #fafbfc;
```
tokens.scss html.dark（:143 后）：
```scss
  --color-bg: #1a1a1a;
  --color-canvas: #161616; /* 比页底深一档、比卡 #212121 深两档，同向逻辑 */
```
tailwind.css :80 后加 `--color-canvas: #fafbfc;`；:130 html.dark 块加 `--color-canvas: #161616;`（与 card 同块同风格，注释一行）。

- [ ] **步骤 2：卡片收敛为细框，卡头显式白底**

index.vue 模板卡头容器（`<div class="border-b border-line px-3 pb-2 pt-2">`）→ 加 `bg-card`：
```html
<div class="border-b border-line bg-card px-3 pb-2 pt-2">
```
scoped `.graph-canvas-card`（:1844）改为：
```scss
.graph-canvas-card {
  padding: 0;
  overflow: hidden;
  /* 容器层次修正（2026-11-24）：不再整卡白底——收敛为细框（边框+圆角），
     卡头显式 bg-card、画布铺 --color-canvas，两段式消三层套娃 */
  background-color: transparent;
  box-shadow: none;
}
```
（box-shadow 去掉：透明卡上的投影会露出"空框"感；细框已足够分层。）

- [ ] **步骤 3：画布底读新令牌**

graphVisuals.ts `drawWorldGrid`：`token("--color-bg", CANVAS_BG)` → `token("--color-canvas", CANVAS_BG)`；`export let CANVAS_BG = "#f5f7fa"` → `"#fafbfc"`；`refreshPalette` 内 `CANVAS_BG = token("--color-bg", CANVAS_BG)` → `--color-canvas`（三处同名替换）。点阵 alpha 先保持 `* 3`，走查后过重再降。

- [ ] **步骤 4：验证**

- `cd jknow-web && pnpm typecheck && pnpm test`（425）。
- Playwright 走查（复用 gs-walkthrough.js 改断言）：画布像素亮 `250,251,252`、暗 `22,22,22`；卡头 computed `background-color` 为 `rgb(255,255,255)`（亮）；卡片容器 `background-color` 为 `rgba(0,0,0,0)`；四截图 `cs-01~04`（1280/1920×亮暗）存 `docs/superpowers/plans/graph-canvas-surface/`；console 零 error。目验：页面灰仅在卡外侧留白带、无"开洞"感、点阵不抢眼。
- 本体页 `/ontology` 抽验一屏（画布底同令牌）截图 `cs-05-ontology.png`。

- [ ] **步骤 5：Commit**

```bash
git add jknow-web/src/style/tokens.scss jknow-web/src/style/tailwind.css jknow-web/src/views/jknow/knowledge/graph/index.vue jknow-web/src/domain/pages/graphVisuals.ts
git commit -m "style(web): 图谱容器层次修正——独立 --color-canvas 令牌+卡片收敛细框+卡头显式白底，白卡头/灰画布两段式消三层套娃"
```

---

### 任务 2：回归与证据

- [ ] **步骤 1：全量回归**

`pnpm test && pnpm typecheck && pnpm build:prod`；双 smoke（`JKNOW_APP_BASE=http://localhost:27401`，跑毕复位 captcha + 复种 seed-demo/seed-nodebudget-demo——smoke-p1 会级联清库，纪律在案）。

- [ ] **步骤 2：报告**

`docs/superpowers/plans/graph-canvas-surface/report.md`：前后像素对照（亮 245,247,250→250,251,252；暗 26,26,26→22,22,22）、卡片/卡头 computed 值、点阵 alpha 终值、本体页跟随情况、截图引用。commit `docs(plan): graph-canvas-surface 证据汇总报告`。
