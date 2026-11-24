# 留档清偿批 总报告（三节改造 + 全量回归 + 证据汇总）

日期：2026-11-24　分支：`feat/deferred-cleanup`（HEAD 回归起点 `c286ec6`）
T1 改造：`969fa01`　T2 改造：`fe2883d`　T3 种子：`c286ec6`　本批（T4 回归+归档）：**零代码改动**
计划：[2026-11-24-deferred-cleanup.md](../2026-11-24-deferred-cleanup.md)　截图目录：本目录（`dc-01~08` + 2 追加）

---

## 0. 本批构成

| 任务 | Commit | 内容 | 走查 |
|---|---|---|---|
| T1 | `969fa01` | 图谱卡头行2 边语义图例收 el-popover（单行化） | 4 断言 PASS（task-1-report.md） |
| T2 | `fe2883d` | 本体/审查页 PageHeader 删除、标题+chip 入卡头、segmented 迁右段 | 12 断言 PASS（task-2-report.md） |
| T3 | `c286ec6` | `scripts/seed-nodebudget-demo.sql`（独立幂等压测库种子）+ README 一行 | Playwright 16/16 PASS（task-3-report.md） |
| T4 | 本报告 | 全量回归 + 证据归档（无代码改动） | 见 §4/§5 |

---

## 1. T1 验收：图谱行2 边语义图例收弹层（commit `969fa01`）

改动面：仅 `jknow-web/src/views/jknow/knowledge/graph/index.vue`（61+/44−），卡头行1 零动、零逻辑改动（模板+注释+1 个 icon import）。

### 1.1 弹层开合断言（task-1 走查实测）

| # | 断言 | 结果 | 实测 |
|---|---|---|---|
| ① | 触发钮在位（GitCompare 图标） | PASS | 钮 26px |
| ② | 点开弹层：placement=bottom-end width=300，标题"边样式"+五项齐全（事实/推断/推导/⚠争议/未走通） | PASS | 5/5 项 |
| ③ | 再点触发钮/点外关闭 | PASS | toggle + outside 双路径 |
| ④ | 暗态弹层随 EP 令牌 | PASS | 弹层 bg `rgb(29,30,31)` vs 卡 `rgb(33,33,33)`，可读 |
| ⑤ | console error / HTTP≥400 | PASS | 0 / 0 |

### 1.2 高度对照（getBoundingClientRect 实测，同环境同数据重放）

| 指标 | 前 | 后 | Δ |
|---|---|---|---|
| 卡头总高 | 112.03px | **96.03px** | **−16px** |
| 行2 | 折行（边图例+标题溢出折 2 行） | **32px 单行不折** | 单行化 |
| sigma 包裹层 top | 245.03px | 229.03px | 上移 16px |
| 画布净高（包裹层高） | 513.97px | **529.97px** | **+16px** |

**净高实补 +16px，非简报口径 ~50px**：在简报自身约束（行1 不动 39px、搜索框 32px、内距+行距 24px、border 1px）下卡头物理下限即 96px，≤~64px 算术不可达。~50px 口径对应上批 graph-header 报告的另一方案（两行并一行/类型 chips 也收弹层，~55-60px）——**与简报"行1 不动"冲突，未越权执行，留待用户裁定（见 §7）**。

截图：`dc-01-popover-open.png`（亮·开）、`dc-02-1280-light.png`（亮·合）、`dc-03-1280-dark.png`（暗·开）、`dc-03b-1280-dark-closed.png`（暗·合，追加）。

---

## 2. T2 验收：本体/审查页头合一（commit `fe2883d`）

改动面：`ontology/index.vue` + `review/index.vue`（96+/57−），两页 PageHeader 及 import 删除，`schema-graph.tsx` 零动。

### 2.1 断言摘要（12/12 PASS，task-2 走查，页根 `.knowledge-page` 作用域）

