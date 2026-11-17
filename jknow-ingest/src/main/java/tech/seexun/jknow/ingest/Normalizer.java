package tech.seexun.jknow.ingest;

/**
 * 压缩连续空白行、统一换行符（lib.rs normalize 逐字语义）：\r\n/\r 都归 \n，
 * 每行去尾空白，连续空行压成一行，行尾一律补 \n。
 */
public final class Normalizer {

    private Normalizer() {
    }

    static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(unified.length());
        int blankRun = 0;
        int i = 0;
        int n = unified.length();
        while (i < n) {
            int lineEnd = unified.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = n;
            }
            String line = unified.substring(i, lineEnd);
            i = lineEnd + 1;
            String trimmed = stripTrailing(line);
            if (trimmed.isEmpty()) {
                blankRun++;
                if (blankRun <= 1) {
                    out.append('\n');
                }
            } else {
                blankRun = 0;
                out.append(trimmed).append('\n');
            }
        }
        return out.toString();
    }

    /** 剥 NUL（#611）：PDF 文本层抄来的正文常夹 NUL，管线在算长度、分块之前剥（pipeline.rs:130）。 */
    public static String withoutNul(String text) {
        return text.indexOf('\0') < 0 ? text : text.replace("\0", "");
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
