package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tech.seexun.jknow.web.knowledge.pipeline.GuardedHttp.GuardedClient;

/**
 * Jira 工单来源连接器（jira_issues.rs 全量对等，M3 任务 9）：一张工单 = 一篇文档，
 * 正文里带<b>字段级</b>的变更史。
 *
 * <p>与 {@link GithubIssues} 同一个判断（别取现在，取变化），但 Jira 给的原料更强、
 * 取法更省：{@code search?expand=changelog} 一次调用就带回工单本体、完整变更史与评论，
 * <b>没有 N+1</b>。变更史是字段级的 from → to——from/to 本身就是一次认知变更的两端。
 *
 * <p><b>增量靠 JQL，不靠 since 参数</b>：Jira 没有 since，但 JQL 能表达
 * {@code updated >= "2026-08-30 12:00"}。时间格式必须是 Jira 认的那种（不是 RFC3339），
 * 且<b>要带引号</b>——这两点写错的症状都是 400，而不是「查不到」。
 *
 * <p>按 <b>API v2</b>（Jira Server/DC）写；Cloud 的 v3 把正文换成 ADF（一棵 JSON 树），
 * 需要一个渲染器，是另一件事——v2 在 Cloud 上通常仍可用且返回字符串。
 */
final class JiraIssues {

    /** 一次同步最多翻多少页；total 常常是几万，增量窗口之外的等下一次 JQL 取。 */
    static final int MAX_PAGES = 10;

    /** jira_issues.rs:47 PAGE_SIZE。 */
    static final int PAGE_SIZE = 50;

    /**
     * 想要的字段（jira_issues.rs:51-52）。<b>必须显式列</b>：不列 comment 就不返回评论，
     * 而默认返回全部字段会把响应撑到几百 KB 一条。
     */
    static final String FIELDS =
            "summary,status,issuetype,priority,created,updated,resolutiondate,labels,assignee,"
                    + "reporter,description,comment";

    private static final ObjectMapper JSON = new ObjectMapper();

    private JiraIssues() {
    }

    /** 一张工单（fields 的宽容读；JiraTime 的双格式解析在 {@link #parseJiraTime}）。 */
    record Issue(String key, Fields fields) {

        static Issue of(JsonNode n) {
            return new Issue(n.path("key").asText(""), Fields.of(n.path("fields")));
        }
    }

    record Fields(String summary, String description, OffsetDateTime created,
                  OffsetDateTime updated, OffsetDateTime resolutiondate, String status,
                  String issuetype, String priority, String assignee, String reporter,
                  List<String> labels, List<Comment> comments) {

        static Fields of(JsonNode f) {
            List<Comment> comments = new ArrayList<>();
            for (JsonNode c : f.path("comment").path("comments")) {
                comments.add(Comment.of(c));
            }
            List<String> labels = new ArrayList<>();
            for (JsonNode l : f.path("labels")) {
                if (l.isTextual()) {
                    labels.add(l.asText());
                }
            }
            return new Fields(textOrNull(f, "summary"), textOrNull(f, "description"),
                    parseJiraTime(f.path("created").asText(null)),
                    parseJiraTime(f.path("updated").asText(null)),
                    parseJiraTime(f.path("resolutiondate").asText(null)),
                    named(f, "status"), named(f, "issuetype"), named(f, "priority"),
                    user(f, "assignee"), user(f, "reporter"), labels, comments);
        }
    }

    record Comment(String author, OffsetDateTime created, String body) {

        static Comment of(JsonNode n) {
            return new Comment(user(n, "author"), parseJiraTime(n.path("created").asText(null)),
                    textOrNull(n, "body"));
        }
    }

    /** 一条变更（只有 expand=changelog 时才有整棵 changelog）。 */
    record History(OffsetDateTime created, String author, String field, String from, String to) {
    }

