package tech.seexun.jknow.common;

import java.util.Optional;

/**
 * 应用错误（对等旧仓库 utopia-core AppError，error.rs:6-40）：携带稳定 HTTP 语义的异常基类。
 * 变体与固定文案逐字对齐——{@link #NOT_FOUND}/{@link #UNAUTHORIZED}/{@link #FORBIDDEN} 的
 * message 就是客户端看到的 {@code error} 字段原文（error.rs thiserror #[error] 文案），
 * 不许改字：契约剧本比对的就是这些字符串。HTTP 状态映射在 server 侧
 * AppExceptionHandler（error.rs:18-66 全表）。
 */
public final class AppException extends RuntimeException {

    /** 错误变体（对齐 AppError 枚举项；Db/Other 合并为 INTERNAL——细节只进日志）。 */
    public enum Kind {
        /** 422：带稳定 code 的校验错误（message 永久英文，界面拿 code 做 i18n）。 */
        INVALID,
        /** 422：无 code 的校验错误。 */
        VALIDATION,
        /** 404 "Not found"。 */
        NOT_FOUND,
        /** 401 "Not signed in or invalid credentials"。 */
        UNAUTHORIZED,
        /** 403 "You don't have permission to do that"。 */
        FORBIDDEN,
        /** 409：无 code 冲突。 */
        CONFLICT,
        /** 409：带稳定 code 的冲突（对齐 CodedConflict）。 */
        CODED_CONFLICT,
        /** 500 "Internal server error"（Db/Other，细节不出响应体）。 */
        INTERNAL
    }

    public static final AppException NOT_FOUND = new AppException(Kind.NOT_FOUND, "Not found", null, null);
    public static final AppException UNAUTHORIZED =
            new AppException(Kind.UNAUTHORIZED, "Not signed in or invalid credentials", null, null);
    public static final AppException FORBIDDEN =
            new AppException(Kind.FORBIDDEN, "You don't have permission to do that", null, null);

    private final Kind kind;
    /** 稳定错误码（INVALID/CODED_CONFLICT 才有），进响应体 {@code code} 字段。 */
    private final String code;
    /** 机器补充（INVALID 可选），进响应体 {@code detail} 字段。 */
    private final String detail;

    private AppException(Kind kind, String message, String code, String detail) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.detail = detail;
    }

    /** 带稳定 code 的校验错误（对齐 AppError::invalid）。 */
    public static AppException invalid(String code, String message) {
        return new AppException(Kind.INVALID, message, code, null);
    }

    /** 带 code + detail 的校验错误（对齐 AppError::invalid_detail）。 */
    public static AppException invalidDetail(String code, String message, String detail) {
        return new AppException(Kind.INVALID, message, code, detail);
    }

    /** 无 code 校验错误（对齐 AppError::Validation）。 */
    public static AppException validation(String message) {
        return new AppException(Kind.VALIDATION, message, null, null);
    }

    /** 无 code 冲突（对齐 AppError::Conflict）。 */
    public static AppException conflict(String message) {
        return new AppException(Kind.CONFLICT, message, null, null);
    }

    /** 带稳定 code 的冲突（对齐 AppError::CodedConflict）。 */
    public static AppException codedConflict(String code, String message) {
        return new AppException(Kind.CODED_CONFLICT, message, code, null);
    }

    /** 数据库错误（对齐 AppError::Db；message 只进日志，响应体恒 "Internal server error"）。 */
    public static AppException db(String message, Throwable cause) {
        AppException e = new AppException(Kind.INTERNAL, "Internal server error", null, null);
        e.initCause(cause);
        return e;
    }

    /** 其他内部错误（对齐 AppError::Other）。 */
    public static AppException other(String message, Throwable cause) {
        AppException e = new AppException(Kind.INTERNAL, "Internal server error", null, null);
        e.initCause(cause);
        return e;
    }

    public Kind kind() {
        return kind;
    }

    public Optional<String> code() {
        return Optional.ofNullable(code);
    }

    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }
}
