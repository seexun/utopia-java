package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_timeline_holds_whatever_the_order.rs（517 行）——单值状态的
 * 时间线不论值怎么到都保持完整（#679 评审复现：最新先到、重抽、无日期上传、有日期
 * 无起点的值、合并后到达与撤回、晚到的两个值同时入账）。
 * 等价点（tokio→JUnit）：tokio::join! 并发到达改固定线程池全收集；无库跳过→SharedDbGuard。
 * 依赖 merge_entities/revert_merge 的 3 例（M3 任务 4 起走 ResolutionService 真身）。
 */
class ATimelineHoldsWhateverTheOrderTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;

    record Fixture(UUID kb, UUID etype, UUID lease, UUID deadline) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID deadline = Uuids.nowV7();
        UUID lease = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'rv')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'rv')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'rv')",
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

    /**
     * 模拟抽取（Rust arrive_on）：落库，只在新建时对账。
     * {@code doc}：null=无文档（Rust Option&lt;(…)&gt;::None）；"none"=无日期文档
     * （0045 决定 3）；其余=文档日期（ISO 串，doc_time_source='content'）。
     * 无日期文档的行随后清掉 attested_from（模拟文档抽取的无时间行；人工写入口的
     * None 缺省是此刻）。
     */
    private UUID arriveOn(Fixture f, UUID subject, String value, String from,
                          String doc, boolean reconcile) {
        Validity validity = from != null ? Validity.starting(t(from), "day") : Validity.empty();
        UUID chunk = null;
        boolean undatedDoc = false;
        if (doc != null) {
            UUID d = Uuids.nowV7();
            UUID c = Uuids.nowV7();
            boolean dated = !"none".equals(doc);
            jdbc.update("""
                    INSERT INTO documents (id, kb_id, filename, sha256, doc_time, doc_time_source)
                     VALUES (?, ?, ?, ?, ?, ?)
                    """, d, f.kb(), "doc-" + d + ".html", "doc-" + d + ".html",
                    dated ? t(doc) : null, dated ? "content" : "none");
            jdbc.update(
                    "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                    c, f.kb(), d, value);
            validity = validity.attested(dated ? t(doc) : null);
            chunk = c;
            undatedDoc = !dated;
        }
        JsonNode object = JSON.createObjectNode().put("value", value);
        var write = writes.insertValueFact(f.kb(), subject, f.deadline(), object, validity, 0.9f);
        UUID id = write.id();
        if (undatedDoc) {
            jdbc.update("UPDATE facts SET attested_from = NULL WHERE id = ?", id);
        }
        if (chunk != null) {
            writes.addEvidence(id, chunk, value, null);
        }
        if (write.created() && reconcile) {
            temporal.reconcileNewFact(f.kb(), id, subject, f.deadline(), null, Side.SUBJECT_SIDE);
        }
        return id;
    }

    private UUID arrive(Fixture f, String value, String from) {
        return arriveOn(f, f.lease(), value, from, null, true);
    }

    record Seg(String value, String from, String to) {
    }

    private static Seg s(String v, String from, String to) {
        return new Seg(v, from, to);
    }

    private List<Seg> timelineOf(Fixture f, UUID subject) {
        return jdbc.query("""
                        SELECT object_value #>> '{value}', to_char(valid_from, 'YYYY-MM-DD'),
                                to_char(valid_to, 'YYYY-MM-DD')
                         FROM facts
                         WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                         ORDER BY valid_from NULLS FIRST, valid_to NULLS LAST
                        """,
                (rs, i) -> new Seg(rs.getString(1), rs.getString(2), rs.getString(3)),
                f.kb(), subject, f.deadline());
    }

    /** 活着的、有起点、区间相交且值不同的行对数。 */
    private long overlaps(Fixture f, UUID subject) {
        return jdbc.queryForObject("""
                        SELECT count(*) FROM facts a JOIN facts b
                           ON a.kb_id = b.kb_id AND a.subject_id = b.subject_id AND a.predicate_id = b.predicate_id
                          AND a.id < b.id AND a.object_value IS DISTINCT FROM b.object_value
                         WHERE a.kb_id = ? AND a.subject_id = ? AND a.predicate_id = ?
                           AND a.invalidated_at IS NULL AND b.invalidated_at IS NULL
                           AND a.valid_from IS NOT NULL AND b.valid_from IS NOT NULL
                           AND a.valid_from < COALESCE(b.valid_to, 'infinity')
                           AND b.valid_from < COALESCE(a.valid_to, 'infinity')
                        """, Long.class, f.kb(), subject, f.deadline());
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
    }

    /** 最新先到：9th、7th、5th、6th——谁先到都不叠。 */
    @Test
    void newest_first_arrival_leaves_no_overlap() {
        Fixture f = seed();
        try {
            arrive(f, "2020-06-23", "2020-06-08");
            arrive(f, "2020-05-26", "2020-04-14");
            arrive(f, "2020-03-17", "2020-02-18");
            arrive(f, "2020-04-14", "2020-03-17");
            List<Seg> rows = timelineOf(f, f.lease());
            long n = overlaps(f, f.lease());
            assertEquals(0L, n, rows::toString);
        } finally {
            cleanup(f);
        }
    }

    /** 同一批值按相反顺序再抽一遍：什么也不变。 */
    @Test
    void extracting_the_same_values_again_changes_nothing() {
        Fixture f = seed();
        try {
            String[][] chain = {
                    {"2020-03-17", "2020-02-18"},
                    {"2020-06-23", "2020-06-08"},
                    {"2020-05-26", "2020-04-14"},
                    {"2020-04-14", "2020-03-17"},
            };
            for (String[] pair : chain) {
                arrive(f, pair[0], pair[1]);
            }
            List<Seg> before = timelineOf(f, f.lease());
            for (int i = chain.length - 1; i >= 0; i--) {
                arrive(f, chain[i][0], chain[i][1]);
            }
            List<Seg> after = timelineOf(f, f.lease());
            assertEquals(before, after);
        } finally {
            cleanup(f);
        }
    }

    /** 无日期上传：文档写手存 doc_time NULL、doc_time_source='none'（0045 决定 3）。 */
    @Test
    void an_undated_upload_is_not_a_document_date() {
        Fixture f = seed();
        try {
            UUID bare = arriveOn(f, f.lease(), "BBHQ1", null, "none", true);
            arriveOn(f, f.lease(), "HPBB1", "2021-01-01", "2021-01-01", true);
            Boolean open = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM facts WHERE id = ? AND invalidated_at IS NULL"
                            + " AND valid_to IS NULL)", Boolean.class, bare);
            Long conflicts = jdbc.queryForObject(
                    "SELECT count(*) FROM fact_conflicts WHERE old_fact_id = ?", Long.class, bare);
            List<Seg> rows = timelineOf(f, f.lease());
            // 上传时刻不把 BBHQ1 放在 2026 年：HPBB1（2021）既不接替它、它也不接替 HPBB1。
            // 自己没有时间的值进人审成 no_time 对，两边都还开着（第三轮评审第 11 条）。
            assertTrue(open && conflicts == 1,
                    "an undated row is neither ordered by its upload time nor closed; conflicts="
                            + conflicts + ", rows=" + rows);
        } finally {
            cleanup(f);
        }
    }

    /**
     * 有日期无起点的 X（2020-08-13）与 Y（2016）no_time 成对后都开着；Z（2021）到了：
     * X 在 2020-08-13 成立 &lt; 2021，Z 是它的后任。
     */
    @Test
    void a_dated_value_with_no_start_ends_at_the_next_start_after_its_date() {
        Fixture f = seed();
        try {
            UUID x = arriveOn(f, f.lease(), "BBHQ1", null, "2020-08-13", true);
            arriveOn(f, f.lease(), "HPBB1", "2016-05-16", "2016-05-16", true);
            arriveOn(f, f.lease(), "NEWCO", "2021-01-01", "2021-01-01", true);
            List<Seg> rows = timelineOf(f, f.lease());
            Boolean open = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM facts WHERE id = ? AND invalidated_at IS NULL"
                            + " AND valid_to IS NULL)", Boolean.class, x);
            assertEquals(false, open, rows::toString);
        } finally {
            cleanup(f);
        }
    }

    /** 落在同一个已关区间里的两个晚到的值，同时入账（tokio::join! → 线程池）。 */
    @Test
    void two_late_values_reconciled_at_once_leave_one_timeline() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int trials = 40;
            int bad = 0;
            for (int i = 0; i < trials; i++) {
                Fixture f = seed();
                arrive(f, "2020-03-17", "2020-02-18");
                arrive(f, "2020-06-23", "2020-06-08");
                List<Callable<Object>> pair = List.of(
                        () -> arrive(f, "2020-05-26", "2020-04-14"),
                        () -> arrive(f, "2020-04-14", "2020-03-17"));
                for (Future<Object> fu : pool.invokeAll(pair)) {
                    fu.get();
                }
                long n = overlaps(f, f.lease());
                List<Seg> rows = timelineOf(f, f.lease());
                cleanup(f);
                if (n > 0 || rows.size() != 4) {
                    bad++;
                }
            }
            assertEquals(0, bad, "RV concurrent: trials ended with overlaps or extra rows");
        } finally {
            pool.shutdownNow();
        }
    }

    // ==== 依赖 merge_entities/revert_merge 的三例（M3 任务 4 起走 ResolutionService 真身，
    //      断言逐条对译原文、一字未动） ====

    @Autowired
    tech.seexun.jknow.store.ResolutionService resolution;

    /**
     * 合并搬来的值落在目标的历史里；合并后到的值落在合并画出的界上；撤回后界没了——
     * 撤回把合并搬来的 D 送回源实体，N 止于 B 的开始，A 止于 N 的开始。
     */
    @Test
    void a_revert_releases_a_value_that_arrived_after_the_merge() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            arrive(f, "A", "2020-01-01");
            arrive(f, "B", "2020-06-01");
            arriveOn(f, source, "D", "2020-04-01", null, true);
            var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "rv");
            List<Seg> afterMerge = timelineOf(f, f.lease());
            arrive(f, "N", "2020-02-01");
            List<Seg> afterArrival = timelineOf(f, f.lease());
            resolution.revertMerge(f.kb(), merge);
            List<Seg> afterRevert = timelineOf(f, f.lease());
            List<Seg> sourceRows = timelineOf(f, source);
            assertEquals(List.of(s("A", "2020-01-01", "2020-02-01"),
                            s("N", "2020-02-01", "2020-06-01"),
                            s("B", "2020-06-01", null)),
                    afterRevert,
                    "N no longer ends at D's start once D goes back; merge=" + afterMerge
                            + " arrival=" + afterArrival);
            assertEquals(List.of(s("D", "2020-04-01", null)), sourceRows);
        } finally {
            cleanup(f);
        }
    }

    /**
     * 源上两个开放值（声明唯一性之前写的）：合并重切自己画过的修正，撤回复原两条时间线。
     * 回到源上的是改写出的新行，不是把旧行复活——撤回也是一次认知变更，记录轴看得见
     * （0057）；源实体上的时间线顺带按两行重算。
     */
    @Test
    void a_revert_restores_both_timelines_a_merge_rewrote() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            arrive(f, "A", "2020-01-01");
            arrive(f, "B", "2020-06-01");
            arriveOn(f, source, "D", "2020-03-01", null, false);
            arriveOn(f, source, "E", "2020-04-01", null, false);
            List<Seg> beforeTarget = timelineOf(f, f.lease());
            var merge = resolution.mergeEntities(f.kb(), source, f.lease(), null, "rv");
            List<Seg> afterMerge = timelineOf(f, f.lease());
            resolution.revertMerge(f.kb(), merge);
            List<Seg> afterRevert = timelineOf(f, f.lease());
            List<Seg> sourceRows = timelineOf(f, source);
            assertEquals(beforeTarget, afterRevert,
                    "target history not restored; after merge it was " + afterMerge);
            // 两个值都回到源实体上。回来的是改写出的新行，不是把旧行复活
            assertEquals(List.of(s("D", "2020-03-01", "2020-04-01"),
                            s("E", "2020-04-01", null)),
                    sourceRows, "source lost a value");
        } finally {
            cleanup(f);
        }
    }

    /**
     * 只搬已关行的合并照样重排目标时间线：源的 D [03-01,05-01) 与 E [05-01,07-01)
     * 落在目标的开放 A（自 01-01）旁边，A 必须止于 D 开始。
     */
    @Test
    void a_merge_of_closed_rows_still_ends_the_open_one_they_follow() {
        Fixture f = seed();
        try {
            UUID source = entity(f.kb(), f.etype(), "Lease Agreement", Uuids.nowV7());
            arrive(f, "A", "2020-01-01");
            arriveOn(f, source, "D", "2020-03-01", null, true);
            arriveOn(f, source, "E", "2020-05-01", null, true);
            // E's end is one the text states（Rust 字面量在 UTC 会话下=UTC 零点；本会话
            // 可能在 +08，显式 Z 后缀才过 facts_to_precision_matches_date 的 UTC 截断 CHECK）
            jdbc.update("""
                    UPDATE facts SET valid_to = '2020-07-01T00:00:00Z'::timestamptz, valid_to_precision = 'day'
                     WHERE subject_id = ? AND object_value #>> '{value}' = 'E' AND invalidated_at IS NULL
                    """, source);
            resolution.mergeEntities(f.kb(), source, f.lease(), null, "closed rows");
            List<Seg> rows = timelineOf(f, f.lease());
            long n = overlaps(f, f.lease());
            assertEquals(0L, n, rows::toString);
            assertTrue(rows.contains(s("A", "2020-01-01", "2020-03-01")), rows::toString);
        } finally {
            cleanup(f);
        }
    }
}
