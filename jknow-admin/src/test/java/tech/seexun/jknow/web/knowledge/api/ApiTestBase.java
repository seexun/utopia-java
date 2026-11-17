package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tech.seexun.jknow.common.core.domain.entity.SysUser;
import tech.seexun.jknow.common.core.domain.model.LoginUser;
import tech.seexun.jknow.web.knowledge.store.StoreTestBase;

/**
 * 业务 HTTP 面重建基类（P1 任务 8，T7 交接兑现）：源仓 TestRestTemplate + JwtService
 * utopia_token cookie 的形态退役——重建为 <b>MockMvc + LoginUser+SecurityContext</b>
 * 直挂（{@link SecurityMockMvcRequestPostProcessors#authentication}），等价
 * JwtAuthenticationTokenFilter 认证后的 principal 形状；请求照常穿过完整若依安全链
 * （jwt filter → pt- 兜底 → ClientContextBridgeFilter），桥接把 LoginUser 折算成业务
 * UserRow/ClientContext 请求属性，业务端点零改动。Redis 会话面不参与（业务语义不读它）。
 *
 * <p>401 形状注意：无凭据/若依面拒绝走若依 AuthenticationEntryPoint（{@code {"msg":...}}
 * 形状）；controller 抛的 AppException 仍走业务 AppExceptionHandler（{@code {"error":...}}
 * 源形状）——与 T7 收口后的生产实况一致，断言按各自形状写。
 */
@AutoConfigureMockMvc
public abstract class ApiTestBase extends StoreTestBase {

    @Autowired
    protected MockMvc mockMvc;

    /** 业务语系（Jackson 2）树解析器（KnowledgeJacksonConfig 的 bean）。 */
    @Autowired
    protected com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** 若依登录态铸造：utopia_uuid → sys_user 主档 → LoginUser 塞 SecurityContext。 */
    protected RequestPostProcessor ruoyi(UUID utopiaUuid) {
        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM sys_user WHERE utopia_uuid = ?", Long.class, utopiaUuid);
        SysUser sysUser = new SysUser();
        sysUser.setUserId(userId);
        LoginUser loginUser = new LoginUser(userId, null, sysUser, java.util.Set.of("*:*:*"));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }

    /** 请求结果三元组（status + 解析体；非 JSON 体时 body 为 null、raw 兜底）。 */
    public record Resp(int status, JsonNode body, String raw) {
        public int code() {
            return body != null && body.has("code") && body.get("code").isInt()
                    ? body.get("code").asInt() : status;
        }
    }

    protected Resp get(String path, UUID as) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path).accept(MediaType.APPLICATION_JSON), as);
    }

    protected Resp post(String path, UUID as, String json) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json == null ? "" : json).accept(MediaType.APPLICATION_JSON), as);
    }

    protected Resp put(String path, UUID as, String json) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(path).contentType(MediaType.APPLICATION_JSON)
                .content(json == null ? "" : json).accept(MediaType.APPLICATION_JSON), as);
    }

    protected Resp delete(String path, UUID as) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(path).accept(MediaType.APPLICATION_JSON), as);
    }

    protected Resp patch(String path, UUID as, String json) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch(path).contentType(MediaType.APPLICATION_JSON)
                .content(json == null ? "" : json).accept(MediaType.APPLICATION_JSON), as);
    }

    private Resp perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                         UUID as) throws Exception {
        if (as != null) {
            req = req.with(ruoyi(as));
        } else {
            // 无登录态请求：清掉同线程前一个 postprocessor 请求可能残留的 TestSecurityContextHolder
            org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        }
        var result = mockMvc.perform(req).andReturn();
        String raw = result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                body = mapper.readTree(raw);
            } catch (Exception e) {
                body = mapper.createObjectNode().put("raw", raw);
            }
        }
        return new Resp(result.getResponse().getStatus(), body, raw);
    }
}
