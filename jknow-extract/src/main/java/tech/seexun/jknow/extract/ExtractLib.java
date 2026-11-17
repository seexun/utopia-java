package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * lib.rs 其余零件的移植：实体消解的攒批裁决提示词与修补解析（M4 的裁决器用，本任务
 * 落库——常量与解析属抽取合同的一部分），以及量与时间的读法（本体属性的采纳要它们）。
 */
public final class ExtractLib {

    private ExtractLib() {
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ---------------------------------------------------------------------------
    // 实体消解裁决（攒批：一次调用裁多对，LLM 只处理 embedding 分不出的灰区）
    // ---------------------------------------------------------------------------

    /** lib.rs:138-143 AdjudicationSide：待裁决的一侧——名字 + 类型 + 事实摘要行。 */
    public record AdjudicationSide(String name, String typeLabel, List<String> facts) {
    }

    /** lib.rs:145-155 AdjudicationPair：一对 + 先例（这个库里的人做过什么）+ 提议依据。 */
    public record AdjudicationPair(AdjudicationSide left, AdjudicationSide right,
                                   List<String> precedents, String proposedBecause) {
    }

    /** lib.rs:161-168 proposed_because：名字向量召回提的对写成裁决器读得懂的一句。 */
    public static String proposedBecause(String cosine) {
        if (cosine == null) {
            return null;
        }
        return "the names are similar but NOT the same string (name-vector cosine " + cosine
                + "): this may be a short form, another script, or a different thing with a "
                + "similar name; a dropped qualifier is not evidence here, the facts are";
    }

    /** lib.rs:170-179 AdjudicationVerdict。 */
    public record AdjudicationVerdict(long i, String verdict, Float confidence, String why) {
    }

    /**
     * 身份规则（lib.rs:193-258 逐字）——从 ai-timeline 真值上量出来的四种错写成的规则：
     * 版本并进系列、「X 的 Y」并进 X、列表并进成员、子公司并进母公司；以及同名被类型
     * 标签/事实多少拆开。攒批与工具循环两个提示词共用。
     */
    public static final String IDENTITY_RULES = """
            A record is one specific thing in the world: a person, an organization, a legal entity, a product, one version of a product, a document, an event, a place, or a concept. Two records are the SAME only when they denote exactly the same thing.

            Names:
            - The same proper name, compatible kinds, and no real contradiction: the same thing. Type labels were assigned by an extractor and are noisy: Organization, Corporation, ResearchOrganization, NGO and Store can all be one company; SoftwareApplication, CreativeWork, Intangible, Product, Service, Offer and ComputerLanguage can all be one product; Place and State can be one state. Only incompatible kinds count as a difference: a person against a company, a place against a product, an event against an organization. For identical proper names answer "different" only when you can name the contradiction in one sentence; a type label, a missing fact, fewer facts, or different partners, products, roles or events is not one. Never treat the absence of facts as a difference.
            - A surname or a first name alone against a full name that contains it, in the same documents, is the same person unless another person with that name appears: "Pachocki" is "Jakub Pachocki", "Kwon" is "Jason Kwon", "Nadella" is "Satya Nadella". A person who moved between two organizations is still one person.
            - A parenthetical acronym, an expanded acronym or a fuller product designation is the same thing: "reinforcement learning (RL)" is "reinforcement learning", "US Federal Trade Commission (FTC)" is "Federal Trade Commission", "MI450" is the "AMD Instinct MI450".
            - A name that is the other name with a qualifier removed from the FRONT is usually the same thing abbreviated: "Google DeepMind" and "DeepMind", "Adam D'Angelo" and "D'Angelo", "Meta Platforms" and "Meta", "Altimeter Capital" and "Altimeter", "The New York Times" and "New York Times", "Apple Inc." and "Apple". Documents drop the qualifier after first mention.
            - A name that is the other name with something ADDED AT THE END is a different, more specific thing: a version or edition ("Claude 4 Opus" is not "Claude", "AlphaFold2" is not "AlphaFold", "Genie 2" is not "Genie", "GPT-4.5" is not "GPT-4", "2024 International Mathematical Olympiad" is not "International Mathematical Olympiad"), a variant or tier ("Gemini Robotics-ER" is not "Gemini Robotics", "Claude 3 Haiku" is not "Haiku"), a division, subsidiary or legal entity ("DeepMind Health" is not "DeepMind", "OpenAI Ireland Ltd" is not "OpenAI", "Microsoft AI" is not "Microsoft"), a project, programme, team, app or component. Never merge a version into its family or a part into its whole.
            - A document, agreement or filing is cited in many ways and stays one thing through its amendments: "the Lease", "Lease Agreement", "Lease Agreement dated May 16, 2016" and "Lease Agreement dated May 16, 2016, as amended" are one agreement when their parties and subject do not contradict each other. When it was signed and that it was amended describe the agreement; they do not make a second one. Each amendment is a document of its own, and an agreement for other premises, another phase or other parties ("Phase 2 Lease") is a different agreement.
            - A phrase that merely contains a name is not that name: "Sam Altman's efforts", "psychological abuse from Sam Altman", "share sale led by Thrive Capital", "leaked letter from the National Data Guardian", "ChatGPT played a role in the campaign", "a consistent pattern of lying". These describe something about the thing; they are not the thing.
            - A list of names ("MuZero, AlphaStar, AlphaGeometry") is not any of its members.
            - A common noun or generic phrase ("employees", "users", "lawsuit", "investors", "event", "safety") is not a proper name. Two such records are the same only when their facts show they are one specific instance; usually they are different.
            - When the facts of either record describe ownership, control, a subsidiary, a holding or a parent relation between the two names, or show one of them as one legal entity among several in a group ("OpenAI, Inc. controls the for-profit company", "OpenAI GP LLC controls OpenAI LP"), they are two entities even if one name is the other plus a corporate suffix. A group and its legal entities are different records.

            Facts:
            - Different facts are not contradictory facts. One company has many partnerships, investors, lawsuits and contracts; a person changes jobs; one product is praised in one document and criticised in another. A contradiction is two facts that cannot both hold of one thing at once: two different founders, two headquarters at the same time, two different birth dates, or affiliations that overlap in time and exclude each other. Only a contradiction is evidence of difference.
            - A record with no facts contributes nothing; the name and the other record decide.""";

    /**
     * lib.rs:260-335 build_adjudication_messages：保守偏置——证据不足答 unsure（宁分勿合）。
     */
    public static List<ChatMessage> buildAdjudicationMessages(List<AdjudicationPair> pairs) {
        String system = "You are an entity-resolution adjudicator for a knowledge graph. For each "
                + "numbered pair, decide whether the two records refer to the SAME real-world thing "
                + "or are two things that share a name.\n"
                + "\n"
                + IDENTITY_RULES + "\n"
                + "\n"
                + "Some pairs carry precedents: decisions people made in this same knowledge base "
                + "on these names, or on pairs of the same two types. Treat them as how the owners "
                + "of this base want such cases judged. Follow a precedent on the same pair unless "
                + "the facts of this pair clearly differ from it; when precedents disagree with "
                + "each other, answer \"unsure\". A precedent never overrides a contradiction in "
                + "the facts. Some precedents quote what the person wrote when deciding: weigh that "
                + "stated ground, not only the outcome; a decision made for a reason that does not "
                + "hold here is not a precedent for this pair.\n"
                + "\n"
                + "Output exactly one JSON object and nothing else:\n"
                + "{\"verdicts\":[{\"i\":0,\"verdict\":\"same|different|unsure\",\"confidence\":0.9,"
                + "\"why\":\"one sentence\"}]}\n"
                + "\n"
                + "Rules:\n"
                + "1. One verdict per pair, using the pair's number as \"i\".\n"
                + "2. confidence in 0~1.\n"
                + "3. \"why\" is one short sentence; when a precedent decided it, say which.\n"
                + "4. A wrong merge is far more damaging than leaving two records separate: answer "
                + "\"same\" only when the rules above make it so; when a rule says two things, say "
                + "\"different\" with confidence, not \"unsure\"; \"unsure\" is for evidence that "
                + "genuinely points both ways.";
        StringBuilder user = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            AdjudicationPair p = pairs.get(i);
            String whyPaired = p.proposedBecause() != null ? "Why paired: " + p.proposedBecause()
                    + "\n" : "";
            String precedents = p.precedents().isEmpty() ? "" : "Precedents (decided by people "
                    + "in this base):\n" + bulletList(p.precedents()) + "\n";
            user.append("Pair ").append(i).append(":\nRecord A: ").append(fmt(p.left()))
                    .append("\nRecord B: ").append(fmt(p.right())).append('\n')
                    .append(whyPaired).append(precedents).append('\n');
        }
        return List.of(ChatMessage.system(system), ChatMessage.user(user.toString()));
    }

