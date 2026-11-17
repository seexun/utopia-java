package tech.seexun.jknow.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * html（html.rs 的 Jsoup 简化对等——「readability 简化：正文提取 + 去 interstitial」）：
 *
 * <ul>
 *   <li>正文提取：article/[itemprop=articleBody]/main/[role=main]/#content 里第一个像
 *       正文的（Readability 的简化形态）；提出来的正文太短（&lt;200 非空白字符）就整页裸转
 *       ——登录页的控件标签不算正文证据；</li>
 *   <li>interstitial 拒收：表单+口令字段/播放器壳，或提取文本是登录/订阅壳而没有成段文字
 *       （looks_like_challenge_shell 的高置信标记 + 结构标记逐字搬运）→
 *       Unreadable("HTML is an authentication or player interstitial")；</li>
 *   <li>表 → Markdown 表；链接剥危险目标（javascript:/data: 只留锚文本）。</li>
 * </ul>
 */
public final class HtmlText {

    /** 提出的正文短于这么多非空白字符时，当 Readability 失败走整页裸转（html.rs:159-163）。 */
    private static final int ARTICLE_MIN_CHARS = 200;

    public static final String INTERSTITIAL = "HTML is an authentication or player interstitial";

    private static final Set<String> DROP = Set.of("script", "style", "iframe", "object", "embed",
            "img", "svg", "math", "noscript", "nav", "aside", "footer", "header", "form", "button",
            "input", "select", "textarea", "label");

    private HtmlText() {
    }

    /** @throws IOException interstitial（Rust HtmlError::Interstitial；空正文走 parse 的空=Unreadable 路） */
    public static String parse(byte[] bytes) throws IOException {
        String html = TextCodecs.plainText(bytes);
        Document doc = Jsoup.parse(html);
        // 表先于转换渲染成 Markdown（table.rs lift_tables 的同位实现）：结构复杂的
        // 财报表（colspan 标题行/两行列头/$ 占位格/小节缩进）交给 TableGrids；渲染不出
        // （单列排版表/嵌套表）的留在 DOM 里按普通元素走。分页符把一张报表劈成两个
        // <table> 时，后半张接着用前一张的列头。
        Map<Element, String> lifted = liftTables(doc);
        Element root = article(doc);
        String markdown = toMarkdown(root, lifted);
        if (looksLikeChallengeShell(markdown) || (nonWs(markdown) < ARTICLE_MIN_CHARS
                && looksLikeChallengeHtml(doc.body() != null ? doc.body() : doc))) {
            throw new IOException(INTERSTITIAL);
        }
        String title = doc.title() == null ? "" : doc.title().strip();
        if (!title.isEmpty() && !markdown.contains(title)) {
            markdown = "# " + title + "\n\n" + markdown;
        }
        return postProcess(markdown);
    }

    /**
     * 预扫最外层且不含内层表的表（table.rs:62-91 lift_tables）：渲染好的表挂在
     * {@code Element -> Markdown} 映射上；walk 遇到映射里的表直接吐 Markdown。
     * 上一张表的列头传给下一张——续表（同列数、无表头）不丢列头。
     */
    private static Map<Element, String> liftTables(Document doc) {
        Map<Element, String> lifted = new LinkedHashMap<>();
        List<String> lastHeaders = new ArrayList<>();
        for (Element tbl : doc.select("table")) {
            boolean nested = tbl.parents().stream()
                    .anyMatch(p -> p.tagName().equalsIgnoreCase("table"));
            // jsoup 的 Element.select 会把元素自己也算进结果——判内层表要排除自己
            boolean hasInner = tbl.select("table").stream().anyMatch(e -> e != tbl);
            if (nested || hasInner) {
                continue;
            }
            TableGrids.RenderedTable rendered = TableGrids.renderTable(tbl, lastHeaders);
            if (rendered != null) {
                lifted.put(tbl, rendered.markdown());
                lastHeaders = rendered.headers();
            }
        }
        return lifted;
    }

    /**
     * 片段 → Markdown（html.rs:183-185 fragment_to_markdown 的对等）：feed 内嵌正文
     * （content:encoded）走这条路——不做页面级正文提取（片段不是页面）、不提标题，
     * 防活动内容/图片的规则与整页同源（DROP 集合 + 链接剥危险目标）。
     */
    public static String fragment(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body() != null ? doc.body() : doc;
        return postProcess(toMarkdown(body, Map.of()));
    }

    // ---- 正文提取（readability 简化） ----

