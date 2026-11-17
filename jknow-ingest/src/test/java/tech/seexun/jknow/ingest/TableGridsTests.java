package tech.seexun.jknow.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * 表格网格渲染器（table.rs:679-874 mod tests 全量移植——lift_tables/render_grid/
 * grid_or_lines/restore 形状的断言逐字；嵌套 span/缺列补空的复杂表另加两例）。
 */
class TableGridsTests {

    private static String render(String html) {
        Document doc = Jsoup.parse(html);
        StringBuilder out = new StringBuilder();
        for (Element tbl : doc.select("table")) {
            boolean nested = tbl.parents().stream()
                    .anyMatch(p -> p.tagName().equalsIgnoreCase("table"));
            boolean hasInner = tbl.select("table").stream().anyMatch(e -> e != tbl);
            if (nested || hasInner) {
                continue;
            }
            TableGrids.RenderedTable rendered = TableGrids.renderTable(tbl, List.of());
            if (rendered != null) {
                out.append(rendered.markdown()).append("\n=====\n");
            }
        }
        return out.toString();
    }

    private static TableGrids.GridRow row(String... cells) {
        // 每格两个值：text 与 span（pad 缺省 0）；单值 = span 1
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String cell : cells) {
            String[] parts = cell.split("@", 2);
            out.add(new String[] {parts[0], parts.length > 1 ? parts[1] : "1", "0"});
        }
        return new TableGrids.GridRow(out);
    }

    /** table.rs:689-728：不是表的网格按行留字（docx/pptx 侧）。 */
    @Test
    void aGridThatIsNotATableKeepsItsWordsAsLines() {
        TableGrids.GridRow oneColumnA = row("Action");
        TableGrids.GridRow empty = row("");
        TableGrids.GridRow approve = row("Approve contract");
        TableGrids.GridRow renew = row("Renew license");
        List<TableGrids.GridRow> oneColumn = List.of(oneColumnA, empty, approve, renew);
        for (boolean firstIsHeader : new boolean[] {false, true}) {
            assertNull(TableGrids.renderGrid(oneColumn, firstIsHeader));
            assertEquals("Action\n\nApprove contract\n\nRenew license",
                    TableGrids.gridOrLines(oneColumn, firstIsHeader));
        }
        // 两列只有左边有字；一格跨整排的告示
        List<TableGrids.GridRow> leftOnly = List.of(row("Action", ""), row("Approve  contract", ""));
        assertEquals("Action\n\nApprove contract",
                TableGrids.gridOrLines(leftOnly, false));
        assertEquals("Notice for the board",
                TableGrids.gridOrLines(List.of(row("Notice for the board@2")), false));
        // 一个字都没有的网格什么都不写；真正的表照旧是表
        assertNull(TableGrids.gridOrLines(List.of(row("")), false));
        List<TableGrids.GridRow> table = List.of(row("Item", "2025"), row("Revenue", "10"));
        assertEquals(TableGrids.gridOrLines(table, true), TableGrids.renderGrid(table, true));
        assertTrue(TableGrids.gridOrLines(table, true).startsWith("| Item | 2025 |"));
    }

    /** table.rs:731-737：单列 HTML 表多半是排版用的——留给页面转换器。 */
    @Test
    void aSingleColumnHtmlTableIsLeftToThePageConverter() {
        String html = "<table><tr><td><h2>To our shareholders</h2><p>Revenue grew.</p></td>"
                + "</tr><tr><td><p>We opened three offices.</p></td></tr></table>";
        assertEquals("", render(html));
    }

    /** table.rs:740-777：NVIDIA 财报表——真列头/小节折进标签/符号并格（核心用例）。 */
    @Test
    void aFilingTableGetsItsRealHeadersAndItsSectionPath() {
        String html = """
                <table>
                  <tr><td style="width:0.1%"></td><td style="width:40%"></td><td style="width:0.1%"></td><td style="width:1%"></td><td style="width:20%"></td><td style="width:1%"></td><td style="width:1%"></td><td style="width:20%"></td><td style="width:0.1%"></td><td colspan="3" style="display:none"></td></tr>
                  <tr><td colspan="9" style="text-align:center">NVIDIA CORPORATION</td><td colspan="3" style="display:none"></td></tr>
                  <tr><td colspan="9" style="text-align:center">CONDENSED CONSOLIDATED BALANCE SHEETS</td></tr>
                  <tr><td colspan="9" style="text-align:center">(In millions)</td></tr>
                  <tr><td colspan="3"></td><td colspan="3" style="text-align:center">July 26,</td><td colspan="3" style="text-align:center">January 25,</td></tr>
                  <tr><td colspan="3"></td><td colspan="3" style="text-align:center">2026</td><td colspan="3" style="text-align:center">2026</td></tr>
                  <tr><td colspan="3" style="padding:2pt 1pt 2pt 6pt">Current assets:</td><td colspan="3"></td><td colspan="3"></td></tr>
                  <tr><td colspan="3" style="padding:2pt 1pt 2pt 12pt">Accounts receivable, net</td><td>$</td><td>63,059</td><td></td><td>$</td><td>38,466</td><td></td></tr>
                  <tr><td colspan="3" style="padding:2pt 1pt 2pt 12pt">Inventories</td><td></td><td>31,575</td><td></td><td></td><td>21,403</td><td></td></tr>
                  <tr><td colspan="3" style="padding:2pt 1pt 2pt 6pt">Total current assets</td><td></td><td>197,412</td><td></td><td></td><td>125,605</td><td></td></tr>
                </table>""";
        String md = render(html);
        assertTrue(md.startsWith(
                "NVIDIA CORPORATION · CONDENSED CONSOLIDATED BALANCE SHEETS · (In millions):\n"),
                "标题行提成表前的说明句: " + md);
        assertTrue(md.contains("|  | July 26, 2026 | January 25, 2026 |"),
                "两行列头拼成一行: " + md);
        assertTrue(md.contains("| Current assets › Accounts receivable, net | $63,059 | $38,466 |"),
                "小节折进标签、美元符并回数字: " + md);
        assertTrue(md.contains("| Current assets › Inventories | 31,575 | 21,403 |"), md);
        assertTrue(md.contains("| Total current assets | 197,412 | 125,605 |"),
                "合计行回到小节外: " + md);
        assertFalse(md.contains("Current assets:"), "小节行自己不再出现: " + md);
    }

    /** table.rs:780-794：单个标签在一切之前是标题；行保住自己的值。 */
    @Test
    void aLoneLabelBeforeEverythingIsTheCaptionAndRowsKeepTheirValues() {
        String html = "<table><tr><td colspan=\"2\">a. Tench Coxe</td></tr>"
                + "<tr><td>Number of shares For</td><td>15,411,252,412</td></tr>"
                + "<tr><td>Number of shares Against</td><td>1,399,727</td></tr></table>";
        String md = render(html);
        assertTrue(md.startsWith("a. Tench Coxe:\n"), md);
        assertTrue(md.contains("| Number of shares For | 15,411,252,412 |"), md);
        assertTrue(md.contains("| Number of shares Against | 1,399,727 |"), md);
    }

    /** table.rs:797-803：没表头的表第一排词就是列头。 */
    @Test
    void aFirstRowOfWordsIsTheHeaderWhenTheTableHasNone() {
        String html = "<table><tr><td>Revenue</td><td>Q2 FY27</td><td>Q1 FY27</td></tr>"
                + "<tr><td>total</td><td>$96,221</td><td>$81,615</td></tr></table>";
        String md = render(html);
        assertTrue(md.contains("| Revenue | Q2 FY27 | Q1 FY27 |"), md);
        assertTrue(md.contains("| total | $96,221 | $81,615 |"), md);
    }

    /** table.rs:806-811：display:none 的格与空列不进结果。 */
    @Test
    void hiddenCellsAndEmptyColumnsDoNotSurvive() {
        String html = "<table><tr><td>Margin</td>"
                + "<td style=\"display:none\">ghost</td><td></td><td>75.0</td><td></td>"
                + "</tr></table>";
        String md = render(html);
        assertFalse(md.contains("ghost"), md);
        assertTrue(md.contains("| Margin | 75.0 |"), md);
    }

    /** table.rs:814-819：右括号单独一格并回数字。 */
    @Test
    void aClosingParenInItsOwnCellRejoinsTheNumber() {
        String html = "<table><tr><td>Other</td><td>(5,497</td><td>)</td><td>387</td>"
                + "<td></td></tr></table>";
        String md = render(html);
        assertTrue(md.contains("| Other | (5,497) | 387 |"), md);
    }

    /** table.rs:822-827：破折号是「空值」占位符，留在自己那格。 */
    @Test
    void aDashThatMeansNothingStaysInItsOwnCell() {
        String html = "<table><tr><td>Other</td><td>112</td><td>—</td><td>31</td><td>—</td>"
                + "</tr></table>";
        String md = render(html);
        assertTrue(md.contains("| Other | 112 | — | 31 | — |"), md);
    }

    /** table.rs:830-854：分页符劈开的续表接着用前一张的列头（lift_tables 状态传递）。 */
    @Test
    void aHeaderlessTableAfterAPageBreakTakesTheHeadersBeforeIt() {
        String html = "<table>"
                + "<tr><td></td><td>Q2 FY27</td><td>Q1 FY27</td></tr>"
                + "<tr><td>Revenue</td><td>96,221</td><td>81,615</td></tr></table>"
                + "<hr>"
                + "<table><tr><td colspan=\"3\">Cash flows from financing activities:</td></tr>"
                + "<tr><td>Dividends paid</td><td>(6,047)</td><td>(244)</td></tr></table>";
        Document doc = Jsoup.parse(html);
        List<String> tables = new java.util.ArrayList<>();
        List<String> lastHeaders = new java.util.ArrayList<>();
        for (Element tbl : doc.select("table")) {
            boolean nested = tbl.parents().stream()
                    .anyMatch(p -> p.tagName().equalsIgnoreCase("table"));
            boolean hasInner = tbl.select("table").stream().anyMatch(e -> e != tbl);
            if (nested || hasInner) {
                continue;
            }
            TableGrids.RenderedTable rendered = TableGrids.renderTable(tbl, lastHeaders);
            if (rendered != null) {
                tables.add(rendered.markdown());
                lastHeaders = rendered.headers();
            }
        }
        assertEquals(2, tables.size(), String.valueOf(tables));
        assertTrue(tables.get(1).startsWith("Cash flows from financing activities:\n"),
                tables.get(1));
        assertTrue(tables.get(1).contains("|  | Q2 FY27 | Q1 FY27 |"),
                "续表接着用前一张的列头: " + tables.get(1));
        assertTrue(tables.get(1).contains("| Dividends paid | (6,047) | (244) |"), tables.get(1));
    }

    /** table.rs:857-863：嵌套表留给页面转换器。 */
    @Test
    void nestedTablesAreLeftToTheMarkdownConverter() {
        String html = "<table><tr><td><table><tr><td>inner</td><td>1</td></tr></table>"
                + "</td></tr></table>";
        assertEquals("", render(html));
    }

    /** 复杂表附加 1：rowspan 跨行把同一格带到后续行（table.rs grid 的 carry 语义）。 */
    @Test
    void aRowspanCarriesItsCellIntoTheFollowingRows() {
        String html = "<table>"
                + "<tr><th rowspan=\"2\">项目</th><th>2025</th></tr>"
                + "<tr><td>118</td></tr>"
                + "</table>";
        String md = render(html);
        // rowspan=2 的「项目」盖住第 0 列两行；第二行的 118 落在值列
        assertTrue(md.contains("| 项目 | 2025 |"), md);
        assertTrue(md.contains("| 项目 | 118 |"), md);
    }

    /** 复杂表附加 2：行缺列补空（网格对齐——空格补齐到列头数，不挪位）。 */
    @Test
    void aRowWithFewerCellsIsPaddedNotShifted() {
        String html = "<table><tr><td>Item</td><td>2025</td><td>2026</td></tr>"
                + "<tr><td>Revenue</td><td>10</td></tr></table>";
        String md = render(html);
        // Revenue 行缺 2026 列：补空而不是把 10 挪到 2026
        assertTrue(md.contains("| Revenue | 10 |  |"), md);
    }
}
