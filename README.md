# jknow — 企业知识图谱平台

基于 [RuoYi-Vue v3.9.2](https://github.com/yangzongzhuan/RuoYi-Vue) 骨架重宿主的知识图谱系统（业务源自 utopia，四阶段迁移已完成：骨架 → 后端 → 前端 → 联调部署，见文末索引）。

- 后端：Java 21 / Spring Boot 4.1.2 / PostgreSQL 16 / Redis 7 / Elasticsearch 8.14.3（IK 分词）/ Flyway
- 前端：jknow-web（RuoYi-Vue3-typescript 壳 + utopia 知识图谱前端，双形态一体，见下节）
- 坐标：`tech.seexun:jknow`，包根 `tech.seexun.jknow`

迁移设计规格见 `docs/superpowers/specs/`。

## 架构总览

```
                         ┌────────────────────────────────────────────┐
                         │                jknow-web (:80)             │
                         │  若依管理面 /system /monitor /tool + 首页    │
                         │  知识图谱业务面 /kb/{kbId}/* + /account/*    │
                         └──────────────┬─────────────────────────────┘
                                        │ /api/v1/** + 若依 REST（Bearer）
                         ┌──────────────▼─────────────────────────────┐
                         │            jknow-admin (:19080)             │
                         │  启动模块：27 业务 Controller + 若依装配      │
                         │  安全链：若依 token + pt- 个人令牌双通        │
                         ├────────────────────────────────────────────┤
   若依骨架（P0）          │ jknow-framework（安全/数据源）· system ·     │
                         │ quartz · generator · common（含 utopia 并入）│
   业务七域（P1）          │ llm · reason · ingest · extract ·          │
                         │ store（45 mapper + Flyway V0001+）·         │
                         │ search（ES 客户端）· contract（回放工装）     │
                         └──┬──────────────┬──────────────┬───────────┘
                            │              │              │
                     ┌──────▼─────┐ ┌──────▼─────┐ ┌──────▼─────────┐
                     │ pg (:15432)│ │redis(:36379)│ │ es-ik (:9200)  │
                     │ pgvector16 │ │ 7-alpine    │ │ 8.14.3+IK      │
                     │ Flyway 103 │ │ 会话/缓存    │ │ BM25/向量索引  │
                     └────────────┘ └─────────────┘ └────────────────┘
```

### 后端模块结构（13 模块）

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

### 前端 jknow-web（双形态一体）

一个 Vue 3 + TS 应用，两种页面形态共用同一登录态（若依 token，`Authorization: Bearer`）：

- **若依管理面**（`/system` `/monitor` `/tool` 等及首页）：RuoYi-Vue3 原生页面，Layout 壳 + 动态路由（`getRouters` 后端菜单树）。
- **知识图谱业务面**（源自 utopia-web，P2 迁入）：
  - 菜单入口「知识图谱」（V0102 开启）：8 个 C 页统一指向入口组件 `views/jknow/knowledge/kb-entry`——解析工作区/库后跳 `/kb/{kbId}/{页}` 或原地渲染工作空间页（my-kbs），冷登录自动建 General 库；
  - `/kb/{kbId}/*` 10 页独立整页（graph/search/chat/ontology/review/settings/library/mappings/docs/doc-viewer，hidden 不进菜单）；旧路径 `/graph` `/library` 等由 KbRedirect 接管重定向；
  - `/account` 族（profile/我的库/令牌/管理）独立壳整页——kb 页顶栏 user-menu 的链接目标；`/admin` 页承载部署级配置（模型/用户/数据源/单点登录）；
  - 代码落位：api `src/api/knowledge/`、页面 `src/views/jknow/knowledge/`、组件 `src/components/knowledge/`、store `src/store/knowledge/`（共用若依 pinia 实例）、i18n `src/i18n/`（vue-i18n，仅业务面使用）+ tailwind v4（scope 限 `.knowledge-page`）。
  - 接口面：业务 API 走 `/api/v1/**`（client.ts 统一 Bearer/401 会话处置）；菜单/按钮权限对齐 V0100 `knowledge:*` 16 项，后端 `@PreAuthorize` 粗粒度挂载。

## 快速开始

前置：JDK 21、Maven、Node 20+/pnpm、Docker；ES-IK 镜像 `utopia-es-ik:8.14.3`（见部署要点）。

```bash
# 1) 三中间件一键起（pg 15432 / redis 36379 / es 9200，数据落卷）
docker compose -f docker-compose.dev.yml up -d

# 2) 后端（Flyway 103 迁移自动建表；JKNOW_ES_RECONCILE=1 启动对账 ES 索引）
mvn install -q -DskipTests -pl jknow-admin -am        # 先刷新本地仓库（SNAPSHOT 陷阱）
JKNOW_PG_PORT=15432 SERVER_PORT=19080 JKNOW_ES_RECONCILE=1 \
  mvn spring-boot:run -pl jknow-admin                  # 就绪后 http://localhost:19080/api/v1/health

# 3) 演示数据（可选；幂等，固定 uuid）
docker compose -f docker-compose.dev.yml exec -T pg psql -U postgres -d jknow \
  -v ON_ERROR_STOP=1 -q -f - < scripts/seed-demo.sql  # 回收用 scripts/teardown-demo.sql

# 4) 前端
cd jknow-web && pnpm install
pnpm dev                                              # http://localhost（vite 代理到 baseUrl 后端）
```

登录演示账号 **admin / admin123**（seed 后冷登录直落演示库「演示：智造产业链」：图谱 18 实体/24 事实、检索 5 分块真 ES+IK 召回、审查队列 3 待审、摄取上传即走分块管线）。接口文档 http://localhost:19080/swagger-ui.html。

## 部署要点

- **ES-IK 镜像构建**：`utopia-es-ik:8.14.3` 由 es-ik-build 工程产出（elasticsearch 8.14.3 + analysis-ik 同版插件）。缺镜像时 `cd es-ik-build && docker build -t utopia-es-ik:8.14.3 .`。宿主 9200 被占时改用 `docker-compose.es.yml` 备选形态（端口 19200 + `JKNOW_ES_URL=http://localhost:19200` 覆盖，文件头注释有说明）。容器内存限 `ES_JAVA_OPTS=-Xms512m -Xmx512m`，数据落独立卷 `jknow-es-data`。
- **端口约定**：PG 15432 / Redis 36379 / ES 9200 / 后端 19080 / 前端 80；均可用环境变量覆盖（`JKNOW_PG_PORT`/`SERVER_PORT`/`JKNOW_ES_URL` 等）。
- **LLM 配置指引（不随部署伪造）**：对话/语义检索需先配模型——登录后右上角进「管理 → 模型」（`/admin`，即 Settings→Models）：OpenAI 兼容协议，DeepSeek/SiliconFlow/Qwen/Ollama/OpenAI 预置模板，填接口地址+模型+API 密钥后「测试连接→保存」；向量模型可选（启用语义检索），另有 OCR/录音转写位。未配模型时检索走 BM25 关键词（真 ES+IK），对话面给出引导提示（不伪造流）。
- **jobs worker 开关**：`JKNOW_JOBS_WORKER=true` 才会有摄取解析/抽取 worker（默认 false——双打期写路径所有权信号；上传文档在关态停在「排队中」）。演示闭环起后端时建议一并带上。
- **smoke 脚本族**（`scripts/`）：
  - `smoke-p0.sh` 若依面 20 断言（登录/菜单/CRUD/分页 date-range）——20/20；
  - `smoke-p1.sh` 业务面 67 PASS + 1 SKIP（LLM chat 面需 `JKNOW_LLM_API_KEY`+模型配置，条件断言）；含 seed 态明细 6 面自足段（seed→断言→teardown）；
  - `seed-demo.sql` / `teardown-demo.sql` 演示数据灌/收（幂等；见文件头注释的用户口径）。
  - 注意：冒烟会 flushdb Redis（若依会话全失效）；spring-boot:run 前必须先 `mvn install`（SNAPSHOT 本地仓陷阱）。

## 验证

```bash
./scripts/smoke-p0.sh                                  # ALL PASS 20/20
./scripts/smoke-p1.sh                                  # ALL PASS (PASS=67 SKIP=1)
mvn -q test                                            # 全仓 1330 run / 0 fail / 9 skip
cd jknow-web && pnpm test && pnpm typecheck && pnpm build:prod   # 418 passed / 0 / 0
```

清卷重建（全新环境从零到闭环）：`docker compose -f docker-compose.dev.yml down -v && docker compose -f docker-compose.dev.yml up -d` → 起后端（Flyway 103 自动建）→ seed → 上述全部闸门照跑（P3 已演练，见 p3-report）。

注：**全仓 `mvn -q test` 的共享库热身态**——两处只读测试前提依赖共享库里「至少一个组织」与「空 General 库」各一行（源仓注册流遗产，jknow 无建行路径；累积开发库天然满足）。清卷后的空库先执行一次热身（p3-report §五有现成 SQL），否则 3 例因前提缺失报错（非代码回归）。

## 迁移四阶段索引

| 阶段 | 主题 | 报告 |
|---|---|---|
| P0 | 若依骨架落位（13 模块 reactor + 双 token 认证地基） | [docs/superpowers/plans/p0-report.md](docs/superpowers/plans/p0-report.md) |
| P1 | 后端业务全量迁入（七域 + web/knowledge + Flyway V0001-V0101 + smoke-p1） | [docs/superpowers/plans/p1-report.md](docs/superpowers/plans/p1-report.md) |
| P2 | 前端迁移收尾（知识面 10 页 + 权限/菜单/entry point 三裁定 + 418 spec） | [docs/superpowers/plans/p2-report.md](docs/superpowers/plans/p2-report.md) |
| P3 | 联调部署收官（compose 全套 + seed 挂 sys_user + 遗留清偿 + 清卷重建闭环验收） | [docs/superpowers/plans/p3-report.md](docs/superpowers/plans/p3-report.md) |
