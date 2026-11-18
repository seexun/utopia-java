# P2 前端迁移验收报告

- 分支 `p2-frontend`，基线 `p1-done`，收尾提交见文末，tag **`p2-done`**
- 目标：utopia-web 知识图谱前端能力迁入 jknow-web（若依 Vue3+TS 壳）——页面族落位 `views/jknow/knowledge/**`、若依登录与动态路由、api 客户端切若依 Bearer token、vitest 全绿 + UI 全链走查；同时兑现 P1 落字两项硬依赖（pt- 权限集展开 + @PreAuthorize 挂载、entry point 适配裁定）
- 规模（计划提交 `328cbb3` → 收尾前 `b2aa010`）：5 个任务提交 + 1 修复提交，274 files，+64,394 / −27（大头：pnpm-lock 8273 行、i18n 词典 4923 行、页面/组件族 50+30 件、api/domain/composables/store 支撑层）
- dev 环境（写实）：PG 15432 / Redis 36379（compose），后端 19080（jar），前端 `pnpm dev` 端口 80；T5 终验日另起本地镜像 `utopia-es-ik:8.14.3`（9200）供检索面实测——ES 不在 dev compose 内，检索面的环境边界见 §八

## 一、五任务勾选状态

| # | 任务 | 提交 | 状态 |
|---|---|---|---|
| 1 | jknow-web 基建接入：依赖合并（38 新增 + 7 升版，取高原则）+ tailwind v4 + vue-i18n 11 + vitest 4（app 面） | `9ffea36` | [x] DONE |
| 2 | 支撑层移植：api 16 件（client.ts 改若依 Bearer/401）+ domain 40 件 + composables 8 + 样式三件套(+reset) + 32 spec 随迁 | `ef8850a` | [x] DONE |
| 3 | 页面族/组件族移植（views 14 族 50 件 + components 9 族+基建件）+ store/knowledge 落位 + kb 动态路由 + 红清单清零（56 spec 绿） | `d03dda4` + 修复轮 `98cbe5b` | [x] DONE |
| 4 | 菜单开启 V0102（25 行 visible + 8 C 页 component=kb-entry）+ PT_PERMISSIONS 16 项展开 + @PreAuthorize 25 controller 挂载 + kb-entry 入口组件 | `b2aa010` | [x] DONE |
| 5 | P2 收尾——/account 族路由收口 + 全链验收走查 + p2-report + README 前端章节 + tag | 本提交（tag `p2-done` 处） | [x] DONE |

计划文档提交：`328cbb3`（P2 实现计划）。各任务详报：`.superpowers/sdd/2026-11-18-p2-frontend/task-{1..5}-report.md`（git-ignored 台账）。

## 二、二分表终稿（23 族，以源仓 router 实况核对）

| 页面族 → `src/views/jknow/knowledge/<域>/**`（14 族 50 件） | 组件族 → `src/components/knowledge/<族>`（9 族）+ 基建件 |
|---|---|
| graph、search、chat、ontology、review、library、mappings、kb-page、kb-settings、account、docs、admin、legal、doc-viewer | kb-switcher、alert-bell、user-menu、wordmark、sources-rail、kb-redirect、not-found、placeholder、server-down；+ kit.tsx / dialog.tsx / ToastHost.vue（源仓 src/components/utopia）、+ ReCol/index.ts、+ ReIcon/offlineIcon.ts |

依据：源仓 router/modules/utopia.ts（KbPage/KbRedirect/9 个真页面族）+ remaining.ts（legal/account/admin/docs）+ router/utils.ts（not-found 兜底）+ layout 引用（kb-switcher/user-menu/alert-bell/wordmark）。23 族与简报一致零修正；kit 三件与 pure-admin 基建件按依赖闭包补入组件族侧。

**路由注册面实况**（constantRoutes，hidden 不进菜单）：

- `/kb/:kbId/*` 10 页（graph/search/library/doc/:docId/ontology/review/mappings/settings/chat/:conversationId?/docs）——比简报 8 条多出的 library/doc/mappings 三条为 T3 审查修复轮补全（缺即 404）；
- `/kb` → `/library` 重定向 + 旧路径族 8 条（/graph /search /chat /library /ontology /mappings /review /kb-settings → KbRedirect 接管）+ `/docs`→`/docs/ingest` 与 `/docs/:slug`；
- **`/account` 族（T5 收口，本报告 §五.3）**：account shell + profile/my-kbs/tokens + /admin 挂壳下，hidden 独立整页；
- 登录校验全交若依 permission.ts beforeEach（未登录 → /login?redirect=，冒烟实证）。

