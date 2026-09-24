package io.github.yueyinxin.shortlink.common.exception;

import io.github.yueyinxin.shortlink.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.List;

/**
 * 全局异常处理。
 *
 * <p>目标：<b>把任意异常转换为统一格式的响应，且绝不把内部实现细节泄露给客户端。</b>
 *
 * <h2>两个关键的分级原则</h2>
 *
 * <p><b>其一：4xx 不打堆栈，5xx 必须打。</b>
 * 用户输错密码、短码不存在这类情况是正常的业务分支，每天会发生无数次。
 * 如果它们都打完整堆栈，日志会被彻底淹没，真正的故障反而看不见。
 * 反之，5xx 意味着代码有缺陷或依赖故障，必须带堆栈以便定位。
 *
 * <p><b>其二：对外文案与对内日志分离。</b>
 * 500 响应只返回固定的通用文案，绝不带 {@code exception.getMessage()}。
 * 数据库连接串、SQL 片段、类名、文件路径经常出现在异常消息里，
 * 把它们回显给客户端是最常见的信息泄露途径之一。
 *
 * <p><b>注意：</b>本类<b>无法</b>拦截 Servlet Filter 中抛出的异常（Filter 在
 * DispatcherServlet 之前执行）。限流与鉴权失败由 {@code ApiErrorWriter} 直接写出响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_KEY = "traceId";

    // ------------------------------------------------------------------ 业务异常

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.errorCode();

        if (code.status().is5xxServerError()) {
            log.error("业务异常（服务端错误）: code={}, message={}", code, ex.getMessage(), ex);
        } else {
            log.debug("业务异常: code={}, message={}", code, ex.getMessage());
        }

        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, ex.getMessage(), ex.details(), traceId()));
    }

    // ------------------------------------------------------------------ 参数校验

    /**
     * {@code @RequestBody} 上的 {@code @Valid} 校验失败。
     *
     * <p>返回字段级明细而不是笼统的"参数错误"，让调用方能一次性知道所有不合法的字段，
     * 而不是"改一个、报一个"地反复试错。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                // 排序保证响应稳定：字段错误的返回顺序在 JDK 层面是不确定的，
                // 不稳定会让基于响应内容的测试出现偶发失败
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();

        log.debug("请求体校验失败: {}", details);
        return badRequest(details);
    }

    /** 方法参数（{@code @RequestParam} / {@code @PathVariable}）上的约束校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> details = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        lastPathSegment(violation.getPropertyPath().toString()),
                        violation.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();

        log.debug("参数约束校验失败: {}", details);
        return badRequest(details);
    }

    /** 请求体不是合法 JSON，或类型无法转换。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // 不回显 ex.getMessage()：它会包含期望的类型与触发的解析器，
        // 相当于把服务端的对象结构告诉调用方
        log.debug("请求体无法解析: {}", ex.getMessage());
        return badRequest(List.of(new FieldViolation("body", "请求体不是合法的 JSON 或字段类型不匹配")));
    }

    /** 缺少必填的查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.debug("缺少请求参数: {}", ex.getParameterName());
        return badRequest(List.of(new FieldViolation(ex.getParameterName(), "该参数为必填")));
    }

    /** 参数类型不匹配，例如把 {@code page=abc} 传给 {@code int}。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "期望的类型";
        log.debug("参数类型不匹配: name={}, value={}", ex.getName(), ex.getValue());
        return badRequest(List.of(new FieldViolation(ex.getName(), "参数类型不正确，应为 " + expected)));
    }

    // ------------------------------------------------------------------ HTTP 协议层

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("请求方法不支持: {}", ex.getMethod());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED,
                        ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), null, traceId()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("内容类型不支持: {}", ex.getContentType());
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status())
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(), null, traceId()));
    }

    /** 静态资源或未匹配的路径。让 404 也走统一信封，客户端无需为它特殊处理。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.debug("路径未匹配: {}", ex.getResourcePath());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND,
                        ErrorCode.NOT_FOUND.defaultMessage(), null, traceId()));
    }

    // ------------------------------------------------------------------ 安全

    /**
     * 认证失败。
     *
     * <p>正常情况下鉴权在 Spring Security 的过滤器链中完成，不会走到这里。
     * 保留这个处理器是为了覆盖在方法级安全注解（{@code @PreAuthorize}）等
     * 在 MVC 层内触发鉴权的场景。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.debug("认证失败: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.status())
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED,
                        ErrorCode.UNAUTHORIZED.defaultMessage(), null, traceId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.debug("权限不足: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ApiResponse.error(ErrorCode.FORBIDDEN,
                        ErrorCode.FORBIDDEN.defaultMessage(), null, traceId()));
    }

    // ------------------------------------------------------------------ 兜底

    /**
     * 未预期的异常。
     *
     * <p>这是最后一道防线：任何没有被前面处理器覆盖的异常都会落到这里。
     * 对外只返回通用文案，<b>绝不回显 {@code ex.getMessage()}</b>；
     * 对内记录完整堆栈。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未预期的异常，这应当被修复", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.defaultMessage(), null, traceId()));
    }

    // ------------------------------------------------------------------ 内部工具

    private static ResponseEntity<ApiResponse<Void>> badRequest(List<FieldViolation> details) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.defaultMessage(), details, traceId()));
    }

    private static String traceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 提取属性路径的最后一段。
     *
     * <p>约束校验的路径形如 {@code create.page} 或 {@code list.size}，
     * 带上了方法名。客户端只关心字段名，因此截取最后一段。
     */
    private static String lastPathSegment(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 && lastDot < propertyPath.length() - 1
                ? propertyPath.substring(lastDot + 1)
                : propertyPath;
    }
}