| 页 | 断言 | 结果 | 实测 |
|---|---|---|---|
| 本体 | 独立页头带不存在；单 h1"本体"在 aside 卡头行 | PASS | h1 count=1；卡头行 48.03px |
| 本体 | kb chip 在卡头、不截断、行不溢出 | PASS | "演示：智造产业链"，scrollWidth≤clientWidth |
| 本体 | 视图 segmented 在图示卡头右段（图/表格） | PASS | 右缘 gap 13px 右对齐；slim 卡头 50px |
| 本体 | segmented 切换 table↔diagram：canvas 卡卸载/复现、落点同位、localStorage 持久化 | PASS | table 态 right-gap 21px；`utopia.ontology.view` 两向写入正确 |
| 本体暗态 | html.dark 结构同亮态 | PASS | dark=true |
| 审查 | 独立页头带不存在；单 h1"审核"在过滤卡卡头（border-b 行）；kb chip 在卡头 | PASS | h1InCard=true；卡 160px |
| 审查 | 14 档药丸原样入卡身 | PASS | pillsInCard=14 |
| 审查暗态 | 同构 | PASS | |
| 两页 | console error / pageerror / HTTP≥400 | PASS | 0/0/0 |

### 2.2 截图（本页四张 + 追加一张）

`dc-04-ontology-light.png`（本体亮·图示）、`dc-04b-ontology-table-light.png`（本体亮·表格，追加）、`dc-05-ontology-dark.png`（本体暗·图示）、`dc-06-review-light.png`（审查亮）、`dc-07-review-dark.png`（审查暗）。

---

## 3. T3 验收：nodeBudget 压测库种子（commit `c286ec6`）

产物：`scripts/seed-nodebudget-demo.sql`（独立幂等脚本，不入 seed/smoke 链）+ README 验证节一行。

### 3.1 库与分档探针（task-3 Playwright 实测，16/16 PASS）

| # | 断言 | 结果 | 实测 |
|---|---|---|---|
| ① | 压测库 170 实体灌库幂等（两遍 RUN：第二遍全 INSERT 0） | PASS | entities=170、kb_members=1 |
| ② | 基线演示库（18 实体 < 150 地板档）步进器双键 disabled | PASS | minus/plus 均 disabled |
| ③ | 切压测库：>150 步进器激活；150 地板档 capped 形态 | PASS | `已画 150 / 共 170 个实体`；− disabled（地板档 by design）、+ enabled |
| ④ | 点 + 升 300 档：uncapped 全量渲染 | PASS | `170 个实体`；− enabled、+ disabled（uncapped by design） |
| ⑤ | **点 − 实减**：300 档 → 150 档节点数实减 | PASS | **170 → 150（Δ=−20）**，statsText 切回 capped |
| ⑥ | 切回演示库：chip/stats 恢复，console 零错误 | PASS | 18/24/23 恢复；errors=0 |

截图：`dc-08-nodebudget.png`（压测库 150 档定格局：`已画 150 / 共 170 个实体` + 灰点群）。

### 3.2 uuid 段说明（a0/a1 段新占用）

- seed-demo.sql 既有段：`0190d3e0-0000-7d5a-8000-0000000000xx`（尾段 12 hex）。
- T3 新占用两段（灌库前实测两前缀库内 0 行，不撞）：
  - **a0 段**：kb `…00000000a0f2`（演示：档位压测）；
  - **a1 段**：实体 `…0000000a1` + lpad(g,3) → `0000000a1001 ~ 0000000a1170`（170 个全 hex 合法 cast 实测）。
- 简报骨架原尾段 `000000a0f0`/`000000a1001` 仅 10/11 hex，非合法 uuid（尾段必须 12 hex）——定稿已改制（task-3-report.md 偏差①）。
- 段位与 p1-smoke 动态段（`01a20c3b-…`，uv7 生成）天然不交。

---

## 4. 全量回归（T4 实测，HEAD `c286ec6`）

| 项 | 命令 | 结果 |
|---|---|---|
| 单测 | `cd jknow-web && pnpm test`（vitest run） | **PASS：58 files / 425 passed（=425 基线）**，10.95s |
| 类型 | `pnpm typecheck`（vue-tsc --noEmit --skipLibCheck） | **PASS：0 错** |
| 生产构建 | `pnpm build:prod` | **PASS：EXIT=0**（唯一告警=既有 Sass `@import` deprecation，非本批引入） |
| P0 冒烟 | `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` | **ALL PASS：20/20**（登录闭环 + 11 只读面 + 8 日期筛选面） |
| P1 冒烟 | 同上 `./scripts/smoke-p1.sh` | **ALL PASS（PASS=67 SKIP=1）**；SKIP=chat 面（JKNOW_LLM_API_KEY 未配，外部依赖非回归，与历批口径一致） |
| mvn 后端面 | 本批后端零改动（三 commit 均前端/脚本/文档） | 按批口径不跑 mvn test |

