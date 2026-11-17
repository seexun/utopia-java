package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.RelationAxioms;
import tech.seexun.jknow.store.ReasoningService;

/**
 * 移植自 tests/store/an_axiom_that_moved_closes_its_findings.rs（707 行，8 例）——判据一动，
 * 靠着它的 open 违规就得当场对账（#564 / 0062）。
 *
 * <p>{@code axiom_violations.status = 'open'} 的承诺是「已对账于<b>当前</b>本体」。
 * 从前 update_relation_type 只管写：放宽一条公理，上一轮检出的 open 行原样留着——
 * 下一次导出会把一个只在旧判据下成立的发现说成是现在的。这里钉的是同一条事务里
 * 做完的三件事：
 * <ol>
 *   <li><b>旧判据下的检出先记下来</b>——动笔之前库里还是旧本体</li>
 *   <li><b>写完后按新本体重检出</b>——新的违规同一份提交里进来</li>
 *   <li><b>只在旧判据下成立的 open 行收 criterion_changed</b>——不是人裁的
 *       （decided_by 空），是公理/签名换了；早就不成立的照旧删掉，与 run 同一条清陈规矩</li>
 * </ol>
 *
 * <p>没动判据的编辑（label、描述）不跑检测——那是对账不必付的价。
 *
 * <p>第 5/7 例（deleting_a_linked_relation / unlinking_a_sub_property）断言面在
 * rules_disagree 缺陷——派生引擎（sub_property_of 推导 + functional 互撞）随任务 6
 * 落地，测试体与断言全量移植，seam 抛出 @Disabled 待任务 6（断言一字不动）。
 */
