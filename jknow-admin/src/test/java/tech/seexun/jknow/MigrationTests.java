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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 闸门：空 PG 重放全链 V0000-V0103（104 个迁移）。
 * 镜像用 pgvector/pgvector:pg16——V0001 起业务迁移依赖 vector 扩展，与 compose 本地库同源。
 * 仿 utopia-java MigrationTests 模式（整类共用一个容器：首测重放，次测幂等）。
 * V0001-V0099 逐字拷自 utopia-server（V0090-V0110 顺位改号为 V0081-V0099，内容字节一致）；
 * V0100 菜单种子、V0101 用户重绑（sys_user.utopia_uuid 桥接列 + FK 改指 + DROP users）、
 * V0102 菜单开启（visible='0' + C 页 component 统一指 kb-entry 入口组件）、
 * V0103 菜单三分组重排（工作台/知识管理/配置对齐原程序 IA；账目 85+25-11+10=109，
 * -1 若依官网 -8 旧目录/C -2 工作空间 F +10 新 M/C（2120 图谱保留改造不计新增）；F perms 一字不动仅重挂）、
 * V0104 系统组后移（T2 走查发现三组与系统组 order 同为 1/2/3、PG 稳定排序按 menu_id 决胜致侧栏交错；
 * 系统管理/监控/工具 order+3→4/5/6，行数零变动）为本工程新写。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationTests {

    /** V0000 + 99（源拷） + V0100 + V0101 + V0102 + V0103 + V0104 */
    private static final int MIGRATION_COUNT = 105;

    /** V0103 后 2100-2299 区间行数：3 M（2200/2210/2220）+ 8 C（2120 保留 + 2202/2203/2211/2212/2213/2214/2221）
     *  + 14 F（V0100 的 16 行减工作空间 2111/2112 定谳删除）= 25 */
    private static final int KNOWLEDGE_MENU_COUNT = 25;

    /** 31（V0000：20 sys/gen + 11 qrtz）+ 80（V0001-V0099 业务表）+ 1 视图 derivation_premises
     *  - 1（V0101 DROP users）；information_schema.tables 含普通视图 */
    private static final int TABLE_COUNT = 111;

    /** V0000 种子 85 行 + V0100 新增 25 行 + V0103 净变动 -1（删 11：官网 1 + 旧目录/C 8 + 工作空间 F 2；
     *  增 10：新 M 3 + 新 C 7——2120 图谱为保留 UPDATE 不计新增）= 109 */
    private static final int MENU_COUNT = 109;

    /** V0100 F 按钮 16 项 perms 原集合；V0103 删除工作空间 2 行（workspace:list/edit）后
     *  菜单内剩余 14 行 F 的 perms 应逐项取自此集合（权限值面零变动） */
    private static final Set<String> KNOWLEDGE_F_PERMS_V0100 = Set.of(
            "knowledge:workspace:list", "knowledge:workspace:edit",
            "knowledge:graph:list", "knowledge:graph:edit",
            "knowledge:ontology:list", "knowledge:ontology:edit",
            "knowledge:search:list", "knowledge:search:edit",
            "knowledge:chat:list", "knowledge:chat:edit",
            "knowledge:ingest:list", "knowledge:ingest:edit",
            "knowledge:review:list", "knowledge:review:edit",
            "knowledge:settings:list", "knowledge:settings:edit");

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
        assertEquals(MIGRATION_COUNT, result.migrationsExecuted, "应执行 V0000-V0104 共 105 个迁移");
        assertEquals(TABLE_COUNT, queryInt(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
                        + " AND table_name <> 'flyway_schema_history'"),
                "public schema 应有 111 张表（31 sys/qrtz + 80 业务表 + 1 视图 - 1 已删 users，不含 flyway_schema_history）");
        assertEquals("admin", queryInt2Str("select user_name from sys_user where user_id=1"));
        assertEquals(MENU_COUNT, queryInt("select count(*) from sys_menu"),
                "sys_menu 应为 109 行（V0000 种子 85 + V0100 菜单 25 + V0103 净变动 -1：-11 退役 +10 新增）");
        assertEquals(3, queryInt("select count(*) from sys_job where invoke_target like 'jknowTask.%'"),
                "sys_job 3 个任务 invoke_target 应全部为 jknowTask.* 前缀（钉住任务 2 改名联动）");
        // —— V0103 三分组重排断言：2100-2299 区间 = 3 M + 8 C（2120 保留+新 7）+ 14 F = 25 ——
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2299"),
                "知识菜单（V0103 三分组后 2100-2299 区间）应为 25 行（3 M + 8 C + 14 F）");
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2299 and visible = '0'"),
                "知识菜单 25 行应全部 visible='0'（V0102 开启 + V0103 新增行直接显示）");
        assertEquals(0, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2299 and visible = '1'"),
                "区间内不应残留 visible='1' 隐藏行");
        assertEquals(8, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2299 and menu_type = 'C'"
                        + " and component = 'jknow/knowledge/kb-entry/index'"),
                "8 个 C 页（2120 保留改造 + 新 7）component 应统一指菜单入口组件 kb-entry"
                        + "（kb 页 kbId 取自路由参数，kb-entry 解析当前库后跳转——V0102/V0103 一致口径）");
        assertEquals(3, queryInt(
                "select count(*) from sys_menu where menu_id in (2200,2210,2220)"
                        + " and menu_type = 'M' and parent_id = 0 and visible = '0' and status = '0'"),
                "三个分组目录 2200 工作台/2210 知识管理/2220 配置应存在（M、顶级、显示、正常）");
        assertEquals(3, queryInt(
                "select count(*) from sys_menu where (menu_id = 2200 and order_num = 1)"
                        + " or (menu_id = 2210 and order_num = 2) or (menu_id = 2220 and order_num = 3)"),
                "工作台/知识管理/配置 order_num 应为 1/2/3（三组排系统菜单之前）");
        // —— V0104 系统组后移断言（规格"系统组在后"收口；T2 走查发现 V0103 后三组与系统组
        //    order 同为 1/2/3，PG 稳定排序按 menu_id 决胜 → 侧栏交错）——
        assertEquals(3, queryInt(
                "select count(*) from sys_menu where (menu_id = 1 and order_num = 4)"
                        + " or (menu_id = 2 and order_num = 5) or (menu_id = 3 and order_num = 6)"),
                "系统管理/系统监控/系统工具 order_num 应为 4/5/6（V0104 系统组后移）");
        assertEquals(0, queryInt(
                "select count(*) from sys_menu where parent_id = 0 and menu_type = 'M'"
                        + " and order_num <= 3 and menu_id not in (2200, 2210, 2220)"),
                "顶级 M 中 order 1-3 应只有三分组（其余顶级目录（系统组）order 必须 >3——\"系统组在后\"不变式）");
        // —— 树形挂边逐行钉死（审查 Important-1 加固）：C→M 与 F→C 的 per-row parent 映射 ——
        // 区间 25 行全量 case（含 3 M 的 parent=0）；任何一行 parent 与期望不符即计数>0 变红
        //（防错挂：如 2211 误挂 2200、2141 误挂 2211 这类计数面全对但挂边错的回归）。
        // 期望：M 2200/2210/2220→0；C 2120→2200, 2202/2203→2200, 2211/2212/2213/2214→2210, 2221→2220；
        //      F 2121/2122→2120, 2131/2132→2213, 2141/2142→2202, 2151/2152→2203,
        //         2161/2162→2211, 2171/2172→2212, 2181/2182→2221。
        assertEquals(0, queryInt(
                "select count(*) from sys_menu m where m.menu_id between 2100 and 2299"
                        + " and m.parent_id <> (case m.menu_id"
                        + " when 2200 then 0 when 2210 then 0 when 2220 then 0"
                        + " when 2120 then 2200 when 2202 then 2200 when 2203 then 2200"
                        + " when 2211 then 2210 when 2212 then 2210 when 2213 then 2210 when 2214 then 2210"
                        + " when 2221 then 2220"
                        + " when 2121 then 2120 when 2122 then 2120"
                        + " when 2131 then 2213 when 2132 then 2213"
                        + " when 2141 then 2202 when 2142 then 2202"
                        + " when 2151 then 2203 when 2152 then 2203"
                        + " when 2161 then 2211 when 2162 then 2211"
                        + " when 2171 then 2212 when 2172 then 2212"
                        + " when 2181 then 2221 when 2182 then 2221 end)"),
                "树形挂边逐行校验：区间 25 行 parent 应与期望映射完全一致（错挂行数应为 0）");
        assertEquals(0, queryInt(
                "select count(*) from sys_menu m where m.parent_id <> 0"
                        + " and not exists (select 1 from sys_menu p where p.menu_id = m.parent_id)"),
                "sys_menu 不应存在孤儿行（parent 指向不存在的菜单）");
        // F perms 面：工作空间 2 行退役后剩 14 行，perms 值应逐项取自 V0100 原 16 项集合
        //（permission 值面零变动；knowledge:workspace:* 仅菜单按钮行退役，@PreAuthorize 面不受影响）
        var fPerms = queryStrings(
                "select perms from sys_menu where menu_type = 'F' and perms like 'knowledge:%' order by perms");
        assertEquals(14, fPerms.size(), "工作空间 F 2111/2112 退役后菜单内 knowledge:* F 行应剩 14");
        assertEquals(14, fPerms.stream().distinct().count(), "14 行 F perms 应无重复（list/edit 成对）");
        assertTrue(fPerms.stream().allMatch(KNOWLEDGE_F_PERMS_V0100::contains),
                "剩余 14 行 F perms 应逐项取自 V0100 原 16 项集合（F 权限值一字不动）");
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

    private static List<String> queryStrings(String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
