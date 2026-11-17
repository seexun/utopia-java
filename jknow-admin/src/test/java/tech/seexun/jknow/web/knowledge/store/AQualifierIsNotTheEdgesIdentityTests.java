package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyService;

/**
 * 移植自 tests/store/a_qualifier_is_not_the_edges_identity.rs（184 行，0037）——边上的
 * 属性不进边的身份：同一条边再听到一次带了金额的，是同一条边补上金额；两次金额打架，
 * 先写者留着、报冲突，不覆盖。声明那一侧：只有本库的属性能当限定项，自己不行。
 *
 * <p>等价点：upsert_fact_qualifier/fact_qualifiers_for 走任务 2 的 GraphWriteService
 * （graph.rs 逐字）；set/add_relation_qualifiers 走本任务落的 OntologyService。
 */
class AQualifierIsNotTheEdgesIdentityTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    OntologyService ontology;
    @Autowired
    GraphWriteService writes;

    @Test
    void a_qualifier_is_added_to_the_same_edge_and_never_overwritten() throws Exception {
        UUID tag = Uuids.nowV7();
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, "qual-" + tag);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org,
                "qual-" + tag);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb,
                ws, "qual-" + tag);
        UUID klass = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Org')",
                klass, kb);
        try {
            // 一个属性定义（金额）、一条关系（投资）
            UUID amount = ontology.createRelationType(kb, "amount", "amount", "state",
                    OntologyService.RelationAxioms.empty(), "", "attribute", List.of(klass),
                    List.of(), "number", "$");
            UUID invested = ontology.createRelationType(kb, "invested_in", "invested in", "event",
                    OntologyService.RelationAxioms.empty(), "", "relation", List.of(), List.of(),
                    null, null);

            // 声明：属性能当限定项；关系不能；自己不能
            ontology.setRelationQualifiers(kb, invested, List.of(amount));
            var declared = ontology.relationTypes(kb).stream()
                    .filter(r -> r.id().equals(invested))
                    .map(r -> r.qualifiers())
                    .findFirst().orElse(List.of());
            assertEquals(List.of(amount), declared, "声明要能从关系上读回来");
            assertThrows(AppException.class,
                    () -> ontology.setRelationQualifiers(kb, invested, List.of(invested)),
                    "关系不能把自己声明成自己的属性");
            UUID otherRel = ontology.createRelationType(kb, "owns", "owns", "state",
                    OntologyService.RelationAxioms.empty(), "", "relation", List.of(), List.of(),
                    null, null);
            assertThrows(AppException.class,
                    () -> ontology.setRelationQualifiers(kb, invested, List.of(otherRel)),
                    "只有属性能当限定项");

            // 追加式声明：只加不删、撞上已有的不报错、同样的校验
            ontology.addRelationQualifier(kb, invested, amount);
            ontology.addRelationQualifier(kb, invested, amount);
            var again = ontology.relationTypes(kb).stream()
                    .filter(r -> r.id().equals(invested))
                    .map(r -> r.qualifiers())
                    .findFirst().orElse(List.of());
            assertEquals(List.of(amount), again, "追加同一个不重复、不覆盖");
            assertThrows(AppException.class,
                    () -> ontology.addRelationQualifier(kb, invested, otherRel),
                    "追加式同样只认属性");

            // 一条边
            UUID a = Uuids.nowV7();
            UUID b = Uuids.nowV7();
            UUID fact = Uuids.nowV7();
            for (Object[] e : new Object[][]{{a, "Vega-" + tag}, {b, "Northwind-" + tag}}) {
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                        e[0], kb, klass, e[1]);
            }
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, fact, kb, a, invested, b);

            // 第一次：写上；第二次同值：什么都不做；第三次不同值：报冲突、不覆盖
            var five = JSON.readTree("{ \"value\": 5000000000.0, \"unit\": \"$\" }");
            var twenty = JSON.readTree("{ \"value\": 20000000000.0, \"unit\": \"$\" }");
            assertEquals(GraphWriteService.QualifierWrite.SET,
                    writes.upsertFactQualifier(fact, amount, five));
            assertEquals(GraphWriteService.QualifierWrite.SAME,
                    writes.upsertFactQualifier(fact, amount, five));
            assertEquals(GraphWriteService.QualifierWrite.CONFLICT,
                    writes.upsertFactQualifier(fact, amount, twenty));
            var read = writes.factQualifiersFor(List.of(fact));
            var q = read.get(fact);
            assertEquals(1, q.size());
            assertEquals("amount", q.get(0).key());
            assertEquals(five, q.get(0).value(), "打架时先写者留着，不覆盖");
            assertTrue(q.get(0).entityId() == null);
        } finally {
            // 收拾：库删了，级联带走全部
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
