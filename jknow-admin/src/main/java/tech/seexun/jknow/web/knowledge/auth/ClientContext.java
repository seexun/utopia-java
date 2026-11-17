package tech.seexun.jknow.web.knowledge.auth;

/**
 * 请求来源（对齐旧仓库 utopia-server/src/client_ctx.rs + utopia-store audit::ClientContext）：
 * 审计写入时取当前请求的客户端 IP 与 User-Agent。Rust 侧用 task-local 携带（调用点太多），
 * Java 侧等价物是 request attribute——SessionFilter 每请求捕获，审计服务读取。
 */
public record ClientContext(String ip, String userAgent) {

    /** X-Forwarded-For 第一段（最靠近客户端那跳）→ X-Real-IP → TCP 对端（client_ctx.rs:20-35）。 */
    static ClientContext capture(jakarta.servlet.http.HttpServletRequest request) {
        return new ClientContext(clientIp(request), userAgent(request));
    }

    private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    /** User-Agent 截断 256 字节、不切多字节字符（client_ctx.rs:39-53）。 */
    private static String userAgent(jakarta.servlet.http.HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.trim().isEmpty()) {
            return null;
        }
        String trimmed = ua.trim();
        byte[] bytes = trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 256) {
            return trimmed;
        }
        int end = 256;
        // 退到 UTF-8 字符边界：续字节 10xxxxxx 不是边界
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }
}
