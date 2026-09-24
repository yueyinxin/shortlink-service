package io.github.yueyinxin.shortlink.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * @param usernameOrEmail 用户名或邮箱
 * @param password        明文密码，由服务端与存储的哈希比对。<b>服务端绝不记录或持久化它。</b>
 */
public record LoginRequest(

        @NotBlank(message = "用户名或邮箱不能为空")
        String usernameOrEmail,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
