package tech.seexun.jknow.ingest;

/**
 * 正文里的一段（UTF-8 字节区间，含头不含尾），连同它的出处。读扫描件、录音的引擎一页、
 * 一段地交回文字拼成一份正文交给分块；分块按段切开，一块只装一种出处（0040 决定 2）。
 */
public record Segment(int start, int end, Provenance provenance) {
}
