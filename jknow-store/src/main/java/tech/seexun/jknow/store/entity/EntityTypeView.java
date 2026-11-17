package tech.seexun.jknow.store.entity;

import java.util.List;
import java.util.UUID;

/**
 * 本体编辑器视图：实体类 + 使用量（models.rs:540-557 EntityTypeView serde 形状）。
 * parents/disjoint 由 SQL 数组子查询聚合（uuid[]）；usage=该类下在世实体计数
 * （LEFT JOIN 聚合，无实体为 0——COALESCE(u.usage, 0)）。
 */
public record EntityTypeView(
        UUID id,
        String key,
        String label,
        String color,
        String shape,
        boolean builtin,
        List<UUID> parents,
        UUID primaryParent,
        List<UUID> disjoint,
        String description,
        long usage) {
}
