package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tech.seexun.jknow.contract.model.Scenario;

/**
 * 黄金录制器：对目标服务跑剧本，每步存一个 JSON（meta + normalized 响应）。
 *
 * <p>单打（M0，黄金重录用）：
 * <pre>mvn -pl utopia-contract compile exec:java \
 *   -Dexec.args="--target http://127.0.0.1:1516 --scenario scenarios/health.yaml --out golden/"</pre>
 * 全部步骤（含 setup）打同一服务，忽略步骤定向；输出单一 {@code --out} 目录。
 *
 * <p>双打（M1，m1-recon §5 共享库架构）：
 * <pre>-Dexec.args="--rust http://127.0.0.1:1516 --java http://127.0.0.1:17001 \
 *   --scenario scenarios/auth-login.yaml --out golden"</pre>
 * setup/ensure 与 {@code target: rust} 步骤打 Rust；{@code target: both}（缺省）步骤对两服务
 * 各发一次同请求、各录一份：{@code <out>-rust/} 与 {@code <out>-java/}（如 --out golden →
 * golden-rust/、golden-java/）。Java 侧响应<b>如实记录不校验</b>（未实现端点记 404 也是数据），
 * 对错由 {@code Diff --dual golden} 两目录比对裁决。Rust 侧仍是契约权威：expect 不符即失败。
 *
 * <p>失败步骤（Rust 侧状态码≠expect / 请求异常 / 捕获缺失）中止该剧本并整体失败；
 * 剧本全绿才落盘（goldens 不允许半截）；录制<b>前</b>先删除该剧本既有 golden 目录
 * （双打同时清 {@code <out>-rust/} 与 {@code <out>-java/} 两侧）——否则失败剧本的旧样本会残留，
 * 被误当本次录制（先清后录是 M0 走查1 确立的纪律；M1 任务1 曾回归为先录后清，走查1 恢复）。
 *
 * <p>M3 任务 1：{@code --writes rust|java}（双打限定，缺省关闭=行为与 M1/M2 完全一致）。
 * <b>写路径单侧录制</b>：setup/ensure 与 {@code target: rust} 写步整体重定向到指定实例，
 * 只清、只写该侧目录——「同一剧本写路径在 Rust 跑一遍（--writes rust，产 golden-rust 快照）、
 * 在 Java 跑一遍（--writes java，产 golden-java 快照）」，两侧各自新建 kb 固定装置（UUID 不同，
 * 归一化后可比），{@code Diff --dual} 深度比对两轮的库内终态（锁 4）。缺省（不带 --writes）
 * 仍是 M1 语义一轮双录：写步打 Rust、both 步双发双录、两目录同清同写。
 */
