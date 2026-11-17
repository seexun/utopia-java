package tech.seexun.jknow.web.knowledge.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.store.TokensService;
import tech.seexun.jknow.store.entity.TokenView;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.TokensMapper;

/**
 * 个人访问令牌面（token_routes.rs 逐字——issue/list/revoke，M5 任务 6 切流演练落位：
 * 写路径此前随 MCP/PAT 面留在 Rust 侧，剧本 target:rust；列表行打戳不删行 tokens.rs:130，
 * 录制链路 token_prefix 掩码、行数确定性由剧本 ${run_id} 唯一账号钉死（M5 挂账核销））。
 */
@RestController
public class TokensController {

    private final TokensMapper tokens;
    private final TokensService tokenService;
    private final AuditService audit;

    public TokensController(TokensMapper tokens, TokensService tokenService,
                            AuditService audit) {
        this.tokens = tokens;
        this.tokenService = tokenService;
        this.audit = audit;
    }

    public record TokensResponse(List<TokenView> tokens) {
    }

    /** 我发过哪几把（撤过的也列——撤过这件事本身要看得见）。 */
    @GetMapping("/api/v1/me/tokens")
    public TokensResponse list(HttpServletRequest request) {
        UserRow user = user(request);
        return new TokensResponse(tokens.selectTokens(user.id()));
    }


    /**
     * token_routes.rs:63-91 issue：发一枚。**明文只在这一次的响应里出现**，之后库里
     * 只有哈希。token 字段只在这里出现一次，列表接口永远给不出它。
     */
    /**
     * token_routes.rs:17-30 IssueReq（serde Option 隐式可省——按 WorkspaceKbWriteController
     * 先例读 JsonNode 显式取键：全局 fail-on-missing-creator-properties 对可缺省项不友好）。
     */
    @PostMapping("/api/v1/me/tokens")
    public java.util.Map<String, Object> issue(@RequestBody com.fasterxml.jackson.databind.JsonNode body,
                                               HttpServletRequest request) {
        UserRow user = user(request);
        String name = body.path("name").asText(null);
        String scope = body.path("scope").asText(null);
        List<UUID> kbIds = null;
        if (body.path("kb_ids").isArray() && body.path("kb_ids").size() > 0) {
            kbIds = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode id : body.path("kb_ids")) {
                kbIds.add(UUID.fromString(id.asText()));
            }
        }
        long expiresInDays = body.path("expires_in_days").asLong(0);
        OffsetDateTime expiresAt = tokenExpiresAt(expiresInDays);
        TokensService.Issued issued = tokenService.issue(user.id(), name, scope,
                kbIds, expiresAt);
        audit.record(client(request), null, user.id(), "token.issued", "personal_token",
                issued.view().id(),
                "{\"name\": \"" + issued.view().name() + "\", \"scope\": \""
                        + issued.view().scope() + "\"}");
        return java.util.Map.of("token", issued.plain(), "info", issued.view());
    }

    /** token_routes.rs:40-59 token_expires_at：0 = 永不过期；负数拒。 */
    private static OffsetDateTime tokenExpiresAt(long expiresInDays) {
        if (expiresInDays < 0) {
            throw AppException.invalid("bad_token_expiry", "expires_in_days must be non-negative");
        }
        if (expiresInDays == 0) {
            return null;
        }
        try {
            return OffsetDateTime.now().plus(Duration.ofDays(expiresInDays));
        } catch (Exception e) {
            throw AppException.invalid("bad_token_expiry", "Token expiration date is out of range");
        }
    }

    /** token_routes.rs:101-118 revoke：打戳不删行。 */
    @DeleteMapping("/api/v1/me/tokens/{id}")
    public java.util.Map<String, Object> revoke(@PathVariable UUID id, HttpServletRequest request) {
        UserRow user = user(request);
        tokenService.revoke(user.id(), id);
        audit.record(client(request), null, user.id(), "token.revoked", "personal_token", id, "{}");
        return java.util.Map.of("ok", true);
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
