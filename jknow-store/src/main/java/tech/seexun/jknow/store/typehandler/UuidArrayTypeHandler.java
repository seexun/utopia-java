package tech.seexun.jknow.store.typehandler;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PG uuid[] 双向处理器（M1 任务 3，graph mapper 首用）：
 * <ul>
 *   <li>参数方向：{@code List<UUID>} → {@code connection.createArrayOf("uuid", ...)}——
 *       对应 Rust sqlx 把 {@code Vec<Uuid>} 绑到 {@code = ANY($n)}（graph.rs edges_among 的
 *       节点 id 集、fact_qualifiers_for 的 fact id 集）。SQL 文本保持 {@code = ANY(#{ids})}
 *       逐字，占位符内部以 {@code #{ids,typeHandler=...}} 指定本处理器。</li>
 *   <li>结果方向：uuid[] 列（边查询的 premises 两形态——断言段 {@code ARRAY[]::uuid[]} 与
 *       派生段 {@code ARRAY(SELECT ...)}）→ {@code List<UUID>}。</li>
 * </ul>
 */
public class UuidArrayTypeHandler extends BaseTypeHandler<List<UUID>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<UUID> parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("uuid", parameter.toArray(new UUID[0])));
    }

    @Override
    public List<UUID> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<UUID> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<UUID> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<UUID> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object raw = array.getArray();
            UUID[] ids = (UUID[]) raw;
            List<UUID> out = new ArrayList<>(ids.length);
            for (UUID id : ids) {
                out.add(id);
            }
            return out;
        } finally {
            array.free();
        }
    }
}
