package tech.seexun.jknow.web.knowledge.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.seexun.jknow.common.core.domain.model.LoginUser;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 身份桥接（P1 任务 7，方案 B）：若依 LoginUser → 业务 {@link UserRow} /
 * {@link ClientContext}，装配进旧 SessionFilter 的两个请求属性
 * （{@link SessionFilter#USER_ATTR}/{@link SessionFilter#CLIENT_ATTR}），
 * 业务引用面（约 30 处 getAttribute + 审计调用）零改动。
 *
 * <p>装配语义对齐旧 SessionFilter：
 * <ul>
 * <li>CLIENT_ATTR 无条件捕获（匿名也装配——审计 ip/ua 不挑人）；</li>
 * <li>USER_ATTR 仅在若依鉴权通过（principal 为 LoginUser）且 sys_user 在职
 *     （del_flag='0' AND status='0'，等价旧 users.deactivated_at IS NULL）时挂；</li>
 * <li>仅 REQUEST 派发（/error 转发与异步派发不再装配）。</li>
 * </ul>
 *
 * <p>挂接位置：若依 SecurityConfig 的 filter 链，JwtAuthenticationTokenFilter 之后
 * （见 jknow-framework SecurityConfig 的 addFilterAfter；本类不注册为独立 servlet filter，
 * 由 ClientContextBridgeConfig 显式关停 Boot 自动注册，防止先于安全链执行置位
 * OncePerRequestFilter 的 already-filtered 标记导致链内装配被跳过）。
 * pt- 双通（PAT 与若依 token 并存）归 P1 任务 8。
 */
public class ClientContextBridgeFilter extends OncePerRequestFilter {

    private final UtopiaUuidResolver resolver;

    public ClientContextBridgeFilter(UtopiaUuidResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getDispatcherType() != DispatcherType.REQUEST;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.setAttribute(SessionFilter.CLIENT_ATTR, ClientContext.capture(request));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            UserRow user = resolver.userOf(loginUser.getUserId());
            if (user != null) {
                request.setAttribute(SessionFilter.USER_ATTR, user);
            }
        }
        chain.doFilter(request, response);
    }
}
