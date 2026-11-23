package tech.seexun.jknow.contract;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import tech.seexun.jknow.contract.model.RegisterSetup;
import tech.seexun.jknow.contract.model.Scenario;
import tech.seexun.jknow.contract.model.Setup;
import tech.seexun.jknow.contract.model.SnapshotSpec;
import tech.seexun.jknow.contract.model.SqlSpec;
import tech.seexun.jknow.contract.model.Step;
import tech.seexun.jknow.contract.model.StoreSpec;
import tech.seexun.jknow.contract.model.Target;
import tech.seexun.jknow.contract.model.WaitSpec;

/**
 * YAML 剧本装载器（SnakeYAML 2.x 默认安全构造：只产标准 Java 类型，无任意类实例化风险）。
 * 先 load 成 Map 再手工映射到模型，字段名错误给可定位的报错而不是 ClassCastException。
 */
public final class ScenarioLoader {

    private ScenarioLoader() {
    }

    public static Scenario load(Path yamlFile) {
        try (Reader reader = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException(yamlFile + ": 剧本必须是 YAML 映射");
            }
            return fromMap(yamlFile.toString(), root);
        } catch (IOException e) {
            throw new IllegalStateException("读剧本失败: " + yamlFile, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Scenario fromMap(String src, Map<?, ?> root) {
        String name = requireString(root, "name", src);
        String desc = optString(root, "desc");
        Map<?, ?> setupNode = optMap(root, "setup");
        List<RegisterSetup> ensure = new ArrayList<>();
        Object ensureNode = setupNode == null ? null : setupNode.get("ensure");
        if (ensureNode != null) {
            if (!(ensureNode instanceof List<?> list)) {
                throw new IllegalArgumentException(src + ": setup.ensure 必须是列表");
            }
            for (Object item : list) {
                ensure.add(parseRegisterBody(src, "setup.ensure[]", asMap(item, src + ": setup.ensure[]")));
            }
        }
        Setup setup = setupNode == null ? null : parseSetup(src, setupNode, ensure);
        Map<String, String> vars = new LinkedHashMap<>();
        Map<?, ?> varsNode = optMap(root, "vars");
        if (varsNode != null) {
            varsNode.forEach((k, v) -> vars.put(String.valueOf(k), String.valueOf(v)));
        }
        List<Step> steps = parseSteps(src, root.get("steps"));
        return new Scenario(name, desc, setup, ensure, vars, steps);
    }

    /**
     * setup 块解析（P3 复活批两模式）：mode 缺省 register（源仓注册流，register 或 ensure
     * 至少其一——旧校验口径不变）；ruoyi_login 需 username/password 且不接受 register/ensure
     * （那是源仓注册流装置，混写是剧本错误）。ensure-only 时返回 null（无会话开局，旧语义：
     * Scenario.setup() 为 null）。
     */
    private static Setup parseSetup(String src, Map<?, ?> setupNode, List<RegisterSetup> ensure) {
        Setup.Mode mode = Setup.parseMode(optString(setupNode, "mode"), src);
        RegisterSetup register = optRegister(src, "setup.register", setupNode, "register");
        String username = optString(setupNode, "username");
        String password = optString(setupNode, "password");
        if (mode == Setup.Mode.RUOYI_LOGIN) {
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException(src + ": setup.mode=ruoyi_login 需要 username 与 password");
            }
            if (register != null || !ensure.isEmpty()) {
                throw new IllegalArgumentException(src
                        + ": setup.mode=ruoyi_login 不接受 register/ensure（那是源仓注册流装置）");
            }
            return new Setup(mode, null, username, password);
        }
        if (register == null && ensure.isEmpty()) {
            throw new IllegalArgumentException(src + ": setup 至少需要 register 或 ensure 之一");
        }
        return register == null ? null : new Setup(mode, register, null, null);
    }

    /** setup.register / setup.ensure[] 共用的单账号体解析。 */
    private static RegisterSetup parseRegisterBody(String src, String where, Map<?, ?> register) {
        String email = requireString(register, "email", src + ": " + where);
        String password = requireString(register, "password", src + ": " + where);
        String displayName = optString(register, "display_name");
        return new RegisterSetup(email, password, displayName);
    }

    /** 从给定映射里取可选的账号体（setup.register 用）。 */
    private static RegisterSetup optRegister(String src, String where, Map<?, ?> owner, String key) {
        Map<?, ?> m = owner == null ? null : asMap(owner.get(key), src + ": " + where);
        return m == null ? null : parseRegisterBody(src, where, m);
    }

    private static List<Step> parseSteps(String src, Object stepsNode) {
        if (stepsNode == null) {
            throw new IllegalArgumentException(src + ": steps 为空，剧本至少一步");
        }
        if (!(stepsNode instanceof List<?> list)) {
            throw new IllegalArgumentException(src + ": steps 必须是列表");
        }
        List<Step> steps = new ArrayList<>();
        if (list.isEmpty()) {
            throw new IllegalArgumentException(src + ": steps 为空，剧本至少一步");
        }
        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> m = asMap(list.get(i), src + ": steps[" + i + "]");
            String id = requireString(m, "id", src + ": steps[" + i + "]");
            // M3 任务 1/11：步骤六选一——call（HTTP）或 snapshot/sql/wait/store 四类非 HTTP 步。
            // 快照步没有 call 行；call 与任一非 HTTP 载荷同时出现是剧本写错，早暴露。
            String call = optString(m, "call");
            SnapshotSpec snapshot = parseSnapshot(src, i, m);
            SqlSpec sql = parseSql(src, i, m);
            WaitSpec wait = parseWait(src, i, m);
            StoreSpec store = parseStore(src, i, m);
            List<String> nonCall = new ArrayList<>();
            if (snapshot != null) {
                nonCall.add("snapshot");
            }
            if (sql != null) {
                nonCall.add("sql");
            }
            if (wait != null) {
                nonCall.add("wait");
            }
            if (store != null) {
                nonCall.add("store");
            }
            if (nonCall.size() > 1) {
                throw new IllegalArgumentException(src + ": steps[" + i + "] "
                        + String.join("/", nonCall) + " 互斥（一步只能是其一）");
            }
            if ((call == null || call.isBlank()) && nonCall.isEmpty()) {
                throw new IllegalArgumentException(src + ": steps[" + i + "] 缺必填字段 call（或 snapshot/sql/wait/store）");
            }
            if (call != null && !call.isBlank() && !nonCall.isEmpty()) {
                throw new IllegalArgumentException(src + ": steps[" + i + "] call 与 " + nonCall.get(0)
                        + " 互斥（一步只能是其一）");
            }
            // 任务 1 审查 M-2 收口（任务 11 落地）：非 HTTP 步骤不得声明 target——定向是
            // HTTP 概念；快照/store 跟随写路径侧，sql/wait 与侧无关，写了都是死配置，
            // 装载器拒收（heart-ledger 骨架曾给快照步写过 target: rust，靠引擎忽略才没炸）。
            if (!nonCall.isEmpty() && m.get("target") != null) {
                throw new IllegalArgumentException(src + ": steps[" + i + "] " + nonCall.get(0)
                        + " 步不接受 target（定向是 HTTP 概念；快照/store 跟随写路径侧，sql/wait 与侧无关）");
            }
            Map<String, String> capture = new LinkedHashMap<>();
            Map<?, ?> capNode = asMap(m.get("capture"), src + ": steps[" + i + "].capture");
            if (capNode != null) {
                capNode.forEach((k, v) -> capture.put(String.valueOf(k), String.valueOf(v)));
            }
            Integer expectStatus = null;
            Map<?, ?> expect = asMap(m.get("expect"), src + ": steps[" + i + "].expect");
            if (expect != null && expect.get("status") != null) {
                Object st = expect.get("status");
                expectStatus = st instanceof Number n ? n.intValue()
                        : Integer.parseInt(String.valueOf(st).trim());
            }
            Map<String, String> headers = new LinkedHashMap<>();
            Map<?, ?> headerNode = asMap(m.get("headers"), src + ": steps[" + i + "].headers");
            if (headerNode != null) {
                headerNode.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            }
            Map<String, String> upload = new LinkedHashMap<>();
            Map<?, ?> uploadNode = asMap(m.get("upload"), src + ": steps[" + i + "].upload");
            if (uploadNode != null) {
                if (!uploadNode.containsKey("filename") || !uploadNode.containsKey("content")) {
                    throw new IllegalArgumentException(src + ": steps[" + i + "].upload 需要 filename 与 content");
                }
                uploadNode.forEach((k, v) -> upload.put(String.valueOf(k), String.valueOf(v)));
            }
            Long pauseMs = null;
            Object pause = m.get("pause_ms");
            if (pause != null) {
                pauseMs = pause instanceof Number n ? n.longValue()
                        : Long.parseLong(String.valueOf(pause).trim());
            }
            Target target = Target.parse(optString(m, "target"), src + ": steps[" + i + "].target");
            steps.add(new Step(id, call, capture, expectStatus, headers, upload, pauseMs, target,
                    snapshot, sql, wait, store));
        }
        return steps;
    }

