# 图谱页国内风格收口 证据汇总报告

- 日期：2024-11-24（分支 feat/graph-domestic-style，单任务内联执行——子代理配额受限，主会话按 executing-plans 模式实施，纪律同 SDD）
- 范围：规格七项（画布层次/节点描边/边标签中文化默认隐藏/工具栏归组/tag 化/去 GitHub/时间轴双标签）

## 一、七项落地对照

| 项 | 改前 | 改后 | 证据 |
|---|---|---|---|
| A1 画布层次 | 纯白+近不可见细线网格 | `#F5F7FA` 灰底（亮）/`#1a1a1a`（暗，随 --color-bg）+ 点阵网格（最细可见层级交点撒点，半径/透明度随缩放淡入因子） | 走查 canvasBg=245,247,250、darkBg=26,26,26（画布像素实测） |
| A2 节点样式 | 实心彩底（15% 染白）+墨字 | 88% 染白浅底+1.5px 彩描边+彩字（暗色深底亮字）；hover/选中反转实心+反色字 | gs-01~04 截图目验 |
| A3 边标签 | snake_case 英文沿边旋转全显 | 中文映射（predicate-zh.ts，relation_types.key 全量 11 键）+默认隐藏；hover 边/钉住事实时卡头下浮层显示该边中文标签（供货给 实测）；"边样式"弹层内"显示全部标签"开关（默认关） | edgeChip=供货给、switchOn=true |
| A4 工具栏 | 三截 ToolTower | 一座 ToolTower + 两枚 ToolDivider 分组（按钮零增删） | 模板 diff |
| A5 布局收敛中 | 裸文本 | primary-soft 底+spinner 旋转+文案的 tag，v-if 语义不变 | stabilizingTag=true（强刷 4s 窗口抓拍） |
| A6 GitHub | 顶栏"源码地址"入口 | 移除（RuoYiGit import+模板标签；组件本体保留） | githubGone=true |
| A7 时间轴 | 双"2026" | 单标签 | single2026=1 |

## 二、双"2026"根因

`maxYear.value` 是 **number 2026**、`label.value` 是 **string "2026"**——严格不等比较失效（两端都渲染）。修法：`String(maxYear.value) !== label.value` 归一后再比较。

## 三、主题切换画布重上妆（规格外必要补齐）

底色填充后暴露既有局限：主题切换不经过 refreshPalette/sigma 重绘——本批的灰底会放大成"暗色下刺眼亮灰"。补：graph 页 watch settingsStore.isDark → nextTick（**先等一拍**：useDark 的 html.dark 类 post-flush 才落 DOM，立即取令牌会拿到旧主题值——probe 实证）→ refreshPalette + sigma.refresh + 网格层重铺。实测：亮→暗切换后画布像素 26,26,26 ✓。

## 四、边标签显示机制择形

sigma 的 renderEdgeLabels 是全局闸（per-edge forceLabel 在全局关时不生效——zoom 联动时代的 deepZoom forceLabel 随之失效移除）。故 hover/钉住单边显示走 **HTML 浮层**（edgeChip computed：边中点 graphToViewport 定位、camTick 随相机 updated 自增驱动重算、内容 zhEdge(label)）；"显示全部标签"开关直接驱动 renderEdgeLabels。两者互斥（开关开时浮层退场）。

## 五、回归

- pnpm typecheck ✓ / pnpm test 425/425（58 文件）✓ / build:prod ✓
- smoke-p0 ALL PASS(20) / smoke-p1 ALL PASS(67+1SKIP) ✓；跑毕 captcha 复位 true ✓
- 压测库按新纪律复种 170 ✓（smoke-p1 teardown 级联清掉是已文档化行为，警示行已入库）
- 走查 console 零 error（gs-results.json）

## 六、deviation

1. 净高：本批未动卡头行结构（+16px 回补保持上批口径）；七项均为画布/控件视觉层。
2. 边语义图例弹层内新增 el-divider+checkbox（规格"弹层内加开关"落地形态）。
3. 本体页共用绘制层（graphVisuals）——本体 schema-graph 同步获得灰底点阵/节点新样式（家族一致，规格允许），本体页抽验不破。
4. T1 子代理配额中断 → 单任务内联执行（本报告首行注明），T2/T3 合并实施+提交为单 commit。
5. 已知遗留：主题切换前的旧主题妆问题（前及画布外元素）不在本批；谓词映射需新增关系类型时同步补表（文件头注已注）。

## 七、截图

gs-01（1280 亮）/gs-02（1280 暗）/gs-03（1920 亮）/gs-04（1920 暗）/gs-05（hover 边 chip 供货给）/gs-06（开关开全量中文标签）/gs-07（工具塔归组）/gs-08（暗态工具塔）——docs/superpowers/plans/graph-style/。
