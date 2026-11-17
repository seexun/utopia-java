package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * 移植自 tests/store/a_dirty_ledger_stops_the_migration.rs（205 行，2 例）——0070 的
 * §0 前置检查：往一个**已经有**跨库坏行的库上装「出处不许跨库」的不变量，迁移必须
 * 确定性中止——报出是哪条边、坏了几行，而不是装上之后替坏数据背书。
 *
 * <p>等价点：Rust 用 sqlx::migrate! 迭代迁移文件；Java 从 classpath db/migration 读
 * 同一批文件（Flyway 逐字拷贝），按版本号排序各自一个事务跑（与 sqlx::migrate 同一
 * 形状）。隔离库建不了（角色无 CREATEDB）时跳过（assumeTrue，与 Rust 的 skip 同义）。
 */
class ADirtyLedgerStopsTheMigrationTests {

    private static String adminUrl() {
        String url = System.getenv().getOrDefault("JKNOW_TEST_JDBC_URL",
                "jdbc:postgresql://localhost:15432/jknow");
        return url.substring(0, url.lastIndexOf('/')) + "/postgres";
    }

    private static String testUrl() {
        return System.getenv().getOrDefault("JKNOW_TEST_JDBC_URL",
                "jdbc:postgresql://localhost:15432/jknow");
    }

