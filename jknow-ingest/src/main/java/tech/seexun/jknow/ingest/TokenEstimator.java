package tech.seexun.jknow.ingest;

/**
 * 近似 token 计数（对等 Rust cl100k BPE 的<b>简化</b>——引 tiktoken 过重，任务 2 按简报走
 * 「1 个 CJK 字 ≈ 1 token，≈4 个拉丁字符 ≈ 1 token」起步，再按真实文档双打校准）：
 *
 * <ul>
 *   <li>CJK 字（假名/谚文/全角标点）逐字 {@value #CJK_TOKENS} token——cl100k 对常用汉字
 *       1 token、次常用 2+，中文商务语料均值约 1.2；</li>
 *   <li><b>英文词单元</b>（任务 5 校准）：「空格 + ASCII 字母起头的连续段」整体计——
 *       cl100k 把常见英文词连同前导空格并成单块（" the"、" company" 各 1 token），
 *       逐字符 ÷3.5 + 空格 0.85 的旧算法把英文 prose 数高 2.1-2.5 倍（任务 2 校准集全中文
 *       无留出）。新定价 = 1 + max(0, (词长+空格-{@value #WORD_FREE_CHARS})/{@value #WORD_EXTRA_CHARS_PER_TOKEN})：
 *       短词 1 token，每超约 {@value #WORD_EXTRA_CHARS_PER_TOKEN} 字符再加 1（长词/复合词
 *       在 cl100k 里也确实切成多块）；拟合留出集英文行误差 ≤10%（报告附前后对照）；</li>
 *   <li>空格逐个 {@value #SPACE_TOKENS}（词单元内的空格已折入词价），竖线逐个
 *       {@value #PIPE_TOKENS}（cl100k 把 " |" 一类并成单块，表行里这个差最明显）；</li>
 *   <li>字母/符号连续段（非空格引导的：行首标记、表格线、URL 片段）⌈长度/
 *       {@value #LATIN_CHARS_PER_TOKEN}⌉；数字段 ⌈长度/{@value #DIGIT_CHARS_PER_TOKEN}⌉
 *       （cl100k 按三位左右切数字）；</li>
 *   <li>换行 1 token。</li>
 * </ul>
 *
 * 权重经真实/仿真文档逐行对 cl100k 拟合（中文六篇：中位比 ≈1.0、块数差 0%；英文留出集
 * 见上）。整篇块数差 ≤15% 由 {@code ChunkAlignmentTests} 验收；再调先动这里。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 校准旋钮：CJK 逐字 token 数（双打块数差超 15% 时先动这里）。 */
    public static final double CJK_TOKENS = 1.2;

    /** 空格逐个 token 数（词单元内的空格已折入词价，这里只剩孤立的）。 */
    public static final double SPACE_TOKENS = 0.85;

    /** 竖线逐个 token 数（表行结构字符）。 */
    public static final double PIPE_TOKENS = 0.25;

    /** 换行逐个 token 数——cl100k 常把 "|\n" 并成一块、"\n\n" 算 1，均值约 0.4。 */
    public static final double NEWLINE_TOKENS = 0.4;

    /** 非词单元的字母/符号段多少字符折 1 token。 */
    public static final double LATIN_CHARS_PER_TOKEN = 3.5;

    /** 数字段多少字符折 1 token（cl100k 约三位一切）。 */
    public static final double DIGIT_CHARS_PER_TOKEN = 3.0;

    /** 英文词单元（含前导空格）不额外计价的部分：短词整体 1 token。 */
    public static final double WORD_FREE_CHARS = 8;

    /** 英文词单元每超这么多字符加 1 token。 */
    public static final double WORD_EXTRA_CHARS_PER_TOKEN = 6;

    /** 这几个区间的字符逐字计（BPE 里一个字一块；0x3000.. 覆盖全角标点「，。」）。 */
    private static boolean perChar(int c) {
        return c >= 0x3000 && c <= 0x30FF || c >= 0x3400 && c <= 0x4DBF
                || c >= 0x4E00 && c <= 0x9FFF || c >= 0xAC00 && c <= 0xD7AF
                || c >= 0xF900 && c <= 0xFAFF;
    }

    public static int tokens(String s) {
        double total = 0;
        int run = 0;
        boolean digitRun = false;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            // 词单元：空格紧跟 ASCII 字母——常见英文词在 cl100k 里连同前导空格是一块，
            // 按「1 + 超长加价」整体计（类注释英文校准节）
            if (ch == ' ' && i + 1 < n && isAsciiLetter(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < n) {
                    char c2 = s.charAt(j);
                    if (c2 == ' ' || c2 == '|' || c2 == '\n' || perChar(c2)) {
                        break;
                    }
                    j++;
                }
                int wordChars = j - i; // 含前导空格
                total += runCost(run, digitRun) + wordCost(wordChars);
                run = 0;
                i = j;
                continue;
            }
            int cp = ch;
            if (Character.isHighSurrogate(ch) && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = Character.toCodePoint(ch, s.charAt(++i));
            }
            if (cp == '\n') {
                total += runCost(run, digitRun) + NEWLINE_TOKENS;
                run = 0;
            } else if (perChar(cp)) {
                total += runCost(run, digitRun) + CJK_TOKENS;
                run = 0;
            } else if (cp == ' ') {
                total += runCost(run, digitRun) + SPACE_TOKENS;
                run = 0;
            } else if (cp == '|') {
                total += runCost(run, digitRun) + PIPE_TOKENS;
                run = 0;
            } else {
                boolean digit = cp >= '0' && cp <= '9';
                if (run > 0 && digit != digitRun) {
                    total += runCost(run, digitRun);
                    run = 0;
                }
                digitRun = digit;
                run++;
            }
            i++;
        }
        return (int) Math.round(total + runCost(run, digitRun));
    }

    /** 词单元定价：短词 1 token；每超 {@link #WORD_FREE_CHARS} 约 {@value #WORD_EXTRA_CHARS_PER_TOKEN} 字符加 1。 */
    private static double wordCost(int charsIncludingSpace) {
        return 1 + Math.max(0, (charsIncludingSpace - WORD_FREE_CHARS) / WORD_EXTRA_CHARS_PER_TOKEN);
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static double runCost(int run, boolean digitRun) {
        if (run == 0) {
            return 0;
        }
        return digitRun ? Math.ceil(run / DIGIT_CHARS_PER_TOKEN)
                : Math.ceil(run / LATIN_CHARS_PER_TOKEN);
    }
}
