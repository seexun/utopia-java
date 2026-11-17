package tech.seexun.jknow.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：融合多路召回的排名（k=60 为经验常数）。
 * 逐字对齐旧仓库 utopia-search/src/lib.rs:173-190 rrf_fuse——含同分按 id 定序
 * （HashMap 随机顺序不得改变上限内保留的证据和口径）。
 *
 * <p>M1 任务 6 落在 utopia-search；M3 任务 10 起分块检索（utopia-search）与口径检索
 * （utopia-store MappingsService）两处都要融合——单一实现上移 common（对等 Rust 侧
 * utopia-search::rrf_fuse 被 retrieval.rs 与 mapping_index.rs 共用）。向量通道未配
 * 嵌入模型时不存在（retrieval.rs 的静默降级），单通道传入即退化为「那一路的原顺序」
 * （lib.rs rrf_tests one_list_comes_back_in_its_own_order）。
 */
public final class Rrf {

    private Rrf() {
    }

    /** 融合多路名次并列：score = Σ 1/(K + rank + 1)，按分数降序、同分按 id 升序，取前 limit。 */
    public static List<String> fuse(List<List<String>> lists, int limit) {
        final double k = 60.0;
        Map<String, Double> scores = new HashMap<>();
        for (List<String> list : lists) {
            for (int rank = 0; rank < list.size(); rank++) {
                scores.merge(list.get(rank), 1.0 / (k + rank + 1.0), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        // 同分按 id 定序，否则 HashMap 的随机顺序会改变上限内保留的证据和口径
        // （String.compareTo 对 UUID 文本与 Rust String::cmp 的字节序一致）。
        ranked.sort(Map.Entry.<String, Double>comparingByValue(java.util.Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        List<String> out = new ArrayList<>(Math.min(limit, ranked.size()));
        for (Map.Entry<String, Double> e : ranked) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e.getKey());
        }
        return out;
    }
}
