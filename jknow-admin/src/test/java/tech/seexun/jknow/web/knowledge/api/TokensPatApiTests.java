package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * pt- 个人令牌 HTTP 面重建（P1 任务 8 核心验收位）：源 token_routes.rs 的 issue/list/revoke
 * 三步 + <b>pt- 双通</b>（T7 交接）——若依 token（LoginUser+SecurityContext 形态）与
 * pt- Bearer（不挂 postprocessor，真走 PersonalTokenAuthFilter 的摘要查表验签）打同一个
 * 业务端点同形 200；坏令牌/前缀不对 401；撤销后立刻 401（判断在 SQL 里）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokensPatApiTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private UUID orgId;
    private UUID userId;
    private UUID wsId;
    private UUID kbId;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        userId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Pat Api Test')",
                userId, orgId, "t8-pat-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 't8-pat-ws-" + RUN + "')",
                wsId, orgId);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')",
                userId, wsId);
        kbId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 't8-pat-kb-" + RUN + "')",
                kbId, wsId);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-pat-" + RUN + "@test.local");
    }

    @Test
    void issueListRevokeAndDoublePass() throws Exception {
        // ---- 若依 token 面：LoginUser 形态打业务端点（双通之一）
        Resp ws = get("/api/v1/workspaces", userId);
        assertEquals(200, ws.status(), "若依 token（LoginUser+SecurityContext）打业务面：双通之一\n" + ws.raw());
        assertTrue(ws.body().isArray());

        // ---- issue：明文只在这一次的响应里出现（pt- + JWT 载体）
        Resp issued = post("/api/v1/me/tokens", userId,
                "{\"name\":\"t8-pat-我的钥匙\",\"scope\":\"read\"}");
        assertEquals(200, issued.status(), issued.raw());
        assertTrue(issued.body().get("token").asText().startsWith("pt-"), "前缀要认得出");
        assertTrue(issued.body().get("token").asText().substring(3).indexOf('.') > 0, "载体是 JWT");
        assertEquals("read", issued.body().get("info").get("scope").asText());
        assertTrue(issued.body().get("info").has("token_prefix"),
                "源契约：token_prefix 键（snake_case），实际响应：" + issued.raw());
        String plain = issued.body().get("token").asText();
        String id = issued.body().get("info").get("id").asText();
        // 库里只有哈希：明文任何一段不出现在 token_hash/token_prefix 之外
        String stored = jdbc.queryForObject(
                "SELECT token_hash FROM personal_tokens WHERE id = ?", String.class, UUID.fromString(id));
        assertNotEquals(plain, stored);

        // ---- pt- 双通之二：不挂若依登录态，凭 Bearer pt- 真走 PersonalTokenAuthFilter
        Resp viaPt = rawGet("/api/v1/workspaces", plain);
        assertEquals(200, viaPt.status(), "pt- Bearer 打业务面：双通之二\n" + viaPt.raw());
        assertTrue(viaPt.body().isArray(), "与若依 token 同形响应");

        // pt- 也能打 me 面（USER_ATTR 由桥接从 pt- 建立的 LoginUser 折算）
        Resp list = rawGet("/api/v1/me/tokens", plain);
        assertEquals(200, list.status(), list.raw());
        assertEquals(1, list.body().get("tokens").size());
        assertNotNull(list.body().get("tokens").get(0).get("last_used_at"),
                "authenticate 顺手写 last_used_at（撤之前要答得出这把还在用吗）");
        assertEquals("pt-" + plain.substring(3, 19),
                list.body().get("tokens").get(0).get("token_prefix").asText());

        // ---- 坏令牌/前缀不对：401（pt- 面走源 SessionFilter 401 体形状；非 pt- 凭据落到
        // 若依 entry point——RuoYi 原生 renderString 是 HTTP 200 + 体 code 401，按实况断言）
        assertEquals(401, rawGet("/api/v1/workspaces", "pt-not-a-jwt").status());
        assertEquals("Not signed in or invalid credentials",
                rawGet("/api/v1/workspaces", "pt-not-a-jwt").body().get("error").asText());
        assertEquals(401, rawGet("/api/v1/workspaces",
                plain.replace("pt-", "utp_pat_")).code(), "旧前缀不该走这条路");

        // ---- revoke：打戳不删行，pt- 立刻不认
        Resp revoked = delete("/api/v1/me/tokens/" + id, userId);
        assertEquals(200, revoked.status(), revoked.raw());
        assertEquals(true, revoked.body().get("ok").asBoolean());
        assertEquals(401, rawGet("/api/v1/workspaces", plain).status(),
                "撤了就立刻不认——判断在 SQL 里，不在取回来之后");
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM personal_tokens WHERE id = ?",
                java.time.OffsetDateTime.class, UUID.fromString(id)), "打戳不删行");
        // 撤两次的第二次 404
        assertEquals(404, delete("/api/v1/me/tokens/" + id, userId).status());

        // ---- 签发校验：name 空 422 / scope 不认 422 / expires_in_days 负 422（源 token_routes.rs）
        assertEquals(422, post("/api/v1/me/tokens", userId, "{\"name\":\"\",\"scope\":\"read\"}").status());
        assertEquals(422, post("/api/v1/me/tokens", userId,
                "{\"name\":\"x\",\"scope\":\"admin\"}").status());
        assertEquals(422, post("/api/v1/me/tokens", userId,
                "{\"name\":\"x\",\"scope\":\"read\",\"expires_in_days\":-1}").status());
    }

    /** 不挂若依登录态、凭 Authorization: Bearer 打（pt- 真链路）——先清 TestSecurityContextHolder，
     *  免得同线程前一个 postprocessor 请求的登录态泄漏进来。 */
    protected Resp rawGet(String path, String bearer) throws Exception {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + bearer)).andReturn();
        String raw = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Resp(result.getResponse().getStatus(),
                raw.isEmpty() ? null : mapper.readTree(raw), raw);
    }
}
