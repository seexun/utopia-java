package tech.seexun.jknow.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

/**
 * pptx（parsers.rs pptx 的 POI XSLF 对等）：按演示的逻辑页序取 a:t 文字。第一页写在任何
 * 标题之前；第二页起每页一节（## Slide N）——封面也是一节的话，它的日期只管封面（0064）。
 * 页内表走 {@link MarkdownTables}。
 */
final class PptxText {

    private PptxText() {
    }

    static String parse(byte[] bytes) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            int num = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                num++;
                String text = slideText(slide);
                if (text.isBlank()) {
                    continue;
                }
                if (num == 1) {
                    out.append(text.stripTrailing()).append('\n');
                } else {
                    out.append("\n## Slide ").append(num).append('\n')
                            .append(text.stripTrailing()).append('\n');
                }
            }
            return out.toString();
        } catch (IOException e) {
            throw new IOException("Failed to unzip pptx: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("Failed to unzip pptx: " + e.getMessage(), e);
        }
    }

    private static String slideText(XSLFSlide slide) {
        StringBuilder out = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTable table) {
                // I-3 收口：按 Rust parsers.rs:1083-1170 接线——a:tblPr 的 firstRow
                // 决定第一排是否表头惯例；getGridSpan 给跨列；vMerge 延续格在 POI
                // 展平行里不出现（parsers.rs 里它们清文字占位），列对齐由跨列值承担
                List<TableGrids.GridRow> rows = new ArrayList<>();
                for (XSLFTableRow tr : table.getRows()) {
                    List<String[]> cells = new ArrayList<>();
                    for (XSLFTableCell tc : tr.getCells()) {
                        String text = tc.getText().replace('\n', ' ').strip();
                        int span = Math.max(1, tc.getGridSpan());
                        cells.add(new String[] {text, String.valueOf(span), "0"});
                    }
                    rows.add(new TableGrids.GridRow(cells));
                }
                boolean firstRow = table.getCTTable().getTblPr() != null
                        && table.getCTTable().getTblPr().isSetFirstRow();
                String md = TableGrids.gridOrLines(rows, firstRow);
                if (md != null && !md.isEmpty()) {
                    out.append('\n').append(md.stripTrailing())
                            .append("\n\n");
                }
            } else if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText().strip();
                if (!text.isEmpty()) {
                    out.append(text).append('\n');
                }
            }
        }
        return out.toString();
    }
}