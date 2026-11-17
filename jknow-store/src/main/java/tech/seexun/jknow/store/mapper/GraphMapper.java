package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.EvidenceRow;
import tech.seexun.jknow.store.entity.GraphEdge;
import tech.seexun.jknow.store.entity.GraphNodeRow;

/**
 * graph 读路径 mapper（逐字搬迁自旧仓库 utopia-0.1.0-rc9/crates/utopia-store/src/graph.rs；
 * 每条 SQL 的 file:line 与「现在/回放（as_of）」两形态的展开规则见 GraphMapper.xml 注释）。
 *
 * <p>Rust 侧按 {@code as_of} 是否传值在<b>SQL 文本上</b>切换谓词形态（record_axis.rs：None
 * 退化为单支谓词保部分索引，Some 保留双支+时刻参数）——Java 侧以成对方法对等
 * （xxx / xxxAsOf），service 按 {@code asOf != null} 分派，SQL 文本各自整段逐字。
 */
public interface GraphMapper {

    // ---- overview（graph.rs:1288-1340）----

    /** 节点 topN（graph.rs:1295-1299，node_sql「现在」形态）。 */
    List<GraphNodeRow> selectOverviewNodes(@Param("kbId") UUID kbId, @Param("limit") long limit);

    /** 节点 topN 回放形态（as_of=$3：主宾 fact_owner_at + facts 双支 + entity created_at 钳）。 */
    List<GraphNodeRow> selectOverviewNodesAsOf(@Param("kbId") UUID kbId, @Param("limit") long limit,
                                               @Param("asOf") OffsetDateTime asOf);

    /** 实体总数（graph.rs:1316-1319，与画布同一口径：合并掉的实体不算）。 */
    long countOverviewNodes(@Param("kbId") UUID kbId);

    /** 实体总数回放形态（as_of=$2）。 */
    long countOverviewNodesAsOf(@Param("kbId") UUID kbId, @Param("asOf") OffsetDateTime asOf);

    /** 边总数（graph.rs:1324-1334：facts + derived_facts 两子查询和，object_id 非空才算）。 */
    long countOverviewEdges(@Param("kbId") UUID kbId);

    /** 边总数回放形态（as_of=$2）。 */
    long countOverviewEdgesAsOf(@Param("kbId") UUID kbId, @Param("asOf") OffsetDateTime asOf);

    // ---- 边三段 UNION（graph.rs:1363-1481 edges_among）+ qualifiers 回填 ----

    /** facts + derived_facts + 幽灵边三段 UNION（「现在」形态；$3=at 世界轴，可空=不过滤）。 */
    List<GraphEdge> selectEdgesAmong(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                     @Param("at") OffsetDateTime at);

    /** 同上回放形态（$4=as_of：facts/derived/violation/conflict 记录轴双支 + 主宾 fact_owner_at）。 */
    List<GraphEdge> selectEdgesAmongAsOf(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                         @Param("at") OffsetDateTime at, @Param("asOf") OffsetDateTime asOf);

    /** 边上的属性另一张表（graph.rs:177-185 fact_qualifiers_for，按边 id 批量回填）。 */
    List<GraphEdge.QualifierRow> selectFactQualifiers(@Param("factIds") List<UUID> factIds);

    // ---- entities 搜索（graph.rs:1575-1625 search_entities）----

    /** 按名字找实体（精确同名优先，再按度数；$5=exact 同名集）。 */
    /** graph.rs:1889-1893 update_entity 的回读（node_sql(None,None) + WHERE e.id）。 */
    GraphNodeRow selectNodeById(@Param("kbId") UUID kbId, @Param("id") UUID id);

    List<GraphNodeRow> selectSearchNodes(@Param("kbId") UUID kbId, @Param("pattern") String pattern,
                                         @Param("limit") long limit, @Param("offset") long offset,
                                         @Param("exact") List<String> exact);

    /** 同上回放形态（$6=as_of）。 */
    List<GraphNodeRow> selectSearchNodesAsOf(@Param("kbId") UUID kbId, @Param("pattern") String pattern,
                                             @Param("limit") long limit, @Param("offset") long offset,
                                             @Param("exact") List<String> exact,
                                             @Param("asOf") OffsetDateTime asOf);

    /** 搜索总数（同一召回谓词，graph.rs:1611-1616）。 */
    long countSearch(@Param("kbId") UUID kbId, @Param("pattern") String pattern);

    /** 搜索总数回放形态（$3=as_of）。 */
    long countSearchAsOf(@Param("kbId") UUID kbId, @Param("pattern") String pattern,
                         @Param("asOf") OffsetDateTime asOf);

    // ---- neighborhood（graph.rs:1484-1543）----

    /** BFS 铺开：frontier 集触到的边两端（graph.rs:1502-1509，按记录轴找邻居）。 */
    List<TouchingRow> selectTouching(@Param("kbId") UUID kbId, @Param("frontier") List<UUID> frontier);

    /** 同上回放形态（$3=as_of）。 */
    List<TouchingRow> selectTouchingAsOf(@Param("kbId") UUID kbId, @Param("frontier") List<UUID> frontier,
                                         @Param("asOf") OffsetDateTime asOf);

    /** 按 id 集取节点（graph.rs:1531-1535，node_sql + e.id = ANY($2) + visible）。 */
    List<GraphNodeRow> selectNodesByIds(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids);

