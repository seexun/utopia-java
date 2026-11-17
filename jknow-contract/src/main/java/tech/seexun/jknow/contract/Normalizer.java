package tech.seexun.jknow.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 响应规范化器：把同一接口两次真实调用的响应抹成逐字节一致，黄金样本才可比。
 * 四件事（NormalizerTests 一一对应）：
 * 1. uuid / ISO-8601 时间戳字符串 → {@code <uuid>} / {@code <ts>} 占位符；
 * 2. 对象键按字典序输出；纯对象数组的元素按规范化后的序列化形排序（服务端不保证顺序时也稳定）；
 * 3. 浮点按 6 位小数四舍五入再去尾零（抹掉序列化/计算尾差，0.30000001 == 0.3）；
 * 4. maskFields 命中的字段整体替换为 {@code <masked>}（token/duration 类每次必变的噪声）。
 * 输出为紧凑 JSON 字符串，确定性：同输入同输出，键序、数字格式均固定。
 */
public final class Normalizer {

    /**
     * 录制链路的默认掩码集：跨次调用必变且无契约意义的字段。
     * token/duration——JWT、会话令牌与耗时统计；job_id——任务表自增序列；
     * token_prefix——PAT 随机前缀；ingest_token——来源推送密钥（每次轮换随机）。
     */
    public static final Set<String> DEFAULT_MASKS =
            Set.of("token", "duration", "job_id", "token_prefix", "ingest_token");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TS =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$");
    private static final int FLOAT_SCALE = 6;

    private Normalizer() {
    }

    /** 单参入口：使用 {@link #DEFAULT_MASKS}（黄金录制走这里，token/duration 自动掩码）。 */
    public static String json(String raw) {
        return json(raw, DEFAULT_MASKS);
    }

    /** 全参入口：解析→规范化→紧凑序列化。非 JSON 文本原样作为 JSON 字符串参与比对（保确定性）。 */
    public static String json(String raw, Set<String> maskFields) {
        if (raw == null || raw.isBlank()) {
            return "null";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return write(TextNode.valueOf(raw.trim()));
        }
        return write(normalize(node, maskFields));
    }

