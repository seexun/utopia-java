package tech.seexun.jknow.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** ParseWarning.detail / Provenance.anchor 的小型构造助手（不外泄 Jackson 类型）。 */
final class Json {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Json() {
    }

    /** {"k":"v",...}——detail 的字段一律按字符串写（Rust 侧也是 serde_json 字符串标量）。 */
    static JsonNode values(String[][] pairs) {
        ObjectNode node = F.objectNode();
        for (String[] pair : pairs) {
            node.put(pair[0], pair[1]);
        }
        return node;
    }

    static JsonNode number(String key, long value) {
        ObjectNode node = F.objectNode();
        node.put(key, value);
        return node;
    }

    static ObjectNode object() {
        return F.objectNode();
    }

    static ArrayNode array() {
        return F.arrayNode();
    }
}
