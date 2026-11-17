package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.ingest.ChunkPiece;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Timeline;

/**
 * 文档写族服务（逐字对齐旧仓库 crates/utopia-store/src/documents.rs 的编排层，
 * M2 任务 3 全量）：上传（D9 消除：版本 1 + 同事务入队）/内容替换/墓碑删除（锁序
 * source→document→cited timelines + 作废「出处全删」事实）/恢复（共同作证救援）/
 * 真删 purge（独占 blob 判定）/状态推进/抽取排队。
 *
 * <p><b>D9 消除</b>（m2-plan 前置知识 3）：Rust 上传路径 {@code create_from_upload} 不写
 * document_versions、事务外入队；Java 侧按 {@code create_with_version_and_processing}
 * （documents.rs:121-176）的形状落：INSERT documents + INSERT document_versions 版本 1 +
 * 同事务 {@code enqueue_with_max_attempts_tx("process_document",…,3)}——一个事务里
 * 「文档、台账、任务要么都在、要么都不在」。
 *
 * <p><b>M3 任务 2 起</b>：删除/恢复收尾的 {@code tidy_timelines_tx}（时间线重算）与
 * {@code reattest_tx}（见证重算）已由 {@link TemporalService} 真身承担——锁
 * （timelines_of + lock_timelines 两级咨询锁与排序）与重算同一事务，与 Rust 侧并发
 * 删除/落库对账互不死锁。
 */
@Service
public class DocumentsWriteService {

