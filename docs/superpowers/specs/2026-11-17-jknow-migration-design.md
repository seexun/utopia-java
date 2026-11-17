# jknow 迁移设计规格：utopia 知识图谱系统 → 若依（RuoYi-Vue 3.9.2）

- 日期：2026-11-17
- 状态：已获用户逐节批准（A/B/C/D/E 五节）
- 源仓（只读参照，保持不动）：`D:\workspace_com\utopia-java`（Spring Boot 3.3.13 / Java 21 / PG / ES+IK / 9 模块约 8.4 万行 Java + utopia-web 前端）
- 目标仓：`D:\workspace_com\jknow`（RuoYi-Vue 3.9.2：Spring Boot 4.1.2 / Java 17 / MySQL / Redis；含 RuoYi-Vue3-typescript 前端；当前为解压 zip，无 `.git`）

## 0. 已拍板的七项决策

| # | 问题 | 决策 |
|---|---|---|
| 1 | 整合深度 | **若依为主，业务重宿主**：若依接管认证/用户/角色/菜单/权限；删除 utopia 自有 JWT/Argon2/OIDC 认证 |
| 2 | 业务范围 | **全量迁移**：27 个 Controller 的全部能力（图谱/本体/检索/对话/摄取/任务/审查/MCP/导入导出/工作空间） |
| 3 | 前端归属 | **统一进若依前端**：utopia-web 页面移植进 RuoYi-Vue3-ts 壳，最终一个前端 |
| 4 | 模块粒度 | **镜像保留 7 域模块**：jknow-contract/llm/store/search/reason/ingest/extract；utopia-common 并入 jknow-common；server 的控制器拆入 jknow-admin |
| 5 | 数据库管理 | **单库 + Flyway 统一管**：若依表转 PG 作 baseline，utopia 99 迁移接续 |
| 6 | OIDC | **本期舍弃**（将来企业 SSO 另立项） |
| 7 | 规格组织 | **总规格 + 分阶段实现计划**（本文件为总规格；P0–P3 各出一份实现计划） |

## 1. 总体架构与命名（A 节）

**目标形态**：jknow 为标准若依系单仓：一个可部署后端 `jknow-admin.jar` + 一个前端 `jknow-web` + PG 单库（Flyway 管全部表）。

**Maven 坐标**：根 pom `groupId=tech.seexun`、`artifactId=jknow`、`version=1.0.0-SNAPSHOT`。全部同系依赖版本在根 pom `<properties>` 单点收口（合并若依与 utopia 双方收口习惯；冲突取可跑通版本，如 jjwt 统一 0.13）。

**模块清单（13 个）**：

| 若依系（改名自 ruoyi-*） | 图谱业务系（改名自 utopia-*，源行数） |
|---|---|
| jknow-admin（唯一启动模块） | jknow-contract（4.3k） |
| jknow-framework | jknow-llm（1.2k） |
| jknow-system | jknow-store（26k） |
| jknow-common（utopia-common 419 行并入） | jknow-search（0.7k） |
| jknow-quartz | jknow-reason（5.6k） |
| jknow-generator | jknow-ingest（5.2k） |
| — | jknow-extract（7.3k） |

**包名**：
- `com.ruoyi.*` → `tech.seexun.jknow.*`（含 yml 中日志/mybatis typeAliases/banner 等 `com.ruoyi` 字样）
- `tech.seexun.utopia.*` → `tech.seexun.jknow.{contract,llm,store,search,reason,ingest,extract}.*`
- utopia-server 控制器落位 `tech.seexun.jknow.web.controller.knowledge.*`（若依 web.controller 惯例）

**运行时基线**：Java 21（若依 pom 17→21，Boot 4.1.2 兼容）；Spring Boot 4.1.2；PG 16+；Redis（若依必需）；ES 8.14.3 + IK 分词（检索必需）。

**git**：jknow 先 `git init`；首个代码提交为 pristine 若依基线（未改名），使改名 diff 可审。设计文档位于 `jknow/docs/superpowers/specs/`。

**显式舍弃**：OIDC 登录（前后端）、utopia 自有 JWT/Argon2 认证、utopia-server 原启动壳、utopia-web 独立登录页与自建 layout。

## 2. 后端迁移（B 节）

