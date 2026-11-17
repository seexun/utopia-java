package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.entity.GraphNodeRow;
import tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText;

/**
 * 聊天/MCP 图谱工具的 store 面（graph.rs/ontology.rs 的工具专用读，M5 任务 4）：
 * 同名实体按问题排序的画像距离、事实行上的证据号原料（first_live_evidence）、
 * 边上的属性（fact_qualifiers_for）、谓词对齐的近邻关系类型、工具版 entity_facts
 * （predicate_key/other_type/qualifiers 全带——面板那份 EntityFact 刻意不带这些）。
 *
 * <p>UUID 批参以 {@code ?::uuid[]} 文本数组传（PG 驱动的 ANY(?) 需要 java.sql.Array，
 * JdbcTemplate 路上用文本字面量同效）。
 */
@Service
public class ChatGraphQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final GraphService graph;

    public ChatGraphQueries(JdbcTemplate jdbc, GraphService graph) {
        this.jdbc = jdbc;
        this.graph = graph;
    }

    /** 边上的属性（0037，graph.rs:169-191 fact_qualifiers_for）。 */
    public record FactQualifier(UUID qualifierTypeId, String key, String label, JsonNode value,
                                UUID entityId, String entityName) {
    }

    /**
     * 工具版的实体事实行：比面板的 EntityFact 多 predicate_key/other_type/qualifiers
     * ——过滤按谓词 key、对象类型，渲染带属性（模型读事实行最常问的就是「投了多少」）。
     */
    public record ToolEntityFact(UUID id, String saidAs, String direction, String predicateKey,
                                 String predicateLabel, boolean inferred, String temporal,
                                 UUID otherId, String otherName, String otherType,
                                 JsonNode objectValue, List<FactQualifier> qualifiers,
                                 java.time.OffsetDateTime validFrom, String validFromPrecision,
                                 java.time.OffsetDateTime validTo, String validToPrecision,
                                 java.time.OffsetDateTime holdsFrom,
                                 java.time.OffsetDateTime holdsTo, float confidence,
                                 boolean endDerived, boolean timeCorrected,
                                 String correctedEnds, UUID closedById, String closedBy,
                                 JsonNode closedByValue, String correctionNote) {
    }

    public record EntityToolsDetail(GraphNodeRow node, List<ToolEntityFact> facts) {
    }

    /**
     * graph.rs:1721-1840 entity_detail 的工具版：同一份 SQL（GraphService 持有，两轴谓词
     * 不分叉），映射多带 predicate_key/other_type；qualifiers 按 fact_qualifiers 另一张表
     * 一次取回补上。找不到实体回 null（调用方按 404/工具各自的 not_found 话术）。
     */
    public EntityToolsDetail entityFactsForTools(UUID kbId, UUID entityId,
                                                 java.time.OffsetDateTime at,
                                                 java.time.OffsetDateTime asOf) {
        GraphService.EntityDetail detail = graph.entityDetail(kbId, entityId, at, asOf);
        if (detail == null) {
            return null;
        }
        List<ToolEntityFact> facts = new ArrayList<>();
        Map<UUID, List<FactQualifier>> quals = factQualifiersFor(
                detail.facts().stream().map(GraphService.EntityFact::id).toList());
        for (GraphService.EntityFact f : detail.facts()) {
            facts.add(new ToolEntityFact(f.id(), f.saidAs(), f.direction(),
                    f.predicateKey(), f.predicateLabel(), f.inferred(), f.temporal(),
                    f.otherId(), f.otherName(), f.otherType(), f.objectValue(),
                    quals.getOrDefault(f.id(), List.of()),
                    f.validFrom(), f.validFromPrecision(), f.validTo(), f.validToPrecision(),
                    f.holdsFrom(), f.holdsTo(), f.confidence(), f.endDerived(),
                    f.timeCorrected(), f.correctedEnds(), f.closedById(), f.closedBy(),
                    f.closedByValue(), f.correctionNote()));
        }
        return new EntityToolsDetail(detail.node(), facts);
    }

    /** fact_qualifiers_for（graph.rs:169-191 逐字）。 */
    public Map<UUID, List<FactQualifier>> factQualifiersFor(List<UUID> factIds) {
        Map<UUID, List<FactQualifier>> out = new HashMap<>();
        if (factIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT q.fact_id, q.qualifier_type_id, r.key, r.label, q.value, q.entity_id,
                       e.canonical_name AS entity_name
                 FROM fact_qualifiers q
                 JOIN relation_types r ON r.id = q.qualifier_type_id
                 LEFT JOIN entities e ON e.id = q.entity_id
                 WHERE q.fact_id = ANY(?::uuid[])
                 ORDER BY q.fact_id, r.key
                """, rs -> {
            out.computeIfAbsent(rs.getObject("fact_id", UUID.class), k -> new ArrayList<>())
                    .add(new FactQualifier(rs.getObject("qualifier_type_id", UUID.class),
                            rs.getString("key"), rs.getString("label"),
                            readJson(rs.getString("value")),
                            rs.getObject("entity_id", UUID.class), rs.getString("entity_name")));
        }, uuidListLiteral(factIds));
        return out;
    }

    /**
     * 这批实体的上下文画像与一个向量的余弦距离（graph.rs:1547-1567）；没有画像的不在
     * 结果里。图谱工具拿用户的问题来比：同名的几个里，谁的画像离问题近，问的多半是谁。
     */
    public Map<UUID, Double> profileDistances(UUID kbId, List<UUID> ids, float[] embedding) {
        Map<UUID, Double> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String vector = VectorText.literal(embedding);
        jdbc.query("""
                SELECT e.id, (e.profile_embedding <=> ?::vector)::float8 AS distance
                  FROM entities e
                 WHERE e.kb_id = ? AND e.id = ANY(?::uuid[]) AND e.profile_embedding IS NOT NULL
                """, rs -> {
            out.put(rs.getObject("id", UUID.class), rs.getDouble("distance"));
        }, vector, kbId, uuidListLiteral(ids));
        return out;
    }

    /** 一次显示出来的事实各自的第一条有效证据（graph.rs:2106-2155 first_live_evidence）。 */
    public record LiveEvidence(ChunkView chunk, String quote) {
    }

    public Map<UUID, LiveEvidence> firstLiveEvidence(UUID kbId, List<UUID> factIds,
                                                     java.time.OffsetDateTime asOf) {
        Map<UUID, LiveEvidence> out = new LinkedHashMap<>();
        if (factIds.isEmpty()) {
            return out;
        }
        String chunkLive = asOf == null ? "c.superseded_at IS NULL"
                : "c.created_at <= '" + asOf + "' AND (c.superseded_at IS NULL"
                        + " OR c.superseded_at > '" + asOf + "')";
        String documentLive = asOf == null ? "d.deleted_at IS NULL"
                : "d.created_at <= '" + asOf + "' AND (d.deleted_at IS NULL"
                        + " OR d.deleted_at > '" + asOf + "')";
        jdbc.query("""
                SELECT DISTINCT ON (fe.fact_id) fe.fact_id, c.id, c.document_id, c.seq, c.text,
                       d.filename, NULLIF(btrim(fe.quote), '') AS quote
                  FROM fact_evidence fe
                  JOIN chunks c ON c.id = fe.chunk_id
                  JOIN documents d ON d.id = c.document_id
                 WHERE fe.fact_id = ANY(?::uuid[]) AND d.kb_id = ? AND %s AND %s
                 ORDER BY fe.fact_id, COALESCE(d.doc_time, d.created_at), d.id, c.seq, c.id
                """.formatted(chunkLive, documentLive), rs -> {
            out.put(rs.getObject("fact_id", UUID.class), new LiveEvidence(
                    new ChunkView(rs.getObject("id", UUID.class),
                            rs.getObject("document_id", UUID.class), rs.getInt("seq"),
                            rs.getString("text"), rs.getString("filename")),
                    rs.getString("quote")));
        }, uuidListLiteral(factIds), kbId);
        return out;
    }

    /** 谓词对齐的近邻关系类型（ontology.rs:1360-1386 nearest_relation_types：key+distance）。 */
    public record RelationCandidate(UUID id, String key, double distance) {
    }

    public List<RelationCandidate> nearestRelationTypes(UUID kbId, float[] embedding, int limit) {
        String vector = VectorText.literal(embedding);
        return jdbc.query("""
                SELECT id, key, (embedding <=> ?::vector)::float8 AS distance
                 FROM relation_types
                 WHERE kb_id = ? AND embedding IS NOT NULL
                 ORDER BY embedding <=> ?::vector
                 LIMIT ?
                """, (rs, i) -> new RelationCandidate(rs.getObject("id", UUID.class),
                        rs.getString("key"), rs.getDouble("distance")),
                vector, kbId, vector, limit);
    }

    private static JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    static String uuidListLiteral(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
