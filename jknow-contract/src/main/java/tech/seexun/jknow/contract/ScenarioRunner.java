package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.seexun.jknow.contract.model.RegisterSetup;
import tech.seexun.jknow.contract.model.Scenario;
import tech.seexun.jknow.contract.model.Setup;
import tech.seexun.jknow.contract.model.Step;
import tech.seexun.jknow.contract.model.Target;

/**
 * 剧本引擎：Recorder（录制）与 Replayer（回放）共用的执行器。
 * 职责：setup（注册即会话，409 转登录）→ 逐步变量替换 → 发请求（会话 cookie 自动携带）
 * → 捕获（cookie / body.*）→ expect 校验（不符抛 {@link ScenarioFailure}，中止该剧本）。
 *
 * <p>M1 双打（m1-recon §5 共享库裁定）：{@code new ScenarioRunner(rustUrl, javaUrl)} 双基址构造。
 * setup/ensure 整体视为 target: rust（账号注册/登录是写路径，归 Rust）；每步按声明定向
 * {@code target: rust|java|both}（缺省 both）分侧执行：
 * <ul>
 * <li>rust/java 步骤只打所属服务，expect 校验与 capture 取该侧响应；</li>
 * <li>both 步骤对两服务各发一次同请求：Rust 侧是契约权威——expect 校验、capture（写路径权威，
 * 两服务共享同一 PostgreSQL，id 天然一致）都取 Rust 响应；Java 侧<b>如实记录</b>（不校验 expect），
 * 对错交给 Diff 两目录比对裁决——这样单个未实现端点不会中止录制，双打报告一次跑全。</li>
 * </ul>
 * 请求异常（服务不可达）任何一侧都中止该剧本（基础设施故障不是契约数据）。
 * 单打构造 {@code new ScenarioRunner(url)} 保留 M0 语义：忽略定向，全部步骤打同一服务。
 *
 * <p>M3 任务 1 两项扩展：
 * <ul>
 * <li><b>写路径重定向</b>（{@code new ScenarioRunner(rustUrl, javaUrl, writesSide)}，
 * writesSide="rust" 与两参构造完全同义）：把 setup/ensure 与 {@code target: rust} 步骤整体
 * 重定向到指定实例——「同一剧本写路径在 Rust 跑一遍、在 Java 跑一遍」（各自新建 kb 固定装置），
 * 配合快照步比对两侧的库内终态（锁 4）。重定向步的 expect/capture 取重定向侧（写路径权威
 * 跟着写侧走），meta.target 记实际落侧。both 步语义不变（仍双发、Rust 侧权威）。</li>
 * <li><b>快照步骤</b>（{@code snapshot:}，见 {@link tech.seexun.jknow.contract.model.SnapshotSpec}）：
 * 不发生 HTTP——先静默等待该 kb 的后台 job，再直连共享 PG dump 终态，归一化后作为步骤载荷；
 * 跟随写路径侧归属（默认 rust 侧；--writes java 时记 java 侧）。</li>
 * </ul>
 *
 * <p>M3 任务 11 扩展（心脏剧本的装置与裁决通道）：
 * <ul>
 * <li><b>重定向显式化</b>：缺省双打（两参构造）与 {@code --writes rust} 在「写步打谁」上
 * 同义，但快照步只在<b>显式重定向轮</b>（Recorder {@code --writes rust|java}，四参构造）
 * 执行——缺省双打一轮写的是 Rust 路径，此刻 dump 出的「java 侧」必然缺席（Diff 报缺证据），
 * 在该模式下跳过快照步（留注释不留假样本）；快照证据来自两轮 --writes 各自的树。</li>
 * <li><b>sql/wait 步</b>：直连共享 PG 的装置与静默等待（见 {@link tech.seexun.jknow.contract.model.SqlSpec}
 * / {@link tech.seexun.jknow.contract.model.WaitSpec}）。与侧无关（两轮同一串 SQL），
 * 不产黄金样本。</li>
 * <li><b>store 步</b>：按写路径侧执行 store 层操作（见 {@link tech.seexun.jknow.contract.model.StoreSpec}）
 * ——rust 轮映射 Rust HTTP 等价端点；java 轮经 {@link StoreBridge} 直调 utopia-store
 * 的 Spring bean（M4 前 Java 侧唯一通道）。落常量样本，真实对错由快照裁决。</li>
 * </ul>
 *
 * <p>P3 golden 复活批（jknow 对表）：setup 增 ruoyi_login 模式（{@code setup.mode: ruoyi_login}
 * ——POST /login 捕 token，会话标识 {@code "bearer:<token>"} 走 Authorization 默认注入，
 * cookie 路径零触碰）；ruoyi_login 剧本的 owner 侧普通步增假 200 识别（若依 renderString
 * 恒 HTTP 200，body code ∉ {200,0} 判步失败；修复轮收窄：expect 显式声明非 200 的
 * 预期业务错误步跳过，由 expect 兜底）。
 */
