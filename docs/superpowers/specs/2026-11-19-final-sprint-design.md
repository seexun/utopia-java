# 收尾冲刺规格（SSE 鉴权+mappings/doc-viewer+V0105+seed 补全）

- 日期：2026-11-19
- 状态：已获用户批准（四节设计 OK）
- 背景：UI 对齐 gvdata 子项目（基座+批次 1-4）已合并收官；本规格打包四项已归档的用户可感知缺口，一个分支收口。

## 设计

### 1. SSE 鉴权（对话功能打通前置；master 既有横切问题修复）

- `src/domain/api.ts` 三处 fetch（:47 通用请求/:2841 conversations stream/:2858 chat POST）补 `Authorization: Bearer ${getToken()}`（token 源与 `api/knowledge/client.ts` 拦截器同源——`@/utils/auth` getToken）；`credentials: "include"` 保留（无害）。
- `src/domain/eventStream.ts`：原生 `EventSource`（不支持自定义头）改为 **fetch 流式实现**——`fetch(url, { headers: { Authorization: Bearer ... } })` + `ReadableStream` + TextDecoder 逐行解析 SSE 帧（`data:` 行聚合、空行分发），`subscribeEvents` 的消息/错误/关闭回调契约不变（消费方零动）。
- 验收：真实 token 下 `/api/v1/kbs/{id}/chat` SSE 握手过鉴权（无 LLM key 环境验证：响应头 200+`text/event-stream` 且响应体非若依 401 JSON——即"握手打通"，模型输出另需 key）；`/conversations/{id}/stream` reattach 同验。

### 2. mappings + doc-viewer 重造（968 行，逻辑零动铁律）

- **mappings**（701 行，列表型）：`PageHeader(数据映射, subtitle=库名)` + 状态过滤条（UPill）+ 映射表卡（el-table：列集以现有页实况不增删；revisions/状态徽章 badge-soft）——批次 1 列表型模板复用。
- **doc-viewer**（267 行，读页型）：`PageHeader(文档名, subtitle=库名)` + 原文卡（.card：chunk 分块排版 + 琥珀引文高亮[highlightSegments 已就位] + 引文跳转锚）。
- 两页 script 仅 import/纯展示 computed；走查用独立 Playwright+DOM 断言（T1 纪律）。

### 3. V0105 菜单授权（非 admin 可见）

- `V0105__role_menu_grant.sql`：`sys_role_menu` 为普通角色（role_id=2）插入 2200-2221 区间全部菜单行（3M+8C+16F=27 行——F 按钮权限一并授）；`sys_user_role` 为演示访客（user_id=9999）绑 role_id=2（幂等 ON CONFLICT）。
- MigrationTests：MIGRATION_COUNT=105+断言（role 2 的 2100-2299 区间知识菜单计数=27；9999 有角色绑定）。
- 验收：演示访客（demo/admin123）登录侧栏可见三分组菜单并可进页面（@PreAuthorize 的 perms 由 F/C 行 perms 支撑——V0103 perms 即此套）。

### 4. seed 三缺口补全（演示数据修复，改 scripts/seed-demo.sql）

1. `audit_events` 演示行 detail 只带 note → 补完整快照字段（对齐 DecisionRow 渲染面：subject/predicate/object/confidence 等——以 PendingService 写入的真实快照形状为准）。
2. 待审行 `time_words` 缺 `char_start/char_end` → 补齐（确认操作不再 422）。
3. 待审行谓语缺 relation_types 落库 → 种子 INSERT 补 relation_types 行并在事实行落 predicate_id（消 fallback 问号）。
- 验收：teardown+seed 后——确认一条待审 → 200（非 422）；裁决史行无 undefined 字面、谓语非"？"、置信度徽章在；teardown→seed 幂等三连零错。

## 总验收

- `pnpm test` 420 基线（MigrationTests 105）+ typecheck + build 绿
- 双 smoke 零退化；走查截图 ≥5 张（mappings/doc-viewer 页/SSE 握手证据[响应头]/演示访客菜单/裁决史完整态）存 `docs/superpowers/plans/final-sprint/`
- console 零 error

## 不做的事

- 不做 LLM 模型配置（key 属环境）；不动对话 UI（批次 3 已造）；docs/legal 两路由归通用打磨批；暗态令牌层不做。
