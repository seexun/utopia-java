package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.ConceptMapping;
import tech.seexun.jknow.store.entity.ExplorationRun;
import tech.seexun.jknow.store.entity.MappingRevision;
import tech.seexun.jknow.store.entity.MappingText;

/**
 * 语义层口径读 mapper（逐字搬迁自旧仓库 utopia-store/src/mappings.rs、
 * exploration_runs.rs；每条 SQL 的 file:line 见 MappingsMapper.xml）。
 */
public interface MappingsMapper {

    /** mappings.rs:270-299 page 主体（概念名/源/表名可搜，canonical_name 序）。 */
    List<ConceptMapping> selectMappingPage(@Param("kbId") UUID kbId, @Param("status") String status,
                                           @Param("q") String q,
                                           @Param("limit") long limit, @Param("offset") long offset);

    /** mappings.rs:301-313 page 总数（同一 WHERE）。 */
    long countMappingPage(@Param("kbId") UUID kbId, @Param("status") String status,
                          @Param("q") String q);

    /** mappings.rs:314-323 status_counts：三种状态各多少。 */
    StatusCounts selectStatusCounts(@Param("kbId") UUID kbId);

    /** mappings.rs:518-540 revisions：改版历史（kb 归属走 JOIN 校验，最新在前，50 封顶）。 */
    List<MappingRevision> selectRevisions(@Param("kbId") UUID kbId, @Param("mappingId") UUID mappingId);

    /** exploration_runs.rs:124-135 recent：最近几轮探索的覆盖账。 */
    List<ExplorationRun> selectRecentRuns(@Param("kbId") UUID kbId, @Param("limit") long limit);

    /** mappings.rs:159-173 confirmed：人确认过的（relevant 的空查询/兜底路径）。 */
    List<ConceptMapping> selectConfirmed(@Param("kbId") UUID kbId, @Param("limit") long limit);

    /** mappings.rs:485-496 confirmed_texts：词面检索的全集行。 */
    List<MappingText> selectConfirmedTexts(@Param("kbId") UUID kbId);

    /** mappings.rs:497-516 by_ids：按调用方顺序回（HashMap 重排后按 ids 序还原）。 */
    List<ConceptMapping> selectMappingsByIds(@Param("kbId") UUID kbId,
                                             @Param("ids") List<UUID> ids);

    /** mappings.rs:179-201 decide：有人表态了——改状态不删行（0 行 → NotFound，服务层转）。 */
    int decideMapping(@Param("kbId") UUID kbId, @Param("mappingId") UUID mappingId,
                      @Param("status") String status, @Param("actor") UUID actor);

    // ---- 口径向量（mappings.rs:367-481；M3 任务 10 随 relevant 向量路落地） ----

    /** mappings.rs:377-390 needing_embedding 第一查：没嵌、或嵌的时候模型跟现在不一样。 */
    List<MappingText> selectUnembeddedOrModelChanged(@Param("kbId") UUID kbId,
                                                     @Param("model") String model,
                                                     @Param("limit") long limit);

    /** mappings.rs:393-401 第二查：同模型嵌过的行 id（文本比对在 Java 侧做，#672 同教训）。 */
    List<UUID> selectModelMatchingIds(@Param("kbId") UUID kbId, @Param("model") String model);

    /** mappings.rs:405-412 第三查：按 id 集合取回现在的文字。 */
    List<MappingText> selectMappingTextsByIds(@Param("ids") List<UUID> ids);

    /** (id, 当时嵌的原文) 形状。 */
    record EmbeddedText(UUID id, String embeddedText) {
    }

    /** mappings.rs:414-420 第四查：当时嵌的原文。 */
    List<EmbeddedText> selectEmbeddedTexts(@Param("ids") List<UUID> ids);

    /** mappings.rs:440-448 set_embeddings：向量连同嵌的是哪段文字、哪个模型同一次写回。 */
    int updateMappingEmbedding(@Param("id") UUID id, @Param("vector") String vector,
                               @Param("model") String model, @Param("text") String text);

    /** mappings.rs:466-473 vector_search：只比同一个模型嵌出来的行，维度不同再挡一道。 */
    List<UUID> selectMappingVectorSearch(@Param("kbId") UUID kbId, @Param("vector") String vector,
                                         @Param("limit") long limit, @Param("model") String model);

