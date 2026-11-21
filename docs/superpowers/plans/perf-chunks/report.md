# 性能批（perf-chunks）证据汇总报告

分支 `feat/perf-chunks`，三任务三 commit（T3 另含 docs 归档 commit）。规格：`docs/superpowers/plans/2026-11-21-perf-chunks.md`（2026-11-21 获批）；计划修正：T3 增补 Quill 桶化（用户拍板 B，T2 实证 hljs 假设证伪后的方向修正）。

## 0. 三任务验收口径

| 任务 | Commit | 改动 | 验收结论 |
|---|---|---|---|
| T1 echarts 按需 | `f46c1ae` | monitor/cache 改 `echarts/core`+Pie/Gauge/Tooltip/Canvas 按需；manualChunks vendor-utils 去 echarts；删 init 死主题参数；PieChart 图标别名补丁 | **DONE**：vendor-utils 1.2MB→114KB（**-91%**）；echarts 落缓存监控页懒加载 chunk（cache-\*.js 444,029B raw / 148KB gz），仅该页拉取 |
| T2 hljs 入桶+分页基座拆除 | `333e0eb` | manualChunks vendor-md +`highlight.js`（钉桶）；pnpm add highlight.js@11.11.2（幽灵依赖修复）；ruoyi.scss 删分页条透明压制行 | **DONE**（产物维度假设证伪见 §2）：分页条 card 令牌激活（亮 #fff / 暗 #212121 双态验证，t2-01/02）；hljs 本体 T2 前就已随 rehype-highlight 闭包在 vendor-md（312K 前后不变） |
| T3 Quill 桶化+验证汇总 | `c24e994` | manualChunks 新增 `vendor-quill`；pnpm add quill@2.0.2（幽灵依赖修复，简报预判"无幽灵依赖"证伪） | **DONE**：主 entry 814,657B→608,334B（**-206,323B raw / -61,357B gzip（≈-59.9KB），-25.3%**），vendor-quill 205,545B raw / 61,563B gzip 独立桶；network 三页断言全过（§3）；全量回归全绿（§5） |

## 1. 产物前后对比表（pnpm build:prod，raw 字节）

| chunk | 批前基线（f6179d7 侦察值） | T3 前（333e0eb 干净重建实测） | T3 后（c24e994） | 批内净变化 |
|---|---|---|---|---|
| vendor-utils | 1.2MB（echarts 全量在内） | 113,785 B | 113,785 B | **-1,129,331 B（-91%，T1）** |
| vendor-ep | 1.1MB | 1,084,582 B | 1,084,582 B | 持平（未动） |
| vendor-md | 312K | 316,329 B | 316,329 B | 持平（T2 钉桶；hljs 早已在桶，见 §2） |
| 主 entry（index-\*.js） | 796K | 814,657 B | **608,334 B** | **-206,323 B（-25.3%，T3 Quill 桶化）** |
| echarts 懒加载 chunk | 无独立（在 vendor-utils 内） | cache-HU1ZNWAd.js 443,994 B | cache-Cx-VWlAQ.js 444,029 B | T1 起独立懒加载（仅缓存监控页拉取） |
| vendor-quill（T3 新增） | —（在主 entry 内） | —（在主 entry 内） | **205,545 B raw / 61,563 B gzip** | 新增独立桶，业务发版不动其 hash |

主 entry gzip：279,887B → 218,530B（**-61,357B ≈ -59.9KB**；raw -206,323B / -25.3%）。index.html 直引 JS：index + vendor-vue + vendor-utils + vendor-ep + vendor-quill（5 个）。
首屏 gzip 对冲说明：主 entry gz -59.9KB vs vendor-quill 首屏直引 +61,563B gz（Editor 全局注册）——首屏净 gz 近乎持平；本改动的真实收益是**缓存分离**（业务发版不动 vendor-quill hash，回访命中强缓存）与主 entry 解析/执行成本下降，而非首屏传输缩减。
注：批前"796K/312K/1.1MB"侦察值与本批 T3 前干净重建（795.56kb/308.92kb/1059.16kb，vite 输出口径）一致，数字互证成立。

## 2. hljs 证伪记录（T2 实证，T3 终裁）

