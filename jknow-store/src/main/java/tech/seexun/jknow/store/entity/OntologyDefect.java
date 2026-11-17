package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 本体自相矛盾行（models.rs:1287-1303 OntologyDefect serde 形状；SQL reasoning.rs:2711-2729）。
 * subject/other 同一列指两张表（类或谓词），SQL 双 LEFT JOIN 取 label；path_labels 为环上
 * 类标签数组（text[]）。
 */
public record OntologyDefect(
        java.util.UUID id,
        String kind,
        JsonNode detail,
        String subjectLabel,
        String otherLabel,
        List<String> pathLabels,
        OffsetDateTime detectedAt) {
}
