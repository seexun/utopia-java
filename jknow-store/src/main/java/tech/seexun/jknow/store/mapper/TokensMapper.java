package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.TokenView;

/**
 * 个人访问令牌 mapper（tokens.rs:61-176 逐字）。写/鉴权面 M5 任务 6 切流演练落位——
 * 此前 issue/revoke 与 PAT 鉴权随 MCP 面留在 Rust 侧（剧本六步 target:rust），演练翻出。
 */
@Mapper
public interface TokensMapper {

    /** 我发过哪几把（created_at 倒序；token_prefix 录制链路被掩码，明文永不出库）。 */
    List<TokenView> selectTokens(@Param("userId") UUID userId);

    /** tokens.rs:98-117 issue：INSERT RETURNING 视图九列（哈希/前缀由 service 算好）。 */
    TokenView insertToken(@Param("id") UUID id, @Param("userId") UUID userId,
                          @Param("name") String name, @Param("tokenHash") String tokenHash,
                          @Param("tokenPrefix") String tokenPrefix, @Param("scope") String scope,
                          @Param("kbIds") List<UUID> kbIds,
                          @Param("expiresAt") OffsetDateTime expiresAt);

    /** tokens.rs:132-145 revoke：打戳不删行；0 行 = 不存在/已撤 → NotFound 由 service 报。 */
    int stampRevoked(@Param("userId") UUID userId, @Param("id") UUID id);

    /**
     * tokens.rs:155-176 authenticate：**过期与撤销在 SQL 里判**，顺手写 last_used_at
     * （撤销之前人要答得出「这把还在用吗」，没有这个数没人敢撤）。查不到即 401。
     */
    AuthRow authenticate(@Param("tokenHash") String tokenHash);

    /** authenticate 的回行。 */
    record AuthRow(UUID tokenId, UUID userId, String scope, List<UUID> kbIds) {
    }
}
