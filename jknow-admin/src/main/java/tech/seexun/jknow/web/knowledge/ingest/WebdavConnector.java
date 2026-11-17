package tech.seexun.jknow.web.knowledge.ingest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebDAV 来源连接器（逐字对齐旧仓库 webdav.rs：Nextcloud、ownCloud、坚果云、群晖、
 * Apache mod_dav、rclone serve webdav，以及一切说这套协议的网盘）。
 *
 * <p><b>不引 WebDAV 客户端库</b>（0013）：这套协议要的东西很窄——一个 PROPFIND 拿列表、
 * 一个 GET 取内容，响应是 XML。JDK 的 HttpClient + javax.xml DOM 零新增依赖；现成的
 * WebDAV 客户端要么裹着自己的 HTTP 栈，要么把整套 RFC 4918（锁、属性写、版本）都拖进来。
 *
 * <p>解析铁律（webdav.rs:51-58）：只认<b>本地名</b>不认前缀（D:/d:/ns0:/无前缀都合法，
 * 按前缀硬匹配换一台服务端就「同步成功，零个文件」）；目录判据是
 * {@code <collection/>} 存在而不是 href 以 / 结尾（rclone 与 Nextcloud 就不一致）；
 * 同一属性被声明两次（200 块给值、404 块把没找到的再列一遍）取<b>最后一个非空值</b>
 * ——空的那次不许盖掉好值，盖掉之后长度是 0，文件会被静默跳过。
 */
public final class WebdavConnector {

    private static final Logger log = LoggerFactory.getLogger(WebdavConnector.class);

    /** webdav.rs:21 MAX_FILES_PER_SYNC：一次同步最多摄入多少个文件（摄入不可逆）。 */
    static final int MAX_FILES_PER_SYNC = 2_000;

    /** webdav.rs:24 MAX_FILE_BYTES：单个文件的大小上限（32MiB）。 */
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    /** webdav.rs:29 MAX_DEPTH：目录递归层数上限——不是怕深，是怕环（符号链接自指）。 */
    static final int MAX_DEPTH = 8;

