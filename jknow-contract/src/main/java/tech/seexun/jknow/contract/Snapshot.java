package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import tech.seexun.jknow.contract.model.SnapshotSpec;

/**
 * 终态快照 dumper（M3 任务 1，规格 §3.2/§5 锁 4）：按 kb_id <b>直连共享 PostgreSQL</b>，
 * 把账本终态 dump 成归一化 JSON（归一化算法与动机见 {@link SnapshotNormalizer}）。
 *
 * <p><b>为什么直连库而不是走 HTTP</b>：快照比的是<b>写路径的库内产物</b>（facts 全列、
 * 合并日志、公理队列表……），这些大多数没有读端点、或有也不投影全列；直连是唯一能看见
 * 「账本到底长什么样」的路径。共享库双打架构下（m1-recon §5）两侧本就共享同一 PG，
 * 直连读不影响任何一侧。连接串从环境变量取、缺省与 utopia-server application-dev.yml /
 * 共享栈 docker-compose 一致（127.0.0.1:1517 的 utopia 库）。
 *
 * <p><b>静默等待（awaitQuiet）</b>：dump 前先轮询 jobs 表，该 kb 无 queued/running 才继续
 * （60s 超时 / 500ms 间隔）。为什么：写步骤返回 ≠ 落库完成——摄取/抽取/物化是后台 job，
 * 两侧 job 时序差异（谁先消费、跑多快）会让「半加工态」进快照制造假差异。等待以该 kb
 * 的 job 静默为准，而不是固定 sleep——固定时长在慢机器上等不够、快机器上白等。
 * jobs 表本身<b>永不进快照</b>（瞬态队列不是终态）。
 *
 * <p>独立 CLI（调试/巡检）：{@code mvn -pl utopia-contract compile exec:java
 * -Dexec.mainClass=tech.seexun.jknow.contract.Snapshot
 * -Dexec.args="--kb <uuid> --out dump.json"}；剧本内则经
 * {@code snapshot:} 步骤（{@link SnapshotSpec}）由 ScenarioRunner 调用同一入口。
 */
public final class Snapshot {

    /** 静默等待参数（任务规格定死：60s 超时、500ms 轮询间隔）。 */
    static final long QUIET_TIMEOUT_MS = 60_000;
    static final long QUIET_INTERVAL_MS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Snapshot() {
    }

