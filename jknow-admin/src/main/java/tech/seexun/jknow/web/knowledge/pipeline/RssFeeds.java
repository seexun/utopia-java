package tech.seexun.jknow.web.knowledge.pipeline;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * RSS/Atom feed 观察面（对齐旧仓库 sync_rss 的 feed-rs 观察段——ingest_sources.rs:565-735）：
 * 只做「条目识别」（guid→链接兜底→>4096B 摘要身份），content_mode=feed 的正文合成由
 * {@link SyncSourceJob} 完成；full_new_items 的台账/发现/领取属 M3 挂账。
 *
 * <p><b>只认本地名不认前缀</b>（与 WebdavConnector 同一铁律）：rss: RDF 前缀、atom: 前缀、
 * 无前缀的裸 XML 都合法，按前缀硬匹配换一台服务端就「同步成功，零个条目」。
 * feed-rs 会给缺 id 的条目合成 id（链接+标题或随机 UUID）——那不是应用级稳定身份，
 * 这里照旧留空让链接兜底（ingest_sources.rs:583-587 注释原意）。
 */
public final class RssFeeds {

    private RssFeeds() {
    }

    /** 一条观察（ingest_sources.rs:43-52 RssObservation 的 feed 判定子集）。 */
    public record Observation(String key, String title, String articleUrl, String summary,
                              String embeddedHtml, OffsetDateTime docTime) {
    }

    /** RSS_EXTERNAL_KEY_MAX_BYTES（ingest_sources.rs:478）。 */
    static final int RSS_EXTERNAL_KEY_MAX_BYTES = 4_096;

    /** 嵌入正文截断（ingest_sources.rs:602-607 truncate_utf8 2MiB）。 */
    private static final int MAX_EMBEDDED_BYTES = 2 * 1024 * 1024;

    /** 解析 feed：RSS 2.0（item）与 Atom（entry）双形；解析失败抛 IllegalArgumentException。 */
    public static List<Observation> parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            List<Element> entries = children(doc.getDocumentElement(), "item");
            if (entries.isEmpty()) {
                entries = children(doc.getDocumentElement(), "entry");
            }
            List<Observation> out = new ArrayList<>(entries.size());
            for (Element entry : entries) {
                Observation o = observe(entry);
                if (o != null) {
                    out.add(o);
                }
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse feed: " + e.getMessage(), e);
        }
    }

    private static Observation observe(Element entry) {
        String id = textOf(entry, "guid");
        if (id == null || id.isBlank()) {
            id = textOf(entry, "id");
        }
        String title = textOf(entry, "title");
        title = title == null || title.isBlank() ? "untitled" : title.trim();
        String articleUrl = articleLinkOf(entry);
        // identity：guid 优先、链接兜底；两者皆空 = 没有稳定身份，跳过
        String identity = id != null && !id.trim().isEmpty() ? id.trim()
                : articleUrl != null && !articleUrl.trim().isEmpty() ? articleUrl.trim() : null;
        if (identity == null || identity.isEmpty()) {
            return null;
        }
        String summary = orEmpty(textOf(entry, "description"));
        if (summary.isEmpty()) {
            summary = orEmpty(textOf(entry, "summary"));
        }
        String embedded = textOf(entry, "encoded");
        if (embedded == null || embedded.isBlank()) {
            embedded = textOf(entry, "content");
        }
        if (embedded != null) {
            embedded = embedded.trim();
            if (embedded.isEmpty() || embedded.getBytes(StandardCharsets.UTF_8).length
                    > MAX_EMBEDDED_BYTES) {
                embedded = embedded.isEmpty() ? null
                        : truncateUtf8(embedded, MAX_EMBEDDED_BYTES);
            }
        }
        return new Observation(boundIdentity(identity), title, articleUrl, summary.trim(),
                embedded, docTimeOf(entry));
    }

    /** article 链接：rel=alternate（或缺省）、type 不是 html/xhtml 的不要、http/https 才要。 */
    private static String articleLinkOf(Element entry) {
        List<Element> links = children(entry, "link");
        // RSS：<link>正文即地址</link>
        if (!links.isEmpty() && links.get(0).getTextContent() != null
                && !links.get(0).getTextContent().isBlank()) {
            String href = links.get(0).getTextContent().trim();
            return httpUrl(href) ? href : null;
        }
        // Atom：<link href=… rel=… type=…/>
        for (Element link : links) {
            String rel = link.getAttribute("rel");
            if (!rel.isEmpty() && !"alternate".equalsIgnoreCase(rel)) {
                continue;
            }
            String type = link.getAttribute("type");
            if (!type.isEmpty() && !type.equalsIgnoreCase("text/html")
                    && !type.equalsIgnoreCase("application/xhtml+xml")) {
                continue;
            }
            String href = link.getAttribute("href").trim();
            if (httpUrl(href)) {
                return href;
            }
        }
        return null;
    }

    private static boolean httpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** >4096B 摘身份：绝不截断出版方的 GUID/URL（同前缀合并成一条），全量摘要保留身份材料。 */
    static String boundIdentity(String identity) {
        if (identity.getBytes(StandardCharsets.UTF_8).length <= RSS_EXTERNAL_KEY_MAX_BYTES) {
            return identity;
        }
        return "rss:v1:sha256:" + sha256Hex(identity.getBytes(StandardCharsets.UTF_8));
    }

    /** pubDate（RFC 1123/RFC 822 族）→ dc:date/published/updated（ISO 8601）；解析不了 null。 */
    private static OffsetDateTime docTimeOf(Element entry) {
        for (String field : new String[] {"pubDate", "published", "updated", "date"}) {
            String raw = textOf(entry, field);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            OffsetDateTime t = parseTime(raw.trim());
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static OffsetDateTime parseTime(String raw) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return OffsetDateTime.parse(raw,
                    DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        // RFC 822 旧形（GMT 之外的军用时区缩写/无星期）——Zulu 兜底
        for (String zone : new String[] {"UT", "Z", "EST", "EDT", "CST", "CDT", "MST", "MDT",
            "PST", "PDT"}) {
            try {
                return OffsetDateTime.parse(raw.endsWith(" " + zone) ? raw.substring(0,
                        raw.length() - zone.length()) + "GMT"
                        : raw,
                        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
            } catch (DateTimeParseException ignored) {
                // 试下一个
            }
        }
        return null;
    }

    // ---- DOM 小工具（本地名匹配，前缀无关） ----

    private static List<Element> children(Element element, String localName) {
        List<Element> out = new ArrayList<>();
        collect(element.getElementsByTagName("*"), localName, out);
        return out;
    }

    private static void collect(NodeList all, String localName, List<Element> out) {
        for (int i = 0; i < all.getLength(); i++) {
            org.w3c.dom.Node n = all.item(i);
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
    }

    /** 第一个非空文本子元素（本地名匹配）。 */
    private static String textOf(Element entry, String localName) {
        NodeList all = entry.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            org.w3c.dom.Node n = all.item(i);
            if (n instanceof Element e && localName.equals(e.getLocalName())
                    && n.getTextContent() != null && !n.getTextContent().isBlank()) {
                return n.getTextContent();
            }
        }
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** ingest_sources.rs:469-476 truncate_utf8（按字节截、不切断多字节字符）。 */
    static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
