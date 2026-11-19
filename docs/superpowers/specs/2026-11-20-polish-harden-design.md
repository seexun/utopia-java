# 打磨+加固批规格（docs/legal 重造+画布 fit+V0106 demo 只读角色+暗态令牌层+小项清扫）

- 日期：2026-11-20
- 状态：已获用户批准（一节打包设计 OK）
- 背景：收尾冲刺已合并；本批清偿全部打磨级遗留（B 组观感）+安全加固（C 组 demo 只读角色），此后遗留清单仅剩长期挂账项（vendor-utils 拆包/SSE 竞态/pipeline 测试等，维持归档）。

## 设计

### 1. docs + legal 两页重造（631 行，逻辑零动铁律）

- **docs**（index.vue 514 + parts.tsx 29）：`PageHeader(产品文档)` + 左侧目录卡（锚点导航）+ 正文卡（分节排版；parts.tsx 引文琥珀 mark 已令牌化零改）。
- **legal**（index.vue 112，9 处硬编码色）：`PageHeader(法务信息)` + 条文卡；硬编码色全清令牌化。
- script 仅 import/纯展示 computed。

### 2. 画布初始 fit padding（图谱+本体同修）

- sigma 初始化/fitView 加边距（bottom 尤甚——底缘节点不再被时间刷/卡缘裁半）：以 sigma `settings.autoRescale`/`fitView({ padding })` 实况口径实现（读 graphCanvas.ts 与 schema-graph 现初始化代码定形）；图谱与本体 schema-graph 同修。

### 3. 小项清扫（账本逐条清偿）

1. doc-viewer PageHeader 下距：`class-name="mb-1"` 惰性属性——给 PageHeader 组件加 `class` 透传（Vue3 attrs 默认透传，核实后正确挂 `class="mb-1"`）或包裹元素承担间距（择小改）。
2. ReadUsers.vue（system/notice）+ tool/build 的 `#409eff` 旧蓝 → `var(--color-primary)` 扫尾。
3. admin/deployment-tab.vue `badge-soft badge-soft` 重复类名去重。
4. kb-settings 各卡保存钮 plain → primary（卡内主行动统一；与 models-tab 卡语言对齐口径——读 models-tab 现状择同款）。
5. 14 档过滤条折行：极窄视口核对（flex-wrap 已有——仅确认无溢出破版，破了才动）。

### 4. V0106 demo 只读角色 + 孤儿行清理（安全加固）

- 新建 `sys_role(role_id=3, role_key='demo', role_name='演示访客', status='0', system=false)`；
- `sys_role_menu` 为 role 3 授 2200-2221 区间全部菜单行（27 行=25 现存+workspace F 2111/2112——与 V0105 的 role 2 授权集相同口径）；
- `sys_user_role`：9999 从 role 2 **解绑**改绑 role 3（UPDATE 或 DELETE+INSERT，幂等）；
- 顺手清 V0103 孤儿行：`DELETE FROM sys_role_menu WHERE menu_id NOT IN (SELECT menu_id FROM sys_menu)`（通用清——(2,4) 在内）；
- MigrationTests：MIGRATION_COUNT=107 + 断言（role 3 知识菜单=27/role 3 系统菜单=0/9999 绑定=3 且无 2/全表无孤儿行）。

### 5. 暗态令牌层（`--color-*` html.dark 覆盖档）

- `tokens.scss` 尾部追加 `html.dark { }` 块：`--color-bg: #1a1a1a`、`--color-neutral-soft: #262626`、`--color-border: #3a3a3a`、`--color-ink: #e8e8e8`、`--color-ink-2: #a0a0a0`、`--color-ink-3: #6b6b6b`、`--color-line-strong/--color-fill`（tailwind.css 里的同名令牌同批加暗档——两处令牌源都要覆盖）；soft 系（primary-soft/warning-soft 等）加暗档半透明（如 `rgb(37 99 235 / 24%)`）。
- 验收：暗态下知识页自定义面（卡底/正文/徽章底）翻暗无刺眼白块；亮态零回归（light 值不动，仅加 dark 覆盖）。

## 验收标准

1. **demo 登录（demo/admin123）**：侧栏只见三分组**无系统管理/监控/工具**，图谱/检索/对话全通（截图 2 张）——C 组核心验收
2. docs/legal 走查截图 2 张；画布 fit 前后对比 1 张（底缘节点完整）
3. 暗态两态截图（知识页翻暗无白块/亮态不回归）
4. `pnpm test` 422 基线（受影响 spec 适配有账目）+ typecheck + build；MigrationTests 2/2（107 重放）；双 smoke 零退化
5. 小项清扫 grep 归零（`#409eff` 全仓/badge-soft 重复）

## 不做的事

- vendor-utils 拆包、~11 明细冒烟面、pt- TTL、SSE 竞态、pipeline 7 测试、scenarios·golden 复活、测试热身 SQL 种子化——全部维持长期挂账归档。
