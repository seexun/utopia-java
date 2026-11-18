package tech.seexun.jknow.web.knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.search.EsChunkIndex;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 文档写族端点（对齐旧仓库 api/documents_routes.rs 写面 + graph_routes.rs:486-510 extract +
 * sources_routes.rs:396-425 ingest；M2 任务 3）。路由层职责（Rust 同序）：store 写→
 * ES 索引（删除/重建）→settle_derivations（M3 任务 7 起真身：materialize_inferences
 * 开着才跑派生物化）→审计→SSE 广播（AppEvent 无数据模型，AppEventBus 实播）。
 *
 * <p><b>D9 消除</b>：上传与 kb-ingest 都写 document_versions 版本 1 + 同事务入队
 * （Rust 侧上传路径不写台账——content 500、versions=[] 的已知缺陷；Java 侧已消除，
 * 双打差异走 WAIVERS.md「java-fixes-d9」挂账，参照实例修复后核销）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:ingest:list')")
public class DocumentsWriteController {

    private static final Logger log = LoggerFactory.getLogger(DocumentsWriteController.class);

    private final DocumentsWriteService documents;
    private final DocumentsService reads;
    private final DocumentsWriteMapper writes;
    private final JobsService jobs;
    private final KbAccess rbac;
    private final AuditService audit;
    private final BlobStore blobs;
    private final ChunkIndexer search;
    private final AppEventBus events;
    private final ObjectMapper mapper;
    private final KbMapper kbs;
    private final ReasoningService reasoning;

    public DocumentsWriteController(DocumentsWriteService documents, DocumentsService reads,
                                    DocumentsWriteMapper writes, JobsService jobs, KbAccess rbac,
                                    AuditService audit, BlobStore blobs, ChunkIndexer search,
                                    AppEventBus events, ObjectMapper mapper, KbMapper kbs,
                                    ReasoningService reasoning) {
        this.documents = documents;
        this.reasoning = reasoning;
        this.reads = reads;
        this.writes = writes;
        this.jobs = jobs;
        this.rbac = rbac;
        this.audit = audit;
        this.blobs = blobs;
        this.search = search;
        this.events = events;
        this.mapper = mapper;
        this.kbs = kbs;
    }

    // ---- POST /api/v1/kbs/{kbId}/documents（documents_routes.rs:243-328 批量上传） ----

    public record UploadResponse(List<DocumentRow> created, List<Map<String, String>> skipped) {
    }

