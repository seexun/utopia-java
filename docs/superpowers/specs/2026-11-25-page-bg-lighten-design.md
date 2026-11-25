# 页面底色提亮规格（方案 B：页灰 #F5F7FA → #FCFCFD，全站灰边减淡）

- 日期：2026-11-25
- 状态：已获用户批准（A/B/C 三方案量化呈现，用户选 B）
- 背景：图谱画布回归纯白后，用户指出白卡外圈的页面灰留白带仍然显眼。实测页灰/白卡对比 1.073；提亮到 `#FCFCFD`（1.025）后灰边近乎隐形，同时保留「灰底白卡」的层次语义（hover 填充 `#F1F5F9` 落在页灰上 1.068，仍可辨——quantified）。

## 设计

- `--color-bg` 亮档 `#F5F7FA` → `#FCFCFD`（tokens.scss :root + tailwind.css 双源）；`html.dark` `#1A1A1A` **不动**（用户反馈仅针对亮色）。
- `--el-bg-color-page`（tokens.scss :113 → var(--color-bg)）自动跟随。
- 连带同步：`utopia-pages.css:275/:284` 纸底色环 rgba(245,247,250,.95) → rgba(252,252,253,.95)；`ruoyi.scss:321` 分隔线 #f5f7fa → #fcfcfd（消除散落的旧值）。
- `graphVisuals` 摊平纸底 `LIGHT_GROUND_RGB`（255,255,255）不受影响——那是画布内（已回归纯白），页灰只在卡片外。

## 验收

页面灰像素实测 `252,252,253`；白卡/页灰层次仍可辨（截图目验）；425 测试+typecheck+build 绿；无散落 f5f7fa 旧值（grep 归零，豁免：graphVisuals.ts 注释、test 存档注释）。

## 不做的事

暗色主题不动；neutral-soft 不动（hover 层对比 1.068 保留）；不动卡片留白宽度（p-6）。
