package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tech.seexun.jknow.extract.PhraseAlign.PropertyCandidate;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 蕴含规则的两次模型调用（extract/implication.rs 逐语义，0044 决定 3 第五片）：
 * <b>提规则</b>（{@link #RULE_SYSTEM}）与<b>读数</b>（{@link #READING_SYSTEM}）。
 *
 * <p>提规则：给对齐器刚判过的签名（或类别词），问「这种形状的陈述除了它绑到的属性，
 * 还蕴含哪条属性的事实，宾语怎么来」。答案是 (属性, 读数或 null)；null 属性 = 什么也
 * 不蕴含。读数：给 distinct 的字，问「按这种读法它指什么」，答一个名字或一个值或 null。
 * 读数按字缓存，一个字一辈子只问一次；提示词里只有字和读法，没有文档。
 *
 * <p>回复都是紧凑 JSON，解析同 phrase_align：坏的一条计数，不毁掉整批；没答到的 id
 * 不出现。m4-plan 文件结构里这块记作 ReadingPrompts——随提规则测试一并落地，索性按
 * Rust 单文件（extract/implication.rs）的布局合成一个类。
 */
public final class ImplicationPrompts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ImplicationPrompts() {
    }

    /** 一条待提规则的形状（implication.rs:15-27 RuleItem）：签名或类别词，带例句与候选属性。 */
    public record RuleItem(long id, String trigger, String phrase, String subjectClass,
                           String objectClass, boolean objectIsValue, String boundTo,
                           List<String> examples, List<PropertyCandidate> candidates) {
    }

    /** 模型对一条形状的回答（implication.rs:30-33 RuleChoice）：implies=null 即什么也不蕴含。 */
    public record RuleChoice(long id, Implies implies) {
    }

    /** 蕴含的属性键与读法（读法可空——宾语就是陈述自己的宾语）。 */
    public record Implies(String property, String reading) {
    }

    /**
     * implication.rs:35-53 RULE_SYSTEM 逐字（{READINGS} 占位由
     * {@link #buildRuleMessages} 换成读数清单）。
     */
    public static final String RULE_SYSTEM = "You find facts a careful reader draws from a statement without the text stating them. "
            + "Each numbered item is one shape: a relation phrase between a subject class and an object class "
            + "(or a value), or a kind word the documents use for a thing, with example statements. The item "
            + "may already be bound to a property; that binding is not the question. The question is whether "
            + "this shape implies a fact of ANOTHER candidate property about the subject, and how the object "
            + "of that fact is obtained:\n"
            + "- null: the object is the statement's own object (the shape implies a second property about the same pair);\n"
            + "- a reading: the object is read from the words of the statement's object (for a kind word, from the kind word itself). "
            + "Readings available, by name:\n"
            + "{READINGS}\n"
            + "Examples of what is implied: a kind word \"British film\" implies country of origin = United Kingdom "
            + "(reading country_of_nationality); \"located in the Piedmont region of Virginia\" implies country = "
            + "United States (reading country_of_place); \"released in the summer of 1952\" implies publication year "
            + "(reading year_of_phrase). Answer null when nothing beyond the binding is implied, when the implication "
            + "would only sometimes hold, or when no candidate property fits.\n"
            + "Answer with one JSON object and nothing else: {\"i\": [[id, \"property_key\" | null, \"reading\" | null]]}. "
            + "Every item id appears exactly once.";

    /** implication.rs:55-60 readings_text：读数清单（- 键: 描述 一行一条）。 */
    static String readingsText(String[][] readings) {
        StringBuilder out = new StringBuilder();
        for (String[] r : readings) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("- ").append(r[0]).append(": ").append(r[1]);
        }
        return out.toString();
    }

    /** implication.rs:62-134 build_rule_messages：候选表一批只写一遍，各项只列键。 */
    public static List<ChatMessage> buildRuleMessages(List<RuleItem> items, String[][] readings) {
        // 候选表一批只写一遍，各项只列键（与 phrase_align::build_phrase_messages 同一条理由：
        // 每项带整张表时一次请求五万多 token）
        List<String> glossary = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RuleItem item : items) {
            for (PropertyCandidate c : item.candidates()) {
                if (seen.add(c.key().trim())) {
                    glossary.add(PhraseAlign.candidateLine(new PropertyCandidate(c.key(),
                            c.label(), c.description(), c.kind(), c.temporal(), c.domains(),
                            c.ranges(), List.of())));
                }
            }
        }
        StringBuilder user = new StringBuilder();
        if (!glossary.isEmpty()) {
            user.append("Properties:\n").append(String.join("\n", glossary)).append("\n\n");
        }
        for (RuleItem item : items) {
            String shape;
            if ("kind_word".equals(item.trigger())) {
                shape = "kind word \"" + item.phrase() + "\"";
            } else {
                String object = item.objectIsValue() ? "value"
                        : (item.objectClass() != null ? item.objectClass() : "?");
                shape = "phrase \"" + item.phrase() + "\" · subject class: "
                        + (item.subjectClass() != null ? item.subjectClass() : "?")
                        + " · object: " + object;
            }
            StringBuilder examples = new StringBuilder();
            for (String ex : item.examples()) {
                examples.append("\n  · ").append(ex);
            }
            if (examples.isEmpty()) {
                examples.append(" (none)");
            }
            String bound = item.boundTo() != null ? " · already bound to: " + item.boundTo() : "";
            String candidates;
            if (item.candidates().isEmpty()) {
                candidates = " (none)";
            } else {
                List<String> keys = new ArrayList<>();
                for (PropertyCandidate c : item.candidates()) {
                    keys.add(c.key().trim());
                }
                candidates = " " + String.join(", ", keys);
            }
            user.append("Item ").append(item.id()).append(": ").append(shape).append(bound)
                    .append("\nExamples:").append(examples).append("\nCandidates:")
                    .append(candidates).append("\n\n");
        }
        return List.of(
                ChatMessage.system(RULE_SYSTEM.replace("{READINGS}", readingsText(readings))),
                ChatMessage.user(user.toString().stripTrailing()));
    }

    /**
     * implication.rs:137-185 parse_rule_response：解析提规则的回复 → (裁决, 坏项数)。
     * 同一个 id 只收第一次；属性键不在候选里、读数不在清单里、属性就是已绑到的那条，
     * 都算坏。JSON 读不出抛 {@link IllegalArgumentException}。
     */
    public static RuleReply parseRuleResponse(String raw, List<RuleItem> items,
                                              String[][] readings) {
        JsonNode v;
        try {
            v = JSON.readTree(extractJson(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        JsonNode rows = v == null ? null : v.get("i");
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("no \"i\" array");
        }
        List<RuleChoice> out = new ArrayList<>();
        int malformed = 0;
        // 坏行不占 id：只有收下的答案才算答过，后面同 id 的完好答案还能收；收过再来的才是重复
        Set<Long> seen = new HashSet<>();
        for (JsonNode row : rows) {
            RuleChoice choice = parseRuleRow(row, items, readings, seen);
            if (choice != null) {
                seen.add(choice.id());
                out.add(choice);
            } else {
                malformed++;
            }
        }
        return new RuleReply(out, malformed);
    }

    /** parse_rule_response 的返回（Rust 元组）。 */
    public record RuleReply(List<RuleChoice> choices, int malformed) {
    }

    private static RuleChoice parseRuleRow(JsonNode row, List<RuleItem> items,
                                           String[][] readings, Set<Long> seen) {
        if (!row.isArray() || row.isEmpty() || !row.get(0).isIntegralNumber()) {
            return null;
        }
        long id = row.get(0).asLong();
        RuleItem item = null;
        for (RuleItem i : items) {
            if (i.id() == id) {
                item = i;
                break;
            }
        }
        if (item == null || seen.contains(id)) {
            return null;
        }
        String key = row.size() > 1 && row.get(1).isTextual() ? row.get(1).asText() : null;
        String reading = row.size() > 2 && row.get(2).isTextual() ? row.get(2).asText() : null;
        if (key == null) {
            return new RuleChoice(id, null);
        }
        String k = resolveKey(item, key);
        if (k == null || k.equals(item.boundTo())) {
            return null;
        }
        if (reading != null) {
            boolean known = false;
            for (String[] r : readings) {
                if (r[0].equals(reading)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return null;
            }
        }
        // 类别词自己没有宾语：没有读数就没有宾语，这条答案没意义
        if ("kind_word".equals(item.trigger()) && reading == null) {
            return null;
        }
        return new RuleChoice(id, new Implies(k, reading));
    }

    /**
     * implication.rs:300-319 resolve_key：模型写的键——候选里的键（大小写不论），或唯一
     * 对上的标签。键是 {@code p569} 这种代号时模型常答标签（与
     * {@code phrase_align::candidate_key} 同一条理由）。
     */
    static String resolveKey(RuleItem item, String written) {
        String trimmed = written.trim();
        for (PropertyCandidate c : item.candidates()) {
            if (c.key().trim().equals(trimmed)) {
                return c.key();
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        PropertyCandidate byKey = null;
        int keyHits = 0;
        for (PropertyCandidate c : item.candidates()) {
            if (c.key().trim().toLowerCase(Locale.ROOT).equals(lower)) {
                byKey = c;
                keyHits++;
            }
        }
        // 大小写折完对上一条就是答案；对上多条不猜（Rust then_some(None) 直接返回）
        if (byKey != null) {
            return keyHits == 1 ? byKey.key() : null;
        }
        String folded = PhraseAlign.fold(written);
        PropertyCandidate byLabel = null;
        int labelHits = 0;
        for (PropertyCandidate c : item.candidates()) {
            if (PhraseAlign.fold(c.label()).equals(folded)) {
                byLabel = c;
                labelHits++;
            }
        }
        return labelHits == 1 ? byLabel.key() : null;
    }

    // ---- 读数（implication.rs:189-266） ----

    /** 一条待读的字（implication.rs:191-196 ReadingItem）。 */
    public record ReadingItem(long id, String reading, String phrase) {
    }

    /** 读数的答案（implication.rs:199-204）：一个名字（库里的一样东西）、一个值，或读不出来。 */
    public record ReadingAnswer(long id, String name, String value) {
    }

    /**
     * implication.rs:206-216 READING_SYSTEM 逐字（{READINGS} 占位由
     * {@link #buildReadingMessages} 换成读数清单）。
     */
    public static final String READING_SYSTEM = "You read a short phrase in a stated way and answer what it names. Each numbered item gives the "
            + "reading and the phrase. Readings:\n"
            + "{READINGS}\n"
            + "Answer the canonical English name of the thing (a country's common name, e.g. \"United Kingdom\", "
            + "\"United States\"), or for year_of_phrase the four-digit year as a string, or null when the phrase "
            + "does not determine an answer (an ambiguous demonym, a place you cannot place, no year in the words). "
            + "Do not guess. Answer with one JSON object and nothing else: {\"r\": [[id, \"answer\" | null]]}. "
            + "Every item id appears exactly once.";

    /** implication.rs:218-235 build_reading_messages：user 行 {@code Item {i}: {reading} · "{phrase}"}。 */
    public static List<ChatMessage> buildReadingMessages(List<ReadingItem> items,
                                                         String[][] readings) {
        StringBuilder user = new StringBuilder();
        for (ReadingItem i : items) {
            if (user.length() > 0) {
                user.append('\n');
            }
            user.append("Item ").append(i.id()).append(": ").append(i.reading())
                    .append(" · \"").append(i.phrase()).append("\"");
        }
        return List.of(
                ChatMessage.system(READING_SYSTEM.replace("{READINGS}", readingsText(readings))),
                ChatMessage.user(user.toString()));
    }

    /**
     * implication.rs:237-276 parse_reading_response：解析读数的回复 → (答案, 坏项数)。
     * 年份读数的答案落成值，其余落成名字；空串、非四位数的年份算坏。null=读不出来，
     * 也算答了（调用方照样落缓存，别再问）。JSON 读不出抛
     * {@link IllegalArgumentException}。
     */
    public static ReadingReply parseReadingResponse(String raw, List<ReadingItem> items) {
        JsonNode v;
        try {
            v = JSON.readTree(extractJson(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        JsonNode rows = v == null ? null : v.get("r");
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("no \"r\" array");
        }
        List<ReadingAnswer> out = new ArrayList<>();
        int malformed = 0;
        Set<Long> seen = new HashSet<>();
        for (JsonNode row : rows) {
            ReadingAnswer answer = parseReadingRow(row, items, seen);
            if (answer != null) {
                seen.add(answer.id());
                out.add(answer);
            } else {
                malformed++;
            }
        }
        return new ReadingReply(out, malformed);
    }

    /** parse_reading_response 的返回（Rust 元组）。 */
    public record ReadingReply(List<ReadingAnswer> answers, int malformed) {
    }

    private static ReadingAnswer parseReadingRow(JsonNode row, List<ReadingItem> items,
                                                 Set<Long> seen) {
        if (!row.isArray() || row.isEmpty() || !row.get(0).isIntegralNumber()) {
            return null;
        }
        long id = row.get(0).asLong();
        ReadingItem item = null;
        for (ReadingItem i : items) {
            if (i.id() == id) {
                item = i;
                break;
            }
        }
        if (item == null || seen.contains(id)) {
            return null;
        }
        JsonNode a = row.size() > 1 ? row.get(1) : null;
        String answer;
        if (a == null || a.isNull()) {
            answer = null;
        } else if (a.isTextual()) {
            answer = a.asText().trim();
        } else if (a.isNumber()) {
            answer = a.asText();
        } else {
            // 怪形态（布尔/数组/对象）当「读不出来」落缓存——Rust `_ => None` 同款，
            // 不是 malformed（审查 M-2：malformed 会重问，双空答案不重问）
            return new ReadingAnswer(id, null, null);
        }
        if (answer == null) {
            // 读不出来也是答案：落缓存，别再问
            return new ReadingAnswer(id, null, null);
        }
        if (answer.isEmpty()) {
            return null;
        }
        if ("year_of_phrase".equals(item.reading())) {
            if (answer.length() == 4 && answer.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return new ReadingAnswer(id, null, answer);
            }
            return null;
        }
        return new ReadingAnswer(id, answer, null);
    }

    /** implication.rs:324-329 extract_json：取第一个 { 到最后一个 }，取不到原样（随后 JSON 解析报错）。 */
    static String extractJson(String raw) {
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        return b > a ? raw.substring(a, b + 1) : raw;
    }
}
