package tech.seexun.jknow.framework.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.filter.CorsFilter;
import tech.seexun.jknow.framework.config.properties.PermitAllUrlProperties;
import tech.seexun.jknow.framework.security.filter.JwtAuthenticationTokenFilter;
import tech.seexun.jknow.framework.security.handle.AuthenticationEntryPointImpl;
import tech.seexun.jknow.framework.security.handle.LogoutSuccessHandlerImpl;

/**
 * spring security配置
 * 
 * @author jknow
 */
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@Configuration
public class SecurityConfig
{
    /**
     * 认证失败处理类
     */
    @Autowired
    private AuthenticationEntryPointImpl unauthorizedHandler;

    /**
     * 退出处理类
     */
    @Autowired
    private LogoutSuccessHandlerImpl logoutSuccessHandler;

    /**
     * token认证过滤器
     */
    @Autowired
    private JwtAuthenticationTokenFilter authenticationTokenFilter;
    
    /**
     * 跨域过滤器
     */
    @Autowired
    private CorsFilter corsFilter;

    /**
     * 允许匿名访问的地址
     */
    @Autowired
    private PermitAllUrlProperties permitAllUrl;

    /**
     * 业务身份桥接 filter（jknow-admin 的 web.knowledge.auth.ClientContextBridgeFilter，
     * bean 名限定注入——framework 不依赖 admin 模块；桥 bean 不存在时安全链保持原样）。
     * 挂在 JwtAuthenticationTokenFilter 之后：若依 token 建立 LoginUser 后折算业务
     * UserRow/ClientContext 请求属性（P1 任务 7 方案 B）。
     */
    @Autowired(required = false)
    @Qualifier("clientContextBridgeFilter")
    private Filter clientContextBridgeFilter;

    /**
     * pt- 个人令牌兜底 filter（jknow-admin 的 web.knowledge.auth.PersonalTokenAuthFilter，
     * 同上 bean 名限定注入）。挂在 JwtAuthenticationTokenFilter 之后（P1 任务 8 认证收口）：
     * 若依 parser 认不出的 pt- 前缀 Bearer 在这里验个人令牌并建立 LoginUser，身后的
     * 桥接 filter 照常折算业务身份——对若依 token 与 pt- 双通。
     */
    @Autowired(required = false)
    @Qualifier("personalTokenAuthFilter")
    private Filter personalTokenAuthFilter;

	/**
	 * 身份验证实现
	 */
	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception 
	{
		return authenticationConfiguration.getAuthenticationManager();
	}

    /**
     * anyRequest          |   匹配所有请求路径
     * access              |   SpringEl表达式结果为true时可以访问
     * anonymous           |   匿名可以访问
     * denyAll             |   用户不能访问
     * fullyAuthenticated  |   用户完全认证可以访问（非remember-me下自动登录）
     * hasAnyAuthority     |   如果有参数，参数表示权限，则其中任何一个权限可以访问
     * hasAnyRole          |   如果有参数，参数表示角色，则其中任何一个角色可以访问
     * hasAuthority        |   如果有参数，参数表示权限，则其权限可以访问
     * hasIpAddress        |   如果有参数，参数表示IP地址，如果用户IP和参数匹配，则可以访问
     * hasRole             |   如果有参数，参数表示角色，则其角色可以访问
     * permitAll           |   用户可以任意访问
     * rememberMe          |   允许通过remember-me登录的用户访问
     * authenticated       |   用户登录后可访问
     */
    @Bean
    protected SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception
    {
        // pt- 个人令牌兜底（P1 任务 8）：addFilterAt 站 UsernamePasswordAuthenticationFilter
        // 本槽（该槽在无表单登录的链里无实例，占位不撞车）——链序收口为
        // jwt(-1) → pt 兜底(0) → 桥接(+1)：若依 parser 先跑（pt- 串解析失败被其
        // getLoginUser 吞掉不 401），pt- 凭据在兜底位换装 LoginUser，桥接最后统一
        // 折算业务 UserRow/ClientContext——若依 token 与 pt- 对业务面同构。锚同样
        // 必须是有注册序的标准 filter（自定义类不行）。bean 缺席（非 admin 装配形态）时链不变。
        if (personalTokenAuthFilter != null)
        {
            httpSecurity.addFilterAt(personalTokenAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }
        // 业务身份桥接（P1 任务 7）：若依 token / pt- 建立的 LoginUser → 业务
        // UserRow/ClientContext 请求属性。桥挂在 UsernamePasswordAuthenticationFilter 之后
        // （即链序 … → pt 兜底(0) → 桥接(+1)，LoginUser 已就位才有得折算）。
        if (clientContextBridgeFilter != null)
        {
            httpSecurity.addFilterAfter(clientContextBridgeFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return httpSecurity
            // CSRF禁用，因为不使用session
            .csrf(csrf -> csrf.disable())
            // 禁用HTTP响应标头
            .headers((headersCustomizer) -> {
                headersCustomizer.cacheControl(cache -> cache.disable()).frameOptions(options -> options.sameOrigin());
            })
            // 认证失败处理类
            .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
            // 基于token，所以不需要session
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 注解标记允许匿名访问的url
            .authorizeHttpRequests((requests) -> {
                // ASYNC/ERROR 转发放行（P1 任务 9 冒烟暴露）：StreamingResponseBody（kb 导出）
                // 流完回容器的 ASYNC dispatch 会再过一遍安全链，而 OncePerRequestFilter 族的
                // jwt/pt-/桥接 filter 默认不跑二趟——上下文为空即 Access Denied，committed
                // 响应写不了 401，chunked 终止块发不出去（curl 18「缺一段」）。鉴权在
                // REQUEST dispatch 已完成，转发放行不弱化请求面；ERROR 同放（错误页可渲染）。
                requests.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                permitAllUrl.getUrls().forEach(url -> requests.requestMatchers(url).permitAll());
                // 对于登录login 注册register 验证码captchaImage 允许匿名访问
                requests.requestMatchers("/login", "/register", "/captchaImage").permitAll()
                    // 静态资源，可匿名访问
                    .requestMatchers(HttpMethod.GET, "/", "/*.html", "/**.html", "/**.css", "/**.js", "/profile/**").permitAll()
                    .requestMatchers("/swagger-ui.html", "/v3/api-docs/**", "/swagger-ui/**", "/druid/**").permitAll()
                    // 除上面外的所有请求全部需要鉴权认证
                    .anyRequest().authenticated();
            })
            // 添加Logout filter
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(logoutSuccessHandler))
            // 添加JWT filter
            .addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
            // 添加CORS filter
            .addFilterBefore(corsFilter, JwtAuthenticationTokenFilter.class)
            .addFilterBefore(corsFilter, LogoutFilter.class)
            .build();
    }

    /**
     * 强散列哈希加密实现
     */
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder()
    {
        return new BCryptPasswordEncoder();
    }
}
