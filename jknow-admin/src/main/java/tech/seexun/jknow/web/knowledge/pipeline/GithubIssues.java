package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitHub 工单来源连接器（github_issues.rs 全量对等，M3 任务 9）：一张工单 = 一篇文档，
 * 正文里带<b>它的状态变更史</b>。
 *
 * <p><b>为什么不是只抓当前状态</b>：工单最有价值的部分不是「它现在是 closed」，而是
 * 「它 8 月 18 日开出、8 月 20 日关掉、中间被指派给谁、标签怎么变的」。与维基百科历史
 * 快照同一个判断：<b>别取现在，取变化</b>——区别是 GitHub 直接把变化给了你。
 *
 * <p><b>工单列表增量筛选，评论与事件逐工单读取</b>（github_issues.rs:12-32 的教训）：
 * 仓库级 {@code issues/comments} 的 since 只给改动过的评论，而文档是整篇替换——只改标题
 * 也会把所有旧讨论从当前文档删掉；仓库级 {@code issues/events} 不支持 since 且会被 PR
 * 事件挤出分页上限，于是「状态变更史」悄悄变成空的。所以评论与事件都逐工单取、不带
 * since，只对筛选后本轮要写的工单请求（N 是本轮要写的工单数，有列表的 since 兜着，
 * 通常是个位数）。
 *
 * <p><b>关于 PR</b>：GitHub 的模型里 PR 也是 issue，靠 {@code pull_request} 字段区分。
 * 默认排除——问「工单系统」要的是工单；但有些仓库的决策记录写在 PR 描述里，留了开关。
 */
final class GithubIssues {

    /** 一次同步最多取多少页（每页 100）；GitHub 分页没有天然终点（github_issues.rs:46）。 */
    static final int MAX_PAGES = 10;

    /** github_issues.rs:47 PER_PAGE。 */
    static final int PER_PAGE = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GithubIssues() {
    }

    /** github_issues.rs:50-66 Issue（手动宽容读：null/缺字段当默认，多余字段忽略）。 */
    record Issue(long number, String title, String state, String body,
                 OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime closedAt,
                 List<String> labels, List<String> assignees, String user,
                 boolean pullRequest) {

        static Issue of(JsonNode n) {
            return new Issue(n.path("number").asLong(0), text(n, "title"),
                    text(n, "state"), text(n, "body"),
                    time(n, "created_at"), time(n, "updated_at"), time(n, "closed_at"),
                    names(n, "labels", "name"), names(n, "assignees", "login"),
                    n.path("user").isMissingNode() || n.path("user").isNull() ? null
                            : text(n.path("user"), "login"),
                    n.hasNonNull("pull_request"));
        }
    }

    /** github_issues.rs:78-85 Comment——issue_url 末段就是它挂的工单号。 */
    record Comment(long issueNumber, String user, OffsetDateTime createdAt, String body) {

        static Comment of(JsonNode n) {
            return new Comment(issueNumberFromUrl(n.path("issue_url").asText("")),
                    n.path("user").isMissingNode() || n.path("user").isNull() ? null
                            : text(n.path("user"), "login"),
                    time(n, "created_at"), text(n, "body"));
        }
    }

    /** github_issues.rs:87-94 Event（逐工单端点不返回 issue 字段——形状由这里宽容掉）。 */
    record Event(String event, OffsetDateTime createdAt, String actor, String label,
                 String assignee) {

        static Event of(JsonNode n) {
            return new Event(text(n, "event"), time(n, "created_at"),
                    actorOf(n, "actor"),
                    n.path("label").isMissingNode() || n.path("label").isNull() ? null
                            : text(n.path("label"), "name"),
                    actorOf(n, "assignee"));
        }
    }

