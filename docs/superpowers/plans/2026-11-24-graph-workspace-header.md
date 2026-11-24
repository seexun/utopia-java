# 图谱页工作区型页头合一 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 图谱页取消独立页头带，标题降级+kb chip 并入卡头，统计/节点档位从画布浮组迁入卡头右段，画布上移满铺不再被浮条盖顶。

**架构：** 单页模板改造（graph/index.vue）——PageHeader 删除；原 `absolute top-3` 顶部悬浮条改为卡内 static 两行卡头（行1 标题+kb chip / nodeBudget+statsText 右段；行2 搜索+类型图例 / 边语义图例右段）；画布区 flex-1 自然上移。零逻辑改动（全部模板/样式搬移）。

**技术栈：** Vue3 SFC 模板+tailwind 令牌类；独立 Playwright 四截图走查。

**规格：** `docs/superpowers/specs/2026-11-24-graph-workspace-header-design.md`（已获批准）

**侦察事实（实现者必读，防误改）：**
- 页文件：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（1794 行）。
- PageHeader 在 :1259：`<PageHeader :title="S.nav.graph" :subtitle="kb?.name ?? ''" />`。
- 顶部悬浮条 :1265 起：`class="pointer-events-none absolute left-3 right-3 top-3 z-10 flex items-start gap-2"`——**改为 static 卡头行时此 absolute 类整组移除**，容器改卡内常排（card 内首个 border-b 区块）。
- 悬浮条右段（:1420 附近 `ml-auto flex flex-col items-end gap-1`）内含三件：①**nodeBudget ± 步进器**（:1486-1523，控制画多少节点——**功能控件，不是缩放，不可删**；控制设计评审时误称"缩放 +/-"）+ statsText（`{{ statsText }}` "18 个实体·24 条事实·23 条现行"）；②stabilizing 提示；③边语义图例卡（:1528-1567，已是 `bg-card/90` 半透明）。
- 左下 ToolTower×2（:1600-1685）：形态切换组（pin/orbit/圈选/pack）+ 缩放组（zoom in/out/fit）——**缩放本来就只在左下，保留全部**；两组统一容器间距即可。
- 类型图例 chips（人物/公司/…/指标）在悬浮条左段搜索框旁（:1290 附近）。
- kb 名：`kb?.name`（已有变量）；statsText/stats computed 已存在；nodeBudget/NODE_BUDGETS/capped 已存在。
- 空态/错误行 `absolute top-20` 等画布内定位不改（画布区参照系不变）。

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：后端 27401/前端 24110 在跑；登录 admin/admin123（验证码算术题人读后填）。图谱页直达 `http://localhost:24110/kb/0190d3e0-0000-7d5a-8000-000000000001/graph`。
- 走查纪律：独立 Playwright（Node+playwright 库）+ DOM 断言先行 + 截图存 docs/superpowers/plans/graph-header/。
- 永不提交：jknow-web/auto-imports.d.ts、jknow-web/vite.config.ts（本地 baseUrl=27401 环境态——**若 git status 显示它 M，不要 add**）、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md。
- 分支：`feat/graph-workspace-header`（自 master 切出）。

---

### 任务 1：卡头合一改造 + 四截图走查（§1+§2+§3）

**文件：**
- 修改：`jknow-web/src/views/jknow/knowledge/graph/index.vue`（模板 :1255-1575 区域 + scoped 样式如需）

- [ ] **步骤 1：删页头带，悬浮条改两行 static 卡头**

- 删除 :1259 `<PageHeader ... />` 及其注释行；PageHeader import（:105）一并删（若无其它引用）。
- 原 `absolute left-3 right-3 top-3 z-10` 悬浮条容器改为 static 两行卡头（放在 `.card.graph-canvas-card` 内首个子块，`border-b border-line` 收底）：

```html
<!-- 卡头（工作区型合一，2026-11-24 批）：行1 标题+kb chip / 节点档位+统计；行2 搜索+类型图例 / 边语义图例 -->
<div class="border-b border-line px-3 pb-2 pt-2">
  <div class="flex items-center gap-2">
    <h1 class="text-body font-semibold text-ink">图谱</h1>
    <span class="rounded-full border border-line bg-fill px-2 py-0.5 text-aux text-ink-2">{{ kb?.name }}</span>
    <div class="ml-auto flex items-center gap-2">
      <!-- nodeBudget ± 步进器整体从原浮组迁入（含 disabled 逻辑原样） -->
      …原步进器按钮对…
      <span class="tabular-nums text-aux text-ink-2">{{ statsText }}</span>
    </div>
  </div>
  <div class="mt-2 flex items-start gap-2">
    <!-- 搜索框（w-58）+ 建议框 原样迁入（建议框 absolute 定位以本行为参照不变） -->
    <!-- 类型图例 chips 原样迁入，允许 flex-wrap -->
    <div class="ml-auto flex flex-wrap items-center justify-end gap-x-4 gap-y-1 text-aux text-ink-3">
      <!-- 边语义图例五项原样迁入；去掉外框浮卡样式改无底 inline（半透明底不再需要——不在画布上了） -->
    </div>
  </div>
</div>
```

（类名/间距可按 tailwind 令牌微调；锚点约束：①`pointer-events-none` 整组语义删除——卡头是真实 UI 不再透点；②stabilizing 提示迁到行1 stats 旁或行2 尾，任选并在报告记录；③画布容器 `min-h-0 flex-1` 不动。）

- [ ] **步骤 2：左下工具组间距统一**

两个 ToolTower 的外层容器间距统一（如包一层 `flex flex-col gap-2 items-start` 或对齐既有 gap 令牌），视觉成一组；不删任何按钮。

- [ ] **步骤 3：typecheck + 单测 + build**

`cd jknow-web && pnpm typecheck && pnpm test && pnpm build:prod`（425 基线零退化；本批纯模板）。

- [ ] **步骤 4：四截图走查（1280/1920 × 亮/暗）**

dev 环境（已在跑）独立 Playwright：登录 → 直达图谱页 → DOM 断言（①页面无独立页头带元素；②卡头含"图谱"标题+kb 名 chip+statsText 三段；③画布容器顶部 y 坐标 < 改造前基准[上移成立]；④nodeBudget ± 与边图例五项在卡头区内）→ 1280 亮 `gh-01-1280-light.png` → 1280 暗（Navbar 月亮切换）`gh-02-1280-dark.png` → 1920×1080 视口亮/暗各一 `gh-03/04`。交互抽验：搜索"星辰"出建议框、nodeBudget −/＋ 各点一次（画布节点数变化）、缩放 +/− 生效、时间轴可见。console 零 error。

- [ ] **步骤 5：Commit**

```bash
git add jknow-web/src/views/jknow/knowledge/graph/index.vue
git commit -m "style(web): 图谱页工作区型页头合一——标题/kb chip/统计/节点档位并入卡头两行，画布上移满铺（方案 A）"
```

---

### 任务 2：回归与证据汇总

- [ ] **步骤 1：全量回归**

`cd jknow-web && pnpm test && pnpm typecheck`（425 基线）；双 smoke：`JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` + smoke-p1.sh（零退化）。

- [ ] **步骤 2：报告与归档**

`docs/superpowers/plans/graph-header/report.md`：四截图对照（改前基线可取 git stash 或直接引用用户走查截图描述）、DOM 断言记录、交互抽验结果、deviation 清单。commit `docs(plan): graph-header 证据汇总报告`（截图一并 add）。
