package tech.seexun.jknow.web.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.search.EsChunkIndex;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 来源条目摄入（逐字对齐旧仓库 ingest_sources.rs:244-369 ingest_item 的三路判定，
 * M2 任务 4）：主判定按逻辑身份（external_key）——同身份同内容=Unchanged、异内容=原地
 * 替换+版本台账；兜底两路——迁移前历史文档按文件名认领（补 key+补记版本 1）、同内容
 * 换路径=Moved（只改身份不重跑管道）；全新=INSERT+版本 1+同事务入队。墓碑复活
 * （身份回来了）走恢复事务原路复活（restore 语义），Rust 同款。
 *
 * <p>锁序铁律沿用：恢复路径 documents.rs:1130 锁 source→document。各判定环各自成
 * 事务（Rust pool 调用序），不把三路合成一个大事务。
 */
@Service
public class SourceIngestService {

    private static final Logger log = LoggerFactory.getLogger(SourceIngestService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** ingest_sources.rs:148-156 IngestAction（route 层 action_str 的字符串形态）。 */
    public enum IngestAction {
        Created("created"),
        Updated("updated"),
        Moved("moved"),
        Unchanged("unchanged"),
        /** 墓碑：标记「不在来源中」（不删文档，删不删由用户在 UI 决定）。 */
        Tombstoned("marked_missing");

        private final String wire;

        IngestAction(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final DocumentsWriteMapper documents;
    private final DocumentsWriteService writes;
    private final ReasoningService reasoning;
    private final BlobStore blobs;
    private final ChunkIndexer search;
    private final AuditService audit;
    private final AppEventBus events;
    private final KbMapper kbs;

    public SourceIngestService(DocumentsWriteMapper documents, DocumentsWriteService writes,
                               BlobStore blobs, ChunkIndexer search, AuditService audit,
                               AppEventBus events, KbMapper kbs, ReasoningService reasoning) {
        this.documents = documents;
        this.writes = writes;
        this.reasoning = reasoning;
        this.blobs = blobs;
        this.search = search;
        this.audit = audit;
        this.events = events;
        this.kbs = kbs;
    }

    /**
     * ingest_sources.rs:244-369 ingest_item 逐字语义。空内容=Unchanged（调用方的
     * 空文件语义），Content 冲突（同库同内容且活着、不同身份）=Unchanged——不伪造
     * 新身份，保留幂等结果。
     */
    public IngestAction ingestItem(UUID kbId, UUID sourceId, String externalKey, String filename,
                                   String mime, byte[] bytes, OffsetDateTime docTime) {
        if (bytes.length == 0) {
            return IngestAction.Unchanged;
        }
        String sha256 = DocumentsWriteController.sha256Hex(bytes);

        // 主判定：逻辑身份；兜底：迁移前的历史文档（无 key）按文件名认领并补 key
        DocumentRow existing = documents.findByExternalKey(sourceId, externalKey);
        if (existing == null) {
            DocumentRow legacy = documents.findLegacyByFilename(sourceId, filename);
            if (legacy != null) {
                documents.adoptExternalKey(legacy.id(), externalKey);
                // 认领时把旧内容记为版本 1（此前没有版本记录）
                documents.recordVersion(Uuids.nowV7(), legacy.id(), legacy.sha256(),
                        legacy.sizeBytes());
                existing = legacy;
            }
        }

        if (existing != null) {
            // 普通来源的失而复得保留上游恢复语义（检索与派生修复都跟），再比内容
            boolean revived = existing.deletedAt() != null;
            if (revived) {
                DocumentRow restored = writes.restore(kbId, existing.id());
                reindex(restored);
                settleDerivations(kbId);
                audit.recordOpt(null, kbId, null, "document.restored", "document", existing.id(),
                        JSON.createObjectNode().put("filename", restored.filename())
                                .put("via", "sync").toString());
            }
            if (sha256.equals(existing.sha256())) {
                if (revived) {
                    events.emitDocument(kbId, existing.id());
                    return IngestAction.Updated;
                }
                return IngestAction.Unchanged;
            }
            // 变更：原地替换，旧版本入 document_versions，并与处理任务一起提交
            putBlob(sha256, bytes);
            writes.replaceContentAndEnqueue(existing.id(), filename, mime, bytes.length, sha256,
                    docTime);
            events.emitDocument(kbId, existing.id());
            return IngestAction.Updated;
        }

        // 同内容出现在新路径：识别为移动/改名，不重跑管道
        DocumentRow moved = documents.findBySourceSha(sourceId, sha256);
        if (moved != null) {
            documents.updateLocation(moved.id(), filename, externalKey);
            events.emitDocument(kbId, moved.id());
            return IngestAction.Moved;
        }

        putBlob(sha256, bytes);
        try {
            DocumentRow doc = writes.createWithVersion(kbId, filename, mime, bytes.length, sha256,
                    sourceId, docTime, "source", externalKey, false);
            events.emitDocument(kbId, doc.id());
            return IngestAction.Created;
        } catch (AppException e) {
            if (e.kind() == AppException.Kind.CONFLICT) {
                // KB 内已有同内容：不伪造新的身份，保留幂等结果
                return IngestAction.Unchanged;
            }
            throw e;
        }
    }

    // ---- missing 台账（documents.rs:718-771 直通；api 墓碑推送/对账用） ----

    /** documents.rs:750-761 显式墓碑。 */
    public int markMissingKeys(UUID sourceId, List<String> keys) {
        return documents.markMissingKeys(sourceId, keys);
    }

    /** documents.rs:737-748 失而复得摘标记。 */
    public void clearMissingKeys(UUID sourceId, List<String> keys) {
        documents.clearMissingKeys(sourceId, keys);
    }

    /** documents.rs:763-771 该来源下已标 missing 的活文档名单（cleanup_missing 用）。 */
    public List<UUID> listMissing(UUID sourceId) {
        return documents.listMissing(sourceId);
    }

    // ---- 恢复后的路由层动作（documents_routes.rs:544-571 同位） ----

    /** documents_routes.rs:544-559 reindex：复活文档的索引条目重写（先删后加，幂等）。 */
    private void reindex(DocumentRow doc) {
        List<EsChunkIndex.IndexEntry> entries = new ArrayList<>();
        for (DocumentsWriteMapper.ChunkText c : writes.chunksInDocument(doc.kbId(), doc.id())) {
            entries.add(new EsChunkIndex.IndexEntry(c.id(), doc.id(), c.text()));
        }
        try {
            search.reindexDocument(doc.kbId(), doc.id(), entries);
        } catch (IOException e) {
            throw AppException.other("search reindex failed", e);
        }
    }

    /**
     * 前提变了就重推一遍，让派生跟上——开关关着的库不推（documents_routes.rs:561-571
     * settle_derivations 逐字语义）。删除、撤销、同步复活三条路共用。M3 任务 7 换真身：
     * reasoning::materialize 的对等物是 {@link ReasoningService#materialize}（任务 6 落的
     * 派生物化——断言边检查+业务规则不动点+六元组对账/派生 stale 作废，单一求解源）；
     * 公理违规/本体缺陷的对账（settle_violations/settle_defects）在 {@link ReasoningService#run}
     * 一侧（materialize_inferences 定时任务与本体编辑钩子触发，recon §4.2 的说明）。
     */
    private void settleDerivations(UUID kbId) {
        KbRow kb = kbs.selectById(kbId);
        // kb 缺行按 NotFound 上抛（kbs.rs:68-73 kbs::get 的口径；审查 M-2）——静默跳过
        // 会吞掉「库没了还来收尾」的错配
        if (kb == null) {
            throw AppException.NOT_FOUND;
        }
        if (kb.materializeInferences()) {
            reasoning.materialize(kbId);
        }
    }

    private void putBlob(String sha256, byte[] bytes) {
        try {
            blobs.put(sha256, bytes);
        } catch (IOException e) {
            throw AppException.other("blob put failed", e);
        }
    }
}
