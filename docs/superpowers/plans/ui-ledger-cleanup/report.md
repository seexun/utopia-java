# UI 挂账清偿批（ui-ledger-cleanup）总验收报告

- 分支：`feat/ui-ledger-cleanup`（任务链 3bd5bf4 基线上：82a6b69 → 9979df7 → d16edde → 81a40f7）
- 汇总日：2026-11-21（任务 5：全量回归与证据汇总）
- 结论：**四任务全部 PASS；全量回归零退化（test 421/421 = 基线、typecheck 绿、build:prod 成功、双 smoke 全过）；9 张证据截图逐张核对与文件名对应成立（含 2 条非阻断观察项）。**

---

## 一、四任务逐项验收口径 + 结果

### 任务 1：业务组件硬编码色 var 化（commit `82a6b69`，23 文件 204+/204-）

| 验收口径 | 结果 |
|---|---|
| 8 个确定性映射色值在清单文件中归零（脚本替换） | PASS：4 文件 35 处（TagsView 1 / alert-bell 18 / account/shell 10 / user-menu 6），其余清单文件本就不含 |
| 语境色逐处判定（167 处：162 换 + 5 处 dark-only 保留；另 19 处反白/数据/注释保留） | PASS：判定表见任务 1 报告 §3（含 5 文件 9 处清单外补替，门禁驱动） |
| grep 归零门禁（8 色值全 src） | PASS：零输出（exit=1） |
| typecheck / build:prod | PASS（vue-tsc 零错；vite build 成功） |
| 走查 5 屏 + console error | PASS：5/5 断言通过，console error = 0（t1-01~t1-05 截图） |
| alert-bell 组件佐证 | PASS：`tests/alert-bell.spec.ts` 3/3（组件当前无路由挂载点，见任务 1 报告疑虑 1） |

### 任务 2：register 页 SaaS Light 化（commit `9979df7`，1 文件 +17/-8，仅 style 段）

| 验收口径 | 结果 |
|---|---|
| 逻辑零动（template/script 1-156 行零 diff） | PASS：diff 首 hunk 从 `</script>` 后 style 段开始 |
| 镜像 login 家族（背景渐变/卡片/暗影段） | PASS：computed style 实测 bg=rgb(255,255,255)、radius=8px(--radius-card)、radial-gradient(rgb(239,246,255)=--color-primary-soft) |
| 残留 hex 清偿（该文件 4 处） | PASS：#707070→ink、#ffffff→el-bg-color、#bfbfbf→ink-3、#fff→ink-3（语境判断见 §四 deviation） |
| grep 归零（register.vue 内 `#[0-9a-f]{3,6}`） | PASS：零输出（exit=1） |
| 走查 DOM 断言 + 像素复核 | PASS：8 项断言全过（验证码框 captchaEnabled=false 不渲染属正常）；像素复核照片背景已清除、SaaS Light 版式成立；console error = 0（t2-01 截图） |

### 任务 3：`--offset-app-content` 令牌抽取（commit `d16edde`，5 文件 +27/-12）

| 验收口径 | 结果 |
|---|---|
| tokens.scss 单源定义（124px + 拆解注释） | PASS：:root 块末尾、html.dark 前，逐字简报 |
| 10 处消费点位收口（8 CSS calc + 2 JS 读取） | PASS：tool/build ×5、RightPanel ×1、iFrame ×2、InnerLink ×1；appOffset() 兜底 124 |
| RightPanel 陈旧基数修正（90→124） | PASS：.field-box computed 554px = 720−124−42，实测吻合 |
| grep 归零（`100vh - 124` / `clientHeight - 124` / `100vh - 50px - 40px`） | PASS：零输出；残留 124 仅为定义处/兜底/注释三类非消费面 |
| 令牌管线实测 | PASS：documentElement 探针 `"124px"`；.container 596px = 720−124；文档 720/720 无纵向双滚动条；iframe 容器 596px（JS 读取生效） |
| 附带：T1 搁置项 build:500 rgb(89,89,223) 清偿 | PASS：→ var(--color-primary)；全库 `rgb(89` 仅余 theme.scss:460 青色系无关项 |

### 任务 4：折叠弹层亮色化 + 暗色侧栏 44/40 分档（commit `81a40f7`，2 文件 +61/-0）

