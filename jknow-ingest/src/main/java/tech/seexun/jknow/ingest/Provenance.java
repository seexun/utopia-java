package tech.seexun.jknow.ingest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一段文字从哪来（provenance.rs 逐字语义，0040）：文件里写着的（Stated）、扫描页上认出来的
 * （Ocr）、录音里转出来的（Transcribed）、模型看图说的（Described）。前三种是「原话，照读」，
 * 第四种没有原话可对——所以来源跟着分块走，一块只有一种出处。
 */
public record Provenance(Origin origin, String model, JsonNode anchor) {

    /** 文字的来源；数据库 chunks.origin 存 {@link Origin#asStr} 那个词。 */
    public enum Origin {
        Stated("stated"),

        Ocr("ocr"),

        Transcribed("transcribed"),

        Described("described");

        private final String asStr;

        Origin(String asStr) {
            this.asStr = asStr;
        }

        public String asStr() {
            return asStr;
        }

        /** 反查；认不出的回 null（不强行映射）。 */
        public static Origin parse(String s) {
            for (Origin o : values()) {
                if (o.asStr.equals(s)) {
                    return o;
                }
            }
            return null;
        }
    }

    /** 原文段落：没有锚点（char_start/char_end 已给出位置）。 */
    public static Provenance stated() {
        return new Provenance(Origin.Stated, null, null);
    }

    /** 锚点形状由来源决定（库上有 CHECK）：OCR 是 {"page":n}(+bbox)、转写是 {start_ms,end_ms,speaker}。 */
    public Provenance withAnchor(JsonNode anchor) {
        return new Provenance(origin, model, anchor);
    }
}
