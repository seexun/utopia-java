# 收尾冲刺实现计划（SSE 鉴权+两页重造+V0105+seed 补全）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 打包收口四项已归档缺口——SSE fetch 鉴权（对话打通前置）+ mappings/doc-viewer 两页重造 + V0105 菜单授权 + seed 三缺口补全。

**架构：** SSE 三处 fetch 补 Bearer 头 + EventSource 改 fetch 流式（回调契约不变）；两页照批次模板重造（逻辑零动）；V0105 一条授权迁移+MigrationTests 105；seed 三处数据补全（幂等三连验收）。

**技术栈：** fetch/ReadableStream SSE 解析 / Vue3 模板层 / Flyway / psql / vitest。

---

## 全局约束

1. 两页重造逻辑零动铁律（script 仅 import/纯展示 computed）；SSE 改动限传输层（回调契约/消费方零动）。
2. MigrationTests 计数 105；`pnpm test` 420 基线；双 smoke 零退化；走查独立 Playwright+DOM 断言。
3. 环境：前端 80（baseUrl 19080 保留不提交）/后端 19080/compose 三容器；`LC_ALL=C grep -a`；截图存 `docs/superpowers/plans/final-sprint/`。

---

### 任务 1：SSE 鉴权（api.ts 三处 fetch + EventSource fetch 化）

**文件：**
- 修改：`jknow-web/src/domain/api.ts`（:47 通用 fetch/:2841 stream/:2858 chat 三处补 Authorization）
- 修改：`jknow-web/src/domain/eventStream.ts`（EventSource → fetch 流式）

- [ ] **步骤 1：api.ts 三处 fetch 补头**——每处 `fetch(path, { credentials: "include", ... })` 加 `headers: { Authorization: \`Bearer ${getToken()}\` }`（getToken 从 `@/utils/auth` import——若 api.ts 在 domain 层不便引 vue 生态，读 client.ts 现有取 token 方式同源处理，报告记录选型）；已有其它 headers 的 fetch 合并不覆盖。

- [ ] **步骤 2：eventStream.ts fetch 化**——保持 `subscribeEvents(url, { onMessage, onError, ... })` 回调契约（读文件现签名），内部改：

```ts
const res = await fetch(url, { headers: { Authorization: `Bearer ${getToken()}` }, credentials: "include" });
if (!res.ok || !res.body) { onError?.(new Error(`SSE ${res.status}`)); return; }
const reader = res.body.getReader(); const decoder = new TextDecoder(); let buf = ""; let data = "";
// SSE 帧解析：逐行累积 data: 前缀行，空行分发 onMessage(JSON.parse(data))；event: 行若现契约有消费则保持
```

（abort/close 语义对照现 EventSource 的 close() 用法——返回句柄含 close() 调 reader.cancel()。）

- [ ] **步骤 3：验证**：`pnpm test` 420+typecheck 绿（w3-chat 全帧测试若依赖 EventSource mock——适配后绿）→ 起后端+真实 token curl：`curl -N -H "Authorization: Bearer $TOKEN" http://localhost:19080/api/v1/kbs/{kb}/chat -d '{"query":"hi"}'` ——**验收=响应头 200+content-type: text/event-stream 且响应体首帧非若依 401 JSON**（无 LLM key 时模型不回复但握手即证鉴权通过；截图/输出留档）→ reattach stream 同验。

- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/domain/api.ts jknow-web/src/domain/eventStream.ts docs/superpowers/plans/final-sprint
git commit -m "feat(sse): 流式请求过若依鉴权——三处 fetch 补 Bearer+EventSource 改 fetch 流式（回调契约不变，握手 200 实证）"
```

---

### 任务 2：mappings + doc-viewer 重造（968 行）

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/mappings/index.vue`（701，模板层）
- 修改：`jknow-web/src/views/jknow/knowledge/doc-viewer/index.vue`（267，模板层）

- [ ] **步骤 1：读实况定形**：mappings 骨架（状态过滤/表列集/revisions 面）；doc-viewer 骨架（chunk 分块/引文高亮/锚跳转）。
- [ ] **步骤 2：模板重造**：mappings=PageHeader(数据映射·库名)+UPill 状态过滤+el-table 卡（列集不增删）；doc-viewer=PageHeader(文档名·库名)+原文卡（chunk 排版+琥珀 .mark 高亮+锚跳转）。逻辑零动；令牌纪律。
- [ ] **步骤 3：验证**：420+typecheck 绿 → 走查（Playwright+DOM 断言）：mappings 列表+过滤切换、doc-viewer 从文库行点入+引文高亮可见 → 截图≥3 张 → console 零 error。
- [ ] **步骤 4：Commit**

