# 业务页重造·批次 2 规格（图谱页+旧壳件收敛）

- 日期：2026-11-19
- 状态：已获用户批准（两节设计 OK）
- 上游：批次 1 模板已合并（PageHeader/卡容器/徽章用法范例：search 与 library 两页）；UI 基座（tokens/anivia/light 侧栏）就位
- 范式参照：gvdata `da/dataQuery`（左右分栏工作区：左树卡 leftWidth 300+右侧内容区）——工作区型页的参照解

## 设计

### 1. 图谱页重造（graph/index.vue 1734 行 + entity-panel.tsx 1296 行 + time-scrubber.tsx 384 行；逻辑零动铁律）

- **布局**：`PageHeader(title=图谱, subtitle=库名)`（无 extra 主行动——图谱页主行动在画布工具条内，遵循"每页唯一"不重复放页头）→ 工作区行：
  - **画布主卡**：`.card` 包裹现有画布容器（sigma 画布/图例/缩放控制工具条 anivia 徽章与按钮形态）；画布配色与 graphVisuals 零动
  - **实体面板**（entity-panel）：卡片化——右侧固定位或现有浮层位改为 anivia 卡形态（radius-card/shadow-pop 令牌、内部列表/事实行徽章化，首批 badge-soft 语言复用）；指/钉交互零动
  - **时间刷卡**（time-scrubber 底部）：同款卡化
- **模板纪律**：与批次 1 相同——script 仅 import/纯展示 computed；事件绑定 1:1（画布拖拽/缩放/选中/面板联动/时间刷全量保留）；新样式零硬编码色。

### 2. 旧壳件收敛（文库页遗留）

- `library/write.tsx`（307 行）拆解：`DocRow`/`rowIcon` 已零消费（批次 1 后）——删除；`DeletedTable` 仍被 library/index.vue 引用——**迁出为独立文件** `library/DeletedTable.tsx`（内容原样或按 anivia 表格形态重写模板、逻辑零动，择小改）后删除 write.tsx。
- RunsPanel 历史视图过滤框惰性（批次 1 Minor 遗留）：走查确认现状，一行可对齐则顺手，否则如实记录。

### 验收标准

1. 图谱页三卡形态走查截图（全景+实体面板选中态+时间刷）；画布交互回归：拖拽/缩放/选中环/面板指钉联动全量
2. 暗态两态正常（light 侧栏+html.dark）；console 零 error
3. 文库页回归：删除→已删视图→恢复/purge 链不破（write.tsx 拆解后）；`LC_ALL=C grep -rn "write.tsx\|DocRow" src` 归零（除 DeletedTable 新文件）
4. `pnpm test` 419 基线绿（受影响 spec 随迁移适配）+ typecheck/build；截图存 docs/superpowers/plans/reskin-b2/

## 不做的事

- 不动 graphVisuals 画布配色（已令牌化）；不改任何交互/数据流；不做画布性能优化；对话/本体/审查/设置页留批次 3/4。
