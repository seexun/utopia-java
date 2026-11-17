package tech.seexun.jknow.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.TokenView;
import tech.seexun.jknow.store.mapper.TokensMapper;

/**
 * 个人访问令牌（tokens.rs 逐字，M5 任务 6 切流演练落位）。这一枚令牌是给 MCP 客户端
 * 用的：长命、配在别人机器上的一个文件里、以发它的人的身份行事——所以它必须撤得掉、
 * 过得了期、范围只能比这个人更小（0014 / 迁移 0017）。
 *
 * <ul>
 *   <li><b>明文只出现一次</b>：库里只有 sha256 哈希，拿到整张表也复原不出那串字符；</li>
 *   <li><b>撤销打戳不删行</b>：「这把钥匙存在过」要查得到——事后追查要问的第一件事；</li>
 *   <li><b>过期与撤销在 SQL 里判</b>（authenticate）：取回来再比较的话，取回与比较之间
 *       被撤掉的令牌照样能过，而 MCP 的连接是长命的。</li>
 * </ul>
 */
@Service
public class TokensService {

    /** tokens.rs:22 PREFIX：换个前缀是为了在日志里分得清（与 ingest token 同一做法）。 */
    public static final String PREFIX = "utp_pat_";
    /** tokens.rs:24 SHOWN：列表里露出的前缀长度。 */
    private static final int SHOWN = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private final TokensMapper tokens;

    public TokensService(TokensMapper tokens) {
        this.tokens = tokens;
    }

    /** tokens.rs:61-66 hash：sha256 的十六进制小写。 */
    static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * tokens.rs:69-118 issue。两个 v4 拼起来 ≈ 244 位熵；名字 1-64 字符（码点）、
     * scope 只认 read|write。返回 (视图, 明文)——明文是它唯一一次出现。
     */
    public Issued issue(UUID userId, String name, String scope, List<UUID> kbIds,
                        OffsetDateTime expiresAt) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 64) {
            throw AppException.invalid("bad_token_name", "Token name must be 1-64 characters");
        }
        if (!"read".equals(scope) && !"write".equals(scope)) {
            throw AppException.invalid("bad_token_scope", "Scope must be read or write");
        }
        String plain = PREFIX + randomHex(32) + randomHex(32);
        TokenView view = tokens.insertToken(Uuids.nowV7(), userId, trimmed, hash(plain),
                plain.substring(0, PREFIX.length() + SHOWN), scope, kbIds, expiresAt);
        return new Issued(view, plain);
    }

    /** tokens.rs:119-128 list（撤过的也列）。 */
    public List<TokenView> list(UUID userId) {
        return tokens.selectTokens(userId);
    }

    /** tokens.rs:130-145 revoke：只撤自己的、未撤的；0 行 → NotFound。 */
    public void revoke(UUID userId, UUID tokenId) {
        if (tokens.stampRevoked(userId, tokenId) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /**
     * tokens.rs:147-176 authenticate：明文 → 这是谁、准干到哪一步。查不到 = 401
     * （哈希对不上/已撤/已过期都在 SQL 里判）。
     */
    public Authenticated authenticate(String plain) {
        if (plain == null || !plain.startsWith(PREFIX)) {
            throw AppException.UNAUTHORIZED;
        }
        TokensMapper.AuthRow row = tokens.authenticate(hash(plain));
        if (row == null) {
            throw AppException.UNAUTHORIZED;
        }
        return new Authenticated(row.tokenId(), row.userId(), row.scope(), row.kbIds());
    }

    private static String randomHex(int chars) {
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            out.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return out.toString();
    }

    /** issue 的返回：视图 + 只出现一次的明文。 */
    public record Issued(TokenView view, String plain) {
    }

    /** tokens.rs:41-58 Authenticated：令牌身份 + 够到哪几个库（kbIds null = 全部）。 */
    public record Authenticated(UUID tokenId, UUID userId, String scope, List<UUID> kbIds) {

        /** tokens.rs:186-190 covers：限定过库的，够不着别的。 */
        public boolean covers(UUID kbId) {
            return kbIds == null || kbIds.contains(kbId);
        }

        /** tokens.rs:192-194 can_write。 */
        public boolean canWrite() {
            return "write".equals(scope);
        }
    }
}
