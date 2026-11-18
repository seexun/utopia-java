# 任务 3 资产台账（ASSET-LEDGER）

## W3 页面层记录（任务 8/9 台账归档，任务 10 收口）

任务 8/9 的审查 Minor 有几条按"记台账"处置，但本台账是 domain 层资产账，页面层的
条目当时无处落——指针悬空。此处补记（页面层记录，与Ⅰ类资产账无关）：

| 项 | 内容 | 裁定（任务 10 终审） |
| --- | --- | --- |
| 8-M-5 发送键变体微差 | 旧仓 IconButton primary/secondary 两档皮 vs 本仓 UIconButton 中性档 + 有输入时字色升 primary——**有意微差**，注记在 `views/utopia/chat/ChatComposer.vue`；完全复刻需 kit 加 variant 档 | **随维护收口**：不值得为单一按钮给 kit 加档；走查对照表已记为"有意演进" |
| 8-M-3 加载更早按钮条件口径 | 旧仓 `!isFetchNextPageError`（只有翻页失败才收起）vs 本仓 `isError`（任意页失败都收）——首屏挂时翻页无从谈起，重试走 retryConversations；注记在代码现场 | **明确放弃（保留现状）**：语义成立，注释已写明口径差 |
| 多根地雷教训 | **多根页面（注释根/v-if 链无常驻根）+ 壳 out-in Transition = 静默吞掉新叶挂载**，零报错、整壳空白（W3 聊天页实证，二分到只剩模板才现形）。纪律：页面组件外层常驻单元素根——kb-page 与 chat 头注、路由表注释都已钉死 | **已收口**（教训入三处头注）；lint 级兜底（vue/no-multiple-template-root 对注释根的覆盖）记为维护项，见报告遗留表 |
| 9-M-5/M-6（承接审查单） | 内容以审查单为准，而审查单未归档入仓——指针散佚 | **终验收兜底关闭**：B 节全站走查覆盖图谱交互/面板联动各面未见同类缺陷；散佚本身记入报告遗留表（流程教训：审查单应入仓） |
| 9-普通历史行方向前缀 | 历史抽屉普通事实行缺方向箭头（旧仓 in-edge `← ` 前缀/out ` →` 后缀 + 谓词缺失时斜体 unknownPredicate 降级）——任务 9 只修了合并三种 | **已修**（任务 10 触及 entity-panel 顺手收，旧仓逐字） |
| 9-注释行号漂移 | 审查单指注释引用的行号随编辑漂移 | **已扫**（任务 10 全库 sweep：W3 触及文件零残留行号引用，类引用均用常量名/#issue 号等稳定锚）；未找到可修对象，视为扫清 |
| 9-query mock 噪音 | `tests/w1-review.spec.ts` 的 `@/api/graph` mock 桩了六个图谱读函数、漏了页面真实 import 的三个写函数（confirmFact/rejectFact/revertMerge）——纯读用例下无症状但形状失真 | **已修**（mock 收敛到真实 import 面） |

## 退役记录（任务 7，2026-11-15）

React 随迁件与其冻结用例按 ledger 计划随任务 7 退役：删除 `useKbEvents.ts` /
`useAlertEvents.ts`（Ⅱ(d) hook 随迁）与 `useEventReconnect.test.ts`（Ⅰ，14 例）/
`useKbEvents.test.ts`（Ⅰ，5 例）。**测试改写嫁接而非保留**：14 例 →
`tests/sse-reconnect.spec.ts`（钉生产 Vue composable），5 例 →
`src/domain/kbEventsCore.test.ts`（钉 kbEventsCore 纯函数）——逻辑与断言逐字保留，
仅被测对象从 React 副本换到生产 Vue 面。`queryDefaults.test.ts` 的 QueryClient 来源
react-query → vue-query（同源 query-core，断言零改）。devDeps
`react` / `@tanstack/react-query` / `@types/react` 三件裁撤（lockfile 已清），
生产与测试代码自此零 react 引用。

---

对照旧仓 `web/src/`（utopia-0.1.0-rc9）**全量逐文件盘点**。判定口径：