    private static String fmt(AdjudicationSide s) {
        String facts = s.facts().isEmpty() ? "  (no recorded facts)"
                : bulletList(s.facts());
        return "\"" + s.name() + "\" (" + s.typeLabel() + ")\n" + facts;
    }

    private static String bulletList(List<String> lines) {
        List<String> bullets = new ArrayList<>();
        for (String l : lines) {
            bullets.add("  - " + l);
        }
        return String.join("\n", bullets);
    }

    /** lib.rs:337-339 parse_adjudication。 */
    public static List<AdjudicationVerdict> parseAdjudication(String raw) {
        return parseAdjudicationRepairing(raw).verdicts();
    }

    /** 解析结果：判决列表 + 修补过没有。 */
    public record AdjudicationReply(List<AdjudicationVerdict> verdicts, boolean repaired) {
    }

    /**
     * lib.rs:350-364 parse_adjudication_repairing：模型在引号里的理由中间直接换行——JSON
     * 不许字符串里出现裸控制字符（U+0000–U+001F）。先按原样解；解不开就把字符串字面量里
     * 的控制字符转义后再解一次。修补与否只改 bool，裁决本身一样。
     */
    public static AdjudicationReply parseAdjudicationRepairing(String raw) {
        String jsonStr = JsonFuns.jsonBlock(raw);
        List<AdjudicationVerdict> verdicts = tryVerdicts(jsonStr);
        if (verdicts != null) {
            return new AdjudicationReply(verdicts, false);
        }
        String escaped = escapeControlInStrings(jsonStr);
        if (escaped.equals(jsonStr)) {
            throw new IllegalArgumentException("Failed to parse adjudication JSON");
        }
        verdicts = tryVerdicts(escaped);
        if (verdicts == null) {
            throw new IllegalArgumentException("Failed to parse adjudication JSON (after repair)");
        }
        return new AdjudicationReply(verdicts, true);
    }

