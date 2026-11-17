package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.jobs.TerminalFailure;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.VectorIndexService.Built;
import tech.seexun.jknow.store.VectorIndexService.Target;
import tech.seexun.jknow.store.entity.Job;

/**
 * {@code build_vector_index} 任务（main.rs:467-499 逐语义；M3 任务 10 落真身，替换
 * JobDispatcher NOOP 桩）：第一次写下某个维度的向量时排的，事务外 CONCURRENTLY 建
 * pgvector 的部分表达式 HNSW 索引（0035 / #512）。任务本体见
 * {@link VectorIndexService#build}（互斥/维度首建/无效索引先删全在那头）。
 *
 * <p><b>维度超过 HNSW 上限的到此为止</b>（Terminal，不烧预算）：重试也建不出来，查询
 * 照常走精确路径。属主连接的取舍（#1120）见 {@link VectorIndexService#buildAsOwner}。
 */
@Component
public class BuildVectorIndexJob {

    private static final Logger log = LoggerFactory.getLogger(BuildVectorIndexJob.class);

    private final VectorIndexService vectorIndexes;
    /** 迁移身份的连接串（JKNOW_MIGRATION_URL）；空 = 与运行身份同一个，用应用池子。 */
    private final String ownerUrl;

    public BuildVectorIndexJob(VectorIndexService vectorIndexes,
                               @Value("${jknow.migration-url:}") String ownerUrl) {
        this.vectorIndexes = vectorIndexes;
        this.ownerUrl = ownerUrl;
    }

    /** main.rs:467-499：载荷 {table, dims}；Validation 失败包 Terminal（重试无意义）。 */
    public void handle(Job job) {
        JsonNode table = job.payload().get("table");
        Target target = table != null && table.isTextual() ? Target.parse(table.asText()) : null;
        if (target == null) {
            throw new IllegalStateException("payload 缺少 table");
        }
        JsonNode dimsNode = job.payload().get("dims");
        if (dimsNode == null || !dimsNode.canConvertToInt()) {
            throw new IllegalStateException("payload 缺少 dims");
        }
        int dims = dimsNode.asInt();
        Built built;
        try {
            built = vectorIndexes.buildAsOwner(ownerUrl, target, dims);
        } catch (AppException e) {
            if (e.kind() == AppException.Kind.VALIDATION) {
                // Validation 档（main.rs:486-491 .context(Terminal)）：维度超 HNSW 上限，
                // 这种失败重试也建不出来
                throw new TerminalFailure(e.getMessage(), e);
            }
            throw e;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("向量索引构建失败: " + e.getMessage(), e);
        }
        log.info("向量索引就绪 index={} created={} seconds={}", built.name(), built.created(),
                String.format("%.1f", built.seconds()));
    }
}
