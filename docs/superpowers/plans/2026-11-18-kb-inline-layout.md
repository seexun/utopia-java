# kb/account 若依壳内联修复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** kb 13 条路由与 account 4 页从顶级全屏路由改为若依 Layout children 内联渲染（右侧主体区、侧栏/顶栏/页签保留），account Utopia 壳退役。

**架构：** constantRoutes 重排——`{path:'/kb', component:Layout, hidden:true, children:[相对 path×13]}` 与 `{path:'/account', component:Layout, hidden:true, children:[shell 去壳化容器 + /admin]}`；页面根高度改相对 AppMain 的 100% 链；public-routes.spec 断言随 matched 链更新。

**技术栈：** vue-router 4 / 若依 Layout / vitest。

---

### 任务 1（唯一任务）：路由重排 + 壳退役 + 高度适配 + 验收

**文件：**
- 修改：`jknow-web/src/router/index.ts`（kb/account 段重排）
- 修改：`jknow-web/src/views/jknow/knowledge/account/shell.vue`（去壳化）
- 修改：`jknow-web/src/style/utopia.scss` / `utopia-pages.css`（高度链，按实况）
- 修改：`jknow-web/tests/public-routes.spec.ts`

- [ ] **步骤 1：kb 路由重排**——把现有 13 条顶级对象（`/kb/:kbId/graph` … `/kb/:kbId/docs`，含 `/kb → /library` redirect 保留顶级）包为：

```ts
{
  path: "/kb",
  component: Layout,
  hidden: true,
  children: [
    { path: ":kbId/graph", component: () => import("@/views/jknow/knowledge/graph/index.vue"), hidden: true },
    // …其余 12 条同法：path 去掉 "/kb/" 前缀改相对；component/hidden 原样
    // （chat 的 ":kbId/chat/:conversationId?" 与 doc 的 ":kbId/doc/:docId" 同步改相对）
  ]
},
```

- [ ] **步骤 2：account 重排**——`{path:"/account", component:Layout, hidden:true, children:[ {path:"", redirect:"/account/profile"}, {path:"/account", component:shell 去壳化, children:[profile/my-kbs/tokens 三条]}, {path:"/admin", component:admin/index.vue} ]}`（shell 若需嵌套 children 层则以实况简化为 Layout children 直挂 4 页+页内导航由 shell 残余逻辑或页面 tab 提供——**以 shell.vue 实读结构择简，报告记录选型**）。shell.vue 去壳化：模板外层全屏容器（fixed/100vh/独立侧栏）改为普通 `div.knowledge-account`（含页内 tab 导航 + `<router-view/>`），样式同步收敛。

- [ ] **步骤 3：高度链适配**——`.knowledge-page`/account 根若用 `100vh/min-height:100vh/fixed` 改 `height:100%; min-height:0`（AppMain 的 `.app-main` 已有 `calc(100vh - 50px)` 链承载）；图谱 sigma 容器实测 resize（DevTools 改窗口尺寸）。

- [ ] **步骤 4：测试适配**——public-routes.spec.ts：kb 13 条断言的 matched 链长改新值（Layout→页面=2 或 Layout→容器→页面=3，以实况）；account 4 页同口径；hidden 计数断言同步；跑 `pnpm test` 全绿（418±断言数变化）+ `pnpm typecheck` 绿。

- [ ] **步骤 5：验收**——起后端（19080，已在跑则复用）+ `pnpm dev`（80）→ 浏览器：菜单点"知识图谱"各页均右侧内联（若依侧栏/顶栏/页签在，无全屏跳变）→ URL 直达 `/kb/x/graph`、`/account/kbs` 同在壳内 → 图谱页窗口 resize 画布正常 → 截图 3 张（菜单图谱态/account 态/直达态）存 `docs/superpowers/plans/p4-walkthrough/` → console 零 error。

- [ ] **步骤 6：Commit**

```bash
git add jknow-web/src/router/index.ts jknow-web/src/views/jknow/knowledge/account/shell.vue jknow-web/src/style jknow-web/tests/public-routes.spec.ts docs/superpowers/plans/p4-walkthrough
git commit -m "fix(web): kb/account 若依壳内联——路由重排进 Layout children（全屏跳变修正）+account 壳退役+高度链相对化（规格 2026-11-18-kb-inline-layout）"
```

---

## 自检记录

- **规格覆盖度**：规格 §1 路由重排（步骤 1/2）、§2 样式适配（步骤 3）、§3 测试适配（步骤 4）、§4 验收四条（步骤 5）——全覆盖；`/admin` 挂法二选一落在步骤 2 的"以实况择简并记录"。
- **占位符扫描**：13 条路由的逐条改写以模式给出（同族同法+两条特殊参数形态点名）；无 TODO/待定。
- **类型一致性**：`Layout` import 已存在于 router/index.ts:3；`.knowledge-account` 命名仅 shell 用；hidden 属性形态与既有路由一致。
