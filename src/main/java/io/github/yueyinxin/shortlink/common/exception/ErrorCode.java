package io.github.yueyinxin.shortlink.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 全站错误码。
 *
 * <p>每个错误码同时携带 HTTP 状态码与面向用户的文案。集中定义而非散落在各处的好处是：
 * 可以一眼看出全部对外错误面，避免同一个语义在不同接口返回不同的码或文案。
 *
 * <p>文案是<b>面向使用者</b>的，不含任何内部实现细节。堆栈与内部信息只进日志，不进响应体。
 */
public enum ErrorCode {

    // ---- 400 ----
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
    CODE_RESERVED(HttpStatus.BAD_REQUEST, "该短码为系统保留字，请更换"),
    INVALID_URL(HttpStatus.BAD_REQUEST, "链接格式不合法，仅支持 http 与 https 协议"),

    // ---- 401 ----
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未提供访问凭证"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "访问令牌无效或已过期"),

    // ---- 403 ----
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该资源"),
    LINK_DISABLED(HttpStatus.FORBIDDEN, "该短链已被禁用"),

    // ---- 404 ----
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),

    // ---- 405 / 415 ----
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求内容类型"),

    // ---- 409 ----
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "邮箱已被占用"),
    CODE_TAKEN(HttpStatus.CONFLICT, "该短码已被占用"),

    // ---- 410 ----
    LINK_EXPIRED(HttpStatus.GONE, "该短链已过期"),

    // ---- 429 ----
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),

    // ---- 500 ----
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请稍后重试");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
