package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 关系短语按签名绑到属性的提示词与解析（phrase_align.rs 逐语义，0044 决定 3 的第二片）。
 * 签名 = 短语 × 主语的类 × 宾语的类（宾语是字面值时记「值」）。模型判「**每一条**这个
 * 签名下的陈述都在陈述这个属性吗」，还要说方向（forward/reverse）与一刻标哪一端（marks，
 * #966——只对状态属性有意义）。
 *
 * <p>回复是紧凑 JSON：{@code {"b": [[id, "key"|null, "forward"|"reverse"|null, marks]]}。
 * 形状宽容与类别词那边同一套：按 id 作键的对象、一条写成对象、裸数组、值里先抄一遍 id——
 * 读不出的键与 id 算坏项，不当缺席。
 */
public final class PhraseAlign {

    private PhraseAlign() {
    }

    /** phrase_align.rs:36-52 PropertyCandidate：键照抄进答案；via 是按继承命中的依据（#807：模型得看见它）。 */
    public record PropertyCandidate(String key, String label, String description, String kind,
                                    String temporal, List<String> domains, List<String> ranges,
                                    List<String> via) {
    }

    /** phrase_align.rs:54-73 PhraseItem：一条待绑定的签名。 */
    public static final class PhraseItem {
        public final long id;
        public final String phrase;
        /** the subject's class key; null when its kind word is bound to no class yet */
        public final String subjectClass;
        /** the object's class key; null when unbound, or when the object is a value */
        public final String objectClass;
        public final boolean objectIsValue;
        public final long statementCount;
        /** rendered statements with their quotes */
        public final List<String> examples;
        public final List<String> quotes;
        public final List<PropertyCandidate> candidates;
        /** 结构上也对得上、但没进短名单的键：短名单是省 token 的手段，不是限制。 */
        public final List<String> alsoAllowed;

        public PhraseItem(long id, String phrase, String subjectClass, String objectClass,
                          boolean objectIsValue, long statementCount, List<String> examples,
                          List<String> quotes, List<PropertyCandidate> candidates,
                          List<String> alsoAllowed) {
            this.id = id;
            this.phrase = phrase;
            this.subjectClass = subjectClass;
            this.objectClass = objectClass;
            this.objectIsValue = objectIsValue;
            this.statementCount = statementCount;
            this.examples = examples;
            this.quotes = quotes;
            this.candidates = candidates;
            this.alsoAllowed = alsoAllowed;
        }
    }

    /** phrase_align.rs:76-82 PhraseChoice。 */
    public record PhraseChoice(long id, Property property, Marks marks) {

        /** Some = (候选的键, 方向)；None = 不绑。 */
        public record Property(String key, Direction direction) {
        }
    }

    /** phrase_align.rs:84-112 Marks：一刻在状态上标的是哪一端（#966）。 */
    public enum Marks {
        START("start"), END("end"), NEITHER("none");

        private final String wire;

        Marks(String wire) {
            this.wire = wire;
        }

        public String asString() {
            return wire;
        }

        static Marks parse(String written) {
            String w = written.strip().toLowerCase(Locale.ROOT);
            return switch (w) {
                case "start" -> START;
                case "end" -> END;
                case "none", "neither" -> NEITHER;
                default -> null;
            };
        }
    }

    /** phrase_align.rs:114-127 Direction。 */
    public enum Direction {
        FORWARD("forward"), REVERSE("reverse");

        private final String wire;

        Direction(String wire) {
            this.wire = wire;
        }

        public String asString() {
            return wire;
        }
    }

    /** 系统消息（phrase_align.rs:130-172 逐字）。 */
    public static final String PHRASE_SYSTEM = """
            You bind the relation phrases documents use to the properties of a knowledge base's ontology. Each numbered item is one signature: a phrase as the documents wrote it, the class of the thing it is said of (its subject) and the class of what it points at (its object), or "value" when the object is a figure, a title or a status; a few statements with that signature, each with the sentence it was taken from; and the keys of its candidate properties. The properties themselves are listed once under "Properties", each with its key, its label, its kind (a relation between two things, or an attribute whose object is a value), whether it is a state (holds over a span), an event (happens at a moment) or eternal, its domain, its range and its definition. A class written as "?" means the documents' kind word for that side is bound to no class yet. A candidate key marked "fits by inheritance" declares its domain or range on an ancestor of the item's class; that is a fit, not a mismatch.
            For each item, answer with the key of the one property that every statement of this signature states by that property's definition, and the direction: "forward" when the statement's subject is the property's subject, "reverse" when the statement's object is; or null. For a state, also say which end of it a statement dated at a single moment marks (rule 6).

            Output exactly one JSON object and nothing else, one entry per item:
            {"b": [[12, "headquartered_in", "forward", "none"], [13, "member_of", "forward", "start"], [14, null, null, null]]}

            1. Choose a property only when each of the statements under this signature states that property, by the definition as given. The property may be broader than the phrase: "opened a plant in" states located_in; "is the chief executive of" states officer_of. It is never narrower and never merely related: "announced the acquisition of" is not acquired when the sentence says it was announced, not completed; "revenue grew 18%" is a change from the prior period, not revenue.
            2. Judge by the definition and by the sentences, not by the label. Labels, definitions and phrases may be in any language.
            3. Answer null when no candidate fits; when the sentences show the phrase meaning different things under this signature; when the phrase only reports, introduces or evaluates ("said", "announced", "is expected to") unless a candidate is about that; or when a value is not what the attribute measures (a share count is not revenue, a date is not an amount).
            4. The direction follows the definition: for "X —is a subsidiary of→ Y" subsidiary_of is forward; for "X —owns→ Y", if subsidiary_of is the only fitting candidate, it is reverse.
            5. Never invent a key, never answer with a label, never choose for an item a key that is not among its candidates. One entry per item, every item answered.
            6. When the property you choose is a state, the fourth value says what a statement of this signature dated at a single moment means for that state: "start" when the phrase says the state began then ("joined", "was appointed", "took over"), "end" when it says the state ended then ("left", "resigned from", "stepped down as"), "none" when the date only says the state held at that moment ("was chairman in 2019"). For an event or eternal property, or no property, the fourth value is null.""";

    /** phrase_align.rs:174-187 candidate_line：属性表的一行（via 不进总表——那是项与候选之间的事）。 */
    static String candidateLine(PropertyCandidate c) {
        StringBuilder line = new StringBuilder("- ").append(c.key()).append(" · ")
                .append(c.label()).append(" · ").append(c.kind()).append(" · ").append(c.temporal());
        if (c.domains() != null && !c.domains().isEmpty()) {
            line.append(" · domain: ").append(String.join(", ", c.domains()));
        }
        if (c.ranges() != null && !c.ranges().isEmpty()) {
            line.append(" · range: ").append(String.join(", ", c.ranges()));
        }
        if (c.via() != null && !c.via().isEmpty()) {
            line.append(" · fits by inheritance: ").append(String.join("; ", c.via()));
        }
        line.append(" · ").append(c.description());
        return line.toString();
    }

    /**
     * phrase_align.rs:193-257 build_phrase_messages：候选属性表在一批里只写一遍（Properties，
     * 从前每项都带整张表，12 项一批就是三万多 token）；「按继承对上」写在项的键后面。
     */
    public static List<ChatMessage> buildPhraseMessages(List<PhraseItem> items) {
        List<String> glossary = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PhraseItem item : items) {
            for (PropertyCandidate c : item.candidates) {
                if (seen.add(c.key().strip())) {
                    glossary.add(candidateLine(new PropertyCandidate(c.key(), c.label(),
                            c.description(), c.kind(), c.temporal(), c.domains(), c.ranges(),
                            List.of())));
                }
            }
        }
        StringBuilder user = new StringBuilder();
        if (!glossary.isEmpty()) {
            user.append("Properties:\n").append(String.join("\n", glossary)).append("\n\n");
        }
        for (PhraseItem item : items) {
            String object = item.objectIsValue ? "value"
                    : (item.objectClass != null ? item.objectClass : "?");
            StringBuilder examples = new StringBuilder();
            for (int i = 0; i < item.examples.size(); i++) {
                String quote = i < item.quotes.size() ? item.quotes.get(i) : "";
                examples.append("\n  · ").append(item.examples.get(i)).append("\n    \"")
                        .append(quote.strip()).append('"');
            }
            if (item.examples.isEmpty()) {
                examples.append(" (none)");
            }
            String candidates;
            if (item.candidates.isEmpty()) {
                candidates = " (none)";
            } else {
                List<String> keys = new ArrayList<>();
                for (PropertyCandidate c : item.candidates) {
                    if (c.via().isEmpty()) {
                        keys.add(c.key());
                    } else {
                        keys.add(c.key() + " (fits by inheritance: "
                                + String.join("; ", c.via()) + ")");
                    }
                }
                candidates = " " + String.join(", ", keys);
            }
            user.append("Item ").append(item.id).append(": phrase \"").append(item.phrase)
                    .append("\" · subject class: ")
                    .append(item.subjectClass != null ? item.subjectClass : "?")
                    .append(" · object: ").append(object).append(" · ")
                    .append(item.statementCount).append(" statements\nStatements:")
                    .append(examples).append("\nCandidates:").append(candidates).append("\n\n");
        }
        return List.of(ChatMessage.system(PHRASE_SYSTEM),
                ChatMessage.user(user.toString().stripTrailing()));
    }

    /**
     * phrase_align.rs:260-281 parse_phrase_response：同一个 id 只收第一次；没答到的 id 不出现。
     */
    public static PhraseReply parsePhraseResponse(String raw, List<PhraseItem> items) {
        JsonNode value = Align.parseValue(raw);
        Map<Long, PhraseItem> byId = new HashMap<>();
        for (PhraseItem i : items) {
            byId.put(i.id, i);
        }
        // 批里描述过的属性各是什么时间语义：只有状态读第四格
        Map<String, String> temporal = new HashMap<>();
        for (PhraseItem i : items) {
            for (PropertyCandidate c : i.candidates) {
                temporal.put(c.key().strip(), c.temporal());
            }
        }
        List<PhraseChoice> choices = new ArrayList<>();
        int[] malformed = {0};
        Set<Long> seen = new HashSet<>();
        for (Answer answer : answers(value)) {
            PhraseChoice choice = parseAnswer(answer, byId, temporal);
            if (choice != null && seen.add(choice.id())) {
                choices.add(choice);
            } else {
                malformed[0]++;
            }
        }
        return new PhraseReply(choices, malformed[0]);
    }

    /** 解析结果：裁决 + 坏项数。 */
    public record PhraseReply(List<PhraseChoice> choices, int malformed) {
    }

    /** 回复里的一条原始答案：（id，键，方向，marks）。 */
    record Answer(JsonNode id, JsonNode key, JsonNode direction, JsonNode marks) {
    }

    /**
     * phrase_align.rs:292-335 answers：认这几种写法——{@code {"b": [[id, key, dir]]}}、
     * {@code {"b": {"id": [key, dir]}}}、没有 b 的顶层对象、顶层数组；一条也可以写成对象
     * （字段别名见 {@link #fields}），按 id 作键时值也可以是对象/null/先抄一遍 id 的数组。
     */
    static List<Answer> answers(JsonNode value) {
        JsonNode listed = value.get("b");
        if (listed == null) {
            listed = value;
        }
        List<Answer> out = new ArrayList<>();
        JsonNode nullNode = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        if (listed.isArray()) {
            for (JsonNode entry : listed) {
                if (entry.isArray() && entry.size() >= 2) {
                    Triple t = keyDirectionMarks(entry, 1);
                    out.add(new Answer(entry.get(0), t.key(), t.direction(), t.marks()));
                } else if (entry.isObject()) {
                    Triple t = fields(entry);
                    JsonNode id = entry.get("id");
                    out.add(new Answer(id != null ? id : nullNode, t.key(), t.direction(),
                            t.marks()));
                } else {
                    out.add(new Answer(entry, nullNode, nullNode, nullNode));
                }
            }
        } else if (listed.isObject()) {
            Map<String, JsonNode> map = new LinkedHashMap<>();
            var fieldsIter = listed.fields();
            while (fieldsIter.hasNext()) {
                var e = fieldsIter.next();
                map.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, JsonNode> e : map.entrySet()) {
                JsonNode v = e.getValue();
                Triple t;
                if (v.isArray()) {
                    // 值里先抄一遍 id 再给键：{"0": [0, "headquartered_in", "forward"]}
                    boolean copied = false;
                    if (!v.isEmpty()) {
                        Long first = Align.itemId(v.get(0));
                        Long self = parseLong(e.getKey().strip());
                        copied = first != null && self != null && first.equals(self);
                    }
                    int from = copied ? 1 : 0;
                    t = keyDirectionMarks(v, from);
                } else if (v.isObject()) {
                    t = fields(v);
                } else {
                    t = new Triple(v, nullNode, nullNode);
                }
                out.add(new Answer(com.fasterxml.jackson.databind.node.TextNode
                        .valueOf(e.getKey()), t.key(), t.direction(), t.marks()));
            }
        }
        return out;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record Triple(JsonNode key, JsonNode direction, JsonNode marks) {
    }

    /**
     * phrase_align.rs:340-347 key_direction_marks：{@code [key, dir, marks]} 的三格；只有
     * 一格就没有方向（null 的答案常只写一格）；空数组是「什么都没写」——留成空数组标记，
     * 调用方算坏（读成 null 键会把没答到说成不绑）。
     */
    static Triple keyDirectionMarks(JsonNode list, int from) {
        JsonNode nullNode = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        int size = list.size() - from;
        if (size <= 0) {
            return new Triple(EMPTY_ARRAY, nullNode, nullNode);
        }
        JsonNode key = list.get(from);
        JsonNode dir = size >= 2 ? list.get(from + 1) : nullNode;
        JsonNode marks = size >= 3 ? list.get(from + 2) : nullNode;
        return new Triple(key, dir, marks);
    }

    private static final JsonNode EMPTY_ARRAY = new ObjectMapper().createArrayNode();

    /**
     * phrase_align.rs:349-364 fields：一条写成对象时的三个字段：键叫 key/property/p，
     * 方向叫 direction/dir/d，一刻标哪一端叫 marks/m。
     */
    static Triple fields(JsonNode map) {
        JsonNode nullNode = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        java.util.function.Function<String[], JsonNode> pick = names -> {
            for (String n : names) {
                JsonNode v = map.get(n);
                if (v != null) {
                    return v;
                }
            }
            return nullNode;
        };
        return new Triple(pick.apply(new String[]{"key", "property", "p"}),
                pick.apply(new String[]{"direction", "dir", "d"}),
                pick.apply(new String[]{"marks", "m"}));
    }

    /**
     * phrase_align.rs:370-419 parse_answer：id 得是这批里的；键是 null（不绑）或候选中的
     * 一个，绑了就得有方向，方向不认识算坏。键包在数组里的照样读。marks 只在绑到状态属性
     * 时读：null/缺席是没说，写了却认不出算坏；事件、恒常不读这一格，写了什么都忽略。
     */
    static PhraseChoice parseAnswer(Answer a, Map<Long, PhraseItem> byId,
                                    Map<String, String> temporal) {
        Long id = Align.itemId(a.id());
        if (id == null) {
            return null;
        }
        PhraseItem item = byId.get(id);
        if (item == null) {
            return null;
        }
        JsonNode keyNode = a.key();
        JsonNode directionNode = a.direction();
        JsonNode marksNode = a.marks();
        if (keyNode != null && keyNode.isArray() && keyNode.size() > 0) {
            Triple t = keyDirectionMarks(keyNode, 0);
            keyNode = t.key();
            if (t.direction().isNull()) {
                t = new Triple(t.key(), directionNode, t.marks());
            }
            if (t.marks().isNull()) {
                t = new Triple(t.key(), t.direction(), marksNode);
            }
            directionNode = t.direction();
            marksNode = t.marks();
        }
        PhraseChoice.Property property = null;
        if (keyNode != null && keyNode.isNull()) {
            property = null;
        } else if (keyNode != null && keyNode.isTextual()) {
            String key = candidateKey(item, keyNode.asText());
            if (key == null) {
                return null;
            }
            Direction dir;
            if (directionNode != null && directionNode.isTextual()) {
                String d = directionNode.asText().strip().toLowerCase(Locale.ROOT);
                dir = switch (d) {
                    case "forward" -> Direction.FORWARD;
                    case "reverse" -> Direction.REVERSE;
                    default -> null;
                };
            } else {
                dir = null;
            }
            if (dir == null) {
                return null;
            }
            property = new PhraseChoice.Property(key, dir);
        } else {
            return null;
        }
        boolean state = property != null && "state".equals(temporal.get(property.key()));
        Marks marks = null;
        if (state && marksNode != null && !marksNode.isNull()) {
            if (!marksNode.isTextual()) {
                return null;
            }
            marks = Marks.parse(marksNode.asText());
            if (marks == null) {
                return null;
            }
        }
        return new PhraseChoice(id, property, marks);
    }

    /**
     * phrase_align.rs:421-451 candidate_key：先原样，再不分大小写（唯一命中）；键是
     * {@code p569} 这种代号时模型十有八九答标签——标签折叠后唯一对上就认它的键。
     * 也允许 {@code also_allowed} 里的键（结构上对得上、没进短名单）。
     */
    static String candidateKey(PhraseItem item, String written) {
        String trimmed = written.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        java.util.stream.Stream<String> keys = java.util.stream.Stream.concat(
                item.candidates.stream().map(c -> c.key().strip()),
                item.alsoAllowed.stream().map(String::strip));
        List<String> all = keys.toList();
        for (String k : all) {
            if (k.equals(trimmed)) {
                return k;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String hit = null;
        int hits = 0;
        for (String k : all) {
            if (k.toLowerCase(Locale.ROOT).equals(lower)) {
                hit = k;
                hits++;
            }
        }
        if (hits == 1) {
            return hit;
        }
        if (hits > 1) {
            return null;
        }
        // 标签折叠对上（唯一命中才认）
        String folded = fold(trimmed);
        String labelHit = null;
        int labelHits = 0;
        for (PropertyCandidate c : item.candidates) {
            if (fold(c.label()).equals(folded)) {
                labelHit = c.key().strip();
                labelHits++;
            }
        }
        return labelHits == 1 ? labelHit : null;
    }

    /** phrase_align.rs:453-459 fold：标签比较用的折叠——大小写、空格、下划线、连字符都不算。 */
    public static String fold(String s) {
        StringBuilder out = new StringBuilder();
        s.toLowerCase(Locale.ROOT).codePoints().forEach(c -> {
            if (!Character.isWhitespace(c) && c != '_' && c != '-') {
                out.appendCodePoint(c);
            }
        });
        return out.toString();
    }
}
