package tech.seexun.jknow.ingest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档块（blocks.rs 的对等）：把 Markdown 读成顶层块的序列，每块带着自己在原文里的
 * UTF-8 字节范围（对齐到整行，块文本不带尾随空白）。分块器在这一层之上打包。
 * 被分页符（水平线）劈开的表按窄判据（同宽 + 首格前两词与某体行相同）接回去。
 * 这里是行级近似（Rust 用 pulldown-cmark）：标题/水平线/GFM 表/段落/列表·代码·引用·HTML。
 */
final class Blocks {

    /** [start, end) 字节区间。 */
    record Range(int start, int end) {
        int len() {
            return end - start;
        }
    }

    sealed interface Kind permits Heading, Paragraph, Table, Rule, Other {
    }

    /** # 的个数。 */
    record Heading(int level) implements Kind {
    }

    record Paragraph() implements Kind {
    }

    /** head = 表头行连同分隔行；rows = 体行一行一个范围。 */
    record Table(Range head, List<Range> rows) implements Kind {
    }

    /** 水平线——多半是分页符，不是章节边界。 */
    record Rule() implements Kind {
    }

    /** 列表、代码块、引用、原样 HTML：整块一个单元，不往里看。 */
    record Other() implements Kind {
    }

    record Block(Kind kind, Range range) {
    }

    private final byte[] bytes;

    private Blocks(byte[] bytes) {
        this.bytes = bytes;
    }

    static List<Block> blocks(String text) {
        Blocks b = new Blocks(text.getBytes(StandardCharsets.UTF_8));
        return b.joinContinuations(b.parse());
    }

