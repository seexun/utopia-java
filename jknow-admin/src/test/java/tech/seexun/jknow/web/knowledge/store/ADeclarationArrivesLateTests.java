package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.TemporalService.UniquenessCandidate;

/**
 * 声明来晚了：本体自己长出来的库里，接任不会闭合前任（a_declaration_arrives_late.rs
 * 原值移植，#341——M5 任务 6 台账收口）。钉住三样：
 * <ul>
 *   <li><b>候选是算出来的，不是猜出来的</b>——同一端挂着两个以上开放值的持有者才算；
 *     每人各管一个项目的那一端不报；</li>
 *   <li><b>补上声明之后能对账</b>——按年表走：三条开放事实闭合成一条链，前任闭合在
 *     <b>最早的</b>后任起点上，与摄入顺序无关；再跑一遍不动；</li>
 *   <li><b>没声明就不动</b>——对账接口拒绝，引擎不替人推断。</li>
 * </ul>
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ADeclarationArrivesLateTests extends StoreTestBase {

    @Autowired
    TemporalService temporal;

    private UUID org;
    private UUID kb;
    private UUID leads;
    private UUID salary;
    private UUID aurora;
    private UUID zhang;
    private UUID li;
    private UUID zhou;
    private UUID lin;

    private static OffsetDateTime day(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        leads = Uuids.nowV7();
        salary = Uuids.nowV7();
        aurora = Uuids.nowV7();
        zhang = Uuids.nowV7();
        li = Uuids.nowV7();
        zhou = Uuids.nowV7();
        lin = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'late-declaration-test')",
                org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name)"
                + " VALUES (?, ?, 'late-declaration-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                + " VALUES (?, ?, 'late-declaration-test')", kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label)"
                + " VALUES (?, ?, 'thing', 'Thing')", etype, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label)"
                + " VALUES (?, ?, 'leads', 'leads')", leads, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, unit)"
                + " VALUES (?, ?, 'salary', 'salary', 'attribute', 'number', 'CNY')",
                salary, kb);
        for (Object[] row : new Object[][]{{aurora, "Project Aurora"}, {zhang, "Zhang San"},
                {li, "Li Si"}, {zhou, "Zhou Qi"}, {lin, "Lin Zhao"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name)"
                    + " VALUES (?, ?, ?, ?)", row[0], kb, etype, row[1]);
        }
    }

    @AfterAll
    void cleanup() {
        // 先删库再删 org：pending_facts.proposed_by 之类不级联到 org
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    private UUID edge(UUID s, UUID p, UUID o, OffsetDateTime from) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                   valid_from, valid_from_precision)
                 VALUES (?, ?, ?, ?, ?, ?, 'day')
                """, id, kb, s, p, o, from);
        return id;
    }

    private UUID attr(UUID s, UUID p, String value, String unit, OffsetDateTime from) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value,
                                   valid_from, valid_from_precision)
                 VALUES (?, ?, ?, ?, ?::jsonb, ?, 'day')
                """, id, kb, s, p, "{\"value\": " + value + ", \"unit\": \"" + unit + "\"}",
                from);
        return id;
    }

    /** 一条谓词上还活着的事实：(主语名, 起点, 终点)，按起点排。 */
    private List<Object[]> live(UUID p) {
        return jdbc.query("""
                SELECT e.canonical_name, f.valid_from, f.valid_to
                  FROM facts f JOIN entities e ON e.id = f.subject_id
                 WHERE f.kb_id = ? AND f.predicate_id = ? AND f.invalidated_at IS NULL
                 ORDER BY f.valid_from
                """, (rs, i) -> new Object[]{rs.getString(1),
                rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class)},
                kb, p);
    }

    @Test
    void aSuccessionClosesOnceSomeoneDeclaresIt() {
        // 摄入顺序故意打乱：李四先到，周七次之，张三最后——年表与摄入顺序不同
        edge(li, leads, aurora, day(2024, 7, 5));
        edge(zhou, leads, aurora, day(2025, 9, 1));
        edge(zhang, leads, aurora, day(2023, 2, 1));
        attr(lin, salary, "28000", "CNY", day(2023, 6, 1));
        attr(lin, salary, "32000", "CNY", day(2024, 2, 20));

        // —— 候选 ——
        List<UniquenessCandidate> cands = temporal.uniquenessCandidates(kb);
        List<UniquenessCandidate> onLeads = cands.stream()
                .filter(c -> c.predicateId().equals(leads)).toList();
        assertEquals(1, onLeads.size(), "每人各管一个项目：主语侧不该报");
        UniquenessCandidate leadsCand = onLeads.get(0);
        assertEquals("object", leadsCand.side());
        assertTrue(!leadsCand.declared());
        assertEquals(1, leadsCand.holders());
        assertEquals(3, leadsCand.openFacts());
        assertEquals(2, leadsCand.wouldClose());
        assertEquals(0, leadsCand.wouldReview());
        var ex = leadsCand.examples().get(0);
        assertEquals("Project Aurora", ex.holder());
        assertEquals(List.of("Zhang San", "Li Si", "Zhou Qi"),
                ex.values().stream().map(v -> v.name()).toList(),
                "例子按年表排，不按摄入顺序");
        UniquenessCandidate salaryCand = cands.stream()
                .filter(c -> c.predicateId().equals(salary)).findFirst()
                .orElseThrow(() -> new AssertionError("两份开放的薪资是主语侧的候选"));
        assertEquals("subject", salaryCand.side());
        assertEquals(1, salaryCand.holders());
        assertEquals(2, salaryCand.openFacts());
        assertEquals(1, salaryCand.wouldClose());
        assertEquals("28000 CNY", salaryCand.examples().get(0).values().get(0).name(),
                "字面值带单位");

        // —— 没声明就不动 ——
        assertThrows(AppException.class, () -> temporal.reconcilePredicate(kb, leads),
                "没有唯一性声明的谓词不能对账：引擎不替人推断");
        assertEquals(3, live(leads).size());

        // —— 声明宾语侧唯一，对账 ——
        jdbc.update("UPDATE relation_types SET inverse_functional = TRUE WHERE id = ?", leads);
        var r = temporal.reconcilePredicate(kb, leads);
        assertEquals(2, r.corrected.size());
        assertEquals(0, r.conflicts);
        List<Object[]> after = live(leads);
        assertEquals(3, after.size());
        assertEquals("Zhang San", after.get(0)[0]);
        assertEquals(day(2023, 2, 1), after.get(0)[1]);
        assertEquals(day(2024, 7, 5), after.get(0)[2]);
        assertEquals("Li Si", after.get(1)[0]);
        assertEquals(day(2025, 9, 1), after.get(1)[2]);
        assertEquals("Zhou Qi", after.get(2)[0]);
        assertEquals(null, after.get(2)[2]);
        // 被改写的原行还在，记录轴倒回去看得见
        assertEquals(2L, jdbc.queryForObject("""
                SELECT count(*) FROM facts
                 WHERE kb_id = ? AND predicate_id = ? AND invalidated_at IS NOT NULL
                """, Long.class, kb, leads),
                "前任闭合在最早的后任起点上：张三止于李四，李四止于周七");

        // 再跑一遍不动；候选里也不再有它
        var again = temporal.reconcilePredicate(kb, leads);
        assertTrue(again.corrected.isEmpty() && again.conflicts == 0);
        assertTrue(temporal.uniquenessCandidates(kb).stream()
                .noneMatch(c -> c.predicateId().equals(leads)));

        // —— 属性走主语侧 ——
        jdbc.update("UPDATE relation_types SET functional = TRUE WHERE id = ?", salary);
        var rs = temporal.reconcilePredicate(kb, salary);
        assertEquals(1, rs.corrected.size());
        assertEquals(0, rs.conflicts);
        OffsetDateTime closed = jdbc.queryForObject("""
                SELECT valid_to FROM facts
                 WHERE kb_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                   AND object_value->>'value' = '28000'
                """, OffsetDateTime.class, kb, salary);
        assertEquals(day(2024, 2, 20), closed, "旧薪资止于新薪资的起点");
    }
}
