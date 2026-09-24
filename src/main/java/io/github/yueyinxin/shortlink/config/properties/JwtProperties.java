package io.github.yueyinxin.shortlink.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 配置。
 *
 * <p>关于双令牌的设计权衡见 {@code docs/adr/0005-authentication.md}。
 *
 * @param secret          HMAC-SHA256 签名密钥。<b>必须通过环境变量注入，不得写入配置文件。</b>
 *                        长度至少 32 字节（256 位），否则 HS256 的实际安全强度低于标称值。
 *                        <p>更换密钥会使所有已签发的 access token 立即失效（用户需重新登录），
 *                        这是密钥泄露时的应急手段，也是密钥轮换的固有代价。
 * @param accessTokenTtl  访问令牌有效期。这个值直接决定了"令牌泄露后的最大损失窗口"——
 *                        取 30 分钟是一个显式的权衡：更短更安全，但客户端刷新更频繁。
 * @param refreshTokenTtl 刷新令牌有效期。仅在 Redis 中存储，一次性使用。
 * @param issuer          签发者标识，写入 JWT 的 {@code iss} 声明
 */
@Validated
@ConfigurationProperties(prefix = "shortlink.jwt")
public record JwtProperties(

        @NotBlank
        String secret,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl,

        @NotBlank
        String issuer
) {

    /** HS256 要求密钥至少 256 位，与 JWA 规范一致。 */
    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        // 提前在构造阶段拦住弱密钥，给出比启动后验签失败更明确的错误信息。
        // secret 为 null 的情况交给 @NotBlank 处理，这里只校验长度。
        if (secret != null) {
            int actual = secret.getBytes(StandardCharsets.UTF_8).length;
            if (actual < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "JWT 密钥至少需要 %d 字节（当前 %d 字节），否则 HS256 安全强度不足"
                                .formatted(MIN_SECRET_BYTES, actual));
            }
        }
    }
}
