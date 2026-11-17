package tech.seexun.jknow.web.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp;

/**
 * WebDAV 连接器（任务 4）：multistatus 解析铁律（前缀任意/自闭合 collection/成对
 * propstat 空值不盖好值/未定义实体不掀列表/文本碎片拼完整 href）+ 百分号转义路径
 * （请求用原始转义、身份用解码、反代绝对 URL 逐段重编码）+ 上限（2000 文件截断、
 * 深度 8 防环）。fixture XML 逐条对齐旧仓库 webdav.rs:346-687 的测试面。
 */
class WebdavConnectorTests {

    // ---- 解析（webdav.rs:352-378 / :381-392 / :395-415 / :592-615 / :619-627） ----

    /** 同一属性被声明两次：200 给值、404 把没找到的再列一遍——空的那次不许盖掉好值。 */
    @Test
    void anEmptyRedeclarationDoesNotClobberAProperty() throws Exception {
        String paired = """
                <multistatus xmlns="DAV:"><response>
                    <href>/docs/a.txt</href>
                    <propstat><prop><getcontentlength>5</getcontentlength>
                      <getlastmodified>Wed, 02 Sep 2026 15:04:05 GMT</getlastmodified></prop>
                      <status>HTTP/1.1 200 OK</status></propstat>
                    <propstat><prop><getcontentlength></getcontentlength>
                      <getlastmodified></getlastmodified></prop>
                      <status>HTTP/1.1 404 Not Found</status></propstat>
                    </response></multistatus>""";
        for (String xml : List.of(paired,
                // 自闭合的那种写法本来就不带文本；两种都要过
                paired.replace("<getcontentlength></getcontentlength>", "<getcontentlength/>")
                        .replace("<getlastmodified></getlastmodified>", "<getlastmodified/>"))) {
            List<WebdavConnector.Entry> entries = WebdavConnector.parseMultistatus(xml);
            assertEquals(1, entries.size());
            assertEquals(5, entries.get(0).len(), "空的那次把长度盖成了 0");
            assertNotNull(entries.get(0).modified(), "空的那次把时间盖没了");
        }
    }

    /** 认不出的实体只影响它自己那一条，不掀掉整张列表（预扫把未定义实体按原文保留）。 */
    @Test
    void anUnknownEntityDoesNotAbortTheListing() throws Exception {
        String xml = """
                <multistatus xmlns="DAV:">
                    <response><href>/docs/a&nbsp;b.txt</href><propstat><prop>
                      <getcontentlength>5</getcontentlength></prop></propstat></response>
                    <response><href>/docs/c.txt</href><propstat><prop>
                      <getcontentlength>7</getcontentlength></prop></propstat></response>
                    </multistatus>""";
        List<WebdavConnector.Entry> entries = WebdavConnector.parseMultistatus(xml);
        assertEquals(2, entries.size(), "一个怪实体不该让整张列表失败");
        assertEquals("/docs/a&nbsp;b.txt", entries.get(0).href(), "未定义实体按原文保留");
        assertEquals("/docs/c.txt", entries.get(1).href());
    }

    /** 实体/字符引用/CDATA 到 DOM 里都是解码后的文本——href 拼得完整。 */
    @Test
    void xmlTextFragmentsKeepTheCompleteHref() throws Exception {
        for (String href : List.of("/docs/A&amp;B.txt", "/docs/A&#38;B.txt", "/docs/A&#x26;B.txt")) {
            String xml = "<multistatus xmlns=\"DAV:\"><response><href>" + href + "</href>"
                    + "<propstat><prop><getcontentlength>&#53;</getcontentlength>"
                    + "<getlastmodified>Wed, 02 Sep 2026 15:04:05 GMT</getlastmodified>"
                    + "</prop></propstat></response></multistatus>";
            List<WebdavConnector.Entry> entries = WebdavConnector.parseMultistatus(xml);
            assertEquals("/docs/A&B.txt", entries.get(0).href(), href);
            assertEquals(5, entries.get(0).len());
            assertNotNull(entries.get(0).modified());
        }
    }

    /** 命名空间前缀任意（D:/ns0:），换前缀解出同样的东西——「同步成功零文件」最难查。 */
    @Test
    void anyNamespacePrefixParsesTheSame() throws Exception {
        String withD = """
                <?xml version="1.0"?>
                <D:multistatus xmlns:D="DAV:"><D:response>
                  <D:href>/docs/a.txt</D:href>
                  <D:propstat><D:prop>
                    <D:getcontentlength>5</D:getcontentlength>
                    <D:getlastmodified>Wed, 02 Sep 2026 15:04:05 GMT</D:getlastmodified>
                    <D:resourcetype/>
                  </D:prop></D:propstat>
                </D:response></D:multistatus>""";
        List<WebdavConnector.Entry> a = WebdavConnector.parseMultistatus(withD);
        List<WebdavConnector.Entry> b =
                WebdavConnector.parseMultistatus(withD.replace("D:", "ns0:"));
        assertEquals(a, b, "换个前缀就解不出来了");
        assertEquals(1, a.size());
        assertEquals("/docs/a.txt", a.get(0).href());
        assertEquals(5, a.get(0).len());
        assertFalse(a.get(0).isDir());
        assertNotNull(a.get(0).modified(), "getlastmodified 是 doc_time 的来源");
    }

