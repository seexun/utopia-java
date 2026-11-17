package tech.seexun.jknow.web.knowledge;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 单 jar 前端托管（规格 W6 / §2）：utopia-web 的 Vite 产物 dist/ 由 utopia-server
 * 以 classpath:/static 托管（dist → target/classes/static 的拷贝在 pom 的
 * prepare-package 执行，dist 缺省（纯后端构建、未跑前端）时拷贝空转，本配置的
 * handler 挂着但 404，与"纯后端 jar"行为一致）。
 *
 * <p>**路由口径**：前端是 hash 路由（utopia-web/.env VITE_ROUTER_HISTORY=hash）——
 * 所有前端深链都是 /#/… 形态，HTTP 层只需要 "/" 一个入口（index.html）加静态素材，
 * **不需要 SPA fallback**（history 模式才需要把未配路径 forward 到 index.html，
 * 那会和 /api 的 404 JSON 契约冲突）。未配路径（含未知 /api/*）照旧走
 * NoHandlerFoundException → AppExceptionHandler 的 404 {"error":"Not found"}
 * （对齐 Rust axum api.fallback，api/mod.rs:622）——本配置只挂窄名单 pattern，
 * 不开 /** 兜底，契约零破坏。/api 与 /actuator（本工程无 actuator starter）均不在
 * handler 名单内，无劫持面。
 *
 * <p>dist 素材面（vite 产物）：/index.html（登录/壳入口）、/static/{js,css,png}/*
 * （素材目录恰好也叫 static，落在 classpath:/static/static/ 下）、
 * /favicon.ico、/logo.svg、/platform-config.json、/version。
 *
 * <p>**缓存口径**（终审 Minor-5）：hash 文件名的 /static/** 给
 * {@code public, max-age=31536000, immutable}；入口 HTML 与 version.json 给
 * {@code no-cache}（可存但每次回源验）——App.vue 的版本探针轮询 version.json
 * 才能第一时间看见新版本，入口 HTML 才能第一时间引用新 hash 的素材。
 */
@Configuration
public class StaticWebConfig implements WebMvcConfigurer {

    /** dist 内相对素材目录（index.html 以 /static/js/… 绝对路径引用） */
    private static final String[] ASSET_PREFIXES = {"/static/**"};

    /**
     * dist 根下的具名文件（favicon/logo/平台配置/版本探针）。
     * 版本探针的实际产物是 <b>version.json</b>（generate-version-file 产出，App.vue 每 5 分钟
     * 轮询它做新版本提示）——窄名单漏了它会让 jar 部署下探针每 5 分钟吃一个 404 JSON、
     * 新版本提示永不工作（终审 I-1）。
     */
    private static final String[] ROOT_FILES = {
        "/favicon.ico", "/logo.svg", "/platform-config.json", "/version.json", "/index.html"
    };

    /** hash 文件名（Vite 产物 /static/js/app-<hash>.js）：内容变 → 名字变，
     *  缓存给到极限——immutable 连协商请求都省掉（终审 Minor-5） */
    private static final CacheControl HASHED_ASSETS =
            CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();

    /** 具名文件（入口 HTML / 版本探针）：内容随时会换而路径不变——
     *  可存但每次必须回源验（no-cache = 过期前也要 revalidate） */
    private static final CacheControl ROOT_ENTRY_FILES = CacheControl.noCache();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(ASSET_PREFIXES)
                .addResourceLocations("classpath:/static/static/")
                .setCacheControl(HASHED_ASSETS);
        registry.addResourceHandler(ROOT_FILES)
                .addResourceLocations("classpath:/static/")
                .setCacheControl(ROOT_ENTRY_FILES);
    }

    /** 前端唯一入口：/ → index.html。ViewController 的 forward 走同一
     *  DispatcherServlet 重派发，命中上面的 /index.html 资源 handler（hash 路由下
     *  所有页面共用这一个 HTML）。注：不用 @RestController 返回 "forward:/…" 字符串
     *  ——那会被当响应体原样吐出去，forward 前缀只在视图名通道生效。 */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
