package tech.seexun.jknow.ingest;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.mozilla.universalchardet.UniversalDetector;

/**
 * 文本解码与二进制判别（parsers.rs plain_text + lib.rs looks_binary 逐字语义）：
 *
 * <ul>
 *   <li>plainText：BOM 先认（UTF-8/UTF-16），否则 juniversalchardet 探测（覆盖 GBK/GB18030/
 *       BIG5/UTF-16 等），探不出按 UTF-8；坏字节替换不抛（chardetng decode 同语义）；</li>
 *   <li>looksBinary：开头 8KiB 夹 NUL <b>且</b>不是合法 UTF-8 才算二进制——两条都要
 *       （PDF 文本层抄来的正文夹 NUL 但合法 UTF-8，照读；GBK 不夹 NUL 也不算），
 *       UTF-16 字节序标记豁免；截断恰切在多字节字符中间的那一刀不算坏。</li>
 * </ul>
 */
public final class TextCodecs {

    private TextCodecs() {
    }

    /**
     * 解码（编码探测覆盖 GBK 等）。pub：Rust 侧同函数以 {@code decode_text} 名义再导出
     * （lib.rs:178），上传路径的正文日期识别要读 4KiB 头（documents_routes.rs:612）。
     *
     * <p>解码后剥<b>单个</b>前导 \uFEFF（任务 2 挂账：Rust 走 encoding_rs 解码，BOM 在
     * 解码层就被吃掉；JDK {@code new String(bytes, charset)} 把 UTF-8/UTF-16 BOM 解成
     * 正文里的 \uFEFF——不剥的话首标题丢面包屑（首块以 "\uFEFF# 标题" 起头，不匹配
     * 行首 {@code #}），且污染物入库）。只剥一个：正文中间出现的零宽不换行字符是内容。
     */
    public static String plainText(byte[] bytes) {
        Charset charset = detect(bytes);
        String text = new String(bytes, charset);
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static Charset detect(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return StandardCharsets.UTF_8;
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return StandardCharsets.UTF_16LE;
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return StandardCharsets.UTF_16BE;
        }
        String name;
        try {
            name = UniversalDetector.detectCharset(new java.io.ByteArrayInputStream(bytes));
        } catch (java.io.IOException e) {
            name = null;
        }
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset cs = Charset.forName(name);
            // 探测器说 UTF-16 却没有 BOM：多数是误报（两字节错读），按 UTF-8 再看
            if (StandardCharsets.UTF_16.equals(cs) || StandardCharsets.UTF_16BE.equals(cs)
                    || StandardCharsets.UTF_16LE.equals(cs)) {
                return StandardCharsets.UTF_8;
            }
            return cs;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 按文本解码之前看一眼是不是二进制（lib.rs:299-312 逐字语义）。 */
    static boolean looksBinary(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) {
            return false;
        }
        int head = Math.min(bytes.length, 8192);
        boolean hasNul = false;
        for (int i = 0; i < head; i++) {
            if (bytes[i] == 0) {
                hasNul = true;
                break;
            }
        }
        if (!hasNul) {
            return false;
        }
        return !validUtf8Prefix(bytes, head);
    }

    /** head 长的一段是不是合法 UTF-8：截断切在多字节字符中间（只剩尾巴几字节）不算坏。 */
    private static boolean validUtf8Prefix(byte[] bytes, int end) {
        int i = 0;
        while (i < end) {
            int b0 = bytes[i] & 0xFF;
            if (b0 < 0x80) {
                i++;
                continue;
            }
            int len;
            int min;
            if (b0 >= 0xC2 && b0 <= 0xDF) {
                len = 2;
                min = 0x80;
            } else if (b0 >= 0xE0 && b0 <= 0xEF) {
                len = 3;
                min = 0x800;
            } else if (b0 >= 0xF0 && b0 <= 0xF4) {
                len = 4;
                min = 0x10000;
            } else {
                return false; // 裸 continuation / 过长序列
            }
            if (i + len > end) {
                return end - i < 4 && i + len <= bytes.length; // 尾巴被 8KiB 截断
            }
            int cp = b0 & (0x7F >> len);
            for (int k = 1; k < len; k++) {
                int bk = bytes[i + k] & 0xFF;
                if (bk < 0x80 || bk > 0xBF) {
                    return false;
                }
                cp = cp << 6 | (bk & 0x3F);
            }
            if (cp < min || cp > 0x10FFFF || cp >= 0xD800 && cp <= 0xDFFF) {
                return false;
            }
            i += len;
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