    /** 目录判据是 collection 存在（自闭合标签），不是 href 以 / 结尾。 */
    @Test
    void aSelfClosingCollectionIsStillADirectory() throws Exception {
        String xml = """
                <multistatus xmlns="DAV:"><response>
                  <href>/docs/</href>
                  <propstat><prop><resourcetype><collection/></resourcetype></prop></propstat>
                </response></multistatus>""";
        assertTrue(WebdavConnector.parseMultistatus(xml).get(0).isDir(),
                "自闭合的 collection 没被认出来");
    }

    /** 实测有服务端写大写标签名——本地名小写化处理。 */
    @Test
    void aCapitalisedGetLastModifiedStillParses() throws Exception {
        String xml = "<multistatus xmlns=\"DAV:\"><response><href>/a.txt</href>"
                + "<propstat><prop><GetContentLength>5</GetContentLength>"
                + "<getLastmodified>Wed, 02 Sep 2026 15:04:05 GMT</getLastmodified>"
                + "</prop></propstat></response></multistatus>";
        List<WebdavConnector.Entry> entries = WebdavConnector.parseMultistatus(xml);
        assertEquals(5, entries.get(0).len());
        assertNotNull(entries.get(0).modified());
    }

    // ---- 路径工具（webdav.rs:631-651） ----

    @Test
    void aPercentEncodedHrefIsDecodedForIdentity() {
        assertEquals("/docs/中文.txt",
                WebdavConnector.percentDecode("/docs/%E4%B8%AD%E6%96%87.txt"));
        assertEquals("/a%ZZb", WebdavConnector.percentDecode("/a%ZZb"), "非法序列原样留着，不猜");
    }

    @Test
    void pathsNormaliseToOneShape() {
        assertEquals("/docs", WebdavConnector.normalize("docs"));
        assertEquals("/docs", WebdavConnector.normalize("/docs/"));
        assertEquals("/", WebdavConnector.normalize("/"));
        assertEquals("/", WebdavConnector.normalize(""));
    }

    @Test
    void documentPathHandlesAbsoluteAndProxyHrefs() {
        // 同源绝对 URL：剥 base 后解码
        assertEquals("/docs/a.txt",
                WebdavConnector.documentPath("http://127.0.0.1:1/docs/a.txt",
                        "http://127.0.0.1:1"));
        // 反代改写过的绝对 URL：逐段解码再重编码——'#' 不截断、字面 '%' 可区分
        assertEquals("/docs/plan%23a.txt",
                WebdavConnector.documentPath("https://proxy.example/docs/plan%23a.txt",
                        "http://127.0.0.1:1"));
        assertEquals("/docs/literal%2523.txt",
                WebdavConnector.documentPath("https://proxy.example/docs/literal%2523.txt",
                        "http://127.0.0.1:1"));
        // 相对 href：直接解码
        assertEquals("/docs/中文.txt",
                WebdavConnector.documentPath("/docs/%E4%B8%AD%E6%96%87.txt", "http://x"));
    }

    // ---- HTTP 面真走一遍（webdav.rs:417-587 的本地复刻） ----

    @Test
    void anXmlEscapedFilenameIsFetchedFromItsFullPath() throws Exception {
        String xml = "<multistatus xmlns=\"DAV:\"><response><href>/docs/A&amp;B.txt</href>"
                + "<propstat><prop><getcontentlength>5</getcontentlength>"
                + "</prop></propstat></response></multistatus>";
        withServer((method, target) -> {
            if ("PROPFIND".equals(method) && target.equals("/docs")) {
                return response(207, xml);
            }
            if ("GET".equals(method) && target.equals("/docs/A&B.txt")) {
                return response(200, "hello");
            }
            return response(404, "");
        }, base -> {
            WebdavConnector.WebdavResult result =
                    WebdavConnector.fetch(HttpClientHolder.of(base), base, "/docs", null, null);
            assertFalse(result.truncated());
            assertEquals(1, result.files().size(), "转义过的 href 不能被静默跳过");
            assertEquals("A&B.txt", result.files().get(0).filename());
            assertEquals("hello", new String(result.files().get(0).bytes(), StandardCharsets.UTF_8));
            assertTrue(result.files().get(0).externalKey().endsWith("/docs/A&B.txt"));
        });
    }

