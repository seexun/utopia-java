package tech.seexun.jknow.store.typehandler;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PG timestamptz[] 参数处理器：{@code List<OffsetDateTime>} → sql ARRAY
 * （M2 任务 3，恢复路径「共同作证救援」按 (id, 作废时间戳) 精确匹配复活的
 * {@code unnest($2::uuid[], $3::timestamptz[])} 平行数组——documents.rs:1197-1198）。
 * 结果方向（timestamptz[] 列读取）一并支持，与本包 UuidArrayTypeHandler 同构。
 */
public class OffsetArrayTypeHandler extends BaseTypeHandler<List<OffsetDateTime>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<OffsetDateTime> parameter,
                                    JdbcType jdbcType) throws SQLException {
        OffsetDateTime[] array = parameter.toArray(new OffsetDateTime[0]);
        ps.setArray(i, ps.getConnection().createArrayOf("timestamptz", array));
    }

    @Override
    public List<OffsetDateTime> getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<OffsetDateTime> getNullableResult(ResultSet rs, int columnIndex)
            throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<OffsetDateTime> getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<OffsetDateTime> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            // pgjdbc 对 timestamptz[] 的元素读作 java.sql.Timestamp
            Object[] vals = (Object[]) raw;
            List<OffsetDateTime> out = new ArrayList<>(vals.length);
            for (Object v : vals) {
                out.add(v == null ? null
                        : ((java.sql.Timestamp) v).toInstant().atOffset(java.time.ZoneOffset.UTC));
            }
            return out;
        } finally {
            array.free();
        }
    }
}
