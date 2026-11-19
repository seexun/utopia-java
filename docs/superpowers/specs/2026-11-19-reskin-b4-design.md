# 业务页重造·批次 4 规格（审查+设置·账户·admin+清扫收官，终批）

- 日期：2026-11-19
- 状态：已获用户批准（三节设计 OK）
- 上游：批次 1-3 已合并（检索/文库/图谱/对话/本体五页 + 模板体系）；本批为 UI 对齐 gvdata 子项目**全站终批**
- 页面类型：审查页（行密集型）/ kb-settings·account·admin（tab 表单型）——均有成熟模板可循

## 设计

### 1. 审查页重造（review/ 2993 行：index.vue + overview.tsx 294 + rows.tsx 1659；逻辑零动铁律）

- `PageHeader(审查, subtitle=库名)` + **队列过滤条**（14 队列筛选——UPill 选中 primary-soft、计数徽章）+ **行卡列表**（rows.tsx 事实行卡化：状态徽章 badge-soft 族、来源/置信度徽章 cat 色板、行操作 UIconButton、行 hover neutral-soft）+ **概览卡**（overview.tsx 统计复用文库统计卡语言：.stat-grid）。
- 队列切换/行操作（通过/驳回/详情）/事实弹窗逻辑零动；script 仅 import/纯展示 computed。

### 2. 设置·账户·admin 重造（tab 表单型；逻辑零动铁律）

- **kb-settings**（927 行）：`PageHeader(设置, subtitle=库名)` + el-tabs（全局 anivia 自动跟随）+ 各 tab 表单卡（.card 包裹）。
- **account**（1114 行）：shell tab 导航（已有）+ profile/my-kbs/tokens 三页内容卡化（表格随全局）。
- **admin**（1919 行，五 tab）：datasources/deployment/members/models/sso 同款 tab+表单卡；members 表格随全局。
- 全部表单/上传/保存/权限闸/tokens 生成逻辑零动。

### 3. 清扫收官（账本归档项终清）

1. **琥珀 mark 令牌化**（`utopia-pages.css` `.mark`）：`rgba(180,83,9,.16)` → `var(--color-warning-soft)`；若伴生字色为字面量 → `var(--color-warning-text)`。**语义保留**：蓝 glow=检索/浮搜命中，琥珀=文档内引文定位（chat 引用预览/docs 查看器）——两色区分是设计意图。
2. `.tool.is-on` 背景 `#e2e8f0` → `var(--color-border)`（与 pill hover 同档）。
3. **切段函数抽共享**：四份实现（search `quoteSegments`/graph `nameSegments`/citations `quotePieces`/docs parts.tsx 内联）→ 新建 `src/utils/highlightSegments.ts` 单一实现（参数：文本+命中词数组，返回段数组；多词/单词/引文串需求差异以参数吸收），四处改引——行为等价（各处现有输出形态不变）。

### 验收标准（全站收官验收）

1. 七页最终走查截图一轮（检索/文库/图谱/对话/本体/审查/设置·账户——审查+设置为本批新增截图 ≥5 张）+ console 零 error + 暗态两态
2. 切段重构行为等价：四处消费点走查（检索命中/图谱浮搜/对话引用预览/docs 查看器高亮）形态与批次 3 一致
3. `pnpm test` 419 基线 + typecheck/build 绿；双 smoke 零退化
4. `LC_ALL=C grep -rn "rgba(180\|#e2e8f0" jknow-web/src/style/` 归零（清扫面）；切段四份归一（grep 函数名）

## 不做的事

- 不动任何表单/队列/行操作逻辑；不做 SSE 鉴权（单开任务已归档）；tool/build（若依原生）不在范围；画布初始 fit padding 留通用收尾批。
