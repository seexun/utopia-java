package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.ChunkFactView;
import tech.seexun.jknow.store.entity.ChunkFull;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.DocumentVersion;
import tech.seexun.jknow.store.entity.ExtractionDrop;
import tech.seexun.jknow.store.entity.IndexChunkRow;
import tech.seexun.jknow.store.entity.JobStatus;

/**
 * documents 读族 mapper（逐字搬迁自旧仓库 utopia-store/src/documents.rs、graph.rs、
 * extraction_drops.rs、jobs.rs；每条 SQL 的 file:line 见 DocumentsMapper.xml）。
 */
public interface DocumentsMapper {

    /** documents.rs:451-464 page 主体（WHERE=437-444，scope 哨兵 'any'/'none'/uuid 文本）。 */
    List<DocumentRow> selectDocPage(@Param("kbId") UUID kbId, @Param("scope") String scope,
                                    @Param("q") String q, @Param("graphStatus") String graphStatus,
                                    @Param("deleted") boolean deleted,
                                    @Param("limit") long limit, @Param("offset") long offset);

    /** documents.rs:466-473 page 总数（同一 WHERE）。 */
    long countDocPage(@Param("kbId") UUID kbId, @Param("scope") String scope,
                      @Param("q") String q, @Param("graphStatus") String graphStatus,
                      @Param("deleted") boolean deleted);

    /** documents.rs:479-494 四列统计（只按来源作用域，不受名字/状态筛选影响）。 */
    DocStats selectDocStats(@Param("kbId") UUID kbId, @Param("scope") String scope);

    /** documents.rs:497-503 整库墓碑数（不随作用域变）。 */
    long countDeletedDocs(@Param("kbId") UUID kbId);

    /** documents.rs:540-546 get：无行→NotFound。 */
    DocumentRow selectDocumentById(@Param("id") UUID id);

    /** documents_routes.rs:174-178 content 的行锁快照（FOR NO KEY UPDATE，读 blob 期间不许挪）。 */
    DocumentRow selectDocumentByIdForUpdate(@Param("id") UUID id);

    /** documents_routes.rs:183-191：按版本号取台账行（无行→404）。 */
    DocumentVersion selectVersionByNumber(@Param("id") UUID id, @Param("version") int version);

    /** documents_routes.rs:192-207：按当前 sha256 取最新台账行（无行→500 no ledger version）。 */
    DocumentVersion selectVersionByCurrentSha(@Param("id") UUID id, @Param("sha256") String sha256);

    /** documents.rs:561-569 versions：保留的原始版本，最老在前（含墓碑文档的版本）。 */
    List<DocumentVersion> selectVersions(@Param("id") UUID id);

    /** documents.rs:1548-1560 chunks_full：文档查看器全部分块（现行版本，按 seq）。 */
    List<ChunkFull> selectChunksFull(@Param("documentId") UUID documentId);

    /** graph.rs:2067-2097 document_extractions：反向证据链（每块抽出的 live 事实）。 */
    List<ChunkFactView> selectExtractions(@Param("documentId") UUID documentId);

    /** extraction_drops.rs:100-108 for_kb：整库丢弃信号（聚合行，上限 2000）。 */
    List<ExtractionDrop> selectExtractionDrops(@Param("kbId") UUID kbId);

    /** jobs.rs:306-311 failed_count（KB_SCOPE=266-274：payload 三路归属判定）。 */
    long countFailedJobs(@Param("kbId") UUID kbId);

    /** jobs.rs:330-339 status_in_kb：任务属于这个库才答，否则无行→404。 */
    JobStatus selectJobInKb(@Param("kbId") UUID kbId, @Param("id") long id);

    // ---- search 域（M1 任务 6；逐字搬迁自 documents.rs chunks_by_ids / all_chunks_for_index /
    //      live_chunk_count，record_axis 谓词按 as_of 有无展开——file:line 见 DocumentsMapper.xml） ----

    /**
     * documents.rs:1699-1725 chunks_by_ids：按 id 集合取分块（带文档名）。as_of null=现在
     * （superseded_at IS NULL AND deleted_at IS NULL）；非 null=记录轴双支。行序不保证，
     * 调用方按传入 ids 顺序（RRF 名次）重排后 truncate(top_k)。
     */
    List<ChunkView> selectChunksByIds(@Param("kbId") UUID kbId,
                                      @Param("ids") List<UUID> ids,
                                      @Param("asOf") OffsetDateTime asOf);

    /** documents.rs:1843-1851 all_chunks_for_index：全量重建源（kb_id, document_id, id, text）。 */
    List<IndexChunkRow> selectAllChunksForIndex();

    /** 上条的每库变体（ES 每 KB 一索引的单库重建，Java 侧新增）。 */
    List<IndexChunkRow> selectKbChunksForIndex(@Param("kbId") UUID kbId);

    /** documents.rs:1854-1860 live_chunk_count：启动对账用。 */
    long countLiveChunks();

    /** 上条的每库变体（ES 每库计数对账，Java 侧新增）。 */
    long countKbLiveChunks(@Param("kbId") UUID kbId);

    /** selectDocStats 的四列（models DocumentPage 的统计段：ready/done/extracting/failed）。 */
    record DocStats(long ready, long done, long extracting, long failed) {
    }
}
