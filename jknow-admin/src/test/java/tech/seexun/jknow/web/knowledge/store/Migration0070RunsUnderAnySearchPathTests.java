package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.sql.Array;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * 移植自 tests/store/migration_0070_runs_under_any_search_path.rs（1257 行，9 例）——
 * 不变量家族（0070、0091）的 DDL 不许依赖会话的 search_path：CREATE FUNCTION、
 * CREATE TRIGGER … ON、EXECUTE FUNCTION 全部限定到 public.*——pg_restore 会把会话
 * search_path 置空再灌数据，首位被占住的会话也不能把函数建到别的 schema 去。落在
 * 别处的触发器等于没有触发器。
 *
 * <p>三种会话下逐个验证（0070 与 0091 各跑一遍）：正常 / SET LOCAL search_path = '' /
 * 首位摆上同名干扰物（decoy）。完备性守卫的 scratch 库迁到<b>当前链尾</b>，不停在
 * 0070：链停在哪，责任面就停在哪——0073 之后进库的表对停在 0070 的守卫结构性不可见
 * （#901 的盲区就是这么来的）。守卫同时核对 export_provenance_integrity.sql 的每条
 * 扫描分支都对应一条受保护的 catalog 边（M5 任务 1 起该 SQL 是 classpath 资源，运行时
 * 执行的就是守卫读的这一份）。
 *
 * <p>共享栈不可达或角色无 CREATEDB 时跳过（assumeTrue，与 Rust 的 skip 同义）。
 */
class Migration0070RunsUnderAnySearchPathTests {

    // ---- 连接/迁移小工具（ADirtyLedgerStopsTheMigrationTests 同款） ----

    private static String adminUrl() {
        String url = System.getenv().getOrDefault("JKNOW_TEST_JDBC_URL",
                "jdbc:postgresql://localhost:15432/jknow");
        return url.substring(0, url.lastIndexOf('/')) + "/postgres";
    }

    private static String user() {
        return System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres");
    }

    private static String pass() {
        return System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres");
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, user(), pass());
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

