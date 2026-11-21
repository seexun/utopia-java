# 收尾清账批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 清偿账本剩余全部小项——UI 零头清扫（Pagination/HeaderSearch/body[layout] 死代码）、EP 壳暗态主色实测、login footer 对比度、SSE 竞态测试固化、/terms whitelist 项核实关闭。

**架构：** 三任务小批：T1 机械清扫+核实关闭（6 处点位零散改）；T2 实测驱动的暗态探针（不预设结论，回蓝才补令牌）；T3 TDD 测试固化（先写用例，测出洞才修生产码）。

**技术栈：** Vue3 + SCSS + Element Plus；vitest（vi.stubGlobal mock fetch + fake timers）；Playwright 暗态探针。

**规格：** `docs/superpowers/specs/2026-11-21-final-ledger-sweep-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`，前端 `jknow-web/`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：端口读 `/tmp/jknow-ports.txt`（后端 27401 / 前端 24110）；未起则按 README 恢复。走查纪律：独立 Playwright 实例 + DOM 断言先行。
- 永不提交：`jknow-web/auto-imports.d.ts`、`jknow-web/vite.config.ts`、`tests/__snapshots__/*.snap`、`.playwright-cli/`、`jknow-web/src/docs/*.md`。
- 分支：`feat/final-ledger-sweep`（自 master 切出，master 已含本批规格 commit）。

---

### 任务 1：UI 零头清扫 + footer 对比度 + /terms 关闭（§1+§3+§5）

**文件：**
- 修改：`jknow-web/src/components/Pagination/index.vue:100`
- 修改：`jknow-web/src/components/HeaderSearch/index.vue:254,:277`
- 修改：`jknow-web/src/style/utopia.scss:98-110 与 :137 起的第二块（范围见步骤 3）`
- 修改：`jknow-web/src/views/login.vue`（.el-login-footer 的 color 行）
- 修改：`jknow-web/src/views/register.vue`（.el-register-footer 的 color 行）

- [ ] **步骤 1：分页条与搜索红清偿**

- `Pagination/index.vue:100` `background: #fff;` → `background: var(--color-card);`
- `HeaderSearch/index.vue:254` `color: red;` → `color: var(--color-danger);`
- `HeaderSearch/index.vue:277` `color: red;` → `color: var(--color-danger);`
- **不动**：HeaderSearch:209 `"color": "#fff"`（activeStyle 主色底反白，规格明示保留）。

- [ ] **步骤 2：走查 vertical 布局留存基线（删除前证据）**

dev 环境独立 Playwright：登录 → DOM 断言侧栏 `.el-menu-item` 存在且 `getComputedStyle` 高 `44px`、`.sidebar-container` 宽 `210px` → 截图 `docs/superpowers/plans/final-ledger-sweep/t1-01-before-removal.png`。（此步证明 body[layout="vertical"] 块当前不命中——命中则菜单 36px/侧栏 240px。）

- [ ] **步骤 3：删除 body[layout="vertical"] 两块死代码**

utopia.scss 中**精确框定**两块（全仓无 `setAttribute("layout")`/`dataset.layout` 挂点，规格侦察已证；步骤 2 走查再证不命中）：
- 前块：`:98` 注释起（`/* 侧栏宽度对齐旧壳 240px…`）至 `:110` `}` 止（含 `body[layout="vertical"] .app-wrapper.openSidebar` 整块）。
- 后块：`:135` 注释起（`/* 菜单条目（国内中后台紧凑形态）…`）至该 `body[layout="vertical"] { ... }` 顶层块的闭合 `}` 止（实施时通读块体确认结束行——块内含 .el-menu/.el-menu-item 等 nested 规则）。
- **中间活代码严禁误删**：`.logo-mark`（:116）与 `.logo-name`（:126）是品牌字标（侧栏 Logo/故障页共用），保留。
- 删除后跑 `LC_ALL=C grep -rn -a "body\[layout" jknow-web/src/` 预期零输出。

- [ ] **步骤 4：footer 对比度（login+register 双改）**

login.vue `.el-login-footer` 块内 color 行（约 :230，当前 `var(--color-ink-3)`）：改 `var(--color-ink-2)`；register.vue `.el-register-footer` 同款对齐 `var(--color-ink-2)`。若现值不是 ink-3（以实读为准），一律改为 ink-2 并在报告记录前值。

- [ ] **步骤 5：/terms 核实关闭（零代码）**

复核并记录：`LC_ALL=C grep -rn -a "terms" jknow-admin/src/main/java jknow-system/src/main/java --include="*.java" | grep -vi "TermsUtil"`（预期无端点）；`LC_ALL=C grep -rn "legal\|terms" jknow-web/src/router/`（legal 页路由挂知识业务面）。结论"后端无 /terms 端点，法务内容已前端静态化，SecurityConfig 无需加行"写入报告。

