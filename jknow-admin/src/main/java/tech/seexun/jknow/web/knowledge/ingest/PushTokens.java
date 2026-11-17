package tech.seexun.jknow.web.knowledge.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * api/statements 来源的推送密钥（sources_routes.rs:17-43 逐字语义）。
 *
 * <p><b>常量时间比对</b>（push_key_matches）：挡的不是计时攻击——这条路上它打不动：
 * 密钥是服务端生成的 244 位随机串（两个 v4 拼起来），比对又发生在一次数据库往返之后，
 * 毫秒级的网络与查询抖动盖住的是纳秒级的差异，真正在保护这把密钥的是熵。这样写是
 * 为了读代码的人不必重新推一遍上面那段：仓库里另外两条令牌路径都不做明文比对
 * （个人令牌存 sha256 按哈希查、会话走 JWT 验签），这里是唯一要拿明文对明文的地方，
 * 那就让它自己看起来是想过的。长度不等直接返回——等长比对才有意义，密钥长度固定
 * 不是秘密的一部分，短路不泄漏任何东西。
 */
public final class PushTokens {

    private PushTokens() {
    }

    /** sources_routes.rs:18-20：`utp_` + 两个 v4（simple 形态，64 位十六进制）。 */
    public static String newIngestToken() {
        return "utp_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    /** sources_routes.rs:34-43 push_key_matches（{@link MessageDigest#isEqual} 即 ct_eq）。 */
    public static boolean pushKeyMatches(String stored, String offered) {
        if (stored == null) {
            return false;
        }
        if (stored.length() != offered.length()) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                offered.getBytes(StandardCharsets.UTF_8));
    }
}