    // ---- 口径写面（mappings.rs:92-132 create / 209-262 revise / 166-193
    //      ensure_concept_types；M5 任务 3 随 create/revise/preview 落地） ----

    /** mappings.rs:105-110 create：落下来就是 confirmed（写的人就是表态的人）。唯一约束冲突由 service 折 Conflict。 */
    int insertMapping(@Param("id") UUID id, @Param("kbId") UUID kbId,
                      @Param("conceptId") UUID conceptId, @Param("source") String source,
                      @Param("tableName") String tableName, @Param("expr") String expr,
                      @Param("sql") String sql, @Param("unit") String unit,
                      @Param("summary") String summary, @Param("derived") boolean derived,
                      @Param("actor") UUID actor);

    /** mappings.rs:222-229 revise 第一步：整版快照的 jsonb 文本（null = 该映射不在本库 → NotFound；service 解析后经 JsonNodeTypeHandler 写回 revisions）。 */
    String selectMappingBefore(@Param("kbId") UUID kbId, @Param("mappingId") UUID mappingId);

    int insertMappingRevision(@Param("id") UUID id, @Param("mappingId") UUID mappingId,
                              @Param("before") com.fasterxml.jackson.databind.JsonNode before,
                              @Param("actor") UUID actor);

    /** mappings.rs:244-259 revise 第二步：整版覆写。 */
    int reviseMapping(@Param("kbId") UUID kbId, @Param("mappingId") UUID mappingId,
                      @Param("tableName") String tableName, @Param("expr") String expr,
                      @Param("sql") String sql, @Param("unit") String unit,
                      @Param("summary") String summary, @Param("derived") boolean derived);

    /** mappings.rs:166-193 ensure_concept_types：Metric/Dimension 内置类缺了补（幂等）。 */
    int insertConceptTypeIfMissing(@Param("id") UUID id, @Param("kbId") UUID kbId,
                                   @Param("key") String key, @Param("label") String label,
                                   @Param("description") String description);

    /** mapping_routes.rs:291-296：概念类的 id（ensure 之后必在）。 */
    UUID selectEntityTypeId(@Param("kbId") UUID kbId, @Param("key") String key);

    /** selectStatusCounts 的三元组（proposed/confirmed/rejected）。 */
    record StatusCounts(long proposed, long confirmed, long rejected) {
    }

    // ---- M5 任务 5：探索提议写面（mappings.rs:24-90）+ 探索账写面
    //      （exploration_runs.rs:41-121 全量）。 ----

    /** mappings.rs:29-36 propose 第一步：这条映射是哪一行（无则 null——新提议）。 */
    UUID selectMappingRowId(@Param("kbId") UUID kbId, @Param("conceptId") UUID conceptId,
                            @Param("source") String source);

    /** mappings.rs:38-59 propose 第二步：只刷 proposed 的行（返回行数即「写没写进」）。 */
    int upsertProposedMapping(@Param("id") UUID id, @Param("kbId") UUID kbId,
                              @Param("conceptId") UUID conceptId, @Param("source") String source,
                              @Param("tableName") String tableName, @Param("expr") String expr,
                              @Param("sql") String sql, @Param("unit") String unit,
                              @Param("summary") String summary, @Param("derived") boolean derived);

    /** exploration_runs.rs:43-49 start：先开行再干活。 */
    int insertExplorationRun(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** exploration_runs.rs:55-78 scanned：问模型前先记规模。 */
    int markExplorationScanned(@Param("id") UUID id,
                               @Param("sources") List<String> sources,
                               @Param("tables") int tables, @Param("columns") int columns,
                               @Param("truncated") boolean truncated, @Param("cap") int cap);

    /** exploration_runs.rs:85-107 finish：收账（returned/accepted/dropped/覆盖表）。 */
    int finishExplorationRun(@Param("id") UUID id, @Param("returned") int returned,
                             @Param("accepted") int accepted,
                             @Param("dropped") com.fasterxml.jackson.databind.JsonNode dropped,
                             @Param("tablesCovered") List<String> tablesCovered);

    /** exploration_runs.rs:111-120 fail：这一轮挂了（left(,500)）。 */
    int failExplorationRun(@Param("id") UUID id, @Param("error") String error);
}
