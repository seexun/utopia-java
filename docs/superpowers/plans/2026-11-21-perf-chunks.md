# 性能批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 产物瘦身——echarts 按需引入（vendor-utils 1.2MB→~250KB）、hljs 入桶收口（主 entry 796K→~350-400KB）、分页条基座压制拆除（var(--color-card) 激活）。

**架构：** 三任务：T1 monitor/cache 按需化+manualChunks 移除 echarts；T2 manualChunks 补 highlight.js+ruoyi.scss 基座行删除；T3 产物前后对比表+network/走查验证+双 smoke 回归。纯打包与消费面改造，业务逻辑零动。

**技术栈：** Vite manualChunks、echarts/core 按需 API、Playwright network 面板断言。

**规格：** `docs/superpowers/specs/2026-11-21-perf-chunks-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`，前端 `jknow-web/`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：读 `/tmp/jknow-ports.txt`（后端 27401 / 前端 24110）；未起按 README 恢复。走查独立 Playwright+DOM 断言先行。
- **vite.config.ts 提交纪律（每任务涉及 vite.config 时必须执行）**：该文件工作区带本地环境态 `baseUrl = 'http://localhost:27401'`（HEAD 值为 `'http://localhost:8080'`）。commit 三步：①把 baseUrl 行临时还原为 `'http://localhost:8080'` ②`git add vite.config.ts` + commit ③把 baseUrl 改回 `'http://localhost:27401'`。baseUrl 环境态永不入库。
- 永不提交：`jknow-web/auto-imports.d.ts`、`jknow-web/vite.config.ts` 的 baseUrl 改动、`tests/__snapshots__/*.snap`、`.playwright-cli/`、`jknow-web/src/docs/*.md`。
- 分支：`feat/perf-chunks`（自 master 切出）。

---

### 任务 1：echarts 按需引入（§1）

**文件：**
- 修改：`jknow-web/src/views/monitor/cache/index.vue:69-70`（import 区）与 :82/:101（init 死主题参数）
- 修改：`jknow-web/vite.config.ts:78`（manualChunks vendor-utils 数组）

- [ ] **步骤 1：monitor/cache 按需化改写**

替换 :69 `import * as echarts from 'echarts'` 为（页面实况 API：pie(rose)+gauge+tooltip，无 grid/legend）：

```ts
import * as echarts from 'echarts/core'
import { GaugeChart, PieChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GaugeChart, PieChart, TooltipComponent, CanvasRenderer])
```

（import 区已有 auto-import；若 typecheck 报缺组件（如 gauge 需额外 Component），按报错最小补注册并在报告记录。）

- [ ] **步骤 2：删除死主题参数**

:82 `echarts.init(commandstats.value!, "macarons")` → `echarts.init(commandstats.value!)`
:101 `echarts.init(usedmemory.value!, "macarons")` → `echarts.init(usedmemory.value!)`
（macarons 全仓无 registerTheme，从未生效——删除为行为等价。）

- [ ] **步骤 3：manualChunks 移除 echarts**

vite.config.ts:78 `'vendor-utils': ['axios', 'dayjs', '@vueuse/core', 'echarts', 'qs']` → `'vendor-utils': ['axios', 'dayjs', '@vueuse/core', 'qs']`

- [ ] **步骤 4：验证**

- `cd jknow-web && pnpm typecheck && pnpm build:prod`。
- 产物断言：`du -h dist/static/js/vendor-utils-*.js`（预期 ~250KB 级，1.2MB 显著缩）；`ls dist/static/js/ | grep -c "vendor-utils"` 为 1；echarts 相关代码应随 monitor 页 chunk——`for f in dist/static/js/index-*.js; do echo "$f: $(LC_ALL=C grep -c 'gauge' $f)"; done | grep -v ': 0'` 找出含 gauge 的懒加载 chunk 并记录文件名（预期非主 entry）。
- `pnpm test` 425 基线零退化。

- [ ] **步骤 5：Commit（baseUrl 三步纪律）**

```bash
# ①还原环境态
cd /d/workspace_com/jknow/jknow-web && sed -i "s|const baseUrl = 'http://localhost:27401'|const baseUrl = 'http://localhost:8080'|" vite.config.ts
git add src/views/monitor/cache/index.vue vite.config.ts
git commit -m "perf(web): echarts 按需引入（pie+gauge+tooltip）——vendor-utils 瘦身 +死主题参数清理"
# ③恢复环境态
sed -i "s|const baseUrl = 'http://localhost:8080'|const baseUrl = 'http://localhost:27401'|" vite.config.ts
```

---

### 任务 2：hljs 入桶 + 分页条基座压制拆除（§2+§3）

**文件：**
- 修改：`jknow-web/vite.config.ts:71-77`（vendor-md 数组）
- 修改：`jknow-web/src/assets/styles/ruoyi.scss:111-117`（基座规则）

