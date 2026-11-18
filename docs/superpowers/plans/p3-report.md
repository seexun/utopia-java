# P3 报告：联调部署收官（jknow 迁移 · 阶段四 · 终章）

分支 `p3-deploy`，基线 `9a32f70`（master），四任务五提交：`658997c`（计划）→ `db2249a` → `3db7233` → `8d2fcbc`/`a358194`/`1f5e704` → 本提交（文档终稿 + tag `p3-done`）。日期 2026-11-18。

## 一、四任务勾选表

| # | 任务 | 状态 | 提交 | 要点 |
|---|---|---|---|---|
| 1 | compose 全套（+ES-IK） | [x] DONE | `db2249a` | 三服务（pg/redis/es-ik 8.14.3，512m 限内存/独立卷）；`docker-compose.es.yml` 备选端口形态；smoke-p1 ES 断言转真（61 PASS/1 SKIP） |
| 2 | seed-demo 改造（挂 sys_user） | [x] DONE | `3db7233` | users insert 退役/uuid 引用子查询化（24+2 处）/演示访客 sys_user 化（9999）；三连幂等绿；UI 闭环截图 |
| 3 | 遗留专项票清偿（前端为主） | [x] DONE | `8d2fcbc` + `a358194` + `1f5e704` | 无宿主四件清理（421→418 spec）/EP locale 随语言/kb-entry 8s 引导卡/manualChunks 五桶（入口 775.39KB）/smoke-p1 明细补面（67 PASS）/PhraseAlignment 注释去重 |
| 4 | 端到端闭环验收 + 文档终稿 + tag | [x] DONE | 本提交 | 清卷重建演练四步全绿（§二）；演示叙事闭环走查 11 截图 console 零 error（§五）；README 终稿 + 本报告；tag `p3-done` |

## 二、清卷重建演练（任务 4 步骤 1——全新环境从零到闭环）

1. **清卷**：`docker compose -f docker-compose.dev.yml down -v`——`jknow_jknow-pg-data`/`jknow_jknow-es-data` 两卷删除（Redis 无卷），`up -d` 三容器重起；ES 就绪自检 `curl :9200` 200（cluster docker-cluster 8.14.3）+ `_cat/plugins` 含 `analysis-ik`。
2. **重建**：起后端（`mvn install -q -DskipTests -pl jknow-admin -am` → `JKNOW_PG_PORT=15432 SERVER_PORT=19080 JKNOW_ES_RECONCILE=1 mvn spring-boot:run`）——空库 Flyway `Successfully applied 103 migrations … now at version v0102`（V0000-V0102，2.3s）；`seed-demo.sql` 灌库零 ERROR（facts=44/kbs=1/workspaces=1/访客 9999/kb_members 含 admin）。
3. **全闸门**：

| 闸门 | 结果 | 备注 |
|---|---|---|
| `./scripts/smoke-p0.sh` | ALL PASS **20/20** | 清卷库直跑 |
| `./scripts/smoke-p1.sh` | ALL PASS **PASS=67 SKIP=1** | SKIP=chat（LLM key 外部依赖，预期口径）；seed 态明细 6 面自足段随跑随收 |
| `mvn -q test` 全仓 | **exit 0：1330 run / 0 fail / 0 err / 9 skip** | admin 970/6S + common 6 + reason 107 + ingest 62/1S + extract 109 + store 24 + search 5 + contract 47/2S。**首跑 3 错**（见 §五-1 热身态发现） |
| `pnpm test` | **56 files / 418 passed** / 0 fail | |
| `pnpm typecheck` | exit 0 | |
| `pnpm build:prod` | exit 0 | chunk 分布与 T3 拆包表一致（§六） |

4. **演示闭环走查 + 回收 + 就绪态**：见 §五；演练毕环境恢复「compose 三容器 Up + 库已 seed + 应用进程停净」（80/19080 无监听；ES 索引清空待下次起后端对账重建）。

## 三、种子改造对账（源 48 insert → 新口径）

