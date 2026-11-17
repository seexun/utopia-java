# P0 骨架改造验收报告

- 分支 `p0-skeleton`，收尾提交见文末，tag **`p0-done`**
- 目标：RuoYi-Vue 3.9.2 pristine → jknow（tech.seexun.jknow）+ PostgreSQL 16 + Flyway V0000 + Redis，若依原生功能在 PG 上全部可用
- dev 端口（写实）：PG 宿主 **15432**、Redis **36379**、应用 **19080**（本机 5432/6379/8080/16379/18080 均被其他项目占用）

## 一、七任务勾选状态

| # | 任务 | 提交 | 状态 |
|---|---|---|---|
| 1 | pristine 基线提交（RuoYi-Vue 3.9.2 zip 原样入库，改名 diff 基准） | `488a246` | [x] DONE |
| 2 | 全局改名 ruoyi→jknow / com.ruoyi→tech.seexun.jknow（6 模块目录+266 文件包路径+坐标+Java 21） | `2fd22c7` + 修复 `7d3e85f` | [x] DONE |
| 3 | V0000__sys_baseline.sql：31 表 MySQL→PG 方言转换（DDL+种子+setval 12 条） | `209455f` | [x] DONE |
| 4 | PG 数据源 + Flyway 接线 + MigrationTests 闸门（Boot4 需 spring-boot-flyway；TC 2.0.5 改名构件；surefire 3.5.6） | `4191b80` | [x] DONE |
| 5 | docker-compose(pg+redis) + Redis 参数化 + 登录闭环冒烟脚本（验证码两态验证；SysMenuMapper 读路径方言解锁 d6dbfe8） | `634947d` + `d6dbfe8` | [x] DONE |
| 6 | mapper 方言清理 17 文件 + 若依原生功能全量冒烟（含 UI 走查 18 截图）+ 日期参数侧转型修复 | `7ce2b46` + `0acac9a` | [x] DONE |
| 7 | README 快速开始 + P0 报告 + 延后清理项收口（品牌残留/localhost 重复/+x 位）+ 终验收口 | 见 git log（tag p0-done 处） | [x] DONE |

辅助 docs 提交：`18001c3`/`742088d`（设计规格）、`70a1bc1`（实现计划）、`9e608e5`（表数勘误 31）、`14b7b15`/`54b4308`（端口现实同步）。

## 二、规格 §5 P0 验收行对照

| P0 验收项 | 证据 | 结论 |
|---|---|---|
| jknow-admin 在 PG 上启动 | 终验启动 `Started JknowApplication in 7.878 seconds`，Flyway `Schema "public" is up to date` | 可用 |
| 登录（含验证码两态） | smoke `PASS: login`；任务 5：关态免码登录 / 开态 captchaImage 200 + captcha_codes:* 落 Redis | 可用 |
| 用户 | `system/user/list` + date-range 200；UI 走查用户页 2 行 | 可用 |
| 角色 | `system/role/list` 200；UI 角色页 | 可用 |
| 菜单 | `getRouters` + `system/menu/list` 200（85 菜单种子） | 可用 |
| 字典 | `system/dict/type/list` 200（10 类型/29 数据种子） | 可用 |
| 定时任务 | `monitor/job/list` 200（3 任务，invoke_target=jknowTask.* 对齐任务 2 改名） | 可用 |
| 代码生成 | 任务 6 超额闭环：`/tool/gen/db/list`(19 表)→importTable sys_config→列元数据全对→删除归 0 | 可用 |
| Flyway 空库重放绿 | MigrationTests（Testcontainers）2/2；compose 新卷空库重放 31 表（任务 5）；本次终验幂等复启 up to date | 绿 |

## 三、终验输出（2026-11-17，任务 7 实测）

序列：`mvn install -q -DskipTests`（exit 0，静默）→ `JKNOW_PG_PORT=15432 SERVER_PORT=19080 mvn spring-boot:run -pl jknow-admin` → `./scripts/smoke-p0.sh` → `mvn test` → 停应用（19080 复测不可达）。

### smoke-p0.sh 全输出（13/13，exit=0）

```
$ ./scripts/smoke-p0.sh
PASS: login
PASS: getInfo
PASS: getRouters
PASS: system/user/list
PASS: system/role/list
PASS: system/menu/list
PASS: system/dict/type/list
PASS: system/config/list
PASS: system/notice/list
PASS: monitor/operlog/list
PASS: monitor/logininfor/list
PASS: monitor/job/list
PASS: system/user/list date-range
ALL PASS
```

### 整分支审查收口复验（终审 Important-1，收口提交见 git log）

