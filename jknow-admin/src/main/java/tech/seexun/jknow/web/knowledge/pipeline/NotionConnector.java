package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.GuardedClient;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Limits;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Reach;

/**
 * Notion 来源连接器（notion.rs 全量对等，M3 任务 9）：把 integration 能看见的页面摄进来。
 *
 * <p><b>doc_time 取 last_edited_time</b>——这是页面自己的编辑时刻，比对象存储那边的
 * 写入时刻实在。但它只交出现状不交出历史：Notion 的版本历史不在公开 API 里，一次同步
 * 只能看见此刻，之前的编辑全靠一次次同步慢慢攒（与 url/rss 同一形状）。
 *
 * <p><b>两个容易踩的</b>（notion.rs:19-29）：Notion-Version 头必填且值是日期，少了直接
 * 400；限流是<b>每秒三次</b>——顺序发请求还不够，所以两件事一起做：请求之间保底间隔
 * （{@link #MIN_INTERVAL}），撞上 429 按 Retry-After 等完再发。仍然不并发。这套退避是
 * 本地的、很小的——抽取那条路的退避是为模型厂商设计的，判据和节奏都不同，不借用。
 */
final class NotionConnector {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 请求头里的 API 版本（notion.rs:39）。<b>写死而不是留给配置</b>：响应的形状跟着它
     * 变，让用户填一个我们没适配过的版本，换来的是解析静默失配。
     */
    static final String NOTION_VERSION = "2026-03-11";

    /** 一次同步最多取多少页：摄入不可逆，而一个 workspace 可以有几万页。 */
    static final int MAX_PAGES_PER_SYNC = 500;

    /** 每页所有层级合计最多取多少个 block（含无文字容器）；再深的页面截断。 */
    static final int MAX_BLOCKS_PER_PAGE = 500;

    /** 两次请求之间至少隔这么久：Notion 说「平均每秒三次」，取 350ms 留一点余量。 */
    static final Duration MIN_INTERVAL = Duration.ofMillis(350);

    /** 一次请求撞上 429 最多等几回：连等几回还在限流就不是节奏问题，该把错误交出去。 */
    static final int MAX_RATE_LIMIT_RETRIES = 5;

