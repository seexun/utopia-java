# P3 golden 复活批规格（rust 权威面补拷+引擎若依 setup 适配+剧本机械适配+全量对表）

- 日期：2026-11-23
- 状态：已获用户批准（四节设计 OK）
- 背景：测试基建批 spike 判定"值得复活"（11/13 一致、失配 100% 单根因）；P2 错误形状批已清障（404 对齐+msg-code 形状）。本批实施复活：对表形态升级为「源 rust 权威 vs jknow 实录」，golden 进入常规验证面。

## 侦察结论（规格依据）

1. 源仓 `utopia-contract/golden-rust` 288 快照（权威对照面，未随迁）+ `golden-java` 253（已随仓）；heart-java/rust 为 heartbeat 工装另一用途域。
2. jknow-contract 引擎 2968 行；ScenarioRunner 718 行——setup（注册即会话，409 转登录）→ cookie 携带 → 双打/单打形态齐；`--single` 单打模式现成。
3. spike 实证适配手法（不入库的 spike-out 已验证）：删 setup + 首步 `POST /login` 捕 token + 步级 headers `Authorization: Bearer ${var}` 注入（引擎 `${var}` 替换与 capture 现成，ScenarioRunner:521-545）。
4. 22 剧本：health 无 setup；21 个带 setup.register 块；auth.yaml 401 族 7 步 + auth-login 的 login/me 2 步为 auth 域退役步。
5. P2 后形状：已认证未知路由 404 {"error":"Not found"}（零适配）；未认证仍 200+code:401（若依入口点）；业务异常 200+{msg,code:500}。
6. 字段演进已知面：/api/v1/health version "0.1.0"→"1.0.0"（1 处）。

## 设计

### §1 权威对照面补拷 + 引擎适配（任务 1）

- **golden-rust 拷入**：`/d/workspace_com/utopia-java/utopia-contract/golden-rust/`（288 快照）→ `jknow-contract/src/test/resources/golden-rust/`（与 golden-java 平级；单独 commit 标注数据面）。
- **引擎适配（ScenarioRunner，预估 ~40 行）**：
  1. **若依 setup 模式**：剧本 setup 块新字段 `mode: ruoyi-login`（+ `username`/`password` 变量，默认 admin/admin123）→ 引擎 `POST /login {"username":...,"password":...}` → 响应 `data.token`（若依 AjaxResult 包装）capture 进 vars["token"] → 后续步默认 headers 注入 `Authorization: Bearer ${token}`。原 `setup.register`（409 转登录）路径原样保留（向后兼容，mode 字段缺省走旧路径）。
  2. **假 200 识别**：expect 状态校验前增——若响应 body JSON 含 `code` 字段且值 ∉ {200, 0}，判该步失败（报错含 body msg）。仅对 `mode: ruoyi-login` 剧本启用（register 旧剧本不感知）。
  3. cookie 携带逻辑不动（register 老路径仍走 cookie；ruoyi-login 路径走 Bearer——两模式互斥清晰分离）。
- **验收（任务 1 内）**：引擎改造后以 spike 的三族适配剧本（graph/ontology/auth-login 重建版）实录→Diff，11+2 步全部复现 spike 结论（11 一致+2 退役定位）；`mvn -pl jknow-contract test` 47/2S 不回归。

### §2 19+1 剧本适配 + auth 族处置（任务 2）

- **机械适配（graph/ontology/mappings/review/search/documents/health 之外全部非 heart 剧本）**：setup 块删 `register` 换 `mode: ruoyi-login`；步级无变量硬编码核对（`${kb_id}` 等引用链保持）。health 无 setup 原样。
- **heart-* 5 剧本**：随迁适配同法（heart 族属源仓心跳自检工装；实录后按结果择保留/归档——低价值面不强制全绿）。
- **auth.yaml 401 族（7 步）**：逐步择小——(a) expect 改为若依实形（200 + code:401，body msg 掩码）；(b) Waivers.java 豁免挂账。预期 (a) 为主（形状已知），(b) 兜底。
- **auth-login 的 login/me（2 步）**：删除（/api/v1/auth/* 在 jknow 无路由，源仓设计性退役）——剧本内移除步 + COVERAGE.md 记退役原因。
- **多用户步预建**：graph viewer-* 等 5 步需第二用户——剧本 sql 步预建 `sys_user`+`sys_user_role`+`utopia_uuid` 绑定（sql 步工具现成；uuid 固定幂等）。

### §3 全量对表 + 常规化（任务 3）

- **对表形态**：22 剧本逐一 Recorder 实录 jknow（27401）→ `Diff --dual golden-rust golden-java-relocated/实录`（或 CLI 实际形态：rust 权威 vs jknow 实录单侧比对；以 Cli.java 参数实况为准，实施时读源定形）。
- **产出数字**：一致/失配/退役/豁免 四类计数全 22 族；失配逐例归因（预期残面：health version 字段演进 1 处——Normalizer 掩码扩展或剧本 expect 修正，择小）。
- **处置分支**：
  - 非 auth 族失配率 ≈0（spike 外推）→ **复活成立**：验证入口写进 README 验证节（命令序列：起后端 → Recorder 全量 → Diff 对表；非 CI 门，人工验证面）。
  - 失配超预期 → 失配族逐个 Waivers 归档，报告记录残余面。
- **总验收**：对表数字表入报告；contract 门控 47/2S；全仓 mvn test（停常驻后端纪律+清卷口径）0 fail；双 smoke 零退化；前端 425（本批零前端改动，防意外）。

## 不做的事

不做 golden-heart-*（heartbeat 工装另用途域，仅 heart-* 剧本随迁适配）；不动后端产品码（P2 已就位；401 包装形状差异由剧本 expect 层吸收——这是与 utopia 形状的有意分层，不改造若依入口点）；不接 CI 门（人工验证面写 README）；不做 msg-code/401 形状的后端对齐改造。

## 风险与已知开放点

- 多用户步的 utopia_uuid 绑定若 seed/登录链不闭环（sys_user 建行后 token 鉴权路径），spike 已证 sql 预建可行但未实测第二用户 token 面——任务 2 实施时先单步验证 viewer-* 一例，不闭环则该 5 步 Waivers 归档（不阻塞复活主体）。
- Diff CLI 的双面参数形态以源码实况为准（spike 用过单侧比对形态）。
