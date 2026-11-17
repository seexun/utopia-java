package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * 六域读族端点 HTTP 面重建（P1 任务 8；源 SixDomainApiTests 核心语义——MockMvc+LoginUser
 * 重建，断言精简）：workspace-kb 族（workspaces/kbs/my-kbs/members/audit/readiness/
 * settings 封印布尔/packs）、admin 403 判定面与 GET /users、PAT 列表、告警分组/unread、
 * documents 页查与 404/410 族、mappings 面、会话 404、SSE 三端点无凭据拒绝、chat 无模型
 * 422。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SixDomainApiTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "t8-six-" + RUN + "@test.local";
    private static final String EMAIL2 = "t8-six2-" + RUN + "@test.local";

    private UUID orgId;
    private UUID adminId;
    private UUID userId;
    private UUID wsId;
    private UUID kbId;
    private UUID kbOther;
    private UUID docId;
    private UUID docPurged;
    private UUID docTomb;
    private UUID wsBareId;
    private UUID kbBareId;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        adminId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Six Admin', TRUE)", adminId, orgId, EMAIL);
        userId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Six User')", userId, orgId, EMAIL2);
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId,
                "ws-t8-six-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')",
                adminId, wsId);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'viewer')",
                userId, wsId);
        wsBareId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsBareId, orgId,
                "ws-t8-bare-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')",
                adminId, wsBareId);
        kbBareId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                kbBareId, wsBareId, "kb-t8-bare-" + RUN);
        kbId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                kbId, wsId, "kb-t8-six-" + RUN);
        kbOther = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility) "
                + "VALUES (?, ?, ?, 'restricted')", kbOther, wsId, "kb-t8-other-" + RUN);

        docId = Uuids.nowV7();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, mime, size_bytes, sha256, status, graph_status) "
                + "VALUES (?, ?, 'note-api.txt', 'text/plain', 5, 'sha-t8-" + RUN + "', 'ready', 'done')",
                docId, kbId);
        docPurged = Uuids.nowV7();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, mime, size_bytes, sha256, status, graph_status, "
                + "deleted_at, purged_at) VALUES (?, ?, 'purged.txt', 'text/plain', 5, 'sha-p-" + RUN + "', "
                + "'ready', 'done', now(), now())", docPurged, kbId);
        docTomb = Uuids.nowV7();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, mime, size_bytes, sha256, status, graph_status, "
                + "deleted_at) VALUES (?, ?, 'tomb.txt', 'text/plain', 5, 'sha-t-" + RUN + "', "
                + "'ready', 'done', now())", docTomb, kbId);

        jdbc.update("INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, detail) "
                + "VALUES (?, ?, ?, 'kb.updated', 'kb', '{}')", UUID.randomUUID(), kbId, adminId);
        // settings：封印形态的钥匙入库（读面只见布尔）
        jdbc.update("INSERT INTO llm_settings (workspace_id, chat_base_url, chat_api_key, chat_model) "
                + "VALUES (?, 'http://m.test/v1', 'enc:v1:c2VhbGVk', 'gpt-t8')", wsId);
        // 告警两条同 kind 连组
        jdbc.update("INSERT INTO alerts (id, kb_id, severity, kind, min_role, detail) "
                + "VALUES (?, ?, 'warning', 'source.sync_failed', 'viewer', '{\"s\":1}')",
                UUID.randomUUID(), kbId);
        jdbc.update("INSERT INTO alerts (id, kb_id, severity, kind, min_role, detail) "
                + "VALUES (?, ?, 'error', 'source.sync_failed', 'viewer', '{\"s\":2}')",
                UUID.randomUUID(), kbId);
    }

    @AfterAll
    void cleanup() {
        // audit_events append-only：台账行留账（kb 随工作区级联删除，对按 kb 过滤的查询不可见）
        jdbc.update("DELETE FROM workspaces WHERE id IN (?, ?)", wsId, wsBareId);
        jdbc.update("CALL jknow_test_user_cleanup(?, ?)", EMAIL, EMAIL2);
    }

    // ---- workspace-kb 族 ----

    @Test
    void workspacesAndGetOneCarryRole() throws Exception {
        Resp list = get("/api/v1/workspaces", adminId);
        assertEquals(200, list.status());
        assertTrue(list.body().isArray());
        Resp one = get("/api/v1/workspaces/" + wsId, adminId);
        assertEquals("owner", one.body().get("role").asText());
        assertEquals(wsId.toString(), one.body().get("workspace").get("id").asText());
        assertEquals(404, get("/api/v1/workspaces/" + UUID.randomUUID(), adminId).status());
    }

    @Test
    void kbFamilyEndpointsShape() throws Exception {
        assertTrue(get("/api/v1/workspaces/" + wsId + "/kbs", adminId).body().isArray());
        Resp kb = get("/api/v1/kbs/" + kbId, adminId);
        assertEquals("owner", kb.body().get("my_role").asText(), "详情附带调用者角色");
        assertTrue(kb.body().has("visibility"));
        Resp my = get("/api/v1/workspaces/" + wsId + "/my-kbs", adminId);
        var row = my.body().get("kbs").get(0);
        assertEquals("owner", row.get("my_role").asText());
        assertTrue(row.get("kb").has("ontology_lang"), "kb 整对象嵌在 kb 键下");
        assertEquals(1, row.get("doc_count").asInt());
        assertEquals(5, get("/api/v1/kbs/" + kbId + "/readiness", adminId).body().size());
        assertTrue(get("/api/v1/kbs/" + kbId + "/members", adminId).body().get("members").isArray());
        Resp auditLog = get("/api/v1/kbs/" + kbId + "/audit?limit=20&offset=0", adminId);
        assertEquals(1, auditLog.body().get("total").asInt());
        assertEquals("kb.updated", auditLog.body().get("actions").get(0).asText());
        assertEquals("Not found", get("/api/v1/kbs/" + UUID.randomUUID(), adminId)
                .body().get("error").asText());
    }

    @Test
    void restrictedKbHiddenAs404WithoutMatrix() throws Exception {
        assertEquals(404, get("/api/v1/kbs/" + kbOther + "/readiness", userId).status(),
                "restricted 无矩阵 → 404 不泄露存在性");
    }

    @Test
    void ontologyPacksAreTheStaticFive() throws Exception {
        Resp packs = get("/api/v1/ontology-packs", adminId);
        assertEquals(5, packs.body().get("packs").size());
        var schema = packs.body().get("packs").get(0);
        assertEquals("schema-org", schema.get("id").asText());
        assertTrue(schema.get("classes").asInt() > 0);
    }

    @Test
    void settingsGetShowsOnlyBooleanPresenceOfSealedKeys() throws Exception {
        Resp s = get("/api/v1/workspaces/" + wsId + "/settings", adminId);
        assertTrue(s.body().get("has_chat_key").asBoolean(), "封印列非空 ⟺ 已配置");
        assertFalse(s.body().has("chat_api_key"), "凭据键绝不出现在响应里");
        assertFalse(s.body().get("has_embed_key").asBoolean());
        assertEquals("gpt-t8", s.body().get("chat_model").asText());
        assertEquals(403, get("/api/v1/workspaces/" + wsId + "/settings", userId).status(),
                "settings 面 admin 起步：viewer 成员 403");
    }

    // ---- admin 403 判定面 + users 读面 ----

    @Test
    void orgUsersListIsReadableWithoutAdmin() throws Exception {
        Resp users = get("/api/v1/users", userId);
        assertEquals(200, users.status());
        assertTrue(users.body().isArray());
        var first = users.body().get(0);
        assertEquals(4, first.size(), "OrgUser 四键");
        assertTrue(first.has("display_name"));
        assertTrue(first.has("is_admin"));
    }

    @Test
    void adminFacesRefuseNonAdminWithSameShape() throws Exception {
        String[] gets = {"/api/v1/users/deactivated", "/api/v1/admin/deployment",
                "/api/v1/admin/data-sources"};
        for (String path : gets) {
            Resp resp = get(path, userId);
            assertEquals(403, resp.status(), path);
            assertEquals("You don't have permission to do that",
                    resp.body().get("error").asText(), path);
        }
        assertEquals(403, post("/api/v1/admin/users", userId,
                "{\"email\":\"x@test.local\",\"display_name\":\"x\",\"password\":\"12345678\"}").status());
        assertEquals(403, delete("/api/v1/admin/users/" + UUID.randomUUID(), userId).status());
    }

    @Test
    void adminDeploymentReadsForAdmin() throws Exception {
        Resp d = get("/api/v1/admin/deployment", adminId);
        assertEquals(200, d.status());
        assertTrue(d.body().has("open_registration"));
        assertTrue(d.body().has("worker_concurrency"));
        assertTrue(d.body().has("model_limits"));
    }

    // ---- tokens / alerts ----

    @Test
    void tokensListIsEmptyEnvelope() throws Exception {
        Resp tokens = get("/api/v1/me/tokens", userId);
        assertEquals(200, tokens.status());
        assertTrue(tokens.body().get("tokens").isArray());
        assertEquals(0, tokens.body().get("tokens").size());
    }

    @Test
    void alertsPageGroupsAndUnread() throws Exception {
        Resp page = get("/api/v1/alerts?limit=10&offset=0", userId);
        assertEquals(1, page.body().get("total").asInt(), "只见 kbId 这组（min_role=viewer）");
        var group = page.body().get("items").get(0);
        assertEquals("source.sync_failed", group.get("kind").asText());
        assertEquals(2, group.get("count").asInt());
        assertEquals("error", group.get("severity").asText());
        assertEquals(2, group.get("unread").asInt());
        assertEquals(2, group.get("lines").size(), "组内明细前 5 条");
        Resp unread = get("/api/v1/alerts/unread", userId);
        assertEquals(2, unread.body().get("unread").asInt());
    }

    // ---- documents ----

    @Test
    void documentsPageAndDetailShapes() throws Exception {
        Resp page = get("/api/v1/kbs/" + kbId + "/documents?limit=10&offset=0", adminId);
        assertEquals(1, page.body().get("total").asInt());
        assertEquals(1, page.body().get("ready").asInt());
        assertEquals(1, page.body().get("deleted").asInt(), "整库墓碑数（真删的不算）");
        Resp detail = get("/api/v1/documents/" + docId, adminId);
        assertEquals("note-api.txt", detail.body().get("document").get("filename").asText());
        assertTrue(detail.body().get("chunks").isArray());
        assertEquals(404, get("/api/v1/documents/" + UUID.randomUUID(), adminId).status());
        assertEquals(404, get("/api/v1/kbs/" + UUID.randomUUID() + "/documents?limit=5&offset=0",
                adminId).status());
    }

    @Test
    void documentVersionsExtractionsAndJobs() throws Exception {
        assertEquals(0, get("/api/v1/documents/" + docId + "/versions", adminId)
                .body().get("versions").size());
        assertTrue(get("/api/v1/documents/" + docId + "/extractions", adminId)
                .body().get("facts").isEmpty());
        assertEquals(0, get("/api/v1/kbs/" + kbId + "/jobs/failed", adminId)
                .body().get("failed").asLong());
        assertEquals(404, get("/api/v1/kbs/" + kbId + "/jobs/999999", adminId).status());
    }

    @Test
    void purgedDocumentContentIs410() throws Exception {
        Resp purged = get("/api/v1/documents/" + docPurged + "/content", adminId);
        assertEquals(410, purged.status(), "真删不可撤销：410");
        assertTrue(purged.raw().contains("The document contents have been purged"));
    }

    // ---- mappings ----

    @Test
    void mappingsListRelevantAndDataSources() throws Exception {
        Resp list = get("/api/v1/kbs/" + kbId + "/mappings?limit=10&offset=0", adminId);
        assertTrue(list.body().get("items").isEmpty());
        assertEquals(0, list.body().get("total").asInt());
        assertTrue(list.body().get("last_run").isNull(), "从没探索过的库 last_run=null");
        Resp relevant = get("/api/v1/kbs/" + kbId + "/mappings/relevant?q=total", adminId);
        assertEquals(8, relevant.body().get("k").asInt(), "k 缺省 DEFINITIONS_IN_PROMPT=8");
        assertTrue(get("/api/v1/kbs/" + kbId + "/data-sources", adminId)
                .body().get("data_sources").isEmpty());
        assertEquals(404, get("/api/v1/kbs/" + UUID.randomUUID() + "/mappings?limit=5&offset=0",
                adminId).status());
    }

    @Test
    void mappingsRelevantWithoutQIs400() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/mappings/relevant", adminId);
        assertEquals(400, resp.status(), "缺 q 的反序列化文案面");
    }

    // ---- conversations / SSE / chat ----

    @Test
    void conversationsListAnd404() throws Exception {
        Resp list = get("/api/v1/kbs/" + kbId + "/conversations?limit=5&offset=0", adminId);
        assertTrue(list.body().get("conversations").isEmpty());
        assertEquals(404, get("/api/v1/kbs/" + kbId + "/conversations/" + UUID.randomUUID(),
                adminId).status());
    }

    @Test
    void sseEndpointsRefuseUnauthenticated() throws Exception {
        String[] paths = {"/api/v1/kbs/00000000-0000-0000-0000-000000000000/events",
                "/api/v1/alerts/events"};
        for (String path : paths) {
            assertEquals(401, get(path, null).code(),
                    "无凭据被若依链拒（体 code 401）：" + path);
        }
    }

    @Test
    void chatWithoutModelIsNoChatModel422() throws Exception {
        Resp resp = post("/api/v1/kbs/" + kbBareId + "/chat", adminId,
                "{\"message\":\"Which companies are mentioned?\"}");
        assertEquals(422, resp.status());
        assertEquals("no_chat_model", resp.body().get("code").asText());
        assertEquals("Chat model not configured. Go to Settings → Models.",
                resp.body().get("error").asText());
    }
}