    /** 规范化为 JsonNode（Recorder/Replayer 直接存树，避免二次解析）。null/空白 → NullNode。 */
    public static JsonNode normalize(String raw, Set<String> maskFields) {
        if (raw == null || raw.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return normalize(MAPPER.readTree(raw), maskFields);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw.trim());
        }
    }

    /**
     * RDF 归一化（M5 任务 1，kb-export 步的比对口径）：把 Turtle/JSON-LD 文本<b>解析回
     * 三元组集</b>再落成排序后的 N-Triples 行——Jena 与 oxrdfio 的序列化空白/前缀展开
     * 顺序不同，按原文比对必然假红；按三元组集比对与 Rust 侧「RDF 能解析回来才算数」
     * 的测试纪律同一条。空白节点在两侧各自匿名：行序先按内容排（空白节点标签参与排序
     * 会失稳），标签按排序后首现次序重编 {@code _:b0/:b1/…}——两次导出只要同构，归一
     * 形逐字节一致。解析失败原样作为文本节点返回（假文件不该被归一吞掉）。
     */
    public static JsonNode triples(String raw) {
        if (raw == null || raw.isBlank()) {
            return NullNode.getInstance();
        }
        org.apache.jena.rdf.model.Model model = org.apache.jena.rdf.model.ModelFactory
                .createDefaultModel();
        String trimmed = raw.trim();
        org.apache.jena.riot.Lang lang = trimmed.startsWith("{") || trimmed.startsWith("[")
                ? org.apache.jena.riot.Lang.JSONLD
                : org.apache.jena.riot.Lang.TURTLE;
        try {
            model.read(new java.io.StringReader(trimmed), null, lang.getName());
        } catch (RuntimeException e) {
            try {
                // JSON-LD 探测错了就换 Turtle 再试一次（导出端点只产这两种格式）
                model.removeAll();
                model.read(new java.io.StringReader(trimmed), null,
                        org.apache.jena.riot.Lang.TURTLE.getName());
            } catch (RuntimeException e2) {
                return TextNode.valueOf(trimmed);
            }
        }
        if (model.isEmpty()) {
            // 空图与空体同义（Rust oxrdfio 零三元组时连前缀头都不发；Jena 会发 7 行 @prefix）
            return NullNode.getInstance();
        }
        List<String> lines = new ArrayList<>();
        Map<String, String> bnodeNames = new java.util.HashMap<>();
        for (var it = model.listStatements(); it.hasNext();) {
            var stmt = it.nextStatement();
            lines.add(term(stmt.getSubject()) + " " + term(stmt.getPredicate()) + " "
                    + term(stmt.getObject()));
        }
        // 第一遍按「空白节点标签占位」排序定出稳定的空白节点编号，第二遍代入后整体排序
        Collections.sort(lines);
        List<String> out = new ArrayList<>(lines.size());
        int[] next = {0};
        for (String line : lines) {
            java.util.regex.Matcher m = BLANK.matcher(line);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                sb.append(line, last, m.start());
                String label = m.group(1);
                String canon = bnodeNames.computeIfAbsent(label,
                        k -> "_:b" + (next[0]++));
                sb.append(canon);
                last = m.end();
            }
            sb.append(line.substring(last));
            out.add(sb.toString());
        }
        Collections.sort(out);
        return TextNode.valueOf(String.join("\n", out));
    }

    private static final java.util.regex.Pattern BLANK =
            java.util.regex.Pattern.compile("_:(\\S+)");

    private static String term(org.apache.jena.rdf.model.RDFNode node) {
        if (node.isResource()) {
            org.apache.jena.rdf.model.Resource r = node.asResource();
            return r.isAnon() ? "_:" + r.getId().getLabelString() : "<" + r.getURI() + ">";
        }
        org.apache.jena.rdf.model.Literal lit = node.asLiteral();
        String lex = lit.getLexicalForm().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        String dt = lit.getDatatypeURI();
        if (dt == null || "http://www.w3.org/2001/XMLSchema#string".equals(dt)) {
            return "\"" + lex + "\"";
        }
        return "\"" + lex + "\"^^<" + dt + ">";
    }

    /** 树入口（快照归一化复用）：对已解析的 JsonNode 做同一套规范化（键排序/占位/浮点容差）。 */
    static JsonNode normalizeTree(JsonNode node, Set<String> masks) {
        return normalize(node, masks);
    }

    private static JsonNode normalize(JsonNode node, Set<String> masks) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode out = F.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            // 键本身的噪声：服务端用 uuid 当对象键（如规则版本史的 labels 按类型 id 索引）——
            // 键不占位则两次录制的键名不同，样本必漂移。同形键碰撞时按排序序加 #2/#3 去重，保条目数。
            Map<String, Integer> seen = new java.util.HashMap<>();
            for (String name : names) {
                String outKey = name;
                if (UUID.matcher(name).matches()) {
                    outKey = "<uuid>";
                } else if (TS.matcher(name).matches()) {
                    outKey = "<ts>";
                }
                int seq = seen.merge(outKey, 1, Integer::sum);
                if (seq > 1) {
                    outKey = outKey + "#" + seq;
                }
                out.set(outKey, masks.contains(name)
                        ? TextNode.valueOf("<masked>")
                        : normalize(node.get(name), masks));
            }
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> elems = new ArrayList<>();
            for (JsonNode child : node) {
                elems.add(normalize(child, masks));
            }
            // 纯对象数组：元素按键序规范化后的序列化形排序，服务端不保证顺序时两次录制也一致。
            // 标量数组（分页游标、时间线）语义有序，保持原序。
            boolean allObjects = !elems.isEmpty() && elems.stream().allMatch(JsonNode::isObject);
            if (allObjects) {
                elems.sort(Comparator.comparing(Normalizer::write));
            }
            ArrayNode out = F.arrayNode();
            elems.forEach(out::add);
            return out;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (UUID.matcher(text).matches()) {
                return TextNode.valueOf("<uuid>");
            }
            if (TS.matcher(text).matches()) {
                return TextNode.valueOf("<ts>");
            }
            return node;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal bd = node.decimalValue().setScale(FLOAT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
            if (bd.scale() < 0) {
                // 1200.0 去尾零会得到 1.2E+3（负 scale），拉回 0 避免 E 记法
                bd = bd.setScale(0);
            }
            return new DecimalNode(bd);
        }
        return node;
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JsonNode 序列化不应失败: " + e.getMessage(), e);
        }
    }

    /** 文本是否形如 UUID（快照归一化复用同一判式，保证两套占位口径不漂移）。 */
    static boolean looksLikeUuid(String text) {
        return UUID.matcher(text).matches();
    }

    /** 文本是否形如 ISO-8601 时间戳（同上）。 */
    static boolean looksLikeTimestamp(String text) {
        return TS.matcher(text).matches();
    }

    /**
     * 浮点按 6 位小数四舍五入再去尾零（快照归一化复用）：抹掉 REAL 列两侧序列化/计算尾差
     * （Rust f32 与 Java float 的末位差异），0.30000001 == 0.3。
     */
    static BigDecimal roundFloating(BigDecimal value) {
        BigDecimal bd = value.setScale(FLOAT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.scale() < 0) {
            // 1200.0 去尾零会得到 1.2E+3（负 scale），拉回 0 避免 E 记法
            bd = bd.setScale(0);
        }
        return bd;
    }
}