    /** RFC 1123（webdav.rs:136：Wed, 02 Sep 2026 15:04:05 GMT）。 */
    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US);

    /** 一个远端文件（webdav.rs:32-38）。 */
    public record RemoteFile(String externalKey, String filename, byte[] bytes,
                             OffsetDateTime lastModified) {
    }

    /** PROPFIND 回来的一条（webdav.rs:41-48）。 */
    record Entry(String href, boolean isDir, long len, OffsetDateTime modified) {
    }

    private WebdavConnector() {
    }

    /**
     * 逐层走目录取回所有文件（webdav.rs:198-296 fetch 逐字控制流）。
     * <b>不用 Depth: infinity</b>——规范允许服务端拒绝（403 Propfind-Finite-Depth），
     * Nextcloud 与 Apache mod_dav 默认就拒绝；能接受它的服务端在大目录上又会一次性吐出
     * 几十兆 XML。逐层加封顶，两头的问题都没有（栈序遍历与 Rust Vec::pop 同构）。
     *
     * @param base 服务基地址（如 https://dav.example.com/remote.php/dav/files）
     * @param root 起始目录（服务端路径，如 /docs）
     * @param user Basic 认证用户名（null=匿名）
     * @param pass Basic 认证密码
     * @return 文件列表 + 是否因条数上限截断
     */
    public static WebdavResult fetch(GuardedHttp.GuardedClient http, String base, String root,
                                     String user, String pass) throws Exception {
        String host = hostOf(base);
        String trimmedBase = trimTrailingSlashes(base);

        List<RemoteFile> out = new ArrayList<>();
        boolean truncated = false;
        ArrayDeque<Frame> queue = new ArrayDeque<>();
        String rootPath = normalize(root);
        queue.addLast(new Frame(rootPath, rootPath, 0));
        int unreadable = 0;

        while (!queue.isEmpty()) {
            Frame frame = queue.pollLast();
            if (frame.depth() > MAX_DEPTH) {
                log.warn("webdav 目录太深，不再往下 dir={}", frame.dir());
                continue;
            }
            String url = trimmedBase + frame.requestDir();
            List<String[]> propfind = new ArrayList<>();
            propfind.add(new String[] {"Depth", "1"});
            propfind.add(new String[] {"Content-Type", "application/xml"});
            if (user != null) {
                propfind.add(new String[] {"Authorization", basic(user, pass)});
            }
            GuardedHttp.GuardedClient.Response resp =
                    http.exchange("PROPFIND", URI.create(url), propfind, null);
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new IllegalStateException(
                        "PROPFIND " + url + " 回了 " + resp.status());
            }
            for (Entry e : parseMultistatus(resp.text())) {
                // 请求路径保留原始百分号转义：解码 %23/%3F 会把文件名字节变成 URL 语法，
                // 而 %2523 该取到名字里真有 '#' 的那一个文件
                String path = documentPath(e.href(), trimmedBase);
                String requestPath = normalize(stripBase(e.href(), trimmedBase));
                // 服务端会把被查询的目录自己也列进来，跳过它否则会无限打转
                if (path.equals(frame.dir())) {
                    continue;
                }
                if (e.isDir()) {
                    queue.addLast(new Frame(path, requestPath, frame.depth() + 1));
                    continue;
                }
                if (e.len() == 0 || e.len() > MAX_FILE_BYTES) {
                    continue;
                }
                if (out.size() >= MAX_FILES_PER_SYNC) {
                    truncated = true;
                    break;
                }

                // 一个文件取不回来不该带走整次同步——与对象存储那边同一个判断
                byte[] bytes;
                try {
                    List<String[]> get = new ArrayList<>();
                    if (user != null) {
                        get.add(new String[] {"Authorization", basic(user, pass)});
                    }
                    GuardedHttp.GuardedClient.Response got =
                            http.exchange("GET", URI.create(trimmedBase + requestPath),
                                    get, null);
                    if (got.status() < 200 || got.status() >= 300) {
                        throw new IllegalStateException(String.valueOf(got.status()));
                    }
                    bytes = got.body();
                } catch (Exception err) {
                    unreadable++;
                    log.warn("webdav 文件取不回来，跳过 path={} error={}", path, err.toString());
                    continue;
                }

                out.add(new RemoteFile("webdav://" + host + path, lastSegment(path), bytes,
                        e.modified()));
            }
            if (truncated) {
                break;
            }
        }
        if (unreadable > 0) {
            log.warn("webdav 有文件没能取回 count={}", unreadable);
        }
        return new WebdavResult(out, truncated);
    }

    /** fetch 的产出（webdav.rs:203）。 */
    public record WebdavResult(List<RemoteFile> files, boolean truncated) {
    }

    private record Frame(String dir, String requestDir, int depth) {
    }

    // ==== multistatus 解析（webdav.rs:59-160 parse_multistatus） ====

    /**
     * DOM 版解析：每个 {@code <response>} 取 href/getcontentlength/getlastmodified 的
     * <b>最后一个非空文本</b>（成对写法两块 propstat，404 那块把没找到的属性再列一遍
     * ——空的不许盖掉好值）；collection 存在即目录（自闭合与成对写法在 DOM 里同形）。
     * 认不出的实体由解析器自己吞掉它那一条，不掀掉整张列表。
     */
    static List<Entry> parseMultistatus(String xml) throws Exception {
        // 认不出的实体只影响它自己那一条：照原文留着，不掀掉整张列表
        // （webdav.rs:109-122——一个 &nbsp; 不该让一个目录里所有文件都同步不了；
        // XML 预定义实体之外的 & 一律按字面 '&' 收下）
        String sanitized = xml.replaceAll(
                "&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)", "&amp;");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 只认本地名不认前缀（webdav.rs:53-58）——按前缀硬匹配的解析器换台服务端就零文件
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(sanitized.getBytes(StandardCharsets.UTF_8)));
        List<Entry> entries = new ArrayList<>();
        for (Element response : descendantsOf(doc.getDocumentElement(), "response").toList()) {
            String href = lastNonEmptyText(response, "href");
            long len = parseLongOrZero(lastNonEmptyText(response, "getcontentlength"));
            OffsetDateTime modified = parseRfc1123(lastNonEmptyText(response, "getlastmodified"));
            boolean isDir = descendantsOf(response, "collection").findAny().isPresent();
            entries.add(new Entry(href == null ? "" : href, isDir, len, modified));
        }
        return entries;
    }

    /** 标签的本地名（webdav.rs:166-168）：丢掉命名空间前缀、转小写（实测有服务端大写）。 */
    private static String localName(String raw) {
        int colon = raw.lastIndexOf(':');
        return (colon >= 0 ? raw.substring(colon + 1) : raw).toLowerCase(Locale.ROOT);
    }

    private static java.util.stream.Stream<Element> descendantsOf(Element root, String localName) {
        List<Element> found = new ArrayList<>();
        collectDescendants(root, localName, found, true);
        return found.stream();
    }

    private static void collectDescendants(Node node, String localName, List<Element> found,
                                           boolean includeRoot) {
        if (includeRoot && node instanceof Element el && localName(node.getNodeName()).equals(localName)) {
            found.add(el);
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectDescendants(child, localName, found, true);
        }
    }

    /** 成对声明取最后一个非空值（webdav.rs:123-151「空的不回写」）。 */
    private static String lastNonEmptyText(Element response, String localName) {
        String last = null;
        for (Element el : descendantsOf(response, localName).toList()) {
            String text = el.getTextContent();
            if (text != null && !text.trim().isEmpty()) {
                last = text.trim();
            }
        }
        return last;
    }

    private static long parseLongOrZero(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static OffsetDateTime parseRfc1123(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.trim(), RFC1123);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ==== 路径工具（webdav.rs:174-344） ====

    /**
     * href 里的百分号转义（webdav.rs:174-191 percent_decode）：非法序列原样留下，不猜；
     * 解码只用于既有文档身份与文件名。
     */
    static String percentDecode(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length];
        int n = 0;
        int i = 0;
        while (i < b.length) {
            if (b[i] == '%' && i + 2 < b.length) {
                int hi = Character.digit((char) b[i + 1], 16);
                int lo = Character.digit((char) b[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out[n++] = (byte) ((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            out[n++] = b[i];
            i++;
        }
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    /** 文档身份路径（webdav.rs:311-330 document_path）：相对/同源 href 解码；反代改写过的
     * 绝对 URL 逐段解码再重编码——'#'/'?' 不能截断身份键，字面 '%' 保持可区分。 */
    static String documentPath(String href, String base) {
        String decoded = percentDecode(href);
        if (!decoded.startsWith(base)
                && (href.startsWith("http://") || href.startsWith("https://"))) {
            try {
                URI url = URI.create(href);
                String[] segments = url.getRawPath().split("/", -1);
                StringBuilder rebuilt = new StringBuilder();
                for (int i = 0; i < segments.length; i++) {
                    rebuilt.append(i == 0 ? "" : "/").append(encodeSegment(percentDecode(segments[i])));
                }
                return normalize(rebuilt.toString());
            } catch (IllegalArgumentException ignored) {
                // 解不动就按相对路径的规则走
            }
        }
        return normalize(stripBase(decoded, base));
    }

    /** href 可能是绝对 URL 也可能只是路径（webdav.rs:333-344 strip_base）。 */
    static String stripBase(String href, String base) {
        if (href.startsWith(base)) {
            return href.substring(base.length());
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            try {
                return URI.create(href).getRawPath();
            } catch (IllegalArgumentException ignored) {
                // 解不动原样给
            }
        }
        return href;
    }

    /** 路径统一成 /a/b 的形状：前有斜杠、后无斜杠（根除外）（webdav.rs:299-309 逐字：
     * 只剥一个尾斜杠——两端不一致会让同一个目录被走两遍，剥多个是另一套语义）。 */
    static String normalize(String p) {
        String t = p.trim();
        if (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.isEmpty()) {
            return "/";
        }
        return t.startsWith("/") ? t : "/" + t;
    }

    /** url crate 的 PATH_SEGMENT 编码近似：保留 unreserved+sub-delims+':','@'。 */
    private static String encodeSegment(String segment) {
        StringBuilder out = new StringBuilder(segment.length());
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            boolean subDelim = c == '!' || c == '$' || c == '&' || c == '\'' || c == '('
                    || c == ')' || c == '*' || c == '+' || c == ',' || c == ';' || c == '='
                    || c == ':' || c == '@';
            if (unreserved || subDelim) {
                out.append((char) c);
            } else {
                out.append(String.format("%%%02X", c));
            }
        }
        return out.toString();
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String hostOf(String base) {
        try {
            String host = URI.create(base).getHost();
            return host != null ? host : "webdav";
        } catch (IllegalArgumentException e) {
            return "webdav";
        }
    }

    private static String trimTrailingSlashes(String base) {
        String t = base;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + (pass == null ? "" : pass)).getBytes(StandardCharsets.UTF_8));
    }
}
