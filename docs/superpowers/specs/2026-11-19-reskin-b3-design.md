# 业务页重造·批次 3 规格（对话+本体+令牌清扫）

- 日期：2026-11-19
- 状态：已获用户批准（三节设计 OK）
- 上游：批次 1（列表型模板）/批次 2（工作区型模板）已合并；UI 基座就位
- 范式适配：对话页=聊天流型（无 gvdata 直接范式，按批次模板语言组装配）；本体页=双视图工作区型（批次 2 手法复用）

## 设计

### 1. 对话页重造（chat/ 共 2024 行；逻辑零动铁律）

- `PageHeader(对话, subtitle=库名)` + 左侧**会话列表卡**（会话项 anivia 化、选中 `--color-primary-soft`、新建会话主行动）+ **消息流主卡**（用户/助手消息气泡卡化：助手消息白卡 radius-card/shadow-card、引用徽章 badge-soft/cat 色板、steps 折叠卡、markdown/citations/chatCopy 组件渲染零动）+ **Composer 输入卡**（底部固定、focus 光晕 `--color-primary-glow`、发送按钮主行动形态）。
- SSE 流式/会话即路由（/kb/:kbId/chat/:conversationId）逻辑零动；script 仅 import/纯展示 computed。

### 2. 本体页重造（ontology/ 共 4969 行；逻辑零动铁律）

- `PageHeader(本体, subtitle=库名, extra=视图切换 segmented[表格/图示])`（视图状态与 localStorage 持久化零动）+ **表格视图**（ontology-tables 的 el-table 形态随全局 anivia、类型树卡化）+ **图视图**（schema-graph 画布卡——批次 2 包壳手法复用：padding:0/overflow:hidden/浮件卡化）+ 侧面板（class-panel/rules-panel/import-panel 卡化）+ **dialogs.tsx 1076 行走查驱动**：el-dialog 全局 anivia 自动跟随，走查确认形态——微调仅限明显不一致处（择小改，报告记录每处）。

### 3. 令牌清扫打包（账本累积项一次清）

1. `kit.tsx` StatusCell 死导出删除（35 行，批次 2 遗留二跳）。
2. `utopia-pages.css` `.pill:hover` `#e2e8f0` → `var(--color-neutral-soft)`。
3. **mark 高亮形态统一**：命中词底从 `--color-primary-soft` 改为在白卡上可感知的形态（候选：`--color-primary-glow` rgb(37 99 235/12%) 或加 1px `--color-primary-light-7` 描边）——实施时两种候选截图对比择优，**全站一致**（检索结果卡+图谱浮搜建议两处消费点同改）。
4. 图谱页搜索建议列表补 `max-height`（极短视口溢出，1 行）。

### 验收标准

1. 对话页走查：会话切换/新建/UI 形态截图；SSE 一轮真流（有 LLM key 则真验流式与引用，无 key 则按 UI 降级形态记录）——**不伪造模型输出**
2. 本体页走查：双视图切换/类型树/表格分页/图示画布/对话框开合（classes/relations/rules 至少各一）截图
3. 批次 1/2 页零回归（检索/文库/图谱各抽验一眼）+ mark 新形态全站一致截图
4. `pnpm test` 419 基线 + typecheck/build 绿；console 零 error；暗态两态

## 不做的事

- 不动 SSE/路由/数据流；不做移动端；审查+设置·账户页留批次 4；tool/build（若依原生）不在重造范围。
