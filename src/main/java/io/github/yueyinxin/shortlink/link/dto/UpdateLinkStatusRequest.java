package io.github.yueyinxin.shortlink.link.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 修改短链状态请求。
 *
 * @param enabled {@code true} 启用，{@code false} 禁用。
 *                <p>用 {@code Boolean} 而不是 {@code boolean}：基本类型无法区分
 *                "传了 false" 与 "没传"，字段缺失时会被静默当作 {@code false}，
 *                导致一个漏传参数的请求<b>静默地禁用</b>了用户的短链。
 *                用包装类型配合 {@code @NotNull}，缺失参数会返回 400。
 */
public record UpdateLinkStatusRequest(

        @NotNull(message = "enabled 不能为空")
        Boolean enabled
) {
}