    private static final Logger log = LoggerFactory.getLogger(DocumentsWriteService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocumentsWriteMapper documents;
    private final JobsService jobs;
    private final TemporalService temporal;
    private final VectorIndexService vectorIndexes;

    public DocumentsWriteService(DocumentsWriteMapper documents, JobsService jobs,
                                 TemporalService temporal, VectorIndexService vectorIndexes) {
        this.documents = documents;
        this.jobs = jobs;
        this.temporal = temporal;
        this.vectorIndexes = vectorIndexes;
    }

    /**
     * 配上了 reader 那一种模型（ocr/transcribe）：工作区里等着它的文档（活着的）
     * 重新排进处理队列（documents.rs:819-842 逐字语义）。一个事务里改状态、排任务；
     * 返回 (文档, 库) 列表给调用方逐个推送列表更新。
     */
    @Transactional
    public List<DocumentsWriteMapper.RequeuedDoc> requeueWaitingForReader(UUID workspaceId,
                                                                          String reader) {
        List<DocumentsWriteMapper.RequeuedDoc> rows =
                documents.requeueWaitingForReader(workspaceId, reader);
        for (DocumentsWriteMapper.RequeuedDoc row : rows) {
            jobs.enqueueWithMaxAttemptsTx("process_document", processPayload(row.documentId()), 3);
        }
        return rows;
    }

    // ---- 上传 / kb-ingest（D9 消除，documents.rs:121-176 形状） ----

    /**
     * 新建文档 + 版本 1 + 同事务入队（D9 消除）。timeSource：上传识别正文日期='content'、
     * JSON ingest/同步='source'——没有日期就是 'none'（上传的时刻是记录时间，0045 决定 3）。
     * 墓碑复活预检（documents.rs:79-89）逐字：同内容只是被删过 → 走恢复事务原路复活。
     */
    @Transactional
    public DocumentRow createWithVersion(UUID kbId, String filename, String mime, long sizeBytes,
                                         String sha256, UUID sourceId, OffsetDateTime docTime,
                                         String timeSource) {
        return createWithVersion(kbId, filename, mime, sizeBytes, sha256, sourceId, docTime,
                timeSource, null, true);
    }

    /**
     * 同步/推送路径的新建（documents.rs:121-176 create_with_version_and_processing 逐字形状）：
     * 带 external_key；<b>无墓碑复活预检</b>——同步的三路判定上一环已按身份认领过墓碑
     * （revived 走 restore），落到这里的 INSERT 撞唯一索引就按 Conflict 上抛
     * （Rust 语义：不伪造新身份，调用方译 Unchanged）。上传路径传 reviveOnTombstone=true
     * 复用本方法并预检（documents.rs:79-89）。
     */
    @Transactional
    public DocumentRow createWithVersion(UUID kbId, String filename, String mime, long sizeBytes,
                                         String sha256, UUID sourceId, OffsetDateTime docTime,
                                         String timeSource, String externalKey,
                                         boolean reviveOnTombstone) {
        if (reviveOnTombstone) {
            UUID revived = documents.selectDeletedIdBySha(kbId, sha256);
            if (revived != null) {
                return restore(kbId, revived);
            }
        }
        String docTimeSource = docTime != null ? timeSource : "none";
        DocumentRow doc;
        try {
            doc = documents.insertDocument(Uuids.nowV7(), kbId, filename, mime, sizeBytes,
                    sha256, sourceId, docTime, docTimeSource, externalKey);
        } catch (DuplicateKeyException e) {
            // documents.rs:112-117：唯一索引冲突=同库同内容（且活着）→ Conflict，调用方译 skip
            throw AppException.conflict("File already exists (identical content): " + filename);
        }
        documents.insertVersionOne(Uuids.nowV7(), doc.id(), sha256, sizeBytes);
        jobs.enqueueWithMaxAttemptsTx("process_document", processPayload(doc.id()), 3);
        return doc;
    }

    // ---- 内容替换（documents.rs:178-233） ----

    /** 替换 + 版本 max+1 + 同事务入队；文档不存在或已删→NotFound。 */
    @Transactional
    public void replaceContentAndEnqueue(UUID id, String filename, String mime, long sizeBytes,
                                         String sha256, OffsetDateTime docTime) {
        if (documents.selectLiveByIdForUpdate(id) == null) {
            throw AppException.NOT_FOUND;
        }
        documents.updateReplaceContent(id, filename, mime, sizeBytes, sha256, docTime);
        documents.insertVersionNext(Uuids.nowV7(), id, sha256, sizeBytes);
        jobs.enqueueWithMaxAttemptsTx("process_document", processPayload(id), 3);
    }

    // ---- 墓碑删除（documents.rs:920-1001） ----

    /** 一次删除的产出，给审计与界面提示用（documents.rs:901-907）。 */
    public record DeletionReport(UUID deletionId, int invalidatedFacts, int supersededChunks) {
    }

    /**
     * 删除一篇文档——认知轴上的一个事件，不是减法（#268）。锁序
     * <b>source→document→cited timelines</b>；打墓碑→分块 superseded→作废「出处全删」
     * 事实（known_as 本名豁免）→reattest→<b>tidy_timelines</b>（牵连的时间线重算——
     * 作废的事实可能是别的值的后任，它走了，关在它开始时的前任要重新接上）→删除账入账。
     *
     * @param actor null=引擎（来源对账的批量清理）
     */
    @Transactional
    public DeletionReport delete(UUID kbId, UUID id, UUID actor) {
        documents.lockDocumentSource(id);
        int hit = documents.tombstone(id, kbId);
        if (hit == 0) {
            throw AppException.NOT_FOUND;
        }
        List<UUID> chunkIds = documents.supersedeChunks(id);
        // 先打了墓碑再算：这篇文档此刻已经算「已删除」，所以只剩它作出处的事实才落网
        LockedCited locked = lockCitedTimelines(kbId, id, List.of());
        List<UUID> factIds = documents.invalidateFacts(kbId, id, locked.facts());
        temporal.reattestTx(kbId, locked.facts());
        // 作废完行，同一事务里按已持的锁重算牵连的时间线（temporal.rs tidy_timelines_tx）
        temporal.tidyTimelinesTx(kbId, locked.timelines());
        UUID deletionId = Uuids.nowV7();
        documents.insertDeletion(deletionId, kbId, id, actor, factIds, chunkIds);
        return new DeletionReport(deletionId, factIds.size(), chunkIds.size());
    }

    // ---- 恢复（documents.rs:1117-1216） ----

    /**
     * 撤销一次删除：文档、这次打标的分块、这次作废的事实原路复活（三条路共用：人点撤销、
     * 同步撞见墓碑、同内容重传）。共同作证救援按删除事务时间戳精确匹配；复活完行，
     * 同一事务里 tidy_timelines 把牵连的时间线重算回有这些行的样子。
     */
    @Transactional
    public DocumentRow restore(UUID kbId, UUID id) {
        documents.lockDocumentSource(id);
        documents.lockDocumentRow(id);
        DocumentsWriteMapper.DeletionRow row = documents.selectLatestDeletion(id, kbId);
        if (row == null) {
            throw AppException.conflict("Document is not deleted");
        }
        if (Boolean.TRUE.equals(documents.selectPurgedFlag(id))) {
            throw AppException.conflict(
                    "The document's content was purged; there is nothing to restore");
        }
        int hit = documents.resurrectDocument(id, kbId);
        if (hit == 0) {
            throw AppException.conflict("Document is not deleted");
        }
        documents.reviveChunks(row.supersededChunks());
        List<DocumentsWriteMapper.SharedFact> shared = documents.selectSharedWitness(kbId, id);
        List<UUID> sharedIds = shared.stream().map(DocumentsWriteMapper.SharedFact::id).toList();
        List<OffsetDateTime> sharedStamps =
                shared.stream().map(DocumentsWriteMapper.SharedFact::invalidatedAt).toList();
        List<UUID> restored = new ArrayList<>(row.invalidatedFacts());
        restored.addAll(sharedIds);
        // 一次锁齐：先锁引用的、再锁复活的（分两轮拿锁会与按序拿同样两把锁的事务互等死）
        LockedCited locked = lockCitedTimelines(kbId, id, restored);
        documents.reviveFacts(row.invalidatedFacts(), sharedIds, sharedStamps);
        List<UUID> touched = new ArrayList<>(locked.facts());
        touched.addAll(restored);
        temporal.reattestTx(kbId, touched);
        // 复活完行重算牵连的时间线（temporal.rs tidy_timelines_tx，与 delete 同一收尾）
        temporal.tidyTimelinesTx(kbId, locked.timelines());
        documents.revertDeletion(row.id());
        return documents.selectDocumentById(id);
    }

    // ---- 真删 purge（documents.rs:1242-1310） ----

    /** 一次真删的产出：blobs=这篇（连同历史版本）独占的原文指纹（documents.rs:1220-1224）。 */
    public record PurgeReport(List<String> blobs, long chunks) {
    }

    /** 真删（#268 下半）：只对已删除的文档开放，不可撤销；身份让出（external_key=NULL）。 */
    @Transactional
    public PurgeReport purge(UUID kbId, UUID id) {
        documents.lockDocumentSource(id);
        DocumentsWriteMapper.PurgeRow row = documents.selectDocumentForPurge(id, kbId);
        if (row == null) {
            throw AppException.NOT_FOUND;
        }
        if (row.purgedAt() != null) {
            throw AppException.conflict("The document was already purged");
        }
        if (row.deletedAt() == null) {
            throw AppException.conflict("Delete the document before purging it");
        }
        List<String> shas = new ArrayList<>(documents.selectVersionShas(id));
        if (!shas.contains(row.sha256())) {
            shas.add(row.sha256());
        }
        long chunks = documents.deleteChunks(id);
        documents.deleteVersions(id);
        documents.markPurged(id);
        // 必须站在版本行删掉之后判独占（#516）——旧版本不再算引用，独占的才收得回来
        List<String> blobs = documents.selectExclusiveBlobs(shas);
        return new PurgeReport(blobs, chunks);
    }

    // ---- 状态推进（documents.rs:774-813/886-898/1605-1616/1814-1824，单语句） ----

    public void setStatus(UUID id, String status) {
        documents.setStatus(id, status);
    }

    public void setFailed(UUID id, String error) {
        documents.setFailed(id, error);
    }

    public void setReady(UUID id, int textLen, int chunkCount) {
        documents.setReady(id, textLen, chunkCount);
    }

    public void setGraphStatus(UUID id, String status) {
        documents.setGraphStatus(id, status);
    }

    public void setNeedsReader(UUID id, String reader, String error) {
        documents.setNeedsReader(id, reader, error);
    }

    public void setGraphFailed(UUID id, String error) {
        documents.setGraphFailed(id, error);
    }

    // ---- 认领式分块（documents.rs:1334-1488 replace_chunks_if_current，管线 parsing 步） ----

    /** 分块落库产物：(chunk_id, text) 按块序——全文索引与 embedding 都吃这一份。 */
    public record ChunkPair(UUID id, String text) {
    }

    /**
     * 认领式替换（documents.rs:1334-1488 逐字语义）：sha 快照不符（读取期间源文档已更新
     * 或删除）返回 <b>null</b>——过期结果丢弃，不写新任务的索引/状态/抽取队列。
     *
     * <p>认领按「文本 + 出处」四元组（同文重复块按多重集配对，各认领各的；同一句话从
     * 原文读到和从扫描页认出来不是同一块，0040）。三阶段：认领（原行更新）→ 落选软删
     * （<b>向量保留</b>）→ 新块 INSERT——新块必须等软删跑完再插，否则落选判定会把本轮
     * 刚插入的新块一并软删。
     */
    /** documents.rs:1319-1326 replace_chunks：无 sha 快照的整替（删/恢复复活链用）。 */
    @Transactional
    public List<ChunkPair> replaceChunks(UUID kbId, UUID documentId, List<ChunkPiece> pieces) {
        return replaceChunksIfCurrent(kbId, documentId, pieces, null);
    }

    @Transactional
    public List<ChunkPair> replaceChunksIfCurrent(UUID kbId, UUID documentId,
                                                  List<ChunkPiece> pieces, String sha256) {
        DocumentsWriteMapper.SnapshotRow snapshot = documents.selectSnapshotForChunkUpdate(documentId);
        if (snapshot == null || snapshot.tombstoned()
                || (sha256 != null && !sha256.equals(snapshot.sha256()))) {
            return null;
        }
        int version = documents.selectCurrentVersion(documentId);

        // 认领池：现行块按四元组分组（同款文本的多块排队领号）
        record ClaimKey(String text, String origin, String model,
                        com.fasterxml.jackson.databind.JsonNode anchor) {
        }
        Map<ClaimKey, java.util.ArrayDeque<UUID>> pool = new HashMap<>();
        for (DocumentsWriteMapper.StoredChunk c : documents.selectCurrentChunks(documentId)) {
            pool.computeIfAbsent(new ClaimKey(c.text(), c.origin(), c.originModel(), c.anchor()),
                    k -> new java.util.ArrayDeque<>()).add(c.id());
        }

        List<UUID> adopted = new ArrayList<>();
        UUID[] ids = new UUID[pieces.size()];
        // 第一阶段：认领（原行更新）与待插清单——新块必须等软删跑完再插，否则「落选」
        // 判定会把本轮刚插入的新块一并软删（documents.rs:1377-1379 注释原文）
        for (int i = 0; i < pieces.size(); i++) {
            ChunkPiece piece = pieces.get(i);
            ClaimKey key = new ClaimKey(piece.text(), piece.provenance().origin().asStr(),
                    piece.provenance().model(), piece.provenance().anchor());
            java.util.ArrayDeque<UUID> queue = pool.get(key);
            UUID claimed = queue != null ? queue.poll() : null;
            if (claimed != null) {
                documents.updateChunkClaim(claimed, piece.seq(), piece.charStart(), piece.charEnd(),
                        version, piece.heading());
                adopted.add(claimed);
                ids[i] = claimed;
            }
        }

        // 第二阶段：落选旧块（新版里没有同款文本）软删（keep=认领名单；空名单=全部落选）
        documents.supersedeChunksExcept(documentId, adopted);

        // 第三阶段：插入新块
        for (int i = 0; i < pieces.size(); i++) {
            if (ids[i] != null) {
                continue;
            }
            ChunkPiece piece = pieces.get(i);
            ids[i] = Uuids.nowV7();
            documents.insertChunk(ids[i], kbId, documentId, piece.seq(), piece.text(),
                    piece.charStart(), piece.charEnd(), version, piece.heading(),
                    piece.provenance().origin().asStr(), piece.provenance().model(),
                    piece.provenance().anchor());
        }
        List<ChunkPair> pairs = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            pairs.add(new ChunkPair(ids[i], pieces.get(i).text()));
        }
        return pairs;
    }