- **Ⅰ类**：纯逻辑模块/用例，整文件逐字节搬运（仅修断掉的 import 路径，不改逻辑与断言）。
- **Ⅱ类**：可搬面——(a) 抽离：自 React 模块取纯逻辑子集（文件头注明来源与取舍，除头注外逐字）；(b) 整文件可搬（本就无 React 面）；(c) 旧世界 API 快照（支撑Ⅰ类依赖）；(d) hook 随迁（为钉住行为规格而整文件随迁）。
- **Ⅲ类**：React 组件面/页面/组件用例——不搬，Vue 侧重写（任务 4+）。

**一致性说明**：搬运在工作树以 CRLF 落盘（与本仓 `core.autocrlf=true` 检出口径一致），内容与旧仓逐字节一致（按 CR 归一后 `cmp` 全等）。提交时 git 归一为 LF，与仓内既有文件同口径。

## 统计

| 项 | 数 |
| --- | --- |
| 旧仓 `web/src` TS 文件总数（含 *.tsx/*.d.ts） | **144** |
| 搬入 `src/domain/` 文件数 | **62**（37 源码 + 25 用例） |
| ├ Ⅰ类整文件逐字（21 源模块 + 25 用例） | 46 |
| ├ Ⅱ类抽离（6 个旧文件 → 6 个新文件，除头注逐字） | 6 |
| ├ Ⅱ类可搬面整文件（i18n×3 + graph 面×4） | 7 |
| ├ Ⅱ类旧世界 API 快照（api.ts） | 1 |
| └ Ⅱ类 hook 随迁（useAlertEvents/useKbEvents） | 2 |
| Ⅲ类不搬 | **82** |
| 用例对账 | 旧仓 36 个用例文件 / 267 例 → 新仓 25 文件 / **200 例全绿零改写**；未搬 11 文件 / 67 例（均为组件渲染用例，见Ⅲ类行） |

Ⅱ/Ⅲ类边界注记：`unread.ts` 虽列Ⅱ类抽离，其纯面（convMarks store）即简报点名的 Ⅰ类"unread"；`graphVisuals/graphCanvas` 等是简报点名的"Ⅱ类可搬面"。`domain/api.ts` 全文等于旧仓 `api.ts`，与任务 2 的 `src/api/`（axios 新世界）职责重叠——**保留不删**，理由见该行。

## 一、旧仓 `web/src/` 根

| 文件 | 类别 | 去向 / 理由 |
| --- | --- | --- |
| time.ts | Ⅰ | `domain/time.ts` 逐字 |
| time.test.ts | Ⅰ | `domain/time.test.ts` 逐字（16 例） |
| objectValue.ts | Ⅰ | `domain/objectValue.ts` 逐字 |
| objectValue.test.ts | Ⅰ | 逐字（4 例） |
| objectValue.units.test.ts | Ⅰ | 逐字（5 例） |
| quotes.ts | Ⅰ | `domain/quotes.ts` 逐字 |
| quotes.test.ts | Ⅰ | 逐字（8 例） |
| citations.ts | Ⅰ | `domain/citations.ts` 逐字（remark/unified 依赖已入 package.json） |
| citations.test.ts | Ⅰ | 逐字（9 例） |
| steps.ts | Ⅰ | `domain/steps.ts` 逐字（import 自 `./api`、`./ui` 均在 domain 内闭环） |
| steps.test.ts | Ⅰ | 逐字（29 例） |
| classHierarchy.test.ts | Ⅲ | **不搬**。2 例中例 1 渲染 React 组件 `ClassDefinition`（pages/Ontology）+ renderToStaticMarkup；例 2 是纯 i18n 断言但与组件用例同文件，不拆分（拆分即改写用例文件）。Vue 侧任务 4 重写类树时以同断言覆盖 |
| clipboard.ts | Ⅰ | `domain/clipboard.ts` 逐字（消费 domain/toast） |
| clipboard.test.ts | Ⅰ | 逐字（6 例） |
| eventStream.ts | Ⅰ | `domain/eventStream.ts` 逐字（简报"eventSource 解析"；旧仓无独立用例，行为由 useEventReconnect/useKbEvents 用例覆盖） |
| liveAnswer.ts | Ⅰ | `domain/liveAnswer.ts` 逐字 |
| liveAnswer.stream.test.ts | Ⅰ | 逐字（6 例，SSE 流解析） |
| liveAnswer.test.ts | Ⅲ | **不搬**。19 例：10 例纯函数（citedSources 4 + answeredWithoutSources 6）+ 9 例 react-markdown 渲染。混合文件不拆分；纯例的断言随 Vue 聊天页重写（任务 4）重建。**疑虑：citedSources/answeredWithoutSources 两个纯函数已随 liveAnswer.ts 搬入但暂无直测**（见任务报告） |
| chatScroll.ts | Ⅰ | `domain/chatScroll.ts` 逐字 |
| chatScroll.test.ts | Ⅰ | 逐字（4 例） |
| chatStream.test.ts | Ⅰ | `domain/chatStream.test.ts` 逐字（29 例）。主题实现（streamChat/reattachChat）无独立文件，在 api.ts 内——随 api.ts 快照闭环 |
| fieldLabels.test.ts | Ⅲ | **不搬**。3 例全是 ui/ Field·Input·Textarea React 组件的 label 关联用例（renderToStaticMarkup）。简报点名的"fieldLabels"即此文件——它是组件用例而非纯逻辑，Vue 表单组件重写时重建 |
| origin.ts | Ⅰ | `domain/origin.ts` 逐字 |
| origin.test.ts | Ⅰ | 逐字（6 例） |
| palette.ts | Ⅰ | `domain/palette.ts` 逐字（无独立用例；消费面在 pages/graph*） |
| predicateText.ts | Ⅰ | `domain/predicateText.ts` 逐字（无独立用例） |
| pushEndpoint.ts | Ⅰ | `domain/pushEndpoint.ts` 逐字 |
| pushEndpoint.test.ts | Ⅰ | 逐字（2 例） |
| queryDefaults.ts | Ⅰ | `domain/queryDefaults.ts` 逐字（type-only 依赖 @tanstack/react-query） |
| queryDefaults.test.ts | Ⅰ | 逐字（4 例） |
| sourceKinds.ts | Ⅰ | `domain/sourceKinds.ts` 逐字（无独立用例） |
| api.ts | Ⅱ(c) | `domain/api.ts` **全文逐字快照**（2951 行）。判定：与任务 2 `src/api/`（axios 基座 client.ts + 按域拆分端点 + types.ts）**职责重叠但不是同一职责的两份拷贝**——domain Ⅰ类资产（steps/liveAnswer/chatStream 用例/ruleExpressions 用例/alignmentPhrase/reviewErrors/ruleDependencies）的 import 面与用例断言零改写要求依赖旧世界语义：`streamChat`/`reattachChat`（fetch + eventsource-parser）、`api` 对象（task2 未导出 api 对象）、全套视图类型。删则必须改写用例与被测对象——违反验收铁律。app 运行时不受其影响（src/domain 之外零引用）。**任务 4+ 页面迁完后评估裁撤** |
| api.ts（终审 Minor-4 归档补记，遗留收口 2026-11-15） | Ⅱ(c) | **评估完成，裁定：保留 + 漂移闸**。消费面复核不变（Ⅰ类 import + 五个 .test，app 运行时零引用）。防两真源分叉：`tests/api-snapshot-drift-gate.spec.ts` 文本层契约断言「快照 (method+path) 端点集合 ⊆ src/api 实际端点集合」（提取面：括号配平/注释剔除/段中插值→:v、尾缀插值截断/query 剥离//api 前缀归一；收口时实测**两侧集合完全相等**——快照 164 条 0 缺失，多余端点 0 条以快照钉住）。将来快照端点从 src/api 消失→闸先炸→本行「保留」前提失效，届时重评裁撤 |
| i18n/en.ts | Ⅱ(b) | `domain/i18n/en.ts` 逐字（纯数据包，无 React 面） |
| i18n/zh.ts | Ⅱ(b) | `domain/i18n/zh.ts` 逐字 |
| i18n/index.ts | Ⅱ(b) | `domain/i18n/index.ts` 逐字（S 取词 + localStorage 检测，无 React；vitest-setup 钉 `utopia.lang=en`） |
| toast.tsx | Ⅲ | **不搬**。2 行 re-export shim（`ui/toast`）。纯单例已抽离至 domain/toast.ts（见 ui/ 节） |
| kb.tsx | Ⅲ | React 应用根（Provider/路由），Vue 侧由 main.ts/router 承担 |
| main.tsx | Ⅲ | React 入口，新仓 main.ts 已有 Vue 入口 |
| router.tsx | Ⅲ | React Router 配置，新仓 vue-router 已有 |
| useTitle.ts | Ⅲ | **不搬**。`usePageTitle` = React useEffect + document.title 一行包装；Vue 侧在视图/路由守卫直接实现 |
| vite-env.d.ts | Ⅲ | 新仓 pure-admin 有自己的 types/ 体系（env.d.ts 等），不搬 |
| unread.ts | Ⅱ(a) | `domain/unread.ts` 抽离：去掉 `useSyncExternalStore` import 与 `useUnread`/`useLive` 两 hook（React 绑定面，任务 4/7 重写为 composable），其余逐字；文件头注明 |
| wsStore.ts | Ⅰ | `domain/wsStore.ts` 逐字（纯逻辑：localStorage 持久化 + 订阅的模块级 store，无 React 面——hook 消费面在旧仓 kb.tsx，Ⅲ） |
| useAlertEvents.ts | Ⅱ(d) | `domain/useAlertEvents.ts` **hook 随迁**：React `useEffect`/`useQueryClient` 绑定面整文件逐字随迁，为钉住 useEventReconnect.test.ts 的行为规格；任务 4/7 重写为 Vue composable 后连同移除（react/@tanstack/react-query devDeps 一并） |
| useEventReconnect.test.ts | Ⅰ | `domain/useEventReconnect.test.ts` 逐字（14 例，node 环境直调 hook effect + 假 EventSource）。含**唯一许可偏差**：1 处 `@ts-expect-error 2349`——1 指令 + 2 行缘由说明共 3 行注释（TS 6 将 `void \| (()=>void)` 联合调用升级为硬错误，旧仓 TS 5.7 原样可过）；逻辑与断言零改写 |
| useKbEvents.ts | Ⅱ(d) | `domain/useKbEvents.ts` **hook 随迁**：同 useAlertEvents.ts——KB 流 hook 面（订阅/重连/事件合并/页面隐藏释放），由 useEventReconnect.test.ts 的 14 例钉住行为 |
| useKbEvents.test.ts | Ⅰ | `domain/useKbEvents.test.ts` 逐字（5 例，假 EventSource 直测事件分发） |

## 二、旧仓 `web/src/i18n/`（已并入上表Ⅱ(b)行）

en.ts / zh.ts / index.ts → `domain/i18n/` 逐字，与上表合一，不另计行数。

## 三、旧仓 `web/src/ui/`（React 组件库）

| 文件 | 类别 | 去向 / 理由 |
| --- | --- | --- |
| index.tsx | Ⅱ(a) | `domain/ui.ts` 抽离：仅取纯时间函数 `localDate`/`localDateTime`（steps.ts 消费），除头注逐字；其余组件面Ⅲ |
| toast.tsx | Ⅱ(a) | `domain/toast.ts` 抽离：纯消息总线（模块级单例 push/dismiss/toast 对象）逐字；`<ToastHost/>` 组件面Ⅲ |
| citation.tsx | Ⅲ | React 引用角标组件，Vue 重写 |
| dialog.tsx | Ⅲ | 同上（ui/dialog） |
| field.tsx | Ⅲ | 同上 |
| table.tsx | Ⅲ | 同上 |
| tooltip.tsx | Ⅲ | 同上 |

## 四、旧仓 `web/src/components/ui/`（shadcn 面板，20 文件）

badge/button/card/checkbox/command/dialog/dropdown-menu/input-group/input/label/popover/radio-group/select/separator/skeleton/switch/table/tabs/textarea/tooltip.tsx —— **全部Ⅲ类不搬**：shadcn/Radix 生态，新仓以 element-plus 为组件基座（任务 2 已定），无对应消费面。

## 五、旧仓 `web/src/lib/`

| 文件 | 类别 | 去向 / 理由 |
| --- | --- | --- |
| utils.ts | Ⅲ | 一行 `export { cn } from "cn"`——shadcn 配套，新仓 class 拼装走各自工具，不搬 |

## 六、旧仓 `web/src/pages/`（React 组件面 42 个 .tsx）

Account / AccountShell / AddOntologyRegressionDialog / AlertBell / Chat / DocViewer / Docs / EntityHistory / ExpressionDraftEditor / ExpressionDraftLab / Graph / HeaderActions / KbScope / KbSettings / KbSwitcher / Legal / Login / Mappings / Members / MyKbs / NextStep / Ontology / OntologyRegressionsPanel / OntologySchemaGraph / PendingFacts / Review / ReviewOverview / Search / ServerDown / Shell / SourcesRail / Sso / Tokens / UserMenu / chatCitations / chatCopy / graphDialogs / ontologyDialogs / ontologyTables.tsx —— **Ⅲ类不搬**（Vue 重写，任务 4/7）。以下 3 个 .tsx 同时是Ⅱ类抽离源：

| 文件 | 类别 | 去向 / 理由 |
| --- | --- | --- |
| Settings.tsx | Ⅱ(a) | `domain/pages/Settings.ts` 抽离：`modelCardStatus`（优先级表，Settings.test 随迁）+ `dsSpecs`（引擎连接串拼装，dsSpecs 两用例随迁），除头注/import 外逐字（收尾核对时补上被遗漏的 modelCardStatus 闭括号与两段注释，已恢复逐字）；设置页组件面Ⅲ |
| Library.tsx | Ⅱ(a) | `domain/pages/Library.ts` 抽离：`scheduleToPickerState` 逐字；Library 页组件面Ⅲ |
| RulesPanel.tsx | Ⅱ(a) | `domain/pages/RulesPanel.ts` 抽离：`OPS`/`operandKind`/`parseOperand` 逐字；规则编辑台组件面Ⅲ |

## 七、旧仓 `web/src/pages/`（纯逻辑 .ts 与用例）

| 文件 | 类别 | 去向 / 理由 |
| --- | --- | --- |
| alignmentPhrase.ts | Ⅰ | `domain/pages/alignmentPhrase.ts` 逐字 |
| alignmentPhrase.test.ts | Ⅰ | 逐字（6 例） |
| expressionDraft.ts | Ⅰ | `domain/pages/expressionDraft.ts` 逐字 |
| expressionDraft.test.ts | Ⅰ | 逐字（4 例） |
| reviewErrors.ts | Ⅰ | `domain/pages/reviewErrors.ts` 逐字（消费 domain/api 的 ApiError） |
| reviewErrors.test.ts | Ⅰ | 逐字（2 例） |
| ruleDependencies.ts | Ⅰ | `domain/pages/ruleDependencies.ts` 逐字 |
| ruleDependencies.test.ts | Ⅲ | **不搬**。10 例：9 例纯 `ruleDependencies()` + 1 例渲染 `RuleDependencyList` 组件。混合文件不拆分。**疑虑：主题模块已搬入但 9 个纯例暂无直测**（见任务报告） |
| ruleExpressions.ts | Ⅰ | `domain/pages/ruleExpressions.ts` 逐字 |
| ruleExpressions.test.ts | Ⅰ | 逐字（8 例；用 `api.updateRule` 走 api.ts 快照的内存桩） |
| ruleEmptyOperands.test.ts | Ⅰ | 逐字（5 例，测 domain/pages/RulesPanel.ts 的 parseOperand） |
| ruleRangeOperands.test.ts | Ⅰ | 逐字（6 例，同上） |
| graphVisuals.ts | Ⅱ(b) | `domain/pages/graphVisuals.ts` 逐字（简报点名"规格常量可搬面"；type-only 依赖 sigma） |
| graphVisuals.test.ts | Ⅰ | 逐字（8 例，纯函数 flattenOnLight/mix） |
| graphCanvas.ts | Ⅱ(b) | `domain/pages/graphCanvas.ts` 逐字（简报点名"数据映射可搬面"；sigma/@sigma/node-border/graphology 依赖已入 package.json） |
| hoverVoidProgram.ts | Ⅱ(b) | 逐字（sigma 渲染程序，纯 WebGL，无 React） |
| squareShellProgram.ts | Ⅱ(b) | 逐字（同上） |
| Settings.test.ts | Ⅰ | `domain/pages/Settings.test.ts` 逐字（6 例：modelCardStatus 优先级表 + 字段显隐） |
| dsSpecs.test.ts | Ⅰ | 逐字（4 例，测 domain/pages/Settings.ts 的 dsSpecs） |
| dsSpecs.identifiers.test.ts | Ⅰ | 逐字（4 例）。**收尾处置：新仓该文件曾被写坏为全 NUL 字节，已自旧仓原样恢复并核对逐字一致** |
| deploymentWorkerRange.test.ts | Ⅲ | **不搬**。渲染 `DeploymentAdmin`（pages/Settings.tsx）读 worker 输入格（256/64 回退值）——常量在 React 组件内，Vue 部署页（任务 4+）重建同断言 |
| scheduleIncompleteTime.test.ts | Ⅲ | **不搬**。渲染 `SchedulePicker`（pages/Library.tsx）组件树，React 绑定 |
| weeklyScheduleLocale.test.ts | Ⅲ | **不搬**。2 例：例 1 纯 `scheduleToPickerState`（该函数已搬入 domain/pages/Library.ts），例 2 渲染 SchedulePicker。混合文件不拆分。**疑虑：scheduleToPickerState 暂无直测**（见任务报告） |
| Graph.test.ts | Ⅲ | 渲染 Graph 页组件（17 例），不搬 |
| Mappings.test.ts | Ⅲ | 渲染 Mappings 页组件（5 例），不搬 |
| OntologyRegressionsPanel.test.ts | Ⅲ | 渲染该面板组件（3 例），不搬 |
| Shell.test.ts | Ⅲ | 渲染 Shell 页组件（1 例），不搬 |

## 八、非 TS 资产（不在本任务范围，记录备查）

docs/ingest.md、docs/mcp.md（文档）、styles.css、typeset.css（样式面，新仓 style/ 体系）、assets/fonts/DIN.otf（字体）——不属 *.ts，任务 4+ 按需处理。

## 九、收尾核对的改动记录（相对前代理中断时的工作树）

1. `domain/pages/Settings.ts`：补回被遗漏的 `modelCardStatus` 闭括号（前代理抽离时截断，typecheck 必炸）与"系统层数据源注册/每种引擎问什么"两段注释——现为逐字。
2. `domain/pages/dsSpecs.identifiers.test.ts`：恢复被写坏的全 NUL 文件为旧仓逐字内容。
3. `domain/useEventReconnect.test.ts`：仅加 1 处 `@ts-expect-error 2349`——1 指令 + 2 行缘由说明共 3 行注释——TS 6 将 `void | (() => void)` 联合调用升级为硬错误（旧仓 TS 5.7 同文件原样可过）；逻辑与断言零改动。这是全部 62 个文件中唯一的逐字偏差。
4. `package.json` 补依赖：`graphology@^0.26.0`、`sigma@^3.0.3`、`@sigma/node-border@^3.0.0`（graphCanvas/graphVisuals/hoverVoid/squareShell 四个Ⅱ(b)文件的 import 面；版本对齐旧仓）。前代理已加的 `eventsource-parser`/`remark-gfm`/`remark-parse`/`unified`（deps）与 `react`/`@tanstack/react-query`/`@types/react`（devDeps）经核实均为 domain 用例所需，保留。
5. `vitest-setup.ts`（前代理新增）与 vitest.config.ts 的双 project（app=jsdom / domain=node）经核实合理，保留。