    /** 同上回放形态（$3=as_of）。 */
    List<GraphNodeRow> selectNodesByIdsAsOf(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                            @Param("asOf") OffsetDateTime asOf);

    // ---- fact 证据（graph.rs:2163-2181 fact_evidence，graph_routes.rs:451-459 端点）----

    /** 一条事实的证据行（chunk 连文档；unknown fact → 空表）。 */
    List<EvidenceRow> selectFactEvidence(@Param("factId") UUID factId);

    // ---- entity_history（graph.rs:2232-2423，M3 任务 4：合并/撤销也进认识轴 #337）----

    /** 事件页（三支 UNION：事实/改类/合并，按 at DESC 稳定排序）。 */
    /**
     * graph.rs:2421-2481 graph_changes：一段时间里我们改了什么主意（认知轴窗口——
     * recorded_at / invalidated_at，与问「某时刻世界什么样」的 valid_from/to 正交）。
     * MCP 工具（api/tools.rs:774）消费；Java 侧随 the_second_clock 移植落 store 层
     * （M3 任务 11），HTTP/MCP 暴露属 M5。
     */
    List<tech.seexun.jknow.store.GraphService.GraphChangeRow> selectGraphChanges(
            @Param("kbId") UUID kbId, @Param("since") OffsetDateTime since,
            @Param("until") OffsetDateTime until, @Param("entityId") UUID entityId,
            @Param("kinds") List<String> kinds, @Param("limit") long limit);

    List<tech.seexun.jknow.store.entity.EntityHistoryEvent> selectEntityHistory(
            @Param("kbId") UUID kbId, @Param("entityId") UUID entityId,
            @Param("limit") long limit, @Param("offset") long offset);

    /** 总数（把改类、合并那两支也算上，否则分页会少一截）。 */
    long countEntityHistory(@Param("kbId") UUID kbId, @Param("entityId") UUID entityId);

    /** BFS 铺开行（subject/object 两端；WHERE object_id IS NOT NULL 保证 object 非空）。 */
    record TouchingRow(UUID subjectId, UUID objectId) {
    }

    // ===================== entity_detail / same_name_peers（M3 任务 6） =====================

    /** graph.rs:1629-1643 entity_node：按 id 取一个节点（entity_detail 的 404 判据）。 */
    GraphNodeRow selectEntityNode(@Param("kbId") UUID kbId, @Param("entityId") UUID entityId);

    /** 同上回放形态（as_of=$3）。 */
    GraphNodeRow selectEntityNodeAsOf(@Param("kbId") UUID kbId, @Param("entityId") UUID entityId,
                                      @Param("asOf") OffsetDateTime asOf);

    /** graph.rs:1945-1961 same_name_peers（「现在」形态）：同名实体 top10 按度数。 */
    List<GraphNodeRow> selectSameNamePeers(@Param("kbId") UUID kbId,
                                           @Param("entityId") UUID entityId);

    /** 同上回放形态（$3=as_of）。 */
    List<GraphNodeRow> selectSameNamePeersAsOf(@Param("kbId") UUID kbId,
                                               @Param("entityId") UUID entityId,
                                               @Param("asOf") OffsetDateTime asOf);

    // ===================== paths（#558，M3 任务 6：paths.rs） =====================

    /** paths.rs touching 的行：事实 id + 折算过归属的两端。 */
    record PathTouchRow(UUID factId, UUID subjectId, UUID objectId) {
    }

    /** paths.rs:378-397 touching：碰到这批实体的边（两方向、自环不要、两轴都过）。 */
    List<PathTouchRow> selectPathTouching(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                          @Param("at") OffsetDateTime at);

    List<PathTouchRow> selectPathTouchingAsOf(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                              @Param("at") OffsetDateTime at,
                                              @Param("asOf") OffsetDateTime asOf);

    /** paths.rs:403-419 degrees：这批实体各自的度数（同一套时间轴过滤下）。 */
    List<DegreeRow> selectPathDegrees(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                      @Param("at") OffsetDateTime at);

    List<DegreeRow> selectPathDegreesAsOf(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                          @Param("at") OffsetDateTime at,
                                          @Param("asOf") OffsetDateTime asOf);

    /** (实体, 度数) 行。 */
    record DegreeRow(UUID id, long degree) {
    }

    /** paths.rs:425-452 details：这批事实的两端名字、谓词与区间（含 closer 修正注记）。 */
    List<PathEdgeRow> selectPathDetails(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids);

    List<PathEdgeRow> selectPathDetailsAsOf(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids,
                                            @Param("asOf") OffsetDateTime asOf);

    /** 路径上的一条边（paths.rs:88-110 PathEdge，方向由调用方按走向判断）。 */
    record PathEdgeRow(UUID factId, UUID subjectId, String subjectName, UUID objectId,
                       String objectName, String predicate, OffsetDateTime validFrom,
                       String validFromPrecision, OffsetDateTime validTo, String validToPrecision,
                       OffsetDateTime holdsFrom, OffsetDateTime holdsTo, float confidence,
                       boolean endDerived, boolean timeCorrected, String correctedEnds,
                       UUID closedById, String closedBy,
                       com.fasterxml.jackson.databind.JsonNode closedByValue,
                       String correctionNote) {
    }
}