## 三、红清单清零对账（T2 红 25 = T3 修 24 + 裁定弃 1）

T2 末 `pnpm test`：57 文件 = 32 绿 / **25 红**（断言 276/276 全过，红全部是缺 T3 基建的模块解析失败）。T3 末：56 文件 / 416 断言全绿。对账：

| T2 红族 | 数 | T3 处置 |
|---|---|---|
| store 面（`@/store/*` 缺件） | 16 | store 落位 `src/store/knowledge/` + 4 个 composable 桩还原 → 全绿（3 件断言适配：user-session/kb-context 随 client.ts 行为改造，w3-chat 是真 bug 修复——vue-router 4↔5 可缺省参数语义差） |
| 视图族（`@/views/utopia/**`） | 4 | 页面落位 + import 改写 → 全绿 |
| 组件基建（kit/ReCol/tree.ts/offlineIcon） | 3 | 闭包补件落位 + icon-offline NON_ICON_KEYS 登记 4 条 → 全绿 |
| public-routes | 1 | 改写为 jknow 语义（kb 路由全在 constantRoutes/hidden/独立整页/KbRedirect/纯重定向）21 断言 → 绿；T4 修复轮补 3 条存在性断言（24 断言），**T5 再补 /account 族 5 断言（29 tests）** |
| locales-dead-keys | 1 | **裁定弃（删 spec）**：守的是 pure-admin 基座 yaml 词条（T2 已弃该体系，vue-i18n dicts 且 i18n.spec 9 例在守）；迁入是零引用死文件。总账 57=56+1 |

T5 终值：**56 文件 / 421 断言全绿**（416 + T5 新增 5）。

## 四、@PreAuthorize 挂载清单（T4，粗粒度：类级 list / 写方法级 edit）

25 个 controller 挂载 + 2 个公开面豁免（HealthController：/api/v1/health permitAll；ApiPushController：ingest_token 密钥认证面，类级 :list 会把无会话推送 403）：

| 域 | Controller |
|---|---|
| workspace:list/edit | WorkspaceKbController、WorkspaceKbWriteController、MembersWriteController |
| graph:list/edit | GraphController |
| ontology:list/edit | OntologyController、OntologyReadFamilyController、OntologyImportController、MappingsController |
| search:list | SearchController（方法级也是 :list——POST 是查询载体，源仓同形；见 §五.2 附注） |
| chat:list/edit | ChatController、ChatMiscController |
| ingest:list/edit | SourcesController、DocumentsWriteController、DocumentsController、JobsController（ApiPushController 豁免） |
| review:list/edit | ReviewController、ReviewWriteController、QuestionsRulesWriteController |
| settings:list/edit | SettingsPutController、DatasourcesController、TokensController、AdminUsersController、AlertsController、McpController、ExportController |

连带收口（挂注解暴露的缺口）：AppExceptionHandler 补 AccessDeniedException → 403 源文案；StoreTestBase 补直调登录态助手（ChatFx 11 类 + PhraseAlignmentTests）。TokensPatApiTests 新增 pt- 双通复核用例（读面 200 / 嵌套签发 200 / 空权限集对照 403）。

## 五、三裁定落档

### 1. entry point 适配裁定（T4 §八）

**不改后端**，保持若依 entry point 的 200+体 `code:401` 形状。知识前端 client.ts 成功分支按体 `code===401` 截住走若依会话过期处置（ElMessage + logOut + /login?redirect=），源 401 体形状仅 pt- 面（PersonalTokenAuthFilter 直写响应）保留——两形状互不干扰，契约闭合。api-client.spec 有对应断言用例（200+体401 → 截住）。

### 2. pt- 权限集展开（T4 §三）

