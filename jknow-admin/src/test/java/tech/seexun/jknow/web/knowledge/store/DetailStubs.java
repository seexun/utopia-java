package tech.seexun.jknow.web.knowledge.store;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * entity_detail 事实行断言面的<b>测试桩</b>（M3 任务 3）——SQL 逐字取自 graph.rs:1721-1830
 * （TIME_CORRECTED_TARGETS + corrected_ends_sql + correction_note_sql + closed_by_join
 * 三片段就位）。M3 任务 6 起核心读（度数/事实 id）与 as_of 核心已由真身
 * {@code GraphService.entityDetail} 承担（a_name_is_a_fact / unknown_date / second_clock
 * / contradiction 等已换真调用）；本桩余下的 entityDetail 全列断言面
 * （a_fact_says_where_its_dates_came_from 等 @Disabled 测试）随 entity_detail
 * HTTP 端点族（M4）一并换真。
 *
 * <p>M3 任务 4 起，MentionStubs 删除，其上属于后续里程碑断言面的两个读点留在本桩：
 * governance::quotes_of 已随 M4 任务 1 的 GovernanceService.quotesOf 退役；
 * ontology::relations_needing_embedding 待 M4 落真端点时换真。
 *
 * <p>as_of/at 是测试受控的时间戳，按 GraphMapper 头注的口径在 SQL 文本上切换两形态
 * （record_axis.rs None 退化单支、Some 走 fact_owner_at/双支谓词；world_axis facts_hold_at
 * 的 $4 为 NULL 即「任何时刻」）——与 Rust format! 生成同一形状，不经占位符。
 */
final class DetailStubs {

    private DetailStubs() {
    }

    /** ontology.rs:1194-1232 relations_needing_embedding（SQL 逐字 + is_stale 的 Rust 侧比对）。 */
    static Set<UUID> relationsNeedingEmbedding(JdbcTemplate jdbc, UUID kb, String model) {
        Set<UUID> stale = new LinkedHashSet<>();
        jdbc.query("""
                SELECT id, label, coalesce(description, '') AS description,
                        embedding IS NOT NULL AS embedded, embedded_model, embedded_text
                   FROM relation_types
                  WHERE kb_id = ? AND NOT (builtin AND key = 'known_as')
                """, rs -> {
            // embed_text（ontology.rs:1107-1116）：标签在前、描述在后；key 不进去
            String d = rs.getString("description");
            d = d == null ? "" : d.trim();
            String label = rs.getString("label").trim();
            String text = d.isEmpty() ? label : label + "\n" + d;
            boolean embedded = rs.getBoolean("embedded");
            String storedModel = rs.getString("embedded_model");
            String storedText = rs.getString("embedded_text");
            // is_stale（ontology.rs:1252-1269）：没嵌过、嵌的原文与模型名对不上
            if (!embedded || !model.equals(storedModel) || !text.equals(storedText)) {
                stale.add(rs.getObject("id", UUID.class));
            }
        }, kb);
        return stale;
    }

    /** entity_detail 事实行的断言面列（EntityFact 子集）。 */
    record DetailFact(UUID id,
                      String predicateLabel,
                      boolean inferred,
                      String temporal,
                      UUID otherId,
                      String otherName,
                      OffsetDateTime validTo,
                      boolean endDerived,
                      boolean timeCorrected,
                      boolean corrected,
                      String correctedEnds,
                      UUID closedById,
                      String closedBy,
                      String correctionNote,
                      OffsetDateTime holdsFrom,
                      OffsetDateTime holdsTo) {
    }

    static List<DetailFact> entityDetail(JdbcTemplate jdbc, UUID kbId, UUID entityId,
                                         OffsetDateTime at, OffsetDateTime asOf) {
        return jdbc.query(sql(at, asOf), (rs, i) -> new DetailFact(
                rs.getObject("id", UUID.class),
                rs.getString("predicate_label"),
                rs.getBoolean("inferred"),
                rs.getString("temporal"),
                rs.getObject("other_id", UUID.class),
                rs.getString("other_name"),
                rs.getObject("valid_to", OffsetDateTime.class),
                rs.getBoolean("end_derived"),
                rs.getBoolean("time_corrected"),
                rs.getBoolean("corrected"),
                rs.getString("corrected_ends"),
                rs.getObject("closed_by_id", UUID.class),
                rs.getString("closed_by"),
                rs.getString("correction_note"),
                rs.getObject("holds_from", OffsetDateTime.class),
                rs.getObject("holds_to", OffsetDateTime.class)),
                kbId, entityId, entityId, kbId, entityId, entityId);
    }

