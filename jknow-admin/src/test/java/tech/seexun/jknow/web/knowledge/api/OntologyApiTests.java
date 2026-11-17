package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * ontology HTTP 面重建（P1 任务 8；源 OntologyApiTests+OntologyReadFamilyApiTests 核心
 * 语义合并——MockMvc+LoginUser 重建）：双视图字段集、实例分页钳位、未知类空形、
 * 401/404 面 + 读族（uniqueness/questions/rules 的形状主路径）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OntologyApiTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private UUID orgId;
    private UUID adminId;
    private UUID wsId;
    private UUID kbId;
    private UUID orgType;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        adminId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Onto Api Admin', TRUE)",
                adminId, orgId, "t8-onto-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId,
                "ws-t8-onto-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')",
                adminId, wsId);
        kbId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                kbId, wsId, "kb-t8-onto-" + RUN);
        // 自播种本体视图数据（共享库无演示数据也能跑）：两个类 + 三实例
        orgType = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, description) "
                + "VALUES (?, ?, 'organization', 'Organization', 'ORG desc')", orgType, kbId);
        UUID personType = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                personType, kbId);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'employs', 'Employs')",
                Uuids.nowV7(), kbId);
        for (String name : new String[]{"Acme", "Beta", "Gamma"}) {
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, type_id, created_at) "
                    + "VALUES (?, ?, ?, ?, now())", Uuids.nowV7(), kbId, name, orgType);
        }
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-onto-" + RUN + "@test.local");
    }

    @Test
    void ontologyViewsShapeOnRealData() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/ontology", adminId);
        assertEquals(200, resp.status(), resp.raw());
        assertTrue(resp.body().has("entity_types") && resp.body().has("relation_types"), "两视图键");
        assertTrue(resp.body().get("entity_types").size() >= 2, "自播种的类");
        var et = resp.body().get("entity_types").get(0);
        for (String f : new String[]{"id", "key", "label", "color", "shape", "builtin", "parents",
                "primary_parent", "disjoint", "description", "usage"}) {
            assertTrue(et.has(f), "entity_types 字段集含 " + f);
        }
        var rt = resp.body().get("relation_types").get(0);
        for (String f : new String[]{"id", "key", "label", "temporal", "functional",
                "inverse_functional", "is_transitive", "is_symmetric", "is_asymmetric",
                "is_irreflexive", "inverse_of", "sub_property_of", "builtin", "description",
                "kind", "domains", "ranges", "qualifiers", "datatype", "unit", "usage"}) {
            assertTrue(rt.has(f), "relation_types 字段集含 " + f);
        }
        assertTrue(rt.get("usage").asLong() >= 0);
    }

    @Test
    void instancesClampPerPage() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId
                + "/ontology/entity-types/" + orgType + "/entities?page=0&per=0", adminId);
        assertEquals(200, resp.status(), resp.raw());
        assertEquals(1, resp.body().get("entities").size(), "per=0 钳到 1");
        var row = resp.body().get("entities").get(0);
        assertTrue(row.has("id") && row.has("name") && row.has("fact_count"), "实例字段集");
        assertEquals(3, resp.body().get("total").asLong(), "自播种的 3 实例");
    }

    @Test
    void unknownTypeInstancesAreEmptyShape() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/ontology/entity-types/"
                + UUID.randomUUID() + "/entities?page=0&per=10", adminId);
        assertEquals(200, resp.status());
        assertEquals(0, resp.body().get("entities").size(), "未知类 → 空形不炸");
        assertEquals(0, resp.body().get("total").asLong());
    }

    @Test
    void readFamilyShapesOnFreshKb() throws Exception {
        // uniqueness / questions / rules / imports：新库的空形主路径
        Resp uniqueness = get("/api/v1/kbs/" + kbId + "/ontology/uniqueness", adminId);
        assertEquals(200, uniqueness.status(), uniqueness.raw());
        assertTrue(uniqueness.body().has("candidates"), "uniqueness 响应形（candidates）");
        Resp questions = get("/api/v1/kbs/" + kbId + "/questions", adminId);
        assertEquals(200, questions.status());
        assertTrue(questions.body().isArray() || questions.body().has("questions"), "questions 响应形");
        Resp rules = get("/api/v1/kbs/" + kbId + "/rules", adminId);
        assertEquals(200, rules.status());
        assertTrue(rules.body().isArray() || rules.body().has("rules"), "rules 响应形");
        Resp imports = get("/api/v1/kbs/" + kbId + "/ontology/imports", adminId);
        assertEquals(200, imports.status());
        assertTrue(imports.body().has("imports"), "imports 响应形");
    }

    @Test
    void ontologyWithoutCredentialsIs401() throws Exception {
        assertEquals(401, get("/api/v1/kbs/" + kbId + "/ontology", null).code(), "无凭据拒之门外");
    }

    @Test
    void unknownKbIs404() throws Exception {
        assertEquals(404, get("/api/v1/kbs/" + UUID.randomUUID() + "/ontology", adminId).status());
    }
}
