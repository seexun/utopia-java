package tech.seexun.jknow.store;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.ingest.Normalizer;
import tech.seexun.jknow.store.mapper.MemoryMapper;

/**
 * Agent/对话记忆：episodes 快速路径（memory.rs 头注）。
 *
 * <p>设计（零新轮子）：记忆空间 = 知识库本身；每条 episode = 隐式 "Memory" 来源下
 * "Memory log" 文档上追加的一个 chunk（文本内嵌事发时间戳行）。于是全链路复用：chunk
 * 进全文/向量索引（记忆可检索）、fact_evidence 指 chunk（记忆事实可溯源到原话）、
 * extracted_at 增量抽取只处理新 episode、事实 valid_from 取事发时间、与既有 functional
 * 事实矛盾时时态引擎自动闭合——「上月喜欢 A 本月改 B」自然变成两段区间。
 * <b>账本纪律：episodes 只追加，永不改写。</b>
 */
@Service
public class MemoryService {

    /** memory.rs:19 MEMORY_SOURCE_KIND：隐式来源的 kind。 */
    public static final String MEMORY_SOURCE_KIND = "memory";

    /** memory.rs:20 MEMORY_DOC_KEY：每 KB 一份的 Memory log 文档 external_key。 */
    private static final String MEMORY_DOC_KEY = "memory:log";

    /** memory.rs:92 事发时间戳的格式（[YYYY-MM-DD HH:MM]）。 */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MemoryMapper memory;
    private final TransactionTemplate tx;

    public MemoryService(MemoryMapper memory, PlatformTransactionManager txManager) {
        this.memory = memory;
        this.tx = new TransactionTemplate(txManager);
    }

    /** memory.rs:21-34 {@code get_or_create_memory_source}：每 KB 的隐式 Memory 来源。 */
    public UUID getOrCreateMemorySource(UUID kbId) {
        UUID existing = memory.selectMemorySource(kbId, MEMORY_SOURCE_KIND);
        if (existing != null) {
            return existing;
        }
        UUID id = Uuids.nowV7();
        memory.insertMemorySource(id, kbId, MEMORY_SOURCE_KIND);
        return id;
    }

    /** memory.rs:36-57 {@code get_or_create_memory_doc}：Memory log 文档（每 KB 一份）。 */
    public UUID getOrCreateMemoryDoc(UUID kbId) {
        UUID existing = memory.selectMemoryDoc(kbId, MEMORY_DOC_KEY);
        if (existing != null) {
            return existing;
        }
        UUID sourceId = getOrCreateMemorySource(kbId);
        UUID id = Uuids.nowV7();
        memory.insertMemoryDoc(id, kbId, sourceId, MEMORY_DOC_KEY);
        return id;
    }

    /**
     * memory.rs:81-121 {@code append_episode}：追加一条 episode。事发时间内嵌进文本
     * 首行，抽取模型据此定 valid_from。
     *
     * <p><b>NUL 字节在入库前剥掉</b>（#665）：chat / MCP 来的 {@code remember} 工具里
     * JSON 解码出来的 {@code \0} 走到这一行——Postgres TEXT 不收 0x00，与文档路径同
     * 一个错误，整段就丢了。先剥再拼前缀：入库的 text 与 char_end 都从 stamped 来，
     * 算的是同一份。
     *
     * @return (documentId, chunkId)
     */
    public Episode appendEpisode(UUID kbId, String text, OffsetDateTime occurredAt) {
        UUID docId = getOrCreateMemoryDoc(kbId);
        String stripped = Normalizer.withoutNul(text);
        // Rust 侧固定按 DateTime<Utc> 格式化（memory.rs append_episode）——先折 UTC 再格式，
        // 调用方传非 UTC offset 时不出偏（审查 M-3）
        String stamped = "[" + occurredAt.withOffsetSameInstant(ZoneOffset.UTC).format(STAMP) + "] "
                + stripped.trim();
        UUID chunkId = Uuids.nowV7();
        tx.executeWithoutResult(status -> {
            memory.insertEpisodeChunk(chunkId, kbId, docId, stamped,
                    stamped.codePointCount(0, stamped.length()));
            memory.bumpMemoryDoc(docId, stamped.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        });
        return new Episode(docId, chunkId);
    }

    /**
     * memory.rs:126-138 is_memory_document 逐字：这篇文档是不是记忆日志——抽取据此决定
     * 事实要不要人点头（0015：记忆是人在对话里特意说的一句话，一次一条、人就在现场）。
     */
    public boolean isMemoryDocument(UUID documentId) {
        return memory.selectIsMemoryDocument(documentId, MEMORY_SOURCE_KIND) != null;
    }

    /** memory.rs:85 append_episode 的返回（doc, chunk）。 */
    public record Episode(UUID documentId, UUID chunkId) {
    }
}
