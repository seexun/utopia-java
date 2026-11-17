package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.GuardedClient;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Limits;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.Reach;
import tech.seexun.jknow.web.knowledge.pipeline.NotionConnector.Paced;

/**
 * Notion 连接器（notion.rs 内联 mod tests + notion_tests.rs 全量移植——分页/嵌套/
 * 预算/限流节奏/读不出的页不连累别的页；stub 用 JDK HttpServer，按 start_cursor 与
 * page_size 分发，与 Rust wiremock 同一职责面）。
 */
class NotionConnectorTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final AtomicInteger searchCalls = new AtomicInteger();

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---- 内联纯函数（notion.rs:449-517） ----

    /** 标题属性的名字是任意的：按类型找，不按名字找。 */
    @Test
    void aTitleIsFoundByTypeNotByName() throws Exception {
        for (String key : new String[] {"title", "Name", "名称", "任务"}) {
            ObjectNode page = JSON.createObjectNode();
            ObjectNode props = page.putObject("properties");
            ObjectNode title = props.putObject(key);
            title.put("type", "title");
            ArrayNode arr = title.putArray("title");
            arr.addObject().put("plain_text", "季度复盘");
            ObjectNode status = props.putObject("Status");
            status.put("type", "select");
            status.putObject("select").put("name", "Done");
            assertEquals("季度复盘", NotionConnector.pageTitle(page),
                    "属性名 " + key + " 时找不到标题");
        }
    }

    /** 富文本是分段的——加粗、链接都会把一句话切开，拼不全就会丢字。 */
    @Test
    void richTextSegmentsJoinBackIntoOneLine() throws Exception {
        ArrayNode v = JSON.createArrayNode();
        v.addObject().put("plain_text", "把总部搬到");
        v.addObject().put("plain_text", "深圳");
        v.addObject().put("plain_text", "了");
        assertEquals("把总部搬到深圳了", NotionConnector.richText(v));
    }

    /** 认不出的 block 类型不能丢：新类型静默消失比丢字更隐蔽。 */
    @Test
    void anUnknownBlockKeepsItsText() throws Exception {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "some_new_block_type_2027");
        b.putObject("some_new_block_type_2027").putArray("rich_text")
                .addObject().put("plain_text", "还是有内容的");
        assertEquals("还是有内容的", NotionConnector.renderBlock(b));
    }

    /** 没有文字的装饰性 block 该消失，否则正文里全是空行。 */
    @Test
    void aDividerRendersToNothing() throws Exception {
        ObjectNode b = JSON.createObjectNode();
        b.put("type", "divider");
        b.putObject("divider");
        assertNull(NotionConnector.renderBlock(b));
    }

    /** 文件名不能带路径分隔符或换行。 */
    @Test
    void aSlugIsSafeAsAFilename() {
        assertEquals("2026-Q3---复盘", NotionConnector.slug("2026 Q3 / 复盘"));
        assertEquals("untitled", NotionConnector.slug("///"));
        assertTrue(NotionConnector.slug("x".repeat(200)).codePointCount(0,
                NotionConnector.slug("x".repeat(200)).length()) <= 60);
    }

    /** Retry-After 的几档：秒数照做、缺失和垃圾按缺省、离谱的截顶。 */
    @Test
    void retryAfterFollowsTheHeaderWithinBounds() {
        assertEquals(Duration.ofSeconds(3), NotionConnector.retryAfter("3"));
        assertEquals(Duration.ofMillis(500), NotionConnector.retryAfter(" 0.5 "));
        assertEquals(Duration.ofSeconds(1), NotionConnector.retryAfter(null));
        assertEquals(Duration.ofSeconds(1), NotionConnector.retryAfter("soon"));
        assertEquals(Duration.ofSeconds(1), NotionConnector.retryAfter("-2"));
        assertEquals(Duration.ofSeconds(30), NotionConnector.retryAfter("600"));
    }

    // ---- 取页遍历（notion_tests.rs；stub 按路径 + start_cursor 分发） ----

    private static JsonNode block(String id, String kind, String text, boolean hasChildren) {
        ObjectNode b = JSON.createObjectNode();
        b.put("id", id);
        b.put("type", kind);
        b.put("has_children", hasChildren);
        b.putObject(kind).putArray("rich_text").addObject().put("plain_text", text);
        return b;
    }

    /** (parent, cursor|null) → (blocks, nextCursor|null)；start_cursor 必须精确匹配。 */
    private final Map<String, Map<String, Children>> byParent = new ConcurrentHashMap<>();

    record Children(List<JsonNode> blocks, String nextCursor) {
    }

    private void children(String parent, String cursor, List<JsonNode> blocks,
                          String nextCursor) {
        byParent.computeIfAbsent(parent, k -> new ConcurrentHashMap<>())
                .put(cursor == null ? "" : cursor, new Children(blocks, nextCursor));
    }

    private Paced paced() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::dispatch);
        server.start();
        GuardedClient http = GuardedHttp.clientFor(
                java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/"),
                Reach.OPERATOR, Limits.defaults());
        return new NotionConnector.Paced(http, "http://localhost:"
                        + server.getAddress().getPort());
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        if (path.startsWith("/search")) {
            searchCalls.incrementAndGet();
            ObjectNode body = JSON.createObjectNode();
            body.putArray("results").add(JSON.createObjectNode()
                    .put("id", "page").putObject("properties"));
            body.put("has_more", false);
            respond(exchange, 200, body.toString());
            return;
        }
        assertTrue(query != null && query.contains("page_size=100"),
                "children 端点必须带 page_size=100: " + query);
        requestPaths.add(path);
        String parent = path.substring("/blocks/".length(), path.length() - "/children".length());
        String cursor = param(query, "start_cursor");
        Children c = byParent.getOrDefault(parent, Map.of()).get(cursor == null ? "" : cursor);
        if (c == null) {
            respond(exchange, 404, "{\"message\":\"no such children fixture\"}");
            return;
        }
        ObjectNode body = JSON.createObjectNode();
        ArrayNode results = body.putArray("results");
        c.blocks().forEach(results::add);
        body.put("has_more", c.nextCursor() != null);
        if (c.nextCursor() != null) {
            body.put("next_cursor", c.nextCursor());
        }
        respond(exchange, 200, body.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String pageText(Paced paced, String pageId) throws IOException {
        return NotionConnector.pageText(paced, pageId);
    }

    /** notion_tests.rs:62-96：toggle 的子树读完再继续兄弟块。 */
    @Test
    void toggleBodyIsReadBeforeTheNextSibling() throws Exception {
        children("page", null, List.of(
                block("toggle", "toggle", "Quarterly policy", true),
                block("after", "paragraph", "After toggle", false)), null);
        children("toggle", null, List.of(
                block("paragraph", "paragraph", "Revenue was 42 million.", false)), null);
        assertEquals("Quarterly policy\nRevenue was 42 million.\nAfter toggle\n",
                pageText(paced(), "page"));
        assertEquals(List.of("/blocks/page/children", "/blocks/toggle/children"), requestPaths);
    }

    /** notion_tests.rs:99-164：没有 rich_text 的 column 容器保住嵌套正文的阅读顺序。 */
    @Test
    void columnsWithoutRichTextKeepNestedBodyInReadingOrder() throws Exception {
        ObjectNode columns = JSON.createObjectNode();
        columns.put("id", "columns");
        columns.put("type", "column_list");
        columns.put("has_children", true);
        columns.putObject("column_list");
        ObjectNode left = JSON.createObjectNode();
        left.put("id", "left");
        left.put("type", "column");
        left.put("has_children", true);
        left.putObject("column");
        ObjectNode right = JSON.createObjectNode();
        right.put("id", "right");
        right.put("type", "column");
        right.put("has_children", true);
        right.putObject("column");
        children("page", null, List.of(columns,
                block("after", "paragraph", "After columns", false)), null);
        children("columns", null, List.of(left, right), null);
        children("left", null, List.of(
                block("nested", "toggle", "Left toggle", true),
                block("left-after", "paragraph", "Left after", false)), null);
        children("nested", null, List.of(block("body", "paragraph", "Deep body", false)), null);
        children("right", null, List.of(block("right-body", "paragraph", "Right body", false)),
                null);
        assertEquals("Left toggle\nDeep body\nLeft after\nRight body\nAfter columns\n",
                pageText(paced(), "page"));
        assertEquals(List.of("/blocks/page/children", "/blocks/columns/children",
                "/blocks/left/children", "/blocks/nested/children", "/blocks/right/children"),
                requestPaths);
    }

    /** notion_tests.rs:167-235：分页游标属于各自的父块。 */
    @Test
    void paginationCursorsBelongToEachParent() throws Exception {
        children("page", null, List.of(
                block("a", "toggle", "A", true), block("b", "toggle", "B", true)),
                "root-next");
        children("a", null, List.of(block("a1", "paragraph", "A1", false)), "a-next");
        children("a", "a-next", List.of(block("a2", "paragraph", "A2", false)), null);
        children("b", null, List.of(block("b1", "paragraph", "B1", false)), "b-next");
        children("b", "b-next", List.of(block("b2", "paragraph", "B2", false)), null);
        children("page", "root-next", List.of(block("end", "paragraph", "End", false)), null);
        assertEquals("A\nA1\nA2\nB\nB1\nB2\nEnd\n", pageText(paced(), "page"));
        assertEquals(List.of("/blocks/page/children", "/blocks/a/children", "/blocks/a/children",
                "/blocks/b/children", "/blocks/b/children", "/blocks/page/children"),
                requestPaths);
    }

    /** notion_tests.rs:238-275：块预算整页共享，耗尽后不再发任何请求。 */
    @Test
    void blockBudgetIsSharedAndStopsBeforeAnyFurtherRequest() throws Exception {
        ObjectNode container = JSON.createObjectNode();
        container.put("id", "container");
        container.put("type", "column");
        container.put("has_children", true);
        container.putObject("column");
        List<JsonNode> first = new ArrayList<>();
        first.add(container);
        first.add(block("sibling", "toggle", "Must not appear", true));
        children("page", null, first, "root-next");
        // 无文字容器也占一块；子层第五页的第 99 块耗尽整页预算
        for (int page = 0; page < 5; page++) {
            String cursor = "child-" + page;
            String next = "child-" + (page + 1);
            List<JsonNode> blocks = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                String id = "item-" + (page * 100 + index);
                blocks.add(block(id, "paragraph", id, page == 4 && index == 98));
            }
            children("container", page == 0 ? null : cursor, blocks, next);
        }
        String text = pageText(paced(), "page");
        assertEquals(NotionConnector.MAX_BLOCKS_PER_PAGE - 1, text.lines().count(),
                "first=" + text.lines().findFirst().orElse("") + " last=" + text.lines().reduce((a, b) -> b).orElse(""));
        assertTrue(text.endsWith("item-498\n"));
        assertFalse(text.contains("Must not appear"));
        assertEquals(6, requestPaths.size());
    }

    /** notion_tests.rs:278-304：恰好用满预算时不再取下一页。 */
    @Test
    void exactTopLevelBudgetDoesNotFetchAnotherPage() throws Exception {
        for (int page = 0; page < 5; page++) {
            String cursor = "root-" + page;
            String next = "root-" + (page + 1);
            List<JsonNode> blocks = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                blocks.add(block(page + "-" + index, "paragraph", "Body", false));
            }
            children("page", page == 0 ? null : cursor, blocks, next);
        }
        String text = pageText(paced(), "page");
        assertEquals(NotionConnector.MAX_BLOCKS_PER_PAGE, text.lines().count());
        assertEquals(5, requestPaths.size());
    }

    /** notion_tests.rs:307-319：独立页面与数据库不内联进父页正文。 */
    @Test
    void independentPagesAndDatabasesAreNotInlined() throws Exception {
        ObjectNode childPage = JSON.createObjectNode();
        childPage.put("id", "child");
        childPage.put("type", "child_page");
        childPage.put("has_children", true);
        childPage.putObject("child_page").put("title", "Separate page");
        ObjectNode childDb = JSON.createObjectNode();
        childDb.put("id", "database");
        childDb.put("type", "child_database");
        childDb.put("has_children", true);
        childDb.putObject("child_database").put("title", "Separate database");
        children("page", null, List.of(childPage, childDb,
                block("body", "paragraph", "Parent body", false)), null);
        assertEquals("- Separate page\nParent body\n", pageText(paced(), "page"));
        assertEquals(List.of("/blocks/page/children"), requestPaths);
    }

    /** notion_tests.rs:322-353：嵌套请求共享节奏并尊重 Retry-After。 */
    @Test
    void nestedRequestsSharePacingAndHonorRetryAfter() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<Instant> times = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            calls.add(exchange.getRequestURI().getPath());
            times.add(Instant.now());
            int n = calls.size();
            if (n == 2) {
                exchange.getResponseHeaders().set("Retry-After", "0.5");
                respond(exchange, 429, "{\"message\":\"slow down\"}");
                return;
            }
            ObjectNode body = JSON.createObjectNode();
            body.putArray("results");
            if (n == 1) {
                ((ArrayNode) body.get("results")).add(block("child", "toggle", "Parent", true));
            } else {
                ((ArrayNode) body.get("results")).add(block("body", "paragraph", "Child", false));
            }
            body.put("has_more", false);
            respond(exchange, 200, body.toString());
        });
        server.start();
        GuardedClient http = GuardedHttp.clientFor(
                java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/"),
                Reach.OPERATOR, Limits.defaults());
        NotionConnector.Paced paced = new NotionConnector.Paced(http,
                "http://localhost:" + server.getAddress().getPort());
        assertEquals("Parent\nChild\n", NotionConnector.pageText(paced, "page"));
        assertEquals(List.of("/blocks/page/children", "/blocks/child/children",
                "/blocks/child/children"), calls);
        Duration since0 = Duration.between(times.get(0), times.get(1));
        Duration since1 = Duration.between(times.get(1), times.get(2));
        assertTrue(since0.compareTo(NotionConnector.MIN_INTERVAL) >= 0,
                "请求间隔至少 MIN_INTERVAL: " + since0);
        assertTrue(since1.compareTo(Duration.ofMillis(500)) >= 0,
                "429 后按 Retry-After 等: " + since1);
    }

    /** notion_tests.rs:356-403：读不出的页整页跳过，别的页照常回来（点名报错）。 */
    @Test
    void aPageWhoseBodyCannotBeReadIsLeftOutAndTheOthersStillComeBack() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/search")) {
                ObjectNode body = JSON.createObjectNode();
                ArrayNode results = body.putArray("results");
                for (String[] page : new String[][] {{"page", "Quarterly policy"},
                        {"other", "Travel policy"}}) {
                    ObjectNode p = results.addObject();
                    p.put("id", page[0]);
                    ObjectNode title = p.putObject("properties").putObject("Name");
                    title.put("type", "title");
                    title.putArray("title").addObject().put("plain_text", page[1]);
                    p.put("last_edited_time", "2026-10-01T12:00:00Z");
                }
                body.put("has_more", false);
                respond(exchange, 200, body.toString());
                return;
            }
            if (path.startsWith("/blocks/page/")) {
                respond(exchange, 503, "{\"message\":\"unavailable\"}");
                return;
            }
            if (path.startsWith("/blocks/other/")) {
                ObjectNode body = JSON.createObjectNode();
                body.putArray("results").add(block("p", "paragraph",
                        "Economy class under six hours", false));
                body.put("has_more", false);
                respond(exchange, 200, body.toString());
                return;
            }
            respond(exchange, 404, "{\"message\":\"no\"}");
        });
        server.start();
        GuardedClient http = GuardedHttp.clientFor(
                java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/"),
                Reach.OPERATOR, Limits.defaults());
        NotionConnector.Paced paced = new NotionConnector.Paced(http,
                "http://localhost:" + server.getAddress().getPort());
        NotionConnector.Result result = NotionConnector.fetchPages(paced, null);
        // 读坏的那一页不带着半截正文或只有标题回来，另一页不受它连累
        assertEquals(1, result.pages().size());
        assertEquals("notion://other", result.pages().get(0).externalKey());
        assertTrue(result.pages().get(0).text().contains("Economy class under six hours"));
        assertFalse(result.truncated());
        assertEquals(List.of("notion page page: notion blocks returned 503: unavailable"),
                result.unread());
    }

    /** notion_tests.rs:406-473：嵌套表保列头与分页行；单元格转义/换行规则。 */
    @Test
    void aNestedTableKeepsItsHeaderCellsAndPaginatedRows() throws Exception {
        ObjectNode table = JSON.createObjectNode();
        table.put("id", "table");
        table.put("type", "table");
        table.put("has_children", true);
        ObjectNode tableInner = table.putObject("table");
        tableInner.put("table_width", 3);
        tableInner.put("has_column_header", true);
        tableInner.put("has_row_header", true);
        children("page", null, List.of(block("toggle", "toggle", "Quarterly policy", true)),
                null);
        children("toggle", null, List.of(table,
                block("after", "paragraph", "After table", false)), null);
        ObjectNode row1 = JSON.createObjectNode();
        row1.put("type", "table_row");
        ArrayNode cells1 = row1.putObject("table_row").putArray("cells");
        for (String cell : new String[] {"Metric", "Value", "Notes"}) {
            cells1.addArray().addObject().put("plain_text", cell);
        }
        ObjectNode row2 = JSON.createObjectNode();
        row2.put("type", "table_row");
        ArrayNode cells2 = row2.putObject("table_row").putArray("cells");
        cells2.addArray().addObject().put("plain_text", "Revenue");
        cells2.addArray();
        cells2.addArray().addObject().put("plain_text", "Pending");
        children("table", null, List.of(row1, row2), "table-next");
        ObjectNode row3 = JSON.createObjectNode();
        row3.put("type", "table_row");
        ArrayNode cells3 = row3.putObject("table_row").putArray("cells");
        ArrayNode c1 = cells3.addArray();
        c1.addObject().put("plain_text", "Pipe | slash \\");
        ArrayNode c2 = cells3.addArray();
        c2.addObject().put("plain_text", "42");
        c2.addObject().put("plain_text", " million");
        ArrayNode c3 = cells3.addArray();
        c3.addObject().put("plain_text", "First\r\nSecond\nThird\rLast");
        children("table", "table-next", List.of(row3), null);
        String text = pageText(paced(), "page");
        assertEquals("Quarterly policy\n\n| Metric | Value | Notes |\n"
                + "| --- | --- | --- |\n| Revenue |  | Pending |\n"
                + "| Pipe \\| slash \\\\ | 42 million | First<br>Second<br>Third<br>Last |\n\n"
                + "After table\n", text);
        assertEquals(List.of("/blocks/page/children", "/blocks/toggle/children",
                "/blocks/table/children", "/blocks/table/children"), requestPaths);
        List<tech.seexun.jknow.ingest.ChunkPiece> chunks =
                tech.seexun.jknow.ingest.Chunker.chunkText(text);
        assertTrue(chunks.stream().anyMatch(c -> c.text()
                .contains("| --- | --- | --- |")));
    }

    /** notion_tests.rs:476-508：无列头的表用空表头占位，不把第一条数据当列头。 */
    @Test
    void aTableWithoutColumnHeadersKeepsTheFirstRowAsData() throws Exception {
        ObjectNode table = JSON.createObjectNode();
        table.put("id", "table");
        table.put("type", "table");
        table.put("has_children", true);
        ObjectNode tableInner = table.putObject("table");
        tableInner.put("table_width", 2);
        tableInner.put("has_column_header", false);
        children("page", null, List.of(
                block("before", "paragraph", "Before table", false), table,
                block("after", "paragraph", "After table", false)), null);
        ObjectNode row1 = JSON.createObjectNode();
        row1.put("type", "table_row");
        ArrayNode cells1 = row1.putObject("table_row").putArray("cells");
        cells1.addArray().addObject().put("plain_text", "Revenue");
        cells1.addArray().addObject().put("plain_text", "42 million");
        ObjectNode row2 = JSON.createObjectNode();
        row2.put("type", "table_row");
        ArrayNode cells2 = row2.putObject("table_row").putArray("cells");
        cells2.addArray();
        cells2.addArray();
        children("table", null, List.of(row1, row2), null);
        assertEquals("Before table\n\n|  |  |\n| --- | --- |\n| Revenue | 42 million |\n"
                + "|  |  |\n\nAfter table\n", pageText(paced(), "page"));
    }
}
