package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 单点登录域 mapper（对齐旧仓库 oidc_routes.rs 内嵌 SQL，逐字搬迁见
 * resources/mapper/OidcMapper.xml，每条注释标 oidc_routes.rs 行号）。
 * 流程编排（state/PKCE/nonce 生成、一次性消费语义）在 server 侧 OidcController。
 */
@Mapper
public interface OidcMapper {

    /** 过期半途弃单的流程行扫除：oidc_routes.rs:328-330。 */
    int deleteExpiredFlows();

    /** 在途流程数（MAX_PENDING_FLOWS 闸门）：oidc_routes.rs:331-333。 */
    long countFlows();

    /** 落一次登录/绑定尝试的服务端状态：oidc_routes.rs:343-353。 */
    int insertFlow(@Param("state") String state, @Param("nonce") String nonce,
                   @Param("verifier") String verifier, @Param("linkUserId") UUID linkUserId,
                   @Param("ttlSeconds") double ttlSeconds);

    /** 回调一次性消费（DELETE...RETURNING；无行=未知或过期 state）：oidc_routes.rs:630-636。 */
    FlowRow consumeFlow(@Param("state") String state);

    /** 这个 subject 现在绑在谁身上（无=null）：oidc_routes.rs:719-725。 */
    UUID selectIdentityHolder(@Param("issuer") String issuer, @Param("subject") String subject);

    /** 建立绑定（PK/UNIQUE 撞车由调用方按约束名分诊）：oidc_routes.rs:732-737。 */
    int insertIdentity(@Param("issuer") String issuer, @Param("subject") String subject,
                       @Param("userId") UUID userId);

    /** 登录映射——只认显式绑定且账号未停用：oidc_routes.rs:527-534 / 692-699。 */
    UUID selectLinkedActiveUser(@Param("issuer") String issuer, @Param("subject") String subject);

    /** 我自己绑的 subject（无=null）：oidc_routes.rs:769-775。 */
    String selectSubjectForUser(@Param("issuer") String issuer, @Param("userId") UUID userId);

    /** 解绑（行数 0=没绑过）：oidc_routes.rs:784-788。 */
    int deleteIdentityForUser(@Param("issuer") String issuer, @Param("userId") UUID userId);

    /** 管理员列表（jsonb_build_object 行）：oidc_routes.rs:813-821。 */
    List<String> selectIdentities(@Param("issuer") String issuer, @Param("orgId") UUID orgId);

    /** 管理员解绑——限本组织（跨组织 404 而非 403，不泄露别组织账号存在性）：oidc_routes.rs:838-846。 */
    int deleteIdentityInOrg(@Param("issuer") String issuer, @Param("userId") UUID userId,
                            @Param("orgId") UUID orgId);

    /** 过期换会话记录扫除：oidc_routes.rs:504-506。 */
    int deleteExpiredExchanges();

    /** 一次性换会话记账（ON CONFLICT DO NOTHING，0 行=换过了）：oidc_routes.rs:508-520。 */
    int insertExchange(@Param("tokenHash") String tokenHash,
                       @Param("expiresAtSeconds") double expiresAtSeconds);

    /** 一次流程行的消费结果（RETURNING 三列）。 */
    record FlowRow(String nonce, String verifier, UUID linkUserId) {
    }
}