    private static List<AdjudicationVerdict> tryVerdicts(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<AdjudicationVerdict> out = new ArrayList<>();
            JsonNode verdicts = root.get("verdicts");
            if (verdicts != null && verdicts.isArray()) {
                for (JsonNode v : verdicts) {
                    JsonNode i = v.get("i");
                    if (i == null || !i.isNumber()) {
                        return null;
                    }
                    JsonNode verdict = v.get("verdict");
                    if (verdict == null || !verdict.isTextual()) {
                        return null;
                    }
                    Float confidence = v.get("confidence") != null
                            && v.get("confidence").isNumber()
                            ? (float) v.get("confidence").asDouble() : null;
                    String why = v.get("why") != null && v.get("why").isTextual()
                            ? v.get("why").asText() : null;
                    out.add(new AdjudicationVerdict(i.asLong(), verdict.asText(), confidence,
                            why));
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * lib.rs:368-394 escape_control_in_strings：把字符串字面量里的裸控制字符（U+0000–U+001F）
     * 写成 JSON 转义；字面量外的原样。引号与反斜杠的跟踪同 close_brackets。
     */
    public static String escapeControlInStrings(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                } else if (c < 0x20) {
                    switch (c) {
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> out.append(String.format("\\u%04x", (int) c));
                    }
                    continue;
                }
            } else if (c == '"') {
                inStr = true;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------
    // 量的读法
    // ---------------------------------------------------------------------------

    /**
     * lib.rs:418-420 parse_quantity：一个<b>整体就是一个量</b>的字符串 → (数值, 单位)。
     * 判据从严：可选货币符号 + 数字 + 可选量级词 + 可选百分号，此外一个词都不许有。
     * 量级词只认全写（3M 是一家公司，不是三百万）；单位照抄符号，不猜币种。
     */
    public static Quantity parseQuantity(String s) {
        return scanQuantity(s, true);
    }

    /** lib.rs:429-431 parse_leading_quantity：开头是一个量、后面还挂着词的 → 那个量（已声明 datatype=number 的宽读法）。 */
    public static Quantity parseLeadingQuantity(String s) {
        return scanQuantity(s, false);
    }

    /** (数值, 单位|null)。 */
    public record Quantity(double value, String unit) {
    }

    /**
     * lib.rs:434-450 currency_unit：货币——符号、ISO 码、中英文单词，统一成符号。只认这张
     * 表，认不出的不猜。
     */
    static String currencyUnit(String tok) {
        String t = tok.replaceAll("^[,.;]+|[,.;]+$", "");
        return switch (t) {
            case "$", "USD", "usd", "US$", "dollar", "dollars", "美元" -> "$";
            case "€", "EUR", "eur", "euro", "euros", "欧元" -> "€";
            case "£", "GBP", "gbp", "pound", "pounds", "英镑" -> "£";
            case "¥", "JPY", "jpy", "yen", "日元" -> "¥";
            case "CNY", "cny", "RMB", "rmb", "yuan", "人民币", "元", "元人民币", "人民币元" -> "¥";
            case "HKD", "hkd", "HK$", "港元", "港币" -> "HK$";
            case "₩", "KRW", "won", "韩元" -> "₩";
            case "₹", "INR", "rupee", "rupees", "卢比" -> "₹";
            default -> null;
        };
    }

    /** lib.rs:452-464 magnitude：量级词对应的十进制指数。不认单字母。 */
    static Integer magnitude(String tok) {
        return switch (tok) {
            case "thousand", "千" -> 3;
            case "万" -> 4;
            case "million", "百万" -> 6;
            case "千万" -> 7;
            case "亿" -> 8;
            case "billion", "十亿" -> 9;
            case "trillion", "万亿" -> 12;
            default -> null;
        };
    }

    /**
     * lib.rs:469-554 scan_quantity：[前缀货币] 数字 [量级] [后缀货币/单位] [其余]。
     * strict = 整体必须就是一个量：其余部分非空就不认。量级走十进制指数一次转换——
     * 先乘再进位会吃掉真实的小数尾巴。
     */
    static Quantity scanQuantity(String input, boolean strict) {
        String s = input.strip();
        if (s.isEmpty()) {
            return null;
        }
        boolean percent = false;
        if (s.endsWith("%")) {
            percent = true;
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        // 1. 前缀货币：符号紧贴，或 ISO 码/单词后跟空格
        String rest = s;
        String currency = null;
        if (!rest.isEmpty()) {
            String first = new String(Character.toChars(rest.codePointAt(0)));
            String u = currencyUnit(first);
            if (u != null) {
                currency = u;
                rest = rest.substring(first.length()).stripLeading();
            }
        }
        if (currency == null) {
            int ws = firstWhitespace(rest);
            if (ws >= 0) {
                String head = rest.substring(0, ws);
                String u = currencyUnit(head);
                if (u != null) {
                    currency = u;
                    rest = rest.substring(ws).stripLeading();
                }
            }
        }
        // 2. 数字：前导的 [-+0-9.,_]
        int numEnd = 0;
        while (numEnd < rest.length()) {
            char c = rest.charAt(numEnd);
            if ((c >= '0' && c <= '9') || c == '.' || c == ',' || c == '_' || c == '-' || c == '+') {
                numEnd++;
            } else {
                break;
            }
        }
        String num = rest.substring(0, numEnd);
        String after = rest.substring(numEnd);
        String cleaned = num.replace(",", "").replace("_", "");
        double n;
        try {
            n = Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
        // 3. 数字后面：紧贴或空格隔开的量级词、货币词，逐个吃
        String tail = after.stripLeading();
        String unit = null;
        boolean ateMagnitude = false;
        while (!tail.isEmpty()) {
            Tok tok = nextToken(tail);
            if (!ateMagnitude) {
                Integer m = magnitude(tok.tok());
                if (m != null) {
                    try {
                        // 一次十进制转换（先乘再进位会吃掉小数尾巴）
                        n = Double.parseDouble(cleaned + "e" + m);
                        ateMagnitude = true;
                        tail = tok.rest().stripLeading();
                        continue;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
            if (unit == null && currency == null) {
                String u = currencyUnit(tok.tok());
                if (u != null) {
                    unit = u;
                    tail = tok.rest().stripLeading();
                    continue;
                }
            }
            break;
        }
        if (percent && (currency != null || unit != null)) {
            return null;
        }
        if (!Double.isFinite(n)) {
            return null;
        }
        String finalUnit = percent ? "%" : (currency != null ? currency : unit);
        if (strict) {
            return tail.isEmpty() ? new Quantity(n, finalUnit) : null;
        }
        // 宽松：其余部分的第一个词当单位（1,250 people → people），没有货币时才用
        if (finalUnit == null && !tail.isEmpty()) {
            return new Quantity(n, nextToken(tail).tok());
        }
        return new Quantity(n, finalUnit);
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    record Tok(String tok, String rest) {
    }

    /**
     * lib.rs:556-579 next_token：ASCII 按空白切；CJK 试最长四字、三字、两字、一字里能认出
     * 的量级/货币词，都认不出就取到下一个空白为止。
     */
    static Tok nextToken(String s) {
        int first = s.codePointAt(0);
        if (first < 0x80) {
            int end = firstWhitespace(s);
            if (end < 0) {
                end = s.length();
            }
            return new Tok(s.substring(0, end), s.substring(end));
        }
        // 候选长度按 UTF-16 边界截（charAt 索引），不是码点值——码点值当索引用会在
        // CJK 上直接越界
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < s.length(); i += Character.charCount(s.codePointAt(i))) {
            idx.add(i);
        }
        idx.add(s.length());
        for (int len : new int[]{4, 3, 2, 1}) {
            if (idx.size() > len) {
                String cand = s.substring(0, idx.get(len));
                if (magnitude(cand) != null || currencyUnit(cand) != null) {
                    return new Tok(cand, s.substring(idx.get(len)));
                }
            }
        }
        int end = firstWhitespace(s);
        if (end < 0) {
            end = s.length();
        }
        return new Tok(s.substring(0, end), s.substring(end));
    }

    // ---------------------------------------------------------------------------
    // 属性值归一与契约时间
    // ---------------------------------------------------------------------------

    /**
     * lib.rs:584-646 normalize_attr_value：属性值按 datatype 归一。失败返回 null——宁缺勿脏。
     * number 容忍千分位/空格（65 与 "65%" 是同一个数）；date 收规则 3 的格式与写得清的
     * 写法（#688）；bool 宽容 yes/no。
     */
    public static JsonNode normalizeAttrValue(String datatype, JsonNode raw) {
        switch (datatype) {
            case "number" -> {
                if (raw.isNumber()) {
                    return finiteNumber(raw.asDouble());
                }
                if (raw.isTextual()) {
                    String cleaned = raw.asText().replaceAll("[, _]", "");
                    Double v = null;
                    try {
                        v = Double.parseDouble(cleaned);
                    } catch (NumberFormatException ignore) {
                        // 清洗解不动的再当量解：$5 billion、1,250 people、42% ……
                        Quantity q = parseLeadingQuantity(raw.asText());
                        if (q != null) {
                            v = q.value();
                        }
                    }
                    return v != null && Double.isFinite(v) ? finiteNumber(v) : null;
                }
                return null;
            }
            case "date" -> {
                if (!raw.isTextual()) {
                    return null;
                }
                String s = raw.asText().strip();
                var wd = writtenDate(s);
                if (wd != null) {
                    String out = "month".equals(wd.precision())
                            ? wd.date().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                            : wd.date().toString();
                    return JSON.getNodeFactory().textNode(out);
                }
                return parseTime(s) != null ? JSON.getNodeFactory().textNode(s) : null;
            }
            case "bool" -> {
                if (raw.isBoolean()) {
                    return raw;
                }
                if (raw.isTextual()) {
                    return switch (raw.asText().strip().toLowerCase(Locale.ROOT)) {
                        case "true", "yes", "是" -> JSON.getNodeFactory().booleanNode(true);
                        case "false", "no", "否" -> JSON.getNodeFactory().booleanNode(false);
                        default -> null;
                    };
                }
                return null;
            }
            default -> {
                String s = null;
                if (raw.isTextual()) {
                    s = raw.asText().strip();
                } else if (raw.isNumber()) {
                    s = raw.asText();
                }
                if (s == null || s.isEmpty()) {
                    return null;
                }
                String cut = s.codePoints().limit(500)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint,
                                StringBuilder::append)
                        .toString();
                return JSON.getNodeFactory().textNode(cut);
            }
        }
    }

    private static JsonNode finiteNumber(double v) {
        return Double.isFinite(v) ? JSON.getNodeFactory().numberNode(v) : null;
    }

    /** (UTC 时刻, 精度)。 */
    public record ParsedTime(OffsetDateTime time, String precision) {
    }

    /** (日期, 精度)。 */
    public record WrittenDate(LocalDate date, String precision) {
    }

    /**
     * lib.rs:656-691 parse_time：契约格式 YYYY / YYYY-MM / YYYY-MM-DD，带时区的时刻
     * （HH[:MM[:SS]] + Z/±HH:MM）。没有时区的钟点不是时刻——只取日期那一半，按天。
     * 亚秒一律丢：账本到秒为止。
     */
    public static ParsedTime parseTime(String input) {
        String s = input.strip();
        if (s.isEmpty() || s.equalsIgnoreCase("null")) {
            return null;
        }
        int sep = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'T' || c == ' ') {
                sep = i;
                break;
            }
        }
        if (sep >= 0) {
            LocalDate d;
            try {
                d = LocalDate.parse(s.substring(0, sep));
            } catch (Exception e) {
                return null;
            }
            String clock = s.substring(sep + 1);
            Zone zone = splitZone(clock);
            if (zone == null) {
                return new ParsedTime(d.atStartOfDay().atOffset(ZoneOffset.UTC), "day");
            }
            String[] parts = clock(clock.substring(0, clock.length() - zone.suffix().length())
                    .strip());
            if (parts == null) {
                return null;
            }
            try {
                LocalDateTime t = d.atTime(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
                return new ParsedTime(t.toInstant(zone.offset()).atOffset(ZoneOffset.UTC),
                        parts[3]);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            LocalDate d = LocalDate.parse(s);
            return new ParsedTime(d.atStartOfDay().atOffset(ZoneOffset.UTC), "day");
        } catch (Exception ignore) {
            // 落到月/年
        }
        try {
            LocalDate d = LocalDate.parse(s + "-01");
            return new ParsedTime(d.atStartOfDay().atOffset(ZoneOffset.UTC), "month");
        } catch (Exception ignore) {
            // 落到年
        }
        if (s.length() == 4) {
            try {
                int year = Integer.parseInt(s);
                return new ParsedTime(
                        LocalDate.of(year, 1, 1).atStartOfDay().atOffset(ZoneOffset.UTC), "year");
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String[] clock(String clock) {
        String[] parts = clock.split(":");
        return switch (parts.length) {
            case 1 -> new String[]{parts[0], "0", "0", "hour"};
            case 2 -> new String[]{parts[0], parts[1], "0", "minute"};
            case 3 -> new String[]{parts[0], parts[1], parts[2].split("\\.")[0], "second"};
            default -> null;
        };
    }

    record Zone(String suffix, ZoneOffset offset) {
    }

    /** lib.rs:781-798 split_zone：Z 或 ±HH[:]MM；没有时区返回 null。 */
    static Zone splitZone(String clock) {
        if (clock.endsWith("Z") || clock.endsWith("z")) {
            return new Zone(clock.substring(clock.length() - 1), ZoneOffset.UTC);
        }
        int i = -1;
        for (int k = clock.length() - 1; k >= 0; k--) {
            char c = clock.charAt(k);
            if (c == '+' || c == '-') {
                i = k;
                break;
            }
        }
        if (i < 0) {
            return null;
        }
        String zone = clock.substring(i);
        int sign = zone.startsWith("-") ? -1 : 1;
        StringBuilder digits = new StringBuilder();
        for (char ch : zone.substring(1).toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        String d = digits.toString();
        String hh;
        String mm;
        if (d.length() == 2) {
            hh = d.substring(0, 2);
            mm = "0";
        } else if (d.length() == 4) {
            hh = d.substring(0, 2);
            mm = d.substring(2);
        } else {
            return null;
        }
        try {
            return new Zone(zone, ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(hh),
                    Integer.parseInt(mm)));
        } catch (Exception e) {
            return null;
        }
    }

    /** lib.rs:695-703 read_time：先按规则 3；没照它写、但写得清是哪天的日期也收（#688）。 */
    public static ParsedTime readTime(String s) {
        ParsedTime t = parseTime(s);
        if (t != null) {
            return t;
        }
        WrittenDate wd = writtenDate(s);
        if (wd == null) {
            return null;
        }
        return new ParsedTime(wd.date().atStartOfDay().atOffset(ZoneOffset.UTC), wd.precision());
    }

    /**
     * lib.rs:715-745 written_date（#688）：月份写成名字的（March 17, 2020、17 Mar 2020）
     * 与年在前的数字（2020/03/17、2020年3月17日）。日、月都是数字而年不在前的不收——
     * 03/04/2020 说不清是三月四日还是四月三日，猜错一次就是一个错的截止日。
     */
    public static WrittenDate writtenDate(String input) {
        String s = input.strip();
        var yf = yearFirstDate(s);
        if (yf instanceof YearFound found) {
            return found.date();
        }
        List<String> words = new ArrayList<>();
        for (String w : s.split("[\\s,.]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        java.util.function.Predicate<String> isNumber =
                w -> !w.isEmpty() && w.chars().allMatch(c -> c >= '0' && c <= '9');
        String day = null;
        String month = null;
        String year = null;
        if (words.size() == 3 && !isNumber.test(words.get(0)) && isNumber.test(words.get(1))
                && isNumber.test(words.get(2))) {
            month = words.get(0);
            day = words.get(1);
            year = words.get(2);
        } else if (words.size() == 3 && isNumber.test(words.get(0)) && !isNumber.test(words.get(1))
                && isNumber.test(words.get(2))) {
            day = words.get(0);
            month = words.get(1);
            year = words.get(2);
        } else if (words.size() == 2 && !isNumber.test(words.get(0)) && isNumber.test(words.get(1))) {
            month = words.get(0);
            year = words.get(1);
        } else {
            return null;
        }
        if (year.length() != 4 || (day != null && day.length() > 2)) {
            return null;
        }
        Integer monthNum = monthByName(month);
        if (monthNum == null) {
            return null;
        }
        int y;
        try {
            y = Integer.parseInt(year);
        } catch (NumberFormatException e) {
            return null;
        }
        try {
            if (day != null) {
                return new WrittenDate(LocalDate.of(y, monthNum, Integer.parseInt(day)), "day");
            }
            return new WrittenDate(LocalDate.of(y, monthNum, 1), "month");
        } catch (Exception e) {
            return null;
        }
    }

    /** chrono %B/%b 的名字月份（整名或三个字母的缩写，不分大小写）。 */
    private static Integer monthByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String[] full = {"january", "february", "march", "april", "may", "june", "july",
                "august", "september", "october", "november", "december"};
        for (int i = 0; i < full.length; i++) {
            if (full[i].equals(lower)) {
                return i + 1;
            }
        }
        String[] abbr = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct",
                "nov", "dec"};
        for (int i = 0; i < abbr.length; i++) {
            if (abbr[i].equals(lower)) {
                return i + 1;
            }
        }
        return null;
    }

    sealed interface YearFirst permits YearFound, NotThisForm {
    }

    record NotThisForm() implements YearFirst {
        static final NotThisForm NOT_THIS_FORM = new NotThisForm();
    }

    record YearFound(WrittenDate date) implements YearFirst {
    }

    /**
     * lib.rs:749-779 year_first_date：年在前的数字日期。NOT_THIS_FORM = 不是这种写法，
     * 交给下一种；null 不是这种写法的第三态由 YearFound(null date) 表达——是这种写法但
     * 不是真实的日子/年不在前（日月顺序说不清，不交给别的写法去猜）。
     */
    static YearFirst yearFirstDate(String s) {
        boolean marked = s.contains("年");
        boolean separated = (s.contains("/") || s.contains(".")) && firstWhitespace(s) < 0;
        if (!marked && !separated) {
            return NotThisForm.NOT_THIS_FORM;
        }
        String trimmed = s.endsWith("日") ? s.substring(0, s.length() - 1) : s;
        List<String> parts = new ArrayList<>();
        for (String p : trimmed.split("[/.年月]")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        for (String p : parts) {
            for (char c : p.toCharArray()) {
                if (c < '0' || c > '9') {
                    return NotThisForm.NOT_THIS_FORM;
                }
            }
            if (p.isEmpty()) {
                return NotThisForm.NOT_THIS_FORM;
            }
        }
        if (parts.isEmpty() || parts.get(0).length() != 4) {
            // 年不在前：日月顺序说不清，不交给别的写法去猜
            return new YearFound(null);
        }
        int year;
        try {
            year = Integer.parseInt(parts.get(0));
        } catch (NumberFormatException e) {
            return new YearFound(null);
        }
        if (parts.size() == 3 && parts.get(1).length() <= 2 && parts.get(2).length() <= 2) {
            try {
                return new YearFound(new WrittenDate(
                        LocalDate.of(year, Integer.parseInt(parts.get(1)),
                                Integer.parseInt(parts.get(2))), "day"));
            } catch (Exception e) {
                return new YearFound(null);
            }
        }
        // 只到月：写了「年」「月」才算（2020/03 太像别的东西）
        if (parts.size() == 2 && marked && s.endsWith("月") && parts.get(1).length() <= 2) {
            try {
                return new YearFound(new WrittenDate(
                        LocalDate.of(year, Integer.parseInt(parts.get(1)), 1), "month"));
            } catch (Exception e) {
                return new YearFound(null);
            }
        }
        return new YearFound(null);
    }
}