- [ ] **步骤 1：vendor-md 补 highlight.js**

vite.config.ts vendor-md 数组（:71-77）末尾补一项：

```ts
'vendor-md': [
  'remark-parse',
  'remark-rehype',
  'remark-gfm',
  'rehype-highlight',
  'unified',
  'highlight.js'
]
```

- [ ] **步骤 2：删除分页基座压制行**

ruoyi.scss:111-117 `.pagination-container` 块内删除 `background-color: transparent !important;` 一行（保留块内其余 display/justify-content/margin-top）。

- [ ] **步骤 3：验证**

- `cd jknow-web && pnpm build:prod`。
- 产物断言：`du -h dist/static/js/vendor-md-*.js dist/static/js/index-*.js | sort -rh | head -5`——vendor-md 增至 ~800KB 级、主 entry（最大 index-*.js）缩至 ~350-400KB 级；`for f in dist/static/js/index-*.js; do echo "$f: $(LC_ALL=C grep -ac 'hljs' $f)"; done` 确认 hljs 字面已不在主 entry。
- `pnpm test` 425 + `pnpm typecheck`。

- [ ] **步骤 4：走查（分页条激活+图表回归）**

dev 环境独立 Playwright：系统管理→用户页（DOM 断言 `.pagination-container` 存在）→亮态截图 `t2-01-pagination-light.png`（分页条白卡底）→切暗态截图 `t2-02-pagination-dark.png`（#212121）；监控→缓存监控页（pie+gauge 双图）DOM 断言 canvas 存在 → 截图 `t2-03-cache-charts.png`。console 零 error。

- [ ] **步骤 5：Commit（baseUrl 三步纪律）**

```bash
cd /d/workspace_com/jknow/jknow-web && sed -i "s|const baseUrl = 'http://localhost:27401'|const baseUrl = 'http://localhost:8080'|" vite.config.ts
git add vite.config.ts src/assets/styles/ruoyi.scss
git commit -m "perf(web): hljs 入 vendor-md 桶——主 entry 瘦身 +分页基座压制拆除（card 令牌激活）"
sed -i "s|const baseUrl = 'http://localhost:8080'|const baseUrl = 'http://localhost:27401'|" vite.config.ts
```

---

### 任务 3：Quill 桶化+产物对比+network 验证+回归汇总（分支终审前置）

> 计划修正（用户拍板 B）：T2 实证 hljs 假设证伪（本体早已在 vendor-md 桶，主 entry 无变化）；主 entry 796K 真实大头为 Quill（main.ts:118 Editor 全局注册，消费方仅首页+审查页）。本任务增补 Quill 桶化达成"主 entry 显著缩"的本批目标（规格 §2 意图的修正实现路径）。

- [ ] **步骤 0：Quill 桶化（增补）**

vite.config.ts manualChunks 增一行桶（与 vendor-md 同构）：

```ts
'vendor-quill': ['quill', '@vueup/vue-quill']
```

（quill@2.0.2 与 @vueup/vue-quill@1.2.0 均为 package.json 直接依赖——无幽灵依赖问题。）`pnpm build:prod` 后断言：主 entry 最大 index-*.js 显著缩（~200KB 级，Quill ~590KB 移出）；`ls dist/static/js/ | grep vendor-quill` 存在。走查：首页+审查页（用 Editor 的页面）富文本编辑器渲染正常（DOM 断言 .ql-editor 存在）——截图 `t3-00-quill-pages.png`。

- [ ] **步骤 1：network 首屏验证**

dev 环境独立 Playwright（**注意：dev 模式不分包，需用 preview 模式验证 network**）：`cd jknow-web && pnpm preview --port 24111 --strictPort &`（preview 走 dist 产物；dist 为最新 build:prod 产物）。访问登录页→记录 network JS 请求清单（断言无 echarts/gauge 相关 chunk）→登录进首页→再断言仍无→进缓存监控页→断言此时才拉 echarts chunk 且双图渲染。记录请求清单进报告。完成后 kill preview 进程。

- [ ] **步骤 2：产物前后对比表**

从 git 历史取改造前产物数字（master 的 f6179d7 版本 `git stash` 或直接用报告引用侦察值：vendor-utils 1.2MB/vendor-ep 1.1MB/主 entry 796K/vendor-md 312K）与现产物并列成表（vendor-utils/vendor-ep/vendor-md/主 entry/echarts 懒加载 chunk 五行）。

- [ ] **步骤 3：全量回归**

`cd jknow-web && pnpm test && pnpm typecheck`；`cd /d/workspace_com/jknow && JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh && JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh`。

- [ ] **步骤 4：报告与证据归档**

截图（t2-01/02/03 + network 清单）存 `docs/superpowers/plans/perf-chunks/`；报告 `report.md`：对比表、network 断言结果、baseUrl 三步纪律执行记录（两任务各一次）、deviation 清单。commit `docs(plan): perf-chunks 证据汇总报告`。
