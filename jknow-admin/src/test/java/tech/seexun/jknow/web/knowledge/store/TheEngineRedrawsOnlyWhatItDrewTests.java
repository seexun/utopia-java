package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自顶层 tests/the_engine_redraws_only_what_it_drew.rs（1000 行，12 例）——引擎只
 * 重画自己画的终点（#679 第三轮评审的复现，留作回归）：两侧唯一不翻面/原文说结束的旧值
 * 不关当下/撤回合并不改写窗口/等锁的删除连改写行一并作废/并发落库证据跟行/人裁过的对
 * 不再问/没人裁的冲突改写后仍开/引擎关的被驳回后原文终点保住/删文档不再定序/说不出时间
 * 的值先后同对/0057 回填/连环合并撤回/冲突跟行走/批量删除与恢复。
 */
class TheEngineRedrawsOnlyWhatItDrewTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;
    @Autowired
    ResolutionService resolution;
    @Autowired
    DocumentsWriteService documents;

    private static OffsetDateTime t(String day) {
        return OffsetDateTime.parse(day + "T00:00:00Z");
    }

    record Fixture(UUID org, UUID kb, UUID etype, UUID lease, UUID deadline) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID deadline = Uuids.nowV7();
        UUID leaseId = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'redraw-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'redraw-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'redraw-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'lease', 'Lease')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, temporal, functional)
                 VALUES (?, ?, 'option_deadline', 'option deadline', 'attribute', 'date', 'state', TRUE)
                """, deadline, kb);
        UUID lease = entity(kb, etype, "HQ Lease", leaseId);
        return new Fixture(org, kb, etype, lease, deadline);
    }

    private UUID entity(UUID kb, UUID etype, String name, UUID id) {
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, kb, etype, name);
        return id;
    }

    /** 一次观察：值、起止、出自哪天的文档、置信度。 */
    record Seen(UUID subject, String value, OffsetDateTime from, OffsetDateTime to,
                boolean endedUnknown, OffsetDateTime doc, float confidence, boolean described) {

        static Seen of(Fixture f, String value, String from) {
            return new Seen(f.lease(), value, t(from), null, false, null, 0.9f, false);
        }
    }

    private UUID[] document(UUID kb, String day, String text) {
        UUID d = Uuids.nowV7();
        UUID c = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO documents (id, kb_id, filename, sha256, doc_time, doc_time_source)
                 VALUES (?, ?, ?, ?, ?, 'content')
                """, d, kb, "doc-" + d + ".html", d.toString(), t(day));
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                c, kb, d, text);
        return new UUID[]{d, c};
    }

    /** 抽取的写法：落库（或并进已有断言）、写证据、对账。 */
    private UUID observe(Fixture f, Seen x) {
        Validity validity = new Validity(x.from(), x.from() != null ? "day" : null, x.to(),
                x.to() != null ? "day" : null, null, null);
        Validity v = new Validity(validity.from, validity.fromPrecision,
                x.endedUnknown() ? null : validity.to,
                x.endedUnknown() ? "unknown" : validity.toPrecision, null, null);
        UUID chunk = null;
        if (x.doc() != null) {
            UUID[] dc = document(f.kb(), x.doc().toString().substring(0, 10), x.value());
            v = new Validity(v.from, v.fromPrecision, v.to, v.toPrecision,
                    t(x.doc().toString().substring(0, 10)), null);
            chunk = dc[1];
        }
        var object = JsonNodeFactory.instance.objectNode().put("value", x.value());
        var wrote = writes.insertValueFact(f.kb(), x.subject(), f.deadline(), object, v,
                x.confidence());
        if (x.from() == null && x.doc() == null) {
            // 模拟文档没有时间（0064），不使用人工写入口的此刻见证
            jdbc.update("UPDATE facts SET attested_from = NULL WHERE id = ?", wrote.id());
        }
        if (chunk != null) {
            if (x.described()) {
                jdbc.update("""
                        UPDATE chunks SET origin = 'described', origin_model = 'test-vision',
                                anchor = jsonb_build_object('page', 1, 'image', 0) WHERE id = ?
                        """, chunk);
            }
            writes.addEvidence(wrote.id(), chunk, x.value(), null);
        }
        temporal.reconcileNewFact(f.kb(), wrote.id(), x.subject(), f.deadline(), null,
                Side.SUBJECT_SIDE);
        return wrote.id();
    }

    /** (值, 起点, 终点, 终点精度, 终点锚点)。 */
    private List<String[]> timeline(Fixture f, UUID subject) {
        return jdbc.query("""
                SELECT object_value #>> '{value}', to_char(valid_from, 'YYYY-MM-DD'),
                        to_char(valid_to, 'YYYY-MM-DD'), valid_to_precision,
                        to_char(attested_to, 'YYYY-MM-DD')
                 FROM facts f
                  WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                  ORDER BY """ + " " + HF_F + " NULLS FIRST, " + HT_F
                + " NULLS LAST, object_value #>> '{value}'",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)}, f.kb(), subject, f.deadline());
    }

    private long overlaps(Fixture f, UUID subject) {
        return jdbc.queryForObject("""
                WITH r AS (
                    SELECT f.id, f.object_value, %s AS hf, %s AS ht FROM facts f
                    WHERE f.kb_id = ? AND f.subject_id = ? AND f.predicate_id = ?
                      AND f.invalidated_at IS NULL)
                 SELECT count(*) FROM r a JOIN r b ON a.id < b.id
                    AND a.object_value IS DISTINCT FROM b.object_value
                 WHERE COALESCE(a.hf, '-infinity') < COALESCE(b.ht, 'infinity')
                   AND COALESCE(b.hf, '-infinity') < COALESCE(a.ht, 'infinity')
                """.formatted(HF_F, HT_F), Long.class, f.kb(), subject, f.deadline());
    }

    private long openRows(Fixture f, UUID subject) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM facts WHERE kb_id = ? AND subject_id = ? AND predicate_id = ?
                   AND invalidated_at IS NULL AND valid_to IS NULL AND valid_to_precision IS NULL
                """, Long.class, f.kb(), subject, f.deadline());
    }

    private List<String> openConflicts(UUID kb) {
        return jdbc.queryForList(
                "SELECT reason FROM fact_conflicts WHERE kb_id = ? AND status = 'open' ORDER BY reason",
                String.class, kb);
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
    }

    private List<String> stateOf(UUID kb, UUID leads) {
        return jdbc.query("""
                SELECT o.canonical_name, to_char(x.valid_to, 'YYYY-MM-DD')
                  FROM facts x JOIN entities o ON o.id = x.object_id
                 WHERE x.kb_id = ? AND x.predicate_id = ? AND x.invalidated_at IS NULL
                 ORDER BY x.valid_from
                """, (rs, i) -> rs.getString(1) + "|" + rs.getString(2), kb, leads);
    }

    private long totalOf(UUID leads) {
        return jdbc.queryForObject("SELECT count(*) FROM facts WHERE predicate_id = ?",
                Long.class, leads);
    }

    /** 「一个人同时只领导一个项目，一个项目同时只有一个领导」：一行在两条时间线上。 */
    @Test
    void aRelationUniqueOnBothSidesDoesNotFlipBetweenThem() {
        Fixture f = seed();
        try {
            UUID leads = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, temporal, functional, inverse_functional)
                     VALUES (?, ?, 'leads', 'leads', 'relation', 'state', TRUE, TRUE)
                    """, leads, f.kb());
            UUID pat = entity(f.kb(), f.etype(), "Pat", Uuids.nowV7());
            UUID a = entity(f.kb(), f.etype(), "Project A", Uuids.nowV7());
            UUID b = entity(f.kb(), f.etype(), "Project B", Uuids.nowV7());
            long totalAfterB = 0;
            List<String> rowsAfterB = List.of();
            int edgeIndex = 0;
            for (Object[] edge : new Object[][]{{a, "2020-01-01"}, {b, "2021-01-01"},
                    {a, "2020-01-01"}, {a, "2020-01-01"}}) {
                Validity validity = new Validity(t((String) edge[1]), "day", null, null, null, null);
                var wrote = writes.insertFact(f.kb(), pat, leads, (UUID) edge[0], validity, 0.9f);
                temporal.reconcileNewFact(f.kb(), wrote.id(), pat, leads, (UUID) edge[0],
                        Side.SUBJECT_SIDE);
                temporal.reconcileNewFact(f.kb(), wrote.id(), pat, leads, (UUID) edge[0],
                        Side.OBJECT_SIDE);
                if (++edgeIndex == 2) {
                    totalAfterB = totalOf(leads);
                    rowsAfterB = stateOf(f.kb(), leads);
                }
            }
            assertEquals(List.of("Project A|2021-01-01", "Project B|null"), rowsAfterB);
            assertEquals(rowsAfterB, stateOf(f.kb(), leads));
            assertEquals(totalAfterB, totalOf(leads), "再读一遍没变的事实，时间线不该变，也不该多出行");
        } finally {
            cleanup(f);
        }
    }

    /** 原文说一个旧值已经结束：那份文件的日期不能把它排在 2021 年、去关上当下的值。 */
    @Test
    void aValueTheTextSaysHasEndedDoesNotCloseTheCurrentOne() {
        Fixture f = seed();
        try {
            observe(f, new Seen(f.lease(), "HPBB1", t("2016-05-16"), null, false, t("2016-05-16"), 0.9f, false));
            observe(f, new Seen(f.lease(), "BBHQ1", t("2020-07-31"), null, false, t("2020-09-01"), 0.9f, false));
            observe(f, new Seen(f.lease(), "HPBB1", null, null, true, t("2021-03-01"), 0.9f, false));
            long recital = openRows(f, f.lease());
            cleanup(f);

            // 结束在一个写明的日子，没有起点，出自一份更晚的文件
            Fixture f2 = seed();
            try {
                observe(f2, Seen.of(f2, "NEW", "2020-01-01"));
                observe(f2, new Seen(f2.lease(), "OLD", null, t("2019-12-31"), false, t("2021-03-01"), 0.9f, false));
                long dated = openRows(f2, f2.lease());
                assertEquals(1L, recital);
                assertEquals(1L, dated);
            } finally {
                cleanup(f2);
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 合并后到了一个值关上搬来的行；撤回。回放合并窗口的某一刻，撤前撤后答案一样。 */
    @Test
    void aRevertDoesNotRewriteWhatTheMergeWindowHeld() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            observe(f, Seen.of(f, "A", "2019-01-01"));
            observe(f, new Seen(source, "D", t("2020-04-01"), null, false, null, 0.9f, false));
            UUID merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            observe(f, Seen.of(f, "N", "2020-06-01"));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            OffsetDateTime mid = jdbc.queryForObject("SELECT clock_timestamp()",
                    OffsetDateTime.class);
            // 记录轴回放（record_axis facts_held_at / owner_at 的展开）
            List<Object[]> before = replay(f, mid);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            resolution.revertMerge(f.kb(), merge);
            List<Object[]> after = replay(f, mid);
            assertEquals(before.stream().map(java.util.Arrays::toString).toList(),
                    after.stream().map(java.util.Arrays::toString).toList());
            assertEquals(1, timeline(f, source).size(), "D 回到源实体上");
        } finally {
            cleanup(f);
        }
    }

    /**
     * 记录轴回放（record_axis.rs:45 held + :143 owner_at 的展开，as_of=参数版）：
     * 断言在 T 时刻仍被持有；行当时的属主经 fact_owner_at 归并回放。
     */
    private List<Object[]> replay(Fixture f, OffsetDateTime at) {
        return jdbc.query("""
                SELECT object_value #>> '{value}', to_char(valid_to, 'YYYY-MM-DD'),
                        fact_owner_at(f.id, f.subject_id, ?::timestamptz, false) = ?::uuid
                 FROM facts f WHERE f.kb_id = ? AND f.predicate_id = ?
                   AND f.recorded_at <= coalesce(?::timestamptz, now())
                   AND (f.invalidated_at IS NULL OR f.invalidated_at > coalesce(?::timestamptz, now()))
                 ORDER BY valid_from
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getBoolean(3)},
                at, f.lease(), f.kb(), f.deadline(), at, at);
    }

    /** 删除文档等锁时那一行被改写成新行：锁上之后重读名单，新行一并作废。 */
    @Test
    void aDeletionThatWaitedForTheLockStillTakesTheRewrittenRow() throws Exception {
        Fixture f = seed();
        try {
            UUID a = observe(f, new Seen(f.lease(), "A", t("2020-01-01"), null, false, t("2020-01-01"), 0.9f, false));
            UUID doc = jdbc.queryForObject(
                    "SELECT document_id FROM fact_evidence WHERE fact_id = ?", UUID.class, a);
            // 像一次时间线重算那样持着锁，把 A 改写成新的一行
            String key = "timeline:" + f.kb() + ":" + f.lease() + ":" + f.deadline() + ":SubjectSide";
            try (var tx = jdbc.getDataSource().getConnection()) {
                boolean old = tx.getAutoCommit();
                tx.setAutoCommit(false);
                try (var lock = tx.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, key);
                    lock.executeQuery();
                }
                var deleting = new Thread(() -> {
                    try {
                        documents.delete(f.kb(), doc, null);
                    } catch (Exception ignored) {
                        // 删除线程的失败不影响断言面（锁释放后应成功）
                    }
                });
                deleting.start();
                Thread.sleep(400);
                UUID rewritten = Uuids.nowV7();
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value, valid_from,
                                            valid_from_precision, valid_to, valid_to_precision, confidence,
                                            supersedes, attested_from, end_derived)
                         SELECT ?, kb_id, subject_id, predicate_id, object_value, valid_from, valid_from_precision,
                                '2020-06-01T00:00:00Z', 'day', confidence, id, attested_from, TRUE FROM facts WHERE id = ?
                        """, rewritten, a);
                jdbc.update("""
                        INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id, doc_version)
                         SELECT ?, chunk_id, quote, document_id, doc_version FROM fact_evidence WHERE fact_id = ?
                        """, rewritten, a);
                jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", a);
                tx.commit();
                deleting.join(10_000);
                Boolean alive = jdbc.queryForObject(
                        "SELECT invalidated_at IS NULL FROM facts WHERE id = ?", Boolean.class,
                        rewritten);
                assertTrue(!Boolean.TRUE.equals(alive), "唯一的出处删掉了，改写出来的那一行也该作废");
                tx.setAutoCommit(old);
            }
        } finally {
            cleanup(f);
        }
    }

    /** 并行落库：证据不会写到刚被改写掉的旧行上，每一条现存的行都有证据。 */
    @Test
    void evidenceFollowsARowRewrittenWhileItWasWritten() throws Exception {
        for (int round = 0; round < 30; round++) {
            Fixture f = seed();
            try {
                var pool = Executors.newFixedThreadPool(5);
                var done = new java.util.concurrent.CountDownLatch(5);
                var failures = new java.util.concurrent.CopyOnWriteArrayList<Exception>();
                for (Object[] spec : new Object[][]{
                        new Object[]{"v4", "2020-05-10", true},
                        new Object[]{"v2", "2020-03-17", true},
                        new Object[]{"v5", "2020-06-08", false},
                        new Object[]{"v1", "2020-02-18", true},
                        new Object[]{"v3", "2020-04-14", false}}) {
                    final boolean withStart = (Boolean) spec[2];
                    final String value = (String) spec[0];
                    final String day = (String) spec[1];
                    pool.submit(() -> {
                        try {
                            observe(f, new Seen(f.lease(), value,
                                    withStart ? t(day) : null, null, false, t(day), 0.9f, false));
                        } catch (Exception e) {
                            failures.add(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
                pool.shutdown();
                assertTrue(failures.isEmpty(), failures.toString());
                long bare = jdbc.queryForObject("""
                        SELECT count(*) FROM facts f WHERE f.kb_id = ? AND f.invalidated_at IS NULL
                           AND NOT EXISTS (SELECT 1 FROM fact_evidence fe WHERE fe.fact_id = f.id)
                        """, Long.class, f.kb());
                long n = overlaps(f, f.lease());
                assertEquals(0L, bare);
                assertEquals(0L, n);
            } finally {
                cleanup(f);
            }
        }
    }

    /** 人裁过「两个都留着」的一对，两行后来被改写，不再问第二遍。 */
    @Test
    void aPairAPersonKeptIsNotAskedAgainAfterARewrite() {
        Fixture f = seed();
        try {
            observe(f, Seen.of(f, "A", "2020-01-01"));
            observe(f, new Seen(f.lease(), "B", t("2020-03-01"), null, false, t("2020-03-01"), 0.9f, true));
            List<UUID> asked = jdbc.queryForList(
                    "SELECT id FROM fact_conflicts WHERE kb_id = ? AND status = 'open'", UUID.class,
                    f.kb());
            for (UUID conflict : asked) {
                temporal.resolveConflict(f.kb(), conflict, "keep", null, "day");
            }
            observe(f, Seen.of(f, "C", "2020-06-01"));
            List<String> again = openConflicts(f.kb());
            assertEquals(1, asked.size());
            assertEquals(List.of(), again);
        } finally {
            cleanup(f);
        }
    }

    /** 同一天开始的两个值记了一对冲突；后来的值把两行都改写了，那对冲突还开着。 */
    @Test
    void anUnresolvedConflictStaysOpenWhenItsRowsAreRewritten() {
        Fixture f = seed();
        try {
            observe(f, Seen.of(f, "P", "2020-01-01"));
            observe(f, Seen.of(f, "Q", "2020-01-01"));
            List<String> before = openConflicts(f.kb());
            observe(f, Seen.of(f, "R", "2020-06-01"));
            List<String> after = openConflicts(f.kb());
            assertEquals(List.of("simultaneous"), before);
            assertEquals(before, after);
        } finally {
            cleanup(f);
        }
    }

    /** 引擎把 A 关在 B 开始时；一份文件写明 A 到那天为止。后来 B 被驳回，A 仍到那天为止。 */
    @Test
    void anEndTheTextStatesOnARowTheEngineClosedIsKept() {
        Fixture f = seed();
        try {
            observe(f, Seen.of(f, "A", "2020-01-01"));
            UUID b = observe(f, Seen.of(f, "B", "2020-03-01"));
            observe(f, new Seen(f.lease(), "A", t("2020-01-01"), t("2020-03-01"), false, t("2020-04-01"), 0.9f, false));
            writes.rejectFact(f.kb(), b);
            List<String[]> rows = timeline(f, f.lease());
            assertEquals(1, rows.size());
            assertEquals("2020-03-01", rows.get(0)[2]);
        } finally {
            cleanup(f);
        }
    }

    /** 删掉一个没起点的值最早那份文档：它不再在那天排进时间线，前任的锚点跟着往后挪。 */
    @Test
    void aDeletedDocumentNoLongerDatesARow() {
        Fixture f = seed();
        try {
            observe(f, Seen.of(f, "P", "2019-01-01"));
            UUID s = observe(f, new Seen(f.lease(), "S", null, null, false, t("2020-06-01"), 0.9f, false));
            UUID early = jdbc.queryForObject(
                    "SELECT document_id FROM fact_evidence WHERE fact_id = ?", UUID.class, s);
            observe(f, new Seen(f.lease(), "S", null, null, false, t("2021-06-01"), 0.9f, false));
            documents.delete(f.kb(), early, null);
            List<String[]> rows = timeline(f, f.lease());
            assertEquals("P", rows.get(0)[0]);
            assertEquals("2021-06-01", rows.get(0)[4]);
        } finally {
            cleanup(f);
        }
    }

    /** 一个说不出时间的值与一个有日期的值：谁先到都是同一对冲突交给人，两边都开着。 */
    @Test
    void aValueWithNoTimeMeetsADatedOneTheSameWayInEitherOrder() {
        Fixture f = seed();
        try {
            UUID other = entity(f.kb(), f.etype(), "Other Lease", Uuids.nowV7());
            observe(f, new Seen(f.lease(), "K", null, null, false, null, 0.9f, false));
            observe(f, Seen.of(f, "N", "2020-01-01"));
            observe(f, new Seen(other, "N", t("2020-01-01"), null, false, null, 0.9f, false));
            observe(f, new Seen(other, "K", null, null, false, null, 0.9f, false));
            var shape = new java.util.function.Function<List<String[]>, java.util.Set<String>>() {
                @Override
                public java.util.Set<String> apply(List<String[]> rows) {
                    java.util.Set<String> out = new java.util.TreeSet<>();
                    for (String[] r : rows) {
                        out.add(r[0] + "|" + r[2] + "|" + r[3]);
                    }
                    return out;
                }
            };
            var kFirst = shape.apply(timeline(f, f.lease()));
            var nFirst = shape.apply(timeline(f, other));
            List<String> asked = openConflicts(f.kb());
            assertEquals(kFirst, nFirst);
            assertEquals(List.of("no_time", "no_time"), asked);
        } finally {
            cleanup(f);
        }
    }

    /** 迁移之前引擎关上的行由 0057 回填成引擎画的；人关上的、原文说出终点的不回填。 */
    @Test
    void theMigrationMarksTheEndsTheOldEngineDrew() throws Exception {
        String backfill = readMigration("V0057__an_end_the_engine_drew_moves_with_what_follows.sql");
        backfill = backfill.substring(backfill.indexOf("UPDATE facts c"));

        Fixture f = seed();
        try {
            observe(f, Seen.of(f, "v1", "2020-02-18"));
            observe(f, Seen.of(f, "v5", "2020-06-08"));
            // 人手动关上的一段，与引擎关的形状一样
            UUID other = entity(f.kb(), f.etype(), "Other Lease", Uuids.nowV7());
            UUID byHand = observe(f, new Seen(other, "h1", t("2020-01-01"), null, false, null, 0.9f, false));
            observe(f, new Seen(other, "h2", t("2020-03-01"), null, false, t("2020-03-01"), 0.9f, true));
            temporal.closeSuperseded(byHand, t("2020-03-01"), "day");
            jdbc.update("""
                    INSERT INTO audit_events (id, kb_id, action, target_kind, target_id)
                     VALUES (?, ?, 'fact.close', 'fact', ?)
                    """, Uuids.nowV7(), f.kb(), byHand);
            // 迁移之前的库：这一列全是假
            jdbc.update("UPDATE facts SET end_derived = FALSE WHERE kb_id = ?", f.kb());
            try (var conn = jdbc.getDataSource().getConnection();
                 var st = conn.createStatement()) {
                st.execute(backfill);
            }
            List<String> marked = jdbc.queryForList("""
                    SELECT object_value #>> '{value}' FROM facts
                      WHERE kb_id = ? AND invalidated_at IS NULL AND end_derived ORDER BY 1
                    """, String.class, f.kb());
            observe(f, Seen.of(f, "v2", "2020-03-17"));
            observe(f, Seen.of(f, "v3", "2020-04-14"));
            long n = overlaps(f, f.lease());
            assertEquals(List.of("v1"), marked, "只有引擎关的那一段");
            assertEquals(0L, n);
        } finally {
            cleanup(f);
        }
    }

    private String readMigration(String name) throws Exception {
        try (var in = new org.springframework.core.io.ClassPathResource("db/migration/" + name)
                .getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 连环合并里撤回头一环：S 的此刻挂在 C 身上，也要跟着 S 回去，C 的时间线上不留它。 */
    @Test
    void undoingTheFirstMergeOfAChainBringsItsFactsHome() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease S", Uuids.nowV7());
            UUID chainEnd = entity(f.kb(), f.etype(), "Lease C", Uuids.nowV7());
            observe(f, new Seen(source, "S1", t("2020-01-01"), null, false, null, 0.9f, false));
            UUID first = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            resolution.mergeEntities(f.kb(), f.lease(), chainEnd, null, "test");
            resolution.revertMerge(f.kb(), first);
            List<String[]> home = timeline(f, source);
            List<String[]> tail = timeline(f, chainEnd);
            assertEquals(1, home.size(), "S1 回到 S 上");
            assertEquals("S1", home.get(0)[0]);
            assertTrue(tail.stream().noneMatch(s -> "S1".equals(s[0])), "C 上不留 S1");
        } finally {
            cleanup(f);
        }
    }

    /** 撤回合并把合并之后改写出来的行送回源实体时，它们身上开着的冲突跟着走。 */
    @Test
    void aRevertKeepsTheConflictItsRowsCarried() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            for (String value : new String[]{"D", "E"}) {
                observe(f, new Seen(source, value, t("2020-01-01"), null, false, null, 0.9f, false));
            }
            UUID merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            observe(f, Seen.of(f, "N", "2020-06-01"));
            resolution.revertMerge(f.kb(), merge);
            List<String> conflicts = openConflicts(f.kb());
            List<String[]> back = timeline(f, source);
            long overlap = overlaps(f, source);
            assertEquals(2, back.size(), "D 与 E 回到源实体");
            assertTrue(overlap == 0 || conflicts.contains("simultaneous"),
                    "两行叠在一起就得有一条开着的冲突");
        } finally {
            cleanup(f);
        }
    }

    /** 删一篇给几百个实体各记了一个属性的文档：改拿谓词一级的锁，删得掉，也都作废了。 */
    @Test
    void deletingADocumentThatDatesManyTimelinesStillWorks() {
        Fixture f = seed();
        try {
            UUID[] dc = document(f.kb(), "2020-01-01", "a table");
            UUID doc = dc[0];
            UUID chunk = dc[1];
            int n = 300;
            for (int i = 0; i < n; i++) {
                UUID holder = entity(f.kb(), f.etype(), "Lease " + i, Uuids.nowV7());
                var wrote = writes.insertValueFact(f.kb(), holder, f.deadline(),
                        JsonNodeFactory.instance.objectNode()
                                .put("value", String.format("2021-01-%02d", i % 28 + 1)),
                        new Validity(t("2020-01-01"), "day", null, null, null, null), 0.9f);
                writes.addEvidence(wrote.id(), chunk, "row", null);
            }
            var report = documents.delete(f.kb(), doc, null);
            var restored = documents.restore(f.kb(), doc);
            long live = jdbc.queryForObject("""
                    SELECT count(*) FROM facts WHERE kb_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                    """, Long.class, f.kb(), f.deadline());
            assertEquals(n, report.invalidatedFacts());
            assertEquals(n, live, "撤销删除之后全都回来");
        } finally {
            cleanup(f);
        }
    }

    /** 删掉没起点那一行最早的证据文档：锚点挪到第二份文档，两段不叠；撤销删除又都回去。 */
    @Test
    void deletingTheFirstDocumentMovesTheReadStartWithTheAnchor() {
        Fixture f = seed();
        try {
            observe(f, Seen.of(f, "P", "2019-01-01"));
            for (String day : new String[]{"2020-06-01", "2021-06-01"}) {
                observe(f, new Seen(f.lease(), "S", null, null, false, t(day), 0.9f, false));
            }
            UUID first = jdbc.queryForObject("""
                    SELECT d.id FROM fact_evidence fe JOIN chunks c ON c.id = fe.chunk_id
                       JOIN documents d ON d.id = c.document_id
                      WHERE d.kb_id = ? ORDER BY d.doc_time LIMIT 1
                    """, UUID.class, f.kb());
            long before = overlaps(f, f.lease());
            documents.delete(f.kb(), first, null);
            long afterDelete = overlaps(f, f.lease());
            documents.restore(f.kb(), first);
            long afterRestore = overlaps(f, f.lease());
            assertEquals(0L, before);
            assertEquals(0L, afterDelete);
            assertEquals(0L, afterRestore);
        } finally {
            cleanup(f);
        }
    }
}
