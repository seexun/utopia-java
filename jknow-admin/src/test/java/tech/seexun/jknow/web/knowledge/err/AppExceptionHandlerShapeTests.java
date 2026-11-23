package tech.seexun.jknow.web.knowledge.err;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tech.seexun.jknow.common.exception.base.BaseException;
import tech.seexun.jknow.common.exception.user.CaptchaExpireException;
import tech.seexun.jknow.common.utils.spring.SpringUtils;

/** 错误形状对齐（2026-11-23 批）：未知路由 404 与若依 BaseException 族分流的双 handler 钉形状。 */
class AppExceptionHandlerShapeTests {

    private final AppExceptionHandler handler = new AppExceptionHandler(new ObjectMapper());

    @BeforeAll
    static void bootstrapMessageUtils() {
        // BaseException.getMessage() 走 MessageUtils → SpringUtils 静态 beanFactory，
        // 纯单测无 Spring 上下文时为 null（NPE）——借 SpringUtils 专为非 Spring 环境留的
        // 入口注册真实 i18n/messages 束，测试即钉线上字面量（user.jcaptcha.expire=验证码已失效）。
        // 用裸 DefaultListableBeanFactory：StaticApplicationContext 构造即内置 StaticMessageSource，
        // 同名 'messageSource' 单例注册会撞车。
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("classpath:i18n/messages");
        // 镜像生产：Boot 自动配置默认 UTF-8 读 properties；不设则按 ISO-8859-1 读出 mojibake
        messages.setDefaultEncoding("UTF-8");
        beanFactory.registerSingleton("messageSource", messages);
        new SpringUtils().postProcessBeanFactory(beanFactory);
    }

    @Test
    void unknownRouteIs404NotJsonInternalError() {
        // Spring Framework 7.0.x 实况：构造器三参 (HttpMethod, resourcePath, servletContextPath)
        // （简报骨架的两参构造在该版本不存在，testCompile 红灯时报错校准）
        var res = handler.noRoute(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/v1/nonexistent", ""));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("{\"error\":\"Not found\"}", res.getBody());
    }

    @Test
    void ruoyiUserExceptionFamilyGoesBackAsMsgCode() {
        var res = handler.service(new CaptchaExpireException());
        assertEquals(HttpStatus.OK, res.getStatusCode());
        // msg 字面量实测（jknow-admin/src/main/resources/i18n/messages.properties:4）：
        // user.jcaptcha.expire=验证码已失效（简报猜的「验证码不存在或已过期」不实，以实测为准）
        String body = res.getBody();
        assertTrue(body != null && body.contains("\"msg\":\"验证码已失效\"") && body.contains("\"code\":500"),
                "shape must be {\"msg\":\"验证码已失效\",\"code\":500}, got: " + body);
    }
}
