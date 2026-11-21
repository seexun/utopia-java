# 收尾清账批 总报告（final-ledger-sweep）

**分支**：`feat/final-ledger-sweep`（自 master 切出）｜ **日期**：2026-11-21
**任务链**：T1 `d261448` → T2 零改动关闭 → T3 `0d08249` → T4 全量回归与证据汇总（本报告，commit 见 §6）
**结论**：三任务全部通过任务审查、全量验证链绿、双 smoke 绿、证据核对通过——**本批清账完成，可进分支终审**。

---

## 0. 任务 4：全量回归验证链（分支终审前置，全绿）

| 验证项 | 命令 | 结果 |
|---|---|---|
| 单元测试 | `cd jknow-web && pnpm test` | **Test Files 58 passed (58)；Tests 425 passed (425)**；0 failed（421 基线 + T3 新增 4，无退化） |
| 类型检查 | `pnpm typecheck`（vue-tsc --noEmit --skipLibCheck） | 通过（零报错，退出码 0） |
| 生产构建 | `pnpm build:prod` | **✓ built in 30.20s**（vite v6.4.1，3729 modules transformed；gzip 产物正常，如 index-*.js.gz 170.50kb/65.79kb） |
| 后端冒烟 P0 | `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` | **ALL PASS**（20 项：login/getInfo/getRouters + system/monitor/tool 全列表 + date-range） |
| 后端冒烟 P1 | `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh` | **ALL PASS（PASS=67 SKIP=1）**（唯一 SKIP：POST chat——JKNOW_LLM_API_KEY 未配，环境预期非缺陷） |

环境：后端 27401 / 前端 24110 双端口在跑（HTTP 200），smoke 均在本机 dev 环境实测通过。

### 截图核对表（5 张，逐张打开核对内容与文件名对应）

| # | 文件 | 大小/时间 | 核对内容 | 结论 |
|---|---|---|---|---|
| 1 | `t1-01-before-removal.png` | 49,686 B 16:14 | 亮色主题首页走查：侧栏（Logo"若依管理系统"+菜单）+顶栏+图谱主区完整，一级菜单 7-8 项（首页/工作台/知识管理/配置/系统管理/系统监控/系统工具 + General 切换器），零渲染异常，图谱空态属数据未配非缺陷 | ✅ 与"删除前基线"对应 |
| 2 | `t1-02-after-removal.png` | 49,686 B 16:15 | 与 t1-01 **字节级一致**（同尺寸同 hash，上传 CDN 去重为同一对象）——死代码删除零渲染回归的最强证明；视觉复核布局/菜单/主题一致 | ✅ 与"删除后复走查"对应 |
| 3 | `t1-02a-pagination.png` | 118,576 B 16:15 | 确为 系统管理→用户 列表页（搜索栏+组织树+用户表格全渲染正常）；**分页条被视口下缘裁切未入画**——T1 报告引用的核心证据是 computed 探针（`.pagination-container` background=`rgba(0,0,0,0)` + ruoyi.scss:116 基座压制诊断），截图仅走查留档，与探针结论不矛盾但证据力弱于探针 | ⚠️ 页面对应、分页条未入画（如实记录） |
| 4 | `t1-03-footer.png` | 3,151 B 16:15 | 登录页 footer 特写：文案 "Copyright © 2018-2026 RuoYi. All Rights Reserved."，中灰色（ink-2 观感）清晰可读 | ✅ 与"footer 对比度特写"对应 |
| 5 | `t2-01-dark-ep.png` | 117,851 B 16:27 | 暗色主题 /system/user 全局：深底、主操作按钮（新增等）**深靛蓝族观感（#2852ad 一类）非亮蓝 #409eff**，表格/复选框/Tab 配色协调，零渲染异常、无刺眼亮蓝 | ✅ 与"暗态 EP 全局"+T2 未回蓝结论视觉一致 |

---

## 1. 任务 1：UI 零头清扫 + footer 对比度 + /terms 关闭（§1+§3+§5）

**Commit**：`d2614489aa694f364bf1b617147acd27be6c5554`（5 insertions / 193 deletions，5 文件）｜ **任务审查**：clean

### 1.1 六点位前后值（验收口径：逐点位前值→后值 + 保留项）

| # | 点位 | 前值 | 后值 | 结果 |
|---|------|------|------|------|
| 1 | `jknow-web/src/components/Pagination/index.vue:100` | `background: #fff;` | `background: var(--color-card);`（亮 #ffffff / 暗 #212121 随档翻） | ✅ |
| 2 | `HeaderSearch/index.vue:254`（:deep(.highlight)） | `color: red;` | `color: var(--color-danger);`（#ef4444） | ✅ |
| 3 | `HeaderSearch/index.vue:277`（.result-count strong） | `color: red;` | `color: var(--color-danger);` | ✅ |
| 4 | `login.vue:230`（.el-login-footer） | `var(--color-ink-3)`（#9ca3af） | `var(--color-ink-2)`（#6b7280，白底对比度 ≈4.8:1 达标） | ✅ |
| 5 | `register.vue:216`（.el-register-footer） | `var(--color-ink-3)`（#9ca3af） | `var(--color-ink-2)`（#6b7280） | ✅ |
| 6 | /terms 核实关闭 | 零代码，grep 证据见 §1.3 | —（后端无端点，SecurityConfig 无需加行） | ✅ |