    /** 转义 href：请求用原始转义（%23 不变 '#'），身份/文件名用解码值。 */
    @Test
    void percentEncodedFilenamesUseTheOriginalRequestPath() throws Exception {
        record FileCase(String path, String decodedName, String requestPath) {
        }
        List<FileCase> files = List.of(
                new FileCase("/docs/plan%23a.txt", "plan#a.txt", "/docs/plan%23a.txt"),
                new FileCase("/docs/literal%2523.txt", "literal%23.txt", "/docs/literal%2523.txt"),
                new FileCase("/docs/%E4%B8%AD%E6%96%87%20notes.txt", "中文 notes.txt",
                        "/docs/%E4%B8%AD%E6%96%87%20notes.txt"));
        StringBuilder entries = new StringBuilder();
        for (FileCase f : files) {
            entries.append("<response><href>").append(f.path())
                    .append("</href><propstat><prop><getcontentlength>5</getcontentlength>")
                    .append("</prop></propstat></response>");
        }
        String xml = "<multistatus xmlns=\"DAV:\">" + entries + "</multistatus>";
        withServer((method, target) -> {
            if ("PROPFIND".equals(method) && target.equals("/docs")) {
                return response(207, xml);
            }
            if ("GET".equals(method) && files.stream().anyMatch(f -> f.path().equals(target))) {
                return response(200, target);
            }
            return response(404, "");
        }, base -> {
            WebdavConnector.WebdavResult result =
                    WebdavConnector.fetch(HttpClientHolder.of(base), base, "/docs", null, null);
            assertFalse(result.truncated());
            assertEquals(files.size(), result.files().size());
            for (int i = 0; i < files.size(); i++) {
                WebdavConnector.RemoteFile file = result.files().get(i);
                FileCase want = files.get(i);
                // 请求路径原样发出 → 服务器看到的是转义形态，body 即证词
                assertEquals(want.path(), new String(file.bytes(), StandardCharsets.UTF_8));
                assertEquals(want.decodedName(), file.filename());
                assertEquals("webdav://127.0.0.1/docs/" + want.decodedName(), file.externalKey());
            }
        });
    }

    /** 转义目录：下层遍历沿用该目录自己的原始转义请求路径。 */
    @Test
    void percentEncodedDirectoriesKeepTheirRequestPathWhenTraversed() throws Exception {
        String dir = "/docs/folder%23%3F";
        String file = "/docs/folder%23%3F/note%23one.txt";
        String collection = "<response><href>" + dir + "</href><propstat><prop><resourcetype>"
                + "<collection/></resourcetype></prop></propstat></response>";
        String root = "<multistatus xmlns=\"DAV:\">" + collection + "</multistatus>";
        String nested = "<multistatus xmlns=\"DAV:\">" + collection
                + "<response><href>" + file + "</href><propstat><prop>"
                + "<getcontentlength>5</getcontentlength></prop></propstat></response>"
                + "</multistatus>";
        AtomicInteger propfinds = new AtomicInteger();
        ConcurrentHashMap<String, Integer> seen = new ConcurrentHashMap<>();
        withServer((method, target) -> {
            seen.merge(method + " " + target, 1, Integer::sum);
            if ("PROPFIND".equals(method) && target.equals("/docs")) {
                return response(207, root);
            }
            if ("PROPFIND".equals(method) && target.equals(dir)) {
                return response(207, nested);
            }
            if ("GET".equals(method) && target.equals(file)) {
                return response(200, "hello");
            }
            return response(404, "");
        }, base -> {
            WebdavConnector.WebdavResult result =
                    WebdavConnector.fetch(HttpClientHolder.of(base), base, "/docs", null, null);
            assertFalse(result.truncated());
            assertEquals(1, result.files().size());
            assertEquals("hello", new String(result.files().get(0).bytes(),
                    StandardCharsets.UTF_8));
            assertEquals("note#one.txt", result.files().get(0).filename());
            assertEquals("webdav://127.0.0.1/docs/folder#?/note#one.txt",
                    result.files().get(0).externalKey());
            assertEquals(1, seen.getOrDefault("PROPFIND /docs", 0));
            assertEquals(1, seen.getOrDefault("PROPFIND " + dir, 0));
            assertEquals(1, seen.getOrDefault("GET " + file, 0));
            assertNull(seen.get("GET /docs/folder#?/note#one.txt"),
                    "解码形态的路径绝不能发出去");
        });
    }

