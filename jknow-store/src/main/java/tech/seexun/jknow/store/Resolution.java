package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 消解纯函数族（resolution.rs:19-188 逐字移植）——阈值、后缀词表、类型漂移分类、余弦。
 * <b>零 SQL、零依赖</b>：语义注释照搬原文（为什么这个数、为什么宁分勿合）。
 *
 * <p>名称规整 {@link Names#normalizeName} 与数字比对 {@link Names#numbersDiffer}
 * 已在 {@link Names}（任务 3 落），此处不重复。
 */
public final class Resolution {

    private Resolution() {
    }

    /**
     * resolution.rs:21-22 上下文相似度阈值（bge-m3 类模型余弦相似度经验值，后续可调）。
     * ≥ ATTACH 归并到既有实体；&lt; NEW 判为不同实体；中间灰区宁分勿合 + 审核项。
     */
    public static final float SIM_ATTACH = 0.55f;
    public static final float SIM_NEW = 0.35f;

    /**
     * resolution.rs:28 同名并列的判定边界：两个<b>同名</b>候选的画像分数相差不超过
     * 这个值，就算「分不开」。画像分不出谁是谁时，attach 到分高的那个只是候选顺序
     * 掷出的硬币（#270）——与其掷硬币，不如两个都不并、都入人工审核。取值偏紧：
     * 真正拉得开的同名人（不同 chunk、不同事实积累）分差远大于此，只有质心几乎重合
     * （如同一 chunk 播种）才触发。
     */
    public static final float SIM_TIE_MARGIN = 0.02f;

    /**
     * resolution.rs:32 旁证宾语的最短长度。低于它的（"IT"、"A 组"）在任何一篇文档里
     * 都可能出现，命中不含信息量。<b>宁可漏掉一条旁证</b>：漏了就是今天的行为，
     * 错了会并错人。
     */
    public static final int MIN_CORROBORATION_CHARS = 2;

    /**
     * resolution.rs:37 每个候选取几个消歧宾语参与旁证。取多个是因为文档提到的未必是
     * 排序最靠前的那一个（他的部门写在这句、职位写在下一句），但也不能全取——
     * 候选的事实越多，撞上一个泛泛的宾语的机会越大。
     */
    public static final long CORROBORATION_OBJECTS = 3;

    /**
     * resolution.rs:673 包含关系候选的最短边：两个名字里<b>较短的那个</b>至少这么长
     * 才算数。低于它的多是「研究院」「中心」这类通名，配对没有信息量，只会刷爆队列。
     */
    public static final int MIN_CONTAIN_CHARS = 4;

    /** resolution.rs:677 单次最多产出的包含关系审阅对（防通名淹没队列）。 */
    public static final int MAX_CONTAIN_REVIEWS = 4;

    /**
     * resolution.rs:681 SQL 侧多取几行：类型硬互斥的在 Java 侧才筛得掉，只取 4 行的话
     * 可能 4 行全是互斥类型，真正那一对反而被 LIMIT 切掉。
     */
    public static final long CONTAIN_SCAN_LIMIT = 16;

    /** resolution.rs:134 单次消解最多入队的漂移审核对（防同名大组刷爆审核队列）。 */
    public static final int MAX_DRIFT_REVIEWS = 4;

    /**
     * resolution.rs:55-56 泛用后缀词表：中文直接拼在词干后；英文按独立词算、首尾两种
     * 语序都认（"Phoenix Project" / "Project Phoenix"）。只影响召回，判定仍走画像相似度。
     */
    static final List<String> GENERIC_SUFFIXES_CJK =
            List.of("项目", "公司", "集团", "部门", "团队");
    static final List<String> GENERIC_WORDS_EN = List.of("project", "corp", "inc", "team");

    /**
     * resolution.rs:60-90 {@code name_stem}：词干——剥去一个泛用后缀后的 lower 形态。
     * 未命中、剥空、或词干本身就是泛用词（"项目团队"）时返回 null。输入应已过
     * {@link Names#normalizeName}。
     */
    public static String nameStem(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suf : GENERIC_SUFFIXES_CJK) {
            if (lower.endsWith(suf)) {
                String stem = stripTrailingSpaces(lower.substring(0, lower.length() - suf.length()));
                if (!stem.isEmpty() && !isGeneric(stem)) {
                    return stem;
                }
            }
        }
        String[] words = lower.split(" ", -1);
        if (words.length >= 2) {
            if (isGeneric(words[words.length - 1])) {
                String stem = joinWords(words, 0, words.length - 1);
                if (!isGeneric(stem)) {
                    return stem;
                }
            }
            if (isGeneric(words[0])) {
                String stem = joinWords(words, 1, words.length);
                if (!isGeneric(stem)) {
                    return stem;
                }
            }
        }
        return null;
    }

    /** Rust {@code strip_punct}：词尾的句读（'.'、','）不算词的一部分。 */
    private static String stripPunct(String w) {
        return w.isEmpty() ? w : w.replaceAll("[.,]+$", "");
    }

    /** Rust {@code trim_end()}：词干剥掉后缀后的尾随空格。 */
    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean isGeneric(String s) {
        return GENERIC_SUFFIXES_CJK.contains(s) || GENERIC_WORDS_EN.contains(stripPunct(s));
    }

    /** Rust {@code words[a..b].join(" ")}：split(' ') 的原样拼接（保空段）。 */
    private static String joinWords(String[] words, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /**
     * resolution.rs:95-116 {@code recall_keys}：mention 的召回键集合（全 lower）——
     * 本名 + 词干 + 词干的泛用后缀增广。增广覆盖反方向（库内是"星尘项目"、mention
     * 只说"星尘"）；词干含 CJK 拼中文尾缀，否则拼英文词（两种语序）。键数 ≤10，
     * 走 (kb,type,lower(name)) 索引多点查。
     */
    public static List<String> recallKeys(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String base = nameStem(name);
        String stem = base != null ? base : lower;
        List<String> keys = new ArrayList<>();
        keys.add(lower);
        if (!keys.contains(stem)) {
            keys.add(stem);
        }
        boolean cjk = stem.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
        if (cjk) {
            for (String suf : GENERIC_SUFFIXES_CJK) {
                add(keys, stem + suf);
            }
        } else {
            for (String w : GENERIC_WORDS_EN) {
                add(keys, stem + " " + w);
                add(keys, w + " " + stem);
            }
        }
        return keys;
    }

    private static void add(List<String> keys, String k) {
        if (!keys.contains(k)) {
            keys.add(k);
        }
    }

    /**
     * resolution.rs:131 {@code CONFUSABLE_TYPE_KEYS}：易混具体类型——抽取常在这几类间
     * 摇摆（一个团队算组织还是项目？平台算项目还是产品？）。同名跨这组类型 →
     * 照建实体（宁分勿合），但入队审核对交 LLM/人工裁决。
     *
     * <p><b>本体说了算，这张表只是没声明时的退路。</b>判两个类能不能指同一个东西，
     * 先看 owl:disjointWith（entity_type_disjoint，含继承）——声明了互斥的一律分开，
     * 哪怕它们在类层级上是一家；没声明的再看类层级（同一支系当易混，#226），最后才是
     * 这三个硬 key。没装包也没声明的库里这三个 key 不存在，于是所有跨类型同名判
     * {@link TypeDrift#DISJOINT}——变严不变松，不会错合（0016 B3）。
     */
    public static final List<String> CONFUSABLE_TYPE_KEYS =
            List.of("organization", "project", "product");

    /**
     * resolution.rs:137-145 跨类型同名的处置分类。
     */
    public enum TypeDrift {
        /** 一侧是兜底类型：当召回候选，走画像相似度分层（可 ATTACH）。 */
        RECALL,
        /** 两个易混具体类型：新建 + 审核对。 */
        REVIEW,
        /** 硬互斥（person vs organization 等，含未知自定义类型）：完全分开。 */
        DISJOINT
    }

    /**
     * resolution.rs:147-172 {@code classify_type_drift}。
     *
     * <p><b>同一个类型当然可能是同一个东西。</b>这一档原本不存在，因为这个函数生来
     * 只服务"类型漂移"——同名被抽成两种类型——那里两边相同根本不会发生。后来
     * containment_reviews 借它当相容性判据，而那里<b>两边相同才是最常见的情形</b>，
     * 于是 person 对 person 落进了最后那行 Disjoint，被读成"永不可能是同一个东西"。
     * 代价是全文最明显的同指关系一对都进不了队列：福尔摩斯前六篇里 Sherlock Holmes
     * 与 Holmes 是两个实体，488 个实体只合并掉 14 个。
     */
    public static TypeDrift classifyTypeDrift(String a, String b) {
        // 同型（含两边都是 null）→ 召回候选
        if (java.util.Objects.equals(a, b)) {
            return TypeDrift.RECALL;
        }
        // 一侧还没判出来 → 当召回候选，走画像相似度分层。从前比的是 FALLBACK_TYPE_KEY，
        // 那个 key 已经没有了：「还没判出来」现在由 null 表达（0009）
        if (a == null || b == null) {
            return TypeDrift.RECALL;
        }
        if (CONFUSABLE_TYPE_KEYS.contains(a) && CONFUSABLE_TYPE_KEYS.contains(b)) {
            return TypeDrift.REVIEW;
        }
        return TypeDrift.DISJOINT;
    }

    /**
     * resolution.rs:174-188 {@code cosine}：维度不等或空向量 → null（换过嵌入模型的
     * 画像不比）；任一侧零模 → null。
     */
    public static Float cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return null;
        }
        float dot = 0f;
        float na = 0f;
        float nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0f || nb == 0f) {
            return null;
        }
        return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * resolution.rs:832-841 {@code drift_reason}：reason 存 code，措辞归界面
     * （docs/decisions/0004）——这一列不该一半 code 一半英文句子，那样中文界面上就是
     * 一半能翻一半不能。未分类的一侧写成 {@code (untyped)}：留空会让 "a vs b" 变成
     * "a vs "，读起来像被截断而不像"没有"。
     */
    public static String driftReason(String mentionKey, String otherKey, Float sim) {
        String a = mentionKey != null ? mentionKey : "(untyped)";
        String b = otherKey != null ? otherKey : "(untyped)";
        return sim != null
                ? "type_drift|" + a + " vs " + b + " " + String.format(Locale.ROOT, "%.2f", sim)
                : "type_drift|" + a + " vs " + b;
    }
}
