package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.store.secrets.SecretsBox;
import tech.seexun.jknow.store.secrets.SourceSecretKeys;

/**
 * 封印跨实现互通（任务 1 验收清单的<b>真钥匙真密文</b>面）：Rust 参照实例用
 * {data_dir}/secret.key 封进共享库的 {@code enc:v1:} 行，Java 拿同一把钥匙必须能开——
 * 这是「双实现共享 data_dir 则互通」承诺的全部内容（m2-recon §5）。
 *
 * <p>与 SecretsBoxTests（单测：Java seal→Java open、格式契约）互补：这里取的是
 * <b>共享库现存的真实封印行</b>（llm_settings 四把 key / sources.config 凭据键 /
 * sources.ingest_token），密文由 Rust aes-gcm crate 写下。只读不写——共享库的行
 * 不动（StoreTestBase 造数自律），Java 侧的封印往返在内存里做。
 *
 * <p>环境依赖与 store 直连测试同口径：共享库不可达整类跳过（StoreTestBase 的
 * @EnabledIf）；密钥文件找不到或库里没有封印行时单个用例 assumeTrue 跳过——
 * 干净 clone 的 mvn verify 仍绿。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SealInteropTests extends StoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    /** Rust 参照实例的数据目录（secret.key 所在）：env 可覆盖，缺省按仓库相对位置探候选。 */
    private static List<Path> rustDataDirCandidates() {
        String fromEnv = System.getenv("UTOPIA_RUST_DATA_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return List.of(Path.of(fromEnv.trim()));
        }
        // surefire 工作目录是模块 basedir（utopia-server）；IDE/手动从仓库根跑时是仓库根。
        // 曾有 Java 侧首启在模块目录旁误生成过一把新钥匙（../utopia-0.1.0-rc9/data 落到
        // utopia-java 仓库内）——因此不认「存在即用」，以「能开共享库的密文」验真。
        List<Path> out = new ArrayList<>();
        for (String candidate : new String[]{"../utopia-0.1.0-rc9/data",
                "../../utopia-0.1.0-rc9/data", "utopia-0.1.0-rc9/data"}) {
            out.add(Path.of(candidate));
        }
        return out;
    }

    /**
     * Rust 实例的真钥匙（首启生成的 64 位十六进制）：候选目录里<b>能解开共享库密文</b>的
     * 那把才算——既防误生成的野钥匙，也把「找不到能用的钥匙」（参照实例改用
     * UTOPIA_SECRET_KEY 环境变量等）折成 skip 而不是误报格式裂开。
     */
    private SecretsBox rustKeyedBox(List<String> samples) throws Exception {
        if (samples.isEmpty()) {
            Assumptions.assumeTrue(false, "共享库暂无 Rust 封印行");
        }
        for (Path dir : rustDataDirCandidates()) {
            Path keyFile = dir.resolve("secret.key");
            if (!Files.isRegularFile(keyFile)) {
                continue;
            }
            byte[] key = SecretsBox.parseKey(Files.readString(keyFile).trim());
            if (key == null) {
                continue;
            }
            SecretsBox box = SecretsBox.armed(key);
            if (opensAll(box, samples)) {
                return box;
            }
        }
        Assumptions.assumeTrue(false,
                "候选目录的 secret.key 都解不开共享库密文（参照实例可能用 UTOPIA_SECRET_KEY）");
        return null; // unreachable
    }

    private static boolean opensAll(SecretsBox box, List<String> samples) {
        for (String sealed : samples) {
            try {
                box.open(sealed);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** 共享库里 Rust 封下的真实密文样本（llm_settings 四把 key 全扫）。 */
    private List<String> rustSealedSamples() {
        List<String> samples = new ArrayList<>();
        jdbc.query("SELECT chat_api_key, embed_api_key, ocr_api_key, transcribe_api_key "
                + "FROM llm_settings", (rs, i) -> {
            for (int c = 1; c <= 4; c++) {
                String v = rs.getString(c);
                if (v != null && SecretsBox.isSealed(v)) {
                    samples.add(v);
                }
            }
            return null;
        });
        return samples;
    }

    /**
     * Java 开 Rust 封的行（任务验收原文）：共享库 llm_settings 里每一条 Rust 写下的
     * enc:v1: 密文，用 Rust 的 secret.key 都要开得出明文——开不出即双实现格式/
     * 钥匙源裂开，这里立刻暴露。
     */
    @Test
    void javaOpensRustSealedLlmKeys() throws Exception {
        List<String> samples = rustSealedSamples();
        Assumptions.assumeTrue(!samples.isEmpty(),
                "共享库 llm_settings 暂无 Rust 封印行（参照实例未配过密钥）");
        SecretsBox box = rustKeyedBox(samples);
        for (String sealed : samples) {
            String plain = box.open(sealed);
            assertNotNull(plain);
            assertTrue(!plain.isEmpty(), "Rust 封进去的密钥不应是空串");
        }
    }

    /** sources.config 凭据键 / ingest_token 的 Rust 密文同一把钥匙开（有才验）。 */
    @Test
    void javaOpensRustSealedSourceSecrets() throws Exception {
        List<String> llmSamples = rustSealedSamples();
        Assumptions.assumeTrue(!llmSamples.isEmpty(),
                "共享库 llm_settings 暂无 Rust 封印行（钥匙验真的探针样本）");
        SecretsBox box = rustKeyedBox(llmSamples);
        List<String> sealedKeys = new ArrayList<>();
        for (String configText : jdbc.queryForList(
                "SELECT config::text FROM sources WHERE config::text LIKE '%enc:v1:%'",
                String.class)) {
            JsonNode config = new com.fasterxml.jackson.databind.ObjectMapper().readTree(configText);
            for (String key : SourceSecretKeys.KEYS) {
                JsonNode v = config.get(key);
                if (v != null && v.isTextual() && SecretsBox.isSealed(v.asText())) {
                    sealedKeys.add(v.asText());
                }
            }
        }
        jdbc.queryForList("SELECT ingest_token FROM sources WHERE ingest_token LIKE 'enc:v1:%'",
                        String.class)
                .forEach(sealedKeys::add);
        Assumptions.assumeTrue(!sealedKeys.isEmpty(), "共享库 sources 暂无 Rust 封印凭据");
        for (String sealed : sealedKeys) {
            assertTrue(!box.open(sealed).isEmpty());
        }
    }

    /**
     * 反向最低线：Java 用 Rust 的钥匙封一个值再开回（同钥匙往返）；且与 Rust 密文
     * 字面不同（nonce 不同）——证明 Java 写下的行也会是 Rust 认得的 enc:v1: 形态
     * （布局契约由 SecretsBoxTests.wireFormatMatchesRustLayout 钉死）。
     */
    @Test
    void javaSealsWithTheRustKeyAndReopens() throws Exception {
        List<String> samples = rustSealedSamples();
        Assumptions.assumeTrue(!samples.isEmpty(), "共享库暂无 Rust 封印行");
        SecretsBox box = rustKeyedBox(samples);
        String sealed = box.seal("interop-probe");
        assertTrue(SecretsBox.isSealed(sealed));
        assertEquals("interop-probe", box.open(sealed));
        assertNotEquals(samples.get(0), sealed, "nonce 每次全新，不会撞上 Rust 的密文");
    }
}
