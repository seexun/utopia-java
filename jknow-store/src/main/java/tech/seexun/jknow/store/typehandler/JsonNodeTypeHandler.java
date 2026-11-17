package tech.seexun.jknow.store.typehandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PG jsonb 列 ↔ Jackson JsonNode（M1 任务 3，graph 读路径首用：fact_qualifiers.value、
 * chunks.anchor——Rust 侧都是 {@code Option<serde_json::Value>}，序列化为嵌套 JSON 值）。
 * 结果方向 getString 取 jsonb 文本再解析；参数方向以字符串 + Types.OTHER 传给 PG
 * （SQL 内显式 {@code ::jsonb} 转换，同任务 2 AuditMapper 的 detail 绑定约定）。
 *
 * <p><b>浮点的写形状（M3 任务 3）</b>：serde_json 把整值 f64 写成 {@code 5000000000.0}
 * （ryu 尾随 .0），jsonb 按数值的 scale 存、读回来还是 {@code 5000000000.0}；Jackson 的
 * {@code DoubleNode.toString()} 却写 {@code 5.0E9}——jsonb 收成 scale 0 的整数，读回来
 * 变成整型节点，与写入侧的浮点节点不再相等（Rust 测试 a_qualifier_is_not_the_edges_identity
 * 靠 jsonb 保住 .0）。参数方向把整值浮点转成 scale 1 的 BigDecimal 文本（"5000000000.0"），
 * 写读形状与 Rust 对齐。
 */
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, plainFloats(parameter).toString(), java.sql.Types.OTHER);
    }

    /**
     * 整值浮点（含嵌套）→ scale 1 的 DecimalNode：{@code BigDecimal.toString()} 对
     * 非负 scale 不用科学计数，序列化与 serde_json 同形；非整值浮点原样
     * （Jackson 对它们的 toString 本就是十进制）。
     */
    private static JsonNode plainFloats(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return JsonNodeFactory.instance.numberNode(
                        BigDecimal.valueOf(d).setScale(1, RoundingMode.UNNECESSARY));
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (var it = node.fields(); it.hasNext(); ) {
                var e = it.next();
                out.set(e.getKey(), plainFloats(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                out.add(plainFloats(child));
            }
            return out;
        }
        return node;
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static JsonNode parse(String raw) throws SQLException {
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new SQLException("jsonb 列解析失败: " + e.getMessage(), e);
        }
    }
}
