package tech.seexun.jknow.store.entity;

/**
 * 这个库对这种类型对的习惯（governance.rs:49-54 TypePairStats）：人合了多少、分了多少、
 * 合了又撤回多少。
 */
public record TypePairStats(long merged, long kept, long reverted) {
}
