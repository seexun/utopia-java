package tech.seexun.jknow.store.entity;

import java.util.List;
import tech.seexun.jknow.store.GovernanceService;

/**
 * 一对名字的四族先例（governance.rs:56-66 Precedents）。
 */
public record Precedents(
        /** 人对这两个名字（不分左右）做过的决定 */
        List<Precedent> samePair,
        /** 其中一个名字对别的名字：这个名字是重名磁铁，还是一直被合 */
        List<Precedent> sameName,
        /** 这个库对这种类型对的习惯；一侧没类型就没有 */
        TypePairStats typePair,
        /** 涉及任一名字的撤回：有一笔就只建议不动手 */
        List<Precedent> reverts) {

    /** 空先例（没有任何历史）。 */
    public static Precedents none() {
        return new Precedents(List.of(), List.of(), null, List.of());
    }

    /**
     * 历史同意这个判决（governance.rs:525-537 {@code supports}）：这一对被人这么裁过、
     * 这个名字每次都这么裁、或这种类型对有这个习惯（够 {@link GovernanceService#TYPE_PAIR_MIN}
     * 笔；合并的习惯还要求没撤回过）。
     */
    public boolean supports(boolean same) {
        boolean pair = samePair.stream().anyMatch(x -> x.merged() == same);
        boolean name = !sameName.isEmpty() && sameName.stream().allMatch(x -> x.merged() == same);
        boolean habit = typePair != null
                && typePair.merged() + typePair.kept() >= GovernanceService.TYPE_PAIR_MIN
                && (same
                        ? typePair.merged() >= typePair.kept() && typePair.reverted() == 0
                        : typePair.kept() > typePair.merged());
        return pair || name || habit;
    }
}
