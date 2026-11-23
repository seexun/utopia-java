package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.contract.model.Scenario;
import tech.seexun.jknow.contract.model.Setup;
import tech.seexun.jknow.contract.model.Step;
import tech.seexun.jknow.contract.model.Target;

/** 剧本模型/装载/call 行解析（变量捕获/替换、cookie、expect 是全部剧本的骨架，必须有单测兜底）。 */
class ScenarioLoaderTests {

    private static final String YAML = """
            name: kbs-core
            desc: 建库→图谱→404
            setup:
              register: { email: contract0@test.local, password: Contract0!2026 }
            vars:
              ws_id: 01a1b4dc-b012-78e2-b4ff-22d91f94ea2b
            steps:
              - id: login
                call: 'POST /api/v1/auth/login { "email": "${register.email}", "password": "${register.password}" }'
                capture: { cookie: set-cookie }
                expect: { status: 200 }
              - id: create-kb
                call: 'POST /api/v1/workspaces/${ws_id}/kbs { "name": "contract-golden" }'
                capture: { kb_id: body.id }
                expect: { status: 201 }
              - id: graph-404
                call: GET /api/v1/kbs/00000000-0000-0000-0000-000000000000/graph/overview?limit=150
                expect: { status: 404 }
            """;

    @Test void 装载YAML到模型() {
        Scenario scenario = ScenarioLoader.fromYamlString(YAML);
        assertEquals("kbs-core", scenario.name());
        assertEquals("contract0@test.local", scenario.setup().register().email());
        assertEquals("Contract", scenario.setup().register().displayNameOrDefault());
        assertEquals(Setup.Mode.REGISTER, scenario.setup().modeOrDefault());
        assertEquals("01a1b4dc-b012-78e2-b4ff-22d91f94ea2b", scenario.vars().get("ws_id"));
        assertEquals(3, scenario.steps().size());
        assertNotNull(scenario.steps().get(0).capture());
        assertEquals(Integer.valueOf(201), scenario.steps().get(1).expectStatus());
    }

    @Test void call行解析出方法路径与JSON体() {
        Scenario scenario = ScenarioLoader.fromYamlString(YAML);
        Step.ParsedCall login = scenario.steps().get(0).parsedCall();
        assertEquals("POST", login.method());
        assertEquals("/api/v1/auth/login", login.path());
        assertEquals("{ \"email\": \"${register.email}\", \"password\": \"${register.password}\" }", login.body());

        Step.ParsedCall probe = scenario.steps().get(2).parsedCall();
        assertEquals("GET", probe.method());
        assertEquals("/api/v1/kbs/00000000-0000-0000-0000-000000000000/graph/overview?limit=150", probe.path());
        assertEquals(null, probe.body());
    }

    /** 任务4 修正回归：路径里的 ${var} 不是 JSON 体的起点，不能被「首个 {」切走。 */
    @Test void 路径中的变量花括号不误判为体() {
        Step step = new Step("get", "GET /api/v1/workspaces/${ws_id}", null, null, null, null, null);
        Step.ParsedCall call = step.parsedCall();
        assertEquals("/api/v1/workspaces/${ws_id}", call.path());
        assertEquals(null, call.body());

        Step withBody = new Step("put", "PUT /api/v1/kbs/${kb_id}/members/${user_id} { \"role\": \"viewer\" }",
                null, null, null, null, null);
        Step.ParsedCall parsed = withBody.parsedCall();
        assertEquals("/api/v1/kbs/${kb_id}/members/${user_id}", parsed.path());
        assertEquals("{ \"role\": \"viewer\" }", parsed.body());
    }

