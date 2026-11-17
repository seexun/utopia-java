package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_timeline_is_recomputed_from_the_rows_it_has.rs（859 行全量）——
 * 一条时间线只取决于它有哪些行（#679 第二轮评审的复现，留作回归）。
 * 等价点（tokio→JUnit）：tokio::join! 的并发观察改用固定线程池 + Future.get 全收集
 * （错误聚合语义与 x?/y?/z? 一致）；无 JKNOW_TEST_JDBC_URL 跳过 → SharedDbGuard。
 * 依赖 merge_entities/revert_merge 的 6 例（M3 任务 4 起走 ResolutionService 真身）。
 */
class ATimelineIsRecomputedFromTheRowsItHasTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;
    @Autowired
    DocumentsWriteService documentsService;
    @Autowired
    tech.seexun.jknow.store.ResolutionService resolution;

    record Fixture(UUID kb, UUID etype, UUID lease, UUID deadline) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID deadline = Uuids.nowV7();
        UUID lease = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'recompute-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'recompute-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'recompute-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'lease', 'Lease')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, temporal, functional)
                 VALUES (?, ?, 'option_deadline', 'option deadline', 'attribute', 'date', 'state', TRUE)
                """, deadline, kb);
        lease = entity(kb, etype, "HQ Lease", lease);
        return new Fixture(kb, etype, lease, deadline);
    }

    private UUID entity(UUID kb, UUID etype, String name, UUID id) {
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, kb, etype, name);
        return id;
    }

    private static OffsetDateTime t(String day) {
        return OffsetDateTime.parse(day + "T00:00:00Z");
    }

    /** 一次观察：值、起止、出自哪天的哪种文档、置信度、起点的来历（Rust Seen）。 */
    static class Seen {
        UUID subject;
        String value;
        String from;
        String to;
        String doc;
        float confidence = 0.9f;
        String grade;

        Seen(Fixture f, String value, String from) {
            this.subject = f.lease();
            this.value = value;
            this.from = from;
        }
    }

    /**
     * 抽取的写法（Rust observe）：落库（或并进已有断言）、写证据、对账——
     * <b>并进已有断言的也对账</b>。
     */
    private UUID observe(Fixture f, Seen x) {
        Validity validity = new Validity(x.from == null ? null : t(x.from),
                x.from == null ? null : "day", x.to == null ? null : t(x.to),
                x.to == null ? null : "day", null, x.grade);
        UUID chunk = null;
        if (x.doc != null) {
            UUID d = Uuids.nowV7();
            UUID c = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO documents (id, kb_id, filename, sha256, doc_time, doc_time_source)
                     VALUES (?, ?, ?, ?, ?, 'content')
                    """, d, f.kb(), "doc-" + d + ".html", "doc-" + d + ".html", t(x.doc));
            jdbc.update(
                    "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                    c, f.kb(), d, x.value);
            validity = validity.attested(t(x.doc));
            chunk = c;
        }
        JsonNode object = JSON.createObjectNode().put("value", x.value);
        var write = writes.insertValueFact(f.kb(), x.subject, f.deadline(), object, validity,
                x.confidence);
        UUID id = write.id();
        if (chunk != null) {
            writes.addEvidence(id, chunk, x.value, null);
        }
        temporal.reconcileNewFact(f.kb(), id, x.subject, f.deadline(), null, Side.SUBJECT_SIDE);
        return id;
    }

    /** (值, 起点, 终点, 终点精度, 终点锚点, 读出的起点, 读出的终点)——Rust Seg。 */
    record Seg(String value, String from, String to, String prec, String anchor, String hf,
               String ht) {
    }

    private List<Seg> timeline(Fixture f, UUID subject) {
        return jdbc.query("""
                        SELECT object_value #>> '{value}', to_char(valid_from, 'YYYY-MM-DD'),
                                to_char(valid_to, 'YYYY-MM-DD'), valid_to_precision,
                                to_char(attested_to, 'YYYY-MM-DD'),
                                to_char(%s, 'YYYY-MM-DD'), to_char(%s, 'YYYY-MM-DD')
                         FROM facts f
                         WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                         ORDER BY %s NULLS FIRST, %s NULLS LAST, object_value #>> '{value}'
                        """.formatted(HF_F, HT_F, HF_F, HT_F),
                (rs, i) -> new Seg(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                f.kb(), subject, f.deadline());
    }

    /** 读出来的区间叠在一起、值又不同的行有几对。 */
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

    private UUID liveId(Fixture f, UUID subject, String value) {
        return jdbc.queryForObject("""
                        SELECT id FROM facts WHERE kb_id = ? AND subject_id = ?
                           AND object_value #>> '{value}' = ? AND invalidated_at IS NULL
                        """, UUID.class, f.kb(), subject, value);
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
    }

    /**
     * 后面那一段晚到的文件补上了起点（第十一份补充协议之后，交割文件说 BBHQ1 从 7 月 31 日
     * 起就是房东）：HPBB1 止于那一天，不再叠到 8 月 13 日。
     */
    @Test
    void a_start_that_arrives_later_moves_the_end_before_it() {
        Fixture f = seed();
        try {
            Seen hpbb1 = new Seen(f, "HPBB1", "2016-05-16");
            hpbb1.doc = "2016-05-16";
            observe(f, hpbb1);
            Seen bare = new Seen(f, "BBHQ1", null);
            bare.doc = "2020-08-13";
            observe(f, bare);
            Seen started = new Seen(f, "BBHQ1", "2020-07-31");
            started.doc = "2020-09-01";
            observe(f, started);
            List<Seg> rows = timeline(f, f.lease());
            long n = overlaps(f, f.lease());
            assertEquals(0, n, rows::toString);
            assertEquals("HPBB1", rows.get(0).value());
            assertEquals("2020-07-31", rows.get(0).to(), rows::toString);
        } finally {
            cleanup(f);
        }
    }

    /** 没起点的后任又被一份更早的文件提到：前任的锚点跟着往前挪。 */
    @Test
    void earlier_evidence_for_a_value_with_no_start_moves_the_end_before_it() {
        Fixture f = seed();
        try {
            Seen p = new Seen(f, "P", "2019-01-01");
            p.doc = "2019-01-01";
            observe(f, p);
            Seen s1 = new Seen(f, "S", null);
            s1.doc = "2021-06-01";
            observe(f, s1);
            Seen s2 = new Seen(f, "S", null);
            s2.doc = "2020-06-01";
            observe(f, s2);
            List<Seg> rows = timeline(f, f.lease());
            long n = overlaps(f, f.lease());
            assertEquals(0, n, rows::toString);
            assertEquals("P", rows.get(0).value());
            assertEquals("2020-06-01", rows.get(0).anchor(), rows::toString);
        } finally {
            cleanup(f);
        }
    }

    /** 一条没起点的值先到，有起点的后到：文档日期从不写进日期列，也不叠。 */
    @Test
    void a_value_with_no_start_that_arrives_first_still_takes_its_place() {
        Fixture f = seed();
        try {
            Seen s = new Seen(f, "S", null);
            s.doc = "2021-01-01";
            observe(f, s);
            observe(f, new Seen(f, "P", "2019-01-01"));
            observe(f, new Seen(f, "Q", "2020-01-01"));
            long n = overlaps(f, f.lease());
            Long inDateColumns = jdbc.queryForObject("""
                    SELECT count(*) FROM facts
                      WHERE kb_id = ? AND (valid_from = '2021-01-01' OR valid_to = '2021-01-01')
                    """, Long.class, f.kb());
            long open = openRows(f, f.lease());
            assertEquals(0L, n);
            assertEquals(0L, inDateColumns);
            assertEquals(1L, open);
        } finally {
            cleanup(f);
        }
    }

    /** 已经结束的新值与开着的旧值，谁先到都一样。 */
    @Test
    void a_closed_value_and_an_open_one_meet_the_same_way_in_either_order() {
        Fixture f = seed();
        try {
            UUID other = entity(f.kb(), f.etype(), "Other Lease", Uuids.nowV7());
            observe(f, new Seen(f, "A", "2018-01-01"));
            Seen closedHere = new Seen(f, "B", "2019-01-01");
            closedHere.to = "2021-01-01";
            observe(f, closedHere);
            Seen closedThere = new Seen(f, "B", "2019-01-01");
            closedThere.subject = other;
            closedThere.to = "2021-01-01";
            observe(f, closedThere);
            Seen aThere = new Seen(f, "A", "2018-01-01");
            aThere.subject = other;
            observe(f, aThere);
            List<Seg> aThenB = timeline(f, f.lease());
            List<Seg> bThenA = timeline(f, other);
            long n = overlaps(f, f.lease());
            assertEquals(aThenB, bThenA);
            assertEquals(0L, n);
        } finally {
            cleanup(f);
        }
    }

    /** 七十个置信度不够的值排在前面，一个够格的值到了：每一个都关上，没有轮数用完这回事。 */
    @Test
    void unanchored_values_do_not_keep_a_placed_one_from_closing_them() {
        Fixture f = seed();
        try {
            for (int i = 0; i < 70; i++) {
                Seen x = new Seen(f, "w" + String.format("%02d", i),
                        String.format("%d-%02d-01", 2000 + i / 12, i % 12 + 1));
                x.grade = "C";
                observe(f, x);
            }
            observe(f, new Seen(f, "Z", "2010-01-01"));
            long open = openRows(f, f.lease());
            assertEquals(1L, open, "只有 Z 还开着");
        } finally {
            cleanup(f);
        }
    }

    /**
     * 起点锚不到的后任不关前任（0045 第 3 刀）：它进得了图、排得进时间线，可它在轴上的
     * 位置是文档日期给的，不是那句话给的，拿它改写历史就是拿一个猜的位置改写历史。
     */
    @Test
    void an_unanchored_successor_closes_nothing() {
        Fixture f = seed();
        try {
            observe(f, new Seen(f, "first", "2020-01-01"));
            Seen second = new Seen(f, "second", "2021-01-01");
            second.grade = "C";
            observe(f, second);
            long open = openRows(f, f.lease());
            // 再来一个锚得住的：这一个才关得了前面两个
            observe(f, new Seen(f, "third", "2022-01-01"));
            long after = openRows(f, f.lease());
            assertEquals(2L, open, "锚不到的那一行关不了它前面的");
            assertEquals(1L, after, "锚得住的一来，前面两行都关上");
        } finally {
            cleanup(f);
        }
    }

    /** 人驳回了后任：关在它开始时的前任重新开着。 */
    @Test
    void rejecting_a_successor_reopens_the_value_it_closed() {
        Fixture f = seed();
        try {
            observe(f, new Seen(f, "A", "2020-01-01"));
            UUID b = observe(f, new Seen(f, "B", "2020-04-01"));
            writes.rejectFact(f.kb(), b);
            long open = openRows(f, f.lease());
            List<Seg> rows = timeline(f, f.lease());
            assertEquals(1L, open);
            assertEquals("A", rows.get(0).value());
            assertEquals(null, rows.get(0).to());
        } finally {
            cleanup(f);
        }
    }

    /** 人写明的终点不随时间线重算：后任走了，它仍然关着。 */
    @Test
    void an_end_a_person_wrote_is_never_recomputed() {
        Fixture f = seed();
        try {
            UUID a = observe(f, new Seen(f, "A", "2020-01-01"));
            temporal.closeSuperseded(a, t("2020-03-01"), "day");
            UUID b = observe(f, new Seen(f, "B", "2020-04-01"));
            writes.rejectFact(f.kb(), b);
            List<Seg> rows = timeline(f, f.lease());
            assertEquals(1, rows.size());
            assertEquals("2020-03-01", rows.get(0).to());
        } finally {
            cleanup(f);
        }
    }

    /** 删掉后任出自的文档，前任重新开着；恢复文档，前任又关上。 */
    @Test
    void deleting_and_restoring_a_document_moves_the_end_it_drew() {
        Fixture f = seed();
        try {
            observe(f, new Seen(f, "A", "2020-01-01"));
            Seen b = new Seen(f, "B", "2020-04-01");
            b.doc = "2020-04-01";
            UUID bId = observe(f, b);
            UUID doc = jdbc.queryForObject(
                    "SELECT document_id FROM fact_evidence WHERE fact_id = ?", UUID.class, bId);
            assertEquals("2020-04-01", aEnd(f));
            documentsService.delete(f.kb(), doc, null);
            String deleted = aEnd(f);
            documentsService.restore(f.kb(), doc);
            String restored = aEnd(f);
            assertEquals(null, deleted);
            assertEquals("2020-04-01", restored);
        } finally {
            cleanup(f);
        }
    }

    private String aEnd(Fixture f) {
        return jdbc.queryForObject("""
                SELECT to_char(valid_to, 'YYYY-MM-DD') FROM facts
                  WHERE kb_id = ? AND object_value #>> '{value}' = 'A' AND invalidated_at IS NULL
                """, String.class, f.kb());
    }

    /** 并行落库：三个、五个值同时到，最后都是一条时间线（tokio::join! → 线程池全收集）。 */
    @Test
    void values_that_arrive_together_leave_one_timeline() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            for (int round = 0; round < 10; round++) {
                final Fixture f = seed();
                observe(f, new Seen(f, "v1", "2020-02-18"));
                observe(f, new Seen(f, "v5", "2020-06-08"));
                List<Callable<Object>> three = List.of(
                        () -> observe(f, new Seen(f, "v4", "2020-05-10")),
                        () -> observe(f, new Seen(f, "v2", "2020-03-17")),
                        () -> observe(f, new Seen(f, "v3", "2020-04-14")));
                for (Future<Object> fu : pool.invokeAll(three)) {
                    fu.get();
                }
                long overlaps = overlaps(f, f.lease());
                long open = openRows(f, f.lease());
                cleanup(f);
                assertEquals(0L, overlaps);
                assertEquals(1L, open);

                final Fixture g = seed();
                List<Callable<Object>> five = List.of(
                        () -> observe(g, new Seen(g, "v4", "2020-05-10")),
                        () -> observe(g, new Seen(g, "v2", "2020-03-17")),
                        () -> observe(g, new Seen(g, "v5", "2020-06-08")),
                        () -> observe(g, new Seen(g, "v1", "2020-02-18")),
                        () -> observe(g, new Seen(g, "v3", "2020-04-14")));
                for (Future<Object> fu : pool.invokeAll(five)) {
                    fu.get();
                }
                int rows = timeline(g, g.lease()).size();
                long overlaps2 = overlaps(g, g.lease());
                long open2 = openRows(g, g.lease());
                cleanup(g);
                assertEquals(5, rows);
                assertEquals(0L, overlaps2);
                assertEquals(1L, open2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ==== 依赖 merge_entities/revert_merge 的六例（M3 任务 4 起走 ResolutionService 真身，
    //      断言逐条对译原文、一字未动） ====

    /** 两条各一百个值的时间线交错着合并：一次重算排好，没有轮数上限。 */
    @Test
    void a_merge_of_two_long_timelines_leaves_one_timeline() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            for (int i = 0; i < 200; i++) {
                Seen x = new Seen(f, "v" + String.format("%03d", i),
                        String.format("%d-%02d-01", 2010 + i / 12, i % 12 + 1));
                x.subject = i % 2 == 0 ? f.lease() : source;
                observe(f, x);
            }
            resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            long n = overlaps(f, f.lease());
            long open = openRows(f, f.lease());
            assertEquals(0L, n);
            assertEquals(1L, open);
        } finally {
            cleanup(f);
        }
    }

    /** 合并两条交错的时间线再撤回：两边都回到合并之前的样子。 */
    @Test
    void a_revert_gives_both_timelines_back_as_they_were() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            for (String[] ve : new String[][]{{"A", "2020-01-01"}, {"C", "2020-05-01"},
                    {"E", "2020-09-01"}}) {
                observe(f, new Seen(f, ve[0], ve[1]));
            }
            for (String[] ve : new String[][]{{"B", "2020-03-01"}, {"D", "2020-07-01"},
                    {"F", "2020-11-01"}}) {
                Seen x = new Seen(f, ve[0], ve[1]);
                x.subject = source;
                observe(f, x);
            }
            List<Seg> targetBefore = timeline(f, f.lease());
            List<Seg> sourceBefore = timeline(f, source);
            var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            List<Seg> merged = timeline(f, f.lease());
            long mergedOverlaps = overlaps(f, f.lease());
            resolution.revertMerge(f.kb(), merge);
            List<Seg> targetAfter = timeline(f, f.lease());
            List<Seg> sourceAfter = timeline(f, source);
            assertEquals(6, merged.size());
            assertEquals(0L, mergedOverlaps);
            assertEquals(targetBefore, targetAfter);
            assertEquals(sourceBefore, sourceAfter);
        } finally {
            cleanup(f);
        }
    }

    /** 合并之后又到了一个值、关上了搬来的那一行：撤回时那一行（连同改写）回源实体。 */
    @Test
    void a_revert_takes_back_a_moved_value_closed_after_the_merge() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            observe(f, new Seen(f, "A", "2019-01-01"));
            Seen d = new Seen(f, "D", "2020-04-01");
            d.subject = source;
            observe(f, d);
            var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            observe(f, new Seen(f, "N", "2020-06-01"));
            resolution.revertMerge(f.kb(), merge);
            List<Seg> target = timeline(f, f.lease());
            List<Seg> back = timeline(f, source);
            assertTrue(target.stream().allMatch(r -> !"D".equals(r.value())), target::toString);
            assertEquals(1, back.size());
            assertEquals("D", back.get(0).value());
            assertEquals(null, back.get(0).to());
            assertEquals("2020-06-01", target.get(0).to(), "A 止于 N");
        } finally {
            cleanup(f);
        }
    }

    /** 合并把 A 关在 D 开始时，人把 A 的起点改成了 2019-12-01：撤回之后人改的那一行还在。 */
    @Test
    void a_revert_keeps_the_interval_a_person_corrected() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            observe(f, new Seen(f, "A", "2020-01-01"));
            Seen d = new Seen(f, "D", "2020-04-01");
            d.subject = source;
            observe(f, d);
            var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            UUID a = liveId(f, f.lease(), "A");
            UUID fixed = temporal.correctInterval(a, new Validity(t("2019-12-01"), "day",
                    t("2020-04-01"), "day", null, null));
            assertTrue(fixed != null, "the row was live");
            temporal.reconcileMovedFacts(f.kb(), List.of(fixed));
            resolution.revertMerge(f.kb(), merge);
            List<Seg> rows = timeline(f, f.lease());
            assertEquals(List.of(new Seg("A", "2019-12-01", "2020-04-01", "day", null,
                    "2019-12-01", "2020-04-01")), rows);
        } finally {
            cleanup(f);
        }
    }

    /** 合并把 A 关上之后，人把 A 驳回了：撤回合并不让它回来。 */
    @Test
    void a_revert_does_not_bring_back_a_value_a_person_rejected() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            observe(f, new Seen(f, "A", "2020-01-01"));
            Seen d = new Seen(f, "D", "2020-04-01");
            d.subject = source;
            observe(f, d);
            var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
            UUID a = liveId(f, f.lease(), "A");
            writes.rejectFact(f.kb(), a);
            resolution.revertMerge(f.kb(), merge);
            List<Seg> rows = timeline(f, f.lease());
            assertTrue(rows.stream().allMatch(r -> !"A".equals(r.value())), rows::toString);
        } finally {
            cleanup(f);
        }
    }

    /** 撤回合并与落库同时进行：谁也不等死谁，最后没有叠在一起的区间。 */
    @Test
    void a_revert_and_arriving_values_do_not_deadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < 40; i++) {
                Fixture f = seed();
                UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
                for (String[] ve : new String[][]{{"A", "2020-01-01"}, {"C", "2020-05-01"},
                        {"E", "2020-09-01"}}) {
                    observe(f, new Seen(f, ve[0], ve[1]));
                }
                for (String[] ve : new String[][]{{"B", "2020-03-01"}, {"D", "2020-07-01"}}) {
                    Seen x = new Seen(f, ve[0], ve[1]);
                    x.subject = source;
                    observe(f, x);
                }
                final var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "test");
                long delayMs = i % 6;
                List<Callable<Object>> jobs = List.of(
                        () -> {
                            try {
                                resolution.revertMerge(f.kb(), merge);
                                return null;
                            } catch (Exception e) {
                                errors.add("revert: " + e);
                                return null;
                            }
                        },
                        () -> {
                            try {
                                Thread.sleep(delayMs);
                                return observe(f, new Seen(f, "N", "2020-02-01"));
                            } catch (Exception e) {
                                errors.add("arrive: " + e);
                                return null;
                            }
                        },
                        () -> {
                            try {
                                return observe(f, new Seen(f, "M", "2020-08-01"));
                            } catch (Exception e) {
                                errors.add("arrive: " + e);
                                return null;
                            }
                        });
                for (Future<Object> fu : pool.invokeAll(jobs)) {
                    fu.get();
                }
                long target = overlaps(f, f.lease());
                long back = overlaps(f, source);
                cleanup(f);
                assertEquals(0L, target, "trial " + i);
                assertEquals(0L, back, "trial " + i);
            }
            assertTrue(errors.isEmpty(), errors::toString);
        } finally {
            pool.shutdownNow();
        }
    }
}