    private static String sql(OffsetDateTime at, OffsetDateTime asOf) {
        String ts = asOf == null ? null : "'" + asOf + "'";
        // record_axis owner_at（graph.rs 头注展开）：None=裸列；Some=fact_owner_at(...)
        String fSubject = asOf == null ? "f.subject_id"
                : "fact_owner_at(f.id, f.subject_id, " + ts + ", false)";
        String fObject = asOf == null ? "f.object_id"
                : "fact_owner_at(f.id, f.object_id, " + ts + ", true)";
        String nSubject = asOf == null ? "n.subject_id"
                : "fact_owner_at(n.id, n.subject_id, " + ts + ", false)";
        String nObject = asOf == null ? "n.object_id"
                : "fact_owner_at(n.id, n.object_id, " + ts + ", true)";
        // record_axis facts_held_at：None=invalidated_at IS NULL（保部分索引）；Some=双支
        String fHeld = asOf == null ? "f.invalidated_at IS NULL"
                : "f.recorded_at <= coalesce(" + ts + ", now()) AND (f.invalidated_at IS NULL"
                        + " OR f.invalidated_at > coalesce(" + ts + ", now()))";
        String nHeld = asOf == null ? "n.invalidated_at IS NULL"
                : "n.recorded_at <= coalesce(" + ts + ", now()) AND (n.invalidated_at IS NULL"
                        + " OR n.invalidated_at > coalesce(" + ts + ", now()))";
        // world_axis facts_hold_at("f", 4)：$4 为 NULL（at=null）即任何时刻都成立
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
                + " AND ((" + holdsFrom + " IS NULL OR " + holdsFrom + " <= " + atLit
                + "::timestamptz)"
                + " AND (" + holdsTo + " IS NULL OR " + holdsTo + " > " + atLit + "::timestamptz))))";
        // graph.rs:1654-1665 corrected_ends_sql（终点是引擎推的就不再说终点是人改的）
        String correctedEnds = "(CASE WHEN f.end_derived AND f.corrected_ends = 'end' THEN NULL"
                + " WHEN f.end_derived AND f.corrected_ends = 'both' THEN 'start'"
                + " ELSE f.corrected_ends END)";
        // graph.rs:1668-1683 correction_note_sql：链上最近那一次修正的备注
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
                + " THEN n_s.canonical_name ELSE n_o.canonical_name END AS closed_by"
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
        return """
                WITH time_corrected_targets AS MATERIALIZED (
                 SELECT DISTINCT ON (target_id) target_id, detail->>'note' AS note FROM audit_events
                  WHERE kb_id = ? AND action = 'fact.time_corrected' AND target_id IS NOT NULL
                  ORDER BY target_id, created_at DESC)
                SELECT f.id,
                        COALESCE(r.label, fact_surface_predicate(f.id)) AS predicate_label,
                        r.id IS NULL AS inferred, r.temporal,
                        CASE WHEN %s = ? THEN %s ELSE %s END AS other_id,
                        o.canonical_name AS other_name, f.valid_to,
                        (f.supersedes IS NOT NULL) AS corrected,
                        f.end_derived,
                        %s AS corrected_ends,
                        %s IS NOT NULL AS time_corrected,
                        CASE WHEN %s IS NOT NULL THEN %s END AS correction_note,
                        closer.closed_by_id, closer.closed_by,
                        %s AS holds_from, %s AS holds_to
                 FROM facts f
                 LEFT JOIN relation_types r ON r.id = f.predicate_id
                 LEFT JOIN entities o ON o.id = CASE WHEN %s = ? THEN %s ELSE %s END
                 %s
                 WHERE f.kb_id = ? AND %s
                   AND %s
                   AND NOT EXISTS (SELECT 1 FROM relation_types nr
                                    WHERE nr.id = f.predicate_id AND nr.builtin AND nr.key = 'known_as')
                   AND (%s = ? OR %s = ?)
                 ORDER BY f.valid_from NULLS LAST, f.recorded_at
                """.formatted(
                fSubject, fObject, fSubject,
                correctedEnds,
                correctedEnds,
                correctedEnds, correctionNote,
                holdsFrom, holdsTo,
                fSubject, fObject, fSubject,
                closer,
                fHeld,
                factsHold,
                fSubject, fObject);
    }
}
