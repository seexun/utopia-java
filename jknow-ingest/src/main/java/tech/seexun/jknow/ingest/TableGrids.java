package tech.seexun.jknow.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.nodes.Element;

/**
 * 表格从 DOM 直接渲染成带真表头的 Markdown 表（table.rs 全量对等，M3 任务 9——替换
 * MarkdownTables 的 97 行近似，m2-report §8 任务 2 遗留核销）。
 *
 * <p><b>为什么第一排当表头不够</b>：SEC/XBRL 报表的表一个 {@code <th>} 都没有。标题行
 * （公司名、报表名、单位）是 colspan 跨全宽的普通行，列头（"July 26," / "2026"）拆在
 * 两行里，每个数字前后各有一格 "$" 或 ")"，列与列之间是空的占位格。此前的补丁把第一行
 * 提成表头，于是被切开的每一块表头都是「| NVIDIA CORPORATION | | |」，真列头一块都没
 * 带上；模型面对「Accounts receivable, net | 63,059 | 38,466」只能写出「短语 = 数字」。
 *
 * <p>这里只看结构，不认任何词：跨列格、空列、只含符号的格、只有一格的行、行标签的左
 * 内边距、格子里有没有数字和字母。做四件事：标题行提成表前的说明句；多行列头按列拼成
 * 一行；只有标签没有值的行当成小节，折进后面每行的标签里（「Current assets ›
 * Accounts receivable, net」）；"$"、")" 这类符号格并回相邻的数字格。渲染出的表交给
 * {@link Chunker}，它本来就让说明句和表头跟着每一块走。
 *
 * <p>只处理最外层且不含内层表的表；套着的表留给页面转换器原路。docx 和电子表格的表不
 * 经过 DOM：解析器把格子收成 (文字, 跨列, 左内边距) 网格交给 {@link #renderGrid}，
 * 之后的分类与渲染和 HTML 表一模一样。csv/tsv 保留记录与字段边界，交给
 * {@link MarkdownTables#renderRecords}。
 */
final class TableGrids {

    private TableGrids() {
    }

    private static final String PATH_SEP = " › ";

    /** 一格：文字、跨了几列、左内边距（pt，取整）、在网格里的起始列（table.rs:29-34）。 */
    static final class Cell {
        String text;
        final int span;
        final int pad;
        final int col;

        Cell(String text, int span, int pad, int col) {
            this.text = text;
            this.span = span;
            this.pad = pad;
            this.col = col;
        }
    }

    /** 一行（table.rs:36-50 Row）。 */
    static final class Row {
        final List<Cell> cells;
        /** 网格总宽（含跨列）。 */
        final int width;

        Row(List<Cell> cells, int width) {
            this.cells = cells;
            this.width = width;
        }

        /** 覆盖第 k 列的那个非空格。 */
        Cell covering(int k) {
            for (Cell c : cells) {
                if (!c.text.isEmpty() && c.col <= k && k < c.col + c.span) {
                    return c;
                }
            }
            return null;
        }
    }

    /** 一行是什么（table.rs:52-59）：只看非空格的个数/位置/跨度和有没有数字。 */
    enum Kind {
        SKIP, CAPTION, HEADER, SECTION, DATA
    }

    // ---- DOM 工具（table.rs:119-197） ----

    /** display:none 的格不进网格（table.rs:119-122）。 */
    static boolean hidden(String style) {
        String s = style.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        return s.contains("display:none");
    }

