# 图谱页工作区型页头合一——总报告（改造 + 修复轮 + 全量回归证据汇总）

日期：2026-11-24　分支：`feat/graph-workspace-header`
T1 改造：`6e4f121`　T1 修复轮：`00610f0`　本批（回归+归档）：零代码改动
计划：[2026-11-24-graph-workspace-header.md](../2026-11-24-graph-workspace-header.md)　截图目录：本目录（`gh-01~04.png`）

---

## 1. T1 改造摘要（commit `6e4f121`）

文件：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（+242/−240，唯一改动文件）

1. **删独立页头带**：`<PageHeader :title="S.nav.graph" :subtitle="kb?.name ?? ''" />` 及 import 删除（无残留引用）。
2. **悬浮条改两行 static 卡头**：原 `pointer-events-none absolute left-3 right-3 top-3 z-10` 容器改为卡内首个子块 `border-b border-line px-3 pb-2 pt-2`：
   - 行1：`h1 图谱`（改造后 `text-title font-semibold text-ink`）+ kb 名 chip（`rounded-full border-line bg-fill`）+ 右段（stabilizing 提示 + nodeBudget ± 步进器（disabled 逻辑原样）+ `statsText`）。
   - 行2：搜索框（`w-58`，建议框 absolute 以本行为参照）+ 返回全图钮（`v-if focusEntity`）+ 类型图例 chips（`flex min-w-0 flex-wrap` 允许折行）+ 边语义图例（`ml-auto flex-wrap justify-end`，去浮卡外框改无底 inline，五项原样）。
   - `pointer-events-none/auto` 整组语义删除（卡头是真实 UI）。
3. **画布容器**：`.card.graph-canvas-card` 加 `flex flex-col`（本体 `relative min-h-0 flex-1` 不动）；sigma 网格包裹层 `absolute inset-0` → `relative min-h-0 flex-1`（in-flow 满铺，必要偏差见 §6-2）。
4. **左下 ToolTower**：容器既有 `gap-2` 已统一间距，实测 `[8,8]`，零改码仅验证。

## 2. T1 修复轮（commit `00610f0`，审查 Important-1/2，裁定均为简报自身缺陷、实现无过错）

1. **I-1 标题字号升档**：`text-body`（14px）→ **`text-title`（16px）**（`--text-title` @tailwind.css:98，对齐规格 16-18px 档）；复拍 gh-01 实测 `getComputedStyle(h1).fontSize = "16px"`，其余断言不变。
2. **I-2 三浮层锚进画布包裹层**：错误行 `p`（`v-if data.isError`，`top-20`→`top-6` 按画布参照微调）、`<CanvasLoading>`、空态 `div` 从「卡直接子块」移入 `div.relative.min-h-0.flex-1`，锚定面变画布；`pb-20`（给时间岛让位）原样。左下 ToolTower / TimeScrubber / EntityPanel 锚卡底/卡右（=画布底/卡右）未动。
   动态加验：Playwright 路由拦截 `graph/overview` abort 强制取数失败 → 错误行实测在包裹层内、`top: 24px`、几何位于卡头之下（errTop 267 > 卡头底 229，不再叠行2）。

## 3. 四截图对照

改前基线：`D:\pw-gh\before-1280.png / before-1920.png`（改造前实测：`.page-header` 存在、`.graph-canvas-card` 顶部 y = **201.59px**）。
改后（本目录，已入库）：

| 文件 | 视口/主题 | 核验点 |
|---|---|---|
| `gh-01-1280-light.png` | 1280×800 亮 | 卡头两行、类型 chips 折 2 行、边图例折 2 行，无溢出；修复轮后复拍（16px 标题） |
| `gh-02-1280-dark.png` | 1280×800 暗 | Navbar 月亮切换后卡头/画布暗色正常 |
| `gh-03-1920-light.png` | 1920×1080 亮 | 行2 单行形态，边图例一行排开 |
| `gh-04-1920-dark.png` | 1920×1080 暗 | 暗色单行形态正常 |

画布顶部 y 基准：改造前 201.59px → 改造后 **132px**（上移 69.6px，1280/1920 相同）；卡头高 108px@1280（折行）/ 92px@1920（单行）。

## 4. DOM 断言记录（T1 walkthrough，20/20 PASS）

