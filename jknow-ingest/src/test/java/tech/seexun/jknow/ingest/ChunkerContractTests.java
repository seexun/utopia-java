package tech.seexun.jknow.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 分块契约逐条断言（chunker.rs #[cfg(test)] 全套移植 + blocks.rs 表接回）。
 * 预算按 {@link TokenEstimator} 的近似计数断言（与 Rust cl100k 数值接近但非逐字）。
 */
class ChunkerContractTests {

    private static String slice(String text, ChunkPiece p) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, p.charStart(), p.charEnd() - p.charStart(), StandardCharsets.UTF_8);
    }

    // ---- 表 ----

    private static String table(int nRows) {
        StringBuilder s = new StringBuilder(
                "The results of the voting were as follows:\n\n| Nominee | For | Against |\n| --- | --- | --- |\n");
        for (int i = 0; i < nRows; i++) {
            s.append("| Director ").append(i).append(" | ").append(1000 + i).append(" | ")
                    .append(50 + i).append(" |\n");
        }
        return s.toString();
    }

    @Test
    void aTableThatFitsStaysWholeWithItsCaption() {
        String text = "Intro paragraph.\n\n" + table(3) + "\nClosing.";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 1000);
        assertEquals(1, pieces.size());
        String t = pieces.get(0).text();
        assertTrue(t.contains("The results of the voting were as follows:\n\n| Nominee"));
        assertTrue(t.endsWith("Closing."));
    }

    @Test
    void aWideTableIsSplitByRowsAndEveryPieceCarriesItsHeaderAndCaption() {
        String text = "# Item 5.07\n\n" + table(40);
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 120);
        assertTrue(pieces.size() > 2, "40 行在 120 token 里必然切成好几块: " + pieces.size());
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            ChunkPiece p = pieces.get(i);
            assertTrue(p.text().contains("| Nominee | For | Against |"), "第 " + i + " 块没有表头");
            assertTrue(p.text().contains("| --- | --- | --- |"), "第 " + i + " 块没有分隔行");
            assertTrue(p.text().contains("The results of the voting were as follows:"),
                    "第 " + i + " 块没有说明句");
            assertTrue(p.text().startsWith("# Item 5.07"), "第 " + i + " 块没有章节面包屑");
            assertEquals("Item 5.07", p.heading());
            all.append(p.text()).append('\n');
        }
        // 每一行只出现一次，一行不拆
        for (int i = 0; i < 40; i++) {
            int count = all.toString().split("\\| Director " + i + " \\|", -1).length - 1;
            assertEquals(1, count, "Director " + i);
        }
    }

    @Test
    void aCaptionNeverPartsFromItsTable() {
        String filler = "Words about nothing in particular. ".repeat(12);
        String text = filler + "\n\n" + table(4);
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 140);
        assertTrue(pieces.size() >= 2);
        ChunkPiece withTable = pieces.stream().filter(p -> p.text().contains("| Nominee |"))
                .findFirst().orElseThrow();
        assertTrue(withTable.text().contains("The results of the voting were as follows:"));
        for (ChunkPiece p : pieces) {
            if (!p.text().contains("| Nominee |")) {
                assertTrue(!p.text().contains("were as follows"), "说明句留在了没有表的块里");
            }
        }
    }

    @Test
    void aPageBreakBetweenACaptionAndItsTableDoesNotPartThem() {
        String filler = "Words about nothing in particular. ".repeat(10);
        String text = filler + "\n\n5. Stockholders did not approve the proposal. "
                + "The results of the voting were as follows:\n\n* * *\n\n"
                + "| Number of shares For | 144 |\n| --- | --- |\n| Number of shares Against | 16 |\n";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 110);
        ChunkPiece tablePiece = pieces.stream()
                .filter(p -> p.text().contains("| Number of shares For |")).findFirst().orElseThrow();
        assertTrue(tablePiece.text().contains("The results of the voting were as follows:"));
        for (ChunkPiece p : pieces) {
            if (!p.text().contains("| Number of shares For |")) {
                assertTrue(!p.text().contains("were as follows"), "说明句留在了没有表的块里");
            }
        }
    }

    @Test
    void aRunOfShortParagraphsBeforeATableIsItsCaption() {
        String text = "Prose that is long enough not to be a caption, going on about the quarter "
                + "and the outlook and the products and the customers.\n\n"
                + "NVIDIA CORPORATION\n\nCONDENSED CONSOLIDATED STATEMENTS OF INCOME\n\n(In millions)\n\n"
                + "(Unaudited)\n\n" + table(30);
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 120);
        List<ChunkPiece> withTable = pieces.stream().filter(p -> p.text().contains("| --- |"))
                .toList();
        assertTrue(withTable.size() >= 2, "表该被切成几块: " + pieces.size());
        for (ChunkPiece p : withTable) {
            assertTrue(p.text().contains("NVIDIA CORPORATION"), "每块都带公司名");
            assertTrue(p.text().contains("STATEMENTS OF INCOME"), "每块都带报表名");
            assertTrue(p.text().contains("(Unaudited)"), "每块都带最后一段");
            assertTrue(!p.text().contains("Prose that is long"), "长段落不是说明句");
        }
    }

    @Test
    void oneCaptionIntroducesTheWholeRunOfTablesAfterIt() {
        StringBuilder text = new StringBuilder(
                "Stockholders elected each of the director nominees. "
                        + "The results of the voting were as follows:\n\n");
        String[] names = {"Tench Coxe", "John Dabiri", "Jen-Hsun Huang", "Dawn Hudson", "Harvey Jones"};
        for (int i = 0; i < names.length; i++) {
            text.append("| ").append(names[i]).append(" |  |\n| --- | --- |\n")
                    .append("| Number of shares For | ").append(1000 + i).append(" |\n")
                    .append("| Number of shares Against | ").append(50 + i).append(" |\n\n");
        }
        text.append("Another matter entirely.\n\n| Unrelated | 1 |\n| --- | --- |\n| row | 2 |\n");
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text.toString(), 90);
        assertTrue(pieces.size() >= 3, "" + pieces.size());
        ChunkPiece huang = pieces.stream().filter(p -> p.text().contains("Jen-Hsun Huang"))
                .findFirst().orElseThrow(() -> new AssertionError("黄仁勋那块"));
        assertTrue(huang.text().contains("director nominees"), "续块丢了说明句");
        for (ChunkPiece p : pieces) {
            assertTrue(p.text().split("were as follows", -1).length - 1 <= 1, "同一块里说明念了不止一遍");
        }
        // 一串到别的东西出现为止：后面那张无关的表不带这句说明
        ChunkPiece unrelated = pieces.stream().filter(p -> p.text().contains("| Unrelated |"))
                .findFirst().orElseThrow();
        String before = unrelated.text()
                .substring(0, unrelated.text().indexOf("| Unrelated |"));
        assertTrue(!before.endsWith("follows:\n\n"));
        assertTrue(unrelated.text().contains("Another matter entirely."));
    }

    // ---- 标题/面包屑 ----

    @Test
    void aChunkOpensWithTheHeadingsItLivesUnder() {
        String text = "# Report\n\n## Part A\n\nFirst.\n\n## Part B\n\nSecond.\n\n### B.1\n\nThird.";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 1000);
        assertEquals(1, pieces.size());
        assertTrue(pieces.get(0).text().startsWith("# Report\n\n## Part A\n\nFirst."));
        assertEquals("Report › Part B › B.1", pieces.get(0).heading());

        // Rust 原测试预算 14 是 cl100k 尺度；任务 5 英文词单元校准后近似计数回到同尺度附近，
        // 取 13 保持「三节三块」（面包屑 "›" 计价仍比 cl100k 便宜，取略小预算补偿）
        pieces = Chunker.chunkWithBudget(text, 13);
        ChunkPiece third = pieces.stream().filter(p -> p.text().contains("Third.")).findFirst()
                .orElseThrow();
        assertTrue(third.text().startsWith("# Report\n\n## Part B\n\n### B.1"), third.text());
        assertEquals("Report › Part B › B.1", third.heading());
        assertEquals("### B.1\n\nThird.", slice(text, third));
    }

    @Test
    void aChunkIsNamedByTheSectionItHolds() {
        String text = "# Report\n\n## Part A\n\nFirst.\n\n## Part B\n\nSecond.\n\n### B.1\n\nThird.";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 13);
        List<String[]> named = new ArrayList<>();
        for (ChunkPiece p : pieces) {
            String[] lines = p.text().split("\n");
            named.add(new String[] {lines[lines.length - 1], p.heading()});
        }
        assertEquals(3, named.size());
        assertNamed(named.get(0), "First.", "Report › Part A");
        assertNamed(named.get(1), "Second.", "Report › Part B");
        assertNamed(named.get(2), "Third.", "Report › Part B › B.1");
    }

    private static void assertNamed(String[] actual, String lastLine, String heading) {
        assertEquals(lastLine, actual[0]);
        assertEquals(heading, actual[1]);
    }

    @Test
    void aDocumentThatEndsWithAHeadingKeepsItsLastText() {
        String text = "# Report\n\nFirst paragraph of the report.\n\n"
                + "Second paragraph of the report.\n\n## Appendix\n";
        List<ChunkPiece> pieces = Chunker.chunkText(text);
        assertEquals(2, pieces.size());
        assertTrue(pieces.get(0).text().contains("First paragraph of the report.")
                && pieces.get(0).text().contains("Second paragraph of the report."));
        assertEquals("Report", pieces.get(0).heading());
        assertEquals("# Report\n\n## Appendix", pieces.get(1).text().stripTrailing());
        assertEquals("Report › Appendix", pieces.get(1).heading());
        assertEquals("## Appendix", slice(text, pieces.get(1)).stripTrailing());
    }

    // ---- 行/段落 ----

    @Test
    void aLongParagraphStillSplitsBySentenceAndOffsetsAreVerbatim() {
        String text = "One sentence here. Another sentence follows it. ".repeat(30);
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 40);
        assertTrue(pieces.size() > 1);
        for (ChunkPiece p : pieces) {
            assertEquals(p.text(), slice(text, p));
            assertTrue(TokenEstimator.tokens(p.text()) <= 40);
        }
    }

    @Test
    void aChineseParagraphSplitsOnChineseTerminators() {
        String text = "巡星科技发布新一代地面站。该站支持多频段同时接收。部署周期缩短至两周。"
                .repeat(15);
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 60);
        assertTrue(pieces.size() > 1);
        for (ChunkPiece p : pieces) {
            assertEquals(p.text(), slice(text, p));
        }
    }

    @Test
    void chunksAreNumberedFromZeroAndNeverEmpty() {
        String text = "# T\n\nA.\n\n* * *\n\nB.\n\n| x | y |\n| --- | --- |\n| 1 | 2 |";
        List<ChunkPiece> pieces = Chunker.chunkText(text);
        assertEquals(1, pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            assertEquals(i, pieces.get(i).seq());
            assertTrue(!pieces.get(i).text().trim().isEmpty());
        }
        assertTrue(!pieces.get(0).text().contains("* * *"), "水平线不进块");
    }

    @Test
    void aRuleIsNotABoundary() {
        String text = "Alpha.\n\n* * *\n\nBeta.";
        assertEquals(1, Chunker.chunkWithBudget(text, 1000).size());
    }

    @Test
    void aPageNumberNeverBecomesAChunkOfItsOwn() {
        String body = "A sentence that fills the budget nicely. ".repeat(6);
        String text = body + "\n\n4";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text,
                TokenEstimator.tokens(body.strip()));
        assertEquals(1, pieces.size(), () -> pieces.get(0).text());
        assertTrue(pieces.get(0).text().endsWith("\n\n4"));
    }

    // ---- 出处（0040：一块只装一种出处，面包屑跨段照传） ----

    private static Provenance ocr(int page) {
        ObjectNode anchor = JsonNodeFactory.instance.objectNode();
        anchor.put("page", page);
        return new Provenance(Provenance.Origin.Ocr, "mineru", anchor);
    }

    private static Segment seg(int start, int end, Provenance provenance) {
        return new Segment(start, end, provenance);
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void aChunkHoldsOneProvenanceAndTheBreadcrumbCrossesIt() {
        String stated = "# Lease\n\nThe tenant pays rent monthly.\n\n";
        String page1 = "The rent is 1,000 per month.\n\n";
        String page2 = "4\n\n";
        String text = stated + page1 + page2;
        int a = bytes(stated);
        int b = a + bytes(page1);
        List<Segment> segments = List.of(seg(0, a, Provenance.stated()), seg(a, b, ocr(1)),
                seg(b, bytes(text), ocr(2)));
        List<ChunkPiece> chunks = Chunker.chunkSegments(text, segments, Chunker.BUDGET_TOKENS);
        assertEquals(3, chunks.size());
        assertEquals(Provenance.stated(), chunks.get(0).provenance());
        assertEquals(ocr(1), chunks.get(1).provenance());
        assertEquals(ocr(2), chunks.get(2).provenance()); // 页码留在自己的页上
        assertTrue(chunks.get(1).text().contains("1,000"));
        assertTrue(!chunks.get(0).text().contains("1,000"));
        assertEquals("Lease", chunks.get(1).heading());
        // 不分段的老路：整份都是原文
        assertTrue(Chunker.chunkWithBudget(text, Chunker.BUDGET_TOKENS).stream()
                .allMatch(c -> c.provenance().equals(Provenance.stated())));
    }

    @Test
    void aChunkClosedByAChangeOfProvenanceIsNamedByItsOwnSection() {
        String stated = "# Lease\n\nThe tenant pays rent monthly.\n\n## Schedule\n\n";
        String page = "The rent is 1,000 per month.\n\n";
        String text = stated + page;
        List<Segment> segments = List.of(seg(0, bytes(stated), Provenance.stated()),
                seg(bytes(stated), bytes(text), ocr(1)));
        List<ChunkPiece> chunks = Chunker.chunkSegments(text, segments, Chunker.BUDGET_TOKENS);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().contains("rent monthly"));
        assertEquals("Lease", chunks.get(0).heading());
        assertTrue(chunks.get(1).text().contains("## Schedule"));
        assertEquals("Lease › Schedule", chunks.get(1).heading());
    }

    // ---- blocks：表头表体分开 + 分页符接表 ----

    @Test
    void blocksSplitAHeaderlessTableContinuationByRule() {
        // 投票结果 8-K 的形状：分页符劈开一张表，后半截带着分隔行
        String text = "| g. Stephen C. Neal |  |\n| --- | --- |\n| Number of shares For | 14,573 |\n"
                + "| Number of shares Against | 2,234 |\n\n* * *\n\n"
                + "| Number of shares Abstaining | 49,064 |\n| --- | --- |\n"
                + "| Number of Broker Non-Votes | 2,829 |\n";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 1000);
        assertEquals(1, pieces.size()); // 接回一张表 + 一块
        // 接回之后「弃权」是前一张表的体行：Against 与 Abstaining 相邻成行（不是隔一张新表）
        assertTrue(pieces.get(0).text()
                .contains("| Number of shares Against | 2,234 |\n| Number of shares Abstaining | 49,064 |"),
                pieces.get(0).text());
        assertTrue(pieces.get(0).text()
                .contains("| Number of shares Abstaining | 49,064 |\n| Number of Broker Non-Votes | 2,829 |"));
    }

    @Test
    void twoTablesWithNumericHeadersStayTwoTables() {
        String text = "| Revenue | 2025 |\n| --- | --- |\n| Total | 10 |\n\n* * *\n\n"
                + "| 2024 | 2023 |\n| --- | --- |\n| Total | 9 |\n";
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 1000);
        // 不接：两张表两个单元，但预算够大仍一块收
        assertEquals(1, pieces.size());
        assertTrue(pieces.get(0).text().contains("| 2024 | 2023 |"));
    }

    // ---- char_start/end 只指正文（前缀不在内） ----

    @Test
    void offsetsPointAtTheBodyNotThePrefix() {
        String text = "# Chapter\n\n" + "Filler paragraph. ".repeat(20);
        List<ChunkPiece> pieces = Chunker.chunkWithBudget(text, 30);
        assertTrue(pieces.size() >= 2);
        for (ChunkPiece p : pieces) {
            String body = slice(text, p);
            // 第一块的正文以贴进来的标题开头（标题贴下段），后续块以正文开头——都不含面包屑前缀
            assertTrue(body.startsWith("Filler") || body.startsWith("# Chapter"),
                    "正文不该含面包屑: " + body);
            assertTrue(p.text().endsWith(body), "块文本应是前缀+正文");
        }
        assertEquals("Chapter", pieces.get(0).heading());
        assertNotNull(pieces.get(0).heading());
    }
}
