package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 图渲染边（对齐 Rust utopia-core models::GraphEdge，models.rs:710-763 serde 形状：
 * id/source/target/predicate/label/said_as/inferred/derived/qualifiers/rule/premises/
 * valid_from/valid_to/holds_from/holds_to/confidence/contested/blocked）。
 *
 * <p>qualifiers 不是边查询的列（Rust {@code #[sqlx(skip)]}，graph.rs:1470-1479 在
 * edges_among 尾部按边 id 批量 fact_qualifiers_for 回填）——canonical 构造器带它、
 * 缺省空表；MyBatis resultMap 走 17 参辅助构造器（无 qualifiers 列）。
 *
 * <p>confidence=f32（SQL 0::real / facts.confidence real 列）。
 * QualifierRow.fact_id 是 store 内部分组键（Rust QualifierRow 不出站，出站形状是
 * models::FactQualifier）——@JsonIgnore 滤掉，其余六字段同名 snake_case。
 */
public record GraphEdge(
        UUID id,
        UUID source,
        UUID target,
        String predicate,
        String label,
        String saidAs,
        boolean inferred,
        boolean derived,
        String rule,
        List<UUID> premises,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        OffsetDateTime holdsFrom,
        OffsetDateTime holdsTo,
        float confidence,
        boolean contested,
        boolean blocked,
        List<QualifierRow> qualifiers) {

    /** MyBatis &lt;constructor&gt; 用（17 个 SQL 列；qualifiers 缺省空表）。 */
    public GraphEdge(UUID id, UUID source, UUID target, String predicate, String label, String saidAs,
                     boolean inferred, boolean derived, String rule, List<UUID> premises,
                     OffsetDateTime validFrom, OffsetDateTime validTo, OffsetDateTime holdsFrom,
                     OffsetDateTime holdsTo, float confidence, boolean contested, boolean blocked) {
        this(id, source, target, predicate, label, saidAs, inferred, derived, rule, premises,
                validFrom, validTo, holdsFrom, holdsTo, confidence, contested, blocked, List.of());
    }

    /** qualifiers 回填（service 侧逐边替换，record 不可变 → 复制）。 */
    public GraphEdge withQualifiers(List<QualifierRow> q) {
        return new GraphEdge(id, source, target, predicate, label, saidAs, inferred, derived, rule,
                premises, validFrom, validTo, holdsFrom, holdsTo, confidence, contested, blocked, q);
    }

    /** fact_qualifiers 连上定义与实体名之后的一行（graph.rs:160-166 QualifierRow）。 */
    public record QualifierRow(
            @JsonIgnore UUID factId,
            UUID qualifierTypeId,
            String key,
            String label,
            com.fasterxml.jackson.databind.JsonNode value,
            UUID entityId,
            String entityName) {
    }
}
