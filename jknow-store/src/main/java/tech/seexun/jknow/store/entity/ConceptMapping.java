package tech.seexun.jknow.store.entity;

import java.util.UUID;

/**
 * 语义层映射行（models.rs:1221-1245 ConceptMapping serde 形状；SQL mappings.rs:141-148）。
 * 字段是列不是 JSON 键（0011）。
 */
public record ConceptMapping(
        UUID id,
        UUID conceptId,
        String conceptName,
        String source,
        String tableName,
        String expr,
        String sql,
        String unit,
        String summary,
        boolean derived,
        String status,
        UUID writtenBy) {
}
