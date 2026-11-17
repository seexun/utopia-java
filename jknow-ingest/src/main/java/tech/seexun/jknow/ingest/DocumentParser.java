package tech.seexun.jknow.ingest;

import java.io.IOException;
import java.util.List;

/**
 * 解析矩阵入口（lib.rs parse() 317-367 逐分支对等）：
 *
 * <pre>
 * 魔数优先（扩展名可能撒谎）
 *   → 图片 NeedsReader(Ocr) / 录音 NeedsReader(Transcribe) / 视频 Unreadable
 *   → pdf（PDFBox 文本层；空 → NeedsReader "scanned PDF"）
 *   → docx（POI XWPF；标题样式 → # 标题）/ xlsx·xls·ods（POI + StAX；截断告警）
 *   → pptx（POI XSLF）/ html·htm（Jsoup readability 简化 + 去 interstitial）
 *   → csv·tsv（首条记录为列头；1 万条截断告警）
 *   → looks_binary（头 8KiB 夹 NUL 且非合法 UTF-8；UTF-16 BOM 豁免）= Unreadable
 *   → plain_text（编码探测含 GBK/UTF-16）
 *   → normalize → 空 = Unreadable
 * </pre>
 */
public final class DocumentParser {

    private DocumentParser() {
    }

    public static ParseResult parse(String filename, byte[] bytes) {
        String ext = Magic.extension(filename);

        Magic.Media media = Magic.mediaKind(bytes, ext);
        if (media != null) {
            if (media == Magic.Media.VIDEO) {
                return new ParseResult.Unreadable(
                        "Video files are not read yet; upload its audio track or a transcript");
            }
            return new ParseResult.NeedsReader(media.reader, media.what);
        }

        String kind = Magic.formatKind(bytes);

        String text;
        List<ParseWarning> warnings = List.of();
        try {
            switch (new Format(kind, ext)) {
                case Format f when f.is("pdf") -> {
                    String layer = PdfText.parse(bytes);
                    if (layer.isBlank()) {
                        // 文本层是空的：扫描件，字在图里
                        return new ParseResult.NeedsReader(ReaderKind.Ocr, "scanned PDF");
                    }
                    text = layer;
                }
                case Format f when f.is("docx") -> text = DocxText.parse(bytes);
                case Format f when f.isSpreadsheet() -> {
                    CsvText.Result r = SpreadsheetText.parse(bytes, f.spreadsheetExt());
                    text = r.text();
                    warnings = r.warnings();
                }
                case Format f when f.is("pptx") -> text = PptxText.parse(bytes);
                case Format f when f.isHtml() -> text = HtmlText.parse(bytes);
                case Format f when f.is("csv") -> {
                    CsvText.Result r = CsvText.parse(bytes, false);
                    text = r.text();
                    warnings = r.warnings();
                }
                case Format f when f.is("tsv") -> {
                    CsvText.Result r = CsvText.parse(bytes, true);
                    text = r.text();
                    warnings = r.warnings();
                }
                case Format f when f.binary(bytes) -> {
                    return new ParseResult.Unreadable(
                            "This file is not in a format that can be read as text");
                }
                default -> text = TextCodecs.plainText(bytes);
            }
        } catch (IOException e) {
            // Rust 侧格式解析失败是可重试错误；三态模型里统一按 Unreadable 落（理由随格式带出）
            return new ParseResult.Unreadable(e.getMessage() == null ? "Parse failed" : e.getMessage());
        }

        String normalized = Normalizer.normalize(text);
        if (normalized.isBlank()) {
            return new ParseResult.Unreadable("No text could be extracted from this file");
        }
        return new ParseResult.Text(normalized, warnings);
    }

    /** (魔数 kind, 扩展名 ext) 二元组分派（Rust match (kind, ext) 的对等）。 */
    private record Format(String kind, String ext) {
        boolean is(String format) {
            return format.equals(kind) || format.equals(ext);
        }

        boolean isSpreadsheet() {
            return is("xlsx") || "xls".equals(ext) || "ods".equals(ext);
        }

        String spreadsheetExt() {
            return "xls".equals(ext) || "ods".equals(ext) ? ext : "xlsx";
        }

        boolean isHtml() {
            return "html".equals(ext) || "htm".equals(ext);
        }

        boolean binary(byte[] bytes) {
            return TextCodecs.looksBinary(bytes);
        }
    }
}
