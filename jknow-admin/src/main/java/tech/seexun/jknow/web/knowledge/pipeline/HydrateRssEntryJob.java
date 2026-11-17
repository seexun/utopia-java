package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.jobs.TerminalFailure;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.ingest.BlobStore;
import tech.seexun.jknow.store.RssFullContentService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.RssEntries;
import tech.seexun.jknow.store.entity.SourceRow;
import tech.seexun.jknow.store.entity.Job;

/**
 * hydrate_rss_entry 任务真身（rss_full_content.rs:127-298 hydrate_entry/hydrate_content/
 * quality_check + FetchConfig 全量对等，M3 任务 9——去 M2 的 NOOP 桩）。
 *
 * <p>任务载荷只带 UUID；所有有界的 feed 数据都来自台账行——迟到的重放不依赖 feed
 * 还挂着那条。抓取走 {@link GuardedHttp} 的 <b>Content 档</b>：文章地址是 feed 说的，
 * 不是人选的，只准公网（SSRF 边界，见 GuardedHttp 类注）。
 *
 * <p>失败面（ContentError 的 code/is_retryable 逐字）：可重试的网络类按通用队列的
 * 30s×attempts² 退避；不可重试的（质量门/挑战壳/视频壳）第一次就 Terminal——
 * 重试不会让它变好；attempt 达到 {@link RssFullContentService#RSS_HYDRATION_ATTEMPTS}
 * 也 Terminal（预算用尽）。
 */
@Component
public class HydrateRssEntryJob {

    private static final Logger log = LoggerFactory.getLogger(HydrateRssEntryJob.class);

    /**
     * rss_full_content.rs:80-101 FetchConfig::default 逐字：8MiB 响应 / 5 跳重定向 /
     * DNS 5s / 连接 10s / 读 15s / 总 30s。
     */
    static final GuardedHttp.Limits FETCH_CONFIG = new GuardedHttp.Limits(
            8L * 1024 * 1024, 5, Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(15), Duration.ofSeconds(30));

    /** rss_full_content.rs:114 MAX_MARKDOWN_BYTES。 */
    static final int MAX_MARKDOWN_BYTES = 2 * 1024 * 1024;

    private final RssFullContentService rss;
    private final SourcesService sources;
    private final BlobStore blobs;
    private final AppEventBus events;

    public HydrateRssEntryJob(RssFullContentService rss, SourcesService sources,
                              BlobStore blobs, AppEventBus events) {
        this.rss = rss;
        this.sources = sources;
        this.blobs = blobs;
        this.events = events;
    }

    /** dispatcher 入口：payload.source_id + payload.rss_entry_id。 */
    public void handle(Job job) throws Exception {
        UUID sourceId = UUID.fromString(job.payload().path("source_id").asText());
        UUID entryId = UUID.fromString(job.payload().path("rss_entry_id").asText());
        hydrateEntry(job.id(), sourceId, entryId);
    }