**captcha 复位纪律（跑毕即复位）**：两 smoke 自足关验证码（DB 置 false + flushdb）→ 跑毕 `UPDATE sys_config SET config_value='true' WHERE config_key='sys.account.captchaEnabled'`（UPDATE 1）+ `redis-cli DEL sys_config:sys.account.captchaEnabled`（=1）→ 复查 DB=`true`；redis 键随后由应用按 DB 值重载为 `true`（GET 实测 `"true"`，与 DB 一致，TTL=-1）——**验证码已恢复开启态**。

---

## 5. 回归事故与恢复（T4 新记录）：smoke-p1 自足段级联清压测库

### 5.1 事实链（smoke 跑毕 count 复查发现）

| 时点 | 压测库 a0f2 entities | 演示库 001 entities | workspace f5 |
|---|---|---|---|
| smoke 前（基线） | 170 | 19（seed 本值） | 在 |
| **smoke-p1 跑毕** | **0（事故）** | 0（smoke 设计态） | 0（teardown 删除） |
| 恢复后（复跑官方种子） | **170** | 19 | 在 |

### 5.2 根因（已定位，未修码——遵守"只报告不自行修"纪律）

smoke-p1.sh 自足段（:212-253）：`seed-demo.sql 灌库 → 明细 GET 断言 → teardown-demo.sql 拆除`（设计为"跑完不留演示档"）。teardown-demo.sql 第 1 步 `DELETE FROM workspaces WHERE id='…f5'` 走 **ON DELETE CASCADE** 清空该工作区全部 kb——而 **T3 定形把压测库 a0f2 挂在了既有演示工作区 f5**（task-3 偏差③：kb-switcher 只列当前工作区的库，裁撤独立 ws 段改挂 f5）。于是每跑一次 smoke-p1，级联把压测库连 170 实体一并清掉。这正是 T3 遗留 deferred minor 预言的碰撞（progress.md："重跑 seed-demo/teardown 会级联清压测库（CASCADE f5）"），本轮回归使其成为现实。teardown-demo.sql 文件头"既有库与测试残留零影响"的声明自 T3 起已过时。

（附注：演示库 001 的消失是 smoke 自足段**设计态**——"跑完库里不留演示档"，历批 smoke 均如此；事故仅指压测库被连带清空。）

### 5.3 恢复操作与复核（纯数据回填，官方幂等脚本，无代码改动）

```
docker compose exec -T pg psql … -f - < scripts/seed-demo.sql            # 先建回 f5/001（幂等）
docker exec -i     jknow-pg-1 psql … -f - < scripts/seed-nodebudget-demo.sql # 再挂回 a0f2（幂等）
```
复核：`ent_001=19`、`ent_a0f2=170`（id 全落 `…a1001~a1170` 段实测 170）、kb_members=1、kb 名"演示：档位压测"、p1-smoke 残留 workspace=0、captcha 复位态不受影响（种子不触 sys_config）。

### 5.4 遗留（需后续决策，本批不动码）

- 方案候选：a) teardown-demo.sql 改为按 kb 001 精确删除（不再整 ws 级联）；b) 压测库迁独立工作区并补工作区切换链路（动 useKbContext，重）；c) 维持现状 + 文档明示"smoke-p1 跑毕须复跑两个种子脚本"（零成本，代价是每次手补）。
- 与 T3 deferred minor（"脚本头+README 补提示行"）同源，建议终审一并裁定。

---

## 6. deviation 清单（批级汇总，详见各 task 报告）

