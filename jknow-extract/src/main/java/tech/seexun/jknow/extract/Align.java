package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 种类词绑定到类的提示词与解析（align.rs 逐语义，0044 决定 3–4 的第一步）。
 * 模型判的是「**每一个**这种东西都是这个类的实例吗」——类可以比词宽（面包房是组织），
 * 不能比词窄、也不能只是沾边。角色词、描述词、一词两义都答 null。
 *
 * <p>回复是紧凑 JSON：{@code {"b": [[id, "key" | null]]}}。<b>形状也宽容</b>（实测
 * DeepSeek-V3.2）：整段按 id 作键的对象、键包进单元素数组、一对写成对象、裸数组——说的
 * 都是同一件事，读法只有一种；温度为零时同一段提示词回来的形状还是同一个，读不出就是
 * 每一轮都读不出。读不出的键与 id 算坏项，不当缺席。
 */
public final class Align {

    private Align() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** align.rs:31-36 ClassCandidate：键照抄进答案；标签与定义是模型判断的依据。 */
    public record ClassCandidate(String key, String label, String description) {
    }

    /** align.rs:38-51 KindWordItem：一个待绑定的种类词签名。 */
    public static final class KindWordItem {
        public final long id;
        /** the normalised kind word ("company") */
        public final String kindWord;
        /** spellings seen in documents ("Company", "company", "公司") */
        public final List<String> spellings;
        /** example entity names of this kind */
        public final List<String> examples;
        /** relation phrases these things are subjects of */
        public final List<String> phrases;
        public final List<ClassCandidate> candidates;

        public KindWordItem(long id, String kindWord, List<String> spellings,
                            List<String> examples, List<String> phrases,
                            List<ClassCandidate> candidates) {
            this.id = id;
            this.kindWord = kindWord;
            this.spellings = spellings;
            this.examples = examples;
            this.phrases = phrases;
            this.candidates = candidates;
        }
    }

    /** align.rs:53-58 KindWordChoice：Some = 那个候选的键（从候选里抄的，不是模型写的）。 */
    public record KindWordChoice(long id, String key) {
    }

    /** 系统消息（align.rs:62-91 逐字；例子是中性的，不出自任何测量语料）。 */
    public static final String KIND_WORD_SYSTEM = """
            You bind the words documents use for kinds of things to the classes of a knowledge base's ontology. Each numbered item is one kind word as the documents wrote it: the word, the spellings seen, some things of this kind by name, the phrases those things take part in, and the keys of its candidate classes. The classes themselves are listed once under "Classes", each with its key, its label and its definition. For each item, answer with the key of the one candidate class that every thing of this kind is an instance of, or null.

            Output exactly one JSON object and nothing else, one pair per item:
            {"b": [[12, "organization"], [13, null]]}
            The first element of a pair is the item's id; the second is the chosen key, copied exactly as listed under that item, or null.

            1. Choose a candidate only when every thing of this kind is an instance of that class by the class's definition. The class may be broader than the word: a bakery is an organization, a bridge is a structure. It is never narrower and never merely related: a contract is not a document when the definition says a document is a file; a town council is not a town; a bakery is not a building because bakeries are in buildings.
            2. Judge by the definition as given, not by the label or the key alone. Labels and definitions may be written in any language, and so may the kind word; a word in another script or language is the same word when it names the same kind.
            3. Answer null when no candidate fits; when the word names a role, a part or a description rather than a kind of thing ("participants", "results", "the northern wing"); or when the examples and phrases show two kinds of thing under one word (a "bank" that is a lender in one document and a riverbank in another).
            4. The spellings, examples and phrases are the evidence of what the word means in these documents: things that "were founded by" and "are based in" are organizations; things that "were signed on" and "expire" are agreements. Read them before the label.
            5. Never invent a key, never answer with a label or a word of your own, and never choose for an item a key that is not among its candidates. One pair per item, every item answered.""";