    private static String actorOf(JsonNode n, String field) {
        JsonNode actor = n.get(field);
        return actor == null || actor.isNull() ? null : text(actor, "login");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> names(JsonNode n, String array, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : n.path(array)) {
            String name = item.path(field).asText(null);
            if (name != null && !name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    static OffsetDateTime time(JsonNode n, String field) {
        String raw = n.path(field).asText(null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /**
     * 评论的 issue_url 末段就是工单号（github_issues.rs:100-102）。解析失败返回 -1
     * 而不是炸：GitHub 换了 URL 形状时，代价该是「这条评论没归到工单上」。
     */
    static long issueNumberFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(url.substring(slash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 工单上的一个时刻：到秒、带 Z（time_text.rs:22-32 world 的 second 档）。 */
    static String stamp(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US));
    }

    /**
     * 把一张工单连同它的评论与事件排成一篇文档（github_issues.rs:113-193 render）。
     * <b>纯函数，不联网</b>——取回与组织分开，组织这一半测得动。
     *
     * <p>抬头写成带日期的陈述句而不是键值对：抽取器读的是句子。「opened by X on
     * 2026-08-18T16:18:27Z」能抽出带 valid_from 的事实，「created_at: 2026-08-18」
     * 则要它自己猜这是什么意思。<b>时间写到秒</b>（#691）：GitHub 给的是带 Z 的时刻，
     * 截到天会让起点最多提前 24 小时，同一天的两件事还会被判成同时发生的冲突。
     */
    static String render(Issue issue, List<Comment> comments, List<Event> events) {
        StringBuilder out = new StringBuilder();
        out.append("# #").append(issue.number()).append(' ').append(issue.title()).append("\n\n");
        if (issue.user() != null) {
            out.append("Opened by ").append(issue.user()).append(" on ")
                    .append(stamp(issue.createdAt())).append(".\n");
        }
        out.append("Currently ").append(issue.state()).append(".\n");
        if (issue.closedAt() != null) {
            out.append("Closed on ").append(stamp(issue.closedAt())).append(".\n");
        }
        if (!issue.labels().isEmpty()) {
            out.append("Labelled ").append(String.join(", ", issue.labels())).append(".\n");
        }
        if (!issue.assignees().isEmpty()) {
            out.append("Assigned to ").append(String.join(", ", issue.assignees())).append(".\n");
        }
        if (issue.body() != null && !issue.body().isBlank()) {
            out.append("\n## Description\n\n").append(issue.body().strip()).append('\n');
        }
        // 状态变更史是这个来源存在的理由：每一行都带日期，账本拿到的是「何时变成什么」
        if (!events.isEmpty()) {
            out.append("\n## History\n\n");
            for (Event e : events) {
                String who = e.actor() != null ? e.actor() : "?";
                String detail = "";
                if (("labeled".equals(e.event()) || "unlabeled".equals(e.event()))
                        && e.label() != null) {
                    detail = " (" + e.label() + ")";
                } else if (("assigned".equals(e.event()) || "unassigned".equals(e.event()))
                        && e.assignee() != null) {
                    detail = " (" + e.assignee() + ")";
                }
                out.append("- ").append(stamp(e.createdAt())).append(" — ").append(e.event())
                        .append(" by ").append(who).append(detail).append('\n');
            }
        }
        if (!comments.isEmpty()) {
            out.append("\n## Comments\n\n");
            for (Comment c : comments) {
                String body = c.body() == null ? "" : c.body().strip();
                if (body.isEmpty()) {
                    continue; // 空评论不该留下一个只有标题的空节
                }
                String who = c.user() != null ? c.user() : "?";
                out.append("### ").append(who).append(" on ").append(stamp(c.createdAt()))
                        .append("\n\n").append(body).append("\n\n");
            }
        }
        return out.toString();
    }

    /** 只保留这张工单的评论，按时间升序（github_issues.rs:196-203）。 */
    static List<Comment> commentsForIssue(Issue issue, List<Comment> comments) {
        List<Comment> own = new ArrayList<>();
        for (Comment c : comments) {
            if (c.issueNumber() == issue.number()) {
                own.add(c);
            }
        }
        own.sort(java.util.Comparator.comparing(Comment::createdAt));
        return own;
    }

    /** 事件按时间升序——端点的顺序不是契约，排错了的后果是历史倒着讲。 */
    static List<Event> sortEvents(List<Event> events) {
        List<Event> out = new ArrayList<>(events);
        out.sort(java.util.Comparator.comparing(Event::createdAt));
        return out;
    }

    /**
     * 分页取一个端点，直到空页或触顶（github_issues.rs:213-261 fetch_all）。
     * 限流说清楚是限流：未认证时每小时 60 次，报成通用 HTTP 错会让人去查网络，
     * 而正确的动作是配一个令牌。
     */
    static <T> List<T> fetchAll(GuardedHttp.GuardedClient http, String base,
                                List<String[]> query, String auth, JsonParser<T> itemParser)
            throws IOException {
        List<T> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            StringBuilder url = new StringBuilder(base);
            String sep = base.contains("?") ? "&" : "?";
            for (String[] pair : query) {
                url.append(sep).append(pair[0]).append('=')
                        .append(URLEncODE(pair[1]));
                sep = "&";
            }
            url.append(sep).append("per_page=").append(PER_PAGE).append("&page=").append(page);
            List<String[]> headers = new ArrayList<>();
            if (auth != null) {
                headers.add(new String[] {"Authorization", auth});
            }
            GuardedHttp.GuardedClient.Response resp =
                    http.exchange("GET", URI.create(url.toString()), headers, null);
            if (resp.status() / 100 != 2) {
                String remaining = resp.header("x-ratelimit-remaining");
                if (resp.status() == 403 && "0".equals(remaining)) {
                    throw new IOException("GitHub 限流：本小时配额已用尽。未认证时每小时 60 次，"
                            + "配一个令牌可提到 5000");
                }
                throw new IOException("HTTP " + resp.status() + " from GitHub");
            }
            JsonNode batch = JSON.readTree(resp.body() == null || resp.body().length == 0
                    ? "[]" : resp.text());
            int n = batch.isArray() ? batch.size() : 0;
            for (JsonNode item : batch) {
                out.add(itemParser.parse(item));
            }
            if (n < PER_PAGE) {
                break;
            }
        }
        return out;
    }

    /** 单条记录的宽容解析器。 */
    interface JsonParser<T> {
        T parse(JsonNode node);
    }

    /** 查询串转义（form 编码够用：':' 逃成 %3A，服务端解码后是同一个值）。 */
    private static String URLEncODE(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
