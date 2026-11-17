package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 快照样本四分类比对 TDD（M3 任务 1）：Diff --dual 对 {@code .snapshot.json} 的裁决——
 * 一致 / 挂账 WAIVE / 单侧 SIDE / 差异。已知差异注入（改一行 / 加一行 / 浮点末位真差异）
 * 必须逐一被抓到并正确分类；挂账机制对快照步照常生效；快照缺一侧 = 差异（不是单侧跳过）。
 */
class DiffSnapshotTests {

    private static final String SNAPSHOT_A = """
            {
              "meta": { "scenario": "heart-ledger", "step": "ledger-snapshot", "method": "SNAPSHOT",
                        "path": "${kb_id}", "target": "both", "kind": "snapshot", "status": 200 },
              "snapshot": { "tables": { "facts": [
                    { "confidence": 0.9, "kb_id": "<kb>", "subject_id": "<id:1>",
                      "predicate_id": "<id:2>", "object_id": "<id:3>", "recorded_at": "<t:2>" }
                  ], "entities": [
                    { "canonical_name": "Cara", "id": "<id:1>", "kb_id": "<kb>", "created_at": "<t:1>" }
                  ] } }
            }
            """;

    /** 快照 JSON 文件对（golden-rust / golden-java 两目录）。 */
    private record DualDirs(Path left, Path right) {
    }

    private static DualDirs dirs(String leftSnapshot, String rightSnapshot) throws Exception {
        Path root = Files.createTempDirectory("diff-snapshot");
        Path l = root.resolve("golden-rust/heart-ledger");
        Path r = root.resolve("golden-java/heart-ledger");
        Files.createDirectories(l);
        Files.createDirectories(r);
        Files.writeString(l.resolve("ledger-snapshot.snapshot.json"), leftSnapshot);
        Files.writeString(r.resolve("ledger-snapshot.snapshot.json"), rightSnapshot);
        return new DualDirs(root.resolve("golden-rust"), root.resolve("golden-java"));
    }

    /** 两侧同内容 → 一致（无差异、无单侧、计入快照比对数）。 */
    @Test void 双侧快照一致() throws Exception {
        DualDirs d = dirs(SNAPSHOT_A, SNAPSHOT_A);
        Diff.Report report = Diff.compareDirectories(d.left(), d.right(), Waivers.fromArgs(List.of()));
        assertFalse(report.hasDiffs());
        assertEquals(1, report.snapshotCompared());
        assertEquals(1, report.compared());
        assertTrue(report.singleSided().isEmpty());
    }

    /** 注入差异 1——改一行（confidence 0.9 → 0.8）：抓到并分类为差异。 */
    @Test void 注入改行差异分类为差异() throws Exception {
        String mutated = SNAPSHOT_A.replace("\"confidence\": 0.9", "\"confidence\": 0.8");
        DualDirs d = dirs(SNAPSHOT_A, mutated);
        Diff.Report report = Diff.compareDirectories(d.left(), d.right(), Waivers.fromArgs(List.of()));
        assertTrue(report.hasDiffs());
        assertTrue(report.diffs().get(0).contains("confidence"), report.diffs().toString());
    }

    /** 注入差异 2——加一行（多一个实体）：数组长度不同，抓到。 */
    @Test void 注入加行差异分类为差异() throws Exception {
        String mutated = SNAPSHOT_A.replace(
                "{ \"canonical_name\": \"Cara\", \"id\": \"<id:1>\", \"kb_id\": \"<kb>\", \"created_at\": \"<t:1>\" }",
                "{ \"canonical_name\": \"Cara\", \"id\": \"<id:1>\", \"kb_id\": \"<kb>\", \"created_at\": \"<t:1>\" },\n"
                        + "                    { \"canonical_name\": \"Gamma\", \"id\": \"<id:4>\", \"kb_id\": \"<kb>\", \"created_at\": \"<t:1>\" }");
        DualDirs d = dirs(SNAPSHOT_A, mutated);
        Diff.Report report = Diff.compareDirectories(d.left(), d.right(), Waivers.fromArgs(List.of()));
        assertTrue(report.hasDiffs());
    }

    /**
     * 注入差异 3——浮点末位真差异：归一化容差是 6 位小数（0.9 == 0.90000001），
     * 容差内的末位差在 dump 时已抹平；<b>文件里仍不同的浮点</b>（0.9 vs 0.900001）
     * 是容差外的真差异，Diff 必须抓到。
     */
    @Test void 注入浮点真差异分类为差异() throws Exception {
        String mutated = SNAPSHOT_A.replace("\"confidence\": 0.9", "\"confidence\": 0.900001");
        DualDirs d = dirs(SNAPSHOT_A, mutated);
        Diff.Report report = Diff.compareDirectories(d.left(), d.right(), Waivers.fromArgs(List.of()));
        assertTrue(report.hasDiffs());
    }

    /** 挂账机制对快照步生效：命中键跳过比对、单列报告（不算差异也不算通过）。 */
    @Test void 挂账对快照步生效() throws Exception {
        String mutated = SNAPSHOT_A.replace("\"confidence\": 0.9", "\"confidence\": 0.8");
        DualDirs d = dirs(SNAPSHOT_A, mutated);
        Waivers waivers = Waivers.fromArgs(List.of("heart-ledger/ledger-snapshot.snapshot.json#heart-m3-pending"));
        Diff.Report report = Diff.compareDirectories(d.left(), d.right(), waivers);
        assertFalse(report.hasDiffs(), "挂账样本不算差异");
        assertEquals(1, report.waived().size());
        assertTrue(report.waived().get(0).contains("heart-m3-pending"));
        assertEquals(0, report.compared());
    }

    /** 快照缺 java 侧 = 差异（只跑了一轮录制；快照是两侧各自的证据，不是定向单侧样本）。 */
    @Test void 快照缺右侧是差异不是单侧() throws Exception {
        Path root = Files.createTempDirectory("diff-snapshot");
        Path l = root.resolve("golden-rust/heart-ledger");
        Files.createDirectories(l);
        Files.writeString(l.resolve("ledger-snapshot.snapshot.json"), SNAPSHOT_A);
        Path r = root.resolve("golden-java/heart-ledger");
        Files.createDirectories(r); // 空：java 侧未录
        Diff.Report report = Diff.compareDirectories(l.getParent(), r.getParent(), Waivers.fromArgs(List.of()));
        assertTrue(report.hasDiffs());
        assertTrue(report.diffs().get(0).contains("仅左侧存在"));
        assertTrue(report.singleSided().isEmpty(), "快照缺失不得按单侧跳过");
    }

    /** HTTP 定向样本的单侧规则不受快照影响：target=rust 仅左侧仍是 SIDE 跳过。 */
    @Test void HTTP定向单侧样本仍单侧跳过() throws Exception {
        Path root = Files.createTempDirectory("diff-snapshot");
        Path l = root.resolve("golden-rust/heart-ledger");
        Files.createDirectories(l);
        Files.writeString(l.resolve("login.json"),
                "{\"meta\":{\"target\":\"rust\"},\"normalized\":{}}");
        Path r = root.resolve("golden-java/heart-ledger");
        Files.createDirectories(r);
        Diff.Report report = Diff.compareDirectories(l.getParent(), r.getParent(), Waivers.fromArgs(List.of()));
        assertFalse(report.hasDiffs());
        assertEquals(1, report.singleSided().size());
    }
}
