package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;

/**
 * 向量索引由任务来建，不是一条编号迁移（0035）——vector_index.rs 全量对等（M3 任务 10
 * 落真身，替换 JobDispatcher 的 NOOP 桩）。
 *
 * <p>列是无维的（{@code embedding vector}，没有 {@code (N)}）：维度跟着工作区选的嵌入
 * 模型走，迁移跑的时候还不知道，pgvector 也建不了未知维度的 HNSW。另一头，
 * {@code CREATE INDEX CONCURRENTLY} 进不了事务，而不带 CONCURRENTLY 就要在 {@code chunks}
 * 上持 ACCESS EXCLUSIVE 锁到建完。所以：第一次写下某个维度的向量时排一条任务，任务在
 * 事务外建一个<b>按维度的部分表达式索引</b>，{@code IF NOT EXISTS} 让重排无害。
 *
 * <p>读路径三条规矩（缺一条索引就悄悄不生效或悄悄少回，vector_index.rs:10-28）：
 * <ol>
 * <li><b>维度写成字面量。</b> {@code vector_dims(col) = $2} 绑参数时自定义计划能用索引，
 *     generic plan 退回顺扫；预编译语句跑五次就切 generic，第六次起索引悄悄失效。</li>
 * <li><b>两侧都 cast 到 {@code vector(N)}。</b> 索引建在表达式 {@code col::vector(N)} 上，
 *     {@code ORDER BY} 必须一字不差地写同一个表达式。</li>
 * <li><b>{@code hnsw.iterative_scan = relaxed_order}。</b> HNSW 先取 ef_search 个候选再过
 *     WHERE；按 kb 分租的表上小库占不到几个候选，LIMIT 会回三行甚至零行——iterative_scan
 *     让它继续走到凑够为止；两个停止条件一并放宽（内存倍数 4 / 元组上限 100000）。</li>
 * </ol>
 *
 * <p>Java 侧实现注：build/drop/vectorSearch 都走<b>单条裸 JDBC 连接</b>而不是
 * JdbcTemplate/MyBatis——(a) CONCURRENTLY 不能进事务，(b) 建索引要「复用手里那条连接」
 * （a_vector_build_uses_one_connection：池只剩两条时再借一条会跟自己手里那条互相等），
 * (c) {@code SET LOCAL} 只在事务里生效，向量检索套一个小事务把会话设置与查询绑在同一
 * 连接上。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    /** vector_index.rs:36 JOB_KIND：任务种类，分发按这个名字认。 */
    public static final String JOB_KIND = "build_vector_index";

    /**
     * 建索引的会话级咨询锁（vector_index.rs:42-44）。key 用字符串哈希，和 temporal 里的
     * 时间线锁同一套写法。只用 <b>try</b> 版本：阻塞的 {@code pg_advisory_lock} 等锁时那条
     * 语句自己就是一个带快照的事务，而 CONCURRENTLY 建到最后一步要等所有比它老的快照
     * 结束——建的等排队的、排队的等建的，换了个地方死锁。探一下就返回、不留快照，等待
     * 放在客户端。
     */
    static final String BUILD_TRY_LOCK =
            "SELECT pg_try_advisory_lock(hashtextextended('vector_index:build', 0))";
    static final String BUILD_UNLOCK =
            "SELECT pg_advisory_unlock(hashtextextended('vector_index:build', 0))";

    /** vector_index.rs:46 BUILD_LOCK_POLL：没抢到锁时隔多久再探（建一次几十秒到几分钟）。 */
    static final long BUILD_LOCK_POLL_MS = 250;

    /** vector_index.rs:70 MAX_DIMS：pgvector 的 HNSW 对 vector 类型的上限。 */
    public static final int MAX_DIMS = 2000;

    /** vector_index.rs:130-141：进程里记住「已经在了」的索引——往后每次写入只是一次查找。 */
    private static final Set<String> KNOWN_VECTOR_INDEXES = ConcurrentHashMap.newKeySet();

    /**
     * vector_index.rs:74-113 Target：哪一列。两张表同一套机制，名字不同而已。
     * （EntityProfiles 为 entities.profile_embedding——画像向量的写入侧属后续，先按
     * Rust 的三目标齐全建表。）
     */
    public enum Target {
        CHUNKS("chunks", "embedding"),
        ENTITY_PROFILES("entities", "profile_embedding"),
        NAME_VECTORS("name_vectors", "embedding");

        private final String table;
        private final String column;

        Target(String table, String column) {
            this.table = table;
            this.column = column;
        }

        public String table() {
            return table;
        }

        public String column() {
            return column;
        }

        /** 任务载荷里的名字（= 表名）。 */
        public String key() {
            return table;
        }

        /** vector_index.rs:105-112 parse：载荷 → 目标；不认识 → null。 */
        public static Target parse(String key) {
            for (Target t : values()) {
                if (t.key().equals(key)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** vector_index.rs:116-118 index_name：{@code chunks_embedding_hnsw_1024}。 */
    public static String indexName(Target target, int dims) {
        return target.table() + "_" + target.column() + "_hnsw_" + dims;
    }

    /** vector_index.rs:121-123 same_dims：部分索引的谓词；查询里要原样出现（规矩 1）。 */
    public static String sameDims(String column, int dims) {
        return "vector_dims(" + column + ") = " + dims;
    }

    /** vector_index.rs:126-128 distance：{@code <=>} 两侧都 cast 到字面维度（规矩 1、2）。 */
    public static String distance(String column, int param, int dims) {
        return column + "::vector(" + dims + ") <=> $" + param + "::vector(" + dims + ")";
    }

    /**
     * vector_index.rs:136 RESORT：近邻查询的外层次序（#652）。里层照规矩 2 写
     * {@code ORDER BY {distance} LIMIT n} 让索引接住，外层再按这个排——relaxed_order 下
     * 索引回的次序只是大致按距离；距离并列时两种计划各排各的，id 定下来；{@code + 0}
     * 不能省：没有它规划器认 CTE 的次序为已排，只在并列的组里排 id。
     */
    public static final String RESORT = "distance + 0, id";

    private final DataSource dataSource;
    private final DocumentsWriteMapper documents;
    private final JobsService jobs;

    public VectorIndexService(DataSource dataSource, DocumentsWriteMapper documents,
                              JobsService jobs) {
        this.dataSource = dataSource;
        this.documents = documents;
        this.jobs = jobs;
    }

    // ---- 状态（status，vector_index.rs:139-157） ----

    /** 索引现在的状态：null 没有；TRUE 有且有效；FALSE 上次建到一半留下的。 */
    public Boolean status(Target target, int dims) {
        return documents.selectEmbeddingIndexValid(indexName(target, dims));
    }

    /** 同一查询落在指定连接上（build 复用手里的连接，不再借新的）。 */
    private static Boolean statusOn(Connection conn, Target target, int dims) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT i.indisvalid FROM pg_class c"
                        + " JOIN pg_index i ON i.indexrelid = c.oid"
                        + " WHERE c.relname = ? AND c.relkind = 'i'")) {
            ps.setString(1, indexName(target, dims));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    /** 进程内已知集合（测试与 drop 后的遗忘）。 */
    static void remember(String name) {
        KNOWN_VECTOR_INDEXES.add(name);
    }

    static void forget(String name) {
        KNOWN_VECTOR_INDEXES.remove(name);
    }

    static boolean isKnown(String name) {
        return KNOWN_VECTOR_INDEXES.contains(name);
    }

    // ---- 入队侧（request，vector_index.rs:183-201） ----

    /**
     * 写下某个维度的向量时叫一声：索引不在就排一条建索引任务。返回任务 id；null =
     * 这一次没排（已知在/查到在/维度超限）。超过 {@link #MAX_DIMS} 的维度什么都不排：
     * 建不了，查询走精确路径。
     *
     * <p>入队用 {@code enqueueUnlessQueuedTx}（REQUIRED）：调用方在事务里
     * （DocumentsWriteService.setEmbeddings / NameVectorsService.set）时任务行随业务写一起
     * 提交，符合「任务与向量同进退」。
     */
    public Long request(Target target, int dims) {
        if (dims == 0 || dims > MAX_DIMS) {
            return null;
        }
        String name = indexName(target, dims);
        if (isKnown(name)) {
            return null;
        }
        if (Boolean.TRUE.equals(status(target, dims))) {
            remember(name);
            return null;
        }
        ObjectNode payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("table", target.key());
        payload.put("dims", dims);
        return jobs.enqueueUnlessQueuedTx(JOB_KIND, payload);
    }

    // ---- 任务本体（build，vector_index.rs:243-301） ----

    /** 一次 {@link #build} 的结果（vector_index.rs:204-210 Built）。 */
    public record Built(String name, boolean created, double seconds) {
    }

    /**
     * 用表的 owner 建（vector_index.rs:221-237 build_as_owner）。{@code ownerUrl} 是迁移
     * 身份的连接串（UTOPIA_MIGRATION_URL），只在它跟运行身份分开时才有——<b>建索引要的是
     * 表的所有权，授权顶替不了</b>：应用连库用受限角色时自己建索引会拿到
     * {@code must be owner of table}，任务失败三次、索引永远建不出来。分开了身份就用迁移
     * 那个身份开一条连接（max 1），建完即关；没分开（null/空）照旧用应用的池子。
     */
    public Built buildAsOwner(String ownerUrl, Target target, int dims) throws SQLException {
        if (ownerUrl == null || ownerUrl.isBlank()) {
            return build(target, dims);
        }
        String url = ownerUrl.startsWith("jdbc:") ? ownerUrl : jdbcForm(ownerUrl);
        try (Connection owner = java.sql.DriverManager.getConnection(url)) {
            return buildOn(owner, target, dims, false);
        }
    }

    /** postgres://… → jdbc:postgresql://…（迁移连接串与 Rust 同源，可能是原生形态）。 */
    private static String jdbcForm(String url) {
        return "jdbc:postgresql://" + url.substring(url.indexOf("://") + 3);
    }

    /**
     * 任务本体：事务外（CONCURRENTLY 的要求）、串行（并行建索引要共享内存，Docker 默认
     * 64MB 的 /dev/shm 会报 could not resize shared memory segment）。上一次建到一半留下
     * 的无效索引先删——{@code IF NOT EXISTS} 看见它会以为已经建好。
     *
     * <p><b>一次只建一条</b>（会话级咨询锁）：同一张表上两条 CONCURRENTLY 各自要等表上
     * 其他事务结束、也各自算对方要等的事务，旁边再有摄取往 chunks 写就凑成死锁（40P01）。
     * worker 并发认领两条不同维度的构建任务就是这个局面。锁跟着这条连接走，跨 worker、
     * 跨实例都排队——建索引一次几十秒到几分钟，排队比死锁后重试便宜。
     */
    public Built build(Target target, int dims) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return buildOn(conn, target, dims, true);
        }
    }

    /**
     * 在给定连接上建索引（pool 模式 = 从应用池借的连接，建完还池；owner 模式 = 一次性
     * 连接，建完关闭）。锁—建—解锁全绑这一条连接（AlignTypesJob 审查 I-1 同一条纪律）。
     */
    private Built buildOn(Connection conn, Target target, int dims, boolean fromPool)
            throws SQLException {
        if (dims == 0 || dims > MAX_DIMS) {
            // Validation 档（vector_index.rs:245-248）：分发层据此判 Terminal——这种失败
            // 重试也建不出来，不该烧任务预算
            throw AppException.validation(dims + " dims: HNSW on vector holds up to " + MAX_DIMS);
        }
        String name = indexName(target, dims);
        long started = System.nanoTime();
        boolean originalAutoCommit = conn.getAutoCommit();
        // 咨询锁语句在 autocommit 下每句自成一个事务、不拖快照——与 Rust 逐句执行等价；
        // CONCURRENTLY 本就要求事务外，这里显式保持 autocommit
        conn.setAutoCommit(true);
        lockBuild(conn);
        boolean ok = false;
        try {
            try (Statement st = conn.createStatement()) {
                // 并行维护工人置零：并行建图的进程间共享内存同样会撞 /dev/shm 上限
                st.execute("SET max_parallel_maintenance_workers = 0");
                // 默认 64MB 的 maintenance_work_mem 到一万四千行 1024 维就装不下图，之后
                // 每一行都要落盘再读；512MB 只在建的这一会儿占着
                st.execute("SET maintenance_work_mem = '512MB'");
            }
            Boolean before = statusOn(conn, target, dims);
            if (Boolean.FALSE.equals(before)) {
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
                }
            }
            boolean existed = Boolean.TRUE.equals(before);
            try (Statement st = conn.createStatement()) {
                // 索引定义逐字（vector_index.rs:273-282）：部分表达式索引，谓词与查询侧
                // same_dims 一字不差（规矩 1/2）
                st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name
                        + " ON " + target.table()
                        + " USING hnsw ((" + target.column() + "::vector(" + dims + "))"
                        + " vector_cosine_ops) WHERE " + sameDims(target.column(), dims));
            }
            ok = !existed;
        } finally {
            // 会话级 SET 跟着连接回池，成败都复位。锁也一样——解不掉就把这条连接关掉而
            // 不是还回池子：带着锁回池，之后所有构建都会卡在它后面
            try (Statement st = conn.createStatement()) {
                st.execute("RESET max_parallel_maintenance_workers");
                st.execute("RESET maintenance_work_mem");
            } catch (SQLException resetFailed) {
                closeQuietly(conn, fromPool);
            }
            if (ok) {
                remember(name);
            }
            unlockBuild(conn, fromPool);
        }
        return new Built(name, ok, (System.nanoTime() - started) / 1_000_000_000.0);
    }

    /** vector_index.rs:48-58 lock_build：探一下就返回，没抢到睡 250ms 再探。 */
    private static void lockBuild(Connection conn) throws SQLException {
        while (true) {
            try (PreparedStatement ps = conn.prepareStatement(BUILD_TRY_LOCK);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) {
                    return;
                }
            }
            try {
                Thread.sleep(BUILD_LOCK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("vector_index:build 锁等待被中断", e);
            }
        }
    }

    /** vector_index.rs:60-65 unlock_build：解不开就把连接关掉，不让带锁连接回池。 */
    private static void unlockBuild(Connection conn, boolean fromPool) {
        boolean unlocked = false;
        try (PreparedStatement ps = conn.prepareStatement(BUILD_UNLOCK);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            unlocked = rs.getBoolean(1);
        } catch (SQLException e) {
            log.warn("vector_index:build 咨询锁解锁失败: {}", e.getMessage());
        }
        if (!unlocked) {
            closeQuietly(conn, fromPool);
        }
    }

    private static void closeQuietly(Connection conn, boolean fromPool) {
        if (!fromPool) {
            return; // owner 一次性连接：调用方 try-with-resources 会关
        }
        try {
            conn.close(); // 池连接 close = 归还/废弃；Hikari 废弃坏连接
        } catch (SQLException e) {
            log.debug("关闭构建连接失败: {}", e.getMessage());
        }
    }

    /** vector_index.rs:304-318 drop：删掉（测试与手工维护用；写路径不会走到这里）。 */
    public void drop(Target target, int dims) throws SQLException {
        String name = indexName(target, dims);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            lockBuild(conn);
            boolean ok = false;
            try (Statement st = conn.createStatement()) {
                // Dropping an index must take the same lock as building one: separate
                // concurrent index operations on the table can deadlock each other.
                st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
                ok = true;
            } finally {
                if (ok) {
                    forget(name);
                }
                unlockBuild(conn, true);
            }
        }
    }

    // ---- 读路径会话设置（relaxed_order，vector_index.rs:324-354） ----

    /**
     * {@code hnsw.iterative_scan} 是 pgvector 0.8 才有的参数。旧版本第一次探一下、进程内
     * 记住：探不到就不设——查询还是对的，只是共享表上的小库可能少回几行。
     */
    private static volatile Boolean iterativeScanAvailable;

    private boolean iterativeScanAvailable() {
        Boolean available = iterativeScanAvailable;
        if (available != null) {
            return available;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT current_setting('hnsw.iterative_scan', true)");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            available = rs.getString(1) != null;
        } catch (SQLException e) {
            available = false;
        }
        iterativeScanAvailable = available;
        return available;
    }

    /**
     * vector_index.rs:339-354 relaxed_order：读路径的会话设置（规矩 3）。{@code SET LOCAL}
     * 只在事务里生效，所以近邻查询都套一个事务——在给定连接/事务里执行。
     * （M4 任务 1 起公开：resolution.rs nearest_typed_entities 的近邻查询同样要套。）
     */
    public void relaxedOrder(Connection conn) throws SQLException {
        if (!iterativeScanAvailable()) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
            // 两个停止条件的放宽：内存倍数是实测绑住扫描的那个；元组上限没绑住，放宽是
            // 为了把「到顶」留成看得见的慢
            st.execute("SET LOCAL hnsw.scan_mem_multiplier = 4");
            st.execute("SET LOCAL hnsw.max_scan_tuples = 100000");
        }
    }

    // ---- 分块近邻检索（documents.rs:1657-1693 vector_search） ----

    /**
     * 向量近邻检索（余弦距离），documents.rs:1657-1693 逐字——维度写成字面量、两侧 cast、
     * relaxed_order 三条规矩；次序在外层再排一遍（RESORT）：relaxed_order 下索引给的次序
     * 只是大致按距离，而距离并列时精确路径和 HNSW 各排各的——同一问两种计划回的居首不同
     * （#652），并列由 id 定。维度为 0 返回空。
     *
     * <p>一次查询绑一条池连接（开小事务做 SET LOCAL，再跑 SELECT，提交归还）。Rust 的
     * {@code $2}（查询向量，SELECT 与 ORDER BY 各引用一次）在 JDBC 下是两个独立
     * {@code ?}——同一向量按位绑两次。
     */
    public List<UUID> chunkVectorSearch(UUID kbId, float[] embedding, long limit,
                                        OffsetDateTime asOf) {
        int dims = embedding.length;
        if (dims == 0) {
            return List.of();
        }
        String queryVec = tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText.literal(
                embedding);
        // documents.rs:1671-1679 逐字（$1..$4 → JDBC ?；distance 的 $2 两个引用位各一个 ?）
        String vectorCast = "c.embedding::vector(" + dims + ") <=> ?::vector(" + dims + ")";
        String live = asOf == null
                ? "c.superseded_at IS NULL"
                : "c.created_at <= coalesce(?, now())"
                        + " AND (c.superseded_at IS NULL OR c.superseded_at > coalesce(?, now()))";
        String sql = "WITH nearest AS MATERIALIZED ("
                + " SELECT id, " + vectorCast + " AS distance FROM chunks c"
                + " WHERE c.kb_id = ? AND c.embedding IS NOT NULL AND " + live
                + " AND " + sameDims("c.embedding", dims)
                + " ORDER BY " + vectorCast
                + " LIMIT ?"
                + ") SELECT id FROM nearest ORDER BY " + RESORT;
        try (Connection conn = dataSource.getConnection()) {
            boolean original = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                relaxedOrder(conn);
                List<UUID> ids = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int p = 1;
                    ps.setString(p++, queryVec); // SELECT 里的距离
                    ps.setObject(p++, kbId);
                    if (asOf != null) {
                        ps.setObject(p++, asOf);
                        ps.setObject(p++, asOf);
                    }
                    ps.setString(p++, queryVec); // ORDER BY 里的距离（SQL 文本序：先 ORDER BY 后 LIMIT）
                    ps.setLong(p++, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add((UUID) rs.getObject(1));
                        }
                    }
                }
                conn.commit();
                return ids;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailed) {
                    e.addSuppressed(rollbackFailed);
                }
                throw AppException.other("向量检索失败: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(original);
            }
        } catch (SQLException e) {
            throw AppException.other("向量检索连接失败: " + e.getMessage(), e);
        }
    }
}
