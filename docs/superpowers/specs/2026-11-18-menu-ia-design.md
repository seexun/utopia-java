# 菜单信息架构重设计规格（对齐原程序三分组制）

- 日期：2026-11-18
- 状态：方向与首页方案已获用户批准（"方向对，首页选 A"）
- 背景：jknow 现菜单把 8 个业务页套在"知识图谱"目录下平铺（V0100/V0102）——层级深、无分组、命名与原程序不一致、数据映射缺项、核心业务排在系统菜单之后。原程序（utopia-web）是经视觉走查拍板的三分组制（工作台/知识管理/配置 + 底部切库器，utopia-menu.ts 头注："图谱是门面，本体与数据映射相邻"）。本设计把原结构映射到若依菜单模型。
- 否决记录：①"知识图谱"一级目录（现状）；②极简 4 页+顶部 tab 条（用户否——偏离原结构）。

## 决策

- 一级菜单 = **三个分组目录**（对齐原程序），排最前；系统管理/监控/工具自然排后。
- 菜单名对齐原程序 zh 词典：图谱/检索/问答/文库/审查/本体/数据映射/库设置（"对话"→问答、"摄取"菜单行删除——文库即其所对 library 页）。
- **首页方案 A**：登录后直达当前库图谱页（原程序行为）。
- 切库器：若依侧栏底部注入精简版 kb-switcher（从 P3-T3 清理提交 8d2fcbc 复活组件适配）。
- 载体：V0103 迁移一次入库全部菜单变更；前端改首页链与切库器注入；F 权限 16 项一字不动。

## 菜单树（V0103 目标态）

| menu_id | 名称 | 类型 | parent | order | path | perms | 图标（若依自带集） |
|---|---|---|---|---|---|---|---|
| 2200 | 工作台 | M | 0 | 1 | workbench | — | dashboard |
| 2210 | 知识管理 | M | 0 | 2 | knowledge-mgmt | — | education |
| 2220 | 配置 | M | 0 | 3 | config | — | build |
| 2201 | 图谱 | C | 2200 | 1 | graph（component=jknow/knowledge/kb-entry/index） | knowledge:graph:list | chart |
| 2202 | 检索 | C | 2200 | 2 | search | knowledge:search:list | search |
| 2203 | 问答 | C | 2200 | 3 | chat | knowledge:chat:list | message |
| 2211 | 文库 | C | 2210 | 1 | library | knowledge:ingest:list | documentation |
| 2212 | 审查 | C | 2210 | 2 | review | knowledge:review:list | log |
| 2213 | 本体 | C | 2210 | 3 | ontology | knowledge:ontology:list | tree |
| 2214 | 数据映射 | C | 2210 | 4 | mappings | knowledge:ontology:list | form |
| 2221 | 库设置 | C | 2220 | 1 | settings | knowledge:settings:list | edit |
| 2111..2182 | 16 个 F 按钮行 | F | 重挂对应新 C | 原序 | # | **原 perms 16 项一字不动** | # |

- 删除：menu_id=4（若依官网外链）、2100 目录、2110 工作空间、2160 摄取、2130/2140/2150/2170/2180 旧 C 行（C 行职责由新 id 承接）；F 行 UPDATE parent_id 重挂（2111/2112→2201，2121/2122→2201，2131/2132→2213，2141/2142→2202，2151/2152→2203，2161/2162→2211，2171/2172→2212，2181/2182→2221）。
- 图标取自若依自带 `src/assets/icons/svg/` 现有集（上表名均在集内，实现时逐一核验存在性）。
- 组件 component 全部指 `jknow/knowledge/kb-entry/index`（守卫 resolveKnowledgeMenuPath 按 path 解析当前库目标页——现有机制不动）。

## 首页方案 A（前端）

- 登录落地链：`/` → KbRedirect/守卫解析当前库 → `/kb/{id}/graph`（复用现有旧路径接管与守卫链，最小改动：把若依首页路由（动态路由 index）redirect 到 `/graph`，`/graph` 已是 KbRedirect 接管页；若依 index 组件不再渲染）。
- 无库用户（解析失败）：kb-entry 8s 引导卡现有行为兜底。

## 切库器（前端）

- 从 8d2fcbc 提交复活 kb-switcher 组件源码，适配若依侧栏：`layout/components/Sidebar/index.vue` 底部注入（最小侵入：模板尾追加 `<KbSwitcher/>`，样式对齐侧栏宽/暗色），展示当前库名+下拉切换（复用 useKbContext 的库列表与 switchKb 等价跳转：切库=导航到新库的当前页 section）。
- 原 kb-switcher 依赖被清的 rebuildMenus 链不复活——切库器只需库列表与跳转，不需要菜单重组。

## 不做的事（YAGNI）

- 不改 @PreAuthorize 挂载域（mappings 复用 ontology 域是 P2 既有事实）；不动 F 权限 perms；不做菜单 i18n（若依菜单名单语 DB 值）；不恢复 pure-admin 菜单管线。

## 验收标准

- 侧栏：工作台/知识管理/配置 三组在前（8 个业务页分组呈现、图标齐）、系统组在后、无"若依官网"；底部切库器可切库并跳转新库当前页
- 登录后直达当前库图谱页（方案 A）；菜单点各页 → 守卫解析 → 壳内联渲染（上一修复的行为不回归）
- MigrationTests 104 重放绿（V0103 + visible/树断言更新）；vitest/typecheck/smoke-p0/p1 全绿；走查截图（侧栏全貌/切库/登录直达）