public final class ScenarioRunner {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");
    private static final String JSON = "application/json";
    /** ruoyi_login 会话标识前缀（区别于 cookie 会话；applyHeaders 据此注入 Authorization）。 */
    private static final String BEARER_PREFIX = "bearer:";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** 单打目标；双打时为 null。 */
    private final String single;
    /** 双打两基址；单打时 rust=single 语义、java 为 null。 */
    private final String rust;
    private final String java;
    /** 写路径落侧（"rust"|"java"，双打恒有值；--writes java 重定向 rust 步）。 */
    private final String writesSide;
    /** 显式重定向轮（Recorder --writes rust|java）：快照/store 步的执行前提（任务 11）。 */
    private final boolean writesRedirect;
    /**
     * Rust 参照实例停机（Recorder {@code --rust-down}，M5 任务 6 切流演练）：both 步骤的
     * Rust 侧不执行（实例不在），只跑 Java 侧并按 target=both 记样本——Diff 拿缺省轮的
     * golden-rust both 样本与它比对（跨实现的读面证据仍在），而非把两侧样本写成同轮同侧。
     */
    private final boolean rustDown;

    /** 单打（M0 兼容）：全部步骤（含 setup）打同一服务，忽略步骤定向。 */
    public ScenarioRunner(String target) {
        this.single = trimSlash(target);
        this.rust = null;
        this.java = null;
        this.writesSide = "rust";
        this.writesRedirect = false;
        this.rustDown = false;
    }

    /** 双打（M1 语义）：setup/ensure 与写路径打 rust，读步骤按定向分侧。 */
    public ScenarioRunner(String rustUrl, String javaUrl) {
        this(rustUrl, javaUrl, "rust", false);
    }

    /**
     * 双打 + 写路径重定向（M3 任务 1）：writesSide="java" 时 setup/ensure 与 target:rust
     * 步骤全部打 Java 实例（captures/expect 随之取 Java 侧）；"rust" 与两参构造等价。
     */
    public ScenarioRunner(String rustUrl, String javaUrl, String writesSide) {
        this(rustUrl, javaUrl, writesSide, true);
    }

    /**
     * 全参构造（任务 11）：{@code writesRedirect} 区分「缺省双打」（false——快照步跳过，
     * 该模式一轮只写 Rust 路径，java 侧快照必然缺席，不留假样本）与「显式重定向轮」
     * （true——Recorder --writes rust|java 两轮，快照证据的来源）。
     */
    public ScenarioRunner(String rustUrl, String javaUrl, String writesSide, boolean writesRedirect) {
        this(rustUrl, javaUrl, writesSide, writesRedirect, false);
    }

    /** 五参构造（M5 任务 6）：rustDown=切流演练轮——both 步只跑 Java 侧、记 target=both。 */
    public ScenarioRunner(String rustUrl, String javaUrl, String writesSide, boolean writesRedirect,
                          boolean rustDown) {
        this.single = null;
        this.rust = trimSlash(rustUrl);
        this.java = trimSlash(javaUrl);
        if (!"rust".equals(writesSide) && !"java".equals(writesSide)) {
            throw new IllegalArgumentException("writesSide 必须是 rust|java，实际是: " + writesSide);
        }
        this.writesSide = writesSide;
        this.writesRedirect = writesRedirect;
        this.rustDown = rustDown;
    }

    private static String trimSlash(String url) {
        String t = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return t;
    }

    private boolean dual() {
        return single == null;
    }

    /** 单条 HTTP 响应（状态 + 原文 + 头），set-cookie 从 headers.allValues 取。 */
    record Resp(int status, String body, Map<String, List<String>> headers) {
        String firstSetCookie() {
            return headers().getOrDefault("set-cookie", List.of()).stream().findFirst().orElse(null);
        }
    }

    /**
     * 跑完整个剧本（setup 不进结果）；任何一步失败抛 ScenarioFailure。
     * 双打模式下一步可产出两份结果（both 步骤 rust+java 各一，rust 在前）。
     *
     * <p>预种子 {@code ${run_id}}（M3 任务 1）：本轮录制的唯一后缀（时间戳+随机）——
     * 自包含剧本（如 heart-ledger）用它注册唯一账号、建唯一工作空间/库，每轮固定装置
     * 互不串扰；两侧各自跑各自的一轮，kb/账号 UUID 不同，归一化后仍可比。
     */
    public List<RecordedStep> run(Scenario scenario) {
        return run(scenario, null);
    }

