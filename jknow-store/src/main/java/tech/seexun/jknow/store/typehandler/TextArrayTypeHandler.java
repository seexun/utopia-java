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
 * PG text[] 参数处理器：{@code List<String>} → sql ARRAY（M1 任务 3，graph entities 搜索的
 * 精确同名集 {@code lower(e.canonical_name) = ANY($5)}，graph.rs:1588/1595 的 exact 向量）。
 * 结果方向（text[] 列读取）一并支持，与本包 UuidArrayTypeHandler 同构。
 */
public class TextArrayTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("text", parameter.toArray(new String[0])));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            String[] values = (String[]) array.getArray();
            List<String> out = new ArrayList<>(values.length);
            for (String v : values) {
                out.add(v);
            }
            return out;
        } finally {
            array.free();
        }
    }
}
