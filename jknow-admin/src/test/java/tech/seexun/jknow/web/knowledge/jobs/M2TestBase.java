package tech.seexun.jknow.web.knowledge.jobs;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * M2 写路径测试基类（Testcontainers 全新库 + Flyway 99 迁移 + Java 全栈）：
 * <b>与共享库双打隔离的原因</b>——共享栈的 Rust 参照实例 worker 正在消费同一张
 * jobs 表（PgListener 即时唤醒），任何排队行都会被它在毫秒级认领走，claim/退避/
 * requeue 断言无从谈起；Testcontainers 侧 worker 恒关（utopia.jobs.worker-enabled
 * 默认 false），任务行只被测试自己动。封印钥匙指向每 JVM 独立临时目录（不落仓库）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class M2TestBase {

    /** 全类共享的单例容器（一个 JVM 一个库，省去逐类 99 迁移的重放）。 */
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    static {
        PG.start();
    }

    static final Path SECRET_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("utopia-m2-secret");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.druid.master.url", PG::getJdbcUrl);
        registry.add("spring.datasource.druid.master.username", PG::getUsername);
        registry.add("spring.datasource.druid.master.password", PG::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("jknow.data-dir", () -> SECRET_DIR.toString());
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected JdbcTemplate jdbc;
}
