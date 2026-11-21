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
 * graph 域 HTTP 面重建（P1 任务 8；源 GraphApiTests 核心语义——MockMvc+LoginUser 重建）：
 * overview 四键与缺省 limit、上下钳位、坏 at 422 逐字文案、date/RFC3339 双形 as_of、
 * 极早 as_of 滤空、entities 搜索与空 q 先于 SQL、restricted 对 viewer 的 404 不泄露、
 * 无凭据 401。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphApiTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private UUID orgId;
    private UUID adminId;
    private UUID viewerId;
    private UUID wsId;
    private UUID kbId;

    @BeforeAll
    void fixture() {
        // 源仓注册流遗产自足化：清卷空库无 organizations 根行，测试自建（p3-report §五-1 固定 uuid）
        jdbc.update("""
                INSERT INTO organizations (id, name, created_at)
                VALUES ('0190d3e0-0000-7d5a-8000-0000000000f0', '默认组织', '2026-10-01T00:00:00Z')
                ON CONFLICT (id) DO NOTHING
                """);
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        adminId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Gr Api Admin', TRUE)",
                adminId, orgId, "t8-gr-" + RUN + "@test.local");
        viewerId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Gr Api Viewer')",
                viewerId, orgId, "t8-grv-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId,
                "ws-t8-gr-" + RUN);
        kbId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility) VALUES (?, ?, ?, 'restricted')",
                kbId, wsId, "kb-t8-gr-" + RUN);
        // admin 矩阵（restricted 对成员可见）+ 自播种实体图（共享库无演示数据也能跑）
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role) VALUES (?, ?, 'admin')", kbId, adminId);
        UUID rel = UUID.randomUUID();
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'employs', 'Employs')",
                rel, kbId);
        java.util.List<UUID> chain = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID e = Uuids.nowV7();
            chain.add(e);
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, created_at) "
                    + "VALUES (?, ?, ?, now())", e, kbId, (i == 0 ? "AGV Holdings" : "Node-" + i));
        }
        for (int i = 1; i < chain.size(); i++) {
            jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, recorded_at) "
                            + "VALUES (?, ?, ?, ?, ?, now())",
                    UUID.randomUUID(), kbId, chain.get(i - 1), rel, chain.get(i));
        }
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?, ?)",
                "t8-gr-" + RUN + "@test.local", "t8-grv-" + RUN + "@test.local");
    }

    @Test
    void overviewShapeAndTotalsOnRealData() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/graph/overview", adminId);
        assertEquals(200, resp.status(), resp.raw());
        assertTrue(resp.body().has("nodes") && resp.body().has("edges")
                && resp.body().has("total_nodes") && resp.body().has("total_edges"), "四键齐");
        assertEquals(12, resp.body().get("total_nodes").asLong(), "自播种的 12 实体");
        assertTrue(resp.body().get("nodes").size() <= 150, "缺省 limit=150");
        if (resp.body().get("nodes").size() > 0) {
            var node = resp.body().get("nodes").get(0);
            assertTrue(node.has("id") && node.has("name") && node.has("degree")
                    && node.has("color") && node.has("shape"), "节点字段集");
        }
    }

    @Test
    void limitClampedToFloorAndCeiling() throws Exception {
        assertEquals(10, get("/api/v1/kbs/" + kbId + "/graph/overview?limit=5", adminId)
                .body().get("nodes").size(), "limit=5 钳到 10（[10,1000]）");
        assertTrue(get("/api/v1/kbs/" + kbId + "/graph/overview?limit=999999", adminId)
                .body().get("nodes").size() <= 1000, "上限 1000");
    }

    @Test
    void badAtIs422WithCanonicalMessage() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/graph/overview?at=bogus", adminId);
        assertEquals(422, resp.status());
        assertEquals("Invalid `at` (expected YYYY-MM-DD or RFC3339)",
                resp.body().get("error").asText(), "Validation 文案逐字");
    }

    @Test
    void dateAtAndRfc3339AsOfAcceptedAndFarPastFilters() throws Exception {
        assertEquals(200, get("/api/v1/kbs/" + kbId + "/graph/overview?at=2026-11-01", adminId).status(),
                "YYYY-MM-DD");
        assertEquals(200, get("/api/v1/kbs/" + kbId + "/graph/overview?as_of=2026-11-01T10:30:00Z",
                adminId).status(), "RFC3339");
        long nowNodes = get("/api/v1/kbs/" + kbId + "/graph/overview", adminId)
                .body().get("total_nodes").asLong();
        Resp early = get("/api/v1/kbs/" + kbId + "/graph/overview?as_of=2000-01-01T00:00:00Z", adminId);
        assertEquals(200, early.status());
        long earlyNodes = early.body().get("total_nodes").asLong();
        assertTrue(earlyNodes == 0 || earlyNodes * 4 < nowNodes,
                "极早 as_of 节点空或显著小于 now（early=" + earlyNodes + " now=" + nowNodes + "）");
    }

    @Test
    void entitiesSearchAndEmptyQueryShape() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/entities?q=AGV&limit=5", adminId);
        assertEquals(200, resp.status());
        assertTrue(resp.body().get("total").asLong() > 0, "自播种实体搜得到");
        assertTrue(resp.body().get("entities").size() <= 5, "limit=5");
        Resp empty = get("/api/v1/kbs/" + kbId + "/entities?q=&limit=5", adminId);
        assertEquals(200, empty.status());
        assertEquals(0, empty.body().get("total").asLong(), "空 q 先于 SQL，回空形不炸");
    }

    @Test
    void restrictedKbHiddenFromViewerAs404() throws Exception {
        // viewer 不是 ws 成员：restricted 库的图面 404 不泄露存在性
        assertEquals(404, get("/api/v1/kbs/" + kbId + "/graph/overview", viewerId).status());
        assertEquals(200, get("/api/v1/kbs/" + kbId + "/graph/overview", adminId).status(),
                "owner 可见（自家 restricted）");
    }

    @Test
    void overviewWithoutCredentialsIs401() throws Exception {
        assertEquals(401, get("/api/v1/kbs/" + kbId + "/graph/overview", null).code(),
                "无凭据：若依 entry point（体 code 401）");
    }
}
