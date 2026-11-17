package tech.seexun.jknow.reason;

import java.util.List;
import java.util.UUID;

/**
 * 查出来的一处矛盾（lib.rs:62-73）。落库在 {@code axiom_violations}——<b>不进
 * {@code fact_conflicts}</b>：那张表问的是「哪条对」，而公理违规问的是「错在数据还是错在
 * 定义」，后者的出路可能是去改本体。
 *
 * @param kind  违规种类
 * @param left  涉及的事实。<b>自反违反只有一条</b>——它跟自己矛盾，不需要第二条。
 *              环取首尾两条（中间那些在 {@code path} 里）；互斥的三类（asymmetry、functional、
 *              inverse_functional）取整组里 id 最小与最大的两条；签名与派生撞断言同一条/被撞+末前提
 * @param right 同上第二条
 * @param path  环的完整路径，按事实排列；互斥的三类是整组事实，按 id 排序；自环为空。
 *              留着是因为「A→B→C→A」比「A 与 C 矛盾」有用得多——人要顺着看一遍才知道
 *              该撤哪一条
 */
public record Violation(Kind kind, UUID left, UUID right, List<UUID> path) {
}
