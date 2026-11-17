package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 勘误 agent 的提示词与回复解析（extract/errata.rs 逐语义，0044 决定 7）：一份文档、
 * 本体的属性、从它读出的事实，结构报了的带着理由；模型对每一条说 keep / retract /
 * revise，全部答完才许 add。撤、改、加都得引文档的原话——引文在不在文档里由调用方验
 * （store 侧 {@code ErrataService.quoteIn}），这里只认形状。
 *
 * <p>第二票（{@link #CONFIRM_SYSTEM}）只问「文档说了没有」：第一票带着整份清单和结构的
 * 理由，容易顺着理由撤掉原文其实说了的事；第二票只看事实与原文，<b>只认显式的
 * {@code not_stated}</b>——没答到的、答成别的都不算票，撤要两票齐，缺一票就留着
 * （调用方把没进集合的撤/改改判 keep）。
 *
 * <p>协议逐字（模型答的 JSON 形状，errata.rs:34-48）：
 * {@code {"a":[[id,"keep"],[id,"retract",reason,quote],[id,"revise",{"property":key|null,
 * "object":text|null},reason,quote],[null,"add",{"subject":name,"property":key,
 * "object":name_or_value},reason,quote]]}}；未答完就 add → additions 全拒
 * （「先查完给它的，再加」是契约）。
 */
public final class ErrataPrompts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErrataPrompts() {
    }

    /** 送去看的一条事实（errata.rs:10-19 ErrataFact）。 */
    public record ErrataFact(long id, String subject, String subjectClass, String property,
                             String object, String objectClass, String flag, String quote) {
    }

    /** 本体里的一条属性，给模型当词汇表（errata.rs:22-31 ErrataProperty）。 */
    public record ErrataProperty(String key, String label, String description, String kind,
                                 List<String> domains, List<String> ranges, String datatype) {
    }

    /**
     * errata.rs:34-48 ERRATA_SYSTEM 逐字（Rust 行尾 {@code \} 续行折掉换行与前导空白，
     * 续行处留一个空格——这里是拼好的整段）。
     */
    public static final String ERRATA_SYSTEM = "You review facts that were read from ONE document into a knowledge graph. "
            + "You get the document, the ontology's properties, and a numbered list of facts. Some facts carry a FLAG from a structural check: "
            + "domain = the subject is not a kind of thing the property allows; range = the object is not; "
            + "name_absent = a name in the fact does not occur in the document; no_date = a date property holds something that is not a date.\n"
            + "For EVERY fact answer one of:\n"
            + "- keep: the document states it and the property fits.\n"
            + "- retract: the document does not state it, or no property in the ontology fits what it states.\n"
            + "- revise: the document states something close; give the property key and/or the object the document supports (a name that occurs in the document, or a value).\n"
            + "Only after you have answered every fact may you add facts the document states plainly, the ontology can hold, and the list lacks. "
            + "Do not add a fact whose subject or object is not named in the document.\n"
            + "Every retract, revise and add must quote the document's own words: one contiguous span, verbatim. Never quote words that are not in the document. "
            + "Prefer keeping a fact over retracting it when the document supports it; removing a correct fact costs more than leaving a doubtful one.\n"
            + "Answer with JSON only, no prose:\n"
            + "{\"a\":[[id,\"keep\"],[id,\"retract\",reason,quote],[id,\"revise\",{\"property\":key|null,\"object\":text|null},reason,quote],"
            + "[null,\"add\",{\"subject\":name,\"property\":key,\"object\":name_or_value},reason,quote]]}";

    /** errata.rs:50-64 property_line：属性表的一行。 */
    static String propertyLine(ErrataProperty p) {
        StringBuilder line = new StringBuilder("- ").append(p.key()).append(" (")
                .append(p.label()).append("): ").append(p.description().trim());
        if ("attribute".equals(p.kind())) {
            line.append(" [value").append(p.datatype() != null ? ": " + p.datatype() : "")
                    .append("]");
        } else {
            line.append(" [thing]");
        }
        if (p.domains() != null && !p.domains().isEmpty()) {
            line.append(" subject: ").append(String.join("|", p.domains()));
        }
        if (p.ranges() != null && !p.ranges().isEmpty()) {
            line.append(" object: ").append(String.join("|", p.ranges()));
        }
        return line.toString();
    }

    /** errata.rs:66-78 fact_line：事实清单的一行（旗与读它的原话都带着）。 */
    static String factLine(ErrataFact f) {
        StringBuilder line = new StringBuilder().append(f.id()).append(": ").append(f.subject())
                .append(f.subjectClass() != null ? " (" + f.subjectClass() + ")" : "")
                .append(" —").append(f.property()).append("→ ").append(f.object())
                .append(f.objectClass() != null ? " (" + f.objectClass() + ")" : "");
        if (f.flag() != null) {
            line.append(" FLAG ").append(f.flag());
        }
        if (f.quote() != null && !f.quote().trim().isEmpty()) {
            line.append(" · read from: \"").append(f.quote().trim()).append("\"");
        }
        return line.toString();
    }

    /** errata.rs:80-103 build_errata_messages。 */
    public static List<ChatMessage> buildErrataMessages(String document,
            List<ErrataProperty> properties, List<ErrataFact> facts) {
        String props = String.join("\n",
                properties.stream().map(ErrataPrompts::propertyLine).toList());
        StringBuilder list = new StringBuilder();
        for (ErrataFact f : facts) {
            if (list.length() > 0) {
                list.append('\n');
            }
            list.append(factLine(f));
        }
        if (list.isEmpty()) {
            // 没有类型化事实的文档也送去看：清单为空，agent 只能加
            list.append("(none yet: add what the document states plainly)");
        }
        return List.of(
                ChatMessage.system(ERRATA_SYSTEM),
                ChatMessage.user("DOCUMENT:\n" + document + "\n\nPROPERTIES:\n" + props
                        + "\n\nFACTS:\n" + list));
    }

    /** 模型对一条事实的说法（errata.rs:117-124 Verdict）。 */
    public sealed interface Verdict permits Keep, Retract, Revise {
    }

    /** keep：文档说了、属性也对。 */
    public record Keep() implements Verdict {
    }

    /** retract：文档没说，或本体没有属性装得下。 */
    public record Retract() implements Verdict {
    }

    /** revise：文档说了相近的——换属性（键）、换宾语（名字或值），至少一样。 */
    public record Revise(String property, String object) implements Verdict {
    }

    /** errata.rs:126-133 FactVerdict：一条事实的一票。 */
    public record FactVerdict(long id, Verdict verdict, String reason, String quote) {
    }

    /** errata.rs:135-142 Addition：模型想加的一条。 */
    public record Addition(String subject, String property, String object, String reason,
                           String quote) {
    }

    /** errata.rs:144-152 ErrataResponse。 */
    public static final class ErrataResponse {

        public final List<FactVerdict> verdicts;
        public final List<Addition> additions;
        /** 形状不对的行：不认识的 id、重复的 id、撤改加没带引文、改了等于没改。 */
        public final int malformed;
        /** 没把给它的事实答完就加的：加的全部不算（「先查完给它的，再加」是契约）。 */
        public final int additionsRefused;

        public ErrataResponse(List<FactVerdict> verdicts, List<Addition> additions,
                              int malformed, int additionsRefused) {
            this.verdicts = verdicts;
            this.additions = additions;
            this.malformed = malformed;
            this.additionsRefused = additionsRefused;
        }
    }

    /** errata.rs:157-163 text_of：一段值当文本收（数字也收），空串不算。 */
    private static String textOf(JsonNode v) {
        if (v == null) {
            return null;
        }
        String s;
        if (v.isTextual()) {
            s = v.asText().trim();
        } else if (v.isNumber()) {
            s = v.asText();
        } else {
            return null;
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * errata.rs:166-230 parse_errata_response：解析勘误的回复。给出的事实一条一票，
     * 多的、错的计数不算；加的只在全部答完时算。JSON 读不出（没有 "a" 数组/不是 JSON）
     * 抛 {@link IllegalArgumentException}——调用方把「读不出」记成整份文档的失败让任务
     * 重试，而不是当成零票。
     */
    public static ErrataResponse parseErrataResponse(String raw, List<ErrataFact> facts) {
        JsonNode v;
        try {
            v = JSON.readTree(extractJson(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        JsonNode rows = v == null ? null : v.get("a");
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("no \"a\" array");
        }
        List<FactVerdict> verdicts = new ArrayList<>();
        int malformed = 0;
        Set<Long> seen = new HashSet<>();
        List<Addition> additions = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray()) {
                malformed++;
                continue;
            }
            String action = row.size() > 1 && row.get(1).isTextual() ? row.get(1).asText() : "";
            if ("add".equals(action)) {
                // [null,"add",{subject,property,object},reason,quote]：三个名字都得是
                // 非空文本、引文得在——缺一样整条算坏
                Addition a = null;
                JsonNode spec = row.size() > 2 && row.get(2).isObject() ? row.get(2) : null;
                if (spec != null) {
                    String subject = textOf(spec.get("subject"));
                    String property = textOf(spec.get("property"));
                    String object = textOf(spec.get("object"));
                    String quote = row.size() > 4 ? textOf(row.get(4)) : null;
                    if (subject != null && property != null && object != null && quote != null) {
                        a = new Addition(subject, property, object,
                                row.size() > 3 ? orEmpty(textOf(row.get(3))) : "", quote);
                    }
                }
                if (a != null) {
                    additions.add(a);
                } else {
                    malformed++;
                }
                continue;
            }
            FactVerdict verdict = null;
            // Rust as_i64：小数不是 id（serde_json 的 as_i64 对 float 回 None——按坏行计）
            if (!row.isEmpty() && row.get(0).isIntegralNumber()) {
                long id = row.get(0).asLong();
                ErrataFact fact = null;
                for (ErrataFact f : facts) {
                    if (f.id() == id) {
                        fact = f;
                        break;
                    }
                }
                if (fact != null && seen.add(id)) {
                    verdict = switch (action) {
                        case "keep" -> new FactVerdict(id, new Keep(),
                                orEmpty(textOf(row.size() > 2 ? row.get(2) : null)), null);
                        case "retract" -> retractVerdict(id, row);
                        case "revise" -> reviseVerdict(id, row);
                        default -> null;
                    };
                }
            }
            if (verdict != null) {
                verdicts.add(verdict);
            } else {
                malformed++;
            }
        }
        boolean answeredAll = true;
        for (ErrataFact f : facts) {
            if (!seen.contains(f.id())) {
                answeredAll = false;
                break;
            }
        }
        // 空清单 vacuously「答完了」：第一次见这份文档就送空清单去，agent 只能加
        if (answeredAll) {
            return new ErrataResponse(verdicts, additions, malformed, 0);
        }
        return new ErrataResponse(verdicts, List.of(), malformed, additions.size());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** errata.rs:188-192 retract 的解析：引文必须在（text_of(arr.get(3))? 的 ? 在闭包里——缺了整行算坏）。 */
    private static FactVerdict retractVerdict(long id, JsonNode row) {
        String quote = row.size() > 3 ? textOf(row.get(3)) : null;
        if (quote == null) {
            return null;
        }
        return new FactVerdict(id, new Retract(),
                orEmpty(textOf(row.size() > 2 ? row.get(2) : null)), quote);
    }

    /** errata.rs:198-214 revise 的解析：{property,object} 至少一样给，引文得在。 */
    private static FactVerdict reviseVerdict(long id, JsonNode row) {
        JsonNode spec = row.size() > 2 && row.get(2).isObject() ? row.get(2) : null;
        if (spec == null) {
            return null;
        }
        String property = textOf(spec.get("property"));
        String object = textOf(spec.get("object"));
        if (property == null && object == null) {
            // 改了等于没改
            return null;
        }
        String quote = row.size() > 4 ? textOf(row.get(4)) : null;
        if (quote == null) {
            return null;
        }
        return new FactVerdict(id, new Revise(property, object),
                orEmpty(textOf(row.size() > 3 ? row.get(3) : null)), quote);
    }

    /**
     * errata.rs:232-237 CONFIRM_SYSTEM 逐字：第二票（撤改要两票）——agent 说撤或改的
     * 那几条再问一遍，只问「文档说了没有」。
     */
    public static final String CONFIRM_SYSTEM = "You check facts against ONE document. For each numbered fact answer whether the document states it, "
            + "in its own words or in other words with the same meaning. Answer stated when the document supports the fact even if a name is "
            + "abbreviated or the wording differs; answer not_stated only when the document does not say it or says otherwise. "
            + "Judge only from the document. Answer with JSON only: {\"c\":[[id,\"stated\"|\"not_stated\"]]}";

    /** errata.rs:239-254 build_confirm_messages。 */
    public static List<ChatMessage> buildConfirmMessages(String document, List<ErrataFact> facts) {
        StringBuilder list = new StringBuilder();
        for (ErrataFact f : facts) {
            if (list.length() > 0) {
                list.append('\n');
            }
            list.append(f.id()).append(": ").append(f.subject()).append(" —")
                    .append(f.property()).append("→ ").append(f.object());
        }
        return List.of(
                ChatMessage.system(CONFIRM_SYSTEM),
                ChatMessage.user("DOCUMENT:\n" + document + "\n\nFACTS:\n" + list));
    }

    /**
     * errata.rs:257-278 parse_confirm_response：第二票的回复——哪些 id 被判
     * not_stated。没答到的、答成别的都不算票（撤要两票齐，缺一票就留着）。
     */
    public static Set<Long> parseConfirmResponse(String raw, List<Long> ids) {
        JsonNode v;
        try {
            v = JSON.readTree(extractJson(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        JsonNode rows = v == null ? null : v.get("c");
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("no \"c\" array");
        }
        Set<Long> notStated = new HashSet<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.isEmpty() || !row.get(0).isIntegralNumber()) {
                continue;
            }
            long id = row.get(0).asLong();
            String verdict = row.size() > 1 && row.get(1).isTextual() ? row.get(1).asText() : null;
            if (ids.contains(id) && "not_stated".equals(verdict)) {
                notStated.add(id);
            }
        }
        return notStated;
    }

    /** errata.rs:280-285 extract_json：回复里可能裹着围栏或前后的话，取第一个 { 到最后一个 }。 */
    static String extractJson(String raw) {
        int a = raw.indexOf('{');
        int b = raw.lastIndexOf('}');
        return b > a ? raw.substring(a, b + 1) : raw;
    }
}
