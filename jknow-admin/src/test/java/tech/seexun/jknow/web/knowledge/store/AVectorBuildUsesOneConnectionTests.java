package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.VectorIndexService.Target;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;

/**
 * 移植自顶层 tests/a_vector_build_uses_one_connection.rs（1 例）——A build already owns
 * a connection; checking its index must not need another. Rust 用 max_connections(2) 的池、
 * 借走一条模拟并发请求；Java 同法：独占小 Hikari 池（2）+ 借走一条，build 必须在剩下的
 * 那一条连接上自己完成——探状态（statusOn）/删无效索引/CREATE/解锁全绑手里这条连接，
 * 中途再嵌套借一条就会跟自己手里那条互相等（acquire_timeout 2s 拦住这种坏嵌套，不断言
 * 建索引时延）。
 *
 * <p>与 Rust 的等价点：status 断言走目录读（Spring 池的 mapper），与池压力无关——池压力
 * 的性质由 build 的整条路径承担（两次 build 全在小池单连接上跑完）。
 */
class AVectorBuildUsesOneConnectionTests extends StoreTestBase {

    @Autowired
    DocumentsWriteMapper documents;
    @Autowired
    tech.seexun.jknow.store.JobsService jobs;

    @Test
    void aBuildFinishesWithOnlyOneConnectionAvailable() throws Exception {
        HikariDataSource small = new HikariDataSource();
        small.setJdbcUrl(DB_URL);
        small.setUsername(DB_USER);
        small.setPassword(DB_PASS);
        small.setMaximumPoolSize(2);
        small.setConnectionTimeout(TimeUnit.SECONDS.toMillis(2));
        try {
            VectorIndexService service = new VectorIndexService(small, documents, jobs);
            for (Object[] pair : new Object[][] {
                    {Target.CHUNKS, 17}, {Target.ENTITY_PROFILES, 18}}) {
                Target target = (Target) pair[0];
                int dims = (Integer) pair[1];
                service.drop(target, dims);
                // Stand in for another request holding one connection. This makes the
                // pool pressure deterministic, without racing two build tasks.
                try (Connection busy = small.getConnection()) {
                    VectorIndexService.Built built = service.build(target, dims);
                    assertTrue(built.created());
                    assertTrue(Boolean.TRUE.equals(service.status(target, dims)));
                    VectorIndexService.Built again = service.build(target, dims);
                    assertFalse(again.created(), "an existing valid index is reused");
                }
                service.drop(target, dims);
            }
        } finally {
            small.close();
        }
    }
}
