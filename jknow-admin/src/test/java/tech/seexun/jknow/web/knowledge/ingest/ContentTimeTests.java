package tech.seexun.jknow.web.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 上传正文日期识别（documents_routes.rs:595-724 逐字语义的单元面）：文件名 ISO/中文
 * 日期 → 正文首行独立日期行（4KiB 头、剥 BOM、去括号）；只对 txt/md/markdown；
 * 不可能的日期（2023-02-29）拒绝；UTC 零点是存储约定。
 */
class ContentTimeTests {

    private static OffsetDateTime atUtc(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    private static OffsetDateTime contentTime(String filename, String text) {
        return ContentTime.contentTime(filename, text.getBytes(StandardCharsets.UTF_8));
    }

    // ---- 文件名 ISO：YYYY-MM-DD 与 YYYY-M-D ----

    @Test
    void filenameIsoDateWinsOverBody() {
        assertEquals(atUtc(2024, 2, 29), contentTime("2024-02-29_gaoshu.txt", "2023-01-01\n别的"));
        assertEquals(atUtc(2024, 2, 9), contentTime("2024-2-9.txt", ""));
    }

    @Test
    void impossibleIsoDateIsRejected() {
        assertNull(contentTime("2023-02-29.txt", ""), "chrono 拒绝 2023-02-29，Java 侧同拒");
        assertNull(contentTime("2024-13-01.txt", ""));
    }

    // ---- 文件名中文：YYYY年M月D日 ----

    @Test
    void filenameChineseDate() {
        assertEquals(atUtc(2024, 2, 29), contentTime("2024年2月29日_公告.txt", "正文没日期"));
        assertEquals(atUtc(2024, 12, 5), contentTime("2024年12月5日.txt", ""));
    }

    @Test
    void filenameWithoutFourDigitYearNeverParses() {
        assertNull(contentTime("notes.txt", "no dates here"));
        assertNull(contentTime("24-02-29.txt", "no dates here"));
        assertNull(contentTime("2024-02-29.pdf", "no dates here"));
    }

    // ---- 正文首行：ISO / 中文 / 括号包裹；BOM 剥离 ----

    @Test
    void bodyFirstLineIsoDate() {
        assertEquals(atUtc(2024, 2, 29), contentTime("note.txt", "\n  2024-02-29  \n后面"));
        assertEquals(atUtc(2024, 2, 29), contentTime("note.md", "2024-2-29\n第二行"));
    }

    @Test
    void bodyFirstLineChineseDateWithParenthesesAndBom() {
        // 剥 BOM + 全角括号包裹（公告、备案一类的惯用形态）
        assertEquals(atUtc(2024, 2, 29),
                contentTime("note.txt", "\uFEFF（2024年2月29日）\n正文"));
        assertEquals(atUtc(2024, 2, 29),
                contentTime("note.txt", "(2024-02-29)\n正文"));
    }

    @Test
    void bodyDateMustBeOnAnIndependentFirstLine() {
        // 事件日期在正文里提到，不当作文档日期（#610）
        assertNull(contentTime("note.txt", "公司于 2024-02-29 发布\n"));
        assertNull(contentTime("note.txt", "关于公告\n2024-02-29"));
    }

    @Test
    void truncatedHeaderDropsTheHalfLine() {
        StringBuilder big = new StringBuilder();
        while (big.length() < 8192) {
            big.append("padding padding padding\n");
        }
        // 日期行在 4KiB 头之外：读不到，null
        assertNull(contentTime("note.txt", big + "2024-02-29 某某公告\n"));
        // 日期行是第一行且完整落在头内（即便总长 > 4KiB，截断丢的是后面的半行）：认
        assertEquals(atUtc(2024, 2, 29), contentTime("note.txt", "2024-02-29\n" + big));
        // 头 4096 字节里没有任何换行（一行超长被截断）：没有完整独立日期行 → null
        assertNull(contentTime("note.txt", "2024-02-29 " + "x".repeat(8192)));
    }

    // ---- 扩展名白名单：只有 txt/md/markdown ----

    @Test
    void onlyTextExtensionsTryDateParsing() {
        assertEquals(atUtc(2024, 2, 29), contentTime("2024-02-29.markdown", ""));
        assertEquals(atUtc(2024, 2, 29), contentTime("2024-02-29.TXT", ""));
    }
}
