package tech.seexun.jknow.web.knowledge.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查（对齐旧仓库 api/mod.rs:638-640）：GET /api/v1/health 无鉴权回
 * {@code {"status":"ok","name":"utopia","version":<CARGO_PKG_VERSION>}}。
 * version 从配置注入（application.yml jknow.health.version），与 Rust 打包版本对齐。
 * 另保留 M0 骨架自检桩 GET /health（无契约步，仅构建自检历史口径）。
 */
@RestController
public class HealthController {

    private final String version;

    public HealthController(@Value("${jknow.health.version}") String version) {
        this.version = version;
    }

    /** 对等响应体：status/name/version 三字段。 */
    record HealthResponse(String status, String name, String version) {
    }

    @GetMapping("/api/v1/health")
    HealthResponse health() {
        return new HealthResponse("ok", "utopia", version);
    }

    /** M0 构建自检桩（非契约路由）。 */
    record M0StubResponse(String status, String impl) {
    }

    @GetMapping("/health")
    M0StubResponse m0Health() {
        return new M0StubResponse("ok", "java-m0");
    }
}
