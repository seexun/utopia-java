package tech.seexun.jknow.store;

import java.util.List;

/**
 * 名字规整（逐字移植旧仓库 crates/utopia-store/src/resolution.rs:41-51 normalize_name）：
 * 全角空格(U+3000)→半角空格、全角区(U+FF01..=U+FF5E)→半角(偏移 -0xFEE0)、Unicode 空白折叠为单空格。
 * graph entities 搜索的「精确同名集」用它与 SQL 侧 lower() 配合
 * （graph.rs:1588 {@code normalize_name(text).to_lowercase()}）。
 */
public final class Names {

    private Names() {
    }

    public static String normalizeName(String raw) {
        StringBuilder mapped = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (cp == 0x3000) {
                mapped.append(' ');
            } else if (cp >= 0xFF01 && cp <= 0xFF5E) {
                mapped.appendCodePoint(cp - 0xFEE0);
            } else {
                mapped.appendCodePoint(cp);
            }
        });
        // split_whitespace：任意 Unicode 空白作分隔，连续空白并作一个，首尾去掉
        StringBuilder out = new StringBuilder(mapped.length());
        boolean inWhitespace = true;
        for (int i = 0; i < mapped.length(); ) {
            int cp = mapped.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                if (!inWhitespace) {
                    out.append(' ');
                    inWhitespace = true;
                }
            } else {
                out.appendCodePoint(cp);
                inWhitespace = false;
            }
            i += Character.charCount(cp);
        }
        // 末尾若以分隔收尾，去掉那个分隔空格（split+join 语义：分隔符只出现在 token 之间）
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * names.rs:244-247 {@code numbers_differ}：两个名字都带数字、而数字不一样——召回
     * 不把它们配成一对。
     *
     * <p><b>为什么</b>：名字相近、只差一个数的东西几乎从来不是同一个（migration 394 与
     * 395、#440 与 #441、海洋探测器1号 与 2号）。名字向量分不开它们——只差一个数的两个
     * 名字在任何嵌入模型下余弦都在 0.9 以上——包含关系也分不开（migration 39 是
     * migration 395 的子串）。一个工程文档库上这类候选裁决了 1663 对、只合并 3 对，却占
     * 了队列的四成（#1104）。
     *
     * <p><b>只在两边都有数字时才拦</b>：一边没有数字的照常提议——沧海 与
     * 沧海分布式推理平台 2.0 是简称对全称，正是包含关系要接住的那一种。比的是排过序的
     * 数字串，前导零不算（0106 就是 106），全角数字当半角。中文数字不认：一号 的「一」
     * 和 一个 的「一」这里分不出来，宁可照常提议。
     */
    public static boolean numbersDiffer(String a, String b) {
        List<String> da = digitRuns(a);
        List<String> db = digitRuns(b);
        return !da.isEmpty() && !db.isEmpty() && !da.equals(db);
    }

    /** names.rs:249-277 digit_runs：连续数字段（全角当半角），前导零截去（空则记 0），排序。 */
    private static List<String> digitRuns(String name) {
        List<String> runs = new java.util.ArrayList<>();
        boolean open = false;
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            String digit = null;
            if (c >= '0' && c <= '9') {
                digit = String.valueOf((char) c);
            } else if (c >= 0xFF10 && c <= 0xFF19) {
                digit = String.valueOf((char) (c - 0xFEE0));
            }
            if (digit != null) {
                if (open) {
                    runs.set(runs.size() - 1, runs.get(runs.size() - 1) + digit);
                } else {
                    runs.add(digit);
                    open = true;
                }
            } else {
                open = false;
            }
            i += Character.charCount(c);
        }
        for (int i = 0; i < runs.size(); i++) {
            String trimmed = runs.get(i).replaceFirst("^0+", "");
            runs.set(i, trimmed.isEmpty() ? "0" : trimmed);
        }
        java.util.Collections.sort(runs);
        return runs;
    }
}
