package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.jobs.TerminalFailure;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.ingest.ChunkPiece;
import tech.seexun.jknow.ingest.Chunker;
import tech.seexun.jknow.ingest.DocumentParser;
import tech.seexun.jknow.ingest.Normalizer;
import tech.seexun.jknow.ingest.ParseResult;
import tech.seexun.jknow.ingest.Provenance;
import tech.seexun.jknow.ingest.ReaderKind;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.search.EsChunkIndex;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.SourcesMapper;

/**
 * 摄入管线（逐字对齐旧仓库 crates/utopia-server/src/pipeline.rs:49-255）：
 * pending→parsing（解析矩阵→分块→认领式替换）→NeedsReader 降级（set_needs_reader+Terminal）
 * /Unreadable Terminal→indexing（ES 单文档增量 reindex）→embedding（可选：配了才做，批 16
 * 并发 4）→ready→抽取触发（来源 extracts()==false→graph_status='skipped' 止；否则
 * statements/chat_ready→graph_status='queued'+enqueue extract_document）。
 *
 * <p><b>M2 完成线</b>（m2-recon §7）：到这里文档 status='ready'、分块/ES 索引/（可选）
 * 向量就绪、graph_status∈{queued,skipped}；extract_document 的<b>执行器</b>属 M3
 * （dispatcher 里是 NOOP_STUB）。
 *
 * <p><b>每步幂等</b>：重跑会先清掉旧分块与旧索引条目（replace_chunks 认领 + reindexDocument
 * 先删后插）；读取期间源文档已更新或删除（sha 快照不符）→ 丢弃过期结果，不改写新任务的
 * 索引、状态和抽取队列。
 *
 * <p><b>边界（报告说明）</b>：Rust 在 NeedsReader 且工作区<b>配了</b> OCR/转写服务时交给
 * 读取器读（pipeline.rs:153-180 readers::Ocr/Transcriber）；Java M2 未实现读取器执行器
 * ——NeedsReader 一律降级 set_needs_reader（配上服务后任务 1 的
 * requeue_waiting_for_reader 端点会重新排队，恢复链完整）。
 */
