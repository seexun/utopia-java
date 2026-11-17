package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.DocumentRow;

/**
 * 文档写族 mapper（M2 任务 3：上传（D9 消除）/内容替换/墓碑删除/恢复/真删 purge/
 * 状态推进/抽取排队/重试）。SQL 见 resources/mapper/DocumentsWriteMapper.xml——逐字搬迁
 * 自旧仓库 crates/utopia-store/src/documents.rs（+temporal.rs/materialize.rs 引文），每条带 file:line。
 */
@Mapper
public interface DocumentsWriteMapper {

    /**
     * documents.rs:819-842 requeue_waiting_for_reader 的 UPDATE：配上 reader 那一种
     * 模型后，工作区里等着它的活文档改回 pending，返回 (document_id, kb_id)
     * 给调用方逐个排任务/推送列表更新。
     */
    List<RequeuedDoc> requeueWaitingForReader(@Param("workspaceId") UUID workspaceId,
                                              @Param("reader") String reader);

    /** 重排结果对（documents.rs:829 RETURNING d.id, d.kb_id）。 */
    record RequeuedDoc(UUID documentId, UUID kbId) {
    }

    // ---- 上传（D9 消除：documents.rs:79-118 墓碑复活预检 + :121-176 版本 1 + 同事务入队） ----

    /** documents.rs:80-81：同内容只是被删过（未真删）→ 复活那一篇而不是撞唯一索引。 */
    UUID selectDeletedIdBySha(@Param("kbId") UUID kbId, @Param("sha256") String sha256);

    /** documents.rs:91-93 INSERT ... RETURNING *（doc_time_source 的取值由调用方算好传入）。 */
    DocumentRow insertDocument(@Param("id") UUID id, @Param("kbId") UUID kbId,
                               @Param("filename") String filename, @Param("mime") String mime,
                               @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                               @Param("sourceId") UUID sourceId,
                               @Param("docTime") OffsetDateTime docTime,
                               @Param("docTimeSource") String docTimeSource,
                               @Param("externalKey") String externalKey);

    /** documents.rs:158-159 版本 1（create_with_version 的台账行）。 */
    int insertVersionOne(@Param("id") UUID id, @Param("documentId") UUID documentId,
                         @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes);

    // ---- 内容替换（documents.rs:178-233） ----

    /** documents.rs:189 替换预检：活文档行锁（不存在→NotFound 由调用方判）。 */
    UUID selectLiveByIdForUpdate(@Param("id") UUID id);

    /** documents.rs:197-202 替换主行。 */
    int updateReplaceContent(@Param("id") UUID id, @Param("filename") String filename,
                             @Param("mime") String mime, @Param("sizeBytes") long sizeBytes,
                             @Param("sha256") String sha256,
                             @Param("docTime") OffsetDateTime docTime);

    /** documents.rs:213-216 版本 max+1。 */
    int insertVersionNext(@Param("id") UUID id, @Param("documentId") UUID documentId,
                          @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes);

    // ---- 墓碑删除（documents.rs:920-1001）与恢复（:1117-1216）共用的锁与读 ----

    /** documents.rs:1228 锁序第一环：source 先于 document（RSS 顺序，绝不倒过来）。 */
    void lockDocumentSource(@Param("id") UUID id);

    /** documents.rs:1130 恢复路径的文档行锁（无 kb 条件）。 */
    void lockDocumentRow(@Param("id") UUID id);

