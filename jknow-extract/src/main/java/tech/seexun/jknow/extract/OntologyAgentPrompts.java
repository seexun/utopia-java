package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 本体代理的一次提案调用（extract/ontology_agent.rs 540 行逐语义，0061 决定 2）。
 *
 * <p>输入是开放图谱里本体接不住的形状——对齐判成「无」或「判不定」的签名（短语 × 两端
 * 的类，带例句与引文）和绑不到类的类别词（带例名）——外加现在的本体（类与属性的定义、
 * 定义域、值域）和库的能力问题。问的是：<b>加上哪几个类、哪几条属性，这些形状就接得住、
 * 这些问题就答得了</b>。每条提案带一份写给对齐器看的定义、它会绑上的形状、它服务的问题；
 * 本体里已经有的属性接得住的形状写在 {@code existing} 里，不另提。代理只提，不写本体
 * （0012：本体是人签的契约）。
 *
 * <p>回复是紧凑 JSON，解析同 phrase_align：坏的一条计数、不毁掉整批；键不合法、形状 id
 * 不在批里都算坏。
 */
public final class OntologyAgentPrompts {

    private OntologyAgentPrompts() {
    }

    /** 一条对齐接不住的签名（OpenSignature）。 */
    public record OpenSignature(long id, String phrase, String subjectClass, String objectClass,
                                boolean objectIsValue, long statementCount, List<String> examples,
                                List<String> quotes) {
    }

    /** 一个绑不到类的类别词（OpenKindWord）。 */
    public record OpenKindWord(long id, String kindWord, long count, List<String> examples,
                               List<String> phrases) {
    }

    /** 词表里的一条属性：(key, label, kind, domains, ranges, description)。 */
    public record GlossaryProperty(String key, String label, String kind, List<String> domains,
                                   List<String> ranges, String description) {
    }

    /** 本体现状：类与属性，照库里的语言给（Glossary）。{@code classes}/{@code others} 的元素是 (key, label[, description])。 */
    public record Glossary(List<String[]> classes, List<GlossaryProperty> properties,
                           List<String[]> others) {

        public static Glossary empty() {
            return new Glossary(List.of(), List.of(), List.of());
        }
    }

    /** 一条能力问题（QuestionItem）。 */
    public record QuestionItem(long id, String text) {
    }

    /** 代理提的一条元素（Proposal）。 */
    public record Proposal(String kind, String key, String label, String definition,
                           boolean value, String datatype, List<String> domains,
                           List<String> ranges, List<String> parents, List<Long> signatures,
                           List<Long> kindWords, List<Long> questions) {
    }

    /**
     * 「本体里已经有」：现有属性或类的键，与它接得住的形状。
     * {@code direction} = forward | reverse：形状的主语是不是属性的主语（类别词对类的
     * 没有方向）；解析层把缺失/空白归一成 {@code forward}（与 Rust 的默认一致）。
     */
    public record Existing(String key, String direction, List<Long> signatures,
                           List<Long> kindWords) {
    }

    /** parse_proposal_response 的产物：提案、已有、坏项计数与逐条理由。 */
    public record ProposalReply(List<Proposal> proposals, List<Existing> existing, int malformed,
                                List<String> reasons) {
    }

