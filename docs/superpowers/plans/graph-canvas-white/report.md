# 图谱画布回归纯白 证据汇总报告

- 日期：2026-11-25（分支 feat/graph-canvas-surface，承接上一提交 0cb9492「两段式」后的回调；单任务内联执行）
- 规格：`docs/superpowers/specs/2026-11-25-graph-canvas-white-design.md`（方案 A）
- 上一批报告：`docs/superpowers/plans/graph-canvas-surface/report.md`（两段式实现记录，其结论已被本批回调）

## 一、为什么回调

两段式（透明细框 + 灰画布 #FAFBFC）落地后用户仍觉"灰色背景太突兀"。量化：画布色与卡头白仅差 5 阶（250 vs 255），**明度差不足以解释突兀感**——真实成因是点阵（透明度 ×3）在近白底上读成灰雾，叠加外框 + 卡头线的双重描边把画布"框"成灰盒子。两轮反馈均指向这个边界感，故直接消灭灰（方案 A），而非继续调灰度。

## 二、落地

| 项 | 两段式（0cb9492） | 本批（方案 A） |
|---|---|---|
| `--color-canvas` 亮 / 暗 | #fafbfc / #161616 | **#ffffff / #212121**（与卡面同值；令牌保留为独立语义位） |
| 点阵 | 最细层级交点撒点，alpha ×3 | **撤除**：`drawWorldGrid` 仅保留尺寸同步 + 铺底 |
| 卡片 | 透明 + 去阴影 | **恢复 `.card` 默认**（白底+细边+阴影），覆写只剩内距/溢出 |
| 卡头 | 显式 bg-card | 保留（与卡身同白，无害）；底部分隔线是区分工具区/画布的唯一线条 |
| 摊平纸底 `LIGHT_GROUND_RGB` | 250,251,252 | 255,255,255；单测期望值 206,172,113 → **208,174,114** |

## 三、走查实测（cw-walkthrough.js）

| 断言 | 亮 | 暗 |
|---|---|---|
| 画布像素 | `255,255,255` ✓ | `33,33,33` ✓ |
| 画布 60×60 空白区像素一致性 | uniform=true ✓（无点阵） | uniform=true ✓ |
| 卡片背景 / 阴影 | `rgb(255,255,255)` / has-shadow ✓ | `rgb(33,33,33)` / has-shadow ✓ |
| 卡头背景 | `rgb(255,255,255)` ✓ | `rgb(33,33,33)` ✓ |
| 本体页画布（共用绘制层） | `255,255,255` ✓ | — |
| console | 零 error ✓ | |

截图 cw-01~05（1280/1920×亮暗 + 本体）目验：画布与卡头为完整一块白，仅一条卡头分隔线；无灰雾、无框中框。

## 四、回归

`pnpm typecheck` ✓ / `pnpm test` 425/425（58 文件）✓（首跑 52/58 为机器闪断，重跑全绿）/ `build:prod` ✓（27.42s）/ smoke-p0 ALL PASS / smoke-p1 ALL PASS(67+1S) ✓。跑毕按纪律：captcha 复位 true、seed-demo + seed-nodebudget-demo 复种（3 库）、后端 ES_RECONCILE 重启。

## 五、净效果与留档

- 画布底回到「国内风收口批之前」的纯白形态；该批其余六项成果（节点白底描边彩字 / 边标签中文默认隐藏+hover+开关 / 工具塔归组 / 布局收敛 tag / 去 GitHub / 时间轴单标签）全部保留。
- 主题切换画布重上妆的 `watch(settingsStore.isDark)+nextTick` 保留（上上批补的既有局限修复，与底色无关）。
- 两次尝试的结论留档：**画布区灰底 + 点阵在本产品的白卡体系里不成立**——若将来要给画布加层次，应走方案 C（页面同灰+去外框+悬浮白卡头）而非"卡内嵌灰"。
- 教训（设计侧）：国内风收口批给画布加灰底时未考虑外层已有白卡，属设计失误；本次回调撤销。

## 六、追补：工作区两卡结构（2026-11-26，用户线框图定案）

纯白满铺仍被否（"还是不太好看"）后用户给出手绘线框：主体区域=**上卡头框 + 下画布框**两个独立描边卡，中间留缝。据此把 `graph/index.vue` 从"单卡内分区"改为两卡兄弟结构：

- 根容器 `knowledge-page` 加 `gap-[15px]`（与审查页滤卡同宽缝隙）；卡头独立成 `<div class="card">`（去 border-b 分隔线与 bg-card——整卡即工具区）。
- 画布框独立成 `<div class="card graph-canvas-card">`（`padding:0; overflow:hidden`），`flex-1 min-h-0` 满铺剩余高度；错误行/Loading/空态锚定不变（仍在画布层内）。
- 实施中踩坑：卡头卡未闭合导致画布卡嵌套其内——补 `</div>` 后 div 配平（根容器沿既有"模板尾隐式闭合"模式，与 HEAD 一致）。

### 实测（two-card-shot.js / two-card-dark.js，1920×1080）

| 断言 | 亮 | 暗 |
|---|---|---|
| `.knowledge-page > .card` 数 | 2 ✓ | 2 ✓ |
| 卡头框 rect | y=132 h=127 ✓ | — |
| 画布框 rect | y=274 h=766（满铺到底）✓ | — |
| 两卡缝隙 | **15px** ✓ | 15px ✓ |
| 画布框底色/圆角 | #fff / 8px | `rgb(33,33,33)` / 8px ✓ |
| console | 零 error ✓ | |

