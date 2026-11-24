# 图谱页国内风格收口 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 七项国内风格收口——画布灰底点阵、节点白底描边强化、边标签中文化+默认隐藏（hover/钉住显示+开关）、左下工具栏归组、"布局收敛中"tag 化、顶栏去 GitHub、时间轴双"2026"修复。

**架构：** 三任务：T1 画布视觉层（graphVisuals.ts 网格/底色 + graphCanvas.ts settings + index.vue 边标签开关与中文映射 + i18n）；T2 杂项（index.vue 工具塔归组/tag 化 + Navbar.vue 去 Git + time-scrubber.tsx 双标签）；T3 回归汇总。零业务逻辑（边标签开关是纯展示态）。

**技术栈：** sigma.js settings/绘制程序、Vue3 SFC、i18n 字典、独立 Playwright。

**规格：** `docs/superpowers/specs/2026-11-24-graph-domestic-style-design.md`（已获批准）

**侦察事实（实现者必读）：**
- 节点 pill 绘制：`src/domain/pages/graphVisuals.ts:202-209`——`strokeStyle = ring?RING_SELECT:typeColor`、`roundRect`、`fillStyle = tint`（浅彩底）、`fillStyle = CANVAS_TEXT`（统一墨字）。改造点：tint 亮度提高（更接近白/极浅彩）、描边加重（1.5px）、文字色由墨字改 typeColor 深档（彩字）；hover/选中实心反转走既有 ring/tint 分支语义调整。
- 网格：`graphVisuals.ts:275` 线制网格（INK_RGB × GRID_MAX_ALPHA）→ 改点阵（dot 矩阵绘制或 CSS 容器点阵二选一，**优先绘制层**——与 sigma 背景同层可控）；画布底色：查 graphVisuals/graphCanvas 的 clear/backgroundColor 设置点（sigma 默认白，改 `#F7F8FA` 亮 / 暗档深灰）。
- sigma settings：`src/domain/pages/graphCanvas.ts:139 sigmaOptions()`——labelColor/edgeLabelColor/labelFont 在此；`renderEdgeLabels` 由调用方传（graph/index.vue:776 `renderEdgeLabels: true` + :1087 按 zoom 动态 setSetting）。
- 边标签：graph/index.vue edgeReducer（侦察 :820+ 区域）设边 attrs；label 来源=谓词键（invested_in 等）。
- 中文映射落点：新建 `src/views/jknow/knowledge/graph/predicate-zh.ts` 常量表 + edgeReducer 里 label 映射；演示数据已知键以实际库谓词为准（实测 SELECT DISTINCT predicate 或读 graph 数据 shape）。
- 开关：卡头行2"边样式"弹层（上批已建 el-popover）内加 `el-checkbox "显示全部标签"`（默认关）→ `setSetting("renderEdgeLabels", on)`；关=默认隐藏。**hover/钉住边显示**：enableEdgeEvents 已开——hover 边时用既有 hover 机制联动（edgeReducer 给 hover/钉住边单独置 label，其余隐藏；或 HTML 浮层，实现者按现状机制择形并记录）。
- 左下工具塔：index.vue :1588 容器（`flex flex-col items-start gap-2`）内三座 ToolTower → 合并一座，组间 ToolDivider。
- stabilizing：:524 ref、:569 复位、文案 zh.ts:1032 `stabilizing: "布局收敛中"`；模板位置在卡头行1（上批迁入）。
- Navbar Git：`src/layout/components/Navbar.vue:77 import RuoYiGit from '@/components/RuoYi/Git/index.vue'`——模板中找到对应标签删除（连带 tooltip）；组件文件本体保留（其它处可能用）。
- 时间轴：`src/views/jknow/knowledge/graph/time-scrubber.tsx`（:174 年月日格式化、:179 minYear）；双"2026"成因在模板标签段（实施时实读定位——疑似选择区间端标签与轴端标签同值都渲染）。
- i18n：`src/i18n/dicts/zh.ts`（graph 段 :1032 附近）/`en.ts` 同步新增键。
- 永不提交：auto-imports.d.ts、vite.config.ts（baseUrl 环境态）、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md。
- 分支：`feat/graph-domestic-style`（自 master 切出）。dev 环境 24110/27401（在跑则用；挂了按 README 恢复）。图谱页直达 /kb/0190d3e0-0000-7d5a-8000-000000000001/graph。
- 走查纪律：独立 Playwright + DOM 断言先行；截图存 docs/superpowers/plans/graph-style/。grep 用 LC_ALL=C grep -a。

