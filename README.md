# jknow — 企业知识图谱平台

基于 [RuoYi-Vue v3.9.2](https://github.com/yangzongzhuan/RuoYi-Vue) 骨架重宿主的知识图谱系统（业务源自 utopia，分阶段迁移中）。

- 后端：Java 21 / Spring Boot 4.1.2 / PostgreSQL 16 / Redis 7 / Flyway
- 前端：RuoYi-Vue3-typescript（阶段二迁移为 jknow-web）
- 坐标：`tech.seexun:jknow`，包根 `tech.seexun.jknow`

迁移设计规格见 `docs/superpowers/specs/`。

## 本地启动

```bash
docker compose -f docker-compose.dev.yml up -d   # PG 宿主 15432 + Redis 36379
mvn install -q -DskipTests                       # 先刷新本地仓库（-pl 启动会解析旧 SNAPSHOT jar）
JKNOW_PG_PORT=15432 SERVER_PORT=19080 mvn spring-boot:run -pl jknow-admin   # Flyway 自动建表（V0000）
# 默认账号 admin / admin123；接口文档 http://localhost:19080/swagger-ui.html
```
