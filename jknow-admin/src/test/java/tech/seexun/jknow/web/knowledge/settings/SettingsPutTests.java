package tech.seexun.jknow.web.knowledge.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.secrets.SecretsBox;
import tech.seexun.jknow.web.knowledge.api.ApiTestBase;

/**
 * settings PUT 面重建（P1 任务 8；源 SettingsPutTests 核心语义——MockMvc+LoginUser 重建，
 * 注册流改 jknow_test_user 直插）：主卡钥匙入库即封印、COALESCE 缺席保旧、空串同义、
 * 换钥匙换密文；chat_reasoning_effort 值域校验与空串清空。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingsPutTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    SettingsService settings;

    private UUID orgId;
    private UUID userId;
    private UUID wsId;

    @BeforeAll
    void bootstrap() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        userId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Set Put')", userId, orgId,
                "t8-set-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 't8-set-ws')",
                wsId, orgId);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'admin')",
                userId, wsId);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-set-" + RUN + "@test.local");
    }

    private Resp putMain(String body) throws Exception {
        // 主卡就是 PUT /settings（jknow 版面：无 /main 子卡——源卡片参数在此收拢为主端点）
        return put("/api/v1/workspaces/" + wsId + "/settings", userId, body);
    }

    private String dbText(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM llm_settings WHERE workspace_id = ?",
                String.class, wsId);
    }

    // ---- 主卡：COALESCE 保旧密钥 + 入库即封印 + 出库即开封 ----

    @Test
    void mainCardSealsKeysAndCoalesceKeepsTheOldOne() throws Exception {
        // 第一次保存：两把钥匙都传——落库即 enc:v1: 密文
        assertEquals(200, putMain("{\"chat_base_url\":\"https://chat.example\","
                + "\"chat_api_key\":\"sk-first\",\"chat_model\":\"gpt-x\","
                + "\"embed_base_url\":\"https://embed.example\",\"embed_api_key\":\"ek-first\","
                + "\"embed_model\":\"bge-m3\",\"embed_dim\":1024}").status());
        String chatSealed = dbText("chat_api_key");
        String embedSealed = dbText("embed_api_key");
        assertTrue(SecretsBox.isSealed(chatSealed), "chat_api_key 入库即封印");
        assertTrue(SecretsBox.isSealed(embedSealed), "embed_api_key 入库即封印");
        assertNotEquals("sk-first", chatSealed, "密文不等于明文（不是明文落库）");
        // Service 出库即开封：明文回来
        assertEquals("sk-first", settings.get(wsId).chatApiKey());
        assertEquals("ek-first", settings.get(wsId).embedApiKey());

        // 第二次保存：不回传密钥（前端不回传密钥的形态）——COALESCE 保旧，密文一字节不动
        assertEquals(200, putMain("{\"chat_base_url\":\"https://chat2.example\","
                + "\"chat_model\":\"gpt-y\"}").status());
        assertEquals(chatSealed, dbText("chat_api_key"), "缺席=保留旧密钥（密文原样）");
        assertEquals(embedSealed, dbText("embed_api_key"));
        assertEquals("https://chat2.example", dbText("chat_base_url"), "非密钥列照常覆盖");

        // 空串与缺席同义
        assertEquals(200, putMain("{\"chat_api_key\":\"\"}").status());
        assertEquals(chatSealed, dbText("chat_api_key"), "空串=保留旧密钥");

        // 第三次保存：换新钥匙——新 nonce 新密文，且开得出新明文
        assertEquals(200, putMain("{\"chat_api_key\":\"sk-second\"}").status());
        String rotated = dbText("chat_api_key");
        assertTrue(SecretsBox.isSealed(rotated));
        assertNotEquals(chatSealed, rotated, "换钥匙=换密文（nonce 每次全新）");
        assertEquals("sk-second", settings.get(wsId).chatApiKey());
    }

    // ---- chat_reasoning_effort：单独 UPDATE + 值域校验 + 空串清空 ----

    @Test
    void reasoningEffortValidatesAndClears() throws Exception {
        assertEquals(200, putMain("{\"chat_reasoning_effort\":\"high\"}").status());
        assertEquals("high", dbText("chat_reasoning_effort"));
        Resp bad = putMain("{\"chat_reasoning_effort\":\"ultra\"}");
        assertEquals(422, bad.status(), "非法 effort 应 422：" + bad.raw());
        assertEquals("bad_reasoning_effort", bad.body().path("code").asText());
        assertEquals("high", dbText("chat_reasoning_effort"), "非法值不落库");
        assertEquals(200, putMain("{\"chat_reasoning_effort\":\"\"}").status());
        assertNull(dbText("chat_reasoning_effort"), "空串=清空");
    }
}