    /** Retry-After 缺失或读不出来时等多久。 */
    static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);

    /** 单次等待上限：头里的值照做，但不能让离谱的值把同步挂死。 */
    static final Duration RETRY_AFTER_CAP = Duration.ofSeconds(30);

    private NotionConnector() {
    }

    /** 换行字符常量（char 字面量转义在多处源码生成时易碎，收成一处）。 */
    private static final char NL = '\n';

    /** 一个待摄入的页面（notion.rs:61-67）。 */
    public record NotionPage(String externalKey, String filename, String text,
                             java.time.OffsetDateTime lastEdited) {
    }

    static final class Paced {

        final GuardedClient http;
        final String apiRoot;
        final List<String[]> defaultHeaders;
        private Instant last;

        Paced(GuardedClient http) {
            this(http, "https://api.notion.com/v1", List.of());
        }

        Paced(GuardedClient http, String apiRoot) {
            this(http, apiRoot, List.of());
        }

        Paced(GuardedClient http, String apiRoot, List<String[]> defaultHeaders) {
            this.http = http;
            this.apiRoot = apiRoot;
            this.defaultHeaders = defaultHeaders;
        }

        /**
         * 发一次请求并解析 JSON。429 在这里消化：等 Retry-After 再发，最多
         * {@link #MAX_RATE_LIMIT_RETRIES} 回；其它非 2xx 直接报错，带上 Notion 自己的
         * message——它比状态码说得清楚。
         */
        JsonNode send(String what, String method, URI url, String jsonBody) throws IOException {
            int attempt = 0;
            while (true) {
                if (last != null) {
                    long since = Duration.between(last, Instant.now()).toMillis();
                    if (since < MIN_INTERVAL.toMillis()) {
                        sleep(MIN_INTERVAL.toMillis() - since);
                    }
                }
                List<String[]> headers = new ArrayList<>(defaultHeaders);
                byte[] body = null;
                if (jsonBody != null) {
                    headers.add(new String[] {"Content-Type", "application/json"});
                    body = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                GuardedClient.Response resp = http.exchange(method, url, headers, body);
                last = Instant.now();
                if (resp.status() == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    Duration wait = retryAfter(resp.header("Retry-After"));
                    attempt++;
                    sleep(wait.toMillis());
                    continue;
                }
                JsonNode v;
                try {
                    v = MAPPER.readTree(
                            resp.body() == null || resp.body().length == 0 ? "{}" : resp.text());
                } catch (IOException e) {
                    throw new IOException("notion " + what + ": reading the response", e);
                }
                if (resp.status() / 100 != 2) {
                    throw new IOException("notion " + what + " returned " + resp.status() + ": "
                            + v.path("message").asText("no message"));
                }
                return v;
            }
        }

        private static void sleep(long millis) throws IOException {
            try {
                Thread.sleep(Math.max(0, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    /** Retry-After 头 → 等多久（notion.rs:157-164）：秒数（可带小数）照做，离谱的截顶。 */
    static Duration retryAfter(String header) {
        double seconds = -1;
        if (header != null) {
            try {
                seconds = Double.parseDouble(header.trim());
            } catch (NumberFormatException ignored) {
                // 垃圾值按缺省
            }
        }
        if (!Double.isFinite(seconds) || seconds < 0) {
            return DEFAULT_RETRY_AFTER;
        }
        Duration wait = Duration.ofMillis((long) (seconds * 1000));
        return wait.compareTo(RETRY_AFTER_CAP) > 0 ? RETRY_AFTER_CAP : wait;
    }

    /** 取 integration 能看见的所有页面（notion.rs:172-178 fetch）。 */
    public static Result fetch(String token, String query) throws IOException {
        GuardedClient http = GuardedHttp.clientFor(URI.create("https://api.notion.com/v1/"),
                Reach.OPERATOR, Limits.defaults().withOverall(Duration.ofSeconds(60)));
        List<String[]> headers = new ArrayList<>();
        headers.add(new String[] {"Notion-Version", NOTION_VERSION});
        headers.add(new String[] {"Authorization", "Bearer " + token});
        return fetchPages(new Paced(http, "https://api.notion.com/v1", headers), query);
    }

    /** fetch 的产出：(pages, 是否被页数上限截断, 正文读不出来的页每页一句原因)。 */
    public record Result(List<NotionPage> pages, boolean truncated, List<String> unread) {
    }

    /**
     * 逐页 search + 取正文（notion.rs:180-248）。<b>只搜页面不搜 data source</b>——后者
     * 是表格容器自己没有正文；表格里的每一行是一个页面，会在同一次搜索里出现。
     *
     * <p><b>正文读不出来的页整页跳过</b>：不摄入，库里已有的正文原样留着，不拿仅标题的
     * 版本去替换它。<b>其余的页照常同步</b>——中止整轮是另一头的错：有的页永远读不出来
     * （嵌套块没共享给 integration，Notion 回 404），那样整个来源就再也不动了。
     */
    static Result fetchPages(Paced paced, String query) throws IOException {
        List<NotionPage> out = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        String cursor = null;
        boolean truncated = false;

        loop:
        while (true) {
            com.fasterxml.jackson.databind.node.ObjectNode body = MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode filter = body.putObject("filter");
            filter.put("property", "object");
            filter.put("value", "page");
            body.put("page_size", 100);
            if (query != null && !query.isEmpty()) {
                body.put("query", query);
            }
            if (cursor != null) {
                body.put("start_cursor", cursor);
            }
            JsonNode v = paced.send("search", "POST", URI.create(paced.apiRoot + "/search"),
                    body.toString());

            for (JsonNode p : v.path("results")) {
                // 回收站里的和归档的都不要——它们在界面上已经不算数了
                if (p.path("in_trash").asBoolean(false)
                        || p.path("is_archived").asBoolean(false)) {
                    continue;
                }
                if (out.size() >= MAX_PAGES_PER_SYNC) {
                    truncated = true;
                    break loop;
                }
                String id = p.path("id").asText(null);
                if (id == null) {
                    continue;
                }
                String title = pageTitle(p);
                String text;
                try {
                    text = pageText(paced, id);
                } catch (Exception error) {
                    unread.add("notion page " + id + ": " + error.getMessage());
                    continue;
                }
                out.add(new NotionPage("notion://" + id, slug(title) + ".md",
                        "# " + title + "\n\n" + text,
                        parseRfc3339(p.path("last_edited_time").asText(null))));
            }

            if (v.path("has_more").asBoolean(false)) {
                cursor = v.path("next_cursor").asText(null);
                if (cursor == null) {
                    break;
                }
                continue;
            }
            break;
        }
        return new Result(out, truncated, unread);
    }

    private static java.time.OffsetDateTime parseRfc3339(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 页面标题（notion.rs:255-274）。<b>标题藏在 properties 里 type=="title" 的属性下，
     * 而它的名字不固定</b>：数据库里的页面可能叫 Name、名称、任务，普通页面叫 title。
     * 按名字找会在别人的 workspace 上找不到，所以按类型找。
     */
    static String pageTitle(JsonNode page) {
        StringBuilder s = new StringBuilder();
        for (JsonNode prop : page.path("properties")) {
            if ("title".equals(prop.path("type").asText())) {
                for (JsonNode r : prop.path("title")) {
                    s.append(r.path("plain_text").asText(""));
                }
                break;
            }
        }
        return s.toString().isBlank() ? "untitled" : s.toString();
    }

    /** 取一页的正文，逐层展开 block（notion.rs:277-282）。 */
    static String pageText(Paced paced, String pageId) throws IOException {
        StringBuilder out = new StringBuilder();
        int[] blockCount = {0};
        appendChildren(paced, pageId, out, blockCount);
        return out.toString();
    }

    /**
     * 每个父块独立分页，先写父块，再读完子树，最后继续兄弟块（notion.rs:286-342）。
     * 输出和预算由整页共享；递归深度也受同一块数上限约束。
     */
    static void appendChildren(Paced paced, String parentId, StringBuilder out, int[] blockCount)
            throws IOException {
        String cursor = null;

        while (blockCount[0] < MAX_BLOCKS_PER_PAGE) {
            String url = paced.apiRoot + "/blocks/" + parentId + "/children?page_size=100"
                    + (cursor != null ? "&start_cursor=" + cursor : "");
            JsonNode v = paced.send("blocks", "GET", URI.create(url), null);

            for (JsonNode b : v.path("results")) {
                if (blockCount[0] >= MAX_BLOCKS_PER_PAGE) {
                    return;
                }
                blockCount[0]++;
                String line = renderBlock(b);
                if (line != null) {
                    out.append(line).append('\n');
                }
                // 不能因父块没有 rich_text 而跳过 column 等容器。独立页面由 search
                // 单独摄入，数据库也不是父页正文的一部分。
                boolean independentContent = "child_page".equals(b.path("type").asText())
                        || "child_database".equals(b.path("type").asText());
                if (blockCount[0] < MAX_BLOCKS_PER_PAGE
                        && b.path("has_children").asBoolean(false) && !independentContent) {
                    String id = b.path("id").asText(null);
                    if (id == null) {
                        throw new IOException("notion blocks: child block is missing id");
                    }
                    if ("table".equals(b.path("type").asText())) {
                        StringBuilder rows = new StringBuilder();
                        appendChildren(paced, id, rows, blockCount);
                        out.append(renderTable(b.path("table"), rows.toString()));
                    } else {
                        appendChildren(paced, id, out, blockCount);
                    }
                }
            }
            if (!v.path("has_more").asBoolean(false)) {
                break;
            }
            cursor = v.path("next_cursor").asText(null);
            if (cursor == null) {
                break;
            }
        }
    }

    /**
     * 把一个 block 渲染成一行文本（notion.rs:349-390）。
     * <b>认不出的类型返回它的纯文本而不是丢掉</b>：Notion 的 block 类型一直在加，
     * 硬编码白名单会让新类型静默消失；普通文字都在 {type}.rich_text 下。
     */
    static String renderBlock(JsonNode b) {
        String t = b.path("type").asText(null);
        if (t == null) {
            return null;
        }
        JsonNode inner = b.path(t);
        String text = richText(inner.path("rich_text"));

        return switch (t) {
            case "heading_1" -> "## " + text;
            case "heading_2" -> "### " + text;
            case "heading_3" -> "#### " + text;
            case "bulleted_list_item" -> "- " + text;
            case "numbered_list_item" -> "1. " + text;
            case "to_do" -> "- [" + (inner.path("checked").asBoolean(false) ? "x" : " ") + "] "
                    + text;
            case "quote" -> "> " + text;
            case "code" -> "```" + inner.path("language").asText("") + "\n" + text + "\n```";
            case "table_row" -> {
                JsonNode cells = inner.path("cells");
                if (!cells.isArray()) {
                    yield null;
                }
                List<String> parts = new ArrayList<>();
                for (JsonNode cell : cells) {
                    parts.add(richText(cell).replace("\\", "\\\\").replace("|", "\\|")
                            .replace("\r\n", "\n").replace("\r", "<br>").replace("\n", "<br>"));
                }
                yield "| " + String.join(" | ", parts) + " |";
            }
            // 分割线与图片没有 rich_text，但它们在正文里也没有信息量
            case "divider", "image", "video", "file" -> null;
            // child_page 的标题在 title 而不是 rich_text
            case "child_page" -> "- " + inner.path("title").asText("");
            default -> text.isBlank() ? null : text;
        };
    }

    /** 无列头的表用空表头占位，不能把第一条数据误标为列头（notion.rs:393-418）。 */
    static String renderTable(JsonNode table, String rows) {
        if (rows.isEmpty()) {
            return "";
        }
        long width = table.path("table_width").asLong(0);
        if (width <= 0) {
            return "";
        }
        boolean hasHeader = table.path("has_column_header").asBoolean(false);
        String separator = "|" + " --- |".repeat((int) width) + "\n";
        StringBuilder out = new StringBuilder("\n");
        if (!hasHeader) {
            out.append("|").append("  |".repeat((int) width)).append('\n');
            out.append(separator);
        }
        // Rust rows.lines()：不产生尾空行（split -1 会多出一个空行 = 多一个换行）
        List<String> lines = new ArrayList<>();
        rows.lines().forEach(lines::add);
        for (int index = 0; index < lines.size(); index++) {
            out.append(lines.get(index)).append(NL);
            if (index == 0 && hasHeader) {
                out.append(separator);
            }
        }
        out.append(NL);
        return out.toString();
    }

    /** rich_text 数组拼成纯文本（notion.rs:421-429）：加粗、链接都会把一句话切段。 */
    static String richText(JsonNode v) {
        if (!v.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode r : v) {
            out.append(r.path("plain_text").asText(""));
        }
        return out.toString();
    }

    /** 标题变成能当文件名的东西（notion.rs:432-443）。 */
    static String slug(String title) {
        StringBuilder s = new StringBuilder();
        for (char c : title.toCharArray()) {
            s.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        String trimmed = s.toString();
        while (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "untitled";
        }
        return trimmed.codePoints().limit(60)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
