package io.github.yueyinxin.shortlink.auth.jwt;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从请求头解析访问令牌并建立认证上下文。
 *
 * <h2>关键设计：令牌无效时不直接返回 401</h2>
 * 本过滤器在令牌无效时只是"不建立认证上下文"，然后<b>继续执行过滤器链</b>，
 * 而不是立即写出 401 响应。原因：
 *
 * <p>本服务同时存在公开接口（短链跳转 {@code GET /{code}}）与受保护接口
 * （{@code /api/v1/**}）。浏览器在跳转请求上可能携带过期的 Cookie 或旧令牌，
 * 如果过滤器见到无效令牌就返回 401，<b>公开接口会因为一个无关的过期令牌而拒绝服务</b> ——
 * 用户的短链将无法跳转，而这与"令牌过期"毫无关系。
 *
 * <p>把"令牌是否有效"与"该接口是否需要令牌"交给 Spring Security 的授权规则分别处理，
 * 是这两个问题正确的解耦方式：过滤器只负责"如果能认证就认证"，授权规则负责"没认证是否放行"。
 *
 * <h2>关于令牌来源</h2>
 * 只支持 {@code Authorization: Bearer <token>} 头，不支持从查询参数读取。
 * 查询参数会出现在访问日志、浏览器历史、Referer 头中，把令牌放进查询参数
 * 等于把它泄露到多个地方。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = HttpHeaders.AUTHORIZATION;

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        // 已有认证上下文时不重复认证：可能是更早的过滤器（如测试中注入的）
        // 已经完成认证，覆盖它会丢失权限信息
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token, request);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            AuthenticatedUser user = tokenProvider.parseAccessToken(token);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, List.of());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("令牌认证成功: userId={}", user.userId());

        } catch (JwtException | IllegalArgumentException e) {
            // 不记录令牌内容：日志会长期保存，令牌是可用于冒充身份的凭证。
            // 只记录失败原因的分类，足够排查问题。
            SecurityContextHolder.clearContext();
            log.debug("令牌解析失败，按未认证处理: {}", e.getClass().getSimpleName());
        }
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
