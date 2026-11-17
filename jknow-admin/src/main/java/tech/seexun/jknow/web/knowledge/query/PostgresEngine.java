package tech.seexun.jknow.web.knowledge.query;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Postgres 族（顺带覆盖 Greenplum/Timescale 等 PG 兼容系，postgres.rs 全量对等）。
 * 线协议直连（pgjdbc），是引擎里唯一 sqlx→JDBC 同写法的那个——SQL 文本一字不改。
 *
 * <p>会话只读与超时（纵深防御第 3 层）：同一条连接上先 {@code SET default_transaction_read_only = on}
 * 与 {@code SET statement_timeout = '10s'} 再跑查询——parser 万一漏网也写不进去、跑不死库。
 * Rust 侧每次 pool().connect 新开一条连接（max 1、5s acquire）——Java 侧同样每次
 * {@link DriverManager#getConnection} 新连接、用完即关，语义等价（连接不复用，
 * 会话 SET 不跨查询串味）。
 */
public final class PostgresEngine implements QueryEngine {

    private final String conn;

    public PostgresEngine(String conn) {
        this.conn = conn;
    }

    /**
     * postgres://user:pass@host:port/db[?params] → jdbc:postgresql://...（pgjdbc）。
     * userinfo 拆成 user/password 属性；其余 query 参数原样透传（sslmode 等），
     * 另加 connectTimeout=5（对齐 Rust acquire_timeout 5s 的连接等待档）。
     */
    private Connection connect() throws SQLException {
        URI u = URI.create(conn.trim());
        StringBuilder url = new StringBuilder("jdbc:postgresql://");
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
        props.setProperty("connectTimeout", "5");
        if (u.getRawQuery() != null && !u.getRawQuery().isEmpty()) {
            for (String part : u.getRawQuery().split("&")) {
                int eq = part.indexOf('=');
                String key = eq < 0 ? ConnParser.decode(part)
                        : ConnParser.decode(part.substring(0, eq));
                if ("user".equals(key) || "password".equals(key)) {
                    continue;
                }
                props.setProperty(key, eq < 0 ? ""
                        : ConnParser.decode(part.substring(eq + 1)));
            }
        }
        return DriverManager.getConnection(url.toString(), props);
    }

    @Override
    public void test() throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("SELECT 1");
        }
    }

    @Override
    public List<SchemaColumn> fetchSchema() throws Exception {
        List<Object[]> cols;
        Keys keys;
        try (Connection c = connect()) {
            cols = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT c.table_schema, c.table_name, c.column_name,\n"
                            + "                    c.data_type, pgd.description\n"
                            + "             FROM information_schema.columns c\n"
                            + "             LEFT JOIN pg_catalog.pg_statio_all_tables st\n"
                            + "               ON st.schemaname = c.table_schema"
                            + " AND st.relname = c.table_name\n"
                            + "             LEFT JOIN pg_catalog.pg_description pgd\n"
                            + "               ON pgd.objoid = st.relid"
                            + " AND pgd.objsubid = c.ordinal_position\n"
                            + "             WHERE c.table_schema NOT IN ('pg_catalog',"
                            + " 'information_schema')\n"
                            + "             ORDER BY c.table_schema, c.table_name,"
                            + " c.ordinal_position");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)});
                }
            }
            // 键是锦上添花：读不出来就照从前那样只给列，不让整次取 schema 失败（#502）
            try {
                keys = keys(c);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(PostgresEngine.class)
                        .warn("读不出主键/外键，schema 不带键标记: {}", e.getMessage());
                keys = Keys.EMPTY;
            }
        }
        List<SchemaColumn> out = new ArrayList<>(cols.size());
        for (Object[] row : cols) {
            String schema = (String) row[0];
            String table = (String) row[1];
            String column = (String) row[2];
            out.add(new SchemaColumn(schema, table, column, (String) row[3], (String) row[4],
                    keys.primary.contains(key(schema, table, column)),
                    keys.foreign.get(key(schema, table, column))));
        }
        return out;
    }

    @Override
    public QueryResult execute(String sql) throws Exception {
        List<String> rows;
        try (Connection c = connect(); Statement st = c.createStatement()) {
            // 纵深防御第 3 层：会话级只读 + 超时（parser 漏网也写不进去、跑不死库）
            st.execute("SET default_transaction_read_only = on");
            st.execute("SET statement_timeout = '" + QueryEngines.STATEMENT_TIMEOUT_SECS + "s'");
            // 第 2 层：外包 LIMIT；row_to_json 让 PG 全权处理类型→JSON（文本键序保留列序）
            String wrapped = "SELECT row_to_json(_q)::text AS _j FROM ( " + sql + " ) AS _q LIMIT "
                    + (QueryEngines.ROW_CAP + 1);
            try (ResultSet rs = st.executeQuery(wrapped)) {
                rows = new ArrayList<>();
                while (rs.next()) {
                    String line = rs.getString("_j");
                    rows.add(line == null ? "{}" : line);
                }
            }
        }
        boolean truncated = rows.size() > QueryEngines.ROW_CAP;
        return new QueryResult(
                new ArrayList<>(rows.subList(0, Math.min(rows.size(), QueryEngines.ROW_CAP))),
                truncated);
    }

    /** (schema, table, column) 的稳定键。 */
    private static String key(String schema, String table, String column) {
        return schema + "\u0000" + table + "\u0000" + column;
    }

    /** 一个库里的单列主键与单列外键（postgres.rs:112-120 Keys）。 */
    private static final class Keys {
        static final Keys EMPTY = new Keys();
        final java.util.Set<String> primary = new java.util.HashSet<>();
        /** 外键列 → 它指向的 schema.table */
        final Map<String, String> foreign = new LinkedHashMap<>();
    }

    /**
     * 从 pg_catalog.pg_constraint 读键，<b>不走 information_schema</b>（postgres.rs:122-167）。
     * 三个原因，都实测过：
     * <ul>
     *   <li>information_schema 的约束视图只给表的属主或有 SELECT 以外权限的角色看。BI
     *       连接通常只有 SELECT，于是一个键都读不到也不报错；pg_catalog 对所有角色可见；</li>
     *   <li>information_schema 按约束名连接，千张表时要几秒到几分钟；这里按 oid 连接毫秒级。
     *       取 schema 跑在挂载请求里，不能挂住；</li>
     *   <li>约束名只在一张表内唯一。两张表各有一个叫 fk_ref 的外键，按 schema+约束名连接
     *       会把目标串到别的表上；conrelid/confrelid 是这条约束自己的表。</li>
     * </ul>
     * 只认<b>单列</b>的键：组合主键、组合外键的成员不标——探索提示词拿 PK 当「一行是什么」
     * 的 ID、拿 FK 当关联路径，把组合键的一列单独标出来是误导。一列同时在两个单列外键里
     * （少见）取排序在前的那个，结果稳定。
     */
    private static Keys keys(Connection c) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT n.nspname, c.relname, a.attname, k.contype::text,\n"
                        + "                CASE WHEN k.contype = 'f'"
                        + " THEN rn.nspname || '.' || rc.relname END\n"
                        + "           FROM pg_catalog.pg_constraint k\n"
                        + "           JOIN pg_catalog.pg_class c ON c.oid = k.conrelid\n"
                        + "           JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\n"
                        + "           JOIN pg_catalog.pg_attribute a\n"
                        + "             ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]\n"
                        + "           LEFT JOIN pg_catalog.pg_class rc ON rc.oid = k.confrelid\n"
                        + "           LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rc.relnamespace\n"
                        + "          WHERE k.contype IN ('p', 'f')\n"
                        + "            AND cardinality(k.conkey) = 1\n"
                        + "            AND n.nspname NOT IN ('pg_catalog', 'information_schema')\n"
                        + "          ORDER BY 1, 2, 3, 4, 5");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)});
            }
        }
        Keys keys = new Keys();
        for (String[] row : rows) {
            String key = key(row[0], row[1], row[2]);
            if ("p".equals(row[3])) {
                keys.primary.add(key);
            } else if ("f".equals(row[3]) && row[4] != null) {
                keys.foreign.putIfAbsent(key, row[4]);
            }
        }
        return keys;
    }
}