    /**
     * ontology_agent.rs:119-148 SYSTEM 逐字（Rust 行尾 {@code \} 续行折掉换行与前导空白，
     * 续行处留一个空格——这里是拼好的整段）。
     */
    public static final String SYSTEM = "You are the ontology agent of a knowledge base. The base extracts statements from documents as "
            + "they are worded; an aligner then binds each statement shape (a relation phrase between a subject class and an object class, "
            + "or a kind word the documents use for a thing) to a property or class of the ontology. The shapes below are the ones the ontology cannot hold: "
            + "no property or class fits them. The competency questions are what the base exists to answer.\n"
            + "Propose the smallest set of new classes and properties that would let the base hold these shapes "
            + "AND serve the questions. Rules:\n"
            + "- A proposal binds shapes: list the shape ids it would bind (s for signatures, k for kind words). "
            + "Do not propose an element that binds nothing.\n"
            + "- Prefer serving a question. An element that serves no question must bind at least two statements' "
            + "worth of shapes, or be a class that several kind words name.\n"
            + "- Write the definition for the aligner, in the language of the ontology: one or two sentences saying "
            + "what the property asserts, what its subject and object are, and what it does NOT cover.\n"
            + "- A property whose object is a value (a number, a date, text) is an attribute: value=true with a "
            + "datatype (number | date | text | bool) and no ranges. Otherwise value=false with domains and ranges "
            + "as class keys, existing or proposed in this same answer.\n"
            + "- Keys are snake_case ASCII. Reuse an existing key only under existing, never as a proposal.\n"
            + "- If an existing property or class already holds a shape, list it under existing with the shape ids "
            + "instead of proposing, with dir: forward when the shape's subject is the property's subject, reverse "
            + "when the shape reads the property backwards (\"X wrote Y\" is author read in reverse).\n"
            + "- Do not propose the inverse of an existing property: list the shape under that property with dir "
            + "reverse.\n"
            + "- An existing entry holds shapes, not questions: every entry under existing lists at least one "
            + "shape id in s or k. Do not add an entry only to say that a property serves a question.\n"
            + "Answer with one JSON object and nothing else:\n"
            + "{\"p\":[{\"kind\":\"property\",\"key\":\"...\",\"label\":\"...\",\"definition\":\"...\",\"value\":false,"
            + "\"datatype\":null,\"domains\":[\"class_key\"],\"ranges\":[\"class_key\"],\"s\":[ids],\"k\":[ids],\"q\":[ids]},"
            + "{\"kind\":\"class\",\"key\":\"...\",\"label\":\"...\",\"definition\":\"...\",\"parents\":[\"class_key\"],"
            + "\"s\":[],\"k\":[ids],\"q\":[ids]}],\"existing\":[{\"key\":\"existing_key\",\"dir\":\"forward\",\"s\":[ids],\"k\":[ids]}]}";

    /** ontology_agent.rs:150-250 build_proposal_messages 逐字拼装。 */
    public static List<ChatMessage> buildProposalMessages(List<OpenSignature> signatures,
                                                          List<OpenKindWord> kindWords,
                                                          Glossary glossary,
                                                          List<QuestionItem> questions) {
        StringBuilder user = new StringBuilder();
        user.append("Ontology classes:\n");
        if (glossary.classes().isEmpty()) {
            user.append("  (none)\n");
        }
        for (String[] c : glossary.classes()) {
            user.append("- ").append(c[0]).append(" · ").append(c[1]);
            if (c[2] != null && !c[2].trim().isEmpty()) {
                user.append(" · ").append(c[2].trim());
            }
            user.append('\n');
        }
        user.append("\nOntology properties:\n");
        if (glossary.properties().isEmpty()) {
            user.append("  (none)\n");
        }
        for (GlossaryProperty p : glossary.properties()) {
            user.append("- ").append(p.key()).append(" · ").append(p.label()).append(" · ")
                    .append(p.kind());
            if (!p.domains().isEmpty()) {
                user.append(" · domain: ").append(String.join(", ", p.domains()));
            }
            if (!p.ranges().isEmpty()) {
                user.append(" · range: ").append(String.join(", ", p.ranges()));
            }
            if (p.description() != null && !p.description().trim().isEmpty()) {
                user.append(" · ").append(p.description().trim());
            }
            user.append('\n');
        }
        if (!glossary.others().isEmpty()) {
            user.append("Other existing properties (key · label only; none of them fits these shapes by "
                    + "structure or meaning, but do not propose a key already here):\n");
            for (String[] o : glossary.others()) {
                user.append("- ").append(o[0]).append(" · ").append(o[1]).append('\n');
            }
        }
        user.append("\nCompetency questions:\n");
        if (questions.isEmpty()) {
            user.append("  (none written yet: propose for the shapes that bind the most statements)\n");
        }
        for (QuestionItem q : questions) {
            user.append("q").append(q.id()).append(": ").append(q.text().trim()).append('\n');
        }
        user.append("\nShapes the ontology cannot hold:\n");
        for (OpenSignature s : signatures) {
            String subject = s.subjectClass() == null ? "(untyped)" : s.subjectClass();
            String object = s.objectIsValue() ? "(a value)"
                    : s.objectClass() == null ? "(untyped)" : s.objectClass();
            user.append("s").append(s.id()).append(": phrase \"").append(s.phrase())
                    .append("\" · subject class: ").append(subject).append(" · object: ")
                    .append(object).append(" · ").append(s.statementCount()).append(" statements\n");
            for (int i = 0; i < s.examples().size(); i++) {
                String quote = i < s.quotes().size() ? s.quotes().get(i).trim() : "";
                if (quote.isEmpty()) {
                    user.append("  · ").append(s.examples().get(i)).append('\n');
                } else {
                    user.append("  · ").append(s.examples().get(i)).append('\n')
                            .append("    \"").append(quote).append("\"\n");
                }
            }
        }
        for (OpenKindWord k : kindWords) {
            user.append("k").append(k.id()).append(": kind word \"").append(k.kindWord())
                    .append("\" · ").append(k.count()).append(" things");
            if (!k.examples().isEmpty()) {
                user.append(" · e.g. ").append(String.join(", ", k.examples()));
            }
            if (!k.phrases().isEmpty()) {
                user.append(" · they are subjects of: ").append(String.join(", ", k.phrases()));
            }
            user.append('\n');
        }
        return List.of(ChatMessage.system(SYSTEM), new ChatMessage("user", user.toString()));
    }

