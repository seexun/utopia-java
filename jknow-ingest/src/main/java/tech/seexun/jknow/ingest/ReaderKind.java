package tech.seexun.jknow.ingest;

/**
 * 读出字要靠哪一种模型（lib.rs:202-225 逐字语义）。图片/扫描件要版面识别（OCR），
 * 录音要带说话人的转写；都不是解析失败——文件没坏，只是这一步轮不到文本解析器。
 */
public enum ReaderKind {

    Ocr("ocr"),

    Transcribe("transcribe");

    private final String asStr;

    ReaderKind(String asStr) {
        this.asStr = asStr;
    }

    /** 数据库 reader 列存的那个词。 */
    public String asStr() {
        return asStr;
    }
}