- **总账**：源 48 条 INSERT → 新 50 条 = **-1 + 3**：源 §1/§2 `users` 两段退役（users 表已随 V0101 DROP）；新增 `sys_user` 演示访客（user_id=9999，BCrypt 同 admin123 口令，`ON CONFLICT DO NOTHING` 幂等）、`organizations` 演示组织（…00f4）、`workspaces` 演示工作区（…00f5）——后两者是源脚本「假设库里有」的两行前提，清卷空库没有，改 seed 自带、teardown 随工作区级联回收。
- **uuid 子查询化 24+2 处**：源「演示用户」字面量 → `(SELECT utopia_uuid FROM sys_user WHERE user_id = 1)` 24 处（kb_members/memberships/resolution_reviews/entity_merges/data_sources/data_source_grants/concept_mappings×5/concept_mapping_revisions×2/conversations/personal_tokens/audit_events×7）；源「第二用户」→ 访客（9999）子查询 2 处。
- **锚 DO 块**：seed 头部校验 admin(1) utopia_uuid 存在，缺失即 RAISE（先起应用完成 V0000 的前置提示）。
- **幂等实证**：三连（seed→teardown→seed）零 ERROR，第二遍 seed `INSERT 0 0`（访客行跳过）；teardown 不动 sys_user（访客行幂等保留），演示工作区一条 DELETE 级联回收 kb/memberships/grants/organizations。
- **读面口径**：facts=44（=源 17 known_as+23 断言+3 开放+1 未配对）、entities 19、chunks 6（5 现行+1 superseded）、audit_events 8。

## 四、遗留终核表（p1 §七 16 项 + p2 §九 11 项 + 账本 minor 行，逐条终态）

**p1-report §七（P1 收口时的 16 项）**

| # | 项 | 终态 | 依据/去向 |
|---|---|---|---|
| 1 | 必填参数缺失回 500≠源 400 | **清偿** | P1 终审已修（AppExceptionHandler 补 400 分支，冒烟挂 q/chunk_id 两面断言——原文括注） |
| 2 | 若依 entry point 200+体401 | **清偿** | 升级为 P2 硬依赖后落地（P2 终审 auth.ts 身份/触碰面，`9a32f70`） |
| 3 | SSE 滞后帧收口竞态容忍 | **关闭** | 容忍语义+注册表侧收口全量断言（源仓同款行为，非缺陷） |
| 4 | pipeline 族 7 测试不重建 | **关闭** | 有意决策（注册流已退役/Testcontainers 专属）；存活覆盖=纯面+HTTP 写面+worker 面；检索面 P3 已转真（`db2249a`） |
| 5 | 17 个明细 GET 未入冒烟；ES/LLM SKIP | **清偿（P3 范围）/仍开放（余量）** | seed 可造数 6 面入 smoke-p1（`1f5e704`）；ES 断言转真（`db2249a`）；LLM chat 面维持条件 SKIP（key 外部依赖）。余 ~11 面无 seed 造数路径，**开放→去向**：后续按明细资源造数同法扩段（非阻塞） |
| 6 | MysqlEngineTests 宿主时钟敏感 | **关闭** | 环境项非代码（修法 `w32tm /resync /force` 已存档；P3 每任务开工执行） |
| 7 | jsqlparser 锁 4.7 | **关闭** | 有意锁定（pagehelper 6.1.1 配对约束）；升 5.x 需连 pagehelper 评估——长期约束记录 |
| 8 | ES 客户端锁 8.13.4 | **关闭** | 有意锁定（Boot4 BOM 9.4.8 对 8.14.3 服务端实测不兼容，P1 §四 T5 存档） |
| 9 | V0101 不回填旧 users 映射 | **关闭** | 空库策略（有意）；存量导入需先建 utopia_uuid 映射——部署须知 |
| 10 | 迁移改号备案（源 V0090-V0110→V0081-V0099） | **关闭** | 备案性质，增量同步按内容顺序对齐 |
| 11 | is_admin 双道谓词（user_id=1 OR role_id=1） | **仍开放** | 单租户下两道谓词行为一致；长期统一属产品决策。**去向**：运维/产品专项 |
| 12 | 用户管理面两套并存（/system/user vs /api/v1/users） | **仍开放** | 部署内单租户语义已定（orgId 仅签名兼容）；**去向**：长期统一评估 |
| 13 | UtopiaUuidResolver/pt- 反查进程内缓存无失效 | **仍开放** | 停用/解绑生效窗口到进程重启（立即生效面只有表行撤销/过期）。**去向**：如需即时生效加 TTL/事件失效 |
| 14 | 菜单 2100-2199 visible='1' 待开页 | **清偿** | V0102 开启 + component 校正（P2） |
| 15 | Minor 账本（字段序耦合/ASYNC 无回归/token.secret 轮换/双打语境键/scenarios+golden 未随迁/wire 级标识保留） | **关闭/仍开放（1 项）** | 记录性账本：除 **scenarios(22)+golden(331) 契约工装数据未随迁**（复活口径=从源仓整体拷入，**开放→去向**：需要契约回放时拷入）外均为已知代价/有意保留 |
| 16 | data/secret.key → .gitignore + README 13 模块清单 | **清偿** | P1 当期提交（原文「本提交」） |

