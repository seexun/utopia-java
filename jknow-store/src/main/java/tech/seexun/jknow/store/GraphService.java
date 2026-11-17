package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.EvidenceRow;
import tech.seexun.jknow.store.entity.GraphEdge;
import tech.seexun.jknow.store.entity.GraphNodeRow;
import tech.seexun.jknow.store.mapper.GraphMapper;

/**
 * graph 读路径装配（对齐旧仓库 graph.rs 的函数编排：overview 1288-1340 / edges_among
 * 1363-1481 / neighborhood 1484-1543 / search_entities 1575-1625 / fact_evidence 2163-2181）。
 * SQL 全在 {@link GraphMapper}（逐字），本类只做 Rust 侧同构的流程控制：
 * 节点→id 集→边三段 UNION→qualifiers 批量回填；BFS 铺开（MAX_NODES=300，hops 钳 [1,2]）；
 * 「现在/回放」按 {@code asOf != null} 分派到对应 mapper 方法对。
 */
@Service
public class GraphService {

    /** graph.rs:1492：邻域铺开的节点数天花板。 */
    private static final int MAX_NODES = 300;

    private final GraphMapper graph;
    private final JdbcTemplate jdbc;

    public GraphService(GraphMapper graph, JdbcTemplate jdbc) {
        this.graph = graph;
        this.jdbc = jdbc;
    }

    public record Overview(List<GraphNodeRow> nodes, List<GraphEdge> edges, long totalNodes, long totalEdges) {
    }

    public record Neighborhood(List<GraphNodeRow> nodes, List<GraphEdge> edges) {
    }

    public record Search(List<GraphNodeRow> entities, long total) {
    }

    /** graph.rs:908-934 GraphChange：变更清单的一行（事件在认知轴，区间在世界轴——正交）。 */
    public record GraphChangeRow(UUID factId, OffsetDateTime at, String kind, UUID subjectId,
                                 String subjectName, String predicateLabel, String objectName,
                                 JsonNode objectValue, OffsetDateTime validFrom,
                                 OffsetDateTime validTo, String validFromPrecision,
                                 String validToPrecision, float confidence, UUID documentId,
                                 String filename, String quote) {
    }

    /**
     * graph.rs:2421-2481 graph_changes：since–until 窗口内的断言/订正/推翻/并入事件
     * （各自按自己那根时间列开窗——一条 2 月写入、8 月被推翻的事实，在「3–4 月」窗口里
     * 两个事件都不该出现）。工具提示的「前一微秒」靠它读回旧事实（#351）。
     */
    public List<GraphChangeRow> graphChanges(UUID kbId, OffsetDateTime since, OffsetDateTime until,
                                             UUID entityId, List<String> kinds, long limit) {
        return graph.selectGraphChanges(kbId, since, until, entityId, kinds, limit);
    }

    /** graph.rs:1288-1340 overview：度数 topN + 其间的边 + 两个总数（与画布同口径）。 */
    public Overview overview(UUID kbId, long limit, OffsetDateTime at, OffsetDateTime asOf) {
        List<GraphNodeRow> nodes = asOf == null
                ? graph.selectOverviewNodes(kbId, limit)
                : graph.selectOverviewNodesAsOf(kbId, limit, asOf);
        List<UUID> ids = nodes.stream().map(GraphNodeRow::id).toList();
        List<GraphEdge> edges = edgesAmong(kbId, ids, at, asOf);
        long totalNodes = asOf == null
                ? graph.countOverviewNodes(kbId)
                : graph.countOverviewNodesAsOf(kbId, asOf);
        long totalEdges = asOf == null
                ? graph.countOverviewEdges(kbId)
                : graph.countOverviewEdgesAsOf(kbId, asOf);
        return new Overview(nodes, edges, totalNodes, totalEdges);
    }