---

### 任务 1：画布层次 + 节点描边 + 边标签中文化默认隐藏（§1+§2+§3）

**文件：**
- 修改：`jknow-web/src/domain/pages/graphVisuals.ts`（网格点阵/底色/节点 pill 绘制）
- 修改：`jknow-web/src/domain/pages/graphCanvas.ts`（sigma settings：背景/边标签默认值如需）
- 修改：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（edgeReducer 中文映射、开关、"边样式"弹层加 checkbox、renderEdgeLabels 接线）
- 创建：`jknow-web/src/views/jknow/knowledge/graph/predicate-zh.ts`（谓词中文映射常量）
- 修改：`jknow-web/src/i18n/dicts/zh.ts` + `en.ts`（"显示全部标签"新键）

- [ ] **步骤 1：画布底色+点阵网格（graphVisuals.ts）**

- 画布清屏底色：亮色 `#F7F8FA`、暗色既有暗档深灰（读暗色分支现值定）；网格线制（:275）改点阵——按网格步长画 dot（fillRect 1.5px 圆点或 arc，ink-3 @ 8-10% alpha），暗色 alpha 再降。
- `pnpm dev` 热更新后 Playwright 快照验证画布底色/点阵可见（getComputedStyle 容器或截图目验）。

- [ ] **步骤 2：节点白底描边强化（graphVisuals.ts:202-209）**

- tint 亮度提档（更接近白/极浅彩——以截图对比微调）；描边宽 1.5px 且选中/常态都用 typeColor 深档；文字 `CANVAS_TEXT` → typeColor 深档（彩字）。hover/选中分支（ring=RING_SELECT 时）反转实心：fill=typeColor、text=白/反色。
- 暗色主题对应档（底深灰、描边/文字亮彩）。红绿对比度目验（4.5:1 量级）。

- [ ] **步骤 3：边标签中文化+默认隐藏+开关（index.vue + predicate-zh.ts + i18n）**

- `predicate-zh.ts`：`export const PREDICATE_ZH: Record<string,string> = { invested_in: "投资", … }`（键集实测：`docker exec jknow-pg-1 psql -U postgres -d jknow -tc "SELECT DISTINCT predicate FROM facts LIMIT 30;"` 或读 graph 数据 edges 的 label 来源——以代码里边 label 赋值点实测为准）。
- edgeReducer：`res.label = PREDICATE_ZH[raw] ?? raw`。
- 默认隐藏：:776 `renderEdgeLabels: true` → `false`；:1087 zoom 动态 setSetting 删除或改由开关统一接管（**开关为唯一事实源**，zoom 联动逻辑移除并记录）。
- 开关：edge 样式弹层内加 `el-checkbox v-model="showAllEdgeLabels"`（i18n 新键 `showAllEdgeLabels`：zh"显示全部标签"/en"Show all edge labels"）；watch → `sigma.setSetting("renderEdgeLabels", showAllEdgeLabels)`。
- hover/钉住边显示：侦察 index.vue 现有 edge hover 机制（enableEdgeEvents=true）——实现"hover 边/钉住事实时该边标签显示（其余仍隐藏）"，机制择形（edgeReducer 按 hoverEdge 置 label/hidden，或 HTML 浮层），报告记录。

- [ ] **步骤 4：验证与 Commit**

