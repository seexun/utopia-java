package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.Namesake;
import tech.seexun.jknow.store.entity.Precedent;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.TypePairStats;
import tech.seexun.jknow.store.mapper.GovernanceMapper;

/**
 * 治理（0025）store 全量（M4 任务 1，逐字搬迁 crates/utopia-store/src/governance.rs）：
 * agent 先读台账再裁。三件事——<b>先例</b>（这个库里的人对这一对、这个名字、这种类型对
 * 做过什么）、<b>队列</b>（等人的重复对按先进先出排，队头带着它的簇一起走）、<b>闸门</b>
 * （一个纯函数，说这条判决是自己动手还是写成建议）。模型调用在 server 的 govern 任务里
 * （任务 4），这里不碰模型。
 *
 * <p>先例只认人的决定（actor 不为空）。agent 自己裁过的不算——否则它引用自己，越判越
 * 自信。人接受了 agent 的建议算：那一笔是人签的，走的是人的裁决路径。
 */
@Service
public class GovernanceService {

    /**
     * 自己动手的置信度线（governance.rs:21）。比攒批裁决器的 0.8 高一点：那一档只看
     * 这一对，这里是替人做主。
     */
    public static final float AUTO_CONF = 0.85f;
    /** 历史同意时的线（governance.rs:23）：人对这一对、这个名字或这种类型对做过同样的决定，agent 的把握可以低一点。 */
    public static final float SUPPORTED_CONF = 0.75f;
    /** 类型对的习惯要有多少笔人的决定才算数（governance.rs:25）。 */
    public static final long TYPE_PAIR_MIN = 5;
    /** 每族先例最多带几条进提示词（governance.rs:27）。 */
    private static final long PER_FAMILY = 8;
    /** 保险丝（0025 决定 9）：多少天里撤回几次就跳（governance.rs:1060-1061）。 */
    public static final long FUSE_WINDOW_DAYS = 7;
    public static final long FUSE_REVERTS = 2;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final GovernanceMapper mapper;
    private final ReviewService reviews;
    private final DataSource dataSource;

    public GovernanceService(GovernanceMapper mapper, ReviewService reviews,
                             DataSource dataSource) {
        this.mapper = mapper;
        this.reviews = reviews;
        this.dataSource = dataSource;
    }

    // ====================================================================
    // 先例三族（governance.rs:77-258）

    /**
     * governance.rs:77-145 {@code precedents_for}：四族先例各归各。SQL 在
     * GovernanceMapper（COLS/L/R 模板逐字）；这里只做两侧名字的小写化与类型对的
     * 「两侧都有才统计」。
     */
    public Precedents precedentsFor(UUID kbId, ReviewItem item) {
        String a = item.left().name().toLowerCase(Locale.ROOT);
        String b = item.right().name().toLowerCase(Locale.ROOT);
        List<Precedent> samePair = mapper.selectSamePair(kbId, a, b, PER_FAMILY);
        List<Precedent> sameName = mapper.selectSameName(kbId, a, b, PER_FAMILY);
        List<Precedent> reverts = mapper.selectRevertPrecedents(kbId, a, b, PER_FAMILY);
        // 类型对先查两侧的 type_id：一侧没类型就没有这一族
        Map<UUID, UUID> typeOf = new HashMap<>();
        for (GovernanceMapper.TypeIdRow row : mapper.selectEntityTypes(
                List.of(item.left().id(), item.right().id()))) {
            typeOf.put(row.id(), row.typeId());
        }
        UUID ta = typeOf.get(item.left().id());
        UUID tb = typeOf.get(item.right().id());
        TypePairStats typePair = ta != null && tb != null ? typePairStats(kbId, ta, tb) : null;
        return new Precedents(samePair, sameName, typePair, reverts);
    }

    /** governance.rs:148-185 {@code type_pair_stats}：这个库对这种类型对的习惯。 */
    private TypePairStats typePairStats(UUID kbId, UUID ta, UUID tb) {
        GovernanceMapper.TypePairCountsRow counts = mapper.selectTypePairCounts(kbId, ta, tb);
        long reverted = mapper.selectTypePairReverted(kbId, ta, tb);
        return new TypePairStats(counts.merged(), counts.kept(), reverted);
    }