    /** rss_full_content.rs:127-210 hydrate_entry 逐字控制流。 */
    public void hydrateEntry(long hydrationJobId, UUID sourceId, UUID entryId) throws Exception {
        RssEntries.Entry entry = rss.getEntry(entryId);
        if (entry == null) {
            throw new IllegalStateException("RSS hydration entry was deleted");
        }
        if (!entry.sourceId().equals(sourceId)) {
            throw new TerminalFailure("source_mismatch", null);
        }
        SourceRow source = sources.get(sourceId);
        Integer attempt = rss.currentAttempt(entryId, hydrationJobId);
        if (attempt == null) {
            return; // 闸外（源编辑/迟到）：安静收 done，不写任何东西
        }
        events.emitSource(source.kbId());

        AcceptedContent accepted;
        try {
            accepted = hydrateContent(entry);
        } catch (ContentError e) {
            handleHydrationFailure(entryId, hydrationJobId, attempt, e);
            return;
        }
        String digest = sha256Hex(accepted.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            blobs.put(digest, accepted.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) {
            handleHydrationFailure(entryId, hydrationJobId, attempt,
                    new ContentError(Kind.INGEST_FAILED, error.toString()));
            return;
        }
        java.util.UUID documentId;
        try {
            documentId = rss.completeHydration(entryId, hydrationJobId, source.id(),
                    entry.activationGeneration(), source.kbId(), entry.externalKey(),
                    hydrationFilename(entry.title()) + ".md", "text/markdown",
                    accepted.markdown().length(), digest, entry.docTime())
                    .orElse(null);
        } catch (RuntimeException error) {
            handleHydrationFailure(entryId, hydrationJobId, attempt,
                    new ContentError(Kind.INGEST_FAILED, error.toString()));
            return;
        }
        if (documentId != null) {
            events.emitDocument(source.kbId(), documentId);
        }
        events.emitSource(source.kbId());
    }

    /**
     * rss_full_content.rs:212-233 hydrate_content：feed 内嵌正文先用（过质量门才算），
     * 没有可用的就抓文章链接；text/markdown 直接收，页面走正文提取 + 链接页质量门。
     */
    AcceptedContent hydrateContent(RssEntries.Entry entry) throws ContentError {
        if (entry.embeddedHtml() != null) {
            String markdown = normalizeFeedHtml(entry.embeddedHtml());
            if (markdown != null && passesQuality(markdown, entry.summary(), false)) {
                return canonicalContent(markdown, "feed", null);
            }
        }
        if (entry.articleUrl() == null) {
            throw new ContentError(Kind.NO_USABLE_SOURCE, "article has no usable content source");
        }
        FetchedPage page = fetchArticle(entry.articleUrl());
        String markdown;
        if ("text/markdown".equals(page.contentType())) {
            markdown = normalizeMarkdown(page.body());
        } else {
            markdown = extractArticleMarkdown(page.body());
        }
        return acceptLinkedMarkdown(markdown, entry.summary(), page.finalUrl());
    }

    /** quality_check 的布尔面（Rust .is_ok()）：门没过就落到下一路，不是抛出去。 */
    private static boolean passesQuality(String markdown, String summary, boolean linkedPage) {
        try {
            qualityCheck(markdown, summary, linkedPage);
            return true;
        } catch (ContentError e) {
            return false;
        }
    }

    /** rss_full_content.rs:235-251 canonical_content。 */
    private static AcceptedContent canonicalContent(String markdown, String source,
                                                    String finalUrl) throws ContentError {
        if (markdown.isEmpty()) {
            throw new ContentError(Kind.NON_SUBSTANTIVE, "article content is not substantive");
        }
        if (markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_MARKDOWN_BYTES) {
            throw new ContentError(Kind.MARKDOWN_TOO_LARGE, "canonical Markdown is too large");
        }
        return new AcceptedContent(markdown, source, finalUrl);
    }

    /** rss_full_content.rs:253-260 accept_linked_markdown：链接页要过更严的质量门。 */
    private static AcceptedContent acceptLinkedMarkdown(String markdown, String summary,
                                                        String finalUrl) throws ContentError {
        qualityCheck(markdown, summary, true);
        return canonicalContent(markdown, "web", finalUrl);
    }

    /**
     * rss_full_content.rs:262-284 handle_hydration_failure：不可重试或预算用尽 →
     * Terminal（第一次就 failed，运维看见）；否则交给通用队列退避。
     */
    private void handleHydrationFailure(UUID entryId, long hydrationJobId, int attempt,
                                        ContentError error) throws TerminalFailure {
        boolean terminal = !error.kind.retryable
                || attempt >= RssFullContentService.RSS_HYDRATION_ATTEMPTS;
        if (rss.currentAttempt(entryId, hydrationJobId) == null) {
            return; // 失败处理期间闸已关：安静收 done
        }
        if (terminal) {
            throw new TerminalFailure(error.kind.wire, error);
        }
        throw new IllegalArgumentException(error.kind.wire + ": " + error.getMessage());
    }

    /**
     * rss_full_content.rs:286-298 hydration_filename：非字母数字折 '-'，take(80) 在折号
     * 之后——80 是 <b>码点</b>（M-2：代理对折成两个 '-' 会把 40 个增补字符的标题写满）。
     */
    static String hydrationFilename(String title) {
        StringBuilder filename = new StringBuilder();
        java.util.PrimitiveIterator.OfInt cps = title.codePoints().iterator();
        while (cps.hasNext() && filename.codePointCount(0, filename.length()) < 80) {
            int cp = cps.nextInt();
            filename.appendCodePoint(Character.isLetterOrDigit(cp) ? cp : '-');
        }
        String trimmed = filename.toString();
        while (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "untitled" : trimmed;
    }

    // ---- 抓取与转换（rss_full_content.rs:307-391） ----

    /** rss_full_content.rs:103-108 FetchedPage。 */
    record FetchedPage(String finalUrl, String contentType, String body) {
    }

    /** rss_full_content.rs:110-112 fetch_article：Content 档抓文章。 */
    static FetchedPage fetchArticle(String rawUrl) throws ContentError {
        return fetchArticleWithConfig(rawUrl, FETCH_CONFIG);
    }

    static FetchedPage fetchArticleWithConfig(String rawUrl, GuardedHttp.Limits config)
            throws ContentError {
        GuardedHttp.Fetched fetched;
        try {
            fetched = GuardedHttp.get(rawUrl, GuardedHttp.Reach.CONTENT, config);
        } catch (GuardedHttp.FetchException e) {
            throw fromFetchError(e);
        } catch (java.io.IOException e) {
            throw new ContentError(Kind.NETWORK, e.getMessage());
        }
        return readPageResponse(fetched);
    }

    /** rss_full_content.rs:328-348 read_page_response：mime 门 + 压缩门 + 共享解码。 */
    static FetchedPage readPageResponse(GuardedHttp.Fetched page) throws ContentError {
        if (!"text/html".equals(page.mime()) && !"application/xhtml+xml".equals(page.mime())
                && !"text/markdown".equals(page.mime())) {
            throw new ContentError(Kind.NOT_HTML, "article response is not HTML");
        }
        String encoding = page.contentEncoding();
        if (encoding != null && !encoding.isBlank()
                && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw new ContentError(Kind.UNSUPPORTED_ENCODING,
                    "article response encoding is unsupported");
        }
        return new FetchedPage(page.finalUrl().toString(), page.mime(),
                tech.seexun.jknow.ingest.TextCodecs.plainText(page.bytes()));
    }

    /** rss_full_content.rs:350-366 From&lt;FetchError&gt; 的映射逐字。 */
    static ContentError fromFetchError(GuardedHttp.FetchException e) {
        return switch (e.kind) {
            case INVALID_URL, UNSUPPORTED_SCHEME -> new ContentError(Kind.INVALID_URL, e.getMessage());
            case BLOCKED_ADDRESS -> new ContentError(Kind.BLOCKED_ADDRESS, e.getMessage());
            case DNS -> new ContentError(Kind.DNS_RESOLUTION, e.getMessage());
            case BLOCKED_REDIRECT -> new ContentError(Kind.BLOCKED_REDIRECT, e.getMessage());
            case REDIRECT_LIMIT -> new ContentError(Kind.REDIRECT_LIMIT, e.getMessage());
            case TOO_LARGE -> new ContentError(Kind.TOO_LARGE, e.getMessage());
            case STATUS -> new ContentError(
                    retryableStatus(e.status) ? Kind.HTTP_RETRYABLE : Kind.HTTP_DENIED,
                    "HTTP " + e.status);
            case TRANSPORT -> e.isTimeout() ? new ContentError(Kind.TIMEOUT, e.getMessage())
                    : new ContentError(Kind.NETWORK, e.getMessage());
        };
    }

    /** rss_full_content.rs:360。 */
    static boolean retryableStatus(int status) {
        return status == 401 || status == 403 || status == 408 || status == 425 || status == 429
                || (status >= 500 && status <= 599);
    }

    /** rss_full_content.rs:377-379 normalize_feed_html（Interstial/Conversion 失败语义同族）。 */
    static String normalizeFeedHtml(String raw) {
        return tech.seexun.jknow.ingest.HtmlText.fragment(raw);
    }

    /** rss_full_content.rs:381-383 extract_article_markdown。 */
    static String extractArticleMarkdown(String raw) throws ContentError {
        try {
            return tech.seexun.jknow.ingest.HtmlText.parse(
                    raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (tech.seexun.jknow.ingest.HtmlText.INTERSTITIAL.equals(e.getMessage())) {
                throw new ContentError(Kind.CHALLENGE_PAGE,
                        "article page is a challenge or consent shell");
            }
            throw new ContentError(Kind.CONVERSION, e.getMessage());
        }
    }

    /**
     * rss_full_content.rs:385-391 normalize_markdown：直收的 markdown 归一 + 空判 +
     * <b>链接消毒</b>（M-3 收口：Rust 是 sanitize_markdown_links(post_process(x))——
     * {@code %6Aavascript:} 这类百分号编码的危险目标必须在落文档前剥掉锚文本之外的
     * 目的地；html.rs:537-605 的对等实现）。
     */
    static String normalizeMarkdown(String markdown) throws ContentError {
        String normalized = markdown.strip();
        if (normalized.isEmpty()) {
            throw new ContentError(Kind.NON_SUBSTANTIVE, "article content is not substantive");
        }
        return sanitizeMarkdownLinks(normalized);
    }

    /**
     * 链接消毒（html.rs:537-569 sanitize_markdown_links）：不安全目的地只留锚文本。
     * 图片（! 开头的 [）不算链接；找不到 ]( 或未闭合 ) 的照原样。
     */
    static String sanitizeMarkdownLinks(String markdown) {
        StringBuilder result = new StringBuilder(markdown.length());
        int cursor = 0;
        while (cursor < markdown.length()) {
            int start = markdown.indexOf('[', cursor);
            if (start < 0) {
                result.append(markdown, cursor, markdown.length());
                break;
            }
            result.append(markdown, cursor, start);
            if (start > 0 && markdown.charAt(start - 1) == '!') {
                result.append('[');
                cursor = start + 1;
                continue;
            }
            int closeLabel = markdown.indexOf("](", start + 1);
            if (closeLabel < 0) {
                result.append('[');
                cursor = start + 1;
                continue;
            }
            int closeLink = findUnescaped(markdown, closeLabel + 2, ')');
            if (closeLink < 0) {
                result.append('[');
                cursor = start + 1;
                continue;
            }
            String destination = markdown.substring(closeLabel + 2, closeLink);
            if (!isUnsafeMarkdownDestination(destination)) {
                result.append(markdown, start, closeLink + 1);
            } else {
                result.append(markdown, start + 1, closeLabel);
            }
            cursor = closeLink + 1;
        }
        return result.toString();
    }

    private static int findUnescaped(String text, int start, char wanted) {
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == wanted) {
                return i;
            }
        }
        return -1;
    }

    /** html.rs:589-605：剥 <>、百分号解码、去控制/空白前缀后按 scheme 判。 */
    static boolean isUnsafeMarkdownDestination(String destination) {
        String d = destination.trim();
        while (d.startsWith("<")) {
            d = d.substring(1);
        }
        while (d.endsWith(">")) {
            d = d.substring(0, d.length() - 1);
        }
        d = d.trim();
        String decoded = java.net.URLDecoder.decode(d, java.nio.charset.StandardCharsets.UTF_8);
        int i = 0;
        while (i < decoded.length() && (decoded.charAt(i) <= ' ')) {
            i++;
        }
        decoded = decoded.substring(i);
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        return switch (decoded.substring(0, colon).toLowerCase(Locale.ROOT)) {
            case "javascript", "vbscript", "data", "file", "blob" -> true;
            default -> false;
        };
    }

    // ---- 质量门（rss_full_content.rs:393-458 逐字） ----

    /**
     * 质量门（rss_full_content.rs:393-413 quality_check）：内容够不够格、是不是挑战壳、
     * 是不是视频壳。三条都过才算「拿到正文」。
     */
    static void qualityCheck(String markdown, String summary, boolean linkedPage)
            throws ContentError {
        if (!isSubstantive(markdown, summary, linkedPage)) {
            throw new ContentError(Kind.NON_SUBSTANTIVE, "article content is not substantive");
        }
        String lowered = markdown.toLowerCase(Locale.ROOT);
        if (looksLikeChallengeShell(lowered)) {
            throw new ContentError(Kind.CHALLENGE_PAGE,
                    "article page is a challenge or consent shell");
        }
        int linkCount = countOf(markdown, "](");
        if (lowered.contains("video player") || lowered.contains("watch the video")
                || (linkCount > 20 && wordsOf(markdown) < linkCount * 8)) {
            throw new ContentError(Kind.VIDEO_REQUIRED,
                    "article page requires unsupported video or player content");
        }
    }

    /**
     * 够不够格算正文（rss_full_content.rs:415-458 is_substantive 逐字）：
     * CJK 散文没有词分隔——每个表意字符算一个词法单位，不降低英文门槛；反复的一把
     * 导航标签不是一篇文章的证据。链接页门槛更高（200 词/1200 字符 vs 80/500）；
     * 与摘要全同或只是摘要加一点尾巴的也不算（那是壳）。
     */
    static boolean isSubstantive(String markdown, String summary, boolean linkedPage) {
        int nonWhitespace = 0;
        for (int cp : markdown.codePoints().toArray()) {
            if (!Character.isWhitespace(cp)) {
                nonWhitespace++;
            }
        }
        // M-2 收口：按 codePoint 数——char 上限 0xFFFF，CJK 扩展 B（U+20000+）在 char
        // 循环里是代理对，每个还会被数两遍；Rust char 就是码点
        int cjkCount = 0;
        Set<Integer> cjkUnique = new HashSet<>();
        java.util.PrimitiveIterator.OfInt cps = markdown.codePoints().iterator();
        while (cps.hasNext()) {
            int cp = cps.nextInt();
            if (isCjk(cp)) {
                cjkCount++;
                cjkUnique.add(cp);
            }
        }
        if (cjkCount >= 80 && cjkUnique.size() < 20) {
            return false;
        }
        int words = cjkCount + wordsOf(markdown);
        int minWords = linkedPage ? 200 : 80;
        int minChars = linkedPage ? 1_200 : 500;
        if (words < minWords && nonWhitespace < minChars) {
            return false;
        }
        if (summary == null || summary.isBlank()) {
            return true;
        }
        String body = normalized(markdown);
        String sum = normalized(summary);
        if (body.equals(sum)) {
            return false;
        }
        int tailRoom = linkedPage ? 300 : 160;
        return !(body.startsWith(sum) && body.length() - sum.length() < tailRoom);
    }

    /** 词数：非 CJK 段按空白切（rss_full_content.rs:434-438 split(is_cjk).flat_map）。 */
    private static int wordsOf(String markdown) {
        // M-2 收口：码点遍历（CJK 扩展 B 的代理对不再被当两个非 CJK 字符切开）
        StringBuilder spaced = new StringBuilder(markdown.length());
        markdown.codePoints().forEach(cp -> spaced.appendCodePoint(isCjk(cp) ? ' ' : cp));
        int count = 0;
        for (String token : spaced.toString().trim().split("\\s+")) {
            if (!token.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** rss_full_content.rs:421-424 的表意/音节区间（按 codePoint——U+20000+ 走得到）。 */
    static boolean isCjk(int cp) {
        return cp >= 0x3400 && cp <= 0x4DBF || cp >= 0x4E00 && cp <= 0x9FFF
                || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0x20000 && cp <= 0x323AF
                || cp >= 0x3040 && cp <= 0x30FF || cp >= 0xAC00 && cp <= 0xD7AF;
    }

    private static String normalized(String value) {
        return String.join(" ", value.trim().split("\\s+")).toLowerCase(Locale.ROOT);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    /** looks_like_challenge_shell 的复用入口（html.rs:241；Java 侧在 HtmlText，词表同源）。 */
    static boolean looksLikeChallengeShell(String loweredMarkdown) {
        return tech.seexun.jknow.ingest.HtmlText.looksLikeChallengeShell(loweredMarkdown);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** rss_full_content.rs:117-122 AcceptedContent。 */
    record AcceptedContent(String markdown, String source, String finalUrl) {
    }

    /** 失败分类（rss_full_content.rs:8-78 ContentError：wire 码 + retryable 逐字）。 */
    static final class ContentError extends Exception {

        final Kind kind;

        ContentError(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    enum Kind {
        INVALID_URL("invalid_url", false),
        BLOCKED_ADDRESS("blocked_address", false),
        DNS_RESOLUTION("dns_timeout", true),
        NETWORK("connect_timeout", true),
        TIMEOUT("connect_timeout", true),
        TOO_LARGE("response_too_large", false),
        NOT_HTML("unsupported_content_type", false),
        UNSUPPORTED_ENCODING("unsupported_content_type", false),
        BLOCKED_REDIRECT("blocked_redirect", false),
        REDIRECT_LIMIT("blocked_redirect", false),
        HTTP_RETRYABLE("http_retryable", true),
        HTTP_DENIED("http_denied", false),
        CONVERSION("conversion_failed", false),
        CHALLENGE_PAGE("challenge_page", false),
        VIDEO_REQUIRED("video_required", false),
        NON_SUBSTANTIVE("non_substantive", false),
        NO_USABLE_SOURCE("non_substantive", false),
        MARKDOWN_TOO_LARGE("conversion_failed", false),
        INGEST_FAILED("ingest_failed", true);

        final String wire;
        final boolean retryable;

        Kind(String wire, boolean retryable) {
            this.wire = wire;
            this.retryable = retryable;
        }
    }
}
