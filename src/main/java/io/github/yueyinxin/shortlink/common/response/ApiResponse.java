package io.github.yueyinxin.shortlink.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.common.exception.FieldViolation;

import java.util.List;

/**
 * 统一响应包装。
 *
 * <p>成功时：{@code {"success": true, "data": {...}}}
 * <br>失败时：{@code {"success": false, "error": {"code": "...", "message": "...", "traceId": "..."}}}
 *
 * <p>两种形态共用一个信封，客户端只需判断 {@code success} 字段即可决定解析方向，
 * 不需要根据 HTTP 状态码分别处理。
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让成功响应中不出现 {@code error} 字段、
 * 失败响应中不出现 {@code data} 字段 —— 避免客户端对"data 为 null 是成功还是失败"产生歧义。
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error
) {

    /**
     * 错误明细。
     *
     * @param code    错误码，供客户端做程序化判断
     * @param message 面向用户的文案，可直接展示
     * @param details 字段级明细，仅参数校验失败时存在
     * @param traceId 追踪 ID，用于把用户反馈与后端日志关联起来
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            List<FieldViolation> details,
            String traceId
    ) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 用于无响应体的成功场景（如 204）。 */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> error(ErrorCode code, String message,
                                          List<FieldViolation> details, String traceId) {
        return new ApiResponse<>(false, null, new ErrorDetail(code.name(), message, details, traceId));
    }
}
