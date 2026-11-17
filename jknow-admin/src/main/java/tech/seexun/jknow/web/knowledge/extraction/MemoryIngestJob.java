package tech.seexun.jknow.web.knowledge.extraction;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.search.EsChunkIndex;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 记忆摄入 job（pipeline.rs:263-336 memory_ingest 逐语义）：episodes 快速路径的后半程
 * ——新 episode chunk 补 embedding、重建全文索引、触发增量抽取（extracted_at 为空的
 * 新 chunk 才会被抽）。免解析免分块——episode 落库时已是 chunk。
 *
 * <p><b>一句一句嵌</b>，嵌不了的那句留着没有向量、别的照常往下走（#1187）：日志是一篇
 * 越写越长的文档，从前所有没向量的块一起嵌、一处出错整个任务失败——一句嵌不了的记忆从此
 * 挡在每一句新记忆前面，索引不重建、抽取不排队，而 remember 照样回「记下了」。
 */
@Component
public class MemoryIngestJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryIngestJob.class);

    private final DocumentsService documents;
    private final DocumentsWriteService writesService;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final ChunkIndexer search;
    private final JobsService jobs;
    private final AppEventBus events;

    public MemoryIngestJob(DocumentsService documents, DocumentsWriteService writesService,
                          KbMapper kbs, SettingsService settingsService, ChunkIndexer search,
                          JobsService jobs, AppEventBus events) {
        this.documents = documents;
        this.writesService = writesService;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.search = search;
        this.jobs = jobs;
        this.events = events;
    }

    public void handle(Job job) throws Exception {
        UUID documentId = UUID.fromString(job.payload().get("document_id").asText());
        ExtractionJob.Proposer proposer = new ExtractionJob.Proposer(
                job.payload().hasNonNull("proposed_by")
                        ? UUID.fromString(job.payload().get("proposed_by").asText()) : null,
                job.payload().hasNonNull("proposed_token")
                        ? UUID.fromString(job.payload().get("proposed_token").asText()) : null);
        ingest(documentId, proposer);
    }

    /** pipeline.rs:263-314 memory_ingest 主体。 */
    void ingest(UUID documentId, ExtractionJob.Proposer proposer) throws Exception {
        DocumentRow doc = documents.get(documentId);
        if (doc == null) {
            throw new IllegalStateException("document not found: " + documentId);
        }
        if (doc.deletedAt() != null) {
            log.info("document={} 跳过已删除文档", documentId);
            return;
        }
        KbRow kbRow = kbs.selectById(doc.kbId());
        LlmSettingsRow settings = settingsService.get(kbRow.workspaceId());

        // 一句一句嵌：一次一条，没有错位可言，一条失败不连累后来的
        EmbedClient embedder = LlmSupport.embedClient(settings);
        if (embedder != null) {
            embedEach(settings, embedder, documentId);
        }

        List<EsChunkIndex.IndexEntry> entries = new ArrayList<>();
        for (tech.seexun.jknow.store.entity.ChunkFull c : documents.chunksFull(documentId)) {
            entries.add(new EsChunkIndex.IndexEntry(c.id(), documentId, c.text()));
        }
        search.reindexDocument(doc.kbId(), documentId, entries);

        if (settings != null && settings.chatReady()) {
            writesService.setGraphStatus(documentId, "queued");
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("document_id", documentId.toString());
            if (proposer.userId() != null) {
                payload.put("proposed_by", proposer.userId().toString());
            }
            if (proposer.tokenId() != null) {
                payload.put("proposed_token", proposer.tokenId().toString());
            }
            jobs.enqueue("extract_document", payload);
        }
        events.emitDocument(doc.kbId(), documentId);
    }

    /**
     * pipeline.rs:338-365 embed_each：每块单独一次请求，失败的只记日志、留着下次再试。
     * 与 embed_pending 相反的取舍——那边一批错位会把向量写到别人身上，所以整篇失败；
     * 这里一次一条，没有错位可言。
     */
    private void embedEach(LlmSettingsRow settings, EmbedClient client, UUID documentId) {
        for (DocumentsWriteMapper.ChunkText pending : writesService.chunksPendingEmbedding(documentId)) {
            List<float[]> embedded;
            try {
                embedded = client.embed(List.of(pending.text()));
            } catch (Exception e) {
                log.warn("document={} chunk={} 这一句记忆没嵌成，别的照常: {}", documentId,
                        pending.id(), e.getMessage());
                continue;
            }
            if (embedded.size() != 1) {
                log.warn("document={} chunk={} 记忆的向量数量对不上（{}），这一句先不嵌",
                        documentId, pending.id(), embedded.size());
                continue;
            }
            writesService.setEmbeddings(List.of(
                    new DocumentsWriteMapper.EmbeddingWrite(pending.id(), embedded.get(0))));
        }
    }
}
