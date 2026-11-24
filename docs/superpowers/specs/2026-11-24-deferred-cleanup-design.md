# 留档清偿批规格（图谱图例弹层回补净高+本体/审查页头合一+nodeBudget 演示种子）

- 日期：2026-11-24
- 状态：已获用户批准（三节设计 OK）
- 背景：图谱页页头合一批（3b9c092）留档三项的清偿——净高权衡、工作区型页头推广、nodeBudget 演示条件。

## 设计

### §1 图谱行2 边语义图例收弹层（任务 1）

- `jknow-web/src/views/jknow/knowledge/graph/index.vue` 卡头行2：边语义图例（`边样式`标题 + 事实/推断/推导/争议/未走通五项）从 inline 平铺收进 el-popover：
  - 触发钮：小尺寸按钮（icon `lucide/git-compare` 或既有边语义近义图标 + 文案 `边样式`），形态与卡头既有控件一致（rounded-ctl border）。
  - 弹层内容：原五项+标题原样（横排或纵排均可，以 240-320px 宽弹层不溢出为准），placement bottom-end。
- 行2 剩余：搜索框 w-58 + 类型图例 chips（平铺不变）+ 触发钮（右端）——**单行不折行**（1280 视口验证）。
- 预期：卡头总高 108px → ~60px，画布净高回补约 50px。
- 零逻辑改动；既有 statsText/nodeBudget/搜索/图例交互原样。

### §2 本体/审查页同款页头合一（任务 2）

- **本体页**（`src/views/jknow/knowledge/ontology/index.vue`，PageHeader :327 带 extra=图/表 segmented）：
  - 删 PageHeader 及 import；`本体` 标题（text-title font-semibold）+ kb chip（badge-soft，`kbName` 变量）并入左栏清单卡（filter 卡）第一行左段；**图/表 segmented（原 extra）迁入该行右段**（页级视图切换语义/状态零动）。
  - 结构以实侦察为准：左栏 filter 卡若非首屏首卡，则并入实际首卡头行；行右段也可能放原 extra——总原则"标题+chip 左、视图切换右"。
- **审查页**（`src/views/jknow/knowledge/review/index.vue`，PageHeader :79-80）：
  - 删 PageHeader 及 import；`审查` 标题 + kb chip 并入 14 档队列过滤条卡第一行左段（`审查`+chip 在 UPill 条上方独立一行或同排，以过滤条卡实况择形）；右段可放队列总计数（若页内已有现成数据，不新增计算）。
- 两页 script 零动（仅删 import）；type chips/过滤条/视图切换全部原样。

### §3 nodeBudget 演示库种子（任务 3）

- 新建 `scripts/seed-nodebudget-demo.sql`（独立脚本，不入默认 seed/smoke 链）：
  - 建"演示：档位压测"知识库（独立 uuid 前缀 `0190d3e0-0000-7d5a-8000-0000000000nb` 段——uuid 须合法 hex，nb 非法，改用 `...000000a000` 段示意，实施时以 entities/orgs 等表 uuid 列格式实测为准）；
  - `generate_series` 批量插 ~170 实体（name 用 `压测实体-<n>`、type/color 复用既有实体类型行或字面 company），覆盖图谱 nodeBudget 档位 [150,300,600,1000] 的 − 按钮启用条件（total>150）；
  - 幂等：固定 uuid `ON CONFLICT DO NOTHING`；文件尾附注释掉的清理 DELETE 段（需要时启用）；
  - README 验证节补一行用法（灌库→图谱页左下 kb-switcher 切换→± 生效）。
- 侦察依赖：entities 表列清单（name/type_key/kb_id/color 等）以 migration 或 GraphStoreTests 插入语句为准。

## 验收

1. 图谱页 1280 亮/暗两截图：卡头单行（≤~64px 高）、`边样式`触发钮+弹层开态五项完整；画布容器 top 较上批再上移。
2. 本体/审查页 1280 亮暗四截图：无独立页头带、标题+chip 在卡头、本体 segmented 在右段可用（点切换视图正常）。
3. nodeBudget：灌压测库后 ± 启用，点 − 画布节点数实减（前后截图）；幂等重放零错；teardown/seed-demo 默认链不受影响（演示：智造产业链原样）。
4. `pnpm typecheck` + `pnpm test`（425 基线）+ `pnpm build:prod` 绿；双 smoke 零退化。
5. console 零 error（全部走查页）。

## 不做的事

- 不动检索/文库/对话等阅读型页页头；不做移动端/375 适配；graph 行1/画布逻辑零动；本体/审查视图切换与过滤逻辑零动；nodeBudget 种子不入默认演示叙事与 smoke。
