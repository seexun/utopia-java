package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.secrets.SealingService;
import tech.seexun.jknow.store.secrets.SecretsBox;

/**
 * 凭据静态加密的<b>补封面</b>（a_secret_is_sealed_at_rest.rs 第 4 节原值移植，M5 任务 6
 * 台账收口）：升级前落库的明文行照常读得出来，补封（{@link SealingService}，启动时
 * {@code backfill} 扫全部）之后它也封上了；幂等——再跑 0 行。其余三面（llm 两键/六源键/
 * ingest_token 入库即封印 + None 保留旧值）已由 SettingsPutTests.mainCard… 与
 * SourcesTests.secretsAreSealedAtRest… 覆盖（M1/M5 台账记指针）。
 *
 * <p><b>只补自己的行</b>：全局 backfill 会拿这把一次性钥匙把共用测试库里别人的凭据也
 * 封上——那些行从此谁也打不开（Rust 同注记）。
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ASealIsSealedAtRestTests extends StoreTestBase {

    @Autowired
    SealingService sealing;
    @Autowired
    SettingsService settings;
    @Autowired
    SourcesService sources;
    @Autowired
    tech.seexun.jknow.store.mapper.DatasourcesMapper datasourcesMapper;

    /** Rust 夹具 secrets::init(generate_key()) 的等价物：一把测试自己的钥匙。 */
    private static final String TEST_KEY =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private UUID org;
    private UUID ws;
    private UUID kb;
    private UUID user;
    private UUID source;
    private UUID dataSource;

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        user = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'seal-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'seal-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                + " VALUES (?, ?, 'seal-test')", kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Seal Test', TRUE)
                """, user, org, "seal-" + user + "@test.local");
        source = Uuids.nowV7();
        jdbc.update("INSERT INTO sources (id, kb_id, kind, name)"
                + " VALUES (?, ?, 'upload', 'seal-test-src')", source, kb);
    }

    @AfterAll
    void cleanup() {
        if (dataSource != null) {
            jdbc.update("DELETE FROM data_sources WHERE id = ?", dataSource);
        }
        jdbc.update("DELETE FROM sources WHERE id = ?", source);
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    @Test
    void legacyPlaintextReadsBackfillSealsAndStaysReadable() {
        DatasourcesService datasources = new DatasourcesService(datasourcesMapper, TEST_KEY);
        dataSource = datasources.create("warehouse-" + Uuids.nowV7(), "postgres",
                "postgres://analyst:pw-secret@db.example:5432/sales", user);
        // 连接串入库即封印；摘要里看得到凭据原文（store 面），库里的行是密文
        String storedConn = jdbc.queryForObject(
                "SELECT conn_string FROM data_sources WHERE id = ?", String.class, dataSource);
        assertTrue(SecretsBox.isSealed(storedConn), "sealed in the database");
        assertNotEquals("postgres://analyst:pw-secret@db.example:5432/sales", storedConn);

        // 旧行：明文照常读，补封之后是密文、读出来还是原文
        jdbc.update("INSERT INTO llm_settings (workspace_id, chat_base_url, chat_model)"
                + " VALUES (?, 'http://m.test/v1', 'gpt-seal')", ws);
        jdbc.update("UPDATE llm_settings SET chat_api_key = 'legacy-plain'"
                + " WHERE workspace_id = ?", ws);
        jdbc.update("UPDATE sources SET ingest_token = 'utp_legacy' WHERE id = ?", source);
        assertEquals("legacy-plain", settings.get(ws).chatApiKey(),
                "升级前的明文行照常读得出");
        assertEquals("utp_legacy", sources.get(source).ingestToken());

        // 只补自己的行（同 Rust：全局 backfill 会把别人封死在这把一次性钥匙下）
        int n = sealing.sealLlmSettings(ws) + sealing.sealSources(source);
        assertEquals(2, n, "both legacy rows were sealed");
        String stored = jdbc.queryForObject(
                "SELECT chat_api_key FROM llm_settings WHERE workspace_id = ?",
                String.class, ws);
        assertTrue(SecretsBox.isSealed(stored));
        assertEquals("legacy-plain", settings.get(ws).chatApiKey());
        assertEquals("utp_legacy", sources.get(source).ingestToken());

        assertEquals(sealing.sealLlmSettings(ws) + sealing.sealSources(source)
                + sealing.sealDataSources(dataSource), 0, "idempotent");
    }
}