### 2.1 若依骨架改造
- 全局重命名：模块目录、artifactId、包名、配置文件中的 `com.ruoyi`/`ruoyi` 字样（README 致谢保留出处）。
- PG 化：保留 Druid 连接池，`driverClassName=org.postgresql.Driver`；PageHelper 依 JDBC URL 自动切 PG 方言。
- mapper 方言清理：system/quartz/generator 的 mapper XML 与注解 SQL 中 MySQL 专属写法（`find_in_set`、`date_format`、`sysdate()`、反引号等）逐处替换为 PG 等价（`= any(...)`、`to_char`、`now()` 等）；清单在 P0 实现计划穷举。
- `sql/quartz.sql` 建表脚本转 PG 方言。

### 2.2 认证与用户重宿主（核心手术）
- 删除：utopia 的 AuthController、OidcController、Argon2 密码校验、自签 JWT、auth 过滤器。
- 替换：全部接口过若依 SecurityConfig + JWT；**业务 REST 路径与请求/响应契约原样保留**（如 `/api/kbs/{kbId}/graph` 不变），前端 API 层因此可近乎照搬。
- 用户身份：`sys_user` 为唯一身份主档；业务表用户外键改指 `sys_user.user_id`（见 V0101）。
- 权限：业务接口加 `@PreAuthorize("@ss.hasPermi('knowledge:xxx')")`；权限标识与菜单种子见 §3。

### 2.3 七域模块搬运
- 包名/artifactId 机械替换为主；Boot 3.3→4.1 兼容修复集中在 Spring Security 7 / MyBatis starter 4.x 交界处；P1 首任务产出编译修复清单再批量改。
- jjwt 0.12→0.13（personal_tokens 业务签发改用统一版本）。

### 2.4 utopia-server 拆解去向
- 27 个 Controller（除 Auth/Oidc）→ jknow-admin `web.controller.knowledge`。
- ES/LLM/worker/Flyway 等 application*.yml 与 `@Configuration` 按归属并入对应模块和 jknow-admin。
- 启动类/健康检查用若依原生。

### 2.5 保留的外部依赖
- ES：`utopia-es-ik:8.14.3` 单节点（镜像构建工程 `D:\workspace_com\es-ik-build` 已有）；ensureFresh 对账机制照搬；不可达时服务照常启动。
- LLM API：配置键随迁（环境变量可覆盖）。
- 摄取 worker：`worker-enabled` 开关语义照搬。
- MCP：能力全量保留。

### 2.6 测试随迁
各模块单测/集成测（含 Testcontainers MigrationTests）随包名搬迁并修绿，作为阶段闸门。

## 3. 数据库（C 节）

**单库**：PG 16+，库名 `jknow`；dev 默认 `jdbc:postgresql://localhost:5432/jknow`，`JKNOW_JDBC_URL/JKNOW_DB_USERNAME/JKNOW_DB_PASSWORD` 可覆盖。不与 utopia 旧库共用；演示数据重灌。

**Flyway 编排（不改历史原则）**：

编号策略保证任何阶段都是有序追加、无 Flyway 乱序问题（若依 baseline 编号在最前，业务迁移 P1 搬入居中，改造类殿后）：

| 迁移 | 入库阶段 | 内容 |
|---|---|---|
| V0000__sys_baseline.sql | P0 | 若依 `sys_*` 全表 + `qrtz_*` 表（ry_20260417.sql + quartz.sql 转 PG）+ 初始 admin 账号/字典/默认角色 |
| V0001–V0099 | P1 | utopia 99 个迁移**原样搬入** jknow-admin `resources/db/migration` |
| V0100__knowledge_menu.sql | P1 | 图谱业务菜单树 + `knowledge:*` 权限标识（sys_menu 种子，驱动前端动态路由） |
| V0101__user_rebind.sql | P1 | 业务表用户外键（kb members、personal_tokens、审计等）ALTER 改指 `sys_user.user_id`；utopia 原 `users` 表退役归档 |

**角色语义分界**：`sys_role` 只管系统级（超管/普通）；kb 内成员角色（owner/editor/viewer 等）保留在业务表。菜单可见性走 sys_role，库内操作权限走业务角色。

**初始化路径**：起 PG → 建空库 jknow → 启动 jknow-admin（Flyway 自动建表+种子）→ 跑 `scripts/seed-demo.sql`（改造版：演示账号挂 sys_user）灌演示数据。seed/teardown 脚本随迁 `jknow/scripts/`。

