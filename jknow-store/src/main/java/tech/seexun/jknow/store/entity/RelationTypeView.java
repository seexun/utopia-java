package tech.seexun.jknow.store.entity;

import java.util.List;
import java.util.UUID;

/**
 * 本体编辑器视图：关系类 + 公理开关 + 定义域/值域/qualifier 数组 + 使用量
 * （models.rs:559-595 RelationTypeView serde 形状；21 列全量——前端表单要回显
 * inverse_of/sub_property_of 与 datatype/unit，缺一格界面就把已声明的抹掉）。
 */
public record RelationTypeView(
        UUID id,
        String key,
        String label,
        String temporal,
        boolean functional,
        boolean inverseFunctional,
        boolean isTransitive,
        boolean isSymmetric,
        boolean isAsymmetric,
        boolean isIrreflexive,
        UUID inverseOf,
        UUID subPropertyOf,
        boolean builtin,
        String description,
        String kind,
        List<UUID> domains,
        List<UUID> ranges,
        List<UUID> qualifiers,
        String datatype,
        String unit,
        long usage) {
}