    /**
     * align.rs:100-138 build_kind_word_messages：常量系统消息 + 用户消息。类的定义表一批只
     * 写一遍（按第一次出现的次序）——从前每项下面各列一遍候选的定义，一批二十个词、每个
     * 十条候选，同一张表写二十遍。每项：id、词、拼写、例子、短语、候选的键（次序照给的，
     * 第二票倒着给就倒着列）。空清单写 {@code (none)}。
     */
    public static List<ChatMessage> buildKindWordMessages(List<KindWordItem> items) {
        List<String> glossary = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KindWordItem item : items) {
            for (ClassCandidate c : item.candidates) {
                if (seen.add(c.key().strip())) {
                    glossary.add(candidateLine(c));
                }
            }
        }
        StringBuilder user = new StringBuilder();
        if (!glossary.isEmpty()) {
            user.append("Classes:\n").append(String.join("\n", glossary)).append("\n\n");
        }
        for (KindWordItem item : items) {
            String candidates;
            if (item.candidates.isEmpty()) {
                candidates = " (none)";
            } else {
                List<String> keys = new ArrayList<>();
                for (ClassCandidate c : item.candidates) {
                    keys.add(c.key().strip());
                }
                candidates = " " + String.join(", ", keys);
            }
            user.append("Item ").append(item.id).append(": \"").append(item.kindWord).append("\"\n")
                    .append("Spellings: ").append(quotedList(item.spellings)).append('\n')
                    .append("Examples: ").append(quotedList(item.examples)).append('\n')
                    .append("Phrases: ").append(quotedList(item.phrases)).append('\n')
                    .append("Candidates:").append(candidates).append("\n\n");
        }
        return List.of(ChatMessage.system(KIND_WORD_SYSTEM),
                ChatMessage.user(user.toString().stripTrailing()));
    }

    /** align.rs:140-150 quoted_list：{@code "a", "b"}；空清单是 {@code (none)}。带引号因为名字里可能有逗号。 */
    static String quotedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "(none)";
        }
        List<String> quoted = new ArrayList<>();
        for (String v : values) {
            quoted.add("\"" + v.strip() + "\"");
        }
        return String.join(", ", quoted);
    }

    /** align.rs:152-165 candidate_line：{@code - key: label — description}；同/空省略。 */
    static String candidateLine(ClassCandidate c) {
        String key = c.key().strip();
        StringBuilder line = new StringBuilder("- ").append(key);
        String label = c.label() == null ? "" : c.label().strip();
        if (!label.isEmpty() && !label.equals(key)) {
            line.append(": ").append(label);
        }
        String description = c.description() == null ? "" : c.description().strip();
        if (!description.isEmpty()) {
            line.append(" — ").append(description);
        }
        return line.toString();
    }

    /**
     * align.rs:173-189 parse_kind_word_response：逐项宽容地解回复，返回（裁决, 坏项数）。
     * 一对坏在：不是数组、不足两格、id 不是整数、id 不在这批里、键不是 null 也不是字符串、
     * 键不在那一项的候选里、同一个 id 已经答过（留第一次）。键先按原样对候选，对不上再不
     * 分大小写对——对上了写回候选自己的键。
     */
    public static KindWordReply parseKindWordResponse(String raw, List<KindWordItem> items) {
        JsonNode value = parseValue(raw);
        Map<Long, KindWordItem> byId = new HashMap<>();
        for (KindWordItem i : items) {
            byId.put(i.id, i);
        }
        List<KindWordChoice> choices = new ArrayList<>();
        int[] malformed = {0};
        Set<Long> seen = new HashSet<>();
        for (Map.Entry<JsonNode, JsonNode> answer : answers(value)) {
            KindWordChoice choice = parsePair(answer.getKey(), answer.getValue(), byId);
            if (choice != null && seen.add(choice.id())) {
                choices.add(choice);
            } else {
                malformed[0]++;
            }
        }
        return new KindWordReply(choices, malformed[0]);
    }

    /** 解析结果：裁决 + 坏项数。 */
    public record KindWordReply(List<KindWordChoice> choices, int malformed) {
    }

    /**
     * align.rs:195-218 parse_value：先按常规取块；解不开才从第一个 {@code {} 取到结尾去
     * 修补。回复不要外层对象、直接给数组的，取第一个 {@code [} 到最后一个 {@code ]}——只在
     * 文字本身以 {@code [} 开头时这么读。
     */
    public static JsonNode parseValue(String raw) {
        String text = JsonFuns.jsonText(raw).strip();
        if (text.startsWith("[")) {
            int end = text.lastIndexOf(']');
            if (end >= 0) {
                try {
                    return JSON.readTree(text.substring(0, end + 1));
                } catch (Exception ignore) {
                    // 解不开走下面的路
                }
            }
        }
        Exception first = null;
        String block = null;
        try {
            block = JsonFuns.jsonBlock(raw);
        } catch (Exception e) {
            first = e;
        }
        if (block != null) {
            try {
                return JSON.readTree(block);
            } catch (Exception e) {
                first = e;
            }
        }
        int at = text.indexOf('{');
        String fixed = at >= 0 ? OpenParser.repairTruncatedCompact(text.substring(at)) : null;
        if (fixed == null) {
            throw new IllegalArgumentException(
                    "Failed to parse kind-word binding JSON: " + first);
        }
        try {
            return JSON.readTree(fixed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse kind-word binding JSON: " + e);
        }
    }

    /**
     * align.rs:226-250 answers：回复里的每一条答案——（id，键）两个原始值，形状还没验。
     * 认四种写法；{@code b} 在就只看 {@code b}，顶层别的键是模型的旁白。缺了 {@code b}
     * 又不是对象或数组的，一条都没有。
     */
    static List<Map.Entry<JsonNode, JsonNode>> answers(JsonNode value) {
        JsonNode listed = value.get("b");
        if (listed == null) {
            listed = value;
        }
        List<Map.Entry<JsonNode, JsonNode>> out = new ArrayList<>();
        if (listed.isArray()) {
            for (JsonNode entry : listed) {
                if (entry.isArray() && entry.size() >= 2) {
                    out.add(Map.entry(entry.get(0), entry.get(1)));
                } else if (entry.isObject()) {
                    JsonNode key = entry.get("key");
                    if (key == null) {
                        key = entry.get("class");
                    }
                    if (key == null) {
                        key = entry.get("b");
                    }
                    out.add(Map.entry(entry.get("id") != null ? entry.get("id")
                            : NULL_NODE, key != null ? key : NULL_NODE));
                } else {
                    out.add(Map.entry(entry, NULL_NODE));
                }
            }
        } else if (listed.isObject()) {
            var fields = listed.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                out.add(Map.entry(JSON.getNodeFactory().textNode(e.getKey()), e.getValue()));
            }
        }
        return out;
    }

    private static final JsonNode NULL_NODE =
            com.fasterxml.jackson.databind.node.NullNode.getInstance();

    /**
     * align.rs:255-268 parse_pair：id 得是这批里的，键得是 null 或候选中的一个（数组里
     * 包着的也算——{@code ["organization"]}、{@code [null]} 是模型的另几种写法）。
     */
    static KindWordChoice parsePair(JsonNode idNode, JsonNode keyNode,
                                     Map<Long, KindWordItem> byId) {
        Long id = itemId(idNode);
        if (id == null) {
            return null;
        }
        KindWordItem item = byId.get(id);
        if (item == null) {
            return null;
        }
        JsonNode unwrapped = unwrapped(item, id, keyNode);
        if (unwrapped == null) {
            return null;
        }
        String key;
        if (unwrapped.isNull()) {
            key = null;
        } else if (unwrapped.isTextual()) {
            key = candidateKey(item, unwrapped.asText());
            if (key == null) {
                return null;
            }
        } else {
            return null;
        }
        return new KindWordChoice(id, key);
    }

    /**
     * align.rs:274-290 unwrapped：模型会把键包进数组，后面有时跟着那个候选的标签与定义，
     * 前面有时又抄一遍 id（跳过）。后面跟的若是<b>另一个候选的键</b>，那是两个答案——null。
     * 空数组也是 null；不是数组的原样。
     */
    static JsonNode unwrapped(KindWordItem item, long id, JsonNode v) {
        if (v == null || !v.isArray()) {
            return v == null ? NULL_NODE : v;
        }
        List<JsonNode> list = new ArrayList<>();
        v.forEach(list::add);
        int from = 0;
        if (!list.isEmpty()) {
            Long first = itemId(list.get(0));
            if (first != null && first == id) {
                from = 1;
            }
        }
        if (from >= list.size()) {
            return null;
        }
        JsonNode first = list.get(from);
        for (int i = from + 1; i < list.size(); i++) {
            JsonNode other = list.get(i);
            if (other.isTextual() && candidateKey(item, other.asText()) != null) {
                return null; // 两个答案不是一个答案
            }
        }
        return first;
    }

    /** align.rs:292-299 item_id：id 是 JSON 整数；模型偶尔写成字符串，照数字读。 */
    static Long itemId(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * align.rs:301-316 candidate_key：模型写的键对回这一项的候选：先原样，再不分大小写
     * （只在唯一命中时）；返回候选自己的键。空串与不在候选里的都是 null。
     */
    static String candidateKey(KindWordItem item, String written) {
        String trimmed = written.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        String exact = null;
        for (ClassCandidate c : item.candidates) {
            if (c.key().strip().equals(trimmed)) {
                exact = c.key().strip();
                break;
            }
        }
        if (exact != null) {
            return exact;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String hit = null;
        int hits = 0;
        for (ClassCandidate c : item.candidates) {
            if (c.key().strip().toLowerCase(Locale.ROOT).equals(lower)) {
                hit = c.key().strip();
                hits++;
            }
        }
        return hits == 1 ? hit : null;
    }
}
