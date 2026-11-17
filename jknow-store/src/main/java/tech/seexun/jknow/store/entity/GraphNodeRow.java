package tech.seexun.jknow.store.entity;

import java.util.UUID;

/**
 * 图渲染节点（对齐 Rust utopia-core models::GraphNode，models.rs:693-707 serde 形状）：
 * type_key/type_label 可空（0009：没判出类型的实体照样是节点，LEFT JOIN 不是 JOIN），
 * color/shape 由 SQL coalesce 给缺省值，disambiguator 同名消歧可空。degree=i64。
 */
public record GraphNodeRow(
        UUID id,
        String name,
        String typeKey,
        String typeLabel,
        String color,
        String shape,
        String disambiguator,
        long degree) {
}
