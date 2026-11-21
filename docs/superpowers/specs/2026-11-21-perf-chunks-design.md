# 性能批规格（echarts 按需引入+hljs 入桶收口+分页条基座压制拆除）

- 日期：2026-11-21
- 状态：已获用户批准（三节设计 OK）
- 背景：收尾清账批（f6179d7）已合并；账本仅剩性能批（本规格）与测试基建批（留档独立立项）。现产物：vendor-utils 1.2MB（echarts 大头）/ vendor-ep 1.1MB / 主 entry 796K。

## 侦察结论（规格依据）

1. echarts 全仓唯一消费点 `src/views/monitor/cache/index.vue:69` `import * as echarts from 'echarts'`（全量），用 API：`echarts.init` + BarChart + PieChart。
2. `init(dom, "macarons")` 的 macarons 主题**全仓无 `registerTheme`**——从未生效的死参数（echarts 对未注册主题名回退默认主题）。
3. hljs 无直接 import，经 `rehype-highlight`（`src/views/jknow/knowledge/chat/markdown.tsx:25`）传递；`rehype-highlight` 钉在 vendor-md 桶（vite.config.ts:71-77）但 `highlight.js`/`lowlight` 未钉——rollup 将其分配到主 entry（index-qjUDNutX 796K 的大头，grep 实证 hljs 在主 entry 而非 vendor-md）。
4. `ruoyi.scss:116` `.pagination-container { background-color: transparent !important; }` 基座规则压制组件侧声明——上批已把 Pagination/index.vue:100 改 `var(--color-card)` 但被此压制（渲染等价改动待激活）。

## 设计

### §1 echarts 按需引入（任务 1）

- `monitor/cache/index.vue` 按需化：

```ts
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([BarChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
```

（组件清单以页面 option 实况核对补齐——如用了 title/legend 等对应 Component 一并注册；`echarts.init` 从 `echarts/core` 导出同名可用。）
- 两处 `init(dom, "macarons")` → `init(dom)`（死主题参数删除，行为等价）。
- vite.config.ts manualChunks `vendor-utils` 数组移除 `'echarts'`（剩 `['axios', 'dayjs', '@vueuse/core', 'qs']`）——按需后 echarts 仅被懒加载页引用，vite 自动随页 code-split。
- 效果预估：vendor-utils 1.2MB → ~250KB；echarts ~350KB 随 monitor/cache 页懒加载（登录+首页不拉）。

### §2 hljs 入桶收口（任务 2）

- vite.config.ts manualChunks `vendor-md` 数组补 `'highlight.js'`（lowlight 随依赖链归桶）：

```ts
'vendor-md': ['remark-parse', 'remark-rehype', 'remark-gfm', 'rehype-highlight', 'unified', 'highlight.js']
```

- 效果预估：主 entry 796K → ~350-400KB；vendor-md 312K → ~800K（独立缓存桶，业务发版不动其 hash）。实测数字前后对比表入报告。

### §3 分页条基座压制拆除（任务 2 内）

- `ruoyi.scss:116` 删除 `background-color: transparent !important;` 一行（注释同步清理）——Pagination 组件侧 `var(--color-card)` 激活：亮态白卡/暗态 #212121 自动翻转。
- 走查：带分页列表页（系统管理→用户）亮/暗双态截图；确认弹窗内分页（`ruoyi.scss:120` 后续规则）不回归。

## 验收

1. build:prod 产物前后对比表（vendor-utils/vendor-ep/vendor-md/主 entry/echarts 懒加载 chunk）入报告；vendor-utils ≤300KB、主 entry 显著缩减。
2. network 验证：登录+首页 network 面板无 echarts chunk 请求；缓存监控页打开时才拉且 Bar/Pie 双图渲染正常（截图）。
3. 分页条亮/暗双态走查截图；console 零 error。
4. `pnpm test` 425 基线零退化 + typecheck + 双 smoke 零退化。
5. vite.config.ts 提交纪律（本地 baseUrl=27401 环境态永不入库）：commit 前临时还原该行为 HEAD 值（`http://localhost:8080`）→ `git add vite.config.ts` 提交功能改动 → 改回 `http://localhost:27401`。三步一气呵成，报告记录。

## 不做的事

- vendor-ep 1.1MB 拆解（EP 按需组件改造面大，独立评估立项）。
- 业务懒加载 chunk（index-CS4hKg7r 等）不动——正常形态。
- 测试基建批（golden/pipeline/热身 SQL）维持留档独立立项。
- hljs 八色/gvdesign/ 零改区维持；markdown.tsx 生产码零动（纯打包配置变更）。