    /** 先例行落进提示词的日期（chrono DateTime&lt;Utc&gt; 的 %Y-%m-%d——按 UTC 展示）。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * governance.rs:188-235 {@code render_lines}：先例写成提示词里的行。英文：读者是
     * 模型。人写了理由就带上——模型该学的是「凭什么」，不是「多半怎么判」。
     */
    public static List<String> renderLines(Precedents p) {
        List<String> out = new ArrayList<>();
        for (Precedent x : p.samePair()) {
            out.add("this same pair was " + verb(x) + " by a person on " + day(x) + because(x));
        }
        for (Precedent x : p.sameName()) {
            out.add("\"" + x.left() + "\" against \"" + x.right() + "\" was " + verb(x)
                    + " by a person on " + day(x) + because(x));
        }
        TypePairStats t = p.typePair();
        if (t != null && t.merged() + t.kept() > 0) {
            out.add("for pairs of these two types in this base, people merged " + t.merged()
                    + ", kept " + t.kept() + " apart, and later reverted " + t.reverted()
                    + " merge(s)");
        }
        for (Precedent x : p.reverts()) {
            out.add("a merge of \"" + x.left() + "\" into \"" + x.right()
                    + "\" was reverted by a person on " + day(x) + because(x));
        }
        return out;
    }

    private static String verb(Precedent x) {
        return x.merged() ? "merged" : "kept apart";
    }

    private static String day(Precedent x) {
        return x.at().withOffsetSameInstant(ZoneOffset.UTC).format(DAY);
    }

    private static String because(Precedent x) {
        return x.why() == null ? "" : "; they wrote: \"" + x.why() + "\"";
    }

    /**
     * governance.rs:238-258 {@code precedents_json}：落进 agent_decisions.precedents 的
     * 样子——每条带一个 family。
     */
    public static JsonNode precedentsJson(Precedents p) {
        ArrayNode all = JSON.createArrayNode();
        appendFamily(all, "same_pair", p.samePair());
        appendFamily(all, "same_name", p.sameName());
        appendFamily(all, "revert", p.reverts());
        TypePairStats t = p.typePair();
        if (t != null) {
            ObjectNode o = all.addObject();
            o.put("family", "type_pair");
            o.put("merged", t.merged());
            o.put("kept", t.kept());
            o.put("reverted", t.reverted());
        }
        return all;
    }

    private static void appendFamily(ArrayNode all, String family, List<Precedent> xs) {
        for (Precedent x : xs) {
            ObjectNode o = all.addObject();
            o.put("family", family);
            o.put("event_id", x.eventId().toString());
            o.put("action", x.action());
            o.put("left", x.left());
            o.put("right", x.right());
            // serde DateTime<Utc> → RFC3339（UTC 尾 Z），与 Rust 台账 JSON 同形
            o.put("at", x.at().withOffsetSameInstant(ZoneOffset.UTC).toString());
            // serde 对 Option::None 也写键（"why": null）——putNull 保键存在（审查 M-1）
            if (x.why() != null) {
                o.put("why", x.why());
            } else {
                o.putNull("why");
            }
        }
    }

    // ====================================================================
    // 名字的形状（governance.rs:260-441 纯函数，断言不改写）

    /** 名字的形状（governance.rs:261-277 NameShape）：不靠模型也能看出来的那几种。 */
    public enum NameShape {
        /** 一字不差（忽略大小写与开头的 the）。 */
        IDENTICAL,
        /** 一个是另一个去掉了前面的限定词：Google DeepMind / DeepMind。 */
        ABBREVIATION,
        /** 一个是另一个加了公司后缀：Apple Inc. / Apple、Thrive Capital / Thrive。 */
        SUFFIX,
        /** 一个是另一个加了版本、届次：Claude 4 / Claude、AlphaFold2 / AlphaFold、2024 IMO / IMO。 */
        VERSION,
        /** 一个是另一个加了一两个词：DeepMind Health / DeepMind、Gemini Robotics-ER / Gemini Robotics。 */
        EXTENSION,
        /** 一个是含着另一个的一句话：Sam Altman's efforts / Sam Altman；或列表 / 成员。 */
        PHRASE,
        /** 互不包含。 */
        UNRELATED
    }

