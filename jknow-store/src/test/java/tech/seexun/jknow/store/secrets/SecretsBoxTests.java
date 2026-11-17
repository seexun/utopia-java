package tech.seexun.jknow.store.secrets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.AppException;

/**
 * 封印盒单元测试（对齐旧仓库 utopia-core/src/secrets.rs:140-198 的测试族逐条：
 * 往返/新 nonce/幂等/明文透传/篡改报错/JSON 键/钥匙解析）。跨实现互通
 * （Java 开 Rust 封的行）由 SealInteropTests 在共享库上用真钥匙真密文验证。
 */
class SecretsBoxTests {

    private static final byte[] KEY = new byte[32]; // 全零钥匙：确定性，够测往返

    private final SecretsBox box = SecretsBox.armed(KEY);

    /** secrets.rs:150-158 a_secret_round_trips_and_never_repeats。 */
    @Test
    void aSecretRoundTripsAndNeverRepeats() {
        String a = box.seal("sk-live-123");
        String b = box.seal("sk-live-123");
        assertTrue(SecretsBox.isSealed(a));
        assertNotEquals(a, b, "a fresh nonce every time");
        assertEquals("sk-live-123", box.open(a));
        assertEquals("sk-live-123", box.open(b));
    }

    /** secrets.rs:161-166 sealing_is_idempotent_and_plaintext_passes_through。 */
    @Test
    void sealingIsIdempotentAndPlaintextPassesThrough() {
        String once = box.seal("token");
        assertEquals(once, box.seal(once), "already sealed stays as it is");
        assertEquals("legacy-plain", box.open("legacy-plain"));
        assertNull(box.sealOpt(null));
        assertNull(box.openOpt(null));
    }

    /** secrets.rs:169-177 a_tampered_value_does_not_open（改末字符/坏 base64 都报错）。 */
    @Test
    void aTamperedValueDoesNotOpen() {
        String sealed = box.seal("secret");
        String broken = sealed.substring(0, sealed.length() - 1)
                + (sealed.endsWith("A") ? "B" : "A");
        assertThrows(AppException.class, () -> box.open(broken));
        assertThrows(AppException.class, () -> box.open("enc:v1:not-base64!"));
        // 太短（不足 12 字节 nonce）
        assertThrows(AppException.class, () -> box.open(
                "enc:v1:" + java.util.Base64.getEncoder().encodeToString(new byte[5])));
    }

    /** secrets.rs:180-189 json_keys_are_sealed_in_place（非字符串值不动、缺键不动）。 */
    @Test
    void jsonKeysAreSealedInPlace() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var cfg = mapper.readTree("{\"token\":\"t0k\",\"url\":\"https://x\",\"n\":3}");
        box.sealJsonKeys(cfg, new String[]{"token", "url_missing", "n"});
        assertTrue(SecretsBox.isSealed(cfg.get("token").asText()));
        assertEquals("https://x", cfg.get("url").asText());
        assertEquals(3, cfg.get("n").asInt());
        box.openJsonKeys(cfg, new String[]{"token"});
        assertEquals("t0k", cfg.get("token").asText());
    }

    /** secrets.rs:192-197 keys_parse_from_hex_and_base64。 */
    @Test
    void keysParseFromHexAndBase64() {
        byte[] key = SecretsBox.generateKey();
        assertNotNull(key);
        assertEquals(32, key.length);
        assertTrue(Arrays.equals(key, SecretsBox.parseKey(SecretsBox.keyToHex(key))));
        assertTrue(Arrays.equals(key,
                SecretsBox.parseKey(java.util.Base64.getEncoder().encodeToString(key))));
        assertNull(SecretsBox.parseKey("too-short"));
        assertNull(SecretsBox.parseKey(null));
    }

    /** 未装钥匙（secrets.rs:68/91-93）：seal 原样落库（明文与从前一样）、open 只认明文。 */
    @Test
    void anUnarmedBoxPassesPlaintextThroughAndRefusesSealedRows() {
        SecretsBox unarmed = SecretsBox.unarmed();
        assertEquals("plain", unarmed.seal("plain"));
        assertEquals("plain", unarmed.open("plain"));
        assertFalse(unarmed.isReady());
        assertThrows(AppException.class, () -> unarmed.open(box.seal("x")));
    }

    /** 与 Rust 的 enc:v1: 结构契约：base64 里 nonce(12) ‖ ct ‖ tag(16)——空明文最小密文 28B。 */
    @Test
    void wireFormatMatchesRustLayout() {
        String sealed = box.seal("");
        assertTrue(sealed.startsWith("enc:v1:"));
        byte[] raw = java.util.Base64.getDecoder()
                .decode(sealed.substring("enc:v1:".length()));
        assertEquals(12 + 16, raw.length, "nonce12 ‖ (empty ct) ‖ tag16");
        // Java 封的行 Java 开（Java seal→Java open 的最低互通线）
        assertDoesNotThrow(() -> assertEquals("", box.open(sealed)));
    }
}