| # | 来源 | deviation | 定性 |
|---|---|---|---|
| 1 | T1 | 净高实补 +16px（96.03 vs 112.03），非简报 ~50px 口径；~50px 需两行并一行方案，与"行1 不动"约束冲突未执行 | 简报算术不可达，已留裁定（§7） |
| 2 | T1 | commit message "约 50px" 与事实 +16px 不符（勘误留档）；108px 基准同口径复现 112.03（差 4px 字体渲染） | minor，趋势/Δ 不受影响 |
| 3 | T1 | dev DB 曾被清空→官方 seed-demo.sql 重放回填（本批 §5 再次发生同类，见事故节） | 环境回填，官方口径 |
| 4 | T2 | 简报"过滤条所在卡"实读不存在→过滤条块升 `.card`（卡头首行=标题行）；模板 px-3/pt-2 未照抄（`.card` 自带 20px 内距），补 border-b 对齐图谱家族 | 简报假设不符下的必要定形 |
| 5 | T2 | 本体图示卡新增 50px slim 卡头（原无头画布卡需提供右段落点），画布净高 −50px；与图谱 +16px 净账在案 | 同上 |
| 6 | T2 | aside 卡头分隔线内缩式（栏内语言一致，未反向撑满）；h1 文案用 i18n 键"审核"（原 PageHeader 展示即如此） | 微差记录 |
| 7 | T3 | uuid 尾段 12 hex 改制（简报骨架 10/11 hex 非法）；visibility 'public'→'open'（CHECK 约束）；裁撤 org/ws 段改挂演示工作区 f5（kb-switcher 工作区过滤+无 ws 切换 UI）；"±均 enabled"口径修正为分档探针（170 实体下两钮不可能同时 enabled，by design） | 简报骨架执行级错误 4 处，均实测修正 |
| 8 | T4 | smoke-p1 自足段级联清压测库（§5 事故+恢复）；teardown 头注"零影响"声明过时 | 事故已恢复；遗留待裁定（§5.4） |
| 9 | T4 | 演示库表内实体 19 行 vs 页面 statsText"18 个实体"——口径差（statsText 为图谱 overview 计数，非表行数），非数据缺失 | 口径说明 |

---

## 7. 待用户裁定项

1. **【净高方向】** 现状 96px（本批 T1 落地态，卡头物理下限，实补 +16px）vs 激进单行 ~55-60px（行1+行2 并一行/类型 chips 同收弹层，可回补至 ~50px 口径）。T1 是否追加修复轮取决于此裁定。
2. **【压测库抗 smoke】** §5.4 三方案择一（teardown 精确删 / 独立 ws+切换链路 / 文档明示跑毕复种）。
3. 【顺带，T3 deferred minor 合并点】seed-nodebudget-demo.sql 头部与 README 是否补"重跑 seed-demo/teardown 会级联清压测库，跑毕复种"提示行。

---

## 8. 截图清单（本目录，已入库）

| 文件 | 任务 | 内容 |
|---|---|---|
| `dc-01-popover-open.png` | T1 | 1280 亮·弹层开：边样式+五项色样 |
| `dc-02-1280-light.png` | T1 | 1280 亮·合态：行2 单行，画布满铺 |
| `dc-03-1280-dark.png` | T1 | 1280 暗·弹层开 |
| `dc-03b-1280-dark-closed.png` | T1 | 1280 暗·合态（追加） |
| `dc-04-ontology-light.png` | T2 | 本体 1280 亮·图示：aside 卡头+slim 头右段 |
| `dc-04b-ontology-table-light.png` | T2 | 本体 1280 亮·表格（追加）：双 segmented 同头行 |
| `dc-05-ontology-dark.png` | T2 | 本体 1280 暗·图示 |
| `dc-06-review-light.png` | T2 | 审查 1280 亮：过滤卡卡头+14 档药丸 |
| `dc-07-review-dark.png` | T2 | 审查 1280 暗 |
| `dc-08-nodebudget.png` | T3 | 压测库 150 档定格局：`已画 150 / 共 170 个实体` |

---

## 9. 提交与永不提交清单核对

- 本 commit：仅 `docs/superpowers/plans/deferred-cleanup/`（report.md + dc-01~08 等 10 图）——`docs(plan): deferred-cleanup 证据汇总报告`。
- 未提交（保持 M/?? 态，遵守永不提交清单）：`jknow-web/auto-imports.d.ts`、`jknow-web/vite.config.ts`、`tests/__snapshots__/*.snap`、`.playwright-cli/`、`jknow-web/src/docs/*.md`；另有本批前即存在的无关 untracked（spike-t1~3/、jknow-contract scenarios-spike/、ui-walkthrough 截图）不动。
- 走查日志：/tmp/dc4-{test,typecheck,build,smoke-p0,smoke-p1}.log（临时，不入库）。
