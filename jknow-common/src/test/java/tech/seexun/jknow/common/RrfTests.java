package tech.seexun.jknow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RRF 融合语义（逐字移植旧仓库 utopia-search/src/lib.rs:192-256 rrf_tests 六断言——
 * 单通道退化/倒数名次算术/空路不添乱/融合后裁/同分定序不受 HashMap 顺序影响）。
 */
class RrfTests {

    private static List<String> ids(String... list) {
        return List.of(list);
    }

    /** 一路召回就是那一路的顺序，融合不许重排（lib.rs:199-205）。 */
    @Test
    void oneListComesBackInItsOwnOrder() {
        List<List<String>> lists = List.of(ids("a", "b", "c"));
        assertEquals(ids("a", "b", "c"), Rrf.fuse(lists, 10));
    }

    /** 两路都点名的排最前；只在一路里的按各自名次的倒数相加比大小：
     * a = 1/61 + 1/62，c = 1/63 + 1/61，b = 1/62（lib.rs:207-212）。 */
    @Test
    void twoListsFollowTheReciprocalRankArithmetic() {
        List<List<String>> lists = List.of(ids("a", "b", "c"), ids("c", "a"));
        assertEquals(ids("a", "c", "b"), Rrf.fuse(lists, 10));
    }

    /** 空的那一路不是失败的那一路：它什么分都不加，也不影响别人（lib.rs:214-218）。 */
    @Test
    void anEmptyListContributesNothing() {
        List<List<String>> lists = List.of(ids("a", "b"), List.of());
        assertEquals(ids("a", "b"), Rrf.fuse(lists, 10));
    }

    /** 上限在融合之后裁，不是每路各裁（lib.rs:220-226）。 */
    @Test
    void theLimitCutsAfterFusion() {
        List<List<String>> lists = List.of(ids("a", "b"), ids("b", "c"));
        assertEquals(ids("b"), Rrf.fuse(lists, 1));
    }

    /** 同分候选在每个上限切法下都稳定（lib.rs:228-244：随机顺序的抗性）。 */
    @Test
    void equalScoresKeepTheSameCandidatesAtEveryLimit() {
        List<List<String>> lists = new java.util.ArrayList<>(
                List.of(ids("z", "b"), ids("y", "a")));
        List<String> expected = ids("y", "z", "a", "b");
        // 每轮新建 HashMap（等价 Rust 每次调用重新播种）：两头必须先于各自的次名，
        // 且切在任何平分点上结果稳定
        for (int i = 0; i < 32; i++) {
            for (int limit = 0; limit <= expected.size() + 1; limit++) {
                List<String> want = expected.subList(0, Math.min(limit, expected.size()));
                assertEquals(want, Rrf.fuse(lists, limit));
            }
            java.util.Collections.reverse(lists);
        }
    }

    /** 对面名次凑出的同总分按 id 定序，不得偏爱先到的通道（lib.rs:246-255）。 */
    @Test
    void equalScoresFromOppositeRanksAreOrderedById() {
        List<List<String>> lists = List.of(ids("c", "a", "b"), ids("b", "a", "c"));
        // b 与 c 各得 1/61 + 1/63，高于 a 的 2/62
        for (int i = 0; i < 32; i++) {
            assertEquals(ids("b", "c", "a"), Rrf.fuse(lists, 3));
            assertEquals(ids("b"), Rrf.fuse(lists, 1));
        }
    }
}