按规格保留未动：`HeaderSearch/index.vue:209` `"color": "#fff"`（activeStyle 主色底反白）。

### 1.2 body[layout] 死代码删除（含第三块裁定）

- 简报框定"两块"，实读 `utopia.scss` 存在**三个** `body[layout]` 顶层块——第三块（原 :261-:303，"选中态竖条式覆写"）同样以 `body[layout]` 为前缀的死代码，且验收"grep body[layout 零输出"覆盖它。**裁定：三块一并删除**（任务审查认可该超字面处理）。
- 删除行范围（删除前行号，1-based）：前块 `:97-:112`、后块 `:133-:259`、第三块 `:261-:303`（+尾空行 304）；合计纯删 **188 行零新增**（文件 419→231 行）；中间活代码 `.logo-mark`/`.logo-name` 完整保留（删除后 :97-:110），脚本内置 15 个行号守卫防误删。
- 删除后验收：`LC_ALL=C grep -rn -a "body\[layout" jknow-web/src/` 零输出（exit 1）。
- 死代码双保险：静态（全仓无 `setAttribute("layout")`/`dataset.layout` 挂点）+ 运行时（走查探针 `document.body.getAttribute('layout')` = **null**，删除前后一致）。
- 走查前后对照：侧栏 210px / 菜单项 44px / 菜单 28 项·组头 7 / console errors 0——**删除前后完全一致**；t1-01 与 t1-02 截图字节级一致（49,686 B）。
- 分页条诊断结论：`ruoyi.scss:112-116` 的 `.pagination-container { background-color: transparent !important; }` 在改动前后均压制组件 scoped 背景——本改动**渲染完全等价、非回归**（详见 Minor 搁置 §4）。

### 1.3 /terms 关闭记录（转录自 task-1-report §4）

```
$ LC_ALL=C grep -rn -a "terms" jknow-admin/src/main/java jknow-system/src/main/java --include="*.java" | grep -vi "TermsUtil"
→ 仅 RdfEmitter.java 两行注释/异常文案（criterion_terms 为 RDF 词项概念，与 HTTP /terms 端点无关）；无任何 Controller 端点；"TermsUtil" 全后端零引用（该类不存在）
$ LC_ALL=C grep -rn "legal\|terms" jknow-web/src/router/
→ /terms、/privacy 同挂 legal 组件按 route.path 切词条（polish-b I1 收口）
$ grep -n -i "terms\|legal" jknow-framework/.../SecurityConfig.java
→ 零输出
```

**关闭结论**：后端无 /terms 端点，法务内容已前端静态化，SecurityConfig 无需加行。失效账本项关闭。

---

## 2. 任务 2：EP 壳暗态主色完整度——实测驱动（§2）

**结论**：**未回蓝——已审无洞，该项关闭**（无代码改动，无 commit；tokens.scss 零触碰，git status 验证干净）。**任务审查**：clean。

### 2.1 五档亮态 vs 暗态对照（验收口径：computed 直读 + 内联双读）

| 变量 | 亮态 computed | 暗态 computed（=内联） | EP 2.14.8 dark 默认（回蓝参照） | 暗态归属 |
|---|---|---|---|---|
| --el-color-primary | #2563eb | **#2852ad** | #409eff | #2563EB 软化族 |
| --el-color-primary-light-3 | #6691f1 | **#6885c5** | #3375b9 | #2563EB 软化族 |
| --el-color-primary-light-5 | #92b1f5 | **#93a8d6** | #2a598a | #2563EB 软化族 |
| --el-color-primary-light-7 | #bdd0f9 | **#becbe6** | #213d5b | #2563EB 软化族 |
| --el-color-primary-light-8 | #d3dffb | **#d4dcee** | #1d3043 | #2563EB 软化族 |
| --el-color-primary-dark-2 | #1d4fbc | **#20418a** | #66b1ff | #2563EB 软化族 |

**判定依据**：① 暗态五档 computed 与内联逐字节相等，全部属 `softenPrimaryForDark(#2563eb)` 派生族（手算 rgb 逐项吻合，±1 为 floor 舍入）；② EP dark 暗档默认族（#3375b9 等）在暗态 computed **零出现**——被 `utils/theme.ts handleThemeStyle` 运行时内联样式（特异性高于一切选择器）全量压制；③ 组件抽查交叉印证（主按钮 hover=light-3、checkbox 激活=primary，全 #2563EB 族，无 #409eff 族踪迹）；④ 复测两条暗态进入路径（点击切换 + 刷新恢复 reload）五档完全一致，均不回蓝。简报预设的"EP dark 压回默认蓝"风险模型实测不成立。

本报告 §0 截图核对表第 5 行（t2-01-dark-ep.png）视觉复核与该结论一致。