    private static Element article(Document doc) {
        for (String selector : new String[] {"article", "[itemprop=articleBody]", "main",
                "[role=main]", "#content", ".post-content", ".article-content", ".entry-content"}) {
            Elements found = doc.select(selector);
            for (Element e : found) {
                if (nonWs(e.text()) >= ARTICLE_MIN_CHARS) {
                    return e;
                }
            }
        }
        return doc.body() != null ? doc.body() : doc;
    }

    // ---- DOM → Markdown ----

    private static String toMarkdown(Element root, Map<Element, String> lifted) {
        StringBuilder out = new StringBuilder();
        walk(root, out, lifted);
        return out.toString();
    }

    private static void walk(Element element, StringBuilder out, Map<Element, String> lifted) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (DROP.contains(tag)) {
            return;
        }
        switch (tag) {
            case "h1" -> block(out, "# " + inlineText(element));
            case "h2" -> block(out, "## " + inlineText(element));
            case "h3" -> block(out, "### " + inlineText(element));
            case "h4", "h5", "h6" -> block(out, "#### " + inlineText(element));
            case "p" -> block(out, inlineText(element));
            case "pre" -> block(out, element.wholeText().stripTrailing());
            case "blockquote" -> block(out, inlineText(element));
            case "li" -> block(out, "- " + inlineText(element));
            case "br" -> out.append('\n');
            case "table" -> {
                // lift_tables 预渲染的表直接吐（财报网格对等）；没渲染的（单列排版表/
                // 嵌套表）留给页面转换器——标题、列表和链接才留得住
                String liftedMd = lifted.get(element);
                if (liftedMd != null) {
                    block(out, liftedMd.stripTrailing());
                    return;
                }
                for (Element child : element.children()) {
                    walk(child, out, lifted);
                }
            }
            default -> {
                // 容器：只有文本没有块级子的时候按一段收，否则递归
                boolean hasBlockChild = element.children().stream()
                        .anyMatch(c -> !isInline(c.tagName()));
                if (!hasBlockChild && !element.ownText().isBlank()
                        && element.childNodeSize() == element.textNodes().size()) {
                    block(out, inlineText(element));
                } else {
                    for (var child : element.children()) {
                        walk(child, out, lifted);
                    }
                    if (!element.ownText().isBlank() && element.children().isEmpty()) {
                        block(out, element.ownText().strip());
                    }
                }
            }
        }
    }

    private static boolean isInline(String tag) {
        return switch (tag) {
            case "a", "b", "i", "em", "strong", "span", "code", "small", "sub", "sup", "u", "s",
                    "abbr", "cite", "mark" -> true;
            default -> false;
        };
    }

    /** 行内文本：链接剥危险目标只留锚文本，空白折一。 */
    private static String inlineText(Element element) {
        StringBuilder sb = new StringBuilder();
        collectInline(element, sb);
        return MarkdownTables.clean(sb.toString());
    }

    private static void collectInline(Element element, StringBuilder sb) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (DROP.contains(tag)) {
            return;
        }
        if (tag.equals("a")) {
            String dest = element.attr("abs:href").isEmpty()
                    ? element.attr("href") : element.attr("abs:href");
            String text = MarkdownTables.clean(element.text());
            sb.append(dest.isBlank() || unsafeDestination(dest) ? text
                    : "[" + text + "](" + dest.trim() + ")");
            return;
        }
        if (tag.equals("br")) {
            sb.append(' ');
            return;
        }
        sb.append(element.ownText());
        for (Element child : element.children()) {
            collectInline(child, sb);
            if (isInline(child.tagName()) && !child.ownText().isBlank()) {
                sb.append(' ');
            }
        }
    }

    private static boolean unsafeDestination(String destination) {
        String d = destination.trim();
        if (d.startsWith("<") && d.endsWith(">")) {
            d = d.substring(1, d.length() - 1).trim();
        }
        int colon = d.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String scheme = d.substring(0, colon).toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "javascript", "vbscript", "data", "file", "blob" -> true;
            default -> false;
        };
    }

    private static void block(StringBuilder out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        out.append(text.strip()).append("\n\n");
    }

    // ---- interstitial 判定（html.rs:187-320 逐字搬运） ----

    private static boolean looksLikeChallengeHtml(Element root) {
        String lowered = root.html().toLowerCase(Locale.ROOT);
        boolean authForm = lowered.contains("<form") && (lowered.contains("password")
                || lowered.contains("type=\"email\"") || lowered.contains("type='email'"));
        String[] playerShell = {"jwplayer", "brightcove", "data-player", "<video",
            "youtube.com/embed", "player.vimeo.com"};
        int hits = 0;
        for (String marker : playerShell) {
            if (lowered.contains(marker)) {
                hits++;
            }
        }
        return authForm || hits >= 2;
    }

    private static final String[] HIGH_CONFIDENCE_MARKERS = {"verify you are human",
        "checking your browser", "enable javascript and cookies", "accept cookies to continue",
        "subscribe to continue", "subscribe to read", "sign in to continue", "log in to continue",
        "this content is behind a paywall", "content is behind a paywall", "unlock this article",
        "captcha challenge", "complete the captcha", "solve the captcha", "enter the captcha",
        "captcha verification"};

    private static final String[] STRUCTURAL_MARKERS = {"sign in", "log in", "login",
        "create account", "register", "cookie settings", "consent preferences", "accept all cookies",
        "enable javascript", "access denied", "play video", "watch now"};

    /** 登录/订阅壳：标记命中且没有成段的正文（looks_like_challenge_shell）。 */
    public static boolean looksLikeChallengeShell(String markdown) {
        String lowered = markdown.toLowerCase(Locale.ROOT);
        boolean high = false;
        for (String marker : HIGH_CONFIDENCE_MARKERS) {
            if (lowered.contains(marker)) {
                high = true;
                break;
            }
        }
        String[] lines = lowered.split("\n", -1);
        if (!high) {
            for (int i = 0; i < Math.min(3, lines.length); i++) {
                String line = lines[i].strip().replaceFirst("^#+\\s*", "").strip();
                if (line.equals("access denied")) {
                    high = true;
                    break;
                }
            }
        }
        if (high) {
            return !hasSubstantiveProse(markdown);
        }
        int structuralHits = 0;
        int looked = 0;
        for (String raw : lines) {
            if (looked >= 40) {
                break;
            }
            looked++;
            String line = raw.strip().replaceFirst("^#+\\s*", "").strip();
            if (line.length() > 96) {
                continue;
            }
            for (String marker : STRUCTURAL_MARKERS) {
                if (line.contains(marker)) {
                    structuralHits++;
                    break;
                }
            }
        }
        boolean hasAuthFields = lowered.contains("password") && (lowered.contains("email")
                || lowered.contains("username")) || lowered.contains("<form");
        return (structuralHits >= 2 || structuralHits >= 1 && hasAuthFields)
                && !hasSubstantiveProse(markdown);
    }

    /** 有没有成段、有变化的正文：≥3 句、≥400 非空白字符、≥40 个不同的词或 CJK 字。 */
    static boolean hasSubstantiveProse(String markdown) {
        StringBuilder prose = new StringBuilder();
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("#") || line.contains("](")) {
                continue;
            }
            prose.append(line).append(' ');
        }
        String p = prose.toString();
        int sentences = 0;
        for (int i = 0; i < p.length(); i++) {
            if (".!?。！？".indexOf(p.charAt(i)) >= 0) {
                sentences++;
            }
        }
        if (sentences < 3 || nonWs(p) < 400) {
            return false;
        }
        Set<String> words = new HashSet<>();
        StringBuilder word = new StringBuilder();
        Set<Integer> cjk = new HashSet<>();
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (isCjk(ch)) {
                cjk.add((int) ch);
                if (word.length() >= 3) {
                    words.add(word.toString().toLowerCase(Locale.ROOT));
                }
                word.setLength(0);
            } else if (Character.isLetter(ch)) {
                word.append(ch);
            } else {
                if (word.length() >= 3) {
                    words.add(word.toString().toLowerCase(Locale.ROOT));
                }
                word.setLength(0);
            }
        }
        if (word.length() >= 3) {
            words.add(word.toString().toLowerCase(Locale.ROOT));
        }
        return words.size() >= 40 || cjk.size() >= 40;
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x3400 && ch <= 0x4DBF || ch >= 0x4E00 && ch <= 0x9FFF
                || ch >= 0x3040 && ch <= 0x30FF || ch >= 0xAC00 && ch <= 0xD7AF;
    }

    private static int nonWs(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /** 压 3+ 连续换行为 3、去首尾（html.rs post_process）。 */
    private static String postProcess(String markdown) {
        StringBuilder sb = new StringBuilder(markdown.length());
        int newlines = 0;
        for (int i = 0; i < markdown.length(); i++) {
            char ch = markdown.charAt(i);
            if (ch == '\n') {
                newlines++;
                if (newlines <= 3) {
                    sb.append(ch);
                }
            } else {
                newlines = 0;
                sb.append(ch);
            }
        }
        return sb.toString().strip();
    }
}