`PersonalTokenAuthFilter` 的 `Set.of("knowledge:*")` → 常量 `PT_PERMISSIONS`（V0100 全集 16 项逐字）。**展开是挂 @PreAuthorize 后的硬依赖**：若依 `@ss.hasPermi` 精确匹配（仅 `*:*:*` 通配），`knowledge:*` 必 403；展开后 pt- 双通实测 200。附注：`knowledge:search:edit` 为有意空缺（search 域唯一端点是查询载体挂 :list，未来加检索写面时再挂）。

### 3. kb-entry 入口组件 + /account 族路由收口（T4 §二 / T5）

- **kb-entry**：V0102 的 8 个 C 页 component 统一指菜单入口组件（实测直连死页——kb 页 kbId 严格取自 /kb/:kbId 路由参数，菜单路由无参；my-kbs 依赖 localStorage 记忆）。kb 页模式解析（workspaces→kbs，含空工作区自动建 General）后 replace 到 /kb/{id}/{页}；workspace 模式原地渲染 my-kbs；ingest→library 域→页换名在组件内映射。
- **/account 族（T5 收口，T4 遗留交接项）**：kb 页顶栏 user-menu（个人资料/知识库/管理）与 kb-switcher「新建知识库」（/account/kbs?create=true）的链接目标在 T3 只迁了页面未注册路由，直落 404。T5 走查实测确认 404 面（t5-account-404-before.png）后**最小修**：constantRoutes 补 `/account`（shell）+ profile/my-kbs/tokens + `/admin`（挂壳下，源仓 remaining.ts 同构，子项绝对路径）——页面零新增，纯路由注册；public-routes.spec 补 5 断言钉住。源仓 /admin 的 meta.roles:['admin'] 守卫 403 闸不随迁：入口可见性由 user-menu 仅对 is_admin 渲染兜底，后端面有 @PreAuthorize 粗粒度闸；源仓另有 /settings→/admin 旧书签重定向，jknow 无历史用户未迁。

## 六、账本延后项全量归纳（progress.md 逐条兑现）

