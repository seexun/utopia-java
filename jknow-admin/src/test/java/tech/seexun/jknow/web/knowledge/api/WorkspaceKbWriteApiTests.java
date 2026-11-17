package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * workspace-kb 写面 HTTP 重建（P1 任务 8；源 WorkspaceKbWriteApiTests 核心语义——
 * MockMvc+LoginUser 重建）：无凭据 401、建 ws 即 owner、名字按码点校验、改名鉴权
 * （非成员 404 不泄露/viewer 403/owner 200）、删 ws Owner 专属、建 kb 的角色门与
 * 本体包安装、restricted 缺省拒。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceKbWriteApiTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private final List<UUID> createdWs = new ArrayList<>();

    private UUID orgId;
    private UUID ownerId;
    private UUID outsiderId;
    private UUID sysadminId;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        ownerId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Ws Owner')", ownerId, orgId,
                "t8-wsw-" + RUN + "@test.local");
        outsiderId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Ws Outsider')", outsiderId, orgId,
                "t8-wso-" + RUN + "@test.local");
        sysadminId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Ws Sysadmin', TRUE)", sysadminId, orgId,
                "t8-wss-" + RUN + "@test.local");
    }

    @AfterAll
    void cleanup() {
        for (UUID ws : createdWs) {
            // jobs 行不级联（payload 引用 kb），先按库清；audit_events append-only 留账
            jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' IN "
                    + "(SELECT id::text FROM knowledge_bases WHERE workspace_id = ?)", ws);
            jdbc.update("DELETE FROM workspaces WHERE id = ?", ws);
        }
        jdbc.update("CALL jknow_test_user_cleanup(?, ?, ?)",
                "t8-wsw-" + RUN + "@test.local", "t8-wso-" + RUN + "@test.local",
                "t8-wss-" + RUN + "@test.local");
    }

    private UUID createWorkspace(String name) throws Exception {
        Resp resp = post("/api/v1/workspaces", ownerId, "{\"name\": \"" + name + "\"}");
        assertEquals(200, resp.status(), resp.raw());
        UUID ws = UUID.fromString(resp.body().get("id").asText());
        createdWs.add(ws);
        return ws;
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        assertEquals(401, post("/api/v1/workspaces", null, "{\"name\": \"x\"}").code(),
                "无凭据：若依链拒（体 code 401）");
    }

    @Test
    void createWorkspaceMakesCreatorOwner() throws Exception {
        Resp resp = post("/api/v1/workspaces", ownerId, "{\"name\": \"  ws-t8-trim-" + RUN + "  \"}");
        assertEquals(200, resp.status(), resp.raw());
        assertTrue(resp.body().has("id") && resp.body().has("org_id")
                && resp.body().has("name") && resp.body().has("created_at"), "Workspace 形状四键");
        assertEquals(orgId.toString(), resp.body().get("org_id").asText());
        assertEquals("ws-t8-trim-" + RUN, resp.body().get("name").asText(), "validate_name 返回 trim 结果");
        UUID ws = UUID.fromString(resp.body().get("id").asText());
        createdWs.add(ws);
        assertEquals("owner", jdbc.queryForObject(
                "SELECT role FROM memberships WHERE workspace_id = ? AND user_id = ?",
                String.class, ws, ownerId), "创建者 membership=owner");
    }

    @Test
    void createWorkspaceValidatesNameByCodePoints() throws Exception {
        assertEquals("bad_name", post("/api/v1/workspaces", ownerId, "{\"name\": \"   \"}")
                .body().get("code").asText());
        assertEquals("bad_name", post("/api/v1/workspaces", ownerId,
                "{\"name\": \"" + "a".repeat(65) + "\"}").body().get("code").asText());
        assertEquals("bad_name", post("/api/v1/workspaces", ownerId,
                "{\"name\": \"" + "端".repeat(65) + "\"}").body().get("code").asText(),
                "按码点数：65 个 CJK 也是 65");
        Resp ok = post("/api/v1/workspaces", ownerId, "{\"name\": \"" + "端".repeat(64) + "\"}");
        assertEquals(200, ok.status(), "64 码点 CJK 合法（边界另一侧）：" + ok.raw());
        createdWs.add(UUID.fromString(ok.body().get("id").asText()));
    }

    @Test
    void renameWorkspaceChecksAdminAndLeaksNothing() throws Exception {
        UUID ws = createWorkspace("ws-t8-ren-" + RUN);
        assertEquals(404, patch("/api/v1/workspaces/" + ws, outsiderId, "{\"name\": \"nope\"}").status(),
                "非成员 404（不泄露工作区存在性）");
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'viewer')",
                outsiderId, ws);
        assertEquals(403, patch("/api/v1/workspaces/" + ws, outsiderId, "{\"name\": \"nope\"}").status(),
                "viewer 成员 403");
        Resp resp = patch("/api/v1/workspaces/" + ws, ownerId,
                "{\"name\": \"ws-t8-renamed-" + RUN + "\"}");
        assertEquals(200, resp.status());
        assertEquals("ws-t8-renamed-" + RUN, resp.body().get("name").asText());
        assertEquals(422, patch("/api/v1/workspaces/" + ws, ownerId, "{\"name\": \"\"}").status(),
                "坏名字在鉴权前就挡（校验先于 require_role）");
    }

    @Test
    void deleteWorkspaceRequiresOwner() throws Exception {
        UUID ws = createWorkspace("ws-t8-del-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'admin')",
                outsiderId, ws);
        assertEquals(403, delete("/api/v1/workspaces/" + ws, outsiderId).status(),
                "admin 成员也删不得（Owner 专属）");
        Resp resp = delete("/api/v1/workspaces/" + ws, ownerId);
        assertEquals(200, resp.status());
        assertEquals(true, resp.body().get("ok").asBoolean());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE id = ?",
                Long.class, ws));
    }

    @Test
    void createKbChecksRoleAndInstallsOntologyPacks() throws Exception {
        UUID ws = createWorkspace("ws-t8-kb-" + RUN);
        // 非成员 404（不泄露存在性）
        assertEquals(404, post("/api/v1/workspaces/" + ws + "/kbs", outsiderId,
                "{\"name\": \"kb-x\"}").status());
        // viewer 成员 403（建库是部署管理动作）
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'viewer')",
                outsiderId, ws);
        assertEquals(403, post("/api/v1/workspaces/" + ws + "/kbs", outsiderId,
                "{\"name\": \"nope\"}").status(), "viewer 建不得");
        // 非成员的系统管理员也 404（require_role(Viewer) 在前，is_admin 抬权在后——不泄露）
        assertEquals(404, post("/api/v1/workspaces/" + ws + "/kbs", sysadminId,
                "{\"name\": \"kb-t8-sysnm-" + RUN + "\"}").status());
        // sysadmin 只要是个成员即可（viewer 成员 + is_admin 抬权）+ 显式装包 + 默认库
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'viewer')",
                sysadminId, ws);
        Resp bySys = post("/api/v1/workspaces/" + ws + "/kbs", sysadminId,
                "{\"name\": \"kb-t8-sys-" + RUN + "\"}");
        assertEquals(200, bySys.status(), bySys.raw());
        assertEquals("knowledge", bySys.body().get("kind").asText(), "kind 缺省 knowledge");
        assertEquals(true, bySys.body().get("is_default").asBoolean(), "第一个库自动默认库");
        UUID kb = UUID.fromString(bySys.body().get("id").asText());
        assertEquals("admin", jdbc.queryForObject(
                "SELECT role FROM kb_members WHERE kb_id = ? AND user_id = ?",
                String.class, kb, sysadminId), "创建者自动入矩阵 admin");
        // 本体包安装：显式 ontology_packs=["foaf"] → foaf:Person 落 entity_types（#580 无默认包）
        Resp withPack = post("/api/v1/workspaces/" + ws + "/kbs", ownerId,
                "{\"name\": \"kb-t8-foaf-" + RUN + "\", \"ontology_packs\": [\"foaf\"]}");
        assertEquals(200, withPack.status(), withPack.raw());
        String packKb = withPack.body().get("id").asText();
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM entity_types WHERE kb_id = ?::uuid AND key = 'person'",
                Long.class, packKb), "foaf:Person 该随包装进来");
        // restricted 不能当默认库：全新 ws 的第一个库（自动默认）带 restricted → 拒（源同断言）
        UUID freshWs = createWorkspace("ws-t8-vis-" + RUN);
        assertEquals(422, post("/api/v1/workspaces/" + freshWs + "/kbs", ownerId,
                "{\"name\": \"kb-t8-r-" + RUN + "\", \"visibility\": \"restricted\"}").status(),
                "默认库必须 open——restricted 缺省矩阵为空 → 拒");
    }
}
