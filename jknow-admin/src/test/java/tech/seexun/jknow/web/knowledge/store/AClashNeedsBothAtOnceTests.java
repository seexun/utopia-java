package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/a_clash_needs_both_at_once.rs（247 行，3 例）——互斥违规
 * （asymmetry、functional、inverse_functional）打在真库上（#624、#634）。
 *
 * <p>纯逻辑那部分——全排列下同一个键、首尾相接不算、连通块成组——在 utopia-reason
 * 里跑。这里钉的是只有过一遍取数与落库才看得见的四样：
 * <ol>
 *   <li><b>接任不进 Review</b>。从前 run 把区间剥掉再查：一次调薪、一次换负责人、一次
 *       上下级对调，各进一行。区间是 timed_edges 从库里读出来的，这一段纯逻辑测不到</li>
 *   <li><b>宾语侧记成自己的种类</b>。从前落库是 functional，人照着去主语那一侧找</li>
 *   <li><b>换一个取数顺序，裁决照样接得上</b>。这是 #624 报的那个序列：裁成
 *       axiom_relaxed，公理与事实都没变，只是堆里的顺序变了，重跑——从前插了一行新的
 *       open、原来那行不重开</li>
 *   <li><b>认可管的是它见过的那几条</b>。组里多一条，要人再看；那条撤了，认可照样管着
 *       剩下的，哪怕组的首尾（也就是键）已经变了</li>
 * </ol>
 */
class AClashNeedsBothAtOnceTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID etype, UUID salary, UUID leads, UUID reportsTo) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID salary = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        UUID reportsTo = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'clash-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'clash-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'clash-test')",
                kb, ws);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        for (Object[] row : new Object[][]{{salary, "salary", true, false, false},
                {leads, "leads", false, true, false},
                {reportsTo, "reports_to", false, false, true}}) {
            jdbc.update("""
                    INSERT INTO relation_types
                         (id, kb_id, key, label, temporal, functional, inverse_functional, is_asymmetric)
                     VALUES (?, ?, ?, ?, 'state', ?, ?, ?)
                    """, row[0], kb, row[1], row[1], row[2], row[3], row[4]);
        }
        return new Fixture(org, kb, etype, salary, leads, reportsTo);
    }

    private UUID entity(Fixture f, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), f.etype(), name);
        return id;
    }

    /** 一条关系事实，区间 [from, to)，按天。两端都不给就是「从证据那天起一直成立」。 */
    private UUID fact(Fixture f, UUID s, UUID p, UUID o, String from, String to) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    valid_from, valid_from_precision, valid_to, valid_to_precision)
                 VALUES (?, ?, ?, ?, ?, ?::timestamptz, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'day' END,
                         ?::timestamptz, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'day' END)
                """,
                id, f.kb(), s, p, o, from, from, to, to);
        return id;
    }

    record OpenRow(String kind, UUID leftFact, UUID rightFact, List<UUID> path) {
    }

    private List<OpenRow> open(Fixture f) {
        return jdbc.query("""
                SELECT kind, left_fact, right_fact, path FROM axiom_violations
                  WHERE kb_id = ? AND status = 'open' ORDER BY kind, left_fact
                """, (rs, i) -> new OpenRow(rs.getString(1), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                List.of((UUID[]) rs.getArray(4).getArray())), f.kb());
    }

    private static List<UUID> sorted(UUID... ids) {
        List<UUID> out = new ArrayList<>(List.of(ids));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Test
    void aSuccessionStaysOutOfReviewAndAnOverlapComesIn() {
        Fixture f = seed();
        try {
            UUID lin = entity(f, "Lin Zhao");
            UUID k28 = entity(f, "28000");
            UUID k32 = entity(f, "32000");
            UUID k40 = entity(f, "40000");
            UUID aurora = entity(f, "Project Aurora");
            UUID zhang = entity(f, "Zhang San");
            UUID zhou = entity(f, "Zhou Qi");
            UUID kim = entity(f, "Kim");
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");

            // ---- 一、三种接任：调薪首尾相接，换负责人中间空了一年，上下级前后对调
            fact(f, lin, f.salary(), k28, "2023-06-01T00:00:00Z", "2024-02-20T00:00:00Z");
            UUID raised = fact(f, lin, f.salary(), k32, "2024-02-20T00:00:00Z", null);
            fact(f, zhang, f.leads(), aurora, "2023-02-01T00:00:00Z", "2024-07-05T00:00:00Z");
            UUID tookOver = fact(f, zhou, f.leads(), aurora, "2025-09-01T00:00:00Z", null);
            fact(f, a, f.reportsTo(), b, "2020-01-01T00:00:00Z", "2022-01-01T00:00:00Z");
            fact(f, b, f.reportsTo(), a, "2023-01-01T00:00:00Z", null);

            var r = reasoning.run(f.kb());
            assertEquals(6, r.edges());
            assertEquals(0, r.found(), "三次接任，没有一处矛盾");
            assertTrue(open(f).isEmpty());

            // ---- 二、重叠才是矛盾。宾语侧记成 inverse_functional
            UUID doubled = fact(f, lin, f.salary(), k40, "2024-06-01T00:00:00Z", null);
            UUID secondLead = fact(f, kim, f.leads(), aurora, "2025-10-01T00:00:00Z", null);
            reasoning.run(f.kb());
            List<OpenRow> rows = open(f);
            assertEquals(2, rows.size(), rows.toString());

            OpenRow func = rows.stream().filter(x -> "functional".equals(x.kind())).findFirst()
                    .orElseThrow(() -> new AssertionError("functional row"));
            List<UUID> pair = sorted(raised, doubled);
            assertEquals(pair.get(0), func.leftFact());
            assertEquals(pair.get(1), func.rightFact(), "首尾是组里最小与最大的 id");
            assertEquals(pair, func.path(), "path 是整组，按 id 排序");

            OpenRow inv = rows.stream()
                    .filter(x -> "inverse_functional".equals(x.kind())).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "宾语侧违规记成 inverse_functional，不冒充 functional"));
            assertEquals(sorted(tookOver, secondLead), inv.path());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    @Test
    void aDecisionFollowsTheClashNotTheScanOrder() {
        Fixture f = seed();
        try {
            UUID a = entity(f, "A");
            UUID b = entity(f, "B");
            UUID ab = fact(f, a, f.reportsTo(), b, null, null);
            UUID ba = fact(f, b, f.reportsTo(), a, null, null);

            var r = reasoning.run(f.kb());
            assertEquals(1, r.inserted());
            jdbc.update(
                    "UPDATE axiom_violations SET status = 'resolved', resolution = 'axiom_relaxed'"
                            + " WHERE kb_id = ? AND kind = 'asymmetry'",
                    f.kb());

            // 公理没放宽、事实没动，只把先读到的那条挪到堆的后面：一次什么都不改的
            // UPDATE 会写一个新的行版本，顺序扫描于是先读到另一条。从前这就换了键——
            // 插一行新的 open，裁过的那行不重开（#624 的复现序列）
            jdbc.update("UPDATE facts SET confidence = confidence WHERE id = ?", ab);

            var again = reasoning.run(f.kb());
            assertEquals(0, again.inserted(), "同一处冲突不因为读的顺序变了就成了另一行");
            assertEquals(1, again.reopened(), "放宽公理的承诺没兑现，那一行回到 open");
            List<OpenRow> rows = open(f);
            assertEquals(1, rows.size());
            assertEquals(sorted(ab, ba), rows.get(0).path());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    @Test
    void anAcceptanceCoversTheFactsItSaw() {
        Fixture f = seed();
        try {
            UUID lin = entity(f, "Lin Zhao");
            UUID x = entity(f, "base");
            UUID y = entity(f, "bonus");
            UUID z = entity(f, "stock");
            UUID p = fact(f, lin, f.salary(), x, "2024-01-01T00:00:00Z", null);
            UUID q = fact(f, lin, f.salary(), y, "2024-01-01T00:00:00Z", null);
            reasoning.run(f.kb());
            jdbc.update(
                    "UPDATE axiom_violations SET status = 'resolved', resolution = 'accepted'"
                            + " WHERE kb_id = ? AND kind = 'functional'",
                    f.kb());

            var quiet = reasoning.run(f.kb());
            assertEquals(0, quiet.inserted());
            assertEquals(0, quiet.reopened(), "认可过的并存重跑沉默");
            assertTrue(open(f).isEmpty());

            // ---- 组里多了一条认可时没见过的：那是新情况，要人再看
            UUID extra = fact(f, lin, f.salary(), z, "2024-03-01T00:00:00Z", null);
            reasoning.run(f.kb());
            List<OpenRow> rows = open(f);
            assertEquals(1, rows.size(), rows.toString());
            assertEquals(sorted(p, q, extra), rows.get(0).path());

            // ---- 那条撤了：剩下的仍是认可过的那两条。组的首尾可能已经换了，键跟着换，
            // 认可照样管着——按事实比，不按键比
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", extra);
            var settled = reasoning.run(f.kb());
            assertTrue(open(f).isEmpty(), "撤掉新来的那条，回到认可过的状态");
            assertEquals(0, settled.inserted());
            Long accepted = jdbc.queryForObject(
                    "SELECT count(*) FROM axiom_violations WHERE kb_id = ?"
                            + " AND status = 'resolved' AND resolution = 'accepted'",
                    Long.class, f.kb());
            assertEquals(1L, accepted, "人的认可活过这一整串重跑");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
