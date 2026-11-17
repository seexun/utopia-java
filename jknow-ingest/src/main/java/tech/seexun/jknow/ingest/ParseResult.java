package tech.seexun.jknow.ingest;

import java.util.List;

/**
 * 解析产物（lib.rs parse() 的三态，Rust 用 Err 挂 NeedsReader/Unreadable、Java 收成一个
 * 代数类型，M2 任务 5 的管线按 kind 分派降级/终态）：
 *
 * <ul>
 *   <li>{@link Text}——读出文字（已 normalize：统一换行、压连续空行）；</li>
 *   <li>{@link NeedsReader}——文件没坏，字要靠模型读（图片→OCR、录音→转写、扫描 PDF→OCR）；
 *       配了对应服务就走读取器，没配降级 set_needs_reader 并挂 Terminal；</li>
 *   <li>{@link Unreadable}——换什么模型也读不了（视频、二进制、空文件），重试没用。</li>
 * </ul>
 */
public sealed interface ParseResult {

    /** 读出的正文（normalize 之后）；夹 NUL 的 PDF 文本层在管线里入库前剥（#611）。 */
    record Text(String text, List<ParseWarning> warnings) implements ParseResult {
        public Text(String text) {
            this(text, List.of());
        }
    }

    /**
     * 要模型读。what 是给人看的文件类别（image / recording / scanned PDF），
     * reason 是 NeedsReader 的完整报错文案（set_needs_reader 的 error 列）。
     */
    record NeedsReader(ReaderKind kind, String what, String reason) implements ParseResult {
        public NeedsReader(ReaderKind kind, String what) {
            this(kind, what, "This " + what + " has no text layer; reading it needs "
                    + (kind == ReaderKind.Ocr
                            ? "a document-reading (OCR) service"
                            : "a transcription model that labels speakers"));
        }
    }

    /** 读不了，换什么模型也一样。 */
    record Unreadable(String reason) implements ParseResult {
    }
}
