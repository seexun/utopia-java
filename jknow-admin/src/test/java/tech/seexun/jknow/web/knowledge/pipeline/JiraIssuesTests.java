package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.pipeline.JiraIssues.Issue;

/**
 * Jira 工单连接器纯函数面（jira_issues.rs:337-442 mod tests 全量移植）。
 */
class JiraIssuesTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** JQL 的时间格式与引号：两点写错都报 400，而不是「查不到」。 */
    @Test
    void theIncrementalJqlUsesJirasOwnTimeFormat() {
        OffsetDateTime t = OffsetDateTime.parse("2026-08-30T12:34:56Z");
        String q = JiraIssues.jql("KAFKA", t);
        assertTrue(q.contains("project = KAFKA"), q);
        assertTrue(q.contains("updated >= \"2026-08-30 12:34\""), q);
        assertTrue(q.contains("ORDER BY updated ASC"), q);
        // 没有 since 时不该带上 updated 子句。查子句不查词——ORDER BY updated ASC 里
        // 也有 "updated"，第一版断言就栽在这上面
        assertFalse(JiraIssues.jql("KAFKA", null).contains("updated >="));
    }

    /** 拿真实响应钉住字段形状（jira_issues.rs:372-398）：驼峰命名/非 RFC3339/changelog 嵌套。 */
    @Test
    void theRealJiraShapesStillParse() throws Exception {
        String raw = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/resources/fixtures/jira_issues.json"),
                StandardCharsets.UTF_8);
        JsonNode page = JSON.readTree(raw);
        List<JsonNode> issues = new java.util.ArrayList<>();
        page.path("issues").forEach(issues::add);
        assertFalse(issues.isEmpty(), "夹具是空的，这条测试什么都没验");

        for (JsonNode rawIssue : issues) {
            Issue issue = Issue.of(rawIssue);
            String doc = JiraIssues.render(issue, rawIssue);
            assertTrue(doc.startsWith("# " + issue.key() + " "), doc);
        }

        // 变更史是这个来源存在的理由。夹具里至少有一张带 changelog 的，且必须排成
        // 「字段: 旧值 → 新值」——只写「发生了变更」等于没说
        JsonNode withLog = issues.stream().filter(i -> i.path("changelog").path("histories")
                .elements().hasNext() && i.path("changelog").path("histories").path(0)
                .path("items").size() > 0).findFirst()
                .orElseThrow(() -> new AssertionError("夹具里该有带 changelog 的工单"));
        String doc = JiraIssues.render(Issue.of(withLog), withLog);
        assertTrue(doc.contains("## History"), "历史一节缺失：" + doc);
        assertTrue(doc.contains(" changed "), "变更行没写成字段级：" + doc);
        assertTrue(doc.contains(" → "), "缺少 from → to：" + doc);
    }

    /** 正文里的时刻写到秒、带 Z；时区偏移换算到 UTC（jira_issues.rs:402-422）。 */
    @Test
    void aTimeInTheDocumentIsWrittenToTheSecond() throws Exception {
        Issue issue = Issue.of(JSON.readTree("""
                {"key": "X-2", "fields": {
                    "summary": "t", "description": null, "labels": [],
                    "reporter": {"displayName": "Mickael"},
                    "created": "2026-08-24T23:59:59.944+0000",
                    "updated": "2026-08-25T00:00:00.000+0000",
                    "resolutiondate": "2026-08-25T08:30:00.123+0800"}}
                """));
        String out = JiraIssues.render(issue, JSON.readTree("{}"));
        assertTrue(out.contains("Reported by Mickael on 2026-08-24T23:59:59Z."), out);
        assertTrue(out.contains("Resolved on 2026-08-25T00:30:00Z."), out);
    }

    /** 空评论不该留下一个只有标题的空节（与 GitHub 那边同一个口径）。 */
    @Test
    void anEmptyCommentLeavesNoStub() throws Exception {
        Issue issue = Issue.of(JSON.readTree("""
                {"key": "X-1", "fields": {
                    "summary": "t", "description": null, "labels": [],
                    "created": "2026-01-01T00:00:00.000+0000",
                    "updated": "2026-01-01T00:00:00.000+0000",
                    "comment": {"comments": [
                        {"author": {"displayName": "a"},
                         "created": "2026-01-02T00:00:00.000+0000", "body": "   "}]}}}
                """));
        String out = JiraIssues.render(issue, JSON.readTree("{}"));
        assertFalse(out.contains("### a"), "空评论不该留下小节：" + out);
    }

    /** 渲染挂 null 原始树（无 changelog 端点形状）不炸、不吐 History。 */
    @Test
    void renderWithoutChangelogOmitsHistory() throws Exception {
        Issue issue = Issue.of(JSON.readTree("""
                {"key": "X-3", "fields": {"summary": "s", "description": "d", "labels": []}}
                """));
        String out = JiraIssues.render(issue, JSON.readTree("{\"key\": \"X-3\"}"));
        assertNotNull(out);
        assertFalse(out.contains("## History"), out);
        assertTrue(out.contains("## Description"), out);
    }
}
