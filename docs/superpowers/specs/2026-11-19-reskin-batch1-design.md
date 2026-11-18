# 业务页重造·首批规格（检索+文库，子项目 2/批次 1）

- 日期：2026-11-19
- 状态：已获用户批准（三节设计 OK）
- 上游：UI 设计基座（2026-11-18-ui-gvdata-base）已合并——tokens/anivia/gvdesign 素材就位
- 方法论：照 gvdata `styleGuide/index.vue` 页面范式（PageHeader → 统计卡条 → 卡片列表，主行动每页唯一）与 `uiKit/index.vue` 组件规范（色彩/字体/按钮/表单/表格）重造；本批产出"列表型页重造模板"，后续批次（图谱/对话/本体/审查/设置·账户）照法递推。
- 首批范围（用户未答时按推荐裁定）：检索+文库（列表型，范式直接可套）+graphVisuals 双蓝顺手修；图谱等工作区型页后续批次。

## 设计

### 1. 重造模板件

- **PageHeader 迁入**：`/d/workspace_com/gvdata/gvdata-ui/src/components/PageHeader` → `jknow-web/src/components/PageHeader/`（零改写+import 路径适配；若其内部依赖 gvdata 特有组件则最小适配并记录）；按需 import 使用（不做全局注册，与 jknow 现行组件用法一致）。
- **范式约定**：业务页顶 `PageHeader`（title+subtitle+右侧主行动，每页唯一主行动）；内容区卡片用 anivia `.card`/`radius-card`/`shadow-card` 形态；统计卡条参照 styleGuide 的统计卡形态。
- **graphVisuals 双蓝**：`src/domain/pages/graphVisuals.ts` 的 `RING_SELECT = "#1677ff"` 改为与 node-core/edge-focus 同法的 `--color-primary` 读取（1 行，虽属图谱页素材但顺手本批清）。

### 2. 检索页重造（search/index.vue，156 行轻页）

- 结构：`PageHeader(title="检索", subtitle=当前库名)` → 查询卡（搜索框入卡、radius-ctl 8、focus 光晕 `--color-primary-glow`、提交按钮主行动形态）→ 结果卡列表（每条：文档名+段位徽章、引文高亮（命中词 `--color-primary-text` + `--color-primary-soft` 底）、来源徽章用 cat 色板、时间戳 ink-3）→ 空态/加载态（anivia 空态形态）→ 页脚分页（el-pagination，table-style 形态）。
- **逻辑零动**：useQuery 数据流/地址栏查询语义/打字本地事提交才写地址——纯模板层重造。

### 3. 文库页重造（library/index.vue，762 行重页，功能全保留）

- 结构：`PageHeader(title="文库", 主行动=上传按钮[canWrite 闸保留])` → **统计卡条**（文档数/分块数/来源数——读现有 documents stats 端点；无现成端点的项用列表侧统计，不新增后端）→ 来源侧栏卡（sources 列表 anivia 化、选中态 primary-soft）→ **文档列表卡**（表格形态——`table-style-optimized.scss` 首个消费点在此装配引入；列：名称/来源/分块/大小/时间/操作）→ 拖放上传区（虚线卡、拖入态 `--color-primary-glow` 边框）→ 详情抽屉/删除确认等交互保持 el 原生+anivia 形态。
- **逻辑零动**：权限闸（canWrite/isKbAdmin）/写动作/拖放/来源选择全部保留。

### 验收标准

1. 双页 vs uiKit/styleGuide 范式对照截图（PageHeader/卡片/按钮/表格形态一致）+ 重造前后对比各 1 张
2. 功能回归：检索真查（seed 数据命中高亮）、文库上传一个测试文件→列表出现→删除（回收纪律）
3. `pnpm test` 419 全绿、typecheck/build 绿、console 零 error；graphVisuals 修复后图谱选中环与节点主色一致（截图）
4. 模板沉淀：检索/文库两个范例的 PageHeader+卡容器用法在提交信息与代码注释中可循（后续批次照抄）

## 不做的事（YAGNI）

- 不动图谱/对话/本体/审查/设置·账户页（后续批次）；不改任何 API/数据流；不新增后端端点；不做移动端适配；不引入 gvdata 的 ai/dify 样式。
