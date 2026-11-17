package tech.seexun.jknow.web.knowledge.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.ingest.SourceIngestService;
import tech.seexun.jknow.web.knowledge.ingest.WebdavConnector;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.SourceRow;

/**
 * 来源同步任务（逐字对齐旧仓库 ingest_sources.rs:67-145 sync_source）：mark_running→
 * start_run→按 kind 分派→finish_run + finish_sync（失败记 SOURCE_SYNC_FAILED 告警）。
 * url（config.urls 列表 + reconcile_missing 全集对账——缺席标 missing，<b>抓取失败不算</b>，
 * 它仍被配置着）/ rss feed（identity=guid 或链接，>4096B 摘）/ webdav（PROPFIND BFS，
 * 任务 4 连接器接线）/ 被动容器（folder/api/statements/memory/upload=空同步）/
 * github_issues·jira_issues·s3·azure_blob·gcs·notion·custom 属各自里程碑（M2 暂按
 * 空同步收尾并在 run 记零——拉取型语义未搬，报告挂账）。
 *
 * <p><b>M3 任务 9：拉取型 7 kind 真同步</b>（github_issues/jira_issues REST 分页 + since
 * 增量、s3/azure_blob/gcs 对象列举下载、notion API 分页、custom 通用 HTTP 抓取），全部
 * 接三路判定与 MAX_NEW_PER_SYNC=200——m2-report §7.2 的「空同步陷阱」就此消除。
 * rss 的 full_new_items 模式走 rss_full_content 台账（基线/发现/领取 hydrate_rss_entry，
 * 在飞≤25 尝试≤5）；hydrate_rss_entry 任务本体在 {@link HydrateRssEntryJob}。
 */
@Component
public class SyncSourceJob {

    private static final Logger log = LoggerFactory.getLogger(SyncSourceJob.class);

    /** ingest_sources.rs:19 MAX_NEW_PER_SYNC（防超长 feed/URL 列表拖垮任务）。 */
    static final int MAX_NEW_PER_SYNC = 200;

    /** ingest_sources.rs:20 MAX_FEED_BYTES。 */
    static final long MAX_FEED_BYTES = 4L * 1024 * 1024;

    /** ingest_sources.rs:21 RSS_MAX_INFLIGHT：台账在飞上限。 */
    static final int RSS_MAX_INFLIGHT = 25;

    /** ingest_sources.rs:22 RSS_HYDRATION_ATTEMPTS：单条补全的尝试预算。 */
    static final int RSS_HYDRATION_ATTEMPTS = 5;

    /** 单次同步产出统计（ingest_sources.rs:34-41 SyncStats；Moved/Unchanged 不计）。 */
    record SyncStats(int created, int updated, int discovered, int queuedForContent,
                     int contentTerminal) {

        static final SyncStats ZERO = new SyncStats(0, 0, 0, 0, 0);

        int total() {
            return created + updated;
        }

        SyncStats absorb(SourceIngestService.IngestAction action) {
            return switch (action) {
                case Created -> new SyncStats(created + 1, updated, discovered, queuedForContent,
                        contentTerminal);
                case Updated -> new SyncStats(created, updated + 1, discovered, queuedForContent,
                        contentTerminal);
                default -> this;
            };
        }
    }

    private final SourcesService sources;
    private final SourceIngestService ingest;
    private final DocumentsWriteMapper documents;
    private final AppEventBus events;
    private final tech.seexun.jknow.store.RssFullContentService rss;

    public SyncSourceJob(SourcesService sources, SourceIngestService ingest,
                         DocumentsWriteMapper documents, AppEventBus events,
                         tech.seexun.jknow.store.RssFullContentService rss) {
        this.sources = sources;
        this.ingest = ingest;
        this.documents = documents;
        this.events = events;
        this.rss = rss;
    }

    /** dispatcher 入口：payload.source_id。 */
    public void handle(Job job) throws Exception {
        sync(UUID.fromString(job.payload().path("source_id").asText()));
    }