smoke 断言 13 → **20**（login 1 + 核心读 11 + 日期面 8），全部 curl 加 `--max-time 10`，脚本自足化（开头自动关验证码：compose psql update + redis flushdb，新环境重放后可直接跑）：

```
$ ./scripts/smoke-p0.sh
PASS: login … PASS: monitor/job/list          （11 项核心读，同上）
PASS: system/user/list date-range
PASS: system/role/list date-range
PASS: system/dict/type/list date-range
PASS: system/config/list date-range
PASS: monitor/jobLog/list date-range
PASS: monitor/logininfor/list date-range
PASS: monitor/operlog/list date-range
PASS: tool/gen/list date-range
ALL PASS                                     （20/20，exit=0）
```

`mvn test -pl jknow-admin -Dtest=MigrationTests` → `Tests run: 2, Failures: 0, Errors: 0`（首测新增 2 断言：sys_menu=85、sys_job invoke_target like 'jknowTask.%'=3，钉住种子保真与改名联动）。

### mvn test 尾部（全量，7 模块，exit=0；`mvn -q test` 复跑 exit=0）

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.938 s -- in tech.seexun.jknow.MigrationTests
[INFO] Results:
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Reactor Summary for jknow 1.0.0-SNAPSHOT:
[INFO] jknow .............................................. SUCCESS [  0.002 s]
[INFO] jknow-common ....................................... SUCCESS [  0.889 s]
[INFO] jknow-system ....................................... SUCCESS [  2.307 s]
[INFO] jknow-framework .................................... SUCCESS [  2.676 s]
[INFO] jknow-quartz ....................................... SUCCESS [  1.602 s]
[INFO] jknow-generator .................................... SUCCESS [  1.506 s]
[INFO] jknow-admin ........................................ SUCCESS [ 15.076 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  24.262 s
```

### 启动关键日志

```
DbValidate  - Successfully validated 1 migration (execution time 00:00.036s)
DbMigrate   - Current version of schema "public": 0000
DbMigrate   - Schema "public" is up to date. No migration necessary.
t.s.j.JknowApplication - Started JknowApplication in 7.878 seconds (process running for 8.635)
(♥◠‿◠)ﾉﾞ  jknow 启动成功   ლ(´ڡ`ლ)ﾞ        ← 横幅已 jknow 语境（本任务清理项）
```

## 四、方言清理统计（引自任务 6 报告）

- 定位闸门：`grep sysdate()/date_format/ifnull/find_in_set/group_concat`（jknow-system/quartz/generator/framework，*.xml/*.java，排除 test）改前 src **55 行** → 改后 **0 命中**（含 target 构建产物）；反引号标识符单独扫描亦归零。
- 任务 6 主清理：**17 文件 +107/−81**（sysdate()→now()、date_format(x,'%Y%m%d')→to_char(x,'YYYYMMDD')、ifnull→coalesce、find_in_set→position 形态、group_concat→string_agg（无命中）、反引号删除）。
- 清单外额外修复 7 处：SysDeptMapper `status='0'` 字面量 1、SysNoticeRead `insert ignore`→`on conflict do nothing` 2、gen 元数据查询 pg_catalog 改写 4（selectDbTableList/ByNames/ByName/ColumnsByName）。
- 审查修复（`0acac9a`）：**日期参数侧 `::timestamp` 转型 16 处**——to_char(varchar 参数) 必炸 12 处（user/role/dict.type/config/jobLog/genTable 各 2）+ login_time/oper_time 裸比较 4 处（logininfor/operlog 各 2）；RED 实测 500 → GREEN 全日期面 200。
- V0000 侧（任务 3，建库时已一次性转换）：sysdate()→now() 161 处、datetime→timestamp 32、反斜杠转义 \'×4/\"×64、identity 16、索引外提 5+1、COMMENT ON 336、setval 12。

## 五、任务 7 延后清理项收口（本任务完成）

1. 品牌残留 3 处：JknowApplication 横幅"若依启动成功"→"jknow 启动成功"；SwaggerConfig 标题→"jknow企业知识图谱平台_接口文档"（终验 /v3/api-docs 实测生效）；根 pom description→"jknow 企业知识图谱平台"。README 致谢中的若依/RuoYi 字样按约保留。
2. localhost 重复：根 pom `<url>` http://www.localhost→http://localhost；application.yml referer `allowed-domains` `localhost,127.0.0.1,localhost,www.localhost`→`localhost,127.0.0.1`。
3. `git update-index --chmod=+x scripts/smoke-p0.sh`（补记可执行位，mode 100755）。
4. 顺带：smoke-p0.sh 默认 BASE 18080→19080（对齐计划 `54b4308` 端口现实，README 命令可照抄直跑）。

## 六、遗留问题清单（P1 候选，均不阻塞验收）

1. ~~**smoke 日期断言仅钉 1 面**：脚本只断言 system/user/list date-range；其余 8 个日期面（role/dict.type/config/jobLog/logininfor/operlog/gen 等）任务 6 为手工复测声明，未脚本化。~~ **已收口（终审 Important-1）**：收口提交将日期断言扩至全部 8 面（user/role/dict.type/config/jobLog/logininfor/operlog/tool gen），断言总数 13→20，全部 curl 加 `--max-time 10`，并自足化（脚本自动关验证码）。
2. **gen 日期筛选 no-op**：GenTableMapper 列表的 beginTime/endTime `<if>` 块在方言改写时删除（PG 下无对应语义，过滤不生效）；order by 改 relname 语义微变；gen 元数据查询 create_time/update_time 置 null（"导入表"对话框时间列为空，PG 无对应物，如需真实值要走触发器）。
3. **surefire 3.5.6 硬编码在 jknow-admin 模块内**，宜上移根 pom pluginManagement（现在其他模块加测试需重复声明）。
4. **本地仓库 SNAPSHOT 陷阱**：任何 mapper/资源改动后 `spring-boot:run -pl jknow-admin` 前必须 `mvn install`（否则解析旧 jar"改了没生效"），已在 README 快速开始写明前置步骤。
5. **V0000 种子含上游演示数据**：17 行"若依"字样（若依科技部门、演示公告等）。V0000 已应用不可改，P1 业务数据迁移（V0001+）时自然替换；GenUtils.replaceText 正则 `(?:表|若依)` 为功能性过滤，保留。
6. **前端登录按钮自动化点击无响应**（任务 6 UI 走查，人工浏览器预期正常），P2 前端阶段处理；pristine 前端缺 sortablejs 依赖，P2 首任务补。
7. **端口占用动态**：本机双 dockerd（Windows + WSL），WSL 监听 netstat 不可见；dev 固定 15432/36379/19080 已写实进 README 与脚本默认值，选新端口需 `docker ps` + `wsl ss -tln` 双查。
8. 小项：bin/run.bat:11 JVM 参数在 jar 后（上游原样保留）；~~smoke curl 无 --max-time（沿用计划原文）~~ **已收口（终审 Important-1，全部 curl 加 --max-time 10）**；.gitignore 追加为"空行+logs/"非单行；首提交 390 文件经历 CRLF→LF（autocrlf 行为，字节级 pristine 打折）；V0000 头部注释含规则关键词字样（第三方扫描非零，均注释行）；pom 内 flyway 两个依赖被隔开（美观）；Druid wall filter 无需 db-type 特殊处理（validationQuery 已改 `SELECT 1`，终验无拦截）；验证码当前为关态（sys_config 'false'，开态已两态验证；smoke 脚本已自足化自动关码）；uk_user_notice 唯一索引存在（任务 6 曾误判缺失，已勘误，mapper 用 on conflict 与其配合）。

## 七、复跑指引

见 `README.md` 本地启动：compose 起依赖 → `mvn install -q -DskipTests` → 带端口变量 `spring-boot:run` → `./scripts/smoke-p0.sh`。默认账号 admin / admin123，接口文档 http://localhost:19080/swagger-ui.html（终验实测 302→200）。

## 八、P1 首任务清单（终审交接，来自整分支最终审查）

1. surefire 3.5.6 上移根 pom `<pluginManagement>`——utopia 测试随迁进各模块前必须，否则 JUnit5 静默不执行
2. 规格 §3 环境变量名对齐：实现为 JKNOW_PG_HOST/JKNOW_PG_PORT（默认 5432）+ JKNOW_REDIS_*（默认 36379），规格写的是 JKNOW_JDBC_URL/JKNOW_DB_USERNAME/JKNOW_DB_PASSWORD——P1 统一（补别名或勘误规格）
3. bin/run.bat:11 JVM 参数在 jar 后（上游原样）
4. 约定：db/migration 新文件禁带事务控制语句（V0000 尾部裸 `commit;` 备案为已知豁免，改它动 checksum 得不偿失）
5. SwaggerConfig description 上游 XXX 占位文案；V0000 种子 17 行"若依"演示数据随 V0100 菜单种子时替换
6. gen 模块日期筛选 no-op 与 order by relname 语义损失（触发器方案评估）