    /** ontology_agent.rs:252-270 ids：s/k/q 前缀的 id 清单（数字或 "s0" 形字符串）。 */
    private static List<Long> ids(JsonNode v, char prefix, Set<Long> known) {
        if (v == null) {
            return List.of();
        }
        if (!v.isArray()) {
            return null;
        }
        List<Long> out = new ArrayList<>();
        for (JsonNode x : v) {
            Long id = null;
            if (x.isNumber()) {
                id = x.asLong();
            } else if (x.isTextual()) {
                String s = x.asText().trim();
                if (s.length() > 0 && s.charAt(0) == prefix) {
                    // 剥掉全部前导前缀字符（Rust trim_start_matches——审查 M-4："ss0" 也收 0，
                    // 只剥一个会整条算坏项）
                    String stripped = s.replaceFirst("^" + Pattern.quote(String.valueOf(prefix)) + "+", "");
                    try {
                        id = Long.parseLong(stripped);
                    } catch (NumberFormatException e) {
                        id = null;
                    }
                } else {
                    try {
                        id = Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        id = null;
                    }
                }
            }
            if (id == null || !known.contains(id)) {
                return null;
            }
            out.add(id);
        }
        return out;
    }

    /** ontology_agent.rs:272-283 strings：null→空、字符串→单元素、数组→逐条 trim。 */
    private static List<String> strings(JsonNode v) {
        if (v == null) {
            return List.of();
        }
        if (v.isNull()) {
            return List.of();
        }
        if (v.isTextual()) {
            return List.of(v.asText().trim());
        }
        if (v.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode x : v) {
                if (!x.isTextual()) {
                    return null;
                }
                out.add(x.asText().trim());
            }
            return out;
        }
        return null;
    }

    /**
     * ontology_agent.rs:285-291 valid_key：ASCII 小写字母/数字/下划线、≤64、小写字母开头。
     * 按 ASCII 判而不是 {@link Character#isLowerCase}——后者放过非 ASCII 小写。
     */
    static boolean validKey(String k) {
        return !k.isEmpty() && k.length() <= 64
                && k.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '_')
                && k.charAt(0) >= 'a' && k.charAt(0) <= 'z';
    }

    /**
     * 读回复（parse_proposal_response）。三个 id 集是这一批里合法的 id：答案里指向别处的
     * 算坏项——一条坏项不毁掉整批。
     */
    public static ProposalReply parseProposalResponse(String raw, Set<Long> signatureIds,
                                                      Set<Long> kindWordIds,
                                                      Set<Long> questionIds) throws Exception {
        JsonNode v = Align.parseValue(raw);
        int[] malformed = {0};
        List<String> reasons = new ArrayList<>();
        List<Proposal> proposals = new ArrayList<>();
        List<Existing> existing = new ArrayList<>();

        List<JsonNode> items = new ArrayList<>();
        JsonNode p = v.get("p");
        if (p != null && p.isArray()) {
            p.forEach(items::add);
        } else if (p != null && p.isObject()) {
            p.forEach(items::add);
        } else if (p != null && !p.isNull()) {
            malformed[0]++;
            reasons.add("`p` is not a list");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode it : items) {
            if (!it.isObject()) {
                malformed[0]++;
                reasons.add("a proposal is not an object");
                continue;
            }
            JsonNode obj = it;
            String kind = obj.hasNonNull("kind") ? obj.get("kind").asText().trim().toLowerCase()
                    : "property";
            String key = obj.hasNonNull("key") ? obj.get("key").asText().trim().toLowerCase() : "";
            if (!("class".equals(kind) || "property".equals(kind)) || !validKey(key)
                    || !seen.add(key)) {
                malformed[0]++;
                reasons.add("bad kind, bad key or repeated key: " + kind + " \"" + key + "\"");
                continue;
            }
            String label = obj.hasNonNull("label") ? obj.get("label").asText().trim() : "";
            if (label.isEmpty()) {
                label = key;
            }
            String definition = obj.hasNonNull("definition") ? obj.get("definition").asText().trim()
                    : "";
            boolean value = obj.hasNonNull("value") && obj.get("value").asBoolean();
            String datatype = null;
            if (obj.hasNonNull("datatype")) {
                String d = obj.get("datatype").asText().trim().toLowerCase();
                if ("number".equals(d) || "date".equals(d) || "text".equals(d)
                        || "bool".equals(d)) {
                    datatype = d;
                }
            }
            List<String> domains = strings(obj.get("domains"));
            List<String> ranges = strings(obj.get("ranges"));
            List<String> parents = strings(obj.get("parents"));
            if (domains == null || ranges == null || parents == null) {
                malformed[0]++;
                reasons.add("domains, ranges or parents are not lists of strings: " + key);
                continue;
            }
            List<Long> sigs = ids(obj.get("s"), 's', signatureIds);
            List<Long> words = ids(obj.get("k"), 'k', kindWordIds);
            List<Long> qs = ids(obj.get("q"), 'q', questionIds);
            if (sigs == null || words == null || qs == null) {
                malformed[0]++;
                reasons.add("a shape or question id outside the batch: " + key);
                continue;
            }
            // 什么形状都不绑的提案不是从图谱里长出来的：不收
            if (sigs.isEmpty() && words.isEmpty()) {
                malformed[0]++;
                reasons.add("binds no shape: " + key);
                continue;
            }
            proposals.add(new Proposal(kind, key, label, definition, value,
                    value ? datatype : null,
                    domains.stream().filter(d -> !d.isEmpty()).toList(),
                    value ? List.of() : ranges.stream().filter(r -> !r.isEmpty()).toList(),
                    parents.stream().filter(x -> !x.isEmpty()).toList(),
                    sigs, words, qs));
        }
        JsonNode ex = v.get("existing");
        if (ex != null && ex.isArray()) {
            for (JsonNode it : ex) {
                if (!it.isObject()) {
                    malformed[0]++;
                    reasons.add("an existing entry is not an object");
                    continue;
                }
                JsonNode obj = it;
                String key = obj.hasNonNull("key") ? obj.get("key").asText().trim() : "";
                List<Long> sigs = ids(obj.get("s"), 's', signatureIds);
                List<Long> words = ids(obj.get("k"), 'k', kindWordIds);
                if (sigs == null || words == null) {
                    malformed[0]++;
                    reasons.add("existing: a shape id outside the batch: " + key);
                    continue;
                }
                if (key.isEmpty() || (sigs.isEmpty() && words.isEmpty())) {
                    malformed[0]++;
                    reasons.add("existing: no key or no shapes: \"" + key + "\"");
                    continue;
                }
                String dirRaw = obj.hasNonNull("dir") ? obj.get("dir").asText().trim() : "";
                String direction;
                if ("reverse".equals(dirRaw)) {
                    direction = "reverse";
                } else if ("forward".equals(dirRaw) || dirRaw.isEmpty()) {
                    direction = "forward";
                } else {
                    malformed[0]++;
                    reasons.add("existing: unknown direction: " + key);
                    continue;
                }
                existing.add(new Existing(key, direction, sigs, words));
            }
        }
        return new ProposalReply(proposals, existing, malformed[0], reasons);
    }
}