    /**
     * 批量上传（multipart，可多文件）。{@code ?source=} 只接受同 KB 且 kind='folder'；
     * 空文件 skip("empty file")；重复内容（同 KB 同 sha256）skip("duplicate content")；
     * 皆空→422 no_files。正文日期识别（#610）只对 txt/md/markdown。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/documents")
    public UploadResponse upload(@PathVariable UUID kbId,
                                 @RequestParam(value = "source", required = false) UUID source,
                                 MultipartHttpServletRequest multipart,
                                 HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        UUID targetSource = null;
        if (source != null) {
            DocumentsWriteMapper.SourceRef src = writes.selectSourceRef(source);
            if (src == null) {
                throw AppException.NOT_FOUND;
            }
            if (!src.kbId().equals(kbId) || !"folder".equals(src.kind())) {
                throw AppException.invalid("upload_needs_folder",
                        "Uploads can only target a folder source in this knowledge base");
            }
            targetSource = source;
        }

        List<DocumentRow> created = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        for (Map.Entry<String, List<MultipartFile>> part : multipart.getMultiFileMap().entrySet()) {
            for (MultipartFile file : part.getValue()) {
                String filename = file.getOriginalFilename();
                if (filename == null) {
                    continue;
                }
                String mime = file.getContentType() == null
                        ? "application/octet-stream" : file.getContentType();
                byte[] bytes;
                try {
                    bytes = file.getBytes();
                } catch (IOException e) {
                    throw AppException.invalidDetail("upload_read_failed",
                            "Failed to read upload", e.toString());
                }
                if (bytes.length == 0) {
                    skipped.add(skip(filename, "empty file"));
                    continue;
                }
                String sha256 = sha256Hex(bytes);
                try {
                    blobs.put(sha256, bytes);
                } catch (IOException e) {
                    throw AppException.other("blob put failed", e);
                }
                try {
                    // D9 消除：版本 1 + 同事务入队（Rust 上传路径的事务外 enqueue 不再现）
                    created.add(documents.createWithVersion(kbId, filename, mime, bytes.length,
                            sha256, targetSource, ContentTime.contentTime(filename, bytes),
                            "content"));
                } catch (AppException e) {
                    if (e.kind() == AppException.Kind.CONFLICT) {
                        skipped.add(skip(filename, "duplicate content"));
                    } else {
                        throw e;
                    }
                }
            }
        }
        if (created.isEmpty() && skipped.isEmpty()) {
            throw AppException.invalid("no_files", "No files received");
        }
        return new UploadResponse(created, skipped);
    }

    // ---- DELETE /api/v1/documents/{id}（documents_routes.rs:442-480 墓碑删除） ----

    public record DeleteResponse(boolean ok, UUID deletionId, int invalidatedFacts) {
    }

    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @DeleteMapping("/api/v1/documents/{id}")
    public DeleteResponse delete(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = requireDoc(id);
        rbac.requireKb(user, doc.kbId(), Role.EDITOR);
        DocumentsWriteService.DeletionReport report = documents.delete(doc.kbId(), id, user.id());
        // 索引删（spawn_blocking delete_document 同位）
        try {
            search.deleteDocument(doc.kbId(), id);
        } catch (IOException e) {
            throw AppException.other("search delete failed", e);
        }
        settleDerivations(doc.kbId());
        audit.record(client(request), doc.kbId(), user.id(), "document.deleted", "document", id, mapper.createObjectNode()
                .put("filename", doc.filename())
                .put("deletion_id", report.deletionId().toString())
                .put("invalidated_facts", report.invalidatedFacts())
                .toString());
        events.emitDocument(doc.kbId(), id);
        return new DeleteResponse(true, report.deletionId(), report.invalidatedFacts());
    }

    // ---- POST /api/v1/documents/{id}/restore（documents_routes.rs:484-506 撤销删除） ----

    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/documents/{id}/restore")
    public Map<String, Object> restore(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow before = requireDoc(id);
        rbac.requireKb(user, before.kbId(), Role.EDITOR);
        DocumentRow doc = documents.restore(before.kbId(), id);
        // 复活的文档回到全文索引：分块正文一直都在，只是重写一遍索引条目
        reindex(doc);
        settleDerivations(doc.kbId());
        audit.record(client(request), doc.kbId(), user.id(), "document.restored", "document", id,
                mapper.createObjectNode().put("filename", doc.filename()).toString());
        events.emitDocument(doc.kbId(), id);
        return Map.of("ok", true);
    }

    // ---- POST /api/v1/documents/{id}/purge（documents_routes.rs:511-542 真删，Admin） ----

    public record PurgeResponse(boolean ok, long chunks, int blobs) {
    }

    /** 真删（#268 下半）：不可撤销，只对已删除的文档开放，库管理员才能按。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/documents/{id}/purge")
    public PurgeResponse purge(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = requireDoc(id);
        rbac.requireKb(user, doc.kbId(), Role.ADMIN);
        DocumentsWriteService.PurgeReport report = documents.purge(doc.kbId(), id);
        for (String sha : report.blobs()) {
            try {
                blobs.delete(sha);
            } catch (IOException e) {
                // 删文件失败只是漏一份孤儿原文（库里已记账 purged）——仅告警
                log.warn("purge: blob left behind document={} sha={} error={}", id, sha,
                        e.toString());
            }
        }
        audit.record(client(request), doc.kbId(), user.id(), "document.purged", "document", id,
                mapper.createObjectNode()
                        .put("filename", doc.filename())
                        .put("chunks", report.chunks())
                        .put("blobs", report.blobs().size())
                        .toString());
        events.emitDocument(doc.kbId(), id);
        return new PurgeResponse(true, report.chunks(), report.blobs().size());
    }

    // ---- POST /api/v1/documents/{id}/reprocess（documents_routes.rs:574-589） ----

    public record ReprocessResponse(long jobId) {
    }

    /** 重新处理（解析器升级/失败重试）：set_status('pending') + 入队（同 sha 重跑，不写版本）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/documents/{id}/reprocess")
    public ReprocessResponse reprocess(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = requireDoc(id);
        rbac.requireKb(user, doc.kbId(), Role.EDITOR);
        documents.setStatus(id, "pending");
        long jobId = jobs.enqueue("process_document",
                mapper.createObjectNode().put("document_id", id.toString()));
        return new ReprocessResponse(jobId);
    }

    // ---- POST /api/v1/documents/{id}/extract（graph_routes.rs:486-510 手动触发抽取） ----

    public record ExtractResponse(long jobId) {
    }

    /** 手动触发抽取（failed 重试/补配模型后补抽）：强制全量，一个事务办完（queue_extraction_one）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/documents/{id}/extract")
    public ExtractResponse extract(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        DocumentRow doc = requireDoc(id);
        rbac.requireKb(user, doc.kbId(), Role.EDITOR);
        // 来源说了不抽取的（schema 文档，0035 决定 7）：说清楚为什么，而不是排一个
        // 流水线到了那一步又跳过的任务
        if (doc.sourceId() != null) {
            DocumentsWriteMapper.SourceRef src = writes.selectSourceRef(doc.sourceId());
            if (src == null) {
                throw AppException.NOT_FOUND;
            }
            if (!src.extracts()) {
                throw AppException.invalid("source_not_extracted",
                        "Documents under this source are searched, not extracted");
            }
        }
        long jobId = documents.queueExtractionOne(id);
        events.emitDocument(doc.kbId(), id);
        return new ExtractResponse(jobId);
    }

    // ---- POST /api/v1/kbs/{kbId}/documents/retry-failed（documents_routes.rs:391-416） ----

    public record RetryFailedResponse(int queued, int found) {
    }

    /**
     * 一键重试这个作用域里全部抽取失败的文档：逐个走 {@code queue_extraction_one}
     * （排队本身有别的动作——解雇在跑的任务、清增量标记，批量 SQL 会留下半截状态）。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/documents/retry-failed")
    public RetryFailedResponse retryFailed(@PathVariable UUID kbId,
                                           @RequestParam(value = "source", required = false)
                                           String source,
                                           HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        List<UUID> ids = documents.failedIds(kbId, DocumentsService.parseScope(source));
        int queued = 0;
        for (UUID id : ids) {
            try {
                documents.queueExtractionOne(id);
                queued++;
            } catch (Exception e) {
                // Rust 逐个 is_ok() 才计数：单篇失败不拖累其余
            }
        }
        if (queued > 0) {
            events.emitDocument(kbId, ids.get(0));
        }
        return new RetryFailedResponse(queued, ids.size());
    }

    // ---- POST /api/v1/kbs/{kbId}/ingest（sources_routes.rs:396-425 JSON 直灌） ----

    public record IngestActionResponse(String action) {
    }

    /**
     * 普通上传语义的 JSON 直灌（无来源、无身份键、不做三路判定）：同内容视为无操作，
     * 其余一律新建（D9 消除：版本 1 + 同事务入队；{@code deleted:true} 的墓碑走 api 来源）。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:ingest:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/ingest")
    public IngestActionResponse ingest(@PathVariable UUID kbId, @RequestBody JsonNode body,
                                       HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        if (body.path("deleted").asBoolean(false)) {
            throw AppException.validation(
                    "tombstones need identity tracking — push to an api source instead");
        }
        String filename = body.path("filename").asText("");
        String content = body.path("content").asText("");
        if (filename.trim().isEmpty() || content.trim().isEmpty()) {
            throw AppException.validation("filename and content are required");
        }
        OffsetDateTime docTime = parseDocTime(body.get("doc_time"));
        String action = ingestUpload(kbId, filename.trim(), content, docTime);
        return new IngestActionResponse(action);
    }

    /** ingest_sources.rs:176-215 ingest_upload 逐字语义（D9 消除后版本 1 随主行落）。 */
    private String ingestUpload(UUID kbId, String filename, String content,
                                OffsetDateTime docTime) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            return "unchanged";
        }
        String sha256 = sha256Hex(bytes);
        try {
            blobs.put(sha256, bytes);
        } catch (IOException e) {
            throw AppException.other("blob put failed", e);
        }
        try {
            DocumentRow doc = documents.createWithVersion(kbId, filename, "text/plain",
                    bytes.length, sha256, null, docTime, "source");
            events.emitDocument(kbId, doc.id());
            return "created";
        } catch (AppException e) {
            if (e.kind() == AppException.Kind.CONFLICT) {
                return "unchanged";
            }
            throw e;
        }
    }

    // ---- 路由层共用的写后动作 ----

    /** documents_routes.rs:544-559 reindex：复活文档的索引条目重写（先删后加，幂等）。 */
    private void reindex(DocumentRow doc) {
        List<EsChunkIndex.IndexEntry> entries = new ArrayList<>();
        for (DocumentsWriteMapper.ChunkText c : documents.chunksInDocument(doc.kbId(), doc.id())) {
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

    private DocumentRow requireDoc(UUID id) {
        DocumentRow doc = reads.get(id);
        if (doc == null) {
            throw AppException.NOT_FOUND;
        }
        return doc;
    }

    private static OffsetDateTime parseDocTime(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw AppException.validation("doc_time must be an RFC 3339 timestamp");
        }
    }

    private static Map<String, String> skip(String filename, String reason) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("filename", filename);
        entry.put("reason", reason);
        return entry;
    }

    /** documents_routes.rs:591-593 hex。 */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }
}
