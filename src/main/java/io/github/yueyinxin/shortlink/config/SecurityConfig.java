package io.github.yueyinxin.shortlink.config;

import io.github.yueyinxin.shortlink.auth.jwt.JwtAuthenticationFilter;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import io.github.yueyinxin.shortlink.security.RateLimitFilter;
import io.github.yueyinxin.shortlink.common.response.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 *
 * <h2>授权规则顺序</h2>
 * Spring Security 的规则<b>按声明顺序匹配，首个匹配的规则生效</b>。因此顺序不是风格问题，
 * 而是正确性问题。本配置中必须把 {@code /api/**} 的认证要求放在
 * <b>"放行所有 GET"之前</b> —— 后者是为了短链跳转（{@code GET /{code}}）而设的。
 * 如果两者顺序颠倒，"放行所有 GET" 会先把 {@code GET /api/v1/links} 匹配掉，
 * <b>整个管理接口将变成完全公开</b>，任何人不登录就能读写所有人的短链。
 *
 * <p>同理，Actuator 的认证要求也必须早于 GET 放行。
 *
 * <h2>关于 CSRF</h2>
 * 关闭 CSRF 保护。CSRF 攻击成立的前提是浏览器会<b>自动</b>携带凭证（Cookie / Basic 认证）。
 * 本服务使用 {@code Authorization: Bearer} 头传递令牌，浏览器不会自动附加它 ——
 * 攻击者诱导用户点击链接时，请求里不会有令牌，因此 CSRF 在本架构下不成立。
 *
 * <p>反过来说，如果将来改为用 Cookie 承载令牌，<b>必须重新启用 CSRF 保护</b>，
 * 并配合 {@code SameSite} 属性使用。
 *
 * <h2>关于会话</h2>
 * 显式声明 {@code STATELESS}：不创建 HttpSession，也不用它保存安全上下文。
 * 这既是无状态鉴权的正确配置，也能避免"会话隐式创建"带来的内存占用 ——
 * 在只提供 API 的服务里，HttpSession 没有任何用途。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** BCrypt 强度。10 是安全性与登录延迟（约 50-100ms）的常用平衡点。 */
    private static final int BCRYPT_STRENGTH = 10;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ApiErrorWriter errorWriter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          ApiErrorWriter errorWriter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.errorWriter = errorWriter;
    }

    /**
     * 密码编码器。
     *
     * <p>选择 BCrypt 而非 SHA-256 等快速哈希：BCrypt 是<b>刻意慢</b>的，
     * 且内置随机盐。快速哈希（如 MD5/SHA）可以在 GPU 上以每秒数十亿次的速度尝试，
     * 拖库后弱密码会被瞬间破解。BCrypt 的单次成本让这类暴力破解在经济上不可行。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())   // 登出由 /api/v1/auth/logout 显式实现

                // -------- 规则顺序即正确性，不要重排 --------
                .authorizeHttpRequests(auth -> auth

                        // 1. 健康检查：容器编排的存活/就绪探针需要匿名访问。
                        //    只放行 health，不放行 health/**，避免 health 下的详细组件信息泄露。
                        .requestMatchers("/actuator/health").permitAll()

                        // 2. 认证入口本身必须匿名可访问
                        //    logout 也在此列：它的请求体里携带的 refresh token 就是要被
                        //    作废的凭证，本身已具备证明身份的能力。如果额外要求一个未过期的
                        //    access token，那么 access token 一旦过期，用户就无法登出，
                        //    其 refresh token 会继续有效最长 14 天 —— 这比"匿名可登出"更危险。
                        .requestMatchers("/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()

                        // 3. 接口文档（生产环境应通过配置关闭 springdoc，见 application-prod.yml）
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // 4. ⚠️ 管理接口需要认证 —— 必须排在下面的「放行所有 GET」之前，
                        //    否则会被后者抢先匹配，整个管理接口变成公开的
                        .requestMatchers("/api/**").authenticated()

                        // 5. ⚠️ 其余 Actuator 端点需要认证 —— 同样必须在 GET 放行之前。
                        //    /actuator/env、/actuator/beans、/actuator/heapdump 足以
                        //    泄露配置、内部结构与内存中的凭证。
                        .requestMatchers("/actuator/**").authenticated()

                        // 6. 短链跳转：匿名 GET，这是服务的主要流量入口。
                        //    放在最后，只兜住前面未匹配到的路径。
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()

                        // 7. 兜底：任何其他请求都需要认证（默认拒绝，而非默认放行）
                        .anyRequest().authenticated()
                )

                .exceptionHandling(exceptions -> exceptions
                        // 未认证：返回统一信封格式的 401，而不是 Spring 默认的
                        // 空响应体 + WWW-Authenticate 头。客户端只需处理一种响应格式。
                        .authenticationEntryPoint((request, response, authException) ->
                                errorWriter.write(response, ErrorCode.UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED.defaultMessage()))
                        // 已认证但无权限
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                errorWriter.write(response, ErrorCode.FORBIDDEN,
                                        ErrorCode.FORBIDDEN.defaultMessage()))
                )

                // -------- 自定义过滤器 --------
                // 令牌解析放在标准用户名密码过滤器之前：本服务没有表单登录，
                // 让令牌过滤器先建立认证上下文，后续的授权判断才能看到身份。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 限流放在令牌解析之后：创建接口的限流按用户维度计数，
                // 需要先有认证上下文才能拿到 userId。跳转接口按 IP 计数，不依赖认证。
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
