package tech.seexun.jknow.web.knowledge.ingest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;

/**
 * 上传文档的正文日期识别（documents_routes.rs:595-724 逐字语义）：只认开头独立的完整
 * 日期行，不把正文里提到的事件日期当作文档日期（#610）。顺序=文件名→正文首行；
 * 只对 txt/md/markdown 试（PDF/Word 在各自解析器里读元数据，#610 决定 2）。
 * 与项目已有日精度约定一致：UTC 零点是存储约定，不猜作者所在时区。
 */
public final class ContentTime {

    private ContentTime() {
    }

    /** chrono "%Y-%m-%d" 的严格解析等价（SMART 会把 2023-02-29 静默折成 02-28——chrono 拒绝）。 */
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT);

    /** chrono "%Y年%m月%d日" 的严格解析等价。 */
    private static final DateTimeFormatter ZH =
            DateTimeFormatter.ofPattern("uuuu年M月d日").withResolverStyle(ResolverStyle.STRICT);

    /** documents_routes.rs:601-637 content_time：文件名 ISO/中文日期 → 正文首行日期行。 */
    public static OffsetDateTime contentTime(String filename, byte[] bytes) {
        if (!isTextExtension(filename)) {
            return null;
        }
        String stem = fileStem(filename);
        if (stem == null) {
            return null;
        }
        LocalDate day = parseFilenameDate(stem);
        if (day != null) {
            return day.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        }
        // 只解码头部 4 KiB：日期行只认开头（documents_routes.rs:610-612）
        final int headerBytes = 4096;
        String text = tech.seexun.jknow.ingest.TextCodecs.plainText(
                Arrays.copyOf(bytes, Math.min(bytes.length, headerBytes)));
        String header;
        if (bytes.length > headerBytes) {
            // 截断的半行可能在日期后还有文字，不能把它误当独立日期行：丢掉最后一段
            int lastNewline = text.lastIndexOf('\n');
            if (lastNewline < 0) {
                return null;
            }
            header = text.substring(0, lastNewline);
        } else {
            header = text;
        }
        header = stripLeading(header, '\uFEFF');
        String line = firstNonEmptyTrimmedLine(header);
        if (line == null) {
            return null;
        }
        String date = unwrapParens(line);
        // 头 4 位必须是数字（提前失败省一次解析；少于 4 字符同样不算）
        if (date.length() < 4 || !asciiDigits(date, 0, 4)) {
            return null;
        }
        LocalDate parsed = parseIso(date);
        if (parsed == null) {
            parsed = parseZh(date);
        }
        return parsed == null ? null : parsed.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    // ---- documents_routes.rs:641-651 is_text_extension ----

    private static boolean isTextExtension(String filename) {
        String ext = extension(filename);
        if (ext == null) {
            return false;
        }
        return ext.equalsIgnoreCase("txt") || ext.equalsIgnoreCase("md")
                || ext.equalsIgnoreCase("markdown");
    }

    /** Path::extension：最后一个 '.' 之后（无 '.' 或以 '.' 开头且无第二个 '.' → None）。 */
    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return filename.substring(dot + 1);
    }

    /** Path::file_stem：最后一个 '.' 之前（无 '.' → 整名；隐藏文件 ".gitignore" 整名）。 */
    private static String fileStem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    // ---- documents_routes.rs:653-724 parse_filename_date（手工解析，不依赖 regex） ----

    /**
     * 主名前缀里的 ISO {@code YYYY-MM-DD}/{@code YYYY-M-D} 或中文 {@code YYYY年M月D日}。
     * 严格：%Y 是 4 位数字；%m/%d 1-2 位；chrono 拒不可能的日期。
     */
    static LocalDate parseFilenameDate(String stem) {
        if (stem.length() < 4 || !asciiDigits(stem, 0, 4)) {
            return null;
        }
        String year = stem.substring(0, 4);
        // ISO：YYYY-MM-…，至少 8 个字符位
        if (stem.length() >= 8 && stem.charAt(4) == '-') {
            int monthEnd = digitEnd(stem, 5);
            String month = stem.substring(5, monthEnd);
            if (monthEnd >= stem.length() || stem.charAt(monthEnd) != '-') {
                return null;
            }
            int dayStart = monthEnd + 1;
            int dayEnd = digitEnd(stem, dayStart);
            String day = stem.substring(dayStart, dayEnd);
            return parseIso(year + "-" + month + "-" + day);
        }
        // 中文：年号后必须是「年」；月/日各读 1-2 位数字（read_ascii_digits take(2)）
        if (stem.length() <= 4 || stem.charAt(4) != '年') {
            return null;
        }
        int m2 = readAtMostTwoDigits(stem, 5);
        if (m2 == 5) {
            return null;
        }
        String month = stem.substring(5, m2);
        if (m2 >= stem.length() || stem.charAt(m2) != '月') {
            return null;
        }
        int d2 = readAtMostTwoDigits(stem, m2 + 1);
        if (d2 == m2 + 1) {
            return null;
        }
        String day = stem.substring(m2 + 1, d2);
        if (d2 >= stem.length() || stem.charAt(d2) != '日') {
            return null;
        }
        return parseZh(year + "年" + month + "月" + day + "日");
    }

    /** read_ascii_digits（documents_routes.rs:703-713）：从 start 读 1-2 位 ASCII 数字。 */
    private static int readAtMostTwoDigits(String s, int start) {
        int i = start;
        while (i < Math.min(start + 2, s.length()) && isAsciiDigit(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 正文行剥（…）/（…）括号（documents_routes.rs:624-628）。 */
    private static String unwrapParens(String line) {
        if (line.length() >= 2 && line.charAt(0) == '（' && line.charAt(line.length() - 1) == '）') {
            return line.substring(1, line.length() - 1);
        }
        if (line.length() >= 2 && line.charAt(0) == '(' && line.charAt(line.length() - 1) == ')') {
            return line.substring(1, line.length() - 1);
        }
        return line;
    }

    /** documents_routes.rs:619-623：剥 BOM 后第一行非空 trim 行。 */
    private static String firstNonEmptyTrimmedLine(String header) {
        for (String raw : header.split("\n", -1)) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    private static String stripLeading(String s, char c) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == c) {
            i++;
        }
        return s.substring(i);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean asciiDigits(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!isAsciiDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** find_ascii_digit_end：从 start 起走到第一个非数字（不设上限——3 位「月」交给解析器拒）。 */
    private static int digitEnd(String s, int start) {
        int i = start;
        while (i < s.length() && isAsciiDigit(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static LocalDate parseIso(String s) {
        try {
            return LocalDate.parse(s, ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseZh(String s) {
        try {
            return LocalDate.parse(s, ZH);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
