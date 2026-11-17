package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.mapper.AuditMapper;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_fact_says_where_its_dates_came_from.rs（413 行，#970）——一条事实
 * 的日期从哪来：end_derived（时间线推的终点）/ time_corrected（人改过的区间，corrected_ends
 * 随修正行写下）/ corrected（修正行）；第二步：推出来的终点找得到关上它的那一行，
 * 人改的区间带着修正时写下的备注。
 *
 * <p><b>@Disabled 挂起：任务 6</b>——断言面全在 entity_detail 读端点（DetailStubs 桩 =
 * graph.rs:1721-1830 核心列，任务 6 落真身后把桩换成真调用即可激活，断言一字不动）。
 * 落库门/对账/correct_interval/审计走的都是真身。
 */
class AFactSaysWhereItsDatesCameFromTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;
    @Autowired
    GraphService graph;
    @Autowired
    TemporalService temporal;
    @Autowired
    AuditMapper audit;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    /** 一个日精度的日子。 */
    private static OffsetDateTime day(String s) {
        return t(s + "T00:00:00Z");
    }

    private static Validity since(String at) {
        return Validity.starting(day(at), "day");
    }

    private static Validity between(String from, String to) {
        return new Validity(day(from), "day", day(to), "day", null, null);
    }

    static GraphService.EntityFact factOf(List<GraphService.EntityFact> facts, String who) {
        return facts.stream().filter(f -> who.equals(f.otherName())).findFirst()
                .orElseThrow(() -> new AssertionError("no fact of " + who));
    }

    /** 那个人的那一行：(终点是推出来的, 区间是人改过的, 是修正行)。 */
    private static boolean[] flags(List<GraphService.EntityFact> facts, String who) {
        GraphService.EntityFact f = factOf(facts, who);
        return new boolean[]{f.endDerived(), f.timeCorrected(), f.corrected()};
    }

    /** 那个人的那一行，人改过哪一端。 */
    private static String ends(List<GraphService.EntityFact> facts, String who) {
        return factOf(facts, who).correctedEnds();
    }

    /** 那个人的那一行被谁关上：(关它的那一行, 接任的那一端)。 */
    private static Object[] closer(List<GraphService.EntityFact> facts, String who) {
        GraphService.EntityFact f = factOf(facts, who);
        return new Object[]{f.closedById(), f.closedBy()};
    }

    @Test
    void a_fact_says_where_its_dates_came_from() throws Exception {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 17; i++) {
            ids.add(Uuids.nowV7());
        }
        UUID org = ids.get(0);
        UUID ws = ids.get(1);
        UUID kb = ids.get(2);
        UUID user = ids.get(3);
        UUID person = ids.get(4);
        UUID project = ids.get(5);
        UUID leads = ids.get(6);
        UUID advises = ids.get(7);
        UUID zhang = ids.get(8);
        UUID li = ids.get(9);
        UUID zhou = ids.get(10);
        UUID wang = ids.get(11);
        UUID aurora = ids.get(12);
        UUID company = ids.get(13);
        UUID employer = ids.get(14);
        UUID acme = ids.get(15);
        UUID globex = ids.get(16);
        // Only locally generated UUIDs are interpolated into fixture SQL.
        jdbc.update("""
                INSERT INTO organizations (id, name) VALUES ('%s', 'dates-came-from');
                """.formatted(org));
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'dates-came-from')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'dates-came-from')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'editor')
                """, user, org, user + "@dates.test");
        jdbc.update("""
                INSERT INTO entity_types (id, kb_id, key, label, color, shape) VALUES
                    (?, ?, 'person', 'person', '#7fd0ff', 'circle'),
                    (?, ?, 'project', 'project', '#7fd0ff', 'circle'),
                    (?, ?, 'organization', 'organization', '#7fd0ff', 'circle')
                """, person, kb, project, kb, company, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal, inverse_functional) VALUES
                    (?, ?, 'leads', 'leads', 'state', TRUE),
                    (?, ?, 'advises', 'advises', 'state', FALSE)
                """, leads, kb, advises, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal, functional)
                 VALUES (?, ?, 'employer', 'employer', 'state', TRUE)
                """, employer, kb);
        jdbc.update("""
                INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES
                    (?, ?, ?, 'Zhang San'), (?, ?, ?, 'Li Si'),
                    (?, ?, ?, 'Zhou Qi'), (?, ?, ?, 'Wang Wu'),
                    (?, ?, ?, 'Aurora'),
                    (?, ?, ?, 'Acme'), (?, ?, ?, 'Globex')
                """, zhang, kb, person, li, kb, person, zhou, kb, person, wang, kb, person,
                aurora, kb, project, acme, kb, company, globex, kb, company);

        try {
            // 周七接手：一个项目同时只有一个 lead，对账把李四那一段关在 2025-09-01
            UUID liRow = writes.insertFact(kb, li, leads, aurora, since("2024-07-05"), 0.9f).id();
            UUID zhouRow = writes.insertFact(kb, zhou, leads, aurora, since("2025-09-01"), 0.9f).id();
            OffsetDateTime beforeClose = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
            temporal.reconcileFacts(kb, List.of(liRow, zhouRow));

            // 王五换了雇主：一个人同时只有一个雇主（functional），对账把 Acme 那段关在换的那天
            UUID acmeRow = writes.insertFact(kb, wang, employer, acme, since("2020-01-01"), 0.9f).id();
            UUID globexRow = writes.insertFact(kb, wang, employer, globex, since("2022-03-01"),
                    0.9f).id();
            temporal.reconcileFacts(kb, List.of(acmeRow, globexRow));

            // 人改张三的起点，终点照旧；审计记在被改的那一行上（与 PATCH /facts/{id} 同一条路）
            UUID zhangRow = writes.insertFact(kb, zhang, leads, aurora,
                    between("2023-01-10", "2024-07-05"), 0.9f).id();
            UUID corrected = temporal.correctInterval(zhangRow, between("2023-02-01", "2024-07-05"));
            assertNotNull(corrected, "the row was live");
            audit.insertAuditEvent(Uuids.nowV7(), kb, user, "fact.time_corrected", "fact",
                    zhangRow, JSON.createObjectNode()
                            .put("note", "The charter date was the approval date").toString(),
                    null, null, null);

            // 人手关上的一段：一条修正行，但终点是人给的，区间也没被改过
            UUID wangRow = writes.insertFact(kb, wang, advises, aurora, since("2023-03-02"), 0.9f).id();
            temporal.closeSuperseded(wangRow, t("2024-01-01T00:00:00Z"), "day");

            var facts = graph.entityDetail(kb, aurora, null, null).facts();
            assertEquals("[true, false, true]",
                    java.util.Arrays.toString(flags(facts, "Li Si")), "the timeline closed it");
            assertEquals("[false, false, false]",
                    java.util.Arrays.toString(flags(facts, "Zhou Qi")));
            assertEquals("[false, true, true]",
                    java.util.Arrays.toString(flags(facts, "Zhang San")), "a person corrected it");
            assertEquals("[false, false, true]",
                    java.util.Arrays.toString(flags(facts, "Wang Wu")),
                    "closed by hand: rewritten, but neither mark");
            // 第二步：谁关的它，人改时说了什么
            var liCloser = closer(facts, "Li Si");
            assertEquals(zhouRow, liCloser[0], "Zhou Qi's row took over");
            assertEquals("Zhou Qi", liCloser[1]);
            var zhouCloser = closer(facts, "Zhou Qi");
            assertEquals(null, zhouCloser[0], "still open");
            assertEquals(null, zhouCloser[1]);
            var wangCloser = closer(facts, "Wang Wu");
            assertEquals(null, wangCloser[0], "closed by hand");
            assertEquals(null, wangCloser[1]);
            assertEquals("The charter date was the approval date",
                    factOf(facts, "Zhang San").correctionNote());
            assertEquals("start", ends(facts, "Zhang San"));
            assertEquals(null, ends(facts, "Wang Wu"), "closed by hand, not corrected");
            var wangFacts = graph.entityDetail(kb, wang, null, null).facts();
            var acmeCloser = closer(wangFacts, "Acme");
            assertEquals(globexRow, acmeCloser[0], "a functional property: the next employer took over");
            assertEquals("Globex", acmeCloser[1]);
            // 记录轴回到接手之前：李四那段还开着，没有谁关它
            var then = graph.entityDetail(kb, aurora, null, beforeClose).facts();
            assertEquals(false, flags(then, "Li Si")[0], "not closed yet");
            var thenCloser = closer(then, "Li Si");
            assertEquals(null, thenCloser[0]);
            assertEquals(null, thenCloser[1]);

            // 改过的区间再被改写一次（这里是关上），标记随行带着，备注仍在链上找得到
            temporal.closeSuperseded(corrected, t("2024-06-30T00:00:00Z"), "day");
            facts = graph.entityDetail(kb, aurora, null, null).facts();
            assertEquals("[false, true, true]",
                    java.util.Arrays.toString(flags(facts, "Zhang San")));
            assertEquals("start", ends(facts, "Zhang San"));
            assertEquals("The charter date was the approval date",
                    factOf(facts, "Zhang San").correctionNote(), "the note is found up the chain");

            // 人只改了李四的起点、终点照抄：交来的是整段区间，那个推出来的终点从此是人钉住的
            //（时间线不再重画它），两端都算人的——行上不能说终点是原句的。这一次没有审计，
            // 标记照样在行上
            UUID liNow = factOf(facts, "Li Si").id();
            assertNotNull(temporal.correctInterval(liNow, between("2024-07-08", "2025-09-01")),
                    "the row was live");
            facts = graph.entityDetail(kb, aurora, null, null).facts();
            assertEquals("[false, true, true]",
                    java.util.Arrays.toString(flags(facts, "Li Si")),
                    "a person's interval: the timeline no longer owns its end");
            assertEquals("both", ends(facts, "Li Si"),
                    "the derived end the person kept is theirs too");
            assertEquals(null, factOf(facts, "Li Si").correctionNote(), "no note written");

            // 张三再改一次，这回改终点：两端都是人改的了，备注取最近那一次
            UUID zhangNow = factOf(facts, "Zhang San").id();
            assertNotNull(temporal.correctInterval(zhangNow, between("2023-02-01", "2024-07-01")),
                    "the row was live");
            audit.insertAuditEvent(Uuids.nowV7(), kb, user, "fact.time_corrected", "fact",
                    zhangNow, JSON.createObjectNode().put("note", "The handover was on 1 July")
                            .toString(), null, null, null);
            facts = graph.entityDetail(kb, aurora, null, null).facts();
            assertEquals("both", ends(facts, "Zhang San"));
            assertEquals("The handover was on 1 July", factOf(facts, "Zhang San").correctionNote());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /**
     * 关闭者的两种情形（#986 合并时的评审）：同一天开始的两个后任，时间线按 id 取最先的
     * 那一行关上前任；关闭者自己后来又被下一任关上、改写成新的一行——说的是活着的那一版。
     */
    @Test
    void a_closer_is_the_row_the_timeline_closed_with() {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(Uuids.nowV7());
        }
        UUID org = ids.get(0);
        UUID ws = ids.get(1);
        UUID kb = ids.get(2);
        UUID person = ids.get(3);
        UUID project = ids.get(4);
        UUID leads = ids.get(5);
        UUID li = ids.get(6);
        UUID zhou = ids.get(7);
        UUID sun = ids.get(8);
        UUID qian = ids.get(9);
        UUID aurora = ids.get(10);
        UUID helios = ids.get(11);
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'closers')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'closers')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'closers')",
                kb, ws);
        jdbc.update("""
                INSERT INTO entity_types (id, kb_id, key, label, color, shape) VALUES
                    (?, ?, 'person', 'person', '#7fd0ff', 'circle'),
                    (?, ?, 'project', 'project', '#7fd0ff', 'circle')
                """, person, kb, project, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal, inverse_functional)
                 VALUES (?, ?, 'leads', 'leads', 'state', TRUE)
                """, leads, kb);
        jdbc.update("""
                INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES
                    (?, ?, ?, 'Li Si'), (?, ?, ?, 'Zhou Qi'),
                    (?, ?, ?, 'Sun Ba'), (?, ?, ?, 'Qian Qi'),
                    (?, ?, ?, 'Aurora'), (?, ?, ?, 'Helios')
                """, li, kb, person, zhou, kb, person, sun, kb, person, qian, kb, person,
                aurora, kb, project, helios, kb, project);

        try {
            // Aurora：周七与孙八同一天接手。一个项目同时只有一个 lead，李四那段关在那一天，
            // 关它的是 id 在前的周七那一行
            UUID liA = writes.insertFact(kb, li, leads, aurora, since("2024-07-05"), 0.9f).id();
            UUID zhouA = writes.insertFact(kb, zhou, leads, aurora, since("2025-09-01"), 0.9f).id();
            UUID sunA = writes.insertFact(kb, sun, leads, aurora, since("2025-09-01"), 0.9f).id();
            // assert!(zhou_a < sun_a)：now_v7 单调，先建的在 前
            temporal.reconcileFacts(kb, List.of(liA, zhouA, sunA));
            var facts = graph.entityDetail(kb, aurora, null, null).facts();
            assertEquals(true, flags(facts, "Li Si")[0], "the timeline closed it");
            assertEquals(day("2025-09-01"), factOf(facts, "Li Si").validTo());
            var liCloser = closer(facts, "Li Si");
            assertEquals(zhouA, liCloser[0], "the first of the two by id, as the engine orders them");
            assertEquals("Zhou Qi", liCloser[1]);

            // Helios：周七接手之后又被钱七接手，周七那一行被关上、改写成新的一行。李四那段的
            // 关闭者是周七活着的那一版
            UUID liH = writes.insertFact(kb, li, leads, helios, since("2024-07-05"), 0.9f).id();
            UUID zhouH = writes.insertFact(kb, zhou, leads, helios, since("2025-09-01"), 0.9f).id();
            temporal.reconcileFacts(kb, List.of(liH, zhouH));
            UUID qianH = writes.insertFact(kb, qian, leads, helios, since("2026-03-01"), 0.9f).id();
            temporal.reconcileFacts(kb, List.of(qianH));
            UUID zhouNow = jdbc.queryForObject("""
                    SELECT id FROM facts WHERE kb_id = ? AND subject_id = ? AND object_id = ?
                        AND invalidated_at IS NULL
                    """, UUID.class, kb, zhou, helios);
            assertEquals(false, zhouNow.equals(zhouH),
                    "the closer was rewritten when Qian Qi took over");
            var heliosFacts = graph.entityDetail(kb, helios, null, null).facts();
            assertEquals(day("2026-03-01"), factOf(heliosFacts, "Zhou Qi").validTo());
            assertEquals(day("2025-09-01"), factOf(heliosFacts, "Li Si").validTo());
            var hLiCloser = closer(heliosFacts, "Li Si");
            assertEquals(zhouNow, hLiCloser[0], "the live version of the closer");
            assertEquals("Zhou Qi", hLiCloser[1]);
            var hZhouCloser = closer(heliosFacts, "Zhou Qi");
            assertEquals(qianH, hZhouCloser[0]);
            assertEquals("Qian Qi", hZhouCloser[1]);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
