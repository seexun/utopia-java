# 收尾清账批规格（UI 零头+EP 暗态主色+login footer 对比度+SSE 竞态固化+/terms 关闭）

- 日期：2026-11-21
- 状态：已获用户批准（五节设计 OK）
- 背景：UI 挂账清偿批（1364383）已合并；本批清偿账本剩余全部小项（A 组 UI 零头 + D 组小加固），此后账本仅剩两个独立大项：vendor-utils 拆包（性能批）与测试基建批（golden/pipeline/热身 SQL），各自单独立项。

## 设计

### §1 UI 零头清扫（并入任务 1）

| 项 | 位置 | 处置 |
|---|---|---|
| 分页条白底 | `jknow-web/src/components/Pagination/index.vue:100` | `background: #fff` → `var(--color-card)`（暗态白条缺陷，既有遗留非上批引入） |
| 搜索命中红 | `jknow-web/src/components/HeaderSearch/index.vue:254/:277` | `color: red` → `var(--color-danger)`；:209 `"color": "#fff"` 为 activeStyle 主色底反白，**保留** |
| body[layout] 死代码 | `jknow-web/src/style/utopia.scss:99/:137` 两块 | 删除——全仓 grep 无任何 `setAttribute("layout")`/`dataset.layout` 挂点（本规格侦察已证）；删前走查 vertical 布局无回归、删后复走查 |
| hljs 八色豁免 | 本规格附录 | 文档化永久记录（见附录 A） |

### §2 EP 壳暗态主色完整度（实测驱动，任务 2）

- 现状：tokens.scss :root 已有 `--el-color-primary: var(--color-primary)`（:52）+ 静态色阶 light-3/5/7/8（:54-57）；注释声明 html.dark 下 `--el-*` 族由 element-plus `dark/css-vars.css` 接管。
- 风险：EP dark css-vars 的暗档色阶是按默认 #409eff 编译的静态值——若接管优先级压过项目静态色阶，暗态下 EP 组件 hover/disabled/选中态回默认蓝族。
- 处置（实测驱动，不预设结论）：
  1. 暗态 Playwright 探针：EP 主按钮 hover 色、表格选中行色、分页器 active 色、禁用按钮色的 computed `--el-color-primary-light-N` 解析值。
  2. **回默认蓝** → tokens.scss `html.dark` 块补 `--el-color-primary-dark-2/light-3/5/7/8` 暗档 5 值（#2563EB 混黑公式：light-N = 主色×(1-N/10)+黑×N/10；dark-2 = 主色×0.8+黑×0.2）+ 探针复测。
  3. **未回蓝** → 报告记录"已审无洞"，该项关闭。

### §3 login footer 对比度收口（并入任务 1）

- `jknow-web/src/views/login.vue` `.el-login-footer`：ink-3 #9ca3af on #f5f7fa ≈ 2.6:1（WCAG AA 4.5:1 不达）→ 改 `var(--color-ink-2)` #6b7280 ≈ 4.8:1。装饰性版权行从严处理，一行改动。register 同位选择器（`.el-register-footer`，上批已用 ink-3）**一并**对齐 ink-2。

### §4 SSE 竞态测试固化（审计驱动，任务 3）

- 审计结论：eventStream.ts（139 行）通读——yield-gate（`if (!controller && retry === null) return`）、abort 传播（finally 守卫 `controller === current`）、visibility 让出三窗口逻辑闭环，无显性洞。
- 处置：新增 `jknow-web/tests/eventStream.spec.ts`，mock `fetch`/`AbortController`/`visibilitychange` 驱动三类窗口用例：
  1. **重连窗口 close**：流断后排程 retry 期间调用返回的 dispose 函数——retry 被 clear，不再重建连接。
  2. **release 与 connect 交错**：hidden 满 HIDDEN_RELEASE_MS 让出（controller/retry 均清、missed 打标）→ visible 重连成功触发 onRecover 补刷。
  3. **卸载后迟到帧**：dispose 后 reader 才吐帧——dispatch 不再触达 handlers（迟到帧不作数契约）。
  4. **open 重入闸**：controller 占位期间再 open() 不开第二条连接。
- **测出真洞则修（同任务内）**；无洞则测试即交付——SSE 竞态账本项以"已审计+已固化"关闭。生产逻辑零预设改动。

### §5 /terms whitelist 核实关闭（并入任务 1）

- 侦察：jknow 后端（jknow-admin/jknow-system 全 Java 面）无 `/terms` 端点——utopia 的法务内容已前端静态化（jknow-web/src/views/jknow/knowledge/legal 页，polish-harden 批重造）；SecurityConfig permitAll 清单（/login,/register,/captchaImage + @Anonymous 动态收集）无需加行。
- 处置：任务 1 内复核一遍（grep 后端 + 前端路由白名单）后在报告记录"该项失效关闭"，零代码改动。

## 验收

1. grep 归零：`LC_ALL=C grep -n -a -E "color: red|#fff" jknow-web/src/components/Pagination/index.vue jknow-web/src/components/HeaderSearch/index.vue` 仅剩 :209 反白保留项；`body[layout` 全仓零输出。
2. 走查截图：vertical 布局侧栏（body[layout] 删除前后各一）、暗态 EP 组件探针记录（§2 无论结论）、login+register footer 特写；console 零 error。
3. `pnpm test` 基线 421 + eventStream 新用例全绿 + typecheck + build:prod；双 smoke 零退化。
4. §4 若修洞：修复 diff + 新用例复绿；§5 关闭记录在报告。

## 不做的事

- vendor-utils 拆包（1.2MB echarts）与测试基建（golden 复活/pipeline 7 测试/清卷热身 SQL）——留独立批次立项。
- SSE 生产逻辑零预设改动（仅测试暴露真洞时最小修复）。
- hljs 八色、gvdesign/ 零改区维持。

---

## 附录 A：hljs 八色豁免记录（永久）

代码高亮（hljs）主题使用专属八色配色（token 色：关键字/字符串/数字/注释/函数/变量/标签/属性各一色），属**语法语义色**而非 UI 主题色——与 SaaS Light 令牌体系解耦是设计意图：

- 位置：`jknow-web/src/style/utopia-pages.css`（hljs 段）及 markdown 渲染面（chat/docs 消费）。
- 豁免理由：语法高亮色板遵循代码编辑器惯例（可比 Monokai/One Dark 家族），令牌化会破坏跨主题可辨识度；暗态下由 hljs 自身的 dark 主题类翻转。
- 永久豁免：任何后续"硬编码色清扫"批次均不得把 hljs 段纳入替换面。
