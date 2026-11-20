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
 * P1 闸门：空 PG 重放全链 V0000-V0105（106 个迁移）。
 * 镜像用 pgvector/pgvector:pg16——V0001 起业务迁移依赖 vector 扩展，与 compose 本地库同源。
 * 仿 utopia-java MigrationTests 模式（整类共用一个容器：首测重放，次测幂等）。
 * V0001-V0099 逐字拷自 utopia-server（V0090-V0110 顺位改号为 V0081-V0099，内容字节一致）；
 * V0100 菜单种子、V0101 用户重绑（sys_user.utopia_uuid 桥接列 + FK 改指 + DROP users）、
 * V0102 菜单开启（visible='0' + C 页 component 统一指 kb-entry 入口组件）、
 * V0103 菜单三分组重排（工作台/知识管理/配置对齐原程序 IA；账目 85+25-11+10=109，
 * -1 若依官网 -8 旧目录/C -2 工作空间 F +10 新 M/C（2120 图谱保留改造不计新增）；F perms 一字不动仅重挂）、
 * V0104 系统组后移（T2 走查发现三组与系统组 order 同为 1/2/3、PG 稳定排序按 menu_id 决胜致侧栏交错；
 * 系统管理/监控/工具 order+3→4/5/6，行数零变动）与
 * V0105 普通角色知识菜单授权+演示访客绑定（补回工作空间 perms 载体 F 2111/2112 挂 2200 下、
 * 区间 27 行整体授 role 2、9999→2；用户本体由 seed-demo 造）与
 * V0106 演示访客只读角色（role 3 demo 只授知识区间 27 行、系统菜单零授予、
 * 9999 解绑 2 改绑 3、sys_role_menu 孤儿行通用清——V0103 删菜单未清关联遗留 (2,4)）
 * 为本工程新写。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationTests {

    /** V0000 + 99（源拷） + V0100 + V0101 + V0102 + V0103 + V0104 + V0105 + V0106 */
    private static final int MIGRATION_COUNT = 107;

    /** V0105 后 2100-2299 区间行数：3 M（2200/2210/2220）+ 8 C（2120 保留 + 2202/2203/2211/2212/2213/2214/2221）
     *  + 16 F（V0103 退役的工作空间 2111/2112 由 V0105 补回为 perms 载体行挂 2200 下——
     *  @PreAuthorize 仍引用 knowledge:workspace:list/edit，非 admin 缺此即 workspaces 403）= 27 */
    private static final int KNOWLEDGE_MENU_COUNT = 27;

    /** 31（V0000：20 sys/gen + 11 qrtz）+ 80（V0001-V0099 业务表）+ 1 视图 derivation_premises
     *  - 1（V0101 DROP users）；information_schema.tables 含普通视图 */
    private static final int TABLE_COUNT = 111;

    /** V0000 种子 85 行 + V0100 新增 25 行 + V0103 净变动 -1（删 11：官网 1 + 旧目录/C 8 + 工作空间 F 2；
     *  增 10：新 M 3 + 新 C 7——2120 图谱为保留 UPDATE 不计新增）+ V0105 补回 2（工作空间
     *  perms 载体 F）= 111 */
    private static final int MENU_COUNT = 111;

    /** V0100 F 按钮 16 项 perms 原集合；V0103 曾删除工作空间 2 行（workspace:list/edit），
     *  V0105 补回为 perms 载体行（@PreAuthorize 面仍引用）——区间 16 行 F 应逐项取自此集合 */
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
        assertEquals(MIGRATION_COUNT, result.migrationsExecuted, "应执行 V0000-V0106 共 107 个迁移");
        assertEquals(TABLE_COUNT, queryInt(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
                        + " AND table_name <> 'flyway_schema_history'"),
                "public schema 应有 111 张表（31 sys/qrtz + 80 业务表 + 1 视图 - 1 已删 users，不含 flyway_schema_history）");
        assertEquals("admin", queryInt2Str("select user_name from sys_user where user_id=1"));
        assertEquals(MENU_COUNT, queryInt("select count(*) from sys_menu"),
                "sys_menu 应为 111 行（V0000 种子 85 + V0100 菜单 25 + V0103 净变动 -1 + V0105 补回 2）");
        assertEquals(3, queryInt("select count(*) from sys_job where invoke_target like 'jknowTask.%'"),
                "sys_job 3 个任务 invoke_target 应全部为 jknowTask.* 前缀（钉住任务 2 改名联动）");
        // —— V0103 三分组重排断言：2100-2299 区间 = 3 M + 8 C（2120 保留+新 7）+ 16 F = 27（V0105 补回 2）——
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2299"),
                "知识菜单（V0105 后 2100-2299 区间）应为 27 行（3 M + 8 C + 16 F——含补回的工作空间载体 2）");
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_menu where menu_id between 2100 and 2299 and visible = '0'"),
                "知识菜单 27 行应全部 visible='0'（V0102 开启 + V0103 新增行直接显示 + V0105 补回行同口径）");
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
        // 区间 27 行全量 case（含 3 M 的 parent=0）；任何一行 parent 与期望不符即计数>0 变红
        //（防错挂：如 2211 误挂 2200、2141 误挂 2211 这类计数面全对但挂边错的回归）。
        // 期望：M 2200/2210/2220→0；C 2120→2200, 2202/2203→2200, 2211/2212/2213/2214→2210, 2221→2220；
        //      F 2111/2112→2200（V0105 补回的工作空间 perms 载体行）,
        //         2121/2122→2120, 2131/2132→2213, 2141/2142→2202, 2151/2152→2203,
        //         2161/2162→2211, 2171/2172→2212, 2181/2182→2221。
        assertEquals(0, queryInt(
                "select count(*) from sys_menu m where m.menu_id between 2100 and 2299"
                        + " and m.parent_id <> (case m.menu_id"
                        + " when 2200 then 0 when 2210 then 0 when 2220 then 0"
                        + " when 2120 then 2200 when 2202 then 2200 when 2203 then 2200"
                        + " when 2211 then 2210 when 2212 then 2210 when 2213 then 2210 when 2214 then 2210"
                        + " when 2221 then 2220"
                        + " when 2111 then 2200 when 2112 then 2200"
                        + " when 2121 then 2120 when 2122 then 2120"
                        + " when 2131 then 2213 when 2132 then 2213"
                        + " when 2141 then 2202 when 2142 then 2202"
                        + " when 2151 then 2203 when 2152 then 2203"
                        + " when 2161 then 2211 when 2162 then 2211"
                        + " when 2171 then 2212 when 2172 then 2212"
                        + " when 2181 then 2221 when 2182 then 2221 end)"),
                "树形挂边逐行校验：区间 27 行 parent 应与期望映射完全一致（错挂行数应为 0）");
        assertEquals(0, queryInt(
                "select count(*) from sys_menu m where m.parent_id <> 0"
                        + " and not exists (select 1 from sys_menu p where p.menu_id = m.parent_id)"),
                "sys_menu 不应存在孤儿行（parent 指向不存在的菜单）");
        // F perms 面：V0105 补回工作空间载体 2 行后回到 16 行，perms 值应逐项取自 V0100 原 16 项集合
        //（permission 值面零变动；V0103 曾仅剩 14 行——@PreAuthorize 面引用 knowledge:workspace:*
        //  导致非 admin workspaces 403，V0105 补回收口）
        var fPerms = queryStrings(
                "select perms from sys_menu where menu_type = 'F' and perms like 'knowledge:%' order by perms");
        assertEquals(16, fPerms.size(), "V0105 补回工作空间载体 2 行后菜单内 knowledge:* F 行应为 16");
        assertEquals(16, fPerms.stream().distinct().count(), "16 行 F perms 应无重复（list/edit 成对）");
        assertTrue(fPerms.stream().allMatch(KNOWLEDGE_F_PERMS_V0100::contains),
                "剩余 16 行 F perms 应逐项取自 V0100 原 16 项集合（F 权限值一字不动）");
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
        // —— V0105 授权断言：role 2 补齐知识区间全量 + 演示访客绑定 ——
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_role_menu where role_id = 2 and menu_id between 2100 and 2299"),
                "role 2 应持有知识区间全量 27 行授权（3 分组 M + 8 C + 16 F——非 admin 侧栏三分组可见的前提；"
                        + "V0106 孤儿清/改绑均不动 role 2 此 27 行——V0105 不回退）");
        assertEquals(0, queryInt(
                "select count(*) from sys_menu m where m.menu_id between 2100 and 2299"
                        + " and not exists (select 1 from sys_role_menu rm"
                        + " where rm.role_id = 2 and rm.menu_id = m.menu_id)"),
                "知识区间不应存在 role 2 未授权的菜单（逐行双向钉死，防部分授权）");
        assertEquals(0, queryInt(
                "select count(*) from sys_role_menu where role_id not in (2, 3) and menu_id between 2100 and 2299"),
                "知识区间不应授权给 role 2/role 3 以外的角色（admin 走超级管理员直通，不需关联行；"
                        + "V0106 起 role 3 同持区间——demo 只读角色与 role 2 同口径）");
        assertEquals(2, queryInt(
                "select count(*) from sys_menu where menu_id in (2111, 2112)"
                        + " and menu_type = 'F' and parent_id = 2200 and visible = '0' and status = '0'"),
                "V0105 补回的工作空间 perms 载体行 2111/2112 应为 F 型挂 2200 下、显示且正常"
                        + "（F 不进侧栏导航，仅向权限集合贡献 knowledge:workspace:list/edit）");
        assertEquals(111, queryInt(
                "select count(*) from sys_role_menu where role_id = 2"),
                "role 2 关联总数应为 111（V0000 基线 85 系统菜单 + V0105 知识区间 27 = 112，"
                        + "V0106 孤儿行通用清去 V0103 删菜单遗留的 (2,4) 若依官网关联 = 111）");
        // —— V0106 演示访客只读角色断言群（安全核心：demo 侧栏只见三分组）——
        assertEquals("demo", queryInt2Str(
                "select role_key from sys_role where role_id = 3"),
                "role 3 应存在且 role_key=demo（演示访客只读角色）");
        assertEquals(1, queryInt(
                "select count(*) from sys_role where role_id = 3 and status = '0' and del_flag = '0'"),
                "role 3 应为正常启用状态（status='0'、del_flag='0'）");
        assertEquals(KNOWLEDGE_MENU_COUNT, queryInt(
                "select count(*) from sys_role_menu where role_id = 3 and menu_id between 2100 and 2299"),
                "role 3 应持有知识区间全量 27 行授权（与 V0105 的 role 2 同口径——三分组 M + 8 C + 16 F，"
                        + "图谱/检索/对话全链鉴权面放行）");
        assertEquals(0, queryInt(
                "select count(*) from sys_menu m where m.menu_id between 2100 and 2299"
                        + " and not exists (select 1 from sys_role_menu rm"
                        + " where rm.role_id = 3 and rm.menu_id = m.menu_id)"),
                "知识区间不应存在 role 3 未授权的菜单（逐行双向钉死，防部分授权断链）");
        assertEquals(0, queryInt(
                "select count(*) from sys_role_menu where role_id = 3 and menu_id < 2100"),
                "role 3 系统菜单授权必须为 0（安全核心断言——demo 登录侧栏不得见系统管理/监控/工具）");
        assertEquals(1, queryInt(
                "select count(*) from sys_user_role where user_id = 9999 and role_id = 3"),
                "sys_user_role 应存在 9999→3 绑定（V0106 改绑演示访客 demo——用户本体由 seed-demo.sql §0.5 造，"
                        + "sys_user_role 无外键故空库重放先行建绑定）");
        assertEquals(1, queryInt(
                "select count(*) from sys_user_role where user_id = 9999"),
                "9999 应只绑 role 3 一个角色（V0105 的 2 绑定已解——绑 2 会带出 85 行系统菜单）");
        assertEquals(0, queryInt(
                "select count(*) from sys_role_menu rm"
                        + " where not exists (select 1 from sys_menu m where m.menu_id = rm.menu_id)"),
                "全表 sys_role_menu 不应存在孤儿行（menu_id 指向已删菜单——V0106 通用清后归零）");
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
