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
 * P1 闸门：空 PG 重放全链 V0000-V0102（103 个迁移）。
 * 镜像用 pgvector/pgvector:pg16——V0001 起业务迁移依赖 vector 扩展，与 compose 本地库同源。
 * 仿 utopia-java MigrationTests 模式（整类共用一个容器：首测重放，次测幂等）。
 * V0001-V0099 逐字拷自 utopia-server（V0090-V0110 顺位改号为 V0081-V0099，内容字节一致）；
 * V0100 菜单种子、V0101 用户重绑（sys_user.utopia_uuid 桥接列 + FK 改指 + DROP users）、
 * V0102 菜单开启（visible='0' + C 页 component 统一指 kb-entry 入口组件）为本工程新写。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationTests {

    /** V0000 + 99（源拷） + V0100 + V0101 + V0102 */
    private static final int MIGRATION_COUNT = 103;

    /** V0100__knowledge_menu 实际插入行数：1 顶级目录 + 8 个 C 页 + 每页 list/edit 两个 F 按钮 = 25 */
    private static final int KNOWLEDGE_MENU_COUNT = 25;

    /** 31（V0000：20 sys/gen + 11 qrtz）+ 80（V0001-V0099 业务表）+ 1 视图 derivation_premises
     *  - 1（V0101 DROP users）；information_schema.tables 含普通视图 */
    private static final int TABLE_COUNT = 111;

    /** V0000 种子 85 行 + V0100 新增 25 行 */
    private static final int MENU_COUNT = 110;

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
    void replayFullChainCreatesAllTables() throws SQLException {
        var result = flyway().migrate();
        assertEquals(MIGRATION_COUNT, result.migrationsExecuted, "应执行 V0000-V0102 共 103 个迁移");
        assertEquals(TABLE_COUNT, queryInt(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
                        + " AND table_name <> 'flyway_schema_history'"),
                "public schema 应有 111 张表（31 sys/qrtz + 80 业务表 + 1 视图 - 1 已删 users，不含 flyway_schema_history）");
        assertEquals("admin", queryInt2Str("select user_name from sys_user where user_id=1"));
        assertEquals(MENU_COUNT, queryInt("select count(*) from sys_menu"),
                "sys_menu 应为 110 行（V0000 种子 85 + V0100 知识图谱菜单 25）");
        assertEquals(3, queryInt("select count(*) from sys_job where invoke_target like 'jknowTask.%'"),
                "sys_job 3 个任务 invoke_target 应全部为 jknowTask.* 前缀（钉住任务 2 改名联动）");
        // —— V0100 菜单种子断言：2100-2199 区间行数与实际插入一致 ——
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2199"),
                "知识图谱菜单应为 25 行（1 目录 + 8 页 + 16 按钮）");
        // —— V0102 菜单开启断言：25 行全部 visible='0'（显示），C 页 component 统一指 kb-entry ——
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2199 and visible = '0'"),
                "V0102 开启后知识图谱菜单 25 行应全部 visible='0'");
        assertEquals(8, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2199 and menu_type = 'C'"
                        + " and component = 'jknow/knowledge/kb-entry/index'"),
                "8 个 C 页 component 应统一指菜单入口组件 kb-entry（kb 页 kbId 取自路由参数，"
                        + "直连页面在 /knowledge/<页> 上是死页——V0102 校正项）");
        // —— V0101 用户重绑断言 ——
        assertEquals(1, queryInt(
                "select count(*) from information_schema.columns"
                        + " where table_name='sys_user' and column_name='utopia_uuid'"),
                "sys_user.utopia_uuid 桥接列应存在");
        assertEquals(1, queryInt(
                "select count(*) from pg_constraint"
                        + " where conname='memberships_user_id_fkey'"
                        + " and conrelid='memberships'::regclass and confrelid='sys_user'::regclass"),
                "memberships_user_id_fkey 应存在且指向 sys_user（V0101 改指）");
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
