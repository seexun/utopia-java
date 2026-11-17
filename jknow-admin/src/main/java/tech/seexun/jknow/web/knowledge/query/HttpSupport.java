package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * HTTP 族引擎共用的请求执行件。reqwest 在 Rust 侧开了 gzip 特性自动解压
 * （Cargo.toml features 含 "gzip"）——JDK HttpClient 不会，这里显式带
 * {@code Accept-Encoding: gzip} 并按 {@code Content-Encoding} 解，行为对齐。
 * 服务端没有 gzip 时原样走（Snowflake 的后续分区线上会压，测试里用脚本端点复刻）。
 *
 * <p><b>超时覆盖整个交换</b>（头 + 体全收完）——对齐 Rust 侧
 * {@code tokio::time::timeout(deadline, send + read)} 的语义：sendAsync 的 future
 * 在体收完才完成，{@code get(timeout)} 把 deadline 钉在整段上；中断（chat 里的 Stop）
 * 会取消 future、掐断连接，同 Rust 丢 future。
 */
final class HttpSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private HttpSupport() {
    }

    /** 一条请求的构造材料：URL、方法、头、体、超时。 */
    record Call(String url, String method, Map<String, String> headers, byte[] body,
                Duration timeout) {
    }

    /** 发出并收完整响应体（自动 gzip 解压）。读体中断=IOException（对齐 reqwest bytes()/text() 的 Err）。 */
    static Response send(HttpClient client, Call call)
            throws IOException, InterruptedException {
        Response response = sendPartial(client, call);
        if (response.readError() != null) {
            throw response.readError();
        }
        return response;
    }

    /**
     * 发出请求，读体中断时<b>不抛</b>——把已读字节连同读错误一并交回
     * {@link Response#readError()}。对齐 Rust 侧 {@code resp.chunk()} 循环累积
     * {@code body: Vec<u8>} 的手法：ClickHouse 25.11+ 在 {@code __exception__} 块后
     * 故意掐断回复（clickhouse.rs:269-283），已读部分就是服务器的回答——JDK 内建的
     * {@code ofByteArray} 在读错误时丢弃已读字节，这里自己累积。gzip 解压只在体收完时
     * 做：截断的压缩流解不出完整体，保留原始字节（截断场景本就无压缩——服务器掐断前
     * 头已发出，{@code enable_http_compression} 攒头的路走的是 500+整错体）。
     */
    static Response sendPartial(HttpClient client, Call call)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(java.net.URI.create(call.url()))
                .timeout(call.timeout() == null ? QueryEngines.HTTP_REQUEST_TIMEOUT
                        : call.timeout());
        switch (call.method().toUpperCase()) {
            case "GET" -> builder.GET();
            default -> {
                builder.header("Content-Type", "application/json");
                builder.method(call.method(), HttpRequest.BodyPublishers.ofByteArray(call.body()));
            }
        }
        // Trino 的语句体不是 JSON（是裸 SQL 文本），Content-Type 由头表覆盖
        for (Map.Entry<String, String> h : call.headers().entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        CompletableFuture<HttpResponse<Body>> pending =
                client.sendAsync(builder.build(), info -> new AccumulatingBody());
        HttpResponse<Body> response;
        try {
            response = pending.get(
                    call.timeout() == null ? QueryEngines.HTTP_REQUEST_TIMEOUT.toMillis()
                            : call.timeout().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("request timed out");
        } catch (ExecutionException e) {
            pending.cancel(true);
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(String.valueOf(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
        Body received = response.body();
        byte[] body = received.bytes();
        if (received.readError() == null) {
            String encoding = response.headers().firstValue("Content-Encoding").orElse("");
            if ("gzip".equalsIgnoreCase(encoding)) {
                try (InputStream in = new GZIPInputStream(
                        new java.io.ByteArrayInputStream(body));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    body = out.toByteArray();
                }
            }
        }
        return new Response(response.statusCode(), response.headers().map(), body,
                received.readError());
    }

    /** 原始字节 + 可选的读错误（读错误时字节是掐断前已读到的部分）。 */
    private record Body(byte[] bytes, IOException readError) {
    }

    /**
     * 累积式 {@link HttpResponse.BodySubscriber}：onError 也以<b>值</b>完成
     * {@code getBody()}（带上已读字节与错误），让 {@code sendAsync} 的 future 正常完成
     * ——这是把已读字节救回来的唯一通道（内建 subscriber 在 onError 时以异常完成，字节
     * 丢失）。onError 之后 JDK 不再回调任何方法。
     */
    private static final class AccumulatingBody implements HttpResponse.BodySubscriber<Body> {

        private final CompletableFuture<Body> done = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            IOException io = throwable instanceof IOException ioException
                    ? ioException
                    : new IOException(String.valueOf(throwable), throwable);
            done.complete(new Body(out.toByteArray(), io));
        }

        @Override
        public void onComplete() {
            done.complete(new Body(out.toByteArray(), null));
        }

        @Override
        public CompletionStage<Body> getBody() {
            return done;
        }
    }

    /** 收到的响应：状态、头（JDK Map 形）、解压后的体、读体错误（null=完整收完）。 */
    record Response(int status, Map<String, List<String>> headers, byte[] body,
                    IOException readError) {

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }

        /** header 的首个值（大小写不敏感——JDK 归一化过，这里保险再取一次）。 */
        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && !e.getValue().isEmpty()) {
                    return e.getValue().get(0).trim();
                }
            }
            return null;
        }
    }

    /** error_for_status 的等价文案：reqwest 的 Display 含 "HTTP status ... ({code})"——保留码可断言。 */
    static IllegalStateException httpStatusError(Response response) {
        return new IllegalStateException(
                "HTTP status " + (response.status() >= 500 ? "server" : "client")
                        + " error (" + response.status() + ")");
    }
}
