package tech.seexun.jknow.web.knowledge.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 桥接 filter 装配（P1 任务 7）：实例由 jknow-framework SecurityConfig 以
 * addFilterAfter 挂进若依安全链（JwtAuthenticationTokenFilter 之后）——
 * 因此这里必须显式关停 Boot 对 Filter bean 的 servlet 自动注册
 * （servlet 层注册会在安全链之外先行执行，OncePerRequestFilter 的
 * already-filtered 标记会让链内装配被跳过、且彼时 SecurityContext 尚未建立）。
 */
@Configuration
public class ClientContextBridgeConfig {

    @Bean
    public ClientContextBridgeFilter clientContextBridgeFilter(UtopiaUuidResolver resolver) {
        return new ClientContextBridgeFilter(resolver);
    }

    @Bean
    public FilterRegistrationBean<ClientContextBridgeFilter> clientContextBridgeFilterRegistration(
            ClientContextBridgeFilter filter) {
        FilterRegistrationBean<ClientContextBridgeFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