class AnAxiomThatMovedClosesItsFindingsTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    @Autowired
    OntologyService ontology;

    record Fixture(UUID org, UUID kb, UUID thing, UUID person, UUID reportsTo, UUID worksFor) {
    }

    private static final RelationAxioms NONE =
            new RelationAxioms(false, false, false, false, false, false, null, null);

    /** asymmetric=true（RelationAxioms 位序：functional/inverse/transitive/symmetric/asymmetric/irreflexive）。 */
    private static RelationAxioms ax() {
        return new RelationAxioms(false, false, false, false, true, false, null, null);
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID thing = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID reportsTo = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'moved-axiom')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'moved-axiom')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'moved-axiom')",
                kb, ws);
        jdbc.update("""
                INSERT INTO entity_types (id, kb_id, key, label) VALUES
                    (?, ?, 'thing', 'Thing'), (?, ?, 'person', 'Person')
                """, thing, kb, person, kb);
        jdbc.update("""
                INSERT INTO relation_types
                     (id, kb_id, key, label, temporal, is_asymmetric)
                 VALUES (?, ?, 'reports_to', 'reportsTo', 'state', TRUE)
                """, reportsTo, kb);
        jdbc.update(
                "INSERT INTO relation_types (id, kb_id, key, label, temporal)"
                        + " VALUES (?, ?, 'works_for', 'worksFor', 'state')",
                worksFor, kb);
        jdbc.update(
                "INSERT INTO relation_type_domains (relation_type_id, entity_type_id)"
                        + " VALUES (?, ?)",
                worksFor, person);
        return new Fixture(org, kb, thing, person, reportsTo, worksFor);
    }

    private UUID entity(Fixture f, UUID ty, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), ty, name);
        return id;
    }

    private UUID fact(Fixture f, UUID s, UUID p, UUID o) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, f.kb(), s, p, o);
        return id;
    }

    /** 某一行的现状：(status, resolution, decidedAt 非空)。 */
    record RowState(String status, String resolution, boolean decided) {
    }

    private RowState rowState(UUID id) {
        List<RowState> rows = jdbc.query(
                "SELECT status, resolution, decided_at IS NOT NULL FROM axiom_violations"
                        + " WHERE id = ?",
                (rs, i) -> new RowState(rs.getString(1), rs.getString(2), rs.getBoolean(3)), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long openCount(Fixture f) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM axiom_violations WHERE kb_id = ? AND status = 'open'",
                Long.class, f.kb());
        return n == null ? 0 : n;
    }

    /** 放宽一条公理：open 行收 criterion_changed，不是删也不是继续开着；把公理改回去，
     *  同一行重开——criterion_changed 是破约家族的一员。 */
    @Test
    void relaxingAnAxiomClosesItsFindingAndReinstatingReopensIt() {
        Fixture f = seed();
        try {
            UUID a = entity(f, f.thing(), "A");
            UUID b = entity(f, f.thing(), "B");
            fact(f, a, f.reportsTo(), b);
            fact(f, b, f.reportsTo(), a);
            var r = reasoning.run(f.kb());
            assertTrue(r.found() == 1, "expected one asymmetry finding, got " + r);
            UUID id = jdbc.queryForObject(
                    "SELECT id FROM axiom_violations WHERE kb_id = ? AND status = 'open'",
                    UUID.class, f.kb());

            // 改判据：asymmetric 关掉。同一事务里这行该被收编成 criterion_changed
            ontology.updateRelationType(f.kb(), f.reportsTo(), "reportsTo", "state", NONE, "",
                    null, null, null, null);
            assertTrue(openCount(f) == 0, "open finding outlived its axiom");
            RowState state = rowState(id);
            assertNotNull(state);
            assertTrue("resolved".equals(state.status())
                    && "criterion_changed".equals(state.resolution()),
                    "finding was not closed as criterion_changed: " + state);
            assertTrue(state.decided(), "decided_at should mark when it stopped being open");

            // 改回来：同一处矛盾又成立，同一行重开——不是另插一行
            ontology.updateRelationType(f.kb(), f.reportsTo(), "reportsTo", "state", ax(), "",
                    null, null, null, null);
            RowState back = rowState(id);
            assertNotNull(back);
            assertTrue("open".equals(back.status()) && back.resolution() == null,
                    "finding should reopen under the restored axiom, got " + back);
            assertTrue(openCount(f) == 1);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** domain 换了签名就换：原来开着的 signature 违规按新判据收场。 */
    @Test
    void aDomainChangeClosesTheSignatureFinding() {
        Fixture f = seed();
        try {
            // works_for domain=person，而一个 thing 断言了它——signature 违规
            UUID acme = entity(f, f.thing(), "Acme");
            UUID alice = entity(f, f.thing(), "Alice");
            fact(f, alice, f.worksFor(), acme);
            var r = reasoning.run(f.kb());
            assertEquals(1, r.found());
            String kind = jdbc.queryForObject(
                    "SELECT kind FROM axiom_violations WHERE kb_id = ? AND status = 'open'"
                            + " LIMIT 1",
                    String.class, f.kb());
            assertEquals("signature", kind, "expected one signature finding");

            // domain 放宽到 thing：判据没了，这行收 criterion_changed
            ontology.updateRelationType(f.kb(), f.worksFor(), "worksFor", "state", NONE, "",
                    null, null, List.of(f.thing()), null);
            assertTrue(openCount(f) == 0, "signature finding outlived its domain");
            var state = jdbc.queryForMap(
                    "SELECT status, resolution, decided_at IS NOT NULL AS decided"
                            + " FROM axiom_violations WHERE kb_id = ? AND kind = 'signature'",
                    f.kb());
            assertEquals("resolved", state.get("status"));
            assertEquals("criterion_changed", state.get("resolution"));
            assertEquals(Boolean.TRUE, state.get("decided"));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 不动判据的编辑不跑对账：一条只在旧检测里才「成立过」的 open 行，换 label 不碰它，
     *  真改判据时按早就陈掉的处置——删掉，不是 criterion_changed。 */
    @Test
    void aLabelEditLeavesTheLedgerAlone() {
        Fixture f = seed();
        try {
            UUID a = entity(f, f.thing(), "A");
            UUID b = entity(f, f.thing(), "B");
            UUID f1 = fact(f, a, f.worksFor(), b);
            // 一行检出函数永远不会产出的 open 行（自环挂着 functional 键）：
            // 它早就陈了，下一次对账该删掉而不是收编
            UUID stray = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO axiom_violations (id, kb_id, kind, left_fact, right_fact)
                     VALUES (?, ?, 'functional', ?, ?)
                    """, stray, f.kb(), f1, f1);

            // 只改 label：判据没动，不跑检测——陈行原样留着（检出是有代价的，
            // 不该为一次改名付）
            ontology.updateRelationType(f.kb(), f.worksFor(), "employs", "state", NONE, "",
                    null, null, null, null);
            RowState afterLabel = rowState(stray);
            assertNotNull(afterLabel);
            assertEquals("open", afterLabel.status(),
                    "a label-only edit must not touch violation state");

            // 真改判据（domain 从 person 换成 thing）：这行不在旧基线里
            //（检出从没产出过它）——删掉，与 run() 的清陈同一条规矩
            ontology.updateRelationType(f.kb(), f.worksFor(), "employs", "state", NONE, "",
                    null, null, List.of(f.thing()), null);
            assertNull(rowState(stray),
                    "a stale row that never held under the old ontology should be deleted,"
                            + " not adjudicated");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 父链也是判据：给类挂上父类后签名对上了，open 违规当场收编。 */
    @Test
    void aNewParentClosesTheSignatureFinding() {
        Fixture f = seed();
        try {
            // works_for domain=person；thing ⊏ person——thing 断言它算违规
            UUID acme = entity(f, f.thing(), "Acme");
            UUID alice = entity(f, f.thing(), "Alice");
            fact(f, alice, f.worksFor(), acme);
            reasoning.run(f.kb());
            assertTrue(openCount(f) == 1);

            // 给 thing 挂上 person 当父类：签名沿祖先链判，这就对上了
            ontology.updateEntityType(f.kb(), f.thing(), "Thing", null, "circle",
                    List.of(f.person()), "");
            assertTrue(openCount(f) == 0,
                    "signature finding should close once the parent makes the signature hold");
            var state = jdbc.queryForMap(
                    "SELECT status, resolution FROM axiom_violations WHERE kb_id = ?"
                            + " AND kind = 'signature'",
                    f.kb());
            assertEquals("resolved", state.get("status"));
            assertEquals("criterion_changed", state.get("resolution"));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /**
     * 任务 6 已去封：rules_disagree 缺陷（supervises ⊑ manages 派生出两条 manages 撞
     * functional，按规则对进 ontology_defects）——reasoning.run 走 DerivedPoolProvider
     * 真身（与 materialize 同一次求解），断言一字未动。
     */
    @Test
    void deletingALinkedRelationReconcilesWhatDependedOnIt() {
        Fixture f = seed();
        try {
            // manages 是 functional 的超属性：supervises ⊑ manages——零断言，
            // 只活在派生链上，所以它能被删
            UUID manages = Uuids.nowV7();
            UUID supervises = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, functional)
                     VALUES (?, ?, 'manages', 'manages', 'state', TRUE)
                    """, manages, f.kb());
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, sub_property_of)
                     VALUES (?, ?, 'supervises', 'supervises', 'state', ?)
                    """, supervises, f.kb(), manages);
            UUID alice = entity(f, f.thing(), "Alice");
            UUID carol = entity(f, f.thing(), "Carol");
            UUID dave = entity(f, f.thing(), "Dave");
            fact(f, alice, supervises, carol);
            fact(f, alice, supervises, dave);
            reasoning.run(f.kb());
            // supervises ⊑ manages 派生出 manages(alice→carol) 与 manages(alice→dave)：
            // 两条派生撞上 functional——规则对层的发现进 ontology_defects
            Long defects = jdbc.queryForObject(
                    "SELECT count(*) FROM ontology_defects WHERE kb_id = ?"
                            + " AND kind = 'rules_disagree'",
                    Long.class, f.kb());
            assertTrue(defects != null && defects == 1,
                    "expected one rules_disagree defect, got " + defects);

            // manages 零断言可以删：先断 supervises 的链再删再对账——发现随之消失，
            // 不留「一条只对已删判据成立的发现」走出提交点
            ontology.deleteRelationType(f.kb(), manages);
            Long left = jdbc.queryForObject(
                    "SELECT count(*) FROM ontology_defects WHERE kb_id = ?", Long.class, f.kb());
            assertTrue(left != null && left == 0, "defect survived the deletion of its criterion");
            Long dangling = jdbc.queryForObject(
                    "SELECT count(*) FROM relation_types WHERE sub_property_of = ?",
                    Long.class, manages);
            assertTrue(dangling != null && dangling == 0,
                    "deferred SET NULL would have hidden the link");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 证据漂移不是身份漂移：行里留的 path 是上一轮拍下的组员集，检出重算时它可以变——
     *  分档比的是 (kind, left, right)，不是那张旧照片。一条旧判据下确实检得出的违规，
     *  不能因为存的证据换了组就当成「早就陈了」删掉。 */
    @Test
    void driftedEvidenceStillClosesAsCriterionChanged() {
        Fixture f = seed();
        try {
            UUID a = entity(f, f.thing(), "A");
            UUID b = entity(f, f.thing(), "B");
            UUID c = entity(f, f.thing(), "C");
            UUID f1 = fact(f, a, f.reportsTo(), b);
            fact(f, b, f.reportsTo(), a);
            UUID f3 = fact(f, a, f.reportsTo(), c);
            reasoning.run(f.kb());
            UUID id = jdbc.queryForObject(
                    "SELECT id FROM axiom_violations WHERE kb_id = ? AND status = 'open'",
                    UUID.class, f.kb());

            // 模拟上一轮检出留下的旧证据：path 组员漂移过，端点没变——
            // 这还是同一处违规（0054：非环种类按 (kind,left,right) 认身份）
            jdbc.update("UPDATE axiom_violations SET path = ?::uuid[] WHERE id = ?",
                    "{" + f1 + "," + f3 + "}", id);

            ontology.updateRelationType(f.kb(), f.reportsTo(), "reportsTo", "state", NONE, "",
                    null, null, null, null);
            RowState state = rowState(id);
            assertNotNull(state);
            assertTrue("resolved".equals(state.status())
                    && "criterion_changed".equals(state.resolution()),
                    "evidence drift must not turn a baseline finding into a deletion: " + state);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /**
     * 任务 6 已去封（同上：摘掉 sub_property_of 断派生链，缺陷行随提交清掉）。
     */
    @Test
    void unlinkingASubPropertyReconcilesWhatDependedOnIt() {
        Fixture f = seed();
        try {
            UUID manages = Uuids.nowV7();
            UUID supervises = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, functional)
                     VALUES (?, ?, 'manages', 'manages', 'state', TRUE)
                    """, manages, f.kb());
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal, sub_property_of)
                     VALUES (?, ?, 'supervises', 'supervises', 'state', ?)
                    """, supervises, f.kb(), manages);
            UUID alice = entity(f, f.thing(), "Alice");
            UUID carol = entity(f, f.thing(), "Carol");
            UUID dave = entity(f, f.thing(), "Dave");
            fact(f, alice, supervises, carol);
            fact(f, alice, supervises, dave);
            reasoning.run(f.kb());
            Long defects = jdbc.queryForObject(
                    "SELECT count(*) FROM ontology_defects WHERE kb_id = ?"
                            + " AND kind = 'rules_disagree'",
                    Long.class, f.kb());
            assertTrue(defects != null && defects == 1, "expected one rules_disagree defect");

            // 摘掉 supervises ⊑ manages：判据变了，派生链断——发现随提交一起清
            ontology.updateRelationType(f.kb(), supervises, "supervises", "state", NONE, "",
                    null, null, null, null);
            Long left = jdbc.queryForObject(
                    "SELECT count(*) FROM ontology_defects WHERE kb_id = ?", Long.class, f.kb());
            assertTrue(left != null && left == 0,
                    "defect survived the unlinking of its derivation chain");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 删掉一个牵着判据的类，同一事务里把靠着它的发现收掉。 */
    @Test
    void deletingADomainClassClosesTheSignatureFinding() {
        Fixture f = seed();
        try {
            UUID acme = entity(f, f.thing(), "Acme");
            UUID alice = entity(f, f.thing(), "Alice");
            fact(f, alice, f.worksFor(), acme);
            reasoning.run(f.kb());
            assertTrue(openCount(f) == 1);

            // person 是 works_for 的 domain：删掉它，判据跟着没了
            ontology.deleteEntityType(f.kb(), f.person());
            assertTrue(openCount(f) == 0, "finding should close when its domain class is deleted");
            var state = jdbc.queryForMap(
                    "SELECT status, resolution FROM axiom_violations WHERE kb_id = ?"
                            + " AND kind = 'signature'",
                    f.kb());
            assertEquals("resolved", state.get("status"));
            assertEquals("criterion_changed", state.get("resolution"));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