    /**
     * 左内边距（table.rs:125-158）：{@code padding-left: 12pt}、{@code text-indent: 6pt}，
     * 或 {@code padding: a b c d} 的第四个值（3 值取第二个、2 值取第二个、1 值取唯一）。
     */
    static int paddingLeft(String style) {
        String lower = style.toLowerCase(Locale.ROOT);
        for (String key : new String[] {"padding-left:", "text-indent:"}) {
            int i = lower.indexOf(key);
            if (i >= 0) {
                Double v = numberAt(lower.substring(i + key.length()));
                if (v != null) {
                    return (int) Math.round(v);
                }
            }
        }
        int i = lower.indexOf("padding:");
        if (i >= 0) {
            String rest = lower.substring(i + "padding:".length());
            int semi = rest.indexOf(';');
            if (semi >= 0) {
                rest = rest.substring(0, semi);
            }
            String[] parts = rest.trim().split("\\s+");
            String left = switch (parts.length) {
                case 4 -> parts[3];
                case 3, 2 -> parts[1];
                case 1 -> parts[0];
                default -> null;
            };
            if (left != null) {
                Double v = numberAt(left);
                if (v != null) {
                    return (int) Math.round(v);
                }
            }
        }
        return 0;
    }

    private static Double numberAt(String s) {
        String t = s.stripLeading();
        int end = 0;
        while (end < t.length() && (Character.isDigit(t.charAt(end)) || t.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            return Double.parseDouble(t.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** nbsp 归空格、连续空白折一个（table.rs:160-165 clean）。 */
    static String clean(String text) {
        return String.join(" ", text.replace('\u00A0', ' ').trim().split("\\s+"));
    }

    private static int attrInt(Element cell, String name) {
        try {
            return Math.max(1, Integer.parseInt(cell.attr(name).trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 有数字没字母（table.rs:174-176）。 */
    static boolean isNumeric(String text) {
        boolean digit = false;
        for (char c : text.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (Character.isLetter(c)) {
                return false;
            }
        }
        return digit;
    }

    /**
     * 该并回邻格的符号格（table.rs:180-193）：货币符号和左括号贴到后一格前面，右括号和
     * 百分号贴到前一格后面。破折号那类「空值」占位符不算——它自己就是这一格的内容，
     * 并过去会把整行的值挪位。
     */
    static Boolean mergeDirection(String text) {
        if (text.length() != 1) {
            return null;
        }
        char first = text.charAt(0);
        if (first == '(' || isCurrency(first)) {
            return true;
        }
        if (first == ')' || first == '%') {
            return false;
        }
        return null;
    }

    static boolean isCurrency(char c) {
        return c == '$' || c == '€' || c == '£' || c == '¥' || c == '₩' || c == '₹'
                || c == '¢' || c == '￥';
    }

    /** 读出网格（table.rs:200-234）：按 tr 逐行，跨行的格顺着往下带。 */
    static List<Row> grid(Element tbl) {
        List<Row> rows = new ArrayList<>();
        // (列, 格, 还剩几行)
        List<Object[]> carry = new ArrayList<>();
        for (Element tr : tbl.select("tr")) {
            List<Cell> cells = new ArrayList<>();
            int[] col = {0};
            for (Element cell : tr.children()) {
                String tag = cell.tagName().toLowerCase(Locale.ROOT);
                if (!tag.equals("td") && !tag.equals("th")) {
                    continue;
                }
                String style = cell.hasAttr("style") ? cell.attr("style") : "";
                if (hidden(style)) {
                    continue;
                }
                placeCarried(col, cells, carry);
                int span = attrInt(cell, "colspan");
                int rowspan = attrInt(cell, "rowspan");
                Cell c = new Cell(clean(cell.text()), span, paddingLeft(style), col[0]);
                if (rowspan > 1) {
                    carry.add(new Object[] {col[0], new Cell(c.text, c.span, c.pad, c.col),
                            rowspan - 1});
                }
                col[0] += span;
                cells.add(c);
            }
            placeCarried(col, cells, carry);
            rows.add(new Row(cells, col[0]));
        }
        return rows;
    }

    private static void placeCarried(int[] col, List<Cell> cells, List<Object[]> carry) {
        while (true) {
            int pos = -1;
            for (int i = 0; i < carry.size(); i++) {
                if ((Integer) carry.get(i)[0] == col[0]) {
                    pos = i;
                    break;
                }
            }
            if (pos < 0) {
                return;
            }
            Object[] entry = carry.get(pos);
            Cell cell = (Cell) entry[1];
            int left = (Integer) entry[2];
            Cell copy = new Cell(cell.text, cell.span, cell.pad, col[0]);
            col[0] += copy.span;
            cells.add(copy);
            if (left <= 1) {
                carry.remove(pos);
            } else {
                entry[2] = left - 1;
            }
        }
    }

    /** 一行是什么（table.rs:251-286 kind）。 */
    static Kind kind(Row row, boolean headersSeen, boolean dataSeen, int tableWidth) {
        List<Cell> nonempty = new ArrayList<>();
        for (Cell c : row.cells) {
            if (!c.text.isEmpty()) {
                nonempty.add(c);
            }
        }
        if (nonempty.isEmpty()) {
            return Kind.SKIP;
        }
        Cell first = nonempty.get(0);
        if (nonempty.size() == 1) {
            boolean wide = first.span >= 2 && first.span * 2 >= Math.max(2, tableWidth);
            if (isNumeric(first.text)) {
                return dataSeen ? Kind.DATA : Kind.HEADER;
            }
            if (!headersSeen && !dataSeen) {
                // 表头和数据都还没来：靠左或跨宽的一格文字是标题，不靠左的是一根列头
                return wide || first.col == 0 ? Kind.CAPTION : Kind.HEADER;
            }
            return Kind.SECTION;
        }
        boolean label = !isNumeric(first.text);
        boolean valuesAfter = false;
        for (int i = 1; i < nonempty.size(); i++) {
            if (isNumeric(nonempty.get(i).text)) {
                valuesAfter = true;
                break;
            }
        }
        // 有标签跟着数字的是数据行；最左一格就有内容的整排数字也是（年份列的表）。
        // 最左几格空着、全是数字的一排（"2026 | 2026"）是列头的第二行
        if (valuesAfter && (label || first.col == 0 || dataSeen)) {
            return Kind.DATA;
        }
        if (!dataSeen) {
            return Kind.HEADER;
        }
        if (label) {
            // 数据行之后又一排没有数字的格：当成一条小节
            return Kind.SECTION;
        }
        return Kind.DATA;
    }

    /** 只含符号的格并回邻格（table.rs:289-309 merge_symbols）。 */
    static void mergeSymbols(Row row) {
        int n = row.cells.size();
        for (int i = 0; i < n; i++) {
            Boolean opening = mergeDirection(row.cells.get(i).text);
            if (opening == null) {
                continue;
            }
            String sym = row.cells.get(i).text;
            Integer target = null;
            if (opening) {
                for (int j = i + 1; j < n; j++) {
                    if (!row.cells.get(j).text.isEmpty()) {
                        target = j;
                        break;
                    }
                }
            } else {
                for (int j = i - 1; j >= 0; j--) {
                    if (!row.cells.get(j).text.isEmpty()) {
                        target = j;
                        break;
                    }
                }
            }
            if (target == null) {
                continue;
            }
            if (opening) {
                row.cells.get(target).text = sym + row.cells.get(target).text;
            } else {
                row.cells.get(target).text = row.cells.get(target).text + sym;
            }
            row.cells.get(i).text = "";
        }
    }

    static String escape(String text) {
        return text.replace("|", "\\|");
    }

    /** 缩进深度（table.rs:347-349）：先看标签格前面有几根空列（Workiva 报表），再看内边距。 */
    static int depthOf(Cell cell) {
        return cell.col * 1000 + cell.pad;
    }

    // ---- 入口（table.rs:351-434） ----

    /** HTML 表 → (Markdown, 它用的列头)（table.rs:353-355 render_table）。 */
    static RenderedTable renderTable(Element tbl, List<String> inherited) {
        List<Row> rows = grid(tbl);
        return renderRows(rows, inherited, null);
    }

    /** 渲染产物（table.rs:353 的元组）。 */
    record RenderedTable(String markdown, List<String> headers) {
    }

    /**
     * 别的解析器用的入口：一行是若干 (文字, 跨几列, 左内边距 pt)。{@code firstIsHeader}
     * 为真时，第一排至少两格有字的行按列头算（电子表格的惯例），其余行照常分类
     * （table.rs:359-372 render_grid）。
     */
    public static String renderGrid(List<GridRow> rows, boolean firstIsHeader) {
        List<Row> converted = gridRows(rows);
        int[] forced = null;
        if (firstIsHeader) {
            for (int i = 0; i < converted.size(); i++) {
                int filled = 0;
                for (Cell c : converted.get(i).cells) {
                    if (!c.text.isEmpty()) {
                        filled++;
                    }
                }
                if (filled >= 2) {
                    forced = new int[] {i, i + 1};
                    break;
                }
            }
        }
        RenderedTable out = renderRows(converted, List.of(), forced);
        return out == null ? null : out.markdown();
    }

    /**
     * docx 和 pptx 用的入口：renderGrid 不认作表的网格也不能丢字（table.rs:377-395
     * grid_or_lines）——没有列头也没有数据行的网格（单列文字、每排只有一格有字、一格
     * 跨整排的告示）按行写出来：一排一段，有字的格用 " | " 连起来。
     */
    public static String gridOrLines(List<GridRow> rows, boolean firstIsHeader) {
        String asTable = renderGrid(rows, firstIsHeader);
        if (asTable != null) {
            return asTable;
        }
        List<String> lines = new ArrayList<>();
        for (GridRow row : rows) {
            List<String> cells = new ArrayList<>();
            for (String[] cell : row.cells()) {
                String cleaned = clean(cell[0]);
                if (!cleaned.isEmpty()) {
                    cells.add(cleaned);
                }
            }
            if (!cells.isEmpty()) {
                lines.add(String.join(" | ", cells));
            }
        }
        return lines.isEmpty() ? null : String.join("\n\n", lines);
    }

    /** 明确表头区间的入口（table.rs:399-411 render_grid_with_headers）：电子表格的 merge 表头。 */
    public static String renderGridWithHeaders(List<GridRow> rows, int[] headers) {
        List<Row> converted = gridRows(rows);
        int[] forced = null;
        if (headers != null) {
            int end = Math.min(headers[1], converted.size());
            if (headers[0] < end) {
                forced = new int[] {headers[0], end};
            }
        }
        RenderedTable out = renderRows(converted, List.of(), forced);
        return out == null ? null : out.markdown();
    }

    /** 一行的输入形状：(文字, 跨列, 左内边距)。 */
    public record GridRow(List<String[]> cells) {

        /** cells 每格 [text, span, pad]（字符串承载，解析器侧好组）。 */
        public GridRow(List<String[]> cells) {
            this.cells = cells;
        }
    }

    private static List<Row> gridRows(List<GridRow> rows) {
        List<Row> out = new ArrayList<>(rows.size());
        for (GridRow row : rows) {
            int col = 0;
            List<Cell> cells = new ArrayList<>(row.cells().size());
            for (String[] cell : row.cells()) {
                String text = cell[0];
                int span = Math.max(1, parseInt(cell[1], 1));
                int pad = parseInt(cell[2], 0);
                cells.add(new Cell(clean(text), span, pad, col));
                col += span;
            }
            out.add(new Row(cells, col));
        }
        return out;
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ---- 核心（table.rs:436-677 render_rows） ----

    /**
     * 一张表 → 说明句（可选）+ 带真表头的 Markdown 表，连同它用的列头（给续表接着用）。
     * 表里什么都没有时返回 null。
     */
    static RenderedTable renderRows(List<Row> rows, List<String> inherited, int[] forcedHeader) {
        int width = 0;
        for (Row r : rows) {
            width = Math.max(width, r.width);
        }
        if (width == 0) {
            return null;
        }
        List<String> captions = new ArrayList<>();
        List<Row> headers = new ArrayList<>();
        // (小节深度, 小节名, 已经见过比它深的行)——一个小节被同深度的数据行结束，只在它
        // 已经有过更深的子行之后：资产负债表「Current assets:」的子行缩进了，同深度的
        // 「Property and equipment」是下一项；现金流量表的子行没缩进，同深度的
        // 「Net income」就是它的第一行
        List<Object[]> sections = new ArrayList<>(); // {depth int, name String, deeper Boolean}
        List<Object[]> data = new ArrayList<>(); // {path String, row Row}
        boolean[] seen = {false, false}; // headers_seen, data_seen
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int filled = 0;
            for (Cell c : row.cells) {
                if (!c.text.isEmpty()) {
                    filled++;
                }
            }
            Kind k;
            if (forcedHeader != null && i >= forcedHeader[0] && i < forcedHeader[1]) {
                k = Kind.HEADER;
            } else if (forcedHeader != null && i >= forcedHeader[1]) {
                // 列头之后的行是记录，哪怕一格数字都没有；只填了一个数字的行（序号列）
                // 也是记录，只填了一个词的行才按小节算
                Cell only = firstFilled(row);
                k = filled >= 2 || only != null && isNumeric(only.text) ? Kind.DATA : Kind.SECTION;
            } else {
                k = kind(row, seen[0], seen[1], width);
            }
            switch (k) {
                case SKIP -> {
                }
                case CAPTION -> {
                    Cell c = firstFilled(row);
                    if (c != null) {
                        captions.add(c.text);
                    }
                }
                case HEADER -> {
                    mergeSymbols(row);
                    headers.add(row);
                    seen[0] = true;
                }
                case SECTION -> {
                    Cell cell = firstFilled(row);
                    if (cell == null) {
                        continue;
                    }
                    int depth = depthOf(cell);
                    while (!sections.isEmpty()
                            && (Integer) sections.get(sections.size() - 1)[0] >= depth) {
                        sections.remove(sections.size() - 1);
                    }
                    sections.add(new Object[] {depth,
                            trimSectionEnd(cell.text), Boolean.FALSE});
                }
                case DATA -> {
                    mergeSymbols(row);
                    Cell labelCell = firstFilled(row);
                    int depth = labelCell != null ? depthOf(labelCell) : 0;
                    while (!sections.isEmpty()) {
                        Object[] top = sections.get(sections.size() - 1);
                        int topDepth = (Integer) top[0];
                        boolean topDeeper = (Boolean) top[2];
                        if (topDepth > depth || (topDepth == depth && topDeeper)) {
                            sections.remove(sections.size() - 1);
                        } else {
                            break;
                        }
                    }
                    if (!sections.isEmpty()) {
                        Object[] top = sections.get(sections.size() - 1);
                        if ((Integer) top[0] < depth) {
                            top[2] = Boolean.TRUE;
                        }
                    }
                    StringBuilder path = new StringBuilder();
                    for (Object[] s : sections) {
                        if (!path.isEmpty()) {
                            path.append(PATH_SEP);
                        }
                        path.append((String) s[1]);
                    }
                    data.add(new Object[] {path.toString(), row});
                    seen[1] = true;
                }
            }
        }
        // 全是列头没有一行数据的表不存在：一格数字都没有的表，第一排是列头，其余是数据行
        if (data.isEmpty() && headers.size() >= 2) {
            // split_off(1)：留第一排当列头，其余按数据行收
            List<Row> rest = new ArrayList<>(headers.subList(1, headers.size()));
            headers.subList(1, headers.size()).clear();
            for (Row r : rest) {
                data.add(new Object[] {"", r});
            }
        }
        if (data.isEmpty() && headers.isEmpty()) {
            return null;
        }

        // 标签列 = 第一根出现过数字的列左边的所有列；一个数字都没有的表，标签列只有最左那格。
        // 值列 = 标签列右边、数据行里出现过内容的列（表头有字但整列没数据的列不算）
        int labelEnd = -1;
        for (int k = 0; k < width; k++) {
            boolean anyNumeric = false;
            for (Object[] d : data) {
                Cell c = ((Row) d[1]).covering(k);
                if (c != null && isNumeric(c.text)) {
                    anyNumeric = true;
                    break;
                }
            }
            if (anyNumeric) {
                labelEnd = k;
                break;
            }
        }
        if (labelEnd < 0) {
            int min = Integer.MAX_VALUE;
            for (Object[] d : data) {
                Cell c = firstFilled((Row) d[1]);
                if (c != null) {
                    min = Math.min(min, c.col + c.span);
                }
            }
            if (min == Integer.MAX_VALUE) {
                for (Row h : headers) {
                    Cell c = firstFilled(h);
                    if (c != null) {
                        min = Math.min(min, c.col);
                    }
                }
            }
            labelEnd = min == Integer.MAX_VALUE ? 0 : min;
        }
        final int labelEndF = labelEnd;
        List<Integer> valueCols = new ArrayList<>();
        for (int k = labelEnd; k < width; k++) {
            if (hasContent(k, headers, data)) {
                valueCols.add(k);
            }
        }
        List<List<Integer>> groups = collapseSpans(valueCols, headers, data);
        // 一行的标签：标签列里的格子按出现顺序拼起来
        List<String> headerTexts = new ArrayList<>();
        for (List<Integer> g : groups) {
            headerTexts.add(columnHeader(g, headers));
        }
        if (headers.isEmpty() && !data.isEmpty() && inherited.size() == headerTexts.size()) {
            headerTexts = new ArrayList<>(inherited);
        }
        // 标签列的表头：只算整格都落在标签列里的表头格，跨进值列的是值列的头
        // M-4 收口：去重看的是「上一段」，用 List 维护——把累积串 split 回去会在
        // 含空格的段名上错判（"a b" 折回 ["a","b"] 后 last 变成 "b"）
        List<String> labelParts = new ArrayList<>();
        for (Row h : headers) {
            for (Cell c : h.cells) {
                if (!c.text.isEmpty() && c.col + c.span <= labelEndF) {
                    if (labelParts.isEmpty() || !labelParts.get(labelParts.size() - 1)
                            .equals(c.text)) {
                        labelParts.add(c.text);
                    }
                }
            }
        }
        String labelHeader = String.join(" ", labelParts);

        List<String> lines = new ArrayList<>();
        if (!captions.isEmpty()) {
            String caption = String.join(" · ", captions);
            if (!caption.endsWith(":") && !caption.endsWith("：")) {
                caption = caption + ":";
            }
            lines.add(caption);
            lines.add("");
        }
        // 最左一列本身就是数字（年份列的表）：没有标签列，行就是它的值
        boolean hasLabelCol = labelEndF > 0;
        int ncols = (hasLabelCol ? 1 : 0) + Math.max(1, groups.size());
        List<String> headerCells = new ArrayList<>();
        if (hasLabelCol) {
            headerCells.add(escape(labelHeader));
        }
        for (String h : headerTexts) {
            headerCells.add(escape(h));
        }
        while (headerCells.size() < ncols) {
            headerCells.add("");
        }
        lines.add("| " + String.join(" | ", headerCells) + " |");
        lines.add("|" + " --- |".repeat(ncols));
        for (Object[] d : data) {
            String sectionsPath = (String) d[0];
            Row r = (Row) d[1];
            List<String> cellsOut = new ArrayList<>();
            if (hasLabelCol) {
                String own = ownLabel(r, labelEndF);
                String label;
                if (sectionsPath.isEmpty()) {
                    label = own;
                } else if (own.isEmpty()) {
                    label = sectionsPath;
                } else {
                    label = sectionsPath + PATH_SEP + own;
                }
                cellsOut.add(escape(label));
            }
            for (List<Integer> g : groups) {
                cellsOut.add(escape(at(r, g)));
            }
            while (cellsOut.size() < ncols) {
                cellsOut.add("");
            }
            lines.add("| " + String.join(" | ", cellsOut) + " |");
        }
        return new RenderedTable(String.join("\n", lines), headerTexts);
    }

    private static Cell firstFilled(Row row) {
        for (Cell c : row.cells) {
            if (!c.text.isEmpty()) {
                return c;
            }
        }
        return null;
    }

    /** 小节名去尾冒号（table.rs:497 trim_end_matches([':', '：'])）。 */
    private static String trimSectionEnd(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ':' || text.charAt(end - 1) == '：')) {
            end--;
        }
        return text.substring(0, end);
    }

    /** 值列在数据行（无数据时表头行）上有没有内容（table.rs:555-561 has_content）。 */
    private static boolean hasContent(int k, List<Row> headers, List<Object[]> data) {
        if (data.isEmpty()) {
            for (Row h : headers) {
                if (h.covering(k) != null) {
                    return true;
                }
            }
            return false;
        }
        for (Object[] d : data) {
            if (((Row) d[1]).covering(k) != null) {
                return true;
            }
        }
        return false;
    }

    /** 一根值列是一组网格列：取这一行落在这组里的第一个非空格（table.rs:565-571 at）。 */
    private static String at(Row r, List<Integer> group) {
        for (int k : group) {
            Cell c = r.covering(k);
            if (c != null) {
                return c.text;
            }
        }
        return "";
    }

    /** 一行的标签：标签列里的格子按出现顺序拼起来，紧邻重复只留一次（table.rs:573-585）。 */
    private static String ownLabel(Row r, int labelEnd) {
        List<String> parts = new ArrayList<>();
        for (Cell c : r.cells) {
            if (!c.text.isEmpty() && c.col < labelEnd) {
                if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(c.text)) {
                    parts.add(c.text);
                }
            }
        }
        return String.join(" ", parts);
    }

    /** 多行列头按列拼成一行（table.rs:586-595 column_header）。 */
    private static String columnHeader(List<Integer> group, List<Row> headers) {
        List<String> parts = new ArrayList<>();
        for (Row h : headers) {
            String t = at(h, group);
            if (!t.isEmpty() && (parts.isEmpty() || !parts.get(parts.size() - 1).equals(t))) {
                parts.add(t);
            }
        }
        return String.join(" ", parts);
    }

    /**
     * 跨列的格会让同一个值落在好几根网格列上，"$" 又常常单独占一格：相邻两列在每一行上
     * 要么由同一格覆盖、要么至少一边是空的，就是同一根值列，并成一组
     * （table.rs:660-677 collapse_spans）。
     */
    private static List<List<Integer>> collapseSpans(List<Integer> cols, List<Row> headers,
                                                     List<Object[]> data) {
        List<Row> all = new ArrayList<>(headers);
        for (Object[] d : data) {
            all.add((Row) d[1]);
        }
        List<List<Integer>> out = new ArrayList<>();
        for (int k : cols) {
            boolean sameAsPrev = false;
            if (!out.isEmpty()) {
                List<Integer> g = out.get(out.size() - 1);
                int p = g.get(g.size() - 1);
                sameAsPrev = true;
                for (Row r : all) {
                    Cell a = r.covering(p);
                    Cell b = r.covering(k);
                    if (a != null && b != null && a.col != b.col) {
                        sameAsPrev = false;
                        break;
                    }
                }
            }
            if (sameAsPrev && !out.isEmpty()) {
                out.get(out.size() - 1).add(k);
            } else {
                List<Integer> group = new ArrayList<>();
                group.add(k);
                out.add(group);
            }
        }
        return out;
    }
}