截图 `two-card-1920.png` / `two-card-1920-dark.png` 目验：与线框一致——上=短卡头框，下=大画布框，独立描边圆角，缝隙露页面底色。`pnpm typecheck` ✓ / `pnpm test` 425/425 ✓。

## 七、追补：工作区左右两栏（2026-11-26，用户拍板）

上下两卡上线后用户反馈"画布太窄"（高宽比 0.48 扁带状，径向布局四角浪费），拍板改左右两栏（视觉伴侣提案页 two-column-proposal 先行确认）。`graph/index.vue` 结构再调整：

- 根容器 `flex h-full flex-col gap-[15px] p-6` → `flex h-full gap-[15px] p-6`（row 向）。
- 左工具栏卡 `card flex w-[280px] flex-none flex-col overflow-y-auto`：竖排五段=标题+kb chip → 搜索（w-full）→ 返回全图（w-full）→ 类型图例（横排转竖排 `flex-col items-start`）→ 边图例弹层（触发钮 w-full、placement bottom-start）→ 底部组（稳定 tag/档位步进器/统计，`mt-auto` 沉底）。控件与状态全部原样迁移，仅重排。
- 画布框 `flex-1 min-h-0` 满铺整个内容高；时间轴/工具塔/详情浮层/错误行/Loading/空态锚定不变。
- 踩坑两枚：① 左栏卡吃全局 `.card` 15px 内距后容器又叠 `p-3` 双重内距（内容仅 224px）——去掉容器 p-3 统一走卡内距；② dev server 启动命令带 `| head -20`，head 读完退出 SIGPIPE 误杀 dev server——重启不带管道。

### 实测（two-col-shot.js / two-col-dark.js，1920×1080）

| 断言 | 亮 | 暗 |
|---|---|---|
| 根容器 flexDirection | row ✓ | — |
| `.knowledge-page > .card` 数 | 2 ✓ | 2 ✓ |
| 左栏卡 | x=280 w=280 h=908（满高）✓ | — |
| 画布框 | x=575 1305×908（高宽比 0.70）✓ | — |
| 横向缝隙 | 15px ✓ | — |
| 同顶同底 | true/true ✓ | — |
| 搜索框宽 | 248（满内容宽）✓ | — |
| 沉底组 | bottom=1024（贴内距沿）✓ | — |
| 画布框底色 | #FFFFFF | rgb(33,33,33) ✓ |
| console | 零 error ✓ | |

截图 `two-col-1920.png` / `two-col-1920-dark.png`。`pnpm typecheck` ✓ / `pnpm test` 425/425（58 文件）✓。

## 八、追补：左栏 gvdata 同款面板重设计（2026-11-26，用户拍板）

用户两轮反馈"左栏不像国内软件"后，放弃泛化方案，直接移植参考项目 gvdata-ui 自己的左栏语言（`dpp/components/canvasShell/OperatorTree.vue` + `prop-pane` + `left-pane-toggle` 源码逐项对照）：

- **面板头**：标题+kb chip 固定顶部，border-b 分隔（prop-pane__header 同款）
- **灰底填充式搜索**：`.graph-search` 改 `--color-fill` 底、无描边、圆角 8px，focus 白底+主色描边（op-search 同款）
- **竖条分组**：实体类型 / 关系两组，3px 竖条（主色/紫）+ 12px/600 组名 + 计数徽章 + 折叠箭头（op-group__header 同款）；实体行=色点+名称+计数，hover 浅主色底；"+N 个类"弹层撤除（全类直列、组内滚动）
- **边图例常显**（用户拍板，回归 graph-edge-legend.md 本意）：五类边线样本行直排进关系组，"显示全部标签"开关同步直排，原弹层撤除
- **沉底统计条**：border-t 钉底——稳定 tag + 节点档位（新增 label 键）+ 统计右贴
- **贴缘折叠钮**：18×38 圆角 9px 白底胶囊贴左栏右缘垂直居中（left-pane-toggle 同款）；收起后栏宽 16px、画布吃满 1569px；会话内状态
- 撤除：UPill 图例、GitCompareIcon、legendQ/legendShown/legendRest/legendRestFiltered/LEGEND_MAX；新增 i18n 键 entityGroup/edgeGroup/nodeBudgetLabel/panelCollapse/panelExpand（zh/en）

### 实测（panel-shot.js / panel-shot-dark.js，1920×1080）

| 断言 | 展开 | 收起 |
|---|---|---|
| 左栏宽 | 280px ✓ | 16px ✓（画布 1305→1569 吃满）|
| 面板头 border-b / 沉底条 border-t | 有 ✓（头 62px、条 944-1024 贴内距沿）| — |
| 实体类型行 / 边图例行 | 6 行 ✓ / 5 行常显 ✓ | — |
| 折叠往返 | 280→16→280 ✓ | — |
| 横向缝隙 | 15px ✓ | 15px ✓ |
| console | 零 error ✓ | 零 error ✓ |

截图 `panel-1920.png` / `panel-dark-1920.png` / `panel-collapsed-1920.png` / `panel-dark-collapsed-1920.png`。`pnpm typecheck` ✓ / `pnpm test` 425/425（58 文件）✓。

**踩坑留档**：实施中根容器被提前闭合（`</div>` 误插在折叠钮之后），画布框跑到根外成为第二根节点——正则 div 计数器骗过了检查（总数配平但嵌套错位），Vite 直接 500 "Invalid end tag"（vue/compiler-core）；换真·标签栈解析器定位后修正。教训：**结构性改动用栈式解析器验嵌套，不要只数数量**；多根节点还会复发 AppMain Transition 空白页（f1b08e2 同源）。