```bash
git add jknow-web/src/views/jknow/knowledge/mappings jknow-web/src/views/jknow/knowledge/doc-viewer docs/superpowers/plans/final-sprint
git commit -m "feat(reskin): mappings/doc-viewer 两页重造——列表型+读页型模板（逻辑零动，审查页过滤条落点页收口）"
```

---

### 任务 3：V0105 菜单授权（非 admin 可见）

**文件：**
- 创建：`jknow-admin/src/main/resources/db/migration/V0105__role_menu_grant.sql`
- 修改：`jknow-admin/src/test/java/tech/seexun/jknow/MigrationTests.java`（105+断言）

- [ ] **步骤 1：V0105 SQL**：

```sql
-- V0105__role_menu_grant：普通角色知识菜单授权+演示访客绑定（收尾冲刺规格 §3）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2299
ON CONFLICT DO NOTHING;
INSERT INTO sys_user_role (user_id, role_id) VALUES (9999, 2) ON CONFLICT DO NOTHING;
```

（`ON CONFLICT DO NOTHING` 需这两表有唯一约束——先读 V0000 的 sys_role_menu/sys_user_role DDL 确认约束名/唯一键；若无唯一约束则改 `WHERE NOT EXISTS` 形态，幂等语义不变。）
- [ ] **步骤 2：MigrationTests**：MIGRATION_COUNT=105；断言 `role 2 的 2100-2299 计数=27`、`sys_user_role 中 9999→2 存在`。
- [ ] **步骤 3：验证**：MigrationTests 2/2 绿（重放 105）→ 重启后端应用 V0105 → **演示访客登录**（demo/admin123——若 9999 密码非此则 psql 重置为与 admin 同 BCrypt 串）→ 侧栏见三分组菜单+可进图谱页（截图 2 张）→ smoke 双绿零退化。
- [ ] **步骤 4：Commit**

```bash
git add jknow-admin/src/main/resources/db/migration/V0105__role_menu_grant.sql jknow-admin/src/test/java/tech/seexun/jknow/MigrationTests.java docs/superpowers/plans/final-sprint
git commit -m "feat(menu): V0105 普通角色知识菜单授权+演示访客绑定——非 admin 三分组可见（27 行幂等，访客登录实证）"
```

---

### 任务 4：seed 三缺口补全 + 总验收

**文件：**
- 修改：`jknow-web/../scripts/seed-demo.sql`（三处数据补全——注意路径为仓库根 `scripts/seed-demo.sql`）

- [ ] **步骤 1：三缺口补全**（先读现 seed 与 PendingService 写入的真实快照形状对齐）：
  1. audit_events 演示行 detail 补完整快照（subject/predicate/object/confidence/qualifiers 等字段名以 DecisionRow 消费面为准）
  2. 待审行 time_words 补 `char_start/char_end`（对齐 chunk 原文偏移——从 chunk 文本实算）
  3. relation_types 补种子行（固定 uuid）+ 事实/待审行落 `predicate_id`（消问号）
- [ ] **步骤 2：幂等三连**：teardown→seed→teardown→seed 零 ERROR。
- [ ] **步骤 3：验收**：确认一条待审 200（非 422）；裁决史行无 undefined/谓语非问号/置信度徽章在（Playwright 断言+截图）→ 总验收：420+typecheck+build、双 smoke、全链截图归档（≥5 张）。
- [ ] **步骤 4：Commit**

```bash
git add scripts/seed-demo.sql scripts/teardown-demo.sql docs/superpowers/plans/final-sprint
git commit -m "fix(seed): 三缺口补全——audit 完整快照/time_words 偏移/谓语 relation_types 落库（确认 200+裁决史完整实证，幂等三连绿）"
```

---

## 自检记录

- **规格覆盖度**：§1 SSE（T1）/§2 两页（T2）/§3 V0105（T3）/§4 seed（T4）/总验收（T4 步骤 3）——全覆盖。
- **占位符扫描**：SSE 帧解析骨架给出；快照字段名/time_words 偏移/relation_types uuid 以"实况对齐"规则锚定（有读取指令与对齐目标）。无 TODO/待定。
- **类型一致性**：getToken 源/subscribeEvents 契约/V0105 区间 2100-2299=27 行/9999-2 绑定，全计划一致。