**闸门**：MigrationTests（Testcontainers）空 PG 重放当时已入库迁移全链（终态 V0000→V0101）必须绿。

## 4. 前端迁移（D 节）

**基座**：`RuoYi-Vue3-typescript` 改名 **`jknow-web`**，唯一前端（Vue3 + element-plus + pinia + TS，与 utopia-web 同栈）。

**移植内容**：
- `src/views/utopia/**` → `src/views/knowledge/**`（图谱/检索/对话/摄取/本体/审查/账户/文档）。
- 支撑件随页面走：graphVisuals、kb-context、域 API 层与 TS 类型契约（含 API 快照漂移闸测试）。
- i18n：utopia en/zh 词条合并进若依 i18n，键加 `knowledge.` 前缀防撞。
- 样式：utopia.scss/theme.scss 并入；引入 tailwind v4（utopia-web 在用 ^4.3.3），vitest ^4.0.4 随迁。
- 测试：vitest 套件随页面搬迁修绿。

**对接改造**：
- 登录/登出/用户信息走若依 `/login` `/getInfo` `/logout`；删除 utopia 登录页与 auth 调用。
- API 客户端保留 utopia 类型化 api 层（业务路径不变）；axios 实例 baseURL 走若依 vite 代理，`Authorization` 从若依 user store 取 token。
- 路由：若依动态路由（sys_menu 驱动），业务页面注册为菜单组件（component 指向 `views/knowledge/**`），与 V0100 菜单种子对齐。
- 按钮级权限（v-hasPermi）本期不上，页面级菜单权限即够。

**布局定位**：若依 layout 为壳（侧栏保持若依风格）；kb 切换器等业务二级导航作为页面内组件保留；utopia 侧栏重设计规格仅适用于页面内部导航。

## 5. 阶段划分与验收（E 节）

总原则：utopia-java 原仓只读；每阶段末三闸门全绿才进下一阶段；每阶段单独出实现计划。

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| P0 骨架 | git init（先 pristine 基线提交）→ 全局改名 → PG 化（数据源/mapper 方言/quartz 脚本）→ Flyway 接入（V0000 sys baseline）→ Redis 接入 | jknow-admin 在 PG 上启动；若依原生功能（登录/用户/角色/菜单/字典/定时任务/代码生成）全可用；Flyway 空库重放绿 |
| P1 后端业务 | 7 域模块搬运（包替换+Boot 4 适配，首任务编译修复清单；V0001–V0099 随模块入库）→ server 拆解（控制器入 admin、删认证、配置并入）→ V0100 菜单种子 → V0101 用户重绑 | 登录后全量业务 API 冒烟（kb→本体→图谱→摄取→检索→对话→审查→MCP→导入导出）；MigrationTests 绿；随迁后端测试绿 |
| P2 前端 | jknow-web 改名 → 页面/组件/i18n/样式移植 → 登录与 API 客户端对接 → 动态路由（对齐 V0100 菜单种子） | 若依登录后图谱全功能页面走查（浏览/编辑/检索/对话/摄取）；vitest+API 快照闸绿 |
| P3 联调部署 | docker-compose（pg/redis/es-ik）→ seed-demo 改造重灌 → 端到端演示闭环 → README/部署文档 | compose 一键起全套；演示叙事闭环；文档齐 |

**三闸门**：① Flyway MigrationTests；② 后端单测/集成；③ 前端 vitest + API 快照漂移闸。

**风险与对策**：
| 风险 | 对策 |
|---|---|
| Boot 3.3→4.1 适配面未知（Security 7 / MyBatis starter 4.x） | P1 首任务产出编译修复清单再批量改 |
| 若依 mapper MySQL 方言残留难穷举 | P0 用 PG 实例逐功能冒烟兜底 |
| jjwt 0.12→0.13 API 变更 | 统一在根 pom 收口，personal_tokens 签发处适配 |
| tailwind 版本对齐（若依前端无 tailwind） | P2 首任务对齐版本并验证构建 |
| es-ik 镜像可得性 | 复用 workspace_com/es-ik-build 工程，P3 compose 固化 |
| Java 21 下若依代码编译 | P0 改 pom 后全量编译验证（预期无问题） |
