package tech.seexun.jknow.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.IndexChunkRow;
import tech.seexun.jknow.store.mapper.DocumentsMapper;

/**
 * 分块索引器（M2 任务 5 起<b>增量为主、重建为辅</b>）：写路径的
 * {@link #reindexDocument}/{@link #deleteDocument} 承担日常同步（process_document 的
 * indexing 步 / 墓碑删除 / 恢复），本类的重建与对账只剩<b>显式入口</b>——M1 检索路径的
 * ensureFresh 每查一次 count 对账已按回收承诺拆除（SearchService 只查不账）。
 *
 * <p>入口三处（报告记录）：
 * <ol>
 *   <li><b>写路径增量（M2 主干）</b>：ProcessDocumentJob 的 indexing 步（删旧+插新+refresh）、
 *       DocumentsWriteController 的删除（deleteDocument）与恢复（reindexDocument）；</li>
 *   <li>启动对账：utopia-server 的 SearchIndexerRunner（reindex_if_empty 语义——索引空
 *       而库里有货则全量重建+清孤儿；<b>默认关</b>，多库共享一个 ES 的测试形态下孤儿清理
 *       是破坏性的，见其注释）；ensureFresh 在这里作「显式对账入口」保留；</li>
 *   <li>手动触发：{@code java -jar utopia-server.jar --reindex}（删了再建的强制全量）。</li>
 * </ol>
 *
 * <p>对账的已知边界（M1 自审留痕）：计数相等但内容不同（同数换块）检不出来——增量索引
 * 后该盲区不再由对账兜（写路径删旧插新天然覆盖），仅存量漂移靠 {@code --reindex} 手动重建。
 *
 * <p>并发：每 KB 一把读写锁——重建持写锁，检索读索引持读锁（{@link #searchUnderReadLock}）；
 * 写锁可重入（ensureFresh 内联重建）。重建幂等（删索引再建）。
 */