    /**
     * 带钉住的 ${run_id}（Recorder {@code --run-id}，M5 任务 6 切流演练）：两轮（缺省轮
     * 与 java 轮）用同一个后缀时，注册体同邮箱——第二轮 409 转登录，账号与 org 级
     * 列表（users-list）跨轮逐字相同；轮间把该账号的 personal_tokens 清零即可让
     * tokens-list 行数回到 0/1 的钉死值。
     */
    public List<RecordedStep> run(Scenario scenario, String pinnedRunId) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (scenario.vars() != null) {
            vars.putAll(scenario.vars());
        }
        if (pinnedRunId != null) {
            vars.put("run_id", pinnedRunId);
        }
        vars.putIfAbsent("run_id", Long.toUnsignedString(System.currentTimeMillis(), 36) + "-"
                + Integer.toUnsignedString(ThreadLocalRandom.current().nextInt(0x10000), 36));
        String[] cookie = {setup(scenario, vars)};
        List<RecordedStep> out = new ArrayList<>();
        for (Step step : scenario.steps()) {
            runStepRouted(scenario, step, vars, cookie, out);
        }
        return out;
    }

    /** 按定向分侧执行一步，结果按执行序追加（both：rust 侧先，java 侧后）。 */
    private void runStepRouted(Scenario scenario, Step step, Map<String, String> vars,
                               String[] cookie, List<RecordedStep> out) {
        // 等待步：静默等待该 kb 的后台 job（due-or-running 口径），无 HTTP、无样本
        if (step.waitSpec() != null) {
            runWaitStep(scenario, step, vars);
            return;
        }
        // SQL 步：装置与裁决前提（两轮同串），无 HTTP；可选 wait_quiet 前置静默，捕获进 vars
        if (step.sql() != null) {
            runSqlStep(scenario, step, vars);
            return;
        }
        // store 步：按写路径侧执行 store 层操作——rust 轮走 Rust 的 HTTP 等价端点，
        // java 轮经 StoreBridge 直调 bean；落常量样本（真实对错由其后的快照步裁决）
        if (step.store() != null) {
            out.add(runStoreStep(scenario, step, vars, cookie));
            return;
        }
        // 快照步不发生 HTTP：静默等待 + 直连共享 PG dump。side 跟随写路径落侧（决定文件进
        // golden-rust 还是 golden-java）；meta.target 恒记 both——两侧各录各的一份，Diff 恒双侧比对。
        // 只在显式重定向轮执行：缺省双打一轮写的是 Rust 路径，「java 侧快照」必然缺席——
        // 跳过并留注释（Diff 不留缺证据的假差异；快照证据来自 --writes 两轮各自的树）
        if (step.snapshot() != null) {
            if (dual() && !writesRedirect) {
                System.out.println("  [SKIP] " + step.id() + "：快照步只在 --writes 显式重定向轮执行"
                        + "（缺省双打一轮只写 Rust 路径，java 侧快照必然缺席；见 golden-heart 两轮树）");
                return;
            }
            out.add(runSnapshotStep(scenario, step, vars, dual() ? writesSide : "single", Target.BOTH));
            return;
        }
        if (!dual()) {
            // 单打恒记 Target.BOTH（审查 M-3 的有意变化：旧版记声明值，声明 target 的步在
            // 单打自比对时会按 SIDE 跳过——本版参与比对，更严方向；双打/回放语义不变）
            out.add(runStep(scenario, step, vars, cookie, single, "single", true, Target.BOTH));
            return;
        }
        Target target = step.targetOrDefault();
        switch (target) {
            // rust 步是写路径：--writes java 时整体重定向到 Java 实例（expect/capture 随写侧），
            // meta.target 记实际落侧——重定向后该样本应出现在 golden-java（否则 Diff 误报「仅右侧存在」）
            case RUST -> {
                if ("java".equals(writesSide)) {
                    out.add(runStep(scenario, step, vars, cookie, java, "java", true, Target.JAVA));
                } else {
                    out.add(runStep(scenario, step, vars, cookie, rust, "rust", true, Target.RUST));
                }
            }
            case JAVA -> out.add(runStep(scenario, step, vars, cookie, java, "java", true, Target.JAVA));
            case BOTH -> {
                if (rustDown) {
                    // 切流演练轮（--rust-down）：Rust 参照实例停机，both 步只跑 Java 侧；
                    // expect 在 Java 侧校验（它是本轮唯一的执行者），样本仍记 target=both
                    // ——Diff 用缺省轮 golden-rust 的 both 样本作跨实现对照
                    out.add(runStep(scenario, step, vars, cookie, java, "java", true, Target.BOTH));
                } else {
                    // Rust 侧先：契约权威（expect 校验 + capture），失败即中止
                    out.add(runStep(scenario, step, vars, cookie, rust, "rust", true, Target.BOTH));
                    // Java 侧如实记录（不校验 expect）：对错由 Diff 裁决
                    out.add(runStep(scenario, step, vars, cookie, java, "java", false, Target.BOTH));
                }
            }
        }
    }

    /**
     * 执行快照步：${var} 替换 kb_id 模板 → 静默等待（60s/500ms，超时即失败——后台卡死时
     * 终态不成立，诚实失败优于 dump 半加工态）→ dump 归一化终态。
     * method=SNAPSHOT、pathTemplate=kb_id 模板原文（进 meta，跨次稳定）。
     */
    private RecordedStep runSnapshotStep(Scenario scenario, Step step, Map<String, String> vars,
                                         String side, Target recorded) {
        try {
            if (step.pauseMs() != null && step.pauseMs() > 0) {
                Thread.sleep(step.pauseMs());
            }
            UUID kb = UUID.fromString(substitute(scenario, step, step.snapshot().kbId(), vars));
            try (Connection conn = Snapshot.openConnection(null, null, null)) {
                Snapshot.awaitQuiet(conn, kb, Snapshot.QUIET_TIMEOUT_MS, Snapshot.QUIET_INTERVAL_MS);
                JsonNode dump = Snapshot.dump(conn, kb, step.snapshot().tables());
                return new RecordedStep(step.id(), "SNAPSHOT", step.snapshot().kbId(), null, 200,
                        dump, recorded, side, true);
            }
        } catch (ScenarioFailure e) {
            throw e;
        } catch (Exception e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": 快照失败 " + e.getMessage(), e);
        }
    }

    /** 等待步：解析 kb_id 模板 → awaitQuiet（60s 超时，超时即失败——同快照门口径）。 */
    private void runWaitStep(Scenario scenario, Step step, Map<String, String> vars) {
        try {
            UUID kb = UUID.fromString(substitute(scenario, step, step.waitSpec().kbId(), vars));
            try (Connection conn = Snapshot.openConnection(null, null, null)) {
                Snapshot.awaitQuiet(conn, kb, Snapshot.QUIET_TIMEOUT_MS, Snapshot.QUIET_INTERVAL_MS);
            }
        } catch (ScenarioFailure e) {
            throw e;
        } catch (Exception e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": 等待失败 " + e.getMessage(), e);
        }
    }

    /**
     * SQL 步：可选 wait_quiet 静默 → 一事务逐条执行（每条先做 ${var} 替换，前条 capture
     * 对后条可见）→ 捕获（var → 列名，扫每条结果集首行）。失败（SQL 错/超时/捕获缺失）
     * 抛 ScenarioFailure 中止剧本——半截装置比没有装置更糟。
     */
    private void runSqlStep(Scenario scenario, Step step, Map<String, String> vars) {
        tech.seexun.jknow.contract.model.SqlSpec spec = step.sql();
        try (Connection conn = Snapshot.openConnection(null, null, null)) {
            if (spec.waitQuiet() != null) {
                UUID kb = UUID.fromString(substitute(scenario, step, spec.waitQuiet(), vars));
                Snapshot.awaitQuiet(conn, kb, Snapshot.QUIET_TIMEOUT_MS, Snapshot.QUIET_INTERVAL_MS);
            }
            conn.setAutoCommit(false);
            try {
                for (String template : spec.statements()) {
                    String sql = substitute(scenario, step, template, vars);
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        boolean hasRows = ps.execute();
                        if (hasRows && spec.capture() != null && !spec.capture().isEmpty()) {
                            try (ResultSet rs = ps.getResultSet()) {
                                if (rs.next()) {
                                    captureColumns(spec, rs, vars);
                                }
                            }
                        }
                    }
                }
                conn.commit();
            } catch (ScenarioFailure | SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (ScenarioFailure e) {
            throw e;
        } catch (Exception e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": SQL 执行失败 " + e.getMessage(), e);
        }
        // 捕获完整性校验：声明的捕获全部拿到（模板替换在后续步骤引用时会因未定义变量早失败，
        // 但在这里点名更有定位性）
        if (spec.capture() != null) {
            for (String var : spec.capture().keySet()) {
                if (!vars.containsKey(var)) {
                    throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                            + ": SQL 捕获未命中 " + var + "（没有任何结果集首行带该列）");
                }
            }
        }
    }

    /**
     * SQL 结果集首行按 capture 表捕获（var → 列名）。只捕获<b>该结果集里有</b>的列
     * （按 ResultSetMetaData 判定）——capture 表覆盖整步全部语句，单条语句的结果集
     * 只承载其中一部分；全步未命中的捕获由步骤末尾的完整性校验点名。
     */
    private void captureColumns(tech.seexun.jknow.contract.model.SqlSpec spec, ResultSet rs,
                                Map<String, String> vars) throws SQLException {
        java.util.Set<String> columns = new java.util.HashSet<>();
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i));
        }
        for (Map.Entry<String, String> cap : spec.capture().entrySet()) {
            if (!columns.contains(cap.getValue())) {
                continue;
            }
            String value = rs.getString(cap.getValue());
            if (value != null) {
                vars.put(cap.getKey(), value);
            }
        }
    }

    /**
     * store 步：按写路径侧执行——rust 轮映射 Rust 的 HTTP 等价端点（会话 cookie 随 setup），
     * java 轮经 {@link StoreBridge} 直调 utopia-store bean（M4 前 Java 侧唯一通道）。
     * 落常量样本 {"ok": true}：真实对错由其后的快照步裁决，这里只留执行轨迹。
     */
    private RecordedStep runStoreStep(Scenario scenario, Step step, Map<String, String> vars,
                                      String[] cookie) {
        tech.seexun.jknow.contract.model.StoreSpec spec = step.store();
        Map<String, String> args = new LinkedHashMap<>();
        spec.args().forEach((k, v) -> args.put(k, substitute(scenario, step, v, vars)));
        try {
            String base = dual() ? ("java".equals(writesSide) ? java : rust) : single;
            if (base != null && base.equals(java)) {
                StoreBridge.call(spec.op(), args);
            } else {
                storeOverHttp(spec.op(), args, base, cookie[0]);
            }
            // meta.target 记实际落侧（rust 轮=RUST/java 轮=JAVA）：与重定向的 HTTP 写步同一
            // 口径——单侧样本，Diff 按 SIDE 跳过（常量载荷没有可比面，真实对错在快照步）
            String side = dual() ? writesSide : "single";
            return new RecordedStep(step.id(), "STORE", spec.op(), null, 200,
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("ok", true),
                    "java".equals(side) ? Target.JAVA : Target.RUST, side, false);
        } catch (ScenarioFailure e) {
            throw e;
        } catch (Exception e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": store 操作 " + spec.op() + " 失败 " + e.getMessage(), e);
        }
    }

    /** store 步的 Rust 侧映射：op → Rust HTTP 端点（review_routes::decide 的动作与体逐字）。 */
    private void storeOverHttp(String op, Map<String, String> args, String base, String cookie)
            throws IOException, InterruptedException {
        switch (op) {
            case "decide_review" -> {
                String body = "{\"action\":\"" + args.getOrDefault("action", "")
                        + "\",\"rationale\":\"" + args.getOrDefault("rationale", "") + "\"}";
                Resp resp = send(base, "POST", "/api/v1/kbs/" + args.get("kb_id")
                        + "/review/" + args.get("review_id"), body, null, cookie);
                if (resp.status() != 200) {
                    throw new ScenarioFailure("store decide_review HTTP 侧失败 " + resp.status()
                            + ": " + preview(resp.body()));
                }
            }
            default -> throw new ScenarioFailure("不支持的 store 操作（rust 侧无 HTTP 映射）: " + op);
        }
    }

    /**
     * setup（响应不进黄金样本）：
     * register——注册拿会话，409（已注册）转登录；ensure——逐个保证账号存在（同样 409 转登录验证），
     * 不建立会话。只有 ensure 时剧本以无 cookie 开跑（401 无会话用例段），login 步骤再建立会话。
     * 双打下 setup 整体打写路径侧（默认 Rust；--writes java 时打 Java——注册/登录是写路径，
     * 且会话须与后续重定向写步同侧）。register 体支持 ${var}（如 ${run_id} 唯一后缀）。
     *
     * <p>P3 golden 复活批增 ruoyi_login 分支：{@code setup.mode: ruoyi_login} 时 POST /login
     * 捕 token，返回 {@code bearer:<token>} 会话标识（见 {@link #ruoyiLoginSetup}）——cookie 路径零触碰。
     */
    private String setup(Scenario scenario, Map<String, String> vars) {
        Setup block = scenario.setup();
        if (block != null && block.ruoyiLogin()) {
            return ruoyiLoginSetup(scenario, block, vars);
        }
        RegisterSetup reg = block == null ? null : block.register();
        if (reg != null) {
            vars.put("register.email", substitute(scenario, "setup.register", reg.email(), vars));
            vars.put("register.password", substitute(scenario, "setup.register", reg.password(), vars));
        }
        String base = dual() ? ("java".equals(writesSide) ? java : rust) : single;
        try {
            for (RegisterSetup acc : scenario.ensure() == null ? List.<RegisterSetup>of() : scenario.ensure()) {
                ensureAccount(scenario, acc, base, vars);
            }
            if (reg == null) {
                return null;
            }
            RegisterSetup sub = substituteRegister(scenario, "setup.register", reg, vars);
            Resp resp = send(base, "POST", "/api/v1/auth/register", registerBody(sub), null, null);
            if (resp.status() == 200) {
                return cookieOf(resp);
            }
            if (resp.status() == 409) {
                Resp login = send(base, "POST", "/api/v1/auth/login", loginBody(sub), null, null);
                if (login.status() == 200) {
                    return cookieOf(login);
                }
                throw new ScenarioFailure("剧本 " + scenario.name() + ": setup 登录失败 "
                        + login.status() + " " + login.body());
            }
            throw new ScenarioFailure("剧本 " + scenario.name() + ": setup 注册失败 "
                    + resp.status() + " " + resp.body());
        } catch (IOException e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + ": setup 请求异常 " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioFailure("剧本 " + scenario.name() + ": setup 被中断", e);
        }
    }

    /**
     * ruoyi_login setup（P3 golden 复活批）：POST /login 捕 token 建 Bearer 会话。
     * 若依 AjaxResult 恒 HTTP 200（renderString 包装），业务成败只在 body code——
     * code ∉ {200, 0} 即 setup 失败（假 200 识别的 setup 侧）。token 在顶层
     * （实测 27401：{@code {"msg","code","token"}}；data.token 作兼容变体兜底）。
     * 返回 {@code "bearer:<token>"}：与 cookie 会话标识区分，后续步由 {@link #applyHeaders}
     * 默认注入 {@code Authorization: Bearer}（步级 headers 仍可覆盖）。token 同时进
     * vars（{@code ${token}}），步级 capture 亦可见。
     */
    private String ruoyiLoginSetup(Scenario scenario, Setup block, Map<String, String> vars) {
        String base = dual() ? ("java".equals(writesSide) ? java : rust) : single;
        String user = substitute(scenario, "setup.username", block.username(), vars);
        String pass = substitute(scenario, "setup.password", block.password(), vars);
        try {
            Resp login = send(base, "POST", "/login",
                    "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}", null, null);
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login.body());
            int code = body.path("code").asInt(-1);
            if (login.status() != 200 || (code != 200 && code != 0)) {
                throw new ScenarioFailure("剧本 " + scenario.name() + ": ruoyi setup 失败 HTTP "
                        + login.status() + " code=" + code + " " + preview(login.body()));
            }
            String token = body.path("token").asText("");
            if (token.isBlank()) {
                token = body.path("data").path("token").asText("");
            }
            if (token.isBlank()) {
                throw new ScenarioFailure("剧本 " + scenario.name() + ": ruoyi setup 响应无 token "
                        + preview(login.body()));
            }
            vars.put("token", token);
            return BEARER_PREFIX + token;
        } catch (IOException e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + ": ruoyi setup 请求异常 " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioFailure("剧本 " + scenario.name() + ": ruoyi setup 被中断", e);
        }
    }

    /** ensure 单账号：注册即可；409（已存在）转登录验证口令仍有效（体同样支持 ${var}）。 */
    private void ensureAccount(Scenario scenario, RegisterSetup acc, String base, Map<String, String> vars)
            throws IOException, InterruptedException {
        RegisterSetup sub = substituteRegister(scenario, "setup.ensure", acc, vars);
        Resp resp = send(base, "POST", "/api/v1/auth/register", registerBody(sub), null, null);
        if (resp.status() == 200) {
            return;
        }
        if (resp.status() == 409) {
            Resp login = send(base, "POST", "/api/v1/auth/login", loginBody(sub), null, null);
            if (login.status() == 200) {
                return;
            }
            throw new ScenarioFailure("剧本 " + scenario.name() + ": setup.ensure 账号 "
                    + sub.email() + " 口令校验失败 " + login.status() + " " + login.body());
        }
        throw new ScenarioFailure("剧本 " + scenario.name() + ": setup.ensure 注册失败 "
                + resp.status() + " " + resp.body());
    }

    /** setup/ensure 账号体的 ${var} 替换（M3：${run_id} 唯一后缀可用在 email/display_name）。 */
    private RegisterSetup substituteRegister(Scenario scenario, String where, RegisterSetup acc,
                                             Map<String, String> vars) {
        return new RegisterSetup(
                substitute(scenario, where + ".email", acc.email(), vars),
                substitute(scenario, where + ".password", acc.password(), vars),
                acc.displayName() == null ? null
                        : substitute(scenario, where + ".display_name", acc.displayName(), vars));
    }

    private static String registerBody(RegisterSetup acc) {
        return "{\"email\":\"" + acc.email() + "\",\"password\":\"" + acc.password()
                + "\",\"display_name\":\"" + acc.displayNameOrDefault() + "\"}";
    }

    private static String loginBody(RegisterSetup acc) {
        return "{\"email\":\"" + acc.email() + "\",\"password\":\"" + acc.password() + "\"}";
    }

    /**
     * 执行一步（指定基址与侧别）。{@code owner=true} 表示该侧拥有 expect 校验与 capture
     * （rust/java 步骤的所属侧；both 步骤的 Rust 侧）；{@code owner=false} 只如实记录。
     * {@code recordedTarget} 进黄金 meta 的实际路由侧（--writes java 重定向的 rust 步记 java）。
     */
    private RecordedStep runStep(Scenario scenario, Step step, Map<String, String> vars,
                                 String[] cookie, String base, String side, boolean owner,
                                 Target recordedTarget) {
        Step.ParsedCall call;
        // 初值 null 仅为通过明确赋值检查：IOException 分支（唯一使用处）只能从 send() 进入，彼时 path 必已赋值
        String path = null;
        try {
            if (step.pauseMs() != null && step.pauseMs() > 0) {
                Thread.sleep(step.pauseMs());
            }
            // call 行解析/变量替换也收进 try：写错的剧本只废该剧本（ScenarioFailure 通道），
            // 不再以 IllegalArgumentException 炸掉整个录制/回放进程（审查更正）。
            call = step.parsedCall();
            path = substitute(scenario, step, call.path(), vars);
            Map<String, String> headers = new LinkedHashMap<>();
            if (step.headers() != null) {
                for (Map.Entry<String, String> e : step.headers().entrySet()) {
                    headers.put(e.getKey(), substitute(scenario, step, e.getValue(), vars));
                }
            }
            Resp resp;
            if (step.upload() != null && !step.upload().isEmpty()) {
                Map<String, String> upload = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : step.upload().entrySet()) {
                    upload.put(e.getKey(), substitute(scenario, step, e.getValue(), vars));
                }
                resp = sendMultipart(base, call.method(), path, upload, headers, cookie[0]);
            } else {
                String body = call.body() == null ? null : substitute(scenario, step, call.body(), vars);
                resp = send(base, call.method(), path, body, headers, cookie[0]);
            }
            if (owner) {
                // 假 200 识别闸收窄（P3 修复轮，审查 Important-1）：步显式声明 expect.status≠200
                // 即「预期业务错误步」（T2 auth 401 族/admin 403 族）——交 expect/契约面兜底，
                // 不在此误杀；expect 200 或未声明 expect 的步维持保护（未声明即没有错误预期，
                // 业务码异常仍应早失败）。
                boolean expectErrorDeclared = step.expectStatus() != null && step.expectStatus() != 200;
                if (!expectErrorDeclared && scenario.setup() != null && scenario.setup().ruoyiLogin()) {
                    assertBusinessOk(scenario, step, resp);
                }
                applyCaptures(scenario, step, resp, vars, cookie);
                if (step.expectStatus() != null && resp.status() != step.expectStatus()) {
                    throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                            + ": 期望 " + step.expectStatus() + " 实际 " + resp.status()
                            + "（" + side + " 侧），body=" + preview(resp.body()));
                }
            }
            return new RecordedStep(step.id(), call.method(), call.path(), step.expectStatus(),
                    resp.status(), normalized(resp), recordedTarget, side, false);
        } catch (IllegalArgumentException e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": call 行解析失败 " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": 请求异常（" + side + " 侧 " + base + "）" + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id() + ": 被中断", e);
        }
    }

    /**
     * 响应感知的归一化（M5 任务 1）：RDF 响应（text/turtle / application/ld+json）按
     * 三元组集归一（Normalizer.triples）——Jena 与 oxrdfio 的序列化空白/前缀展开顺序
     * 不同，导出步的双打比对按图同比而不是字节比；其余照 JSON 规则。
     */
    private static com.fasterxml.jackson.databind.JsonNode normalized(Resp resp) {
        String ctype = resp.headers().getOrDefault("content-type", List.of()).stream()
                .findFirst().orElse("");
        if (ctype.startsWith("text/turtle") || ctype.startsWith("application/ld+json")) {
            return Normalizer.triples(resp.body());
        }
        return Normalizer.normalize(resp.body(), Normalizer.DEFAULT_MASKS);
    }

    private void applyCaptures(Scenario scenario, Step step, Resp resp, Map<String, String> vars, String[] cookie) {
        if (step.capture() == null || step.capture().isEmpty()) {
            return;
        }
        step.capture().forEach((name, source) -> {
            if ("set-cookie".equals(source)) {
                // 会话状态：更新 cookie 槽位，后续步骤自动携带新会话
                if (!"cookie".equals(name)) {
                    throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                            + ": set-cookie 只能捕获为 cookie（实际 " + name + "）");
                }
                String fresh = cookieOf(resp);
                if (fresh == null) {
                    throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                            + ": 响应无 set-cookie 头，无法建立会话");
                }
                cookie[0] = fresh;
            } else if (source.startsWith("body.")) {
                String value = extractPath(resp.body(), source.substring("body.".length()));
                if (value == null) {
                    throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                            + ": 捕获路径 " + source + " 不存在，body=" + preview(resp.body()));
                }
                vars.put(name, value);
            } else {
                throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                        + ": 不支持的捕获源 " + source + "（支持 set-cookie / body.路径）");
            }
        });
    }

    private Resp send(String base, String method, String path, String body,
                      Map<String, String> headers, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", JSON);
        if (body != null) {
            builder.header("Content-Type", JSON).method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        applyHeaders(builder, headers, cookie);
        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(resp.statusCode(), resp.body(), resp.headers().map());
    }

    /** multipart 文件上传（唯一非 JSON 请求体）：单文件一段，字段名缺省 files。 */
    private Resp sendMultipart(String base, String method, String path, Map<String, String> upload,
                               Map<String, String> headers, String cookie)
            throws IOException, InterruptedException {
        String boundary = "utopia-contract-" + Long.toHexString(System.nanoTime());
        String field = upload.getOrDefault("field", "files");
        String filename = upload.get("filename");
        String mime = upload.getOrDefault("mime", "text/plain");
        byte[] content = upload.get("content").getBytes(StandardCharsets.UTF_8);
        var out = new java.io.ByteArrayOutputStream();
        var writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write("--" + boundary + "\r\n");
        writer.write("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n");
        writer.write("Content-Type: " + mime + "\r\n\r\n");
        writer.flush();
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", JSON)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        applyHeaders(builder, headers, cookie);
        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(resp.statusCode(), resp.body(), resp.headers().map());
    }

    /** 会话头注入：bearer: 前缀（ruoyi_login 会话）→ Authorization，否则按 cookie 携带。 */
    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers, String cookie) {
        if (cookie != null) {
            if (cookie.startsWith(BEARER_PREFIX)) {
                builder.header("Authorization", "Bearer " + cookie.substring(BEARER_PREFIX.length()));
            } else {
                builder.header("Cookie", cookie);
            }
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }
    }

    /**
     * 假 200 识别（P3 golden 复活批，仅 ruoyi_login 剧本的 owner 侧普通步）：若依
     * renderString 把业务错误也写成 HTTP 200，body code ∉ {200, 0} 即步失败（错误带 msg）。
     * expect/capture 前先过这道——业务失败时捕获与 expect 的报错都不如 msg 直接。
     * 无 code 字段的响应（utopia 形状如 404 的 {"error":...}、空集形状、非 JSON 体）不受影响。
     *
     * <p>修复轮收窄（审查 Important-1）：调用方对「步显式声明 expect.status≠200」的
     * 预期业务错误步跳过本闸（由 expect/契约面兜底），expect 200 或未声明的步维持保护。
     */
    private static void assertBusinessOk(Scenario scenario, Step step, Resp resp) {
        JsonNode body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
        } catch (IOException e) {
            return; // 非 JSON 体不适用 AjaxResult 约定
        }
        JsonNode code = body.path("code");
        if (code.isNumber() && code.asInt() != 200 && code.asInt() != 0) {
            throw new ScenarioFailure("剧本 " + scenario.name() + " 步骤 " + step.id()
                    + ": 业务失败（HTTP " + resp.status() + " code=" + code.asInt()
                    + "）msg=" + preview(body.path("msg").asText("")));
        }
    }

    /** ${var} 替换（步骤版）：引用不存在的变量直接失败（剧本写错早暴露，好过带着空值打接口）。 */
    private String substitute(Scenario scenario, Step step, String template, Map<String, String> vars) {
        return substitute(scenario, "步骤 " + step.id(), template, vars);
    }

    /** ${var} 替换（通用版）：where 是可定位的错误出处（setup.register.email / 步骤 x）。 */
    private String substitute(Scenario scenario, String where, String template, Map<String, String> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = vars.get(key);
            if (value == null) {
                throw new ScenarioFailure("剧本 " + scenario.name() + " " + where
                        + ": 未定义变量 ${" + key + "}（可用: " + vars.keySet() + "）");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 从 set-cookie 头取 {@code name=value}（去掉属性段）。 */
    private String cookieOf(Resp resp) {
        String setCookie = resp.firstSetCookie();
        if (setCookie == null) {
            return null;
        }
        return setCookie.split(";", 2)[0].trim();
    }

    /** 点路径取值；数字段按下标取数组元素（如 created.0.id），负数支持倒数（-1 末元素）。 */
    private static String extractPath(String body, String dottedPath) {
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            for (String seg : dottedPath.split("\\.")) {
                if (node == null) {
                    return null;
                }
                if (node.isArray() && seg.matches("-?\\d+")) {
                    int idx = Integer.parseInt(seg);
                    node = idx < 0 ? node.get(node.size() + idx) : node.get(idx);
                } else {
                    node = node.get(seg);
                }
            }
            return node == null || node.isNull() ? null : node.asText();
        } catch (IOException e) {
            return null;
        }
    }

    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
