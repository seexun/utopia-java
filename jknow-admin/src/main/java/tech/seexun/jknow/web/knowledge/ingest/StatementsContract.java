package tech.seexun.jknow.web.knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 推陈述（0054）门口校验与服务端重序列化（sources_routes.rs:615-760 逐字语义）。
 *
 * <p>载荷上限 64KiB/200 条；信封白名单 {@code [external_id, doc_time, deleted, e, s, n]}
 * 之外任何键 422——契约里本来就没有属性、类或谓词的格子，一个写了 {@code predicate}
 * 的调用方应当在这里得到 422，而不是在图里找不到它以为写进去的类型事实。逐条校验
 * 契约形态：s 八槽（[0] 引文必须 null——陈述本身就是证据；[5] 限定词 object|null）、
 * n 的 [2] 引文格 null、每条 s/e/n 的格子形状（对齐 parse_open_response 的逐项宽容解：
 * 坏一条计一条 skipped，好的照收；{@code e} 里去重键重名也计 skipped）。
 *
 * <p><b>取舍（任务 4 报告）</b>：Rust 门口预跑完整 {@code parse_open_response}
 * （含截断 JSON 的抢救性恢复——那是对<b>模型回复文本</b>的防御）。这里载荷本来就是
 * 解析成功的 JSON，不存在截断抢救面；逐项形状、skipped 计数、主语/别名在 e 内的
 * name_key 折叠（空白折一+小写）校验全部对齐，验证结果与 Rust 等价。JSON 块抢救
 * （json_block/salvage）属 M3 抽取域移植面。
 *
 * <p><b>存储服务端重序列化契约</b>：存的是我们自己写出来的那份，不是调用方发来的
 * 字节——身份、日期（有的话）和 {e, s, n}，键序固定、没有多余空白（serde_json
 * to_string_pretty 同形：2 空格缩进、数组逐元素换行——跨实现 sha256 身份一致，
 * 写步从 Rust 切到 Java 后同一推送仍是 Unchanged）。
 */
final class StatementsContract {

    /** sources_routes.rs:615 STATEMENTS_MAX_BYTES。 */
    static final int MAX_BYTES = 64 * 1024;

    /** sources_routes.rs:616 STATEMENTS_MAX_ITEMS。 */
    static final int MAX_ITEMS = 200;

    /** sources_routes.rs:618 STATEMENTS_ENVELOPE。 */
    private static final Set<String> ENVELOPE =
            Set.of("external_id", "doc_time", "deleted", "e", "s", "n");

    private static final ObjectMapper JSON = new ObjectMapper();

    private StatementsContract() {
    }

    /** 校验通过的产出：deleted 墓碑时 content=null（载荷照原样不落库）。 */
    record Validated(String externalId, OffsetDateTime docTime, boolean deleted, String content) {
    }

