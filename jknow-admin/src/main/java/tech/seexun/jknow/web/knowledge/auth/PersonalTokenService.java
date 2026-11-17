package tech.seexun.jknow.web.knowledge.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.TokenView;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.TokensMapper;

/**
 * 个人访问令牌的签发/验签（P1 任务 8，用户拍板形态：<b>JWT 载体 + 源验签语义</b>）。
 *
 * <p>对源仓 utopia-server 的改写点（源 TokensService 是不透明随机串 {@code utp_pat_+hex}）：
 * <ul>
 *   <li><b>前缀</b> {@code utp_pat_} → {@code pt-}（P1 计划钦定，日志里与若依会话 token 分得清）；</li>
 *   <li><b>载体</b>：明文 = {@code pt-} + JWT（jjwt 0.13，HS256）。jti 承载 32 字节 CSPRNG
 *       （对齐源「两个 v4 拼起来 ≈ 244 位熵」的量级），sub=users.id、uid=若依 userId——
 *       身份载体信息，<b>不作为判据</b>；密钥经 HKDF-SHA256 从若依 {@code token.secret}
 *       派生子密钥（info="pt"），与若依会话 JWT 的签发密钥隔离（同一配置两把钥匙）；</li>
 *   <li><b>验签语义逐字保持源 tokens.rs:147-176</b>：sha256 摘要查 {@code personal_tokens}
 *       表，撤销/过期在 SQL 里判（取回来再比较的话，取回与比较之间被撤掉的令牌照样能过，
 *       而 MCP 的连接是长命的）；JWT 侧签名校验只是载体完整性兜底，有效期判定以表行为准
 *       （exp 镜像表行 expires_at，双闸同源不冲突）。</li>
 * </ul>
 *
 * <p>库里仍只存 sha256 摘要（明文只在签发响应里出现一次）、撤销打戳不删行、
 * {@code last_used_at} 由 authenticate 顺手写——三条源语义原样。
 */
@Service
public class PersonalTokenService {

    /** P1 计划钦定的前缀（源 tokens.rs:22 PREFIX 是 utp_pat_，改写理由见类注）。 */
    public static final String PREFIX = "pt-";
    /** tokens.rs:24 SHOWN：列表里露出的前缀长度（token_prefix 列给人认）。 */
    private static final int SHOWN = 16;
    /** HKDF info（与若依会话密钥的隔离标签）。 */
    private static final byte[] HKDF_INFO = "pt".getBytes(StandardCharsets.UTF_8);
    /** HKDF 输出长度（HS256 密钥 32 字节）。 */
    private static final int HKDF_LEN = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final TokensMapper tokens;
    private final UtopiaUuidResolver resolver;

    public PersonalTokenService(@Value("${token.secret}") String sessionSecret,
                                TokensMapper tokens, UtopiaUuidResolver resolver) {
        this.key = Keys.hmacShaKeyFor(
                hkdfSha256(sessionSecret.getBytes(StandardCharsets.UTF_8), HKDF_INFO, HKDF_LEN));
        this.tokens = tokens;
        this.resolver = resolver;
    }

    /**
     * tokens.rs:69-118 issue（源 TokensService.issue 同位）：名字 1-64 字符（码点）、
     * scope 只认 read|write；返回 (视图, 明文)——明文是它唯一一次出现。
     * uid claim 取签发时若依主档（桥接在位），判据身份仍是 sub/表行 user_id。
     */
    public Issued issue(UserRow user, String name, String scope, List<UUID> kbIds,
                        OffsetDateTime expiresAt) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 64) {
            throw AppException.invalid("bad_token_name", "Token name must be 1-64 characters");
        }
        if (!"read".equals(scope) && !"write".equals(scope)) {
            throw AppException.invalid("bad_token_scope", "Scope must be read or write");
        }
        String plain = mint(user.id(), resolver.bindingOf(user.id()), expiresAt);
        TokenView view = tokens.insertToken(Uuids.nowV7(), user.id(), trimmed, hash(plain),
                plain.substring(0, PREFIX.length() + SHOWN), scope, kbIds, expiresAt);
        return new Issued(view, plain);
    }

    /** tokens.rs:130-145 revoke（源 TokensService.revoke 同位）：打戳不删行；0 行 → NotFound。 */
    public void revoke(UUID userId, UUID tokenId) {
        if (tokens.stampRevoked(userId, tokenId) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /**
     * tokens.rs:147-176 authenticate（源 TokensService.authenticate 同位）：明文 →
     * 这是谁、准干到哪一步。JWT 签名不对/摘要查不到/已撤/已过期一律 401
     * （哈希对不上、撤销、过期都在 SQL 里判；sub 与表行 user_id 互证，防载体被移花接木）。
     */
    public Authenticated authenticate(String plain) {
        if (plain == null || !plain.startsWith(PREFIX)) {
            throw AppException.UNAUTHORIZED;
        }
        String subject;
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(plain.substring(PREFIX.length())).getPayload();
            subject = claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw AppException.UNAUTHORIZED;
        }
        TokensMapper.AuthRow row = tokens.authenticate(hash(plain));
        if (row == null || !row.userId().toString().equals(subject)) {
            throw AppException.UNAUTHORIZED;
        }
        return new Authenticated(row.tokenId(), row.userId(), row.scope(), row.kbIds());
    }

    /** 铸一枚：pt- + JWT（jti=32 字节随机 hex，sub=utopia uuid，uid=若依 userId）。 */
    private String mint(UUID userId, UtopiaUuidResolver.Binding binding, OffsetDateTime expiresAt) {
        var builder = Jwts.builder()
                .id(randomHex(64))
                .subject(userId.toString())
                .issuedAt(new Date());
        if (binding != null) {
            builder = builder.claim("uid", binding.userId());
        }
        if (expiresAt != null) {
            // 镜像表行（权威判定在 SQL：exp 与 expires_at 同源同值，双闸不冲突）
            builder = builder.expiration(Date.from(expiresAt.toInstant()));
        }
        return PREFIX + builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /** tokens.rs:61-66 hash：sha256 的十六进制小写（与源/库存约定逐字一致）。 */
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

    /** RFC 5869 HKDF-SHA256（salt 空 → 0x00*32；L≤32 单块即止），无第三方依赖。 */
    static byte[] hkdfSha256(byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            byte[] t = mac.doFinal();
            if (length <= 32) {
                return java.util.Arrays.copyOf(t, length);
            }
            throw new IllegalArgumentException("HKDF length > 32 unsupported");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int chars) {
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            out.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return out.toString();
    }

    /** issue 的返回：视图 + 只出现一次的明文（源 TokensService.Issued 同形）。 */
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