- 简报背景"hljs 经 rehype-highlight 传递落在主 entry（796K 大头）"**不成立**：stash 对照构建两次 vendor-md hash 完全相同（Ds9BmwS9），hljs 本体（lowlight common 子集，37 语言）T2 前就已随 rehype-highlight 模块闭包归入 vendor-md。
- 主 entry 残留 4 处 'hljs' 字面全部来自 **Quill** 的 syntax/code-token 模块（报错串、`DEFAULTS={hljs:...}`），非 hljs 库代码——主 entry 796K 真实大头是 Quill。
- T2 改动的真实价值：显式钉桶防漂移 + 修复 pnpm 幽灵依赖（不修则构建直接失败）+ 分页基座拆除（UI 实际变化）。
- 方向修正（用户拍板 B）：T3 改做 Quill 桶化，达成"主 entry 显著缩"的批目标原意。

## 3. T3 Quill 桶化实现

### 3.1 diff（jknow-web/vite.config.ts，commit c24e994）

```diff
             'vendor-md': [
               'remark-parse', 'remark-rehype', 'remark-gfm',
               'rehype-highlight', 'unified', 'highlight.js'
             ],
-            'vendor-utils': ['axios', 'dayjs', '@vueuse/core', 'qs']
+            'vendor-utils': ['axios', 'dayjs', '@vueuse/core', 'qs'],
+            // Quill 桶（T3 增补）：Editor 组件全局注册于 main.ts，此前 quill+
+            // @vueup/vue-quill 随闭包落主 entry（~590KB，主 entry 796K 大头）；
+            // 拆独立桶后主 entry 显著缩，Quill 代码 hash 独立缓存
+            'vendor-quill': ['quill', '@vueup/vue-quill']
```

另：`pnpm add quill@2.0.2`（package.json dependencies +1，pnpm-lock.yaml +3）。**简报预判"quill@2.0.2 为 package.json 直接依赖——无幽灵依赖问题"证伪**：quill 仅出现在 overrides/resolutions（钉版本），不在 dependencies；pnpm 严格 node_modules 顶层无链接，首次构建报 `Could not resolve entry module "quill"`——与 T2 hljs 同型幽灵依赖，同款处置（显式直接依赖，版本与 @vueup/vue-quill 传递依赖 2.0.2 同实例，无双版本风险）。

### 3.2 产物断言

- `ls dist/static/js/ | grep vendor-quill` → `vendor-quill-DabgaIBt.js` 存在（205,545 B raw / 61,563 B gzip ≈ 61.5KB）。
- 主 entry `index-C8SGUcW4.js` 608,334 B（前 814,657 B，**-206,323 B raw / -61,357 B gzip（≈-59.9KB），-25.3%**）——"~200KB 级缩量"达成（raw 口径；gzip 口径为 -59.9KB，首屏对冲见 §1）；简报"Quill ~590KB"预估值口径不明（实测移出 205,545 B raw / 61,563 B gzip，590KB 疑为 Quill 未压缩 dist 体积误读，deviation #3）。
- vendor-quill 内 quill/ql-editor 字面 23 处；主 entry 'ql-editor' 0 处——Quill 代码彻底出主 entry。

### 3.3 走查（dev 24110，admin 登录，截图 t3-00-quill-pages.png 三屏）

| 屏 | 断言 | 结果 |
|---|---|---|
| 系统管理→通知公告→新增（Editor 全仓唯一模板消费方，system/notice/index.vue:147） | `.ql-editor` 存在 | ✅ `.ql-editor` ×1、`.ql-toolbar` ×1，console 零 error |
| 首页 /index | 页面渲染正常（**无 Editor**，deviation #1） | ✅ 若依首页完整渲染，零 error |
| 审查页 /kb/0190d3e0…f2/review | 页面渲染正常（**无 Editor**，deviation #1） | ✅ 审核/总览/待审核等全结构渲染，零 error |

## 4. network 首屏验证（preview 24111，dist=含 Quill 桶化的 build:prod 产物）

Playwright + performance API，传输大小为 gzip 实传（transferSize）。

**登录页（/login）JS 请求清单（6 个）：**

| JS | 传输 |
|---|---|
| index-C8SGUcW4.js（主 entry） | 214KB |
| vendor-ep-BrIfv8Ds.js | 333KB |
| vendor-quill-DabgaIBt.js | 61KB |
| vendor-vue-D34z0VJF.js | 58KB |
| vendor-utils-yfBTuArP.js | 40KB |
| login-C0D7azMI.js | 20KB |

断言：**无 cache-\*.js（echarts）、无 vendor-graph、无 vendor-md** ✅

