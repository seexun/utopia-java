package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.RssFullContentService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.RssEntries;
import tech.seexun.jknow.store.mapper.RssFullContentMapper;

/**
 * RSS 全文台账（crates/utopia-store/tests/store/rss_full_content.rs 全量移植，1159 行
 * 13 例——断言逐字；tokio 并发→线程池，等价点记在 javadoc）。台账与 documents 分开：
 * 发现先于内容、补全后于滑动窗口。执行状态不重复持久化（通用队列拥有 attempts）。
 */
class RssFullContentStoreTests extends HeartStoreTestBase {

    @Autowired
    RssFullContentService rss;

    @Autowired
    DocumentsWriteService documents;

    @Autowired
    JobsService jobs;

    @Autowired
    tech.seexun.jknow.store.SourcesService sources;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    record Seed(UUID org, UUID kb, UUID source, UUID ws) {
    }

    private Seed seedSource() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID source = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org,
                "rss-full-content-test-" + org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'rss-full-content-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'rss-full-content-test')",
                kb, ws);
        jdbc.update("""
                INSERT INTO sources (id, kb_id, kind, name, config)
                 VALUES (?, ?, 'rss', 'rss-full-content-test',
                 '{"feed_url":"https://example.com/feed","content_mode":"full_new_items"}'::jsonb)
                """, source, kb);
        return new Seed(org, kb, source, ws);
    }

    private UUID insertSource(UUID kb, String kind, String name, String configJson) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO sources (id, kb_id, kind, name, config) VALUES (?, ?, ?, ?, ?::jsonb)",
                id, kb, kind, name, configJson);
        return id;
    }

    private static RssEntries.NewEntry entry(String key) {
        return new RssEntries.NewEntry(key, "Test entry", "https://example.com/article",
                "A useful summary", null, null, true);
    }

    private void cleanup(UUID org) {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    // ---- rss_full_content.rs:4-49 ----

    /** 失败重试与首准入共享容量（在飞 ≤25）；通用重试不得绕开 RSS 准入。 */
    @Test
    void failedRequeueSharesRssAdmissionCapacity() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        rss.recordBaseline(f.source(), 1, List.of());
        List<RssEntries.NewEntry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            entries.add(entry("capacity-" + i));
        }
        rss.discover(f.source(), 1, entries);
        assertEquals(25, claimAndPark(f.source(), 25));
        jdbc.update("UPDATE jobs SET status = 'failed' WHERE payload->>'source_id' = ?",
                f.source().toString());
        assertEquals(15, claimAndPark(f.source(), 25));
        JobsService.RequeueScope scope = new JobsService.RequeueScope(f.kb(), null, null);
        assertEquals(0, jobs.requeueFailed(scope), "generic retries must not bypass RSS admission");
        // 两路并发重试也超不过准入帽（tokio::join! → 线程池）
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var a = pool.submit(() -> rss.requeueFailed(scope));
        parkJobs(f.source());
        var b = pool.submit(() -> rss.requeueFailed(scope));
        parkJobs(f.source());
        int requeued = a.get() + b.get();
        pool.shutdown();
        long live = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE payload->>'source_id' = ? "
                        + "AND status IN ('queued', 'running')", Long.class, f.source().toString());
        cleanup(f.org());
        assertEquals(25L, live, "RSS requeue exceeded the admission cap");
        assertEquals(10, requeued, "requeue result must count only admitted jobs");
    }

    // ---- rss_full_content.rs:51-89 ----

    @Test
    void preFetchSnapshotRejectsChangedConfigAndStaleGeneration() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        RssFullContentService.FeedObservation snapshot = rss.beginFeedObservation(f.source(),
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(
                        "{\"feed_url\":\"https://example.com/feed\",\"content_mode\":\"full_new_items\"}"));
        jdbc.update("""
                UPDATE sources SET config = jsonb_set(config, '{feed_url}', '"https://example.com/new"'),
                 rss_generation = rss_generation + 1, rss_baselined_at = NULL WHERE id = ?
                """, f.source());
        assertThrows(AppException.class, () -> rss.beginFeedObservation(f.source(),
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(
                        "{\"feed_url\":\"https://example.com/feed\",\"content_mode\":\"full_new_items\"}")));
        assertThrows(AppException.class, () -> rss.recordBaseline(f.source(),
                snapshot.activation().activationGeneration(), List.of()));
        RssFullContentMapper.Activation activation = rss.getActivation(f.source());
        assertEquals("pending", activation.activationState());
        cleanup(f.org());
    }

    // ---- rss_full_content.rs:91-200 ----

    @Test
    void delayedObservationCannotCrossPurgeAndDiagnosticsHideJobErrors() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        rss.recordBaseline(f.source(), 1, List.of());
        var observation = rss.beginFeedObservation(f.source(), config());
        DocumentRow doc = documents.createWithVersion(f.kb(), "race.md", "text/markdown", 10,
                "a".repeat(64), f.source(), null, "source", "race-key", false);
        documents.delete(f.kb(), doc.id(), null);
        documents.purge(f.kb(), doc.id());
        rss.discoverObserved(f.source(), 1, List.of(entry("race-key")), observation.observedAt());
        assertEquals(0, claimAndPark(f.source(), 25));
        List<RssEntries.Entry> rows = rss.listCurrentEntries(f.source(), 100);
        assertEquals("deleted", rows.get(0).state());
        rss.discover(f.source(), 1, List.of(entry("race-key")));
        assertEquals(1, claimAndPark(f.source(), 25));
        RssEntries.Entry row = rss.listCurrentEntries(f.source(), 100).get(0);
        String secret = "nested: https://user:password@example.com/?token=SECRET "
                + "é".repeat(3000);
        jdbc.update("UPDATE jobs SET status='failed',last_error=? WHERE id=?", secret,
                row.hydrationJobId());
        row = rss.getEntry(row.id());
        assertEquals("terminal", row.state());
        assertEquals("Full-content acquisition failed; retry is available", row.errorDetail());
        RssFullContentService.Counts counts = rss.counts(f.source());
        assertEquals(1, counts.terminalCount());
        // 活文档才是接受内容的权威，即使更早的 job 失败了
        documents.createWithVersion(f.kb(), "race.md", "text/markdown", 10,
                "b".repeat(64), f.source(), null, "source", "race-key", false);
        assertEquals("complete", rss.getEntry(row.id()).state());
        counts = rss.counts(f.source());
        assertEquals(1, counts.completeCount());
        assertEquals(0, counts.terminalCount());
        cleanup(f.org());
    }

    // ---- rss_full_content.rs:202-270 ----

    @Test
    void poorerObservationPreservesCandidateWithoutRefreshingPurgePermission() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        rss.recordBaseline(f.source(), 1, List.of());
        RssEntries.NewEntry rich = new RssEntries.NewEntry("retained", "Test entry", null,
                "A useful summary", "<p>Retained substantive article</p>", null, true);
        rss.discover(f.source(), 1, List.of(rich));
        OffsetDateTime before = jdbc.queryForObject(
                "SELECT observed_at FROM rss_full_content_entries WHERE source_id=?",
                OffsetDateTime.class, f.source());
        RssEntries.NewEntry poor = new RssEntries.NewEntry("retained", "Test entry", null,
                "Summary only", null, null, false);
        rss.discover(f.source(), 1, List.of(poor));
        RssEntries.Entry row = rss.listCurrentEntries(f.source(), 100).get(0);
        assertEquals("<p>Retained substantive article</p>", row.embeddedHtml(),
                "poorer feed erased retained acquisition content");
        OffsetDateTime after = jdbc.queryForObject(
                "SELECT observed_at FROM rss_full_content_entries WHERE source_id=?",
                OffsetDateTime.class, f.source());
        assertEquals(before, after, "old content must not acquire a fresh timestamp");
        DocumentRow doc = documents.createWithVersion(f.kb(), "retained.md", "text/markdown", 10,
                "a".repeat(64), f.source(), null, "source", "retained", false);
        documents.delete(f.kb(), doc.id(), null);
        documents.purge(f.kb(), doc.id());
        rss.discover(f.source(), 1, List.of(poor));
        assertEquals(0, claimAndPark(f.source(), 25));
        rss.discover(f.source(), 1, List.of(rich));
        assertEquals(1, claimAndPark(f.source(), 25));
        cleanup(f.org());
    }

    // ---- rss_full_content.rs:341-536 ----

    /** 来源列表的 rss_full_content 块只谈它有的队列；不是 RSS 的整块不在。 */
    @Test
    void sourceListExposesScopedRssSummary() throws Exception {
        Seed f = seedSource();
        UUID folderId = insertSource(f.kb(), "folder", "folder-test", "{}");
        UUID urlId = insertSource(f.kb(), "url", "url-test",
                "{\"urls\":[\"https://example.com\"]}");
        jdbc.update("""
                UPDATE sources SET created_at = CASE id
                    WHEN ? THEN '2020-01-01T00:00:00Z'::timestamptz
                    WHEN ? THEN '2020-01-02T00:00:00Z'::timestamptz
                    WHEN ? THEN '2020-01-03T00:00:00Z'::timestamptz END
                 WHERE id IN (?, ?, ?)
                """, f.source(), folderId, urlId, f.source(), folderId, urlId);
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        var views = sources.list(f.kb());
        assertEquals(List.of("rss-full-content-test", "folder-test", "url-test"),
                views.stream().map(v -> v.name()).toList());
        for (UUID id : List.of(folderId, urlId)) {
            assertNull(views.stream().filter(v -> v.id().equals(id)).findFirst()
                    .orElseThrow().rssFullContent(), "不是 RSS 全文来源不该有这一块");
        }
        var rssView = views.stream().filter(v -> v.id().equals(f.source()))
                .findFirst().orElseThrow();
        var block = rssView.rssFullContent();
        assertEquals("pending", block.get("state").asText());
        assertEquals(0, block.get("queued").asInt());
        assertEquals(0, block.get("terminal").asInt());

        rss.recordBaseline(f.source(), 1, List.of());
        List<RssEntries.NewEntry> entries = List.of(entry("queued"), entry("hydrating"),
                entry("retrying"), entry("complete"), entry("superseded"), entry("deleted"),
                new RssEntries.NewEntry("terminal", "Test entry",
                        "https://example.com/article", "A useful summary", null, null, false));
        rss.discover(f.source(), 1, entries);
        assertEquals(6, claimAndPark(f.source(), 6));
        System.err.println("[P1] " + jdbc.queryForList(
                "SELECT id, status, attempts, run_at FROM jobs WHERE kind='hydrate_rss_entry' AND payload->>'source_id' = ? ORDER BY id", f.source().toString()));
        rss.discover(f.source(), 1, List.of(entry("pending")));
        System.err.println("[DBG] hydrating job=" + jobIdFor(f.source(), "hydrating")
                + " retrying job=" + jobIdFor(f.source(), "retrying")
                + " superseded job=" + jobIdFor(f.source(), "superseded"));
        jdbc.update("UPDATE jobs SET status='running' WHERE id=?",
                jobIdFor(f.source(), "hydrating"));
        jdbc.update("UPDATE jobs SET status='queued', attempts=1 WHERE id=?",
                jobIdFor(f.source(), "retrying"));
        jdbc.update("UPDATE jobs SET status='done' WHERE id=?",
                jobIdFor(f.source(), "superseded"));
        System.err.println("[P2] " + jdbc.queryForList(
                "SELECT id, status, attempts FROM jobs WHERE kind='hydrate_rss_entry' AND payload->>'source_id' = ? ORDER BY id", f.source().toString()));
        documents.createWithVersion(f.kb(), "complete.md", "text/markdown", 10, "a".repeat(64),
                f.source(), null, "source", "complete", false);
        DocumentRow deleted = documents.createWithVersion(f.kb(), "deleted.md",
                "text/markdown", 10, "b".repeat(64), f.source(), null, "source", "deleted", false);
        documents.delete(f.kb(), deleted.id(), null);
        documents.purge(f.kb(), deleted.id());

        views = sources.list(f.kb());
        rssView = views.stream().filter(v -> v.id().equals(f.source()))
                .findFirst().orElseThrow();
        block = rssView.rssFullContent();
        assertEquals("active", block.get("state").asText());
        assertEquals(1, block.get("pending").asInt());
        assertEquals(2, block.get("queued").asInt(),
                "queued and hydrating share one bucket; block=" + block);
        assertEquals(1, block.get("retrying").asInt());
        assertEquals(1, block.get("complete").asInt());
        assertEquals(3, block.get("terminal").asInt(),
                "terminal, deleted and superseded share one bucket");
        assertEquals(1, rssView.docCount(), "purged documents stay out of the live count");
        assertEquals(0, rssView.missingCount());

        jdbc.update("UPDATE sources SET rss_generation=2, rss_baselined_at=NULL WHERE id = ?",
                f.source());
        rss.recordBaseline(f.source(), 2, List.of());
        rss.discover(f.source(), 2, List.of(entry("new-generation")));
        views = sources.list(f.kb());
        rssView = views.stream().filter(v -> v.id().equals(f.source()))
                .findFirst().orElseThrow();
        block = rssView.rssFullContent();
        assertEquals("active", block.get("state").asText());
        assertEquals(1, block.get("pending").asInt(), "old-generation observations are excluded");
        assertEquals(0, block.get("terminal").asInt());

        jdbc.update("""
                UPDATE sources SET config = jsonb_set(config, '{content_mode}', '"feed"')
                 WHERE id = ?
                """, f.source());
        views = sources.list(f.kb());
        rssView = views.stream().filter(v -> v.id().equals(f.source()))
                .findFirst().orElseThrow();
        // 关掉全文的 RSS 来源这一块仍然在：它还是 RSS，只是这一档关着
        block = rssView.rssFullContent();
        assertNotNull(block, "还是 RSS，块还在");
        assertEquals("disabled", block.get("state").asText());
        assertEquals(1, block.get("terminal").asInt(), "disabled current rows are superseded");
        cleanup(f.org());
    }

    private Long jobIdFor(UUID source, String externalKey) {
        return jdbc.queryForObject("""
                SELECT e.current_job_id FROM rss_full_content_entries e
                 WHERE e.source_id = ? AND e.external_key = ?
                """, Long.class, source, externalKey);
    }

    // ---- rss_full_content.rs:545-615 ----

    /** 横向聚合只数被列的那个来源自己的观察（同库两个源 + 另一库第三个源）。 */
    @Test
    void sourceListCountsOnlyTheListedSource() throws Exception {
        Seed f = seedSource();
        String rssConfig =
                "{\"feed_url\":\"https://example.com/feed\",\"content_mode\":\"full_new_items\"}";
        UUID second = insertSource(f.kb(), "rss", "rss-second", rssConfig);
        UUID otherKb = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                otherKb, f.ws(), "rss-full-content-other-base");
        UUID other = insertSource(otherKb, "rss", "rss-other-base", rssConfig);

        for (var p : List.of(new Object[] {f.source(), List.of("first-1")},
                new Object[] {second, List.of("second-1", "second-2")},
                new Object[] {other, List.of("other-1", "other-2", "other-3")})) {
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) p[1];
            UUID sourceId = (UUID) p[0];
            jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", sourceId);
            rss.recordBaseline(sourceId, 1, List.of());
            List<RssEntries.NewEntry> entries = new ArrayList<>();
            for (String key : keys) {
                entries.add(entry(key));
            }
            rss.discover(sourceId, 1, entries);
        }

        var listed = sources.list(f.kb());
        assertEquals(2, listed.size(), "the first base lists its own two sources");
        assertEquals(1L, pendingOf(listed, f.source()), "the first source counts only its own observation");
        assertEquals(2L, pendingOf(listed, second), "a second source in the same base does not inherit the first's rows");
        var otherListed = sources.list(otherKb);
        assertEquals(1, otherListed.size(), "the other base lists only its own source");
        assertEquals(3L, pendingOf(otherListed, other),
                "a source in another base sees none of the first base's rows");
        cleanup(f.org());
    }

    private long pendingOf(List<tech.seexun.jknow.store.entity.SourceView> listed, UUID id) {
        var v = listed.stream().filter(x -> x.id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing " + id));
        return v.rssFullContent() != null ? v.rssFullContent().get("pending").asLong() : 0;
    }

    // ---- rss_full_content.rs:632-719 ----

    /** 激活前就在的文档：purge 之后一次更晚的观察（没有 job）不得复建它。 */
    @Test
    void prePurgeObservationWithoutAJobCannotPublishAfterPurge() throws Exception {
        Seed f = seedSource();
        String sha = "a".repeat(64);
        DocumentRow original = documents.createWithVersion(f.kb(), "existing.md",
                "text/markdown", 10, sha, f.source(), null, "source", "preexisting-key", false);
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        rss.recordBaseline(f.source(), 1, List.of());
        rss.discover(f.source(), 1, List.of(entry("preexisting-key")));
        assertEquals(0, claimAndPark(f.source(), 0));
        documents.delete(f.kb(), original.id(), null);
        documents.purge(f.kb(), original.id());
        Boolean released = jdbc.queryForObject(
                "SELECT external_key IS NULL AND purged_at IS NOT NULL FROM documents WHERE id = ?",
                Boolean.class, original.id());
        assertTrue(released, "exercise real purge, including identity release");

        // 重启/积压 tick 不是又一次出版方观察
        claimAndPark(f.source(), 25);
        var rows = rss.listCurrentEntries(f.source(), 100);
        RssEntries.Entry observation = rows.stream()
                .filter(e -> e.externalKey().equals("preexisting-key")).findFirst().orElseThrow();
        java.util.UUID replacement = null;
        if (observation.hydrationJobId() != null) {
            jdbc.update("""
                    UPDATE jobs SET status='running', attempts=1 WHERE id=? AND status IN ('queued','running')
                    """, observation.hydrationJobId());
            rss.currentAttempt(observation.id(), observation.hydrationJobId());
            replacement = rss.completeHydration(observation.id(), observation.hydrationJobId(),
                    f.source(), 1, f.kb(), "preexisting-key", "existing.md", "text/markdown",
                    10, sha, null).orElse(null);
        }
        Long live = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE source_id = ? AND deleted_at IS NULL",
                Long.class, f.source());
        cleanup(f.org());
        assertNull(replacement, "pre-purge retained payload recreated a purged document");
        assertEquals(0L, live);
    }

    // ---- rss_full_content.rs:752-881（五形态） ----

    /** 迟到的 worker 与 null 观察都要求 purge 之后的新证据（五形态循环，断言逐字）。 */
    @Test
    void lateWorkerAndNullObservationRequireFreshPostPurgeEvidence() throws Exception {
        for (Object[] setup : new Object[][] {
                {true, false, false, false},
                {true, true, false, false},
                {false, true, false, false},
                {false, false, true, false},
                {false, true, true, true}}) {
            boolean createdLater = (Boolean) setup[0];
            boolean admitted = (Boolean) setup[1];
            boolean restore = (Boolean) setup[2];
            boolean observeBeforePurge = (Boolean) setup[3];
            Seed f = seedSource();
            jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
            rss.recordBaseline(f.source(), 1, List.of());
            if (createdLater) {
                rss.discover(f.source(), 1, List.of(entry("race-key")));
            }
            DocumentRow original = documents.createWithVersion(f.kb(), "race.md",
                    "text/markdown", 10, "a".repeat(64), f.source(), null, "source", "race-key",
                    false);
            if (!createdLater) {
                rss.discover(f.source(), 1, List.of(entry("race-key")));
            }
            claimAndPark(f.source(), admitted ? 25 : 0);
            RssEntries.Entry old = rss.listCurrentEntries(f.source(), 100).get(0);
            documents.delete(f.kb(), original.id(), null);
            // 已删的再删报错（documents.rs 的 not deleted）
            assertThrows(AppException.class, () -> documents.delete(f.kb(), original.id(), null));
            if (restore) {
                documents.restore(f.kb(), original.id());
                if (old.hydrationJobId() != null) {
                    assertTrue(finishEntry(f.kb(), f.source(), old.id(),
                            old.hydrationJobId()).isEmpty(), "manual restore must not refresh old worker");
                }
                documents.delete(f.kb(), original.id(), null);
            }
            if (observeBeforePurge) {
                rss.discover(f.source(), 1, List.of(entry("race-key")));
            }
            documents.purge(f.kb(), original.id());
            assertThrows(AppException.class, () -> documents.purge(f.kb(), original.id()));
            assertEquals(0, claimAndPark(f.source(), 25));
            if (old.hydrationJobId() != null) {
                assertTrue(finishEntry(f.kb(), f.source(), old.id(), old.hydrationJobId())
                        .isEmpty(), "late worker recreated purged content");
            }
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM documents WHERE source_id = ? AND deleted_at IS NULL",
                    Long.class, f.source()));
            rss.discover(f.source(), 1, List.of(entry("race-key")));
            assertEquals(1, claimAndPark(f.source(), 25),
                    "actual later observation permits replacement");
            RssEntries.Entry fresh = rss.listCurrentEntries(f.source(), 100).get(0);
            if (old.hydrationJobId() != null) {
                assertTrue(!fresh.hydrationJobId().equals(old.hydrationJobId()));
                assertTrue(finishEntry(f.kb(), f.source(), old.id(), old.hydrationJobId())
                        .isEmpty(), "superseded worker inherited fresh permission");
            }
            java.util.UUID replacement = finishEntry(f.kb(), f.source(), fresh.id(),
                    fresh.hydrationJobId()).orElseThrow();
            assertTrue(!replacement.equals(original.id()));
            assertTrue(finishEntry(f.kb(), f.source(), fresh.id(), fresh.hydrationJobId())
                    .isEmpty());
            cleanup(f.org());
        }
    }

    // ---- rss_full_content.rs:883-920 ----

    @Test
    void laterObservationRestoresSoftDeletedIdentity() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
        rss.recordBaseline(f.source(), 1, List.of());
        rss.discover(f.source(), 1, List.of(entry("race-key")));
        claimAndPark(f.source(), 25);
        RssEntries.Entry old = rss.listCurrentEntries(f.source(), 100).get(0);
        java.util.UUID original = finishEntry(f.kb(), f.source(), old.id(),
                old.hydrationJobId()).orElseThrow();
        documents.delete(f.kb(), original, null);
        assertTrue(finishEntry(f.kb(), f.source(), old.id(), old.hydrationJobId()).isEmpty());
        rss.discover(f.source(), 1, List.of(entry("race-key")));
        assertEquals(1, claimAndPark(f.source(), 25));
        RssEntries.Entry fresh = rss.listCurrentEntries(f.source(), 100).get(0);
        assertEquals(java.util.Optional.of(original), finishEntry(f.kb(), f.source(),
                fresh.id(), fresh.hydrationJobId()));
        cleanup(f.org());
    }

    // ---- rss_full_content.rs:922-997 ----

    /** 完成与删除并发不留活文档；重启 + 通用 failed 重排都不是 feed 证据。 */
    @Test
    void concurrentDeletionAndPublicationLeaveNoLiveDocument() throws Exception {
        for (int round = 0; round < 8; round++) {
            Seed f = seedSource();
            jdbc.update("UPDATE sources SET rss_generation=1 WHERE id = ?", f.source());
            rss.recordBaseline(f.source(), 1, List.of());
            DocumentRow original = documents.createWithVersion(f.kb(), "race.md",
                    "text/markdown", 10, "a".repeat(64), f.source(), null, "source", "race-key",
                    false);
            rss.discover(f.source(), 1, List.of(entry("race-key")));
            claimAndPark(f.source(), 25);
            RssEntries.Entry observation = rss.listCurrentEntries(f.source(), 100).get(0);
            long job = observation.hydrationJobId();
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var completion = pool.submit(() -> finishEntry(f.kb(), f.source(),
                    observation.id(), job));
            var deletion = pool.submit(() -> documents.delete(f.kb(), original.id(), null));
            java.util.Optional<UUID> completed;
            try {
                completed = completion.get(10, java.util.concurrent.TimeUnit.SECONDS);
                deletion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                pool.shutdown();
            }
            if (completed.isPresent()) {
                assertEquals(original.id(), completed.get());
            }
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM documents WHERE source_id = ? AND deleted_at IS NULL",
                    Long.class, f.source()));
            documents.purge(f.kb(), original.id());
            // 模拟重启 + 一次通用 failed 重排——都不是 feed 证据
            jdbc.update("UPDATE jobs SET status='failed' WHERE id = ?", job);
            rss.requeueFailed(new JobsService.RequeueScope(f.kb(), "hydrate_rss_entry", null));
            parkJobs(f.source());
            assertTrue(finishEntry(f.kb(), f.source(), observation.id(), job).isEmpty());
            assertEquals(0, claimAndPark(f.source(), 25));
            cleanup(f.org());
        }
    }

    // ---- rss_full_content.rs:1007-1044 ----

    @Test
    void baselineActivationCreatesNoDocumentsOrHydrationJobs() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1, rss_baselined_at=NULL WHERE id = ?",
                f.source());
        int inserted = rss.recordBaseline(f.source(), 1,
                List.of(entry("baseline-1"), entry("baseline-2")));
        assertEquals(2, inserted);
        RssFullContentMapper.Activation activation = rss.getActivation(f.source());
        assertEquals("active", activation.activationState());
        assertEquals(2, activation.baselineCount());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE source_id = ?", Long.class, f.source()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE kind='hydrate_rss_entry' "
                        + "AND payload->>'source_id' = ?", Long.class, f.source().toString()));
        cleanup(f.org());
    }

    // ---- rss_full_content.rs:1046-1094 ----

    @Test
    void repeatedDiscoveryIsIdempotentAndClaimingIsCappedAtTwentyFive() throws Exception {
        Seed f = seedSource();
        jdbc.update("UPDATE sources SET rss_generation=1, rss_baselined_at=now() WHERE id = ?",
                f.source());
        List<RssEntries.NewEntry> entries = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            entries.add(entry("entry-" + index));
        }
        var first = rss.discover(f.source(), 1, entries);
        assertEquals(30, first.discovered());
        assertEquals(0, first.terminal());
        var second = rss.discover(f.source(), 1, entries);
        assertEquals(0, second.discovered());
        assertEquals(25, claimAndPark(f.source(), 25));
        assertEquals(0, claimAndPark(f.source(), 25));
        RssFullContentService.Counts counts = rss.counts(f.source());
        assertEquals(5, counts.pendingCount());
        assertEquals(25, counts.queuedCount());
        assertEquals(25L, jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE kind = 'hydrate_rss_entry' "
                        + "AND payload->>'source_id' = ?", Long.class, f.source().toString()));
        cleanup(f.org());
    }

    // ---- rss_full_content.rs:1096-1159 ----

    @Test
    void contentReplacementPreservesDocumentIdentityAndQueuesProcessingAtomically()
            throws Exception {
        Seed f = seedSource();
        UUID documentId = Uuids.nowV7();
        String oldSha = "a".repeat(64);
        String newSha = "b".repeat(64);
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, mime, size_bytes, sha256, external_key)
                 VALUES (?, ?, ?, 'entry.md', 'text/markdown', 3, ?, 'rss-key')
                """, documentId, f.kb(), f.source(), oldSha);
        documents.replaceContentAndEnqueue(documentId, "entry.md", "text/markdown", 11, newSha,
                null);
        assertEquals(documentId, jdbc.queryForObject("SELECT id FROM documents WHERE id = ?",
                UUID.class, documentId));
        assertEquals(newSha, jdbc.queryForObject("SELECT sha256 FROM documents WHERE id = ?",
                String.class, documentId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM document_versions WHERE document_id = ? AND sha256 = ?",
                Long.class, documentId, newSha));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE kind='process_document' "
                        + "AND payload->>'document_id' = ?", Long.class, documentId.toString()));
        cleanup(f.org());
    }

    // ---- 工具 ----

    /**
     * 共享库纪律（M2 workerGate/quiesceJob 同法）：入队的 hydrate job 立即 park
     * （run_at+1h），别让共享 Rust worker 在断言途中把它认领走——1516 的参照实例
     * 还在跑。Rust 测试自建自拆不需要这一步；等价点记在此。
     */
    private void parkJobs(UUID source) {
        jdbc.update("""
                UPDATE jobs SET run_at = now() + interval '1 hour'
                 WHERE kind = 'hydrate_rss_entry' AND status = 'queued'
                   AND payload->>'source_id' = ?
                """, source.toString());
    }

    /**
     * 领取 + park <b>同一事务</b>：enqueue（含 pg_notify）与 run_at+1h 一起提交——
     * worker 在提交前看不到这批 job，提交后它们已不可认领，窗口不存在（M2
     * workerGate/quiesceJob 纪律的 RSS 形态）。
     */
    private int claimAndPark(UUID source, int maxInflight) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Integer n = tx.execute(status -> {
            int claimed = rss.claimPendingAndEnqueue(source, 1, maxInflight, 5);
            parkJobs(source);
            return claimed;
        });
        return n == null ? 0 : n;
    }

    private static com.fasterxml.jackson.databind.JsonNode config() throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(
                "{\"feed_url\":\"https://example.com/feed\",\"content_mode\":\"full_new_items\"}");
    }

    private java.util.Optional<UUID> finishEntry(UUID kb, UUID source, UUID entryId, long job)
            throws Exception {
        jdbc.update("""
                UPDATE jobs SET status='running', attempts=1 WHERE id=? AND status IN ('queued','running')
                """, job);
        rss.currentAttempt(entryId, job);
        return rss.completeHydration(entryId, job, source, 1, kb, "race-key", "race.md",
                "text/markdown", 10, "b".repeat(64), null);
    }
}