    /**
     * governance.rs:279-292 {@code norm_name}：去首尾空白、小写、去开头冠词、去尾括号
     * 缩写（reinforcement learning (RL)——末尾括号里的缩写不是版本），空白归一。
     */
    static String normName(String s0) {
        String s = s0.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("the ")) {
            s = s.substring(4);
        }
        int i = s.lastIndexOf(" (");
        // Rust: (Some(i), true) if s[i+2..len-1] 全字母数字且 len-i <= 12（对空串 all() 恒真，
        // 照搬）。i+2 <= len-1 的界是 Rust 切片自带的——"()" 空括号那一步 Rust 会 panic，
        // 这里按「没有括号后缀」处理（真实名字不会走到）
        if (i >= 0 && s.endsWith(")") && s.length() - i <= 12 && i + 2 <= s.length() - 1
                && s.substring(i + 2, s.length() - 1).chars().allMatch(
                        c -> Character.isLetter(c) || Character.isDigit(c))) {
            s = s.substring(0, i);
        }
        // split_whitespace + join(" ")：连续空白压成一个，首尾不留
        StringBuilder sb = new StringBuilder(s.length());
        boolean inWord = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (Character.isWhitespace(c)) {
                inWord = false;
            } else {
                if (!inWord && sb.length() > 0) {
                    sb.append(' ');
                }
                inWord = true;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** governance.rs:294-313 CORPORATE_SUFFIX 词表（逐字）。 */
    private static final List<String> CORPORATE_SUFFIX = List.of(
            "inc", "ltd", "llc", "plc", "corp", "corporation", "co", "company", "capital",
            "group", "platforms", "industries", "technologies", "holdings", "limited", "gmbh",
            "sa", "ag");

    /** governance.rs:369-390 phrase_marker 介词表（逐字，含 's 两形态）。 */
    private static final List<String> PHRASE_MARKERS = List.of(
            " of ", " by ", " from ", " for ", " against ", " with ", " to ", " in ", " on ",
            " at ", " and ", " or ", " led ", " into ");

    /**
     * governance.rs:315-441 {@code name_shape}（逐字）。要整词出现：Time 在 Financial
     * Times 里不算（后面跟着字母），AlphaFold 在 AlphaFold2 里算（后面跟着数字，那是
     * 版本）。
     */
    public static NameShape nameShape(String aRaw, String bRaw) {
        String a = normName(aRaw);
        String b = normName(bRaw);
        if (a.equals(b)) {
            return NameShape.IDENTICAL;
        }
        String shortS = a.length() <= b.length() ? a : b;
        String longS = a.length() <= b.length() ? b : a;
        Integer pos = wordAt(longS, shortS);
        if (pos == null) {
            return NameShape.UNRELATED;
        }
        if (shortS.isEmpty()) {
            return NameShape.UNRELATED;
        }
        if (countMatches(longS, ", ") >= 2) {
            return NameShape.PHRASE;
        }
        // 短的那个在长的里到哪里结束：复数的 s 算在里面
        int end;
        {
            String rest = longS.substring(pos + shortS.length());
            if (!rest.isEmpty() && rest.charAt(0) == 's' && (rest.length() == 1
                    || !isAlphanumeric(rest.charAt(1)))) {
                end = pos + shortS.length() + 1;
            } else {
                end = pos + shortS.length();
            }
        }
        if (end == longS.length()) {
            String front = trimEndMatches(longS.substring(0, pos).strip(), ',', '-', ':');
            if (!front.isEmpty() && isAsciiDigit(front.charAt(0))) {
                return NameShape.VERSION;
            }
            if (phraseMarker(front) || wordCount(front) > 2) {
                return NameShape.PHRASE;
            }
            return NameShape.ABBREVIATION;
        }
        if (pos == 0) {
            String tail = trimStartMatches(longS.substring(end).strip(), ',', '-', ':', '.', 'v');
            tail = tail.strip();
            if (!tail.isEmpty() && isAsciiDigit(tail.charAt(0))) {
                return NameShape.VERSION;
            }
            List<String> tailWords = splitAlnum(tail);
            if (!tailWords.isEmpty() && CORPORATE_SUFFIX.containsAll(tailWords)) {
                return NameShape.SUFFIX;
            }
            // 尾巴两个词起就是一句话了：DeepMind Health data sharing、OpenAI Ireland Ltd、
            // Meta Superintelligence Labs——都不是前面那个东西
            if (phraseMarker(tail) || tailWords.size() > 1) {
                return NameShape.PHRASE;
            }
            return NameShape.EXTENSION;
        }
        return NameShape.PHRASE;
    }

    /**
     * governance.rs:327-357 {@code word_at}：short 在 long 里的整词位置。后面跟着字母
     * 不算整词——除非只多一个复数的 s（sorting algorithms 含着 sorting algorithm，
     * Times 含着 Time）。匹配失败<b>前进一个字符再找</b>：Rust 版按字节前进时 i+1 落在
     * 多字节字符中间会让下一轮切片直接 panic（中文名字一撞就炸，队列静默少一格，
     * 2026-09-08 实测）——Java String 按 char 前进天然安全，语义与 Rust 修后的按字符
     * 前进一致。
     */
    private static Integer wordAt(String longS, String shortS) {
        int from = 0;
        while (true) {
            int i = longS.indexOf(shortS, from);
            if (i < 0) {
                return null;
            }
            if (i < from) {
                return null; // 空针时 indexOf 回落到 len 不再前进——停（Rust 到这一步已 panic）
            }
            boolean beforeOk = i == 0 || !isAlphanumeric(longS.charAt(i - 1));
            // 后面跟着字母不算整词——除非只多一个复数的 s
            String rest = longS.substring(i + shortS.length());
            boolean afterOk;
            if (rest.isEmpty()) {
                afterOk = true;
            } else {
                char c = rest.charAt(0);
                if (!Character.isAlphabetic(c)) {
                    afterOk = true;
                } else if (c == 's' && rest.length() == 1) {
                    afterOk = true;
                } else if (c == 's' && rest.length() > 1 && !isAlphanumeric(rest.charAt(1))) {
                    afterOk = true;
                } else {
                    afterOk = false;
                }
            }
            if (beforeOk && afterOk) {
                return i;
            }
            from = i + 1; // 前进一个字符（不是别的单位）——失败重找的安全边界
        }
    }

    /** governance.rs:364-368 {@code words}：按非字母数字切（撇号算词内）计词数。 */
    private static int wordCount(String s) {
        return splitKeepApostrophe(s).size();
    }

    private static List<String> splitKeepApostrophe(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean sep = !isAlphanumeric(c) && c != '\'';
            if (sep) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** governance.rs:426-429 {@code tail_words}：按非字母数字切（撇号不算词内）。 */
    private static List<String> splitAlnum(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isAlphanumeric(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** governance.rs:369-390 {@code phrase_marker}：'s 两形态 + 介词表（两侧带空格匹配）。 */
    private static boolean phraseMarker(String s) {
        if (s.contains("'s ") || s.endsWith("'s")) {
            return true;
        }
        String padded = " " + s + " ";
        for (String m : PHRASE_MARKERS) {
            if (padded.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** 非重叠出现次数（Rust {@code long.matches(", ").count()}）。 */
    private static int countMatches(String s, String needle) {
        int n = 0;
        int from = 0;
        int i;
        while ((i = s.indexOf(needle, from)) >= 0) {
            n++;
            from = i + needle.length();
        }
        return n;
    }

    /** Rust trim_end_matches(chars)：从尾部反复剥掉集合里的字符。 */
    private static String trimEndMatches(String s, char... chars) {
        int end = s.length();
        while (end > 0 && containsChar(chars, s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Rust trim_start_matches(chars)：从头部反复剥掉集合里的字符（'v' 只剥小写）。 */
    private static String trimStartMatches(String s, char... chars) {
        int start = 0;
        while (start < s.length() && containsChar(chars, s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    private static boolean containsChar(char[] chars, char c) {
        for (char x : chars) {
            if (x == c) {
                return true;
            }
        }
        return false;
    }

    /** Rust char::is_alphanumeric（Unicode 字母或数字）。 */
    private static boolean isAlphanumeric(char c) {
        return Character.isLetter(c) || Character.isDigit(c);
    }

    /** Rust char::is_ascii_digit。 */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ====================================================================
    // 类型大类与冲突（governance.rs:443-512）

    /**
     * governance.rs:445-451 类型标签的大类：抽取器给的标签本身很吵（同一家公司在两篇里
     * 是 Organization 与 Corporation），只有大类不同才算冲突。null = 说不上是哪一类，
     * 与谁都不冲突。
     */
    public enum TypeFamily {PERSON, ORGANIZATION, PLACE, EVENT}

    /** governance.rs:453-504 type_family（逐字：前缀/包含规则与词表照搬）。 */
    public static TypeFamily typeFamily(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.equals("person") || l.equals("researcher") || l.endsWith("person")) {
            return TypeFamily.PERSON;
        }
        if (l.contains("event")) {
            return TypeFamily.EVENT;
        }
        if (List.of("organization", "corporation", "business", "store", "hospital",
                "university", "college", "school", "consortium", "ngo", "party", "agency",
                "company", "bank", "fund", "newspaper", "governmentoffice", "team")
                .stream().anyMatch(l::contains)) {
            return TypeFamily.ORGANIZATION;
        }
        if (List.of("place", "city", "country", "state", "continent",
                "administrativearea", "region", "landmark", "civicstructure", "locality")
                .stream().anyMatch(l::contains)) {
            return TypeFamily.PLACE;
        }
        return null;
    }

    /** governance.rs:507-512 {@code types_conflict}：两边的类型标签是不是不同的大类。 */
    public static boolean typesConflict(String a, String b) {
        TypeFamily fa = a == null ? null : typeFamily(a);
        TypeFamily fb = b == null ? null : typeFamily(b);
        return fa != null && fb != null && fa != fb;
    }

    // ====================================================================
    // 闸门（governance.rs:514-571 纯函数）

    /** governance.rs:514-520 Gate：自己动手（合并可撤，分开可再合）还是写成建议留给人。 */
    public enum Gate {APPLY, PROPOSE}

    /**
     * governance.rs:543-571 {@code gate}：agent 按自己的把握判，历史是参考（0025 决定
     * 4，2026-09-06 修订）。历史反对就拦住：人分开过这一对却说合、合过却说分、涉及任一
     * 名字的撤回。历史同意就把线放低。其余按 agent 自己的把握。类型冲突永远不合——
     * 那是规则，不是把握。
     */
    public static Gate gate(Boolean same, float conf, boolean typesConflict, NameShape shape,
                            Precedents p) {
        if (same == null) {
            return Gate.PROPOSE;
        }
        if (!p.reverts().isEmpty() || p.samePair().stream().anyMatch(x -> x.merged() != same)) {
            return Gate.PROPOSE;
        }
        // 两条不靠模型的规则：类型的大类不同不合；版本尾巴或含着名字的一句话不自动合
        // ——模型在这两种上最爱说 same，而它们几乎从不是
        if (same && (typesConflict || shape == NameShape.VERSION || shape == NameShape.PHRASE)) {
            return Gate.PROPOSE;
        }
        float bar = p.supports(same) ? SUPPORTED_CONF : AUTO_CONF;
        return conf >= bar ? Gate.APPLY : Gate.PROPOSE;
    }

    /**
     * governance.rs:575-593 {@code decided_before}：同一对实体，人裁过没有（true 合过 /
     * false 分过 / null 没裁过）。按实体 id 认，不按名字——名字一样的两个「张伟」
     * 不是同一个问题。
     */
    public Boolean decidedBefore(UUID kbId, UUID a, UUID b) {
        return mapper.selectDecidedBefore(kbId, a, b);
    }

    /**
     * governance.rs:598-616 {@code settled_by_people}：人已经定过的一对，照人的定——
     * 同一对实体裁过；或者名字不同的一对名字，人对这一对名字的决定一致（「OpenAI
     * OpCo ≟ OpenAI」合过就是合）。同名的一对不算——那只说明库里有过重名，不说明
     * 眼前这两个是谁。有撤回的不走这条路。
     */
    public static Boolean settledByPeople(String leftName, String rightName, Precedents p,
                                          Boolean prior) {
        if (!p.reverts().isEmpty()) {
            return null;
        }
        if (prior != null) {
            return prior;
        }
        if (leftName.toLowerCase(Locale.ROOT).equals(rightName.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (p.samePair().isEmpty()) {
            return null;
        }
        boolean first = p.samePair().get(0).merged();
        for (Precedent x : p.samePair()) {
            if (x.merged() != first) {
                return null;
            }
        }
        return first;
    }

    // ====================================================================
    // BASE_TRY_LOCK（governance.rs:630-661）：一个库同一时刻只有一个治理任务在跑

    /**
     * governance.rs:630-631。会话级咨询锁<b>跟着连接走</b>：每篇文档抽完都排一个治理
     * 任务，而 enqueue_unless_queued 只挡排着的、不挡在跑的——几十个 worker 把它们
     * 一起接起来，十个任务同时读同一个队头、同一簇裁十遍。只用 <b>try</b>：抢不到就
     * 说明有人在治理这个库，那个任务会把队列走完、有积压时再排一个。
     */
    static final String BASE_TRY_LOCK =
            "SELECT pg_try_advisory_lock(hashtextextended('governance:' || ?, 0))";
    static final String BASE_UNLOCK =
            "SELECT pg_advisory_unlock(hashtextextended('governance:' || ?, 0))";

    /**
     * 抢到的锁（governance.rs:635-650 BaseLock）。放掉要显式调 {@link #release()}；
     * 直接丢掉的话连接回池子时锁还挂着，所以 release 解不开就<b>逐出那条连接</b>
     * （物理关闭），让 Postgres 收回它。<b>锁—体—解锁绑同一条连接</b>（M3 任务 7
     * 审查 I-1 先例）：try_lock_base 抢到的连接由 BaseLock 持有到 release，中途绝不
     * 归还池子。
     */
    public final class BaseLock {
        private final Connection conn;
        private final String key;

        private BaseLock(Connection conn, String key) {
            this.conn = conn;
            this.key = key;
        }

        /** 解锁失败就逐出连接（Hikari evictConnection / 非 Hikari 直接 close），让 PG 收回。 */
        public void release() {
            boolean unlocked = false;
            try (PreparedStatement ps = conn.prepareStatement(BASE_UNLOCK)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    unlocked = rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                unlocked = false;
            }
            try {
                if (unlocked) {
                    conn.close();
                } else if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                    hikari.evictConnection(conn);
                } else {
                    conn.close();
                }
            } catch (SQLException ignored) {
                // 连接已经死了：锁随会话一起没，无需再做什么
            }
        }
    }

    /**
     * governance.rs:653-661 {@code try_lock_base}：试着拿这个库的治理锁。
     *
     * @return 锁；null = 别的任务正拿着
     */
    public BaseLock tryLockBase(UUID kbId) {
        String key = kbId.toString();
        try {
            // 专属连接持会话级锁——绝不能用模板两次各借各的（借到的是不同会话，锁看不见）
            Connection conn = dataSource.getConnection();
            boolean got;
            try (PreparedStatement ps = conn.prepareStatement(BASE_TRY_LOCK)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    got = rs.getBoolean(1);
                }
            }
            if (got) {
                return new BaseLock(conn, key);
            }
            conn.close();
            return null;
        } catch (SQLException e) {
            throw AppException.db("try_lock_base", e);
        }
    }

    // ====================================================================
    // 队列与簇（governance.rs:663-706）

    /**
     * governance.rs:664-676 {@code queue}：等人的重复对，先进先出。有一条开着的建议
     * 的对不进（等人答）；正在裁的也不进（那是这一次任务自己手里的簇）。
     */
    public List<ReviewItem> queue(UUID kbId, long limit) {
        return reviews.assembleReviews(kbId, mapper.selectQueueRows(kbId, limit));
    }

    /**
     * governance.rs:679-706 {@code cluster_of}：队头的簇——与它同名或同实体的其他等人
     * 的对，同样先进先出。人裁了一对之后，同簇剩下的对要带着新先例再看（任务 4 的
     * after_human_decision 依赖这个口径）。
     */
    public List<ReviewItem> clusterOf(UUID kbId, ReviewItem head, long limit) {
        List<UUID> entityIds = List.of(head.left().id(), head.right().id());
        List<String> names = List.of(head.left().name().toLowerCase(Locale.ROOT),
                head.right().name().toLowerCase(Locale.ROOT));
        return reviews.assembleReviews(kbId,
                mapper.selectClusterRows(kbId, head.id(), entityIds, names, limit));
    }

    // ====================================================================
    // agent_decisions 写读全族（governance.rs:708-1121）

    /** governance.rs:708-725 NewDecision：agent 的一笔（record 的 14 列载荷）。 */
    public record NewDecision(
            UUID runId,
            UUID targetId,
            /** merge | keep | unsure */
            String action,
            float confidence,
            String reason,
            JsonNode precedents,
            /** proposed | applied */
            String status,
            UUID mergeId,
            /** defer 留给人的问题（第二刀） */
            String question,
            /** 循环看了什么：[{tool, args, note}] */
            JsonNode trace,
            /** 循环里花的模型调用 */
            int calls) {
    }

    /**
     * governance.rs:727-751 {@code record}：14 列 INSERT（target_kind 固定 'review'）。
     * 同一目标同时只有一条开着的建议——agent_decisions_open_idx 唯一索引兜底。
     */
    public UUID record(UUID kbId, NewDecision d) {
        UUID id = Uuids.nowV7();
        mapper.insertDecision(id, kbId, d.runId(), d.targetId(), d.action(), d.confidence(),
                d.reason(), d.precedents(), d.status(), d.mergeId(), d.question(), d.trace(),
                d.calls());
        return id;
    }

    /** governance.rs:754-763 {@code loop_calls_today}：今天这个库在循环里花了几次模型调用（每库每天预算按它算）。 */
    public long loopCallsToday(UUID kbId) {
        return mapper.selectLoopCallsToday(kbId);
    }

    /** governance.rs:875-889 {@code list}：Agent 队列，最新的在前。 */
    public List<tech.seexun.jknow.store.entity.AgentDecisionView> list(UUID kbId, long limit,
                                                                        long offset) {
        return mapper.selectDecisionList(kbId, limit, offset);
    }

    /** governance.rs:892-899 {@code get}（无行 → NotFound）。 */
    public tech.seexun.jknow.store.entity.AgentDecisionView get(UUID kbId, UUID id) {
        tech.seexun.jknow.store.entity.AgentDecisionView v = mapper.selectDecision(kbId, id);
        if (v == null) {
            throw AppException.NOT_FOUND;
        }
        return v;
    }

    /** governance.rs:902-911 {@code agent_running}：这个库的 govern 任务此刻在跑（Rust 以 kb_id.to_string() 比对 payload 文本）。 */
    public boolean agentRunning(UUID kbId) {
        return Boolean.TRUE.equals(mapper.selectAgentRunning(kbId.toString()));
    }

    /** governance.rs:914-923 {@code queue_len}：还没轮到 agent 看的对。 */
    public long queueLen(UUID kbId) {
        return mapper.selectQueueLen(kbId);
    }

    /**
     * governance.rs:927-937 {@code lock}：agent 正在裁的一簇标成 adjudicating——
     * 界面上按钮灰掉、接口上拒绝人改（模型调用在飞的时候人把一对裁了，落地那一步
     * 会撞上）。
     */
    public void lock(UUID kbId, List<UUID> ids) {
        mapper.lockReviews(kbId, ids);
    }

    /** governance.rs:940-950 {@code release_locks}：任务开始与结束时放开所有锁——一轮中途出错、开关关掉，都不能把对锁死。 */
    public long releaseLocks(UUID kbId) {
        return mapper.releaseLocks(kbId);
    }

    /**
     * governance.rs:954-968 {@code locked_by_agent}（SQL 逐字）：这一对此刻是不是 agent
     * 正在裁的——治理开着、任务在跑、这一对标着 adjudicating。三个条件缺一个都不算
     * 锁：任务没在跑的 adjudicating 只是旧裁决器留下的标记，人照样能裁（0025）。
     */
    public boolean lockedByAgent(UUID kbId, UUID reviewId) {
        return Boolean.TRUE.equals(mapper.selectLockedByAgent(kbId, reviewId));
    }

    /** governance.rs:971-979 {@code count_proposed}：开着的建议数（review counts 的 agent 列同源）。 */
    public long countProposed(UUID kbId) {
        return mapper.selectCountProposed(kbId);
    }

    /**
     * governance.rs:983-1007 {@code settle}：人的回答（accepted / overridden 答一条建议，
     * reverted / overridden 答一条自动裁决）。已经答过的不能再答——那是另一个人
     * 刚做的决定。
     */
    public void settle(UUID kbId, UUID id, String status, UUID userId) {
        int n = mapper.settleDecision(kbId, id, status, userId);
        if (n == 0) {
            throw AppException.conflict("this decision has already been answered");
        }
    }

    /**
     * governance.rs:1011-1031 {@code answer_open}：人在卡片上裁了一对——这一对上开着的
     * 建议就此有了答案（动作与建议相同是 accepted，不同是 overridden）；没有开着的
     * 建议就什么都不做。
     */
    public void answerOpen(UUID kbId, UUID reviewId, String action, UUID userId) {
        mapper.answerOpen(kbId, reviewId, action, userId);
    }

    /**
     * governance.rs:1035-1057 {@code supersede_siblings}：人裁了一对——同名或同实体的
     * 其他对上开着的建议过时了（那些建议是在没有这笔先例时写的）。标成 superseded，
     * 那些对回到队列，下一轮带着新先例再看。
     */
    public long supersedeSiblings(UUID kbId, UUID reviewId) {
        return mapper.supersedeSiblings(kbId, reviewId);
    }

    /** governance.rs:1064-1074 {@code reverts_since}：从某一刻起，人撤回了 agent 的自动合并几次。 */
    public long revertsSince(UUID kbId, OffsetDateTime since) {
        return mapper.selectRevertsSince(kbId, since);
    }

    /** governance.rs:1077-1087 {@code trip}：保险丝跳闸——开关开着就关掉。true 表示这一次真的关了（告警只发一次）。 */
    public boolean trip(UUID kbId) {
        return mapper.tripGovernance(kbId) > 0;
    }

    /**
     * governance.rs:1091-1108 {@code settle_by_merge}：人从合并历史撤回了一条合并——
     * 如果那是 agent 自己裁的，那一行记成 reverted。
     *
     * @return 那一行的 id；不是 agent 的合并就是 null
     */
    public UUID settleByMerge(UUID kbId, UUID mergeId, UUID userId) {
        return mapper.selectSettleByMerge(kbId, mergeId, userId);
    }

    /** governance.rs:1111-1121 {@code due}：开关开着、队列里还有 agent 没看过的对的库（定时扫描用）。 */
    public List<UUID> due() {
        return mapper.selectDueKbs();
    }

    // ====================================================================
    // 第二刀三工具（governance.rs:765-861）——facts=entity_fact_lines（M3 已有）与
    // consequences=ExecutionGate（M3 已有）不在这里重做

    /**
     * governance.rs:772-802 {@code quotes_of}：提到这个实体的原文片段——它的事实是从
     * 哪句话里抽出来的，出自哪份文档。证据上有 quote 就用 quote，没有就截分块开头。
     * 名字事实排在后面、引文就是名字本身的不要（0041）。
     */
    public List<GovernanceMapper.QuoteRow> quotesOf(UUID kbId, UUID entityId, long limit) {
        return mapper.selectQuotesOf(kbId, entityId, limit);
    }

    /**
     * governance.rs:805-825 {@code ledger_search}：台账里人对某个名字做过的决定——
     * 名字包含 query 的合并、分开与撤回（只认人的）。
     */
    public List<Precedent> ledgerSearch(UUID kbId, String query, long limit) {
        String like = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
        return mapper.selectLedgerSearch(kbId, like, limit);
    }

    /**
     * governance.rs:836-861 {@code namesakes}：库里名字包含 query 的其他实体——叫这个
     * 名字的有几个、各是什么、各有多少事实。
     */
    public List<Namesake> namesakes(UUID kbId, String query, long limit) {
        String like = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
        return mapper.selectNamesakes(kbId, like, limit);
    }
}
