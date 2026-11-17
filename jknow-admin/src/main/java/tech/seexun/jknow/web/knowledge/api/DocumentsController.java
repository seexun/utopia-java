package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.ingest.BlobStore;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.DocumentsService.DocumentPage;
import tech.seexun.jknow.store.entity.ChunkFactView;
import tech.seexun.jknow.store.entity.ChunkFull;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.DocumentVersion;
import tech.seexun.jknow.store.entity.ExtractionDrop;
import tech.seexun.jknow.store.entity.JobStatus;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.DocumentsMapper;

/**
 * documents 域读族端点（对齐旧仓库 documents_routes.rs 的 GET 面 + jobs_routes.rs
 * failed_in_kb/job_in_kb；M1 任务 5）。上传/删除/恢复/真删/重抽/retry-failed 写路径归 M2
 * （剧本定向 rust）。
 *
 * <p>非 SQL 语义逐字：list 的筛选与钳位（documents_routes.rs:354-373：limit 缺省 15 钳
 * [1,200]、q trim 空当没搜、graph 只滤非空、state=deleted 开墓碑视图）；content 的版本
 * 台账查找（documents_routes.rs:156-225：requested 版本号无行→404，按当前 sha256 查
 * 无行→500「no ledger version」——参照实例 multipart 上传不写台账的 D9 现状即此路）；
 * blob 按 sha256 内容寻址平铺（data/files/{sha256}，BlobStore 同一存储）。
 *
 * <p>PAT（utp_pat_ Bearer）读取面 M1 不支（无剧本覆盖；Rust DocumentReader 的 scope 判定
 * 随 MCP 域任务一并补）。
 */
@RestController
public class DocumentsController {

    /** documents_routes.rs:350 一页缺省 15、钳 [1,200]。 */
    private static final long DOCS_PAGE = 15;

    private final DocumentsService documents;
    private final DocumentsMapper documentsMapper;
    private final KbAccess rbac;
    private final ObjectMapper mapper;
    private final BlobStore blobs;

    public DocumentsController(DocumentsService documents, DocumentsMapper documentsMapper,
                               KbAccess rbac, ObjectMapper mapper, BlobStore blobs) {
        this.documents = documents;
        this.documentsMapper = documentsMapper;
        this.rbac = rbac;
        this.mapper = mapper;
        this.blobs = blobs;
    }

    /** gone() 的 410 固定文案（documents_routes.rs:87）。 */
    private static final String PURGED_MESSAGE = "The document contents have been purged";


    // ---- GET /api/v1/kbs/{kbId}/documents（documents_routes.rs:354-373 list） ----