| 验收口径 | 结果 |
|---|---|
| body 类桥接（watch + onUnmounted）三态 | PASS：亮挂/暗摘/回亮恢复（A1/A3/B2/C1） |
| 弹层亮色卡化（teleport 逃出 #app 仍受控） | PASS：outsideApp=true、真实类名 `el-menu--vertical el-menu--popup-container` 与预写选择器一致、项高 40px（A7）、border/radius=8px/minWidth=176px（A8） |
| 暗色 44/40 分档 | PASS：一级 44px（B5/B6 双探针）、二级 40px（B8）、margin 2px 8px + radius 6px（B7） |
| 因果验证（暗色下弹层回落 EP 默认） | PASS：56px ≠ 40px（B4），证明亮色弹层 40px 确由 body 类桥接规则驱动 |
| 18/18 走查断言 + console | PASS：SUMMARY 18/18 passed，console error = 0 |
| typecheck / build:prod | PASS（零错；✓ built in 25.72s） |

---

## 二、全量回归（任务 5 实测，HEAD = 81a40f7）

| 门 | 命令 | 结果 |
|---|---|---|
| 单元/组件测试 | `cd jknow-web && pnpm test` | **57 文件 / 421 通过 / 0 失败**（20.88s）——与基线 421 持平，无受影响 spec 适配（零测试文件改动） |
| 类型 | `pnpm typecheck` | 绿（vue-tsc --noEmit --skipLibCheck 零输出，exit=0） |
| 构建 | `pnpm build:prod` | 成功（exit=0；产物 vendor-utils 1213.98kb/gzip 408.97kb、index 795.56kb/gzip 273.35kb、css 560.24kb/gzip 81.44kb 等） |
| smoke P0 | `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` | **20/20 PASS，ALL ALL PASS（exit=0）**：login/getInfo/getRouters + 11 列表 + 8 date-range |
| smoke P1 | `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh` | **PASS=67 SKIP=1，ALL PASS（exit=0）**：kbs 全 GET 面、匿名 health、三处源文案错误形状、pt- 令牌签发/撤销/作用域、seed 明细体形状；SKIP=chat（JKNOW_LLM_API_KEY 未配，环境性跳过非退化） |

---

## 三、豁免清单终态（保留不改，逐处列出）

### 3.1 hljs 代码高亮八色（豁免维持）

位置：`jknow-web/src/style/utopia-pages.css:698-738`（`.chat-prose` 作用域；src/style/ 目录本属零改区）。注释自证："代码高亮八色无同值新令牌，按旧垫片值内联字面量"。

| 色值 | 用途（hljs token 组） |
|---|---|
| `#6b7280` | comment / quote |
| `#4f5bd5` | keyword / selector-tag / meta |
| `#1a7f4b` | string / regexp / addition |
| `#9a6a12` | number / literal / symbol |
| `#1d6fb8` | title / title.function_ / section |
| `#0f8a86` | type / built_in / class .hljs-title |
| `#6d4fd6` | attr / attribute / variable / template-variable |
| `#c0284f` | deletion |

### 3.2 dark-only 保留（保留原值 + `/* dark-only */` 注释，逐处）

| 位置 | 值 | 语境 |
|---|---|---|
| `Settings/index.vue:315` | `#1b2a47` | 暗色侧栏主题预览块（theme-dark 预览小图），无对应令牌 |
| `Settings/index.vue:327` | `#1b2a47` | 同上 |
| `Settings/index.vue:331` | `#1b2a47` | 同上 |
| `Settings/index.vue:341` | `#1b2a47` | 同上 |
| `lock.vue:181` | `#0f0c29→#302b63→#24243e` 渐变 | 锁屏暗幕装饰渐变，无对应令牌 |

### 3.3 其它功能性保留（非 dark-only，一并留档）

| 位置 | 值 | 语境 |
|---|---|---|
| `Settings/index.vue:142` | predefineColors 8 值 | el-color-picker 预定义色板**功能数据**（含当前主色 #2563eb），非样式字面量 |
| 反白 `#fff` 系（15 处） | — | 彩底/蒙层反白文字与图标：alert-bell:59,60；TagsView:449,454,667；HeaderNotice:122；HeaderSearch:209；build:630,642；RightPanel:836,845,902；lock:202,264,310,325；401:51；404:206——按"彩底反白保留"规则判定，逐处判定表见任务 1 报告 §3.1 |
| 注释内非色 hex（0 改动防误伤） | issue 号/死代码 | search/library/review/rows.tsx/turn.tsx/alert-bell 中文注释 issue 号；build:383,387,411 注释掉的死代码 |