    /** 共享库连接（凭据环境变量注入，缺省对齐共享栈部署：1517/utopia/utopia）。 */
    public static Connection openConnection(String url, String user, String pass) throws SQLException {
        return DriverManager.getConnection(
                url == null ? env("UTOPIA_JDBC_URL", "jdbc:postgresql://localhost:1517/utopia") : url,
                user == null ? env("UTOPIA_DB_USERNAME", "utopia") : user,
                pass == null ? env("UTOPIA_DB_PASSWORD", "utopia") : pass);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    /**
     * 静默等待：轮询该 kb 范围内是否还有「在跑（running）或已到期待跑（queued 且 run_at 已到）」的 job。
     *
     * <p>kb 范围谓词与旧仓库 utopia-store/src/jobs.rs:266-273 的 KB_SCOPE 同构
     * （jobs 表没有 kb 列，payload 只带 kb_id / document_id / source_id 三种之一，
     * 各自解到库）——本工具自有 SQL，非 Rust 搬迁对象，谓词复用是为了圈定口径一致：
     * 「这个库的 job」在两侧实现的语义里就是这三种 payload 形状。
     *
     * <p><b>为什么不算退避重试中的 queued（run_at 在未来）</b>：重试退避的任务此刻不执行、
     * 本等待窗内也不会写库；而抽取收尾无条件入队的 align_types 在无聊天模型的环境里会
     * 以分钟级退避重试到耗尽（失败不落任何账本行），若按「无 queued/running」口径等它
     * settle，60s 超时必炸。真会写库的任务要么在跑要么已到期——due-or-running 口径抓的
     * 就是它们。代价（记录在案）：退避中的任务若最终成功且写库，快照可能早于最后一次
     * 重跑——该场景在录制栈里对应「首次执行就失败、重试才成功」的瞬时故障，不属于
     * 确定性写路径比对的语义范围。
     */
    public static void awaitQuiet(Connection conn, UUID kb, long timeoutMs, long intervalMs)
            throws SQLException {
        // 用途：终态快照前的静默门——该 kb 无在跑/已到期 job 才允许 dump（防后台时序假差异）。
        // JSONB 存在性判定写成 jsonb_exists() 而不是 `?` 操作符：JDBC 把裸 ? 当占位符，
        // 与操作符撞形（Rust sqlx 侧无此问题，语义等价）。
        String sql = """
                SELECT count(*) FROM jobs j
                 WHERE (j.status = 'running' OR (j.status = 'queued' AND j.run_at <= now()))
                   AND (
                       (jsonb_exists(j.payload, 'kb_id') AND j.payload->>'kb_id' = ?::text)
                    OR (jsonb_exists(j.payload, 'document_id') AND EXISTS (
                           SELECT 1 FROM documents d
                            WHERE d.id::text = j.payload->>'document_id' AND d.kb_id = ?))
                    OR (jsonb_exists(j.payload, 'source_id') AND EXISTS (
                           SELECT 1 FROM sources s
                            WHERE s.id::text = j.payload->>'source_id' AND s.kb_id = ?)))""";
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int pending;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, kb);
                ps.setObject(2, kb);
                ps.setObject(3, kb);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    pending = rs.getInt(1);
                }
            }
            if (pending == 0) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new SQLTimeoutException(
                        "静默等待超时(" + timeoutMs + "ms)：kb " + kb + " 仍有 " + pending
                                + " 个 queued/running job——后台任务卡死或栈未起，终态不成立，拒绝 dump");
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("静默等待被中断", e);
            }
        }
    }

    /**
     * dump 一个 kb 的账本终态（表集 = 登记簿默认集或覆盖）→ 归一化 JSON 树
     * {@code {"tables": {...}}}。确定性：同库同表集两次 dump 逐字节一致。
     */
    public static JsonNode dump(Connection conn, UUID kb, List<String> tables) throws SQLException {
        List<String> resolved = SnapshotTables.resolve(tables);
        Map<String, List<Map<String, Object>>> raw = new LinkedHashMap<>();
        for (String table : resolved) {
            raw.put(table, dumpTable(conn, kb, table, SnapshotTables.REGISTRY.get(table).select()));
        }
        return SnapshotNormalizer.normalize(kb, raw);
    }

    private static List<Map<String, Object>> dumpTable(Connection conn, UUID kb, String table, String select)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setObject(1, kb);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), materialize(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new SQLException("快照取数失败: " + table + " (" + e.getMessage() + ")", e);
        }
    }

    /** JDBC 值 → 归一化器可读的 Java 值（uuid/timestamptz/数组/jsonb/vector 各归其位）。 */
    private static Object materialize(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        // timestamptz：pgjdbc 缺省回 java.sql.Timestamp——统一折成 UTC OffsetDateTime
        //（秩按 Instant 比，时区折算不影响保序；统一 UTC 保证两次 dump 折算一致）
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        if (v instanceof PGobject pg) {
            // jsonb/json：解析成 JsonNode（归一化器里递归走 Normalizer 树规范化）
            if (pg.getType() != null && pg.getType().startsWith("json")) {
                try {
                    return MAPPER.readTree(pg.getValue());
                } catch (Exception e) {
                    throw new SQLException("jsonb 解析失败: " + pg.getValue(), e);
                }
            }
            // vector：浮点向量逐维比对只有噪声，哨兵占位
            return "vector".equals(pg.getType()) ? SnapshotNormalizer.SqlVector.INSTANCE : pg.getValue();
        }
        if (v instanceof Array arr) {
            Object[] elements = (Object[]) arr.getArray();
            List<Object> out = new ArrayList<>(elements.length);
            for (Object el : elements) {
                out.add(el); // uuid[]/text[] 元素已是 UUID/String；其余原样
            }
            return out;
        }
        // java.util.UUID（uuid 列）、OffsetDateTime（timestamptz 列）、Float/Double（real）、
        // BigDecimal（numeric）、Integer/Long/Boolean/String——pgjdbc 直接映射，原样即可
        return v;
    }

    /**
     * 独立 CLI：{@code --kb <uuid> --out <file> [--tables t1,t2] [--jdbc-url url --db-user u --db-pass p]}。
     * 先静默等待再 dump；输出与黄金快照同形（{@code {"snapshot": {...}}}，meta 由 Recorder 补）。
     */
    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        UUID kb = UUID.fromString(cli.require("kb"));
        Path out = Path.of(cli.opt("out", "snapshot-" + kb + ".json"));
        List<String> tables = cli.all("tables").isEmpty()
                ? null : List.of(cli.all("tables").get(0).split(","));
        try (Connection conn = openConnection(cli.opt("jdbc-url", null), cli.opt("db-user", null),
                cli.opt("db-pass", null))) {
            awaitQuiet(conn, kb, QUIET_TIMEOUT_MS, QUIET_INTERVAL_MS);
            JsonNode dump = dump(conn, kb, tables);
            ObjectNode root = MAPPER.createObjectNode();
            root.putObject("meta").put("kb", kb.toString());
            root.set("snapshot", dump);
            Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
            int rows = 0;
            for (JsonNode t : dump.path("tables")) {
                rows += t.size();
            }
            System.out.println("快照完成: " + out + "（" + dump.path("tables").size() + " 表 " + rows + " 行）");
        }
    }
}