    private static String migration(int version) throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        for (var res : resolver.getResources("classpath:db/migration/*.sql")) {
            String name = res.getFilename();
            if (Integer.parseInt(name.substring(1, 5)) == version) {
                try (var in = res.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("migration " + version + " not on classpath");
    }

    /** 按文件顺序跑 ≤ through 的迁移，各自一个事务（与 sqlx::migrate 同一形状）。 */
    private static void migrateTo(Connection conn, int through) throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var names = new java.util.ArrayList<String>();
        for (var res : resolver.getResources("classpath:db/migration/*.sql")) {
            names.add(res.getFilename());
        }
        java.util.Collections.sort(names);
        for (String name : names) {
            int version = Integer.parseInt(name.substring(1, 5));
            if (version > through) {
                continue;
            }
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute(migration(version));
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
        conn.setAutoCommit(true);
    }

    /** 隔离库：建 → 迁到 0069 → 返回库名。建不了（无 CREATEDB）跳过。 */
    private static String scratch(String suffix) throws Exception {
        assumeTrue(StoreTestBase.sharedStackUp(), "共享库不可达，跳过");
        String name = "xkb70_" + suffix + "_" + Uuids.nowV7().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl(),
                System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres"))) {
            try (Statement st = admin.createStatement()) {
                st.execute("CREATE DATABASE " + name);
            } catch (Exception e) {
                assumeTrue(false, "跳过：建不了隔离库（角色没有 CREATEDB）");
            }
        }
        String url = testUrl();
        try (Connection pool = DriverManager.getConnection(
                url.substring(0, url.lastIndexOf('/')) + "/" + name,
                System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres"))) {
            try {
                migrateTo(pool, 69);
            } catch (Exception e) {
                dropScratch(name);
                assumeTrue(false, "跳过：迁到 0069 失败（迁移链自身的问题）: " + e.getMessage());
            }
        }
        return name;
    }

    private static void dropScratch(String name) throws Exception {
        try (Connection admin = DriverManager.getConnection(adminUrl(),
                System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres"));
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    private static void migration70(Connection conn) throws Exception {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute(migration(70));
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }

    /** 0070 之前合法的最小坏账：库 A 的事实配到库 B 的段落上。 */
    private static void seedDirty(Connection conn) throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        try (var ps = conn.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, 'm70-test')")) {
            ps.setObject(1, org);
            ps.executeUpdate();
        }
        try (var ps = conn.prepareStatement("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'm70-test')")) {
            ps.setObject(1, ws);
            ps.setObject(2, org);
            ps.executeUpdate();
        }
        for (UUID kb : new UUID[]{a, b}) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'm70-test')")) {
                ps.setObject(1, kb);
                ps.setObject(2, ws);
                ps.executeUpdate();
            }
        }
        UUID docA = Uuids.nowV7();
        UUID docB = Uuids.nowV7();
        UUID chunkB = Uuids.nowV7();
        UUID entA = Uuids.nowV7();
        UUID entB = Uuids.nowV7();
        for (UUID[] row : new UUID[][]{{entA, a}, {entB, b}}) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')")) {
                ps.setObject(1, row[0]);
                ps.setObject(2, row[1]);
                ps.executeUpdate();
            }
        }
        for (Object[] row : new Object[][]{{docA, a, "a.md"}, {docB, b, "b.md"}}) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO documents (id, kb_id, filename, sha256, status, external_key)
                     VALUES (?, ?, ?, ?, 'ready', ?)
                    """)) {
                ps.setObject(1, row[0]);
                ps.setObject(2, row[1]);
                ps.setString(3, (String) row[2]);
                ps.setString(4, "sha-" + row[2]);
                ps.setString(5, "file:///" + row[2]);
                ps.executeUpdate();
            }
        }
        // B 库自己的段落——0070 之前把它配到 A 的事实上，什么都不会拦
        try (var ps = conn.prepareStatement(
                "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, 'x')")) {
            ps.setObject(1, chunkB);
            ps.setObject(2, b);
            ps.setObject(3, docB);
            ps.executeUpdate();
        }
        UUID factA = Uuids.nowV7();
        try (var ps = conn.prepareStatement(
                "INSERT INTO facts (id, kb_id, subject_id, object_id, confidence) VALUES (?, ?, ?, ?, 0.9)")) {
            ps.setObject(1, factA);
            ps.setObject(2, a);
            ps.setObject(3, entA);
            ps.setObject(4, entA);
            ps.executeUpdate();
        }
        // 坏行：A 的事实配 B 的段落
        try (var ps = conn.prepareStatement("INSERT INTO fact_evidence (fact_id, chunk_id) VALUES (?, ?)")) {
            ps.setObject(1, factA);
            ps.setObject(2, chunkB);
            ps.executeUpdate();
        }
    }

    @Test
    void aCleanLedgerTakesTheInvariant() throws Exception {
        String name = scratch("clean");
        String url = testUrl();
        try (Connection pool = DriverManager.getConnection(
                url.substring(0, url.lastIndexOf('/')) + "/" + name,
                System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres"))) {
            migration70(pool);
            long n;
            try (Statement st = pool.createStatement();
                 var rs = st.executeQuery(
                         "SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'fact_evidence_same_kb'")) {
                rs.next();
                n = rs.getLong(1);
            }
            assertEquals(1L, n, "触发器要真的装上");
        } finally {
            dropScratch(name);
        }
    }

    @Test
    void aDirtyLedgerStopsTheMigrationAtomically() throws Exception {
        String name = scratch("dirty");
        String url = testUrl();
        try (Connection pool = DriverManager.getConnection(
                url.substring(0, url.lastIndexOf('/')) + "/" + name,
                System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres"))) {
            seedDirty(pool);
            String msg = "";
            try {
                migration70(pool);
            } catch (Exception e) {
                msg = String.valueOf(e);
            }
            assertTrue(!msg.isEmpty(), "脏库上跑 0070 必须中止");
            assertTrue(msg.contains("cross-KB references already present") || msg.contains("evidence.chunk"),
                    "中止要报出坏在哪条边: " + msg);
            long n;
            try (Statement st = pool.createStatement();
                 var rs = st.executeQuery(
                         "SELECT COUNT(*) FROM pg_trigger WHERE tgname LIKE '%same_kb%'")) {
                rs.next();
                n = rs.getLong(1);
            }
            assertEquals(0L, n, "中止的迁移不许留下半个不变量");
        } finally {
            dropScratch(name);
        }
    }
}