    private String slice(int start, int end) {
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private String slice(Range r) {
        return slice(r.start(), r.end());
    }

    // ---- 行级解析 ----

    private record Line(int start, int end) {
    }

    private List<Line> lines() {
        List<Line> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i == bytes.length || bytes[i] == '\n') {
                out.add(new Line(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    private static int trimTrailing(byte[] bytes, int end) {
        while (end > 0 && (bytes[end - 1] == ' ' || bytes[end - 1] == '\t')) {
            end--;
        }
        return end;
    }

    private static boolean blank(byte[] bytes, Line line) {
        for (int i = line.start; i < line.end; i++) {
            if (bytes[i] != ' ' && bytes[i] != '\t' && bytes[i] != '\r') {
                return false;
            }
        }
        return true;
    }

    private List<Block> parse() {
        List<Block> out = new ArrayList<>();
        List<Line> ls = lines();
        int i = 0;
        while (i < ls.size()) {
            Line line = ls.get(i);
            if (blank(bytes, line)) {
                i++;
                continue;
            }
            String text = slice(line.start, line.end);
            int level = headingLevel(text);
            if (level > 0) {
                out.add(new Block(new Heading(level), rangeOf(ls, i, i)));
                i++;
                continue;
            }
            if (isRule(text)) {
                out.add(new Block(new Rule(), rangeOf(ls, i, i)));
                i++;
                continue;
            }
            if (text.indexOf('|') >= 0 && i + 1 < ls.size()
                    && isDelimiterRow(slice(ls.get(i + 1).start, ls.get(i + 1).end))) {
                int headEnd = rangeOf(ls, i + 1, i + 1).end();
                List<Range> rows = new ArrayList<>();
                int j = i + 2;
                while (j < ls.size() && !blank(bytes, ls.get(j))
                        && slice(ls.get(j).start, ls.get(j).end).indexOf('|') >= 0) {
                    rows.add(rangeOf(ls, j, j));
                    j++;
                }
                out.add(new Block(new Table(new Range(rangeOf(ls, i, i).start(), headEnd), rows),
                        new Range(rangeOf(ls, i, i).start(),
                                j > i + 1 ? rangeOf(ls, j - 1, j - 1).end() : headEnd)));
                i = j;
                continue;
            }
            if (isOtherStart(text)) {
                int j = fenced(text) ? fenceEnd(ls, i) : blockEnd(ls, i);
                out.add(new Block(new Other(), rangeOf(ls, i, Math.max(i, j - 1))));
                i = j;
                continue;
            }
            // 段落：连着的普通行
            int j = i;
            while (j < ls.size() && !blank(bytes, ls.get(j))) {
                String t = slice(ls.get(j).start, ls.get(j).end);
                if (j > i && (headingLevel(t) > 0 || isRule(t) || isOtherStart(t)
                        || t.indexOf('|') >= 0 && j + 1 < ls.size() && isDelimiterRow(
                                slice(ls.get(j + 1).start, ls.get(j + 1).end)))) {
                    break;
                }
                j++;
            }
            out.add(new Block(new Paragraph(), rangeOf(ls, i, Math.max(i, j - 1))));
            i = j;
        }
        return out;
    }

    private Range rangeOf(List<Line> ls, int from, int to) {
        int start = ls.get(from).start();
        int end = trimTrailing(bytes, ls.get(to).end());
        return new Range(start, end);
    }

    private int fenceEnd(List<Line> ls, int from) {
        String fence = slice(ls.get(from).start, ls.get(from).end).strip();
        String marker = fence.startsWith("~~~") ? "~~~" : "```";
        for (int j = from + 1; j < ls.size(); j++) {
            String t = slice(ls.get(j).start, ls.get(j).end).strip();
            if (t.startsWith(marker)) {
                return j + 1;
            }
        }
        return ls.size();
    }

    private int blockEnd(List<Line> ls, int from) {
        int j = from + 1;
        while (j < ls.size() && !blank(bytes, ls.get(j))) {
            j++;
        }
        return j;
    }

    static int headingLevel(String line) {
        String s = line.stripLeading();
        int n = 0;
        while (n < s.length() && s.charAt(n) == '#') {
            n++;
        }
        if (n >= 1 && n <= 6 && (n == s.length() || s.charAt(n) == ' ' || s.charAt(n) == '\t')) {
            return n;
        }
        return 0;
    }

    /** 水平线：{-,_,*} 重复 ≥3、可夹空白（* * * / --- / ___）。 */
    static boolean isRule(String line) {
        String s = line.strip();
        if (s.length() < 3) {
            return false;
        }
        char c = s.charAt(0);
        if (c != '-' && c != '_' && c != '*') {
            return false;
        }
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == c) {
                count++;
            } else if (ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return count >= 3;
    }

    /** GFM 分隔行：只含 |、:、-、空白，至少一个 -。 */
    static boolean isDelimiterRow(String line) {
        String s = line.strip();
        if (s.isEmpty()) {
            return false;
        }
        int dashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-') {
                dashes++;
            } else if (ch != '|' && ch != ':' && ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return dashes >= 1 && s.indexOf('|') >= 0;
    }

    private static boolean fenced(String line) {
        String s = line.stripLeading();
        return s.startsWith("```") || s.startsWith("~~~");
    }

    private static boolean isOtherStart(String line) {
        String s = line.stripLeading();
        if (s.isEmpty()) {
            return false;
        }
        char c0 = s.charAt(0);
        if (fenced(s)) {
            return true; // 代码围栏
        }
        if (c0 == '>') {
            return true; // 引用
        }
        if (c0 == '-' || c0 == '+' || c0 == '*') {
            return s.length() > 1 && (s.charAt(1) == ' ' || s.charAt(1) == '\t'); // 列表项
        }
        if (Character.isDigit(c0)) {
            int i = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            return i <= 9 && i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == ')')
                    && i + 1 < s.length() && (s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t');
        }
        if (c0 == '<' && s.length() > 1 && Character.isLetter(s.charAt(1))) {
            return true; // 原样 HTML
        }
        return false;
    }

    // ---- 被分页符劈开的表接回去（blocks.rs join_continuations 窄判据） ----

    private List<Block> joinContinuations(List<Block> blocks) {
        List<Block> out = new ArrayList<>(blocks.size());
        int i = 0;
        while (i < blocks.size()) {
            Block b = blocks.get(i);
            boolean joined = false;
            if (b.kind() instanceof Rule && !out.isEmpty()
                    && out.get(out.size() - 1).kind() instanceof Table
                    && i + 1 < blocks.size()
                    && blocks.get(i + 1).kind() instanceof Table
                    && continues(out.get(out.size() - 1), blocks.get(i + 1))) {
                Block prev = out.remove(out.size() - 1);
                Block nextBlock = blocks.get(i + 1);
                Table prevTable = (Table) prev.kind();
                Table nextTable = (Table) nextBlock.kind();
                List<Range> rows = new ArrayList<>(prevTable.rows());
                rows.add(firstLine(nextTable.head()));
                rows.addAll(nextTable.rows());
                int end = Math.max(prev.range().end(), blocks.get(i + 1).range().end());
                out.add(new Block(new Table(prevTable.head(), rows),
                        new Range(prev.range().start(), end)));
                i += 2;
                joined = true;
            }
            if (!joined) {
                out.add(b);
                i++;
            }
        }
        return out;
    }

    private boolean continues(Block prev, Block next) {
        if (!(prev.kind() instanceof Table t)) {
            return false;
        }
        if (cellCount(slice(firstLine(t.head()))) != cellCount(slice(firstLine(
                ((Table) next.kind()).head())))) {
            return false;
        }
        List<String> nextLead = lead(firstLine(((Table) next.kind()).head()));
        if (nextLead.size() != 2) {
            return false;
        }
        for (Range row : t.rows()) {
            if (lead(row).equals(nextLead)) {
                return true;
            }
        }
        return false;
    }

    private Range firstLine(Range r) {
        String s = slice(r.start(), r.end());
        int nl = s.indexOf('\n');
        return nl < 0 ? r : new Range(r.start(), r.start() + nl);
    }

    /** 行首格的前两个词（小写，first_cell 逐字）——判据窄：只看行首标签不看数。 */
    private List<String> lead(Range r) {
        String cell = slice(r.start(), r.end()).strip();
        while (cell.startsWith("|")) {
            cell = cell.substring(1);
        }
        int bar = cell.indexOf('|');
        if (bar >= 0) {
            cell = cell.substring(0, bar);
        }
        String[] words = cell.strip().toLowerCase(Locale.ROOT).split("\\s+");
        List<String> out = new ArrayList<>();
        for (String w : words) {
            if (!w.isEmpty()) {
                out.add(w);
                if (out.size() == 2) {
                    break;
                }
            }
        }
        return out;
    }

    static int cellCount(String line) {
        String t = line.strip();
        if (t.startsWith("|") && t.endsWith("|") && t.length() > 1) {
            return t.substring(1, t.length() - 1).split("\\|", -1).length;
        }
        return 0;
    }
}
