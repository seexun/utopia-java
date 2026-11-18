# jknow — 企业知识图谱平台

基于 [RuoYi-Vue v3.9.2](https://github.com/yangzongzhuan/RuoYi-Vue) 骨架重宿主的知识图谱系统（业务源自 utopia，分阶段迁移中）。

- 后端：Java 21 / Spring Boot 4.1.2 / PostgreSQL 16 / Redis 7 / Flyway
- 前端：jknow-web（RuoYi-Vue3-typescript 壳 + utopia 知识图谱前端，双形态一体，见下节）
- 坐标：`tech.seexun:jknow`，包根 `tech.seexun.jknow`

迁移设计规格见 `docs/superpowers/specs/`。

## 后端模块结构（13 模块）

```
jknow
├── 若依骨架（P0 就位，源自 RuoYi-Vue 3.9.2）
│   ├── jknow-admin        启动模块；P1 起承载业务落位 web/knowledge（27 Controller，/api/v1/**）
│   ├── jknow-framework    框架核心：安全链（若依 token + pt- 个人令牌 + ClientContext 桥接）/Druid 动态数据源/拦截器
│   ├── jknow-system       系统管理：用户/角色/菜单/字典/公告等
│   ├── jknow-quartz       定时任务
│   ├── jknow-generator    代码生成
│   └── jknow-common       通用核心（utopia-common 并入：AppException/Rrf/jobs/uuids 等）
└── 业务域（P1 自 utopia-java 搬运，纯依赖序七域）
    ├── jknow-llm          LLM 客户端（Chat/Embed，纯 JDK HttpClient，无 Spring 依赖）
    ├── jknow-reason       推理域：本体检查/规则/推导表达式
    ├── jknow-ingest       摄取域：docx/xlsx/pptx/pdf 解析、分块对齐（POI 6）
    ├── jknow-extract      抽取域：抽取治理/本体 agent/提示词
    ├── jknow-store        存储域：45 mapper + 实体/密封/时序类型，Flyway V0001-V0101 全链
    ├── jknow-search       检索域：ES 客户端（锁 8.13.4）、BM25/向量索引与对账
    └── jknow-contract     契约回放工装：Recorder/Replayer/Diff（rust↔java 双打）
```

依赖方向：admin 聚合若依骨架与七业务域；业务域内部 `extract → llm`、`store → common/ingest/reason`、`search → store`、`contract → store`（llm/reason/ingest 除 JDK+第三方库外零内部依赖）。

## 前端 jknow-web（双形态一体）

一个 Vue 3 + TS 应用，两种页面形态共用同一登录态（若依 token，`Authorization: Bearer`）：

- **若依管理面**（`/system` `/monitor` `/tool` 等及首页）：RuoYi-Vue3 原生页面，Layout 壳 + 动态路由（`getRouters` 后端菜单树）。
- **知识图谱业务面**（源自 utopia-web，P2 迁入）：
  - 菜单入口「知识图谱」（V0102 开启）：8 个 C 页统一指向入口组件 `views/jknow/knowledge/kb-entry`——解析工作区/库后跳 `/kb/{kbId}/{页}` 或原地渲染工作空间页（my-kbs），冷登录自动建 General 库；
  - `/kb/{kbId}/*` 10 页独立整页（graph/search/chat/ontology/review/settings/library/mappings/docs/doc-viewer，hidden 不进菜单）；旧路径 `/graph` `/library` 等由 KbRedirect 接管重定向；
  - `/account` 族（profile/我的库/令牌/管理）独立壳整页——kb 页顶栏 user-menu 的链接目标；
  - 代码落位：api `src/api/knowledge/`、页面 `src/views/jknow/knowledge/`、组件 `src/components/knowledge/`、store `src/store/knowledge/`（共用若依 pinia 实例）、i18n `src/i18n/`（vue-i18n，仅业务面使用）+ tailwind v4（scope 限 `.knowledge-page`）。
  - 接口面：业务 API 走 `/api/v1/**`（client.ts 统一 Bearer/401 会话处置）；菜单/按钮权限对齐 V0100 `knowledge:*` 16 项，后端 `@PreAuthorize` 粗粒度挂载。

```bash
cd jknow-web && pnpm install
pnpm dev            # 端口 80，代理 /dev-api 与 /api 到 vite.config.ts 的 baseUrl
pnpm test           # vitest 56 文件（app jsdom + domain node 双面）
pnpm typecheck && pnpm build:prod
```

## 本地启动

```bash
docker compose -f docker-compose.dev.yml up -d   # PG 宿主 15432 + Redis 36379
mvn install -q -DskipTests                       # 先刷新本地仓库（-pl 启动会解析旧 SNAPSHOT jar）
JKNOW_PG_PORT=15432 SERVER_PORT=19080 mvn spring-boot:run -pl jknow-admin   # Flyway 自动建表（V0000）
# 默认账号 admin / admin123；接口文档 http://localhost:19080/swagger-ui.html
```
