package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.pipeline.ObjectStorage.RemoteObject;
import tech.seexun.jknow.web.knowledge.pipeline.ObjectStorage.Store;

/**
 * 对象存储连接器（object_storage.rs 的可脚本化面——真云连接测试在 Rust 侧按环境变量
 * 门控，Java 侧任务 9 口径改为<b>脚本化端点</b>：S3 List API v2 / Azure List Blobs /
 * GCS XML 列表的线上形状逐字回放；SigV4/SharedKey 签名头在场即可验）。
 */
class ObjectStorageTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final Map<String, String> authSeen = new ConcurrentHashMap<>();

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static String s3List(boolean truncated, String nextToken, Object... keys) {
        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Name>docs</Name><Prefix>docs</Prefix><KeyCount>" + (keys.length / 3)
                + "</KeyCount>"
                + "<IsTruncated>" + truncated + "</IsTruncated>");
        if (nextToken != null) {
            xml.append("<NextContinuationToken>").append(nextToken)
                    .append("</NextContinuationToken>");
        }
        for (int i = 0; i + 2 < keys.length; i += 3) {
            xml.append("<Contents><Key>").append(keys[i]).append("</Key><LastModified>")
                    .append(keys[i + 1]).append("</LastModified><Size>").append(keys[i + 2])
                    .append("</Size></Contents>");
        }
        return xml.append("</ListBucketResult>").toString();
    }

    // ---- I-1：GCS XML API 的 marker/NextMarker 翻页（不是 S3 的 continuation） ----

    /** GCS 翻页：发 marker=、读 NextMarker；有 token 仍翻不完要报 truncated。 */
    @Test
    void gcsPaginatesWithMarkerAndNextMarker() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        final int[] page = {0};
        server.createContext("/docs", exchange -> {
            String query = exchange.getRequestURI().getQuery() == null ? ""
                    : exchange.getRequestURI().getQuery();
            if (page[0] == 0) {
                page[0] = 1;
                assertFalse(query.contains("continuation-token="), "GCS 不是 S3 的翻页协议: "
                        + query);
                assertFalse(query.contains("marker="), "首页不带 marker");
                respond(exchange, 200, "application/xml",
                        gcsList(true, "mark-2", "p/one", "2026-01-01T00:00:00.000Z", 3));
                return;
            }
            assertTrue(query.contains("marker=mark-2"), "第二页要带 marker=mark-2: " + query);
            respond(exchange, 200, "application/xml",
                    gcsList(false, null, "p/two", "2026-01-01T00:00:01.000Z", 4));
        });
        server.createContext("/docs/p/one", exchange ->
                respond(exchange, 200, "application/octet-stream", "one"));
        server.createContext("/docs/p/two", exchange ->
                respond(exchange, 200, "application/octet-stream", "two"));
        server.start();
        Store store = ObjectStorage.client("gcs", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        Store.ListResult listing = store.list("docs", "p");
        assertEquals(2, listing.objects().size());
        assertFalse(listing.truncated(), "第二页完整收尾");
        // 列表条目是裸 key（bucket 前缀在 fetch() 拼 external_key 时才加）
        assertEquals(List.of("p/one", "p/two"),
                listing.objects().stream().map(m -> m.key()).toList());
    }

    /** 列举预算用尽仍有 NextMarker → truncated 必须报出来（>1000 对象的桶不静默截断）。 */
    @Test
    void gcsListingThatNeverFinishesReportsTruncation() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docs", exchange -> respond(exchange, 200, "application/xml",
                gcsList(true, "more")));
        server.start();
        Store store = ObjectStorage.client("gcs", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        assertTrue(store.list("docs", "p").truncated(),
                "翻页预算用尽仍有 NextMarker = 还有没列完的");
    }

    /** GCS XML List 形状（marker 协议；含 NextMarker）。 */
    private static String gcsList(boolean truncated, String nextMarker, Object... keys) {
        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://doc.s3.amazonaws.com/2006-03-01\">"
                + "<Name>docs</Name><Prefix>p</Prefix>"
                + "<IsTruncated>" + truncated + "</IsTruncated>");
        if (nextMarker != null) {
            xml.append("<NextMarker>").append(nextMarker).append("</NextMarker>");
        }
        for (int i = 0; i + 2 < keys.length; i += 3) {
            xml.append("<Contents><Key>").append(keys[i]).append("</Key><LastModified>")
                    .append(keys[i + 1]).append("</LastModified><Size>").append(keys[i + 2])
                    .append("</Size></Contents>");
        }
        return xml.append("</ListBucketResult>").toString();
    }

    // ---- C-2：线上请求只有一个 Host 头 ----

    @Test
    void s3RequestCarriesExactlyOneHostHeader() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        final java.util.concurrent.atomic.AtomicInteger hostHeaders = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/docs", exchange -> {
            if (exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("list-type=2")) {
                List<String> hosts = exchange.getRequestHeaders().get("Host");
                hostHeaders.set(hosts == null ? 0 : hosts.size());
                respond(exchange, 200, "application/xml", s3List(false, null));
                return;
            }
            respond(exchange, 404, "application/xml", "<Error/>");
        });
        server.start();
        Store store = ObjectStorage.client("s3", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        try {
            store.list("docs", null);
        } catch (Exception ignored) {
            // 404 也行——Host 断言在服务端已记录
        }
        assertEquals(1, hostHeaders.get(), "RFC 7230 §5.4：恰好一个 Host 头");
    }

    // ---- 建桶失败面（object_storage.rs:239-269） ----

    @Test
    void aMissingBucketSaysSo() throws Exception {
        try {
            ObjectStorage.client("s3", JSON.readTree("{\"region\":\"us-east-1\"}"));
            fail("缺 bucket 要报得出是缺哪一项");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("config.bucket"), e.getMessage());
        }
    }

    /** 自建端点建得出来（path-style / 明文 http 由配置说了算）。 */
    @Test
    void aSelfHostedEndpointBuilds() throws Exception {
        assertNotNull(ObjectStorage.client("s3", JSON.readTree("""
                {"bucket":"docs","endpoint":"http://127.0.0.1:9000","region":"us-east-1",
                 "access_key_id":"minioadmin","secret_access_key":"minioadmin"}
                """)));
        assertNotNull(ObjectStorage.client("azure_blob", JSON.readTree("""
                {"bucket":"docs","endpoint":"http://127.0.0.1:10000/devstoreaccount1",
                 "account_name":"a","account_key":"Kg=="}""")));
        // 公有云那条路：不给 endpoint 不需要本地端点
        assertNotNull(ObjectStorage.client("s3", JSON.readTree("""
                {"bucket":"docs","region":"ap-southeast-1"}""")));
    }

    // ---- S3 兼容端点：列 + 取 + 身份 + 占位对象（对照 MinIO 实测行为） ----

    @Test
    void s3ListFetchIdentityAndPlaceholderFiltering() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            authSeen.putIfAbsent("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            authSeen.putIfAbsent("amz-date", exchange.getRequestHeaders().getFirst("x-amz-date"));
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if (path.equals("/docs") && query != null && query.contains("list-type=2")) {
                respond(exchange, 200, "application/xml", s3List(false, null,
                        "docs/a.txt", "2026-01-02T03:04:05.678Z", 5,
                        "docs/b.md", "2026-01-02T03:04:06.678Z", 7,
                        // 目录占位对象：零字节——判据是大小不是名字
                        "docs/", "2026-01-01T00:00:00.000Z", 0,
                        // 超限对象：32MiB 之上不是文档是数据文件
                        "docs/huge.bin", "2026-01-02T03:04:07.678Z",
                        String.valueOf(ObjectStorage.MAX_OBJECT_BYTES + 1)));
                return;
            }
            if (path.equals("/docs/docs/a.txt")) {
                respond(exchange, 200, "application/octet-stream", "hello");
                return;
            }
            if (path.equals("/docs/docs/b.md")) {
                respond(exchange, 200, "application/octet-stream", "# world");
                return;
            }
            respond(exchange, 404, "application/xml", "<Error/>");
        });
        server.start();

        Store store = ObjectStorage.client("s3", JSON.readTree("""
                {"bucket":"docs","endpoint":"%s","region":"us-east-1",
                 "access_key_id":"u","secret_access_key":"p"}""".formatted(base())));
        ObjectStorage.FetchResult out = ObjectStorage.fetch("s3", store, "docs", "docs");

        assertFalse(out.truncated(), "四个对象不该触发上限");
        assertEquals(2, out.objects().size(), "占位与超限都该被滤掉："
                + out.objects().stream().map(RemoteObject::externalKey).toList());
        RemoteObject a = out.objects().stream()
                .filter(o -> "a.txt".equals(o.filename())).findFirst().orElseThrow();
        assertEquals("s3://docs/docs/a.txt", a.externalKey(), "身份=s3://bucket/key");
        assertEquals("hello", new String(a.bytes(), StandardCharsets.UTF_8));
        assertNotNull(a.lastModified(), "LastModified 是 doc_time 的唯一来源");
        // SigV4：请求带签名头（凭据给了就签；自建端点 path-style：/docs 不带虚拟主机）
        assertTrue(authSeen.get("auth").startsWith("AWS4-HMAC-SHA256 Credential=u/"),
                authSeen.get("auth"));
        assertTrue(authSeen.get("auth").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
        assertNotNull(authSeen.get("amz-date"));
    }

    /** 列举翻页（continuation-token 带上并继续；第二页完整收尾）。 */
    @Test
    void s3PaginationContinuesAndReportsTruncation() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        final int[] page = {0};
        server.createContext("/docs", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (page[0] == 0) {
                page[0] = 1;
                assertTrue(query.contains("list-type=2"));
                assertFalse(query.contains("continuation-token="));
                respond(exchange, 200, "application/xml",
                        s3List(true, "tok-2", "p/one", "2026-01-01T00:00:00.000Z", 3));
                return;
            }
            assertTrue(query.contains("continuation-token=tok-2"), query);
            respond(exchange, 200, "application/xml",
                    s3List(false, null, "p/two", "2026-01-01T00:00:01.000Z", 4));
        });
        server.createContext("/docs/p/one", exchange ->
                respond(exchange, 200, "application/octet-stream", "one"));
        server.createContext("/docs/p/two", exchange ->
                respond(exchange, 200, "application/octet-stream", "two"));
        server.start();
        Store store = ObjectStorage.client("s3", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        ObjectStorage.FetchResult out = ObjectStorage.fetch("s3", store, "docs", "p");
        assertEquals(2, out.objects().size());
        assertFalse(out.truncated(), "第二页完整收尾就不算截断");
        assertEquals(List.of("s3://docs/p/one", "s3://docs/p/two"),
                out.objects().stream().map(RemoteObject::externalKey).toList());
    }

    /** 列举预算用尽（有 token 仍翻不完）要报 truncated——「还有」必须看得见。 */
    @Test
    void aListingThatNeverFinishesReportsTruncation() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docs", exchange -> respond(exchange, 200, "application/xml",
                s3List(true, "more", (Object[]) new String[] {})));
        server.start();
        Store store = ObjectStorage.client("s3", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        Store.ListResult listing = store.list("docs", "p");
        assertTrue(listing.truncated(), "翻页预算用尽仍有 token = 还有没列完的");
    }

    /** 一个对象取不回来不带走整次同步（#214 的教训）。 */
    @Test
    void anUnreadableObjectSkipsInsteadOfFailingTheSync() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docs/x/ok", exchange ->
                respond(exchange, 200, "application/octet-stream", "ok"));
        server.createContext("/docs", exchange -> {
            if (exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("list-type=2")) {
                respond(exchange, 200, "application/xml", s3List(false, null,
                        "x/ok", "2026-01-01T00:00:00.000Z", 2,
                        "x/gone", "2026-01-01T00:00:00.000Z", 2));
                return;
            }
            respond(exchange, 404, "application/xml", "<Error/>");
        });
        server.start();
        Store store = ObjectStorage.client("s3", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        ObjectStorage.FetchResult out = ObjectStorage.fetch("s3", store, "docs", "x");
        assertEquals(1, out.objects().size(), "404 的那个跳过，别的一个不少");
        assertEquals("s3://docs/x/ok", out.objects().get(0).externalKey());
    }

    // ---- Azure Blob：另一套线上协议（EnumerationResults 形状 + SharedKey 头） ----

    @Test
    void azureBlobListUsesItsOwnProtocolAndIdentityScheme() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/docs", exchange -> {
            authSeen.putIfAbsent("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            authSeen.putIfAbsent("ms-date", exchange.getRequestHeaders().getFirst("x-ms-date"));
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query.contains("restype=container"), query);
            assertTrue(query.contains("comp=list"), query);
            assertTrue(query.contains("prefix=docs"), query);
            String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<EnumerationResults ServiceEndpoint=\"https://a.blob.core.windows.net/\" "
                    + "ContainerName=\"docs\"><Blobs>"
                    + "<Blob><Name>docs/a.txt</Name><Properties>"
                    + "<Last-Modified>Wed, 02 Sep 2026 15:04:05 GMT</Last-Modified>"
                    + "<Content-Length>17</Content-Length></Properties></Blob>"
                    + "</Blobs><NextMarker/></EnumerationResults>";
            respond(exchange, 200, "application/xml", xml);
        });
        server.createContext("/docs/docs/a.txt", exchange -> {
            authSeen.put("get-auth", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/octet-stream", "hello from azure");
        });
        server.start();

        Store store = ObjectStorage.client("azure_blob", JSON.readTree("""
                {"bucket":"docs","endpoint":"%s","account_name":"devstoreaccount1",
                 "account_key":"Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="}
                """.formatted(base())));
        ObjectStorage.FetchResult out = ObjectStorage.fetch("azure_blob", store, "docs", "docs");
        RemoteObject a = out.objects().stream()
                .filter(o -> "a.txt".equals(o.filename())).findFirst().orElseThrow();
        // 身份要带各家自己的 scheme：同名的桶在两家云上是两个东西
        assertEquals("azure://docs/docs/a.txt", a.externalKey());
        assertEquals("hello from azure", new String(a.bytes(), StandardCharsets.UTF_8));
        assertNotNull(a.lastModified());
        assertTrue(authSeen.get("auth").startsWith("SharedKey devstoreaccount1:"),
                authSeen.get("auth"));
        assertNotNull(authSeen.get("ms-date"));
        assertTrue(authSeen.get("get-auth").startsWith("SharedKey devstoreaccount1:"));
    }

    // ---- GCS：无凭据的自建端点跳过签名（object_storage.rs:121-124） ----

    @Test
    void gcsEndpointWithoutServiceAccountSkipsSignature() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authSeen.putIfAbsent("auth", auth);
            }
            if (exchange.getRequestURI().getPath().equals("/docs")) {
                respond(exchange, 200, "application/xml", s3List(false, null,
                        "docs/a.txt", "2026-01-02T03:04:05.678Z", 5));
                return;
            }
            respond(exchange, 200, "application/octet-stream", "hello");
        });
        server.start();
        Store store = ObjectStorage.client("gcs", JSON.readTree(
                "{\"bucket\":\"docs\",\"endpoint\":\"%s\"}".formatted(base())));
        ObjectStorage.FetchResult out = ObjectStorage.fetch("gcs", store, "docs", "docs");
        assertEquals(1, out.objects().size());
        assertEquals("gs://docs/docs/a.txt", out.objects().get(0).externalKey());
        assertEquals("hello", new String(out.objects().get(0).bytes(), StandardCharsets.UTF_8));
        assertEquals(null, authSeen.get("auth"), "无凭据端点不签");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }
}
