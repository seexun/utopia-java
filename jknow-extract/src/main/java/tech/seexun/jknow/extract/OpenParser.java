package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开放抽取回复的解析（open.rs:316-636 逐语义）。紧凑 JSON——数组、短键——因为一块的输出
 * 动辄上百条，冗长的键名烧的是 max_tokens，截断一次丢的是整块的后半。
 *
 * <p><b>一条坏记录不该毁掉一整块</b>。解析的阶梯：
 * <ol>
 *   <li>{@code json_block} 整体解（第一个 {@code {} 到最后一个 {@code }}）——解得开就是
 *       完整回复，结尾之后哪怕跟着废话也不算截断；</li>
 *   <li>{@code json_tail}（第一个 {@code {} 到结尾）——紧凑回复的 {@code }} 只在限定词对象
 *       与结尾出现，截断的回复要么一个 {@code }} 都没有，要么最后一个 {@code }} 是半路
 *       某条陈述的限定词，{@code json_block} 的取块刀对它切错两回；</li>
 *   <li>{@link #salvage} 逐条宽容读——模型写坏的多半不是结尾：{@code e} 没关就写起了
 *       {@code s}，坏在三分之一处，后面三分之二（十几条陈述、别名、文档的日期）就都没了。
 *       宁收勿弃是生产兜底：顺着文本走，按 {@code "e"/"s"/"n"/"t": [} 切节，遇 {@code [}
 *       取配平段单独解；一条坏只计 {@code skipped}；配得平却解不开才计 {@code broken}；
 *       真截断的最后一条配不平自然不收。</li>
 * </ol>
 * 调用方<b>必须</b>把 {@code skipped} 报出去——不报就是一次静默丢弃。
 */
public final class OpenParser {

    private OpenParser() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 整体解那一步的严格形态（审查 I-2）：serde_json::from_str 对
     * {@code \{"e":…\}\}} 这种尾随 token 报错、走 salvage 标 truncated；Jackson 默认
     * 忽略尾随内容会把它当完整回复。
     */
    private static final ObjectMapper STRICT = JSON.copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_TRAILING_TOKENS);

    /** open.rs:51-59 OpenEntity：[名字或描述, 种类词, named]。 */
    public record OpenEntity(String name, String kind, boolean named) {
    }

    /**
     * open.rs:61-81 OpenStatement：[引文, 主语名, 短语, 宾语名|null, 字面值|null, 限定词, 何时|null, 何时终止|null]。
     * 宾语与字面值<b>同时有则都透传</b>（解析层不裁，两个都给调用方看）。限定词顺序是
     * JSON 键序（Jackson 保持输入序），不承载意义。
     */
    public record OpenStatement(String subject, String phrase, String object, String value,
                                List<Map.Entry<String, String>> qualifiers, String when,
                                String ended, String quote) {
    }

    /** open.rs:83-89 OpenName：[e 里列的名字, 这一段用的另一个名字, 引文]。 */
    public record OpenName(String entity, String name, String quote) {
    }

    /** open.rs:91-104 OpenExtraction。 */
    public static final class OpenExtraction {
        public final List<OpenEntity> entities = new ArrayList<>();
        public final List<OpenStatement> statements = new ArrayList<>();
        public final List<OpenName> names = new ArrayList<>();
        /** 0064：这一块报的文档自己的日期（t 节）。 */
        public final List<TimeExtract.TimeEntry> time = new ArrayList<>();
        /** 坏条目数（逐条计；调用方必须报出去）。 */
        public int skipped;
        /** 回复整体不合法、被逐条读出：截断或写坏（调用方拿 hit_token_ceiling 区分是哪种）。 */
        public boolean truncated;
    }

    /**
     * open.rs:322-325 json_tail：从第一个 {@code {} 取到<b>结尾</b>，交给修补自己退到最后
     * 一个完整条目。
     */
    static String jsonTail(String raw) {
        String text = JsonFuns.jsonText(raw);
        int at = text.indexOf('{');
        return at < 0 ? null : text.substring(at);
    }

    /**
     * open.rs:333-345 repair_truncated_compact：紧凑格式的截断修补——退到<b>最后一个完整的
     * 数组或对象</b>的结尾再把括号补齐。回退点是最后一个 {@code ]} <b>或</b> {@code }}，
     * 谁靠后用谁（限定词全是 null 的回复里可能一个 {@code }} 都没有）。至多退 64 步。
     */
    public static String repairTruncatedCompact(String json) {
        int cut = json.length();
        for (int round = 0; round < 64; round++) {
            int idx = lastIndexOfAny(json, cut, ']');
            int idx2 = lastIndexOfAny(json, cut, '}');
            if (idx2 > idx) {
                idx = idx2;
            }
            if (idx < 0) {
                return null;
            }
            String closed = JsonFuns.closeBrackets(json.substring(0, idx + 1));
            if (closed != null) {
                try {
                    JSON.readTree(closed);
                    return closed;
                } catch (Exception ignore) {
                    // 解不开继续退
                }
            }
            cut = idx;
        }
        return null;
    }

    private static int lastIndexOfAny(String s, int end, char c) {
        return s.lastIndexOf(c, end - 1);
    }

    /**
     * open.rs:362-456 salvage：不合法的回复逐条读（整体解不开时才走这里）。
     * 返回 {@code (各类条目组成的 JSON 对象, 写坏的条数)}；一条都读不出来返回 null。
     */
    static Salvaged salvage(String text) {
        List<List<JsonNode>> found = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            found.add(new ArrayList<>());
        }
        Integer[] section = {null};
        int[] broken = {0};
        int[] i = {0};
        int n = text.length();
        while (i[0] < n) {
            char c = text.charAt(i[0]);
            if (c == '"') {
                int end = stringEnd(text, i[0]);
                if (end < 0) {
                    break;
                }
                Integer key = null;
                String inner = text.substring(i[0] + 1, Math.min(end - 1, text.length()));
                for (int k = 0; k < 4; k++) {
                    if (inner.equals(SECTIONS[k])) {
                        key = k;
                        break;
                    }
                }
                if (key != null) {
                    // rest = text[end..].trim_start()；after_colon = rest.strip_prefix(':')
                    int r = end;
                    while (r < n && Character.isWhitespace(text.charAt(r))) {
                        r++;
                    }
                    if (r < n && text.charAt(r) == ':') {
                        r++;
                        while (r < n && Character.isWhitespace(text.charAt(r))) {
                            r++;
                        }
                        if (r < n && text.charAt(r) == '[') {
                            // 只有 (key, ": [") 成立才换节（审查 I-1，open.rs:416-419）：
                            // "t":"oops" 这类键后不是数组的，节照旧——切早了会把紧随其后的
                            // 陈述条目按错节的格数去解（timeEntry 拒收 8 格），白丢陈述
                            section[0] = key;
                            // i = text.len() - r.len() + 1：r 处是 '['，下一个字符从 r+1 起
                            i[0] = r + 1;
                            continue;
                        }
                    }
                }
                i[0] = end;
            } else if (c == '[') {
                Integer end = balancedEnd(text, i[0]);
                JsonNode item = null;
                if (end != null) {
                    try {
                        JsonNode v = JSON.readTree(text.substring(i[0], end + 1));
                        if (v.isArray()) {
                            int own = section[0] == null ? 0 : slots(section[0], v);
                            boolean firstNotArray = v.size() > 0 && !v.get(0).isArray();
                            boolean restNotArray = true;
                            for (int k = own; k < v.size(); k++) {
                                if (v.get(k).isArray()) {
                                    restNotArray = false;
                                    break;
                                }
                            }
                            if (firstNotArray && restNotArray) {
                                item = v;
                            }
                        }
                    } catch (Exception ignore) {
                        // 解不开：配得平却解不开（或套着别的条目）才算写坏
                    }
                }
                if (item != null && section[0] != null) {
                    boolean isStatement = section[0] == 0 && statementShaped(item);
                    found.get(isStatement ? 1 : section[0]).add(item);
                    i[0] = end + 1;
                } else if (item != null) {
                    i[0] = end + 1;
                } else {
                    if (section[0] != null && end != null) {
                        broken[0]++;
                    }
                    i[0]++;
                }
            } else {
                i[0]++;
            }
        }
        if (found.stream().allMatch(List::isEmpty)) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
        for (int k = 0; k < 4; k++) {
            var arr = out.putArray(SECTIONS[k]);
            found.get(k).forEach(arr::add);
        }
        return new Salvaged(out, broken[0]);
    }

    /** salvage 的产物：各类条目组成的对象 + 写坏条数。 */
    record Salvaged(JsonNode value, int broken) {
    }

    private static final String[] SECTIONS = {"e", "s", "n", "t"};

    /** 字符串的结尾（i 在开头的引号上）：结尾引号之后的位置；没有结尾（截断）返回 -1。 */
    private static int stringEnd(String text, int start) {
        int i = start + 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** 与 i 上的 {@code [} 配平的括号的位置；配不平返回 null。字符串里的括号不算。 */
    private static Integer balancedEnd(String text, int start) {
        int i = start;
        int depth = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = stringEnd(text, i);
                if (end < 0) {
                    return null;
                }
                i = end;
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth < 0) {
                    return null;
                }
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return null;
    }

    /**
     * open.rs:458-467 slots：一条该有几格——{@code e} 三格（写在 e 里的陈述八格）、
     * {@code s} 八格、{@code n} 三格、{@code t} 五格。逐条读时分开「一格写错了类型」
     * 和「这一条套进了后面的条目」。
     */
    private static int slots(int section, JsonNode item) {
        if (section == 0 && statementShaped(item)) {
            return 8;
        }
        if (section == 1) {
            return 8;
        }
        if (section == 3) {
            return 5;
        }
        return 3;
    }

    /** open.rs:469-472 statement_shaped：至少八格、前三格是字符串（引文、主语、短语）。 */
    private static boolean statementShaped(JsonNode item) {
        return item.size() >= 8 && item.get(0).isTextual() && item.get(1).isTextual()
                && item.get(2).isTextual();
    }

    /** open.rs:474-480 items：顶层某键下的数组；缺了或不是数组当空。 */
    private static List<JsonNode> items(JsonNode value, String key) {
        JsonNode node = value.get(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return out;
    }

    /** open.rs:482-488 text_at：第 i 格的非空字符串（去首尾空白）——缺一不可的格子用它。 */
    private static String textAt(JsonNode arr, int i) {
        JsonNode v = arr.size() > i ? arr.get(i) : null;
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    /** open.rs:490-497 opt_text：可选的文字格——null、空串与放错的类型都是 null（坏的只是那一格）。 */
    static String optText(JsonNode v) {
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * open.rs:499-506 literal：字面值照原样——字符串去首尾空白；模型偶尔把数字写成 JSON
     * 数字，照它的写法转回文本。限定词的值走同一条规则。
     */
    static String literal(JsonNode v) {
        if (v != null && v.isNumber()) {
            return v.asText();
        }
        return optText(v);
    }

    /**
     * open.rs:508-521 qualifiers：对象里值为字符串或数字的那些；键去首尾空白，空键与别的
     * 类型悄悄丢；不是对象当作没有限定词。保留 JSON 输入序。
     */
    static List<Map.Entry<String, String>> qualifiers(JsonNode v) {
        if (v == null || !v.isObject()) {
            return List.of();
        }
        List<Map.Entry<String, String>> out = new ArrayList<>();
        var fields = v.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            String k = e.getKey().strip();
            String lit = literal(e.getValue());
            if (!k.isEmpty() && lit != null) {
                out.add(Map.entry(k, lit));
            }
        }
        return out;
    }

    /**
     * open.rs:523-530 name_key（与 extraction_open.rs:884-889 同一规则）：首尾空白去掉、
     * 内部空白折成一个、小写。"Harbor  Bridge" 与 "harbor bridge" 是同一条。空白 =
     * char::is_whitespace（Unicode White_Space，含 NBSP——审查 M-2；regex (?U)\\s 不含
     * NBSP 又多收 U+001C-001F）。
     */
    public static String nameKey(String name) {
        StringBuilder out = new StringBuilder();
        boolean inSpace = true;
        int i = 0;
        while (i < name.length()) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (isWs(cp)) {
                if (!inSpace) {
                    out.append(' ');
                    inSpace = true;
                }
            } else {
                out.appendCodePoint(cp);
                inSpace = false;
            }
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        return out.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** char::is_whitespace（Unicode White_Space；与 ExtractionJob.isWs 同一张表）。 */
    static boolean isWs(int cp) {
        switch (cp) {
            case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D:
            case 0x20: case 0x85: case 0xA0: case 0x1680:
            case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000:
                return true;
            default:
                return cp >= 0x2000 && cp <= 0x200A;
        }
    }

    /** open.rs:532-547 parse_entity：name 缺不得；kind 缺了是空串；named 缺了当点了名。 */
    static OpenEntity parseEntity(JsonNode arr) {
        String name = textAt(arr, 0);
        if (name == null) {
            return null;
        }
        JsonNode kindV = arr.size() > 1 ? arr.get(1) : null;
        String kind = kindV != null && kindV.isTextual() ? kindV.asText().strip() : "";
        boolean named = true;
        if (arr.size() > 2) {
            JsonNode n = arr.get(2);
            if (n.isBoolean()) {
                named = n.asBoolean();
            } else if (n.isNumber()) {
                named = n.asDouble() != 0.0;
            }
        }
        return new OpenEntity(name, kind, named);
    }

    /**
     * open.rs:549-568 parse_statement：八格都得在——短了的多半是截断修补切出来的半条，
     * 引文都没有，收下也没法核对。主语与短语缺不得；其余格放错了类型只是那一格为 null。
     */
    static OpenStatement parseStatement(JsonNode arr) {
        if (arr.size() < 8) {
            return null;
        }
        String subject = textAt(arr, 1);
        String phrase = textAt(arr, 2);
        if (subject == null || phrase == null) {
            return null;
        }
        return new OpenStatement(subject, phrase, optText(arr.get(3)), literal(arr.get(4)),
                qualifiers(arr.get(5)), optText(arr.get(6)), optText(arr.get(7)),
                optText(arr.get(0)));
    }

    /** open.rs:570-580 parse_name。 */
    static OpenName parseName(JsonNode arr) {
        String entity = textAt(arr, 0);
        String name = textAt(arr, 1);
        if (entity == null || name == null) {
            return null;
        }
        return new OpenName(entity, name, arr.size() > 2 ? optText(arr.get(2)) : null);
    }

    /**
     * open.rs:582-636 parse_open_response。整体解得开就是完整回复；解不开从第一个
     * {@code {} 取到结尾逐条读；一条都读不出来才是真解析失败（抛
     * {@link IllegalArgumentException}，消息对齐 Rust「Failed to parse open extraction JSON: …」）。
     * {@code e} 重名（去空白、不分大小写）留第一个，其余计入 {@code skipped}。
     */
    public static OpenExtraction parseOpenResponse(String raw) {
        String block = null;
        Exception blockError = null;
        try {
            block = JsonFuns.jsonBlock(raw);
        } catch (Exception e) {
            blockError = e;
        }
        JsonNode value = null;
        boolean truncated = false;
        int skipped = 0;
        if (block != null) {
            try {
                // serde_json::from_str 拒绝尾随 token，Jackson 默认不检——{"e":…}}（双 }）
                // 会被当成完整回复，truncated 错报 false。只在这一步收紧（审查 I-2）；
                // salvage 的配平段以 ] 收尾且内部平衡，不受影响
                value = STRICT.readTree(block);
            } catch (Exception e) {
                blockError = e;
                value = null;
            }
        }
        if (value == null) {
            String tail = jsonTail(raw);
            Salvaged salvaged = tail != null ? salvage(tail) : null;
            if (salvaged == null) {
                throw new IllegalArgumentException(
                        "Failed to parse open extraction JSON: " + blockError);
            }
            skipped = salvaged.broken();
            value = salvaged.value();
            truncated = true;
        }

        OpenExtraction out = new OpenExtraction();
        out.truncated = truncated;
        out.skipped = skipped;
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items(value, "e")) {
            OpenEntity e = item.isArray() ? parseEntity(item) : null;
            if (e != null && seen.add(nameKey(e.name()))) {
                out.entities.add(e);
            } else {
                out.skipped++;
            }
        }
        for (JsonNode item : items(value, "s")) {
            OpenStatement s = item.isArray() ? parseStatement(item) : null;
            if (s != null) {
                out.statements.add(s);
            } else {
                out.skipped++;
            }
        }
        for (JsonNode item : items(value, "n")) {
            OpenName nm = item.isArray() ? parseName(item) : null;
            if (nm != null) {
                out.names.add(nm);
            } else {
                out.skipped++;
            }
        }
        for (JsonNode item : items(value, "t")) {
            TimeExtract.TimeEntry t = TimeExtract.timeEntry(item);
            if (t != null) {
                out.time.add(t);
            } else {
                out.skipped++;
            }
        }
        return out;
    }
}
