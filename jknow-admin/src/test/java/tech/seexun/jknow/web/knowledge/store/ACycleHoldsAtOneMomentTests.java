package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_cycle_holds_at_one_moment.rs（514 行，7 例）——传递环要整条路径
 * 同一时刻成立（#636），打在真库上。
 *
 * <p>纯逻辑那部分——两两重叠而三者无交、剪枝丢不了真环、随机图对拍——在 utopia-reason
 * 里跑。这里钉的是只有过一遍取数与落库才看得见的：
 * <ol>
 *   <li><b>时间上错开的环不进 Review；改对了时间，环就消失</b>。人去把其中一条的结束
 *       日期补上（correct_interval，作废 + 改写），重跑那一行被清掉。从前改完照样算出
 *       同一个环——人按提示修了数据，队列却纹丝不动</li>
 *   <li><b>区间是按库里的写法读出来的</b>（read_span），每一种写法都要走到：结束了不知
 *       哪天的到证据为止（0022）；没起点的从最早的证据起；事件在它命名的那个桶里成立；
 *       恒常谓词没有时间可错开，照报；把谓词改成状态，重跑就清掉</li>
 *   <li><b>同一对节点之间有两条边，一条从没与别的边同时成立</b>：环照样经由另一条被找到</li>
 *   <li><b>裁决照旧</b>：公理放宽了而环仍同时成立，那行重开；认可过的重跑沉默；撤掉环上
 *       一条，环不再算出来，裁决留着</li>
 * </ol>
 *
 * <p>tokio→JUnit 等价点：Rust with_fixture! 宏展开为 seed/run/拆库三段（先删 kb 再删
 * org——裁决行的 decided_by 指着这个组织的用户，级联谁先谁后不保证）。
 */
class ACycleHoldsAtOneMomentTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    @Autowired
    TemporalService temporal;

    record Fixture(UUID org, UUID kb, UUID etype, UUID user, UUID partOf, UUID mergedInto,
                   UUID locatedIn) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID mergedInto = Uuids.nowV7();
        UUID locatedIn = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'cycle-time-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'cycle-time-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'cycle-time-test')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Cycle Reviewer')
                """, user, org, "cycle-" + user + "@test.local");
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        for (Object[] row : new Object[][]{{partOf, "part_of", "state"},
                {mergedInto, "merged_into", "event"}, {locatedIn, "located_in", "eternal"}}) {
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, is_transitive)
                     VALUES (?, ?, ?, ?, ?, TRUE)
                    """, row[0], kb, row[1], row[1], row[2]);
        }
        return new Fixture(org, kb, etype, user, partOf, mergedInto, locatedIn);
    }

    private void teardown(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    private UUID entity(Fixture f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), f.etype(), name);
        return id;
    }

    private UUID[] entities(Fixture f, int n) {
        UUID[] out = new UUID[n];
        for (int i = 0; i < n; i++) {
            out[i] = entity(f, "E" + i);
        }
        return out;
    }

    private static OffsetDateTime ts(String d) {
        return OffsetDateTime.parse(d + "T00:00:00Z");
    }

    /** 一条事实在库里怎么写时间（Rust When）。日期给到精度对齐的那一天（CHECK 要求截断过）。 */
    record When(String fromDate, String fromPrecision, String toDate, String toPrecision,
                String endedUnknownAsOf, String attested) {

        static When span(String from, String to) {
            return new When(from, "day", to, "day", null, null);
        }
    }

    private UUID fact(Fixture f, UUID s, UUID p, UUID o, When w) {
        UUID id = Uuids.nowV7();
        // 精度只在终点真的给了时才写（Rust 的 match (w.to, w.ended_unknown_as_of)：
        // None+None → NULL——CHECK 要求 valid_to 为空时精度为 NULL 或 'unknown'）
        String toPrecision = w.toDate() != null ? w.toPrecision()
                : (w.endedUnknownAsOf() != null ? "unknown" : null);
        OffsetDateTime attestedFrom = w.attested() != null ? ts(w.attested())
                : (w.endedUnknownAsOf() != null ? ts(w.endedUnknownAsOf()) : null);
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    valid_from, valid_from_precision, valid_to, valid_to_precision,
                                    attested_from, attested_to)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()), ?)
                """,
                id, f.kb(), s, p, o,
                w.fromDate() == null ? null : ts(w.fromDate()), w.fromPrecision(),
                w.toDate() == null ? null : ts(w.toDate()), toPrecision,
                attestedFrom,
                w.endedUnknownAsOf() == null ? null : ts(w.endedUnknownAsOf()));
        return id;
    }

    /** open 的环，每个是排过序的事实集合。 */
    private List<List<UUID>> openCycles(Fixture f) {
        List<List<UUID>> rows = jdbc.query(
                "SELECT path FROM axiom_violations WHERE kb_id = ? AND kind = 'cycle'"
                        + " AND status = 'open'",
                (rs, i) -> {
                    java.sql.Array arr = rs.getArray(1);
                    UUID[] ids = (UUID[]) arr.getArray();
                    List<UUID> list = new ArrayList<>(List.of(ids));
                    list.sort(Comparator.naturalOrder());
                    return list;
                }, f.kb());
        rows.sort((a, b) -> {
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                int d = a.get(i).compareTo(b.get(i));
                if (d != 0) {
                    return d;
                }
            }
            return Integer.compare(a.size(), b.size());
        });
        return rows;
    }

    @Test
    void aCycleIsOneMomentAndFixingTheTimeClearsIt() {
        Fixture f = seed();
        try {
            // ---- 一、时间上错开的环：两段前后接上，三段两两重叠而三者无交
            UUID[] e = entities(f, 5);
            UUID a = e[0];
            UUID b = e[1];
            UUID d = e[2];
            UUID g = e[3];
            UUID gg = e[4];
            fact(f, a, f.partOf(), b, When.span("2019-01-01", "2021-01-01"));
            fact(f, b, f.partOf(), a, When.span("2022-01-01", null));
            fact(f, d, f.partOf(), g, When.span("2020-01-01", "2022-01-01"));
            fact(f, g, f.partOf(), gg, When.span("2021-01-01", "2023-01-01"));
            fact(f, gg, f.partOf(), d, When.span("2022-01-01", "2024-01-01"));

            var r = reasoning.run(f.kb());
            assertEquals(5, r.edges());
            assertTrue(openCycles(f).isEmpty(), "没有哪一刻整条成立的环不该进 Review");

            // ---- 二、真正同时成立的环照报
            UUID x = entity(f, "X");
            UUID y = entity(f, "Y");
            UUID xy = fact(f, x, f.partOf(), y, When.span("2020-01-01", null));
            UUID yx = fact(f, y, f.partOf(), x, When.span("2021-06-01", null));
            reasoning.run(f.kb());
            assertEquals(List.of(sorted(xy, yx)), openCycles(f));

            // ---- 三、人去把 X part_of Y 的结束日期补上：它 2021-06-01 结束，正是
            // Y part_of X 开始的那天。改完重跑，那一行被清掉
            UUID corrected = temporal.correctInterval(xy, new Validity(ts("2020-01-01"), "day",
                    ts("2021-06-01"), "day", null, null));
            assertTrue(corrected != null, "这条还活着，应当改得动");
            var after = reasoning.run(f.kb());
            assertTrue(openCycles(f).isEmpty(), "改对了时间，环就不在了");
            assertEquals(1, after.cleared(), "上一轮那一行是陈的");
        } finally {
            teardown(f);
        }
    }

    private static List<UUID> sorted(UUID... ids) {
        List<UUID> out = new ArrayList<>(List.of(ids));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** "former" 的那条读到证据为止。读成开放的话，一条早已结束、只是没写哪天结束的边，
     *  会与今天才开始的反向边凑成一个环。 */
    @Test
    void anUnknownEndStopsWhereTheEvidenceDoes() {
        Fixture f = seed();
        try {
            When former = new When("2019-01-01", "day", null, null, "2020-06-01", null);

            // 证据 2020-06 说它已经结束；反向边 2021 年才开始
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            fact(f, a, f.partOf(), b, former);
            fact(f, b, f.partOf(), a, When.span("2021-01-01", null));
            reasoning.run(f.kb());
            assertTrue(openCycles(f).isEmpty(), "结束了不知哪天的不是还开着");

            // 反向边 2020 年初就开始了：在证据之前，两条确实同时成立过
            UUID c = entity(f, "C");
            UUID d = entity(f, "D");
            UUID cd = fact(f, c, f.partOf(), d, former);
            UUID dc = fact(f, d, f.partOf(), c, When.span("2020-01-01", null));
            reasoning.run(f.kb());
            assertEquals(List.of(sorted(cd, dc)), openCycles(f));
        } finally {
            teardown(f);
        }
    }

    /** 没写起点的从最早的证据起，不是从来如此。 */
    @Test
    void aMissingStartReadsFromTheEvidence() {
        Fixture f = seed();
        try {
            // 2023 年的证据说 A 属于 B（没说从哪天起）；B 属于 A 在 2022 年就结束了
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            fact(f, a, f.partOf(), b, new When(null, null, null, null, null, "2023-01-01"));
            fact(f, b, f.partOf(), a, When.span("2020-01-01", "2022-01-01"));
            reasoning.run(f.kb());
            assertTrue(openCycles(f).isEmpty());

            // 证据是 2021 年的：落在反向边的区间里
            UUID c = entity(f, "C");
            UUID d = entity(f, "D");
            UUID cd = fact(f, c, f.partOf(), d, new When(null, null, null, null, null, "2021-01-01"));
            UUID dc = fact(f, d, f.partOf(), c, When.span("2020-01-01", "2022-01-01"));
            reasoning.run(f.kb());
            assertEquals(List.of(sorted(cd, dc)), openCycles(f));
        } finally {
            teardown(f);
        }
    }

    /** 事件在它命名的桶里成立：2024-03-05 那天与 2024 年 3 月相交，与 3 月 6 日不交。
     *  没日期的事件哪一刻都不成立。 */
    @Test
    void anEventHoldsInTheBucketItNames() {
        Fixture f = seed();
        try {
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            UUID ab = fact(f, a, f.mergedInto(), b,
                    new When("2024-03-05", "day", null, null, null, null));
            UUID ba = fact(f, b, f.mergedInto(), a,
                    new When("2024-03-01", "month", null, null, null, null));

            UUID c = entity(f, "C");
            UUID d = entity(f, "D");
            fact(f, c, f.mergedInto(), d, new When("2024-03-05", "day", null, null, null, null));
            fact(f, d, f.mergedInto(), c, new When("2024-03-06", "day", null, null, null, null));

            UUID e = entity(f, "E");
            UUID g = entity(f, "G");
            fact(f, e, f.mergedInto(), g, new When(null, null, null, null, null, null));
            fact(f, g, f.mergedInto(), e, new When(null, null, null, null, null, null));

            reasoning.run(f.kb());
            assertEquals(List.of(sorted(ab, ba)), openCycles(f),
                    "只有同一天落在同一个月里的那一对成环");
        } finally {
            teardown(f);
        }
    }

    /** 恒常谓词没有时间可以错开：日期写了也不看，照报。把谓词改成状态，重跑就清掉；
     *  改回来，环又回来。 */
    @Test
    void anEternalPredicateHasNoTimeToTellApart() {
        Fixture f = seed();
        try {
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            UUID ab = fact(f, a, f.locatedIn(), b, When.span("2019-01-01", "2021-01-01"));
            UUID ba = fact(f, b, f.locatedIn(), a, When.span("2022-01-01", null));
            reasoning.run(f.kb());
            assertEquals(List.of(sorted(ab, ba)), openCycles(f));

            jdbc.update("UPDATE relation_types SET temporal = 'state' WHERE id = ?",
                    f.locatedIn());
            var r = reasoning.run(f.kb());
            assertTrue(openCycles(f).isEmpty(), "成了状态，就看日期");
            assertEquals(1, r.cleared());

            jdbc.update("UPDATE relation_types SET temporal = 'eternal' WHERE id = ?",
                    f.locatedIn());
            reasoning.run(f.kb());
            assertEquals(List.of(sorted(ab, ba)), openCycles(f));
        } finally {
            teardown(f);
        }
    }

    /** 同一对节点之间两条边：旧的那条从没与环上别的边同时成立，新的那条成立。环经由
     *  新的那条被找到——先碰到旧的那条、被剪掉，不能让整个环跟着丢。 */
    @Test
    void aParallelEdgeThatNeverMetDoesNotHideTheOneThatDid() {
        Fixture f = seed();
        try {
            UUID[] e = entities(f, 3);
            UUID a = e[0];
            UUID b = e[1];
            UUID c = e[2];
            // 先插旧的，顺序扫描先读到它
            fact(f, a, f.partOf(), b, When.span("2019-01-01", "2020-01-01"));
            UUID ab = fact(f, a, f.partOf(), b, When.span("2021-01-01", null));
            UUID bc = fact(f, b, f.partOf(), c, When.span("2021-01-01", null));
            UUID ca = fact(f, c, f.partOf(), a, When.span("2021-01-01", null));
            reasoning.run(f.kb());
            assertEquals(List.of(sorted(ab, bc, ca)), openCycles(f));
        } finally {
            teardown(f);
        }
    }

    /** 裁决照旧：放宽了公理而环仍同时成立，重开；认可过的沉默；撤掉一条，环不再算出来。 */
    @Test
    void decisionsOnACycleStillHold() {
        Fixture f = seed();
        try {
            UUID x = entity(f, "X");
            UUID y = entity(f, "Y");
            UUID xy = fact(f, x, f.partOf(), y, When.span("2020-01-01", null));
            fact(f, y, f.partOf(), x, When.span("2021-01-01", null));
            reasoning.run(f.kb());

            var id = (java.util.function.Supplier<UUID>) () -> jdbc.queryForObject(
                    "SELECT id FROM axiom_violations WHERE kb_id = ? AND kind = 'cycle'",
                    UUID.class, f.kb());

            reasoning.decide(f.kb(), id.get(), "axiom_relaxed", f.user());
            var r = reasoning.run(f.kb());
            assertEquals(1, r.reopened(), "公理没真放宽，环还同时成立，那一行回到 open");

            reasoning.decide(f.kb(), id.get(), "accepted", f.user());
            var r2 = reasoning.run(f.kb());
            assertEquals(0, r2.inserted());
            assertEquals(0, r2.reopened(), "认可过的重跑沉默");
            assertTrue(openCycles(f).isEmpty());

            // 另一个环：撤掉其中一条
            UUID p = entity(f, "P");
            UUID q = entity(f, "Q");
            UUID pq = fact(f, p, f.partOf(), q, When.span("2020-01-01", null));
            fact(f, q, f.partOf(), p, When.span("2020-06-01", null));
            reasoning.run(f.kb());
            UUID open = jdbc.queryForObject(
                    "SELECT id FROM axiom_violations WHERE kb_id = ? AND kind = 'cycle'"
                            + " AND status = 'open'",
                    UUID.class, f.kb());
            reasoning.retractFromViolation(f.kb(), open, pq, f.user());
            var r3 = reasoning.run(f.kb());
            assertEquals(0, r3.reopened(), "撤掉之后环不再算出来，承诺兑现了");
            assertTrue(openCycles(f).isEmpty());
            Long resolved = jdbc.queryForObject(
                    "SELECT count(*) FROM axiom_violations WHERE kb_id = ?"
                            + " AND kind = 'cycle' AND status = 'resolved'",
                    Long.class, f.kb());
            assertEquals(2L, resolved, "两次裁决都留着");
        } finally {
            teardown(f);
        }
    }
}