    /** 校验不过 → message 即 422 文案（Rejected 语义：调用方发错了）。 */
    static Validated validate(byte[] raw) {
        if (raw.length > MAX_BYTES) {
            throw rejected("payload is " + raw.length + " bytes; the limit is " + MAX_BYTES);
        }
        JsonNode value;
        try {
            value = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw rejected("Invalid JSON payload: " + e.getMessage());
        }
        if (!value.isObject()) {
            throw rejected("the payload must be a JSON object");
        }
        for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!ENVELOPE.contains(key)) {
                throw rejected("unknown key " + debug(key)
                        + ": the contract has no slot for it (allowed: external_id, doc_time,"
                        + " deleted, e, s, n)");
            }
        }
        JsonNode externalIdNode = value.get("external_id");
        String externalId = externalIdNode != null && externalIdNode.isTextual()
                ? externalIdNode.asText() : null;
        if (externalId == null || externalId.trim().isEmpty()) {
            throw rejected("external_id is required");
        }
        boolean deleted = value.path("deleted").asBoolean(false);
        OffsetDateTime docTime = docTimeOf(value.get("doc_time"));
        if (deleted) {
            return new Validated(externalId.trim(), docTime, true, null);
        }

        JsonNode e = value.path("e");
        JsonNode s = value.path("s");
        JsonNode n = value.path("n");
        if (!e.isArray() || !s.isArray() || !n.isArray()) {
            String key = !e.isArray() ? "e" : (!s.isArray() ? "s" : "n");
            throw rejected(key + " must be an array");
        }
        if (s.isEmpty()) {
            throw rejected("s must hold at least one statement");
        }
        if (s.size() > MAX_ITEMS) {
            throw rejected(s.size() + " statements; the limit is " + MAX_ITEMS + " per push");
        }
        for (int i = 0; i < s.size(); i++) {
            JsonNode item = s.get(i);
            if (!item.isArray()) {
                throw rejected("s[" + i + "] must be an array of eight slots");
            }
            if (item.size() != 8) {
                throw rejected("s[" + i + "] has " + item.size()
                        + " slots; the contract has eight");
            }
            if (!item.get(0).isNull()) {
                throw rejected("s[" + i + "][0] (quote) must be null: the item is its own evidence");
            }
            JsonNode qualifiers = item.get(5);
            if (!qualifiers.isNull() && !qualifiers.isObject()) {
                throw rejected("s[" + i + "][5] (qualifiers) must be an object or null");
            }
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode item = n.get(i);
            if (!item.isArray()) {
                throw rejected("n[" + i + "] must be an array");
            }
            if (item.size() > 2 && !item.get(2).isNull()) {
                throw rejected("n[" + i + "][2] (quote) must be null");
            }
        }

        // 只有契约能通过逐项解析：这一步在门口跑一遍，抽取时不会再有别的答案
        // （parse_open_response 的逐项宽容解——坏一条计一条，e 里重名留第一个）
        int skipped = 0;
        Set<String> listed = new HashSet<>();
        for (JsonNode item : e) {
            String name = entityName(item);
            if (name == null || !listed.add(nameKey(name))) {
                skipped++;
            }
        }
        int statements = 0;
        for (JsonNode item : s) {
            if (statementShapeOk(item)) {
                statements++;
            } else {
                skipped++;
            }
        }
        for (JsonNode item : n) {
            if (!nameShapeOk(item)) {
                skipped++;
            }
        }
        if (skipped > 0) {
            throw rejected(skipped + " item(s) are malformed for the contract"
                    + " (e: [name, kind, named]; s: [null, subject, phrase, object, value,"
                    + " qualifiers, when, ended]; n: [entity, name, null])");
        }
        if (statements == 0) {
            throw rejected("no statement survived parsing");
        }
        // 主语和别名所属的东西必须在 e 里（0054 决定 5）：抽取时找不到的会作为
        // UNKNOWN_REF 静默丢掉，而门口的职责就是不让调用方以为它写进去了。折叠规则与
        // 抽取的 name_key 同一条：只许空白和大小写不同。宾语不在此列——它落成字面值
        for (int i = 0; i < s.size(); i++) {
            String subject = textAt(s.get(i), 1);
            if (!listed.contains(nameKey(subject))) {
                throw rejected("s[" + i + "][1] (subject) " + debug(subject)
                        + " is not a thing listed in e");
            }
        }
        for (int i = 0; i < n.size(); i++) {
            String entity = textAt(n.get(i), 0);
            if (!listed.contains(nameKey(entity))) {
                throw rejected("n[" + i + "][0] (entity) " + debug(entity)
                        + " is not a thing listed in e");
            }
        }
        String content = reserialize(externalId.trim(), docTime, e, s, n);
        return new Validated(externalId.trim(), docTime, false, content);
    }

    // ==== 逐项形状（open.rs:532-573 parse_entity/parse_statement/parse_name 逐字语义） ====

    /** `[name, kind, named]`：name 缺不得（非空串去空白）。 */
    private static String entityName(JsonNode item) {
        if (!item.isArray()) {
            return null;
        }
        String name = textAt(item, 0);
        return name;
    }

    /** `[quote, subject, phrase, object, value, qualifiers, when, ended]`：八格都得在，
     * 主语与短语缺不得（门口已验槽数，这里只看两格文字）。 */
    private static boolean statementShapeOk(JsonNode item) {
        return textAt(item, 1) != null && textAt(item, 2) != null;
    }

    /** `[entity, other name, quote]`：entity 与 name 缺不得。 */
    private static boolean nameShapeOk(JsonNode item) {
        return textAt(item, 0) != null && textAt(item, 1) != null;
    }

    /** open.rs:483-488 text_at：第 i 格的非空字符串（去首尾空白）。 */
    private static String textAt(JsonNode item, int i) {
        if (!item.isArray() || i >= item.size()) {
            return null;
        }
        JsonNode value = item.get(i);
        if (!value.isTextual()) {
            return null;
        }
        String trimmed = value.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** open.rs:525-530 name_key 逐字：空白折一、小写——"Harbor  Bridge" 与 "harbor bridge" 同一条。 */
    static String nameKey(String name) {
        return String.join(" ", name.trim().split("\\s+")).toLowerCase();
    }

    // ==== 服务端重序列化（serde_json to_string_pretty 同形） ====

    /**
     * 存下的文档带着这次观测的身份和日期，再是契约的三个数组（#900）。
     *
     * <p><b>键序 = serde_json BTreeMap 字典序</b>（doc_time &lt; e &lt; external_id &lt; n &lt; s）：
     * Rust 侧 serde_json::Map 默认 BTreeMap（Cargo.lock 无 preserve_order 特性），
     * to_string_pretty 按字典序输出而非插入序。此前的插入序写法与 Rust 差 11 个字节——
     * HTTP 响应比对永远看不见（两侧只回 action），由 M3 终态快照比对首抓获
     * （挂账 statements-doc-key-order-m2），本方法按字典序输出即核销。
     */
    private static String reserialize(String externalId, OffsetDateTime docTime, JsonNode e,
                                      JsonNode s, JsonNode n) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        if (docTime != null) {
            out.append("  \"doc_time\": ").append(write(rfc3339Utc(docTime), 1)).append(",\n");
        }
        out.append("  \"e\": ").append(write(e, 1));
        out.append(",\n  \"external_id\": ").append(write(externalId, 1));
        out.append(",\n  \"n\": ").append(write(n, 1));
        out.append(",\n  \"s\": ").append(write(s, 1));
        out.append("\n}");
        return out.toString();
    }

    /** chrono serde 对 DateTime&lt;Utc&gt; 的形态：UTC、秒精度、有亚秒才带（Rust 同款）。 */
    private static String rfc3339Utc(OffsetDateTime at) {
        OffsetDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC);
        if (utc.getNano() == 0) {
            return utc.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'"));
        }
        return utc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** serde_json pretty：2 空格缩进、对象/数组逐元素换行、空集立即闭合。 */
    private static CharSequence write(String value, int depth) {
        return escape(value);
    }

    private static CharSequence write(JsonNode node, int depth) {
        StringBuilder out = new StringBuilder();
        String pad = "  ".repeat(depth);
        String inner = "  ".repeat(depth + 1);
        if (node.isObject()) {
            if (node.isEmpty()) {
                return "{}";
            }
            out.append("{\n");
            int i = 0;
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                out.append(inner).append(escape(key)).append(": ")
                        .append(write(node.get(key), depth + 1));
                out.append(++i < node.size() ? ",\n" : "\n");
            }
            out.append(pad).append("}");
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                return "[]";
            }
            out.append("[\n");
            for (int i = 0; i < node.size(); i++) {
                out.append(inner).append(write(node.get(i), depth + 1));
                out.append(i + 1 < node.size() ? ",\n" : "\n");
            }
            out.append(pad).append("]");
        } else if (node.isTextual()) {
            out.append(escape(node.asText()));
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            out.append(node.decimalValue().toPlainString());
        } else if (node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else {
            out.append(escape(node.toString()));
        }
        return out;
    }

    /** serde 的字符串转义（控制字符转十六进制 4 位转义，标准引号/反斜杠/换行族）。 */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** Rust {:?} 的 String 形态近似（消息文案用，不进存储）。 */
    private static String debug(String value) {
        return escape(value);
    }

    private static OffsetDateTime docTimeOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isTextual()) {
            throw rejected("Invalid payload: doc_time must be an RFC 3339 timestamp");
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (Exception e) {
            throw rejected("Invalid payload: doc_time must be an RFC 3339 timestamp");
        }
    }

    /** Rejected 语义的载体：调用方发错了（422，不记来源同步失败）。 */
    static final class StatementRejectedException extends RuntimeException {
        StatementRejectedException(String message) {
            super(message);
        }
    }

    private static StatementRejectedException rejected(String message) {
        return new StatementRejectedException(message);
    }
}