| 账本条目 | 归纳与核实（T5 实测） |
|---|---|
| T1 parked：vite.config 2 行 base 注释被改写 | T2 还原了 loadEnv 区注释，但 base 注释行还原不完整（「子路径上…指定这个子路径」被改写为「子路径下…指定子路径」，T5 核验只对了 14-17 行漏了这行）；**终审收口轮还原该行为 c5ae98d 基线逐字节原文**（diff 核验一致，typecheck 0 错）✓ |
| T1 minor：element-plus 口径误差 | 声明 `^2.14.7`（简报指定），lockfile 实装 **2.14.8**（semver 范围内取新；T1 报告「装出 2.14.7」系口径小误差）✓ |
| T1 minor：passWithNoTests 已清 | T2 移除；T5 grep vite.config.ts 零命中 ✓ |
| T2 minor：若依视图 2 字符触碰备案 | views/system/dept/index.vue:292、menu/index.vue:464 补 `!`（@types/js-cookie 严格化暴露的 TS2538，与邻行 305/477 既有写法一致，零行为变化）——协调者补认知备案接受，维持现状 |
| T2 minor：client.ts timeout 注释损失 | 源仓 timeout 注释未随迁，值仍 60000——知识损失记档，无行为面 |
| T2 minor：api 15→16 勘误 | 简报名单漏 types.ts（全体域模块类型底座）与 workspaces.ts（useKbContext 具名导入+快照闸断言所需），实迁 16 件 |
| T3 minor：graph `?? []` 防御收口补记 | graph/index.vue 数据引用防御 5 处（270/284/308 等）——T5 grep 核实在库 ✓ |
| T3 minor：kb-page 死件去留裁定 | **裁定：保留**。kb-page/index.vue 在 jknow 路由体系无挂载点（/kb/:kbId/* 平铺路由不经壳组件，chat 仅头注引用）——与 kb-switcher 同为「随迁无宿主」件（源仓宿主 pure-admin layout 不迁）。保留理由：头注钉着多根地雷教训（单元素根纪律）与 KbScope 契约文档价值；expression-draft 视图族源仓也未迁（ASSET-LEDGER Ⅲ类不搬），同源截断一致。P3 清理候选 |
| T3 minor：§七原轮冒烟表述未就地勘误 | §十修复轮记录已覆盖（404 截图作废重拍、路由补全）——报告读者以 §十为准 |
| T4 minor：PhraseAlignmentTests 注释重复两行 | 811-812 与 823-824 两处直调登录态注释逐字重复（两测试方法各一份）——纯外观，零行为，P3 顺手可清 |
| T4 minor：kb-entry 未知路径段死等边界 | 手改 URL 到 /knowledge/<未知段> 停「加载中」等待页（segment 不在 KB_PAGES 且非 workspace → 不 replace 也不渲染）——KbRedirect 同观感，T4 已接受；与下条同根 |
| T4 minor：kb 解析失败无错误面（非管理员首登） | **T5 实测走查**（§七.3）：零工作区用户停在等待页静默等——源仓同语义（#580「非管理员 403 就静默等管理员来创建」），非迁移缺陷；错误面优化记 P3 |
| 补记：auth.ts 的「不迁」与实况 | 计划口径是 auth.ts 不迁（源仓 cookie 会话面无意义）；T3 实况以**若依适配层**形态新建 `api/knowledge/auth.ts`——导出面与源仓同名同形（me/login/register/logout/updateMe/changePassword/oidc 三件），实现映射若依端点（me→GET /getInfo、login/register/logout→/login、/register、/logout、updateMe/changePassword→/system/user/profile(+/updatePwd)、oidc→恒「未启用」），不写 /v1/ 请求故快照闸 actual 面不变（RETIRED_AUTH 9 端点登记继续成立）。**§三/任务 2 的「api 16 件」计数含它**（15 源仓件 + 适配层 1 件） |

**若依自有文件触碰面汇总**（全部为闭合所需最小面，逐任务详报有逐行理由）：views/login.vue（T1，2 处 Cookies.set 包 String()）、views/system/dept/index.vue:292 + views/system/menu/index.vue:464（T2，各补 2 字符 `!`）、store/modules/user.ts（T3，pwdChrtype `?? ''`）、types/api/login.ts（T3，+可选 pwdChrtype）、views/lock.vue（T3，Particle 具形）、types/global.d.ts（T2/T3，vue/axios 声明手术——上游 RuoYi-Vue3 既有钝化手段的扩展面）、tsconfig/vite.config.ts/package.json（基建：jsx/noImplicitAny/unplugin-icons types、tailwind/vitest/projects/`/api` 代理、依赖合并）、main.ts（i18n/tailwind 样式引入/VueQueryPlugin/ToastHost/offlineIcon）；App.vue 零净变更（ToastHost 并入又撤出）。dept/menu 等其余若依面零触碰。

## 七、T5 全链验收记录（2026-11-18 实测）

环境：compose pg15432/redis36379 + 后端 jar 19080（V0102 已应用）+ `pnpm dev` 80（baseUrl 临时指 19080，收尾还原）+ 本地 ES 8.14.3（utopia-es-ik 镜像，9200）。admin/admin123 与新建 ryuser/123456（知识库用户角色：V0100 全 25 菜单行）双会话走查。**26 张 t5-*.png 入库 `docs/superpowers/plans/p2-walkthrough/`；每页 console 零 error、network 面板 api/v1 均 200。**

### 1. admin 会话：8 菜单页 + 交互

| 菜单/入口 | 落点 | 截图 | 核验 |
|---|---|---|---|
| 侧栏展开 | — | t5-menu-expanded.png | 知识图谱目录 8 子菜单 |
| 工作空间 | /knowledge/workspace 原地渲染 | t5-workspace.png | 知识库表（General/所有者/文档/成员） |
| 建库→进库 | my-kbs 新建对话框 → /kb/{新}/settings | t5-create-kb-into.png | 建「走查库」成功并进库（设置页） |
| 切库 | 工作空间页「打开」General | （graph 链路承接） | kbStore 切换 + 落 General 图谱 |
| 图谱 | /kb/{id}/graph | t5-graph-nodes / -zoom / -entity-panel / -empty | **浏览**：3 实体 2 事实渲染（张三/李四/Acme 公司，顶栏「3 个实体 · 2 条事实 · 2 条现行」——走查种子直插 DB，图 API 无直建端点；**截图后已回收**，见 §八 mvn 行插曲）；**缩放**：放大/缩小/适应视图/环形-力导-聚簇布局；**选中实体**：搜索框选张三 → 实体面板（1 条事实 + 李四链接 + 关系/记录历史页签）；空库空态（走查库）另证 |
| 本体 | /kb/{id}/ontology | t5-ontology.png | 类/属性页签 + 图/表格视图切换（名称/父类/不相交/实例/属性列）+ 新建类/导入/业务规则 |
| 检索 | /kb/{id}/search | t5-search.png | query「张三」POST /search **200** + 空索引「没有结果」形态（ES 已起；索引内容由抽取管线产出，无 LLM 即空） |
| 对话 | /kb/{id}/chat | t5-chat.png | SSE 降级形态：页面「还没配置对话模型/配置模型」；发送消息 POST /chat 200，回复区「回答连接已中断，请重新打开会话查看状态」（无 LLM key 的 UI 降级，记档） |
| 摄取 | /kb/{id}/library | t5-ingest-library.png | sources rail（上传源 + 计数 2）+ 文库表 + **文档上传**（t5-doc.txt 49B 上传成功 → 排队中；worker 关（JKNOW_JOBS_WORKER=false 默认）即停排队——无 LLM 的既有降级，t3-doc.txt 的「失败」同族） |
| 审查 | /kb/{id}/review | t5-review / -pending.png | 总览仪表盘（裁决统计/趋势/**现在持有 2 条事实**成色卡）+ 待审核队列页（空态「没有待审核的」——pending 事实由抽取管线产出） |
| 设置 | /kb/{id}/settings | t5-settings.png | 常规/成员/活动页签 + 名称/描述/可见性（默认库锁定对所有人开放）+ 物化推理等自动化配置 |
| 账户族 | /account、/account/tokens、/admin | t5-account-profile / -tokens / -admin.png | 修复后三页全渲染（个人资料表单/pt- 令牌管理/管理面模型页签） |
| kb-switcher 建库目标 | /account/kbs?create=true | t5-account-kbs-create.png | 落 my-kbs 且 **create=true 自动开新建对话框** ✓ |
| alert-bell | 账户壳顶栏铃铛 | t5-alert-bell.png | 14 条告警（抽取停摆）+ SSE `alerts/events` EventSource 200 + unread 计数徽标 |
| user-menu | 账户壳顶栏用户钮 | （快照核验） | 头像+若依/若依+管理员 chip+admin——display_name 与若依侧栏同源（getInfo nickName）一致；菜单项 个人资料/知识库/管理(仅 admin)/语言(中文)/退出登录 |

注：**kb-switcher 组件在 jknow 无宿主**（源仓宿主 pure-admin layout 不迁；grep 全库零 import）——「切库」实际入口是工作空间页 my-kbs「打开」，走查以该路径验明切库链路（kbStore.kbId 变更 + 落库渲染）。组件本体与 kb-page 同为随迁无宿主件（§六裁定保留）。

### 2. 若依原生面回归

用户管理（t5-ruoyi-system-user.png，并实测走 UI 新增 ryuser）/ 在线用户（t5-ruoyi-monitor-online.png，admin 会话在线 1 条）/ 表单构建（t5-ruoyi-tool-build.png）——三面渲染正常，console 零 error。

### 3. 非管理员首登（T4 审查建议场景）

建 ryuser/123456 + 「知识库用户」角色（sys_role_menu 2100-2182 全 25 行，SQL 造）：

1. 登录 → 侧栏仅 首页+知识图谱（菜单权限门控 ✓，t5-ryuser 系列前置）；
2. **零工作区态**：点 工作空间 → kb-entry 停「加载中」静默等待（t5-ryuser-no-workspace.png）——与源仓语义一致（#580 注释：非管理员静默等管理员创建），无错误面为已知边界（§六末两条）；
3. **空工作区自动建 General**：admin 经 API 造「T5 协作区」空工作区并拉 ryuser 入成员（PUT members upsert）→ ryuser 重新进入 → **自动建 General 库**（is_default=true）+ my-kbs 完整渲染（General 默认/管理员/0 文档/1 成员/由 润用户 加入）——t5-ryuser-auto-general.png；
4. 图谱菜单 → replace 到 /kb/{auto-General id}/graph，工具栏 + 「还没配置对话模型，请联系管理员。」降级提示——t5-ryuser-graph.png。全程 console 零 error。

### 4. 降级形态汇总（环境边界，非缺陷）

| 面 | 形态 | 根因 |
|---|---|---|
| 对话回复 | 「回答连接已中断，请重新打开会话查看状态」 | 无 JKNOW_LLM_API_KEY（SSE 建流后无模型应答） |
| 检索结果 | 「没有结果」（200 空结果集） | ES 空索引——索引内容随抽取管线产出 |
| 文档抽取 | 排队中（新上传）/ 失败（t3-doc.txt） | jobs worker 默认关 / 抽取需 LLM |
| 图谱编辑弹窗 | 无（面板只展示） | 源仓该面属其任务 6 未实现，同源截断 |

## 八、T5 终验输出（三闸门 + 全仓）

| 闸门 | 结果 |
|---|---|
| `pnpm test` | **56 文件 / 421 断言全绿**（416 + T5 的 /account 族 5 断言；快照闸含 RETIRED_AUTH 9 端点登记） |
| `pnpm typecheck` | **0 错误**（T3 起保持，T1 基线 7 条已全数消解） |
| `pnpm build:prod` | 绿（23.3s）；chunk 体积告警不阻塞：index 2060KB/gzip 684KB、cache 1107KB/gzip 371KB（sigma+graphology+markdown 链，§九 P3） |
| `./scripts/smoke-p0.sh` | **20/20**（若依面零退化） |
| `./scripts/smoke-p1.sh` | **61 PASS / 1 SKIP**（T4 基线 60/2；差额=终验日 ES 可达 → 检索面条件断言转 PASS；LLM 仍 SKIP——环境改善非退化） |
| `mvn -q test` 全仓 | **exit 0：13 模块合计 1330 run / 0 failures / 0 errors / 9 skipped**（admin 970/6S + common 6 + reason 107 + ingest 62/1S + extract 109 + store 24 + search 5 + contract 47/2S；MigrationTests 103 迁移含 V0102；T4 基线 14S→9S 系 ES 可达 8 例转绿跑）。插曲：首跑 2 例红系走查种子直插共享库破坏只读测试前提（「General 唯一且空」「实体最多库=Rust 示例库」），回收种子后复跑全绿——非代码缺陷，走查造数纪律记档 |

## 九、遗留清单（P3 候选）

1. **kb-switcher / kb-page 随迁无宿主件**（§六裁定保留）：切库现走工作空间页；若 P3 想要 kb 页顶栏切库器，需给 kb 页补顶栏宿主或删件收敛。
2. **kb-entry 解析失败无错误面**：零工作区/未知路径段停「加载中」——建议补错误/引导页（源仓同语义，非回归）。
3. **构建体积**：index/cache 两 chunk 超限告警（sigma+graphology+markdown 链）——manualChunks 专项。
4. **样式 scope 收敛点**：utopia.scss/utopia-pages.css 已 scope `.knowledge-page`；body[layout] 系若依壳永不命中原样保留——若后续发现互染再收。
5. **类型面现代化**：global.d.ts 的 vue/axios/EP 替换式声明（any 型扩展面）是源仓钝化手段的延续；**另 tsconfig `noImplicitAny:false` 放宽**（源仓 strict:false 下 97 处隐式 any 同款写法，strict 其余位全开）；augmentation 化、收 noImplicitAny、修约 300 条上游潜在严格性错误留专项。
6. **PhraseAlignmentTests 注释重复两行**（811/823）纯外观。
7. **菜单点击 replace 后若依 tagsView 留 /knowledge/<页> 空标签**（T3 已接受行为）。
8. **knowledge:search:edit 有意空缺**（§五.2），检索写面出现时补挂。
9. **无 LLM key / worker 关的降级面**（§七.4）——配 JKNOW_LLM_API_KEY 与 worker 后自愈，非前端缺陷。
10. **元素选择 WebGL 拾取在自动化下不可点**（源仓同款，dev 有 window.__graphHover 句柄）——人工走查已覆盖选中链路。
11. **ruoyi 原生 login/register 页与知识域 i18n 双体系并存**：若依面不经 t()，业务面 vue-i18n 独立——长期可评估统一。
