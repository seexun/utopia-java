package tech.seexun.jknow.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * xlsx / xls / ods（parsers.rs spreadsheet 的对等）：每 sheet 一节（# Sheet: 名），
 * 限前 2000 行（超出告警 spreadsheet.rows_truncated），网格渲染成 Markdown 表。
 * xlsx/xls 走 POI；ods POI 不支持，走 zip+StAX 读 content.xml（table:table 行列）。
 */
final class SpreadsheetText {

    static final int ROW_LIMIT = 2_000;

    private SpreadsheetText() {
    }

    static CsvText.Result parse(byte[] bytes, String ext) throws IOException {
        List<ParseWarning> warnings = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        switch (ext) {
            case "ods" -> ods(bytes, out, warnings);
            default -> poi(bytes, out, warnings);
        }
        return new CsvText.Result(out.toString(), warnings);
    }

    // ---- POI（xlsx=XSSF / xls=HSSF，WorkbookFactory 认） ----

    private static void poi(byte[] bytes, StringBuilder out, List<ParseWarning> warnings)
            throws IOException {
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < book.getNumberOfSheets(); s++) {
                Sheet sheet = book.getSheetAt(s);
                // xlsx/xls 把 merge 单独给出；ods 的 StAX 路径没有这一步，按无 merge 读
                List<org.apache.poi.ss.util.CellRangeAddress> merges =
                        new ArrayList<>(sheet.getMergedRegions());
                Grid grid = spreadsheetGrid(sheet, merges, formatter);
                int total = sheet.getPhysicalNumberOfRows();
                appendSheet(out, warnings, book.getSheetName(s), grid, total);
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to open spreadsheet: " + e.getMessage(), e);
        }
    }

    /** (行, 格, span) 的网格 + merge 推出的表头区间（parsers.rs:834-925 spreadsheet_grid）。 */
    private static Grid spreadsheetGrid(Sheet sheet,
            List<org.apache.poi.ss.util.CellRangeAddress> merges, DataFormatter formatter) {
        int height = Math.min(sheet.getLastRowNum() + 1, ROW_LIMIT);
        int width = 0;
        for (int r = 0; r < height; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > width) {
                width = row.getLastCellNum();
            }
        }
        if (height == 0 || width == 0) {
            return new Grid(new ArrayList<>(), null);
        }
        // merge 区：起点在网格内的收进来，owner 标记每个格子属于哪个区
        List<int[]> regions = new ArrayList<>(); // {r1, c1, r2, c2}
        List<String> regionText = new ArrayList<>();
        for (org.apache.poi.ss.util.CellRangeAddress m : merges) {
            int r1 = Math.max(0, m.getFirstRow());
            int c1 = Math.max(0, m.getFirstColumn());
            int r2 = Math.min(height - 1, m.getLastRow());
            int c2 = Math.min(width - 1, m.getLastColumn());
            if (r1 > r2 || c1 > c2) {
                continue;
            }
            regions.add(new int[] {r1, c1, r2, c2});
            Row row = sheet.getRow(r1);
            Cell cell = row == null ? null : row.getCell(c1);
            regionText.add(cell == null ? "" : cellText(cell, formatter));
        }
        int[] owner = new int[height * width];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < regions.size(); i++) {
            int[] rg = regions.get(i);
            for (int r = rg[0]; r <= rg[2]; r++) {
                for (int c = rg[1]; c <= rg[3]; c++) {
                    owner[r * width + c] = i;
                }
            }
        }
        List<TableGrids.GridRow> rows = new ArrayList<>(height);
        for (int r = 0; r < height; r++) {
            List<String[]> cells = new ArrayList<>();
            int c = 0;
            while (c < width) {
                int index = owner[r * width + c];
                if (index < 0) {
                    Row row = sheet.getRow(r);
                    Cell cell = row == null ? null : row.getCell(c);
                    cells.add(new String[] {
                            cell == null ? "" : cellText(cell, formatter), "1", "0"});
                    c++;
                    continue;
                }
                int[] rg = regions.get(index);
                if (c == rg[1]) {
                    cells.add(new String[] {regionText.get(index),
                            String.valueOf(rg[3] - rg[1] + 1), "0"});
                    c = rg[3] + 1;
                } else {
                    c++;
                }
            }
            rows.add(new TableGrids.GridRow(cells));
        }
        return new Grid(rows, spreadsheetHeaders(regions, rows));
    }

    /** 表头从第一排至少两格有字的行开始；纵向 merge 伸到的行也是表头（parsers.rs:928-940）。 */
    private static int[] spreadsheetHeaders(List<int[]> regions, List<TableGrids.GridRow> rows) {
        int start = -1;
        for (int i = 0; i < rows.size(); i++) {
            int filled = 0;
            for (String[] cell : rows.get(i).cells()) {
                if (!cell[0].isEmpty()) {
                    filled++;
                }
            }
            if (filled >= 2) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (true) {
            int next = -1;
            for (int[] rg : regions) {
                if (rg[0] >= start && rg[0] < end && rg[2] >= end) {
                    next = Math.max(next, rg[2] + 1);
                }
            }
            if (next < 0) {
                break;
            }
            end = next;
        }
        return new int[] {start, Math.min(end, rows.size())};
    }

    record Grid(List<TableGrids.GridRow> rows, int[] headers) {
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime dt = DateUtil.getLocalDateTime(v);
                if (dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                    return dt.toLocalDate().toString();
                }
                return dt.getSecond() == 0
                        ? dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        : dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return excelNumber(v);
        }
        String text = formatter.formatCellValue(cell);
        return text == null ? "" : text;
    }

    /** 数字按 Excel 认的精度写：15 位有效数字（parsers.rs excel_number 同一招）。 */
    private static String excelNumber(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        BigDecimal bd = new BigDecimal(String.format(Locale.ROOT, "%.14e", value));
        String s = bd.stripTrailingZeros().toPlainString();
        return s;
    }

    // ---- ODS：zip 里的 content.xml，StAX 读 table:table ----

    private static void ods(byte[] bytes, StringBuilder out, List<ParseWarning> warnings)
            throws IOException {
        try (java.util.zip.ZipInputStream zip =
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            byte[] content = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("content.xml")) {
                    content = zip.readAllBytes();
                    break;
                }
            }
            if (content == null) {
                throw new IOException("Malformed ods: no content.xml");
            }
            readOdsContent(new String(content, java.nio.charset.StandardCharsets.UTF_8), out,
                    warnings);
        }
    }

    private static void readOdsContent(String xml, StringBuilder out,
            List<ParseWarning> warnings) throws IOException {
        try {
            XMLStreamReader r = XMLInputFactory.newFactory().createXMLStreamReader(
                    new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            List<List<String>> rows = null;
            List<String> row = null;
            StringBuilder text = null;
            String sheetName = null;
            int totalRows = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "table" -> {
                            sheetName = attr(r, "name");
                            rows = new ArrayList<>();
                            totalRows = 0;
                        }
                        case "table-row" -> row = new ArrayList<>();
                        case "table-cell" -> {
                            int repeat = intAttr(r, "number-columns-repeated", 1);
                            text = new StringBuilder();
                            if (row != null) {
                                for (int i = 1; i < repeat; i++) {
                                    row.add("");
                                }
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    if (text != null) {
                        text.append(r.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    switch (local) {
                        case "p" -> {
                            // 一格里的多个段落用空格接（格内换行不拆行）
                            if (text != null && !text.isEmpty()) {
                                text.append(' ');
                            }
                        }
                        case "table-cell" -> {
                            if (row != null && text != null) {
                                row.add(MarkdownTables.clean(text.toString()));
                            }
                            text = null;
                        }
                        case "table-row" -> {
                            if (rows != null && row != null) {
                                totalRows++;
                                if (rows.size() < ROW_LIMIT
                                        && row.stream().anyMatch(c -> !c.isEmpty())) {
                                    rows.add(row);
                                }
                            }
                            row = null;
                        }
                        case "table" -> {
                            appendSheet(out, warnings, sheetName, odsGrid(rows), totalRows);
                            rows = null;
                        }
                        default -> {
                        }
                    }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException("Malformed ods structure: " + e.getMessage(), e);
        }
    }

    /** ods 无 merge 信息：一格一列、无表头区间（表头行由 render_grid 自行分类）。 */
    private static Grid odsGrid(List<List<String>> rows) {
        List<TableGrids.GridRow> out = new ArrayList<>();
        for (List<String> row : rows) {
            List<String[]> cells = new ArrayList<>();
            for (String cell : row) {
                cells.add(new String[] {cell, "1", "0"});
            }
            out.add(new TableGrids.GridRow(cells));
        }
        return new Grid(out, null);
    }

    private static String attr(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(name)) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static int intAttr(XMLStreamReader r, String name, int dflt) {
        String v = attr(r, name);
        try {
            return v == null ? dflt : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ---- 公共：一节 sheet 的输出 ----

    private static void appendSheet(StringBuilder out, List<ParseWarning> warnings, String name,
            Grid grid, int totalRows) {
        if (grid == null || grid.rows().isEmpty()) {
            return;
        }
        if (totalRows > ROW_LIMIT) {
            warnings.add(new ParseWarning(ParseWarning.SPREADSHEET_ROWS_TRUNCATED, Json.values(
                    new String[][] {{"sheet", name == null ? "" : name},
                            {"rows_read", String.valueOf(Math.min(totalRows, ROW_LIMIT))},
                            {"rows_total", String.valueOf(totalRows)},
                            {"rows_omitted", String.valueOf(totalRows - ROW_LIMIT)}})));
        }
        out.append("\n# Sheet: ").append(name == null ? "" : name).append("\n\n");
        // 每张表按网格渲染成带列头的 Markdown 表；一格字都没有的表退回制表符分隔
        String md = TableGrids.renderGridWithHeaders(grid.rows(), grid.headers());
        if (md == null || md.isEmpty()) {
            for (TableGrids.GridRow line : grid.rows()) {
                List<String> cells = new ArrayList<>();
                for (String[] cell : line.cells()) {
                    cells.add(cell[0]);
                }
                out.append(String.join("\t", cells)).append('\n');
            }
        } else {
            out.append(md).append('\n');
        }
    }
}
