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
 * {@code List<Integer>} ↔ PG {@code int[]}（告警可见性谓词 alerts.rs:125-136 的
 * {@code unnest($3::uuid[], $4::int[])} 平行数组的秩一侧）。UuidArrayTypeHandler 同款。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class IntegerArrayTypeHandler extends BaseTypeHandler<List<Integer>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Integer> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("int4", parameter.toArray(new Integer[0])));
    }

    @Override
    public List<Integer> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<Integer> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<Integer> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<Integer> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            Integer[] vals = (Integer[]) raw;
            List<Integer> out = new ArrayList<>(vals.length);
            for (Integer v : vals) {
                out.add(v == null ? null : v.intValue());
            }
            return out;
        } finally {
            array.free();
        }
    }
}
