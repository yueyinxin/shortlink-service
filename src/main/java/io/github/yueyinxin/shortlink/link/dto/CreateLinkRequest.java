package io.github.yueyinxin.shortlink.link.dto;

import io.github.yueyinxin.shortlink.common.validation.HttpUrl;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建短链请求。
 *
 * @param originalUrl 目标地址。使用 {@link HttpUrl} 而非普通的格式校验，
 *                    因为协议白名单是<b>安全约束</b>：放行 {@code javascript:}
 *                    会让本服务成为 XSS 跳板（详见该注解的说明）。
 * @param customCode  自定义短码，可选。为 {@code null} 或空时由系统自动生成。
 *                    格式与自动生成的短码一致（4-10 位字母数字），
 *                    是否与保留字冲突由服务层判断并返回专门的错误码。
 * @param expiresAt   过期时间，可选。为 {@code null} 表示永不过期。
 *                    {@code @Future} 保证不能创建"一出生就已过期"的短链。
 */
public record CreateLinkRequest(

        @NotBlank(message = "目标链接不能为空")
        @HttpUrl
        String originalUrl,

        @Size(min = 4, max = 10, message = "自定义短码长度需在 4 到 10 位之间")
        @Pattern(regexp = "^[0-9A-Za-z]+$", message = "自定义短码只能包含字母和数字")
        String customCode,

        @Future(message = "过期时间必须晚于当前时间")
        LocalDateTime expiresAt
) {

    /**
     * 判断是否指定了自定义短码。
     *
     * <p>把"空字符串等同于未指定"的判断收在这里，避免服务层各处重复
     * {@code customCode != null && !customCode.isBlank()}。
     * 前端把可选字段传成 {@code ""} 而不是省略，是非常常见的做法。
     */
    public boolean hasCustomCode() {
        return customCode != null && !customCode.isBlank();
    }
}