（p1 §七 终审补录 17-20 四项：17 公开路由 permitAll、18 pt- scope REST 写面强制——P1 终审当期清偿；19 @PreAuthorize+pt- 权限集、20 entry point 真 401——P2 落地。均不悬空。）

**p2-report §九（P2 收口时的 11 项）**

| # | 项 | 终态 | 依据/去向 |
|---|---|---|---|
| 1 | kb-switcher/kb-page 无宿主件 | **清偿** | P3 `8d2fcbc` 四件删除（含 utopia-menu/permission store，rebuildMenus 链整摘，终态 grep 零残留） |
| 2 | kb-entry 解析失败无错误面 | **清偿** | P3 `a358194` 8s 超时引导卡（i18n 双语） |
| 3 | 构建体积（index/cache 超限告警） | **清偿** | P3 `a358194` manualChunks 五桶，入口 2054.69→775.39KB（-62.3%），见 §六 |
| 4 | 样式 scope 收敛点（body[layout] 永不命中） | **关闭** | 条件触发型观察项（若依壳永不命中即无互染），无现行缺陷 |
| 5 | 类型面现代化（noImplicitAny:false 等） | **仍开放** | 源仓 strict:false 同款写法的延续；augmentation 化+收 strict+修约 300 条上游错误。**去向**：前端专项（与业务迭代解耦） |
| 6 | PhraseAlignmentTests 注释重复 | **清偿** | P3 `1f5e704`（:823-824 重复份删除） |
| 7 | tagsView 留 /knowledge/<页> 空标签 | **关闭** | T3 已接受行为（replace 语义） |
| 8 | knowledge:search:edit 有意空缺 | **仍开放** | 检索写面（saved search 等）出现时补挂。**去向**：功能立项时一并 |
| 9 | 无 LLM key/worker 关的降级面 | **关闭** | 自愈型（配 key+worker 即恢复，非缺陷）；P3 README 部署要点给配置指引（Settings→Models），走查实证 worker 开后上传→分块闭环 |
| 10 | WebGL 拾取自动化下不可点 | **关闭** | 人工走查覆盖选中链路（P3 走查再证：图谱筛选/邻域可操作） |
| 11 | 若依面与业务面 i18n 双体系 | **仍开放** | 若依面不经 t()，长期统一评估。**去向**：前端专项（与 #5 同族） |

**账本 minor 行（progress.md，T1-T3 复审各 1 条）**

| 条目 | 终态 |
|---|---|
| T1：简报「0 SKIP」笔误（LLM key 外部依赖，1 SKIP 属预期） | **关闭**（口径更正，本报告 §二 按 67/1 记账） |
| T2：seed §4 注释 16/18 vs 实 19 笔误（源仓既有继承）；截图未带地址栏；访客 memberships 时间戳顺移 1s | 注释笔误**关闭**（不影响行为）；地址栏**清偿**（T4 走查 11 张全带地址栏补课）；时间戳顺移**关闭**（同秒内顺移，展示无感） |
| T3：smoke same_name 断言弱（键存在即可空）；截图对比度/行号小误；简报 66/0 vs 67/1 口径 | **关闭**（记录性：断言形状与 T9 源口径一致；口径以实况 67/1 为准） |

**仍开放项汇总（7 项，均有去向）**：p1#11 is_admin 双道、p1#12 用户面两套、p1#13 pt- 缓存失效、p1#15 scenarios/golden 未随迁、p1#5 余量 11 面冒烟、p2#5+#11 类型/i18n 专项（两族）、p2#8 search:edit 空缺；另加 T4 新发现两项（§五-1 热身态、§五-2 ES 重建时点——均已给 README 指引/热身 SQL，长期修复去向注明）。无阻塞迁移验收项。

## 五、T4 演练两 findings（清卷环境首次全量跑出的真发现）