    /** ingest_sources.rs:67-145 逐字控制流（增量游标：custom/github/jira 取上次成功 run）。 */
    public void sync(UUID sourceId) throws Exception {
        SourceRow source = sources.get(sourceId);
        String kind = source.kind();
        OffsetDateTime since = switch (kind) {
            case "custom", "github_issues", "jira_issues" ->
                    sources.lastSuccessfulSyncStart(sourceId);
            default -> null;
        };
        sources.markRunning(sourceId);
        UUID runId = sources.startRun(sourceId);
        events.emitSource(source.kbId());

        SyncStats stats;
        Exception failure = null;
        try {
            // 按字符串穷举：加一种来源就得在这里决定它怎么同步
            stats = switch (kind) {
                case "url" -> syncUrls(source);
                case "rss" -> syncRss(source);
                case "custom" -> syncCustom(source, since);
                case "github_issues" -> syncGithubIssues(source, since);
                case "jira_issues" -> syncJiraIssues(source, since);
                case "s3", "azure_blob", "gcs" -> syncObjectStorage(source);
                case "webdav" -> syncWebdav(source);
                case "notion" -> syncNotion(source);
                // 被动容器：folder/api/statements/memory/upload 没有拉取语义
                // （ingest_sources.rs:92-99）
                case "folder", "api", "statements", "memory", "upload" -> SyncStats.ZERO;
                default -> throw new IllegalStateException(
                        "unknown source kind `" + kind + "`");
            };
        } catch (Exception e) {
            stats = null;
            failure = e;
        }

        if (failure == null) {
            sources.finishRun(runId, sourceId, null, stats.created(), stats.updated());
            sources.finishSync(sourceId, null, stats.total());
            events.emitSource(source.kbId());
            log.info("来源同步完成 source={} kind={} created={} updated={} discovered={}"
                            + " queued_for_content={} content_terminal={}", sourceId, kind,
                    stats.created(), stats.updated(), stats.discovered(),
                    stats.queuedForContent(), stats.contentTerminal());
        } else {
            sources.finishRun(runId, sourceId, failure.toString(), 0, 0);
            sources.finishSync(sourceId, failure.toString(), 0);
            events.emitSource(source.kbId());
            throw failure;
        }
    }

    // ---- url（ingest_sources.rs:372-411） ----

