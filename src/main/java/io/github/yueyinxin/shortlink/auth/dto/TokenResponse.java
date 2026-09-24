package io.github.yueyinxin.shortlink.auth.dto;

/**
 * 令牌响应。
 *
 * @param accessToken  访问令牌（JWT），用于后续请求的 {@code Authorization: Bearer} 头
 * @param refreshToken 刷新令牌，一次性使用，用于在访问令牌过期后换取新的令牌对
 * @param tokenType    令牌类型，固定为 {@code Bearer}。显式返回它是 OAuth2 的惯例，
 *                     让客户端不需要硬编码字符串
 * @param expiresIn    访问令牌的有效期（秒）。客户端据此决定何时刷新 ——
 *                     有经验的实现会在剩余有效期低于阈值时提前刷新，
 *                     而不是等到请求返回 401 才补救，后者会让用户感知到一次失败
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {

    private static final String BEARER = "Bearer";

    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, refreshToken, BEARER, expiresInSeconds);
    }
}
