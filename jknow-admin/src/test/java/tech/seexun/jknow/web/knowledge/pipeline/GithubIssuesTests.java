package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.pipeline.GithubIssues.Comment;
import tech.seexun.jknow.web.knowledge.pipeline.GithubIssues.Event;
import tech.seexun.jknow.web.knowledge.pipeline.GithubIssues.Issue;

/**
 * GitHub 工单连接器纯函数面（github_issues.rs:264-500 mod tests 全量移植——
 * render/comments_for_issue/sort_events/issue_number_from_url；真实响应夹具
 * github_issues.json 逐字对表，钉住字段形状）。
 */
class GithubIssuesTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Issue issue(String json) throws Exception {
        return Issue.of(JSON.readTree(json));
    }

    private static Comment comment(String json) throws Exception {
        return Comment.of(JSON.readTree(json));
    }

    @Test
    void aCommentUrlYieldsItsIssueNumber() {
        assertEquals(18, GithubIssues.issueNumberFromUrl(
                "https://api.github.com/repos/a/b/issues/18"));
        // 形状变了就归不上，但不该炸
        assertEquals(-1, GithubIssues.issueNumberFromUrl("https://example.com/"));
        assertEquals(-1, GithubIssues.issueNumberFromUrl("nonsense"));
    }

    /** 状态变更史必须进正文——这是这个来源与「抓一个网页」的全部区别。 */
    @Test
    void theHistoryLandsInTheDocumentWithDates() throws Exception {
        Issue i = issue("""
                {"number": 18, "title": "Deleting a conversation removes all turns",
                 "state": "closed", "body": "Steps to reproduce…",
                 "created_at": "2026-08-18T16:18:27Z", "updated_at": "2026-08-20T22:31:47Z",
                 "closed_at": "2026-08-20T22:31:47Z",
                 "labels": [{"name": "bug"}], "assignees": [{"login": "WaylandYang"}],
                 "user": {"login": "Danmushu"}}
                """);
        Event e1 = Event.of(JSON.readTree("""
                {"event": "labeled", "created_at": "2026-08-19T01:00:00Z",
                 "actor": {"login": "WaylandYang"}, "label": {"name": "bug"}}
                """));
        Event e2 = Event.of(JSON.readTree("""
                {"event": "closed", "created_at": "2026-08-20T22:31:47Z",
                 "actor": {"login": "WaylandYang"}}
                """));
        String out = GithubIssues.render(i, List.of(), List.of(e1, e2));
        assertTrue(out.contains("Opened by Danmushu on 2026-08-18T16:18:27Z."), out);
        assertTrue(out.contains("Closed on 2026-08-20T22:31:47Z."), out);
        assertTrue(out.contains("Labelled bug."), out);
        assertTrue(out.contains("Assigned to WaylandYang."), out);
        // 事件行带日期与执行者，labeled 还要带上是哪个标签
        assertTrue(out.contains("- 2026-08-19T01:00:00Z — labeled by WaylandYang (bug)"), out);
        assertTrue(out.contains("- 2026-08-20T22:31:47Z — closed by WaylandYang"), out);
    }

    /** 正文里的时刻写到秒（#691）：UTC 23:59:59 的事起点就是那一秒，不是那天的零点。 */
    @Test
    void aTimeInTheDocumentIsWrittenToTheSecond() throws Exception {
        Issue i = issue("""
                {"number": 18, "title": "Anything", "state": "open",
                 "created_at": "2026-09-05T23:59:59Z", "updated_at": "2026-09-05T23:59:59Z",
                 "user": {"login": "x"}}
                """);
        Comment c = comment("""
                {"issue_url": "https://api.github.com/repos/x/y/issues/18",
                 "user": {"login": "x"}, "created_at": "2026-09-05T23:59:59Z", "body": "late"}
                """);
        String out = GithubIssues.render(i, List.of(c), List.of());
        String written = "2026-09-05T23:59:59Z";
        assertTrue(out.contains("Opened by x on " + written + "."), out);
        assertTrue(out.contains("### x on " + written), out);
    }

    /** 评论按工单号归属、按时间升序——归错了另一张工单的讨论会出现在这张的正文里。 */
    @Test
    void commentsBelongToTheIssueAndAreChronological() throws Exception {
        Issue one = issue("""
                {"number": 1, "title": "one", "state": "open", "body": null,
                 "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-02T00:00:00Z",
                 "closed_at": null, "user": {"login": "a"}}
                """);
        Issue two = issue("""
                {"number": 2, "title": "two", "state": "open", "body": null,
                 "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-02T00:00:00Z",
                 "closed_at": null, "user": {"login": "b"}}
                """);
        List<Comment> comments = List.of(
                comment("""
                        {"issue_url": "https://api.github.com/repos/a/b/issues/2",
                         "user": {"login": "x"}, "created_at": "2026-01-05T00:00:00Z",
                         "body": "第二条"}
                        """),
                comment("""
                        {"issue_url": "https://api.github.com/repos/a/b/issues/2",
                         "user": {"login": "y"}, "created_at": "2026-01-03T00:00:00Z",
                         "body": "第一条"}
                        """),
                // 不在本次工单集合里：该被丢掉，而不是挂到别人身上
                comment("""
                        {"issue_url": "https://api.github.com/repos/a/b/issues/99",
                         "user": {"login": "z"}, "created_at": "2026-01-04T00:00:00Z",
                         "body": "别人的"}
                        """),
                comment("""
                        {"issue_url": "nonsense", "user": null,
                         "created_at": "2026-01-02T00:00:00Z", "body": "无法归属"}
                        """));
        assertTrue(GithubIssues.commentsForIssue(one, comments).isEmpty(), "1 号没有评论");
        List<Comment> own = GithubIssues.commentsForIssue(two, comments);
        assertEquals(2, own.size(), "其他工单和无法归属的评论不该混进来");
        // 升序：先「第一条」（01-03）后「第二条」（01-05）
        assertEquals("第一条", own.get(0).body());
    }

    /** 逐工单事件端点不返回 issue 字段——形状宽容 + 自己排序。 */
    @Test
    void perIssueEventsParseWithoutAnIssueField() throws Exception {
        List<Event> events = List.of(
                Event.of(JSON.readTree("""
                        {"event": "closed", "created_at": "2026-01-06T00:00:00Z",
                         "actor": {"login": "x"}}
                        """)),
                Event.of(JSON.readTree("""
                        {"event": "labeled", "created_at": "2026-01-02T00:00:00Z",
                         "actor": {"login": "y"}, "label": {"name": "bug"}}
                        """)));
        List<Event> sorted = GithubIssues.sortEvents(events);
        // 端点的顺序不是契约，排序自己做
        assertEquals("labeled", sorted.get(0).event());
        assertEquals("closed", sorted.get(1).event());
    }

    /** PR 在 GitHub 的模型里也是 issue，靠这个字段认出来。 */
    @Test
    void aPullRequestIsRecognisableAmongTheIssues() throws Exception {
        Issue pr = issue("""
                {"number": 3, "title": "a pr", "state": "open", "body": null,
                 "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z",
                 "closed_at": null, "user": {"login": "a"},
                 "pull_request": {"url": "https://api.github.com/repos/a/b/pulls/3"}}
                """);
        assertTrue(pr.pullRequest());
        Issue plain = issue("""
                {"number": 4, "title": "an issue", "state": "open", "body": null,
                 "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z",
                 "closed_at": null, "user": {"login": "a"}}
                """);
        assertFalse(plain.pullRequest());
    }

    /** 空评论不该在正文里留下一个只有标题的空节。 */
    @Test
    void anEmptyCommentLeavesNoStub() throws Exception {
        Issue i = issue("""
                {"number": 1, "title": "t", "state": "open", "body": null,
                 "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:00Z",
                 "closed_at": null, "user": {"login": "a"}}
                """);
        Comment c = comment("""
                {"issue_url": "https://api.github.com/repos/a/b/issues/1",
                 "user": {"login": "x"}, "created_at": "2026-01-02T00:00:00Z", "body": "   "}
                """);
        String out = GithubIssues.render(i, List.of(c), List.of());
        assertFalse(out.contains("### x"), "空评论不该留下小节：" + out);
    }

    /**
     * 拿真实响应钉住字段形状（github_issues.rs:427-483）：手写 JSON 只能证明「我以为的
     * 形状」解得出来；GitHub 一条 issue 有上百个字段，哪个字段其实叫别的名、哪个在
     * 某些情况下是 null，只有真数据说得清。夹具取自 deeplethe/utopia。
     */
    @Test
    void theRealGithubShapesStillParse() throws Exception {
        String raw = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/resources/fixtures/github_issues.json"),
                StandardCharsets.UTF_8);
        JsonNode fixture = JSON.readTree(raw);
        List<Issue> issues = new java.util.ArrayList<>();
        for (JsonNode n : fixture.path("issues")) {
            issues.add(Issue.of(n));
        }
        List<Comment> comments = new java.util.ArrayList<>();
        for (JsonNode n : fixture.path("comments")) {
            comments.add(Comment.of(n));
        }
        Map<String, List<Event>> eventsByIssue = new HashMap<>();
        fixture.path("events_by_issue").fields().forEachRemaining(e -> {
            List<Event> events = new java.util.ArrayList<>();
            for (JsonNode n : e.getValue()) {
                events.add(Event.of(n));
            }
            eventsByIssue.put(e.getKey(), events);
        });

        assertFalse(issues.isEmpty(), "夹具是空的，这条测试就什么都没验");
        // 全是 PR 的话，PR 过滤那条路就没被这份夹具覆盖到
        assertTrue(issues.stream().noneMatch(Issue::pullRequest), "夹具应当只含真工单");

        // 每张工单都排得出一篇非空文档，且抬头那句带真实日期
        for (Issue issue : issues) {
            List<Comment> cs = GithubIssues.commentsForIssue(issue, comments);
            List<Event> events = GithubIssues.sortEvents(
                    eventsByIssue.getOrDefault(String.valueOf(issue.number()), List.of()));
            String doc = GithubIssues.render(issue, cs, events);
            assertTrue(doc.contains("# #" + issue.number() + " "), "#" + issue.number()
                    + " 的抬头不对：" + doc);
            assertTrue(doc.contains(GithubIssues.stamp(issue.createdAt())),
                    "#" + issue.number() + " 正文里没有创建日期");
        }

        // 这份夹具里每张工单都被关掉过，所以历史一节必须出现——它是这个来源存在的理由
        Issue first = issues.get(0);
        List<Comment> cs = GithubIssues.commentsForIssue(first, comments);
        List<Event> events = GithubIssues.sortEvents(
                eventsByIssue.getOrDefault(String.valueOf(first.number()), List.of()));
        assertFalse(events.isEmpty(), "夹具里 #" + first.number() + " 没有事件");
        String doc = GithubIssues.render(first, cs, events);
        assertTrue(doc.contains("## History"), "历史一节缺失：" + doc);
        assertTrue(doc.contains("— closed by"), "关闭事件没写进历史：" + doc);
    }

    /** Jira 时间戳双格式也在这里钉住一行（跨连接器共用的时间语义）。 */
    @Test
    void jiraTimestampsAreNotRfc3339() {
        OffsetDateTime t = JiraIssues.parseJiraTime("2026-08-24T11:11:52.944+0000");
        assertNotNull(t);
        assertEquals("2026-08-24 11:11",
                t.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        // 真 RFC3339 也要能收（Cloud 某些端点会给这种）
        OffsetDateTime t2 = JiraIssues.parseJiraTime("2026-08-24T11:11:52.944+00:00");
        assertEquals("2026-08-24", t2.toLocalDate().toString());
    }
}