### 3.4 其它零改区豁免（维持）

`gvdesign/` 目录、`src/style/` 令牌定义本身、测试文件（规格 §"不动"条款）。

---

## 四、Deviation 汇总（全链）

### 任务 1（3 条，均为有意视觉归一，供复核）

1. `build/index.vue` 空画布淡紫提示 `#ccb1ea` → ink-3（灰族提示文归一）。
2. `build/index.vue` 拖拽悬停靛蓝 `#787be8` ×3 → primary（交互示能同族归一）。
3. `lock.vue` 解锁按钮紫渐变 → `var(--gradient-brand)`（品牌渐变令牌归一）。

### 任务 2（2 处偏差，均已消解）

1. **头注字面矛盾消解**：简报"逐字"头注注释含 `#707070` 字面，与步骤 2 grep 归零门自相矛盾——以客观验收门为准，注释措辞改为"硬编码灰标题"（对齐 login.vue 头注），样式规则代码与简报逐字一致。终态 grep 零输出。
2. **`.el-register-footer` #fff 语境判断**：原为照片底反白文字；本任务底色已改令牌浅底，保留 #fff 将不可见——按 login `.el-login-footer` 同位选择器镜像为 `var(--color-ink-3)`。

### 任务 3（0 deviation）

全部代码逐字采用简报文本；RightPanel 基数修正是简报明示的"陈旧基数修正"预期项（90→124），走查实测 554px 吻合。

### 任务 4（5 条，全部已消解/预案内）

1. **`::before` 圆角（预案触发）**：暗色 hover/选中遮罩 `::before` 方角压在分档 6px 圆角项上——按简报预案将两处 `::before` 加 `border-radius: inherit`（与 theme-light 块同款），复测 beforeRadius=6px、18/18 复跑通过。
2. **弹层选择器零修正**：`.el-menu--vertical` / `.el-menu--popup` 与实测类名一致，按简报原文提交。
3. **vue import 形态**：index.vue 原无任何 vue 显式 import（依赖 unplugin-auto-import），无法"补进现有 import"——新增一行 `import { onUnmounted, watch } from 'vue'` 置于 import 块首，未触碰其它 import。
4. **Settings 入口路径**：任务背景称"顶栏齿轮"，实测本仓 Navbar 无齿轮，实际入口为头像下拉「布局设置」（`command="setLayout"`）——纯走查路径差异，不影响代码。
5. **teleport 落点**：弹层挂 body 下无类 wrapper DIV 内（非 body 直挂）——后代选择器不受影响（A4/A7 实证）。

---

## 五、Minor 搁置清单（留台账，本批不处理）

| # | 来源 | 项 | 状态 |
|---|---|---|---|
| 1 | T1 | `build/index.vue:500` `rgb(89,89,223)` 拖拽指示条 | **已在 T3 顺手清偿**（→ var(--color-primary)，commit d16edde） |
| 2 | T1 | `RightPanel.vue:845` dashed `#fff` 隐形占位边框 hack（无碍） | 搁置 |
| 3 | T1 | `TreePanel:594/598` `rgba(64,158,255,·)` resizer 高亮（需 color-mix） | 搁置 |
| 4 | T1 | `HeaderSearch:179` 命名色 `red`（可归 danger） | 搁置 |
| 5 | T1 | 任务 1 报告 §1/§6 计数口误（纯文书） | 搁置（不影响代码与验收） |
| 6 | T2 | `register.vue:174-175` 与 login 排版差一空行（风格观察，无行为影响） | 搁置 |
| 7 | T4 | `sidebar.scss:563` 弹层 is-active font-weight 600 vs light 块 700（简报原文即 600，建议后续统一 700） | 搁置 |

---

## 六、证据截图核对表（9 张，逐张打开核对）

目录：`docs/superpowers/plans/ui-ledger-cleanup/`。核对方式：视觉模型逐张实读（防假证据复发——t1-03 曾为旧裁剪假图，T1 已重拍替换）。

