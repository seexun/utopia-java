package tech.seexun.jknow.store.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * UUID ↔ PG uuid 列。mybatis 默认不注册 UUID 处理器（3.5.x TypeHandlerRegistry 无此项），
 * 经 application.yml 的 mybatis.type-handlers-package 装载。PG 驱动原生支持
 * setObject/getObject(UUID)，无需字符串往返。
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getObject(columnIndex, UUID.class);
    }
}
