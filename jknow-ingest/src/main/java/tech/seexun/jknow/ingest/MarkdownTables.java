package tech.seexun.jknow.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格 → Markdown 表（table.rs 两个入口的 Java 简化）：
 *
 * <ul>
 *   <li>renderRecords——csv/tsv 的对等（table.rs:316-341 逐字）：首条记录是列头，行补齐到
 *       最大宽，全空字段的记录丢行，"\|" 转义；</li>
 *   <li>renderGrid——docx/xlsx/pptx/html 网格的简化：第一排是列头（Rust 版另做小节折叠/
 *       符号并格/多行列头，Java 按「正文提取」目标取第一排为列头的直渲染）。</li>
 * </ul>
 */
final class MarkdownTables {

    private MarkdownTables() {
    }

    /** csv/tsv 的空字段只是缺失值：不能当小节、不能并格（render_records 逐字）。 */
    static String renderRecords(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        List<List<String>> cleaned = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> c = new ArrayList<>();
            for (String s : row) {
                c.add(clean(s));
            }
            cleaned.add(c);
        }
        int width = 0;
        for (List<String> r : cleaned) {
            width = Math.max(width, r.size());
        }
        if (width == 0) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add(renderRow(cleaned.get(0), width));
        lines.add("|" + " --- |".repeat(Math.max(0, width)));
        for (int i = 1; i < cleaned.size(); i++) {
            List<String> r = cleaned.get(i);
            boolean any = r.stream().anyMatch(s -> !s.isEmpty());
            if (any) {
                lines.add(renderRow(r, width));
            }
        }
        return String.join("\n", lines);
    }

    /** 网格直渲染：第一排列头 + 分隔行 + 体行（render_grid 的简化形态）。 */
    static String renderGrid(List<List<String>> rows) {
        List<List<String>> cleaned = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> c = new ArrayList<>();
            for (String s : row) {
                c.add(clean(s));
            }
            cleaned.add(c);
        }
        int width = 0;
        for (List<String> r : cleaned) {
            width = Math.max(width, r.size());
        }
        if (width == 0 || cleaned.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder();
        md.append(renderRow(cleaned.get(0), width)).append('\n');
        md.append("|").append(" --- |".repeat(Math.max(0, width))).append('\n');
        for (int i = 1; i < cleaned.size(); i++) {
            md.append(renderRow(cleaned.get(i), width)).append('\n');
        }
        return md.toString();
    }

    private static String renderRow(List<String> row, int width) {
        StringBuilder sb = new StringBuilder("| ");
        for (int i = 0; i < width; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            if (i < row.size()) {
                sb.append(row.get(i).replace("|", "\\|"));
            }
        }
        return sb.append(" |").toString();
    }

    /** nbsp 归空格、连续空白折一个、去首尾（table.rs clean 逐字）。 */
    static String clean(String text) {
        return String.join(" ", text.replace("\u00A0", " ").trim().split("\\s+"));
    }
}
