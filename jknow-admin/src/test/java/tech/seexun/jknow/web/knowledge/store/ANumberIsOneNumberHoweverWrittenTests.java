package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_number_is_one_number_however_written.rs（119 行）——同一条边上，
 * 先写 {@code 65}（老库里的整数）再写 {@code 65.0}：是同一个数，不是冲突
 * （qualifier_values_agree 的 as_f64 数值比较）。等价点：无（直译）。
 */
class ANumberIsOneNumberHoweverWrittenTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    OntologyService ontology;
    @Autowired
    GraphWriteService writes;

    @Test
    void a_number_is_one_number_however_written() throws Exception {
        UUID tag = Uuids.nowV7();
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, "num-" + tag);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org,
                "num-" + tag);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb,
                ws, "num-" + tag);
        UUID klass = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)", klass,
                kb, "org", "Org");
        try {
            UUID stake = ontology.createRelationType(kb, "stake", "stake", "state",
                    OntologyService.RelationAxioms.empty(), "", "attribute", List.of(klass),
                    List.of(), "number", "%");
            UUID holds = ontology.createRelationType(kb, "holds_stake_in", "holds stake in",
                    "state", OntologyService.RelationAxioms.empty(), "", "relation", List.of(),
                    List.of(), null, null);
            UUID a = Uuids.nowV7();
            UUID b = Uuids.nowV7();
            for (Object[] e : new Object[][]{{a, "澜图科技-" + tag}, {b, "澜图新材料-" + tag}}) {
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                        e[0], kb, klass, e[1]);
            }
            var fact = writes.insertFact(kb, a, holds, b, Validity.empty(), 0.9f);

            var asInt = JSON.readTree("{ \"value\": 65, \"unit\": \"%\" }");
            var asFloat = JSON.readTree("{ \"value\": 65.0, \"unit\": \"%\" }");
            var other = JSON.readTree("{ \"value\": 60.0, \"unit\": \"%\" }");
            assertEquals(GraphWriteService.QualifierWrite.SET,
                    writes.upsertFactQualifier(fact.id(), stake, asInt));
            assertEquals(GraphWriteService.QualifierWrite.SAME,
                    writes.upsertFactQualifier(fact.id(), stake, asFloat),
                    "65 与 65.0 是同一个数");
            assertEquals(GraphWriteService.QualifierWrite.CONFLICT,
                    writes.upsertFactQualifier(fact.id(), stake, other),
                    "60 才是不一样的数");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