| # | 文件 | 大小 | 核对结论 | 判定 |
|---|---|---|---|---|
| 1 | t1-01-alert-bell.png | 59,310 B | 顶栏铃铛（红"3"徽章）+「通知公告」弹层打开：全部已读、通知项（发布开通公告/维护通知）+ 时间戳 + 通知管理入口；admin 布局完整。视觉略淡 = fade-in 动画瞬间，T1 报告已用 computed style 探针证实面板 bg=rgb(255,255,255) 不透明、五组色值全令牌解析 | 相符 |
| 2 | t1-02-user-menu.png | 110,093 B | /docs/ingest 顶栏「若依」chip 下方用户弹层：身份头（若依/管理员/admin）+ 个人资料/知识库/管理/语言(中文)/退出登录；背景文档页正常 | 相符 |
| 3 | t1-03-tagsview.png | 3,470 B | RuoYi TagsView 条：‹ 滚动箭头 + 「首页」单页签 + 右侧「刷新」钮；items=1 与 T1 断言一致（登录初始单页签）；深色「消息通知」气泡为截图瞬间 tooltip。**真证据**（旧假图已替换） | 相符 |
| 4 | t1-04-dict-detail.png | 126,285 B | 背景字典管理列表正确（用户性别/sys_user_sex 首行、共 10 条）；右侧抽屉为滑入动画早期帧，仅约 110px 入画，头部「用户…」（=用户性别）与蓝色文档图标可辨，stat-card 区未入画——DOM 断言（drawer-head + stat-card=2）当时已通过 | 相符（弱证据，观察项①） |
| 5 | t1-05-404.png | 51,481 B | 「404错误!」标题（浅蓝）+ 3D 404 插画（蓝面板/警示牌/橙色 error 路障）；左侧插画+右侧标题为 RuoYi 404 常态。「返回首页」按钮未在截图辨认出（观察项②） | 相符 |
| 6 | t2-01-register.png | 107,804 B | 注册页 SaaS Light 成立：浅灰底 + 右上径向光晕（非照片）、白卡片细边框+软阴影+圆角；账号/密码/确认密码 + 注册钮 + 已有账户链接 + 版权行；无验证码框（captchaEnabled=false 正常） | 相符 |
| 7 | t3-01-build.png | 74,048 B | 表单构建三栏（输入型/选择型/布局型组件列表、画布含手机号字段、右属性面板 mobile 字段）；顶栏+标签栏+内容完整，无双滚动条、无重叠 | 相符 |
| 8 | t4-01-collapse-light.png | 44,882 B | 侧栏折叠至图标宽（~50px + G logo）；弹层白底卡化（边框/圆角/软影）含 3 项（图谱/检索/问答）——与 A6 itemCount=3、A8 卡化断言一致；亮色系成立。弹层下缘露出的白色圆角元素为画布搜索框正常遮挡，非缺陷 | 相符 |
| 9 | t4-02-dark-tiered.png | 52,545 B | 暗色（藏蓝）侧栏，一级（首页/工作台/知识管理…）视觉上高于二级（图谱/检索/问答）——与 44/40 分档方向一致；项间小边距+圆角可见；主内容区仍亮色；空态「还没配置对话模型」为正常 UI | 相符 |

观察项（非阻断）：
- ① t1-04 抽屉截图系滑入动画早期帧，证据力弱于 DOM 断言；后续如重拍建议在断言后增加 300-500ms 动画沉降等待。
- ② t1-05 中「返回首页」按钮未在截图中辨认出（DOM 断言 `.wscn-http404-container` 与 `.bullshit__oops="404错误!"` 已通过）。
- 家族对比图（折叠弹层 vs 展开侧栏同屏）为简报可选项，未补拍——t4-01/t4-02 已分别覆盖两态。

---

## 七、commit 链与证据归档

| 任务 | Commit | 文件 |
|---|---|---|
| T1 | `82a6b69` | 23 文件（18 清单 + 5 门禁补替），204+/204- |
| T2 | `9979df7` | register.vue，+17/-8 |
| T3 | `d16edde` | 5 文件，+27/-12 |
| T4 | `81a40f7` | Sidebar/index.vue + sidebar.scss，+61/-0 |
| T5 | 见本目录 git log | 本报告 + 9 张截图（docs/superpowers/plans/ui-ledger-cleanup/） |

未提交（按禁令排除，留工作区）：`jknow-web/auto-imports.d.ts`、`jknow-web/vite.config.ts`、`jknow-web/tests/__snapshots__/*.snap`、`jknow-web/src/docs/*.md`、`.playwright-cli/`、`.superpowers/` 产物。