- `pnpm typecheck && pnpm test`（425）。
- 走查：1280/1920 × 亮暗 四截图 `gs-01~04`（画布灰底点阵/节点白底描边彩字/无边标签平铺）；hover 一条边截图 `gs-05-edge-hover.png`（中文标签显示）；开关开态 `gs-06-labels-all.png`（中文标签全显）；console 零 error。
- Commit：`git add src/domain/pages/graphVisuals.ts src/domain/pages/graphCanvas.ts src/views/jknow/knowledge/graph/index.vue src/views/jknow/knowledge/graph/predicate-zh.ts src/i18n/dicts/zh.ts src/i18n/dicts/en.ts && git commit -m "style(web): 图谱画布国内风收口——灰底点阵/节点白底描边彩字/边标签中文默认隐藏（hover+开关显示）"`

---

### 任务 2：工具栏归组 + tag 化 + 去 GitHub + 时间轴双标签（§4-§7）

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（ToolTower 合并、stabilizing tag 化）
- 修改：`jknow-web/src/layout/components/Navbar.vue`（去 RuoYiGit）
- 修改：`jknow-web/src/views/jknow/knowledge/graph/time-scrubber.tsx`（双 2026）
- 修改：`jknow-web/src/i18n/dicts/zh.ts` + `en.ts`（tag 文案如需新键）

- [ ] **步骤 1：工具塔合并（§4）**

三座 ToolTower（:1588 容器内）合并为一座，组间插 `<ToolDivider />`：`[魔棒] divider [orbit/lasso/pack] divider [zoom in/out/fit]`；按钮/label/@click 零增删。

- [ ] **步骤 2：stabilizing tag 化（§5）**

行1 裸文本（`{{ S.graph.stabilizing }}` 或等价）改：

```html
<span v-if="stabilizing" class="flex items-center gap-1 rounded-full bg-primary-soft px-2 py-0.5 text-aux text-primary">
  <spinner 小图标 class="animate-spin" /> {{ S.graph.stabilizing }}
</span>
```

（spinner 用仓内既有 loading 图标/lucide loader-2；i18n 键复用不新增。）

- [ ] **步骤 3：去 GitHub（§6）**

Navbar.vue 删 RuoYiGit import 与模板标签（:77 + 模板处）；组件文件本体保留。

- [ ] **步骤 4：时间轴双"2026"修复（§7）**

实读 time-scrubber.tsx 模板标签段定位双标签成因（选择端标签与轴端标签同值都渲染）→ 修复：同值时只渲染一个（v-if 或计算合并）；scrub 交互零动。修后截图验证拖动到不同年份时双端标签正常显示。

- [ ] **步骤 5：验证与 Commit**

`pnpm typecheck && pnpm test`。走查：1280×亮暗 截图 `gs-07-toolbar.png`/`gs-08-dark.png`（工具塔单列三组、无 GitHub、stabilizing tag 抓拍——stabilizing 只在初次布局 ~2.5s 窗口出现，截图可在强刷后立即连拍或在报告记录代码路径验证）；时间轴拖动验证单一标签。console 零 error。
Commit：`git add -A src/views/jknow/knowledge/graph/index.vue src/views/jknow/knowledge/graph/time-scrubber.tsx src/layout/components/Navbar.vue src/i18n/dicts/ && git commit -m "style(web): 图谱工具塔归组+布局收敛 tag 化+顶栏去 GitHub+时间轴双年份标签修复"`

---

### 任务 3：全量回归与证据汇总

- [ ] **步骤 1：全量验证链**

`pnpm test && pnpm typecheck && pnpm build:prod`；双 smoke（JKNOW_APP_BASE=27401；**跑毕复位 captcha**：UPDATE true+redis DEL+复查）。压测库共存确认（count=170 不变）。

- [ ] **步骤 2：报告与归档**

`docs/superpowers/plans/graph-style/report.md`：七项逐项前后对照（截图引用 gs-01~08）、谓词映射表全量、hover/钉住显示机制择形记录、时间轴双标签成因与修法、deviation 清单。截图 add 入库。commit `docs(plan): graph-style 证据汇总报告`。
