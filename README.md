# jknow — 企业知识图谱平台

基于 [RuoYi-Vue v3.9.2](https://github.com/yangzongzhuan/RuoYi-Vue) 骨架重宿主的知识图谱系统（业务源自 utopia，分阶段迁移中）。

- 后端：Java 21 / Spring Boot 4.1.2 / PostgreSQL 16 / Redis 7 / Flyway
- 前端：jknow-web（源自 RuoYi-Vue3-typescript，目录已按规格 D 节改名；内部品牌清理归 P2）
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

## 本地启动

```bash
docker compose -f docker-compose.dev.yml up -d   # PG 宿主 15432 + Redis 36379
mvn install -q -DskipTests                       # 先刷新本地仓库（-pl 启动会解析旧 SNAPSHOT jar）
JKNOW_PG_PORT=15432 SERVER_PORT=19080 mvn spring-boot:run -pl jknow-admin   # Flyway 自动建表（V0000）
# 默认账号 admin / admin123；接口文档 http://localhost:19080/swagger-ui.html
```
