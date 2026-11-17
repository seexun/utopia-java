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

/**
 * PG bool[] 参数处理器：{@code List<Boolean>} → sql ARRAY（M3 任务 5，
 * {@code create_relation_types_bulk} 的 UNNEST 平行数组——ontology.rs:1587-1604）。
 * 与本包 TextArrayTypeHandler/UuidArrayTypeHandler 同构。
 */
public class BooleanArrayTypeHandler extends BaseTypeHandler<List<Boolean>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Boolean> parameter,
                                    JdbcType jdbcType) throws SQLException {
        Boolean[] array = parameter.toArray(new Boolean[0]);
        ps.setArray(i, ps.getConnection().createArrayOf("bool", array));
    }

    @Override
    public List<Boolean> getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        return list(rs.getArray(columnName));
    }

    @Override
    public List<Boolean> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return list(rs.getArray(columnIndex));
    }

    @Override
    public List<Boolean> getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return list(cs.getArray(columnIndex));
    }

    private static List<Boolean> list(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Boolean[] values = (Boolean[]) array.getArray();
        return new ArrayList<>(List.of(values));
    }
}
