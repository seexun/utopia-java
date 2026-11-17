package tech.seexun.jknow.ingest;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF 文本层（parsers.rs pdf 的 PDFBox 对等）。文本层空 = 扫描件（字在图里）→
 * NeedsReader(scanned PDF)；打开/解析失败 → 读不了（Rust 侧是可重试的解析报错，
 * Java 三态模型里归 Unreadable，任务 5 管线接线时再按需细分）。
 */
final class PdfText {

    private PdfText() {
    }

    /** @return 文本层；空串 = 没有文字层（扫描件） */
    static String parse(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new IOException("PDF text-layer extraction failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("PDF text-layer extraction failed: " + e.getMessage(), e);
        }
    }
}