public final class Recorder {

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        boolean dual = !cli.all("rust").isEmpty();
        if (dual != !cli.all("java").isEmpty()) {
            throw new IllegalArgumentException("双打需要 --rust 与 --java 成对给出；单打用 --target");
        }
        if (dual && !cli.all("target").isEmpty()) {
            throw new IllegalArgumentException("--target（单打）与 --rust/--java（双打）互斥");
        }
        String writes = cli.opt("writes", null);
        if (writes != null) {
            if (!dual) {
                throw new IllegalArgumentException("--writes 仅双打模式（--rust/--java）可用；单打本来就是全打 --target");
            }
            if (!writes.equals("rust") && !writes.equals("java")) {
                throw new IllegalArgumentException("--writes 必须是 rust|java，实际是: " + writes);
            }
        }
        boolean rustDown = "true".equals(cli.opt("rust-down", ""));
        String pinnedRunId = cli.opt("run-id", null);
        if (rustDown && (writes == null || !writes.equals("java"))) {
            throw new IllegalArgumentException("--rust-down 仅 --writes java（切流演练轮）可用");
        }
        List<Path> scenarios = cli.all("scenario").stream().map(Path::of).toList();
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("至少一个 --scenario &lt;yaml&gt;");
        }
        Path out = Path.of(cli.opt("out", "golden"));
        String mode;
        if (dual) {
            mode = "双打 rust=" + cli.require("rust") + " java=" + cli.require("java")
                    + (writes == null ? "" : "，写路径单侧录制 --writes " + writes
                    + "（只清/只写 " + out + "-" + writes + "/）")
                    + (rustDown ? "，--rust-down true（Rust 参照实例停机：both 步只跑 Java 侧）" : "")
                    + "，输出 " + out + "-rust/ 与 " + out + "-java/";
        } else {
            mode = "单打 target=" + cli.require("target") + "，输出 " + out;
        }
        System.out.println("录制模式: " + mode + "，剧本 " + scenarios.size() + " 个");

        List<String> failed = new ArrayList<>();
        for (Path yaml : scenarios) {
            Scenario scenario = ScenarioLoader.load(yaml);
            System.out.println("== 剧本 " + scenario.name() + "（" + yaml.getFileName() + "，"
                    + scenario.steps().size() + " 步）");
            try {
                // 先清后录（M0 纪律）：跑剧本前即删旧 golden——失败剧本不留残留，目录要么全绿要么空。
                // --writes 单侧录制只清该侧：另一侧是「对方写路径那一轮」的产物，清了就把证据毁了
                if (dual) {
                    if (writes == null) {
                        clearGoldenDir(Path.of(out + "-rust"), scenario);
                        clearGoldenDir(Path.of(out + "-java"), scenario);
                    } else {
                        clearGoldenDir(Path.of(out + "-" + writes), scenario);
                    }
                } else {
                    clearGoldenDir(out, scenario);
                }
                boolean rustDownHere = rustDown && "java".equals(writes);
                ScenarioRunner runner = dual
                        ? (writes == null
                                ? new ScenarioRunner(cli.require("rust"), cli.require("java"))
                                : new ScenarioRunner(cli.require("rust"), cli.require("java"),
                                        writes, true, rustDownHere))
                        : new ScenarioRunner(cli.require("target"));
                List<RecordedStep> steps = runner.run(scenario, pinnedRunId);
                if (dual) {
                    if (writes == null) {
                        writeGoldens(Path.of(out + "-rust"), scenario, steps, "rust");
                        writeGoldens(Path.of(out + "-java"), scenario, steps, "java");
                    } else {
                        writeGoldens(Path.of(out + "-" + writes), scenario, steps, writes);
                    }
                } else {
                    writeGoldens(out, scenario, steps, null);
                }
                steps.forEach(s -> System.out.println("  [OK] " + (dual ? "[" + s.side() + "] " : "")
                        + s.step() + " " + s.method() + " " + s.pathTemplate() + " -> " + s.status()));
            } catch (ScenarioFailure e) {
                failed.add(scenario.name());
                System.out.println("  [FAIL] " + e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            System.err.println("录制失败剧本: " + failed + "（黄金样本必须是绿的，旧样本已先清、未写入新样本）");
            System.exit(1);
        }
        System.out.println("录制完成: " + scenarios.size() + " 个剧本全部落盘"
                + (dual ? "（" + out + "-rust/ 与 " + out + "-java/，可用 Diff --dual " + out + " 比对）" : " " + out));
    }

    /**
     * 录制前删除该剧本既有 golden 目录（含所有旧样本）。
     * 选删除而非失败时改名 {@code .stale}：简单且语义干净——golden 目录里要么是本次全绿录制，
     * 要么什么都不在；批量录制中某剧本失败后，其旧样本不残留、不会被误当本次录制结果。
     */
    private static void clearGoldenDir(Path out, Scenario scenario) throws IOException {
        Path dir = out.resolve(sanitize(scenario.name()));
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * 落盘：单打（{@code side=null}）写全部结果；双打按 side 分拣——
     * rust 目录收 rust 步骤与 both 步骤的 Rust 侧，java 目录收 java 步骤与 both 步骤的 Java 侧
     * （同一步两份文件名相同、目录不同）。快照步文件名带 {@code .snapshot.json} 后缀
     * （与 HTTP 步的 {@code .json} 区分；载荷键 {@code snapshot}、meta.kind=snapshot）。
     */
    private static void writeGoldens(Path out, Scenario scenario, List<RecordedStep> steps, String side)
            throws IOException {
        Path dir = out.resolve(sanitize(scenario.name()));
        Files.createDirectories(dir);
        ObjectMapper mapper = new ObjectMapper();
        for (RecordedStep step : steps) {
            if (side != null && !side.equals(step.side())) {
                continue;
            }
            ObjectNode root = mapper.createObjectNode();
            ObjectNode meta = root.putObject("meta");
            meta.put("scenario", scenario.name());
            meta.put("step", step.step());
            meta.put("method", step.method());
            // 模板路径（${} 未替换）：真实路径每次录制含新 uuid，进了 meta 自比对必炸
            meta.put("path", step.pathTemplate());
            meta.put("target", step.target().wire());
            if (step.snapshot()) {
                // Diff 据此识别快照样本：恒做双侧深度比对（缺一侧即差异），不按单侧定向跳过
                meta.put("kind", "snapshot");
            }
            if (step.expectedStatus() == null) {
                meta.putNull("expectedStatus");
            } else {
                meta.put("expectedStatus", step.expectedStatus());
            }
            meta.put("status", step.status());
            root.set(step.snapshot() ? "snapshot" : "normalized", step.normalized());
            String suffix = step.snapshot() ? ".snapshot.json" : ".json";
            Files.writeString(dir.resolve(sanitize(step.step()) + suffix),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