    /**
     * 解析 {@code snapshot:} 载荷（M3 任务 1）：{@code kb_id} 必填模板、{@code tables}
     * 可选表名覆盖。无 snapshot 键返回 null（普通 call 步）。
     */
    private static SnapshotSpec parseSnapshot(String src, int i, Map<?, ?> m) {
        Map<?, ?> node = asMap(m.get("snapshot"), src + ": steps[" + i + "].snapshot");
        if (node == null) {
            return null;
        }
        String kbId = requireString(node, "kb_id", src + ": steps[" + i + "].snapshot");
        List<String> tables = new ArrayList<>();
        Object tablesNode = node.get("tables");
        if (tablesNode != null) {
            if (!(tablesNode instanceof List<?> list)) {
                throw new IllegalArgumentException(src + ": steps[" + i + "].snapshot.tables 必须是列表");
            }
            for (Object t : list) {
                tables.add(String.valueOf(t));
            }
        }
        return new SnapshotSpec(kbId, tables.isEmpty() ? null : tables);
    }

    /**
     * 解析 {@code sql:} 载荷（M3 任务 11）：{@code statements} 必填非空、{@code capture}
     * 与 {@code wait_quiet} 可选。无 sql 键返回 null（其他步型）。
     */
    private static SqlSpec parseSql(String src, int i, Map<?, ?> m) {
        Map<?, ?> node = asMap(m.get("sql"), src + ": steps[" + i + "].sql");
        if (node == null) {
            return null;
        }
        List<String> statements = new ArrayList<>();
        Object stNode = node.get("statements");
        if (stNode == null) {
            throw new IllegalArgumentException(src + ": steps[" + i + "].sql.statements 必填（非空列表）");
        }
        if (!(stNode instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(src + ": steps[" + i + "].sql.statements 必须是非空列表");
        }
        for (Object s : list) {
            statements.add(String.valueOf(s));
        }
        Map<String, String> capture = new LinkedHashMap<>();
        Map<?, ?> capNode = asMap(node.get("capture"), src + ": steps[" + i + "].sql.capture");
        if (capNode != null) {
            capNode.forEach((k, v) -> capture.put(String.valueOf(k), String.valueOf(v)));
        }
        String waitQuiet = optString(node, "wait_quiet");
        return new SqlSpec(statements, capture, waitQuiet);
    }

    /** 解析 {@code wait:} 载荷（M3 任务 11）：kb_id 必填模板。无 wait 键返回 null。 */
    private static WaitSpec parseWait(String src, int i, Map<?, ?> m) {
        Map<?, ?> node = asMap(m.get("wait"), src + ": steps[" + i + "].wait");
        if (node == null) {
            return null;
        }
        return new WaitSpec(requireString(node, "kb_id", src + ": steps[" + i + "].wait"));
    }

    /**
     * 解析 {@code store:} 载荷（M3 任务 11）：{@code op} 必填、{@code args} 可选参数表。
     * 无 store 键返回 null。
     */
    private static StoreSpec parseStore(String src, int i, Map<?, ?> m) {
        Map<?, ?> node = asMap(m.get("store"), src + ": steps[" + i + "].store");
        if (node == null) {
            return null;
        }
        String op = requireString(node, "op", src + ": steps[" + i + "].store");
        Map<String, String> args = new LinkedHashMap<>();
        Map<?, ?> argsNode = asMap(node.get("args"), src + ": steps[" + i + "].store.args");
        if (argsNode != null) {
            argsNode.forEach((k, v) -> args.put(String.valueOf(k), String.valueOf(v)));
        }
        return new StoreSpec(op, args);
    }

    private static String requireString(Map<?, ?> map, String key, String where) {
        Object v = map.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException(where + ": 缺必填字段 " + key);
        }
        return String.valueOf(v);
    }

    private static String optString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Map<?, ?> optMap(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : asMap(v, key);
    }

    private static Map<?, ?> asMap(Object node, String where) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(where + " 必须是映射，实际是: " + node.getClass().getSimpleName());
        }
        return m;
    }

    /** 供测试直接从字符串装载。 */
    static Scenario fromYamlString(String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("剧本必须是 YAML 映射");
        }
        return fromMap("<string>", root);
    }
}
