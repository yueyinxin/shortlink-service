package io.github.yueyinxin.shortlink.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <p>校验规则全部写在字段上，由 Jakarta Bean Validation 统一执行，控制器里不写
 * {@code if (username == null || username.length() < 3) ...} 这类手动检查。
 * 理由是这样能保证<b>校验规则只有一处定义</b>：手动检查会在控制器之间逐渐产生差异，
 * 最终同一个字段在不同接口上接受不同的取值范围。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Pattern(
                regexp = "^[a-zA-Z0-9_-]{3,32}$",
                message = "用户名只能包含字母、数字、下划线和短横线，长度 3-32 位"
        )
        String username,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128 位")
        String email,

        /*
         * 密码长度上限设为 64 个字符。
         *
         * 上限不是为了"限制用户",而是两个安全考虑的结果:
         *
         * 1) BCrypt 只使用密码的前 72 字节。若不设上限,用户以为设了 100 位强密码,
         *    实际只有前 72 字节参与计算,而系统不会给出任何提示 ——
         *    这种"看起来更安全实际不然"的落差比直接限制更危险。
         *
         * 2) BCrypt 的计算时间随密码长度增长。不设上限时,攻击者可以用超长密码
         *    让服务端持续做昂贵的哈希运算,构成拒绝服务向量。
         *
         * 注意 64 个"字符"在 UTF-8 下最多可能是 256 字节,因此字节级的上限校验
         * 在 AuthService 中另行执行(见 AuthService#validatePasswordByteLength)。
         */
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8 到 64 位之间")
        String password
) {
}