@Service
public class ChunkIndexer {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexer.class);

    private final DocumentsMapper documents;
    private final EsChunkIndex index;
    private final Map<UUID, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public ChunkIndexer(DocumentsMapper documents, EsChunkIndex index) {
        this.documents = documents;
        this.index = index;
    }

    private ReentrantReadWriteLock lockFor(UUID kbId) {
        return locks.computeIfAbsent(kbId, k -> new ReentrantReadWriteLock());
    }

    /** 重建一个库的索引：删索引再建（幂等），空库=只删不建。 */
    public void rebuildKb(UUID kbId) throws IOException {
        ReentrantReadWriteLock.WriteLock lock = lockFor(kbId).writeLock();
        lock.lock();
        try {
            rebuildLocked(kbId);
        } finally {
            lock.unlock();
        }
    }

    private void rebuildLocked(UUID kbId) throws IOException {
        index.delete(kbId);
        List<EsChunkIndex.IndexEntry> entries = new ArrayList<>();
        for (IndexChunkRow row : documents.selectKbChunksForIndex(kbId)) {
            entries.add(new EsChunkIndex.IndexEntry(row.id(), row.documentId(), row.text()));
        }
        if (entries.isEmpty()) {
            return;
        }
        index.create(kbId);
        index.bulk(kbId, entries);
        log.info("KB {} 索引重建完成：{} 条分块", kbId, entries.size());
    }

    /** 全量重建所有有现行分块的库（手动 --reindex / 启动 reindex_if_empty 路径）。 */
    public int rebuildAll() throws IOException {
        Set<UUID> kbIds = kbIdsWithLiveChunks();
        for (UUID kbId : kbIds) {
            rebuildKb(kbId);
        }
        return kbIds.size();
    }

    /**
     * 单库对账（<b>显式入口</b>——M2 任务 5 从检索路径撤下，只剩启动对账/手动触发走）：
     * 库里现行分块数 == 索引文档数则跳过，否则重建。
     * 库里无现行分块（db=0）<b>不删索引直接返回</b>——检索的最终口径是取块 SQL 的现行性过滤
     * （superseded/deleted），陈旧索引多召回的 id 会被 SQL 滤成空，删索引不是正确性必需；
     * 而「删」在多库共享一个 ES 的形态下（@SpringBootTest 的 Testcontainers 空库 + 开发库
     * 同指一个 ES）是破坏性的——空库会把别人建的同名索引当孤儿删掉。孤儿清理只在
     * 显式开启的启动对账/手动 --reindex 里做（见 SearchIndexerRunner）。
     * ES 不可达向上抛 IOException。
     */
    public void ensureFresh(UUID kbId) throws IOException {
        ReentrantReadWriteLock.WriteLock lock = lockFor(kbId).writeLock();
        lock.lock();
        try {
            long db = documents.countKbLiveChunks(kbId);
            if (db == 0) {
                return;
            }
            if (index.count(kbId) == db) {
                return;
            }
            rebuildLocked(kbId);
        } finally {
            lock.unlock();
        }
    }

    /** 启动对账全体有货的库（ensureFresh 语义的批量形）。 */
    public void ensureAllFresh() throws IOException {
        for (UUID kbId : kbIdsWithLiveChunks()) {
            ensureFresh(kbId);
        }
    }

    /** 索引文档总数（启动 reindex_if_empty 的「索引是不是空的」判定）。 */
    public long totalIndexed() throws IOException {
        long total = 0;
        for (UUID kbId : index.indexedKbs()) {
            total += index.count(kbId);
        }
        return total;
    }

    /**
     * 清孤儿索引：utopia-kb-* 里已无现行分块（库级联删了/清空了）的删掉——剧本每轮新建库
     * 又随工作区级联删除，不清会累积。只在显式开启的启动对账/手动 --reindex 里调用
     * （默认关：多库共享一个 ES 时，别人的空库视角会把本库索引当孤儿，见类注释入口节）。
     */
    public int pruneOrphans() throws IOException {
        int pruned = 0;
        for (UUID kbId : index.indexedKbs()) {
            if (documents.countKbLiveChunks(kbId) == 0) {
                index.delete(kbId);
                pruned++;
            }
        }
        return pruned;
    }

    /** 检索读通道：读锁下查 ES（重建进行中等待，类注释并发节）。 */
    public List<String> searchUnderReadLock(UUID kbId, String query, int limit) throws IOException {
        lockFor(kbId).readLock().lock();
        try {
            return index.search(kbId, query, limit);
        } finally {
            lockFor(kbId).readLock().unlock();
        }
    }

    /**
     * 单文档条目删除（M2 写路径增量；对等 documents_routes.rs:452-457 墓碑删除路由层的
     * spawn_blocking(search.delete_document)）。与整库重建互斥（写锁），索引不存在=无事。
     */
    public void deleteDocument(UUID kbId, UUID documentId) throws IOException {
        lockFor(kbId).writeLock().lock();
        try {
            index.deleteDocument(kbId, documentId);
        } finally {
            lockFor(kbId).writeLock().unlock();
        }
    }

    /**
     * 单文档条目重建（M2 写路径增量；对等 documents_routes.rs:545-559 reindex——
     * 墓碑恢复后「分块正文一直都在，只是重写一遍索引条目」）。
     */
    public void reindexDocument(UUID kbId, UUID documentId,
                                List<EsChunkIndex.IndexEntry> entries) throws IOException {
        lockFor(kbId).writeLock().lock();
        try {
            index.reindexDocument(kbId, documentId, entries);
        } finally {
            lockFor(kbId).writeLock().unlock();
        }
    }

    private Set<UUID> kbIdsWithLiveChunks() {
        // all_chunks_for_index 已全量读行——去重即库集合，不另造 SQL
        Set<UUID> kbIds = new LinkedHashSet<>();
        for (IndexChunkRow row : documents.selectAllChunksForIndex()) {
            kbIds.add(row.kbId());
        }
        return kbIds;
    }
}
