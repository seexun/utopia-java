package tech.seexun.jknow.web.knowledge.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * pt- 兜底 filter 装配（P1 任务 8）：实例由 jknow-framework SecurityConfig 以
 * addFilterAt(UsernamePasswordAuthenticationFilter) 挂进若依安全链
 * （JwtAuthenticationTokenFilter 之后、ClientContextBridgeFilter 之前）——
 * 与 {@link ClientContextBridgeConfig} 同理，这里必须显式关停 Boot 对 Filter bean
 * 的 servlet 自动注册（servlet 层注册会在安全链之外先行执行，OncePerRequestFilter
 * 的 already-filtered 标记会让链内兜底被跳过、且彼时链序语义不成立）。
 */
@Configuration
public class PersonalTokenAuthConfig {

    @Bean
    public PersonalTokenAuthFilter personalTokenAuthFilter(PersonalTokenService tokens,
                                                            UtopiaUuidResolver resolver) {
        return new PersonalTokenAuthFilter(tokens, resolver);
    }

    @Bean
    public FilterRegistrationBean<PersonalTokenAuthFilter> personalTokenAuthFilterRegistration(
            PersonalTokenAuthFilter filter) {
        FilterRegistrationBean<PersonalTokenAuthFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