    @GetMapping("/api/v1/kbs/{kbId}/documents")
    public DocumentPage list(@PathVariable UUID kbId,
                             @RequestParam(required = false) String source,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) String graph,
                             @RequestParam(required = false) String state,
                             @RequestParam(required = false) Long limit,
                             @RequestParam(required = false) Long offset,
                             HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        String needle = q == null || q.trim().isEmpty() ? null : q.trim();
        String graphStatus = graph == null || graph.isEmpty() ? null : graph;
        boolean deleted = "deleted".equals(state);
        long effLimit = limit == null ? DOCS_PAGE : Math.max(1, Math.min(200, limit));
        long effOffset = offset == null ? 0 : Math.max(0, offset);
        return documents.page(kbId, source, needle, graphStatus, deleted, effLimit, effOffset);
    }

    // ---- GET /api/v1/documents/{id}（documents_routes.rs:419-428 detail） ----

    public record DetailResponse(DocumentRow document, List<ChunkFull> chunks) {
    }

    @GetMapping("/api/v1/documents/{id}")
    public DetailResponse detail(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = documents.get(id);
        if (doc == null) {
            throw AppException.NOT_FOUND;
        }
        rbac.requireKb(user, doc.kbId(), Role.VIEWER);
        return new DetailResponse(doc, documents.chunksFull(id));
    }

    // ---- GET /api/v1/documents/{id}/versions（documents_routes.rs:228-240） ----

    public record VersionsResponse(List<DocumentVersion> versions) {
    }

    @GetMapping("/api/v1/documents/{id}/versions")
    public ResponseEntity<?> versions(@PathVariable UUID id,
                                                     HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = documents.get(id);
        if (doc == null) {
            throw AppException.NOT_FOUND;
        }
        rbac.requireKb(user, doc.kbId(), Role.VIEWER);
        if (doc.purgedAt() != null) {
            return gone();
        }
        return ResponseEntity.ok(new VersionsResponse(documents.versions(id)));
    }

    // ---- GET /api/v1/documents/{id}/content（documents_routes.rs:156-225） ----

    /**
     * 服务台账指名保留的原文。行锁（FOR NO KEY UPDATE）与 Rust 同一语义：读快照期间
     * 替换/真删不能挪走将要服务的 blob（documents_routes.rs:171-178 注释原文）。
     */
    @Transactional
    @GetMapping("/api/v1/documents/{id}/content")
    public ResponseEntity<?> content(@PathVariable UUID id,
                                          @RequestParam(required = false) Integer version,
                                          HttpServletRequest request) {
        UserRow user = user(request);
        if (version != null && version < 1) {
            throw AppException.invalid("bad_version", "Version must be 1 or greater");
        }
        DocumentRow doc = documents.get(id);
        if (doc == null) {
            throw AppException.NOT_FOUND;
        }
        rbac.requireKb(user, doc.kbId(), Role.VIEWER);
        if (doc.purgedAt() != null) {
            return gone();
        }
        // 行锁快照（documents_routes.rs:174-178）
        DocumentRow locked = documentsMapper.selectDocumentByIdForUpdate(id);
        if (locked.purgedAt() != null) {
            return gone();
        }
        DocumentVersion v;
        if (version != null) {
            v = documentsMapper.selectVersionByNumber(id, version);
            if (v == null) {
                throw AppException.NOT_FOUND;
            }
        } else {
            v = documentsMapper.selectVersionByCurrentSha(id, locked.sha256());
            if (v == null) {
                throw AppException.other(
                        "document " + id + " has no ledger version for its current digest", null);
            }
        }
        byte[] bytes;
        try {
            // M2 任务 3 起统一走 BlobStore（与上传同一 {data_dir}/files；JKNOW_BLOB_DIR
            // 覆盖兼容在 BlobStore 内）——Java 侧 D9 已消除，本路由对上传文档 200
            bytes = blobs.get(v.sha256());
        } catch (Exception e) {
            bytes = null;
        }
        if (bytes == null) {
            throw AppException.other(
                    "document " + id + " ledger references unavailable blob " + v.sha256(), null);
        }
        if (v.sizeBytes() != bytes.length) {
            throw AppException.other("document " + id + " version " + v.version()
                    + " has an inaccurate ledger size", null);
        }
        HttpHeaders headers = new HttpHeaders();
        MediaType mime = MediaType.parseMediaType(locked.mime());
        headers.setContentType(mime);
        headers.setContentLength(bytes.length);
        headers.set(HttpHeaders.ETAG, "\"" + v.sha256() + "\"");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(locked.filename()));
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // ---- GET /api/v1/documents/{id}/extractions（documents_routes.rs:431-440） ----

    public record ExtractionsResponse(List<ChunkFactView> facts) {
    }

    @GetMapping("/api/v1/documents/{id}/extractions")
    public ExtractionsResponse extractions(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = documents.get(id);
        if (doc == null) {
            throw AppException.NOT_FOUND;
        }
        rbac.requireKb(user, doc.kbId(), Role.VIEWER);
        return new ExtractionsResponse(documents.extractions(id));
    }

    // ---- GET /api/v1/kbs/{kbId}/extraction-drops（documents_routes.rs:733-741） ----

    public record DropsResponse(List<ExtractionDrop> drops) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/extraction-drops")
    public DropsResponse extractionDrops(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        return new DropsResponse(documents.extractionDrops(kbId));
    }

    // ---- GET /api/v1/kbs/{kbId}/jobs/failed（jobs_routes.rs:44-52） ----

    public record FailedResponse(long failed) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/jobs/failed")
    public FailedResponse failedInKb(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        return new FailedResponse(documents.failedJobs(kbId));
    }

    // ---- GET /api/v1/kbs/{kbId}/jobs/{jobId}（jobs_routes.rs:57-67：属于本库才答） ----

    public record JobResponse(JobStatus job) {
    }

    @GetMapping("/api/v1/kbs/{kbId}/jobs/{jobId}")
    public JobResponse jobInKb(@PathVariable UUID kbId, @PathVariable long jobId,
                               HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.VIEWER);
        JobStatus job = documents.jobInKb(kbId, jobId);
        if (job == null) {
            throw AppException.NOT_FOUND;
        }
        return new JobResponse(job);
    }

    /** documents_routes.rs:89-91 gone：410 + {"error"}（真删不可撤销）。 */
    private static ResponseEntity<String> gone() {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("error", PURGED_MESSAGE);
        return ResponseEntity.status(HttpStatus.GONE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }

    // documents_routes.rs:99-114 content_disposition：fallback 折非法字符，filename* 百分号编码
    private static String contentDisposition(String filename) {
        StringBuilder fallback = new StringBuilder();
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            fallback.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' ? c : '_');
        }
        StringBuilder encoded = new StringBuilder();
        byte[] bytes = filename.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            boolean unreserved = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '.' || b == '_';
            if (unreserved) {
                encoded.append((char) b);
            } else {
                encoded.append(String.format("%%%02X", b & 0xFF));
            }
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
