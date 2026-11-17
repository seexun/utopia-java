package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * MySQL 线协议族（mysql.rs 754 行对等）。<b>一条协议顺带覆盖一片</b>：TiDB、OceanBase、
 * Doris、StarRocks 都说这个协议，MariaDB 也是——这个文件的性价比在引擎里最高。
 *
 * <p>与 {@link PostgresEngine} 的两处不同，都来自 MySQL 自己：
 * <ul>
 *   <li><b>没有 row_to_json。</b>PG 那边把整行交给库转成 JSON 文本，列序天然保留；
 *       这里只能逐列取值自己拼（同 HTTP 族的 rows_to_json_lines），于是多出一张
 *       {@link Cell} 类型映射表——判据是<b>驱动（Connector/J）的类型与取值语义</b>，
 *       不是 MySQL 手册：Rust 侧同一张表的判据是 sqlx 的 ColumnType::name，两侧的
 *       共同点是「以驱动的报型为准」，两处分歧会静默地把整列变成 null——无符号整数、
 *       DECIMAL（只有 BigDecimal 读得精确）、二进制列各见 {@link #cellToJson}；</li>
 *   <li><b>超时的写法有两种。</b>MySQL 是 max_execution_time（毫秒），MariaDB 是
 *       max_statement_time（秒）。两个都试，都不认才报错：这一层挡的是全表扫描拖垮库，
 *       外包的 LIMIT 挡不住它（先扫完再截断），所以不能静默降级。</li>
 * </ul>
 */
public final class MysqlEngine implements QueryEngine {

    private final String conn;

    public MysqlEngine(String conn) {
        // Connector/J 只认 jdbc:mysql://，而 mariadb:// 是同一套协议的另一个写法
        // （只剥前缀：密码里出现同样的字符不该被动到）
        this.conn = conn.startsWith("mariadb://")
                ? "mysql://" + conn.substring("mariadb://".length()) : conn;
    }

    String connString() {
        return conn;
    }

    /** mysql://user:pass@host:port/db[?params] → jdbc:mysql://...（Connector/J）。 */
    private Connection connect() throws SQLException {
        URI u = URI.create(conn.trim());
        StringBuilder url = new StringBuilder("jdbc:mysql://");
        url.append(u.getHost() == null ? "" : u.getHost());
        if (u.getPort() > 0) {
            url.append(':').append(u.getPort());
        }
        if (u.getPath() != null && u.getPath().length() > 1) {
            url.append(u.getPath());
        }
        Properties props = new Properties();
        String rawUserInfo = u.getRawUserInfo();
        if (rawUserInfo != null) {
            int colon = rawUserInfo.indexOf(':');
            if (colon >= 0) {
                props.setProperty("user", ConnParser.decode(rawUserInfo.substring(0, colon)));
                props.setProperty("password", ConnParser.decode(rawUserInfo.substring(colon + 1)));
            } else {
                props.setProperty("user", ConnParser.decode(rawUserInfo));
            }
        }
        // 连接等待 5s（对齐 Rust acquire_timeout 档）；serverTimezone 缺省按会话时区，
        // 与 sqlx 不强行设时区同形
        props.setProperty("connectTimeout", "5000");
        if (u.getRawQuery() != null && !u.getRawQuery().isEmpty()) {
            for (String part : u.getRawQuery().split("&")) {
                int eq = part.indexOf('=');
                String key = eq < 0 ? ConnParser.decode(part)
                        : ConnParser.decode(part.substring(0, eq));
                if ("user".equals(key) || "password".equals(key)) {
                    continue;
                }
                props.setProperty(key, eq < 0 ? "" : ConnParser.decode(part.substring(eq + 1)));
            }
        }
        return DriverManager.getConnection(url.toString(), props);
    }

    // ------------------------------------------------------------------
    // 列类型 → 按哪一档去读（mysql.rs:47-100）
    // ------------------------------------------------------------------

    /** 列类型档位（mysql.rs:52-72 Cell）。分派与读取分开：分派是纯函数测得了的那个。 */
    enum Cell {
        BOOL, INT, UNSIGNED_INT, FLOAT, DECIMAL, JSON, DATE, TIME, DATETIME, TIMESTAMP, TEXT
    }

    /**
     * 类型名 → 档位（mysql.rs:77-100 cell_kind）。判据是<b>驱动的类型名表</b>：Rust 侧是
     * sqlx 的 {@code ColumnType::name}（TINYINT(1) 报 BOOLEAN、DECIMAL/NEWDECIMAL 都报
     * DECIMAL、无符号整数带 {@code " UNSIGNED"} 后缀）；Java 侧 Connector/J 的
     * {@code getColumnTypeName} 报的是 MySQL 原始名（TINYINT/BIGINT UNSIGNED/...）。
     * 同一张表两套判据各自的取值路径见 {@link #cellToJson}（getObject 按驱动语义落到
     * 同一档：TINYINT(1) 在 Connector/J 默认 tinyInt1isBit=true 下本就给 Boolean）。
     */
    static Cell cellKind(String typeName) {
        String ty = typeName.toUpperCase(Locale.ROOT);
        // 后缀先判：INT UNSIGNED 落到 INT 那一档会用有符号档去读，无符号大整数会溢出
        if (ty.endsWith(" UNSIGNED")) {
            String base = ty.substring(0, ty.length() - " UNSIGNED".length());
            return switch (base) {
                case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "BIGINT" -> Cell.UNSIGNED_INT;
                case "FLOAT", "DOUBLE" -> Cell.FLOAT;
                case "DECIMAL" -> Cell.DECIMAL;
                default -> Cell.TEXT;
            };
        }
        return switch (ty) {
            case "BOOLEAN", "BOOL" -> Cell.BOOL;
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "BIGINT" -> Cell.INT;
            case "FLOAT", "DOUBLE" -> Cell.FLOAT;
            case "DECIMAL" -> Cell.DECIMAL;
            case "JSON" -> Cell.JSON;
            case "DATE" -> Cell.DATE;
            case "TIME" -> Cell.TIME;
            case "DATETIME" -> Cell.DATETIME;
            case "TIMESTAMP" -> Cell.TIMESTAMP;
            default -> Cell.TEXT;
        };
    }

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * chrono Display 的小数秒档位（%.f）：nano 为 0 不输出；毫秒整 %1e6==0 → 3 位；
     * 微秒整 %1e3==0 → 6 位；否则 9 位——与 chrono NaiveDateTime/NaiveTime 的 Display 同形。
     */
    private static String fraction(int nano) {
        if (nano == 0) {
            return "";
        }
        if (nano % 1_000_000 == 0) {
            return String.format(".%03d", nano / 1_000_000);
        }
        if (nano % 1_000 == 0) {
            return String.format(".%06d", nano / 1_000);
        }
        return String.format(".%09d", nano);
    }

    /**
     * 一格的值 → JSON（mysql.rs:103-177 cell_to_json 的 Java 形）。判据是 Connector/J 的
     * getObject 语义（对等 Rust 「判据是 sqlx try_get 的兼容表」）：
     * <ul>
     *   <li>TINYINT(1) 在驱动默认 tinyInt1isBit=true 下就是 Boolean（Rust 侧 sqlx 报
     *       BOOLEAN 同一档）；</li>
     *   <li>BIGINT UNSIGNED → BigInteger（i64 装不下，驱动拒绝用有符号读它）；</li>
     *   <li>DECIMAL → BigDecimal → toPlainString → {@link QueryEngines#coerce}("DECIMAL")，
     *       与 Databricks/Snowflake 的 DECIMAL 同一条路——超出 f64 精度的值留字符串而不是
     *       变成一个近似数；</li>
     *   <li>非有限浮点 → null（serde_json::Number::from_f64 的同档）；</li>
     *   <li>二进制列（BLOB/VARBINARY/GEOMETRY）不是合法 UTF-8：给个长度而不是塞转义字节
     *       进模型的上下文；</li>
     *   <li>文本列仍过一遍 coerce：服务器把数字列声明成字符串的情况不少见。</li>
     * </ul>
     */
    static JsonNode cellToJson(ResultSet rs, int i, String typeName) throws SQLException {
        Object v;
        try {
            v = rs.getObject(i + 1);
        } catch (Exception e) {
            v = null;
        }
        if (v == null) {
            // NULL 在各自那一档里变成 JSON null——不做统一的前置探测：那种探测会把
            // 一个取不出的有值 BLOB 误判成空（mysql.rs:107-109 同理由）
            return NullNode.getInstance();
        }
        String ty = typeName.toUpperCase(Locale.ROOT);
        // TIMESTAMP 列按 UTC 瞬间出 RFC3339（mysql.rs 的 DateTime&lt;Utc&gt; 档——M5 任务 3
        // 审查 I-2①：TIMESTAMP 是「存 UTC 取会话时区墙钟」的列型，getObject 给的
        // LocalDateTime 是会话时区钟面，服务器时区非 UTC 时与 Rust 的同一时刻数值不同；
        // getTimestamp().toInstant() 走毫秒锚点换回真实瞬间）
        if (ty.startsWith("TIMESTAMP")) {
            try {
                java.sql.Timestamp ts = rs.getTimestamp(i + 1);
                if (ts != null) {
                    return new TextNode(RFC3339.format(
                            OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC)));
                }
            } catch (Exception e) {
                // 落回通用分派
            }
        }
        if (v instanceof Boolean b) {
            return b ? com.fasterxml.jackson.databind.node.BooleanNode.TRUE
                    : com.fasterxml.jackson.databind.node.BooleanNode.FALSE;
        }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long n) {
            return new LongNode(((Number) v).longValue());
        }
        if (v instanceof BigInteger big) {
            return new BigIntegerNode(big);
        }
        if (v instanceof BigDecimal d) {
            return QueryEngines.coerce("DECIMAL", new TextNode(d.toPlainString()));
        }
        if (v instanceof Float || v instanceof Double f) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? NullNode.getInstance()
                    : new DoubleNode(d);
        }
        if (v instanceof byte[] bytes) {
            return new TextNode("<" + bytes.length + " bytes>");
        }
        if (v instanceof String s) {
            // CHAR/VARCHAR/TEXT/ENUM/SET 走这里（CAST 后的 information_schema 列也是）
            return QueryEngines.coerce(ty, new TextNode(s));
        }
        if (v instanceof LocalDate d) {
            return new TextNode(d.format(DATE_FORMAT));
        }
        if (v instanceof LocalTime t) {
            // 小数秒按 chrono %.f 档位（无/3/6/9 位——审查 I-2③：DATETIME(6)/TIME(6)
            // 的微秒不能被整秒格式器截掉）
            return new TextNode(t.format(TIME_FORMAT) + fraction(t.getNano()));
        }
        if (v instanceof LocalDateTime dt) {
            // chrono NaiveDateTime 的 Display 用空格分隔——同一形状（含 %.f 档位小数秒）
            return new TextNode(dt.format(DATETIME_FORMAT) + fraction(dt.getNano()));
        }
        if (v instanceof OffsetDateTime o) {
            return new TextNode(o.format(RFC3339));
        }
        if (v instanceof java.sql.Date d) {
            return new TextNode(d.toLocalDate().format(DATE_FORMAT));
        }
        if (v instanceof java.sql.Time t) {
            return new TextNode(t.toLocalTime().format(TIME_FORMAT));
        }
        if (v instanceof java.sql.Timestamp t) {
            return new TextNode(t.toLocalDateTime().format(DATETIME_FORMAT));
        }
        return new TextNode(String.valueOf(v));
    }

    // ------------------------------------------------------------------
    // QueryEngine
    // ------------------------------------------------------------------

    @Override
    public void test() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SELECT 1");
        }
    }

    @Override
    public List<SchemaColumn> fetchSchema() throws Exception {
        List<String[]> cols;
        Keys keys;
        try (Connection c = connect()) {
            // MySQL 的 schema 就是 database。系统库按名字排除——information_schema 在这里
            // 是**要排除的对象**，与 PG 那边同名的概念不是一回事。
            //
            // 每列都要 CAST(... AS CHAR)：MySQL 8.0 起 information_schema 是建在数据字典
            // 上的视图，这些列经二进制协议报成 VARBINARY——Rust 侧 sqlx 严格类型解码拒绝
            // 读进 String；Java 侧 Connector/J 虽宽松，同一条语句两侧一致（MariaDB 上
            // CAST 无害）。SQL 逐字（mysql.rs:214-222）
            cols = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT CAST(table_schema AS CHAR), CAST(table_name AS CHAR),\n"
                            + "                    CAST(column_name AS CHAR),"
                            + " CAST(column_type AS CHAR),\n"
                            + "                    CAST(column_comment AS CHAR)\n"
                            + "             FROM information_schema.columns\n"
                            + "             WHERE table_schema NOT IN\n"
                            + "                   ('information_schema', 'mysql',"
                            + " 'performance_schema', 'sys')\n"
                            + "             ORDER BY table_schema, table_name,"
                            + " ordinal_position");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)});
                }
            }
            // 键是锦上添花：读不出来就照从前那样只给列，不让整次取 schema 失败（#502）。
            // 可见性：statistics 与 key_column_usage 对只有 SELECT 的用户照样有行（实测
            // MariaDB 11.4）；table_constraints/referential_constraints 对这种用户是空的
            // ——所以键从前两张视图读，不要换成后两张
            try {
                keys = keys(c);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(MysqlEngine.class)
                        .warn("读不出 MySQL 主键/外键，schema 不带键标记: {}", e.getMessage());
                keys = Keys.EMPTY;
            }
        }
        List<SchemaColumn> out = new ArrayList<>(cols.size());
        for (String[] row : cols) {
            String key = row[0] + "\u0000" + row[1] + "\u0000" + row[2];
            // 没有注释时这一列是空串而不是 NULL，照抄会让每张表都挂一个空注释
            String comment = row[4] == null || row[4].trim().isEmpty() ? null : row[4];
            out.add(new SchemaColumn(row[0], row[1], row[2], row[3], comment,
                    keys.primary.contains(key), keys.foreign.get(key)));
        }
        return out;
    }

    @Override
    public QueryResult execute(String sql) throws Exception {
        List<String> columns;
        List<String> types;
        List<JsonNode[]> valueRows;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            // 纵深防御第 3 层：会话只读 + 语句超时。只读在自动提交下逐条生效，
            // parser 万一漏网也写不进去
            st.execute("SET SESSION TRANSACTION READ ONLY");
            int millis = QueryEngines.STATEMENT_TIMEOUT_SECS * 1000;
            boolean timeoutSet;
            try {
                st.execute("SET SESSION max_execution_time = " + millis);
                timeoutSet = true;
            } catch (SQLException mysqlFormFailed) {
                try {
                    // MariaDB：秒
                    st.execute("SET SESSION max_statement_time = "
                            + QueryEngines.STATEMENT_TIMEOUT_SECS);
                    timeoutSet = true;
                } catch (SQLException e) {
                    throw new SQLException(
                            "Could not set a statement timeout on this server: " + e.getMessage(),
                            e);
                }
            }
            if (!timeoutSet) {
                throw new SQLException("Could not set a statement timeout on this server");
            }
            try (ResultSet rs = st.executeQuery(QueryEngines.wrapLimit(sql))) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                columns = new ArrayList<>(n);
                types = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(meta.getColumnLabel(i));
                    types.add(meta.getColumnTypeName(i));
                }
                valueRows = new ArrayList<>();
                while (rs.next()) {
                    JsonNode[] row = new JsonNode[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = cellToJson(rs, i, types.get(i));
                    }
                    valueRows.add(row);
                }
            }
        }
        // 第 201 行只判截断（先收完再判——分块到达的行数上限由外包 LIMIT 保证）
        boolean truncated = valueRows.size() > QueryEngines.ROW_CAP;
        List<List<JsonNode>> kept = new ArrayList<>(
                Math.min(valueRows.size(), QueryEngines.ROW_CAP));
        for (JsonNode[] row : valueRows) {
            if (kept.size() >= QueryEngines.ROW_CAP) {
                break;
            }
            kept.add(List.of(row));
        }
        return new QueryResult(
                QueryEngines.rowsToJsonLines(columns, kept, HttpSupport.JSON), truncated);
    }

    private static final class Keys {
        static final Keys EMPTY = new Keys();
        final java.util.Set<String> primary = new java.util.HashSet<>();
        final Map<String, String> foreign = new LinkedHashMap<>();
    }

    /**
     * 从 information_schema 读键，两支 UNION ALL（mysql.rs:331-391，SQL 逐字）：
     * 单列主键（statistics 里 index_name='PRIMARY' 按表分组只有一列的）∪ 单列外键
     * （key_column_usage 里 referenced_table_name 非空、按约束分组只有一列的）。
     *
     * <p>每张视图只扫一遍，用 GROUP BY 数列，不写逐行的相关子查询——MariaDB（与
     * MySQL 5.7）每次读 information_schema 都现场重建这张表：逐行子查询在 1,000 张表
     * 时要 19 秒，而取 schema 跑在挂载请求里、没有语句超时，慢到头就是挂住；分组写法
     * 在 2,000 张表上是 0.6 秒。列名经二进制协议报成 VARBINARY（MySQL 8.0+），跟
     * fetchSchema 的列查询一样必须 CAST(... AS CHAR)。
     */
    private static Keys keys(Connection c) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT CAST(table_schema AS CHAR), CAST(table_name AS CHAR),\n"
                        + "                CAST(MIN(column_name) AS CHAR),\n"
                        + "                NULL AS ref_schema, NULL AS ref_table\n"
                        + "           FROM information_schema.statistics\n"
                        + "          WHERE index_name = 'PRIMARY'\n"
                        + "            AND table_schema NOT IN\n"
                        + "                ('information_schema', 'mysql',"
                        + " 'performance_schema', 'sys')\n"
                        + "          GROUP BY table_schema, table_name\n"
                        + "         HAVING COUNT(*) = 1\n"
                        + "         UNION ALL\n"
                        + "         SELECT CAST(table_schema AS CHAR), CAST(table_name AS CHAR),\n"
                        + "                CAST(MIN(column_name) AS CHAR),\n"
                        + "                CAST(MIN(referenced_table_schema) AS CHAR),\n"
                        + "                CAST(MIN(referenced_table_name) AS CHAR)\n"
                        + "           FROM information_schema.key_column_usage\n"
                        + "          WHERE referenced_table_name IS NOT NULL\n"
                        + "            AND table_schema NOT IN\n"
                        + "                ('information_schema', 'mysql',"
                        + " 'performance_schema', 'sys')\n"
                        + "          GROUP BY table_schema, table_name, constraint_name\n"
                        + "         HAVING COUNT(*) = 1\n"
                        + "         ORDER BY 1, 2, 3, 4, 5");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)});
            }
        }
        Keys keys = new Keys();
        for (String[] row : rows) {
            String key = row[0] + "\u0000" + row[1] + "\u0000" + row[2];
            if (row[3] == null) {
                // 来自 UNION 的第一支（ref_schema 为 NULL 占位），是 PK 行
                keys.primary.add(key);
            } else if (row[4] != null) {
                // 一列同时在两个单列外键里（少见）：五列全排序、先到先得，结果稳定
                keys.foreign.putIfAbsent(key, row[3] + "." + row[4]);
            }
        }
        return keys;
    }
}