---

## 3. 任务 3：SSE 竞态测试固化——TDD（§4）

**Commit**：`0d08249`（父 d261448）｜ 新增 `jknow-web/tests/eventStream.spec.ts`（84 行）；`src/domain/eventStream.ts` **零改动**。**任务审查**：clean。

### 3.1 四用例结果（验收口径：首跑全绿即无洞）

| # | 窗口 | 用例 | 结果 |
|---|------|------|------|
| ① | 卸载后迟到帧 | dispose 后 reader 吐帧不触达 handlers | ✅（10ms 内） |
| ② | 重连窗口 close | retry 排程期间 dispose，不再重建连接（fetch 计数锁定 1） | ✅ |
| ③ | release 与重连交错 | hidden 满 HIDDEN_RELEASE_MS 让出 → visible 重连触发 onRecover 恰一次（fetch 恰 2 次） | ✅ |
| ④ | open 重入闸 | controller 占位期间 visible 连发两次抖动不开第二条连接 | ✅ |

**无洞结论**：四用例对 HEAD 的 eventStream.ts 首跑全绿，未触发修复路径，无生产码 diff——测试即交付。

### 3.2 变异验证（证明用例咬得住契约，非空转绿灯）

| 变异 | 内容 | 结果 |
|------|------|------|
| A | connect 重入闸拆掉 controller 位 | 用例③④双败 ✅ 咬住 |
| B | close() 不清 retry 计时器 | 仍绿——等价变异（disposed 顶闸拦截；release 路径由既有 sse-reconnect.spec.ts 用例承接） |
| C | release 分支不打 `missed = true` | 用例③败 ✅ 咬住 |
| D | dispatch 拆掉 `!disposed` 闸 | 仍绿——等价变异（abort-break 双闸设计的另一道闸承接） |

四窗口每个至少被一个变异证实咬合；两个未咬住变异均确认为行为等价体。全部变异跑完即 `git checkout --` 还原，最终源码零改动。

---

## 4. Minor 搁置清单（转录自 `.superpowers/sdd/2026-11-21-final-ledger-sweep/progress.md`，全部 minor 行）

| 来源 | 内容（原文转录） |
|---|---|
| T1 minor | 分页条真吃卡色需先拆 ruoyi.scss:116 transparent !important 基座压制（渲染等价改动已就位）；报告行数统计口径差 1；captchaEnabled 外部变化备查 |
| T2 minor | 暗档混白软化语义 vs EP 混黑语义的收敛+运行时换肤/tokens 双轨 ±1 舍入差——可另立任务；探针 C 原始输出未留档；t2-01 截图双份字节差 |
| T3 minor | 用例③④ dispose 未调用的测试卫生；restoreAllMocks 必要性表述偏强 |

（三项均已在各自任务审查中裁定为搁置不阻塞；留待后续批次或另立任务。）

## 5. Deviation 汇总（本批全部偏差记录）

| # | 任务 | 偏差 | 处置与依据 |
|---|---|---|---|
| 1 | T1 | **第三块死代码超出简报"两块"字面**：实读存在第三处 `body[layout]` 顶层块（原 :261-:303 选中态竖条覆写），简报计数与"grep 零输出"验收互斥 | 按验收标准与任务意图三块一并删；走查双证（属性 null + 前后截图字节级一致）背书；任务审查裁定认可 |
| 2 | T1 | **简报行号整体漂移 1-2 行**（如前块实际 97-111 而非 98-110） | 按"通读块体实际闭合为准"授权处理；删除脚本内置 15 个行号守卫 |
| 3 | T1 | dev 环境参数外部变化：走查中途 `sys.account.captchaEnabled` true→false（非本任务操作所致） | 仅记录备查，无影响 |
| 4 | T3 | **afterEach 追加 `vi.restoreAllMocks()`**（骨架未含）：`vi.spyOn(document, "visibilityState", "get")` 不复原会把假可见性泄漏到同文件后续用例 | 加固非语义调整，不影响任何断言；骨架其余逐字采用 |
| 5 | T4 | 截图核对：t1-02a-pagination.png 分页条被视口下缘裁切未入画 | 页面本体渲染正常；T1 结论依据是 computed 探针（分页条背景长期被基座 transparent !important 压制、改动渲染等价），截图仅走查留档，不构成矛盾——如实记录 |

## 6. 交付物与 commit

- 本批代码 commit：`d261448`（T1）、`0d08249`（T3）；T2/T4 零代码改动。
- 本报告与 5 张截图归档于 `docs/superpowers/plans/final-ledger-sweep/`，commit：见本文件入库记录（`docs(plan): final-ledger-sweep 证据汇总报告`）。
- 探针脚本与原始 JSON 留档 `.superpowers/sdd/2026-11-21-final-ledger-sweep/`（t2-probe-a/b/c.js、t2-probe-b-result.json 等），不入库。
- 永不提交清单（auto-imports.d.ts / vite.config.ts / __snapshots__ / src/docs/*.md / .playwright-cli/）全部保持工作区状态未 add。
