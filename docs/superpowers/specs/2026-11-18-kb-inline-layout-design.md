# kb/account 页面若依壳内联设计规格（P2 遗留交互修正）

- 日期：2026-11-18
- 状态：已获用户批准（方案 A + 单节设计）
- 问题：点击"知识图谱"菜单后页面全屏渲染（脱离若依壳）——根因：`/kb/:kbId/*` 13 条路由注册在 constantRoutes 顶级（无 Layout 父级），kb-entry（Layout 内）解析后 `router.replace` 跳出壳；`/account` 族自带 Utopia shell.vue 旧壳同样全屏。与规格 D 节"若依 layout 为壳"原意不符。

## 决策

- 范围（用户拍板）：**全部业务页进若依壳**——kb 13 条 + account 4 页，全部改为 Layout children 内联渲染；account 的 Utopia 自带壳退役。
- 方案：constantRoutes 路由重排（B 动态路由不可行：component 字符串机制不支持参数路径；C 页面自包 Layout 属壳嵌壳反模式，均排除）。

## 设计

### 1. 路由重排（jknow-web/src/router/index.ts）

- 新增 `{ path: '/kb', component: Layout, hidden: true, children: [...] }`：原 13 条 kb 路由的 path 改相对形式（`:kbId/graph`、`:kbId/doc/:docId`、`:kbId/chat/:conversationId?` 等），component 懒加载不变；直达 URL 不变，渲染链变 `Layout → 页面`。
- `/kb → /library` 顶级 redirect 保留原位。
- account：`{ path: '/account', component: <shell 去壳化容器>, hidden: true, children: [profile / kbs(my-kbs) / tokens] }`；`/admin` 挂 Layout 同级 children（或并入 account 容器，以 shell 现有导航结构实况定，实施时择一并记录）。shell.vue 去壳化＝去掉全屏外框样式，仅保留页内 tab 导航 + router-view。

### 2. 样式适配

- `.knowledge-page` 根高度从全屏口径（100vh / min-height:100vh / fixed 定位类）改为相对若依 AppMain 容器的 `height: 100%` 链；改动收敛在 utopia.scss/utopia-pages.css 已 scope 的 `.knowledge-page` 区块。
- 图谱 sigma 画布依赖容器尺寸，容器化后需实测 resize 正常（sigma 自带 resize 监听）。

### 3. 测试适配

- tests/public-routes.spec.ts：kb/account 断言的 matched 链长（原 2 → Layout→页面 或 Layout→容器→页面）、hidden/真组件断言随新结构更新；account 4 页断言同口径。
- smoke 三件套不受影响（HTTP 面）；vitest 全量绿为准。

### 4. 验收标准

- 菜单点"知识图谱"各页 → 若依右侧主体区内联渲染（侧栏/顶栏/页签保留，无全屏跳变）
- URL 直达（/kb/x/graph、/account/kbs）同样在壳内
- vitest 418+ 全绿、typecheck/build 绿；走查截图（菜单态+直达态，前后对比）
