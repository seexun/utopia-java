package tech.seexun.jknow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 闸门：空 PG 重放 V0000 sys baseline。
 * 镜像用 pgvector/pgvector:pg16——P1 起 V0001 迁移依赖 vector 扩展，提前统一镜像。
 * 仿 utopia-java MigrationTests 模式（整类共用一个容器：首测重放，次测幂等）。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationTests {

    private static final int MIGRATION_COUNT = 1;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static int queryInt(String sql) throws SQLException {
        try (var conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "计数查询应返回一行: " + sql);
            return rs.getInt(1);
        }
    }

    @Test
    @Order(1)
    void replayV0000CreatesAllTables() throws SQLException {
        var result = flyway().migrate();
        assertEquals(MIGRATION_COUNT, result.migrationsExecuted, "应执行 V0000 一个迁移");
        assertEquals(31, queryInt(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
                        + " AND table_name <> 'flyway_schema_history'"),
                "public schema 应有 31 张表（20 sys/gen + 11 qrtz，不含 flyway_schema_history）");
        assertEquals("admin", queryInt2Str("select user_name from sys_user where user_id=1"));
    }

    @Test
    @Order(2)
    void secondMigrateIsIdempotent() {
        var result = flyway().migrate();
        assertEquals(0, result.migrationsExecuted, "第二次 migrate 不应执行任何迁移");
    }

    private static String queryInt2Str(String sql) throws SQLException {
        try (var conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