    /**
     * Jira 的时间戳是 {@code 2026-08-24T11:11:52.944+0000}——<b>没有冒号的时区偏移</b>，
     * 不是 RFC3339（jira_issues.rs:88-101）。两种都要能收（Cloud 某些端点给真 RFC3339）。
     */
    static OffsetDateTime parseJiraTime(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // 继续试 Jira 自家格式
        }
        try {
            return OffsetDateTime.parse(raw,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String named(JsonNode f, String field) {
        String name = f.path(field).path("name").asText(null);
        return name == null || name.isEmpty() ? null : name;
    }

    private static String user(JsonNode f, String field) {
        String name = f.path(field).path("displayName").asText(null);
        return name == null || name.isEmpty() ? null : name;
    }

    /**
     * 把一张工单排成一篇文档（jira_issues.rs:160-262 render）。纯函数，不联网。
     * 时间写到秒、带 Z（#691），Jira 给的毫秒不写——抽取器只认到秒。
     *
     * @param rawIssue 原始 JSON——changelog 只在 expand=changelog 时存在，从原始树里读
     */
    static String render(Issue issue, JsonNode rawIssue) {
        Fields f = issue.fields();
        StringBuilder out = new StringBuilder();
        out.append("# ").append(issue.key()).append(' ')
                .append(f.summary() == null ? "" : f.summary()).append("\n\n");
        if (f.reporter() != null && f.created() != null) {
            out.append("Reported by ").append(f.reporter()).append(" on ")
                    .append(GithubIssues.stamp(f.created())).append(".\n");
        }
        if (f.issuetype() != null) {
            out.append("Type ").append(f.issuetype()).append(".\n");
        }
        if (f.status() != null) {
            out.append("Currently ").append(f.status()).append(".\n");
        }
        if (f.priority() != null) {
            out.append("Priority ").append(f.priority()).append(".\n");
        }
        if (f.assignee() != null) {
            out.append("Assigned to ").append(f.assignee()).append(".\n");
        }
        if (f.resolutiondate() != null) {
            out.append("Resolved on ").append(GithubIssues.stamp(f.resolutiondate())).append(".\n");
        }
        if (!f.labels().isEmpty()) {
            out.append("Labelled ").append(String.join(", ", f.labels())).append(".\n");
        }
        if (f.description() != null && !f.description().isBlank()) {
            out.append("\n## Description\n\n").append(f.description().strip()).append('\n');
        }
        // 字段级变更史：端点的顺序不是契约，排序自己做——排错了的后果是历史倒着讲
        List<History> lines = new ArrayList<>();
        for (JsonNode h : changelogHistories(rawIssue)) {
            OffsetDateTime at = parseJiraTime(h.path("created").asText(null));
            if (at == null) {
                continue;
            }
            String author = h.path("author").path("displayName").asText(null);
            if (author == null || author.isEmpty()) {
                author = "?";
            }
            for (JsonNode item : h.path("items")) {
                String field = item.path("field").asText(null);
                if (field == null) {
                    continue;
                }
                String from = item.path("fromString").asText(null);
                String to = item.path("toString").asText(null);
                lines.add(new History(at, author, field,
                        from == null ? "(empty)" : from, to == null ? "(empty)" : to));
            }
        }
        lines.sort(Comparator.comparing(History::created));
        if (!lines.isEmpty()) {
            out.append("\n## History\n\n");
            for (History h : lines) {
                out.append("- ").append(GithubIssues.stamp(h.created())).append(" — ")
                        .append(h.author()).append(" changed ").append(h.field()).append(": ")
                        .append(h.from()).append(" → ").append(h.to()).append('\n');
            }
        }
        if (!f.comments().isEmpty()) {
            out.append("\n## Comments\n\n");
            for (Comment c : f.comments()) {
                String body = c.body() == null ? "" : c.body().strip();
                if (body.isEmpty()) {
                    continue; // 空评论不该留下一个只有标题的空节（与 GitHub 同口径）
                }
                String who = c.author() != null ? c.author() : "?";
                String at = c.created() != null ? GithubIssues.stamp(c.created()) : "?";
                out.append("### ").append(who).append(" on ").append(at)
                        .append("\n\n").append(body).append("\n\n");
            }
        }
        return out.toString();
    }

    private static Iterable<JsonNode> changelogHistories(JsonNode rawIssue) {
        List<JsonNode> out = new ArrayList<>();
        if (rawIssue != null) {
            for (JsonNode h : rawIssue.path("changelog").path("histories")) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * 增量用的 JQL（jira_issues.rs:266-276 jql 逐字）。<b>时间格式是 Jira 自己的</b>
     * （yyyy-MM-dd HH:mm）且要带引号——写错的症状都是 400，不是「查不到」。
     */
    static String jql(String project, OffsetDateTime since) {
        StringBuilder q = new StringBuilder("project = ").append(project);
        if (since != null) {
            q.append(" AND updated >= \"").append(since.withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('"');
        }
        q.append(" ORDER BY updated ASC");
        return q.toString();
    }

    /**
     * 分页取（jira_issues.rs:283-330 fetch_all）：startAt/maxResults，把 <b>total</b> 一起
     * 返回——截断了就得说出来，取回 500 条而服务端有 14506 条时，「同步完成」是误导。
     */
    static FetchResult fetchAll(GuardedClient http, String baseUrl, String jql, String auth)
            throws IOException {
        List<Issue> out = new ArrayList<>();
        List<JsonNode> rawIssues = new ArrayList<>();
        long total = 0;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        for (int page = 0; page < MAX_PAGES; page++) {
            StringBuilder url = new StringBuilder(base).append("/rest/api/2/search")
                    .append("?jql=").append(enc(jql))
                    .append("&expand=changelog")
                    .append("&fields=").append(enc(FIELDS))
                    .append("&maxResults=").append(PAGE_SIZE)
                    .append("&startAt=").append(page * PAGE_SIZE);
            List<String[]> headers = new ArrayList<>();
            headers.add(new String[] {"Accept", "application/json"});
            if (auth != null) {
                headers.add(new String[] {"Authorization", auth});
            }
            GuardedClient.Response resp = http.exchange("GET", URI.create(url.toString()),
                    headers, null);
            if (resp.status() / 100 != 2) {
                // Jira 把 JQL 语法错误也报成 400，正文里才是原因——只说 HTTP 400 会让人
                // 去查网络，而真正该改的是 project key 或时间格式
                String detail = resp.text();
                detail = detail.length() > 200 ? detail.substring(0, 200) : detail;
                throw new IOException("HTTP " + resp.status() + " from Jira: " + detail);
            }
            JsonNode pageData = JSON.readTree(resp.body() == null || resp.body().length == 0
                    ? "{}" : resp.text());
            total = pageData.path("total").asLong(0);
            int n = 0;
            for (JsonNode issue : pageData.path("issues")) {
                out.add(Issue.of(issue));
                rawIssues.add(issue);
                n++;
            }
            if (n < PAGE_SIZE) {
                break;
            }
        }
        return new FetchResult(out, rawIssues, total);
    }

    /** (issues, 原始 JSON——render 要读 changelog, total)。 */
    record FetchResult(List<Issue> issues, List<JsonNode> rawIssues, long total) {
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
