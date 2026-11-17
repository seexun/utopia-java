package tech.seexun.jknow.store.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tech.seexun.jknow.common.AppException;

/**
 * 凭据静态封印（逐字对齐旧仓库 utopia-core/src/secrets.rs:1-138）。
 *
 * <p><b>格式</b>：{@code enc:v1:} + base64(nonce(12) ‖ 密文+tag(16))。AES-256-GCM、
 * 128 位 tag——与 Rust aes-gcm crate 默认一致（encrypt 输出 ct‖tag），JDK GCM doFinal
 * 同形，故<b>双实现互通</b>：Java 封的行 Rust 能开，反之亦然（共享 {data_dir}/secret.key）。
 *
 * <p><b>语义逐字</b>：seal 幂等（已封印原样返回；未装钥匙原样返回——明文落库与从前一样）；
 * open 无前缀按明文原样返回（升级前落库的旧行）；有前缀而解不开<b>报错</b>——静默回一段
 * 乱码会让下游拿它去调外部接口，错得更远。
 *
 * <p>Rust 是进程级 OnceLock 单例；Java 按实例建模（DI 装配一个、测试各建各的），
 * 「一个进程一把钥匙」由 Spring 单例 bean 承担，语义不变。
 */
public final class SecretsBox {

    /** 封印前缀（secrets.rs:25）。 */
    public static final String PREFIX = "enc:v1:";
    /** nonce 长度（secrets.rs:26 NONCE_LEN）。 */
    private static final int NONCE_LEN = 12;
    /** GCM tag 位数——aes-gcm crate 默认 16 字节 tag。 */
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** 装钥匙。 */
    public static SecretsBox armed(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("secret key must be 32 bytes");
        }
        return new SecretsBox(new SecretKeySpec(keyBytes.clone(), "AES"));
    }

    /** 未装钥匙的盒（单测与无服务的工具走这条：seal 原样、open 只认明文）。 */
    public static SecretsBox unarmed() {
        return new SecretsBox(null);
    }

    private SecretsBox(SecretKeySpec key) {
        this.key = key;
    }

    /** 装了钥匙吗（secrets.rs:37 is_ready）。 */
    public boolean isReady() {
        return key != null;
    }

    /** 已是这个格式封印的值吗（secrets.rs:64）。 */
    public static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** 生一把新钥匙（CSPRNG 32 字节，secrets.rs:41）。 */
    public static byte[] generateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    /**
     * 钥匙的文本形态：64 位十六进制或 base64（≥32 字节），都收；其余 null
     * （secrets.rs:47-58 parse_key）。
     */
    public static byte[] parseKey(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        byte[] bytes;
        if (t.length() == 64 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            bytes = new byte[32];
            for (int i = 0; i < 32; i++) {
                bytes[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
            }
        } else {
            try {
                bytes = Base64.getDecoder().decode(t);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return bytes.length == 32 ? bytes : null;
    }

    /** 钥匙写盘的文本形态：64 位十六进制（secrets.rs:60）。 */
    public static String keyToHex(byte[] key) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : key) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 封印。幂等：已封印的原样返回；没装钥匙时原样返回（secrets.rs:69-83）。 */
    public String seal(String plain) {
        if (isSealed(plain) || key == null) {
            return plain;
        }
        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            // doFinal 输出 = 密文 ‖ tag，与 Rust aes-gcm 的 encrypt 输出同形
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE_LEN + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(ct, 0, out, NONCE_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (java.security.GeneralSecurityException e) {
            // AES-GCM 对内存输入不会失败（secrets.rs:79 expect 同一断言）
            throw new IllegalStateException("AES-GCM encryption failed on in-memory input", e);
        }
    }

    /**
     * 开封。没有前缀的按明文原样返回；有前缀而钥匙不对或被改过，报错不回乱码
     * （secrets.rs:87-105）。错误文案对齐 Rust（进日志/last_error 的可读原因）。
     */
    public String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw AppException.other(
                    "a sealed secret was read before the secret key was loaded", null);
        }
        String body = stored.substring(PREFIX.length());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw AppException.other("a sealed secret is not valid base64", e);
        }
        if (bytes.length < NONCE_LEN) {
            throw AppException.other("a sealed secret is too short", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_LEN));
            byte[] plain = cipher.doFinal(bytes, NONCE_LEN, bytes.length - NONCE_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            // AEADBadTagException（钥匙不对/被改）是其中最常见的形态，统一一句可读原因
            throw AppException.other("a sealed secret does not open with this key "
                    + "(wrong UTOPIA_SECRET_KEY, or the value was tampered with)", e);
        }
    }

    public String sealOpt(String plain) {
        return plain == null ? null : seal(plain);
    }

    public String openOpt(String stored) {
        return stored == null ? null : open(stored);
    }

    /**
     * 把 JSON 对象里给定键的字符串值封印（就地）；非字符串值不动（secrets.rs:116-125）。
     * null/非对象入参原样不动。
     */
    public void sealJsonKeys(JsonNode value, String[] keys) {
        if (value == null || !value.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) value;
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isTextual()) {
                obj.put(k, seal(v.asText()));
            }
        }
    }

    /** {@link #sealJsonKeys} 的反向：给定键的字符串值开封（secrets.rs:128-138）。 */
    public void openJsonKeys(JsonNode value, String[] keys) {
        if (value == null || !value.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) value;
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isTextual()) {
                obj.put(k, open(v.asText()));
            }
        }
    }
}
