package tech.seexun.jknow.web.knowledge.err;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tech.seexun.jknow.common.AppException;

/**
 * 统一错误形状（对齐旧仓库 utopia-server/src/error.rs:18-66 IntoResponse 全表）：
 * {@code {"error":"<msg>"}} + 可选 {@code code}/{@code detail}。
 * <pre>
 * Invalid      → 422  {"error", "code", "detail"?}
 * Validation   → 422  {"error"}
 * NotFound     → 404  {"error":"Not found"}
 * Unauthorized → 401  {"error":"Not signed in or invalid credentials"}
 * Forbidden    → 403  {"error":"You don't have permission to do that"}
 * Conflict     → 409  {"error"}
 * CodedConflict→ 409  {"error", "code"}
 * Db / Other   → 500  {"error":"Internal server error"}   // 细节只进日志
 * </pre>
 * 另承接两个 HTTP 层对等：未配路由 404（axum api/mod.rs:622 fallback）、
 * 请求体反序列化失败 422（axum Json 拒绝的原文文案，auth 契约剧本逐步比对）。
 */
@RestControllerAdvice
public class AppExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AppExceptionHandler.class);

    private final ObjectMapper mapper;

    public AppExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(AppException.class)
    ResponseEntity<String> appError(AppException e) {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", e.getMessage());
        switch (e.kind()) {
            case INVALID -> {
                body.put("code", e.code().orElseThrow());
                e.detail().ifPresent(d -> body.put("detail", d));
                return respond(HttpStatus.UNPROCESSABLE_ENTITY, body);
            }
            case VALIDATION -> {
                return respond(HttpStatus.UNPROCESSABLE_ENTITY, body);
            }
            case NOT_FOUND -> {
                return respond(HttpStatus.NOT_FOUND, body);
            }
            case UNAUTHORIZED -> {
                return respond(HttpStatus.UNAUTHORIZED, body);
            }
            case FORBIDDEN -> {
                return respond(HttpStatus.FORBIDDEN, body);
            }
            case CONFLICT -> {
                return respond(HttpStatus.CONFLICT, body);
            }
            case CODED_CONFLICT -> {
                body.put("code", e.code().orElseThrow());
                return respond(HttpStatus.CONFLICT, body);
            }
            default -> {
                // Db/Other：细节只进日志（error.rs:49-60），响应体恒定一句
                log.error("internal error: kind={} message={}", e.kind(), e.getMessage(), e);
                body.put("error", "Internal server error");
                return respond(HttpStatus.INTERNAL_SERVER_ERROR, body);
            }
        }
    }

    /** 未配路由（对齐 axum api.fallback：404 {"error":"Not found"}，api/mod.rs:622）。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<String> noRoute(NoHandlerFoundException e) {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", "Not found");
        return respond(HttpStatus.NOT_FOUND, body);
    }

    /** 已配路径但方法不符：axum 405 空 body（无 JSON 包装），原样对齐。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<String> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * 缺必填 query 参数（P1 终审 I3，对齐 axum Query 提取器拒绝）：400 文本
     * "Failed to deserialize query string: missing field `x`"（mappings/relevant 缺 q
     * 的源金样本逐字；chunk_id/entity 面同形）。此前 MVC 缺参冒泡 500，契约回归。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<String> missingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Failed to deserialize query string: missing field `" + e.getParameterName() + "`");
    }

    /**
     * 请求体反序列化失败（对齐 axum Json 提取器拒绝）：422 文本
     * "Failed to deserialize the JSON body into the target type: …"。
     * 缺字段时 axum/serde 带行列定位（"missing field `display_name` at line 1 column 71"），
     * 契约剧本 register-missing-display-name 比对该原文——Jackson 的定位换算成同一格式。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<String> unreadable(HttpMessageNotReadableException e) {
        String detail;
        if (e.getCause() instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mismatch) {
            // 缺必填字段：Jackson 报 "Missing required creator property 'display_name' (index N)"，
            // serde 等价文案是 "missing field `display_name` at line 1 column <对象收尾列>"；
            // 字段名从消息取（getPropertyName 对缺 creator 属性不可靠），行列取 Jackson 定位
            // （指向对象收尾 token，与 serde 报点一致——契约剧本逐步比对，双打时实测校准）。
            java.util.regex.Matcher name = java.util.regex.Pattern
                    .compile("property '([^']+)'").matcher(mismatch.getOriginalMessage());
            if (name.find()) {
                detail = "missing field `" + name.group(1) + "` at line "
                        + mismatch.getLocation().getLineNr()
                        + " column " + mismatch.getLocation().getColumnNr();
            } else {
                detail = mismatch.getOriginalMessage();
            }
        } else if (e.getCause() instanceof com.fasterxml.jackson.core.JsonParseException parse) {
            detail = parse.getOriginalMessage();
        } else {
            detail = e.getCause() != null ? String.valueOf(e.getCause().getMessage()) : e.getMessage();
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("Failed to deserialize the JSON body into the target type: " + detail);
    }

    /** 兜底：任何未映射异常 → 500 内部错误形状（不出细节）。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpected(Exception e) {
        log.error("unhandled exception", e);
        ObjectNode body = mapper.createObjectNode();
        body.put("error", "Internal server error");
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, body);
    }

    /**
     * RBAC 拒绝（P2 任务 4：业务 controller 挂 @PreAuthorize/@ss.hasPermi 后，
     * AccessDeniedException 从方法拦截器抛进 handler 链）。必须显式接住——本类的
     * Exception 兜底在本工程优先于若依 GlobalExceptionHandler 的同名处理，不接就会
     * 把「权限不够」误报成 500 内部错误。形状对齐源 Forbidden 契约（403 + 上表文案）。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<String> accessDenied(org.springframework.security.access.AccessDeniedException e) {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", "You don't have permission to do that");
        return respond(HttpStatus.FORBIDDEN, body);
    }

    private ResponseEntity<String> respond(HttpStatus status, ObjectNode body) {
        return ResponseEntity.status(status)
                .header("Content-Type", "application/json")
                .body(body.toString());
    }
}
