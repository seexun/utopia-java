package tech.seexun.jknow.common;

/**
 * 审核对的 reason 里召回通道留下的记号（utopia-core/src/lib.rs:11-31 mod review_reasons
 * 逐字）。名字向量召回（0041 第 2 刀通道 2）提的是两个<b>不同的字符串</b>，和同名家族
 * （{@code shared_name|}、{@code ambiguous_name|}、{@code namesake_tie|}）是两种证据强度：
 * 裁决器读它、治理闸门看它、召回写它，都从这里认，不各自拼前缀。
 */
public final class ReviewReasons {

    private ReviewReasons() {
    }

    /** {@code name_vector|<余弦>}：名字向量召回提的对。 */
    public static final String NAME_VECTOR = "name_vector|";

    /**
     * 这一对是名字向量召回提出来的（两个相近但不同的字符串）。
     * 「因相似而提议」和「因同名而提议」不该用同一根线自动合——0041 的度量台上
     * 相似召回把「张伟」并进了「财务部总监张伟」。
     */
    public static boolean similarityProposed(String reason) {
        return reason != null && reason.startsWith(NAME_VECTOR);
    }

    /** 名字向量召回记下的余弦文本；不是这种对时为 null。 */
    public static String nameVectorCosine(String reason) {
        if (reason == null || !reason.startsWith(NAME_VECTOR)) {
            return null;
        }
        return reason.substring(NAME_VECTOR.length());
    }
}