1. **全仓 mvn test 的共享库热身态**：清卷空库首跑 3 例错——GraphApiTests/OntologyApiTests fixture `SELECT id FROM organizations ORDER BY created_at LIMIT 1` 0 行、GraphStoreTests.emptyKbOverviewAndSearchAreZero 缺 `name='General'` 库。前提溯源：两者是源仓注册流遗产（jknow 无建行路径），累积开发库天然满足、清卷库没有。处置：插入热身态（1 组织 + 1 工作区 + 空 General 库，固定 uuid …00f0/00f1/00f2）后复跑全绿 1330/0/0/9。**非代码回归**；README 验证节已注，长期去向：测试自足化（fixture 自建前提）或 V0103 种子化（产品决策）。
2. **ES 索引重建时点**：检索路径自 M1 起不做逐查对账（ensureFresh 撤为启动 runner——`JKNOW_ES_RECONCILE=1` 或 `--reindex`）。清卷演练中「先起后端后 seed」的顺序会让索引空置、检索空结果；调序为 seed 后重启后端即触发 `全文索引是空的，从库里重建：5 条分块`→`索引重建完成`。走查按此闭环（README 快速开始第 2 步已带该环境变量）。

## 六、chunk 对比表（T3 拆包，T4 复跑 build:prod 数值一致）

| 阶段 | chunk | 大小 | gzip |
|---|---|---|---|
| 拆包前（P2 终审档案值） | index | 2059.98 KB | 683.51 KB |
| 拆包前（T3 实测，含清理改动后） | index | 2054.69 KB | 682.12 KB |
| 拆包后 | **index（入口）** | **775.39 KB** | **266.94 KB** |
| | vendor-utils | 1213.98 KB | 408.97 KB |
| | vendor-ep | 1059.15 KB | 332.33 KB |
| | vendor-md | 308.92 KB | 92.26 KB |
| | vendor-graph | 203.14 KB | 50.57 KB |
| | vendor-vue | 149.76 KB | 57.98 KB |
| （不随拆分变化） | css index | 517.23 KB | 75.30 KB |

入口 chunk -62.3%（gzip -60.9%）；vendor 五桶独立缓存。vendor-utils 单桶仍大（echarts 大头），可再拆 `vendor-echarts`——P3 不做（T3 备注）。

## 七、演示闭环截图索引（docs/superpowers/plans/p3-walkthrough/，全带地址栏）

| 文件 | 叙事步 | 核验 |
|---|---|---|
| `t4-01-login.png` | 登录页（admin/admin123） | 地址栏 localhost/login，表单齐 |
| `t4-02-home.png` | 登录后若依首页 | 菜单树含「知识图谱」 |
| `t4-03-libraries.png` | 库列表（工作空间页） | 「演示：智造产业链」行：所有者/2 文档/2 成员 |
| `t4-04-graph.png` | 图谱（实体+关系） | 18 实体·24 事实·23 现行；人物/公司节点+任职于/投资了/合作等边+图例 |
| `t4-05-graph-filter.png` | 图谱筛选 | 搜索「星辰」命中星辰智造+星辰苏州研究院 |
| `t4-06-search-es.png` | 检索（真 ES+IK） | q=星辰智造 蓝海能源 战略合作；两文档 5 现行段全召回带引文（地址栏含 ?q=） |
| `t4-07-ingest.png` | 摄取（上传） | p3-t4-upload.md 上传→「就绪 1 文本块」（worker 开启后分块完成） |
| `t4-08-doc-viewer.png` | 上传文档详情 | 正文渲染（1 段·1KB·上传于 2026/11/18） |
| `t4-09-review.png` | 审查队列总览 | 待审核 3/实体消解 2/知识冲突 1/低置信度 3 等+裁决史+库成色 44 事实 |
| `t4-10-settings.png` | 库设置 | 常规/成员/活动/危险操作+自动跑的那几件事 |
| `t4-11-models.png` | LLM 配置指引面（Settings→Models） | /admin 模型页：OpenAI 兼容五预置+对话/向量/OCR/转写四槽 |

**console 零 error**：每页装载后注入 `window.__errs`（window.onerror+unhandledrejection+console.error 桥）逐页复核，11 页全程空数组；无 4xx/5xx 资源失败面。**回收**：测试文档经文库页 UI 删除（软删，随 seed teardown 物理清除，复验 count=0）；走查毕 teardown+重灌 seed、Edge 专窗关闭、vite baseUrl 还原 8080（未入库）、前后端进程停净（80/19080 无监听）、ES 演示索引 DELETE（下次起后端对账重建）。

## 八、复跑指引

见 `README.md`（快速开始/部署要点/验证三节）：compose 三中间件 → `mvn install -q -DskipTests -pl jknow-admin -am` → 带 `JKNOW_ES_RECONCILE=1`（演示闭环另带 `JKNOW_JOBS_WORKER=true`）起后端 → seed-demo → `pnpm dev` → admin/admin123。冒烟注意 flushdb 与 SNAPSHOT 陷阱；全仓 mvn 前的共享库热身态见 §五-1。
