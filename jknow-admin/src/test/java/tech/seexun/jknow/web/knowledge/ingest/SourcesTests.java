package tech.seexun.jknow.web.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.secrets.SecretsBox;
import tech.seexun.jknow.web.knowledge.api.ApiTestBase;

/**
 * sources HTTP 面重建（P1 任务 8；源 SourcesTests 核心语义——MockMvc+LoginUser 重建，
 * 注册流改 jknow_test_user 直插）：kind/cron/名字校验白名单、封印只进不出
 * （库里 enc:v1 密文、响应剔除）、ingest token 签发与轮换、列表空形、删除语义。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourcesTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private UUID orgId;
    private UUID userId;
    private UUID wsId;
    private UUID kbId;

    @BeforeAll
    void bootstrap() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        userId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'M2 Src')", userId, orgId,
                "t8-src-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 't8-src-ws')", wsId, orgId);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'admin')",
                userId, wsId);
        kbId = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 't8-src-kb')",
                kbId, wsId);
        // kb_members 矩阵显式给 admin（open 库按部署角色只算 Viewer——不插这行 EDITOR 面 403）
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role) VALUES (?, ?, 'admin')", kbId, userId);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-src-" + RUN + "@test.local");
    }

    private Resp post(String url, String json) throws Exception {
        return post(url, userId, json);
    }

    @Test
    void createValidatesKindAndCronAndName() throws Exception {
        // memory/upload 不可手建；未知 kind 同一文案
        for (String kind : new String[]{"memory", "upload", "bogus"}) {
            Resp refused = post("/api/v1/kbs/" + kbId + "/sources",
                    "{\"kind\":\"" + kind + "\",\"name\":\"x\"}");
            assertEquals(422, refused.status(), kind + ": " + refused.raw());
            assertTrue(refused.body().path("error").asText().startsWith("kind must be one of: folder,"),
                    "kind 白名单文案：" + refused.raw());
        }
        // 白名单内的建得起来
        Resp ok = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"webdav\",\"name\":\"wd\",\"config\":{\"url\":\"https://dav\"}}");
        assertEquals(200, ok.status(), ok.raw());
        assertEquals("webdav", ok.body().path("source").path("kind").asText());

        assertEquals("source_name_required", post("/api/v1/kbs/" + kbId + "/sources",
                        "{\"kind\":\"folder\",\"name\":\"  \"}").body().path("code").asText(),
                "空白名=无名");
        // cron 4 段 / 分钟越界 / 6 段
        assertEquals("bad_cron_fields", post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"folder\",\"name\":\"c1\",\"sync_cron\":\"9 * * *\"}")
                .body().path("code").asText());
        assertEquals("bad_cron", post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"folder\",\"name\":\"c2\",\"sync_cron\":\"99 9 * * *\"}")
                .body().path("code").asText());
        // 空白折一 + 名字星期（校验通过并规范化落库）
        Resp okCron = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"folder\",\"name\":\"c4\",\"sync_cron\":\"  0  9 * * Mon,Thu \"}");
        assertEquals(200, okCron.status(), okCron.raw());
        UUID id = UUID.fromString(okCron.body().path("source").path("id").asText());
        assertEquals("0 9 * * Mon,Thu",
                jdbc.queryForObject("SELECT sync_cron FROM sources WHERE id = ?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT sync_interval_minutes FROM sources WHERE id = ?",
                Integer.class, id), "cron 优先：interval 让位");
        // rss content_mode 只认两值
        Resp bad = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"rss\",\"name\":\"r1\",\"config\":{\"content_mode\":\"all\"}}");
        assertEquals(422, bad.status());
        assertEquals("rss_content_mode_invalid", bad.body().path("code").asText());
    }

    @Test
    void secretsAreSealedAtRestAndStrippedFromEveryResponse() throws Exception {
        Resp created = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"s3\",\"name\":\"sealed-s3\",\"config\":{\"bucket\":\"b\","
                        + "\"access_key_id\":\"AKIA\",\"secret_access_key\":\"x\","
                        + "\"account_key\":\"y\",\"password\":\"w\",\"token\":\"t\"}}");
        assertEquals(200, created.status(), created.raw());
        UUID id = UUID.fromString(created.body().path("source").path("id").asText());
        // 库里：SOURCE_SECRET_KEYS 各键密文（enc:v1:），标识符照明文
        String stored = jdbc.queryForObject(
                "SELECT config::text FROM sources WHERE id = ?", String.class, id);
        for (String key : new String[]{"token", "password", "secret_access_key", "account_key"}) {
            assertTrue(stored.contains("\"" + key + "\": \"enc:v1:")
                            || stored.contains("\"" + key + "\":\"enc:v1:"),
                    key + " 应封印落库：" + stored);
        }
        assertTrue(stored.contains("AKIA"), "标识符（access_key_id）不是密钥");
        // 响应剔除：列表不见密键
        Resp list = get("/api/v1/kbs/" + kbId + "/sources", userId);
        assertEquals(200, list.status());
        assertTrue(!list.raw().contains("enc:v1:"), "封印不出响应");
        assertTrue(!list.raw().contains("secret_access_key"), "密钥键剔除");
    }

    @Test
    void tokenEndpointsAndRotation() throws Exception {
        // folder 无密钥 → 404（与「未实现」同形，语义不同）
        Resp folder = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"folder\",\"name\":\"tok-folder\"}");
        assertEquals(200, folder.status(), folder.raw());
        UUID folderId = UUID.fromString(folder.body().path("source").path("id").asText());
        assertEquals(404, get("/api/v1/kbs/" + kbId + "/sources/" + folderId + "/token", userId).status());

        Resp created = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"api\",\"name\":\"tok-api\"}");
        assertEquals(200, created.status(), created.raw());
        String token0 = created.body().path("ingest_token").asText();
        assertTrue(token0.startsWith("utp_") && token0.length() == 68, "utp_+64 位十六进制");
        UUID api = UUID.fromString(created.body().path("source").path("id").asText());
        assertTrue(!created.body().path("source").has("ingest_token"), "source 对象不带密钥");

        assertEquals(token0, get("/api/v1/kbs/" + kbId + "/sources/" + api + "/token", userId)
                .body().path("ingest_token").asText(), "Editor 可随时查看");

        Resp rotated = post("/api/v1/kbs/" + kbId + "/sources/" + api + "/rotate-token", "{}");
        assertEquals(200, rotated.status(), rotated.raw());
        String token1 = rotated.body().path("ingest_token").asText();
        assertTrue(!token1.equals(token0), "轮换出的是新钥匙");
        // 库里是密文
        assertTrue(jdbc.queryForObject("SELECT ingest_token FROM sources WHERE id = ?",
                String.class, api).startsWith("enc:v1:"));
    }

    @Test
    void listViewCountsAndDeleteSemantics() throws Exception {
        Resp list = get("/api/v1/kbs/" + kbId + "/sources", userId);
        assertEquals(200, list.status());
        assertTrue(list.body().path("sources").isArray());
        // memory 源是部署内建，删不得（404/422 面）；删自建 folder 后 404
        Resp created = post("/api/v1/kbs/" + kbId + "/sources",
                "{\"kind\":\"folder\",\"name\":\"to-delete\"}");
        assertEquals(200, created.status());
        UUID id = UUID.fromString(created.body().path("source").path("id").asText());
        Resp deleted = delete("/api/v1/kbs/" + kbId + "/sources/" + id, userId);
        assertEquals(200, deleted.status(), deleted.raw());
        assertEquals(404, delete("/api/v1/kbs/" + kbId + "/sources/" + id, userId).status(),
                "删过的再删 404");
    }
}
