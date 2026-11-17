package tech.seexun.jknow.store;

/**
 * 置信度常量族（extraction.rs:93-106 的 origin_ceiling/DESCRIBED_CEILING + review.rs:20
 * 的 LOW_CONFIDENCE_BELOW——后者 M1 已落在 {@link ReviewService}，这里引用它，两处各写
 * 一个数迟早分叉成「徽标说 12 条，点进去 9 条」）。
 *
 * <p><b>为什么 described 要压顶</b>（0040 决定 4）：模型看图描述出来的事实，置信度压到
 * 低于审核的低置信阈值，于是它在待审里露面——看图描述的事实能进图、能被引用。
 * 「不许它单独关掉一段正确的旧值」这一条<b>不再靠这个数字</b>：0045 第 3 刀之后时态引擎
 * 读的是出处与起点的来历，看图描述出来的行照旧不关、记一条矛盾交给人（柱状图读错一个数
 * 是常事，而库里没有一行会说这次关闭靠的是一张图）——那一半由 temporal 引擎的 DESCRIBED
 * 语义实现（第 4 块 temporal.rs），这一半只管「能进图、能在待审露面」。
 */
public final class Confidence {

    private Confidence() {
    }

    /**
     * extraction.rs:97：看图描述出来的事实的置信度上限——低于 {@link
     * ReviewService#LOW_CONFIDENCE_BELOW}，于是它在待审里露面。
     */
    public static final float DESCRIBED_CEILING = ReviewService.LOW_CONFIDENCE_BELOW - 0.05f;

    /**
     * extraction.rs:100-106 {@code origin_ceiling}：这块文字的来源给事实置信度设的上限。
     * 其余来源不动——这是「上限」不是「统一值」：文档自己写的事实不需要压。
     */
    public static float originCeiling(String origin, float confidence) {
        if ("described".equals(origin)) {
            return Math.min(confidence, DESCRIBED_CEILING);
        }
        return confidence;
    }
}
