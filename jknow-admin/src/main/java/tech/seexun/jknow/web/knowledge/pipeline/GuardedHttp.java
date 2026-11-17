package tech.seexun.jknow.web.knowledge.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 一条受管的抓取路径（http_fetch.rs 全量对等，M3 任务 9 落地）。
 *
 * <p><b>威胁模型（为什么要有这一层）</b>：从前 HttpFetcher 只设了超时和 UA，从不问
 * 「这个请求要连到哪里去」。SSRF 的伤害面有三条：
 * <ol>
 *   <li><b>元数据端点</b>——{@code http://169.254.169.254/latest/meta-data/} 会被当成一篇
 *       网页抓回来、切块、索引，云上的临时凭据就此进了知识库；</li>
 *   <li><b>重定向进内网</b>——公网主机可以把第二跳指进内网（10.x/192.168.x/环回），
 *       防火墙挡的是外部直连，挡不住「服务器自己跟着跳」；</li>
 *   <li><b>DNS 重绑定</b>——校验时解析出公网地址，连接时再解析一次变成内网地址，
 *       那两次解析之间的空隙就是整个手法。</li>
 * </ol>
 *
 * <p><b>谁选的这个地址，决定该有多严</b>（Reach 两档，http_fetch.rs:35-40）：
 * <ul>
 *   <li>{@link Reach#OPERATOR}：地址是这个库的 editor 自己填的（url / webdav / jira /
 *       custom 来源、feed 地址）。内网是<b>正当目标</b>——自部署的内部 wiki 正是本产品的
 *       用途；</li>
 *   <li>{@link Reach#CONTENT}：地址来自抓回来的内容（feed 里的文章链接）。这时「一个人
 *       选的」已经退化成「一个 feed 说的」，只准公网，且 URL 形状受限（默认端口、无
 *       userinfo、无 fragment）。</li>
 * </ul>
 *
 * <p>两档共有的防线（http_fetch.rs:16-22 的六件事）：解析后<b>逐个地址校验</b>（Content
 * 档一个不合格就整体拒绝——公网+内网双解析本身就是把请求引进内网的手法）；<b>连接钉在
 * 已解析的 IP 上</b>（JDK HttpClient 无自定义解析钩子，这里按 HTTP/1.1 直写 socket——
 * 连接目标就是校验过的那个地址，重绑定的空隙不存在了）；重定向手动<b>逐跳跟并复验</b>
 * （跟哪一跳由这里的循环决定，交给 JDK 跟就等于没人看它跟去了哪里）；https 不许降级成
 * http；响应体有字节上限；连接/读取有超时。
 *
 * <p><b>重定向不许升级可达范围</b>：operator 填的公网主机，第二跳就不能指进内网——
 * 他选的是那台机器，不是那台机器的转发能力（checkHop）。
 *
 * <p><b>与 reqwest 的两处刻意差异</b>（记入任务 9 报告）：① 不走系统代理——私网地址
 * 经代理只会 502，公网直连不需要，运维确需代理出网的部署暂不支持；② 请求一律
 * {@code Accept-Encoding: identity}——我们不解码压缩响应（Rust 侧 Content 档同样禁压缩，
 * Operator 档 reqwest 会带 gzip，这里统一收窄为 identity，语义只会更保守）。
 */
public final class GuardedHttp {

    private GuardedHttp() {
    }

    /**
     * 抓取用的 User-Agent（ingest_sources.rs:27-31 UA 逐字）。reqwest/JDK 默认一个都
     * 不发，而维基百科明确拒绝匿名请求（403），Cloudflare 前置的站点也普遍如此。
     * 自报家门也是爬虫礼节：站方能认出我们、能联系到我们。
     */
    public static final String UA =
            "JKnow/1.0.0 (+https://utopia.bi; self-hosted knowledge platform)";

    /** Limits 换字节上限（record wither）。 */
    public static Limits limitsWithMaxBytes(Limits limits, long maxBytes) {
        return new Limits(maxBytes, limits.maxRedirects(), limits.dnsTimeout(),
                limits.connectTimeout(), limits.readTimeout(), limits.overallTimeout());
    }

    /** 目的地是谁选的（http_fetch.rs:35-40）。 */
    public enum Reach {
        /** 库里的 editor 自己填的主机：内网也放行。 */
        OPERATOR,
        /** 抓回来的内容里给出的地址：只准公网，URL 形状受限。 */
        CONTENT
    }

    /** 抓取预算（http_fetch.rs:43-70 Limits 逐字）。 */
    public record Limits(long maxBytes, int maxRedirects, Duration dnsTimeout,
                         Duration connectTimeout, Duration readTimeout,
                         Duration overallTimeout) {

        /** http_fetch.rs:52-63 Default：16MiB / 5 跳 / DNS 30s / 连接 10s / 总 30s。 */
        public static Limits defaults() {
            return new Limits(16L * 1024 * 1024, 5, Duration.ofSeconds(30),
                    Duration.ofSeconds(10), null, Duration.ofSeconds(30));
        }

        Limits withOverall(Duration overall) {
            return new Limits(maxBytes, maxRedirects, dnsTimeout, connectTimeout, readTimeout,
                    overall);
        }
    }

    /** 抓取失败面（http_fetch.rs:73-92 FetchError 的等价物；Kind 一一对应）。 */
    public enum FailureKind {
        INVALID_URL, UNSUPPORTED_SCHEME, BLOCKED_ADDRESS, DNS, BLOCKED_REDIRECT,
        REDIRECT_LIMIT, TOO_LARGE, STATUS, TRANSPORT
    }

    /** 带原因码的抓取异常；{@link #status} 只在 {@link FailureKind#STATUS} 时有意义。 */
    public static final class FetchException extends IOException {

        public final FailureKind kind;
        public final int status;
        public final InetAddress address;

        FetchException(FailureKind kind, String message) {
            this(kind, message, -1, null);
        }

        FetchException(FailureKind kind, String message, int status, InetAddress address) {
            super(message);
            this.kind = kind;
            this.status = status;
            this.address = address;
        }

        /** http_fetch.rs:385 is_public_ip 的否定面；连接超时按超时归类（Rust Transport+is_timeout）。 */
        public boolean isTimeout() {
            return kind == FailureKind.TRANSPORT && getCause() instanceof java.net.SocketTimeoutException
                    || status == 408;
        }
    }

    /** 抓取产物（http_fetch.rs:95-100 Fetched）。 */
    public record Fetched(URI finalUrl, String mime, String contentEncoding, byte[] bytes) {
    }

    // ------------------------------------------------------------------
    // GET：自己逐跳跟重定向（http_fetch.rs:161-220）
    // ------------------------------------------------------------------

    /** GET 一个 URL，手动跟重定向并逐跳复验（http_fetch.rs:161 get）。 */
    public static Fetched get(String rawUrl, Reach reach, Limits limits) throws IOException {
        long deadlineNanos = System.nanoTime() + limits.overallTimeout().toNanos();
        try {
            return getInner(rawUrl, reach, limits, deadlineNanos);
        } catch (FetchException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // 总时限到点按 408 报（http_fetch.rs:163-166 timeout → Status(408) 的口径）
            if (System.nanoTime() > deadlineNanos) {
                throw new FetchException(FailureKind.STATUS, "HTTP 408", 408, null);
            }
            throw transport(e);
        }
    }

    private static Fetched getInner(String rawUrl, Reach reach, Limits limits, long deadlineNanos)
            throws IOException {
        if (reach == Reach.CONTENT) {
            validateContentUrl(rawUrl);
        }
        URI url = parse(rawUrl);
        for (int hop = 0; hop <= limits.maxRedirects(); hop++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new FetchException(FailureKind.STATUS, "HTTP 408", 408, null);
            }
            // 每一跳新建一个不跟重定向的客户端：跟哪一跳由这个循环决定（http_fetch.rs:180-182）
            GuardedClient client = clientFor(url, reach, limits.withOverall(
                    Duration.ofNanos(remainingNanos)));
            List<String[]> headers = new ArrayList<>();
            if (reach == Reach.CONTENT) {
                headers.add(new String[] {"Accept",
                        "text/html,application/xhtml+xml,text/markdown"});
            }
            headers.add(new String[] {"Accept-Encoding", "identity"});
            GuardedClient.Response response = client.exchange("GET", url, headers, null);

            if (response.status() >= 300 && response.status() < 400) {
                if (hop == limits.maxRedirects()) {
                    throw new FetchException(FailureKind.REDIRECT_LIMIT, "too many redirects");
                }
                String location = response.header("location");
                if (location == null || location.isBlank()) {
                    throw new FetchException(FailureKind.BLOCKED_REDIRECT, "(no location)");
                }
                if (reach == Reach.CONTENT && rawAuthorityContainsUserinfo(location)) {
                    throw new FetchException(FailureKind.BLOCKED_REDIRECT, "userinfo");
                }
                URI next;
                try {
                    next = url.resolve(location.trim());
                } catch (IllegalArgumentException e) {
                    throw new FetchException(FailureKind.BLOCKED_REDIRECT, location);
                }
                checkHop(url, next, reach);
                url = next;
                continue;
            }
            if (response.status() / 100 != 2) {
                throw new FetchException(FailureKind.STATUS, "HTTP " + response.status(),
                        response.status(), null);
            }
            return readBounded(response, url, limits.maxBytes());
        }
        throw new FetchException(FailureKind.REDIRECT_LIMIT, "too many redirects");
    }

    /** 声明/流式两道上限都已在 GuardedClient 读路径拦下（I-2）；这里只组包。 */
    private static Fetched readBounded(GuardedClient.Response response, URI finalUrl,
                                       long maxBytes) {
        return new Fetched(finalUrl, mimeOf(response), response.header("content-encoding"),
                response.body());
    }

    /** Content-Type 去参数、小写；缺省 octet-stream（http_fetch.rs:349-355）。 */
    static String mimeOf(GuardedClient.Response response) {
        String raw = response.header("content-type");
        if (raw == null || raw.isBlank()) {
            return "application/octet-stream";
        }
        int semi = raw.indexOf(';');
        String mime = (semi > 0 ? raw.substring(0, semi) : raw).trim().toLowerCase(Locale.ROOT);
        return mime.isEmpty() ? "application/octet-stream" : mime;
    }

    // ------------------------------------------------------------------
    // 受管客户端（http_fetch.rs:107-158 client_for）
    // ------------------------------------------------------------------

    /**
     * 一个只连得到这个主机、且地址已按 {@code reach} 校验过的客户端。
     *
     * <p>给那些自己要发好几个请求的来源用（GitHub / Jira / Notion / 自定义端点 /
     * WebDAV / 对象存储都要带认证头、要翻页、要发 PROPFIND）——它们保留自己的请求
     * 逻辑，只是换一个连得到的地方受管的客户端。
     *
     * <p><b>不跟重定向</b>（对齐 Rust {@code follow=false} 形态）：要跟哪一跳由调用方
     * 决定；{@link #get} 的循环就是那个调用方。GitHub/Jira/Notion 的 API 不发重定向，
     * 真遇到 3xx 会以响应原样返回（reqwest 侧默认跟随的差异记入任务 9 报告）。
     */
    public static GuardedClient clientFor(URI url, Reach reach, Limits limits) throws IOException {
        if (reach == Reach.CONTENT) {
            validateContentUrl(url.toString());
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            throw new FetchException(FailureKind.INVALID_URL, "invalid URL: " + url);
        }
        List<InetAddress> addrs = resolve(host, url, reach, limits.dnsTimeout());
        return new GuardedClient(host, url.getPort(), "https".equals(url.getScheme()), addrs,
                limits);
    }

    // ------------------------------------------------------------------
    // 逐跳判定（http_fetch.rs:226-248 check_hop）
    // ------------------------------------------------------------------

    /**
     * 一跳是否走得过去。除了 {@code reach} 本身的规则，还有一条<b>不许升级</b>：
     * 这一跳原本落在公网上，下一跳就不能落进内网。operator 选的是那台主机，不是
     * 那台主机的转发能力。
     */
    public static void checkHop(URI from, URI to, Reach reach) throws IOException {
        if ("https".equals(from.getScheme()) && "http".equals(to.getScheme())) {
            throw new FetchException(FailureKind.BLOCKED_REDIRECT,
                    "redirect to " + to + " is not allowed from here");
        }
        if (reach == Reach.CONTENT) {
            // 下一跳的形状与地址由 clientFor 在循环里解析并钉住（对齐 Rust 注释）
            validateContentUrl(to.toString());
            return;
        }
        String host = to.getHost();
        if (host == null || host.isEmpty()) {
            throw new FetchException(FailureKind.INVALID_URL, "invalid URL: " + to);
        }
        List<InetAddress> addrs = resolve(host, to, reach, Duration.ofSeconds(30));
        boolean fromPublic = true;
        try {
            String fromHost = from.getHost();
            if (fromHost != null && !fromHost.isEmpty()) {
                List<InetAddress> fromAddrs = resolve(fromHost, from, reach,
                        Duration.ofSeconds(30));
                fromPublic = fromAddrs.stream().allMatch(GuardedHttp::isPublicIp);
            }
        } catch (IOException ignored) {
            // 解析不了来源时保守放行到目标判定（Rust unwrap_or(true) 同形）
        }
        if (fromPublic) {
            for (InetAddress a : addrs) {
                if (!isPublicIp(a)) {
                    throw new FetchException(FailureKind.BLOCKED_REDIRECT,
                            "redirect to " + to + " is not allowed from here");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // URL 形状与地址判定（http_fetch.rs:250-291 / 385-442）
    // ------------------------------------------------------------------

    /**
     * Content 档的 URL 形状门（http_fetch.rs:250-273 validate_content_url 逐字）：
     * 只收 http/https、有主机、默认端口、无 userinfo（含 URL 解析会吞掉的裸 {@code @}）、
     * 无 fragment；主机是 IP 字面量时必须是公网地址。
     */
    public static URI validateContentUrl(String raw) throws IOException {
        URI parsed;
        try {
            parsed = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new FetchException(FailureKind.INVALID_URL, "invalid content URL");
        }
        boolean portOk = parsed.getPort() == -1 || parsed.getPort() == 80 || parsed.getPort() == 443;
        if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getRawFragment() != null || !portOk
                || parsed.getRawUserInfo() != null
                || rawAuthorityContainsUserinfo(raw)) {
            throw new FetchException(FailureKind.INVALID_URL, "invalid content URL");
        }
        InetAddress literal = ipLiteral(parsed.getHost());
        if (literal != null && !isPublicIp(literal)) {
            throw new FetchException(FailureKind.BLOCKED_ADDRESS,
                    "destination " + literal.getHostAddress() + " is not a public address");
        }
        return parsed;
    }

    /** http_fetch.rs:285-291 parse：只收 http/https。 */
    static URI parse(String raw) throws IOException {
        URI url;
        try {
            url = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new FetchException(FailureKind.INVALID_URL, "invalid URL: " + e.getMessage());
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return url;
        }
        throw new FetchException(FailureKind.UNSUPPORTED_SCHEME,
                "only http and https are fetched, got \"" + scheme + "\"");
    }

    /** http_fetch.rs:275-283：解析会吞掉裸 userinfo，先在原文的 authority 段里找 @。 */
    static boolean rawAuthorityContainsUserinfo(String raw) {
        int scheme = raw.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        String rest = raw.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end).contains("@");
    }

    /**
     * 解析主机并按 {@code reach} 校验每一个地址（http_fetch.rs:297-338 resolve +
     * validate_resolved）。<b>一次解析，之后钉住</b>：校验完再让连接层自己解析一遍，
     * 中间那一瞬就是 DNS rebinding 的下手处。
     */
    static List<InetAddress> resolve(String host, URI url, Reach reach, Duration dnsTimeout)
            throws IOException {
        int port = url.getPort() < 0 ? ("https".equals(url.getScheme()) ? 443 : 80) : url.getPort();
        if (port < 0) {
            throw new FetchException(FailureKind.INVALID_URL, "invalid URL: " + url);
        }
        InetAddress literal = ipLiteral(host);
        List<InetAddress> resolved;
        if (literal != null) {
            resolved = List.of(literal);
        } else {
            resolved = lookupWithTimeout(host, dnsTimeout);
        }
        return validateResolved(resolved, reach, host);
    }

    /** InetAddress.getByName 是阻塞调用——放到执行线程里限时（对齐 tokio::time::timeout）。 */
    private static List<InetAddress> lookupWithTimeout(String host, Duration dnsTimeout)
            throws IOException {
        ExecutorService oneShot = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "guarded-dns");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<List<InetAddress>> future = oneShot.submit(() -> {
                List<InetAddress> out = new ArrayList<>();
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    out.add(a);
                }
                return out;
            });
            return future.get(dnsTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new FetchException(FailureKind.DNS, "could not resolve " + host);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException io) {
                throw new FetchException(FailureKind.DNS, "could not resolve " + host);
            }
            throw new FetchException(FailureKind.DNS, "could not resolve " + host);
        } finally {
            oneShot.shutdownNow();
        }
    }

    /**
     * 校验解析结果（http_fetch.rs:317-338）：Content 档<b>一个不合格就整体拒绝</b>，
     * 不是挑出公网的那些来连——一个主机同时解析出公网与内网地址，本身就是要把请求
     * 引进内网的手法。公测入口（包可见）供「混合 DNS 应答整体拒收」断言。
     */
    static List<InetAddress> validateResolved(List<InetAddress> resolved, Reach reach, String host)
            throws IOException {
        if (resolved.isEmpty()) {
            throw new FetchException(FailureKind.DNS, "could not resolve " + host);
        }
        if (reach == Reach.CONTENT) {
            for (InetAddress a : resolved) {
                if (!isPublicIp(a)) {
                    throw new FetchException(FailureKind.BLOCKED_ADDRESS,
                            "destination " + a.getHostAddress() + " is not a public address");
                }
            }
        }
        // 去重保持顺序（Rust seen HashSet 按 IP 去重）
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : resolved) {
            boolean dup = false;
            for (InetAddress seen : out) {
                if (seen.equals(a)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * IP 字面量（v4 / [v6]）；域名返回 null。<b>纯语法判定</b>（M-5 收口）：数字开头的
     * 域名（3com.com）不能落到 getByName——那会绕开 lookupWithTimeout 的时限，一个
     * 无响应的解析器就能把调用线程挂满 dns_timeout 之外的时间。
     */
    static InetAddress ipLiteral(String host) {
        String h = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        boolean v6 = h.contains(":");
        boolean v4Shape = !v6 && h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!v6 && !v4Shape) {
            return null;
        }
        try {
            return InetAddress.getByName(h);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 这个地址在公网上吗（http_fetch.rs:385-442 is_public_ip 逐字）。
     *
     * <p>按 RFC 6890 的特殊用途地址表拦，<b>只拦表里的</b>：{@code 192.0.0.0/16} 整段当
     * 保留是个常见的过度拦截（保留的只有 {@code 192.0.0.0/24} 与 {@code 192.0.2.0/24}），
     * 而多拦一段就是一个用户抓不到的正当站点。
     */
    public static boolean isPublicIp(InetAddress ip) {
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int a = b[0] & 0xFF;
            int c = b[2] & 0xFF;
            int second = b[1] & 0xFF;
            // 0.0.0.0/8 未指定（isAnyLocalAddress 只认全 0 地址；a==0 收整段）
            if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()
                    || ip.isMulticastAddress() || a == 0
                    || (a == 10) || (a == 172 && second >= 16 && second <= 31)
                    || (a == 192 && second == 168)
                    || (a == 255 && second == 255 && c == 255 && (b[3] & 0xFF) == 255)
                    || (a == 192 && second == 0 && c == 2)      // 192.0.2/24 文档
                    || (a == 198 && second == 51 && c == 100)   // 198.51.100/24 文档
                    || (a == 203 && second == 0 && c == 113)    // 203.0.113/24 文档
                    || (a == 100 && second >= 64 && second <= 127) // 100.64/10 CGNAT
                    || (a == 192 && second == 0 && c == 0)      // 192.0.0/24 IETF 协议分配
                    || (a == 192 && second == 88 && c == 99)    // 6to4 中继任播
                    || (a == 198 && (second == 18 || second == 19)) // 198.18/15 基准测试
                    || a >= 224) {                              // 组播与保留
                return false;
            }
            return true;
        }
        // IPv6
        int[] seg = new int[8];
        for (int i = 0; i < 8; i++) {
            seg[i] = ((b[i * 2] & 0xFF) << 8) | (b[i * 2 + 1] & 0xFF);
        }
        // v4 映射（::ffff:a.b.c.d）：按内嵌的 v4 判
        if (seg[0] == 0 && seg[1] == 0 && seg[2] == 0 && seg[3] == 0 && seg[4] == 0
                && seg[5] == 0xFFFF) {
            return isPublicIp(v4Of(seg[6], seg[7]));
        }
        // 本地用途 NAT64（64:ff9b:1::/48）与 v6 特殊段不能当 RSS 目标
        if (seg[0] == 0x0064 && seg[1] == 0xff9b && seg[2] == 1) {
            return false;
        }
        if (seg[0] == 0x2001 && (seg[1] == 0 || seg[1] == 2 || (seg[1] & 0xFFF0) == 0x0010)) {
            return false;
        }
        if (seg[0] == 0x3fff && (seg[1] & 0xF000) == 0) {
            return false;
        }
        // 共享 NAT64（64:ff9b::/96）：内嵌 v4 同判
        if (seg[0] == 0x0064 && seg[1] == 0xff9b) {
            return isPublicIp(v4Of(seg[6], seg[7]));
        }
        // 6to4（2002::/16）：v4 在第 2、3 段
        if (seg[0] == 0x2002) {
            return isPublicIp(v4Of(seg[1], seg[2]));
        }
        return !(ip.isLoopbackAddress() || ip.isAnyLocalAddress()
                || (seg[0] & 0xFF00) == 0xFF00        // ff00::/8 组播
                || (seg[0] & 0xFE00) == 0xFC00       // fc00::/7 唯一本地
                || (seg[0] & 0xFFC0) == 0xFE80       // fe80::/10 链路本地
                || (seg[0] == 0x2001 && seg[1] == 0x0DB8)); // 2001:db8::/32 文档
    }

    private static InetAddress v4Of(int hi, int lo) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) (hi >> 8), (byte) hi,
                    (byte) (lo >> 8), (byte) lo});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static FetchException transport(Throwable cause) {
        return (FetchException) new FetchException(FailureKind.TRANSPORT,
                cause.getMessage() == null ? cause.toString() : cause.getMessage())
                .initCause(cause);
    }

    /**
     * Host 头/签名共用的取值函数（C-2 收口：<b>线上值与签名值必须同源</b>——显式默认
     * 端口 {@code https://host:443} 折成裸 host，非默认端口带 :port；RFC 7230 §5.4
     * 只许一个 Host 头，speak() 是唯一书写者）。
     */
    static String hostHeaderValue(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        boolean tls = "https".equals(uri.getScheme());
        if (port > 0 && !(tls && port == 443) && !(!tls && port == 80)) {
            return host + ":" + port;
        }
        return host;
    }

    // ------------------------------------------------------------------
    // 受管客户端本体：HTTP/1.1 直写 socket（地址已钉住）
    // ------------------------------------------------------------------

    /**
     * 绑定单一主机的受管 HTTP 客户端。连接永远落在构造时校验过的地址上——DNS 再怎么
     * 变，这条连接追不进去。按 {@code Connection: close} 一事一连接（翻页请求各自独立，
     * 与 reqwest 连接池的差异只在性能面）。
     */
    public static final class GuardedClient {

        private final String host;
        private final int explicitPort;
        private final boolean tls;
        private final List<InetAddress> pinned;
        private final Limits limits;

        GuardedClient(String host, int port, boolean tls, List<InetAddress> pinned, Limits limits) {
            this.host = host;
            this.explicitPort = port;
            this.tls = tls;
            this.pinned = pinned;
            this.limits = limits;
        }

        /** 一次响应：状态、头（小写键）、体。 */
        public record Response(int status, Map<String, List<String>> headers, byte[] body) {

            public String header(String name) {
                List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
                return values == null || values.isEmpty() ? null : values.get(0);
            }

            public String text() {
                return new String(body, StandardCharsets.UTF_8);
            }
        }

        /** GET（头按 (name, value) 序对给）。 */
        public Response get(URI uri, String... headerPairs) throws IOException {
            return exchange("GET", uri, pairs(headerPairs), null);
        }

        /** POST JSON。 */
        public Response postJson(URI uri, String json, String... headerPairs) throws IOException {
            List<String[]> headers = pairs(headerPairs);
            headers.add(new String[] {"Content-Type", "application/json"});
            return exchange("POST", uri, headers, json.getBytes(StandardCharsets.UTF_8));
        }

        private static List<String[]> pairs(String[] flat) {
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i + 1 < flat.length; i += 2) {
                out.add(new String[] {flat[i], flat[i + 1]});
            }
            return out;
        }

        /**
         * 发一次请求。URI 的主机必须与绑定主机一致（对齐 resolve_to_addrs 只钉这一个
         * host 名的语义——换主机要另建客户端）。
         */
        public Response exchange(String method, URI uri, List<String[]> headers, byte[] body)
                throws IOException {
            if (!host.equalsIgnoreCase(uri.getHost())) {
                throw new FetchException(FailureKind.INVALID_URL,
                        "guarded client is pinned to " + host + ", asked for " + uri.getHost());
            }
            int port = uri.getPort() < 0 ? (tls ? 443 : 80) : uri.getPort();
            // 换地址重试只留给「连接失败」：协议层的错（超时/体积上限/截断）换地址也救不回
            Socket socket = null;
            IOException connectFailure = null;
            for (InetAddress addr : pinned) {
                try {
                    socket = connect(addr, port);
                    connectFailure = null;
                    break;
                } catch (IOException e) {
                    connectFailure = e;
                }
            }
            if (socket == null) {
                throw transport(connectFailure != null ? connectFailure
                        : new IOException("no pinned address"));
            }
            try {
                return speak(socket, method, uri, headers, body);
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已读结果
                }
            }
        }

        private Socket connect(InetAddress addr, int port) throws IOException {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(addr, port),
                        (int) limits.connectTimeout().toMillis());
                if (limits.readTimeout() != null) {
                    socket.setSoTimeout((int) limits.readTimeout().toMillis());
                } else {
                    socket.setSoTimeout((int) limits.overallTimeout().toMillis());
                }
                return socket;
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        private Response speak(Socket raw, String method, URI uri, List<String[]> headers,
                               byte[] body) throws IOException {
            Socket socket = raw;
            try {
                if (tls) {
                    // 校验过的 IP 之上按原主机名握手：SNI 与证书校验都对着 host 名
                    SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host,
                            raw.getPort(), true);
                    SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(params);
                    ssl.startHandshake();
                    socket = ssl;
                }
                String target = requestTarget(uri);
                StringBuilder head = new StringBuilder();
                head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
                head.append("Host: ").append(hostHeader(uri)).append("\r\n");
                head.append("User-Agent: ").append(UA).append("\r\n");
                boolean hasAcceptEncoding = false;
                for (String[] h : headers) {
                    if (h[0].equalsIgnoreCase("Accept-Encoding")) {
                        hasAcceptEncoding = true;
                    }
                    head.append(h[0]).append(": ").append(h[1]).append("\r\n");
                }
                if (!hasAcceptEncoding) {
                    head.append("Accept-Encoding: identity\r\n");
                }
                head.append("Connection: close\r\n");
                if (body != null) {
                    head.append("Content-Length: ").append(body.length).append("\r\n");
                }
                head.append("\r\n");
                OutputStream out = socket.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (body != null) {
                    out.write(body);
                }
                out.flush();
                // I-2 收口：上限进读路径——无 Content-Length 的无限流在 TOO_LARGE
                // 之前不得吃穿堆（Rust read_bounded 流式语义，不再事后才查）
                return readResponse(socket.getInputStream(), limits.maxBytes());
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已读结果
                }
            }
        }

        private Response readResponse(InputStream in, long maxBytes) throws IOException {
            // 状态行
            String statusLine = readLine(in);
            if (statusLine == null || statusLine.isEmpty()) {
                throw new FetchException(FailureKind.TRANSPORT, "empty response");
            }
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) {
                throw new FetchException(FailureKind.TRANSPORT, "malformed status line: "
                        + statusLine);
            }
            int status;
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new FetchException(FailureKind.TRANSPORT,
                        "malformed status line: " + statusLine);
            }
            // 头
            Map<String, List<String>> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            // 体
            byte[] body;
            List<String> transfer = headers.get("transfer-encoding");
            String length = headerOf(headers, "content-length");
            if (transfer != null && !transfer.isEmpty()
                    && transfer.get(0).toLowerCase(Locale.ROOT).contains("chunked")) {
                body = readChunked(in, maxBytes);
            } else if (length != null) {
                try {
                    long declaredLength = Long.parseLong(length.trim());
                    // 声明就超：别开始读（它是对方说的，仍要流式兜底）
                    if (declaredLength > maxBytes) {
                        throw tooLarge(maxBytes);
                    }
                    body = readExactly(in, declaredLength, maxBytes);
                } catch (NumberFormatException e) {
                    body = readToEnd(in, maxBytes);
                }
            } else {
                body = readToEnd(in, maxBytes);
            }
            return new Response(status, headers, body);
        }

        private static String headerOf(Map<String, List<String>> headers, String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        private String requestTarget(URI uri) {
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            return uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
        }

        private String hostHeader(URI uri) {
            return hostHeaderValue(uri);
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
            int prev = -1;
            for (int c = in.read(); c != -1; c = in.read()) {
                if (prev == '\r' && c == '\n') {
                    // CR 从未写入缓冲（上一轮只把 prev 记成 CR），整段直接就是行内容
                    return buf.toString(StandardCharsets.ISO_8859_1);
                }
                if (prev != -1) {
                    buf.write(prev);
                }
                prev = c;
            }
            if (prev != -1) {
                buf.write(prev);
            }
            return buf.size() == 0 ? null
                    : buf.toString(StandardCharsets.ISO_8859_1);
        }

        private static byte[] readExactly(InputStream in, long n, long maxBytes)
                throws IOException {
            if (n > maxBytes) {
                throw tooLarge(maxBytes);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long remaining = n;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) {
                    throw new EOFException("truncated body");
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        }

        static FetchException tooLarge(long maxBytes) {
            return new FetchException(FailureKind.TOO_LARGE,
                    "response is larger than " + maxBytes + " bytes");
        }

        private static byte[] readToEnd(InputStream in, long maxBytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes) {
                    throw tooLarge(maxBytes);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }

        private static byte[] readChunked(InputStream in, long maxBytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) {
                    throw new EOFException("truncated chunked body");
                }
                int semi = sizeLine.indexOf(';');
                String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
                long size;
                try {
                    size = Long.parseLong(hex, 16);
                } catch (NumberFormatException e) {
                    throw new FetchException(FailureKind.TRANSPORT,
                            "malformed chunk size: " + sizeLine);
                }
                if (size == 0) {
                    // 拖车头直到空行
                    String trailer;
                    while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                        // 丢弃拖车
                    }
                    return out.toByteArray();
                }
                // 块尺寸与累计都受 maxBytes 约束（size>Integer.MAX_VALUE 的边值一并拦下）
                if (size > maxBytes || out.size() + size > maxBytes) {
                    throw tooLarge(maxBytes);
                }
                byte[] chunk = readExactly(in, size, maxBytes);
                out.write(chunk, 0, chunk.length);
                readLine(in); // 块尾 CRLF
            }
        }
    }
}
