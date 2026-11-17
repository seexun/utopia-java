package tech.seexun.jknow.store.entity;

/**
 * 审核队列计数行（models.rs:1461-1489 ReviewCounts serde 形状；SQL 列序见
 * ReviewMapper.selectCounts，review.rs:35-84 的 17 列单查询）。字段序=Rust 结构体序
 * （构造器按列名映射，序列化键序无关——Diff 规范器按键名排序）。
 */
public record ReviewCounts(
        long pending,
        long duplicates,
        long duplicatesSameType,
        long duplicatesTypeConflict,
        long conflicts,
        long unconfirmed,
        long lowconf,
        long mappings,
        long violations,
        long alignment,
        long errata,
        long defects,
        long merges,
        long agent,
        long agentRows,
        boolean agentRunning,
        long agentQueue) {
}
