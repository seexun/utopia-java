package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/an_event_holds_at_the_moment_it_names.rs（700 行，7 例）——事件在
 * 它命名的那一刻成立，恒常每一刻都成立（0031 / #486），打在真库上。
 *
 * <p>为什么非要连库：写入侧的归一（Validity.under）和读出侧的谓词（world_axis）各在一处，
 * 两处必须说同一句话；而读出侧整个活在 SQL 字符串里——一个写反的 CASE、一个拼错的
 * interval 字面量，编译器都看不见。
 *
 * <p>entity_detail 读侧走 DetailStubs（任务 6 换真端点；as_of=None 单支、at 走
 * world_axis facts_hold_at，$4 为 NULL 即「任何时刻」——与 Rust 同一展开）。
 *
 * <p>第 7 例（aDerivationReads…）断言面在 reasoning::materialize（派生桶/恒常开放），
 * 派生引擎随任务 6 落地——测试体与断言全量移植，seam 抛出 @Disabled 待任务 6。
 */
class AnEventHoldsAtTheMomentItNamesTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GraphWriteService graph;

    @Autowired
    TemporalService temporal;

    @Autowired
    tech.seexun.jknow.store.ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID nova, UUID orion, UUID vega, UUID paris, UUID france,
                   UUID leads, UUID acquired, UUID mergedWith, UUID capitalOf, UUID borders) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID city = Uuids.nowV7();
        UUID country = Uuids.nowV7();
        UUID nova = Uuids.nowV7();
        UUID orion = Uuids.nowV7();
        UUID vega = Uuids.nowV7();
        UUID paris = Uuids.nowV7();
        UUID france = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        UUID acquired = Uuids.nowV7();
        UUID mergedWith = Uuids.nowV7();
        UUID capitalOf = Uuids.nowV7();
        UUID borders = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'event-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'event-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'event-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{organization, "organization", "Organization"},
                {city, "city", "City"}, {country, "country", "Country"}}) {
            jdbc.update(
                    "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        for (Object[] row : new Object[][]{{leads, "leads", "state", false},
                {acquired, "acquired", "event", false},
                {mergedWith, "merged_with", "event", true},
                {capitalOf, "capital_of", "eternal", false},
                {borders, "borders", "eternal", true}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, is_symmetric)
                     VALUES (?, ?, ?, ?, ?, ?)
                    """, row[0], kb, row[1], row[1], row[2], row[3]);
        }
        for (Object[] row : new Object[][]{{nova, organization, "Nova Systems"},
                {orion, organization, "Orion Labs"}, {vega, organization, "Vega Analytics"},
                {paris, city, "Paris"}, {france, country, "France"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        return new Fixture(org, kb, nova, orion, vega, paris, france, leads, acquired, mergedWith,
                capitalOf, borders);
    }

    private void teardown(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    private GraphWriteService.FactWrite fact(Fixture f, UUID subject, UUID predicate,
                                             UUID object, Validity validity) {
        return graph.insertFact(f.kb(), subject, predicate, object, validity, 0.9f);
    }

    /** 一行事实的两端与精度：(from, fromPrecision, to, toPrecision)。 */
    record Span(OffsetDateTime from, String fromPrecision, OffsetDateTime to,
                String toPrecision) {
    }

    private Span span(UUID id) {
        return jdbc.queryForObject(
                "SELECT valid_from, valid_from_precision, valid_to, valid_to_precision"
                        + " FROM facts WHERE id = ?",
                (rs, i) -> new Span(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getString(4)), id);
    }

    /** T 时刻某实体面板上的事实（按 id）。at=null = 每一刻。 */
    private Set<UUID> factsAt(Fixture f, UUID entity, OffsetDateTime at) {
        return DetailStubs.entityDetail(jdbc, f.kb(), entity, at, null).stream()
                .map(DetailStubs.DetailFact::id).collect(Collectors.toSet());
    }

    private boolean holds(Fixture f, UUID entity, UUID id, OffsetDateTime at) {
        return factsAt(f, entity, at).contains(id);
    }

    @Test
    void anEventIsOneMomentAndHoldsThroughTheBucketItNames() {
        Fixture f = seed();
        try {
            // 2024-04-01 的文档说：Nova 于 2024-03-15 收购 Orion
            UUID day = fact(f, f.nova(), f.acquired(), f.orion(),
                    Validity.starting(OffsetDateTime.parse("2024-03-15T00:00:00Z"), "day")
                            .attested(OffsetDateTime.parse("2024-04-01T00:00:00Z"))).id();
            Span s = span(day);
            assertEquals(OffsetDateTime.parse("2024-03-15T00:00:00Z"), s.from());
            assertEquals("day", s.fromPrecision());
            assertEquals(OffsetDateTime.parse("2024-03-15T00:00:00Z"), s.to());
            assertEquals("day", s.toPrecision(), "事件写进库是两端同一个值、同一个精度");
            // 那一天里的每一刻成立；前一天、后一天都不；证据日期（4 月）更不
            assertFalse(holds(f, f.nova(), day, OffsetDateTime.parse("2024-03-14T23:59:59Z")));
            assertTrue(holds(f, f.nova(), day, OffsetDateTime.parse("2024-03-15T00:00:00Z")));
            assertTrue(holds(f, f.nova(), day, OffsetDateTime.parse("2024-03-15T23:59:59Z")));
            assertFalse(holds(f, f.nova(), day, OffsetDateTime.parse("2024-03-16T00:00:00Z")));
            assertFalse(holds(f, f.nova(), day, OffsetDateTime.parse("2024-04-01T00:00:00Z")),
                    "证据日期不是事件的日期，状态那套「从证据起」不适用");
            assertTrue(factsAt(f, f.nova(), null).contains(day), "不限时刻时列出");

            // 读出来的区间跟着桶走：从那一天起，到下一天为止
            var read = DetailStubs.entityDetail(jdbc, f.kb(), f.nova(), null, null).stream()
                    .filter(x -> x.id().equals(day)).findFirst().orElseThrow();
            assertEquals(OffsetDateTime.parse("2024-03-15T00:00:00Z"), read.holdsFrom());
            assertEquals(OffsetDateTime.parse("2024-03-16T00:00:00Z"), read.holdsTo());
            assertEquals("event", read.temporal());

            // 月精度的桶是整个月
            UUID month = fact(f, f.nova(), f.acquired(), f.vega(),
                    Validity.starting(OffsetDateTime.parse("2024-06-01T00:00:00Z"), "month")
                            .attested(OffsetDateTime.parse("2024-04-01T00:00:00Z"))).id();
            assertTrue(holds(f, f.nova(), month, OffsetDateTime.parse("2024-06-30T12:00:00Z")));
            assertFalse(holds(f, f.nova(), month, OffsetDateTime.parse("2024-07-01T00:00:00Z")));

            // 状态照旧：领导从 3 月起、开放——4 月也成立
            UUID lead = fact(f, f.nova(), f.leads(), f.orion(),
                    Validity.starting(OffsetDateTime.parse("2024-03-15T00:00:00Z"), "day")).id();
            assertTrue(holds(f, f.nova(), lead, OffsetDateTime.parse("2024-04-01T00:00:00Z")));
        } finally {
            teardown(f);
        }
    }

    @Test
    void aSpanAnEndingOrAnUnknownEndGivenToAnEventCollapses() {
        Fixture f = seed();
        try {
            // 模型给了一段：取起点
            UUID ranged = fact(f, f.nova(), f.acquired(), f.orion(),
                    new Validity(OffsetDateTime.parse("2024-03-15T00:00:00Z"), "day",
                            OffsetDateTime.parse("2025-01-01T00:00:00Z"), "day", null, null)).id();
            Span rs = span(ranged);
            assertEquals(OffsetDateTime.parse("2024-03-15T00:00:00Z"), rs.from());
            assertEquals(OffsetDateTime.parse("2024-03-15T00:00:00Z"), rs.to());

            // 只给了终点：那就是它发生的时候
            UUID ended = fact(f, f.nova(), f.acquired(), f.vega(),
                    new Validity(null, null, OffsetDateTime.parse("2024-05-01T00:00:00Z"),
                            "month", null, null)).id();
            Span es = span(ended);
            assertEquals(OffsetDateTime.parse("2024-05-01T00:00:00Z"), es.from());
            assertEquals("month", es.fromPrecision());
            assertEquals(OffsetDateTime.parse("2024-05-01T00:00:00Z"), es.to());
            assertEquals("month", es.toPrecision());

            // 「结束了不知哪天」对一刻没有意义：落下的是没日期的事件，不是待关的行
            UUID unknown = fact(f, f.orion(), f.acquired(), f.vega(),
                    Validity.empty().endedWhenUnknown()
                            .attested(OffsetDateTime.parse("2024-04-01T00:00:00Z"))).id();
            Span us = span(unknown);
            assertNull(us.from());
            assertNull(us.fromPrecision());
            assertNull(us.to());
            assertNull(us.toPrecision());
            OffsetDateTime attestedTo = jdbc.queryForObject(
                    "SELECT attested_to FROM facts WHERE id = ?", OffsetDateTime.class, unknown);
            assertNull(attestedTo, "没有「结束」这回事，也就没有结束的锚点");
        } finally {
            teardown(f);
        }
    }

    @Test
    void anUndatedEventHoldsAtNoMomentAndTakesADateWhenOneArrives() {
        Fixture f = seed();
        try {
            // 2024-04-01 的文档说 Nova 收购了 Orion，没说哪天
            var bare = fact(f, f.nova(), f.acquired(), f.orion(),
                    Validity.empty().attested(OffsetDateTime.parse("2024-04-01T00:00:00Z")));
            assertTrue(bare.created());
            assertTrue(factsAt(f, f.nova(), null).contains(bare.id()), "实体上列着");
            for (String at : new String[]{"2024-04-01T00:00:00Z", "2024-04-02T00:00:00Z",
                    "2030-01-01T00:00:00Z"}) {
                assertFalse(holds(f, f.nova(), bare.id(), OffsetDateTime.parse(at)),
                        "不知何时发生的事件，" + at + " 不算成立（状态会从证据起算，事件不）");
            }

            // 后来的文档给了日期：精化——裸行作废，新行两端是那一刻
            var dated = fact(f, f.nova(), f.acquired(), f.orion(),
                    Validity.starting(OffsetDateTime.parse("2024-03-15T00:00:00Z"), "day")
                            .attested(OffsetDateTime.parse("2024-05-01T00:00:00Z")));
            assertTrue(dated.created());
            assertNotEquals(bare.id(), dated.id());
            var row = jdbc.queryForMap(
                    "SELECT supersedes, (SELECT invalidated_at IS NOT NULL FROM facts WHERE id = ?)"
                            + " AS old_dead FROM facts WHERE id = ?",
                    bare.id(), dated.id());
            assertEquals(bare.id(), row.get("supersedes"));
            assertEquals(Boolean.TRUE, row.get("old_dead"));
            assertTrue(holds(f, f.nova(), dated.id(),
                    OffsetDateTime.parse("2024-03-15T12:00:00Z")));

            // 再听到一句没日期的「收购了」：并进已有的那一刻，不是第二次收购
            var again = fact(f, f.nova(), f.acquired(), f.orion(),
                    Validity.empty().attested(OffsetDateTime.parse("2024-06-01T00:00:00Z")));
            assertFalse(again.created());
            assertEquals(dated.id(), again.id());
        } finally {
            teardown(f);
        }
    }

    @Test
    void anEternalRelationDropsItsDatesAndHoldsAtEveryMoment() {
        Fixture f = seed();
        try {
            // 原文：「1990 年起巴黎是法国首都」——日期说的是别的事；文档是 2024 年的
            UUID cap = fact(f, f.paris(), f.capitalOf(), f.france(),
                    Validity.starting(OffsetDateTime.parse("1990-01-01T00:00:00Z"), "year")
                            .attested(OffsetDateTime.parse("2024-04-01T00:00:00Z"))).id();
            Span cs = span(cap);
            assertNull(cs.from());
            assertNull(cs.to(), "恒常不存日期");
            for (String at : new String[]{"1800-01-01T00:00:00Z", "2023-12-31T00:00:00Z",
                    "2100-01-01T00:00:00Z"}) {
                assertTrue(holds(f, f.paris(), cap, OffsetDateTime.parse(at)),
                        at + " 成立——证据日期不闸恒常");
            }
            var read = DetailStubs.entityDetail(jdbc, f.kb(), f.paris(), null, null).stream()
                    .filter(x -> x.id().equals(cap)).findFirst().orElseThrow();
            assertNull(read.holdsFrom());
            assertNull(read.holdsTo(), "两端开放");

            // 再说一遍并进同一行
            var again = fact(f, f.paris(), f.capitalOf(), f.france(),
                    Validity.starting(OffsetDateTime.parse("2000-01-01T00:00:00Z"), "year"));
            assertFalse(again.created());
            assertEquals(cap, again.id());
        } finally {
            teardown(f);
        }
    }

    @Test
    void aRowWrittenBeforeTheRuleReadsAsItsStartBucket() {
        Fixture f = seed();
        try {
            // 0031 之前的形状：事件谓词、起点有、终点空——当年被读成「从那天起一直如此」
            UUID legacy = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                        valid_from, valid_from_precision, confidence, attested_from)
                     VALUES (?, ?, ?, ?, ?, '2024-03-15T00:00:00Z', 'day', 0.9, '2024-04-01T00:00:00Z')
                    """, legacy, f.kb(), f.nova(), f.acquired(), f.orion());
            assertTrue(holds(f, f.nova(), legacy,
                    OffsetDateTime.parse("2024-03-15T12:00:00Z")));
            assertFalse(holds(f, f.nova(), legacy,
                    OffsetDateTime.parse("2024-03-16T00:00:00Z")));
            assertFalse(holds(f, f.nova(), legacy,
                    OffsetDateTime.parse("2025-01-01T00:00:00Z")), "不再是开放的");
        } finally {
            teardown(f);
        }
    }

    @Test
    void aPersonCorrectingAnEventLeavesItOneMoment() {
        Fixture f = seed();
        try {
            UUID original = fact(f, f.nova(), f.acquired(), f.orion(),
                    Validity.starting(OffsetDateTime.parse("2024-03-15T00:00:00Z"), "day")).id();
            // 人在面板上填了一段 3-20 ~ 6-01：落下的是 3-20 这一刻
            UUID corrected = temporal.correctInterval(original,
                    new Validity(OffsetDateTime.parse("2024-03-20T00:00:00Z"), "day",
                            OffsetDateTime.parse("2024-06-01T00:00:00Z"), "day", null, null));
            assertNotNull(corrected, "改得动");
            Span cs = span(corrected);
            assertEquals(OffsetDateTime.parse("2024-03-20T00:00:00Z"), cs.from());
            assertEquals("day", cs.fromPrecision());
            assertEquals(OffsetDateTime.parse("2024-03-20T00:00:00Z"), cs.to());
            assertEquals("day", cs.toPrecision());
            assertTrue(holds(f, f.nova(), corrected,
                    OffsetDateTime.parse("2024-03-20T08:00:00Z")));
            assertFalse(holds(f, f.nova(), corrected,
                    OffsetDateTime.parse("2024-04-15T00:00:00Z")));
        } finally {
            teardown(f);
        }
    }

    // ---- reasoning::materialize 真身（任务 6 去封） ----

    private void materialize(UUID kb) {
        reasoning.materialize(kb);
    }

    /**
     * <b>@Disabled 挂起：任务 6</b>——断言面在 reasoning::materialize（对称推反向边：事件
     * 取它的桶、两端带精度；恒常两端开放；没日期的事件空区间不推）。任务 6 落地后把
     * seam 换真调用并启用，断言一字不动。
     */
    @Test
    void aDerivationReadsAnEventAsItsBucketAndAnEternalAsOpen() {
        Fixture f = seed();
        try {
            // merged_with 对称、事件：Nova 与 Orion 于 2024-03 合并
            fact(f, f.nova(), f.mergedWith(), f.orion(),
                    Validity.starting(OffsetDateTime.parse("2024-03-01T00:00:00Z"), "month")
                            .attested(OffsetDateTime.parse("2024-04-01T00:00:00Z")));
            // borders 对称、恒常：法国与……巴黎接壤（测试用的荒唐事实，文档是 2024 年的）
            fact(f, f.france(), f.borders(), f.paris(),
                    Validity.starting(OffsetDateTime.parse("2020-01-01T00:00:00Z"), "year")
                            .attested(OffsetDateTime.parse("2024-04-01T00:00:00Z")));
            // 没日期的事件：Orion 与 Vega 合并，不知何时——推不出反向那条
            fact(f, f.orion(), f.mergedWith(), f.vega(),
                    Validity.empty().attested(OffsetDateTime.parse("2024-04-01T00:00:00Z")));

            materialize(f.kb());

            // 反向的合并：那一个月，两端带月精度
            // timestamptz 按 OffsetDateTime 读（queryForMap 回 Timestamp，按宿主时区渲染）
            record Derived(OffsetDateTime from, String fromPrec, OffsetDateTime to,
                           String toPrec) {
            }
            Derived back = jdbc.queryForObject("""
                    SELECT valid_from, valid_from_precision, valid_to, valid_to_precision
                       FROM derived_facts
                      WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND object_id = ?
                        AND invalidated_at IS NULL
                    """, (rs, i) -> new Derived(rs.getObject(1, OffsetDateTime.class),
                    rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)),
                    f.kb(), f.orion(), f.mergedWith(), f.nova());
            assertEquals(OffsetDateTime.parse("2024-03-01T00:00:00Z"),
                    back.from(), "派生取事件的桶：从 3 月起，到 4 月为止");
            assertEquals("month", back.fromPrec());
            assertEquals(OffsetDateTime.parse("2024-04-01T00:00:00Z"), back.to());
            assertEquals("month", back.toPrec());
            // 反向的接壤：两端开放，证据日期不进来
            Object[] border = jdbc.query("""
                    SELECT valid_from, valid_to FROM derived_facts
                      WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND object_id = ?
                        AND invalidated_at IS NULL
                    """, (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class),
                            rs.getObject(2, OffsetDateTime.class)},
                    f.kb(), f.paris(), f.borders(), f.france()).get(0);
            assertNull(border[0]);
            assertNull(border[1]);
            // 不知何时的合并：空区间，不推
            Long undated = jdbc.queryForObject("""
                    SELECT count(*) FROM derived_facts
                      WHERE kb_id = ? AND subject_id = ? AND predicate_id = ? AND object_id = ?
                    """, Long.class, f.kb(), f.vega(), f.mergedWith(), f.orion());
            assertEquals(0L, undated,
                    "没日期的事件任何时刻都不成立，链经过它推不出东西");
        } finally {
            teardown(f);
        }
    }
}
