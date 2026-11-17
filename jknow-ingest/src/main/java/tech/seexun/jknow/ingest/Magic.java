package tech.seexun.jknow.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 魔数探测（lib.rs media_kind + infer crate 的对等子集）。文件头优先——扩展名可能撒谎
 * （notes.txt 实为 PNG 也得走 OCR）；认不出文件头的才看扩展名。
 */
final class Magic {

    private Magic() {
    }

    private static final String[] IMAGE_EXTS = {
        "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp", "heic", "heif"};
    private static final String[] AUDIO_EXTS = {
        "mp3", "wav", "m4a", "flac", "ogg", "oga", "opus", "aac", "amr", "wma"};
    private static final String[] VIDEO_EXTS = {"mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv"};

    /** 媒体判定结果：图片/录音等读取器（NeedsReader），或视频（Unreadable）；非媒体 null。 */
    enum Media {
        IMAGE(ReaderKind.Ocr, "image"),

        AUDIO(ReaderKind.Transcribe, "recording"),

        VIDEO(null, null);

        final ReaderKind reader;
        final String what;

        Media(ReaderKind reader, String what) {
            this.reader = reader;
            this.what = what;
        }
    }

    /** 按文件头（认得出来的话）或扩展名判断这是不是要模型读的媒体文件；不是媒体返回 null。 */
    static Media mediaKind(byte[] bytes, String ext) {
        HeaderType byHeader = sniff(bytes);
        if (byHeader != null) {
            return byHeader.media;
        }
        if (in(IMAGE_EXTS, ext)) {
            return Media.IMAGE;
        }
        if (in(AUDIO_EXTS, ext)) {
            return Media.AUDIO;
        }
        if (in(VIDEO_EXTS, ext)) {
            return Media.VIDEO;
        }
        return null;
    }

    /**
     * 格式魔数（parse() 分派用，对等 infer::get().extension()）：pdf / zip 家族成员
     * （docx/xlsx/pptx/ods 按包内首件认）/ 认不出返回 ""。
     */
    static String formatKind(byte[] bytes) {
        HeaderType t = sniff(bytes);
        if (t == null) {
            return "";
        }
        return switch (t) {
            case PDF -> "pdf";
            case IMAGE, AUDIO, VIDEO -> "";
            case ZIP -> zipMember(bytes);
        };
    }

    private enum HeaderType {
        PDF(null),

        IMAGE(Media.IMAGE),

        AUDIO(Media.AUDIO),

        VIDEO(Media.VIDEO),

        ZIP(null);

        final Media media;

        HeaderType(Media media) {
            this.media = media;
        }
    }

    private static HeaderType sniff(byte[] b) {
        if (at(b, 0, new byte[] {(byte) 0xFF, (byte) 0xFE})
                || at(b, 0, new byte[] {(byte) 0xFE, (byte) 0xFF})) {
            return null; // UTF-16 字节序标记：是文本不是媒体
        }
        if (at(b, 0, "%PDF".getBytes(StandardCharsets.ISO_8859_1))) {
            return HeaderType.PDF;
        }
        if (at(b, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G'})) {
            return HeaderType.IMAGE;
        }
        if (at(b, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return HeaderType.IMAGE; // JPEG
        }
        if (at(b, 0, "GIF8".getBytes(StandardCharsets.ISO_8859_1))) {
            return HeaderType.IMAGE;
        }
        if (at(b, 0, new byte[] {'B', 'M'}) && b.length > 6) {
            return HeaderType.IMAGE; // BMP（魔数 BM+文件长）
        }
        if (at(b, 0, new byte[] {'I', 'I', 0x2A, 0}) || at(b, 0, new byte[] {'M', 'M', 0, 0x2A})) {
            return HeaderType.IMAGE; // TIFF
        }
        if (at(b, 0, "RIFF".getBytes(StandardCharsets.ISO_8859_1)) && b.length >= 12) {
            if (at(b, 8, "WAVE".getBytes(StandardCharsets.ISO_8859_1))) {
                return HeaderType.AUDIO;
            }
            if (at(b, 8, "AVI ".getBytes(StandardCharsets.ISO_8859_1))) {
                return HeaderType.VIDEO;
            }
            if (at(b, 8, "WEBP".getBytes(StandardCharsets.ISO_8859_1))) {
                return HeaderType.IMAGE;
            }
            return null;
        }
        if (at(b, 4, "ftyp".getBytes(StandardCharsets.ISO_8859_1)) && b.length >= 12) {
            String brand = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
            if (brand.startsWith("he") || brand.equals("mif1") || brand.equals("msf1")) {
                return HeaderType.IMAGE; // HEIC/HEIF
            }
            if (brand.equals("M4A") || brand.equals("M4B")) {
                return HeaderType.AUDIO;
            }
            return HeaderType.VIDEO; // isom/mp41/mp42/qt/avc1/dash…
        }
        if (at(b, 0, "ID3".getBytes(StandardCharsets.ISO_8859_1))) {
            return HeaderType.AUDIO; // MP3 带 tag
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0 && (b[1] & 0xFE) != 0xFE) {
            // MPEG 音频帧同步（MP3/AAC ADTS）；MP3 常见 FB/F3，AAC ADTS F1/F9。
            // FF FE 是 UTF-16 BOM（已在入口排除），FF FF 不是有效帧
            return HeaderType.AUDIO;
        }
        if (at(b, 0, "fLaC".getBytes(StandardCharsets.ISO_8859_1))) {
            return HeaderType.AUDIO;
        }
        if (at(b, 0, "OggS".getBytes(StandardCharsets.ISO_8859_1))) {
            return HeaderType.AUDIO; // OGG/Opus
        }
        if (at(b, 0, "#!AMR".getBytes(StandardCharsets.ISO_8859_1))) {
            return HeaderType.AUDIO;
        }
        if (at(b, 0, new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            return HeaderType.VIDEO; // EBML（MKV/WebM）
        }
        if (at(b, 0, new byte[] {'P', 'K', 0x03, 0x04})
                || at(b, 0, new byte[] {'P', 'K', 0x05, 0x06})) {
            return HeaderType.ZIP;
        }
        return null;
    }

    /** zip 包里认 OOXML 家族（docx=word/、xlsx=xl/、pptx=ppt/）或 ODS（mimetype 件）。 */
    private static String zipMember(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("word/document.xml")) {
                    return "docx";
                }
                if (name.equals("xl/workbook.xml")) {
                    return "xlsx";
                }
                if (name.equals("ppt/presentation.xml")) {
                    return "pptx";
                }
                if (name.equals("mimetype")) {
                    byte[] buf = new byte[(int) Math.min(entry.getSize() < 0 ? 256 : entry.getSize(), 256)];
                    int n = zip.read(buf);
                    String mime = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                    if (mime.contains("opendocument.spreadsheet")) {
                        return "ods";
                    }
                    if (mime.contains("opendocument.text")) {
                        return "odt";
                    }
                }
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    private static boolean at(byte[] b, int off, byte[] magic) {
        if (b.length < off + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (b[off + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean in(String[] exts, String ext) {
        for (String e : exts) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 文件扩展名（小写；无点返回空串）。 */
    static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
