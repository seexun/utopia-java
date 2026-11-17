package tech.seexun.jknow.store.typehandler;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * {@code List<Long>} ↔ PG {@code bigint[]}（rss_full_content.rs:77-85 requeue 的
 * {@code WHERE id = ANY($1)}——job id 是 bigint）。IntegerArrayTypeHandler 同款。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class LongArrayTypeHandler extends BaseTypeHandler<List<Long>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("int8", parameter.toArray(new Long[0])));
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<Long> getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<Long> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            Long[] vals = (Long[]) raw;
            List<Long> out = new ArrayList<>(vals.length);
            for (Long v : vals) {
                out.add(v == null ? null : v.longValue());
            }
            return out;
        } finally {
            array.free();
        }
    }
}
