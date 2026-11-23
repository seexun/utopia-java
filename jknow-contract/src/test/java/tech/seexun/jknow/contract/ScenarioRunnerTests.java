package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.contract.model.Scenario;

/**
 * P3 修复轮（审查 Important-1）：假 200 识别闸收窄的 Runner 层验证——ruoyi_login 剧本中
 * 带 {@code {msg,code:401}} 体的步：expect≠200（预期业务错误步，T2 auth 401 族/admin 403 族
 * 形态）不再被闸 abort、交 expect 兜底（实测整剧本绿跑落样本）；expect 200（若依 renderString
 * 假 200 形）与未声明 expect 的步仍被拦截。桩服务用 JDK 内嵌 HttpServer（/login 合法登录发
 * 顶层 token），零外部依赖。
 */
class ScenarioRunnerTests {

    /** 业务路径（/api/v1/auth/me）的 HTTP 状态：按用例切换假 200 形/契约对齐 401 形。 */
    private volatile int meHttpStatus;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startStub() throws IOException {
        meHttpStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> respond(exchange, 200,
                "{\"msg\":\"操作成功\",\"code\":200,\"token\":\"stub-token\"}"));
        server.createContext("/api/v1/auth/me", exchange -> respond(exchange, meHttpStatus,
                "{\"msg\":\"认证失败，无法访问系统资源\",\"code\":401}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Scenario ruoyi(String expectLine) {
        return ScenarioLoader.fromYamlString("""
                name: ruoyi-stub
                setup:
                  mode: ruoyi_login
                  username: admin
                  password: admin123
                steps:
                  - id: me-401
                    call: GET /api/v1/auth/me
                    %s
                """.formatted(expectLine));
    }

    /** 修复点：expect≠200 的预期业务错误步（{msg,code:401} 体）不被闸 abort——整剧本绿跑落样本。 */
    @Test
    void expect非200的业务错误步不误杀() {
        meHttpStatus = 401;
        List<RecordedStep> steps = new ScenarioRunner(base).run(ruoyi("expect: { status: 401 }"));
        assertEquals(1, steps.size());
        assertEquals(401, steps.get(0).status());
        assertEquals(Integer.valueOf(401), steps.get(0).expectedStatus());
    }

    /** 保护保持：若依 renderString 假 200 形（HTTP 200+code:401）在 expect 200 的步上仍拦截。 */
    @Test
    void expect200的业务错误步仍拦截() {
        ScenarioFailure e = assertThrows(ScenarioFailure.class,
                () -> new ScenarioRunner(base).run(ruoyi("expect: { status: 200 }")));
        assertTrue(e.getMessage().contains("业务失败"), e.getMessage());
        assertTrue(e.getMessage().contains("code=401"), e.getMessage());
    }

    /** 保护保持：未声明 expect 的步没有错误预期，业务码异常仍应早失败。 */
    @Test
    void 未声明expect的步仍受业务码闸保护() {
        ScenarioFailure e = assertThrows(ScenarioFailure.class,
                () -> new ScenarioRunner(base).run(ruoyi("")));
        assertTrue(e.getMessage().contains("业务失败"), e.getMessage());
        // 收窄的边界自证：同样的假 200 形，若步声明了 expect≠200，报错走 expect 而非业务码闸
        ScenarioFailure expectPath = assertThrows(ScenarioFailure.class,
                () -> new ScenarioRunner(base).run(ruoyi("expect: { status: 401 }")));
        assertTrue(expectPath.getMessage().contains("期望 401 实际 200"), expectPath.getMessage());
        assertFalse(expectPath.getMessage().contains("业务失败"), expectPath.getMessage());
    }
}
