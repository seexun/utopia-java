package tech.seexun.jknow.web.knowledge.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.core.domain.entity.SysUser;
import tech.seexun.jknow.common.core.domain.model.LoginUser;

/**
 * pt- 个人令牌的若依安全链兜底（P1 任务 8）：<b>Authorization: Bearer pt-...</b> 时
 * 验个人令牌（摘要查表 + 未撤 + 未过期，见 {@link PersonalTokenService#authenticate}），
 * 过了就以「发它的人」的若依身份进链——构造 {@link LoginUser}（userId=utopia_uuid
 * 反查 sys_user 的主档，permissions=knowledge:*）塞 SecurityContext；身后的
 * {@link ClientContextBridgeFilter} 照常把 LoginUser 折算成业务 UserRow/ClientContext
 * 请求属性，业务端点零改动。
 *
 * <p>链序（SecurityConfig 实挂）：JwtAuthenticationTokenFilter 之后、桥接之前——
 * 若依 token 先解析（pt- 串在若依 parser 里解析失败被吞，不会 401），轮到本 filter
 * 时凭 pt- 前缀兜底；非 pt- 前缀（若依 token/无凭据）直接放行给若依链。
 *
 * <p>pt- 凭据失效（坏签/查无此令牌/已撤/已过期/发令人已不在职）→ 401，响应体保持
 * 源 SessionFilter 的契约形状 {@code {"error":"Not signed in or invalid credentials"}}。
 * pt- 是长命的无状态凭据（无 Redis 会话），LoginUser 不落缓存——撤销靠表行，
 * 下一次请求立即生效（源 tokens.rs 的语义）。
 */
public class PersonalTokenAuthFilter extends OncePerRequestFilter {

    private final PersonalTokenService tokens;
    private final UtopiaUuidResolver resolver;

    public PersonalTokenAuthFilter(PersonalTokenService tokens, UtopiaUuidResolver resolver) {
        this.tokens = tokens;
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅 REQUEST 派发（与 ClientContextBridgeFilter 同口径：/error 转发与异步派发不再鉴权）
        return request.getDispatcherType() != DispatcherType.REQUEST;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String raw = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        if (raw == null || !raw.startsWith(PersonalTokenService.PREFIX)) {
            // 非 pt- 凭据：放行给若依链（jwt filter 已跑过，这里不再碰）
            chain.doFilter(request, response);
            return;
        }
        PersonalTokenService.Authenticated auth;
        try {
            auth = tokens.authenticate(raw);
        } catch (AppException e) {
            unauthorized(response);
            return;
        }
        // 发令人以发令时的 utopia 身份行事；uuid 反查 sys_user（在职口径 del_flag='0'
        // AND status='0'，源 accounts.rs:234-241 语义）。注意：resolver 的反查带进程内
        // 缓存、无失效（T7 裁定的 YAGNI 同口径）——停用/解绑的生效窗口到进程重启，
        // 不是「下一次请求立即生效」；表行的撤销/过期（authenticate 的 SQL 判定）才是
        // 立即生效面。查无此人（首次反查即不在职）= 401。
        UtopiaUuidResolver.Binding binding = resolver.bindingOf(auth.userId());
        if (binding == null) {
            unauthorized(response);
            return;
        }
        SysUser sysUser = new SysUser();
        sysUser.setUserId(binding.userId());
        sysUser.setDeptId(binding.deptId());
        sysUser.setUserName(binding.userName());
        sysUser.setNickName(binding.nickName());
        LoginUser loginUser = new LoginUser(binding.userId(), binding.deptId(), sysUser,
                java.util.Set.of("knowledge:*"));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    /** 401 形状对等（源 SessionFilter.unauthorized → auth_routes.rs 401 体逐字）。 */
    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Not signed in or invalid credentials\"}");
    }
}
