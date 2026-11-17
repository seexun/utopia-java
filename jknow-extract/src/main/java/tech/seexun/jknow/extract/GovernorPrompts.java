package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;

/**
 * 治理的第二层（0025 第二刀）：攒批判不定的对，逐条带工具再看一遍（governor.rs 339 行
 * 逐语义）。模型能看的东西：一侧的全部事实、一侧的原文片段、台账里人对某个名字的决定、
 * 库里名字相近的其他实体、合并会牵动什么（0028：一致性检查会开出的矛盾、靠着一边的
 * 派生、点过名的回答、两边的类型是不是一个大类）。结束只有两种：{@code decide}
 * （same / different + 置信度 + 一句理由）或 {@code defer}（留给人一个具体的问题）。
 * 工具定义、提示词与回合的解析在这里；跑循环、查库的在 server 的治理任务里——
 * 这里不碰库也不碰模型。
 */
public final class GovernorPrompts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GovernorPrompts() {
    }

    /**
     * governor.rs:16 MAX_STEPS：一对最多查几次再表态。模型按菜单走：两侧的事实、
     * 两侧的原文、两个名字的台账、同名者、合并会碰到什么——八次。原来给六次，
     * identity bench 上第二眼三分之一「看了没收尾」：轨迹显示它把最后两次花在被拒的
     * 同名者与后果查询上，没回合表态。
     */
    public static final int MAX_STEPS = 8;

    /**
     * governor.rs:19-21 LIMIT_REACHED：查够了还想查时的回话——不给结果，只提醒收尾。
     */
    public static final String LIMIT_REACHED = "Lookup limit reached: you have seen what can be"
            + " seen. Answer now with decide (same or different, with your confidence) or defer"
            + " (with the question a person should answer).";

    /** governor.rs:178 NUDGE：模型让工具循环去找结论时的提醒。 */
    public static final String NUDGE = "Finish with the decide or defer tool.";

    /** governor.rs:24-29 EarlierLook：攒批那一眼说了什么——带进第二眼，模型知道自己上次为什么没定。 */
    public record EarlierLook(
            /** same | different | unsure */
            String verdict,
            float confidence,
            String why) {
    }

    /** governor.rs:32-49 Step：模型在一个回合里要的事（四态）。 */
    public sealed interface Step {

        /** 查一样东西：facts / quotes / ledger / namesakes / consequences。 */
        record Lookup(String tool, JsonNode args) implements Step {
        }

        record Decide(boolean same, float confidence, String why) implements Step {
        }

        record Defer(String question) implements Step {
        }

        /** 认不出的工具名或缺参数：告诉模型，别猜。 */
        record Unknown(String problem) implements Step {
        }
    }

    /** governor.rs:52-107 tools()：OpenAI 协议的 function 定义（七件，顺序即菜单序）。 */
    public static JsonNode tools() {
        ObjectNode side = JSON.createObjectNode();
        side.put("type", "object");
        ObjectNode sideProps = side.putObject("properties");
        ObjectNode sideEnum = sideProps.putObject("side");
        sideEnum.put("type", "string");
        sideEnum.set("enum", arrayOf("A", "B"));
        side.set("required", arrayOf("side"));

        ObjectNode query = JSON.createObjectNode();
        query.put("type", "object");
        ObjectNode queryProps = query.putObject("properties");
        ObjectNode queryStr = queryProps.putObject("query");
        queryStr.put("type", "string");
        query.set("required", arrayOf("query"));

        ArrayNode tools = JSON.createArrayNode();
        tools.add(function("facts", "Every recorded fact of one side (A or B): its relations to"
                + " other entities, with time ranges where known.", side));
        tools.add(function("quotes", "Source passages that mention one side: the sentences its"
                + " facts were extracted from, each with the document it comes from.", side));
        tools.add(function("ledger", "What people in this base decided before about a name:"
                + " merges, keeps and reverted merges whose names contain the query.", query));
        tools.add(function("namesakes", "Other entities in this base whose name contains the"
                + " query, with their type and how many facts they carry.", query));
        tools.add(function("consequences", "What merging A and B would touch: relations that"
                + " allow one value where the two sides hold different ones, derived facts resting"
                + " on either side, chat answers that named either side, and whether the two types"
                + " belong to one family. A merge that would touch any of these is held for a"
                + " person whatever your confidence.", emptyObject()));
        ObjectNode decideParams = JSON.createObjectNode();
        decideParams.put("type", "object");
        ObjectNode decideProps = decideParams.putObject("properties");
        ObjectNode verdict = decideProps.putObject("verdict");
        verdict.put("type", "string");
        verdict.set("enum", arrayOf("same", "different"));
        ObjectNode confidence = decideProps.putObject("confidence");
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        ObjectNode why = decideProps.putObject("why");
        why.put("type", "string");
        why.put("description", "One sentence naming the fact, passage or precedent that settled"
                + " it.");
        decideParams.set("required", arrayOf("verdict", "confidence", "why"));
        tools.add(function("decide", "Give the verdict for this pair.", decideParams));
        ObjectNode deferParams = JSON.createObjectNode();
        deferParams.put("type", "object");
        ObjectNode deferProps = deferParams.putObject("properties");
        ObjectNode question = deferProps.putObject("question");
        question.put("type", "string");
        question.put("description", "The one question a person could answer at a glance, naming"
                + " the fact or document that would settle it.");
        deferParams.set("required", arrayOf("question"));
        tools.add(function("defer", "Leave the pair for a person, when no lookup can settle it.",
                deferParams));
        return tools;
    }

    private static ObjectNode function(String name, String description, JsonNode parameters) {
        ObjectNode fn = JSON.createObjectNode();
        fn.put("name", name);
        fn.put("description", description);
        fn.set("parameters", parameters);
        ObjectNode tool = JSON.createObjectNode();
        tool.put("type", "function");
        tool.set("function", fn);
        return tool;
    }

    private static ArrayNode arrayOf(String... items) {
        ArrayNode arr = JSON.createArrayNode();
        for (String item : items) {
            arr.add(item);
        }
        return arr;
    }

    private static ObjectNode emptyObject() {
        ObjectNode params = JSON.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        return params;
    }

    /**
     * governor.rs:110-175 messages()：对话的开头——系统提示 + 这一对 + 上一眼的结论。
     * 提示词逐字（Rust 的行续接 {@code \} 折行后即本串）。
     */
    public static List<JsonNode> messages(ExtractLib.AdjudicationPair pair, EarlierLook earlier) {
        String system = "You are an entity-resolution adjudicator for a knowledge graph, looking"
                + " at ONE pair of records that an earlier, quicker look could not settle."
                + " Decide whether the two records refer to the SAME real-world entity or are"
                + " namesakes.\n"
                + "\n"
                + ExtractLib.IDENTITY_RULES + "\n"
                + "\n"
                + "You may look things up before answering, at most " + MAX_STEPS + " lookups:"
                + " the facts of a side, the source passages that mention a side, what people in"
                + " this base decided about a name, other entities with a similar name, and what"
                + " merging the two would touch (a relation that allows one value where the sides"
                + " hold different ones is a contradiction, and a merge that would touch anything"
                + " outside the graph is held for a person whatever your confidence: prefer to"
                + " defer with the question that would settle it). People's earlier decisions are"
                + " how the owners of this base want such cases judged; follow them unless the"
                + " facts of this pair clearly differ, and never let one override a contradiction"
                + " in the facts. Look only for what would change your answer.\n"
                + "\n"
                + "Finish with exactly one tool call: decide (same or different, a confidence in"
                + " 0~1, and one sentence naming the fact, passage or precedent that settled it)"
                + " or defer (when no lookup can settle it: leave the ONE question a person could"
                + " answer at a glance, naming the fact or document that would settle it). A wrong"
                + " merge is far more damaging than leaving two records separate: when the"
                + " evidence is thin, defer.";
        String user = side("A", pair.left()) + "\n" + side("B", pair.right()) + "\n"
                + whyPaired(pair) + precedentsBlock(pair)
                + "The earlier look said: " + earlier.verdict() + " ("
                + String.format(Locale.ROOT, "%.2f", earlier.confidence()) + ")"
                + earlierWhy(earlier) + ".";
        ObjectNode systemMsg = JSON.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", system);
        ObjectNode userMsg = JSON.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", user);
        return List.of(systemMsg, userMsg);
    }

    /** governor.rs:135-146：一侧的样子——名字、类型、事实行（没有事实也说一声）。 */
    private static String side(String label, ExtractLib.AdjudicationSide s) {
        String facts = s.facts().isEmpty() ? "  (no recorded facts)"
                : bulletList(s.facts());
        return "Record " + label + ": \"" + s.name() + "\" (" + s.typeLabel() + ")\n" + facts;
    }

    private static String bulletList(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append("  - ").append(line).append('\n');
        }
        return out.length() == 0 ? "" : out.substring(0, out.length() - 1);
    }

    private static String whyPaired(ExtractLib.AdjudicationPair pair) {
        return pair.proposedBecause() != null ? "Why paired: " + pair.proposedBecause() + "\n"
                : "";
    }

    private static String precedentsBlock(ExtractLib.AdjudicationPair pair) {
        if (pair.precedents().isEmpty()) {
            return "";
        }
        return "Precedents (decided by people in this base):\n"
                + bulletList(pair.precedents()) + "\n";
    }

    private static String earlierWhy(EarlierLook earlier) {
        return earlier.why() != null ? " — " + earlier.why() : "";
    }

    /**
     * governor.rs:181-228 read_step()：一个工具调用读成一步。参数是协议原样的 JSON
     * 字符串；解不开当空对象（缺参数自然落 Unknown，告诉模型别猜）。
     */
    public static Step readStep(String name, String arguments) {
        JsonNode args;
        try {
            args = JSON.readTree(arguments);
        } catch (Exception e) {
            args = JSON.createObjectNode();
        }
        switch (name) {
            case "facts", "quotes" -> {
                String side = args.path("side").asText(null);
                if ("A".equals(side) || "B".equals(side)) {
                    return new Step.Lookup(name, args);
                }
                return new Step.Unknown(name + " needs side A or B");
            }
            case "ledger", "namesakes" -> {
                // as_str 只认字符串：数字 5 不是 query（Jackson asText 会把数字收成 "5"）
                JsonNode q = args.get("query");
                if (q != null && q.isTextual() && !q.asText().trim().isEmpty()) {
                    return new Step.Lookup(name, args);
                }
                return new Step.Unknown(name + " needs a query");
            }
            case "consequences" -> {
                return new Step.Lookup(name, JSON.createObjectNode());
            }
            case "decide" -> {
                boolean same;
                switch (args.path("verdict").asText(null)) {
                    case "same" -> same = true;
                    case "different" -> same = false;
                    default -> {
                        return new Step.Unknown("decide needs verdict same or different");
                    }
                }
                JsonNode conf = args.get("confidence");
                float confidence = conf != null && conf.isNumber()
                        ? (float) conf.asDouble()
                        : 0.5f;
                confidence = Math.max(0f, Math.min(1f, confidence));
                // as_str 只认字符串：非字符串的 why 当没有（Rust unwrap_or("") 同判）
                JsonNode whyNode = args.get("why");
                String why = whyNode != null && whyNode.isTextual()
                        ? whyNode.asText().trim() : "";
                return new Step.Decide(same, confidence, why);
            }
            case "defer" -> {
                JsonNode q = args.get("question");
                if (q != null && q.isTextual() && !q.asText().trim().isEmpty()) {
                    return new Step.Defer(q.asText().trim());
                }
                return new Step.Unknown("defer needs a question");
            }
            default -> {
                return new Step.Unknown("no tool named " + name);
            }
        }
    }
}
