package tech.seexun.jknow.store;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.ChunkFactView;
import tech.seexun.jknow.store.entity.ChunkFull;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.DocumentVersion;
import tech.seexun.jknow.store.entity.ExtractionDrop;
import tech.seexun.jknow.store.entity.JobStatus;
import tech.seexun.jknow.store.mapper.DocumentsMapper;
import tech.seexun.jknow.store.mapper.DocumentsMapper.DocStats;

/**
 * documents 读族编排（对齐旧仓库 documents.rs:421-514 page 的组装与
 * documents_routes.rs:354-373/379-385 的非 SQL 语义）。
 */
@Service
public class DocumentsService {

    private final DocumentsMapper docs;

    public DocumentsService(DocumentsMapper docs) {
        this.docs = docs;
    }

    /** 一页文档 + 计数（documents.rs:445-513：scope 哨兵化 → 四查 → DocumentPage 组装）。 */
    public DocumentPage page(UUID kbId, String source, String q, String graphStatus,
                             boolean deleted, long limit, long offset) {
        String scope = parseScope(source);
        List<DocumentRow> rows = docs.selectDocPage(kbId, scope, q, graphStatus, deleted, limit, offset);
        long total = docs.countDocPage(kbId, scope, q, graphStatus, deleted);
        DocStats stats = docs.selectDocStats(kbId, scope);
        long deletedTotal = docs.countDeletedDocs(kbId);
        return new DocumentPage(rows, total, stats.ready(), stats.done(),
                stats.extracting(), stats.failed(), deletedTotal);
    }

    /**
     * documents_routes.rs:379-385 parse_scope + documents.rs:445-449 scope 哨兵化：
     * null/空串→"any"（全部）；"none"→"none"（只看没有来源的）；合法 UUID→其文本；
     * 认不出的字符串当「全部」——这个参数来自界面上的一次点击，一次点击不该把整页变成错误。
     * M2 任务 3 起 retry-failed 写路径共用（failed_ids 同一哨兵串语义）。
     */
    public static String parseScope(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "any";
        }
        if ("none".equals(raw)) {
            return "none";
        }
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            return "any";
        }
    }

    public DocumentRow get(UUID id) {
        return docs.selectDocumentById(id);
    }

    public List<DocumentVersion> versions(UUID id) {
        return docs.selectVersions(id);
    }

    public List<ChunkFull> chunksFull(UUID documentId) {
        return docs.selectChunksFull(documentId);
    }

    public List<ChunkFactView> extractions(UUID documentId) {
        return docs.selectExtractions(documentId);
    }

    public List<ExtractionDrop> extractionDrops(UUID kbId) {
        return docs.selectExtractionDrops(kbId);
    }

    public long failedJobs(UUID kbId) {
        return docs.countFailedJobs(kbId);
    }

    /** 任务属于这个库才答，否则 null→404（jobs_routes.rs:57-67）。 */
    public JobStatus jobInKb(UUID kbId, long jobId) {
        return docs.selectJobInKb(kbId, jobId);
    }

    /** models.rs:1672-1684 DocumentPage（docs/total/ready/done/extracting/failed/deleted）。 */
    public record DocumentPage(List<DocumentRow> docs, long total, long ready, long done,
                               long extracting, long failed, long deleted) {
    }
}
