package tech.seexun.jknow.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * docx（parsers.rs docx 的 POI XWPF 对等）：段落照读，段末空行；标题（有大纲级别的段落样式）
 * 写成 Markdown 标题——分块器靠它给每块补面包屑。样式按「自己的 w:outlineLvl，其次名字
 * heading N，再顺着 w:basedOn 往上找」认（样式 id 靠不住：中文 Word 写 1、2）；段落自己的
 * w:outlineLvl 优先于样式。表格收成网格走 {@link MarkdownTables}（跨列格占位列留空）。
 */
final class DocxText {

    private DocxText() {
    }

    static String parse(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            Map<String, Integer> levels = headingStyles(doc);
            StringBuilder out = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    paragraph(out, doc, p, levels);
                } else if (element instanceof XWPFTable table) {
                    String md = TableGrids.gridOrLines(grid(table), false);
                    if (md != null && !md.isEmpty()) {
                        if (!out.isEmpty()) {
                            stripTrailingNewlines(out);
                            out.append("\n\n");
                        }
                        out.append(md.stripTrailing()).append("\n\n");
                    }
                }
            }
            return out.toString();
        } catch (IOException e) {
            throw new IOException("Malformed docx structure: " + e, e);
        } catch (RuntimeException e) {
            throw new IOException("Malformed docx structure: " + e, e);
        }
    }

    private static void paragraph(StringBuilder out, XWPFDocument doc, XWPFParagraph p,
            Map<String, Integer> levels) {
        String text = p.getText().replace('\t', ' ').stripTrailing();
        if (text.isBlank()) {
            return;
        }
        Integer level = ownLevel(p);
        if (level == null && p.getStyleID() != null) {
            level = levels.get(p.getStyleID());
        }
        if (level != null) {
            String title = String.join(" ", text.strip().split("\\s+"));
            if (!title.isEmpty()) {
                out.append("#".repeat(Math.min(6, level))).append(' ').append(title).append("\n\n");
                return;
            }
        }
        out.append(text.strip()).append("\n\n");
    }

    /** 段落自己写的 w:outlineLvl（0-8 是 1-9 级，9 是正文）。 */
    private static Integer ownLevel(XWPFParagraph p) {
        try {
            if (p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetOutlineLvl()
                    && p.getCTP().getPPr().getOutlineLvl() != null) {
                long outline = p.getCTP().getPPr().getOutlineLvl().getVal().longValue();
                if (outline <= 8) {
                    return (int) outline + 1;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * 样式表里哪些段落样式是标题（docx_heading_styles 的对等——直接读 word/styles.xml：
     * 样式自己的 w:outlineLvl，其次名字 heading N，再顺着 w:basedOn 往上找 ≤10 步防环；
     * Title/toc 没有大纲级别不算）。样式表缺了或读不了：正文照读，只是认不出标题。
     */
    private static Map<String, Integer> headingStyles(XWPFDocument doc) {
        var styles = new HashMap<String, int[]>(); // styleId → {outline(-1 无), nameLevel(-1 无)}
        var basedOn = new HashMap<String, String>();
        try {
            var part = doc.getPackage()
                    .getPartsByContentType("application/vnd.openxmlformats-officedocument"
                            + ".wordprocessingml.styles+xml");
            if (part.size() != 1) {
                return Map.of();
            }
            var factory = javax.xml.stream.XMLInputFactory.newFactory();
            try (var in = part.get(0).getInputStream()) {
                var r = factory.createXMLStreamReader(in);
                String current = null;
                boolean paragraph = false;
                int outline = -1;
                int nameLevel = -1;
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                        String local = r.getLocalName();
                        switch (local) {
                            case "style" -> {
                                current = null;
                                paragraph = false;
                                outline = -1;
                                nameLevel = -1;
                                for (int i = 0; i < r.getAttributeCount(); i++) {
                                    String name = r.getAttributeLocalName(i);
                                    if (name.equals("styleId")) {
                                        current = r.getAttributeValue(i);
                                    } else if (name.equals("type")
                                            && "paragraph".equals(r.getAttributeValue(i))) {
                                        paragraph = true;
                                    }
                                }
                            }
                            case "name" -> {
                                String val = attr(r, "val");
                                int lvl = val == null ? -1 : headingNumber(val);
                                if (lvl > 0) {
                                    nameLevel = lvl;
                                }
                            }
                            case "basedOn" -> {
                                String val = attr(r, "val");
                                if (current != null && val != null) {
                                    basedOn.put(current, val);
                                }
                            }
                            case "outlineLvl" -> {
                                String val = attr(r, "val");
                                try {
                                    outline = val == null ? -1 : Integer.parseInt(val);
                                } catch (NumberFormatException e) {
                                    outline = -1;
                                }
                            }
                            default -> {
                            }
                        }
                    } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT
                            && r.getLocalName().equals("style")) {
                        if (current != null && paragraph) {
                            styles.put(current, new int[] {outline, nameLevel});
                        }
                        current = null;
                    }
                }
                r.close();
            }
        } catch (Exception e) {
            return Map.of();
        }
        Map<String, Integer> levels = new HashMap<>();
        for (var entry : styles.entrySet()) {
            int outline = entry.getValue()[0];
            int nameLevel = entry.getValue()[1];
            Integer level = null;
            if (outline >= 0 && outline <= 8) {
                level = outline + 1; // 0-8 是 1-9 级，9 是正文
            } else if (nameLevel > 0) {
                level = nameLevel;
            }
            if (level == null) {
                // basedOn：自定义标题样式多半基于内置标题
                String cur = basedOn.get(entry.getKey());
                for (int hop = 0; hop < 10 && cur != null; hop++) {
                    int[] parent = styles.get(cur);
                    if (parent == null) {
                        break;
                    }
                    if (parent[0] >= 0 && parent[0] <= 8) {
                        level = parent[0] + 1;
                        break;
                    }
                    if (parent[1] > 0) {
                        level = parent[1];
                        break;
                    }
                    cur = basedOn.get(cur);
                }
            }
            if (level != null) {
                levels.put(entry.getKey(), level);
            }
        }
        return levels;
    }

    private static String attr(javax.xml.stream.XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(name)) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    /** 内置标题样式的名字 heading 1..9，大小写不论；Title/toc 不算。 */
    private static int headingNumber(String name) {
        String lowered = name.strip().toLowerCase(java.util.Locale.ROOT);
        if (!lowered.startsWith("heading")) {
            return -1;
        }
        try {
            int n = Integer.parseInt(lowered.substring("heading".length()).strip());
            return n >= 1 && n <= 9 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** (文字, 跨列, 左缩进 pt)——parsers.rs:329-378 的 w:gridSpan/w:ind 收集。 */
    private static List<TableGrids.GridRow> grid(XWPFTable table) {
        List<TableGrids.GridRow> rows = new ArrayList<>();
        for (XWPFTableRow tr : table.getRows()) {
            List<String[]> cells = new ArrayList<>();
            for (XWPFTableCell tc : tr.getTableCells()) {
                String text = tc.getText().replace('\n', ' ').replace('\t', ' ').strip();
                int span = 1;
                int pad = 0;
                try {
                    if (tc.getCTTc().isSetTcPr()
                            && tc.getCTTc().getTcPr().isSetGridSpan()) {
                        span = Math.max(1,
                                tc.getCTTc().getTcPr().getGridSpan().getVal().intValue());
                    }
                } catch (RuntimeException ignored) {
                    // 读不出跨列就按 1 列
                }
                try {
                    // 格子的第一段还没攒下文字时，w:ind 的左缩进（twips/20 折 pt）当小节深度
                    if (text.isEmpty() && !tc.getParagraphs().isEmpty()
                            && tc.getParagraphs().get(0).getCTP().isSetPPr()
                            && tc.getParagraphs().get(0).getCTP().getPPr().isSetInd()) {
                        var ind = tc.getParagraphs().get(0).getCTP().getPPr().getInd();
                        // getLeft/getStart 是 STSignedTwipsMeasure 联合类型，toString 即数值
                        long twips = 0;
                        Object left = ind.getLeft();
                        Object start = ind.getStart();
                        try {
                            if (left != null) {
                                twips = Long.parseLong(left.toString());
                            } else if (start != null) {
                                twips = Long.parseLong(start.toString());
                            }
                        } catch (NumberFormatException ignored) {
                            // 非数值的联合形态按无缩进
                        }
                        if (twips > 0) {
                            pad = (int) (twips / 20);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 缩进读不出就当 0
                }
                cells.add(new String[] {text, String.valueOf(span), String.valueOf(pad)});
            }
            rows.add(new TableGrids.GridRow(cells));
        }
        return rows;
    }

    private static void stripTrailingNewlines(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '\n') {
            end--;
        }
        sb.setLength(end);
    }
}
