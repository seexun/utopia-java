# P3 golden 复活批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** golden 复活落地——rust 权威面 288 快照补拷、引擎增若依 setup 模式（~40 行）、22 剧本机械适配+auth 族处置、全量对表产出四类计数、验证入口写进 README。

**架构：** 三任务：T1 引擎（ScenarioRunner+Scenario model 增 ruoyi-login setup 模式+假 200 识别）+rust 数据拷入+三族复验；T2 剧本全量适配（setup 换 mode+auth 处置+多用户 sql 预建）；T3 全量录制对表+README 验证入口+回归汇总。引擎改动向后兼容（mode 缺省走旧 register 路径）。

**技术栈：** jknow-contract 引擎（Cli/Recorder/ScenarioRunner/Diff）、SnakeYAML 剧本模型、mvn exec、curl。

**规格：** `docs/superpowers/specs/2026-11-23-golden-revival-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：后端 27401（演示数据在）/前端 24110；起法读 README（JKNOW_PG_PORT=15432 SERVER_PORT=27401 JKNOW_JOBS_WORKER=true JKNOW_DATA_DIR=/d/workspace_com/jknow/data java -jar jknow-admin/target/jknow-admin.jar）。
- mvn test 纪律：跑全仓 mvn test 前停常驻后端（README 已入档）。
- 源仓：/d/workspace_com/utopia-java/utopia-contract/（golden-rust 288 快照）。
- **Recorder/ Cli 用法**：`mvn -pl jknow-contract exec:java -Dexec.mainClass=tech.seexun.jknow.contract.Recorder -Dexec.args="--target http://localhost:27401 --scenario scenarios/xxx.yaml --out <dir>"`（Recorder 头注原形）；`--single` 单打。Diff：`--left/--right` 或 `--dual <base>`（展开 <base>-rust/<base>-java）。**sql 步 env**：`UTOPIA_JDBC_URL=jdbc:postgresql://localhost:15432/jknow UTOPIA_DB_USERNAME=postgres UTOPIA_DB_PASSWORD=postgres`。
- 永不提交：jknow-web/auto-imports.d.ts、jknow-web/vite.config.ts、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md、spike-out/、实录工作目录。
- 分支：`feat/golden-revival`（自 master 切出）。

---

### 任务 1：rust 权威面补拷 + 引擎若依 setup 模式 + 三族复验（§1）

**文件：**
- 创建：`jknow-contract/src/test/resources/golden-rust/`（源仓 288 快照整目录拷入）
- 修改：`jknow-contract/src/main/java/tech/seexun/jknow/contract/model/Scenario.java`（setup 可选 mode 字段）
- 修改：`jknow-contract/src/main/java/tech/seexun/jknow/contract/ScenarioRunner.java`（setup() 分支+默认 Bearer 注入+假 200 识别）
- 创建：`jknow-contract/src/test/resources/scenarios-spike/`（三族适配剧本，验证用；不入库见步骤 6）

- [ ] **步骤 1：rust 权威面拷入（单独 commit）**

```bash
cp -r /d/workspace_com/utopia-java/utopia-contract/golden-rust jknow-contract/src/test/resources/golden-rust
find jknow-contract/src/test/resources/golden-rust -name "*.json" | wc -l   # 预期 288
cd /d/workspace_com/jknow && git add jknow-contract/src/test/resources/golden-rust
git commit -m "test(contract): golden-rust 权威对照面 288 快照随仓（P3 复活批——对表形态升级 rust 权威 vs jknow 实录）"
```

- [ ] **步骤 2：Scenario model 增 setup.mode（TDD：先写模型测试或随步骤 4 引擎测试一并红绿）**

Scenario record 增可选字段（SnakeYAML 缺省 null 即向后兼容）：

```java
public record Scenario(String name, String desc, Setup setup, Map<String, String> vars, List<Step> steps) {
    /** setup 块两种模式：register（缺省，源仓语义）/ ruoyi-login（P3 新增：若依 /login 捕 token）。 */
    public enum SetupMode { register, ruoyi_login }
    ...
}
```

（Scenario 现 record 字段是 `RegisterSetup setup`——改为新的 `Setup` record：`record Setup(Mode mode, RegisterSetup register, String username, String password)`，mode 缺省 REGISTER。yaml 解析面（loadScenario）同步适配。**以现文件实况为准最小改**——若 SnakeYAML 直接绑 record，增字段即可。）

- [ ] **步骤 3：ScenarioRunner setup() 增分支（引擎 ~40 行）**

setup()（:427）分支：

```java
if (reg != null && scenario.setup().mode() == Mode.RUOYI_LOGIN) {
    String base = dual() ? ("java".equals(writesSide) ? java : rust) : single;
    Resp login = send(base, "POST", "/login",
            mapper.writeValueAsString(Map.of("username", user, "password", pass)),
            null, null);
    // 假 200 识别：若依 renderString 恒 200，code!=200 即业务失败
    if (login.status() != 200 || !loginJson.path("code").asInt(200) == 200) {  // 以解析实况写
        throw new ScenarioFailure("剧本 " + scenario.name() + ": ruoyi setup 失败 " + login.body());
    }
    vars.put("token", loginJson.path("data").path("token").asText());
    return "bearer:" + vars.get("token");   // 会话标识走 Bearer（与 cookie 路径区分）
}
```

后续步请求组装处：会话标识以 `bearer:` 前缀区分 → 注入 `Authorization: Bearer <token>` 头（替代 set-cookie 携带）；步级 headers 声明仍可覆盖（现有合并逻辑不动）。假 200 识别同时作用于普通步 expect 校验前（body code ∉{200,0} 即步失败，报错带 msg——仅 ruoyi-login 剧本启用，用 scenario.setup().mode() 判断）。
（以上为骨架——以 ScenarioRunner 现结构最小侵入落地，cookie 路径零触碰。）