**首页（/index）JS 请求清单（6 个）：** index-C8SGUcW4、vendor-vue、vendor-utils、**vendor-quill（61KB——Editor 全局注册于 main.ts:118，index.html 直引，预期内）**、vendor-ep、views-B4BbezgU。断言：仍**无 cache chunk** ✅，console 零 error ✅。（登录后落地 /kb/:id/graph 亦验证：+graph/vendor-graph 等页面 chunk，无 cache ✅）

**缓存监控页（/monitor/cache）：** 此刻才拉 `cache-Cx-VWlAQ.js` **149KB 传输（echarts 按需 chunk，懒加载断言达成）** + cache-uQAn2Knn.js 1KB（API 模块）；已加载的 index/vendor-\* 全部缓存命中（transferSize=0，分桶缓存价值实证）；canvas ×2（472×420 双图渲染）✅；console 零 error ✅。

环境说明（deviation #4）：vite preview 继承 server.proxy，生产产物 API 前缀为 `/prod-api`（.env.production）而代理表无此项——验证期间临时加 `/prod-api` 代理（环境态，验证后已还原，工作区 diff 仅剩 baseUrl）。验证完 preview 进程已 kill。

## 5. 全量回归（T3 后，全部通过）

| 项 | 结果 |
|---|---|
| `pnpm test` | **58 files / 425 passed**（13.15s，与批前基线持平） |
| `pnpm typecheck` | vue-tsc --noEmit 零错误 |
| `pnpm build:prod` | 成功；产物 hash 稳定复现（index-C8SGUcW4 / vendor-quill-DabgaIBt / cache-Cx-VWlAQ） |
| `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` | **ALL PASS** |
| `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh` | **ALL PASS（PASS=67 SKIP=1**，skip 为 JKNOW_LLM_API_KEY 未配的既有跳过项） |

## 6. baseUrl 三步纪律记录

| 次 | commit | ①27401→8080 | ②提交 | ③8080→27401 | ④`git show HEAD \| grep -c 27401` |
|---|---|---|---|---|---|
| T3 Quill 桶化 | `c24e994`（vite.config.ts+package.json+pnpm-lock.yaml，3 files +9/−1） | ✅ grep 确认 :11=8080 | ✅ | ✅ grep 确认 :11=27401 | **0** ✅ |
| T3 docs 归档 | （本 commit，不含 vite.config.ts） | 不适用（无 vite 改动，提交前核对工作区 vite.config.ts diff 仅 baseUrl 环境态） | — | — | 不适用 |

永不提交清单核对：auto-imports.d.ts、src/docs/\*.md、tests/\_\_snapshots\_\_/\*、.playwright-cli/、vite baseUrl 环境态均未入库；ui-walkthough/crop-usr-tree-y207.png（其他 plan 的 untracked 产物）未混入。

## 7. Deviation 清单（本报告如实记录）

1. **任务描述"Editor 消费方=首页（src/views/index.vue）+审查页（review/index.vue）"证伪**：两文件 grep 命中均为更新日志文本/权限注释，模板无 `<editor>`；全仓唯一模板消费方为 `src/views/system/notice/index.vue:147`（通知公告新增/修改对话框）。走查修正为：通知公告断言 `.ql-editor`（✅）；首页/审查页走查渲染无回归（Editor 全局注册链路经首页加载间接验证——vendor-quill 正常拉取执行且零 error）。
2. **简报"quill 为直接依赖、无幽灵依赖"证伪**（§3.1），处置 pnpm add quill@2.0.2 入库（他机/CI 构建必需）。
3. 简报"Quill ~590KB 移出"实测为 205,545 B raw（61,563 B gzip）；"主 entry 缩 ~200KB 级"达成（raw 口径 -206,323B；gzip 口径 -61,357B ≈ -59.9KB，首屏对冲见 §1）。
4. preview network 验证需临时 `/prod-api` 代理（环境态已还原）。
5. T2 截图（t2-01/02/03）与 t3-00 随本报告一并入库（T2 时未提交，按计划留 T3 统一归档）。
6. Quill 桶化后 index.html 直引多一个 vendor-quill（登录页/首页均拉 61KB gz）——Editor 全局注册的必然代价，已如任务口径记录；若后续要登录页也不拉它，需把 Editor 注册改按需（超本批范围，留待后续）。

## 8. 证据文件（本目录）

- t3-00-quill-pages.png——三屏走查（首页 / 通知公告对话框 .ql-editor / 审查页）
- t2-01-pagination-light.png / t2-02-pagination-dark.png / t2-03-cache-charts.png——T2 证据（双态分页条+缓存双图）