    /** documents.rs:929-930 打墓碑；0 行=NotFound（不在本库或已删）。 */
    int tombstone(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** documents.rs:940-941 分块打 superseded_at；RETURNING id 即名单。 */
    List<UUID> supersedeChunks(@Param("id") UUID id);

    /** documents.rs:1083-1086 引用本篇的现存事实（锁前一遍、锁后再读一遍——#679）。 */
    List<UUID> selectCitedFacts(@Param("kbId") UUID kbId, @Param("documentId") UUID documentId);

    // temporal 域的锁/放置/reattest 语句（selectTimelinesOf/lockPredicateShared/
    // lockTimelineKey/reattest*）M3 任务 2 起迁至 TemporalMapper（SQL 一字未动）。

    /**
     * documents.rs:952-968 作废「出处全删」事实（大 SQL 逐字：EXISTS 本篇证据 ×
     * NOT EXISTS 活文档证据 × known_as 本名豁免）。cited=先打了墓碑再算的名单。
     */
    List<UUID> invalidateFacts(@Param("kbId") UUID kbId, @Param("documentId") UUID documentId,
                               @Param("cited") List<UUID> cited);

    /** documents.rs:980-984 删除账（invalidated_facts/superseded_chunks 名单入账）。 */
    void insertDeletion(@Param("id") UUID id, @Param("kbId") UUID kbId,
                        @Param("documentId") UUID documentId, @Param("deletedBy") UUID deletedBy,
                        @Param("invalidatedFacts") List<UUID> invalidatedFacts,
                        @Param("supersededChunks") List<UUID> supersededChunks);

    /** documents.rs:1134-1138 最近一条未撤销的删除账（无→Conflict "not deleted"）。 */
    DeletionRow selectLatestDeletion(@Param("documentId") UUID documentId,
                                     @Param("kbId") UUID kbId);

    /** documents.rs:1135 SELECT id, invalidated_facts, superseded_chunks。 */
    record DeletionRow(UUID id, List<UUID> invalidatedFacts, List<UUID> supersededChunks) {
    }

    /** documents.rs:1148 真删判定（purged→Conflict "nothing to restore"）。 */
    Boolean selectPurgedFlag(@Param("id") UUID id);

    /** documents.rs:1157-1159 复活主行；0 行=Conflict "not deleted"。 */
    int resurrectDocument(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** documents.rs:1168 这次打标的分块复活。 */
    int reviveChunks(@Param("chunkIds") List<UUID> chunkIds);

    /** documents.rs:1174-1183 共同作证救援名单（按删除事务时间戳精确匹配）。 */
    List<SharedFact> selectSharedWitness(@Param("kbId") UUID kbId,
                                         @Param("documentId") UUID documentId);

    /** documents.rs:1175 SELECT DISTINCT f.id, f.invalidated_at。 */
    record SharedFact(UUID id, OffsetDateTime invalidatedAt) {
    }

    /** documents.rs:1194-1199 复活事实（名单 + (id,时间戳) 对）。 */
    int reviveFacts(@Param("factIds") List<UUID> factIds,
                    @Param("sharedIds") List<UUID> sharedIds,
                    @Param("sharedStamps") List<OffsetDateTime> sharedStamps);

    /** documents.rs:1208 撤销这条删除账。 */
    void revertDeletion(@Param("id") UUID id);

    /** documents.rs:1212 复活后的主行（响应用）。 */
    DocumentRow selectDocumentById(@Param("id") UUID id);

    // ---- 真删 purge（documents.rs:1242-1310） ----

    /** documents.rs:1246-1248（sha256, deleted_at, purged_at）行锁快照；无→NotFound。 */
    PurgeRow selectDocumentForPurge(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** documents.rs:1247 SELECT sha256, deleted_at, purged_at。 */
    record PurgeRow(String sha256, OffsetDateTime deletedAt, OffsetDateTime purgedAt) {
    }

    /** documents.rs:1266 历史版本指纹。 */
    List<String> selectVersionShas(@Param("id") UUID id);

    /** documents.rs:1273 分块行删掉（证据引文随外键级联）。 */
    int deleteChunks(@Param("id") UUID id);

    /** documents.rs:1278 版本行删掉。 */
    int deleteVersions(@Param("id") UUID id);

    /** documents.rs:1282-1286 墓碑打 purged_at、身份让出（external_key=NULL）。 */
    int markPurged(@Param("id") UUID id);

    /** documents.rs:1299-1303 独占 blob 判定（两 NOT EXISTS，一句话判完所有指纹）。 */
    List<String> selectExclusiveBlobs(@Param("shas") List<String> shas);

    // ---- 状态推进（documents.rs:774-813/886-898/1605-1616/1814-1824） ----

    /** documents.rs:776-779 set_status（重新开始处理：缺的读取模型不再算数）。 */
    void setStatus(@Param("id") UUID id, @Param("status") String status);

    /** documents.rs:788-791 set_failed。 */
    void setFailed(@Param("id") UUID id, @Param("error") String error);

    /** documents.rs:887-891 set_ready(text_len, chunk_count)。 */
    void setReady(@Param("id") UUID id, @Param("textLen") int textLen,
                  @Param("chunkCount") int chunkCount);

    /** documents.rs:1607-1610 set_graph_status（顺带清空上一轮失败原因）。 */
    void setGraphStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * documents.rs:801-810 逐字：文件要 reader 那一种模型才读得出字而模型没配（0040）——
     * 停在 failed，记下缺的是哪一种，配上之后按它重新排队。
     */
    void setNeedsReader(@Param("id") UUID id, @Param("reader") String reader,
                        @Param("error") String error);

    /** documents.rs:1816-1819 set_graph_failed。 */
    void setGraphFailed(@Param("id") UUID id, @Param("error") String error);

    // ---- 认领式分块（documents.rs:1334-1488 replace_chunks_for_snapshot，任务 5 管线起用） ----

    /**
     * documents.rs:1347-1354 逐字语义：先锁父记录（<b>FOR NO KEY UPDATE</b>，不是
     * FOR UPDATE——后者与外键检查要的 FOR KEY SHARE 冲突，会挡住这份文档所有子表插入）；
     * 返回 (sha256, 是否墓碑)；无行=null。
     */
    SnapshotRow selectSnapshotForChunkUpdate(@Param("id") UUID id);

    /** documents.rs:1347 SELECT sha256, deleted_at IS NOT NULL。 */
    record SnapshotRow(String sha256, boolean tombstoned) {
    }

    /** documents.rs:1359-1363 逐字：认领块挂到当前版本号（无版本行时 1）。 */
    int selectCurrentVersion(@Param("documentId") UUID documentId);

    /** documents.rs:1371-1375 逐字：现行块（认领池原料：文本+出处四元组）。 */
    List<StoredChunk> selectCurrentChunks(@Param("documentId") UUID documentId);

    /** documents.rs:1370 Stored 五元组（anchor 为 jsonb）。 */
    record StoredChunk(UUID id, String text, String origin, String originModel,
                       com.fasterxml.jackson.databind.JsonNode anchor) {
    }

    /** documents.rs:1383-1392 逐字：认领（原行更新 seq/区间/版本/标题）。 */
    int updateChunkClaim(@Param("id") UUID id, @Param("seq") int seq,
                         @Param("charStart") int charStart, @Param("charEnd") int charEnd,
                         @Param("version") int version, @Param("heading") String heading);

    /**
     * documents.rs:1424-1429 逐字：落选旧块（新版里没有同款文本）软删——<b>向量留着</b>
     * （0019 开放问题②：正文本来就得留，向量几 KB，删掉省的是小头、丢的是 as-of 检索）。
     */
    int supersedeChunksExcept(@Param("documentId") UUID documentId,
                              @Param("keep") List<UUID> keep);

    /** documents.rs:1432-1448 逐字：新块 INSERT（一块一种出处）。 */
    int insertChunk(@Param("id") UUID id, @Param("kbId") UUID kbId,
                    @Param("documentId") UUID documentId, @Param("seq") int seq,
                    @Param("text") String text, @Param("charStart") int charStart,
                    @Param("charEnd") int charEnd, @Param("version") int version,
                    @Param("heading") String heading, @Param("origin") String origin,
                    @Param("originModel") String originModel,
                    @Param("anchor") com.fasterxml.jackson.databind.JsonNode anchor);

    // ---- embedding（documents.rs:1619-1650，管线 embedding 步起用） ----

    /** documents.rs:1619-1631 逐字：该文档尚未 embedding 的分块（id+文本）。 */
    List<ChunkText> selectPendingEmbedding(@Param("documentId") UUID documentId);

    /**
     * documents.rs:1633-1650 set_embeddings 的单条 UPDATE（向量以 {@code '[a,b,…]'}
     * 文本形态传入、PG 侧 cast vector——pgvector-java 依赖免了，语义同 Rust Vector）。
     */
    int setEmbedding(@Param("id") UUID id, @Param("vector") String vector);

    /**
     * vector_index.rs:153-161 status_on 逐字：索引现在的状态（None 没有；Some(valid) 有；
     * false=建到一半的残留）。第一次写下某维度向量时决定要不要排 build_vector_index。
     */
    Boolean selectEmbeddingIndexValid(@Param("indexName") String indexName);

    /** documents.rs:527-533 retry_failed 的名单（graph_status='failed'，作用域哨兵串）。 */
    List<UUID> failedIds(@Param("kbId") UUID kbId, @Param("scope") String scope);

    // ---- 抽取排队（documents.rs:1509-1545 单篇 / :1751-1812 整库整源） ----

    /** documents.rs:1511-1514 清增量标记（单篇）。 */
    int clearChunkExtracted(@Param("documentId") UUID documentId);

    /** documents.rs:1519-1522 解雇未开跑的旧任务（单篇）。 */
    int deleteQueuedExtractJob(@Param("documentId") String documentId);

    /** documents.rs:1526-1529 graph_status='queued' + epoch 自增（单篇）。 */
    int setGraphQueuedEpoch(@Param("id") UUID id);

    /** documents.rs:1534-1538 建抽取任务 RETURNING id（单篇）。 */
    long insertExtractJob(@Param("documentId") UUID documentId);

    /** documents.rs:1760-1766 待抽名单（ready 活文档，extract='false' 豁免）。 */
    List<UUID> selectExtractionIds(@Param("kbId") UUID kbId, @Param("sourceId") UUID sourceId);

    /** documents.rs:1778-1781 解雇未开跑的旧任务（批量，payload 文本比较）。 */
    int deleteQueuedExtractJobsAny(@Param("documentIds") List<String> documentIds);

    /** documents.rs:1785-1788 清增量标记（批量）。 */
    int clearChunkExtractedAny(@Param("documentIds") List<UUID> documentIds);

    /** documents.rs:1792-1795 graph_status='queued' + epoch 自增（批量）。 */
    int setGraphQueuedEpochAny(@Param("documentIds") List<UUID> documentIds);

    /** documents.rs:1801-1807 建抽取任务（批量，unnest）。 */
    int insertExtractJobs(@Param("documentIds") List<UUID> documentIds);

    // ---- 恢复后的索引重建原料（documents.rs:1730-1735 chunks_in_document） ----

    /** documents.rs:1730-1735 现行分块（id+text，reindex 路由层用）。 */
    List<ChunkText> selectChunksInDocument(@Param("kbId") UUID kbId,
                                           @Param("documentId") UUID documentId);

    /** documents.rs:1731 SELECT c.id ... c.text。 */
    record ChunkText(UUID id, String text) {
    }

    /** 一条向量写回（documents.rs:1633-1650 set_embeddings 的单件）。 */
    record EmbeddingWrite(UUID id, float[] vector) {
    }

    // ---- 同步三路判定与 missing 台账（documents.rs:580-780；M2 任务 4 起用） ----

    /** documents.rs:584-597 按来源内逻辑身份查文档（无 deleted_at 过滤——墓碑也认领）。 */
    DocumentRow findByExternalKey(@Param("sourceId") UUID sourceId,
                                  @Param("externalKey") String externalKey);

    /** documents.rs:599-612 按来源+内容哈希查活文档（改名/移动识别）。 */
    UUID selectDeletedIdByKey(@Param("sourceId") UUID sourceId,
                              @Param("externalKey") String externalKey);

    DocumentRow findByExternalKeyForUpdate(@Param("sourceId") UUID sourceId,
                                           @Param("externalKey") String externalKey);

        DocumentRow findBySourceSha(@Param("sourceId") UUID sourceId, @Param("sha256") String sha256);

    /** documents.rs:614-630 迁移前的历史文档（无 external_key）按文件名认领——一次性兜底。 */
    DocumentRow findLegacyByFilename(@Param("sourceId") UUID sourceId,
                                     @Param("filename") String filename);

    /** documents.rs:632-637 给历史文档补逻辑身份（认领时旧内容记为版本 1 配 recordVersion）。 */
    int adoptExternalKey(@Param("id") UUID id, @Param("externalKey") String externalKey);

    /** documents.rs:675-688 移动/改名：同内容换了路径，只更新身份，不重跑管道。 */
    int updateLocation(@Param("id") UUID id, @Param("filename") String filename,
                       @Param("externalKey") String externalKey);

    /** documents.rs:694-712 记录一个内容版本（版本号 max+1；legacy 认领时补记版本 1）。 */
    int recordVersion(@Param("id") UUID id, @Param("documentId") UUID documentId,
                      @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes);

    /** documents.rs:718-735 全集对账：见到的清 missing，没见到的打标（url 型专用）。 */
    int markMissingExcept(@Param("sourceId") UUID sourceId, @Param("seenKeys") List<String> seenKeys);

    /** documents.rs:737-748 本轮出现的条目清除 missing 标记（失而复得）。 */
    int clearMissingKeys(@Param("sourceId") UUID sourceId, @Param("keys") List<String> keys);

    /** documents.rs:750-761 显式墓碑（custom deleted[] / api push deleted:true）。 */
    int markMissingKeys(@Param("sourceId") UUID sourceId, @Param("keys") List<String> keys);

    /** documents.rs:763-771 该来源下所有已标 missing 的活文档 id（批量清理用）。 */
    List<UUID> listMissing(@Param("sourceId") UUID sourceId);

    // ---- 来源引用（写路径的 folder 校验与 extracts 判定；来源域全生命周期属任务 4） ----

    /**
     * 上传目标校验（documents_routes.rs:252-262 的 src.kb_id/kind）+ 手动 Extract 的
     * extracts 判定（models.rs:178-183 config.extract 缺省 true；SQL 谓词形同
     * documents.rs:1765 的豁免判定）。
     */
    SourceRef selectSourceRef(@Param("id") UUID id);

    /** 上传/抽取路径要看的来源三键。 */
    record SourceRef(UUID kbId, String kind, boolean extracts) {
    }

    // ---- extraction_drops 写侧（extraction_drops.rs:63-96） ----

    /** extraction_drops.rs:71-77 记一条丢弃信号（同键再撞只计数并补空 example）。 */
    int insertExtractionDrop(@Param("kbId") UUID kbId, @Param("documentId") UUID documentId,
                             @Param("reason") String reason, @Param("detail") String detail,
                             @Param("example") String example);

    /** extraction_drops.rs:90-93 重抽开始时清掉这篇文档的旧信号。 */
    int clearExtractionDrops(@Param("documentId") UUID documentId);

    // ---- 抽取执行（documents.rs:1496-1604/1831-1834，任务 8 起用） ----

    /** documents.rs:1496-1498 mark_chunk_extracted 逐字：抽取完成一个分块即打标（断点续抽的锚）。 */
    int markChunkExtracted(@Param("chunkId") UUID chunkId);

    /** documents.rs:1553-1587 ChunkForExtract 四元组（embedding 摄入期已算好，消解 v2 复用）。 */
    record ChunkForExtract(UUID id, int seq, String text, String origin,
                           com.fasterxml.jackson.databind.JsonNode embedding) {
    }

    /**
     * documents.rs:1573-1587 chunks_for_extraction 逐字：只取未抽取的分块（认领的未变段落
     * 携带 extracted_at 跳过——增量抽取 + 断点续抽）。
     */
    List<ChunkForExtract> selectChunksForExtraction(@Param("documentId") UUID documentId);

    /**
     * documents.rs:1591-1602 opening_chunk 逐字：文件的<b>开头</b>（序号最小的现存分块，
     * 不论抽没抽过——不能拿 chunks_for_extraction 的第一条代替）。
     */
    OpeningChunk selectOpeningChunk(@Param("documentId") UUID documentId);

    /** opening_chunk 的 (id, text)；无行=null。 */
    record OpeningChunk(UUID id, String text) {
    }

    /**
     * documents.rs:642-653 set_time_context 逐字：文档的时间语境（0045 决定 3）——服务端
     * 边抽取边填、整份覆盖；time_context_at 记最近一次写下它的时刻。
     */
    int setTimeContext(@Param("id") UUID id,
                       @Param("context") com.fasterxml.jackson.databind.JsonNode context);

    /**
     * documents.rs:658-663 set_content_date 逐字：文档自己的日期，从正文里读出来的
     * （doc_time_source='content'；上传时刻永远不进来，#714）。
     */
    int setContentDate(@Param("id") UUID id, @Param("date") java.time.OffsetDateTime date);

    /**
     * documents.rs:1826-1834 extract_epoch 逐字：抽取任务的所有权凭证——每次「开始新一轮
     * 抽取」自增；旧任务每块循环顶部与收尾处比对，不一致即安静退出。
     */
    int extractEpoch(@Param("id") UUID id);
}