- [ ] **步骤 4：三族复验（引擎验收）**

重建三族适配剧本（spike-out 已删，按 spike 报告 §1 手法重建 graph/ontology/auth-login 三 yaml：setup 换 ruoyi-login+删步级 headers 注入改引擎默认）：Recorder 实录→Diff 对表 golden-java → 预期 11 一致+2 退役定位（与 spike 数字一致）。`mvn -pl jknow-contract test` 47/2S。

- [ ] **步骤 5：全仓回归（停常驻后端纪律）**

taskkill :27401/:27499 → 清卷（down -v→up→一次性后端建 schema→停）→ `mvn test` 全仓（admin 971 基线+contract 47，预期全绿）。

- [ ] **步骤 6：Commit（剧本适配工作产物不入库）**

```bash
git add jknow-contract/src/main/java/tech/seexun/jknow/contract/
git commit -m "feat(contract): 引擎增 ruoyi-login setup 模式——POST /login 捕 token+Bearer 注入+假 200 识别（register 旧路径向后兼容）"
```

三族适配剧本为验证性工作产物：如复用价值高可拷入 scenarios-spike/ 单独 commit 标注；否则留盘不入库（报告记录选择）。

---

### 任务 2：22 剧本适配 + auth 族处置 + 多用户预建（§2）

**文件：**
- 修改：`jknow-contract/src/test/resources/scenarios/*.yaml`（21 个带 setup 的剧本；health 原样）
- 修改：`jknow-contract/src/test/resources/scenarios/COVERAGE.md`（auth-login login/me 退役记录）
- 修改：`jknow-contract/src/test/resources/scenarios/WAIVERS.md`（如走豁免路径）

- [ ] **步骤 1：19+heart 剧本机械适配**

非 auth 两剧本的全部 yaml（graph/ontology/mappings/review/search/documents/admin-users/alerts/chat-misc/sources-ingest/workspace-kb + heart-* 5 = 16 个带 setup 的）：setup 块统一改：

```yaml
setup:
  mode: ruoyi-login
  username: admin
  password: admin123
```

（原 register/ensure 块删除；ensure 里有第二用户语义的剧本[graph viewer-*、review 等]走步骤 2 预建装置改造。）

- [ ] **步骤 2：多用户步预建装置（先单步验证）**

先挑 graph 的 viewer-* 一例实测：剧本 sql 步预建第二用户（sys_user 固定 uuid+明文密码 bcrypt 串复用 admin 的+sys_user_role 绑 role 2+`utopia_uuid` 列值=新建业务 uuid）→ 该步以第二用户 token（sql 后插一个 ruoyi-login 步？或 sql 步生成后 capture）**以实测定形**——若引擎单 token 会话模型不支撑第二身份，该 5 步走 Waivers 归档（规格开放点预案），报告记录。
（bcrypt 串来源：`docker exec jknow-pg-1 psql -U postgres -d jknow -tc "SELECT password FROM sys_user WHERE user_name='admin';"` 原样复用。）

- [ ] **步骤 3：auth.yaml 401 族处置**

7 步逐个择小：(a) expect 改若依实形（`expect: { status: 200 }` + body code:401 掩码断言——以引擎 expect 能力实况为准，若 expect 仅校验状态码则该步 expect 改 200、body 断言走 waivers 注记）；(b) WAIVERS.md 豁免。预期 (a) 为主。
auth-login 的 login/me 2 步删除 + COVERAGE.md 记退役（/api/v1/auth/* jknow 无路由，源仓设计性退役）。

- [ ] **步骤 4：三族+新适配剧本实录验证**

对改造后的 auth.yaml/auth-login.yaml/graph/ontology 四族实录→对表：auth 族按处置结果全绿或豁免在案；graph/ontology 与任务 1 结论一致。`mvn -pl jknow-contract test` 47/2S。

- [ ] **步骤 5：Commit**

```bash
git add jknow-contract/src/test/resources/scenarios/
git commit -m "test(contract): 22 剧本 ruoyi-login 适配——setup 换 mode+auth 401 族实形处置+login/me 退役记录+多用户预建"
```

---

### 任务 3：全量对表 + README 验证入口 + 回归汇总（§3）

- [ ] **步骤 1：全 22 族实录+对表**

起后端（27401 演示数据态）→ 逐族 Recorder 实录（env 带 UTOPIA_JDBC_URL 等 sql 步连接串）→ Diff 对表（rust 权威 vs jknow 实录；Cli `--left golden-rust/<族> --right <实录>/<族>` 或实况形态）。产出四类计数表（一致/失配/退役/豁免）全 22 族，失配逐例归因（预期残面：health version 0.1.0→1.0.0——Normalizer 掩码扩展[SnapshotNormalizer]或剧本 expect 修正，择小改并记录）。

- [ ] **步骤 2：处置分支落地**

- 非 auth 族失配 ≈0 → 复活成立：README 验证节增「golden 契约对表」小节（命令序列：起后端→Recorder 全量→Diff；注明非 CI 门、人工验证面、env 连接串）。
- 失配超预期 → 失配族逐个 Waivers 归档。

- [ ] **步骤 3：全量回归**

停常驻后端→清卷口径全仓 mvn test（admin 971+contract 47+其余模块，预期全绿）→ 重建演示环境（seed+ES_RECONCILE=1 重启）→ 双 smoke → 前端 425。

- [ ] **步骤 4：报告与证据归档**

`docs/superpowers/plans/golden-revival/report.md`：四类计数表（22 族）、失配归因表、处置分支落地记录、多用户预建实测结论、README 入口摘录、deviation 清单。commit `docs(plan): golden-revival 证据汇总报告`。
