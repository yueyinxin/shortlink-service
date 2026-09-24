package io.github.yueyinxin.shortlink.link.dto;

import io.github.yueyinxin.shortlink.link.ShortLink;

import java.time.LocalDateTime;

/**
 * 短链信息响应。
 *
 * @param id          主键
 * @param code        短码
 * @param shortUrl    完整短链，由配置的 {@code shortlink.base-url} 与短码拼接而成
 * @param originalUrl 目标地址
 * @param status      状态：{@code ACTIVE} / {@code DISABLED}
 * @param expiresAt   过期时间，{@code null} 表示永不过期
 * @param createdAt   创建时间
 */
public record LinkResponse(
        Long id,
        String code,
        String shortUrl,
        String originalUrl,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {

    /**
     * 由实体构建响应。
     *
     * @param baseUrl 对外基础地址。通过参数传入而不是从配置直接读取，
     *                使本 DTO 不依赖 Spring 容器，可以在任何地方（含测试）构造。
     *                <p>拼接时去掉 {@code baseUrl} 末尾多余的 {@code /}，
     *                避免出现 {@code http://host//abc123} 这种双斜杠 ——
     *                它虽然通常仍能访问，但在部分网关与防盗链校验下会被判为不同路径。
     */
    public static LinkResponse from(ShortLink link, String baseUrl) {
        return new LinkResponse(
                link.getId(),
                link.getCode(),
                joinUrl(baseUrl, link.getCode()),
                link.getOriginalUrl(),
                link.getStatus().name(),
                link.getExpiresAt(),
                link.getCreatedAt()
        );
    }

    private static String joinUrl(String baseUrl, String code) {
        if (baseUrl.endsWith("/")) {
            return baseUrl + code;
        }
        return baseUrl + "/" + code;
    }
}