- [ ] **步骤 6：验证与走查**

- `cd jknow-web && pnpm typecheck && pnpm test`（421 基线零退化）。
- 复走查（对照步骤 2 基线）：侧栏 44px/210px 不变 → 截图 `t1-02-after-removal.png`；任一带分页的列表页（如 系统管理→用户）分页条亮态白卡正常；login 页 footer 特写 `t1-03-footer.png`。console 零 error。

- [ ] **步骤 7：Commit**

```bash
git add jknow-web/src/components/Pagination/index.vue jknow-web/src/components/HeaderSearch/index.vue jknow-web/src/style/utopia.scss jknow-web/src/views/login.vue jknow-web/src/views/register.vue
git commit -m "style(web): 收尾清账——分页/搜索命中令牌化+body[layout] 死代码删除+footer 对比度 4.8:1"
```

---

### 任务 2：EP 壳暗态主色完整度（实测驱动，§2）

**文件：**
- 修改（仅当实测回蓝）：`jknow-web/src/style/tokens.scss`（html.dark 块补 5 个 el 色阶暗档）

- [ ] **步骤 1：暗态探针实测（先取证）**

dev 环境独立 Playwright：登录 → 切暗态（Navbar 太阳/月按钮，html 落 .dark）→ 对以下 EP 组件逐个取 computed 值：
1. 主按钮 hover：`getComputedStyle(btn).backgroundColor`（hover 前后）。
2. 表格选中行/多选框激活色。
3. 分页器 active 页码色。
4. 禁用按钮色。
5. 根变量直读：`getComputedStyle(document.documentElement).getPropertyValue('--el-color-primary-light-3')` 等五档（light-3/5/7/8/dark-2）。
判定：解析值属 #2563EB 族（如 #6692f1/#92b1f5）= 链路正确；属 #409eff 族（如 #79bbff/#a0cfff）= **回默认蓝**。全部记录进报告（含截图 `t2-01-dark-ep.png` 暗态全局）。

- [ ] **步骤 2：按判定分支处置**

- **未回蓝** → 报告记"已审无洞，该项关闭"，任务结束（跳到步骤 4）。
- **回蓝** → tokens.scss `html.dark` 块内补（#2563EB 混黑公式：light-N = 主色×(1-N/10)+黑×N/10；dark-2 = ×0.8+黑×0.2）：

```scss
  /* EP 壳暗态主色完整度（final-ledger-sweep §2）：EP dark/css-vars 的暗档色阶按
     默认 #409eff 编译，html.dark 下压回默认蓝族——按 #2563EB(37,99,235) 混黑重算接管
     （light-N = 主色×(1-N/10)+黑×N/10，round 到整数；dark-2 = ×0.8+黑×0.2） */
  --el-color-primary-dark-2: #1e4fbc;  /* 30,79,188 */
  --el-color-primary-light-3: #1a45a5; /* 26,69,165 */
  --el-color-primary-light-5: #133276; /* 19,50,118 */
  --el-color-primary-light-7: #0b1e47; /* 11,30,71 */
  --el-color-primary-light-8: #07142f; /* 7,20,47 */
```

（注：以上为公式精确计算值，可直抄；仅当实测显示 EP dark 接管的档位集合不同（如还有 light-9 等）才按同公式补算增档，报告记录。）

- [ ] **步骤 3：（仅回蓝分支）复测**

探针复跑步骤 1 的五档直读 + 组件抽查，全部落 #2563EB 族 → 记录前后对比进报告；`pnpm typecheck && pnpm test`。

- [ ] **步骤 4：Commit（仅回蓝分支有 commit）**

```bash
git add jknow-web/src/style/tokens.scss
git commit -m "fix(web): EP 暗态色阶接管——#2563EB 混黑重算 html.dark 五档（回蓝实测驱动）"
```

未回蓝分支：无 commit，报告即交付物。

---

### 任务 3：SSE 竞态测试固化（TDD，§4）

**文件：**
- 创建：`jknow-web/tests/eventStream.spec.ts`
- 修改（仅当用例暴露真洞）：`jknow-web/src/domain/eventStream.ts`

- [ ] **步骤 1：编写四窗口用例（先写测试）**

