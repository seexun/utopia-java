package tech.seexun.jknow.store.typehandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * PG {@code jsonb[]} 列 ↔ {@code List<JsonNode>}（M1 任务 5 首用：告警组的
 * {@code lines}——{@code (array_agg(detail ORDER BY created_at DESC))[1:5]}，alerts.rs:227）。
 * 逐元素按 jsonb 文本解析（PG JDBC 的 array 元素以字符串形态带出）。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class JsonNodeArrayTypeHandler extends BaseTypeHandler<List<JsonNode>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<JsonNode> parameter, JdbcType jdbcType)
            throws SQLException {
        String[] texts = parameter.stream().map(JsonNode::toString).toArray(String[]::new);
        ps.setArray(i, ps.getConnection().createArrayOf("jsonb", texts));
    }

    @Override
    public List<JsonNode> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<JsonNode> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<JsonNode> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<JsonNode> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            Object[] elems = (Object[]) raw;
            List<JsonNode> out = new ArrayList<>(elems.length);
            for (Object e : elems) {
                out.add(e == null ? null : MAPPER.readTree(e.toString()));
            }
            return out;
        } catch (Exception e) {
            throw new SQLException("jsonb[] 列解析失败: " + e.getMessage(), e);
        } finally {
            array.free();
        }
    }
}
