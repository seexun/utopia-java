package tech.seexun.jknow.store.entity;

import java.util.UUID;

/**
 * 某个类下的实体实例行（models.rs:532-537 EntityInstance）：详情区右侧实例列表，
 * fact_count=该实体触达的活事实数（SQL 相关子查询，ontology.rs:47-50）。
 */
public record EntityInstance(
        UUID id,
        String name,
        long factCount) {
}
