package tech.seexun.jknow.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 分块对齐验证（任务 2 验收 + 任务 5 英文留出集）：同一批文档，Rust cl100k 300-token
 * （fixture JSON，生成方式见 {@link AlignmentFixtureGenTests}——三个中文 md 是共享库真实文档，
 * chunks 表已核对一致；英文 prose md 是任务 5 的校准留出集）对比 Java 近似计数分块。
 * **块数与边界不必逐字节相同**（BPE vs 近似计数），但每篇块数差 ≤15%，
 * 且每块起止落在行边界。对照表随测试打印（报告转录）。
 */
class ChunkAlignmentTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path INPUTS = Path.of("src", "test", "resources", "alignment", "inputs");
    private static final Path RUST = Path.of("src", "test", "resources", "alignment", "rust");

    @ParameterizedTest
    @ValueSource(strings = {"星野科技公司简介.md", "B轮融资公告.md", "巡星2号产品说明.md",
        "align-financials.csv", "align-report.docx", "align-article.html",
        "stellar-ground-stations.md"})
    void javaChunkingAlignsWithRustCl100k(String filename) throws IOException {
        byte[] input = Files.readAllBytes(INPUTS.resolve(filename));
        JsonNode rust = JSON.readTree(Files.readString(RUST.resolve(filename + ".json"),
                StandardCharsets.UTF_8));
        assertEquals("ok", rust.path("parse").asText(),
                "Rust fixture must parse: " + rust.path("error").asText());
        int rustCount = rust.path("chunks").size();

        // Java 侧与管线同序：parse → 剥 NUL → chunk(300)
        var parsed = assertInstanceOf(ParseResult.Text.class,
                DocumentParser.parse(filename, input));
        String text = Normalizer.withoutNul(parsed.text());
        List<ChunkPiece> java = Chunker.chunkWithBudget(text, Chunker.BUDGET_TOKENS);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        // 对照表先打印（诊断与报告用），断言随后
        double diff = Math.abs(java.size() - rustCount) * 100.0 / Math.max(1, rustCount);
        System.out.printf("%-24s java=%-2d rust=%-2d diff=%.0f%% bytes=%d%n", filename,
                java.size(), rustCount, diff, bytes.length);
        for (int i = 0; i < Math.max(java.size(), rustCount); i++) {
            String j = i < java.size()
                    ? "%d[%d..%d]%s".formatted(java.get(i).seq(), java.get(i).charStart(),
                            java.get(i).charEnd(), java.get(i).heading() == null ? "-" : "✓")
                    : "—";
            JsonNode r = rust.path("chunks").path(i);
            String rs = i < rustCount
                    ? "%d[%d..%d]%s".formatted(r.path("seq").asInt(), r.path("char_start").asInt(),
                            r.path("char_end").asInt(), r.path("heading").asText(null) == null ? "-"
                                    : "✓")
                    : "—";
            System.out.printf("    java %s | rust %s%n", j, rs);
        }

        // 1) 块数差 ≤15%（对 Rust 计数为基）
        assertTrue(diff <= 15.0,
                () -> "%s: java=%d rust=%d diff=%.1f%%".formatted(filename, java.size(), rustCount,
                        diff));

        // 2) 每块起止在行边界、seq 连号、起点不回退。表续块与 Rust 一样重复表头/说明句：
        //    span 回到表头（区间共享是设计，Rust chunks 表里 csv 续块 char_start 全为 0 同此），
        //    文本≠切片，故 endsWith 只对起点未回退的块断言；起点回退的块只查边界与命名。
        int lastStart = 0;
        int lastEnd = 0;
        for (int i = 0; i < java.size(); i++) {
            ChunkPiece p = java.get(i);
            assertEquals(i, p.seq());
            assertTrue(p.charStart() == 0 || bytes[p.charStart() - 1] == '\n',
                    "%s chunk %d start %d 不在行边界".formatted(filename, i, p.charStart()));
            assertTrue(p.charEnd() == bytes.length || bytes[p.charEnd()] == '\n',
                    "%s chunk %d end %d 不在行边界".formatted(filename, i, p.charEnd()));
            assertTrue(p.charStart() >= lastStart, "%s chunk %d 起点回退".formatted(filename, i));
            lastStart = p.charStart();
            assertTrue(p.charEnd() >= p.charStart(), "%s chunk %d 区间为负".formatted(filename, i));
            if (p.charStart() >= lastEnd) {
                String body = new String(bytes, p.charStart(), p.charEnd() - p.charStart(),
                        StandardCharsets.UTF_8);
                assertTrue(p.text().endsWith(body),
                        "%s chunk %d 前缀+正文切片不符".formatted(filename, i));
            }
            lastEnd = Math.max(lastEnd, p.charEnd());
        }

        // 3) 章节命名一致（首末块的面包屑）：切点可漂移，章节路径不应
        JsonNode rustFirst = rust.path("chunks").path(0);
        JsonNode rustLast = rust.path("chunks").path(rustCount - 1);
        assertEquals(rustFirst.path("heading").asText(null), java.get(0).heading(),
                filename + " 首块面包屑");
        assertEquals(rustLast.path("heading").asText(null), java.get(java.size() - 1).heading(),
                filename + " 末块面包屑");
    }
}
