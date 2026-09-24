package io.github.yueyinxin.shortlink.common.exception;

import java.util.List;

/**
 * 业务异常。
 *
 * <p>所有可预期的业务失败都应抛出本异常，由 {@link GlobalExceptionHandler} 统一转换为响应。
 * 业务代码中<b>不应</b>直接构造 HTTP 响应或自行捕获后返回错误字符串 —— 那会让错误格式
 * 散落到各处，且容易在某个分支上遗漏而泄露内部信息。
 *
 * <p>本异常携带堆栈，但 {@link GlobalExceptionHandler} 对 4xx 级别的业务异常<b>不打印堆栈</b>：
 * 这是正常的业务流程（如用户输错密码），记录堆栈会淹没真正需要关注的错误日志。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<FieldViolation> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldViolation> details) {
        // 关闭 suppression 与 writableStackTrace：业务异常是流程分支而非故障，
        // 不需要堆栈。构造更快，日志也更干净。
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.details = details;
    }

    public static BusinessException of(ErrorCode errorCode, List<FieldViolation> details) {
        return new BusinessException(errorCode, errorCode.defaultMessage(), details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> details() {
        return details;
    }
}
