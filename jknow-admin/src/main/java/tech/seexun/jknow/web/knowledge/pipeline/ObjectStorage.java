package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.GuardedClient;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Limits;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Reach;

/**
 * 对象存储来源连接器（object_storage.rs 全量语义对等，M3 任务 9）：S3（及 MinIO、
 * Ceph RGW、R2 等说同一套协议的）、Azure Blob、GCS。三家共用这一个模块——差异收在
 * 客户端构造里，列与取的那段代码一家不改。
 *
 * <p><b>依赖选择（为什么不搬 aws-sdk / azure-sdk / google-cloud-storage）</b>：Rust 侧
 * 用 object_store 是因为它复用了树里已有的 reqwest（+7 传递依赖 vs SDK 的 +43）。Java
 * 侧同理——这条链路的语义只有「列前缀 + 按键取回 + 各家签名」，SigV4/SharedKey/JWT
 * 签名各约百行即可对等（签名算法都是公开标准：AWS SigV4、Azure SharedKey、GCS 服务
 * 账号 RS256 JWT），三大 SDK 全家桶会拖进各自的 HTTP 栈与几十兆传递依赖，违背父 POM
 * 版本收口纪律。取舍记在 pom 无需改动（零新增依赖）。
 *
 * <p><b>身份</b>：{@code s3://bucket/key} 等各家 scheme——同名的桶在 S3 与 GCS 上是两个
 * 不同的东西，共用一个前缀会让两个来源的文档互相认领。换了前缀但内容没变时，
 * {@code ingest_item} 会认成「搬家」而不是新增，不重跑抽取。
 *
 * <p><b>doc_time 取 LastModified</b>，而它是写入时刻不是文档自身的时间：一份 2019 年的
 * 合同今天传上去，时间线上会落在今天。对象存储没有更好的来源——除非文件名或正文里带
 * 日期，而那是抽取器的活。
 */
