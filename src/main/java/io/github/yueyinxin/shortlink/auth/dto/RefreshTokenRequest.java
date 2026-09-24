package io.github.yueyinxin.shortlink.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求。
 *
 * <p>刷新令牌放在请求体中而不是 {@code Authorization} 头：头里的令牌会被
 * 中间代理、网关、错误日志更频繁地记录。放在请求体里，泄露面更小。
 */
public record RefreshTokenRequest(

        @NotBlank(message = "刷新令牌不能为空")
        String refreshToken
) {
}
