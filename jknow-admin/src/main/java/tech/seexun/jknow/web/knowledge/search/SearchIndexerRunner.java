package tech.seexun.jknow.web.knowledge.search;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.search.ChunkIndexer;
import tech.seexun.jknow.store.mapper.DocumentsMapper;

/**
 * 索引对账入口（语义对齐旧仓库 main.rs:140-151 + 340-384 reindex_if_empty——索引空而库里
 * 有货则全量重建；失败只告警不阻断启动：检索退化成「暂时搜不到」好过整个服务起不来）。
 *
 * <p><b>默认不启用</b>（utopia.elasticsearch.reconcile-on-startup 缺省 false）。M1 的索引
 * 新鲜度由检索路径的 ensureFresh 每次对账保证（ChunkIndexer 类注释），启动重建只是锦上添花；
 * 而它包含的孤儿清理（pruneOrphans）在「多个数据库共享一个 ES」的形态下是破坏性的——
 * @SpringBootTest 的 Testcontainers 空库上下文若先跑，会把开发库建的同名索引全数当孤儿删掉
 * （任务 6 实录踩过：三个参照索引被测试 JVM 清掉）。显式开启才动：
 * <ul>
 *   <li>常规启动（配置 {@code utopia.elasticsearch.reconcile-on-startup=true} 或环境
 *       JKNOW_ES_RECONCILE=1）：清孤儿 + 逐库计数对账（不一致才重建）；</li>
 *   <li>手动触发：{@code java -jar utopia-server.jar --reindex} —— 强制全量重建
 *       （删了再建，幂等；ensureFresh 计数对账检不出「同数换块」时的兜底）。</li>
 * </ul>
 */
@Component
public class SearchIndexerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexerRunner.class);

    private final ChunkIndexer indexer;
    private final DocumentsMapper documents;
    private final boolean enabled;
    /** 测试上下文（多个 @SpringBootTest 类）只对账一次的护栏。 */
    private static final AtomicBoolean DONE = new AtomicBoolean();

    public SearchIndexerRunner(ChunkIndexer indexer, DocumentsMapper documents,
                               @Value("${jknow.elasticsearch.reconcile-on-startup:false}")
                               boolean enabled) {
        this.indexer = indexer;
        this.documents = documents;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean force = args.containsOption("reindex");
        if (!force && !enabled) {
            return;
        }
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            int pruned = indexer.pruneOrphans();
            if (pruned > 0) {
                log.info("清掉 {} 个无现行分块的孤儿索引", pruned);
            }
            if (force) {
                int kbs = indexer.rebuildAll();
                log.info("--reindex 手动全量重建完成：{} 个库", kbs);
                return;
            }
            // reindex_if_empty 逐字语义：总数对账（live_chunk_count vs 索引文档总数）
            long live = documents.countLiveChunks();
            if (live == 0) {
                return;
            }
            if (indexer.totalIndexed() == 0) {
                log.info("全文索引是空的，从库里重建：{} 条分块", live);
                indexer.rebuildAll();
            } else {
                indexer.ensureAllFresh();
            }
        } catch (Exception e) {
            // 失败只告警不阻断启动（main.rs:343-347 同语义），下次启动/检索对账再试
            log.warn("启动索引对账失败，检索暂不可用（服务照常启动）：{}", e.getMessage());
        }
    }
}
