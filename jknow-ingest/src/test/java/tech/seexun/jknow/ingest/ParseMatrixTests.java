package tech.seexun.jknow.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

/**
 * 解析矩阵（lib.rs parse() 317-367 逐分支）：每格式 ≥2 用例，含中文与边界
 * （空文件/二进制/BOM/魔数优先于扩展名）。
 */
class ParseMatrixTests {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0,
        0, 0x0D};
    private static final byte[] MP3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
    private static final byte[] WAV = {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E', 'f',
        'm', 't', ' '};
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    private static ParseResult.Text ok(ParseResult r) {
        if (r instanceof ParseResult.Text text) {
            return text;
        }
        throw new AssertionError("expected Text but was: " + r);
    }

    // ---- 图片/录音/视频（魔数优先；扩展名撒谎也认） ----

    @Test
    void anImageOrARecordingWaitsForItsReader() {
        var needs = assertInstanceOf(ParseResult.NeedsReader.class, DocumentParser.parse("scan.png", PNG));
        assertEquals(ReaderKind.Ocr, needs.kind());
        // 扩展名撒谎：文件头说了算
        needs = assertInstanceOf(ParseResult.NeedsReader.class, DocumentParser.parse("notes.txt", PNG));
        assertEquals(ReaderKind.Ocr, needs.kind());
        needs = assertInstanceOf(ParseResult.NeedsReader.class, DocumentParser.parse("meeting.mp3", MP3));
        assertEquals(ReaderKind.Transcribe, needs.kind());
        needs = assertInstanceOf(ParseResult.NeedsReader.class, DocumentParser.parse("meeting.wav", WAV));
        assertEquals(ReaderKind.Transcribe, needs.kind());
        // 认不出文件头时看扩展名
        needs = assertInstanceOf(ParseResult.NeedsReader.class, DocumentParser.parse("photo.heic", new byte[] {0, 1, 2, 3}));
        assertEquals(ReaderKind.Ocr, needs.kind());
        needs = assertInstanceOf(ParseResult.NeedsReader.class, DocumentParser.parse("call.amr", new byte[] {0, 1, 2, 3}));
        assertEquals(ReaderKind.Transcribe, needs.kind());
    }

    @Test
    void aVideoIsUnreadableNotNeedsReader() {
        var unreadable = assertInstanceOf(ParseResult.Unreadable.class,
                DocumentParser.parse("clip.mp4", MP4));
        assertTrue(unreadable.reason().contains("Video files are not read yet"));
    }

    // ---- BOM（任务 2 挂账收口：解码后剥单个前导 \uFEFF，否则首标题丢面包屑且污染物入库） ----

    @Test
    void aSingleLeadingBomIsStrippedAfterDecoding() {
        var parsed = ok(DocumentParser.parse("bom.md", concat(
                new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "# 标题\n\n正文第一段。".getBytes(StandardCharsets.UTF_8))));
        assertTrue(parsed.text().startsWith("# 标题"), "BOM 剥离后首行是标题（面包屑可识别）");
        // UTF-16LE BOM 同样剥（FF FE 解出的 \uFEFF；normalize 会补行尾换行，按去尾比）
        parsed = ok(DocumentParser.parse("bom16.txt", concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                "hello world".getBytes(StandardCharsets.UTF_16LE))));
        assertEquals("hello world", parsed.text().stripTrailing());
        // 正文中间的零宽不换行字符是内容，不剥（normalize 补的行尾换行去尾再比）
        parsed = ok(DocumentParser.parse("mid.txt",
                "a\uFEFFb".getBytes(StandardCharsets.UTF_8)));
        assertEquals("a\uFEFFb", parsed.text().stripTrailing());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- pdf ----

    @Test
    void aPdfTextLayerIsRead() throws IOException {
        var parsed = ok(DocumentParser.parse("report.pdf", pdf("Quarterly revenue grew 42%.")));
        assertTrue(parsed.text().contains("Quarterly revenue grew"));
    }

    @Test
    void aPdfWithoutATextLayerIsAScannedDocument() throws IOException {
        var needs = assertInstanceOf(ParseResult.NeedsReader.class,
                DocumentParser.parse("scan.pdf", pdf("")));
        assertEquals(ReaderKind.Ocr, needs.kind());
        assertEquals("scanned PDF", needs.what());
    }

    // ---- docx ----

    @Test
    void aDocxKeepsHeadingsAndTables() {
        String md = ok(DocumentParser.parse("doc.docx", docx())).text();
        assertTrue(md.startsWith("# 财务摘要"), md);
        assertTrue(md.contains("| Segment | Revenue |"), md);
        assertTrue(md.contains("| Cloud | $1,200 |"), md);
        assertTrue(md.contains("表格之后的段落。"), md);
    }

    @Test
    void aDocxHeadingIsKnownByOutlineStyle() {
        // heading 2 样式名认级（中文 Word 样式 id 是 1/2，名字才是 heading 2）
        String md = ok(DocumentParser.parse("d.docx", docx())).text();
        assertTrue(md.contains("## 发展历程"), md);
    }

    // ---- xlsx / ods ----

    @Test
    void aXlsxIsSheetsOfTables() throws IOException {
        var parsed = ok(DocumentParser.parse("book.xlsx", xlsx()));
        assertTrue(parsed.text().contains("# Sheet: 业绩"), parsed.text());
        assertTrue(parsed.text().contains("| 产品 | 收入 |"), parsed.text());
        assertTrue(parsed.text().contains("| 云 | 1200 |"), parsed.text());
    }

    @Test
    void aSpreadsheetOverTheRowLimitWarnsTruncation() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            XSSFSheet sheet = book.createSheet("Big");
            for (int r = 0; r < 2100; r++) {
                XSSFRow row = sheet.createRow(r);
                row.createCell(0).setCellValue("r" + r);
                row.createCell(1).setCellValue(r);
            }
            book.write(buffer);
        }
        var parsed = ok(DocumentParser.parse("big.xlsx", buffer.toByteArray()));
        assertEquals(1, parsed.warnings().size());
        assertEquals(ParseWarning.SPREADSHEET_ROWS_TRUNCATED, parsed.warnings().get(0).kind());
        assertTrue(parsed.warnings().get(0).detail().has("rows_total"));
    }

    @Test
    void anOdsIsReadThroughContentXml() throws IOException {
        String content = """
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" \
                xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" \
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">\
                <office:body><table:table table:name="城市">\
                <table:table-row><table:table-cell><text:p>城市</text:p></table:table-cell>\
                <table:table-cell><text:p>人口</text:p></table:table-cell></table:table-row>\
                <table:table-row><table:table-cell><text:p>北京</text:p></table:table-cell>\
                <table:table-cell office:value-type="float" office:value="2100"><text:p>2100</text:p></table:table-cell></table:table-row>\
                </table:table></office:body></office:document-content>
                """;
        var parsed = ok(DocumentParser.parse("sheet.ods", ods(content)));
        assertTrue(parsed.text().contains("# Sheet: 城市"), parsed.text());
        assertTrue(parsed.text().contains("| 北京 | 2100 |"), parsed.text());
    }

    @Test
    void anOdsWithoutContentXmlIsUnreadable() throws IOException {
        var unreadable = assertInstanceOf(ParseResult.Unreadable.class,
                DocumentParser.parse("broken.ods", ods("<x/>")));
        assertNotNull(unreadable.reason());
    }

    // ---- pptx ----

    @Test
    void aPptxCoversThenSlideSections() {
        var parsed = ok(DocumentParser.parse("deck.pptx", pptx()));
        assertTrue(parsed.text().startsWith("巡星计划"), parsed.text());
        assertTrue(parsed.text().contains("## Slide 2"), parsed.text());
        assertTrue(parsed.text().contains("路线图"), parsed.text());
    }

    @Test
    void anEmptyPptxIsUnreadable() {
        var unreadable = assertInstanceOf(ParseResult.Unreadable.class,
                DocumentParser.parse("empty.pptx", pptxEmpty()));
        assertTrue(unreadable.reason().contains("No text could be extracted"));
    }

    // ---- html ----

    @Test
    void anHtmlArticleSurvivesTheChrome() {
        String body = "关于密码安全的深度报道，附来源与佐证细节。".repeat(40);
        String html = "<html><head><title>安全报道</title></head><body><nav>导航噪音</nav>"
                + "<article><h1>安全报道</h1><p>" + body + "</p></article><footer>页脚噪音</footer></body></html>";
        var parsed = ok(DocumentParser.parse("page.html", html.getBytes(StandardCharsets.UTF_8)));
        assertTrue(parsed.text().contains("安全报道"), parsed.text());
        assertTrue(parsed.text().contains("深度报道"), parsed.text());
        assertFalse(parsed.text().contains("导航噪音"), parsed.text());
        assertFalse(parsed.text().contains("页脚噪音"), parsed.text());
    }

    @Test
    void aLoginShellIsAnInterstitialNotAnArticle() {
        String html = "<html><body><h1>Sign in to continue</h1><form><input type='email'>"
                + "<input type='password'></form>"
                + "<p>Account access requires verification of your identity before proceeding. "
                + "Account access requires verification of your identity before proceeding.</p>"
                + "</body></html>";
        var unreadable = assertInstanceOf(ParseResult.Unreadable.class,
                DocumentParser.parse("login.html", html.getBytes(StandardCharsets.UTF_8)));
        assertTrue(unreadable.reason().contains("interstitial"), unreadable.reason());
    }

    // ---- csv / tsv ----

    @Test
    void aCsvIsATableWhoseFirstRecordIsTheHeader() {
        var parsed = ok(DocumentParser.parse("t.csv", "产品,2025,2024\n收入,10,8\n成本,4,3\n".getBytes()));
        assertEquals("| 产品 | 2025 | 2024 |\n| --- | --- | --- |\n| 收入 | 10 | 8 |\n| 成本 | 4 | 3 |\n",
                parsed.text());
    }

    @Test
    void aTsvAndQuotedFieldsKeepTheirColumns() {
        var parsed = ok(DocumentParser.parse("t.tsv",
                "名称\tsymbol\t值\n\"价格, 零售\"\t$\t10\n".getBytes()));
        assertTrue(parsed.text().startsWith("| 名称 | symbol | 值 |"), parsed.text());
        assertTrue(parsed.text().contains("| 价格, 零售 | $ | 10 |"), parsed.text());
    }

    // ---- 二进制/空/编码 ----

    @Test
    void aBinaryIsNotDecodedAsText() {
        // 老式 .doc：开头夹 NUL 且非 UTF-8（从前会解成乱码进库）
        var unreadable = assertInstanceOf(ParseResult.Unreadable.class, DocumentParser.parse("old.doc",
                new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0, 'x'}));
        assertTrue(unreadable.reason().contains("not in a format"), unreadable.reason());
    }

    @Test
    void anEmptyOrWhitespaceFileIsUnreadable() {
        var unreadable = assertInstanceOf(ParseResult.Unreadable.class,
                DocumentParser.parse("empty.txt", "   \n\n  ".getBytes()));
        assertTrue(unreadable.reason().contains("No text could be extracted"));
    }

    @Test
    void aGbkFileIsDecoded() {
        String body = "星野科技成立于二〇二一年五月，总部位于北京中关村软件园。"
                + "公司主营商业航天地面站设备研发，客户覆盖国内主要卫星运营商。"
                + "创始人陈北望此前在航天科技集团工作十五年，负责测控系统总体设计。";
        var parsed = ok(DocumentParser.parse("cn.txt", body.getBytes(Charset.forName("GBK"))));
        assertTrue(parsed.text().contains("星野科技成立于二〇二一年五月"), parsed.text());
        assertTrue(parsed.text().contains("测控系统总体设计"), parsed.text());
    }

    @Test
    void aUtf16FileWithBomIsDecodedAndNotBinary() {
        var parsed = ok(DocumentParser.parse("u16.txt", "\uFEFF张三在2024年加入公司。".getBytes(StandardCharsets.UTF_16LE)));
        assertTrue(parsed.text().contains("张三在2024年加入公司"), parsed.text());
    }

    @Test
    void textWithNulButValidUtf8IsStillRead() {
        // PDF 文本层抄来的正文夹着 NUL 但是合法 UTF-8：照读（#611）
        var parsed = ok(DocumentParser.parse("layer.md", "Revenue grew\0 twelve percent.".getBytes()));
        assertTrue(parsed.text().contains("Revenue grew"));
    }

    @Test
    void normalizeCollapsesBlankRunsAndUnifiesNewlines() {
        var parsed = ok(DocumentParser.parse("n.txt", "a\r\n\r\n\r\nb\rc\n".getBytes()));
        assertEquals("a\n\nb\nc\n", parsed.text());
    }

    // ---- 生成器 ----

    private static byte[] pdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (!text.isEmpty()) {
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx() {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 样式表：id 是英文 Word 写法（Heading1）与中文 Word 写法（2），名字一律 heading N
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles ctStyles =
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles.Factory
                            .newInstance();
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle h1 =
                    ctStyles.addNewStyle();
            h1.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
            h1.setStyleId("Heading1");
            h1.addNewName().setVal("heading 1");
            h1.addNewPPr().addNewOutlineLvl().setVal(java.math.BigInteger.ZERO);
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle h2 =
                    ctStyles.addNewStyle();
            h2.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType.PARAGRAPH);
            h2.setStyleId("2");
            h2.addNewName().setVal("heading 2");
            doc.createStyles().setStyles(ctStyles);

            XWPFParagraph h1p = doc.createParagraph();
            h1p.setStyle("Heading1");
            XWPFRun run = h1p.createRun();
            run.setText("财务摘要");
            XWPFParagraph h2p = doc.createParagraph();
            h2p.setStyle("2");
            h2p.createRun().setText("发展历程");
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("Segment");
            table.getRow(0).getCell(1).setText("Revenue");
            table.getRow(1).getCell(0).setText("Cloud");
            table.getRow(1).getCell(1).setText("$1,200");
            table.getRow(2).getCell(0).setText("Devices");
            table.getRow(2).getCell(1).setText("$300");
            XWPFParagraph after = doc.createParagraph();
            after.createRun().setText("表格之后的段落。");
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] xlsx() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("业绩");
            XSSFRow header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品");
            header.createCell(1).setCellValue("收入");
            XSSFRow row = sheet.createRow(1);
            row.createCell(0).setCellValue("云");
            row.createCell(1).setCellValue(1200);
            book.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] ods(String contentXml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.ISO_8859_1));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] pptx() {
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
                new org.apache.poi.xslf.usermodel.XMLSlideShow();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.createSlide().createTextBox().setText("巡星计划");
            ppt.createSlide().createTextBox().setText("路线图");
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pptxEmpty() {
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
                new org.apache.poi.xslf.usermodel.XMLSlideShow();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.createSlide();
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
