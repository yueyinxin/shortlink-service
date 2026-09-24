package io.github.yueyinxin.shortlink.user.dto;

import io.github.yueyinxin.shortlink.user.User;

import java.time.LocalDateTime;

/**
 * 用户信息响应。
 *
 * <p><b>与实体分离的理由</b>：实体的字段会因为内部需要而增加，但如果直接把实体序列化返回，
 * 每个新字段都会自动暴露给客户端。这类泄露通常悄无声息 —— 某天有人给实体加了一个
 * {@code passwordHash} 或 {@code internalNote} 字段，接口就开始返回它了。
 *
 * <p>显式定义一个响应 DTO，意味着"对外暴露哪些字段"是一个必须被写下来的决定。
 * 本 DTO 中<b>刻意不包含</b> {@code passwordHash}。
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        String status,
        LocalDateTime createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getStatus().name(),
                user.getCreatedAt()
        );
    }
}