独立 Playwright（`D:\pw-gh\`，chromium，登录态 storageState，admin 算术验证码人读填）：

| # | 断言 | 结果 | 实测 |
|---|---|---|---|
| ① | `.page-header` 不存在 | PASS | pageHeaderGone=true |
| ②a/b/c | h1「图谱」/ kb chip / statsText | PASS | 图谱 / 演示：智造产业链 / 18 个实体 · 24 条事实 · 23 条现行 |
| ③a/b/d | 行2 搜索框 ∈ 卡头 / 类型 chips 6 枚 / 边语义图例 | PASS | 人物\|公司\|产品\|城市\|指标\|研发基地；边样式标签+5 项 |
| ③c/⑤ | nodeBudget ± 在卡头区（2 按钮） | PASS | 本库 18 实体 < 最小档 150 → 双键 disabled（原逻辑，疑虑 A） |
| ④ | 画布顶 y 显著小于改前 | PASS | 132 < 201.59（上移 69.6px） |
| ⑤ | 卡头无横向溢出/截断 | PASS | 1280/1920 全子元素 right ≤ 卡右界 |
| ⑥ | ToolTower 成列且间距统一 | PASS | towers=3，gaps=[8,8] |
| — | console 零 error（4 会话） | PASS | errors=[] |

修复轮补验：错误行实测渲染在包裹层内（`parentElement === wrap`、`top:24px`、不叠行2）；空态/Loading 同构锚定面（同一包裹层 inset-0）由该验证覆盖。

## 5. 交互抽验结果（T1，全 PASS）

1. 搜索建议框：输入「星辰」→ 弹建议（命中 `<mark>` 高亮）。
2. nodeBudget −/＋：点击无异常；本库实体数低于最小档，双键按原 disabled 档位禁用，stats 前后一致（疑虑 A）。
3. 缩放 +/−（ToolTower）：截图 hash before/mid/after 三段互异（WebGL 画布不能 drawImage 读回），已 fit 复位。
4. TimeScrubber 时间岛在画布底部正常渲染。
5. 主题切换（useDark）亮↔暗生效（1280/1920 各一次）。

## 6. Deviation 清单（T1，均为有意为之并已记录）

1. stabilizing 提示放行1 stats 旁（右段最左），未放行2 尾。
2. sigma 包裹层 `absolute inset-0` → `relative min-h-0 flex-1`：卡头变 static 后保持 absolute 会垫到卡头后面（节点画进卡头底下）；`.card.graph-canvas-card` 本体未动。
3. 返回全图钮：简报行2 清单未列，原样迁入行2 搜索右侧（保留 `graph-shadow`）。
4. ToolTower 零改码：容器既有 gap-2 已统一，仅验证。

**疑虑（不阻断合入）**
- A. nodeBudget ± 在当前唯一库（18 实体 < 150）无法演示节点数变化，属改造前既有档位逻辑，禁用态已验证一致；需 >150 实体库才能演示 ±。
- B. sigma 绘图面净高权衡：卡头上移 69.6px，但两行卡头占高 → 净高 1280 约 **−38px**、1920 约 **−22px**（简报允许 1280 折行）。
- C. vitest 首跑曾出现 Worker 闪断（dev server 同机并发资源型，与本批无关）；复跑全绿。

## 7. 全量回归（本批实跑数字）

| 门 | 结果 | 明细 |
|---|---|---|
| `pnpm test` | PASS | **58 files / 425 tests 全绿，= 425 基线零退化**（单跑一次即绿，无闪断） |
| `pnpm typecheck` | PASS | vue-tsc 无输出 |
| smoke-p0 | ALL PASS（20 项） | login + getInfo/getRouters + 9 系统面 + 8 日期筛选面 |
| smoke-p1 | ALL PASS（**PASS=67 SKIP=1**） | 造数流 4 + GET 走查 44 + 契约 3 + pt- 双通 5 + ES 检索 1 + seed 明细 6 + 匿名 health 1 + 撤销链 2；SKIP=chat（`JKNOW_LLM_API_KEY` 未配，外部依赖非回归）；ES 可达，检索面实跑 200 |
| 后端 mvn 面 | 本批零改动，未跑 mvn test | — |

回归环境备注：本批执行环境为全新拉起（前次分派环境丢失、无磁盘产物）——Docker Desktop → `docker compose -f docker-compose.dev.yml up -d`（pg/redis/es 三容器，容器名 jknow-pg-1/jknow-redis-1/jknow-es-1）→ 后端按 README 演示态启动：`JKNOW_PG_PORT=15432 SERVER_PORT=27401 JKNOW_ES_RECONCILE=1 JKNOW_JOBS_WORKER=true JKNOW_DATA_DIR=$(pwd)/data java -jar jknow-admin/target/jknow-admin.jar`（jar 为 Nov 23 既有构建，未重新打包），健康面 `/api/v1/health` 200 后执行双 smoke。

## 8. captcha 复位记录（运维纪律）

smoke 脚本自足化会关 captcha 且跑毕不复位，跑完后手动复位并三重复核：

1. 复位前库值：`sys.account.captchaEnabled` = **false**（smoke 所置）。
2. `UPDATE sys_config SET config_value='true' WHERE config_key='sys.account.captchaEnabled'` → UPDATE 1；`redis-cli DEL sys_config:sys.account.captchaEnabled` → 返回 1（键已清）。
3. 复查库值：**true**。
4. 实测强制（README 口径）：无验证码 POST `/login` → `{"msg":"验证码已失效","code":500}`，captcha 已真实恢复。

## 9. 净高优化路径备忘（后批可选，不在本批范围）

若走查复盘认为 1280 折行形态下绘图面净高（−38px）需回补：把行2 的类型图例 chips + 边语义图例收进「+」弹层（浮卡改弹层），卡头可回单行，预计净高回补约 50-60px@1280。现形态为简报允许范围内的锚点约束自定，暂不动。

## 10. 提交（本批）

```
docs(plan): graph-header 证据汇总报告
```

入库物：本 `report.md` + `gh-01~04.png` 四截图；`auto-imports.d.ts`、`vite.config.ts`、`tests/__snapshots__/*.snap`、`.playwright-cli/` 等永不提交清单照旧未动。