```ts
// tests/eventStream.spec.ts —— SSE 竞态窗口固化（final-ledger-sweep §4）
// 审计结论先行：eventStream 三窗口逻辑闭环无显性洞；本套用例把契约钉死：
// 测出洞 → 同任务修；无洞 → 测试即交付。
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

const { subscribeEvents, RECONNECT_MS, HIDDEN_RELEASE_MS } = await import("@/domain/eventStream");

vi.mock("@/domain/api", () => ({ bearerHeaders: () => ({ Authorization: "Bearer t" }) }));

/** 可控的 SSE 假流：手动吐帧/断流 */
function fakeStream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
  return {
    res: { ok: true, status: 200, body: stream },
    push: (text: string) => controller.enqueue(new TextEncoder().encode(text)),
    drop: () => controller.close(),
  };
}

describe("subscribeEvents 竞态窗口", () => {
  let streams: ReturnType<typeof fakeStream>[];
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    streams = [];
    fetchMock = vi.fn(async () => { const s = fakeStream(); streams.push(s); return s.res as unknown as Response; });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  /** 推进到 fetch 落定 + 微任务排空（connect 的 await 全走完） */
  const settle = () => vi.advanceTimersByTimeAsync(0);

  it("卸载后迟到帧不作数：dispose 后 reader 吐帧不触达 handlers", async () => {
    const onEvent = vi.fn();
    const dispose = subscribeEvents("/sse", { tick: onEvent });
    await settle();
    expect(streams.length).toBe(1);
    dispose();
    streams[0].push("event: tick\ndata: {}\n\n");
    await settle();
    expect(onEvent).not.toHaveBeenCalled();
  });

  it("重连窗口 close：流断排程 retry 期间 dispose，不再重建连接", async () => {
    const dispose = subscribeEvents("/sse", {});
    await settle();
    streams[0].drop();               // 服务端断流 → 排程 retry
    await settle();
    dispose();                        // retry 计时器挂着时卸载
    await vi.advanceTimersByTimeAsync(RECONNECT_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(1);  // 没有第二条连接
  });

  it("release 与重连交错：hidden 让出→visible 重连成功触发 onRecover 补刷", async () => {
    const onRecover = vi.fn();
    subscribeEvents("/sse", {}, onRecover);
    await settle();
    // 藏起来满 HIDDEN_RELEASE_MS：让出连接 + missed 打标
    document.dispatchEvent(new Event("visibilitychange"));
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(HIDDEN_RELEASE_MS + 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);  // 连接已让出（fetch 未再调）
    // 回前台：重建连接，missed=true → onRecover 补刷一次
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onRecover).toHaveBeenCalledTimes(1);
  });

  it("open 重入闸：controller 占位期间 visible 抖动不开第二条连接", async () => {
    subscribeEvents("/sse", {});
    await settle();
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    document.dispatchEvent(new Event("visibilitychange"));  // 立刻再触发
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
```

（实施注意：①`visibilityState` 的 spy 要在 dispatch 前挂好——onVisibility 读的是事件时刻的快照；②若 jsdom 的 ReadableStream/TextEncoder 缺位，按 vitest 环境实况补 polyfill 或以最小 stub 替代并在报告记录；③用例骨架允许按真实行为微调断言时序，但四窗口语义不得放宽。）

- [ ] **步骤 2：跑用例验证**

运行：`cd jknow-web && pnpm vitest run tests/eventStream.spec.ts`
预期：四用例通过（审计结论无洞）。**若失败**——失败窗口即真洞：最小修复 eventStream.ts（不重构），修复说明写进报告（洞的时序复现+修法+用例转绿）。

- [ ] **步骤 3：全量回归**

`cd jknow-web && pnpm test`（421 基线+新 4 用例 = 425 预期）+ `pnpm typecheck`。

- [ ] **步骤 4：Commit**

```bash
git add jknow-web/tests/eventStream.spec.ts jknow-web/src/domain/eventStream.ts
git commit -m "test(web): SSE 竞态四窗口用例固化——卸载迟到帧/重连窗口 close/release 交错/open 重入闸"
```

（eventStream.ts 仅在步骤 2 测出洞时才会有改动入列；无洞则该文件不在 add 清单。）

---

### 任务 4：全量回归与证据汇总（分支终审前置）

- [ ] **步骤 1：全量验证链**

```bash
cd jknow-web && pnpm test && pnpm typecheck && pnpm build:prod
cd /d/workspace_com/jknow && JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh && JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p1.sh
```

- [ ] **步骤 2：证据归档与报告**

截图（t1-01/02/03、t2-01 及 §2 探针记录）存 `docs/superpowers/plans/final-ledger-sweep/`；报告 `report.md`：任务 1 六点位前后值、任务 2 探针五档前后对比+分支结论（回蓝补档/已审无洞）、任务 3 四用例结果（+真洞修复记录如有）、§5 关闭记录、deviation 清单。