final class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    /**
     * 一次同步最多摄入多少个对象（object_storage.rs:47 MAX_OBJECTS_PER_SYNC）。
     * <b>不是性能考虑，是「别把一个桶整个吸进来」</b>——摄入不可逆，配错一个前缀就能
     * 烧掉一整天的额度。到顶时报进同步统计，让人看得见「还有」。
     */
    static final int MAX_OBJECTS_PER_SYNC = 2_000;

    /** 单个对象的大小上限（object_storage.rs:51）：超过的多半不是文档而是数据文件。 */
    public static final long MAX_OBJECT_BYTES = 32L * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectStorage() {
    }

    /** 列举里的一条（object_storage.rs 的 ObjectMeta）。 */
    record ObjectMeta(String key, long size, OffsetDateTime lastModified) {
    }

    /** 一个待摄入的对象（object_storage.rs:54-61）。 */
    public record RemoteObject(String externalKey, String filename, byte[] bytes,
                               OffsetDateTime lastModified) {
    }

    /** 各家的 URI scheme（object_storage.rs:151-157）：写死而不是拿 kind 拼——别人认得出的形态。 */
    static String uriScheme(String kind) {
        return switch (kind) {
            case "azure_blob" -> "azure";
            case "gcs" -> "gs";
            default -> "s3";
        };
    }

    /** 一家对象存储的两件事：列前缀、按键取。 */
    interface Store {
        /** 列出前缀下的对象（含分页翻完）；(对象, 是否被上限截断)。 */
        ListResult list(String bucket, String prefix) throws IOException;

        byte[] get(String bucket, String key) throws IOException;

        /** 列举结果。 */
        record ListResult(List<ObjectMeta> objects, boolean truncated) {
        }
    }

    private static String cfg(JsonNode config, String key) {
        JsonNode v = config.get(key);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 从来源配置建一个客户端（object_storage.rs:77-145 client）。三家的「桶」都叫
     * {@code bucket}——这一层要的只是「装东西的那个东西叫什么」；凭据字段按各家原名
     * （access_key_id / account_key / service_account_key），那些是用户从控制台复制
     * 过来的，改名只会让人对不上。
     *
     * <p><b>endpoint 在则走自建</b>（MinIO、Ceph、R2、Azurite、fake-gcs），此时对 S3
     * 强制 path-style：虚拟主机式寻址要求 {@code bucket.host} 解析得开，而自建部署通常
     * 只有一个裸 IP——不强制的症状是「桶不存在」，而桶明明就在那儿。允许 http 也是
     * 为它（内网 MinIO 常不挂 TLS）；<b>这是配置说了算的降级</b>，不给 endpoint 就只走
     * https。
     */
    static Store client(String kind, JsonNode config) throws IOException {
        String bucket = cfg(config, "bucket");
        if (bucket == null) {
            throw new IOException(kind + " source is missing config.bucket");
        }
        String endpoint = cfg(config, "endpoint");
        boolean insecure = endpoint != null && endpoint.startsWith("http://");
        return switch (kind) {
            case "azure_blob" -> azureStore(bucket, endpoint, insecure,
                    cfg(config, "account_name"), cfg(config, "account_key"));
            case "gcs" -> gcsStore(bucket, endpoint, cfg(config, "service_account_key"));
            default -> s3Store(bucket, endpoint, insecure, cfg(config, "region"),
                    cfg(config, "access_key_id"), cfg(config, "secret_access_key"));
        };
    }

    // ---- S3（及一切说同一套协议的） ----

    static Store s3Store(String bucket, String endpoint, boolean insecure, String region,
                         String accessKeyId, String secretAccessKey) throws IOException {
        String base = endpoint != null
                ? trimSlash(endpoint)
                : "https://s3." + (region != null ? region : "us-east-1") + ".amazonaws.com";
        GuardedClient http = GuardedHttp.clientFor(java.net.URI.create(base), Reach.OPERATOR,
                GuardedHttp.limitsWithMaxBytes(Limits.defaults(), MAX_OBJECT_BYTES)
                .withOverall(Duration.ofSeconds(60)));
        return new S3Store(http, base, region != null ? region : "us-east-1", accessKeyId,
                secretAccessKey);
    }

    /** S3 XML List API v2 + GET，SigV4 签名（AWS Signature Version 4，标准算法直写）。 */
    static final class S3Store implements Store {

        private final GuardedClient http;
        private final String base;
        private final String region;
        private final String accessKeyId;
        private final String secretAccessKey;

        S3Store(GuardedClient http, String base, String region, String accessKeyId,
                String secretAccessKey) {
            this.http = http;
            this.base = base;
            this.region = region;
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
        }

        @Override
        public ListResult list(String bucket, String prefix) throws IOException {
            List<ObjectMeta> out = new ArrayList<>();
            String token = null;
            int pages = 0;
            while (true) {
                StringBuilder query = new StringBuilder("list-type=2");
                if (prefix != null && !prefix.isEmpty()) {
                    query.append("&prefix=").append(enc(prefix));
                }
                if (token != null) {
                    query.append("&continuation-token=").append(enc(token));
                }
                String xml = call("GET", "/" + bucket, query.toString(), null);
                Element root = parseXml(xml);
                for (Element contents : children(root, "Contents")) {
                    out.add(new ObjectMeta(textOf(contents, "Key"),
                            Long.parseLong(textOf(contents, "Size")),
                            timeOf(textOf(contents, "LastModified"))));
                }
                token = textOf(root, "NextContinuationToken");
                boolean truncated = "true".equalsIgnoreCase(textOf(root, "IsTruncated"));
                pages++;
                if (token == null || !truncated || pages >= 20) {
                    return new ListResult(out, truncated && token != null);
                }
            }
        }

        @Override
        public byte[] get(String bucket, String key) throws IOException {
            GuardedClient.Response resp = callRaw("GET",
                    "/" + bucket + "/" + encodePath(key), null, null);
            if (resp.status() / 100 != 2) {
                throw new IOException("HTTP " + resp.status() + " from object storage");
            }
            return resp.body();
        }

        private String call(String method, String path, String query, byte[] body)
                throws IOException {
            GuardedClient.Response resp = callRaw(method, path, query, body);
            if (resp.status() / 100 != 2) {
                throw new IOException("HTTP " + resp.status() + " from object storage");
            }
            return resp.text();
        }

        private GuardedClient.Response callRaw(String method, String path, String query,
                                                byte[] body) throws IOException {
            // C-2 收口：Host 头只由 GuardedClient.speak() 写（RFC 7230 §5.4 单 Host）；
            // 签名与线上值共用 hostHeaderValue 同一真源——显式默认端口（https://h:443）
            // 两处同折裸 host，不再出现签名 host:443 / 线上 host 的错配
            String hostHeader = GuardedHttp.hostHeaderValue(java.net.URI.create(base));
            String amzDate = OffsetDateTime.now(ZoneUtc()).format(
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US));
            String dateStamp = amzDate.substring(0, 8);
            String payloadHash = sha256Hex(new byte[0]);
            List<String[]> headers = new ArrayList<>();
            headers.add(new String[] {"x-amz-content-sha256", payloadHash});
            headers.add(new String[] {"x-amz-date", amzDate});
            if (accessKeyId != null && secretAccessKey != null) {
                headers.add(new String[] {"Authorization", sigV4Authorization(method, path,
                        query, hostHeader, payloadHash, amzDate, dateStamp, region, accessKeyId,
                        secretAccessKey)});
            }
            String url = base + path + (query != null ? "?" + query : "");
            return http.exchange(method, java.net.URI.create(url), headers, body);
        }
    }

    /**
     * SigV4（AWS Signature Version 4 通用步骤）：<b>静态纯函数</b>——规范请求 → 派生键
     * → 签名头，供已知向量断言（C-1/C-2 教训：「头在场」不等于「签得对」）。
     */
    static String sigV4Authorization(String method, String path, String query, String hostHeader,
                                     String payloadHash, String amzDate, String dateStamp,
                                     String region, String accessKeyId, String secretAccessKey) {
        String canonicalQuery = canonicalQuery(query);
        // 规范头按名排序：host;x-amz-content-sha256;x-amz-date
        String canonicalHeaders = "host:" + hostHeader + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + hex(hmac(
                sigV4SigningKey(secretAccessKey, dateStamp, region),
                stringToSign.getBytes(StandardCharsets.UTF_8)));
    }

    /** 规范查询串：按名排序、值保持已编码形态（enc 的产物）。 */
    private static String canonicalQuery(String query) {
        TreeMap<String, String> params = new TreeMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                params.put(eq < 0 ? pair : pair.substring(0, eq),
                        eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    static byte[] sigV4SigningKey(String secretAccessKey, String dateStamp, String region) {
        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8),
                dateStamp.getBytes(StandardCharsets.UTF_8));
        key = hmac(key, region.getBytes(StandardCharsets.UTF_8));
        key = hmac(key, "s3".getBytes(StandardCharsets.UTF_8));
        key = hmac(key, "aws4_request".getBytes(StandardCharsets.UTF_8));
        return key;
    }

    // ---- Azure Blob ----

    static Store azureStore(String bucket, String endpoint, boolean insecure, String account,
                            String accountKey) throws IOException {
        String base = endpoint != null ? trimSlash(endpoint)
                : "https://" + account + ".blob.core.windows.net";
        GuardedClient http = GuardedHttp.clientFor(java.net.URI.create(base), Reach.OPERATOR,
                GuardedHttp.limitsWithMaxBytes(Limits.defaults(), MAX_OBJECT_BYTES)
                .withOverall(Duration.ofSeconds(60)));
        return new AzureStore(http, base, account, accountKey);
    }

    /** Azure Blob REST（List Blobs + Get Blob），SharedKey 签名（2015+ 规范）。 */
    static final class AzureStore implements Store {

        private final GuardedClient http;
        private final String base;
        private final String account;
        private final String accountKey;

        AzureStore(GuardedClient http, String base, String account, String accountKey) {
            this.http = http;
            this.base = base;
            this.account = account == null ? "" : account;
            this.accountKey = accountKey;
        }

        @Override
        public ListResult list(String bucket, String prefix) throws IOException {
            List<ObjectMeta> out = new ArrayList<>();
            String marker = null;
            int pages = 0;
            while (true) {
                StringBuilder query = new StringBuilder("restype=container&comp=list");
                if (prefix != null && !prefix.isEmpty()) {
                    query.append("&prefix=").append(enc(prefix));
                }
                if (marker != null) {
                    query.append("&marker=").append(enc(marker));
                }
                String xml = call("GET", "/" + bucket, query.toString());
                Element root = parseXml(xml);
                Element blobs = firstChild(root, "Blobs");
                if (blobs != null) {
                    for (Element blob : children(blobs, "Blob")) {
                        Element properties = firstChild(blob, "Properties");
                        out.add(new ObjectMeta(textOf(blob, "Name"),
                                properties == null ? 0
                                        : Long.parseLong(textOf(properties, "Content-Length")),
                                timeOf(textOf(properties, "Last-Modified"))));
                    }
                }
                marker = textOf(root, "NextMarker");
                pages++;
                if (marker == null || marker.isEmpty() || pages >= 20) {
                    return new ListResult(out, marker != null && !marker.isEmpty());
                }
            }
        }

        @Override
        public byte[] get(String bucket, String key) throws IOException {
            GuardedClient.Response resp = callRaw("GET",
                    "/" + bucket + "/" + encodePath(key), null);
            if (resp.status() / 100 != 2) {
                throw new IOException("HTTP " + resp.status() + " from azure blob");
            }
            return resp.body();
        }

        private String call(String method, String path, String query) throws IOException {
            GuardedClient.Response resp = callRaw(method, path, query);
            if (resp.status() / 100 != 2) {
                throw new IOException("HTTP " + resp.status() + " from azure blob");
            }
            return resp.text();
        }

        private GuardedClient.Response callRaw(String method, String path, String query)
                throws IOException {
            String date = OffsetDateTime.now(ZoneUtc()).format(DateTimeFormatter.RFC_1123_DATE_TIME);
            List<String[]> headers = new ArrayList<>();
            headers.add(new String[] {"x-ms-date", date});
            headers.add(new String[] {"x-ms-version", "2021-08-06"});
            if (accountKey != null && !account.isEmpty()) {
                String signature = Base64.getEncoder().encodeToString(
                        hmac(Base64.getDecoder().decode(accountKey),
                                azureStringToSign(method, path, query, account, date)
                                        .getBytes(StandardCharsets.UTF_8)));
                headers.add(new String[] {"Authorization",
                        "SharedKey " + account + ":" + signature});
            }
            String url = base + path + (query != null ? "?" + query : "");
            return http.exchange(method, java.net.URI.create(url), headers, null);
        }
    }

    /**
     * Azure SharedKey string-to-sign（2015-02-21+ 规范）：<b>静态纯函数</b>。
     *
     * <p>VERB 之后是 11 个可为空的头字段（Content-Encoding/Language/Length/MD5/Type/Date/
     * If-Modified-Since/If-Match/If-None-Match/If-Unmodified-Since/Range）——<b>空字段的
     * 分隔符仍然要求</b>（微软文档明示），即 VERB 后共 <b>12 个换行</b>再接 canonicalized
     * headers（C-1 收口：此前 11 个换行对真 Azure 必 403；纯函数化供已知向量断言）。
     */
    static String azureStringToSign(String method, String path, String query, String account,
                                    String date) {
        StringBuilder canonicalResource = new StringBuilder("/" + account + path);
        StringBuilder canonicalHeaders = new StringBuilder("x-ms-date:").append(date)
                .append("\nx-ms-version:2021-08-06\n");
        if (query != null) {
            TreeMap<String, String> params = new TreeMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String name = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.US);
                params.put(name, eq < 0 ? "" : pair.substring(eq + 1));
            }
            for (Map.Entry<String, String> e : params.entrySet()) {
                canonicalResource.append('\n').append(e.getKey()).append(':')
                        .append(e.getValue());
            }
        }
        // GET 无正文：Content-Length/Content-MD5/Content-Type 全空——12 个换行一个不少
        return method + "\n\n\n\n\n\n\n\n\n\n\n\n"
                + canonicalHeaders + canonicalResource;
    }

    // ---- GCS ----

    static Store gcsStore(String bucket, String endpoint, String serviceAccountKey)
            throws IOException {
        String base = endpoint != null ? trimSlash(endpoint) : "https://storage.googleapis.com";
        GuardedClient http = GuardedHttp.clientFor(java.net.URI.create(base), Reach.OPERATOR,
                GuardedHttp.limitsWithMaxBytes(Limits.defaults(), MAX_OBJECT_BYTES)
                .withOverall(Duration.ofSeconds(60)));
        // 服务账号 JSON 整份贴进来（不是路径）：来源配置是一行 JSONB，服务器上未必有那个文件
        String token = serviceAccountKey != null ? googleAccessToken(serviceAccountKey) : null;
        // 自建端点通常没有 Google 的凭据体系（模拟器、S3 网关）——没给服务账号就跳过签名
        return new GcsStore(http, base, token);
    }

    static final class GcsStore implements Store {

        private final GuardedClient http;
        private final String base;
        private final String token;

        GcsStore(GuardedClient http, String base, String token) {
            this.http = http;
            this.base = base;
            this.token = token;
        }

        @Override
        public ListResult list(String bucket, String prefix) throws IOException {
            // I-1 收口：GCS XML API 的翻页是 marker/NextMarker——不是 S3 的
            // list-type=2/NextContinuationToken。真 GCS 忽略 list-type=2 并按 marker
            // 语义回 NextMarker：续读 S3 的 token 名会拿到 null 而「提前收工」，>1000
            // 对象的桶静默只收第一页
            List<ObjectMeta> out = new ArrayList<>();
            String marker = null;
            int pages = 0;
            while (true) {
                StringBuilder query = new StringBuilder();
                if (prefix != null && !prefix.isEmpty()) {
                    query.append("prefix=").append(enc(prefix));
                }
                if (marker != null) {
                    if (!query.isEmpty()) {
                        query.append('&');
                    }
                    query.append("marker=").append(enc(marker));
                }
                String xml = call("GET", "/" + bucket, query.toString());
                Element root = parseXml(xml);
                for (Element contents : children(root, "Contents")) {
                    out.add(new ObjectMeta(textOf(contents, "Key"),
                            Long.parseLong(textOf(contents, "Size")),
                            timeOf(textOf(contents, "LastModified"))));
                }
                marker = textOf(root, "NextMarker");
                boolean truncated = "true".equalsIgnoreCase(textOf(root, "IsTruncated"));
                pages++;
                if (marker == null || marker.isEmpty() || !truncated || pages >= 20) {
                    // 有 token 仍翻不完 = 还有没列完的，要报出来
                    return new ListResult(out, truncated && marker != null && !marker.isEmpty());
                }
            }
        }

        @Override
        public byte[] get(String bucket, String key) throws IOException {
            GuardedClient.Response resp = callRaw("GET", "/" + bucket + "/" + encodePath(key),
                    null);
            if (resp.status() / 100 != 2) {
                throw new IOException("HTTP " + resp.status() + " from gcs");
            }
            return resp.body();
        }

        private String call(String method, String path, String query) throws IOException {
            GuardedClient.Response resp = callRaw(method, path, query);
            if (resp.status() / 100 != 2) {
                throw new IOException("HTTP " + resp.status() + " from gcs");
            }
            return resp.text();
        }

        private GuardedClient.Response callRaw(String method, String path, String query)
                throws IOException {
            List<String[]> headers = new ArrayList<>();
            if (token != null) {
                headers.add(new String[] {"Authorization", "Bearer " + token});
            }
            String url = base + path + (query != null ? "?" + query : "");
            return http.exchange(method, java.net.URI.create(url), headers, null);
        }
    }

    /**
     * GCS 服务账号换访问令牌：RS256 签名的 JWT 换 OAuth2（标准流程，RS256 用 JDK 自带
     * Signature 即可——不为此引 google-auth-library）。
     */
    static String googleAccessToken(String serviceAccountKey) throws IOException {
        try {
            JsonNode key = JSON.readTree(serviceAccountKey);
            String email = key.path("client_email").asText(null);
            String privateKeyPem = key.path("private_key").asText(null);
            if (email == null || privateKeyPem == null) {
                throw new IOException("gcs service_account_key 缺 client_email/private_key");
            }
            long now = System.currentTimeMillis() / 1000;
            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String claims = "{\"iss\":\"" + email + "\",\"scope\":"
                    + "\"https://www.googleapis.com/auth/devstorage.read_only\","
                    + "\"aud\":\"https://oauth2.googleapis.com/token\","
                    + "\"exp\":" + (now + 3600) + ",\"iat\":" + now + "}";
            String unsigned = b64url(header.getBytes(StandardCharsets.UTF_8)) + "."
                    + b64url(claims.getBytes(StandardCharsets.UTF_8));
            java.security.Signature signer = java.security.Signature
                    .getInstance("SHA256withRSA");
            signer.initSign(privateKeyOf(privateKeyPem));
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            String assertion = unsigned + "." + b64url(signer.sign());
            String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
                    + "&assertion=" + enc(assertion);
            GuardedClient http = GuardedHttp.clientFor(
                    java.net.URI.create("https://oauth2.googleapis.com/"), Reach.OPERATOR,
                    GuardedHttp.limitsWithMaxBytes(Limits.defaults(),
                            MAX_OBJECT_BYTES));
            List<String[]> headers = new ArrayList<>();
            headers.add(new String[] {"Content-Type",
                    "application/x-www-form-urlencoded"});
            GuardedClient.Response resp = http.exchange("POST",
                    java.net.URI.create("https://oauth2.googleapis.com/token"), headers,
                    form.getBytes(StandardCharsets.UTF_8));
            if (resp.status() / 100 != 2) {
                throw new IOException("gcs token exchange failed: HTTP " + resp.status());
            }
            return JSON.readTree(resp.text()).path("access_token").asText(null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("gcs service_account_key 无法签名: " + e, e);
        }
    }

    private static java.security.PrivateKey privateKeyOf(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        java.security.spec.PKCS8EncodedKeySpec spec =
                new java.security.spec.PKCS8EncodedKeySpec(der);
        return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    // ---- 列举 + 取回的共用段（object_storage.rs:173-232 fetch） ----

    /**
     * 列出前缀下的对象并逐个取回。
     *
     * <p><b>零字节的一律跳过，判据是大小不是名字</b>：控制台用零字节占位对象造「文件夹」，
     * 而占位对象按大小判还更宽——零字节的对象无论叫什么都不是文档，ingest_item 拿到
     * 空字节也只会返回 Unchanged。格式不在这里判：抽取按扩展名加 mime 分派，认不出的一律
     * 按文本解码——再维护一张白名单就是同一件事写两遍，而两份清单迟早分叉。
     *
     * <p><b>一个对象取不回来不该带走整次同步</b>：列表与取回之间隔着时间，中途被删、被换
     * 权限都是正常的；记下来在收尾时一并报，而不是让第 900 个对象的一次 404 把前 899 个
     * 的成果一起丢掉。
     */
    public static FetchResult fetch(String kind, Store store, String bucket, String prefix)
            throws IOException {
        String scheme = uriScheme(kind);
        Store.ListResult listing = store.list(bucket, prefix);
        if (listing.truncated()) {
            log.warn("bucket={} prefix={} 对象数到达单次上限，其余留给下一次同步", bucket,
                    prefix == null ? "" : prefix);
        }
        List<RemoteObject> out = new ArrayList<>();
        int unreadable = 0;
        for (ObjectMeta meta : listing.objects()) {
            if (meta.size() == 0) {
                continue;
            }
            if (meta.size() > MAX_OBJECT_BYTES) {
                log.info("key={} size={} 对象太大，跳过", meta.key(), meta.size());
                continue;
            }
            if (out.size() >= MAX_OBJECTS_PER_SYNC) {
                return new FetchResult(out, true);
            }
            byte[] bytes;
            try {
                bytes = store.get(bucket, meta.key());
            } catch (Exception e) {
                unreadable++;
                log.warn("key={} 对象取不回来，跳过 error={}", meta.key(), e.toString());
                continue;
            }
            out.add(new RemoteObject(scheme + "://" + bucket + "/" + meta.key(),
                    lastSegment(meta.key()), bytes, meta.lastModified()));
        }
        if (unreadable > 0) {
            log.warn("bucket={} 有 {} 个对象没能取回", bucket, unreadable);
        }
        return new FetchResult(out, listing.truncated() || out.size() >= MAX_OBJECTS_PER_SYNC);
    }

    /** fetch 的产出。 */
    public record FetchResult(List<RemoteObject> objects, boolean truncated) {
    }

    // ---- 小工具 ----

    private static java.time.ZoneOffset ZoneUtc() {
        return java.time.ZoneOffset.UTC;
    }

    private static String lastSegment(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    private static String trimSlash(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    static String enc(String value) {
        // M-1 收口：URLEncoder 不编码 *（SigV4 规范查询要求 %2A）、却把 ~ 编成 %7E
        // （RFC3986 unreserved 应保持字面）——两处都按规范查询的规矩修
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /** 路径段转义：保留 '/'。 */
    static String encodePath(String key) {
        StringBuilder out = new StringBuilder();
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean ok = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/';
            out.append(ok ? (char) c : String.format("%%%02X", c));
        }
        return out.toString();
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static OffsetDateTime timeOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            // S3/GCS 给 ISO 8601 毫秒（2026-01-02T03:04:05.678Z）；Azure 给 RFC 1123
            return OffsetDateTime.parse(raw);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(raw,
                        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ---- XML（与前缀无关的本地名匹配，与 webdav/rss 同一铁律） ----

    static Element parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new IOException("Malformed listing XML: " + e.getMessage(), e);
        }
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        collectChildren(parent, localName, out);
        return out;
    }

    private static void collectChildren(Element parent, String localName, List<Element> out) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getNodeName().equals(localName)) {
                out.add(e);
            }
        }
    }

    private static Element firstChild(Element parent, String localName) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getNodeName().equals(localName)) {
                return e;
            }
        }
        return null;
    }

    private static String textOf(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        return child == null ? null : child.getTextContent();
    }
}