@Component
public class ProcessDocumentJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessDocumentJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 每次送多少条去嵌入（pipeline.rs:38 EMBED_BATCH——与 ontology_index 的 64 不同，
     * 块文本比类标签长得多；注释原文：两个旋钮别在一刀里同时拧）。
     */
    static final int EMBED_BATCH = 16;

    /**
     * 同时在飞的嵌入批数上限（pipeline.rs:47 EMBED_JOBS，#513）：摄入是用户等着看的前台活，
     * 但一次能带来上千个分块，不设上限就把补齐、消解、检索全饿死；4 与补齐相同。
     */
    static final int EMBED_JOBS = 4;

    /** pipeline.rs:49-103 process_document 的失败外壳：降级/终态/可重试三分。 */
    public static final class NeedsReaderError extends RuntimeException {

        private final ReaderKind reader;

        public NeedsReaderError(ReaderKind reader, String reason) {
            super(reason);
            this.reader = reader;
        }

        public ReaderKind reader() {
            return reader;
        }
    }

    /** 读不了的格式（视频、二进制、空文件）换多少次也一样（pipeline.rs:97-99）。 */
    public static final class UnreadableError extends RuntimeException {
        public UnreadableError(String reason) {
            super(reason);
        }
    }

    private final DocumentsService reads;
    private final DocumentsWriteService writes;
    private final DocumentsWriteMapper writeMapper;
    private final tech.seexun.jknow.web.knowledge.ingest.BlobStore blobs;
    private final ChunkIndexer search;
    private final SettingsService settings;
    private final KbMapper kbs;
    private final SourcesMapper alerts;
    private final JobsService jobs;
    private final AppEventBus events;

    public ProcessDocumentJob(DocumentsService reads, DocumentsWriteService writes,
                              DocumentsWriteMapper writeMapper,
                              tech.seexun.jknow.web.knowledge.ingest.BlobStore blobs,
                              ChunkIndexer search, SettingsService settings, KbMapper kbs,
                              SourcesMapper alerts, JobsService jobs, AppEventBus events) {
        this.reads = reads;
        this.writes = writes;
        this.writeMapper = writeMapper;
        this.blobs = blobs;
        this.search = search;
        this.settings = settings;
        this.kbs = kbs;
        this.alerts = alerts;
        this.jobs = jobs;
        this.events = events;
    }

    /** dispatcher 入口：payload 三键之一的 document_id。 */
    public void handle(tech.seexun.jknow.store.entity.Job job) throws Exception {
        UUID documentId = UUID.fromString(job.payload().path("document_id").asText());
        process(documentId);
    }

    /** pipeline.rs:49-103：成功 Ok / NeedsReader 降级挂 Terminal / Unreadable Terminal / 其余可重试。 */
    public void process(UUID documentId) throws Exception {
        try {
            run(documentId);
        } catch (NeedsReaderError e) {
            // 字要靠模型读、而那种模型没配（0040）：降级——文件留着，文档停在 failed 并记下
            // 缺哪一种，报一条库级告警；配上模型会自己重新处理。重试没用：模型不会在两分钟
            // 里自己配上，所以挂 Terminal（pipeline.rs:58-90）
            writes.setNeedsReader(documentId, e.reader().asStr(), e.getMessage());
            DocumentRow doc = reads.get(documentId);
            if (doc != null) {
                observeNeedsReader(doc, e.reader(), e.getMessage());
                events.emitDocument(doc.kbId(), documentId);
            }
            throw new TerminalFailure(e.getMessage(), e);
        } catch (UnreadableError e) {
            writes.setFailed(documentId, e.getMessage());
            emitIfVisible(documentId);
            throw new TerminalFailure(e.getMessage(), e);
        } catch (Exception e) {
            writes.setFailed(documentId, e.toString());
            emitIfVisible(documentId);
            throw e;
        }
    }

    private void emitIfVisible(UUID documentId) {
        DocumentRow doc = reads.get(documentId);
        if (doc != null) {
            events.emitDocument(doc.kbId(), documentId);
        }
    }

    /** pipeline.rs:105-255 run：状态机主体。 */
    private void run(UUID documentId) throws Exception {
        DocumentRow doc = reads.get(documentId);
        if (doc == null) {
            throw new TerminalFailure("document vanished: " + documentId, null);
        }
        // 排队之后被删了（#268）：墓碑不重建分块、不回索引
        if (doc.deletedAt() != null) {
            log.info("跳过已删除文档 {}", documentId);
            return;
        }

        // 1. 解析（CPU 密集）
        writes.setStatus(documentId, "parsing");
        events.emitDocument(doc.kbId(), documentId);
        byte[] bytes = blobs.get(doc.sha256());
        ParseResult parsed = DocumentParser.parse(doc.filename(), bytes);
        KbRow kb = kbs.selectById(doc.kbId());
        LlmSettingsRow workspaceSettings = kb == null ? null : settings.get(kb.workspaceId());
        String kind = sourceKind(doc.sourceId());
        boolean pushedStatements = "statements".equals(kind);

        String text;
        List<ChunkPiece> pieces;
        List<tech.seexun.jknow.ingest.ParseWarning> warnings;
        switch (parsed) {
            case ParseResult.Text t -> {
                warnings = t.warnings();
                // 解析出来的正文可能夹着 NUL（PDF 文本层常见），入库之前剥掉（#611）——
                // 剥必须在算长度、分块之前：之后的 text_len、分块偏移、全文索引、嵌入读的
                // 都是这一份，彼此才对得上
                text = Normalizer.withoutNul(t.text());
                // 推送来的陈述（0054）：载荷就是契约，整份是一块——切开了契约就解析不回来
                if (pushedStatements) {
                    pieces = List.of(new ChunkPiece(0, text, 0,
                            text.codePointCount(0, text.length()), null, Provenance.stated()));
                } else {
                    pieces = Chunker.chunkWithBudget(text, Chunker.BUDGET_TOKENS);
                }
            }
            case ParseResult.NeedsReader nr -> {
                // M2 未带读取器执行器（类注释边界节）——一律降级
                throw new NeedsReaderError(nr.kind(), nr.reason());
            }
            case ParseResult.Unreadable u -> {
                throw new UnreadableError(u.reason());
            }
        }
        int textLen = text.codePointCount(0, text.length());
        List<DocumentsWriteService.ChunkPair> chunkPairs =
                writes.replaceChunksIfCurrent(doc.kbId(), documentId, pieces, doc.sha256());
        if (chunkPairs == null) {
            // 读取期间源文档可能已更新或删除：丢弃过期结果（documents.rs 快照判定），
            // 不再改写新任务的索引、状态和抽取队列
            log.info("丢弃过期读取结果 {}", documentId);
            return;
        }
        int chunkCount = chunkPairs.size();
        // 只有现行版本配告警：这份读已被顶替的话，警告也是旧的（pipeline.rs:198）
        if (!warnings.isEmpty()) {
            observeTruncated(doc, warnings);
        }

        // 2. 全文索引（ES 单文档增量：删旧+插新+refresh）
        writes.setStatus(documentId, "indexing");
        events.emitDocument(doc.kbId(), documentId);
        List<EsChunkIndex.IndexEntry> entries = new ArrayList<>(chunkPairs.size());
        for (DocumentsWriteService.ChunkPair pair : chunkPairs) {
            entries.add(new EsChunkIndex.IndexEntry(pair.id(), documentId, pair.text()));
        }
        search.reindexDocument(doc.kbId(), documentId, entries);

        // 3. embedding（工作区配了 embedding 模型才做；没配也算 ready，先享受 BM25 搜索）
        if (workspaceSettings != null && workspaceSettings.embedReady()) {
            writes.setStatus(documentId, "embedding");
            events.emitDocument(doc.kbId(), documentId);
            embedPending(workspaceSettings, documentId);
        }

        writes.setReady(documentId, textLen, chunkCount);

        // 来源说了不抽取的，到这里为止：可搜、可问，不进图（0035 决定 7——schema 文档
        // 就是这一类）。状态记成 skipped 而不是留在 none：none 在 Library 读作「还没排到」，
        // 而它永远不会排到
        if (!sourceExtracts(doc.sourceId())) {
            writes.setGraphStatus(documentId, "skipped");
            events.emitDocument(doc.kbId(), documentId);
            log.info("文档处理完成（来源不抽取） document={} chunks={}", documentId, chunkCount);
            return;
        }

        // 两段式：索引就绪后，若配置了对话模型则排队图谱抽取（不阻塞可搜可问）。
        // 推送来的陈述不问模型（0054），没配也排
        if (pushedStatements || workspaceSettings != null && workspaceSettings.chatReady()) {
            writes.setGraphStatus(documentId, "queued");
            jobs.enqueue("extract_document",
                    JSON.createObjectNode().put("document_id", documentId.toString()));
        }
        events.emitDocument(doc.kbId(), documentId);
        log.info("文档处理完成 document={} chunks={}", documentId, chunkCount);
    }

    // ---- 来源判定（pipeline.rs:13-32 source_kind / source_extracts） ----

    private String sourceKind(UUID sourceId) {
        if (sourceId == null) {
            return null;
        }
        DocumentsWriteMapper.SourceRef ref = writeMapper.selectSourceRef(sourceId);
        return ref == null ? null : ref.kind();
    }

    /** 没有来源的文档（直接上传、记忆片段）照旧抽。 */
    private boolean sourceExtracts(UUID sourceId) {
        if (sourceId == null) {
            return true;
        }
        DocumentsWriteMapper.SourceRef ref = writeMapper.selectSourceRef(sourceId);
        return ref == null || ref.extracts();
    }

    // ---- embedding（pipeline.rs:362-397 embed_pending 逐字语义） ----

    /**
     * 把这篇文档还没有向量的分块全部嵌完。批与批之间互不依赖（向量按位置配对，只在一批
     * 之内），{@value #EMBED_JOBS} 个批次同时在飞。<b>数量对不上就整批放弃</b>（配对按位置，
     * 少一条全体错位）；<b>任一批失败整篇失败</b>——已写进去的向量留着，重跑只补缺的。
     */
    private void embedPending(LlmSettingsRow settings, UUID documentId) throws Exception {
        List<DocumentsWriteMapper.ChunkText> pending = writes.chunksPendingEmbedding(documentId);
        if (pending.isEmpty()) {
            return;
        }
        EmbedClient client = new EmbedClient(settings.embedBaseUrl(), settings.embedApiKey(),
                settings.embedModel());
        ExecutorService pool = Executors.newFixedThreadPool(EMBED_JOBS, r -> {
            Thread t = new Thread(r, "utopia-embed");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += EMBED_BATCH) {
                List<DocumentsWriteMapper.ChunkText> batch =
                        pending.subList(i, Math.min(i + EMBED_BATCH, pending.size()));
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        List<String> texts = batch.stream().map(DocumentsWriteMapper.ChunkText::text)
                                .toList();
                        List<float[]> vectors = client.embed(texts);
                        List<DocumentsWriteMapper.EmbeddingWrite> writesBatch = new ArrayList<>();
                        for (int k = 0; k < batch.size(); k++) {
                            writesBatch.add(new DocumentsWriteMapper.EmbeddingWrite(batch.get(k).id(),
                                    vectors.get(k)));
                        }
                        writes.setEmbeddings(writesBatch);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            // 任一批失败整篇失败（join 全体，第一个异常上抛）
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 告警（alerting.rs:61-136 observe_document_truncated / observe_document_needs_reader） ----

    /** 解析截断告警（warning 级，editor 起步；detail 形状 alerting.rs:92-100）。 */
    private void observeTruncated(DocumentRow doc,
                                  List<tech.seexun.jknow.ingest.ParseWarning> warnings) {
        ObjectNode detail = JSON.createObjectNode();
        detail.put("name", doc.filename());
        ArrayNode list = detail.putArray("warnings");
        for (tech.seexun.jknow.ingest.ParseWarning w : warnings) {
            ObjectNode item = list.addObject();
            item.put("kind", w.kind());
            item.set("detail", w.detail());
        }
        raise(doc.kbId(), "warning", "document.contents_truncated", "document", doc.id(), detail);
    }

    /** 缺读取模型告警（名字存进告警——文件删了告警还读得懂）。 */
    private void observeNeedsReader(DocumentRow doc, ReaderKind reader, String error) {
        ObjectNode detail = JSON.createObjectNode();
        detail.put("name", doc.filename());
        detail.put("reader", reader.asStr());
        detail.put("error", error);
        raise(doc.kbId(), "warning", "document.needs_reader", "document", doc.id(), detail);
    }

    private void raise(UUID kbId, String severity, String kind, String subjectType, UUID subjectId,
                       JsonNode detail) {
        try {
            alerts.insertAlert(Uuids.nowV7(), kbId, severity, kind, "editor", subjectType,
                    subjectId, detail);
            events.emitAlert();
        } catch (Exception e) {
            log.warn("上报告警失败: {}", e.getMessage());
        }
    }
}