    private SyncStats syncUrls(SourceRow source) throws Exception {
        List<String> urls = new ArrayList<>();
        JsonNode list = source.config() == null ? null : source.config().get("urls");
        if (list != null && list.isArray()) {
            for (JsonNode v : list) {
                if (v.isTextual() && !v.asText().isBlank()) {
                    urls.add(v.asText().trim());
                }
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException(
                    "url source is missing config.urls (a list of page URLs)");
        }

        SyncStats stats = SyncStats.ZERO;
        String lastErr = null;
        for (String url : urls.subList(0, Math.min(urls.size(), MAX_NEW_PER_SYNC))) {
            try {
                HttpFetcher.Page page = HttpFetcher.get(url, 16L * 1024 * 1024);
                String mime = "application/octet-stream".equals(page.mime()) ? "text/html"
                        : page.mime();
                // 身份仍然是配置里那个 URL（同一页面换地址不该变成新文档）
                stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), url,
                        filenameFromUrl(url, mime), mime, page.bytes(), null));
            } catch (Exception e) {
                log.warn("抓取失败 url={} error={}", url, e.toString());
                lastErr = url + ": " + e;
            }
        }
        // 部分失败：有产出则视为成功（错误进日志），全军覆没才报错
        if (stats.total() == 0 && lastErr != null) {
            throw new IllegalStateException(lastErr);
        }
        // 配置列表即全集：不在列表里的文档标「不在来源中」（抓取失败不算——仍被配置着）
        documents.markMissingExcept(source.id(), urls);
        return stats;
    }

    /** ingest_sources.rs:426-455 filename_from_url 逐字语义。 */
    static String filenameFromUrl(String url, String mime) {
        String stripped = url;
        for (String prefix : new String[] {"https://", "http://"}) {
            if (stripped.startsWith(prefix)) {
                stripped = stripped.substring(prefix.length());
                break;
            }
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        StringBuilder slug = new StringBuilder();
        for (char c : stripped.toCharArray()) {
            // ingest_sources.rs:448-455：is_alphanumeric 是 Unicode 语义——中文标题的
            // 文件名不该整段折成连字符（M2 近似按 ASCII 收，任务 9 勘误）
            boolean keep = Character.isLetterOrDigit(c) || c == '.' || c == '-';
            slug.append(keep ? c : '-');
        }
        String bounded = RssFeeds.truncateUtf8(slug.toString(), 120);
        // rsplit('.') 的尾段 ≤5 且确有句点且不以句点收尾 = 像个扩展名
        int dot = bounded.lastIndexOf('.');
        boolean hasExt = dot >= 0 && !bounded.endsWith(".")
                && bounded.length() - dot - 1 <= 5;
        if (!hasExt || mime.contains("html")) {
            return bounded + ".html";
        }
        return bounded;
    }

    // ---- rss（ingest_sources.rs:565-735 的 feed 模式；full_new_items 挂账 M3） ----

    SyncStats syncRss(SourceRow source) throws Exception {
        String contentMode = SourcesService.rssContentMode(source.config());
        // M3 任务 9：full_new_items 走台账（基线/发现/领取）——feed 模式照旧整篇合成
        tech.seexun.jknow.store.RssFullContentService.FeedObservation observation =
                "full_new_items".equals(contentMode)
                        ? rss.beginFeedObservation(source.id(), source.config())
                        : null;

        String feedUrl = textOrNull(source.config() == null ? null
                : source.config().get("feed_url"));
        if (feedUrl == null || feedUrl.isEmpty()) {
            throw new IllegalStateException("rss source is missing config.feed_url");
        }
        byte[] body = HttpFetcher.get(feedUrl, MAX_FEED_BYTES).bytes();
        List<RssFeeds.Observation> observations = RssFeeds.parse(body);

        if (observation != null) {
            List<tech.seexun.jknow.store.entity.RssEntries.NewEntry> entries = new ArrayList<>();
            for (RssFeeds.Observation o : observations) {
                entries.add(new tech.seexun.jknow.store.entity.RssEntries.NewEntry(
                        o.key(), RssFeeds.truncateUtf8(o.title(), 2_048),
                        o.articleUrl() == null ? null
                                : RssFeeds.truncateUtf8(o.articleUrl(), 8_192),
                        RssFeeds.truncateUtf8(o.summary(), 16_384), o.embeddedHtml(),
                        o.docTime(), hasUsableRssContent(o)));
            }
            var activation = observation.activation();
            if ("pending".equals(activation.activationState())) {
                int discovered = rss.recordBaseline(source.id(), activation.activationGeneration(),
                        entries);
                return new SyncStats(0, 0, discovered, 0, 0);
            }
            if (!"active".equals(activation.activationState())) {
                throw new IllegalStateException("RSS full-content activation is disabled");
            }
            var discovered = rss.discoverObserved(source.id(), activation.activationGeneration(),
                    entries, observation.observedAt());
            int queued = rss.claimPendingAndEnqueue(source.id(),
                    activation.activationGeneration(), RSS_MAX_INFLIGHT, RSS_HYDRATION_ATTEMPTS);
            return new SyncStats(0, 0, discovered.discovered(), queued, discovered.terminal());
        }

        SyncStats stats = SyncStats.ZERO;
        for (RssFeeds.Observation o : observations.subList(0,
                Math.min(observations.size(), MAX_NEW_PER_SYNC))) {
            String bodyHtml = o.embeddedHtml() != null ? o.embeddedHtml() : o.summary();
            String safeTitle = escapeHtml(o.title());
            String safeLink = o.articleUrl() == null ? "" : escapeHtml(o.articleUrl());
            String html = "<html><head><title>" + safeTitle + "</title></head><body><h1>"
                    + safeTitle + "</h1>\n<p><a href=\"" + safeLink + "\">" + safeLink
                    + "</a></p>\n" + bodyHtml + "</body></html>";
            String slug = slugify(o.title());
            String filename = (slug.isEmpty() ? "untitled" : slug) + ".html";
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), o.key(), filename,
                    "text/html", html.getBytes(StandardCharsets.UTF_8), o.docTime()));
        }
        return stats;
    }

    /**
     * 台账首态判据（ingest_sources.rs:545-551）：feed 内嵌正文过质量门，或有文章链接。
     * 质量门在 {@link HydrateRssEntryJob#qualityCheck}（同一套词数/摘要比对）。
     */
    private static boolean hasUsableRssContent(RssFeeds.Observation o) {
        if (o.embeddedHtml() != null) {
            String markdown = tech.seexun.jknow.ingest.HtmlText.fragment(o.embeddedHtml());
            try {
                HydrateRssEntryJob.qualityCheck(markdown, o.summary(), false);
                return true;
            } catch (HydrateRssEntryJob.ContentError ignored) {
                // 落到链接判定
            }
        }
        return o.articleUrl() != null && !o.articleUrl().isBlank();
    }

    /**
     * ingest_sources.rs:932-939 slugify 逐字：Unicode 字母数字保留、其余折 '-'，
     * 60 <b>字节</b>截断（不切多字节字符），两端去 '-'。标题是给文件名的，不是给
     * URL 的——不小写（"A" 与 "a" 是两个标题）。
     */
    static String slugify(String title) {
        StringBuilder s = new StringBuilder();
        for (char c : title.toCharArray()) {
            s.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        String bounded = RssFeeds.truncateUtf8(s.toString(), 60);
        int begin = 0;
        int end2 = bounded.length();
        while (begin < end2 && bounded.charAt(begin) == '-') {
            begin++;
        }
        while (end2 > begin && bounded.charAt(end2 - 1) == '-') {
            end2--;
        }
        return bounded.substring(begin, end2);
    }

    /** ingest_sources.rs:457-465 escape_html。 */
    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ---- webdav（ingest_sources.rs:1108-1150，任务 4 连接器接线） ----

    private SyncStats syncWebdav(SourceRow source) throws Exception {
        JsonNode config = source.config();
        String base = config == null ? null : textOrNull(config.get("base_url"));
        if (base == null || base.isEmpty()) {
            throw new IllegalStateException("webdav source is missing config.base_url");
        }
        String root = config.get("path") != null && config.get("path").isTextual()
                ? config.get("path").asText().trim() : "/";
        String user = textOrNull(config.get("username"));
        String pass = textOrNull(config.get("password"));
        String authUser = user != null && !user.isEmpty() ? user : null;

        URI baseUri = URI.create(base);
        if (!baseUri.isAbsolute() || baseUri.getHost() == null) {
            throw new IllegalStateException("Invalid base_url: " + base);
        }
        // M3 任务 9：webdav 也走受管客户端（Rust 侧 client_for + 整体时限 60s，#330）
        GuardedHttp.GuardedClient http = GuardedHttp.clientFor(baseUri, GuardedHttp.Reach.OPERATOR,
                GuardedHttp.limitsWithMaxBytes(GuardedHttp.Limits.defaults(),
                        WebdavConnector.MAX_FILE_BYTES)
                        .withOverall(java.time.Duration.ofSeconds(60)));
        WebdavConnector.WebdavResult result = WebdavConnector.fetch(http, base, root, authUser,
                pass);
        if (result.truncated()) {
            log.warn("webdav 文件数到达单次上限，其余留给下一次同步 base={} root={}", base, root);
        }
        SyncStats stats = SyncStats.ZERO;
        for (WebdavConnector.RemoteFile f : result.files()) {
            // 不猜 mime，理由同对象存储：解析先看魔数
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), f.externalKey(),
                    f.filename(), "application/octet-stream", f.bytes(), f.lastModified()));
        }
        return stats;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : null;
    }

    // ---- github_issues（ingest_sources.rs:738-846 + github_issues.rs） ----

    /**
     * GitHub 工单同步（ingest_sources.rs:747-760）：api.github.com 是固定公网主机，按
     * 内容级严格度收（Reach.CONTENT）；PR 默认排除（include_pull_requests 开关）。
     * doc_time 取 updated_at——每次同步捕获的是「此刻这张工单是什么样」。
     */
    private SyncStats syncGithubIssues(SourceRow source, OffsetDateTime since) throws Exception {
        GuardedHttp.GuardedClient http = GuardedHttp.clientFor(
                URI.create("https://api.github.com/"), GuardedHttp.Reach.CONTENT,
                GuardedHttp.Limits.defaults());
        return syncGithubIssuesWithClient(source, since, http, "https://api.github.com");
    }

    /** HTTP 接缝（ingest_sources.rs:762-763）：回归测试走同一条同步与落库路径。 */
    SyncStats syncGithubIssuesWithClient(SourceRow source, OffsetDateTime since,
                                         GuardedHttp.GuardedClient http, String apiBase)
            throws Exception {
        JsonNode config = source.config();
        String repo = textOrNull(config == null ? null : config.get("repo"));
        if (repo == null) {
            throw new IllegalStateException(
                    "github_issues source is missing config.repo (owner/name)");
        }
        if (repo.split("/").length != 2) {
            throw new IllegalStateException("config.repo should look like owner/name, got `"
                    + repo + "`");
        }
        String auth = textOrNull(config.get("auth_header"));
        boolean includePrs = config.path("include_pull_requests").asBoolean(false);

        String base = apiBase + "/repos/" + repo;
        // 增量：GitHub 的 since 是「这之后更新过的」
        List<String[]> query = new ArrayList<>();
        query.add(new String[] {"state", "all"});
        if (since != null) {
            query.add(new String[] {"since", GithubIssues.stamp(since)});
        }
        List<GithubIssues.Issue> issues = GithubIssues.fetchAll(http, base + "/issues", query,
                auth, GithubIssues.Issue::of);
        SyncStats stats = SyncStats.ZERO;
        int written = 0;
        for (GithubIssues.Issue issue : issues) {
            // 只重建本轮选中的工单，但每张要读完整评论历史（issues/comments 的 since
            // 只给改动过的——整篇替换会把没改的讨论删掉）
            if (!includePrs && issue.pullRequest()) {
                continue;
            }
            if (written >= MAX_NEW_PER_SYNC) {
                break;
            }
            written++;
            List<GithubIssues.Comment> comments = GithubIssues.fetchAll(
                    http, base + "/issues/" + issue.number() + "/comments", List.of(), auth,
                    GithubIssues.Comment::of);
            List<GithubIssues.Comment> own = GithubIssues.commentsForIssue(issue, comments);
            List<GithubIssues.Event> events = GithubIssues.sortEvents(GithubIssues.fetchAll(
                    http, base + "/issues/" + issue.number() + "/events", List.of(), auth,
                    GithubIssues.Event::of));
            String bodyMarkdown = GithubIssues.render(issue, own, events);
            // 逻辑身份带上仓库：同库接两个仓库时 #18 不会互相覆盖
            String key = "github:" + repo + "#" + issue.number();
            String filename = issue.number() + "-" + slugify(issue.title()) + ".md";
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), key, filename,
                    "text/markdown",
                    bodyMarkdown.getBytes(StandardCharsets.UTF_8), issue.updatedAt()));
        }
        return stats;
    }

    // ---- jira_issues（ingest_sources.rs:848-929 + jira_issues.rs） ----

    /**
     * Jira 工单同步：search?expand=changelog 一次带回本体+变更史+评论（没有 N+1）。
     * doc_time 取 updated（与 github 同口径）。
     */
    private SyncStats syncJiraIssues(SourceRow source, OffsetDateTime since) throws Exception {
        JsonNode config = source.config();
        String baseUrl = textOrNull(config == null ? null : config.get("base_url"));
        if (baseUrl == null) {
            throw new IllegalStateException("jira_issues source is missing config.base_url");
        }
        String project = textOrNull(config.get("project"));
        if (project == null) {
            throw new IllegalStateException("jira_issues source is missing config.project");
        }
        // 项目 key 直接拼进 JQL，所以不能是任意字符串。Jira 的 key 本身就限定字母数字加
        // 下划线——挡住它顺带挡住了 JQL 注入
        for (char c : project.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '_') {
                throw new IllegalStateException("config.project should be a Jira project key, "
                        + "got `" + project + "`");
            }
        }
        String auth = textOrNull(config.get("auth_header"));
        URI baseUri = URI.create(baseUrl);
        GuardedHttp.GuardedClient http = GuardedHttp.clientFor(baseUri, GuardedHttp.Reach.OPERATOR,
                GuardedHttp.Limits.defaults().withOverall(java.time.Duration.ofSeconds(45)));

        String jql = JiraIssues.jql(project, since);
        JiraIssues.FetchResult fetched = JiraIssues.fetchAll(http, baseUrl, jql, auth);
        // 截断了就说出来：翻页上限意味着这一轮只覆盖了一段
        if (fetched.total() > fetched.issues().size()) {
            log.warn("source={} Jira 结果被翻页上限截断（取回 {} / 共 {}），下一轮的 JQL 窗口会接上",
                    source.id(), fetched.issues().size(), fetched.total());
        }
        String host = baseUri.getHost() != null ? baseUri.getHost() : "jira";
        SyncStats stats = SyncStats.ZERO;
        List<JiraIssues.Issue> issues = fetched.issues().subList(0,
                Math.min(fetched.issues().size(), MAX_NEW_PER_SYNC));
        for (int i = 0; i < issues.size(); i++) {
            JiraIssues.Issue issue = issues.get(i);
            String bodyMarkdown = JiraIssues.render(issue, fetched.rawIssues().get(i));
            // 逻辑身份带上站点：一个知识库接两个 Jira 时，PROJ-1 不会互相覆盖
            String key = "jira:" + host + "/" + issue.key();
            String filename = issue.key() + "-" + slugify(
                    issue.fields().summary() == null ? "" : issue.fields().summary()) + ".md";
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), key, filename,
                    "text/markdown", bodyMarkdown.getBytes(StandardCharsets.UTF_8),
                    issue.fields().updated()));
        }
        return stats;
    }

    // ---- s3 / azure_blob / gcs（ingest_sources.rs:1048-1102 + object_storage.rs） ----

    /** 对象存储同步：列前缀下的对象逐个摄入；external_key=s3://bucket/key 等自描述形态。 */
    private SyncStats syncObjectStorage(SourceRow source) throws Exception {
        JsonNode config = source.config();
        String bucket = textOrNull(config == null ? null : config.get("bucket"));
        if (bucket == null) {
            throw new IllegalStateException(source.kind() + " source is missing config.bucket");
        }
        String prefix = textOrNull(config.get("prefix"));
        ObjectStorage.Store store = ObjectStorage.client(source.kind(), config);
        ObjectStorage.FetchResult fetched = ObjectStorage.fetch(source.kind(), store, bucket,
                prefix);
        SyncStats stats = SyncStats.ZERO;
        for (ObjectStorage.RemoteObject obj : fetched.objects()) {
            // 不猜 mime：解析先看魔数——按文件名猜一个只是多一个会撒谎的来源
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), obj.externalKey(),
                    obj.filename(), "application/octet-stream", obj.bytes(), obj.lastModified()));
        }
        return stats;
    }

    // ---- notion（ingest_sources.rs:1152-1204 + notion.rs） ----

    /**
     * Notion 同步：把 integration 能看见的页面摄进来。doc_time 取 last_edited_time——
     * 页面自己的编辑时刻。读不出来的页点名报失败，不然它们停在旧版本上没人知道。
     */
    private SyncStats syncNotion(SourceRow source) throws Exception {
        JsonNode config = source.config();
        String token = textOrNull(config == null ? null : config.get("token"));
        if (token == null) {
            throw new IllegalStateException("notion source is missing config.token");
        }
        String query = textOrNull(config.get("query"));
        NotionConnector.Result result = NotionConnector.fetch(token, query);
        if (result.truncated()) {
            log.warn("notion 页面数到达单次上限，其余留给下一次同步");
        }
        SyncStats stats = SyncStats.ZERO;
        for (NotionConnector.NotionPage page : result.pages()) {
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(),
                    page.externalKey(), page.filename(), "text/markdown",
                    page.text().getBytes(StandardCharsets.UTF_8), page.lastEdited()));
        }
        if (!result.unread().isEmpty()) {
            throw new IllegalStateException(stats.total() + " Notion page(s) synced; "
                    + result.unread().size() + " could not be read and keep their stored "
                    + "content: " + String.join("; ", result.unread().subList(0,
                    Math.min(3, result.unread().size()))));
        }
        return stats;
    }

    // ---- custom（ingest_sources.rs:941-1046） ----

    /**
     * 自定义拉取器——Utopia Ingest Interface：
     * {@code GET {endpoint}?since=<上次成功同步开始 RFC3339>}（首次不带；可配 Authorization 头），
     * 响应 items[] {id,title,content,doc_time,mime}。id→external_key（custom:{id}）。
     * 增量响应里缺席≠删除（不做全集对账）；显式墓碑 deleted[] 才标 missing。
     */
    private SyncStats syncCustom(SourceRow source, OffsetDateTime since) throws Exception {
        JsonNode config = source.config();
        String endpoint = textOrNull(config == null ? null : config.get("endpoint"));
        if (endpoint == null) {
            throw new IllegalStateException("custom source is missing config.endpoint");
        }
        String url = endpoint;
        if (since != null) {
            url += (endpoint.contains("?") ? "&" : "?") + "since="
                    + java.net.URLEncoder.encode(GithubIssues.stamp(since),
                            StandardCharsets.UTF_8);
        }
        // 端点是操作者自己填的：内网与回环都是正当目标（Reach.OPERATOR，#330）
        GuardedHttp.GuardedClient http = GuardedHttp.clientFor(URI.create(url),
                GuardedHttp.Reach.OPERATOR, GuardedHttp.Limits.defaults());
        List<String[]> headers = new ArrayList<>();
        String auth = textOrNull(config.get("auth_header"));
        if (auth != null) {
            headers.add(new String[] {"Authorization", auth});
        }
        GuardedHttp.GuardedClient.Response resp = http.exchange("GET", URI.create(url), headers,
                null);
        if (resp.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.status() + " from endpoint");
        }
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind
                .ObjectMapper().readTree(resp.text());
        JsonNode items = body.get("items");
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("Response is missing the items[] array");
        }

        SyncStats stats = SyncStats.ZERO;
        List<String> seenKeys = new ArrayList<>();
        int count = 0;
        for (JsonNode item : items) {
            if (count >= MAX_NEW_PER_SYNC) {
                break;
            }
            count++;
            String id = item.path("id").asText("").trim();
            if (id.isEmpty()) {
                log.warn("source={} custom item 缺少 id，跳过", source.id());
                continue;
            }
            String content = item.path("content").asText("");
            if (content.isBlank()) {
                log.warn("source={} id={} custom item 缺少 content，跳过", source.id(), id);
                continue;
            }
            String title = item.path("title").asText("").trim();
            if (title.isEmpty()) {
                title = id;
            }
            String mime = item.path("mime").asText("text/markdown");
            OffsetDateTime docTime = null;
            String rawTime = item.path("doc_time").asText("");
            if (!rawTime.isEmpty()) {
                try {
                    docTime = OffsetDateTime.parse(rawTime);
                } catch (java.time.format.DateTimeParseException ignored) {
                    // 解不动就当没给
                }
            }
            String key = "custom:" + id;
            stats = stats.absorb(ingest.ingestItem(source.kbId(), source.id(), key,
                    ensureExtension(title, mime), mime,
                    content.getBytes(StandardCharsets.UTF_8), docTime));
            seenKeys.add(key);
        }
        // 失而复得：再次出现的条目摘掉 missing 标；显式墓碑才标「不在来源中」
        if (!seenKeys.isEmpty()) {
            documents.clearMissingKeys(source.id(), seenKeys);
        }
        List<String> tombstones = new ArrayList<>();
        for (JsonNode v : body.path("deleted")) {
            if (v.isTextual()) {
                tombstones.add("custom:" + v.asText().trim());
            }
        }
        if (!tombstones.isEmpty()) {
            int marked = documents.markMissingKeys(source.id(), tombstones);
            log.info("source={} custom 墓碑：{} 条标记不在来源中", source.id(), marked);
        }
        return stats;
    }

    /** 标题无扩展名时按 mime 补一个（ingest_sources.rs:1207-1225 ensure_extension）。 */
    static String ensureExtension(String title, String mime) {
        int dot = title.lastIndexOf('.');
        boolean hasExt = dot >= 0 && title.length() - dot - 1 >= 2
                && title.length() - dot - 1 <= 5
                && !title.substring(dot + 1).contains(" ");
        if (hasExt) {
            return title;
        }
        String ext;
        if (mime.contains("html")) {
            ext = "html";
        } else if (mime.contains("plain")) {
            ext = "txt";
        } else {
            ext = "md";
        }
        return title + "." + ext;
    }
}
