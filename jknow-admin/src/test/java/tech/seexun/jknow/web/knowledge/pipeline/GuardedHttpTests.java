package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.FailureKind;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.FetchException;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Limits;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Reach;

/**
 * SSRF 防护矩阵（http_fetch.rs:444-647 mod tests 全量移植 + rss_full_content.rs 的
 * URL 形状门；本地 stub 用 JDK HttpServer，与 Rust wiremock 同一职责）。
 *
 * <p>纯函数面（is_public_ip / validate_resolved / check_hop / parse）直接断言；真网络面
 * 起本机 stub——回环是<b>私有地址</b>，正好卡在两档 Reach 的分界上：内容给的地址够不着
 * 它，操作者填的够得着。
 */
class GuardedHttpTests {

    private HttpServer server;

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    private static FailureKind kindOf(ThrowingCall call) {
        try {
            call.run();
        } catch (FetchException e) {
            return e.kind;
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e, e);
        }
        throw new AssertionError("expected failure");
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    /** Rust assert!(...is_err()) 的对等：任何 FetchException 都算拒收。 */
    private static boolean isRefused(ThrowingCall call) {
        try {
            call.run();
        } catch (FetchException e) {
            return true;
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e, e);
        }
        return false;
    }

    // ---- 地址判定矩阵（http_fetch.rs:543-578 逐字） ----

    /** 云上的元数据服务 169.254.169.254 在列——这条路最常被人走。 */
    @Test
    void theAddressesAFetchMustRefuse() {
        for (String blocked : new String[] {
                "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.5",
                "169.254.169.254", "100.64.0.1", "192.0.0.1", "198.18.0.1", "224.0.0.1",
                "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
                "2002:7f00:1::"}) {
            assertFalse(GuardedHttp.isPublicIp(ip(blocked)), blocked + " 该被拦下");
        }
    }

    @Test
    void theAddressesAFetchMustStillAllow() {
        for (String allowed : new String[] {
                "1.1.1.1", "8.8.8.8", "192.0.1.1", "192.88.98.1", "2606:4700::1111",
                "2002:0808:0808::"}) {
            assertTrue(GuardedHttp.isPublicIp(ip(allowed)), allowed + " 不该被拦");
        }
    }

    /** RSS 特殊 v6 段（http_fetch.rs:530-540）：本地用途 NAT64 / Teredo / 基准 / 新文档段。 */
    @Test
    void contentReachPreservesRssSpecialIpv6Exclusions() {
        for (String address : new String[] {"64:ff9b:1::808:808", "2001::1", "2001:10::1",
                "2001:2::1", "3fff::1"}) {
            assertFalse(GuardedHttp.isPublicIp(ip(address)), address);
        }
    }

    /** 一个不合格就整体拒绝，不是挑公网的连（http_fetch.rs:461-470）。 */
    @Test
    void mixedDnsAnswersFailClosedEvenWhenOneAddressIsPublic() {
        List<InetAddress> addresses = List.of(ip("93.184.216.34"), ip("192.168.1.10"));
        assertEquals(FailureKind.BLOCKED_ADDRESS,
                kindOf(() -> GuardedHttp.validateResolved(addresses, Reach.CONTENT, "example.com")));
    }

    // ---- Content 档 URL 形状门（http_fetch.rs:453-458 / rss_full_content.rs:663-738） ----

    /** URL 解析会吞掉裸 userinfo——先在原文里找 @（http_fetch.rs:453-458）。 */
    @Test
    void contentUrlsRejectEmptyUserinfoBeforeParsingErasesIt() {
        assertEquals(FailureKind.INVALID_URL,
                kindOf(() -> GuardedHttp.get("https://@127.0.0.1/", Reach.CONTENT,
                        Limits.defaults())));
    }

    /** 危险形状：非 http(s) / userinfo / 非默认端口 / fragment / 私网字面量。 */
    @Test
    void articleUrlPolicyRejectsUnsafeShapes() {
        for (String raw : new String[] {
                "file:///etc/passwd", "ftp://example.com/article",
                "https://user:pass@example.com/article", "https://@example.com/article",
                "https://example.com:8443/article", "https://example.com/article#fragment",
                "http://127.0.0.1/article", "http://[::1]/article"}) {
            // Rust 断 is_err() 不锁变体：file:// 是 UnsupportedScheme、127.0.0.1 是
            // BlockedAddress——被拒即可（两种 FetchError 在调用方 From 映射里同走 InvalidUrl）
            assertTrue(isRefused(() -> GuardedHttp.validateContentUrl(raw)),
                    "unsafe URL was accepted: " + raw);
        }
        assertDoesNotThrow(() -> GuardedHttp.validateContentUrl("https://example.com/article"));
    }

    /** 特殊与内嵌地址（rss_full_content.rs:719-739）：CGNAT/链路本地/NAT64 裹私网/6to4 裹私网。 */
    @Test
    void articleUrlPolicyBlocksSpecialAndEmbeddedAddresses() {
        for (String raw : new String[] {
                "http://10.0.0.1/article", "http://100.64.0.1/article",
                "http://169.254.1.1/article", "http://192.168.1.1/article",
                "http://224.0.0.1/article", "http://[::]/article", "http://[fc00::1]/article",
                "http://[fe80::1]/article", "http://[::ffff:192.168.1.1]/article",
                "http://[64:ff9b::c0a8:0101]/article", "http://[2002:c0a8:0101::]/article"}) {
            assertEquals(FailureKind.BLOCKED_ADDRESS,
                    kindOf(() -> GuardedHttp.validateContentUrl(raw)),
                    "special address was accepted: " + raw);
        }
        assertDoesNotThrow(() -> GuardedHttp.validateContentUrl(
                "http://[2001:4860:4860::8888]/article"));
    }

    /** 私有 NAT64 前缀 64:ff9b:1::/48（rss_full_content.rs:831-835）。 */
    @Test
    void privateNat64PrefixIsBlocked() {
        assertEquals(FailureKind.BLOCKED_ADDRESS,
                kindOf(() -> GuardedHttp.validateContentUrl("http://[64:ff9b:1::1]/article")));
    }

    // ---- scheme 门（http_fetch.rs:641-646） ----

    @Test
    void onlyHttpAndHttpsAreFetched() {
        assertTrue(isRefused(() -> GuardedHttp.parse("file:///etc/passwd")));
        assertTrue(isRefused(() -> GuardedHttp.parse("gopher://example.com/")));
        assertDoesNotThrow(() -> GuardedHttp.parse("https://example.com/a"));
    }

    // ---- 重定向边界（http_fetch.rs:472-496） ----

    /** Content 档：降级 / 私网 / 非默认端口 / userinfo / fragment / ftp 全拒；同形放行。 */
    @Test
    void contentRedirectsPreserveTheRssBoundary() throws Exception {
        URI from = URI.create("https://8.8.8.8/article");
        for (String target : new String[] {
                "http://8.8.8.8/final", "https://127.0.0.1/admin", "https://[::1]/",
                "https://8.8.8.8:8443/", "https://user:pass@8.8.8.8/", "https://8.8.8.8/#fragment",
                "ftp://8.8.8.8/"}) {
            // Rust 断 is_err()：降级那跳是 BlockedRedirect、私网那跳是 InvalidUrl/BlockedAddress
            assertTrue(isRefused(() -> GuardedHttp.checkHop(from, URI.create(target),
                    Reach.CONTENT)), target);
        }
        GuardedHttp.checkHop(from, from.resolve("/final"), Reach.CONTENT);
    }

    /** Operator 档：公网来源不许跳进私网（http_fetch.rs:226-247「不许升级可达范围」）。 */
    @Test
    void operatorRedirectsMustNotReachPrivateNetworkFromAPublicHost() {
        URI from = URI.create("https://8.8.8.8/article");
        // 公网 → 环回/私网/元数据端点：拒
        for (String target : new String[] {"http://127.0.0.1/x", "http://10.1.2.3/x",
                "http://192.168.1.5/x", "http://169.254.169.254/latest/meta-data/",
                "https://[fd00::1]/"}) {
            assertEquals(FailureKind.BLOCKED_REDIRECT,
                    kindOf(() -> GuardedHttp.checkHop(from, URI.create(target), Reach.OPERATOR)),
                    "public host must not redirect to " + target);
        }
        // 私网 → 私网（自部署内部跳转）：放行；https 降级 http 仍拒
        assertDoesNotThrow(() -> GuardedHttp.checkHop(URI.create("http://10.0.0.5/wiki"),
                URI.create("http://127.0.0.1:8080/wiki"), Reach.OPERATOR));
        assertEquals(FailureKind.BLOCKED_REDIRECT,
                kindOf(() -> GuardedHttp.checkHop(URI.create("https://10.0.0.5/wiki"),
                        URI.create("http://10.0.0.5/wiki"), Reach.OPERATOR)));
    }

    // ---- 真网络面（本地 stub） ----

    /** 回环只有人填的地址才够得着（http_fetch.rs:580-608）。 */
    @Test
    void aLoopbackHostIsReachableOnlyWhenAPersonTypedIt() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/page", exchange -> {
            byte[] out = "<html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/page";

        // feed 里的一个链接指向回环——正是这条路要拦的东西
        assertEquals(FailureKind.INVALID_URL,
                kindOf(() -> GuardedHttp.get(url, Reach.CONTENT, Limits.defaults())),
                "内容给出的地址不该够得到回环");

        // 同一个地址，来源是这个库的人自己填的：内部服务本来就是正当目标
        GuardedHttp.Fetched page = GuardedHttp.get(url, Reach.OPERATOR, Limits.defaults());
        assertEquals("text/html", page.mime());
        assertTrue(new String(page.bytes(), StandardCharsets.UTF_8).contains("hello"));
    }

    /** 响应体超上限拒收（http_fetch.rs:610-624）。 */
    @Test
    void aBodyLargerThanTheCapIsRefused() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] out = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        Limits limits = GuardedHttp.limitsWithMaxBytes(Limits.defaults(), 1024);
        assertEquals(FailureKind.TOO_LARGE,
                kindOf(() -> GuardedHttp.get("http://localhost:" + server.getAddress().getPort()
                        + "/", Reach.OPERATOR, limits)));
    }

    /** 重定向链有终点（http_fetch.rs:626-639）。 */
    @Test
    void aRedirectChainEndsInsteadOfLooping() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Integer> hits = new AtomicReference<>(0);
        server.createContext("/", exchange -> {
            hits.updateAndGet(n -> n + 1);
            exchange.getResponseHeaders().set("Location", "/next");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        Limits limits = new Limits(Limits.defaults().maxBytes(), 2, Limits.defaults().dnsTimeout(),
                Limits.defaults().connectTimeout(), Limits.defaults().readTimeout(),
                Limits.defaults().overallTimeout());
        assertEquals(FailureKind.REDIRECT_LIMIT,
                kindOf(() -> GuardedHttp.get("http://localhost:" + server.getAddress().getPort()
                        + "/", Reach.OPERATOR, limits)));
    }

    /** 公网主机把第二跳指进私网：真网络形态的拒收（重定向逐跳复检）。 */
    @Test
    void aPublicLiteralHostRedirectingToLoopbackIsRefusedForOperator() throws Exception {
        // 公网字面量主机自己起不了服务，这里只验「location 指回环」那一跳的判定，
        // 与 checkHop 的矩阵互补：拿本机 stub 当私网目标，公网来源用字面量
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/inner");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        // from=公网字面量 8.8.8.8（无法真连）→ 用 checkHop 判那一跳即可
        assertEquals(FailureKind.BLOCKED_REDIRECT,
                kindOf(() -> GuardedHttp.checkHop(URI.create("https://8.8.8.8/article"),
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/inner"),
                        Reach.OPERATOR)));
    }

    /** 读超时与总时限都要生效（http_fetch.rs:498-527；408 或传输超时皆可）。 */
    @Test
    void sharedFetchObeysReadAndOverallDeadlines() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/";
        for (Limits limits : new Limits[] {
                new Limits(Limits.defaults().maxBytes(), Limits.defaults().maxRedirects(),
                        Limits.defaults().dnsTimeout(), Limits.defaults().connectTimeout(),
                        Duration.ofMillis(20), Limits.defaults().overallTimeout()),
                new Limits(Limits.defaults().maxBytes(), Limits.defaults().maxRedirects(),
                        Limits.defaults().dnsTimeout(), Limits.defaults().connectTimeout(),
                        Limits.defaults().readTimeout(), Duration.ofMillis(20))}) {
            FetchException e = (FetchException) assertDoesNotThrow(() -> {
                try {
                    GuardedHttp.get(url, Reach.OPERATOR, limits);
                } catch (FetchException fe) {
                    return fe;
                } catch (IOException io) {
                    return new FetchException(FailureKind.TRANSPORT, io.getMessage());
                }
                return null;
            }, "limits=" + limits);
            assertTrue(e != null && (e.kind == FailureKind.STATUS && e.status == 408
                    || e.kind == FailureKind.TRANSPORT),
                    "expected timeout-ish failure, got " + (e == null ? "success" : e.kind));
        }
    }

    /** I-2：无 Content-Length 的 chunked 流要边读边拦——超限即抛，不得先吃穿堆。 */
    @Test
    void aChunkedStreamLargerThanTheCapIsRefusedWhileReading() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            // sendResponseHeaders(200, 0) = 分块传输（无 Content-Length 可预先声明）
            exchange.sendResponseHeaders(200, 0);
            java.io.OutputStream out = exchange.getResponseBody();
            byte[] chunk = new byte[1024];
            for (int i = 0; i < 64; i++) {
                out.write(chunk);
                out.flush();
            }
            out.close();
        });
        server.start();
        GuardedHttp.Limits limits = GuardedHttp.limitsWithMaxBytes(GuardedHttp.Limits.defaults(),
                4096);
        GuardedHttp.FetchException e = assertThrows(GuardedHttp.FetchException.class,
                () -> GuardedHttp.get("http://localhost:" + server.getAddress().getPort() + "/",
                        Reach.OPERATOR, limits));
        assertEquals(FailureKind.TOO_LARGE, e.kind, "读路径流式拦截，不是事后体检");
    }

    /** I-2 底座面：GuardedClient 的读路径同样受 maxBytes（对象下载 32MiB 档）。 */
    @Test
    void guardedClientReadPathEnforcesTheCap() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] out = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            java.io.OutputStream body = exchange.getResponseBody();
            body.write(out);
            body.close();
        });
        server.start();
        GuardedHttp.GuardedClient client = GuardedHttp.clientFor(java.net.URI.create(
                        "http://localhost:" + server.getAddress().getPort() + "/"),
                Reach.OPERATOR, GuardedHttp.limitsWithMaxBytes(GuardedHttp.Limits.defaults(),
                        1024));
        GuardedHttp.FetchException e = assertThrows(GuardedHttp.FetchException.class,
                () -> client.get(java.net.URI.create(
                        "http://localhost:" + server.getAddress().getPort() + "/blob")));
        assertEquals(FailureKind.TOO_LARGE, e.kind);
    }

    /** GuardedClient 也能走通真请求：GET 带认证头、POST JSON（连接器共用的底座）。 */
    @Test
    void guardedClientServesGetAndPostJson() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("POST".equals(exchange.getRequestMethod())) {
                seenBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
            }
            byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        GuardedHttp.GuardedClient client = GuardedHttp.clientFor(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/"),
                Reach.OPERATOR, Limits.defaults());
        GuardedHttp.GuardedClient.Response get = client.get(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/x"),
                "Authorization", "Bearer t");
        assertEquals(200, get.status());
        assertEquals("Bearer t", seenAuth.get());
        assertTrue(get.header("content-type").contains("json"));

        GuardedHttp.GuardedClient.Response post = client.postJson(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/y"),
                "{\"q\":\"x\"}");
        assertEquals(200, post.status());
        assertEquals("{\"q\":\"x\"}", seenBody.get());
    }
}