    private static List<String> migrationNames() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        List<String> names = new ArrayList<>();
        for (var res : resolver.getResources("classpath:db/migration/*.sql")) {
            names.add(res.getFilename());
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private static int maxVersion() throws Exception {
        int max = 0;
        for (String name : migrationNames()) {
            max = Math.max(max, Integer.parseInt(name.substring(1, 5)));
        }
        return max;
    }

    /** 按文件顺序跑 after &lt; version &lt;= through 的迁移，各自一个事务（sqlx::migrate 同形）。 */
    private static void migrateRange(Connection conn, int after, int through) throws Exception {
        for (String name : migrationNames()) {
            int version = Integer.parseInt(name.substring(1, 5));
            if (version <= after || version > through) {
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

    private static void migrateTo(Connection conn, int through) throws Exception {
        migrateRange(conn, 0, through);
    }

    /** 在 search_path 为 path 的事务里跑 version 那份迁移本体。 */
    private static void migrationUnder(Connection conn, int version, String path) throws Exception {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("SET LOCAL search_path = " + path);
            st.execute(migration(version));
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
        conn.setAutoCommit(true);
    }

    private static void migration70Under(Connection conn, String path) throws Exception {
        migrationUnder(conn, 70, path);
    }

    private record Scratch(String name, Connection conn) {
    }

    private static Scratch scratch(String suffix) throws Exception {
        assumeTrue(StoreTestBase.sharedStackUp(), "共享库不可达，跳过");
        String name = "xkb70sp_" + suffix + "_" + Uuids.nowV7().toString().replace("-", "");
        try (Connection admin = connect(adminUrl()); Statement st = admin.createStatement()) {
            try {
                st.execute("CREATE DATABASE " + name);
            } catch (Exception e) {
                assumeTrue(false, "跳过：建不了隔离库（角色没有 CREATEDB）");
            }
        }
        String url = testUrl();
        Connection pool = connect(url.substring(0, url.lastIndexOf('/')) + "/" + name);
        try {
            migrateTo(pool, 69);
        } catch (Exception e) {
            pool.close();
            dropScratch(name);
            throw e;
        }
        return new Scratch(name, pool);
    }

    private static void dropScratch(String name) throws Exception {
        try (Connection admin = connect(adminUrl()); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    /** 在隔离库上跑 f——不管成功失败，库都清掉再走（测失败的证据不许靠运维去捡烂尾库）。 */
    private static void withScratch(String suffix, Consumer<Connection> f) throws Exception {
        Scratch s = scratch(suffix);
        try {
            f.accept(s.conn());
        } finally {
            s.conn().close();
            dropScratch(s.name());
        }
    }

    /** 完备性守卫的隔离库：scratch 迁到 0069 之后把链上剩下的全部跑完。
     *  P1 任务 7：链尾封顶 V0099（业务迁移族尾）——jknow 的 V0100 菜单种子/V0101
     *  身份重绑是若依侧迁移，依赖 V0000 的 sys_* schema（scratch 库从业务链 V0001
     *  起自举，不含 V0000），且不在 0070/0091 不变量守卫的责任面内。 */
    private static void withFullChain(String suffix, Consumer<Connection> f) throws Exception {
        withScratch(suffix, pool -> {
            try {
                migrateRange(pool, 69, Math.min(maxVersion(), 99));
                f.accept(pool);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 装完 0070 之后把链推进到 V0081（源 0090 改号，T3），再在被测的 search_path 下装 V0082（源 0091）。 */
    private static void finishChainThen91Under(Connection pool, String path) throws Exception {
        migrateRange(pool, 70, 81);
        migrationUnder(pool, 82, path);
    }

    // ---- 装配断言（0070/0091 各一套） ----

    /** 0070 必须完成的装配：10 个触发器函数、26 条复合外键、8 个支撑唯一约束、
     *  4 条同表自指的 DEFERRABLE INITIALLY DEFERRED、22 个触发器（FK 内部触发器不算），
     *  全部落在 public schema。 */
    private static void assertInstalled(Connection pool) throws SQLException {
        try (Statement st = pool.createStatement()) {
            assertEquals(10L, count(st,
                    "SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "WHERE n.nspname = 'public' AND p.proname IN ( "
                            + "'fact_evidence_stays_inside_its_kb', "
                            + "'derivation_premise_stays_inside_its_kb',"
                            + "'qualifier_stays_inside_its_facts_kb', "
                            + "'type_parent_stays_inside_its_kb', "
                            + "'relation_scope_stays_inside_its_kb', "
                            + "'relation_qualifier_stays_inside_the_kb', "
                            + "'rule_condition_refs_stay_inside_the_kb',"
                            + "'kb_ownership_is_not_reassigned', "
                            + "'typed_source_stays_inside_its_kb', "
                            + "'squalifier_stays_inside_its_facts_kb')"),
                    "全部触发器函数都必须落在 public schema");
            assertEquals(26L, count(st,
                    "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' AND conname IN ( "
                            + "'chunks_document_same_kb', "
                            + "'facts_subject_same_kb','facts_object_same_kb',"
                            + "'facts_predicate_same_kb', "
                            + "'facts_supersedes_same_kb','facts_from_statement_same_kb', "
                            + "'derived_facts_subject_same_kb','derived_facts_object_same_kb', "
                            + "'derived_facts_predicate_same_kb','derived_facts_rule_same_kb', "
                            + "'derived_facts_attribute_rule_same_kb', "
                            + "'entities_type_same_kb', "
                            + "'entity_type_disjoint_a_same_kb','entity_type_disjoint_b_same_kb', "
                            + "'relation_types_inverse_same_kb','relation_types_sub_property_same_kb', "
                            + "'rules_predicate_same_kb', "
                            + "'attribute_rules_subject_type_same_kb',"
                            + "'attribute_rules_conclude_type_same_kb', "
                            + "'attribute_rules_conclude_predicate_same_kb', "
                            + "'time_mentions_fact_same_kb','time_mentions_chunk_same_kb', "
                            + "'type_bindings_type_same_kb', "
                            + "'phrase_bindings_subject_type_same_kb',"
                            + "'phrase_bindings_object_type_same_kb', "
                            + "'phrase_bindings_relation_type_same_kb')"),
                    "每条声明式边一个复合外键，一条都不能少");
            assertEquals(8L, count(st,
                    "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'u' AND conname IN ( "
                            + "'documents_kb_id_key','chunks_kb_id_key','entities_kb_id_key', "
                            + "'entity_types_kb_id_key','relation_types_kb_id_key',"
                            + "'facts_kb_id_key', "
                            + "'rules_kb_id_key','attribute_rules_kb_id_key')"),
                    "每个被引表一条 (kb_id, id) 唯一约束");
            assertEquals(4L, count(st,
                    "SELECT COUNT(*) FROM pg_constraint "
                            + "WHERE contype = 'f' AND condeferrable AND condeferred "
                            + "AND conname IN ('facts_supersedes_same_kb', "
                            + "'facts_from_statement_same_kb', "
                            + "'relation_types_inverse_same_kb', "
                            + "'relation_types_sub_property_same_kb')"),
                    "同表自指边的提交边界检查必须在场");
            assertEquals(22L, count(st,
                    "SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ( "
                            + "'fact_evidence_same_kb','fact_derivations_same_kb', "
                            + "'fact_qualifiers_same_kb','entity_type_parents_same_kb', "
                            + "'relation_type_domains_same_kb','relation_type_ranges_same_kb', "
                            + "'relation_type_qualifiers_same_kb',"
                            + "'attribute_rule_conditions_same_kb', "
                            + "'typed_fact_sources_same_kb','statement_qualifiers_same_kb', "
                            + "'facts_keep_their_kb','derived_facts_keep_their_kb',"
                            + "'documents_keep_their_kb', "
                            + "'entities_keep_their_kb','entity_types_keep_their_kb',"
                            + "'relation_types_keep_their_kb', "
                            + "'rules_keep_their_kb','attribute_rules_keep_their_kb', "
                            + "'entity_type_disjoint_keep_their_kb', "
                            + "'time_mentions_keep_their_kb','type_bindings_keep_their_kb', "
                            + "'phrase_bindings_keep_their_kb')"),
                    "父行作证的边与不可过户，一条都不能少");
        }
    }

    /** 0091 必须完成的装配：新触发器函数落 public、13 条新复合键、3 个支撑唯一约束、
     *  5 个 kb 不可过户触发器 + implied_fact_sources 的归属推导触发器。 */
    private static void assert0091Installed(Connection pool) throws SQLException {
        try (Statement st = pool.createStatement()) {
            assertEquals(1L, count(st,
                    "SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "WHERE n.nspname = 'public' "
                            + "AND p.proname = 'implied_source_stays_inside_its_kb'"),
                    "触发器函数必须落在 public schema");
            assertEquals(13L, count(st,
                    "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'f' AND conname IN ( "
                            + "'implication_rules_subject_type_same_kb',"
                            + "'implication_rules_object_type_same_kb', "
                            + "'implication_rules_conclude_property_same_kb', "
                            + "'phrase_readings_entity_same_kb', "
                            + "'attribute_rule_versions_rule_same_kb', "
                            + "'derived_facts_attribute_rule_version_same_kb', "
                            + "'errata_runs_document_same_kb', "
                            + "'errata_actions_run_same_kb','errata_actions_document_same_kb', "
                            + "'errata_actions_fact_same_kb','errata_actions_statement_same_kb', "
                            + "'errata_actions_predicate_same_kb',"
                            + "'errata_actions_new_fact_same_kb')"),
                    "0091 的复合外键要么全在要么全不在");
            assertEquals(3L, count(st,
                    "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'u' AND conname IN ( "
                            + "'implication_rules_kb_id_key','attribute_rule_versions_kb_id_key', "
                            + "'errata_runs_kb_id_key')"),
                    "每个新被引表一条 (kb_id, id) 唯一约束");
            assertEquals(6L, count(st,
                    "SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ( "
                            + "'implied_fact_sources_same_kb', "
                            + "'implication_rules_keep_their_kb','phrase_readings_keep_their_kb', "
                            + "'errata_runs_keep_their_kb','errata_actions_keep_their_kb', "
                            + "'attribute_rule_versions_keep_their_kb')"),
                    "归属推导与不可过户触发器，一个都不能少");
        }
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // =====================================================================
    // 完备性守卫：0070 的覆盖面不靠「数过的约束名/触发器名」维持——从 pg_catalog 现数
    // 责任面里的每一条列级引用边，逐条归类，归不进任何一类就红；登记表里有而 catalog
    // 里没有，也是红。
    // =====================================================================

    /** 0070 的责任面：语义账本的表——导出逐边解析引用的那些（范围声明而非覆盖断言）。 */
    private static final Set<String> LEDGER_TABLES = Set.of(
            // 自带 kb_id 的语义行
            "attribute_rule_versions", "attribute_rules", "chunks", "derived_facts", "documents",
            "entities", "entity_type_disjoint", "entity_types", "errata_actions", "errata_runs",
            "facts", "implication_rules", "name_vectors", "ontology_regression_cases",
            "phrase_bindings", "phrase_readings", "relation_types", "rules", "time_mentions",
            "type_bindings",
            // 行自己没有 kb_id、kb 权威由父行作证的连接行
            "attribute_rule_conditions", "entity_type_parents", "fact_derivations", "fact_evidence",
            "fact_qualifiers", "implied_fact_sources", "relation_type_domains",
            "relation_type_qualifiers", "relation_type_ranges", "statement_qualifiers",
            "typed_fact_sources");

    /** 父行作证表：(表, owner 列, owner 表)。owner 边自动豁免，其余指向带 kb_id 表的边
     *  都必须登记到 TRIGGER_EDGES。 */
    private static final String[][] OWNER_ROWS = {
            {"attribute_rule_conditions", "rule_id", "attribute_rules"},
            {"entity_type_parents", "child_id", "entity_types"},
            {"fact_derivations", "derived_fact_id", "derived_facts"},
            {"fact_evidence", "fact_id", "facts"},
            {"fact_qualifiers", "fact_id", "facts"},
            {"implied_fact_sources", "fact_id", "facts"},
            {"relation_type_domains", "relation_type_id", "relation_types"},
            {"relation_type_qualifiers", "relation_type_id", "relation_types"},
            {"relation_type_ranges", "relation_type_id", "relation_types"},
            {"statement_qualifiers", "fact_id", "facts"},
            {"typed_fact_sources", "fact_id", "facts"},
    };

    /** 触发器边登记：(表, 列, 触发器名)。登记是列级的——「这张表装着触发器」不蕴含
     *  「新加的列被它检查」：除登记外，还核对列落在该触发器的 UPDATE OF 清单里。 */
    private static final String[][] TRIGGER_EDGES = {
            {"attribute_rule_conditions", "predicate_id", "attribute_rule_conditions_same_kb"},
            {"entity_type_parents", "parent_id", "entity_type_parents_same_kb"},
            {"fact_derivations", "premise_derived_id", "fact_derivations_same_kb"},
            {"fact_derivations", "premise_fact_id", "fact_derivations_same_kb"},
            {"fact_evidence", "chunk_id", "fact_evidence_same_kb"},
            {"fact_evidence", "document_id", "fact_evidence_same_kb"},
            {"fact_qualifiers", "entity_id", "fact_qualifiers_same_kb"},
            {"fact_qualifiers", "qualifier_type_id", "fact_qualifiers_same_kb"},
            {"implied_fact_sources", "entity_id", "implied_fact_sources_same_kb"},
            {"implied_fact_sources", "rule_id", "implied_fact_sources_same_kb"},
            {"implied_fact_sources", "statement_id", "implied_fact_sources_same_kb"},
            {"relation_type_domains", "entity_type_id", "relation_type_domains_same_kb"},
            {"relation_type_qualifiers", "qualifier_type_id", "relation_type_qualifiers_same_kb"},
            {"relation_type_ranges", "entity_type_id", "relation_type_ranges_same_kb"},
            {"statement_qualifiers", "entity_id", "statement_qualifiers_same_kb"},
            {"typed_fact_sources", "statement_id", "typed_fact_sources_same_kb"},
    };

    /** 面内但不属同库语义引用的边——一条一句话理由。 */
    private static final String[][] NON_SCOPE = {
            // 采集来源归属：导出从不把这条引用解析成 IRI
            {"documents", "source_id", "ingest attribution, not an export-resolved reference"},
            // 合并簿记：merged_into 非空的行不进导出，这条指针永不进 IRI
            {"entities", "merged_into", "merge bookkeeping — merged rows never reach export"},
    };

    /** pg_catalog 里数出来的一条列级引用边；pairs 是同一约束的全部列对（conkey/confkey
     *  按序数对齐）。 */
    record RefEdge(String srcTable, String srcCol, String tgtTable, String tgtCol, String conname,
                   boolean deferrable, boolean deferred, List<String[]> pairs) {

        String label() {
            return srcTable + "." + srcCol + "→" + tgtTable + "." + tgtCol;
        }
    }

    /** 从 pg_catalog 现数 public schema 的全部外键列对——不读迁移源码。 */
    private static List<RefEdge> catalogEdges(Connection pool) throws SQLException {
        record Row(long oid, String st, String sc, String tt, String tc, String cn, boolean dfr,
                   boolean dfd) {
        }
        String sql = """
                SELECT c.oid::bigint,
                       src.relname, sa.attname, tgt.relname, ta.attname,
                       c.conname, c.condeferrable, c.condeferred
                  FROM pg_constraint c
                  JOIN pg_class src ON src.oid = c.conrelid
                  JOIN pg_namespace sn ON sn.oid = src.relnamespace AND sn.nspname = 'public'
                  JOIN pg_class tgt ON tgt.oid = c.confrelid
                  JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = 'public'
                  JOIN unnest(c.conkey) WITH ORDINALITY AS ck(attnum, ord) ON true
                  JOIN pg_attribute sa ON sa.attrelid = c.conrelid AND sa.attnum = ck.attnum
                  JOIN pg_attribute ta ON ta.attrelid = c.confrelid AND ta.attnum = c.confkey[ck.ord]
                 WHERE c.contype = 'f'
                 ORDER BY c.oid, ck.ord
                """;
        List<Row> rows = new ArrayList<>();
        try (Statement st = pool.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8)));
            }
        }
        Map<Long, List<String[]>> pairs = new HashMap<>();
        for (Row r : rows) {
            pairs.computeIfAbsent(r.oid(), k -> new ArrayList<>())
                    .add(new String[]{r.sc(), r.tc()});
        }
        List<RefEdge> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(new RefEdge(r.st(), r.sc(), r.tt(), r.tc(), r.cn(), r.dfr(), r.dfd(),
                    pairs.getOrDefault(r.oid(), List.of())));
        }
        return out;
    }

    /** 带 kb_id 列的表 = 有库归属的行。 */
    private static Set<String> kbScopedTables(Connection pool) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = pool.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT c.relname FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'public' AND c.relkind = 'r' "
                        + "AND EXISTS (SELECT 1 FROM pg_attribute a "
                        + "WHERE a.attrelid = c.oid AND a.attname = 'kb_id' "
                        + "AND a.attnum > 0 AND NOT a.attisdropped)")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** 用户触发器 -> 它 UPDATE OF 盯着的列名集合。 */
    private static Map<String, Set<String>> triggerWatchLists(Connection pool) throws SQLException {
        record T(String table, String trigger, String[] cols) {
        }
        List<T> rows = new ArrayList<>();
        try (Statement st = pool.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT cls.relname, t.tgname, COALESCE(w.cols, '{}'::text[]) "
                        + "FROM pg_trigger t "
                        + "JOIN pg_class cls ON cls.oid = t.tgrelid "
                        + "JOIN pg_namespace n ON n.oid = cls.relnamespace AND n.nspname = 'public' "
                        + "LEFT JOIN LATERAL (SELECT array_agg(a.attname) AS cols "
                        + "FROM pg_attribute a WHERE a.attrelid = t.tgrelid "
                        + "AND a.attnum = ANY (string_to_array(t.tgattr::text, ' ')::smallint[])"
                        + ") w ON true "
                        + "WHERE NOT t.tgisinternal")) {
            while (rs.next()) {
                Array arr = rs.getArray(3);
                String[] cols = arr == null ? new String[0] : (String[]) arr.getArray();
                // pgjdbc 对 name 类型列（relname/tgname）保留尾部 NUL 填充——拼键前剥掉
                rows.add(new T(stripNamePad(rs.getString(1)),
                        stripNamePad(rs.getString(2)), cols));
            }
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (T t : rows) {
            out.put(t.table() + " " + t.trigger(), new HashSet<>(Arrays.asList(t.cols())));
        }
        return out;
    }

    /** 身上装着 0070 机制的表——用来与 LEDGER_TABLES 互证：机制落在面外、或面内表什么
     *  机制都没有，都算漂移。 */

    /** 剥掉 pg_catalog name 类型列的尾部 NUL 填充。 */
    private static String stripNamePad(String raw) {
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == 0) {
            end--;
        }
        return raw.substring(0, end);
    }

    private static Set<String> mechanismTables(Connection pool) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement st = pool.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT cls.relname FROM pg_constraint c "
                            + "JOIN pg_class cls ON cls.oid = c.conrelid "
                            + "JOIN pg_namespace n ON n.oid = cls.relnamespace "
                            + "AND n.nspname = 'public' "
                            + "WHERE c.conname::text ~ '(_same_kb|_kb_id_key)$'")) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT cls.relname FROM pg_trigger t "
                            + "JOIN pg_class cls ON cls.oid = t.tgrelid "
                            + "JOIN pg_namespace n ON n.oid = cls.relnamespace "
                            + "AND n.nspname = 'public' "
                            + "WHERE NOT t.tgisinternal "
                            + "AND t.tgname::text ~ '(_same_kb|_keep_their_kb)$'")) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** 逐条归类的结果。declarative / triggerCovered / nonScope 之外任何一桶非空，覆盖
     *  就是不完备。 */
    static final class Coverage {
        List<String> declarative = new ArrayList<>();
        List<String> triggerCovered = new ArrayList<>();
        List<String> nonScope = new ArrayList<>();
        TreeSet<EdgeKey> protectedSet = new TreeSet<>();
        List<String> unknown = new ArrayList<>();
        List<String> staleRegistry = new ArrayList<>();
        List<String> unprotectedDirect = new ArrayList<>();
        List<String> unprotectedTrigger = new ArrayList<>();
        List<String> surfaceDrift = new ArrayList<>();
        List<String> preflightMissing = new ArrayList<>();
        List<String> stalePreflight = new ArrayList<>();

        Coverage assertComplete() {
            StringBuilder problems = new StringBuilder();
            dump(problems, "UNKNOWN", unknown);
            dump(problems, "STALE_REGISTRY", staleRegistry);
            dump(problems, "UNPROTECTED_DIRECT", unprotectedDirect);
            dump(problems, "UNPROTECTED_TRIGGER", unprotectedTrigger);
            dump(problems, "SURFACE_DRIFT", surfaceDrift);
            dump(problems, "PREFLIGHT_MISSING", preflightMissing);
            dump(problems, "STALE_PREFLIGHT", stalePreflight);
            // 0109 adds the statement and expected-property references on regression cases.
            if (declarative.size() != 44) {
                problems.append("  DECLARATIVE_EDGES = ").append(declarative.size())
                        .append(" (expected 44)\n");
            }
            if (triggerCovered.size() != 16) {
                problems.append("  TRIGGER_EDGES = ").append(triggerCovered.size())
                        .append(" (expected 16)\n");
            }
            assertTrue(problems.isEmpty(), "catalog-derived coverage is incomplete:\n" + problems);
            return this;
        }

        private static void dump(StringBuilder sb, String name, List<String> xs) {
            for (String x : xs) {
                sb.append("  ").append(name).append(": ").append(x).append('\n');
            }
        }
    }

    /** preflight SQL 里一条结构边标记的四元组。 */
    record EdgeKey(String srcTable, String srcCol, String tgtTable, String tgtCol)
            implements Comparable<EdgeKey> {

        static final Comparator<EdgeKey> ORDER = Comparator.comparing(EdgeKey::srcTable)
                .thenComparing(EdgeKey::srcCol).thenComparing(EdgeKey::tgtTable)
                .thenComparing(EdgeKey::tgtCol);

        @Override
        public int compareTo(EdgeKey o) {
            return ORDER.compare(this, o);
        }

        static EdgeKey parse(String marker) {
            int arrow = marker.indexOf("->");
            if (arrow < 0) {
                return null;
            }
            String[] src = marker.substring(0, arrow).trim().split("\\.");
            String[] tgt = marker.substring(arrow + 2).trim().split("\\.");
            if (src.length != 2 || tgt.length != 2) {
                return null;
            }
            return new EdgeKey(src[0].trim(), src[1].trim(), tgt[0].trim(), tgt[1].trim());
        }
    }

    record PreflightSurface(TreeSet<EdgeKey> edges, Map<EdgeKey, String> labels,
                            Set<String> filters) {
    }

    /** 一段 SQL 文本里的字符串字面量，按出现顺序——每个扫描分支的头两个字面量就是它的
     *  报错 label 与 kind。 */
    static List<String> quotedLiterals(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '\'') {
                i++;
                continue;
            }
            StringBuilder lit = new StringBuilder();
            i++;
            while (i < text.length() && text.charAt(i) != '\'') {
                lit.append(text.charAt(i));
                i++;
            }
            i++;
            out.add(lit.toString());
        }
        return out;
    }

    private static String preflightSql() {
        try (var in = Migration0070RunsUnderAnySearchPathTests.class.getClassLoader()
                .getResourceAsStream("export_provenance_integrity.sql")) {
            if (in == null) {
                throw new IllegalStateException("export_provenance_integrity.sql 不在 classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把 preflight SQL 按 UNION ALL 切成扫描分支，逐条核对标记。 */
    static PreflightSurface preflightSurface() {
        PreflightSurface surface = new PreflightSurface(new TreeSet<>(), new HashMap<>(),
                new TreeSet<>());
        StringBuilder problems = new StringBuilder();
        String[] chunks = preflightSql().split("UNION ALL");
        for (int i = 0; i < chunks.length; i++) {
            String chunk = chunks[i];
            List<String> edgeMarkers = new ArrayList<>();
            List<String> filterMarkers = new ArrayList<>();
            for (String line : chunk.split("\n", -1)) {
                String l = line.trim();
                if (l.startsWith("-- @edge ")) {
                    edgeMarkers.add(l.substring("-- @edge ".length()).trim());
                } else if (l.startsWith("-- @filter ")) {
                    filterMarkers.add(l.substring("-- @filter ".length()).trim());
                }
            }
            List<String> literals = quotedLiterals(chunk);
            // 注释里写到 "UNION ALL" 也会切出一段——那种碎片没有 SELECT，跳过；真分支缺字面量仍然是错
            if (literals.size() < 2) {
                if (chunk.contains("SELECT")) {
                    problems.append("  branch ").append(i)
                            .append(": no 'label'/'kind' literals found\n");
                }
                continue;
            }
            String label = literals.get(0);
            String kind = literals.get(1);
            switch (kind) {
                case "cross_kb" -> {
                    if (edgeMarkers.size() != 1 || !filterMarkers.isEmpty()) {
                        problems.append("  ").append(label)
                                .append(": a cross_kb branch must carry exactly one @edge marker\n");
                        continue;
                    }
                    EdgeKey key = EdgeKey.parse(edgeMarkers.get(0));
                    if (key == null) {
                        problems.append("  ").append(label).append(": malformed @edge marker '")
                                .append(edgeMarkers.get(0)).append("'\n");
                        continue;
                    }
                    for (String needle :
                            new String[]{key.srcTable(), key.srcCol(), key.tgtTable()}) {
                        if (!chunk.contains(needle)) {
                            problems.append("  ").append(label).append(": @edge names '")
                                    .append(needle).append("' but the branch never mentions it\n");
                        }
                    }
                    if (!surface.edges().add(key)) {
                        problems.append("  ").append(label).append(": @edge '")
                                .append(edgeMarkers.get(0))
                                .append("' duplicates an earlier branch\n");
                    }
                    surface.labels().put(key, label);
                }
                case "unexported" -> {
                    if (filterMarkers.size() != 1 || !edgeMarkers.isEmpty()) {
                        problems.append("  ").append(label)
                                .append(": an unexported branch must carry exactly one @filter"
                                        + " marker\n");
                        continue;
                    }
                    if (!filterMarkers.get(0).equals(label)) {
                        problems.append("  ").append(label).append(": @filter names '")
                                .append(filterMarkers.get(0))
                                .append("' — the marker must equal the branch label\n");
                    }
                    surface.filters().add(label);
                }
                default -> problems.append("  ").append(label).append(": unknown kind '")
                        .append(kind).append("'\n");
            }
        }
        assertTrue(problems.isEmpty(), "preflight scan markers are inconsistent:\n" + problems);
        return surface;
    }

    /** 每个归类为保护边的 catalog 边，都必须在同一份 preflight SQL 里有对应扫描分支；
     *  反过来，preflight 声称的每条边也必须仍是 catalog 保护集的一员。 */
    static void checkPreflight(Coverage cov) {
        PreflightSurface surface = preflightSurface();
        for (EdgeKey key : surface.edges()) {
            if (!cov.protectedSet.contains(key)) {
                cov.stalePreflight.add(key.srcTable() + "." + key.srcCol() + "→" + key.tgtTable()
                        + "." + key.tgtCol() + " ('" + surface.labels().get(key) + "'): preflight "
                        + "watches an edge the catalog guard does not protect");
            }
        }
        for (EdgeKey key : cov.protectedSet) {
            if (!surface.edges().contains(key)) {
                cov.preflightMissing.add(key.srcTable() + "." + key.srcCol() + "→" + key.tgtTable()
                        + "." + key.tgtCol() + ": protected in the schema but absent from export"
                        + " preflight");
            }
        }
    }

    /** 每条相关边归到恰好一类。相关 = 源表在责任面、引用列不是 kb_id 本身、目标表带 kb_id。 */
    static Coverage classify(List<RefEdge> edges, Set<String> kbScoped,
                             Map<String, Set<String>> triggers, Set<String> mechanism) {
        Map<String, String[]> owner = new HashMap<>();
        for (String[] row : OWNER_ROWS) {
            owner.put(row[0], row);
        }
        Map<String, String> registry = new HashMap<>();
        for (String[] row : TRIGGER_EDGES) {
            registry.put(row[0] + " " + row[1], row[2]);
        }
        Map<String, String> exemptions = new HashMap<>();
        for (String[] row : NON_SCOPE) {
            exemptions.put(row[0] + " " + row[1], row[2]);
        }
        Coverage cov = new Coverage();

        for (String t : mechanism) {
            if (!LEDGER_TABLES.contains(t)) {
                cov.surfaceDrift.add(t + ": carries 0070 mechanism but is outside the declared"
                        + " surface");
            }
        }
        for (String t : LEDGER_TABLES) {
            if (!mechanism.contains(t)) {
                cov.surfaceDrift.add(t + ": declared in the surface but carries no 0070 mechanism");
            }
        }
        for (String[] row : OWNER_ROWS) {
            if (kbScoped.contains(row[0])) {
                cov.surfaceDrift.add(row[0] + ": owner-derived row grew its own kb_id — family"
                        + " changed");
            }
        }
        for (String t : LEDGER_TABLES) {
            if (!owner.containsKey(t) && !kbScoped.contains(t)) {
                cov.surfaceDrift.add(t + ": direct-kb surface table lost its kb_id column");
            }
        }

        // 登记三个方向的腐化：owner 边、触发器边、豁免边在 catalog 里都得还在
        for (String[] row : OWNER_ROWS) {
            boolean present = edges.stream().anyMatch(e -> e.srcTable().equals(row[0])
                    && e.srcCol().equals(row[1]) && e.tgtTable().equals(row[2]));
            if (!present) {
                cov.staleRegistry.add(row[0] + "." + row[1] + "→" + row[2]
                        + ": declared owner edge absent from catalog");
            }
        }
        for (String[] row : TRIGGER_EDGES) {
            boolean present = edges.stream()
                    .anyMatch(e -> e.srcTable().equals(row[0]) && e.srcCol().equals(row[1]));
            if (!present) {
                cov.staleRegistry.add(row[0] + "." + row[1] + " (" + row[2]
                        + "): registered trigger edge absent from catalog");
            }
        }
        for (String[] row : NON_SCOPE) {
            boolean present = edges.stream()
                    .anyMatch(e -> e.srcTable().equals(row[0]) && e.srcCol().equals(row[1]));
            if (!present) {
                cov.staleRegistry
                        .add(row[0] + "." + row[1] + ": exempted edge absent from catalog");
            }
        }

        for (RefEdge e : edges) {
            if (!LEDGER_TABLES.contains(e.srcTable()) || "kb_id".equals(e.srcCol())
                    || !kbScoped.contains(e.tgtTable())) {
                continue;
            }
            String label = e.label();
            String[] ownerRow = owner.get(e.srcTable());
            if (ownerRow != null) {
                if (e.srcCol().equals(ownerRow[1])) {
                    if (e.tgtTable().equals(ownerRow[2])) {
                        cov.nonScope.add(label + " (owner edge)");
                    } else {
                        cov.staleRegistry.add(label + ": owner column now points at "
                                + e.tgtTable() + ", not " + ownerRow[2]);
                    }
                } else {
                    String tg = registry.get(e.srcTable() + " " + e.srcCol());
                    if (tg != null) {
                        Set<String> cols = triggers.get(e.srcTable() + " " + tg);
                        if (cols != null && cols.contains(e.srcCol())) {
                            cov.triggerCovered.add(label);
                            cov.protectedSet.add(new EdgeKey(e.srcTable(), e.srcCol(),
                                    e.tgtTable(), e.tgtCol()));
                        } else if (cols == null) {
                            cov.unprotectedTrigger.add(label + ": trigger " + tg
                                    + " is not installed");
                        } else {
                            cov.unprotectedTrigger.add(label + ": trigger " + tg
                                    + " does not watch this column");
                        }
                    } else {
                        cov.unknown.add(label);
                    }
                }
            } else if (exemptions.containsKey(e.srcTable() + " " + e.srcCol())) {
                cov.nonScope.add(label + " (" + exemptions.get(e.srcTable() + " " + e.srcCol())
                        + ")");
            } else {
                // 直接 kb 边：同一条约束内必须同时绑住 (kb_id→kb_id) 与 (ref→id)；
                // 单列 FOREIGN KEY (ref) REFERENCES t(id) 不算同库覆盖
                boolean composite = "id".equals(e.tgtCol()) && e.pairs().size() == 2
                        && e.pairs().stream().anyMatch(p -> "kb_id".equals(p[0])
                        && "kb_id".equals(p[1]));
                if (!composite) {
                    cov.unprotectedDirect.add(label + " via " + e.conname()
                            + " — no composite (kb_id, ref) → (kb_id, id)");
                } else if (e.srcTable().equals(e.tgtTable()) && !(e.deferrable() && e.deferred())) {
                    cov.unprotectedDirect.add(label + " via " + e.conname()
                            + " — same-table self reference must be DEFERRABLE INITIALLY DEFERRED");
                } else {
                    cov.declarative.add(label);
                    cov.protectedSet.add(new EdgeKey(e.srcTable(), e.srcCol(), e.tgtTable(),
                            e.tgtCol()));
                }
            }
        }
        return cov;
    }

    static Coverage classifyReferenceEdges(Connection pool) throws SQLException {
        Coverage cov = classify(catalogEdges(pool), kbScopedTables(pool),
                triggerWatchLists(pool), mechanismTables(pool));
        checkPreflight(cov);
        return cov;
    }

    // =====================================================================
    // 9 个测试
    // =====================================================================

    @Test
    void every_reference_edge_on_the_ledger_is_classified() throws Exception {
        withFullChain("cover", pool -> {
            try {
                classifyReferenceEdges(pool).assertComplete();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 漂移探针 A：kb 自持行上新增一条单列外键（不配 kb_id）。「名字还在」挡不住它——
     *  catalog 会把它数出来，归不进任何一类。 */
    @Test
    void a_new_reference_on_a_kb_owned_row_fails_the_guard() throws Exception {
        withFullChain("driftd", pool -> {
            try {
                exec(pool, "ALTER TABLE public.time_mentions "
                        + "ADD COLUMN probe_ref uuid REFERENCES public.relation_types(id)");
                Coverage cov = classifyReferenceEdges(pool);
                assertTrue(cov.unprotectedDirect.stream().anyMatch(e -> e.startsWith(
                                "time_mentions.probe_ref→")),
                        "新加的单列引用必须落进 UNPROTECTED_DIRECT");
                assertThrows(AssertionError.class, cov::assertComplete);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 漂移探针 B：父行作证表上新增一条指向 kb 表的引用列，但不去碰它的触发器。
     *  「表上有触发器」不许让这条新列显得已被覆盖——登记是列级的。 */
    @Test
    void a_new_reference_on_an_owner_derived_row_fails_the_guard() throws Exception {
        withFullChain("driftt", pool -> {
            try {
                exec(pool, "ALTER TABLE public.fact_evidence "
                        + "ADD COLUMN probe_rel uuid REFERENCES public.relation_types(id)");
                Coverage cov = classifyReferenceEdges(pool);
                assertTrue(cov.unknown.stream().anyMatch(e -> e.startsWith("fact_evidence.probe_rel→")),
                        "未登记的新边必须落进 UNKNOWN");
                assertThrows(AssertionError.class, cov::assertComplete);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 漂移探针 C：kb 自持行上新增一条保护方式完全正确的复合外键边——schema 侧挑不出
     *  毛病（归进 DECLARATIVE），但没人给它补导出体检。只查 schema 的守卫会放行；
     *  这条边必须落进 PREFLIGHT_MISSING。 */
    @Test
    void a_protected_edge_missing_from_preflight_fails_the_guard() throws Exception {
        withFullChain("driftp", pool -> {
            try {
                exec(pool, "ALTER TABLE public.time_mentions ADD COLUMN probe_ref uuid, "
                        + "ADD CONSTRAINT time_mentions_probe_same_kb "
                        + "FOREIGN KEY (kb_id, probe_ref) REFERENCES public.relation_types (kb_id, id)");
                Coverage cov = classifyReferenceEdges(pool);
                assertTrue(cov.declarative.stream().anyMatch(e -> e.startsWith(
                        "time_mentions.probe_ref→")),
                        "保护方式正确的探针边必须归进 DECLARATIVE——schema 侧没有问题");
                assertTrue(cov.preflightMissing.stream().anyMatch(e -> e.contains(
                        "time_mentions.probe_ref")),
                        "漏登记的探针边必须落进 PREFLIGHT_MISSING");
                AssertionError err = assertThrows(AssertionError.class, cov::assertComplete);
                assertTrue(String.valueOf(err.getMessage()).contains("PREFLIGHT_MISSING")
                                && String.valueOf(err.getMessage()).contains("time_mentions.probe_ref"),
                        "报错必须点名缺体检的结构边");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 比对器自身的敏感度：少一条已声明边 → MISSING；多一条 catalog 保护集没有的 → STALE。
     *  守卫本身不能是「怎么都过」的摆设。 */
    @Test
    void the_preflight_check_notices_a_dropped_or_stray_edge() {
        PreflightSurface surface = preflightSurface();
        // 同一份文件两个方向各数一次：60 条结构边 + 5 条 merged 过滤检查
        assertEquals(60, surface.edges().size(), "preflight 必须覆盖全部保护边");
        assertEquals(Set.of("derived.object(merged)", "derived.subject(merged)",
                "fact.object(merged)", "fact.subject(merged)", "qualifier.entity(merged)"),
                surface.filters(), "merged 过滤完整性检查是单独一类，不许静默增减");

        // 保护集 = 体检声明集时两桶皆空
        Coverage cov = new Coverage();
        cov.protectedSet.addAll(surface.edges());
        checkPreflight(cov);
        assertTrue(cov.preflightMissing.isEmpty() && cov.stalePreflight.isEmpty(),
                "声明集与保护集一致时不许误报");

        // 漏一条：假定未来某条边从 SQL 里被删掉而 schema 仍在保护它
        Coverage missing = new Coverage();
        missing.protectedSet.addAll(surface.edges());
        missing.protectedSet.add(new EdgeKey("phantom_table", "phantom_col", "entities", "id"));
        checkPreflight(missing);
        assertEquals(1, missing.preflightMissing.size(),
                "catalog 有而 preflight 没有的边必须落进 MISSING");

        // 反过来：preflight 还在看一条 catalog 不再保护的边
        Coverage stale = new Coverage();
        checkPreflight(stale);
        assertEquals(surface.edges().size(), stale.stalePreflight.size(),
                "catalog 保护集之外的分支必须全部落进 STALE");
    }

    @Test
    void the_migration_installs_under_an_empty_search_path() throws Exception {
        withScratch("empty", pool -> {
            try {
                migration70Under(pool, "''");
                assertInstalled(pool);
                finishChainThen91Under(pool, "''");
                assert0091Installed(pool);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void the_migration_installs_under_a_hostile_search_path() throws Exception {
        withScratch("evil", pool -> {
            try {
                // 首位摆个干扰 schema：同名的 entities 表与同名函数——裸名解析会先看到它。
                // 限定到 public.* 的 DDL 不该理会它
                exec(pool, "CREATE SCHEMA decoy");
                exec(pool, "CREATE TABLE decoy.entities (id uuid, kb_id uuid, merged_into uuid)");
                exec(pool, "CREATE FUNCTION decoy.kb_ownership_is_not_reassigned() RETURNS trigger "
                        + "LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$");
                exec(pool, "CREATE FUNCTION decoy.implied_source_stays_inside_its_kb() "
                        + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$");

                migration70Under(pool, "decoy, public");
                assertInstalled(pool);
                finishChainThen91Under(pool, "decoy, public");
                assert0091Installed(pool);
                // 干扰物原样留着：一次都没被选中
                try (Statement st = pool.createStatement()) {
                    assertEquals(2L, count(st,
                            "SELECT COUNT(*) FROM pg_proc p "
                                    + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                                    + "WHERE n.nspname = 'decoy' AND p.proname IN "
                                    + "('kb_ownership_is_not_reassigned',"
                                    + "'implied_source_stays_inside_its_kb')"),
                            "decoy 函数不该被覆盖也不该被删掉");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void the_migration_installs_under_a_normal_search_path() throws Exception {
        withScratch("norm", pool -> {
            try {
                migration70Under(pool, "public");
                assertInstalled(pool);
                finishChainThen91Under(pool, "public");
                assert0091Installed(pool);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 0091 的前置检查：账本上已经躺着越库出处的库，装 0091 必须整份中止——装上不变量
     *  却对已违反它的账本报喜，等于替坏数据背书。中止必须连锅端：报了哪条边、
     *  一行新机制都不许留下。 */
    @Test
    void migration_91_refuses_a_ledger_with_cross_kb_sources() throws Exception {
        withScratch("dirty", pool -> {
            try {
                migration70Under(pool, "public");
                migrateRange(pool, 70, 81);

                // 两库、各一实体/属性/规则/事实；出处行的事实在本库、规则在别库
                exec(pool, """
                        WITH org AS (
                            INSERT INTO organizations (id, name) VALUES (gen_random_uuid(), 't')
                            RETURNING id
                         ), ws AS (
                            INSERT INTO workspaces (id, org_id, name)
                            SELECT gen_random_uuid(), id, 't' FROM org RETURNING id
                         ), kbs AS (
                            INSERT INTO knowledge_bases (id, workspace_id, name)
                            SELECT gen_random_uuid(), ws.id, 'kb' || i FROM ws, generate_series(1, 2) i
                            RETURNING id
                         ), a AS (SELECT id FROM kbs ORDER BY id LIMIT 1),
                              b AS (SELECT id FROM kbs ORDER BY id OFFSET 1),
                         ents AS (
                            INSERT INTO entities (id, kb_id, canonical_name)
                            SELECT gen_random_uuid(), id, 'e' FROM kbs RETURNING id, kb_id
                         ), rels AS (
                            INSERT INTO relation_types (id, kb_id, key, label)
                            SELECT gen_random_uuid(), id, 'r', 'r' FROM kbs RETURNING id, kb_id
                         ), fcts AS (
                            INSERT INTO facts (id, kb_id, subject_id, object_id, confidence)
                            SELECT gen_random_uuid(), e.kb_id, e.id, e.id, 0.9 FROM ents e
                            RETURNING id, kb_id
                         ), rules_ AS (
                            INSERT INTO implication_rules
                                (id, kb_id, trigger, phrase, conclude_property_id, status)
                            SELECT gen_random_uuid(), r.kb_id, 'phrase', 'p', r.id, 'approved'
                            FROM rels r RETURNING id, kb_id
                         )
                         -- statement_id 是本库事实（CHECK 要求 statement/entity 恰好一个）：
                         -- 只坏 rule 那一头，报错的边名才好对
                         INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id)
                         SELECT f.id, i.id, f.id FROM fcts f, rules_ i
                          WHERE f.kb_id = (SELECT id FROM a) AND i.kb_id = (SELECT id FROM b)
                        """);

                Exception err = assertThrows(Exception.class,
                        () -> migrationUnder(pool, 82, "public"));
                String msg = String.valueOf(err) + " // "
                        + String.valueOf(err.getCause());
                assertTrue(msg.contains("cross-KB references already present")
                                && msg.contains("implied.rule"),
                        "报错必须点名是哪条边: " + msg);

                // 中止要干净：一行新机制都不能留
                try (Statement st = pool.createStatement()) {
                    assertEquals(0L, count(st,
                            "SELECT COUNT(*) FROM pg_constraint "
                                    + "WHERE contype = 'f' "
                                    + "AND conname LIKE 'implication_rules%_same_kb'"),
                            "失败的迁移不许留半套装上的机制");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
