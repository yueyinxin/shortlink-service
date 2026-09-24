package io.github.yueyinxin.shortlink.auth.jwt;

import io.github.yueyinxin.shortlink.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 的签发与解析。
 *
 * <p>只负责令牌本身的技术细节（签名、过期、声明结构），不涉及业务判断 ——
 * 那是 {@code AuthService} 的职责。这个划分让令牌逻辑可以被独立测试，
 * 也让"换用非 JWT 的令牌方案"时不需要改动业务流程。
 *
 * <h2>安全要点</h2>
 * <ul>
 *   <li><b>算法固定为 HS256。</b> 解析时显式指定密钥，不接受令牌头中的
 *       {@code alg} 声明来决定验证方式。这是防御 {@code alg: none} 与
 *       RS256→HS256 混淆攻击的关键 —— 那类攻击的原理就是让服务端用非预期的方式验签。</li>
 *   <li><b>校验签发者。</b> 使用 {@code requireIssuer}。若同一套密钥被多个服务共用
 *       （应避免，但现实中常见），不校验签发者会接受其他服务签发的令牌。</li>
 *   <li><b>不吞掉异常。</b> 解析失败一律抛出 {@link JwtException}，由调用方决定
 *       如何处理（返回 401）。在这里返回 null 会让调用方有机会忘记判断。</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "username";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        // 密钥长度已在 JwtProperties 的构造阶段校验（至少 256 位），
        // 因此这里不会因密钥过短而抛 WeakKeyException
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发访问令牌。
     *
     * @param userId   用户主键，作为 {@code sub} 声明
     * @param username 用户名，作为自定义声明
     */
    public String createAccessToken(Long userId, String username) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .issuer(properties.issuer())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                // jti 让每个令牌可被唯一标识。当前版本未使用它做撤销
                // （撤销能力由 refresh token 承担），但保留它意味着未来要引入
                // 令牌黑名单时无需改变令牌结构 —— 而改变结构需要所有客户端重新登录。
                .id(UUID.randomUUID().toString())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并验证访问令牌。
     *
     * @return 令牌中的身份信息
     * @throws JwtException 签名无效、已过期、签发者不匹配或结构损坏
     *                      —— 这些情况在调用方看来都是"令牌不可用"，无需区分，
     *                      区分反而会给攻击者提供信息
     */
    public AuthenticatedUser parseAccessToken(String token) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = parseUserId(claims.getSubject());
        String username = claims.get(CLAIM_USERNAME, String.class);
        return new AuthenticatedUser(userId, username);
    }

    /** 访问令牌有效期，用于在登录响应中告知客户端何时需要刷新。 */
    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    /** 刷新令牌有效期。 */
    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    private static Long parseUserId(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            // sub 不是合法数字说明令牌被篡改或由不兼容的实现签发。
            // 转换成本项目的异常类型，让调用方只处理一种异常。
            throw new JwtException("令牌的 sub 声明不是合法的用户 ID");
        }
    }
}
