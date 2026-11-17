package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_late_value_takes_its_place_in_history.rs（451 行）——单值属性的
 * 新值乱序到达，年表仍然一段接一段（Blackbaud 租约补充协议的实测回归）。乱序到达最后
 * 排成正确的年表；原文自己写明的终点不被切。
 *
 * <p>等价点：insert_value_fact 走 GraphWriteService（graph.rs 逐字门）、reconcile_new_fact
 * 走 TemporalService（temporal.rs 引擎真身，任务 2）。
 */
class ALateValueTakesItsPlaceInHistoryTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;

    record Fixture(UUID kb, UUID lease, UUID deadline) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID deadline = Uuids.nowV7();
        UUID lease = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'late-value-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'late-value-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'late-value-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'lease', 'Lease')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, temporal, functional)
                 VALUES (?, ?, 'option_deadline', 'option deadline', 'attribute', 'date', 'state', TRUE)
                """, deadline, kb);
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'HQ Lease')",
                lease, kb, etype);
        return new Fixture(kb, lease, deadline);
    }

    private static OffsetDateTime t(String day) {
        return OffsetDateTime.parse(day + "T00:00:00Z");
    }

    /** 落一条值，然后照抽取的做法对账。 */
    private UUID arrive(Fixture f, String value, String from, String to) {
        Validity validity = Validity.starting(t(from), "day");
        if (to != null) {
            validity = new Validity(validity.from, validity.fromPrecision, t(to), "day",
                    validity.attestedAt, validity.fromGrade);
        }
        ObjectNode object = JSON.createObjectNode().put("value", value);
        var written = writes.insertValueFact(f.kb(), f.lease(), f.deadline(), object, validity,
                0.9f);
        temporal.reconcileNewFact(f.kb(), written.id(), f.lease(), f.deadline(), null,
                Side.SUBJECT_SIDE);
        return written.id();
    }

    /** 从一份带日期的文件里读到的值：起点可以没有，证据文件的日期总是有的。 */
    private UUID arriveFromDocument(Fixture f, ObjectNode object, String from,
                                    String documentDate) {
        String value = object.path("value").asText("");
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO documents (id, kb_id, filename, sha256, doc_time, doc_time_source)
                 VALUES (?, ?, ?, ?, ?, 'content')
                """, doc, f.kb(), "amendment-" + documentDate + "-" + value + ".html",
                "amendment-" + documentDate + "-" + value, t(documentDate));
        jdbc.update(
                "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                chunk, f.kb(), doc, value);
        Validity validity = (from == null ? Validity.empty()
                : Validity.starting(t(from), "day")).attested(t(documentDate));
        var written = writes.insertValueFact(f.kb(), f.lease(), f.deadline(), object, validity,
                0.9f);
        writes.addEvidence(written.id(), chunk, value, null);
        temporal.reconcileNewFact(f.kb(), written.id(), f.lease(), f.deadline(), null,
                Side.SUBJECT_SIDE);
        return written.id();
    }

    private UUID arriveValueFromDocument(Fixture f, String value, String from,
                                         String documentDate) {
        return arriveFromDocument(f, JSON.createObjectNode().put("value", value), from,
                documentDate);
    }

    /** 现存的各段：(值, 起点, 终点)，按起点排。 */
    private List<Object[]> timeline(Fixture f, String orderBy) {
        return jdbc.query("""
                SELECT object_value #>> '{value}', to_char(valid_from, 'YYYY-MM-DD'),
                        to_char(valid_to, 'YYYY-MM-DD')
                 FROM facts
                 WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                 ORDER BY %s
                """.formatted(orderBy), (rs, i) -> new Object[]{rs.getString(1), rs.getString(2),
                rs.getString(3)}, f.kb(), f.lease(), f.deadline());
    }

    private static Object[] seg(String value, String from, String to) {
        return new Object[]{value, from, to};
    }

    private static void assertSegs(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), () -> actual.toString());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i)[0], actual.get(i)[0]);
            assertEquals(expected.get(i)[1], actual.get(i)[1]);
            assertEquals(expected.get(i)[2], actual.get(i)[2]);
        }
    }

    @Test
    void amendments_that_arrive_out_of_order_still_hand_over_one_to_the_next() {
        Fixture f = seed();
        try {
            // 第五、第九、第七、第六份，按它们真实的落库顺序
            arrive(f, "2020-03-17", "2020-02-18", null);
            arrive(f, "2020-06-23", "2020-06-08", null);
            arrive(f, "2020-05-26", "2020-04-14", null);
            arrive(f, "2020-04-14", "2020-03-17", null);

            assertSegs(List.of(
                    seg("2020-03-17", "2020-02-18", "2020-03-17"),
                    seg("2020-04-14", "2020-03-17", "2020-04-14"),
                    seg("2020-05-26", "2020-04-14", "2020-06-08"),
                    seg("2020-06-23", "2020-06-08", null)),
                    timeline(f, "valid_from"));
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /**
     * 原文写明「到 12 月 31 日为止」的一段，不是接替留下的边界：年中来的新值不切它。
     * 那种重叠是两份原文说法相左，不该由引擎替人挑一个。
     */
    @Test
    void an_end_the_text_states_is_not_cut_by_a_later_value() {
        Fixture f = seed();
        try {
            arrive(f, "2020-09-30", "2020-01-01", "2020-12-31");
            arrive(f, "2020-10-31", "2020-06-01", null);

            List<Object[]> rows = timeline(f, "valid_from");
            boolean has = rows.stream().anyMatch(r -> "2020-09-30".equals(r[0])
                    && "2020-01-01".equals(r[1]) && "2020-12-31".equals(r[2]));
            assertTrue(has, "原文写的终点原样留着：" + rows);
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /**
     * 第五份补充协议没抽出起点，它先进了人审；第八份来时，从前把它和 3 月 17 日起的
     * 第六份一起关在 5 月 26 日，两段叠了两个多月。它的后任是它之后最近的已知起点。
     */
    @Test
    void a_value_with_no_start_ends_where_the_next_known_value_begins() {
        Fixture f = seed();
        try {
            arriveValueFromDocument(f, "2020-04-14", "2020-03-17", "2020-03-17");
            arriveValueFromDocument(f, "2020-03-17", null, "2020-02-18");
            arriveValueFromDocument(f, "2020-06-09", "2020-05-26", "2020-05-26");

            assertSegs(List.of(
                    seg("2020-03-17", null, "2020-03-17"),
                    seg("2020-04-14", "2020-03-17", "2020-05-26"),
                    seg("2020-06-09", "2020-05-26", null)),
                    timeline(f, "valid_to NULLS LAST"));
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /**
     * 第十一份补充协议（8 月 13 日）说房东是 BBHQ1，没说从哪天起；另一份说 HPBB1 从
     * 2016 年起是房东。BBHQ1 在 8 月 13 日成立，所以它排在 HPBB1 之后：HPBB1 写成
     * 「结束了，不知哪天」，锚在 8 月 13 日（#681 §1，0022 的形状）。首尾相接不重叠。
     */
    @Test
    void a_dated_value_with_no_start_takes_over_from_its_date() {
        Fixture f = seed();
        try {
            UUID later = arriveValueFromDocument(f, "BBHQ1", null, "2020-08-13");
            arriveValueFromDocument(f, "HPBB1", "2016-05-16", "2016-05-16");

            Boolean stillOpen = jdbc.queryForObject("""
                    SELECT valid_to IS NULL AND valid_to_precision IS NULL AND invalidated_at IS NULL
                     FROM facts WHERE id = ?
                    """, Boolean.class, later);
            assertTrue(stillOpen, "BBHQ1 没被关在 2016 年");
            var earlier = jdbc.queryForMap("""
                    SELECT to_char(valid_from, 'YYYY-MM-DD') AS valid_from, to_char(valid_to, 'YYYY-MM-DD') AS valid_to,
                            valid_to_precision, to_char(attested_to, 'YYYY-MM-DD') AS attested_to
                     FROM facts
                     WHERE kb_id = ? AND object_value #>> '{value}' = 'HPBB1'
                       AND invalidated_at IS NULL
                    """, f.kb());
            assertEquals("2016-05-16", earlier.get("valid_from"));
            assertEquals(null, earlier.get("valid_to"));
            assertEquals("unknown", earlier.get("valid_to_precision"));
            assertEquals("2020-08-13", earlier.get("attested_to"),
                    "HPBB1 结束了、不知哪天，锚在 BBHQ1 那份文件的日期上");
            Long conflicts = jdbc.queryForObject(
                    "SELECT count(*) FROM fact_conflicts WHERE kb_id = ?", Long.class, f.kb());
            assertEquals(0L, conflicts, "排得出先后，就不用人看");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /**
     * 第十份补充协议（6 月 26 日）把截止日改成「触发日后 45 天」：没有日历日期，抽取
     * 标成 relative 照原文存下（#681 §4）。它仍是这份租约新的截止日，所以 6 月 23 日
     * 那个到它为止——它没有起点，前一个就写成「结束了，不知哪天」，锚在 6 月 26 日
     * 这份文件上（§1）。
     */
    @Test
    void a_deadline_stated_relative_to_an_event_still_ends_the_dated_one_before_it() {
        Fixture f = seed();
        try {
            arriveValueFromDocument(f, "2020-06-23", "2020-06-08", "2020-06-08");
            var relativeObject = JSON.createObjectNode().put("value",
                    "45 days after the Trigger Date").put("relative", true);
            UUID relative = arriveFromDocument(f, relativeObject, null, "2020-06-26");

            Boolean open = jdbc.queryForObject("""
                    SELECT valid_to IS NULL AND valid_to_precision IS NULL AND invalidated_at IS NULL
                     FROM facts WHERE id = ?
                    """, Boolean.class, relative);
            assertTrue(open, "相对的截止日是现在的截止日");
            var before = jdbc.queryForMap("""
                    SELECT to_char(valid_to, 'YYYY-MM-DD') AS valid_to, valid_to_precision,
                            to_char(attested_to, 'YYYY-MM-DD') AS attested_to
                     FROM facts
                     WHERE kb_id = ? AND object_value #>> '{value}' = '2020-06-23'
                       AND invalidated_at IS NULL
                    """, f.kb());
            assertEquals(null, before.get("valid_to"));
            assertEquals("unknown", before.get("valid_to_precision"));
            assertEquals("2020-06-26", before.get("attested_to"),
                    "6 月 23 日那个结束了、不知哪天，锚在第十份补充协议的日期");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }
}
