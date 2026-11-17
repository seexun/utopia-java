package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tech.seexun.jknow.contract.model.Scenario;

/**
 * 回放器（M0 基座）：按剧本打目标服务，逐步与黄金样本比对。
 *
 * <pre>mvn -pl utopia-contract compile exec:java -Dexec.mainClass=tech.seexun.jknow.contract.Replayer \
 *   -Dexec.args="--target http://127.0.0.1:1516 --scenario scenarios/auth-login.yaml --golden golden/"</pre>
 *
 * 与 Recorder 共用 ScenarioRunner（同一套变量/会话/规范化口径），差异只在不落盘而比对：
 * meta.method/path/expectedStatus 与 normalized 逐项对，任何一步不等记 FAIL，全部跑完汇总退出码。
 * 单打语义：全部步骤打 --target 指定的服务（忽略步骤定向——定向只在 Recorder 双打模式生效）。
 *
 * <p>挂账（waiver，M1）：{@code --waivers scenarios/WAIVERS.md} 与可重复的
 * {@code --waive 键#原因码}（叠加）；命中步骤跳过比对并单列 [WAIVE]——
 * 不算 FAIL 也不算 PASS（环境敏感步骤的已知漂移，显式挂账，见 {@link Waivers}）。
 * 挂账豁免的是比对而非样本存在性：黄金缺失检查在前，挂账步骤样本缺失同样记 FAIL。
 */
public final class Replayer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        String target = cli.require("target");
        List<Path> scenarios = cli.all("scenario").stream().map(Path::of).toList();
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("至少一个 --scenario &lt;yaml&gt;");
        }
        Path golden = Path.of(cli.require("golden"));
        Waivers waivers = Waivers.fromArgs(cli.all("waive"));
        if (!cli.all("waivers").isEmpty()) {
            waivers = Waivers.load(Path.of(cli.require("waivers"))).merge(waivers);
        }
        System.out.println("回放目标: " + target + "，黄金样本 " + golden
                + (waivers.isEmpty() ? "" : "，挂账 " + waivers.size() + " 条"));

        int pass = 0;
        List<String> fails = new ArrayList<>();
        List<String> waived = new ArrayList<>();
        for (Path yaml : scenarios) {
            Scenario scenario = ScenarioLoader.load(yaml);
            System.out.println("== 剧本 " + scenario.name() + "（" + yaml.getFileName() + "）");
            List<RecordedStep> steps;
            try {
                steps = new ScenarioRunner(target).run(scenario);
            } catch (ScenarioFailure e) {
                fails.add(scenario.name() + ": " + e.getMessage());
                System.out.println("  [FAIL] " + e.getMessage());
                continue;
            }
            for (RecordedStep step : steps) {
                // 布局一：golden/剧本名/步骤.json（Recorder 默认）；布局二：golden/步骤.json（单剧本平铺）。
                // 快照步文件名带 .snapshot.json 后缀（与录制侧同形）
                String fileName = step.step() + (step.snapshot() ? ".snapshot.json" : ".json");
                Path file = golden.resolve(scenario.name()).resolve(fileName);
                Path flat = golden.resolve(fileName);
                if (!Files.exists(file) && Files.exists(flat)) {
                    file = flat;
                }
                if (!Files.exists(file)) {
                    // 黄金缺失检查先于挂账：挂账豁免的是比对，不是样本存在性——
                    // 挂账步骤样本缺失同样是故障，不得静默豁免（走查1 对调）
                    fails.add(scenario.name() + "/" + step.step() + ": 黄金样本缺失 " + file);
                    System.out.println("  [FAIL] " + step.step() + ": 黄金样本缺失");
                    continue;
                }
                String waiveCode = waivers.codeFor(scenario.name() + "/" + step.step());
                if (waiveCode != null) {
                    waived.add(scenario.name() + "/" + step.step() + " —— " + waiveCode);
                    System.out.println("  [WAIVE] " + step.step() + " —— " + waiveCode + "（挂账：不比不算过）");
                    continue;
                }
                JsonNode goldenStep = MAPPER.readTree(file.toFile());
                String mismatch = compare(goldenStep, step);
                if (mismatch == null) {
                    pass++;
                    System.out.println("  [PASS] " + step.step() + " " + step.method() + " " + step.status());
                } else {
                    fails.add(scenario.name() + "/" + step.step() + ": " + mismatch);
                    System.out.println("  [FAIL] " + step.step() + ": " + mismatch);
                }
            }
        }
        System.out.println("回放完成: " + pass + " 步一致，" + fails.size() + " 步不符，"
                + waived.size() + " 步挂账");
        if (!fails.isEmpty()) {
            fails.forEach(f -> System.err.println("  " + f));
            System.exit(1);
        }
    }

    private static String compare(JsonNode goldenStep, RecordedStep actual) {
        JsonNode meta = goldenStep.path("meta");
        if (!meta.path("method").asText("").equals(actual.method())) {
            return "method " + actual.method() + " != 黄金 " + meta.path("method").asText();
        }
        if (!meta.path("path").asText("").equals(actual.pathTemplate())) {
            return "path " + actual.pathTemplate() + " != 黄金 " + meta.path("path").asText();
        }
        if (meta.path("status").asInt(-1) != actual.status()) {
            return "status " + actual.status() + " != 黄金 " + meta.path("status").asInt(-1);
        }
        // 快照步载荷键是 snapshot（HTTP 步是 normalized）——与录制落盘同形
        String payloadKey = actual.snapshot() ? "snapshot" : "normalized";
        JsonNode expected = goldenStep.path(payloadKey);
        if (!expected.equals(actual.normalized())) {
            return payloadKey + " 不符: "
                    + Diff.firstDifference(expected, actual.normalized(), "$." + payloadKey);
        }
        return null;
    }
}
