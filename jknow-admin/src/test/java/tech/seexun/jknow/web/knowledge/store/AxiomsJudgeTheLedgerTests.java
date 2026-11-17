package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/axioms_judge_the_ledger.rs（240 行，1 例）——一致性检查打在真库上：
 * 取数、判断、落库、重跑。纯逻辑那部分在 utopia-reason（24 例）。
 *
 * <p>这里钉的是那一层看不见的四样，每一样都活在 SQL 或表约束里：
 * <ul>
 *   <li><b>取数的三个过滤</b>。被推翻的事实、没有谓词的事实、宾语是字面值的属性事实，
 *       都不该参与——公理谈的是实体之间的关系</li>
 *   <li><b>没有公理就没有判据</b>。一个没导本体包的库跑出来是零，那是实情不是故障</li>
 *   <li><b>重跑幂等</b>。同一处矛盾不重复入库，而人表过态的那一行重跑不会被抹掉</li>
 *   <li><b>陈旧的会被清掉</b>。事实撤了，那条违规就不该还挂在 Review 页上</li>
 * </ul>
 *
 * <p>tokio→JUnit 等价点：async 闭包体内联进测试方法；自建自拆（自己的 org/workspace/kb，
 * 跑完 DELETE 回收），绝不碰已有的库。
 */
class AxiomsJudgeTheLedgerTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID owns, UUID mentions, UUID a, UUID b) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID owns = Uuids.nowV7();
        UUID mentions = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'axioms-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'axioms-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'axioms-test')",
                kb, ws);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, is_asymmetric, is_irreflexive)
                 VALUES (?, ?, 'owns', 'owns', TRUE, TRUE)
                """, owns, kb);
        // 一位公理都没有——它的边永远不该被判成矛盾
        jdbc.update(
                "INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'mentions', 'mentions')",
                mentions, kb);
        for (Object[] row : new Object[][]{{a, "Acme"}, {b, "Beta"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, etype, row[1]);
        }
        return new Fixture(org, kb, owns, mentions, a, b);
    }

    /** 落一条关系事实，返回它的 id。 */
    private UUID fact(UUID kb, UUID s, UUID p, UUID o) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, kb, s, p, o);
        return id;
    }

    private List<String> openKinds(UUID kb) {
        return jdbc.queryForList(
                "SELECT kind FROM axiom_violations WHERE kb_id = ? AND status = 'open'"
                        + " ORDER BY kind",
                String.class, kb);
    }

    @Test
    void theOntologyIsTheOnlyJudge() {
        Fixture f = seed();
        try {
            // ---- 一、没有矛盾的库跑出来是零
            fact(f.kb(), f.a(), f.owns(), f.b());
            var quiet = reasoning.run(f.kb());
            assertEquals(1, quiet.edges());
            assertEquals(1, quiet.predicatesWithAxioms(), "mentions 一位都没声明,不该进来");
            assertEquals(0, quiet.found(), "一条单向的 owns 不构成任何矛盾");

            // ---- 二、公理说了才算数
            // B owns A —— 与上面那条构成反对称违规
            fact(f.kb(), f.b(), f.owns(), f.a());
            // A mentions B / B mentions A —— 双向,但 mentions 没有公理,不该报
            fact(f.kb(), f.a(), f.mentions(), f.b());
            fact(f.kb(), f.b(), f.mentions(), f.a());
            // A owns A —— 自反违规
            UUID loopFact = fact(f.kb(), f.a(), f.owns(), f.a());

            var r = reasoning.run(f.kb());
            assertEquals(5, r.edges());
            assertEquals(List.of("asymmetry", "self_loop"), openKinds(f.kb()),
                    "双向的 mentions 不该被报——它的谓词没有任何公理");
            assertEquals(2, r.inserted());

            // 自反那一条:两列指的是同一条事实
            var lr = jdbc.queryForMap(
                    "SELECT left_fact, right_fact FROM axiom_violations WHERE kb_id = ?"
                            + " AND kind = 'self_loop'",
                    f.kb());
            assertEquals(loopFact, lr.get("left_fact"));
            assertEquals(lr.get("left_fact"), lr.get("right_fact"), "一条事实跟自己矛盾,不需要第二条");

            // ---- 三、重跑不重复入库
            var again = reasoning.run(f.kb());
            assertEquals(2, again.found());
            assertEquals(0, again.inserted(), "同一处矛盾第二次跑不该再插一行");
            assertEquals(0, again.cleared(), "也不该把上一轮的清掉");

            // ---- 四、人表过态的,重跑不抹
            jdbc.update(
                    "UPDATE axiom_violations SET status = 'resolved', resolution = 'accepted'"
                            + " WHERE kb_id = ? AND kind = 'asymmetry'",
                    f.kb());
            var after = reasoning.run(f.kb());
            assertEquals(0, after.inserted(), "已经有人表态的那一行不该被重新插一条");
            Long resolved = jdbc.queryForObject(
                    "SELECT count(*) FROM axiom_violations WHERE kb_id = ? AND status = 'resolved'",
                    Long.class, f.kb());
            assertEquals(1L, resolved, "人的决定必须活过重跑");

            // ---- 五、事实撤了,陈旧的 open 行要清掉
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", loopFact);
            var swept = reasoning.run(f.kb());
            assertEquals(1, swept.cleared(), "被推翻的事实不该还挂着一条违规");
            assertFalse(openKinds(f.kb()).contains("self_loop"),
                    "撤掉那条事实之后自反违规就不成立了");

            // ---- 六、属性事实不参与:宾语是字面值,公理谈的是实体之间的关系
            jdbc.update(
                    "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value)"
                            + " VALUES (?, ?, ?, ?, '\"2015\"'::jsonb)",
                    Uuids.nowV7(), f.kb(), f.a(), f.owns());
            var attrs = reasoning.run(f.kb());
            assertEquals(4, attrs.edges(), "字面值宾语的那条不该被取成边");

            // ---- 七、没有本体包的库:结论是「没有判据」,不是「没有矛盾」
            jdbc.update(
                    "UPDATE relation_types SET is_asymmetric = FALSE, is_irreflexive = FALSE"
                            + " WHERE kb_id = ?",
                    f.kb());
            var blind = reasoning.run(f.kb());
            assertEquals(0, blind.predicatesWithAxioms());
            assertEquals(0, blind.found());
            assertTrue(openKinds(f.kb()).isEmpty(), "公理撤了,据它报出来的违规也该跟着走");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