    // ---- embedding 原料（documents.rs:1619-1650） ----

    /** documents.rs:1619-1631 chunks_pending_embedding。 */
    public List<DocumentsWriteMapper.ChunkText> chunksPendingEmbedding(UUID documentId) {
        return documents.selectPendingEmbedding(documentId);
    }

    /**
     * documents.rs:1633-1650 set_embeddings：一批向量一个事务写回；第一次写下这个维度
     * 的向量就排 build_vector_index（vector_index.rs:183-201 request——任务本体在
     * M3 任务 10 已落真身 {@link VectorIndexService#build}；「在的话不排」由 pg_class
     * 目录查询+进程内缓存承担）。
     */
    @Transactional
    public void setEmbeddings(List<DocumentsWriteMapper.EmbeddingWrite> writes) {
        // 维度取批内第一条（documents.rs:1644-1646 的 items.first()——同批同模型同维度，
        // 取首不取末只是对齐；任务 10 审查 M-1）
        int dims = writes.isEmpty() ? -1 : writes.get(0).vector().length;
        for (var w : writes) {
            documents.setEmbedding(w.id(), vectorLiteral(w.vector()));
        }
        if (dims > 0 && dims <= VectorIndexService.MAX_DIMS) {
            vectorIndexes.request(VectorIndexService.Target.CHUNKS, dims);
        }
    }