    /** graph.rs:1363-1481 edges_among：id 集空直接空表；否则三段 UNION + qualifiers 回填。 */
    private List<GraphEdge> edgesAmong(UUID kbId, List<UUID> ids, OffsetDateTime at, OffsetDateTime asOf) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<GraphEdge> edges = asOf == null
                ? graph.selectEdgesAmong(kbId, ids, at)
                : graph.selectEdgesAmongAsOf(kbId, ids, at, asOf);
        // 边上的属性另一张表（0037），按边 id 一次取回补上（graph.rs:1470-1479）
        if (!edges.isEmpty()) {
            List<UUID> factIds = edges.stream().map(GraphEdge::id).toList();
            Map<UUID, List<GraphEdge.QualifierRow>> byFact = new HashMap<>();
            for (GraphEdge.QualifierRow q : graph.selectFactQualifiers(factIds)) {
                byFact.computeIfAbsent(q.factId(), k -> new ArrayList<>()).add(q);
            }
            List<GraphEdge> out = new ArrayList<>(edges.size());
            for (GraphEdge edge : edges) {
                List<GraphEdge.QualifierRow> q = byFact.get(edge.id());
                out.add(q == null ? edge : edge.withQualifiers(List.copyOf(q)));
            }
            return out;
        }
        return edges;
    }

    /** graph.rs:1484-1543 neighborhood：BFS 最多 2 跳、节点数封顶 300（hops 钳 [1,2]）。 */
    public Neighborhood neighborhood(UUID kbId, UUID entityId, int hops, OffsetDateTime at,
                                     OffsetDateTime asOf) {
        Set<UUID> seen = new HashSet<>();
        seen.add(entityId);
        List<UUID> frontier = List.of(entityId);
        int effectiveHops = Math.max(1, Math.min(2, hops));
        for (int i = 0; i < effectiveHops; i++) {
            if (frontier.isEmpty() || seen.size() >= MAX_NODES) {
                break;
            }
            // 铺开也走记录轴：邻居按「当时」的边找，否则回放的图上会长出只有今天才连得上的节点
            List<GraphMapper.TouchingRow> touching = asOf == null
                    ? graph.selectTouching(kbId, frontier)
                    : graph.selectTouchingAsOf(kbId, frontier, asOf);
            List<UUID> next = new ArrayList<>();
            for (GraphMapper.TouchingRow t : touching) {
                for (UUID id : new UUID[]{t.subjectId(), t.objectId()}) {
                    if (seen.size() >= MAX_NODES) {
                        break;
                    }
                    if (seen.add(id)) {
                        next.add(id);
                    }
                }
            }
            frontier = next;
        }
        List<UUID> ids = List.copyOf(seen);
        List<GraphNodeRow> nodes = asOf == null
                ? graph.selectNodesByIds(kbId, ids)
                : graph.selectNodesByIdsAsOf(kbId, ids, asOf);
        return new Neighborhood(nodes, edgesAmong(kbId, ids, at, asOf));
    }

    /**
     * graph.rs:1575-1625 search_entities：pattern=%trim(q)%（Unicode 空白裁边），
     * exact=normalize_name(q).lower()（规整后小写，SQL 侧再 lower() 比较）。
     * q 空串的「直接回空」在 controller（graph_routes.rs:144-146）。
     */
    public Search searchEntities(UUID kbId, String text, long limit, long offset, OffsetDateTime asOf) {
        String pattern = "%" + text.strip() + "%";
        List<String> exact = List.of(Names.normalizeName(text).toLowerCase(Locale.ROOT));
        List<GraphNodeRow> nodes = asOf == null
                ? graph.selectSearchNodes(kbId, pattern, limit, offset, exact)
                : graph.selectSearchNodesAsOf(kbId, pattern, limit, offset, exact, asOf);
        long total = asOf == null
                ? graph.countSearch(kbId, pattern)
                : graph.countSearchAsOf(kbId, pattern, asOf);
        return new Search(nodes, total);
    }

    /** graph.rs:2163-2181 fact_evidence（graph_routes.rs:451-459 的 /kbs/{id}/facts/{fid}/evidence）。 */
    public List<EvidenceRow> factEvidence(UUID factId) {
        return graph.selectFactEvidence(factId);
    }

    /**
     * graph.rs:2232-2423 {@code entity_history}：一个实体身上的认识轴事件（M3 任务 4 起
     * 合并/撤销也在其中，#337）——事实三态、改类两态、合并三向，总数把后两支也算上。
     */
    public tech.seexun.jknow.store.entity.EntityHistoryEvent.Page entityHistory(UUID kbId,
                                                                                UUID entityId,
                                                                                long limit,
                                                                                long offset) {
        return new tech.seexun.jknow.store.entity.EntityHistoryEvent.Page(
                graph.selectEntityHistory(kbId, entityId, limit, offset),
                graph.countEntityHistory(kbId, entityId));
    }
    // ===================== entity_detail / same_name_peers（M3 任务 6） =====================

    /** entity_detail 的产出一对：节点（含度数）与事实行（graph.rs EntityFact 全列）。 */
    public record EntityDetail(GraphNodeRow node, List<EntityFact> facts) {
    }

    /**
     * 面板的一条事实（graph.rs EntityFact / models.rs serde 形状的核心列）。键名与
     * SQL 别名一一对应；contested 是它坐的地方的争议（violation/conflict 的 jsonb）。
     * M5 任务 4 补 predicateKey/otherType 两列（聊天工具面的过滤与渲染用；面板出口
     * 不序列化本行，字段增补不触契约）。
     */
    public record EntityFact(UUID id, String saidAs, String direction, String predicateKey,
                             String predicateLabel,
                             boolean inferred, String temporal, UUID otherId, String otherName,
                             String otherType, JsonNode objectValue, OffsetDateTime validFrom,
                             String validFromPrecision, OffsetDateTime validTo,
                             String validToPrecision, OffsetDateTime holdsFrom,
                             OffsetDateTime holdsTo, float confidence, long evidenceCount,
                             boolean stale, boolean corrected, boolean endDerived,
                             String correctedEnds, boolean timeCorrected, UUID closedById,
                             String closedBy, JsonNode closedByValue, String correctionNote,
                             JsonNode contested) {
    }

    /**
     * 实体面板读（graph.rs:1721-1830 entity_detail）。SQL 按 Rust format! 同款在文本上
     * 拼接两轴谓词（as_of None 退化单支保部分索引、Some 双支带时刻；world 轴 facts_hold_at
     * 的 at 为 NULL 即任何时刻）——不经占位符，与 DetailStubs 测试桩同一套展开（任务 6
     * 把桩的真身落在这里）。节点查不到回 null（调用方 404）。
     */
    public EntityDetail entityDetail(UUID kbId, UUID entityId, OffsetDateTime at,
                                     OffsetDateTime asOf) {
        GraphNodeRow node = asOf == null
                ? graph.selectEntityNode(kbId, entityId)
                : graph.selectEntityNodeAsOf(kbId, entityId, asOf);
        if (node == null) {
            return null;
        }
        List<EntityFact> facts = jdbc.query(entityFactsSql(at, asOf), (rs, i) -> new EntityFact(
                rs.getObject("id", UUID.class),
                rs.getString("said_as"),
                rs.getString("direction"),
                rs.getString("predicate_key"),
                rs.getString("predicate_label"),
                rs.getBoolean("inferred"),
                rs.getString("temporal"),
                rs.getObject("other_id", UUID.class),
                rs.getString("other_name"),
                rs.getString("other_type"),
                readJson(rs.getString("object_value")),
                rs.getObject("valid_from", OffsetDateTime.class),
                rs.getString("valid_from_precision"),
                rs.getObject("valid_to", OffsetDateTime.class),
                rs.getString("valid_to_precision"),
                rs.getObject("holds_from", OffsetDateTime.class),
                rs.getObject("holds_to", OffsetDateTime.class),
                rs.getFloat("confidence"),
                rs.getLong("evidence_count"),
                rs.getBoolean("stale"),
                rs.getBoolean("corrected"),
                rs.getBoolean("end_derived"),
                rs.getString("corrected_ends"),
                rs.getBoolean("time_corrected"),
                rs.getObject("closed_by_id", UUID.class),
                rs.getString("closed_by"),
                readJson(rs.getString("closed_by_value")),
                rs.getString("correction_note"),
                readJson(rs.getString("contested"))),
                kbId, entityId, entityId, entityId,
                kbId, entityId, entityId);
        return new EntityDetail(node, facts);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper DETAIL_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** jsonb 经字符串读再解（PG 驱动不认 jsonb→JsonNode 的自动转换；null 透传）。 */
    private static JsonNode readJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DETAIL_JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** entity_detail 事实读的 SQL 文本（graph.rs:1731-1811；两轴谓词与 Rust 同拼接）。 */
    private static String entityFactsSql(OffsetDateTime at, OffsetDateTime asOf) {
        String ts = asOf == null ? null : "'" + asOf + "'";
        // record_axis owner_at：None=裸列；Some=fact_owner_at(...)
        String fSubject = asOf == null ? "f.subject_id"
                : "fact_owner_at(f.id, f.subject_id, " + ts + ", false)";
        String fObject = asOf == null ? "f.object_id"
                : "fact_owner_at(f.id, f.object_id, " + ts + ", true)";
        String nSubject = asOf == null ? "n.subject_id"
                : "fact_owner_at(n.id, n.subject_id, " + ts + ", false)";
        String nObject = asOf == null ? "n.object_id"
                : "fact_owner_at(n.id, n.object_id, " + ts + ", true)";
        String fHeld = asOf == null ? "f.invalidated_at IS NULL"
                : "f.recorded_at <= coalesce(" + ts + ", now()) AND (f.invalidated_at IS NULL"
                        + " OR f.invalidated_at > coalesce(" + ts + ", now()))";
        String nHeld = asOf == null ? "n.invalidated_at IS NULL"
                : "n.recorded_at <= coalesce(" + ts + ", now()) AND (n.invalidated_at IS NULL"
                        + " OR n.invalidated_at > coalesce(" + ts + ", now()))";
        // world_axis facts_hold_at("f", $3)：at 为 NULL 即任何时刻
        String atLit = at == null ? "NULL" : "'" + at + "'";
        String temporal = "(SELECT r2.temporal FROM relation_types r2 WHERE r2.id = f.predicate_id)";
        String holdsFrom = "CASE WHEN " + temporal + " = 'eternal' THEN NULL"
                + " ELSE COALESCE(f.valid_from, f.attested_from) END";
        String holdsTo = "CASE WHEN " + temporal + " = 'eternal' THEN NULL"
                + " WHEN " + temporal + " = 'event' THEN"
                + " CASE WHEN f.valid_from IS NULL THEN f.attested_from"
                + " ELSE COALESCE(f.valid_to, f.valid_from)"
                + " + ('1 ' || COALESCE(NULLIF(f.valid_to_precision, 'unknown'), f.valid_from_precision))::interval END"
                + " WHEN f.valid_to IS NOT NULL THEN f.valid_to"
                + " WHEN f.valid_to_precision = 'unknown' THEN f.attested_to END";
        String factsHold = "(" + atLit + "::timestamptz IS NULL OR (NOT (" + temporal
                + " IS NOT DISTINCT FROM 'event' AND f.valid_from IS NULL AND f.attested_from IS NULL)"
                + " AND ((" + holdsFrom + " IS NULL OR " + holdsFrom + " <= " + atLit + "::timestamptz)"
                + " AND (" + holdsTo + " IS NULL OR " + holdsTo + " > " + atLit + "::timestamptz))))";
        String correctedEnds = "(CASE WHEN f.end_derived AND f.corrected_ends = 'end' THEN NULL"
                + " WHEN f.end_derived AND f.corrected_ends = 'both' THEN 'start'"
                + " ELSE f.corrected_ends END)";
        String correctionNote = "(WITH RECURSIVE chain(id, depth) AS ("
                + "SELECT f.supersedes, 1 WHERE f.supersedes IS NOT NULL"
                + " UNION ALL"
                + " SELECT p.supersedes, chain.depth + 1 FROM facts p JOIN chain ON p.id = chain.id"
                + " WHERE p.supersedes IS NOT NULL)"
                + " SELECT t.note FROM chain JOIN time_corrected_targets t ON t.target_id = chain.id"
                + " ORDER BY chain.depth LIMIT 1)";
        // temporal.rs:38-45 DESCRIBED（别名 n）：看图描述出来的后任不拿来关
        String describedN = "(EXISTS (SELECT 1 FROM fact_evidence fe"
                + " JOIN chunks ch ON ch.id = fe.chunk_id"
                + " WHERE fe.fact_id = n.id AND ch.origin = 'described')"
                + " OR EXISTS (SELECT 1 FROM typed_fact_sources ts"
                + " JOIN fact_evidence fe ON fe.fact_id = ts.statement_id"
                + " JOIN chunks ch ON ch.id = fe.chunk_id"
                + " WHERE ts.fact_id = n.id AND ch.origin = 'described'))";
        // graph.rs:1849-1887 closed_by_join（None/Some 形态的 owner/held 就位）
        String closer = "LEFT JOIN LATERAL ("
                + " SELECT n.id AS closed_by_id,"
                + " CASE WHEN rn.inverse_functional AND " + nObject + " = " + fObject
                + " THEN n_s.canonical_name ELSE n_o.canonical_name END AS closed_by,"
                + " CASE WHEN rn.inverse_functional AND " + nObject + " = " + fObject
                + " THEN NULL ELSE n.object_value END AS closed_by_value"
                + " FROM facts n"
                + " JOIN relation_types rn ON rn.id = n.predicate_id"
                + " LEFT JOIN entities n_s ON n_s.id = " + nSubject
                + " LEFT JOIN entities n_o ON n_o.id = " + nObject
                + " WHERE f.end_derived AND f.valid_to IS NOT NULL"
                + " AND n.kb_id = f.kb_id AND n.predicate_id = f.predicate_id AND n.id <> f.id"
                + " AND " + nHeld
                + " AND n.valid_from = f.valid_to"
                + " AND n.valid_from_grade IS DISTINCT FROM 'C'"
                + " AND NOT " + describedN
                + " AND ((rn.functional AND " + nSubject + " = " + fSubject
                + " AND (" + nObject + " IS DISTINCT FROM " + fObject
                + " OR n.object_value IS DISTINCT FROM f.object_value))"
                + " OR (rn.inverse_functional AND " + nObject + " = " + fObject
                + " AND " + nSubject + " <> " + fSubject + "))"
                + " ORDER BY n.id LIMIT 1) closer ON true";
        // record_axis violation/conflict open 谓词（contested 一档）
        String violationOpen = asOf == null ? "v.status = 'open'"
                : "v.detected_at <= coalesce(" + ts + ", now()) AND (v.status = 'open'"
                        + " OR v.decided_at > coalesce(" + ts + ", now()))";
        String conflictOpen = asOf == null ? "c.status = 'open'"
                : "c.created_at <= coalesce(" + ts + ", now()) AND (c.status = 'open'"
                        + " OR c.resolved_at > coalesce(" + ts + ", now()))";
        return """
                WITH time_corrected_targets AS MATERIALIZED (
                 SELECT DISTINCT ON (target_id) target_id, detail->>'note' AS note FROM audit_events
                  WHERE kb_id = ? AND action = 'fact.time_corrected' AND target_id IS NOT NULL
                  ORDER BY target_id, created_at DESC)
                SELECT f.id,
                        (SELECT string_agg(DISTINCT st.phrase, ' · ')
                           FROM typed_fact_sources src JOIN facts st ON st.id = src.statement_id
                          WHERE src.fact_id = f.id) AS said_as,
                        CASE WHEN %s = ? THEN 'out' ELSE 'in' END AS direction,
                        COALESCE(r.key, fact_surface_predicate(f.id)) AS predicate_key,
                        COALESCE(r.label, fact_surface_predicate(f.id)) AS predicate_label,
                        r.id IS NULL AS inferred, r.temporal,
                        CASE WHEN %s = ? THEN %s ELSE %s END AS other_id,
                        o.canonical_name AS other_name, ot.label AS other_type, f.object_value,
                        f.valid_from, f.valid_from_precision, f.valid_to, f.valid_to_precision,
                        %s AS holds_from, %s AS holds_to, f.confidence,
                        (SELECT count(*) FROM fact_evidence fe WHERE fe.fact_id = f.id)
                          AS evidence_count,
                        (EXISTS (SELECT 1 FROM fact_evidence fe WHERE fe.fact_id = f.id)
                         AND NOT EXISTS (SELECT 1 FROM fact_evidence fe
                                         JOIN chunks c ON c.id = fe.chunk_id
                                         WHERE fe.fact_id = f.id AND %s)) AS stale,
                        (f.supersedes IS NOT NULL) AS corrected,
                        f.end_derived,
                        %s AS corrected_ends,
                        %s IS NOT NULL AS time_corrected,
                        closer.closed_by_id, closer.closed_by, closer.closed_by_value,
                        CASE WHEN %s IS NOT NULL THEN %s END AS correction_note,
                        COALESCE(
                            (SELECT jsonb_build_object(
                                        'kind', v.kind, 'ref_id', v.id,
                                        'derived', CASE WHEN v.kind = 'derived_contradiction'
                                            THEN (v.detail->>'subject') || ' · '
                                                 || (v.detail->>'predicate') || ' · '
                                                 || (v.detail->>'object') END)
                               FROM axiom_violations v
                              WHERE %s
                                AND (v.left_fact = f.id
                                     OR (v.right_fact = f.id AND v.kind <> 'derived_contradiction'))
                              ORDER BY v.detected_at DESC LIMIT 1),
                            (SELECT jsonb_build_object('kind', 'temporal_conflict', 'ref_id', c.id)
                               FROM fact_conflicts c
                              WHERE %s
                                AND (c.old_fact_id = f.id OR c.new_fact_id = f.id)
                              ORDER BY c.created_at DESC LIMIT 1)
                        ) AS contested
                 FROM facts f
                 LEFT JOIN relation_types r ON r.id = f.predicate_id
                 LEFT JOIN entities o
                   ON o.id = CASE WHEN %s = ? THEN %s ELSE %s END
                 LEFT JOIN entity_types ot ON ot.id = o.type_id
                 %s
                 WHERE f.kb_id = ? AND %s
                   AND %s
                   AND NOT EXISTS (SELECT 1 FROM typed_fact_sources src
                                   JOIN facts ty ON ty.id = src.fact_id
                                  WHERE src.statement_id = f.id AND ty.invalidated_at IS NULL)
                   AND (%s = ? OR %s = ?)
                   AND NOT EXISTS (SELECT 1 FROM relation_types nr
                                   WHERE nr.id = f.predicate_id AND nr.builtin AND nr.key = 'known_as')
                 ORDER BY f.valid_from NULLS LAST, f.recorded_at
                """.formatted(
                fSubject,
                fSubject, fObject, fSubject,
                holdsFrom, holdsTo,
                // record_axis chunk_live_at（stale 一档）：None=单支；Some=双支带时刻
                asOf == null ? "c.superseded_at IS NULL"
                        : "c.created_at <= coalesce(" + ts + ", now())"
                                + " AND (c.superseded_at IS NULL"
                                + " OR c.superseded_at > coalesce(" + ts + ", now()))",
                correctedEnds,
                correctedEnds,
                correctedEnds, correctionNote,
                violationOpen,
                conflictOpen,
                fSubject, fObject, fSubject,
                closer,
                fHeld,
                factsHold,
                fSubject, fObject);
    }

    /**
     * 同名的那些（graph.rs:1945-1961 same_name_peers）：打开面板时就给——两个张伟并存
     * 是「宁分勿合」的正当产物，合并入口该长在能看见同名的地方。
     */
    /** graph.rs:1844-1852 update_entity 的单实体回读（merged 缺席=null → NotFound 由调用方报）。 */
    public GraphNodeRow entityNode(UUID kbId, UUID entityId) {
        return graph.selectNodeById(kbId, entityId);
    }

    public List<GraphNodeRow> sameNamePeers(UUID kbId, UUID entityId, OffsetDateTime asOf) {
        return asOf == null
                ? graph.selectSameNamePeers(kbId, entityId)
                : graph.selectSameNamePeersAsOf(kbId, entityId, asOf);
    }
}