    @Test void 缺name或缺steps报可定位错误() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("steps:\n  - id: a\n    call: GET /health"));
        assertEquals("<string>: 缺必填字段 name", e1.getMessage());

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("name: x\ndesc: 无步骤"));
        assertEquals("<string>: steps 为空，剧本至少一步", e2.getMessage());
    }

    /** 任务4 扩展：ensure-only setup（无会话开局）+ 步骤级 headers/upload/pause_ms。 */
    @Test void 装载ensure账号与步骤附件() {
        Scenario scenario = ScenarioLoader.fromYamlString("""
                name: two-users
                setup:
                  ensure:
                    - { email: b@test.local, password: Bb!20260101, display_name: Bee }
                steps:
                  - id: sweep-401
                    call: GET /api/v1/auth/me
                    expect: { status: 401 }
                  - id: push
                    call: POST /api/v1/sources/00000000-0000-0000-0000-000000000000/ingest
                    headers: { Authorization: 'Bearer ${push_token}' }
                  - id: upload
                    call: POST /api/v1/kbs/00000000-0000-0000-0000-000000000000/documents
                    upload: { filename: note.txt, content: 'Alice works at Acme.', mime: text/plain }
                    pause_ms: 500
                """);
        assertEquals(null, scenario.setup());
        assertEquals(1, scenario.ensure().size());
        assertEquals("b@test.local", scenario.ensure().get(0).email());
        assertEquals("Bee", scenario.ensure().get(0).displayNameOrDefault());
        Step push = scenario.steps().get(1);
        assertEquals("Bearer ${push_token}", push.headers().get("Authorization"));
        Step upload = scenario.steps().get(2);
        assertEquals("note.txt", upload.upload().get("filename"));
        assertEquals("Alice works at Acme.", upload.upload().get("content"));
        assertEquals(Long.valueOf(500), upload.pauseMs());
    }

    @Test void setup缺register与ensure报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("name: x\nsetup: {}\nsteps:\n  - id: a\n    call: GET /health"));
        assertEquals("<string>: setup 至少需要 register 或 ensure 之一", e.getMessage());
    }

    /** M1 任务1 扩展：步骤级 target 定向（rust|java|both，缺省 both；非法值可定位报错）。 */
    @Test void 装载步骤级target定向() {
        Scenario scenario = ScenarioLoader.fromYamlString("""
                name: dual
                steps:
                  - id: login
                    call: 'POST /api/v1/auth/login {}'
                    target: rust
                  - id: me
                    call: GET /api/v1/auth/me
                    target: BOTH
                  - id: list
                    call: GET /api/v1/users
                """);
        assertEquals(Target.RUST, scenario.steps().get(0).targetOrDefault());
        assertEquals(Target.BOTH, scenario.steps().get(1).targetOrDefault());
        assertEquals(Target.BOTH, scenario.steps().get(2).targetOrDefault()); // 缺省 both
        Step unannotated = new Step("x", "GET /health", null, null, null, null, null);
        assertEquals(Target.BOTH, unannotated.targetOrDefault()); // 旧构造兼容
    }

    @Test void target非法值报可定位错误() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("name: x\nsteps:\n  - id: a\n    call: GET /health\n    target: go"));
        assertEquals("<string>: steps[0].target: target 必须是 rust|java|both，实际是: go", e.getMessage());
    }

    /** M3 任务 1/11：snapshot 步骤装载（kb_id 模板 + tables 覆盖；无 call 行、无 target——
     * 任务 1 审查 M-2 收口：快照步不接受 target（定向是 HTTP 概念，写了是死配置，装载器拒收））。 */
    @Test void 装载快照步骤() {
        Scenario scenario = ScenarioLoader.fromYamlString("""
                name: heart
                steps:
                  - id: ledger-snapshot
                    snapshot: { kb_id: '${kb_id}' }
                  - id: focused-snapshot
                    snapshot:
                      kb_id: '${kb_id}'
                      tables: [facts, entities]
                """);
        Step full = scenario.steps().get(0);
        assertEquals(null, full.call());
        assertEquals("${kb_id}", full.snapshot().kbId());
        assertEquals(null, full.snapshot().tables()); // 缺省表集
        assertEquals(Target.BOTH, full.targetOrDefault());
        Step focused = scenario.steps().get(1);
        assertEquals(List.of("facts", "entities"), focused.snapshot().tables());
    }

    /** 任务 1 审查 M-2 收口（任务 11 落地）：非 HTTP 步（snapshot/sql/wait/store）拒绝 target。 */
    @Test void 非HTTP步骤拒绝target死配置() {
        IllegalArgumentException snapshot = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                            snapshot: { kb_id: '${kb_id}' }
                            target: rust
                        """));
        assertEquals("<string>: steps[0] snapshot 步不接受 target"
                + "（定向是 HTTP 概念；快照/store 跟随写路径侧，sql/wait 与侧无关）", snapshot.getMessage());

        IllegalArgumentException sql = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                            sql: { statements: ['SELECT 1'] }
                            target: both
                        """));
        assertEquals("<string>: steps[0] sql 步不接受 target"
                + "（定向是 HTTP 概念；快照/store 跟随写路径侧，sql/wait 与侧无关）", sql.getMessage());
    }

    /** M3 任务 11：sql/wait/store 三类装置步装载。 */
    @Test void 装载sql与wait与store步骤() {
        Scenario scenario = ScenarioLoader.fromYamlString("""
                name: fixtures
                steps:
                  - id: fixture
                    sql:
                      wait_quiet: '${kb_id}'
                      statements:
                        - 'INSERT INTO t VALUES (''${x}'') RETURNING id AS ws_id'
                        - 'SELECT 1'
                      capture: { ws_id: ws_id }
                  - id: settle
                    wait: { kb_id: '${kb_id}' }
                  - id: decide
                    store:
                      op: decide_review
                      args: { kb_id: '${kb_id}', action: merge }
                """);
        Step fixture = scenario.steps().get(0);
        assertEquals(2, fixture.sql().statements().size());
        assertEquals("${kb_id}", fixture.sql().waitQuiet());
        assertEquals("ws_id", fixture.sql().capture().get("ws_id"));
        Step settle = scenario.steps().get(1);
        assertEquals("${kb_id}", settle.waitSpec().kbId());
        Step decide = scenario.steps().get(2);
        assertEquals("decide_review", decide.store().op());
        assertEquals("merge", decide.store().args().get("action"));
    }

    /** sql.statements 缺失/空列表是剧本写错，早暴露。 */
    @Test void sql步骤缺语句报可定位错误() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                            sql: {}
                        """));
        assertEquals("<string>: steps[0].sql.statements 必填（非空列表）", missing.getMessage());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                            sql: { statements: [] }
                        """));
        assertEquals("<string>: steps[0].sql.statements 必须是非空列表", empty.getMessage());
    }

    /** call 与任一非 HTTP 载荷互斥 / 六者皆缺，剧本写错早暴露；非 HTTP 载荷彼此互斥。 */
    @Test void call与非HTTP载荷互斥且必居其一() {
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                            call: GET /health
                            snapshot: { kb_id: '${kb_id}' }
                        """));
        assertEquals("<string>: steps[0] call 与 snapshot 互斥（一步只能是其一）", both.getMessage());

        IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                        """));
        assertEquals("<string>: steps[0] 缺必填字段 call（或 snapshot/sql/wait/store）", neither.getMessage());

        IllegalArgumentException twoPayloads = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        steps:
                          - id: a
                            wait: { kb_id: '${kb_id}' }
                            store: { op: decide_review }
                        """));
            assertEquals("<string>: steps[0] wait/store 互斥（一步只能是其一）", twoPayloads.getMessage());
    }

    /** P3 golden 复活批：ruoyi_login setup 模式（若依 /login 捕 token，Bearer 会话）。 */
    @Test void 装载ruoyiLoginSetup模式() {
        Scenario scenario = ScenarioLoader.fromYamlString("""
                name: ruoyi
                setup:
                  mode: ruoyi_login
                  username: admin
                  password: admin123
                steps:
                  - id: graph-404
                    call: GET /api/v1/kbs/00000000-0000-0000-0000-000000000000/graph/overview
                    expect: { status: 404 }
                """);
        assertEquals(Setup.Mode.RUOYI_LOGIN, scenario.setup().modeOrDefault());
        assertEquals(true, scenario.setup().ruoyiLogin());
        assertEquals("admin", scenario.setup().username());
        assertEquals("admin123", scenario.setup().password());
        assertEquals(null, scenario.setup().register());
    }

    /** mode 缺省 REGISTER：旧剧本无 mode 键即源仓注册流，向后兼容。 */
    @Test void setup无mode键缺省register() {
        Scenario scenario = ScenarioLoader.fromYamlString(YAML);
        assertEquals(Setup.Mode.REGISTER, scenario.setup().modeOrDefault());
        assertEquals(false, scenario.setup().ruoyiLogin());
    }

    /** ruoyi_login 缺 username/password 是剧本写错，早暴露。 */
    @Test void ruoyiLogin缺username或password报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        setup:
                          mode: ruoyi_login
                          username: admin
                        steps:
                          - id: a
                            call: GET /health
                        """));
        assertEquals("<string>: setup.mode=ruoyi_login 需要 username 与 password", e.getMessage());
    }

    /** ruoyi_login 混写 register/ensure（源仓注册流装置）是剧本写错，早暴露。 */
    @Test void ruoyiLogin混写register与ensure报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        setup:
                          mode: ruoyi_login
                          username: admin
                          password: admin123
                          register: { email: a@test.local, password: Bb!20260101 }
                        steps:
                          - id: a
                            call: GET /health
                        """));
        assertEquals("<string>: setup.mode=ruoyi_login 不接受 register/ensure（那是源仓注册流装置）",
                e.getMessage());
    }

    /** setup.mode 非法值可定位报错。 */
    @Test void setupMode非法值报可定位错误() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScenarioLoader.fromYamlString("""
                        name: x
                        setup:
                          mode: login
                          username: admin
                          password: admin123
                        steps:
                          - id: a
                            call: GET /health
                        """));
        assertEquals("<string>: setup.mode 必须是 register|ruoyi_login，实际是: login", e.getMessage());
    }
}
