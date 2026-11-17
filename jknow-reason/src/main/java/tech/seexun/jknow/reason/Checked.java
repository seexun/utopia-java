package tech.seexun.jknow.reason;

import java.util.List;
import java.util.UUID;

/**
 * 一次检查的完整结果（lib.rs:142-148）：违规，以及哪些谓词的环没搜完。
 *
 * @param violations  检出的违规
 * @param cyclesCapped 撞上 {@code MAX_CYCLES_PER_PREDICATE} 或 {@code MAX_CYCLE_STEPS}、环没搜完的谓词，排过序。
 *                    <b>必须回给调用方</b>：没搜完不能说没有——落库那边据此不清这些谓词上旧的环，
 *                    界面据此说「这个谓词的环太多，先看公理」
 */
public record Checked(List<Violation> violations, List<UUID> cyclesCapped) {

    public static Checked empty() {
        return new Checked(List.of(), List.of());
    }
}