    /** 深度上限防环：自指的目录链到第 8 层为止，不再往下。 */
    @Test
    void theDepthCapStopsCyclicDirectories() throws Exception {
        AtomicInteger propfinds = new AtomicInteger();
        withServer((method, target) -> {
            if (!"PROPFIND".equals(method)) {
                return response(404, "");
            }
            propfinds.incrementAndGet();
            if (target.equals("/docs")) {
                return response(207, "<multistatus xmlns=\"DAV:\"><response><href>/docs/l1</href>"
                        + "<propstat><prop><resourcetype><collection/></resourcetype>"
                        + "</prop></propstat></response></multistatus>");
            }
            // 任意 /docs/lN → 列出 /docs/l{N+1}：无限深
            int depth = Integer.parseInt(target.substring("/docs/l".length()));
            return response(207, "<multistatus xmlns=\"DAV:\"><response><href>/docs/l"
                    + (depth + 1) + "</href><propstat><prop><resourcetype><collection/>"
                    + "</resourcetype></prop></propstat></response></multistatus>");
        }, base -> {
            WebdavConnector.WebdavResult result =
                    WebdavConnector.fetch(HttpClientHolder.of(base), base, "/docs", null, null);
            assertTrue(result.files().isEmpty());
            assertEquals(9, propfinds.get(), "根+l1..l8 共 9 次 PROPFIND；l9 超深不再请求");
        });
    }

    /** 条数上限 2000：第 2001 个触发截断标志。 */
    @Test
    void theFileCapTruncatesTheSync() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < WebdavConnector.MAX_FILES_PER_SYNC + 5; i++) {
            entries.append("<response><href>/docs/f").append(i)
                    .append(".txt</href><propstat><prop><getcontentlength>1</getcontentlength>")
                    .append("</prop></propstat></response>");
        }
        String xml = "<multistatus xmlns=\"DAV:\">" + entries + "</multistatus>";
        AtomicInteger gets = new AtomicInteger();
        withServer((method, target) -> {
            if ("PROPFIND".equals(method)) {
                return response(207, xml);
            }
            if ("GET".equals(method) && target.startsWith("/docs/f")) {
                gets.incrementAndGet();
                return response(200, "x");
            }
            return response(404, "");
        }, base -> {
            WebdavConnector.WebdavResult result =
                    WebdavConnector.fetch(HttpClientHolder.of(base), base, "/docs", null, null);
            assertTrue(result.truncated());
            assertEquals(WebdavConnector.MAX_FILES_PER_SYNC, result.files().size());
            assertEquals(WebdavConnector.MAX_FILES_PER_SYNC, gets.get());
            assertEquals("webdav://127.0.0.1/docs/f0.txt", result.files().get(0).externalKey());
        });
    }

    /** Basic 认证随 PROPFIND/GET 一起发。 */
    @Test
    void basicAuthIsSentOnBothVerbs() throws Exception {
        String xml = "<multistatus xmlns=\"DAV:\"><response><href>/a.txt</href><propstat><prop>"
                + "<getcontentlength>5</getcontentlength></prop></propstat></response>"
                + "</multistatus>";
        ConcurrentHashMap<String, String> authSeen = new ConcurrentHashMap<>();
        withServer((method, target) -> {
            authSeen.put(method, authHeaderOfCurrent);
            if ("PROPFIND".equals(method)) {
                return response(207, xml);
            }
            return "GET".equals(method) ? response(200, "hello") : response(404, "");
        }, base -> {
            WebdavConnector.fetch(HttpClientHolder.of(base), base, "/", "u", "p");
            String expected = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authSeen.get("PROPFIND"));
            assertEquals(expected, authSeen.get("GET"));
        });
    }

    // ---- 本地 HttpServer 脚手架 ----

    /** M3 任务 9 起连接器走受管客户端：对 stub 端点按 Operator 档建（地址由测试自己填）。 */
    private static final class HttpClientHolder {
        static GuardedHttp.GuardedClient of(String base) throws Exception {
            return GuardedHttp.clientFor(java.net.URI.create(base), GuardedHttp.Reach.OPERATOR,
                    GuardedHttp.limitsWithMaxBytes(GuardedHttp.Limits.defaults(),
                            WebdavConnector.MAX_FILE_BYTES));
        }
    }

    /** withServer 递交给 handler 的当前请求 Authorization 头（线程可见性由 CHM 落账保证）。 */
    private static volatile String authHeaderOfCurrent;

    private interface Handler extends BiFunction<String, String, Response> {
    }

    private record Response(int status, String body) {
    }

    private static Response response(int status, String body) {
        return new Response(status, body);
    }

    private void withServer(Handler handler, ThrowingConsumer<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            String method = exchange.getRequestMethod();
            String target = exchange.getRequestURI().getRawPath();
            authHeaderOfCurrent = exchange.getRequestHeaders().getFirst("Authorization");
            Response resp = handler.apply(method, target);
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(resp.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                exchange.close();
            }
        });
        server.start();
        try {
            body.accept("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