    /** 向量的 PG 文本形态（"[0.1,0.2,…]"，pgvector 文本输入格式）。 */
    private static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    // ---- 抽取排队（documents.rs:1509-1545 单篇 / :1751-1812 批量；执行器属 M3） ----

    /**
     * 单篇排队重抽（手动 Extract=强制全量）：清增量标记→解雇未开跑的旧任务→置 queued、
     * epoch 自增→建抽取任务——整体一个事务（分开做任一步失败都会留下半截状态）。
     */
    @Transactional
    public long queueExtractionOne(UUID documentId) {
        documents.clearChunkExtracted(documentId);
        documents.deleteQueuedExtractJob(documentId.toString());
        documents.setGraphQueuedEpoch(documentId);
        long jobId = documents.insertExtractJob(documentId);
        jobs.notifyWorkerTx();
        return jobId;
    }

    /** 批量排队全量重抽（整库/整源）：建任务与置状态同事务，返回待抽文档 id。 */
    @Transactional
    public List<UUID> queueExtraction(UUID kbId, UUID sourceId) {
        List<UUID> ids = documents.selectExtractionIds(kbId, sourceId);
        if (ids.isEmpty()) {
            return ids;
        }
        documents.deleteQueuedExtractJobsAny(ids.stream().map(UUID::toString).toList());
        documents.clearChunkExtractedAny(ids);
        documents.setGraphQueuedEpochAny(ids);
        documents.insertExtractJobs(ids);
        jobs.notifyWorkerTx();
        return ids;
    }

