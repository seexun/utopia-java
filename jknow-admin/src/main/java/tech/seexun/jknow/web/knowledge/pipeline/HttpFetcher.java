package tech.seexun.jknow.web.knowledge.pipeline;

import java.io.IOException;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Fetched;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Limits;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Reach;

/**
 * 页面/Feed 抓取门面（M3 任务 9 起全部语义收进 {@link GuardedHttp}——SSRF 防护/DNS 钉住/
 * 逐跳重定向复验/User-Agent 都在那里；本类只保留旧调用点的便捷入口）。
 *
 * <ul>
 *   <li>User-Agent 自报家门（ingest_sources.rs:27-31 UA 逐字）：维基百科等站拒绝匿名
 *       请求，自报家门也是爬虫礼节；</li>
 *   <li>响应体上限（页面 16MiB——Limits::default / feed 4MiB——MAX_FEED_BYTES）；</li>
 *   <li>地址是这个库的人自己填的：{@code Reach.OPERATOR}（内网放行；重定向不许把请求
 *       带进内网——那条在 GuardedHttp.checkHop）。</li>
 * </ul>
 */
public final class HttpFetcher {

    /** ingest_sources.rs:27-31 UA（对齐 Rust CARGO_PKG_VERSION=0.1.0），真身在 GuardedHttp。 */
    public static final String UA = GuardedHttp.UA;

    private HttpFetcher() {
    }

    /** 抓取产物：mime（Content-Type 去参数）、最终地址（重定向落点）、响应体。 */
    public record Page(String mime, String finalUrl, byte[] bytes) {
    }

    /** 操作者填的地址 + 自定字节上限（M2 调用点兼容面）。 */
    public static Page get(String url, long maxBytes) throws IOException, InterruptedException {
        return get(url, Reach.OPERATOR, GuardedHttp.limitsWithMaxBytes(Limits.defaults(),
                maxBytes));
    }

    /**
     * GET 一个 URL（http_fetch.rs:161 get 的门面）：手动逐跳跟重定向并复验；feed 里的
     * 文章链接要用 {@code Reach.CONTENT}——那是一段内容说的地址，只准公网。
     */
    public static Page get(String url, Reach reach, Limits limits) throws IOException {
        Fetched fetched = GuardedHttp.get(url, reach, limits);
        return new Page(fetched.mime(), fetched.finalUrl().toString(), fetched.bytes());
    }
}
