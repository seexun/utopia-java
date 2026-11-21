# 剧本语法说明（SCENARIOS，冻结口径）

> 契约剧本 = 一个 YAML（`utopia-contract/scenarios/<域>.yaml`）= 一条顺序调用链。
> 引擎：`ScenarioRunner`（Recorder/Replayer 共用）；本文是语法的唯一权威口径（M1 任务1 冻结）。
> 覆盖矩阵见 `COVERAGE.md`；挂账清单见 `WAIVERS.md`。

## 1. 剧本骨架（name/desc/setup/vars/steps）

```yaml
name: my-domain            # 必填，黄金目录名（非法字符转 _）
desc: 一句话说明            # 可选
setup:                      # 可选前置（响应不进黄金样本）；整体视为 target: rust（写路径归 Rust）
  register: { email: contract-x@test.local, password: Xx!2026, display_name: Contract X }  # 主账号：注册拿会话，409 转登录
  ensure:                   # 附加账号（只保证存在，不切换会话；供第二用户 403/越权用例）
    - { email: b@test.local, password: Bb!20260101 }
vars:                       # 静态变量
  note: 'Alice works at Acme.'
steps:                      # 至少一步
  - id: login               # 步骤 id = 黄金文件名
    call: 'POST /api/v1/auth/login { "email": "${register.email}", "password": "${register.password}" }'
    capture: { cookie: set-cookie, token: body.token }
    expect: { status: 200 }
```

变量命名空间扁平：`register.email/register.password` + `vars` 原样 + capture 声明名；
`${名字}` 引用，引用未定义变量直接失败（剧本写错早暴露）。setup 至少 register/ensure 之一；
只有 ensure 时无会话开跑（前几步天然 401 无 cookie），login 步骤再建立会话。

## 2. call 行

`METHOD 路径[?查询] [JSON对象体]`——方法大写；JSON 体以「与路径隔开一个空格的独立 `{`」起
（路径里的 `${var}` 也带花括号，不能按首个 `{` 切）。**YAML 里带 JSON 体的 call 行必须单引号包裹**
（plain 标量不能含「冒号+空格」），无体行可裸写。

## 3. capture（两种源，读未规范化原文）

- `cookie: set-cookie`——会话 cookie，捕获后后续步骤自动携带（唯一合法名 cookie）；
- `名字: body.点路径`——从响应体取值存变量（拿到真实 id/token，掩码只作用于黄金样本）；
  路径段为数字时按数组下标取（`body.created.0.id`），负数支持倒数（`-1` 末元素）。

## 4. expect

`expect: { status: <code>}`——owner 侧（见 §6 target）状态码不符即失败，Recorder 中止该剧本
（黄金样本必须是绿的）。

## 5. 三类步骤附件（均先做 ${var} 替换）

| 附件 | 语法 | 说明 |
|---|---|---|
| headers | `headers: { Authorization: 'Bearer ${api_token}' }` | 附加请求头。Bearer 令牌端点（/kbs/{id}/mcp、/sources/{sid}/ingest）不认 cookie 只能走这里 |
| upload | `upload: { filename: note.txt, content: '${note}', mime: text/plain }` | multipart 文件上传（唯一非 JSON 体）；字段 field 缺省 files；call 行只写方法与路径 |
| pause_ms | `pause_ms: 800` | 发请求前等待毫秒——给后台摄取/抽取留落库时间，稳定列表步骤的响应形状 |

## 6. target 定向（M1 双打，rust \| java \| both，缺省 both）

```yaml
- id: kb-create
  call: 'POST /api/v1/workspaces/${ws_id}/kbs { "name": "kb-x" }'
  target: rust          # 写路径：建工作区/库/上传/推送等一切有副作用的步骤
- id: overview
  call: GET /api/v1/kbs/${kb_id}/graph/overview   # 读步骤缺省 both：双打各录一份
```

- **共享库裁定**（m1-recon §5）：Rust 与 Java 两服务连同一 PostgreSQL。setup 块与
  `target: rust` 步骤打 Rust（写路径归 Rust）；`target: both` 读步骤对两服务各发一次同请求、
  各录一份（`golden-rust/` 与 `golden-java/`）；`target: java` 留给 Java 独有端点（M1 暂无）。
- **owner 侧**：rust/java 步骤的所属侧、both 步骤的 Rust 侧——expect 校验与 capture 只取
  owner 侧响应（写路径权威；两服务同库，id 天然一致）。both 步骤的 Java 侧**如实记录不校验**
  （未实现端点记 404 也是数据），对错由 Diff 比对两目录裁决。
- **仅双打模式生效**：单打（`--target`）忽略定向，全部步骤打同一服务（M0 黄金重录语义）。
- 黄金 `meta.target` 记录声明值；Diff 见 target=rust/java 的样本自动按单侧跳过（不是差异）。

## 7. waiver 挂账

环境敏感/已知缺陷步骤经 `scenarios/WAIVERS.md`（或 `--waive 键#原因码` 临时）挂账：
Diff/Replayer 跳过比对并单列（不算差异也不算通过）。首批四条（PAT 棘轮×2、users-list、
D9 doc-content）的成因与核销条件见 WAIVERS.md。挂账步骤在 Rust 单打录制/自比对中
仍正常参与（Rust 侧自身是稳定的），仅跨侧比对与漂移敏感的回放豁免。

## 8. 命令速查（mvn -pl utopia-contract compile exec:java，Diff/Replayer 加 -Dexec.mainClass）

```bash
# 单打录制（黄金重录，M0 语义：忽略 target，全部打 --target）
-Dexec.args="--target http://127.0.0.1:1516 --scenario scenarios/health.yaml --out golden/"

# 双打录制（M1：setup/rust 步→Rust，both 步两打两录 → golden-rust/ 与 golden-java/）
-Dexec.args="--rust http://127.0.0.1:1516 --java http://127.0.0.1:17001 --scenario scenarios/auth-login.yaml --out golden"

# 双打比对（--dual 展开 base-rust/base-java 两目录；--left/--right 自比对仍可用）
-Dexec.mainClass=tech.seexun.utopia.contract.Diff -Dexec.args="--dual golden --waivers scenarios/WAIVERS.md"
-Dexec.mainClass=tech.seexun.utopia.contract.Diff -Dexec.args="--left golden/ --right target/golden-check/ --waivers scenarios/WAIVERS.md"

# 回放（对单服务逐步比黄金；挂账单列）
-Dexec.mainClass=tech.seexun.utopia.contract.Replayer \
  -Dexec.args="--target http://127.0.0.1:1516 --scenario scenarios/auth-login.yaml --golden golden/ --waivers scenarios/WAIVERS.md"
```

退出码纪律：Recorder 失败剧本未落盘→1；Diff 有差异→1（挂账/单侧跳过不影响）；
Replayer 有 FAIL→1（WAIVE 不算 FAIL 也不算 PASS）。黄金目录布局：
`<out>/<剧本>/<步骤>.json`（双打再分 `-rust`/`-java` 后缀目录），每份含
`meta{scenario,step,method,path,target,expectedStatus,status}` + `normalized`（规范化响应树）。