    /** retry-failed 的名单（scope 哨兵串 any/none/uuid 文本，documents.rs:517-537）。 */
    public List<UUID> failedIds(UUID kbId, String scope) {
        return documents.failedIds(kbId, scope);
    }

    /** 恢复后的索引重建原料（documents.rs:1725-1740 现行分块 (id,text)）。 */
    public List<DocumentsWriteMapper.ChunkText> chunksInDocument(UUID kbId, UUID documentId) {
        return documents.selectChunksInDocument(kbId, documentId);
    }

    // ==== 引用时间线的锁（documents.rs:1068-1110 + temporal.rs:248-291/321-372） ====

    /**
     * 引用这篇文档的现存事实，连同它们所在的唯一性时间线，锁上之后返回
     * <b>再读出的名单</b>（#679：等锁期间时间线重算可能改写行，旧 id 落网不到新行）。
     * {@code also}：一并要锁的别的事实（复活名单）——一次锁齐，分两轮拿锁会互相等死。
     */
    private record LockedCited(List<UUID> facts, List<Timeline> timelines) {
    }

    private LockedCited lockCitedTimelines(UUID kbId, UUID documentId, List<UUID> also) {
        List<UUID> cited = documents.selectCitedFacts(kbId, documentId);
        List<UUID> first = new ArrayList<>(cited);
        first.addAll(also);
        List<Timeline> timelines = temporal.timelinesOf(kbId, first);
        temporal.lockTimelines(kbId, timelines);
        List<UUID> reread = documents.selectCitedFacts(kbId, documentId);
        List<Timeline> late = new ArrayList<>();
        for (Timeline t : temporal.timelinesOf(kbId, reread)) {
            if (!timelines.contains(t)) {
                late.add(t);
            }
        }
        if (!late.isEmpty()) {
            temporal.lockTimelines(kbId, late);
            timelines.addAll(late);
        }
        return new LockedCited(reread, timelines);
    }

    private static ObjectNode processPayload(UUID documentId) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("document_id", documentId.toString());
        return payload;
    }
}
