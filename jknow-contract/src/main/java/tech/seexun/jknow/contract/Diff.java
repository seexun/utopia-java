package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 黄金样本比对器：两份 golden 目录逐文件两两比对。
 *
 * <pre>mvn -pl utopia-contract compile exec:java -Dexec.mainClass=tech.seexun.jknow.contract.Diff \
 *   -Dexec.args="--left golden/ --right target/golden-check/"</pre>
 *
 * 自比对（同一目标连录两遍零差异）是规范化器抹平全部噪声的证明；
 * M1 起同一把尺子量 Rust 与 Java 的双打响应：
 * <pre>-Dexec.args="--dual golden --waivers scenarios/WAIVERS.md"</pre>
 * {@code --dual <base>} 展开为 {@code <base>-rust/} 与 {@code <base>-java/} 两目录
 * （Recorder 双打模式的输出布局），只比对双打步骤（meta.target=both 或缺省）。
 *
 * <p>挂账（waiver）：{@code --waivers <WAIVERS.md>} 声明式清单与 {@code --waive 键#原因码}
 * 临时条目（可重复）叠加，命中键的样本<b>跳过比对并单列报告</b>——不算差异也不算通过，
 * 显式挂账（见 {@link Waivers}）。单侧样本（meta.target=rust 只在左目录、java 只在右目录）
 * 不是差异：定向步骤本来就只录一侧，双打比的是 both 步骤。
 *
 * <p><b>M3 任务 1：快照样本并入 --dual 流程</b>（不设独立 --snapshot 子命令——快照与响应
 * 同目录、同挂账机制，一把尺子量到底）。快照文件（{@code <步名>.snapshot.json}，
 * meta.kind=snapshot）由「写路径单侧录制」两侧各产一份（Recorder {@code --writes} 两轮），
 * 恒做双侧深度比对：<b>缺一侧即差异</b>（快照是两侧各自的证据，不是定向单侧样本），
 * 挂账照常先行豁免。四分类不变：一致 / 挂账 WAIVE / 单侧 SIDE / 差异。
 *
 * <p>比较用 Jackson 树等价（ObjectNode 等价本身键序无关，但两侧都来自规范化器，已是规范形）。
 */
public final class Diff {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 比对结果四分类（单测与 main 共用同一裁决路径）。 */
    record Report(int compared, int snapshotCompared, List<String> diffs,
                  List<String> waived, List<String> singleSided) {
        boolean hasDiffs() {
            return !diffs.isEmpty();
        }
    }

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        Path left;
        Path right;
        if (!cli.all("dual").isEmpty()) {
            String base = cli.require("dual");
            left = Path.of(base + "-rust");
            right = Path.of(base + "-java");
        } else {
            left = Path.of(cli.require("left"));
            right = Path.of(cli.require("right"));
        }
        Waivers waivers = Waivers.fromArgs(cli.all("waive"));
        if (!cli.all("waivers").isEmpty()) {
            waivers = Waivers.load(Path.of(cli.require("waivers"))).merge(waivers);
        }

        Report report = compareDirectories(left, right, waivers);

        report.diffs().forEach(d -> System.out.println("[DIFF] " + d));
        report.waived().forEach(w -> System.out.println("[WAIVE] " + w + "（挂账：不算差异也不算通过）"));
        report.singleSided().forEach(s -> System.out.println("[SIDE] " + s));
        System.out.println("比对完成: " + report.compared() + " 个样本比对"
                + (report.diffs().isEmpty() ? "全部一致" : "，" + report.diffs().size() + " 处差异")
                + "（其中终态快照 " + report.snapshotCompared() + "）"
                + "；挂账 " + report.waived().size() + "，单侧跳过 " + report.singleSided().size());
        if (report.hasDiffs()) {
            System.err.println("比对完成: " + report.diffs().size() + " 处差异");
            System.exit(1);
        }
    }

    /**
     * 两目录比对（纯裁决、无输出/退出副作用——单测可直测四分类）。
     * 快照样本（meta.kind=snapshot）恒比对：定向单侧跳过规则只适用于 HTTP 步
     * （target=rust/java 本来就只录一侧）；快照两侧各录各的，缺一侧就是缺证据。
     */
    static Report compareDirectories(Path left, Path right, Waivers waivers) throws IOException {
        Map<String, Path> leftFiles = index(left);
        Map<String, Path> rightFiles = index(right);

        List<String> diffs = new ArrayList<>();
        List<String> waived = new ArrayList<>();
        List<String> singleSided = new ArrayList<>();
        int compared = 0;
        int snapshotCompared = 0;
        for (String rel : new TreeMap<>(leftFiles).keySet()) {
            String waiveCode = waivers.codeFor(rel);
            JsonNode leftDoc = MAPPER.readTree(leftFiles.get(rel).toFile());
            boolean snapshot = isSnapshot(leftDoc);
            String target = leftDoc.path("meta").path("target").asText("");
            if (waiveCode != null) {
                waived.add(rel + " —— " + waiveCode);
                continue;
            }
            if (!snapshot && "rust".equals(target)) {
                // 定向 rust 的步骤只录 Rust 侧，双打比对对象是 both 步骤；
                // 快照除外：--writes 两轮各录一侧，恒要比
                singleSided.add(rel + "（target=rust，仅 Rust 侧）");
                continue;
            }
            if (!rightFiles.containsKey(rel)) {
                diffs.add(rel + ": 仅左侧存在" + (snapshot ? "（快照缺 java 侧——须 --writes java 补录）" : ""));
                continue;
            }
            compared++;
            if (snapshot) {
                snapshotCompared++;
            }
            String first = firstDifference(leftDoc, MAPPER.readTree(rightFiles.get(rel).toFile()), "$");
            if (first != null) {
                diffs.add(rel + ": " + first);
            }
        }
        for (String rel : rightFiles.keySet()) {
            if (leftFiles.containsKey(rel)) {
                continue;
            }
            String waiveCode = waivers.codeFor(rel);
            JsonNode rightDoc = MAPPER.readTree(rightFiles.get(rel).toFile());
            boolean snapshot = isSnapshot(rightDoc);
            String target = rightDoc.path("meta").path("target").asText("");
            if (waiveCode != null) {
                waived.add(rel + " —— " + waiveCode);
            } else if (!snapshot && "java".equals(target)) {
                singleSided.add(rel + "（target=java，仅 Java 侧）");
            } else {
                diffs.add(rel + ": 仅右侧存在" + (snapshot ? "（快照缺 rust 侧——须 --writes rust 补录）" : ""));
            }
        }
        return new Report(compared, snapshotCompared, diffs, waived, singleSided);
    }

    private static boolean isSnapshot(JsonNode doc) {
        return "snapshot".equals(doc.path("meta").path("kind").asText(""));
    }

    private static Map<String, Path> index(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在: " + dir);
        }
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".json")).forEach(p ->
                    files.put(dir.relativize(p).toString().replace('\\', '/'), p));
        }
        return files;
    }

    /** 返回首个差异点的定位描述（如 {@code $.status: 200 != 401}），一致返回 null。 */
    static String firstDifference(JsonNode a, JsonNode b, String path) {
        if (a.equals(b)) {
            return null;
        }
        if (a instanceof com.fasterxml.jackson.databind.node.ObjectNode oa
                && b instanceof com.fasterxml.jackson.databind.node.ObjectNode ob) {
            for (String field : unionFields(oa, ob)) {
                JsonNode av = oa.get(field);
                JsonNode bv = ob.get(field);
                if (av == null || bv == null) {
                    return path + "." + field + ": " + (av == null ? "缺失" : av) + " != " + (bv == null ? "缺失" : bv);
                }
                String deeper = firstDifference(av, bv, path + "." + field);
                if (deeper != null) {
                    return deeper;
                }
            }
            return path + ": 对象字段等价但节点不等（类型差异?）" + a + " != " + b;
        }
        if (a.isArray() && b.isArray() && a.size() == b.size()) {
            for (int i = 0; i < a.size(); i++) {
                String deeper = firstDifference(a.get(i), b.get(i), path + "[" + i + "]");
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return path + ": " + a + " != " + b;
    }

    private static List<String> unionFields(com.fasterxml.jackson.databind.node.ObjectNode a,
                                            com.fasterxml.jackson.databind.node.ObjectNode b) {
        List<String> fields = new ArrayList<>();
        a.fieldNames().forEachRemaining(fields::add);
        b.fieldNames().forEachRemaining(f -> {
            if (!fields.contains(f)) {
                fields.add(f);
            }
        });
        fields.sort(String::compareTo);
        return fields;
    }
}
